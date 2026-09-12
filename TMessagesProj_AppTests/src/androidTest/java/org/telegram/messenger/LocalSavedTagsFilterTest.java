package org.telegram.messenger;

import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsFilterTest {
    static final long USER = 4294967411L;
    private LocalSavedTagsStorage storage;
    private LocalSavedTagsController controller;
    private Source source;
    private File root;

    static class Source implements LocalSavedTagsController.MessageSource {
        final SparseArray<TLRPC.Message> cached = new SparseArray<>();
        final SparseArray<TLRPC.Message> remote = new SparseArray<>();
        final ArrayList<ArrayList<Integer>> requests = new ArrayList<>();
        final ArrayList<Integer> cancelled = new ArrayList<>();
        final SparseArray<Runnable> replies = new SparseArray<>();
        final ArrayList<TLRPC.Message> history = new ArrayList<>();
        final ArrayList<Integer> historyOffsets = new ArrayList<>();
        Exception historyFailure;
        int failHistoryAt;
        Exception failure;
        boolean hold;
        boolean realCache;
        long userId = USER;

        @Override
        public void loadCached(ArrayList<Integer> ids, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            if (realCache) {
                MessagesStorage.getInstance(0).getMessagesByIds(userId, ids, callback);
            } else {
                TLRPC.TL_messages_messages result = response(cached, ids);
                AndroidUtilities.runOnUIThread(() -> callback.run(result, null), 1);
            }
        }

        @Override
        public int loadRemote(ArrayList<Integer> ids, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            assertTrue(ids.size() <= 100);
            assertFalse(ids.isEmpty());
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            requests.add(new ArrayList<>(ids));
            int request = requests.size();
            Exception error = failure;
            TLRPC.messages_Messages result = response(remote, ids);
            Runnable reply = () -> callback.run(error == null ? result : null, error);
            replies.put(request, reply);
            if (!hold) {
                AndroidUtilities.runOnUIThread(reply, 1);
            }
            return request;
        }

        @Override
        public void cancel(int requestId) {
            cancelled.add(requestId);
        }

        @Override
        public int loadHistory(TLRPC.TL_messages_getHistory request, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            assertEquals(100, request.limit);
            assertTrue(request.peer instanceof TLRPC.TL_inputPeerSelf || request.peer.user_id == userId);
            historyOffsets.add(request.offset_id);
            ArrayList<TLRPC.Message> available = new ArrayList<>();
            for (TLRPC.Message message : history) {
                if (request.offset_id == 0 || message.id < request.offset_id) {
                    available.add(message);
                }
            }
            available.sort((a, b) -> Integer.compare(b.id, a.id));
            TLRPC.messages_Messages result = available.size() > request.limit
                    ? new TLRPC.TL_messages_messagesSlice() : new TLRPC.TL_messages_messages();
            result.messages.addAll(available.subList(0, Math.min(request.limit, available.size())));
            int requestId = 10000 + historyOffsets.size();
            Exception error = failHistoryAt == 0 || failHistoryAt == historyOffsets.size() ? historyFailure : null;
            Runnable reply = () -> callback.run(error == null ? result : null, error);
            replies.put(requestId, reply);
            if (!hold) {
                AndroidUtilities.runOnUIThread(reply, 1);
            }
            return requestId;
        }

        @Override
        public void loadCachedHistory(int beforeId, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            if (realCache) {
                MessagesStorage.getInstance(0).getCachedSavedHistory(userId, beforeId, callback);
                return;
            }
            ArrayList<Integer> ids = new ArrayList<>();
            for (int i = 0; i < cached.size(); i++) {
                if (beforeId == 0 || cached.keyAt(i) < beforeId) {
                    ids.add(cached.keyAt(i));
                }
            }
            ids.sort(Collections.reverseOrder());
            TLRPC.messages_Messages result = response(cached, ids.subList(0, Math.min(ids.size(), 100)));
            AndroidUtilities.runOnUIThread(() -> callback.run(result, null), 1);
        }

        private TLRPC.TL_messages_messages response(SparseArray<TLRPC.Message> data, List<Integer> ids) {
            TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
            for (int id : ids) {
                if (data.get(id) != null) {
                    result.messages.add(data.get(id));
                }
            }
            Collections.reverse(result.messages);
            return result;
        }
    }

    @Before
    public void setUp() throws Exception {
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertFalse(UserConfig.getInstance(0).isClientActivated());
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(), "local_saved_tags_filter_tests/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        main(() -> {
            user(USER);
            storage = new LocalSavedTagsStorage(root, USER, false);
            source = new Source();
            controller = new LocalSavedTagsController(0, storage, source);
        });
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            main(controller::cleanup);
            CountDownLatch closed = new CountDownLatch(1);
            storage.close(closed::countDown);
            assertTrue(closed.await(15, TimeUnit.SECONDS));
        }
        this.<Void>await(callback -> MessagesStorage.getInstance(0).getStorageQueue().postRunnable(() -> {
            Exception error = null;
            try {
                MessagesStorage.getInstance(0).getDatabase().executeFast("DELETE FROM messages_v2 WHERE uid IN (" + USER + "," + (USER + 1) + ")").stepThis().dispose();
            } catch (Exception e) {
                error = e;
            }
            Exception failure = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(null, failure));
        }));
        main(() -> UserConfig.getInstance(0).clearConfig());
        if (root != null) {
            delete(root);
        }
    }

    @Test
    public void t5_01_and_02_all123MessagesKeepIndexOrderAcrossCacheAndRemote() throws Exception {
        LocalSavedTag tag = seed(123, "学习");
        for (int id = 10001; id <= 10040; id++) {
            source.cached.put(id, source.remote.get(id));
        }
        MessageObject current = new MessageObject(0, source.remote.get(10123), true, false);
        LocalSavedTagsController.FilterSession session = session(tag.id, Collections.singletonList(current), () -> {});
        load(session);
        assertEquals(50, session.getMessages().size());
        assertSame(current, session.getMessages().get(0));
        assertFalse(session.isEndReached());
        load(session);
        assertEquals(100, session.getMessages().size());
        load(session);
        assertTrue(session.isEndReached());
        assertEquals(123, session.getMessages().size());
        for (int i = 0; i < 123; i++) {
            assertEquals(10123 - i, session.getMessages().get(i).getId());
        }
        HashSet<Integer> remoteIds = new HashSet<>();
        for (ArrayList<Integer> request : source.requests) {
            remoteIds.addAll(request);
        }
        assertEquals(82, remoteIds.size());
        assertFalse(remoteIds.contains(10123));
        assertFalse(remoteIds.contains(10020));
    }

    @Test
    public void t5_02_realCacheReadsBatchAndRestoresLocalFields() throws Exception {
        ArrayList<TLRPC.Message> data = new ArrayList<>();
        for (int id = 10001; id <= 10123; id++) {
            TLRPC.Message message = message(id, USER);
            message.attachPath = "/data/local/tmp/收藏样本_" + id;
            message.dialog_id = USER;
            message.unread = false;
            data.add(message);
        }
        TLRPC.Message foreign = message(10001, USER + 1);
        foreign.dialog_id = USER + 1;
        foreign.message = "其他会话的同编号";
        data.add(foreign);
        MessagesStorage cache = MessagesStorage.getInstance(0);
        main(() -> cache.putMessages(data, true, true, false, 0, false, 0, 0));
        ArrayList<Integer> ids = new ArrayList<>();
        for (int id = 10001; id <= 10124; id++) {
            ids.add(id);
        }
        TLRPC.messages_Messages result = await(callback -> cache.getMessagesByIds(USER, ids, callback));
        assertEquals(123, result.messages.size());
        HashSet<Integer> found = new HashSet<>();
        for (TLRPC.Message message : result.messages) {
            assertEquals(USER, message.dialog_id);
            assertEquals(USER, message.peer_id.user_id);
            assertEquals("收藏样本 " + message.id, message.message);
            assertFalse(message.unread);
            assertTrue(found.add(message.id));
        }
        LocalSavedTag tag = seed(123, "缓存筛选");
        source.realCache = true;
        source.failure = new IOException("不应请求网络");
        LocalSavedTagsController.FilterSession session = session(tag.id, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(50, session.getMessages().size());
        assertTrue(source.requests.isEmpty());
    }

    @Test
    public void t5_03_endOfIndexDoesNotDropPendingBodiesAfterFailureOrOmission() throws Exception {
        LocalSavedTag tag = seed(1, "保留待补");
        source.failure = new IOException("测试离线");
        LocalSavedTagsController.FilterSession session = session(tag.id, Collections.emptyList(), () -> {});
        load(session);
        assertNotNull(session.getError());
        assertFalse(session.isEndReached());
        assertEquals(1, session.getPendingCount());
        assertEquals(1, count(tag.id));
        source.failure = null;
        source.remote.clear();
        load(session);
        assertFalse(session.isEndReached());
        assertNotNull(session.getError());
        assertEquals(1, count(tag.id));
        source.remote.put(10001, message(10001, USER));
        load(session);
        assertEquals(10001, session.getMessages().get(0).getId());
        assertTrue(session.isEndReached());
        assertEquals(0, session.getPendingCount());
    }

    @Test
    public void t5_04_onlyExplicitEmptyOfRequestedSavedMessageRemovesRelation() throws Exception {
        LocalSavedTag tag = seed(3, "远端确认");
        TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
        empty.id = 10001;
        source.remote.put(10001, empty);
        source.remote.put(10002, message(10002, USER + 1));
        LocalSavedTagsController.FilterSession session = session(tag.id, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(1, session.getMessages().size());
        assertEquals(10003, session.getMessages().get(0).getId());
        assertEquals(2, count(tag.id));
        assertEquals(1, session.getPendingCount());
        assertNotNull(session.getError());
        source.remote.put(10002, message(10002, USER));
        load(session);
        assertEquals(2, session.getMessages().size());
        assertTrue(session.isEndReached());
        assertEquals(10002, session.getMessages().get(1).getId());
    }

    @Test
    public void t5_04_confirmedMissingCleanupIsAtomicAndRetryable() throws Exception {
        LocalSavedTag tag = seed(2, "清理事务");
        for (int id = 10001; id <= 10002; id++) {
            TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
            empty.id = id;
            source.remote.put(id, empty);
        }
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        LocalSavedTagsController.FilterSession session = session(tag.id, Collections.emptyList(), () -> {});
        try {
            db.executeFast("CREATE TRIGGER fail_remove BEFORE DELETE ON local_saved_message_tags WHEN OLD.message_id = 10002 BEGIN SELECT RAISE(ABORT, '测试清理失败'); END").stepThis().dispose();
            load(session);
            assertNotNull(session.getError());
            assertEquals(2, count(tag.id));
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_remove").stepThis().dispose();
            db.close();
        }
        load(session);
        assertTrue(session.isEndReached());
        assertTrue(session.getMessages().isEmpty());
        assertEquals(0, count(tag.id));
        assertEquals(1, controller.getTags().size());
    }

    @Test
    public void t5_05_albumFiltersRetainOnlyTheirMembersWithoutChangingGroupId() throws Exception {
        LocalSavedTag a = seed(4, "相册甲");
        LocalSavedTag b = await(callback -> controller.createTag("相册乙", callback));
        for (int id = 10001; id <= 10004; id++) {
            source.remote.get(id).grouped_id = 800;
        }
        SparseIntArray dates = new SparseIntArray();
        dates.put(10002, source.remote.get(10002).date);
        this.<Void>await(callback -> storage.applyTags(dates, Collections.singletonList(b.id), Collections.emptyList(), callback));
        LocalSavedTagsController.FilterSession first = session(a.id, Collections.emptyList(), () -> {});
        LocalSavedTagsController.FilterSession second = session(b.id, Collections.emptyList(), () -> {});
        load(first);
        load(second);
        assertEquals(4, first.getMessages().size());
        assertEquals(1, second.getMessages().size());
        assertEquals(10002, second.getMessages().get(0).getId());
        for (MessageObject message : first.getMessages()) {
            assertEquals(800, message.messageOwner.grouped_id);
        }
        assertEquals(800, second.getMessages().get(0).messageOwner.grouped_id);
    }

    @Test
    public void t5_06_cancelledQueryCannotPublishItsLateResponse() throws Exception {
        LocalSavedTag tag = seed(2, "延迟响应");
        source.hold = true;
        AtomicInteger changes = new AtomicInteger();
        LocalSavedTagsController.FilterSession old = session(tag.id, Collections.emptyList(), changes::incrementAndGet);
        main(old::loadMore);
        until(() -> source.requests.size() == 1);
        main(old::cancel);
        int before = changes.get();
        main(() -> source.replies.get(1).run());
        assertEquals(before, changes.get());
        assertTrue(old.getMessages().isEmpty());
        assertEquals(Collections.singletonList(1), source.cancelled);
        source.hold = false;
        LocalSavedTagsController.FilterSession next = session(tag.id, Collections.emptyList(), () -> {});
        load(next);
        assertEquals(2, next.getMessages().size());
    }

    @Test
    public void t5_07_twoPagesAndAccountReuseKeepIndependentSessions() throws Exception {
        LocalSavedTag tag = seed(123, "独立页面");
        LocalSavedTagsController.FilterSession a = session(tag.id, Collections.emptyList(), () -> {});
        LocalSavedTagsController.FilterSession b = session(tag.id, Collections.emptyList(), () -> {});
        load(a);
        load(b);
        load(a);
        assertEquals(100, a.getMessages().size());
        assertEquals(50, b.getMessages().size());
        main(a::cancel);
        load(b);
        assertEquals(100, b.getMessages().size());
        source.hold = true;
        main(b::loadMore);
        until(() -> source.requests.size() == 5);
        main(() -> user(USER + 1));
        main(() -> source.replies.get(5).run());
        assertEquals(100, b.getMessages().size());
        main(controller::cleanup);
        assertTrue(b.isCancelled());
    }

    @Test
    public void t5_08_refreshAfterCommittedEditUsesCurrentRelations() throws Exception {
        LocalSavedTag tag = seed(2, "原名");
        LocalSavedTagsController.FilterSession old = session(tag.id, Collections.emptyList(), () -> {});
        load(old);
        this.<LocalSavedTag>await(callback -> controller.renameTag(tag.id, "新名", callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(old.getMessages().get(0)), Collections.emptyList(), Collections.singletonList(tag.id), callback));
        main(old::cancel);
        LocalSavedTagsController.FilterSession next = session(tag.id, Collections.emptyList(), () -> {});
        load(next);
        assertEquals(1, next.getMessages().size());
        assertEquals(10001, next.getMessages().get(0).getId());
        assertEquals("新名", controller.getTags().get(0).name);
        this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
        assertTrue(controller.getTags().isEmpty());
    }

    @Test
    public void t6_01_and_05_bufferPreservesSecondPageAndDefersEndUntilConsumed() throws Exception {
        history(100);
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(50, session.getMessages().size());
        assertFalse(session.isEndReached());
        assertEquals(Collections.singletonList(0), source.historyOffsets);
        load(session);
        assertEquals(100, session.getMessages().size());
        assertTrue(session.isEndReached());
        assertEquals(1, source.historyOffsets.size());
        for (int i = 0; i < 100; i++) {
            assertEquals(10100 - i, session.getMessages().get(i).getId());
        }
        assertTrue(source.requests.isEmpty());
    }

    @Test
    public void t6_02_fiveTaggedBatchesStillAllowFinding150OlderMessages() throws Exception {
        history(650);
        markHistory(id -> id > 10150);
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertTrue(session.getMessages().isEmpty());
        assertTrue(session.isScanLimitReached());
        assertFalse(session.isEndReached());
        assertEquals(Arrays.asList(0, 10551, 10451, 10351, 10251), source.historyOffsets);
        load(session);
        assertEquals(50, session.getMessages().size());
        assertEquals(6, source.historyOffsets.size());
        load(session);
        assertEquals(100, session.getMessages().size());
        assertEquals(6, source.historyOffsets.size());
        load(session);
        assertTrue(session.isEndReached());
        assertEquals(150, session.getMessages().size());
        assertEquals(7, source.historyOffsets.size());
        for (int i = 0; i < 150; i++) {
            assertEquals(10150 - i, session.getMessages().get(i).getId());
        }
    }

    @Test
    public void t6_03_sparseMatchesUseRawBatchCursorWithoutOmissions() throws Exception {
        history(241);
        markHistory(id -> id % 3 != 0);
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(50, session.getMessages().size());
        load(session);
        assertTrue(session.isEndReached());
        ArrayList<Integer> expected = new ArrayList<>();
        for (int id = 10241; id >= 10001; id--) {
            if (id % 3 == 0) {
                expected.add(id);
            }
        }
        assertEquals(expected.size(), session.getMessages().size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals((int) expected.get(i), session.getMessages().get(i).getId());
        }
        assertEquals(Arrays.asList(0, 10142, 10042), source.historyOffsets);
    }

    @Test
    public void t6_04_failureDoesNotAdvanceTheSecondBatchCursor() throws Exception {
        history(260);
        markHistory(id -> id > 10110);
        source.failHistoryAt = 2;
        source.historyFailure = new IOException("测试第二批失败");
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertNotNull(session.getError());
        assertFalse(session.isEndReached());
        assertEquals(Arrays.asList(0, 10161), source.historyOffsets);
        source.historyFailure = null;
        load(session);
        assertEquals(50, session.getMessages().size());
        assertEquals(Arrays.asList(0, 10161, 10161), source.historyOffsets);
        load(session);
        load(session);
        assertEquals(110, session.getMessages().size());
        assertTrue(session.isEndReached());
        for (int i = 0; i < 110; i++) {
            assertEquals(10110 - i, session.getMessages().get(i).getId());
        }
    }

    @Test
    public void t6_04_realOfflineCacheIsIncompleteAndRetryStartsAtSameCursor() throws Exception {
        history(120);
        source.realCache = true;
        source.historyFailure = new IOException("测试纯离线");
        ArrayList<TLRPC.Message> cached = new ArrayList<>(source.history.subList(117, 120));
        main(() -> MessagesStorage.getInstance(0).putMessages(cached, true, true, false, 0, false, 0, 0));
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(3, session.getMessages().size());
        assertTrue(session.isCacheOnly());
        assertFalse(session.isEndReached());
        source.historyFailure = null;
        load(session);
        assertEquals(Arrays.asList(0, 0), source.historyOffsets);
        assertEquals(50, session.getMessages().size());
        assertFalse(session.isCacheOnly());
        assertNull(session.getError());
        load(session);
        load(session);
        assertEquals(120, session.getMessages().size());
        assertTrue(session.isEndReached());
    }

    @Test
    public void t6_04_fullOfflinePageStillVerifiesHistoryWhenRetried() throws Exception {
        history(100);
        for (TLRPC.Message message : source.history) {
            source.cached.put(message.id, message);
        }
        source.historyFailure = new IOException("测试离线一整页");
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(session);
        assertEquals(50, session.getMessages().size());
        assertTrue(session.isCacheOnly());
        source.historyFailure = null;
        load(session);
        assertEquals(50, session.getMessages().size());
        assertEquals(Arrays.asList(0, 0), source.historyOffsets);
        assertFalse(session.isEndReached());
        load(session);
        assertEquals(100, session.getMessages().size());
        assertTrue(session.isEndReached());
    }

    @Test
    public void t6_06_refreshExcludesNewTagsAndFindsNewTopMessage() throws Exception {
        history(3);
        LocalSavedTag tag = await(callback -> controller.createTag("整理", callback));
        LocalSavedTagsController.FilterSession first = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(first);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(first.getMessages().get(0)), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        main(first::cancel);
        LocalSavedTagsController.FilterSession second = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(second);
        assertEquals(2, second.getMessages().size());
        assertEquals(10002, second.getMessages().get(0).getId());
        source.history.add(message(10004, USER));
        main(second::cancel);
        LocalSavedTagsController.FilterSession third = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), () -> {});
        load(third);
        assertEquals(3, third.getMessages().size());
        assertEquals(10004, third.getMessages().get(0).getId());
    }

    @Test
    public void t6_06_and_07_cancelledHistoryDoesNotPublishOrLoadBodiesSeparately() throws Exception {
        history(10);
        source.hold = true;
        AtomicInteger changed = new AtomicInteger();
        LocalSavedTagsController.FilterSession session = session(LocalSavedTagsController.UNTAGGED, Collections.emptyList(), changed::incrementAndGet);
        main(session::loadMore);
        assertEquals(Collections.singletonList(0), source.historyOffsets);
        main(session::cancel);
        int count = changed.get();
        main(() -> source.replies.get(10001).run());
        assertEquals(count, changed.get());
        assertTrue(session.getMessages().isEmpty());
        assertEquals(Collections.singletonList(10001), source.cancelled);
        assertTrue(source.requests.isEmpty());
    }

    private void history(int count) {
        for (int id = 10001; id <= 10000 + count; id++) {
            source.history.add(message(id, USER));
        }
    }

    private void markHistory(java.util.function.IntPredicate predicate) throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("已经打标", callback));
        SparseIntArray dates = new SparseIntArray();
        for (TLRPC.Message message : source.history) {
            if (predicate.test(message.id)) {
                dates.put(message.id, message.date);
            }
        }
        this.<Void>await(callback -> storage.applyTags(dates, Collections.singletonList(tag.id), Collections.emptyList(), callback));
    }

    private LocalSavedTag seed(int count, String name) throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag(name, callback));
        SparseIntArray dates = new SparseIntArray();
        for (int id = 10001; id < 10001 + count; id++) {
            TLRPC.Message message = message(id, USER);
            source.remote.put(id, message);
            dates.put(id, message.date);
        }
        this.<Void>await(callback -> storage.applyTags(dates, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        return tag;
    }

    static TLRPC.Message message(int id, long userId) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = 1700000000 + id / 7;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = userId;
        message.from_id = message.peer_id;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.out = true;
        message.message = "收藏样本 " + id;
        return message;
    }

    private int count(long tagId) throws Exception {
        ArrayList<LocalSavedTag> tags = await(controller::loadTags);
        return tags.stream().filter(tag -> tag.id == tagId).findFirst().get().messageCount;
    }

    private LocalSavedTagsController.FilterSession session(long tagId, List<MessageObject> known, Runnable changed) {
        AtomicReference<LocalSavedTagsController.FilterSession> result = new AtomicReference<>();
        main(() -> result.set(controller.createFilterSession(800, tagId, known, changed)));
        return result.get();
    }

    private void load(LocalSavedTagsController.FilterSession session) throws Exception {
        main(session::loadMore);
        until(() -> !session.isLoading());
    }

    private void until(BooleanSupplier condition) throws Exception {
        long end = System.currentTimeMillis() + 15000;
        AtomicReference<Boolean> done = new AtomicReference<>(false);
        while (System.currentTimeMillis() < end) {
            main(() -> done.set(condition.getAsBoolean()));
            if (done.get()) {
                return;
            }
            Thread.sleep(10);
        }
        fail("筛选状态等待超时");
    }

    private void user(long id) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "筛选合成账号";
        UserConfig.getInstance(0).setCurrentUser(user);
        MessagesController.getInstance(0).putUser(user, false);
    }

    private void main(Runnable action) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(action);
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        main(() -> action.accept((value, failure) -> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            result.set(value);
            error.set(failure);
            latch.countDown();
        }));
        assertTrue(latch.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }

    private void delete(File file) throws Exception {
        File path = file.getCanonicalFile();
        assertTrue(path.equals(root) || path.getPath().startsWith(root.getPath() + File.separator));
        File[] files = path.listFiles();
        if (files != null) {
            for (File child : files) {
                delete(child);
            }
        }
        assertTrue(path.delete());
    }
}
