package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkMediaTest {
    final SavedLinkCardTest ui = new SavedLinkCardTest();
    final ArrayList<File> files = new ArrayList<>();
    LocalSavedTag tag;

    @Before
    public void setUp() throws Exception {
        ui.setUp();
        tag = SavedLinkLoadingTest.await(callback -> ui.tags.createTag("媒体标签", callback));
    }

    @After
    public void tearDown() throws Exception {
        SavedLinkLoadingTest.main(() -> {
            if (PhotoViewer.hasInstance()) {
                PhotoViewer.getInstance().destroyPhotoViewer();
            }
            MediaController.getInstance().cleanupPlayer(true, true);
        });
        ui.tearDown();
        for (File file : files) {
            assertTrue("测试媒体未释放", !file.exists() || file.delete());
        }
    }

    @Test(timeout = 90000)
    public void l4_photoDecodesAndReusesItsCachedFile() throws Exception {
        photo(false, false, false);
    }

    @Test(timeout = 90000)
    public void l4_channelProtectionSurvivesMissingSourceInfo() throws Exception {
        photo(true, false, true);
    }

    @Test(timeout = 90000)
    public void l4_messageProtectionReachesTheViewer() throws Exception {
        photo(false, true, false);
    }

    @Test(timeout = 90000)
    public void l4_combinedProtectionReachesTheViewer() throws Exception {
        photo(true, true, false);
    }

    private void photo(boolean channelProtected, boolean messageProtected, boolean removeSource) throws Exception {
        TLRPC.Message source = source("photo", 601, messageProtected);
        source.grouped_id = 66001;
        byte[] bytes = source.media.photo.sizes.get(0).bytes;
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "x";
        size.w = 240;
        size.h = 160;
        size.size = bytes.length;
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = -902026091401L;
        size.location.local_id = Math.abs(UUID.randomUUID().hashCode());
        source.media.photo.sizes.clear();
        source.media.photo.sizes.add(size);
        File file = FileLoader.getInstance(0).getPathToAttach(size);
        write(file, bytes);
        ui.fixture.source.chats.get(SavedLinkLoadingTest.C1).noforwards = channelProtected;
        SavedLinkPreviewView card = show(source, 701);
        BackupImageView thumbnail = SavedLinkCardTest.field(card, "image", BackupImageView.class);
        waitFor(() -> thumbnail.getImageReceiver().hasImageLoaded() && thumbnail.getImageReceiver().getBitmap() != null);
        assertEquals(View.VISIBLE, thumbnail.getVisibility());
        assertTrue(SavedLinkCardTest.field(card, "mediaInfo", TextView.class).getText().toString().contains(LocaleController.getString(R.string.SavedLinkAlbumMember)));
        if (removeSource) {
            SavedLinkLoadingTest.main(() -> {
                try {
                    Field field = MessagesController.class.getDeclaredField("chats");
                    field.setAccessible(true);
                    ((Map<Long, TLRPC.Chat>) field.get(MessagesController.getInstance(0))).remove(SavedLinkLoadingTest.C1);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
        }
        Bitmap expected = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        long received = mediaBytes(0);
        long modified = file.lastModified();
        for (int i = 0; i < 2; i++) {
            SavedLinkLoadingTest.main(() -> assertTrue(card.openMedia()));
            waitFor(() -> {
                Bitmap bitmap = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap();
                return bitmap != null && bitmap.getWidth() == 240 && bitmap.getHeight() == 160;
            });
            waitFor(() -> SavedLinkPreviewUiTest.viewerField("animationInProgress", Integer.class) == 0);
            SavedLinkLoadingTest.main(() -> {
                Bitmap actual = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap();
                assertEquals(expected.getPixel(100, 80), actual.getPixel(100, 80));
                assertTrue(PhotoViewer.isShowingImage(card.getPreview().message));
                assertEquals(-SavedLinkLoadingTest.C1, card.getPreview().message.getDialogId());
                assertEquals(601, card.getPreview().message.getId());
                boolean protect = channelProtected || messageProtected;
                assertEquals(protect, (ui.activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                WindowManager.LayoutParams params = SavedLinkPreviewUiTest.viewerField("windowLayoutParams", WindowManager.LayoutParams.class);
                assertEquals(protect, (params.flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
                assertEquals(!protect, SavedLinkPreviewUiTest.viewerField("allowShare", Boolean.class));
                PhotoViewer.getInstance().closePhoto(false, false);
            });
            waitFor(() -> !PhotoViewer.getInstance().isVisible());
        }
        expected.recycle();
        assertTrue(source.media_unread);
        assertEquals(received, mediaBytes(0));
        assertEquals(modified, file.lastModified());
        assertEquals(1, ui.tags.getMessageTags(701).size());
        if (!channelProtected && !messageProtected) {
            ui.screenshot("l4-photo-returned");
        }
        report("L4 图片：解码=240×160，像素一致，重复查看新增媒体字节=0，来源成员=601，标签保留；频道保护="
                + channelProtected + "，消息保护=" + messageProtected + "，资料缺失=" + removeSource);
    }

    @Test(timeout = 120000)
    public void l4_videoPlaysPausesResumesAndKeepsOriginalIdentity() throws Exception {
        TLRPC.Message source = source("video", 602, true);
        File file = LocalSavedTagsSamples.videoFile(ui.activity.getCacheDir(), "l4-video-" + UUID.randomUUID() + ".mp4");
        files.add(file);
        source.attachPath = file.getPath();
        source.media.document.size = file.length();
        SavedLinkPreviewView card = show(source, 702);
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(source.media.document)));
        SavedLinkLoadingTest.main(() -> assertTrue(card.openMedia()));
        waitFor(() -> player() != null && player().isPlaying() && player().getCurrentPosition() > 300);
        long[] positions = new long[2];
        SavedLinkLoadingTest.main(() -> {
            assertTrue(player().getDuration() >= 7000);
            player().pause();
            positions[0] = player().getCurrentPosition();
            assertFalse(player().isPlaying());
        });
        SystemClock.sleep(200);
        SavedLinkLoadingTest.main(() -> assertTrue(Math.abs(player().getCurrentPosition() - positions[0]) < 100));
        SavedLinkLoadingTest.main(() -> player().play());
        waitFor(() -> player().isPlaying() && player().getCurrentPosition() > positions[0] + 250);
        SavedLinkLoadingTest.main(() -> {
            positions[1] = player().getCurrentPosition();
            assertFalse(SavedLinkPreviewUiTest.viewerField("allowShare", Boolean.class));
            assertTrue((SavedLinkPreviewUiTest.viewerField("windowLayoutParams", WindowManager.LayoutParams.class).flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertTrue(PhotoViewer.isShowingImage(card.getPreview().message));
            PhotoViewer.getInstance().closePhoto(false, false);
        });
        assertTrue(source.media_unread);
        assertEquals(1, ui.tags.getMessageTags(702).size());
        report("L4 有效视频：字节=" + file.length() + "，暂停=" + positions[0] + "，恢复=" + positions[1] + "，窗口保护及来源身份保留");
    }

    @Test(timeout = 120000)
    public void l4_voicePlaysWithoutMarkingSourceRead() throws Exception {
        audio("voice", 603, 703);
    }

    @Test(timeout = 120000)
    public void l4_musicSharesOnePlayerAcrossSavedCardsAndFilters() throws Exception {
        audio("music", 604, 704);
    }

    private void audio(String type, int sourceId, int savedId) throws Exception {
        TLRPC.Message source = source(type, sourceId, false);
        File file = LocalSavedTagsSamples.audioFile(ui.activity.getCacheDir(), "l4-" + type + "-" + UUID.randomUUID() + ".wav");
        files.add(file);
        source.attachPath = file.getPath();
        source.media.document.mime_type = "audio/wav";
        source.media.document.size = file.length();
        for (TLRPC.DocumentAttribute attribute : source.media.document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                attribute.duration = 8;
            } else if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                attribute.file_name = "有效测试音频.wav";
            }
        }
        SavedLinkPreviewView card = show(source, savedId);
        MediaController player = MediaController.getInstance();
        long received = mediaBytes(0);
        SavedLinkLoadingTest.main(() -> assertTrue(card.openMedia()));
        waitFor(() -> player.isPlayingMessage(card.getPreview().message) && player.getCurrentPosition() > 300);
        long[] position = new long[2];
        SavedLinkLoadingTest.main(() -> {
            assertTrue(card.openMedia());
            assertTrue(player.isMessagePaused());
            position[0] = player.getCurrentPosition();
        });
        waitFor(() -> {
            View controls = SavedLinkCardTest.field(ui.activity.chat, "fragmentContextView", View.class);
            View wrapper = SavedLinkCardTest.field(ui.activity.chat, "fragmentContextViewWrapper", View.class);
            return controls.getHeight() > 0 && wrapper.getHeight() >= controls.getHeight();
        });
        ui.screenshot("l4-" + type + "-paused");
        SavedLinkLoadingTest.main(() -> assertTrue(card.openMedia()));
        waitFor(() -> !player.isMessagePaused() && player.getCurrentPosition() > position[0] + 250);
        SavedLinkLoadingTest.main(() -> {
            position[1] = player.getCurrentPosition();
            assertTrue(position[1] > position[0]);
            assertTrue(player.pauseMessage(card.getPreview().message));
        });
        MessageObject playing = player.getPlayingMessageObject();
        ui.click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "媒体标签"));
        ui.invoke("waitFilter", new Class[]{int.class}, 1);
        assertSame(playing, player.getPlayingMessageObject());
        ui.click(LocaleController.getString(R.string.LocalSavedTagsAll));
        MessageObject second = ui.sample(savedId + 50, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/" + sourceId, null);
        ui.show(second);
        SavedLinkPreviewView shared = ui.card(second.getId());
        assertSame(playing, player.getPlayingMessageObject());
        assertTrue(player.isPlayingMessage(shared.getPreview().message));
        SavedLinkLoadingTest.main(() -> {
            assertFalse(player.isPlayingMessage(new MessageObject(1, source, false, false)));
            UserConfig.selectedAccount = 1;
            try {
                assertFalse(shared.openMedia());
                assertSame(playing, player.getPlayingMessageObject());
            } finally {
                UserConfig.selectedAccount = 0;
            }
        });
        assertTrue("预览播放不能提交来源内容已读", source.media_unread);
        assertEquals(received, mediaBytes(0));
        assertEquals(1, ui.tags.getMessageTags(savedId).size());
        report("L4 " + type + "：有效音频字节=" + file.length() + "，暂停=" + position[0] + "，恢复=" + position[1]
                + "；筛选与卡片回收不重建播放器，新增媒体字节=0，来源内容已读未改变");
    }

    @Test(timeout = 90000)
    public void l4_fileMetadataAndOpenRespectSourceProtection() throws Exception {
        TLRPC.Message source = source("file", 605, false);
        File file = new File(ui.activity.getFilesDir(), "cache/l4-document-" + UUID.randomUUID() + ".txt");
        write(file, "L4 file fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        source.attachPath = file.getPath();
        source.media.document.mime_type = "text/plain";
        source.media.document.size = file.length();
        ((TLRPC.TL_documentAttributeFilename) source.media.document.attributes.get(0)).file_name = "来源资料.txt";
        SavedLinkPreviewView card = show(source, 705);
        String info = SavedLinkCardTest.field(card, "mediaInfo", TextView.class).getText().toString();
        assertTrue(info.contains("来源资料.txt") && info.contains("text/plain") && info.contains(AndroidUtilities.formatFileSize(file.length())));
        Intent[] opened = new Intent[1];
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                opened[0] = intent;
                return new Instrumentation.ActivityResult(0, null);
            }
        };
        ui.instrumentation.addMonitor(monitor);
        try {
            SavedLinkLoadingTest.main(() -> assertTrue(card.openMedia()));
            assertNotNull(opened[0]);
            assertEquals(Intent.ACTION_VIEW, opened[0].getAction());
            assertEquals("text/plain", opened[0].getType());
            TLRPC.Message protectedSource = source("file", 606, true);
            protectedSource.media = source.media;
            protectedSource.attachPath = file.getPath();
            SavedLinkPreviewView protectedCard = show(protectedSource, 706);
            opened[0] = null;
            SavedLinkLoadingTest.main(() -> assertFalse(protectedCard.openMedia()));
            assertNull(opened[0]);
            assertFalse(SavedLinkCardTest.field(protectedCard, "mediaAction", TextView.class).isEnabled());
            assertTrue(SavedLinkCardTest.field(protectedCard, "original", TextView.class).isEnabled());
            assertFalse(protectedCard.getPreview().message.canForwardMessage());
        } finally {
            ui.instrumentation.removeMonitor(monitor);
        }
    }

    @Test(timeout = 90000)
    public void l4_missingAndClearedCacheLoadOnDemandAndShareOneOperation() throws Exception {
        TLRPC.Message source = source("file", 621, false);
        source.media.document.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
        source.media.document.mime_type = "text/plain";
        ((TLRPC.TL_documentAttributeFilename) source.media.document.attributes.get(0)).file_name = "按需文件.txt";
        byte[] bytes = "本轮缓存样本".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        source.media.document.size = bytes.length;
        FileLoader loader = FileLoader.getInstance(0);
        File file = loader.getPathToMessage(source);
        String filename = FileLoader.getAttachFileName(source.media.document);
        assertFalse(file.exists());
        SavedLinkPreviewView first = show(source, 721);
        assertFalse(loader.isLoadingFile(filename));
        Map<String, FileLoadOperation> operations = SavedLinkCardTest.field(loader, "loadOperationPaths", Map.class);
        try {
            SavedLinkLoadingTest.main(() -> assertTrue(first.openMedia()));
            waitFor(() -> operations.containsKey(filename));
            FileLoadOperation operation = operations.get(filename);
            assertEquals(0, operation.currentAccount);
            MessageObject parent = (MessageObject) operation.parentObject;
            assertEquals(-SavedLinkLoadingTest.C1, parent.getDialogId());
            assertEquals(621, parent.getId());
            MessageObject second = ui.sample(722, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/621", null);
            ui.show(second);
            SavedLinkPreviewView shared = ui.card(722);
            assertSame(operation, operations.get(filename));
            assertFalse(first.isAttachedToWindow());
            SavedLinkLoadingTest.main(() -> {
                NotificationCenter.getInstance(1).postNotificationName(NotificationCenter.fileLoadFailed, filename, 0);
                assertFalse(SavedLinkCardTest.field(shared, "mediaFailed", Boolean.class));
                loader.cancelLoadFile(source.media.document);
                NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.fileLoadFailed, filename, 0);
            });
            waitFor(() -> SavedLinkCardTest.field(shared, "mediaFailed", Boolean.class));
            waitFor(() -> !operations.containsKey(filename));
            SavedLinkLoadingTest.main(() -> assertTrue(shared.openMedia()));
            waitFor(() -> operations.containsKey(filename));
            assertNotSame(operation, operations.get(filename));
            loader.cancelLoadFile(source.media.document);
            waitFor(() -> !operations.containsKey(filename));
            // 合成账号只模拟传输完成，真实下载另由服务端媒体用例验证。
            write(file, bytes);
            Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
                @Override
                public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                    assertEquals(Intent.ACTION_VIEW, intent.getAction());
                    return new Instrumentation.ActivityResult(0, null);
                }
            };
            ui.instrumentation.addMonitor(monitor);
            try {
                SavedLinkLoadingTest.main(() -> {
                    NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.fileLoaded, filename, file);
                    assertTrue(shared.openMedia());
                    assertFalse(loader.isLoadingFile(filename));
                });
            } finally {
                ui.instrumentation.removeMonitor(monitor);
            }
            assertTrue(file.delete());
            SavedLinkLoadingTest.main(() -> assertTrue(shared.openMedia()));
            waitFor(() -> operations.containsKey(filename));
            assertEquals(1, ui.fixture.source.messageRequests());
            assertEquals(1, ui.tags.getMessageTags(721).size());
            assertEquals(0, ui.tags.getMessageTags(722).size());
            report("L4 缓存：未缓存及删除媒体缓存后仅点击才加载；来源请求=1；共享下载未被回收取消；失败后显式重试；合成传输完成后复用文件，标签保留");
        } finally {
            loader.cancelLoadFile(source.media.document);
            waitFor(() -> !operations.containsKey(filename));
        }
    }

    @Test(timeout = 90000)
    public void l4_rebindingDoesNotDownloadFullMediaAndSpecialMediaKeepsSourceEntry() throws Exception {
        TLRPC.Message video = source("video", 607, false);
        SavedLinkPreviewView card = show(video, 707);
        String filename = FileLoader.getAttachFileName(video.media.document);
        long bytes = mediaBytes(0);
        for (int i = 0; i < 20; i++) {
            SavedLinkLoadingTest.main(() -> ui.activity.chat.getChatListView().getAdapter().notifyDataSetChanged());
            SystemClock.sleep(40);
            assertFalse(FileLoader.getInstance(0).isLoadingFile(filename));
        }
        assertEquals(bytes, mediaBytes(0));
        assertEquals(1, ui.fixture.source.messageRequests());
        assertFalse(FileLoader.getInstance(0).getPathToMessage(video).exists());
        for (int i = 0; i < 3; i++) {
            TLRPC.Message special = source(i == 0 ? "photo" : "voice", 610 + i, false);
            if (i < 2) {
                special.media.ttl_seconds = i == 0 ? 10 : Integer.MAX_VALUE;
            } else {
                TLRPC.TL_messageMediaPaidMedia paid = new TLRPC.TL_messageMediaPaidMedia();
                paid.stars_amount = 10;
                paid.extended_media.add(new TLRPC.TL_messageExtendedMediaPreview());
                special.media = paid;
            }
            SavedLinkPreviewView view = show(special, 710 + i);
            assertEquals(View.GONE, SavedLinkCardTest.field(view, "image", BackupImageView.class).getVisibility());
            assertFalse(SavedLinkCardTest.field(view, "mediaAction", TextView.class).isEnabled());
            SavedLinkLoadingTest.main(() -> assertFalse(view.openMedia()));
        }
        report("L4 20次重绑：完整媒体新增下载=0，媒体字节增量=0；一次性、阅后即焚及未解锁付费媒体保留原入口");
    }

    TLRPC.Message source(String type, int id, boolean protectedMessage) {
        TLRPC.Message message = SavedLinkLoadingTest.sourceMessage(SavedLinkLoadingTest.C1, id);
        message.message = "来源媒体说明 #原话题";
        message.media = LocalSavedTagsSamples.media(type, id);
        message.noforwards = protectedMessage;
        message.media_unread = true;
        return message;
    }

    SavedLinkPreviewView show(TLRPC.Message source, int savedId) throws Exception {
        ui.fixture.source.remote.put(SavedLinkLoadingTest.C1 + ":" + source.id, source);
        MessageObject message = ui.sample(savedId, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/" + source.id, null);
        SavedLinkLoadingTest.<Void>await(callback -> ui.tags.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        ui.show(message);
        return ui.card(savedId);
    }

    void write(File file, byte[] bytes) throws Exception {
        assertFalse("测试文件不能覆盖原缓存", file.exists());
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        files.add(file);
    }

    static long mediaBytes(int account) {
        long bytes = 0;
        for (int network = 0; network < 3; network++) {
            for (int type : new int[]{StatsController.TYPE_PHOTOS, StatsController.TYPE_VIDEOS, StatsController.TYPE_AUDIOS, StatsController.TYPE_MUSIC, StatsController.TYPE_FILES}) {
                bytes += StatsController.getInstance(account).getReceivedBytesCount(network, type);
            }
        }
        return bytes;
    }

    static void waitFor(java.util.function.BooleanSupplier condition) {
        SavedLinkPreviewUiTest.waitFor(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(), condition, 15000);
    }

    static VideoPlayer player() {
        return SavedLinkPreviewUiTest.viewerField("videoPlayer", VideoPlayer.class);
    }

    static void report(String message) {
        android.os.Bundle status = new android.os.Bundle();
        status.putString("stream", message + "\n");
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }
}
