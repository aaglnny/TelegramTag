package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkMediaLiveTest {
    private Instrumentation instrumentation;
    private int account;
    private SavedLinkPreviewController controller;
    private SavedLinkPreviewController.Subscription subscription;
    private SavedLinkPreviewController.Preview preview;
    private SavedLinkPreviewTestActivity activity;
    private TLRPC.Message saved;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        SavedLinkLoadingTest.main(ApplicationLoader::postInitApplication);
        account = UserConfig.selectedAccount;
        UserConfig config = UserConfig.getInstance(account);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertTrue("必须使用已登录的隔离账号", config.isClientActivated());
        assertTrue(config.getClientUserId() != LocalSavedTagsTestActivity.USER_A && config.getClientUserId() != LocalSavedTagsTestActivity.USER_B);
        assertFalse(config.isPremium());
        SavedLinkLoadingTest.main(() -> controller = SavedLinkPreviewController.getInstance(account));
    }

    @After
    public void tearDown() {
        SavedLinkLoadingTest.main(() -> {
            if (subscription != null) {
                subscription.cancel();
            }
            if (PhotoViewer.hasInstance()) {
                PhotoViewer.getInstance().destroyPhotoViewer();
            }
            if (activity != null) {
                activity.finish();
            }
        });
    }

    @Test(timeout = 240000)
    public void l4_liveProtectedPhotoOpensFromProductionSavedCard() throws Exception {
        SavedLinkPreviewView card = find(false);
        BackupImageView thumb = SavedLinkCardTest.field(card, "image", BackupImageView.class);
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> thumb.getImageReceiver().hasImageLoaded()
                && thumb.getImageReceiver().getBitmap() != null, 45000);
        assertEquals(View.VISIBLE, thumb.getVisibility());
        MessageObject message = card.getPreview().message;
        int readBefore = readPosition(card.getPreview().chat);
        long before = SavedLinkMediaTest.mediaBytes(account);
        openCurrentCard();
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            Bitmap bitmap = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap();
            return bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
        }, 45000);
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> SavedLinkPreviewUiTest.viewerField("animationInProgress", Integer.class) == 0, 10000);
        SavedLinkLoadingTest.main(() -> {
            protection(card);
            Bitmap bitmap = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap();
            SavedLinkMediaTest.report("L4 正式卡片真实图片：普通账号，主图解码=" + bitmap.getWidth() + "×" + bitmap.getHeight()
                    + "，来源与收藏身份保留，窗口保护=true，分享禁用=true");
            PhotoViewer.getInstance().closePhoto(false, false);
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> !PhotoViewer.getInstance().isVisible(), 10000);
        File file = FileLoader.getInstance(account).getPathToMessage(message.messageOwner);
        assertTrue("查看后应存在正常媒体缓存", file.exists() && file.length() > 0);
        long modified = file.lastModified();
        long size = file.length();
        long firstBytes = SavedLinkMediaTest.mediaBytes(account) - before;
        openCurrentCard();
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap() != null, 15000);
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> SavedLinkPreviewUiTest.viewerField("animationInProgress", Integer.class) == 0, 10000);
        SavedLinkLoadingTest.main(() -> {
            protection(card);
            PhotoViewer.getInstance().closePhoto(false, false);
        });
        assertEquals(modified, file.lastModified());
        assertEquals(size, file.length());
        assertEquals(readBefore, readPosition(card.getPreview().chat));
        SavedLinkMediaTest.report("L4 正式卡片图片：首次查看媒体字节增量=" + firstBytes + "，缓存字节=" + size
                + "，再次查看缓存文件未重写；服务器已读位置不变");
    }

    @Test(timeout = 240000)
    public void l4_liveProtectedVideoPlaysPausesAndReturnsToProductionCard() throws Exception {
        SavedLinkPreviewView card = find(true);
        int readBefore = readPosition(card.getPreview().chat);
        long before = SavedLinkMediaTest.mediaBytes(account);
        openCurrentCard();
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> SavedLinkMediaTest.player() != null
                && SavedLinkMediaTest.player().isPlaying() && SavedLinkMediaTest.player().getCurrentPosition() > 300, 60000);
        long[] positions = new long[2];
        SavedLinkLoadingTest.main(() -> {
            protection(card);
            SavedLinkMediaTest.player().pause();
            positions[0] = SavedLinkMediaTest.player().getCurrentPosition();
            assertFalse(SavedLinkMediaTest.player().isPlaying());
            SavedLinkMediaTest.player().play();
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            positions[1] = SavedLinkMediaTest.player().getCurrentPosition();
            return SavedLinkMediaTest.player().isPlaying() && positions[1] > positions[0] + 300;
        }, 20000);
        SavedLinkLoadingTest.main(() -> {
            protection(card);
            PhotoViewer.getInstance().closePhoto(false, false);
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> !PhotoViewer.getInstance().isVisible(), 10000);
        assertNotNull(activity.chat);
        assertEquals(readBefore, readPosition(card.getPreview().chat));
        SavedLinkMediaTest.report("L4 正式卡片真实视频：普通账号，暂停=" + positions[0] + "，恢复=" + positions[1]
                + "，媒体字节增量=" + (SavedLinkMediaTest.mediaBytes(account) - before)
                + "；来源身份、窗口保护、分享限制及返回收藏正确；服务器已读位置不变");
    }

    @Test(timeout = 240000)
    public void l4_liveCachedPhotoThumbnailWithAutoDownloadOff() throws Exception {
        cachedThumbnail(false);
    }

    @Test(timeout = 240000)
    public void l4_liveCachedVideoThumbnailWithAutoDownloadOff() throws Exception {
        cachedThumbnail(true);
    }

    private void cachedThumbnail(boolean video) throws Exception {
        DownloadController downloads = DownloadController.getInstance(account);
        boolean[] enabled = new boolean[3];
        SavedLinkLoadingTest.main(() -> {
            enabled[0] = downloads.mobilePreset.enabled;
            enabled[1] = downloads.wifiPreset.enabled;
            enabled[2] = downloads.roamingPreset.enabled;
            downloads.mobilePreset.enabled = false;
            downloads.wifiPreset.enabled = false;
            downloads.roamingPreset.enabled = false;
        });
        try {
            long bytes = SavedLinkMediaTest.mediaBytes(account);
            SavedLinkPreviewView card = find(video);
            ImageReceiver receiver = SavedLinkCardTest.field(card, "image", BackupImageView.class).getImageReceiver();
            SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
                Bitmap bitmap = receiver.getBitmap();
                return bitmap != null && Math.max(bitmap.getWidth(), bitmap.getHeight()) >= 160
                        && receiver.getImageLocation() != null && receiver.getImageLocation().path != null;
            }, 45000);
            MessageObject message = card.getPreview().message;
            String path = receiver.getImageLocation().path;
            boolean frame = path.startsWith("vthumb://0:");
            File file = new File(frame ? path.substring("vthumb://0:".length()) : path);
            assertTrue("清晰预览必须来自已有本地媒体", file.isFile() && file.length() > 0);
            assertFalse(downloads.canDownloadMedia(message));
            assertNull(receiver.getAnimation());
            assertFalse(PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible());
            if (video) {
                assertFalse(FileLoader.getInstance(account).isLoadingFile(FileLoader.getAttachFileName(message.getDocument())));
            }
            SavedLinkLoadingTest.main(() -> {
                assertTrue(card.getPreview().isProtected());
                assertNotEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
                Bitmap bitmap = receiver.getBitmap();
                SavedLinkMediaTest.report("真实" + (video ? "视频" : "图片") + "缓存预览：关闭自动下载，清晰解码="
                        + bitmap.getWidth() + "×" + bitmap.getHeight() + "，来源=" + (frame ? "本地视频静态帧" : "本地图片缓存")
                        + "，未打开播放器，保护标志保留，媒体字节增量=" + (SavedLinkMediaTest.mediaBytes(account) - bytes));
            });
            assertEquals(bytes, SavedLinkMediaTest.mediaBytes(account));
        } finally {
            SavedLinkLoadingTest.main(() -> {
                downloads.mobilePreset.enabled = enabled[0];
                downloads.wifiPreset.enabled = enabled[1];
                downloads.roamingPreset.enabled = enabled[2];
            });
        }
    }

    private SavedLinkPreviewView find(boolean video) throws Exception {
        TLRPC.TL_messages_getHistory input = new TLRPC.TL_messages_getHistory();
        input.peer = new TLRPC.TL_inputPeerSelf();
        input.limit = 100;
        TLRPC.messages_Messages history = (TLRPC.messages_Messages) request(input);
        ArrayList<TLRPC.Message> candidates = new ArrayList<>(history.messages);
        for (TLRPC.Message item : candidates) {
            SavedLinkReference reference = SavedLinkPreviewController.parse(UserConfig.getInstance(account).getClientUserId(), item);
            if (reference.parseState != SavedLinkReference.PARSED) {
                continue;
            }
            CountDownLatch done = new CountDownLatch(1);
            SavedLinkLoadingTest.main(() -> {
                if (subscription != null) {
                    subscription.cancel();
                }
                subscription = controller.subscribe(item, value -> {
                    preview = value;
                    if (!value.loading && (value.message != null || value.error != null)) {
                        done.countDown();
                    }
                });
            });
            assertTrue("已有收藏的来源读取超时", done.await(40, TimeUnit.SECONDS));
            if (preview.message != null && preview.error == null && preview.isProtected()
                    && (video ? preview.message.isVideo() : preview.message.isPhoto())
                    && !preview.message.isSecretMedia() && !preview.message.needDrawBluredPreview()
                    && !preview.message.hasMediaSpoilers() && !preview.message.isHiddenSensitive()
                    && preview.message.messageOwner.ttl == 0 && preview.message.messageOwner.media.ttl_seconds == 0) {
                saved = item;
                break;
            }
        }
        assertNotNull("已有收藏缺少本阶段的真实受保护媒体样本", saved);
        Intent intent = new Intent(instrumentation.getTargetContext(), SavedLinkPreviewTestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra("account", account);
        intent.putExtra("message_id", saved.id);
        intent.putExtra("production_card", true);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(SavedLinkPreviewTestActivity.class.getName(), null, false);
        try {
            instrumentation.getTargetContext().startActivity(intent);
            activity = (SavedLinkPreviewTestActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        assertNotNull(activity);
        SavedLinkPreviewView[] found = new SavedLinkPreviewView[1];
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            RecyclerListView list = activity.chat.getChatListView();
            if (list == null) {
                return false;
            }
            for (int i = 0; i < list.getChildCount(); i++) {
                if (!(list.getChildAt(i) instanceof ChatMessageCell)) {
                    continue;
                }
                ChatMessageCell cell = (ChatMessageCell) list.getChildAt(i);
                SavedLinkPreviewView value = cell.getSavedLinkPreviewView();
                if (cell.getMessageObject().getId() == saved.id && value != null && value.isShown()
                        && value.getPreview() != null && value.getPreview().message != null && !value.getPreview().loading) {
                    assertEquals(saved.message, cell.getMessageObject().messageOwner.message);
                    assertEquals(saved.id, value.getPreview().reference.savedMessageId);
                    found[0] = value;
                    return true;
                }
            }
            return false;
        }, 45000);
        return found[0];
    }

    private void protection(SavedLinkPreviewView card) {
        assertTrue(card.getPreview().isProtected());
        SavedLinkPreviewUiTest.assertSecure(activity);
        assertTrue(PhotoViewer.isShowingImage(card.getPreview().message));
        assertEquals(card.getPreview().reference.sourceDialogId, card.getPreview().message.getDialogId());
        assertEquals(card.getPreview().reference.sourceMessageId, card.getPreview().message.getId());
        assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
    }

    private void openCurrentCard() {
        SavedLinkLoadingTest.main(() -> activity.chat.scrollToMessageId(saved.id, 0, false, 0, true, 0));
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            RecyclerListView list = activity.chat.getChatListView();
            for (int i = 0; i < list.getChildCount(); i++) {
                if (list.getChildAt(i) instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) list.getChildAt(i);
                    SavedLinkPreviewView card = cell.getSavedLinkPreviewView();
                    if (cell.getMessageObject().getId() == saved.id && card != null && card.isShown()
                            && card.getPreview() != null && card.getPreview().message != null) {
                        assertTrue("该媒体需从来源入口查看", SavedLinkCardTest.field(card, "mediaAction", TextView.class).isEnabled());
                        assertTrue(card.openMedia());
                        return true;
                    }
                }
            }
            return false;
        }, 15000);
    }

    private int readPosition(TLRPC.Chat chat) throws Exception {
        TLRPC.TL_channels_getFullChannel input = new TLRPC.TL_channels_getFullChannel();
        input.channel = MessagesController.getInputChannel(chat);
        TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) request(input);
        assertEquals(chat.id, full.full_chat.id);
        for (TLRPC.Chat item : full.chats) {
            if (item.id == chat.id) {
                assertEquals(chat.left, item.left);
                assertEquals(chat.kicked, item.kicked);
            }
        }
        return full.full_chat.read_inbox_max_id;
    }

    private TLObject request(TLObject input) throws Exception {
        TLObject[] result = new TLObject[1];
        String[] error = new String[1];
        CountDownLatch done = new CountDownLatch(1);
        int id = ConnectionsManager.getInstance(account).sendRequest(input, (value, failure) -> {
            result[0] = value;
            error[0] = failure == null ? null : failure.text;
            done.countDown();
        });
        try {
            assertTrue(done.await(45, TimeUnit.SECONDS));
            assertNull(error[0]);
            assertNotNull(result[0]);
            return result[0];
        } finally {
            ConnectionsManager.getInstance(account).cancelRequest(id, true);
        }
    }
}
