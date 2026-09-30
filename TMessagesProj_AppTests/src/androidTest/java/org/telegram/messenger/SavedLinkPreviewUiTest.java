package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.view.WindowManager;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Components.VideoPlayer;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkPreviewUiTest {

    private Instrumentation instrumentation;
    private SavedLinkPreviewTestActivity activity;
    private final ArrayList<File> files = new ArrayList<>();

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            assertFalse("合成媒体验证不能操作已登录的账号", UserConfig.getInstance(account).isClientActivated());
        }
        instrumentation.runOnMainSync(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = LocalSavedTagsTestActivity.USER_A;
            user.self = true;
            user.first_name = "预览隔离测试";
            user.phone = "";
            UserConfig.getInstance(0).setCurrentUser(user);
            UserConfig.selectedAccount = 0;
            MessagesController.getInstance(0).putUser(user, false);
        });
        activity = start(instrumentation);
        assertNotNull(activity.chat);
    }

    @After
    public void tearDown() {
        instrumentation.runOnMainSync(() -> {
            if (activity != null) {
                activity.finish();
            }
            if (UserConfig.getInstance(0).getClientUserId() == LocalSavedTagsTestActivity.USER_A) {
                UserConfig.getInstance(0).clearConfig();
            }
        });
        for (File file : files) {
            assertTrue("未清理本轮生成的媒体", !file.exists() || file.delete());
        }
    }

    @Test(timeout = 90000)
    public void l0_channelProtectedPhotoUsesOriginalViewerAndSecureWindow() throws Exception {
        photo(true, false);
    }

    @Test(timeout = 90000)
    public void l0_messageProtectedPhotoUsesOriginalViewerAndSecureWindow() throws Exception {
        photo(false, true);
    }

    @Test(timeout = 90000)
    public void l0_protectedVideoActuallyPlaysPausesAndResumes() throws Exception {
        File file = LocalSavedTagsSamples.videoFile(activity.getCacheDir(), "saved-link-l0-video.mp4");
        files.add(file);
        assertTrue(file.length() > 0);
        TLRPC.TL_message original = SavedLinkPreviewProbeTest.message(SavedLinkPreviewProbeTest.CHANNEL + 30, 403);
        original.media = LocalSavedTagsSamples.media("video", 403);
        original.media.document.size = file.length();
        original.attachPath = file.getAbsolutePath();
        TLRPC.TL_channel source = SavedLinkPreviewProbeTest.channel(original.peer_id.channel_id, true);
        instrumentation.runOnMainSync(() -> {
            MessagesController.getInstance(0).putChat(source, false);
            MessageObject message = new MessageObject(0, original, true, true);
            message.attachPathExists = true;
            message.mediaExists = true;
            activity.show(source, message);
            assertTrue(activity.openMedia());
        });
        waitFor(instrumentation, () -> {
            VideoPlayer player = viewerField("videoPlayer", VideoPlayer.class);
            return player != null && player.isPlaying() && player.getCurrentPosition() > 300;
        }, 20000);
        long[] position = new long[2];
        instrumentation.runOnMainSync(() -> {
            assertSecure(activity);
            assertTrue(PhotoViewer.isShowingImage(activity.message));
            VideoPlayer player = viewerField("videoPlayer", VideoPlayer.class);
            player.pause();
            position[0] = player.getCurrentPosition();
            assertFalse(player.isPlaying());
            player.play();
        });
        waitFor(instrumentation, () -> {
            VideoPlayer player = viewerField("videoPlayer", VideoPlayer.class);
            position[1] = player.getCurrentPosition();
            return player.isPlaying() && position[1] > position[0] + 300;
        }, 15000);
        instrumentation.runOnMainSync(() -> {
            PhotoViewer.getInstance().closePhoto(false, false);
            assertEquals(-source.id, activity.message.getDialogId());
            assertEquals(403, activity.message.getId());
            assertNotNull(activity.chat);
            assertFalse(original.media_unread);
        });
        System.out.println("L0 隔离视频：暂停位置=" + position[0] + "，恢复位置=" + position[1]
                + "，有效文件字节=" + file.length() + "，源窗口保护=true");
    }

    @Test
    public void l0_syntheticSessionCannotSendLiveRequests() {
        instrumentation.runOnMainSync(() -> activity.load("https://t.me/preview_sample/301"));
        assertEquals("需要已登录的真实测试账号", activity.status.getText().toString());
        assertTrue(activity.probe.requests.isEmpty());
        assertNull(activity.message);
        assertFalse(activity.mediaButton.isEnabled());
    }

    private void photo(boolean channelProtected, boolean messageProtected) throws Exception {
        int id = channelProtected ? 401 : 402;
        TLRPC.TL_message original = SavedLinkPreviewProbeTest.message(SavedLinkPreviewProbeTest.CHANNEL + id, id);
        original.media = LocalSavedTagsSamples.media("photo", id);
        original.noforwards = messageProtected;
        byte[] bytes = original.media.photo.sizes.get(0).bytes;
        TLRPC.TL_photoSize size = new TLRPC.TL_photoSize();
        size.type = "x";
        size.w = 240;
        size.h = 160;
        size.size = bytes.length;
        size.location = new TLRPC.TL_fileLocationToBeDeprecated();
        size.location.volume_id = -902026091400L - id;
        size.location.local_id = id;
        original.media.photo.sizes.clear();
        original.media.photo.sizes.add(size);
        Bitmap expected = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(expected);
        File file = FileLoader.getInstance(0).getPathToAttach(size);
        assertFalse("隔离图片不得覆盖已有文件", file.exists());
        files.add(file);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
        TLRPC.TL_channel source = SavedLinkPreviewProbeTest.channel(original.peer_id.channel_id, channelProtected);
        instrumentation.runOnMainSync(() -> {
            MessagesController.getInstance(0).putChat(source, false);
            MessageObject message = new MessageObject(0, original, true, true);
            message.mediaExists = true;
            activity.show(source, message);
            assertEquals("原消息正文 #话题", original.message);
            assertTrue(activity.openMedia());
        });
        waitFor(instrumentation, () -> {
            ImageReceiver receiver = viewerField("centerImage", ImageReceiver.class);
            return receiver.getBitmap() != null;
        }, 15000);
        instrumentation.runOnMainSync(() -> {
            assertSecure(activity);
            assertTrue(PhotoViewer.isShowingImage(activity.message));
            Bitmap actual = viewerField("centerImage", ImageReceiver.class).getBitmap();
            assertEquals(expected.getWidth(), actual.getWidth());
            assertEquals(expected.getHeight(), actual.getHeight());
            assertEquals(expected.getPixel(100, 80), actual.getPixel(100, 80));
            assertSame(original, activity.message.messageOwner);
            assertEquals(-source.id, activity.message.getDialogId());
            PhotoViewer.getInstance().closePhoto(false, false);
        });
        expected.recycle();
        System.out.println("L0 隔离图片：实际解码及像素一致，频道保护=" + channelProtected + "，消息保护=" + messageProtected);
    }

    static SavedLinkPreviewTestActivity start(Instrumentation instrumentation) {
        Intent intent = new Intent(instrumentation.getTargetContext(), SavedLinkPreviewTestActivity.class);
        intent.putExtra("account", UserConfig.selectedAccount);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(SavedLinkPreviewTestActivity.class.getName(), null, false);
        SavedLinkPreviewTestActivity activity;
        try {
            instrumentation.getTargetContext().startActivity(intent);
            activity = (SavedLinkPreviewTestActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        assertNotNull("预览测试页面未启动", activity);
        waitFor(instrumentation, activity::hasWindowFocus, 15000);
        return activity;
    }

    static void waitFor(Instrumentation instrumentation, BooleanSupplier condition, int timeout) {
        long deadline = SystemClock.uptimeMillis() + timeout;
        boolean[] result = new boolean[1];
        do {
            instrumentation.runOnMainSync(() -> result[0] = condition.getAsBoolean());
            if (result[0]) {
                return;
            }
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        fail("等待预览或播放器状态超时");
    }

    static void assertSecure(SavedLinkPreviewTestActivity activity) {
        assertNotEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        WindowManager.LayoutParams params = viewerField("windowLayoutParams", WindowManager.LayoutParams.class);
        assertNotEquals(0, params.flags & WindowManager.LayoutParams.FLAG_SECURE);
        Boolean allowShare = viewerField("allowShare", Boolean.class);
        assertFalse("受保护来源不能从预览恢复分享", allowShare);
    }

    static <T> T viewerField(String name, Class<T> type) {
        try {
            Field field = PhotoViewer.class.getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(PhotoViewer.getInstance()));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
