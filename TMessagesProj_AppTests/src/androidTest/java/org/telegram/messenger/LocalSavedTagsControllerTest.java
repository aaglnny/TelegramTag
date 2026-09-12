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
import org.telegram.SQLite.SQLiteException;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.LocalSavedTagsLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsControllerTest {

    private static final long USER_A = 4294967311L;
    private static final long USER_B = 4294967333L;
    private final ArrayList<LocalSavedTagsStorage> stores = new ArrayList<>();
    private final ArrayList<LocalSavedTagsController> controllers = new ArrayList<>();
    private File root;
    private ReadFailureStorage storage;
    private LocalSavedTagsController controller;
    private NotificationCenter.NotificationCenterDelegate observer;
    private boolean installedUser;

    private static class ReadFailureStorage extends LocalSavedTagsStorage {
        boolean failNextRead;

        ReadFailureStorage(File root, long userId) {
            super(root, userId, false);
        }

        @Override
        public void loadTags(Utilities.Callback2<ArrayList<LocalSavedTag>, Exception> callback) {
            if (failNextRead) {
                failNextRead = false;
                AndroidUtilities.runOnUIThread(() -> callback.run(null, new SQLiteException("测试读取失败")));
            } else {
                super.loadTags(callback);
            }
        }
    }

    private static class Response<T> {
        T value;
        Exception error;
        boolean mainThread;
        final AtomicInteger calls = new AtomicInteger();
    }

    @Before
    public void setUp() throws Exception {
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertFalse("需要无真实登录账号的隔离环境", UserConfig.getInstance(0).isClientActivated());
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "local_saved_tags_controller_tests/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        main(() -> {
            user(USER_A);
            installedUser = true;
            storage = new ReadFailureStorage(root, USER_A);
            stores.add(storage);
            controller = new LocalSavedTagsController(0, storage);
            controllers.add(controller);
        });
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            if (observer != null) {
                NotificationCenter.getInstance(0).removeObserver(observer, NotificationCenter.localSavedTagsUpdated);
            }
            for (LocalSavedTagsController item : controllers) {
                item.cleanup();
            }
        });
        for (LocalSavedTagsStorage item : stores) {
            close(item);
        }
        if (installedUser) {
            main(() -> UserConfig.getInstance(0).clearConfig());
        }
        if (root != null) {
            delete(root);
        }
    }

    @Test
    public void t2_01_crudCountsAndRelations() throws Exception {
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        LocalSavedTag a = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag b = await(callback -> controller.createTag("空标签", callback));
        SparseIntArray dates = new SparseIntArray();
        dates.put(101, 1700000003);
        dates.put(103, 1700000002);
        this.<Void>await(callback -> storage.applyTags(dates, Arrays.asList(a.id), Collections.emptyList(), callback));
        ArrayList<LocalSavedTag> tags = await(controller::loadTags);
        assertEquals(2, tags.size());
        assertEquals(a.id, tags.get(0).id);
        assertEquals(2, tags.get(0).messageCount);
        LocalSavedTag renamed = await(callback -> controller.renameTag(a.id, "课程", callback));
        assertEquals(a.id, renamed.id);
        assertEquals(a.createdAt, renamed.createdAt);
        assertEquals(2, renamed.messageCount);
        this.<Void>await(callback -> controller.deleteTag(a.id, callback));
        tags = await(controller::loadTags);
        assertEquals(1, tags.size());
        assertEquals(b.id, tags.get(0).id);
        assertEquals(0, tags.get(0).messageCount);
        LocalSavedTagsStorage.MessagePage page = await(callback -> storage.loadMessages(a.id, null, 50, callback));
        assertTrue(page.messages.isEmpty());
    }

    @Test
    public void t2_02_invalidAndDuplicateNamesKeepCache() throws Exception {
        this.<LocalSavedTag>await(callback -> controller.createTag("Android", callback));
        for (String value : new String[]{"", " \u3000", String.join("", Collections.nCopies(33, "字"))}) {
            Response<LocalSavedTag> response = call(callback -> controller.createTag(value, callback));
            assertEquals(LocalSavedTagsStorage.TagException.INVALID_NAME, ((LocalSavedTagsStorage.TagException) response.error).code);
        }
        Response<LocalSavedTag> duplicate = call(callback -> controller.createTag(" android ", callback));
        assertEquals(LocalSavedTagsStorage.TagException.NAME_EXISTS, ((LocalSavedTagsStorage.TagException) duplicate.error).code);
        this.<LocalSavedTag>await(callback -> controller.createTag("e\u0301", callback));
        duplicate = call(callback -> controller.createTag("é", callback));
        assertEquals(LocalSavedTagsStorage.TagException.NAME_EXISTS, ((LocalSavedTagsStorage.TagException) duplicate.error).code);
        assertEquals(2, this.<ArrayList<LocalSavedTag>>await(controller::loadTags).size());
    }

    @Test
    public void t2_03_onlyCommittedChangesNotifyOnMainThread() throws Exception {
        AtomicInteger events = new AtomicInteger();
        ArrayList<String> namesInEvents = new ArrayList<>();
        main(() -> {
            observer = (id, account, args) -> {
                events.incrementAndGet();
                if (account == 0 && (Long) args[0] == USER_A && Looper.myLooper() == Looper.getMainLooper()) {
                    namesInEvents.add(controller.getTags().get(0).name);
                }
            };
            NotificationCenter.getInstance(0).addObserver(observer, NotificationCenter.localSavedTagsUpdated);
        });
        LocalSavedTag tag = await(callback -> {
            controller.createTag("已提交", callback);
            assertTrue("提交回调前不能乐观修改缓存", controller.getTags().isEmpty());
            assertEquals(0, events.get());
        });
        assertEquals(1, events.get());
        assertEquals(Collections.singletonList("已提交"), namesInEvents);
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_edit BEFORE UPDATE ON local_saved_tags BEGIN SELECT RAISE(ABORT, '测试写入失败'); END").stepThis().dispose();
            Response<LocalSavedTag> failed = call(callback -> controller.renameTag(tag.id, "不能提交", callback));
            assertTrue(failed.error instanceof SQLiteException);
            assertNull(failed.value);
            assertEquals(1, events.get());
            assertEquals("已提交", controller.getTags().get(0).name);
            db.executeFast("DROP TRIGGER fail_edit").stepThis().dispose();
            this.<LocalSavedTag>await(callback -> controller.renameTag(tag.id, "重试成功", callback));
            assertEquals(2, events.get());
            assertEquals(Arrays.asList("已提交", "重试成功"), namesInEvents);
        } finally {
            db.close();
        }
    }

    @Test
    public void t2_04_readFailureRetainsCacheAndRetries() throws Exception {
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        assertTrue(controller.hasLoadedTags());
        this.<LocalSavedTag>await(callback -> controller.createTag("不能丢失", callback));
        main(() -> storage.failNextRead = true);
        Response<ArrayList<LocalSavedTag>> failed = call(controller::loadTags);
        assertNull(failed.value);
        assertTrue(failed.error instanceof SQLiteException);
        assertSame(failed.error, controller.getLoadError());
        assertEquals("不能丢失", controller.getTags().get(0).name);
        ArrayList<LocalSavedTag> restored = await(controller::loadTags);
        assertEquals("不能丢失", restored.get(0).name);
        assertNull(controller.getLoadError());
    }

    @Test
    public void t2_05_oldCallbackCannotReachReusedAccount() throws Exception {
        this.<LocalSavedTag>await(callback -> controller.createTag("账号甲", callback));
        Response<ArrayList<LocalSavedTag>> old = call(callback -> {
            controller.loadTags(callback);
            controller.cleanup();
            user(USER_B);
        });
        assertTrue(old.error instanceof CancellationException);
        assertNull(old.value);
        assertFalse(controller.isActive());
        assertTrue(controller.getTags().isEmpty());
        LocalSavedTagsStorage storeB = new LocalSavedTagsStorage(root, USER_B, false);
        stores.add(storeB);
        LocalSavedTagsController next = new LocalSavedTagsController(0, storeB);
        controllers.add(next);
        assertTrue(this.<ArrayList<LocalSavedTag>>await(next::loadTags).isEmpty());
        this.<LocalSavedTag>await(callback -> next.createTag("账号乙", callback));
        close(storage);
        LocalSavedTagsStorage restored = new LocalSavedTagsStorage(root, USER_A, false);
        stores.add(restored);
        ArrayList<LocalSavedTag> original = await(restored::loadTags);
        assertEquals(1, original.size());
        assertEquals("账号甲", original.get(0).name);
        assertEquals("账号乙", next.getTags().get(0).name);
    }

    @Test
    public void t2_05_reopenControllerPreservesData() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("重开后仍在", callback));
        main(controller::cleanup);
        close(storage);
        LocalSavedTagsStorage reopened = new LocalSavedTagsStorage(root, USER_A, false);
        stores.add(reopened);
        LocalSavedTagsController next = new LocalSavedTagsController(0, reopened);
        controllers.add(next);
        assertFalse(next.hasLoadedTags());
        ArrayList<LocalSavedTag> tags = await(next::loadTags);
        assertEquals(1, tags.size());
        assertEquals(tag.id, tags.get(0).id);
        assertEquals(tag.name, tags.get(0).name);
    }

    @Test
    public void t2_06_membershipDoesNotGateLocalManagement() throws Exception {
        assertFalse(UserConfig.getInstance(0).isPremium());
        LocalSavedTag tag = await(callback -> controller.createTag("普通账号标签", callback));
        assertFalse(UserConfig.getInstance(0).isPremium());
        main(() -> UserConfig.getInstance(0).getCurrentUser().premium = true);
        this.<LocalSavedTag>await(callback -> controller.renameTag(tag.id, "同一本地功能", callback));
        this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
        assertTrue(UserConfig.getInstance(0).isPremium());
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        main(() -> UserConfig.getInstance(0).getCurrentUser().premium = false);
    }

    @Test
    public void t3_01_singleMessageEditsRollbackAndClear() throws Exception {
        LocalSavedTag a = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag b = await(callback -> controller.createTag("资料", callback));
        LocalSavedTag c = await(callback -> controller.createTag("待处理", callback));
        MessageObject message = message(101, USER_A, "#学习 原始正文");
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Arrays.asList(a.id, b.id), Collections.emptyList(), callback));
        SparseArray<ArrayList<LocalSavedTag>> initial = await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertEquals(2, initial.get(101).size());
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_link BEFORE INSERT ON local_saved_message_tags BEGIN SELECT RAISE(ABORT, '测试关联失败'); END").stepThis().dispose();
            Response<Void> failed = call(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(c.id), Collections.singletonList(a.id), callback));
            assertTrue(failed.error instanceof SQLiteException);
            assertEquals(2, controller.getMessageTags(101).size());
            SparseArray<ArrayList<LocalSavedTag>> unchanged = await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
            assertTrue(unchanged.get(101).stream().anyMatch(tag -> tag.id == a.id));
            assertTrue(unchanged.get(101).stream().anyMatch(tag -> tag.id == b.id));
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_link").stepThis().dispose();
            db.close();
        }
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(c.id), Collections.singletonList(a.id), callback));
        SparseArray<ArrayList<LocalSavedTag>> changed = await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertEquals(2, changed.get(101).size());
        assertFalse(changed.get(101).stream().anyMatch(tag -> tag.id == a.id));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.emptyList(), Arrays.asList(b.id, c.id), callback));
        SparseArray<ArrayList<LocalSavedTag>> empty = await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertTrue(empty.get(101).isEmpty());
        assertEquals("#学习 原始正文", message.messageOwner.message);
        for (LocalSavedTag tag : controller.getTags()) {
            assertEquals(0, tag.messageCount);
        }
    }

    @Test
    public void t3_02_savedCopiesKeepTheirOwnIdsAndDates() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("转发资料", callback));
        MessageObject a = message(101, USER_A, "重复收藏正文");
        MessageObject b = message(102, USER_A, "重复收藏正文");
        a.messageOwner.fwd_from = new TLRPC.TL_messageFwdHeader();
        a.messageOwner.fwd_from.channel_post = 700;
        a.messageOwner.fwd_from.date = 1500000000;
        b.messageOwner.fwd_from = a.messageOwner.fwd_from;
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(a), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102, 700), callback));
        assertEquals(1, mapping.get(101).size());
        assertTrue(mapping.get(102).isEmpty());
        assertTrue(mapping.get(700).isEmpty());
        LocalSavedTagsStorage.MessagePage page = await(callback -> storage.loadMessages(tag.id, null, 50, callback));
        assertEquals(101, page.messages.get(0).messageId);
        assertEquals(a.messageOwner.date, page.messages.get(0).messageDate);
    }

    @Test
    public void t3_02_outsideSavedMessagesCannotPartiallyWrite() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("范围校验", callback));
        MessageObject valid = message(101, USER_A, "收藏");
        MessageObject foreign = message(102, USER_B, "其他会话");
        MessageObject temporary = message(-1, USER_A, "发送中");
        MessageObject sending = message(103, USER_A, "发送中");
        sending.messageOwner.send_state = 1;
        MessageObject scheduled = message(104, USER_A, "定时");
        scheduled.scheduled = true;
        MessageObject service = message(105, USER_A, "系统提示");
        service.messageOwner.action = new TLRPC.TL_messageActionHistoryClear();
        MessageObject otherAccount = message(106, USER_A, "其他账号");
        otherAccount.currentAccount = 1;
        MessageObject separator = message(107, USER_A, "日期");
        separator.isDateObject = true;
        for (MessageObject invalid : Arrays.asList(foreign, temporary, sending, scheduled, service, otherAccount, separator)) {
            assertFalse("范围校验未拒绝：" + invalid.messageOwner.message, controller.canTagMessage(invalid));
            Response<Void> result = call(callback -> controller.applyTags(Arrays.asList(valid, invalid), Collections.singletonList(tag.id), Collections.emptyList(), callback));
            assertTrue(result.error instanceof IllegalArgumentException);
        }
        LocalSavedTagsStorage.MessagePage page = await(callback -> storage.loadMessages(tag.id, null, 50, callback));
        assertTrue(page.messages.isEmpty());
    }

    @Test
    public void t3_04_albumCoverageTracksMembersAndGroupEdits() throws Exception {
        LocalSavedTag a = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag b = await(callback -> controller.createTag("待处理", callback));
        ArrayList<MessageObject> album = new ArrayList<>();
        for (int id = 301; id <= 304; id++) {
            MessageObject message = message(id, USER_A, "相册成员 " + id);
            message.messageOwner.grouped_id = 800;
            album.add(message);
        }
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(album.get(0), album.get(2)), Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(album.get(3)), Collections.singletonList(b.id), Collections.emptyList(), callback));
        this.<SparseArray<ArrayList<LocalSavedTag>>>await(callback -> controller.loadMessageTags(Arrays.asList(301, 302, 303, 304), callback));
        ArrayList<LocalSavedTagsLayout.Tag> displayed = LocalSavedTagsLayout.collect(album, controller);
        assertEquals(2, displayed.size());
        assertEquals(2, displayed.stream().filter(tag -> tag.id == a.id).findFirst().get().count);
        assertEquals(4, displayed.get(0).total);
        this.<Void>await(callback -> controller.applyTags(album, Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<SparseArray<ArrayList<LocalSavedTag>>>await(callback -> controller.loadMessageTags(Arrays.asList(301, 302, 303, 304), callback));
        displayed = LocalSavedTagsLayout.collect(album, controller);
        assertEquals(4, displayed.stream().filter(tag -> tag.id == a.id).findFirst().get().count);
        assertEquals(1, displayed.stream().filter(tag -> tag.id == b.id).findFirst().get().count);
        assertEquals(800, album.get(0).messageOwner.grouped_id);
    }

    @Test
    public void t3_05_renamingInvalidatesLoadedMessageTags() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("旧名称", callback));
        MessageObject message = message(101, USER_A, "正文");
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        this.<SparseArray<ArrayList<LocalSavedTag>>>await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertEquals("旧名称", controller.getMessageTags(101).get(0).name);
        this.<LocalSavedTag>await(callback -> controller.renameTag(tag.id, "新名称", callback));
        this.<SparseArray<ArrayList<LocalSavedTag>>>await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertEquals("新名称", controller.getMessageTags(101).get(0).name);
        this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
        this.<SparseArray<ArrayList<LocalSavedTag>>>await(callback -> controller.loadMessageTags(Collections.singletonList(101), callback));
        assertTrue(controller.getMessageTags(101).isEmpty());
    }

    @Test
    public void t3_08_emptyStorageHasNoPreviewTag() throws Exception {
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102), callback));
        assertTrue(mapping.get(101).isEmpty());
        assertTrue(mapping.get(102).isEmpty());
        assertTrue(controller.getTags().isEmpty());
    }

    private MessageObject message(int id, long dialogId, String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = 1700000000 + Math.max(1, id);
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = dialogId;
        message.from_id = message.peer_id;
        message.out = true;
        message.message = text;
        return new MessageObject(0, message, false, false);
    }

    @Test
    public void t4_01_and_02_appendAndRemovePreserveOtherRelations() throws Exception {
        LocalSavedTag study = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag project = await(callback -> controller.createTag("项目资料", callback));
        LocalSavedTag pending = await(callback -> controller.createTag("待处理", callback));
        MessageObject a = message(101, USER_A, "第一条");
        MessageObject b = message(102, USER_A, "第二条");
        MessageObject c = message(103, USER_A, "第三条");
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(a), Collections.singletonList(study.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(b, c), Arrays.asList(project.id, pending.id), Collections.emptyList(), callback));
        for (int i = 0; i < 2; i++) {
            this.<Void>await(callback -> controller.applyTags(Arrays.asList(a, b), Collections.singletonList(pending.id), Collections.emptyList(), callback));
        }
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102, 103), callback));
        assertEquals(2, mapping.get(101).size());
        assertEquals(2, mapping.get(102).size());
        assertEquals(3, controller.getTags().stream().filter(tag -> tag.id == pending.id).findFirst().get().messageCount);
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(b, c), Collections.emptyList(), Collections.singletonList(project.id), callback));
        mapping = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102, 103), callback));
        assertEquals(2, mapping.get(101).size());
        assertEquals(1, mapping.get(102).size());
        assertEquals(pending.id, mapping.get(102).get(0).id);
        assertEquals(pending.id, mapping.get(103).get(0).id);
        assertEquals("第二条", b.messageOwner.message);
    }

    @Test
    public void t4_03_onlySelectedAlbumMembersAreChanged() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("分组范围", callback));
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int id = 301; id <= 306; id++) {
            MessageObject message = message(id, USER_A, "成员");
            message.messageOwner.grouped_id = id <= 304 ? 800 : 900;
            messages.add(message);
        }
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(messages.get(0), messages.get(4)), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(301, 302, 303, 304, 305, 306), callback));
        for (int id = 301; id <= 306; id++) {
            assertEquals(id == 301 || id == 305 ? 1 : 0, mapping.get(id).size());
        }
        this.<Void>await(callback -> controller.applyTags(messages.subList(0, 4), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        mapping = await(callback -> controller.loadMessageTags(Arrays.asList(301, 302, 303, 304, 305, 306), callback));
        assertTrue(mapping.get(306).isEmpty());
        assertEquals(5, controller.getTags().get(0).messageCount);
    }

    @Test
    public void t4_04_midBatchFailureRollsBackAndOnlyRetryNotifies() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("整批事务", callback));
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int id = 101; id <= 103; id++) {
            messages.add(message(id, USER_A, "批次成员"));
        }
        AtomicInteger events = new AtomicInteger();
        main(() -> {
            observer = (id, account, args) -> events.incrementAndGet();
            NotificationCenter.getInstance(0).addObserver(observer, NotificationCenter.localSavedTagsUpdated);
        });
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_batch BEFORE INSERT ON local_saved_message_tags WHEN NEW.message_id = 102 BEGIN SELECT RAISE(ABORT, '测试批次中断'); END").stepThis().dispose();
            Response<Void> failed = call(callback -> controller.applyTags(messages, Collections.singletonList(tag.id), Collections.emptyList(), callback));
            assertTrue(failed.error instanceof SQLiteException);
            assertEquals(0, events.get());
            SparseArray<ArrayList<LocalSavedTag>> unchanged = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102, 103), callback));
            for (int i = 0; i < unchanged.size(); i++) {
                assertTrue(unchanged.valueAt(i).isEmpty());
            }
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_batch").stepThis().dispose();
            db.close();
        }
        this.<Void>await(callback -> controller.applyTags(messages, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        assertEquals(1, events.get());
        assertEquals(3, controller.getTags().get(0).messageCount);
    }

    private void user(long id) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "本地标签合成账号";
        UserConfig.getInstance(0).setCurrentUser(user);
    }

    private void main(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private <T> Response<T> call(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        Response<T> response = new Response<>();
        CountDownLatch done = new CountDownLatch(1);
        main(() -> action.accept((result, error) -> {
            response.value = result;
            response.error = error;
            response.mainThread = Looper.myLooper() == Looper.getMainLooper();
            response.calls.incrementAndGet();
            done.countDown();
        }));
        assertTrue("控制器回调超时", done.await(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals(1, response.calls.get());
        assertTrue(response.mainThread);
        return response;
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        Response<T> response = call(action);
        if (response.error != null) {
            throw new AssertionError(response.error);
        }
        return response.value;
    }

    private void close(LocalSavedTagsStorage store) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        store.close(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    private void delete(File file) throws Exception {
        File target = file.getCanonicalFile();
        assertTrue(target.equals(root) || target.getPath().startsWith(root.getPath() + File.separator));
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                delete(child);
            }
        }
        assertTrue(target.delete());
    }
}
