package org.telegram.messenger;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.Vector;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Components.VideoPlayer;

import java.util.LinkedHashSet;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkPreviewLiveTest {

    private Instrumentation instrumentation;
    private Bundle arguments;
    private int account;
    private SavedLinkPreviewProbe probe;
    private SavedLinkPreviewTestActivity activity;
    private static Bundle savedSamples;
    private static long savedSamplesUser;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        arguments = InstrumentationRegistry.getArguments();
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        account = Integer.parseInt(arguments.getString("account", Integer.toString(UserConfig.selectedAccount)));
        assertTrue("必须指定已登录的隔离测试账号", account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT);
        assertEquals("请先在测试应用切换到指定账号", account, UserConfig.selectedAccount);
        assertTrue("真实服务端验证需要登录账号", UserConfig.getInstance(account).isClientActivated());
        long id = UserConfig.getInstance(account).getClientUserId();
        assertTrue("不能以合成身份替代真实账号", id != LocalSavedTagsTestActivity.USER_A && id != LocalSavedTagsTestActivity.USER_B);
        assertFalse("L0 必须用普通账号完成", UserConfig.getInstance(account).isPremium());
        instrumentation.runOnMainSync(() -> probe = new SavedLinkPreviewProbe(account));
        if ("saved".equals(arguments.getString("samples"))) {
            if (savedSamples == null) {
                savedSamples = findSavedSamples();
                savedSamplesUser = id;
            }
            assertTrue("收藏样本不能跨账号使用", savedSamplesUser == id);
            arguments.putAll(savedSamples);
        }
    }

    @After
    public void tearDown() {
        instrumentation.runOnMainSync(() -> {
            if (probe != null) {
                probe.cancel();
            }
            if (activity != null) {
                activity.finish();
            }
        });
    }

    @Test(timeout = 60000)
    public void l0_liveAccountIsReady() throws Exception {
        TLRPC.TL_users_getUsers request = new TLRPC.TL_users_getUsers();
        request.id.add(new TLRPC.TL_inputUserSelf());
        TLObject response = request(request);
        assertTrue("自账号响应类型不正确", response instanceof Vector);
        Vector<?> users = (Vector<?>) response;
        assertEquals(1, users.objects.size());
        assertTrue(users.objects.get(0) instanceof TLRPC.User);
        TLRPC.User user = (TLRPC.User) users.objects.get(0);
        assertTrue("服务端身份与当前账号不一致", user.id == UserConfig.getInstance(account).getClientUserId());
        assertTrue("服务端未确认当前用户", user.self);
        assertFalse("L0 必须用服务端确认的普通账号完成", user.premium);
        String suffix = arguments.getString("phone_suffix");
        if (suffix != null) {
            assertTrue("当前账号与指定测试账号不一致", user.phone != null && user.phone.endsWith(suffix));
        }
        report("L0 真实账号：已登录=true，账号槽位=" + account
                + "，普通账号=true，自账号服务端校验=true");
    }

    @Test(timeout = 120000)
    public void l0_livePublicProtectedPostIsReadable() throws Exception {
        MessageObject message = read("public_post", true);
        assertTrue("样本必须实际启用内容保护", probe.source.noforwards || message.messageOwner.noforwards);
        assertTrue("公开样本必须来自公开频道", ChatObject.isPublic(probe.source));
        int readBefore = sourceReadPosition(probe.source);
        read("public_post", true);
        assertEquals("预览读取不能推进来源已读位置", readBefore, sourceReadPosition(probe.source));
        report("真实公开受保护帖子：重复读取成功，服务器已读位置未改变，未改变频道加入状态");
    }

    @Test(timeout = 120000)
    public void l0_livePrivateProtectedPostIsReadable() throws Exception {
        MessageObject message = read("private_post", true);
        assertTrue("样本必须实际启用内容保护", probe.source.noforwards || message.messageOwner.noforwards);
        assertFalse("私有样本必须来自私有频道", ChatObject.isPublic(probe.source));
        assertFalse("测试账号必须已加入私有频道", probe.source.left || probe.source.kicked);
    }

    @Test(timeout = 60000)
    public void l0_liveOrdinaryPostIsReadable() throws Exception {
        MessageObject message = read("ordinary_post", true);
        assertFalse("普通帖子样本不能用受保护帖子替代", probe.source.noforwards || message.messageOwner.noforwards);
    }

    @Test(timeout = 120000)
    public void l0_liveProtectedPhotoIsDecodedInSavedContext() throws Exception {
        MessageObject message = read("photo_post", true);
        assertTrue(message.isPhoto());
        assertTrue(probe.source.noforwards || message.messageOwner.noforwards);
        int readBefore = sourceReadPosition(probe.source);
        activity = SavedLinkPreviewUiTest.start(instrumentation);
        instrumentation.runOnMainSync(() -> {
            activity.show(probe.source, message);
            assertTrue(activity.openMedia());
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            ImageReceiver receiver = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class);
            Bitmap bitmap = receiver.getBitmap();
            return receiver.hasImageLoaded() && bitmap != null && bitmap.getWidth() > 0 && bitmap.getHeight() > 0;
        }, 45000);
        instrumentation.runOnMainSync(() -> {
            SavedLinkPreviewUiTest.assertSecure(activity);
            assertTrue(PhotoViewer.isShowingImage(message));
            Bitmap bitmap = SavedLinkPreviewUiTest.viewerField("centerImage", ImageReceiver.class).getBitmap();
            report("真实受保护图片：主图已解码=" + bitmap.getWidth() + "×" + bitmap.getHeight()
                    + "，来源身份一致=true，窗口保护=true，分享禁用=true");
            PhotoViewer.getInstance().closePhoto(false, false);
            assertNotNull(activity.chat);
        });
        assertEquals("查看图片不能推进来源已读位置", readBefore, sourceReadPosition(probe.source));
        report("真实图片查看后：服务器已读位置未改变，未改变频道加入状态");
    }

    @Test(timeout = 120000)
    public void l0_liveProtectedVideoPlaysPausesAndResumes() throws Exception {
        MessageObject message = read("video_post", true);
        assertTrue(message.isVideo());
        assertTrue(probe.source.noforwards || message.messageOwner.noforwards);
        int readBefore = sourceReadPosition(probe.source);
        activity = SavedLinkPreviewUiTest.start(instrumentation);
        instrumentation.runOnMainSync(() -> {
            activity.show(probe.source, message);
            assertTrue(activity.openMedia());
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            VideoPlayer player = SavedLinkPreviewUiTest.viewerField("videoPlayer", VideoPlayer.class);
            return player != null && player.isPlaying() && player.getCurrentPosition() > 300;
        }, 45000);
        long[] position = new long[2];
        instrumentation.runOnMainSync(() -> {
            SavedLinkPreviewUiTest.assertSecure(activity);
            VideoPlayer player = SavedLinkPreviewUiTest.viewerField("videoPlayer", VideoPlayer.class);
            player.pause();
            position[0] = player.getCurrentPosition();
            assertFalse(player.isPlaying());
            player.play();
        });
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> {
            VideoPlayer player = SavedLinkPreviewUiTest.viewerField("videoPlayer", VideoPlayer.class);
            position[1] = player.getCurrentPosition();
            return player.isPlaying() && position[1] > position[0] + 300;
        }, 20000);
        instrumentation.runOnMainSync(() -> {
            assertTrue(PhotoViewer.isShowingImage(message));
            PhotoViewer.getInstance().closePhoto(false, false);
            assertNotNull(activity.chat);
            report("真实受保护视频：暂停位置=" + position[0] + "，恢复位置=" + position[1]
                    + "，来源身份一致=true，窗口保护=true，分享禁用=true，返回收藏=true");
        });
        assertEquals("播放视频不能推进来源已读位置", readBefore, sourceReadPosition(probe.source));
        report("真实视频播放后：服务器已读位置未改变，未改变频道加入状态");
    }

    @Test(timeout = 60000)
    public void l0_liveUnavailableTargetDoesNotShowContent() throws Exception {
        assertNull(read("unavailable_post", false));
    }

    private MessageObject read(String key, boolean available) throws Exception {
        String link = arguments.getString(key);
        assertNotNull("缺少指定的真实样本参数：" + key, link);
        CountDownLatch done = new CountDownLatch(1);
        MessageObject[] result = new MessageObject[1];
        String[] error = new String[1];
        int requestStart = probe.requests.size();
        instrumentation.runOnMainSync(() -> probe.load(link, (message, failure) -> {
            result[0] = message;
            error[0] = failure;
            done.countDown();
        }));
        assertTrue("真实读取未在期限内完成", done.await(30, TimeUnit.SECONDS));
        if (available) {
            assertNull("指定可读样本读取失败", error[0]);
            assertNotNull(result[0]);
            assertTrue("消息会话必须保留来源身份", result[0].getDialogId() == -probe.source.id);
            assertTrue(result[0].getId() > 0);
            assertTrue("消息频道必须保留来源身份", result[0].messageOwner.peer_id.channel_id == probe.source.id);
        } else {
            assertNull(result[0]);
            assertNotNull(error[0]);
            assertTrue("网络错误不能代替无权限验证", error[0].contains("CHANNEL_PRIVATE")
                    || error[0].contains("CHANNEL_INVALID") || error[0].contains("来源已不可访问"));
        }
        for (int i = requestStart; i < probe.requests.size(); i++) {
            String type = probe.requests.get(i);
            assertTrue("原型只能发送来源解析或消息读取请求", type.equals("TL_contacts_resolveUsername")
                    || type.equals("TL_channels_getChannels") || type.equals("TL_channels_getMessages"));
        }
        report("真实样本=" + key + "，可读=" + available + "，原型请求="
                + probe.requests.subList(requestStart, probe.requests.size()));
        return result[0];
    }

    private void report(String text) {
        Bundle status = new Bundle();
        status.putString("stream", text + "\n");
        instrumentation.sendStatus(2, status);
    }

    private Bundle findSavedSamples() throws Exception {
        Bundle samples = new Bundle();
        LinkedHashSet<String> links = new LinkedHashSet<>();
        int offset = 0;
        int pages = Integer.parseInt(arguments.getString("saved_pages", "1"));
        assertTrue("收藏样本单轮最多读取三页", pages > 0 && pages <= 3);
        int messages = 0;
        for (int page = 0; page < pages; page++) {
            TLRPC.TL_messages_getHistory historyRequest = new TLRPC.TL_messages_getHistory();
            historyRequest.peer = new TLRPC.TL_inputPeerSelf();
            historyRequest.limit = 100;
            historyRequest.offset_id = offset;
            TLObject response = request(historyRequest);
            assertTrue("收藏历史响应不正确", response instanceof TLRPC.messages_Messages);
            TLRPC.messages_Messages history = (TLRPC.messages_Messages) response;
            instrumentation.runOnMainSync(() -> {
                MessagesController.getInstance(account).putUsers(history.users, false);
                MessagesController.getInstance(account).putChats(history.chats, false);
            });
            for (TLRPC.Message message : history.messages) {
                messages++;
                offset = message.id;
                if (message.message == null || message.peer_id == null
                        || message.peer_id.user_id != UserConfig.getInstance(account).getClientUserId()) {
                    continue;
                }
                TreeMap<Integer, String> candidates = new TreeMap<>();
                Matcher matcher = AndroidUtilities.WEB_URL.matcher(message.message);
                while (matcher.find()) {
                    candidates.put(matcher.start(), matcher.group());
                }
                for (TLRPC.MessageEntity entity : message.entities) {
                    if (entity.offset < 0 || entity.length <= 0 || entity.offset > message.message.length() - entity.length) {
                        continue;
                    }
                    if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                        candidates.put(entity.offset, entity.url);
                    } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                        candidates.put(entity.offset, message.message.substring(entity.offset, entity.offset + entity.length));
                    }
                }
                for (String link : candidates.values()) {
                    if (link == null) {
                        continue;
                    }
                    Uri uri = Uri.parse(link);
                    if ("https".equalsIgnoreCase(uri.getScheme())
                            && ("t.me".equalsIgnoreCase(uri.getHost()) || "telegram.me".equalsIgnoreCase(uri.getHost()))) {
                        links.add(link);
                    }
                }
            }
            if (history.messages.size() < historyRequest.limit) {
                break;
            }
        }
        report("收藏样本范围：历史消息=" + messages + "，去重后的 Telegram 链接=" + links.size());
        int checked = 0;
        long videoSize = Long.MAX_VALUE;
        for (String link : links) {
            if (checked == 40) {
                break;
            }
            CountDownLatch done = new CountDownLatch(1);
            MessageObject[] result = new MessageObject[1];
            String[] error = new String[1];
            instrumentation.runOnMainSync(() -> probe.load(link, (message, failure) -> {
                result[0] = message;
                error[0] = failure;
                done.countDown();
            }));
            assertTrue("收藏链接样本读取超时", done.await(30, TimeUnit.SECONDS));
            checked++;
            if (result[0] == null) {
                boolean denied = error[0] != null && (error[0].contains("CHANNEL_PRIVATE")
                        || error[0].contains("CHANNEL_INVALID") || error[0].contains("来源已不可访问"));
                if (denied && !samples.containsKey("unavailable_post")) {
                    samples.putString("unavailable_post", link);
                }
                Uri uri = Uri.parse(link);
                report("收藏链接样本 " + checked + "：可读=false，明确不可访问=" + denied
                        + "，路径段数=" + uri.getPathSegments().size() + "，含查询参数=" + (uri.getQuery() != null)
                        + "，结果=" + error[0]);
                continue;
            }
            MessageObject message = result[0];
            boolean publicSource = ChatObject.isPublic(probe.source);
            boolean protectedContent = probe.source.noforwards || message.messageOwner.noforwards;
            if (protectedContent) {
                String key = publicSource ? "public_post" : "private_post";
                if ((publicSource || !probe.source.left && !probe.source.kicked) && !samples.containsKey(key)) {
                    samples.putString(key, link);
                }
                boolean ordinaryMedia = !message.isSecretMedia() && !message.needDrawBluredPreview()
                        && message.messageOwner.ttl == 0
                        && (message.messageOwner.media == null || message.messageOwner.media.ttl_seconds == 0);
                if (ordinaryMedia && message.isPhoto() && !samples.containsKey("photo_post")) {
                    samples.putString("photo_post", link);
                } else if (ordinaryMedia && message.isVideo() && message.getDocument().size < videoSize) {
                    samples.putString("video_post", link);
                    videoSize = message.getDocument().size;
                }
            } else if (!samples.containsKey("ordinary_post")) {
                samples.putString("ordinary_post", link);
            }
            report("收藏链接样本 " + checked + "：可读=true，公开=" + publicSource + "，内容保护="
                    + protectedContent + "，图片=" + message.isPhoto() + "，视频=" + message.isVideo());
            if (samples.size() == 6) {
                break;
            }
        }
        report("收藏样本选择结束：已检查=" + checked + "，公开受保护=" + samples.containsKey("public_post")
                + "，私有受保护=" + samples.containsKey("private_post") + "，普通=" + samples.containsKey("ordinary_post")
                + "，受保护图片=" + samples.containsKey("photo_post") + "，受保护视频=" + samples.containsKey("video_post")
                + "，明确不可访问=" + samples.containsKey("unavailable_post"));
        return samples;
    }

    private int sourceReadPosition(TLRPC.Chat source) throws Exception {
        TLRPC.TL_channels_getFullChannel request = new TLRPC.TL_channels_getFullChannel();
        request.channel = MessagesController.getInputChannel(source);
        TLObject response = request(request);
        assertTrue("来源状态响应不正确", response instanceof TLRPC.TL_messages_chatFull);
        TLRPC.TL_messages_chatFull result = (TLRPC.TL_messages_chatFull) response;
        assertNotNull(result.full_chat);
        assertTrue("来源状态不能串频道", result.full_chat.id == source.id);
        TLRPC.Chat current = null;
        for (TLRPC.Chat chat : result.chats) {
            if (chat.id == source.id) {
                current = chat;
                break;
            }
        }
        assertNotNull("来源状态缺少频道资料", current);
        assertEquals("预览不能改变频道加入状态", source.left, current.left);
        assertEquals("预览不能改变频道访问状态", source.kicked, current.kicked);
        return result.full_chat.read_inbox_max_id;
    }

    private TLObject request(TLObject request) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        TLObject[] response = new TLObject[1];
        String[] error = new String[1];
        int requestId = ConnectionsManager.getInstance(account).sendRequest(request, (result, failure) -> {
            response[0] = result;
            error[0] = failure == null ? null : failure.text;
            done.countDown();
        });
        try {
            assertTrue("真实服务端请求超时：" + request.getClass().getSimpleName(), done.await(45, TimeUnit.SECONDS));
            assertNull("真实服务端请求失败：" + request.getClass().getSimpleName(), error[0]);
            return response[0];
        } finally {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        }
    }
}
