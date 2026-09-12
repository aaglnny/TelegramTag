package org.telegram.messenger;

import android.os.SystemClock;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsLifecycleTest {
    private static final long USER = 4294967611L;
    private File root;
    private LocalSavedTagsStorage storage;
    private LocalSavedTagsController controller;
    private LocalSavedTagsFilterTest.Source source;
    private final ArrayList<LocalSavedTagsStorage> stores = new ArrayList<>();
    private final ArrayList<LocalSavedTagsController> controllers = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertFalse(UserConfig.getInstance(0).isClientActivated());
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "local_saved_tags_lifecycle_tests/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        source = new LocalSavedTagsFilterTest.Source();
        source.userId = USER;
        main(() -> {
            user(USER);
            storage = new LocalSavedTagsStorage(root, USER, false);
            stores.add(storage);
            controller = new LocalSavedTagsController(0, storage, source);
            controllers.add(controller);
            install(controller);
        });
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            for (LocalSavedTagsController item : controllers) {
                item.cleanup();
            }
            install(null);
            UserConfig.getInstance(0).clearConfig();
        });
        for (LocalSavedTagsStorage store : stores) {
            CountDownLatch closed = new CountDownLatch(1);
            store.close(closed::countDown);
            assertTrue(closed.await(15, TimeUnit.SECONDS));
        }
        cacheTask(() -> {
            SQLiteDatabase db = MessagesStorage.getInstance(0).getDatabase();
            db.executeFast("DELETE FROM messages_v2 WHERE uid IN (" + USER + "," + (USER + 1) + ")").stepThis().dispose();
            db.executeFast("DELETE FROM dialogs WHERE did IN (" + USER + "," + (USER + 1) + ")").stepThis().dispose();
        });
        if (root != null) {
            delete(root);
        }
    }

    @Test
    public void t7_01_onlySuccessfulSavedMessageResponseRemovesRelations() throws Exception {
        LocalSavedTag tag = seed(2);
        TLRPC.TL_messages_deleteMessages request = new TLRPC.TL_messages_deleteMessages();
        request.id.add(101);
        TLRPC.TL_messages_affectedMessages success = new TLRPC.TL_messages_affectedMessages();
        TLRPC.TL_error failure = new TLRPC.TL_error();
        failure.text = "测试删除失败";
        main(() -> {
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted, new ArrayList<>(request.id), 0L, false);
            controller.onDeleteResponse(USER, request, success, failure);
            controller.onDeleteResponse(USER + 1, request, success, null);
            controller.onDeleteResponse(USER, request, new TLRPC.TL_boolTrue(), null);
            controller.onDeleteResponse(USER, new TLRPC.TL_messages_deleteScheduledMessages(), new TLRPC.TL_updates(), null);
            controller.onDeleteResponse(USER, new TLRPC.TL_channels_deleteMessages(), success, null);
        });
        assertEquals(2, count(tag.id));
        main(() -> controller.onDeleteResponse(USER, request, success, null));
        assertEquals(1, count(tag.id));
        main(() -> controller.onDeleteResponse(USER, request, success, null));
        assertEquals(1, count(tag.id));
        assertEquals(102, this.<LocalSavedTagsStorage.MessagePage>await(callback -> storage.loadMessages(tag.id, null, 50, callback)).messages.get(0).messageId);
    }

    @Test
    public void t7_01_serverUpdatePipelineDistinguishesChannelCopies() throws Exception {
        LocalSavedTag tag = seed(2);
        TL_update.TL_updateDeleteChannelMessages channel = new TL_update.TL_updateDeleteChannelMessages();
        channel.channel_id = 771;
        channel.messages.add(101);
        main(() -> assertTrue(MessagesController.getInstance(0).processUpdateArray(new ArrayList<>(Collections.singletonList(channel)), null, null, false, 1700000200)));
        assertEquals(2, count(tag.id));
        TL_update.TL_updateDeleteMessages saved = new TL_update.TL_updateDeleteMessages();
        saved.messages.add(101);
        main(() -> assertTrue(MessagesController.getInstance(0).processUpdateArray(new ArrayList<>(Collections.singletonList(saved)), null, null, false, 1700000200)));
        until(() -> controller.getTags().get(0).messageCount == 1);
        assertEquals(1, count(tag.id));
    }

    @Test
    public void t7_02_boundedHistoryWaitsForFinalResponseAndPreservesEmptyTag() throws Exception {
        LocalSavedTag tag = seed(3);
        TLRPC.TL_messages_deleteHistory request = history(102);
        TLRPC.TL_messages_affectedHistory partial = new TLRPC.TL_messages_affectedHistory();
        partial.offset = 1;
        main(() -> controller.onDeleteResponse(USER, request, partial, null));
        assertEquals(3, count(tag.id));
        TLRPC.TL_messages_affectedHistory complete = new TLRPC.TL_messages_affectedHistory();
        main(() -> controller.onDeleteResponse(USER + 1, request, complete, null));
        assertEquals(3, count(tag.id));
        main(() -> controller.onDeleteResponse(USER, request, complete, null));
        assertEquals(1, count(tag.id));
        main(() -> controller.onDeleteResponse(USER, history(103), complete, null));
        assertEquals(0, count(tag.id));
        assertEquals(1, this.<ArrayList<LocalSavedTag>>await(controller::loadTags).size());
        main(() -> controller.onDeleteResponse(USER, history(103), complete, null));
        assertEquals(0, count(tag.id));
        assertTrue(source.requests.isEmpty());
    }

    @Test
    public void t7_02_historyCleanupRollsBackAndCanReplayAfterInterruption() throws Exception {
        LocalSavedTag tag = seed(3);
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_history BEFORE DELETE ON local_saved_message_tags WHEN OLD.message_id = 102 BEGIN SELECT RAISE(ABORT, '测试历史清理中断'); END").stepThis().dispose();
            main(() -> controller.onDeleteResponse(USER, history(103), new TLRPC.TL_messages_affectedHistory(), null));
            assertEquals(3, count(tag.id));
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_history").stepThis().dispose();
            db.close();
        }
        main(() -> controller.onDeleteResponse(USER, history(103), new TLRPC.TL_messages_affectedHistory(), null));
        assertEquals(0, count(tag.id));
    }

    @Test
    public void t7_02_dateRangeVerifiesOnlyItsTaggedCandidates() throws Exception {
        LocalSavedTag tag = seed(5);
        empty(101);
        empty(102);
        empty(104);
        TLRPC.TL_messages_deleteHistory request = history(0);
        request.flags = 12;
        request.min_date = 1700000102;
        request.max_date = 1700000104;
        main(() -> controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> controller.getTags().get(0).messageCount == 3);
        assertEquals(Collections.singletonList(new ArrayList<>(Arrays.asList(102, 103, 104))), source.requests);
        assertEquals(Arrays.asList(101, 103, 105), this.<ArrayList<Integer>>await(callback -> storage.loadHistoryIds(Integer.MAX_VALUE, 0, 0, callback)));
        assertEquals(3, count(tag.id));
    }

    @Test
    public void t7_02_unboundedClearUsesSnapshotAndKeepsNewCollection() throws Exception {
        LocalSavedTag tag = seed(3);
        empty(101);
        empty(102);
        source.hold = true;
        main(() -> controller.onDeleteResponse(USER, history(Integer.MAX_VALUE), new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> source.requests.size() == 1);
        MessageObject added = new MessageObject(0, message(104), true, false);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(added), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        main(() -> source.replies.get(1).run());
        until(() -> controller.getTags().get(0).messageCount == 2);
        assertEquals(Arrays.asList(103, 104), this.<ArrayList<Integer>>await(callback -> storage.loadHistoryIds(Integer.MAX_VALUE, 0, 0, callback)));
        assertEquals(Arrays.asList(101, 102, 103), source.requests.get(0));
    }

    @Test
    public void t7_02_sourceHistoryChecksAllTaggedIdsInBoundedRequests() throws Exception {
        LocalSavedTag tag = seed(203);
        for (int id = 102; id <= 302; id += 2) {
            empty(id);
        }
        TLRPC.TL_messages_deleteSavedHistory request = new TLRPC.TL_messages_deleteSavedHistory();
        request.peer = new TLRPC.TL_inputPeerUser();
        request.peer.user_id = USER + 1;
        request.max_id = 303;
        request.parent_peer = new TLRPC.TL_inputPeerChannel();
        main(() -> controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        assertEquals(203, count(tag.id));
        assertTrue(source.requests.isEmpty());
        request.parent_peer = null;
        main(() -> controller.onDeleteResponse(USER, request, new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> controller.getTags().get(0).messageCount == 102 && source.requests.size() == 3);
        assertEquals(100, source.requests.get(0).size());
        assertEquals(100, source.requests.get(1).size());
        assertEquals(3, source.requests.get(2).size());
        ArrayList<Integer> remaining = await(callback -> storage.loadHistoryIds(Integer.MAX_VALUE, 0, 0, callback));
        assertEquals(102, remaining.size());
        for (int id : remaining) {
            assertEquals(1, id % 2);
        }
    }

    @Test
    public void t7_03_missingCacheNetworkFailureAndOmissionsPreserveRelations() throws Exception {
        LocalSavedTag tag = seed(2);
        source.failure = new IOException("测试核实删除时断网");
        main(() -> controller.onDeleteResponse(USER, history(0), new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> source.requests.size() == 1);
        assertEquals(2, count(tag.id));
        source.failure = null;
        empty(101);
        source.remote.remove(102);
        main(() -> controller.onDeleteResponse(USER, history(0), new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> controller.getTags().get(0).messageCount == 1);
        assertEquals(1, count(tag.id));
        empty(102);
        main(() -> controller.onDeleteResponse(USER, history(0), new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> controller.getTags().get(0).messageCount == 0);
        assertEquals(0, count(tag.id));
    }

    @Test
    public void t7_04_realCacheClearAndDatabaseRecreationKeepTagsAndReloadBodies() throws Exception {
        LocalSavedTag tag = seed(3);
        ArrayList<TLRPC.Message> messages = new ArrayList<>();
        for (int id = 101; id <= 103; id++) {
            messages.add(source.remote.get(id));
        }
        MessagesStorage cache = MessagesStorage.getInstance(0);
        main(() -> cache.putMessages(messages, true, true, false, 0, false, 0, 0));
        cacheTask(() -> cache.getDatabase().executeFast("REPLACE INTO dialogs(did, last_mid, last_mid_i) VALUES(" + USER + ",103,103)").stepThis().dispose());
        main(cache::clearLocalDatabase);
        cacheTask(() -> {});
        TLRPC.messages_Messages cleared = await(callback -> cache.getMessagesByIds(USER, new ArrayList<>(Arrays.asList(101, 102, 103)), callback));
        assertEquals(1, cleared.messages.size());
        assertEquals(103, cleared.messages.get(0).id);
        assertEquals(3, count(tag.id));
        main(() -> cache.cleanup(false));
        cacheTask(() -> {});
        cleared = await(callback -> cache.getMessagesByIds(USER, new ArrayList<>(Arrays.asList(101, 102, 103)), callback));
        assertTrue(cleared.messages.isEmpty());
        assertTrue(storage.getDatabaseFile().isFile());
        source.realCache = true;
        AtomicReference<LocalSavedTagsController.FilterSession> session = new AtomicReference<>();
        main(() -> {
            session.set(controller.createFilterSession(900, tag.id, Collections.emptyList(), () -> {}));
            session.get().loadMore();
        });
        until(() -> !session.get().isLoading());
        assertEquals(3, session.get().getMessages().size());
        assertEquals(3, count(tag.id));
        assertEquals(1, source.requests.size());
    }

    @Test
    public void t7_03_editedTaggedResultKeepsItsRelationAndStableIdentity() throws Exception {
        LocalSavedTag tag = seed(1);
        AtomicReference<LocalSavedTagsController.FilterSession> session = new AtomicReference<>();
        main(() -> {
            session.set(controller.createFilterSession(902, tag.id, Collections.emptyList(), () -> {}));
            session.get().loadMore();
        });
        until(() -> !session.get().isLoading());
        MessageObject previous = session.get().getMessages().get(0);
        previous.stableId = 987;
        TLRPC.Message edited = message(101);
        edited.message = "编辑后的收藏正文";
        MessageObject replacement = new MessageObject(0, edited, true, false);
        main(() -> session.get().replaceMessages(Collections.singletonList(replacement)));
        assertSame(replacement, session.get().getMessages().get(0));
        assertEquals(987, replacement.stableId);
        assertEquals(1, count(tag.id));
        assertEquals(previous.messageOwner.date, replacement.messageOwner.date);
        assertEquals(1, source.requests.size());
    }

    @Test
    public void t7_03_untaggedEditsRefreshVisibleAndBufferedMessages() throws Exception {
        for (int id = 101; id <= 200; id++) {
            source.history.add(message(id));
        }
        AtomicReference<LocalSavedTagsController.FilterSession> session = new AtomicReference<>();
        main(() -> {
            session.set(controller.createFilterSession(903, LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {}));
            session.get().loadMore();
        });
        until(() -> !session.get().isLoading());
        ArrayList<MessageObject> edits = new ArrayList<>();
        for (int id : new int[]{101, 200}) {
            TLRPC.Message edited = message(id);
            edited.message = "编辑后的收藏 " + id;
            edits.add(new MessageObject(0, edited, true, false));
        }
        TLRPC.Message foreign = message(199);
        foreign.peer_id = new TLRPC.TL_peerUser();
        foreign.peer_id.user_id = USER + 1;
        foreign.message = "不能覆盖收藏的其他会话正文";
        edits.add(new MessageObject(0, foreign, true, false));
        main(() -> session.get().replaceMessages(edits));
        assertEquals("编辑后的收藏 200", session.get().getMessages().get(0).messageOwner.message);
        assertEquals("收藏样本 199", session.get().getMessages().get(1).messageOwner.message);
        main(session.get()::loadMore);
        until(() -> !session.get().isLoading());
        assertEquals(100, session.get().getMessages().size());
        assertEquals("编辑后的收藏 101", session.get().getMessages().get(99).messageOwner.message);
        assertTrue(session.get().isEndReached());
        assertEquals(1, source.historyOffsets.size());
    }

    @Test
    public void t7_05_logoutCancelsDeletionChecksAndReusedSlotKeepsBothAccounts() throws Exception {
        LocalSavedTag tag = seed(2);
        source.hold = true;
        empty(101);
        empty(102);
        main(() -> controller.onDeleteResponse(USER, history(0), new TLRPC.TL_messages_affectedHistory(), null));
        until(() -> source.requests.size() == 1);
        main(() -> {
            MessagesController.getInstance(0).cleanup();
            UserConfig.getInstance(0).clearConfig();
            user(USER + 1);
        });
        LocalSavedTagsStorage otherStorage = new LocalSavedTagsStorage(root, USER + 1, false);
        stores.add(otherStorage);
        LocalSavedTagsController other = new LocalSavedTagsController(0, otherStorage);
        controllers.add(other);
        main(() -> install(other));
        this.<LocalSavedTag>await(callback -> other.createTag("另一个账号", callback));
        main(() -> {
            source.replies.get(1).run();
            controller.onDeleteResponse(USER, history(102), new TLRPC.TL_messages_affectedHistory(), null);
        });
        assertFalse(controller.isActive());
        assertEquals(Collections.singletonList(1), source.cancelled);
        LocalSavedTagsStorage restored = new LocalSavedTagsStorage(root, USER, false);
        stores.add(restored);
        ArrayList<LocalSavedTag> original = await(restored::loadTags);
        assertEquals(2, original.get(0).messageCount);
        assertEquals(tag.id, original.get(0).id);
        main(() -> {
            other.cleanup();
            user(USER);
        });
        LocalSavedTagsController relogged = new LocalSavedTagsController(0, restored);
        controllers.add(relogged);
        assertEquals(2, this.<ArrayList<LocalSavedTag>>await(relogged::loadTags).get(0).messageCount);
        LocalSavedTagsStorage otherRestored = new LocalSavedTagsStorage(root, USER + 1, false);
        stores.add(otherRestored);
        assertEquals("另一个账号", this.<ArrayList<LocalSavedTag>>await(otherRestored::loadTags).get(0).name);
    }

    @Test
    public void t7_06_standardAndPremiumAccountsUseTheSameLocalOperations() throws Exception {
        LocalSavedTag tag = seed(1);
        source.history.add(source.remote.get(101));
        File database = storage.getDatabaseFile();
        for (boolean premium : new boolean[]{false, true}) {
            main(() -> UserConfig.getInstance(0).getCurrentUser().premium = premium);
            assertEquals(premium, UserConfig.getInstance(0).isPremium());
            this.<LocalSavedTag>await(callback -> controller.renameTag(tag.id, premium ? "高级账号本地标签" : "普通账号本地标签", callback));
            for (long tagId : new long[]{tag.id, LocalSavedTagsController.UNTAGGED}) {
                AtomicReference<LocalSavedTagsController.FilterSession> session = new AtomicReference<>();
                main(() -> {
                    session.set(controller.createFilterSession(901, tagId, Collections.emptyList(), () -> {}));
                    session.get().loadMore();
                });
                until(() -> !session.get().isLoading());
                assertEquals(tagId == tag.id ? 1 : 0, session.get().getMessages().size());
                assertTrue(session.get().isEndReached());
                main(session.get()::cancel);
            }
            assertEquals(database, storage.getDatabaseFile());
            assertEquals(1, count(tag.id));
        }
    }

    private LocalSavedTag seed(int count) throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("生命周期", callback));
        SparseIntArray dates = new SparseIntArray();
        for (int id = 101; id <= 100 + count; id++) {
            TLRPC.Message message = message(id);
            source.remote.put(id, message);
            dates.put(id, message.date);
        }
        this.<Void>await(callback -> storage.applyTags(dates, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        this.<ArrayList<LocalSavedTag>>await(controller::loadTags);
        return tag;
    }

    private TLRPC.Message message(int id) {
        TLRPC.Message message = LocalSavedTagsFilterTest.message(id, USER);
        message.date = 1700000000 + id;
        return message;
    }

    private void empty(int id) {
        TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
        empty.id = id;
        source.remote.put(id, empty);
    }

    private TLRPC.TL_messages_deleteHistory history(int maxId) {
        TLRPC.TL_messages_deleteHistory request = new TLRPC.TL_messages_deleteHistory();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.max_id = maxId;
        return request;
    }

    private int count(long tagId) throws Exception {
        ArrayList<LocalSavedTag> tags = await(controller::loadTags);
        return tags.stream().filter(tag -> tag.id == tagId).findFirst().get().messageCount;
    }

    private void user(long id) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "生命周期合成账号";
        UserConfig.getInstance(0).setCurrentUser(user);
        MessagesController.getInstance(0).putUser(user, false);
    }

    private void install(LocalSavedTagsController value) {
        try {
            Field field = MessagesController.class.getDeclaredField("localSavedTagsController");
            field.setAccessible(true);
            field.set(MessagesController.getInstance(0), value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void until(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        boolean[] done = new boolean[1];
        do {
            main(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        fail("生命周期状态等待超时");
    }

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        main(() -> action.accept((result, failure) -> {
            value.set(result);
            error.set(failure);
            done.countDown();
        }));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return value.get();
    }

    private interface CacheAction {
        void run() throws Exception;
    }

    private void cacheTask(CacheAction action) throws Exception {
        this.<Void>await(callback -> MessagesStorage.getInstance(0).getStorageQueue().postRunnable(() -> {
            Exception failure = null;
            try {
                action.run();
            } catch (Exception error) {
                failure = error;
            }
            Exception result = failure;
            AndroidUtilities.runOnUIThread(() -> callback.run(null, result));
        }));
    }

    private void delete(File file) throws Exception {
        File path = file.getCanonicalFile();
        assertTrue(path.equals(root) || path.getPath().startsWith(root.getPath() + File.separator));
        File[] children = path.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        assertTrue(path.delete());
    }
}
