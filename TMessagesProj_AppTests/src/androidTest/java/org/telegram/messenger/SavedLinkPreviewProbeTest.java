package org.telegram.messenger;

import android.app.Instrumentation;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkPreviewProbeTest {

    static final long CHANNEL = 6294967401L;
    private Instrumentation instrumentation;
    private SavedLinkPreviewProbe probe;
    private Transport transport;
    private MessageObject message;
    private String error;
    private int completions;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertFalse("受控响应只能用于无真实登录账号的隔离环境", UserConfig.getInstance(0).isClientActivated());
        transport = new Transport();
        instrumentation.runOnMainSync(() -> probe = new SavedLinkPreviewProbe(0, transport));
    }

    @After
    public void tearDown() {
        instrumentation.runOnMainSync(() -> {
            if (probe != null) {
                probe.cancel();
            }
            if (UserConfig.getInstance(0).getClientUserId() == LocalSavedTagsTestActivity.USER_A) {
                UserConfig.getInstance(0).clearConfig();
            }
        });
    }

    @Test
    public void l0_publicPostKeepsSourceIdentityAndProtection() {
        load("https://t.me/preview_sample/301?single");
        assertEquals("preview_sample", ((TLRPC.TL_contacts_resolveUsername) transport.requests.get(0)).username);
        TLRPC.TL_channel chat = channel(CHANNEL, true);
        reply(0, resolved(chat));
        TLRPC.TL_channels_getMessages request = (TLRPC.TL_channels_getMessages) transport.requests.get(1);
        assertEquals(CHANNEL, request.channel.channel_id);
        assertEquals(8123L, request.channel.access_hash);
        assertEquals(Integer.valueOf(301), request.id.get(0));
        TLRPC.TL_message original = message(CHANNEL, 301);
        original.noforwards = true;
        reply(1, messages(original));
        assertNull(error);
        assertSame(original, message.messageOwner);
        assertEquals(-CHANNEL, message.getDialogId());
        assertEquals(301, message.getId());
        assertEquals("原消息正文 #话题", message.messageOwner.message);
        assertTrue(message.messageOwner.noforwards);
        assertTrue(probe.source.noforwards);
        assertEquals(1, completions);
    }

    @Test
    public void l0_unknownPrivateSourceIsConfirmedByServerFirst() {
        long id = CHANNEL + 21;
        load("https://telegram.me/c/" + id + "/302");
        TLRPC.TL_channels_getChannels request = (TLRPC.TL_channels_getChannels) transport.requests.get(0);
        assertEquals(id, request.id.get(0).channel_id);
        assertEquals(0, request.id.get(0).access_hash);
        TLRPC.TL_messages_chats response = new TLRPC.TL_messages_chats();
        response.chats.add(channel(id, true));
        reply(0, response);
        reply(1, messages(message(id, 302)));
        assertEquals(-id, message.getDialogId());
        assertEquals(2, transport.requests.size());
    }

    @Test
    public void l0_knownPrivateSourceUsesExistingAccessData() {
        long id = CHANNEL + 22;
        instrumentation.runOnMainSync(() -> MessagesController.getInstance(0).putChat(channel(id, true), false));
        load("https://t.me/c/" + id + "/303");
        assertTrue(transport.requests.get(0) instanceof TLRPC.TL_channels_getMessages);
        reply(0, messages(message(id, 303)));
        assertEquals(-id, message.getDialogId());
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void l0_unsupportedLinksNeverIssueRequests() {
        for (String link : new String[]{"https://t.me.evil.example/name/301", "http://t.me/name/301",
                "https://t.me/name/301?comment=2", "https://t.me/name/301?t=4", "https://t.me/name/301?single&single",
                "https://t.me/name%2Fother/301", "https://t.me/c/12/7/301", "https://t.me/name/0",
                "https://t.me/name/-1", "https://t.me/name/301#other", "https://user@t.me/name/301"}) {
            load(link);
            assertNotNull(link, error);
            assertNull(message);
        }
        assertTrue(transport.requests.isEmpty());
        assertEquals(11, completions);
    }

    @Test
    public void l0_overflowDoesNotTruncateSourceOrMessageId() {
        load("https://t.me/c/9223372036854775808/301");
        assertEquals("消息或频道编号超出范围", error);
        load("https://t.me/preview_sample/2147483648");
        assertEquals("消息或频道编号超出范围", error);
        assertTrue(transport.requests.isEmpty());
    }

    @Test
    public void l0_userTargetDoesNotBecomeAChannel() {
        load("https://t.me/preview_sample/301");
        TLRPC.TL_contacts_resolvedPeer result = new TLRPC.TL_contacts_resolvedPeer();
        result.peer = new TLRPC.TL_peerUser();
        result.peer.user_id = 1234;
        reply(0, result);
        assertEquals("链接目标不是频道帖子", error);
        assertEquals(1, transport.requests.size());
    }

    @Test
    public void l0_sameNumberFromAnotherChannelIsRejected() {
        begin();
        reply(1, messages(message(CHANNEL + 1, 301)));
        assertNull(message);
        assertEquals("消息来源与链接不一致", error);
    }

    @Test
    public void l0_savedDialogIdentityCannotReplaceSource() {
        begin();
        TLRPC.TL_message original = message(CHANNEL, 301);
        original.dialog_id = LocalSavedTagsTestActivity.USER_A;
        reply(1, messages(original));
        assertNull(message);
        assertEquals("消息来源与链接不一致", error);
    }

    @Test
    public void l0_missingResultDoesNotClaimDeletion() {
        begin();
        reply(1, new TLRPC.TL_messages_messages());
        assertNull(message);
        assertEquals("响应未包含目标消息，尚不能判断已删除", error);
    }

    @Test
    public void l0_explicitEmptyResultHasItsOwnState() {
        begin();
        TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
        empty.id = 301;
        reply(1, messages(empty));
        assertNull(message);
        assertEquals("服务器返回空消息", error);
    }

    @Test
    public void l0_accessErrorStopsWithoutForwardJoinOrReadRequest() {
        begin();
        TLRPC.TL_error failure = new TLRPC.TL_error();
        failure.code = 400;
        failure.text = "CHANNEL_PRIVATE";
        instrumentation.runOnMainSync(() -> transport.callbacks.get(1).run(null, failure));
        instrumentation.runOnMainSync(() -> {});
        assertNull(message);
        assertEquals("消息读取失败：CHANNEL_PRIVATE", error);
        assertEquals(2, transport.requests.size());
        assertEquals(1, completions);
    }

    @Test
    public void l0_cancelIgnoresLateSourceResponse() {
        load("https://t.me/preview_sample/301");
        instrumentation.runOnMainSync(probe::cancel);
        reply(0, resolved(channel(CHANNEL, true)));
        assertEquals(1, transport.canceled.size());
        assertEquals(1, transport.requests.size());
        assertEquals(0, completions);
    }

    @Test
    public void l0_duplicateCallbacksDoNotReadOrCompleteTwice() {
        begin();
        reply(0, resolved(channel(CHANNEL, true)));
        assertEquals(2, transport.requests.size());
        reply(1, messages(message(CHANNEL, 301)));
        reply(1, messages(message(CHANNEL, 301)));
        assertEquals(1, completions);
    }

    @Test
    public void l0_accountChangeRejectsLateResultAndNewLoad() {
        begin();
        instrumentation.runOnMainSync(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = LocalSavedTagsTestActivity.USER_A;
            UserConfig.getInstance(0).setCurrentUser(user);
        });
        reply(1, messages(message(CHANNEL, 301)));
        assertEquals(0, completions);
        load("https://t.me/preview_sample/302");
        assertEquals("测试账号已改变，请重新打开原型", error);
        assertEquals(2, transport.requests.size());
    }

    @Test
    public void l0_freshChannelProtectionIsRetained() {
        load("https://t.me/preview_sample/301");
        reply(0, resolved(channel(CHANNEL, false)));
        TLRPC.TL_messages_messages response = messages(message(CHANNEL, 301));
        response.chats.add(channel(CHANNEL, true));
        reply(1, response);
        assertTrue(probe.source.noforwards);
        assertFalse(message.messageOwner.noforwards);
        assertEquals(-CHANNEL, message.getDialogId());
    }

    @Test
    public void l0_liveReaderRequiresAnExistingRealSession() {
        instrumentation.runOnMainSync(() -> {
            probe.cancel();
            probe = new SavedLinkPreviewProbe(0);
        });
        load("https://t.me/preview_sample/301");
        assertEquals("需要已登录的真实测试账号", error);
        assertTrue(probe.requests.isEmpty());
    }

    private void begin() {
        load("https://t.me/preview_sample/301");
        reply(0, resolved(channel(CHANNEL, true)));
    }

    private void load(String link) {
        instrumentation.runOnMainSync(() -> probe.load(link, (result, failure) -> {
            message = result;
            error = failure;
            completions++;
        }));
    }

    private void reply(int index, TLObject response) {
        instrumentation.runOnMainSync(() -> transport.callbacks.get(index).run(response, null));
        // 读取器总把网络回调投递到主线程，断言前等待这次投递处理完毕。
        instrumentation.runOnMainSync(() -> {});
    }

    static TLRPC.TL_channel channel(long id, boolean protectedContent) {
        TLRPC.TL_channel chat = new TLRPC.TL_channel();
        chat.id = id;
        chat.access_hash = 8123;
        chat.title = "隔离频道样本";
        chat.broadcast = true;
        chat.noforwards = protectedContent;
        return chat;
    }

    static TLRPC.TL_message message(long channelId, int id) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.peer_id = new TLRPC.TL_peerChannel();
        message.peer_id.channel_id = channelId;
        message.from_id = message.peer_id;
        message.date = 1700000000;
        message.post = true;
        message.message = "原消息正文 #话题";
        return message;
    }

    private TLRPC.TL_contacts_resolvedPeer resolved(TLRPC.Chat chat) {
        TLRPC.TL_contacts_resolvedPeer response = new TLRPC.TL_contacts_resolvedPeer();
        response.peer = new TLRPC.TL_peerChannel();
        response.peer.channel_id = chat.id;
        response.chats.add(chat);
        return response;
    }

    private TLRPC.TL_messages_messages messages(TLRPC.Message message) {
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        response.messages.add(message);
        return response;
    }

    private static class Transport implements SavedLinkPreviewProbe.Transport {
        final ArrayList<TLObject> requests = new ArrayList<>();
        final ArrayList<RequestDelegate> callbacks = new ArrayList<>();
        final ArrayList<Integer> canceled = new ArrayList<>();

        @Override
        public int send(TLObject request, RequestDelegate callback) {
            assertTrue("原型只允许读取来源或指定消息", request instanceof TLRPC.TL_contacts_resolveUsername
                    || request instanceof TLRPC.TL_channels_getChannels || request instanceof TLRPC.TL_channels_getMessages);
            requests.add(request);
            callbacks.add(callback);
            return requests.size();
        }

        @Override
        public void cancel(int requestId) {
            canceled.add(requestId);
        }
    }
}
