package org.telegram.messenger;

import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkLifecycleTest {
    final SavedLinkLoadingTest f = new SavedLinkLoadingTest();
    final ArrayList<SavedLinkPreviewStorage> stores = new ArrayList<>();
    final ArrayList<SavedLinkPreviewController> controllers = new ArrayList<>();
    LocalSavedTagsStorage tags;
    LocalSavedTag tag;
    Source source;

    static class Source extends SavedLinkLoadingTest.Source {
        final Map<Integer, TLRPC.Message> saved = new HashMap<>();

        @Override
        TLObject response(Request request) {
            if (!(request.object instanceof TLRPC.TL_messages_getMessages)) return super.response(request);
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            ArrayList<Integer> ids = ((TLRPC.TL_messages_getMessages) request.object).id;
            assertTrue(ids.size() <= 100);
            for (int id : ids) {
                if (saved.containsKey(id)) result.messages.add(saved.get(id));
            }
            return result;
        }

        int checks() {
            return (int) requests.stream().filter(item -> item.object instanceof TLRPC.TL_messages_getMessages).count();
        }
    }

    @Before
    public void setUp() throws Exception {
        f.setUp();
        main(f.controller::cleanup);
        close(f.storage);
        source = new Source();
        source.chats.putAll(f.source.chats);
        f.source = source;
        f.storage = new SavedLinkPreviewStorage(f.root, USER, false);
        main(() -> {
            f.controller = new SavedLinkPreviewController(0, f.storage, source);
            install(f.controller);
        });
        tags = new LocalSavedTagsStorage(f.root, USER, false);
        tag = await(cb -> tags.createTag("生命周期保留", cb));
        SparseIntArray dates = new SparseIntArray();
        for (int id = 101; id <= 103; id++) dates.put(id, 1700000000 + id);
        SavedLinkLoadingTest.<Void>await(cb -> tags.applyTags(dates, Collections.singletonList(tag.id), Collections.emptyList(), cb));
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            for (SavedLinkPreviewController controller : controllers) controller.cleanup();
            SavedLinkPreviewController.cleanupAccount(0);
            if (ConnectionsManager.getInstance(0).isTestBackend()) ConnectionsManager.getInstance(0).switchBackend(false);
        });
        for (SavedLinkPreviewStorage store : stores) close(store);
        if (tags != null) {
            CountDownLatch done = new CountDownLatch(1);
            tags.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        sql("DELETE FROM dialogs WHERE did IN (" + -C1 + "," + -C2 + "," + -C3 + ")");
        f.tearDown();
    }

    @Test
    public void l6_logoutClosesRequestsObserversAndDatabaseThenSameUserRestores() throws Exception {
        source.realCache = true;
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        MessageObject media = first.value.message;
        source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> source.pending.size() == 1);
        Request pending = source.pending.values().iterator().next();
        int calls = first.calls;
        main(() -> {
            UserConfig.getInstance(0).clearConfig();
            MessagesController.getInstance(0).cleanup();
        });
        assertClosed(f.controller, f.storage);
        assertTrue(media.savedLinkInvalidated);
        assertFalse(SavedLinkPreviewController.isMediaValid(media));
        assertEquals(1, source.cancelled);
        Source next = new Source();
        next.realCache = true;
        next.chats.putAll(source.chats);
        main(() -> user(USER));
        SavedLinkPreviewController restored = open(USER, false, next);
        Watch current = watch(restored, saved(101, C1, 301));
        waitFor(current::finished);
        main(() -> source.answer(pending, source.response(pending), null));
        idle();
        assertEquals(calls, first.calls);
        assertEquals(0, next.requests.size());
        assertEquals(0, restored.getParseCount());
        assertEquals(-C1, current.value.message.getDialogId());
        assertTrue(current.value.cached);
        assertTags();
        System.out.println("L6 同账号重登：旧请求取消、监听移除、SQLite关闭；引用与原消息缓存恢复，新增解析/网络=0，标签3条保留");
    }

    @Test
    public void l6_reusedSlotDoesNotInheritOldPermissionOrCallbacks() throws Exception {
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> source.pending.size() == 1);
        Request pending = source.pending.values().iterator().next();
        int calls = first.calls;
        main(() -> {
            MessagesController.getInstance(0).cleanup();
            UserConfig.getInstance(0).clearConfig();
            user(SavedLinkReferenceTest.USER_B);
        });
        Source other = new Source();
        other.auto = false;
        other.chats.putAll(source.chats);
        main(() -> MessagesController.getInstance(0).putChat(channel(C1), false));
        SavedLinkPreviewController controller = open(SavedLinkReferenceTest.USER_B, false, other);
        TLRPC.Message saved = saved(101, C1, 301);
        saved.dialog_id = saved.peer_id.user_id = SavedLinkReferenceTest.USER_B;
        Watch second = watch(controller, saved);
        waitFor(() -> other.pending.size() == 1);
        assertNull(second.value.message);
        Request denied = other.pending.values().iterator().next();
        main(() -> {
            other.answer(denied, null, "CHANNEL_PRIVATE");
            source.answer(pending, source.response(pending), null);
        });
        waitFor(second::finished);
        assertNull(second.value.message);
        assertEquals(SavedLinkReference.UNAVAILABLE, second.value.reference.state);
        assertEquals(calls, first.calls);
        assertEquals(SavedLinkReferenceTest.USER_B, second.value.reference.userId);
        assertFalse(f.storage.getDatabaseFile().equals(stores.get(0).getDatabaseFile()));
        assertTags();
    }

    @Test
    public void l6_backendSwitchImmediatelyReleasesOldUserResources() throws Exception {
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> source.pending.size() == 1);
        Request pending = source.pending.values().iterator().next();
        int calls = first.calls;
        main(() -> ConnectionsManager.getInstance(0).switchBackend(false));
        waitFor(() -> ConnectionsManager.getInstance(0).isTestBackend());
        assertClosed(f.controller, f.storage);
        Source test = new Source();
        test.chats.putAll(source.chats);
        SavedLinkPreviewController controller = open(USER, true, test);
        Watch second = watch(controller, saved(101, C2, 301));
        waitFor(second::finished);
        assertEquals(-C2, second.value.message.getDialogId());
        assertTrue(second.value.message.savedLinkTestBackend);
        assertFalse(SavedLinkPreviewController.isMediaValid(first.value.message));
        assertTrue(stores.get(0).getDatabaseFile().getPath().contains("/test/"));
        main(() -> source.answer(pending, source.response(pending), null));
        idle();
        assertEquals(calls, first.calls);
        main(() -> ConnectionsManager.getInstance(0).switchBackend(false));
        waitFor(() -> !ConnectionsManager.getInstance(0).isTestBackend());
        assertClosed(controller, stores.get(0));
        assertFalse(SavedLinkPreviewController.isMediaValid(second.value.message));
        SavedLinkPreviewStorage original = new SavedLinkPreviewStorage(f.root, USER, false);
        stores.add(original);
        SparseArray<SavedLinkReference> rows = await(cb -> original.load(Collections.singletonList(101), cb));
        assertEquals(-C1, rows.get(101).sourceDialogId);
        assertTags();
    }

    @Test
    public void l6_sharedSubscriptionsReleaseOnlyTheirOwnWorkAndLateResult() throws Exception {
        source.auto = false;
        Watch first = f.watch(saved(101, C1, 301));
        Watch second = f.watch(saved(102, C1, 301));
        waitFor(() -> source.pending.size() == 1 && second.value != null);
        Request pending = source.pending.values().iterator().next();
        main(first.subscription::cancel);
        assertEquals(0, source.cancelled);
        assertEquals(1, source.pending.size());
        main(() -> source.answer(pending, source.response(pending), null));
        waitFor(second::finished);
        assertNotNull(second.value.message);
        assertNull(first.value.message);
        main(second.subscription::retry);
        waitFor(() -> source.pending.size() == 1);
        Request late = source.pending.values().iterator().next();
        main(second.subscription::cancel);
        int calls = second.calls;
        assertEquals(1, source.cancelled);
        main(() -> source.answer(late, source.response(late), null));
        idle();
        assertEquals(calls, second.calls);
        assertTrue(SavedLinkCardTest.field(f.controller, "subscriptions", ArrayList.class).isEmpty());
        assertTrue(SavedLinkCardTest.field(f.controller, "jobs", ArrayList.class).isEmpty());
        assertTags();
    }

    @Test
    public void l6_actualMessageCacheClearInvalidatesOldMediaAndReloadsOnlyMissingBodies() throws Exception {
        source.realCache = true;
        Watch first = f.watch(saved(101, C1, 301));
        Watch second = f.watch(saved(102, C1, 302));
        Watch last = f.watch(saved(103, C1, 303));
        waitFor(() -> first.finished() && second.finished() && last.finished());
        MessageObject old = first.value.message;
        sql("REPLACE INTO dialogs(did,last_mid,last_mid_i) VALUES(" + -C1 + ",303,303)");
        source.auto = false;
        int before = source.messageRequests();
        main(() -> MessagesStorage.getInstance(0).clearLocalDatabase());
        waitFor(() -> source.pending.size() == 1);
        assertEquals(0, number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + -C1 + " AND mid=301"));
        assertEquals(1, number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + -C1 + " AND mid=303"));
        assertTrue(old.savedLinkInvalidated);
        assertNull(first.value.message);
        assertNotNull(last.value.message);
        Request request = source.pending.values().iterator().next();
        ArrayList<Integer> ids = ((TLRPC.TL_channels_getMessages) request.object).id;
        assertEquals(2, ids.size());
        assertTrue(ids.containsAll(Arrays.asList(301, 302)));
        main(() -> source.answer(request, source.response(request), null));
        waitFor(() -> first.finished() && second.finished() && last.finished());
        assertEquals(before + 1, source.messageRequests());
        assertEquals(3, number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + -C1));
        assertTags();
        System.out.println("L6 实际清理消息库：保留最后1条，缺失2条合并1次补载；旧媒体失效，3条收藏标签不变");
    }

    @Test
    public void l6_clearReferenceCacheRejectsQueuedParsesAndKeepsMessageCacheAndTags() throws Exception {
        source.realCache = true;
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        AtomicReference<Exception> parseError = new AtomicReference<>();
        CountDownLatch parsed = new CountDownLatch(1);
        SavedLinkLoadingTest.<Void>await(cb -> {
            f.controller.loadReference(saved(102, C2, 301), (value, error) -> {
                assertNull(value);
                parseError.set(error);
                parsed.countDown();
            });
            f.controller.clearReferenceCache(cb);
        });
        assertTrue(parsed.await(15, TimeUnit.SECONDS));
        assertNotNull(parseError.get());
        assertClosed(f.controller, f.storage);
        SavedLinkPreviewStorage restored = new SavedLinkPreviewStorage(f.root, USER, false);
        stores.add(restored);
        assertTrue(SavedLinkLoadingTest.<ArrayList<Integer>>await(cb -> restored.loadIds(Integer.MAX_VALUE, cb)).isEmpty());
        assertEquals(1, number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + -C1 + " AND mid=301"));
        assertTags();
        Source next = new Source();
        next.realCache = true;
        next.auto = false;
        next.chats.putAll(source.chats);
        SavedLinkPreviewController controller = open(USER, false, next);
        Watch rebound = watch(controller, saved(101, C1, 301));
        waitFor(() -> rebound.value != null && rebound.value.message != null && next.pending.size() == 1);
        assertTrue(rebound.value.cached);
        assertEquals(1, controller.getParseCount());
        assertEquals(1, next.messageRequests());
    }

    @Test
    public void l6_boundedHistoryRequiresConfirmedSelfResponseAndKeepsOtherReferences() throws Exception {
        seedReferences(3);
        Watch shared = f.watch(saved(102, C1, 301));
        waitFor(shared::finished);
        TLRPC.TL_messages_deleteHistory request = history(101);
        TLRPC.TL_messages_affectedHistory partial = new TLRPC.TL_messages_affectedHistory();
        partial.offset = 1;
        TLRPC.TL_error error = new TLRPC.TL_error();
        error.text = "NETWORK_FAILED";
        main(() -> {
            f.controller.onDeleteResponse(USER, request, partial, null);
            f.controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), error);
            f.controller.onDeleteResponse(USER + 1, request, new TLRPC.TL_messages_affectedHistory(), null);
        });
        assertEquals(3, ids().size());
        main(() -> f.controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        waitFor(() -> SavedLinkCardTest.field(f.controller, "deletedSavedIds", java.util.HashSet.class).contains(101));
        assertEquals(Arrays.asList(102, 103), ids());
        assertNotNull(shared.value.message);
        assertFalse(shared.value.message.savedLinkInvalidated);
        assertEquals(0, source.checks());
        assertTags();
    }

    @Test
    public void l6_unboundedAndDateDeletionKeepMissingForeignAndNewSavedMessages() throws Exception {
        seedReferences(4);
        source.auto = false;
        source.saved.put(101, empty(101, USER));
        source.saved.put(102, empty(102, USER + 1));
        source.saved.put(104, saved(104, C1, 301));
        main(() -> f.controller.onDeleteResponse(USER, history(Integer.MAX_VALUE), new TLRPC.TL_messages_affectedHistory(), null));
        waitFor(() -> source.pending.size() == 1);
        SavedLinkLoadingTest.<SavedLinkReference>await(cb -> f.controller.loadReference(saved(105, C1, 301), cb));
        Request pending = source.pending.values().iterator().next();
        main(() -> source.answer(pending, source.response(pending), null));
        waitFor(() -> source.pending.isEmpty());
        assertEquals(Arrays.asList(102, 103, 104, 105), ids());
        assertEquals(Arrays.asList(101, 102, 103, 104), ((TLRPC.TL_messages_getMessages) pending.object).id);
        TLRPC.TL_messages_deleteHistory date = history(104);
        date.flags = 12;
        date.min_date = 1700000000;
        date.max_date = 1700000500;
        main(() -> f.controller.onDeleteResponse(USER, date, new TLRPC.TL_messages_affectedHistory(), null));
        waitFor(() -> source.pending.size() == 1);
        Request failed = source.pending.values().iterator().next();
        main(() -> source.answer(failed, null, "TIMEOUT"));
        waitFor(() -> source.pending.isEmpty());
        assertEquals(Arrays.asList(102, 103, 104, 105), ids());
        assertTags();
    }

    @Test
    public void l6_sourceHistoryChecksOnlyLocalIdsInBoundedBatches() throws Exception {
        seedReferences(205);
        for (int id = 101; id <= 305; id++) source.saved.put(id, empty(id, USER));
        TLRPC.TL_messages_deleteSavedHistory request = new TLRPC.TL_messages_deleteSavedHistory();
        request.peer = new TLRPC.TL_inputPeerChannel();
        request.peer.channel_id = C1;
        request.parent_peer = new TLRPC.TL_inputPeerChannel();
        request.max_id = 305;
        main(() -> f.controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        assertEquals(205, ids().size());
        assertEquals(0, source.checks());
        request.parent_peer = null;
        main(() -> f.controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        waitFor(() -> source.checks() == 3 && source.pending.isEmpty());
        assertTrue(ids().isEmpty());
        assertEquals(100, ((TLRPC.TL_messages_getMessages) source.requests.get(0).object).id.size());
        assertEquals(100, ((TLRPC.TL_messages_getMessages) source.requests.get(1).object).id.size());
        assertEquals(5, ((TLRPC.TL_messages_getMessages) source.requests.get(2).object).id.size());
        assertTrue(source.maxConcurrent <= 2);
        assertTags();
        System.out.println("L6 收藏清空：只核对205个本地引用，批次100/100/5，并发不超过2，不扫描收藏历史");
    }

    @Test
    public void l6_failedReferenceClearReportsErrorAndPreservesRowsAndTags() throws Exception {
        seedReferences(3);
        SQLiteDatabase db = new SQLiteDatabase(f.storage.getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_link_clear BEFORE DELETE ON saved_link_references WHEN OLD.saved_message_id=102 BEGIN SELECT RAISE(ABORT, '隔离清理失败'); END").stepThis().dispose();
            CountDownLatch done = new CountDownLatch(1);
            AtomicReference<Exception> failure = new AtomicReference<>();
            main(() -> f.controller.clearReferenceCache((value, error) -> {
                failure.set(error);
                done.countDown();
            }));
            assertTrue(done.await(15, TimeUnit.SECONDS));
            assertNotNull(failure.get());
            assertClosed(f.controller, f.storage);
            assertEquals(3, (int) db.executeInt("SELECT COUNT(*) FROM saved_link_references"));
            assertTags();
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_link_clear").stepThis().dispose();
            db.close();
        }
        Source next = new Source();
        SavedLinkPreviewController restored = open(USER, false, next);
        SavedLinkLoadingTest.<Void>await(restored::clearReferenceCache);
        SavedLinkPreviewStorage store = new SavedLinkPreviewStorage(f.root, USER, false);
        stores.add(store);
        assertTrue(SavedLinkLoadingTest.<ArrayList<Integer>>await(cb -> store.loadIds(Integer.MAX_VALUE, cb)).isEmpty());
        assertTags();
    }

    private void seedReferences(int count) throws Exception {
        ArrayList<SavedLinkReference> values = new ArrayList<>();
        for (int id = 101; id <= 100 + count; id++) values.add(SavedLinkPreviewController.parse(USER, saved(id, C1, 301)));
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.save(values, cb));
    }

    private ArrayList<Integer> ids() throws Exception {
        return await(cb -> f.storage.loadIds(Integer.MAX_VALUE, cb));
    }

    private void assertTags() throws Exception {
        ArrayList<LocalSavedTag> values = await(tags::loadTags);
        assertEquals(1, values.size());
        assertEquals(tag.id, values.get(0).id);
        assertEquals("生命周期保留", values.get(0).name);
        assertEquals(3, values.get(0).messageCount);
    }

    private SavedLinkPreviewController open(long userId, boolean test, Source source) {
        SavedLinkPreviewStorage storage = new SavedLinkPreviewStorage(f.root, userId, test);
        stores.add(storage);
        SavedLinkPreviewController[] value = new SavedLinkPreviewController[1];
        main(() -> {
            value[0] = new SavedLinkPreviewController(0, storage, source);
            controllers.add(value[0]);
            install(value[0]);
        });
        return value[0];
    }

    static void install(SavedLinkPreviewController controller) {
        try {
            Field field = SavedLinkPreviewController.class.getDeclaredField("instances");
            field.setAccessible(true);
            ((SavedLinkPreviewController[]) field.get(null))[0] = controller;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    static void user(long id) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "链接生命周期测试";
        user.phone = "";
        UserConfig.getInstance(0).setCurrentUser(user);
        MessagesController.getInstance(0).putUser(user, false);
    }

    static Watch watch(SavedLinkPreviewController controller, TLRPC.Message message) {
        Watch watch = new Watch();
        main(() -> watch.subscription = controller.subscribe(message, value -> {
            watch.value = value;
            watch.calls++;
        }));
        return watch;
    }

    static void close(SavedLinkPreviewStorage storage) throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        storage.close(closed::countDown);
        assertTrue(closed.await(15, TimeUnit.SECONDS));
    }

    static void assertClosed(SavedLinkPreviewController controller, SavedLinkPreviewStorage storage) throws Exception {
        close(storage);
        assertFalse(controller.isActive());
        assertTrue(SavedLinkCardTest.field(controller, "subscriptions", ArrayList.class).isEmpty());
        assertTrue(SavedLinkCardTest.field(controller, "jobs", ArrayList.class).isEmpty());
        assertNull(SavedLinkCardTest.field(storage, "database", SQLiteDatabase.class));
        main(() -> {
            for (int id : new int[]{NotificationCenter.replaceMessagesObjects, NotificationCenter.chatInfoDidLoad,
                    NotificationCenter.updateInterfaces, NotificationCenter.didClearDatabase}) {
                ArrayList<NotificationCenter.NotificationCenterDelegate> observers = NotificationCenter.getInstance(0).getObservers(id);
                assertTrue(observers == null || !observers.contains(controller));
            }
        });
    }

    private static TLRPC.TL_messages_deleteHistory history(int maxId) {
        TLRPC.TL_messages_deleteHistory request = new TLRPC.TL_messages_deleteHistory();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.max_id = maxId;
        return request;
    }

    private static TLRPC.Message empty(int id, long peer) {
        TLRPC.TL_messageEmpty message = new TLRPC.TL_messageEmpty();
        message.id = id;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = peer;
        return message;
    }
}
