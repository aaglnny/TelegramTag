package org.telegram.messenger;

import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.C1;
import static org.telegram.messenger.SavedLinkLoadingTest.main;
import static org.telegram.messenger.SavedLinkMediaTest.waitFor;

@RunWith(AndroidJUnit4.class)
public class SavedLinkRefreshUiTest {
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
    public void l5_sharedCardsEditDeleteAndRetryPreserveFilterAndTags() throws Exception {
        TLRPC.Message source = SavedLinkLoadingTest.sourceMessage(C1, 801);
        source.noforwards = false;
        source.message = "变更前正文";
        media.show(source, 901);
        MessageObject first = ui.sample(901, "https://t.me/c/" + C1 + "/801", null);
        MessageObject second = ui.sample(902, first.messageOwner.message, null);
        SavedLinkLoadingTest.<Void>await(cb -> ui.tags.applyTags(Collections.singletonList(second),
                Collections.singletonList(media.tag.id), Collections.emptyList(), cb));
        ui.show(first, second);
        ui.click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "媒体标签"));
        ui.invoke("waitFilter", new Class[]{int.class}, 2);
        Object filter = SavedLinkCardTest.field(ui.activity.chat, "localTagFilter", Object.class);
        int firstDate = first.messageOwner.date;
        TLRPC.Message edited = SavedLinkLoadingTest.sourceMessage(C1, 801);
        edited.message = "来源编辑后的新正文 #原话题";
        edited.noforwards = false;
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.replaceMessagesObjects,
                -C1, new ArrayList<>(Collections.singletonList(new MessageObject(0, edited, false, false)))));
        waitFor(() -> ui.cell(901) != null && ui.cell(902) != null);
        for (int id : new int[]{901, 902}) {
            SavedLinkPreviewView card = ui.card(id);
            assertEquals(edited.message, SavedLinkCardTest.field(card, "body", TextView.class).getText().toString());
            assertEquals(1, ui.tags.getMessageTags(id).size());
        }
        assertSame(filter, SavedLinkCardTest.field(ui.activity.chat, "localTagFilter", Object.class));
        assertEquals(firstDate, first.messageOwner.date);
        assertEquals("https://t.me/c/" + C1 + "/801", first.messageOwner.message);
        main(() -> ui.fixture.controller.onMessagesDeleted(-C1, Collections.singletonList(801)));
        waitFor(() -> ui.cell(901).getSavedLinkPreviewView().getPreview().message == null);
        SavedLinkPreviewView unavailable = ui.cell(901).getSavedLinkPreviewView();
        assertEquals(View.GONE, SavedLinkCardTest.field(unavailable, "body", TextView.class).getVisibility());
        assertFalse(unavailable.openMedia());
        assertSame(filter, SavedLinkCardTest.field(ui.activity.chat, "localTagFilter", Object.class));
        ui.screenshot("l5-source-unavailable-tags-kept");
        ui.fixture.source.remote.put(C1 + ":801", edited);
        int requests = ui.fixture.source.messageRequests();
        main(() -> SavedLinkCardTest.field(unavailable, "retry", TextView.class).performClick());
        waitFor(() -> ui.cell(901).getSavedLinkPreviewView().getPreview().message != null);
        assertEquals(requests + 1, ui.fixture.source.messageRequests());
        assertEquals(2, ui.tags.getTags().get(0).messageCount);
        assertSame(filter, SavedLinkCardTest.field(ui.activity.chat, "localTagFilter", Object.class));
        ui.screenshot("l5-source-restored");
    }

    @Test(timeout = 90000)
    public void l5_videoProtectionChangeAndDeletionCloseTheIndependentViewer() throws Exception {
        TLRPC.Message source = media.source("video", 811, false);
        File file = LocalSavedTagsSamples.videoFile(ui.activity.getCacheDir(), "l5-video-" + UUID.randomUUID() + ".mp4");
        media.files.add(file);
        source.attachPath = file.getPath();
        source.media.document.size = file.length();
        SavedLinkPreviewView card = media.show(source, 911);
        MessageObject old = card.getPreview().message;
        main(() -> assertTrue(card.openMedia()));
        waitFor(() -> SavedLinkMediaTest.player() != null && SavedLinkMediaTest.player().getCurrentPosition() > 300);
        main(() -> {
            TLRPC.Chat chat = SavedLinkLoadingTest.channel(C1);
            MessagesController.getInstance(0).putChat(chat, false);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT);
        });
        waitFor(() -> !PhotoViewer.hasInstance() || !PhotoViewer.getInstance().isVisible());
        assertTrue(old.savedLinkInvalidated);
        assertTrue(card.getPreview().isProtected());
        assertTrue((ui.activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
        main(() -> {
            assertTrue(card.openMedia());
            ui.fixture.controller.onMessagesDeleted(-C1, Collections.singletonList(811));
        });
        waitFor(() -> !PhotoViewer.hasInstance() || !PhotoViewer.getInstance().isVisible());
        assertNull(card.getPreview().message);
        assertEquals(1, ui.tags.getMessageTags(911).size());
        SavedLinkMediaTest.report("L5 视频：实际播放后来源保护变更关闭独立窗口；再次开启转场中确认删除也立即退出，标签保留");
    }

    @Test(timeout = 90000)
    public void l5_voicePermissionLossStopsPlaybackAndRetryRestoresIt() throws Exception {
        TLRPC.Message source = media.source("voice", 812, false);
        File file = LocalSavedTagsSamples.audioFile(ui.activity.getCacheDir(), "l5-voice-" + UUID.randomUUID() + ".wav");
        media.files.add(file);
        source.attachPath = file.getPath();
        source.media.document.mime_type = "audio/wav";
        source.media.document.size = file.length();
        SavedLinkPreviewView card = media.show(source, 912);
        main(() -> assertTrue(card.openMedia()));
        MediaController player = MediaController.getInstance();
        waitFor(() -> player.getCurrentPosition() > 300);
        ui.fixture.source.auto = false;
        main(() -> SavedLinkCardTest.field(card, "retry", TextView.class).performClick());
        waitFor(() -> ui.fixture.source.pending.size() == 1);
        SavedLinkLoadingTest.Request request = ui.fixture.source.pending.values().iterator().next();
        main(() -> ui.fixture.source.answer(request, null, "CHANNEL_PRIVATE"));
        waitFor(() -> player.getPlayingMessageObject() == null && card.getPreview().message == null);
        assertTrue(file.exists());
        assertEquals(1, ui.tags.getMessageTags(912).size());
        ui.fixture.source.auto = true;
        main(() -> SavedLinkCardTest.field(card, "retry", TextView.class).performClick());
        waitFor(() -> card.getPreview().message != null && !card.getPreview().loading);
        main(() -> assertTrue(card.openMedia()));
        waitFor(() -> player.getCurrentPosition() > 300);
        assertEquals(812, player.getPlayingMessageObject().getId());
        assertTrue(source.media_unread);
        SavedLinkMediaTest.report("L5 音频：权限失效停止真实播放并隐藏缓存；明确重试后复用有效文件恢复播放，不改变来源已读和收藏标签");
    }

    @Test(timeout = 90000)
    public void l5_fileReferenceUsesTheSourceQueueAndStopsAfterTwoRefreshes() throws Exception {
        TLRPC.Message source = media.source("file", 821, false);
        source.media.document.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        source.media.document.file_reference = new byte[]{1};
        SavedLinkPreviewView card = media.show(source, 921);
        FileLoader loader = FileLoader.getInstance(0);
        String name = FileLoader.getAttachFileName(source.media.document);
        Map<String, FileLoadOperation> operations = SavedLinkCardTest.field(loader, "loadOperationPaths", Map.class);
        main(() -> assertTrue(card.openMedia()));
        waitFor(() -> operations.containsKey(name));
        FileLoadOperation operation = operations.get(name);
        ui.fixture.source.auto = false;
        try {
            for (int i = 0; i < 2; i++) {
                expire(operation);
                waitFor(() -> ui.fixture.source.pending.size() == 1);
                SavedLinkLoadingTest.Request request = ui.fixture.source.pending.values().iterator().next();
                TLRPC.TL_channels_getMessages input = (TLRPC.TL_channels_getMessages) request.object;
                assertEquals(C1, input.channel.channel_id);
                assertEquals(Collections.singletonList(821), input.id);
                TLRPC.Message refreshed = media.source("file", 821, false);
                refreshed.media.document.id = source.media.document.id;
                byte[] reference = new byte[]{(byte) (i + 2)};
                refreshed.media.document.file_reference = reference;
                ui.fixture.source.remote.put(C1 + ":821", refreshed);
                main(() -> ui.fixture.source.answer(request, ui.fixture.source.response(request), null));
                waitFor(() -> Arrays.equals(reference, operation.location.file_reference) && !operation.requestingReference);
            }
            int requests = ui.fixture.source.messageRequests();
            expire(operation);
            waitFor(() -> !operations.containsKey(name));
            assertEquals(requests, ui.fixture.source.messageRequests());
            assertEquals(3, requests);
            assertEquals(1, ui.tags.getMessageTags(921).size());
            SavedLinkMediaTest.report("L5 文件引用：正式 FileLoadOperation 两次过期均读取原频道821并更新位置，第三次结束；来源读取共3次，无无限重试");
        } finally {
            loader.cancelLoadFile(source.media.document);
            waitFor(() -> !operations.containsKey(name));
        }
    }

    @Test(timeout = 90000)
    public void l5_permissionChangeRejectsLateFileReference() throws Exception {
        TLRPC.Message source = media.source("file", 822, false);
        source.media.document.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        source.media.document.file_reference = new byte[]{7};
        SavedLinkPreviewView card = media.show(source, 922);
        FileLoader loader = FileLoader.getInstance(0);
        String name = FileLoader.getAttachFileName(source.media.document);
        Map<String, FileLoadOperation> operations = SavedLinkCardTest.field(loader, "loadOperationPaths", Map.class);
        main(() -> assertTrue(card.openMedia()));
        waitFor(() -> operations.containsKey(name));
        FileLoadOperation operation = operations.get(name);
        ui.fixture.source.auto = false;
        try {
            expire(operation);
            waitFor(() -> ui.fixture.source.pending.size() == 1);
            SavedLinkLoadingTest.Request request = ui.fixture.source.pending.values().iterator().next();
            main(() -> ui.fixture.controller.onMessagesDeleted(-C1, Collections.singletonList(822)));
            main(() -> ui.fixture.source.answer(request, ui.fixture.source.response(request), null));
            waitFor(() -> !operations.containsKey(name));
            assertArrayEquals(new byte[]{7}, operation.location.file_reference);
            assertNull(card.getPreview().message);
            assertEquals(1, ui.tags.getMessageTags(922).size());
        } finally {
            loader.cancelLoadFile(source.media.document);
            waitFor(() -> !operations.containsKey(name));
        }
    }

    private void expire(FileLoadOperation operation) throws Exception {
        waitFor(() -> SavedLinkCardTest.field(operation, "requestInfos", ArrayList.class) != null);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Utilities.stageQueue.postRunnable(() -> {
            try {
                Method target = null;
                for (Method method : FileLoadOperation.class.getDeclaredMethods()) {
                    if (method.getName().equals("requestReference")) target = method;
                }
                assertNotNull(target);
                target.setAccessible(true);
                target.invoke(operation, new Object[]{null});
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }
}
