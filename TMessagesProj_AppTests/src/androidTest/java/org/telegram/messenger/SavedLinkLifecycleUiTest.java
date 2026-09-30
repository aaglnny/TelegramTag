package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.C1;
import static org.telegram.messenger.SavedLinkLoadingTest.C2;
import static org.telegram.messenger.SavedLinkLoadingTest.USER;
import static org.telegram.messenger.SavedLinkLoadingTest.main;
import static org.telegram.messenger.SavedLinkMediaTest.waitFor;

@RunWith(AndroidJUnit4.class)
public class SavedLinkLifecycleUiTest {
    final SavedLinkMediaTest media = new SavedLinkMediaTest();
    final SavedLinkCardTest ui = media.ui;

    @Before
    public void setUp() throws Exception {
        media.setUp();
    }

    @After
    public void tearDown() throws Exception {
        media.tearDown();
    }

    @Test(timeout = 90000)
    public void l6_twoRealChatPagesReleaseTheirOwnSubscriptions() throws Exception {
        ui.fixture.source.auto = false;
        ChatActivity first = ui.activity.chat;
        INavigationLayout[] secondLayout = new INavigationLayout[1];
        ChatActivity[] second = new ChatActivity[1];
        main(() -> {
            LinearLayout pages = new LinearLayout(ui.activity);
            pages.setOrientation(LinearLayout.VERTICAL);
            ((ViewGroup) ui.activity.navigation.getView().getParent()).removeView(ui.activity.navigation.getView());
            pages.addView(ui.activity.navigation.getView(), new LinearLayout.LayoutParams(-1, 0, 1));
            secondLayout[0] = INavigationLayout.newLayout(ui.activity, false);
            secondLayout[0].setFragmentStack(new ArrayList<>());
            pages.addView(secondLayout[0].getView(), new LinearLayout.LayoutParams(-1, 0, 1));
            ui.activity.setContentView(pages);
            Bundle args = new Bundle();
            args.putLong("user_id", USER);
            second[0] = new ChatActivity(args);
            second[0].setCurrentAccount(0);
            secondLayout[0].addFragmentToStack(second[0]);
            secondLayout[0].showLastFragment();
        });
        try {
            MessageObject a = ui.sample(901, "https://t.me/c/" + C1 + "/301", null);
            MessageObject b = ui.sample(902, a.messageOwner.message, null);
            ui.show(a);
            waitFor(() -> ui.fixture.source.pending.size() == 1);
            SavedLinkLoadingTest.Request request = ui.fixture.source.pending.values().iterator().next();
            SavedLinkPreviewView firstCard = ui.cell(901).getSavedLinkPreviewView();
            main(() -> ui.activity.chat = second[0]);
            ui.show(b);
            waitFor(() -> ui.cell(902) != null && ui.cell(902).getSavedLinkPreviewView() != null
                    && ui.cell(902).getSavedLinkPreviewView().getPreview() != null
                    && ui.cell(902).getSavedLinkPreviewView().getPreview().reference != null
                    && ui.cell(902).getSavedLinkPreviewView().getPreview().reference.sourceDialogId == -C1);
            assertEquals(2, SavedLinkCardTest.field(ui.fixture.controller, "subscriptions", ArrayList.class).size());
            assertEquals(1, ui.fixture.source.messageRequests());
            main(() -> ui.activity.navigation.removeAllFragments());
            waitFor(() -> !firstCard.isAttachedToWindow());
            assertEquals(0, ui.fixture.source.cancelled);
            assertEquals(1, ui.fixture.source.pending.size());
            main(() -> ui.fixture.source.answer(request, ui.fixture.source.response(request), null));
            SavedLinkPreviewView current = ui.card(902);
            assertEquals(301, current.getPreview().message.getId());
            assertNull(SavedLinkCardTest.field(firstCard, "subscription", SavedLinkPreviewController.Subscription.class));
            main(() -> secondLayout[0].removeAllFragments());
            waitFor(() -> !current.isAttachedToWindow());
            assertTrue(SavedLinkCardTest.field(ui.fixture.controller, "subscriptions", ArrayList.class).isEmpty());
            assertTrue(SavedLinkCardTest.field(ui.fixture.controller, "jobs", ArrayList.class).isEmpty());
            for (SavedLinkPreviewView card : new SavedLinkPreviewView[]{firstCard, current}) {
                for (int id : new int[]{NotificationCenter.fileLoaded, NotificationCenter.fileLoadFailed,
                        NotificationCenter.messagePlayingProgressDidChanged, NotificationCenter.savedLinkReferencesCleared}) {
                    assertFalse(NotificationCenter.getInstance(0).getObservers(id).contains(card));
                }
            }
            System.out.println("L6 双收藏页：同源1次请求；关闭第一页不取消第二页请求，最后一页关闭后订阅/卡片监听/消息任务均释放");
        } finally {
            main(() -> {
                secondLayout[0].removeAllFragments();
                ui.activity.chat = first;
            });
        }
    }

    @Test(timeout = 90000)
    public void l6_clearingOnlyReferencesRebindsVisibleCardAndPreservesTagsAndSourceCache() throws Exception {
        ui.fixture.source.realCache = true;
        SavedLinkPreviewController original = ui.fixture.controller;
        SavedLinkPreviewStorage oldStorage = ui.fixture.storage;
        NotificationCenter.NotificationCenterDelegate reopened = (id, account, args) -> {
            ui.fixture.storage = new SavedLinkPreviewStorage(ui.fixture.root, USER, false);
            ui.fixture.controller = new SavedLinkPreviewController(0, ui.fixture.storage, ui.fixture.source);
            SavedLinkLifecycleTest.install(ui.fixture.controller);
        };
        main(() -> NotificationCenter.getInstance(0).addObserver(reopened, NotificationCenter.savedLinkReferencesCleared));
        try {
            TLRPC.Message source = SavedLinkLoadingTest.sourceMessage(C1, 831);
            source.noforwards = false;
            source.message = "清理引用后可重新解析，原文与标签仍保留";
            SavedLinkPreviewView card = media.show(source, 931);
            MessageObject old = card.getPreview().message;
            int requests = ui.fixture.source.messageRequests();
            SavedLinkLoadingTest.<Void>await(original::clearReferenceCache);
            SavedLinkLifecycleTest.assertClosed(original, oldStorage);
            waitFor(() -> original != ui.fixture.controller && ui.cell(931) != null && ui.cell(931).getSavedLinkPreviewView().getPreview() != null
                    && ui.cell(931).getSavedLinkPreviewView().getPreview().message != null
                    && ui.cell(931).getSavedLinkPreviewView().getPreview().message != old
                    && !ui.cell(931).getSavedLinkPreviewView().getPreview().loading);
            assertNotSame(original, ui.fixture.controller);
            assertTrue(old.savedLinkInvalidated);
            SavedLinkPreviewView restored = ui.card(931);
            assertEquals(source.message, restored.getPreview().message.messageOwner.message);
            assertEquals(1, ui.fixture.controller.getParseCount());
            assertEquals(requests + 1, ui.fixture.source.messageRequests());
            assertEquals(1, ui.tags.getMessageTags(931).size());
            assertEquals(media.tag.id, ui.tags.getMessageTags(931).get(0).id);
            assertEquals(1, SavedLinkLoadingTest.number("SELECT COUNT(*) FROM messages_v2 WHERE uid=" + -C1 + " AND mid=831"));
            ui.screenshot("l6-reference-cache-restored-tags-kept");
        } finally {
            main(() -> NotificationCenter.getInstance(0).removeObserver(reopened, NotificationCenter.savedLinkReferencesCleared));
        }
    }

    @Test(timeout = 90000)
    public void l6_logoutClosesPlayingPreviewAndKeepsLocalTagFile() throws Exception {
        TLRPC.Message source = media.source("video", 832, true);
        File file = LocalSavedTagsSamples.videoFile(ui.activity.getCacheDir(), "l6-logout-" + UUID.randomUUID() + ".mp4");
        media.files.add(file);
        source.attachPath = file.getPath();
        source.media.document.size = file.length();
        SavedLinkPreviewView card = media.show(source, 932);
        MessageObject old = card.getPreview().message;
        main(() -> assertTrue(card.openMedia()));
        waitFor(() -> SavedLinkMediaTest.player() != null && SavedLinkMediaTest.player().getCurrentPosition() > 300);
        main(() -> UserConfig.getInstance(0).clearConfig());
        waitFor(() -> !PhotoViewer.hasInstance() || !PhotoViewer.getInstance().isVisible());
        assertTrue(old.savedLinkInvalidated);
        assertFalse(SavedLinkPreviewController.isMediaValid(old));
        SavedLinkLifecycleTest.assertClosed(ui.fixture.controller, ui.fixture.storage);
        assertTrue(file.isFile());
        ArrayList<LocalSavedTag> tags = SavedLinkLoadingTest.await(ui.tagStorage::loadTags);
        assertEquals(1, tags.size());
        assertEquals(1, tags.get(0).messageCount);
        assertEquals(media.tag.id, tags.get(0).id);
        System.out.println("L6 退出：实际播放后立即关闭预览窗口，旧媒体失效，原标签文件与媒体缓存仍在");
    }

    @Test(timeout = 60000)
    public void l6_delayedReferenceNotificationAfterLogoutCannotRecreateOldPreview() throws Exception {
        TLRPC.Message source = SavedLinkLoadingTest.sourceMessage(C1, 835);
        source.noforwards = false;
        SavedLinkPreviewView card = media.show(source, 935);
        main(() -> {
            UserConfig.getInstance(0).clearConfig();
            card.didReceivedNotification(NotificationCenter.savedLinkReferencesCleared, 0, USER);
        });
        assertNull(card.getPreview());
        assertNull(SavedLinkCardTest.field(card, "subscription", SavedLinkPreviewController.Subscription.class));
        assertEquals(android.view.View.GONE, card.getVisibility());
        assertEquals(1, SavedLinkLoadingTest.<ArrayList<LocalSavedTag>>await(ui.tagStorage::loadTags).get(0).messageCount);
    }

    @Test(timeout = 90000)
    public void l6_nativeMediaCacheDeletionKeepsReferencesAndReloadsOnlyOnClick() throws Exception {
        TLRPC.Message source = media.source("file", 833, false);
        source.media.document.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        source.media.document.mime_type = "text/plain";
        ((TLRPC.TL_documentAttributeFilename) source.media.document.attributes.get(0)).file_name = "生命周期缓存.txt";
        byte[] bytes = "可单独清理的媒体缓存".getBytes(StandardCharsets.UTF_8);
        source.media.document.size = bytes.length;
        FileLoader loader = FileLoader.getInstance(0);
        File file = loader.getPathToMessage(source);
        media.write(file, bytes);
        SavedLinkPreviewView card = media.show(source, 933);
        String name = FileLoader.getAttachFileName(source.media.document);
        int requests = ui.fixture.source.messageRequests();
        long transferred = SavedLinkMediaTest.mediaBytes(0);
        loader.deleteFiles(new ArrayList<>(Collections.singletonList(file)), 0);
        waitFor(() -> !file.exists());
        assertEquals(1, ui.tags.getMessageTags(933).size());
        assertNotNull(card.getPreview().message);
        assertFalse(loader.isLoadingFile(name));
        assertEquals(requests, ui.fixture.source.messageRequests());
        assertEquals(transferred, SavedLinkMediaTest.mediaBytes(0));
        Map<String, FileLoadOperation> operations = SavedLinkCardTest.field(loader, "loadOperationPaths", Map.class);
        main(() -> assertTrue(card.openMedia()));
        try {
            waitFor(() -> operations.containsKey(name));
            MessageObject parent = (MessageObject) operations.get(name).parentObject;
            assertEquals(-C1, parent.getDialogId());
            assertEquals(833, parent.getId());
            assertEquals(requests, ui.fixture.source.messageRequests());
        } finally {
            loader.cancelLoadFile(source.media.document);
            waitFor(() -> !operations.containsKey(name));
        }
        media.write(file, bytes);
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                assertEquals(Intent.ACTION_VIEW, intent.getAction());
                return new Instrumentation.ActivityResult(0, null);
            }
        };
        ui.instrumentation.addMonitor(monitor);
        try {
            main(() -> assertTrue(card.openMedia()));
            assertFalse(loader.isLoadingFile(name));
            assertEquals(1, ui.tags.getMessageTags(933).size());
        } finally {
            ui.instrumentation.removeMonitor(monitor);
        }
        System.out.println("L6 FileLoader实际清缓存：正文/引用/标签保留，点击才重新登记源文件加载，缓存恢复后无重复下载");
    }

    @Test(timeout = 90000)
    public void l6_reusedAccountSlotShowsOnlyItsOwnSourceAndTagRelations() throws Exception {
        TLRPC.Message first = SavedLinkLoadingTest.sourceMessage(C1, 834);
        first.noforwards = false;
        first.message = "账号A的预览正文";
        MessageObject old = media.show(first, 934).getPreview().message;
        LocalSavedTagsStorage oldTags = ui.tagStorage;
        SavedLinkPreviewStorage oldLinks = ui.fixture.storage;
        main(() -> {
            ui.activity.finish();
            MessagesController.getInstance(0).cleanup();
            UserConfig.getInstance(0).clearConfig();
        });
        waitFor(() -> ui.activity.isDestroyed());
        SavedLinkLifecycleTest.close(oldLinks);
        CountDownLatch closed = new CountDownLatch(1);
        oldTags.close(closed::countDown);
        assertTrue(closed.await(15, TimeUnit.SECONDS));
        long other = SavedLinkReferenceTest.USER_B;
        ui.fixture.source = new SavedLinkLoadingTest.Source();
        TLRPC.Chat chat = SavedLinkLoadingTest.channel(C2);
        chat.noforwards = false;
        ui.fixture.source.chats.put(C2, chat);
        TLRPC.Message current = SavedLinkLoadingTest.sourceMessage(C2, 834);
        current.noforwards = false;
        current.message = "账号B独立的来源与权限";
        ui.fixture.source.remote.put(C2 + ":834", current);
        ui.fixture.storage = new SavedLinkPreviewStorage(ui.fixture.root, other, false);
        ui.tagStorage = new LocalSavedTagsStorage(ui.fixture.root, other, false);
        ui.tagSource = new LocalSavedTagsFilterTest.Source();
        ui.tagSource.userId = other;
        main(() -> {
            SavedLinkLifecycleTest.user(other);
            MessagesController.getInstance(0).putChat(chat, false);
            ui.fixture.controller = new SavedLinkPreviewController(0, ui.fixture.storage, ui.fixture.source);
            SavedLinkLifecycleTest.install(ui.fixture.controller);
            ui.tags = new LocalSavedTagsController(0, ui.tagStorage, ui.tagSource);
        });
        Field tags = MessagesController.class.getDeclaredField("localSavedTagsController");
        tags.setAccessible(true);
        tags.set(MessagesController.getInstance(0), ui.tags);
        ui.activity = LocalSavedTagsUiTest.startActivity(ui.instrumentation, other);
        for (Object[] value : new Object[][]{{"activity", ui.activity}, {"controller", ui.tags}}) {
            Field field = LocalSavedTagsUiTest.class.getDeclaredField((String) value[0]);
            field.setAccessible(true);
            field.set(ui.driver, value[1]);
        }
        TLRPC.Message saved = SavedLinkLoadingTest.saved(934, C2, 834);
        saved.dialog_id = saved.peer_id.user_id = other;
        ui.show(new MessageObject(0, saved, true, false));
        SavedLinkPreviewView card = ui.card(934);
        assertEquals(current.message, card.getPreview().message.messageOwner.message);
        assertEquals(other, card.getPreview().reference.userId);
        assertEquals(-C2, card.getPreview().message.getDialogId());
        assertTrue(ui.tags.getMessageTags(934).isEmpty());
        assertFalse(SavedLinkPreviewController.isMediaValid(old));
        LocalSavedTagsStorage restored = new LocalSavedTagsStorage(ui.fixture.root, USER, false);
        try {
            ArrayList<LocalSavedTag> retained = SavedLinkLoadingTest.await(restored::loadTags);
            assertEquals(media.tag.id, retained.get(0).id);
            assertEquals(1, retained.get(0).messageCount);
        } finally {
            CountDownLatch done = new CountDownLatch(1);
            restored.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        ui.screenshot("l6-account-b-source-isolated");
    }
}
