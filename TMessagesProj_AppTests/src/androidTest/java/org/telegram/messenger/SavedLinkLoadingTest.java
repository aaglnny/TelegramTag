package org.telegram.messenger;

import android.os.Looper;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkLoadingTest {
    static final long USER = SavedLinkReferenceTest.USER_A;
    static final long C1 = SavedLinkReferenceTest.CHANNEL_A;
    static final long C2 = SavedLinkReferenceTest.CHANNEL_B;
    static final long C3 = C2 + 1;

    static class Request {
        int id;
        TLObject object;
        RequestDelegate callback;
    }

    static class Source extends SavedLinkPreviewController.Backend {
        final ArrayList<Request> requests = new ArrayList<>();
        final LinkedHashMap<Integer, Request> pending = new LinkedHashMap<>();
        final HashMap<String, TLRPC.Message> cache = new HashMap<>();
        final HashMap<String, TLRPC.Message> remote = new HashMap<>();
        final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
        final HashSet<String> missing = new HashSet<>();
        boolean auto = true;
        boolean realCache;
        boolean readFailure;
        boolean writeFailure;
        int reads;
        int writes;
        int cancelled;
        int maxConcurrent;
        long time = 1800000000000L;

        Source() {
            super(0);
        }

        @Override
        int send(TLObject object, RequestDelegate callback) {
            Request request = new Request();
            request.id = requests.size() + 1;
            request.object = object;
            request.callback = callback;
            requests.add(request);
            pending.put(request.id, request);
            maxConcurrent = Math.max(maxConcurrent, pending.size());
            assertTrue("本功能网络在途不能超过两个", pending.size() <= 2);
            if (auto) {
                ApplicationLoader.applicationHandler.post(() -> answer(request, response(request), null));
            }
            return request.id;
        }

        @Override
        void cancel(int id) {
            if (pending.remove(id) != null) {
                cancelled++;
            }
        }

        @Override
        void chat(long id, Utilities.Callback<TLRPC.Chat> callback) {
            if (realCache) {
                super.chat(id, callback);
            } else {
                callback.run(null);
            }
        }

        @Override
        void load(long dialogId, ArrayList<Integer> ids, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            reads++;
            if (readFailure) {
                callback.run(null, new SQLiteException("隔离缓存读取故障"));
            } else if (realCache) {
                super.load(dialogId, ids, callback);
            } else {
                TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
                for (int id : ids) {
                    TLRPC.Message message = cache.get(dialogId + ":" + id);
                    if (message != null) {
                        result.messages.add(message);
                    }
                }
                callback.run(result, null);
            }
        }

        @Override
        void save(long dialogId, TLRPC.messages_Messages result, Utilities.Callback<Exception> callback) {
            writes++;
            if (writeFailure) {
                callback.run(new SQLiteException("隔离缓存写入故障"));
            } else if (realCache) {
                super.save(dialogId, result, callback);
            } else {
                for (TLRPC.Message message : result.messages) {
                    cache.put(dialogId + ":" + message.id, message);
                }
                callback.run(null);
            }
        }

        @Override
        long now() {
            return time;
        }

        void answer(Request request, TLObject result, String text) {
            pending.remove(request.id);
            TLRPC.TL_error error = null;
            if (text != null) {
                error = new TLRPC.TL_error();
                error.code = 400;
                error.text = text;
            }
            request.callback.run(result, error);
        }

        TLObject response(Request request) {
            if (request.object instanceof TLRPC.TL_channels_getMessages) {
                TLRPC.TL_channels_getMessages input = (TLRPC.TL_channels_getMessages) request.object;
                assertTrue(input.id.size() <= 100);
                TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
                result.chats.add(chats.get(input.channel.channel_id));
                for (int id : input.id) {
                    if (!missing.contains(input.channel.channel_id + ":" + id)) {
                        String key = input.channel.channel_id + ":" + id;
                        result.messages.add(remote.containsKey(key) ? remote.get(key) : sourceMessage(input.channel.channel_id, id));
                    }
                }
                Collections.reverse(result.messages);
                return result;
            }
            if (request.object instanceof TLRPC.TL_contacts_resolveUsername) {
                String username = ((TLRPC.TL_contacts_resolveUsername) request.object).username;
                TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
                for (TLRPC.Chat chat : chats.values()) {
                    if (username.equals(chat.username)) {
                        result.peer = new TLRPC.TL_peerChannel();
                        result.peer.channel_id = chat.id;
                        result.chats.add(chat);
                    }
                }
                return result;
            }
            assertTrue("不能发出读取之外的协议请求", request.object instanceof TLRPC.TL_channels_getChannels);
            TLRPC.TL_messages_chats result = new TLRPC.TL_messages_chats();
            for (TLRPC.InputChannel channel : ((TLRPC.TL_channels_getChannels) request.object).id) {
                assertEquals("缺少访问资料只能让服务端确认", 0, channel.access_hash);
                TLRPC.Chat chat = chats.get(channel.channel_id);
                if (chat != null) {
                    result.chats.add(chat);
                }
            }
            return result;
        }

        int messageRequests() {
            int count = 0;
            for (Request request : requests) {
                if (request.object instanceof TLRPC.TL_channels_getMessages) {
                    count++;
                }
            }
            return count;
        }
    }

    static class Watch {
        SavedLinkPreviewController.Subscription subscription;
        SavedLinkPreviewController.Preview value;
        int calls;

        boolean finished() {
            return value != null && !value.loading && (value.message != null || value.error != null
                    || value.reference != null && value.reference.parseState != SavedLinkReference.PARSED);
        }
    }

    File root;
    Source source;
    SavedLinkPreviewStorage storage;
    SavedLinkPreviewController controller;
    final ArrayList<Watch> watches = new ArrayList<>();
    private boolean installedUser;

    @Before
    public void setUp() throws Exception {
        main(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            assertFalse("只允许在合成隔离账号执行", UserConfig.getInstance(account).isClientActivated());
        }
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "saved_link_loading/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        main(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = USER;
            user.self = true;
            user.first_name = "来源缓存隔离测试";
            user.phone = "";
            UserConfig.getInstance(0).setCurrentUser(user);
            UserConfig.selectedAccount = 0;
            MessagesController.getInstance(0).putUser(user, false);
            installedUser = true;
            source = new Source();
            for (long id : new long[]{C1, C2, C3}) {
                TLRPC.Chat chat = channel(id);
                source.chats.put(id, chat);
                MessagesController.getInstance(0).putChat(chat, false);
            }
            storage = new SavedLinkPreviewStorage(root, USER, false);
            controller = new SavedLinkPreviewController(0, storage, source);
        });
        sql("DELETE FROM messages_v2 WHERE uid IN (" + -C1 + "," + -C2 + "," + -C3 + ")");
        sql("DELETE FROM chats WHERE uid IN (" + C1 + "," + C2 + "," + C3 + ")");
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            main(controller::cleanup);
        }
        if (storage != null) {
            CountDownLatch done = new CountDownLatch(1);
            storage.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        if (installedUser) {
            sql("DROP TRIGGER IF EXISTS saved_link_test_failure");
            sql("DELETE FROM messages_v2 WHERE uid IN (" + -C1 + "," + -C2 + "," + -C3 + ")");
            sql("DELETE FROM chats WHERE uid IN (" + C1 + "," + C2 + "," + C3 + ")");
            main(() -> UserConfig.getInstance(0).clearConfig());
        }
        if (root != null) {
            delete(root);
        }
    }

    @Test
    public void l2_knownSourceSharesOneReadAndTwentyBindingsUseMemory() throws Exception {
        Watch first = watch(saved(101, C1, 301));
        Watch second = watch(saved(102, C1, 301));
        waitFor(() -> first.finished() && second.finished());
        assertEquals(1, source.messageRequests());
        assertEquals(1, source.requests.size());
        assertSame(first.value.message, second.value.message);
        int parses = controller.getParseCount();
        int reads = source.reads;
        for (int i = 0; i < 20; i++) {
            Watch next = watch(saved(i % 2 == 0 ? 101 : 102, C1, 301));
            waitFor(next::finished);
            main(next.subscription::cancel);
        }
        assertEquals(parses, controller.getParseCount());
        assertEquals(reads, source.reads);
        assertEquals(1, source.requests.size());
        assertEquals(1, source.writes);
        assertEquals(101, first.value.reference.savedMessageId);
        assertEquals(102, second.value.reference.savedMessageId);
        System.out.println("L2：20 次复用，新增解析=0，来源请求=0，消息请求=0，缓存读取=0，媒体调用=0");
    }

    @Test
    public void l2_cancelOnePageKeepsOtherAndLastCancellationRejectsLateResult() throws Exception {
        source.auto = false;
        Watch first = watch(saved(101, C1, 301));
        Watch second = watch(saved(102, C1, 301));
        waitFor(() -> source.pending.size() == 1);
        Request request = source.requests.get(0);
        main(first.subscription::cancel);
        int calls = first.calls;
        assertEquals(0, source.cancelled);
        main(() -> source.answer(request, source.response(request), null));
        waitFor(second::finished);
        assertEquals(calls, first.calls);
        assertEquals(1, source.messageRequests());
        Watch last = watch(saved(103, C2, 301));
        waitFor(() -> source.pending.size() == 1);
        Request abandoned = source.requests.get(1);
        main(last.subscription::cancel);
        calls = last.calls;
        assertEquals(1, source.cancelled);
        main(() -> source.answer(abandoned, source.response(abandoned), null));
        idle();
        assertEquals(calls, last.calls);
        assertNull(source.cache.get(-C2 + ":301"));
    }

    @Test
    public void l2_reversedRepliesWithSameMessageIdStayInTheirChannels() throws Exception {
        source.auto = false;
        Watch first = watch(saved(101, C1, 301));
        Watch second = watch(saved(103, C2, 301));
        waitFor(() -> source.pending.size() == 2);
        ArrayList<Request> requests = new ArrayList<>(source.pending.values());
        Collections.reverse(requests);
        for (Request request : requests) {
            main(() -> source.answer(request, source.response(request), null));
        }
        waitFor(() -> first.finished() && second.finished());
        assertEquals(-C1, first.value.message.getDialogId());
        assertEquals(-C2, second.value.message.getDialogId());
        assertEquals("来源 " + C1 + " / 301 #原话题", first.value.message.messageOwner.message);
        assertEquals("来源 " + C2 + " / 301 #原话题", second.value.message.messageOwner.message);
    }

    @Test
    public void l2_mismatchedResponseIsRejectedUntilExplicitRetry() throws Exception {
        source.auto = false;
        Watch watch = watch(saved(101, C1, 301));
        waitFor(() -> source.pending.size() == 1);
        Request request = source.requests.get(0);
        TLRPC.TL_messages_messages wrong = new TLRPC.TL_messages_messages();
        wrong.messages.add(sourceMessage(C2, 301));
        main(() -> source.answer(request, wrong, null));
        waitFor(watch::finished);
        assertNull(watch.value.message);
        assertEquals("MESSAGE_MISSING", watch.value.error);
        assertNotEquals(SavedLinkReference.UNAVAILABLE, watch.value.reference.state);
        for (int i = 0; i < 20; i++) {
            Watch rebound = watch(saved(101, C1, 301));
            waitFor(rebound::finished);
            assertNull(rebound.value.message);
        }
        assertEquals(1, source.requests.size());
        source.auto = true;
        main(watch.subscription::retry);
        waitFor(() -> watch.finished() && watch.value.message != null);
        assertEquals(2, source.requests.size());
    }

    @Test
    public void l2_usernameResolutionIsSharedAndPersisted() throws Exception {
        String name = "savedlinkpublictest";
        source.chats.get(C1).username = name;
        Watch first = watch(SavedLinkReferenceTest.message(101, "https://t.me/" + name + "/301"));
        Watch second = watch(SavedLinkReferenceTest.message(102, "https://t.me/" + name + "/301"));
        waitFor(() -> first.finished() && second.finished());
        assertEquals(2, source.requests.size());
        assertTrue(source.requests.get(0).object instanceof TLRPC.TL_contacts_resolveUsername);
        assertEquals(1, source.messageRequests());
        assertEquals(-C1, first.value.reference.sourceDialogId);
        CountDownLatch done = new CountDownLatch(1);
        main(() -> storage.load(Arrays.asList(101, 102), (values, error) -> {
            assertNull(error);
            assertEquals(-C1, values.get(101).sourceDialogId);
            assertEquals(-C1, values.get(102).sourceDialogId);
            done.countDown();
        }));
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    @Test
    public void l2_privateIdWithoutHashWaitsForServerIdentity() throws Exception {
        source.auto = false;
        long channel = C3 + 10;
        source.chats.put(channel, channel(channel));
        Watch watch = watch(saved(101, channel, 301));
        waitFor(() -> source.pending.size() == 1);
        Request resolution = source.requests.get(0);
        assertTrue(resolution.object instanceof TLRPC.TL_channels_getChannels);
        assertEquals(0, source.messageRequests());
        main(() -> source.answer(resolution, source.response(resolution), null));
        waitFor(() -> source.messageRequests() == 1);
        Request read = source.requests.get(1);
        assertEquals(source.chats.get(channel).access_hash, ((TLRPC.TL_channels_getMessages) read.object).channel.access_hash);
        main(() -> source.answer(read, source.response(read), null));
        waitFor(watch::finished);
        assertEquals(-channel, watch.value.message.getDialogId());
    }

    @Test
    public void l2_nonChannelResolutionKeepsOriginalAndDoesNotRead() throws Exception {
        source.auto = false;
        TLRPC.Message original = SavedLinkReferenceTest.message(101, "https://t.me/savedlinkperson/301");
        Watch watch = watch(original);
        waitFor(() -> source.pending.size() == 1);
        TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
        result.peer = new TLRPC.TL_peerUser();
        result.peer.user_id = 123;
        main(() -> source.answer(source.requests.get(0), result, null));
        waitFor(watch::finished);
        assertNull(watch.value.message);
        assertEquals("SOURCE_UNAVAILABLE", watch.value.error);
        assertEquals(0, source.messageRequests());
        assertEquals("https://t.me/savedlinkperson/301", original.message);
    }

    @Test
    public void l2_twoHundredFiveIdsAndThreeChannelsRespectBothLimits() throws Exception {
        source.auto = false;
        for (int i = 0; i < 205; i++) {
            watch(saved(1000 + i, C1, 1000 + i));
        }
        watch(saved(2001, C2, 301));
        watch(saved(2002, C3, 301));
        waitFor(() -> source.pending.size() == 2);
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            main(() -> {
                ArrayList<Request> requests = new ArrayList<>(source.pending.values());
                Collections.reverse(requests);
                for (Request request : requests) {
                    source.answer(request, source.response(request), null);
                }
            });
            idle();
            boolean finished = true;
            for (Watch watch : watches) {
                finished &= watch.finished();
            }
            if (finished) {
                break;
            }
            SystemClock.sleep(20);
        }
        HashSet<String> ids = new HashSet<>();
        for (Request request : source.requests) {
            assertTrue(request.object instanceof TLRPC.TL_channels_getMessages);
            TLRPC.TL_channels_getMessages input = (TLRPC.TL_channels_getMessages) request.object;
            assertTrue(input.id.size() <= 100);
            for (int id : input.id) {
                assertTrue("同一目标不能重复请求", ids.add(input.channel.channel_id + ":" + id));
            }
        }
        assertEquals(207, ids.size());
        assertEquals(2, source.maxConcurrent);
        for (Watch watch : watches) {
            assertTrue(watch.finished());
            assertNotNull(watch.value.message);
            assertEquals(watch.value.reference.sourceMessageId, watch.value.message.getId());
            assertEquals(watch.value.reference.sourceDialogId, watch.value.message.getDialogId());
        }
        assertTrue(source.pending.isEmpty());
        System.out.println("L2：205 条同频道 + 另外两个频道，目标去重=207，单批上限=100，最大并发=" + source.maxConcurrent + "，消息请求=" + source.messageRequests());
    }

    @Test
    public void l2_partialReplyAndDuplicateCallbackKeepSuccessfulItems() throws Exception {
        source.auto = false;
        Watch first = watch(saved(101, C1, 301));
        Watch second = watch(saved(102, C1, 302));
        waitFor(() -> source.pending.size() == 1);
        source.missing.add(C1 + ":302");
        Request request = source.requests.get(0);
        TLObject result = source.response(request);
        main(() -> {
            source.answer(request, result, null);
            source.answer(request, result, null);
        });
        waitFor(() -> first.finished() && second.finished());
        assertNotNull(first.value.message);
        assertNull(second.value.message);
        assertEquals("MESSAGE_MISSING", second.value.error);
        assertEquals(1, source.writes);
        assertEquals(1, source.messageRequests());
        source.auto = true;
        source.missing.clear();
        main(second.subscription::retry);
        waitFor(() -> second.finished() && second.value.message != null);
        assertEquals(2, source.messageRequests());
    }

    @Test
    public void l2_networkFailurePreservesStaleCacheAndDoesNotRetryOnBind() throws Exception {
        source.auto = false;
        source.cache.put(-C1 + ":301", sourceMessage(C1, 301));
        Watch watch = watch(saved(101, C1, 301));
        waitFor(() -> source.pending.size() == 1);
        assertNotNull(watch.value.message);
        assertTrue(watch.value.loading);
        main(() -> source.answer(source.requests.get(0), null, "NETWORK_FAILED"));
        waitFor(watch::finished);
        assertNotNull(watch.value.message);
        assertEquals("NETWORK_FAILED", watch.value.error);
        assertEquals(0, watch.value.reference.lastSuccessAt);
        for (int i = 0; i < 20; i++) {
            Watch next = watch(saved(101, C1, 301));
            waitFor(next::finished);
        }
        assertEquals(1, source.requests.size());
    }

    @Test
    public void l2_timeoutCancelsAndExplicitRetryCanRecover() throws Exception {
        source.auto = false;
        Watch watch = watch(saved(101, C1, 301));
        waitFor(() -> source.pending.size() == 1);
        Field jobs = SavedLinkPreviewController.class.getDeclaredField("jobs");
        jobs.setAccessible(true);
        Object job = ((ArrayList<?>) jobs.get(controller)).get(0);
        Field timeout = job.getClass().getDeclaredField("timeout");
        timeout.setAccessible(true);
        main((Runnable) timeout.get(job));
        waitFor(watch::finished);
        assertEquals("TIMEOUT", watch.value.error);
        assertEquals(1, source.cancelled);
        source.auto = true;
        main(watch.subscription::retry);
        waitFor(() -> watch.finished() && watch.value.message != null);
        assertEquals(2, source.messageRequests());
    }

    @Test
    public void l2_permissionFailureHidesCacheWithoutRemovingReference() throws Exception {
        source.auto = false;
        source.cache.put(-C1 + ":301", sourceMessage(C1, 301));
        Watch watch = watch(saved(101, C1, 301));
        waitFor(() -> source.pending.size() == 1);
        assertNotNull(watch.value.message);
        main(() -> source.answer(source.requests.get(0), null, "CHANNEL_PRIVATE"));
        waitFor(watch::finished);
        assertNull(watch.value.message);
        assertEquals(SavedLinkReference.UNAVAILABLE, watch.value.reference.state);
        assertEquals(101, watch.value.reference.savedMessageId);
        assertEquals(-C1, watch.value.reference.sourceDialogId);
    }

    @Test
    public void l2_failedCacheReadFallsBackAndWriteErrorKeepsVisibleResponse() throws Exception {
        source.readFailure = true;
        source.writeFailure = true;
        Watch watch = watch(saved(101, C1, 301));
        waitFor(watch::finished);
        assertNotNull(watch.value.message);
        assertEquals("CACHE_WRITE_FAILED", watch.value.error);
        assertEquals(0, watch.value.reference.lastSuccessAt);
        assertEquals(1, source.messageRequests());
        assertEquals(1, source.writes);
        assertTrue(source.cache.isEmpty());
        assertTrue(watch.value.isProtected());
    }

    @Test
    public void l2_cacheWithWrongSourceCannotBeShown() throws Exception {
        source.cache.put(-C1 + ":301", sourceMessage(C2, 301));
        Watch watch = watch(saved(101, C1, 301));
        waitFor(watch::finished);
        assertEquals(-C1, watch.value.message.getDialogId());
        assertEquals(1, source.messageRequests());
    }

    @Test
    public void l2_actualJniCachePreservesReadStateAndDoesNotCreateDialogsOrDownloads() throws Exception {
        source.realCache = true;
        long dialogs = number("SELECT COUNT(*) FROM dialogs");
        long downloads = number("SELECT COUNT(*) FROM download_queue");
        Watch watch = watch(saved(101, C1, 301));
        waitFor(watch::finished);
        assertNull(watch.value.error);
        sql("UPDATE messages_v2 SET read_state = 0 WHERE uid = " + -C1 + " AND mid = 301");
        main(watch.subscription::retry);
        waitFor(() -> source.writes == 2 && watch.finished());
        assertEquals(0, number("SELECT read_state FROM messages_v2 WHERE uid = " + -C1 + " AND mid = 301"));
        assertEquals(dialogs, number("SELECT COUNT(*) FROM dialogs"));
        assertEquals(downloads, number("SELECT COUNT(*) FROM download_queue"));
        TLRPC.messages_Messages result = await(callback -> MessagesStorage.getInstance(0).getMessagesByIds(-C1, new ArrayList<>(Collections.singleton(301)), callback));
        assertEquals(1, result.messages.size());
        assertEquals(-C1, result.messages.get(0).dialog_id);
        assertEquals(C1, result.messages.get(0).peer_id.channel_id);
        assertTrue(result.messages.get(0).noforwards);
        assertEquals(watch.value.message.messageOwner.message, result.messages.get(0).message);
    }

    @Test
    public void l2_realCacheAndReferenceReopenDoNotResolveOrReadAgain() throws Exception {
        source.realCache = true;
        Watch first = watch(saved(101, C1, 301));
        waitFor(first::finished);
        assertNull(first.value.error);
        main(controller::cleanup);
        CountDownLatch closed = new CountDownLatch(1);
        storage.close(closed::countDown);
        assertTrue(closed.await(15, TimeUnit.SECONDS));
        main(() -> {
            MessagesController.getInstance(0).getChats().remove(C1);
            storage = new SavedLinkPreviewStorage(root, USER, false);
            source = new Source();
            source.realCache = true;
            source.chats.put(C1, channel(C1));
            controller = new SavedLinkPreviewController(0, storage, source);
        });
        Watch second = watch(saved(101, C1, 301));
        waitFor(second::finished);
        assertNull(second.value.error);
        assertNotNull(second.value.message);
        assertTrue(second.value.cached);
        assertEquals(0, controller.getParseCount());
        assertEquals(0, source.requests.size());
    }

    @Test
    public void l2_cacheTransactionFailureRollsBackAndReportsOnMainThread() throws Exception {
        sql("CREATE TRIGGER saved_link_test_failure BEFORE UPDATE ON messages_v2 WHEN NEW.uid = " + -C1 + " AND NEW.mid = 302 BEGIN SELECT RAISE(ABORT, '隔离写入故障'); END");
        TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
        result.chats.add(channel(C1));
        result.messages.add(sourceMessage(C1, 301));
        result.messages.add(sourceMessage(C1, 302));
        CountDownLatch done = new CountDownLatch(1);
        Exception[] error = new Exception[1];
        main(() -> MessagesStorage.getInstance(0).putSavedLinkMessages(-C1, result, failure -> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            error[0] = failure;
            done.countDown();
        }));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNotNull(error[0]);
        assertEquals(0, number("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + -C1));
        sql("DROP TRIGGER saved_link_test_failure");
        CountDownLatch recovered = new CountDownLatch(1);
        main(() -> MessagesStorage.getInstance(0).putSavedLinkMessages(-C1, result, failure -> {
            assertNull(failure);
            recovered.countDown();
        }));
        assertTrue(recovered.await(15, TimeUnit.SECONDS));
        assertEquals(2, number("SELECT COUNT(*) FROM messages_v2 WHERE uid = " + -C1));
    }

    @Test
    public void l2_savedEditCancelsOldSourceWithoutChangingOriginalText() throws Exception {
        source.auto = false;
        TLRPC.Message original = saved(101, C1, 301);
        Watch old = watch(original);
        waitFor(() -> source.pending.size() == 1);
        Request request = source.requests.get(0);
        Watch edited = watch(saved(101, C2, 301));
        waitFor(() -> source.requests.size() == 2);
        main(() -> source.answer(request, source.response(request), null));
        idle();
        assertNull(old.value.message);
        main(() -> source.answer(source.requests.get(1), source.response(source.requests.get(1)), null));
        waitFor(edited::finished);
        assertEquals(-C2, edited.value.message.getDialogId());
        assertEquals("https://t.me/c/" + C1 + "/301", original.message);
        assertEquals(USER, original.dialog_id);
        assertEquals(101, original.id);
    }

    static TLRPC.Chat channel(long id) {
        TLRPC.TL_channel chat = new TLRPC.TL_channel();
        chat.id = id;
        chat.access_hash = id + 123;
        chat.flags |= 8192;
        chat.broadcast = true;
        chat.title = "隔离来源 " + id;
        chat.photo = new TLRPC.TL_chatPhotoEmpty();
        chat.noforwards = true;
        return chat;
    }

    static TLRPC.Message sourceMessage(long channel, int id) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.peer_id = new TLRPC.TL_peerChannel();
        message.peer_id.channel_id = channel;
        message.from_id = message.peer_id;
        message.flags |= 256;
        message.dialog_id = -channel;
        message.date = 1700000000;
        message.message = "来源 " + channel + " / " + id + " #原话题";
        message.noforwards = true;
        return message;
    }

    static TLRPC.Message saved(int id, long channel, int sourceId) {
        TLRPC.Message message = SavedLinkReferenceTest.message(id, "https://t.me/c/" + channel + "/" + sourceId);
        message.from_id = message.peer_id;
        message.flags |= 256;
        message.out = true;
        return message;
    }

    Watch watch(TLRPC.Message message) {
        Watch watch = new Watch();
        watches.add(watch);
        main(() -> watch.subscription = controller.subscribe(message, value -> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            watch.value = value;
            watch.calls++;
        }));
        return watch;
    }

    static void main(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    static void idle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    static void waitFor(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            boolean[] done = new boolean[1];
            main(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                idle();
                return;
            }
            SystemClock.sleep(10);
        }
        fail("来源加载状态等待超时");
    }

    static <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        Object[] value = new Object[1];
        Exception[] error = new Exception[1];
        CountDownLatch done = new CountDownLatch(1);
        main(() -> action.accept((result, failure) -> {
            value[0] = result;
            error[0] = failure;
            done.countDown();
        }));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(error[0]);
        return (T) value[0];
    }

    static long number(String sql) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        long[] value = new long[1];
        Exception[] error = new Exception[1];
        MessagesStorage.getInstance(0).getStorageQueue().postRunnable(() -> {
            try {
                SQLiteCursor cursor = MessagesStorage.getInstance(0).getDatabase().queryFinalized(sql);
                try {
                    if (cursor.next()) {
                        value[0] = cursor.longValue(0);
                    }
                } finally {
                    cursor.dispose();
                }
            } catch (Exception e) {
                error[0] = e;
            }
            done.countDown();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(error[0]);
        return value[0];
    }

    static void sql(String sql) throws Exception {
        number(sql);
    }

    private void delete(File file) throws Exception {
        assertTrue(file.getCanonicalPath().equals(root.getCanonicalPath()) || file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator));
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        assertTrue(file.delete());
    }
}
