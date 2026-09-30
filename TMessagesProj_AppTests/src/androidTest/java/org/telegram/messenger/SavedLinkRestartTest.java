package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.SavedLinkPreviewView;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.*;
import static org.telegram.messenger.SavedLinkMediaTest.waitFor;

@RunWith(AndroidJUnit4.class)
public class SavedLinkRestartTest {
    private static final long CHANNEL = C1 + 900;
    private static final int SAVED = 190101;
    private static final int SOURCE = 190301;
    private static final String BODY = "跨进程保留的原消息正文 #原话题";
    private static final String TAG = "重启升级保留";
    private final SavedLinkCardTest ui = new SavedLinkCardTest();
    private SavedLinkPreviewStorage storage;
    private SavedLinkPreviewController controller;
    private SavedLinkLoadingTest.Source source;
    private SharedPreferences preferences;
    private Field tagField;
    private Object previousTags;

    @Before
    public void setUp() throws Exception {
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            assertFalse("跨进程样本只允许在未登录的隔离设备运行", UserConfig.getInstance(account).isClientActivated());
        }
        ui.instrumentation = InstrumentationRegistry.getInstrumentation();
        Context context = ui.instrumentation.getTargetContext();
        File root = new File(context.getNoBackupFilesDir(), "saved_link_restart_v1");
        assertTrue(root.isDirectory() || root.mkdirs());
        preferences = context.getSharedPreferences("saved_link_restart_v1", Context.MODE_PRIVATE);
        source = new SavedLinkLoadingTest.Source();
        source.realCache = true;
        source.time = System.currentTimeMillis();
        TLRPC.Chat chat = channel(CHANNEL);
        chat.noforwards = false;
        source.chats.put(CHANNEL, chat);
        TLRPC.Message original = sourceMessage(CHANNEL, SOURCE);
        original.noforwards = false;
        original.message = BODY;
        source.remote.put(CHANNEL + ":" + SOURCE, original);
        storage = new SavedLinkPreviewStorage(root, USER, false);
        ui.tagStorage = new LocalSavedTagsStorage(root, USER, false);
        ui.tagSource = new LocalSavedTagsFilterTest.Source();
        ui.tagSource.userId = USER;
        main(() -> {
            SavedLinkLifecycleTest.user(USER);
            controller = new SavedLinkPreviewController(0, storage, source);
            SavedLinkLifecycleTest.install(controller);
            ui.tags = new LocalSavedTagsController(0, ui.tagStorage, ui.tagSource);
        });
        tagField = MessagesController.class.getDeclaredField("localSavedTagsController");
        tagField.setAccessible(true);
        previousTags = tagField.get(MessagesController.getInstance(0));
        tagField.set(MessagesController.getInstance(0), ui.tags);
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            if (ui.activity != null) ui.activity.finish();
            if (ui.tags != null) ui.tags.cleanup();
            SavedLinkPreviewController.cleanupAccount(0);
            UserConfig.getInstance(0).clearConfig();
        });
        idle();
        if (tagField != null) tagField.set(MessagesController.getInstance(0), previousTags);
        if (storage != null) SavedLinkLifecycleTest.close(storage);
        if (ui.tagStorage != null) {
            CountDownLatch done = new CountDownLatch(1);
            ui.tagStorage.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        // 写入和读取必须由两次独立仪器调用执行，这里保留固定命名空间及原消息缓存。
    }

    @Test(timeout = 90000)
    public void l6_writeBeforeProcessRestartOrUpgrade() throws Exception {
        SavedLinkLoadingTest.<Void>await(storage::clear);
        sql("DELETE FROM messages_v2 WHERE uid=" + -CHANNEL);
        sql("DELETE FROM messages_v2 WHERE uid=" + USER + " AND mid=" + SAVED);
        for (LocalSavedTag tag : SavedLinkLoadingTest.<ArrayList<LocalSavedTag>>await(ui.tags::loadTags)) {
            SavedLinkLoadingTest.<Void>await(cb -> ui.tags.deleteTag(tag.id, cb));
        }
        main(() -> MessagesController.getInstance(0).putChat(source.chats.get(CHANNEL), false));
        TLRPC.Message message = saved(SAVED, CHANNEL, SOURCE);
        main(() -> MessagesStorage.getInstance(0).putMessages(new ArrayList<>(Collections.singletonList(message)),
                true, true, false, 0, false, 0, 0));
        assertEquals(1, number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + USER + " AND mid=" + SAVED));
        Watch watch = SavedLinkLifecycleTest.watch(controller, message);
        waitFor(watch::finished);
        assertEquals(BODY, watch.value.message.messageOwner.message);
        assertEquals(1, source.messageRequests());
        LocalSavedTag tag = await(cb -> ui.tags.createTag(TAG, cb));
        MessageObject saved = new MessageObject(0, message, true, false);
        SavedLinkLoadingTest.<Void>await(cb -> ui.tags.applyTags(Collections.singletonList(saved),
                Collections.singletonList(tag.id), Collections.emptyList(), cb));
        assertTrue(preferences.edit().putInt("writer_pid", Process.myPid()).putLong("writer_version", version())
                .putLong("tag_id", tag.id).putLong("success_at", watch.value.reference.lastSuccessAt).commit());
        show(saved);
        ui.card(SAVED);
        waitFor(() -> ui.tags.getMessageTags(SAVED).size() == 1);
        ui.screenshot("l6-before-process-restart-or-upgrade");
        System.out.println("L6 写入：PID=" + Process.myPid() + "，versionCode=" + version()
                + "，收藏=" + SAVED + "，来源=" + SOURCE + "，标签=" + tag.id + "，真实原消息缓存=1，引用=1");
    }

    @Test(timeout = 90000)
    public void l6_readAfterDifferentProcessSameVersion() throws Exception {
        assertEquals("重启验证必须保持同一版本", preferences.getLong("writer_version", 0), version());
        restored("l6-after-process-restart");
    }

    @Test(timeout = 90000)
    public void l6_readAfterHigherVersionInstalledOverExistingData() throws Exception {
        assertTrue("覆盖升级必须实际提高 versionCode", version() > preferences.getLong("writer_version", Long.MAX_VALUE));
        restored("l6-after-version-upgrade");
    }

    private void restored(String screenshot) throws Exception {
        int writer = preferences.getInt("writer_pid", 0);
        assertTrue("先在低版本或旧进程执行写入方法", writer > 0);
        assertNotEquals("不能用同进程重新创建对象替代重启", writer, Process.myPid());
        source.auto = false;
        SparseArray<SavedLinkReference> rows = await(cb -> storage.load(Collections.singletonList(SAVED), cb));
        SavedLinkReference reference = rows.get(SAVED);
        assertNotNull(reference);
        assertEquals(-CHANNEL, reference.sourceDialogId);
        assertEquals(SOURCE, reference.sourceMessageId);
        assertEquals(SavedLinkReference.AVAILABLE, reference.state);
        assertEquals(preferences.getLong("success_at", 0), reference.lastSuccessAt);
        TLRPC.messages_Messages originals = await(cb -> MessagesStorage.getInstance(0).getMessagesByIds(-CHANNEL,
                new ArrayList<>(Collections.singletonList(SOURCE)), cb));
        assertEquals(1, originals.messages.size());
        assertEquals(BODY, originals.messages.get(0).message);
        TLRPC.messages_Messages saved = await(cb -> MessagesStorage.getInstance(0).getMessagesByIds(USER,
                new ArrayList<>(Collections.singletonList(SAVED)), cb));
        assertEquals(1, saved.messages.size());
        MessageObject message = new MessageObject(0, saved.messages.get(0), true, false);
        ArrayList<LocalSavedTag> tags = await(ui.tags::loadTags);
        assertEquals(1, tags.size());
        assertEquals(preferences.getLong("tag_id", 0), tags.get(0).id);
        assertEquals(TAG, tags.get(0).name);
        assertEquals(1, tags.get(0).messageCount);
        show(message);
        SavedLinkPreviewView card = ui.card(SAVED);
        waitFor(() -> ui.tags.getMessageTags(SAVED).size() == 1);
        assertEquals(BODY, card.getPreview().message.messageOwner.message);
        assertEquals(-CHANNEL, card.getPreview().message.getDialogId());
        assertEquals(USER, message.getDialogId());
        assertEquals(SAVED, card.getPreview().reference.savedMessageId);
        assertTrue(card.getPreview().cached);
        assertEquals(0, source.requests.size());
        assertEquals(0, controller.getParseCount());
        ui.screenshot(screenshot);
        System.out.println("L6 恢复：旧PID=" + writer + "，新PID=" + Process.myPid()
                + "，旧versionCode=" + preferences.getLong("writer_version", 0) + "，新versionCode=" + version()
                + "，原消息/收藏/标签/引用恢复，新增解析=0，来源解析=0，消息网络=0");
    }

    private void show(MessageObject message) throws Exception {
        ui.activity = LocalSavedTagsUiTest.startActivity(ui.instrumentation, USER);
        Object[][] fields = {{"instrumentation", ui.instrumentation}, {"ui", ui.instrumentation.getUiAutomation()},
                {"activity", ui.activity}, {"controller", ui.tags}};
        for (Object[] value : fields) {
            Field field = LocalSavedTagsUiTest.class.getDeclaredField((String) value[0]);
            field.setAccessible(true);
            field.set(ui.driver, value[1]);
        }
        ui.show(message);
    }

    private long version() throws Exception {
        Context context = ui.instrumentation.getTargetContext();
        return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).getLongVersionCode();
    }
}
