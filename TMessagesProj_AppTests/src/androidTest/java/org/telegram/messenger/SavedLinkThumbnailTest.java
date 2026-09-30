package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.Base64;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.PhotoViewer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.main;
import static org.telegram.messenger.SavedLinkMediaTest.waitFor;

@RunWith(AndroidJUnit4.class)
public class SavedLinkThumbnailTest {
    private final SavedLinkMediaTest media = new SavedLinkMediaTest();
    private boolean mobile;
    private boolean wifi;
    private boolean roaming;

    @Before
    public void setUp() throws Exception {
        media.setUp();
        main(() -> {
            DownloadController controller = DownloadController.getInstance(0);
            mobile = controller.mobilePreset.enabled;
            wifi = controller.wifiPreset.enabled;
            roaming = controller.roamingPreset.enabled;
            controller.mobilePreset.enabled = false;
            controller.wifiPreset.enabled = false;
            controller.roamingPreset.enabled = false;
        });
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            DownloadController controller = DownloadController.getInstance(0);
            controller.mobilePreset.enabled = mobile;
            controller.wifiPreset.enabled = wifi;
            controller.roamingPreset.enabled = roaming;
        });
        media.tearDown();
    }

    @Test(timeout = 90000)
    public void l4_cachedPhotoReplacesStrippedWhenAutoDownloadIsOff() throws Exception {
        TLRPC.Message source = source(false, 801);
        TLRPC.PhotoSize size = thumbnail(320, 180, true, false);
        source.media.photo.sizes.add(size);
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1801);
        assertFalse(DownloadController.getInstance(0).canDownloadMedia(card.getPreview().message));
        assertClear(card, 300, "photo-cache", true);
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
        assertEquals(1, media.ui.fixture.source.messageRequests());
    }

    @Test(timeout = 90000)
    public void l4_cachedVideoCoverReplacesStrippedWithoutLoadingVideo() throws Exception {
        TLRPC.Message source = source(true, 802);
        source.media.document.thumbs.add(thumbnail(320, 180, true, true));
        File video = FileLoader.getInstance(0).getPathToMessage(source);
        assertFalse(video.exists());
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1802);
        assertClear(card, 300, "video-cover-cache", true);
        assertFalse(video.exists());
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(source.media.document)));
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 120000)
    public void l4_cachedVideoFileSuppliesStillCoverWithoutPlayback() throws Exception {
        cachedVideo(false);
    }

    @Test(timeout = 120000)
    public void l4_cachedVideoWithoutThumbnailsSuppliesStillCover() throws Exception {
        cachedVideo(true);
    }

    private void cachedVideo(boolean withoutThumbs) throws Exception {
        TLRPC.Message source = source(true, 803);
        if (withoutThumbs) {
            source.media.document.thumbs.clear();
        }
        File video = LocalSavedTagsSamples.videoFile(media.ui.activity.getCacheDir(), "thumbnail-video-" + UUID.randomUUID() + ".mp4");
        media.files.add(video);
        source.attachPath = video.getPath();
        source.media.document.size = video.length();
        long modified = video.lastModified();
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1803);
        assertClear(card, 200, withoutThumbs ? "video-without-thumbs" : "video-file-cache", false);
        assertNull(receiver(card).getAnimation());
        assertFalse(PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible());
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(source.media.document)));
        assertEquals(modified, video.lastModified());
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 90000)
    public void l4_largerPhotoCacheIsUsedWhenSmallVariantIsMissing() throws Exception {
        TLRPC.Message source = source(false, 804);
        TLRPC.PhotoSize missing = thumbnail(320, 180, false, false);
        source.media.photo.sizes.add(missing);
        source.media.photo.sizes.add(thumbnail(960, 540, true, false));
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1804);
        assertClear(card, 300, "larger-photo-cache", true);
        assertFalse(FileLoader.getInstance(0).getPathToAttach(missing).exists());
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(missing)));
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 90000)
    public void l4_strippedOnlyVideoDoesNotStartFullDownload() throws Exception {
        TLRPC.Message source = source(true, 805);
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1805);
        ImageReceiver receiver = receiver(card);
        waitFor(() -> receiver.getBitmap() != null);
        main(() -> {
            assertEquals(32, receiver.getBitmap().getWidth());
            assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(source.media.document)));
        });
        assertFalse(FileLoader.getInstance(0).getPathToMessage(source).exists());
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
        SavedLinkMediaTest.report("缩略图对照：仅有32×32占位图时，不自动下载或播放完整视频。");
    }

    @Test(timeout = 90000)
    public void l4_uncachedPhotoKeepsPlaceholderWhenAutoDownloadIsOff() throws Exception {
        TLRPC.Message source = source(false, 806);
        TLRPC.PhotoSize missing = thumbnail(320, 180, false, false);
        source.media.photo.sizes.add(missing);
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        SavedLinkPreviewView card = media.show(source, 1806);
        waitFor(() -> receiver(card).getBitmap() != null);
        assertEquals(32, receiver(card).getBitmap().getWidth());
        assertFalse(FileLoader.getInstance(0).getPathToAttach(missing).exists());
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(missing)));
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 90000)
    public void l4_photoCacheArrivalUpgradesVisiblePlaceholder() throws Exception {
        TLRPC.Message source = source(false, 807);
        TLRPC.PhotoSize size = thumbnail(320, 180, false, false);
        source.media.photo.sizes.add(size);
        SavedLinkPreviewView card = media.show(source, 1807);
        waitFor(() -> receiver(card).getBitmap() != null);
        assertEquals(32, receiver(card).getBitmap().getWidth());
        File file = FileLoader.getInstance(0).getPathToAttach(size);
        media.write(file, pixels(320, 180));
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(size), file));
        assertClear(card, 300, "photo-cache-arrival", true);
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 120000)
    public void l4_videoCacheArrivalUpgradesVisiblePlaceholder() throws Exception {
        TLRPC.Message source = source(true, 808);
        File video = FileLoader.getInstance(0).getPathToMessage(source);
        assertFalse(video.exists());
        SavedLinkPreviewView card = media.show(source, 1808);
        waitFor(() -> receiver(card).getBitmap() != null);
        assertEquals(32, receiver(card).getBitmap().getWidth());
        assertTrue(video.getParentFile().isDirectory() || video.getParentFile().mkdirs());
        LocalSavedTagsSamples.videoFile(video.getParentFile(), video.getName());
        media.files.add(video);
        long bytes = SavedLinkMediaTest.mediaBytes(0);
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(source.media.document), video));
        assertClear(card, 200, "video-cache-arrival", false);
        assertNull(receiver(card).getAnimation());
        assertFalse(PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible());
        assertFalse(FileLoader.getInstance(0).isLoadingFile(FileLoader.getAttachFileName(source.media.document)));
        assertEquals(bytes, SavedLinkMediaTest.mediaBytes(0));
    }

    @Test(timeout = 90000)
    public void l4_recycledCardRejectsPendingCacheResult() throws Exception {
        TLRPC.Message source = source(false, 809);
        TLRPC.PhotoSize size = thumbnail(320, 180, false, false);
        source.media.photo.sizes.add(size);
        SavedLinkPreviewView card = media.show(source, 1809);
        ImageReceiver receiver = receiver(card);
        waitFor(() -> receiver.getBitmap() != null);
        File file = FileLoader.getInstance(0).getPathToAttach(size);
        media.write(file, pixels(320, 180));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Utilities.globalQueue.postRunnable(() -> {
            started.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            main(() -> {
                card.didReceivedNotification(NotificationCenter.fileLoaded, 0, FileLoader.getAttachFileName(size), file);
                card.bind(null, null, true, null);
            });
        } finally {
            release.countDown();
        }
        CountDownLatch done = new CountDownLatch(1);
        Utilities.globalQueue.postRunnable(() -> AndroidUtilities.runOnUIThread(done::countDown));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        main(() -> {
            assertNull(card.getPreview());
            assertNull(receiver.getBitmap());
            assertEquals(View.GONE, card.getVisibility());
        });
    }

    private TLRPC.Message source(boolean video, int id) {
        TLRPC.Message source = media.source(video ? "video" : "photo", id, false);
        ArrayList<TLRPC.PhotoSize> sizes;
        if (video) {
            source.media.document.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
            source.media.document.dc_id = 4;
            sizes = source.media.document.thumbs;
        } else {
            source.media.photo.id = Math.abs(UUID.randomUUID().getMostSignificantBits());
            source.media.photo.dc_id = 4;
            sizes = source.media.photo.sizes;
        }
        sizes.clear();
        TLRPC.TL_photoStrippedSize stripped = new TLRPC.TL_photoStrippedSize();
        stripped.type = "i";
        // 与服务端相同的32×32精简JPEG，不能用完整内嵌图片代替模糊占位。
        stripped.bytes = Base64.decode("ASAgSiiiqJCiiigAooooAKKKKAM=", Base64.NO_WRAP);
        Bitmap bitmap = ImageLoader.getStrippedPhotoBitmap(stripped.bytes, "b");
        assertNotNull(bitmap);
        assertEquals(32, bitmap.getWidth());
        bitmap.recycle();
        sizes.add(stripped);
        return source;
    }

    private TLRPC.PhotoSize thumbnail(int width, int height, boolean cached, boolean cacheDirectory) throws Exception {
        byte[] bytes = pixels(width, height);
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = width > 320 ? "x" : "m";
        size.w = width;
        size.h = height;
        size.size = bytes.length;
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.dc_id = 4;
        size.location.volume_id = -Math.abs(UUID.randomUUID().getMostSignificantBits());
        size.location.local_id = Math.abs(UUID.randomUUID().hashCode());
        File path = FileLoader.getInstance(0).getPathToAttach(size, cacheDirectory);
        assertFalse(path.exists());
        if (cached) {
            media.write(path, bytes);
        }
        return size;
    }

    private byte[] pixels(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.RED);
        Paint paint = new Paint();
        paint.setColor(Color.BLUE);
        canvas.drawRect(width / 2f, 0, width, height, paint);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        bitmap.recycle();
        return output.toByteArray();
    }

    private ImageReceiver receiver(SavedLinkPreviewView card) {
        BackupImageView view = SavedLinkCardTest.field(card, "image", BackupImageView.class);
        assertEquals(View.VISIBLE, view.getVisibility());
        return view.getImageReceiver();
    }

    private void assertClear(SavedLinkPreviewView card, int width, String name, boolean colors) throws Exception {
        ImageReceiver receiver = receiver(card);
        try {
            waitFor(() -> receiver.getBitmap() != null && receiver.getBitmap().getWidth() >= width);
        } finally {
            Bitmap bitmap = receiver.getBitmap();
            SavedLinkMediaTest.report("缩略图场景=" + name + "，自动下载关闭，实际解码="
                    + (bitmap == null ? "无图片" : bitmap.getWidth() + "×" + bitmap.getHeight())
                    + "，图片键=" + receiver.getImageKey());
            String run = InstrumentationRegistry.getArguments().getString("evidence", "thumbnail");
            media.ui.screenshot(run + "-" + name);
        }
        if (colors) {
            main(() -> {
                Bitmap bitmap = receiver.getBitmap();
                int left = bitmap.getPixel(bitmap.getWidth() / 4, bitmap.getHeight() / 2);
                int right = bitmap.getPixel(bitmap.getWidth() * 3 / 4, bitmap.getHeight() / 2);
                assertTrue("左半应来自清晰缓存的红色区域", Color.red(left) > 200 && Color.blue(left) < 40);
                assertTrue("右半应来自清晰缓存的蓝色区域", Color.blue(right) > 200 && Color.red(right) < 40);
            });
        }
        assertEquals(1, media.ui.tags.getMessageTags(card.getPreview().reference.savedMessageId).size());
    }
}
