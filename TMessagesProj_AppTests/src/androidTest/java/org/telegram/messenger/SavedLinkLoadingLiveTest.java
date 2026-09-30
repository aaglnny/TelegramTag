package org.telegram.messenger;

import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkLoadingLiveTest {
    private int account;
    private SavedLinkPreviewController controller;
    private final ArrayList<String> requests = new ArrayList<>();
    private SavedLinkPreviewController.Preview preview;
    private SavedLinkPreviewController.Subscription subscription;

    @Before
    public void setUp() {
        SavedLinkLoadingTest.main(ApplicationLoader::postInitApplication);
        account = UserConfig.selectedAccount;
        UserConfig config = UserConfig.getInstance(account);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        assertTrue("真实验证需要已登录的独立隔离账号", config.isClientActivated());
        assertTrue(config.getClientUserId() != SavedLinkReferenceTest.USER_A && config.getClientUserId() != SavedLinkReferenceTest.USER_B);
        assertFalse(config.isPremium());
        SavedLinkLoadingTest.main(() -> controller = new SavedLinkPreviewController(account,
                new SavedLinkPreviewStorage(account), new SavedLinkPreviewController.Backend(account) {
            @Override
            int send(TLObject request, RequestDelegate callback) {
                assertTrue(request instanceof TLRPC.TL_contacts_resolveUsername
                        || request instanceof TLRPC.TL_channels_getChannels || request instanceof TLRPC.TL_channels_getMessages);
                requests.add(request.getClass().getSimpleName());
                return super.send(request, callback);
            }
        }));
    }

    @After
    public void tearDown() {
        if (controller != null) {
            SavedLinkLoadingTest.main(controller::cleanup);
        }
    }

    @Test(timeout = 180000)
    public void l2_liveSavedLinkReadsThroughProductionCacheAndReusesTwentyTimes() throws Exception {
        TLRPC.TL_messages_getHistory history = new TLRPC.TL_messages_getHistory();
        history.peer = new TLRPC.TL_inputPeerSelf();
        history.limit = 100;
        TLObject response = request(history);
        assertTrue(response instanceof TLRPC.messages_Messages);
        TLRPC.Message selected = null;
        for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
            if (message.id <= 0 || message.peer_id == null || message.peer_id.user_id != UserConfig.getInstance(account).getClientUserId()) {
                continue;
            }
            SavedLinkReference reference = SavedLinkPreviewController.parse(UserConfig.getInstance(account).getClientUserId(), message);
            if (reference.parseState != SavedLinkReference.PARSED || reference.username == null) {
                continue;
            }
            bind(message);
            if (preview.message != null && preview.error == null) {
                selected = message;
                break;
            }
        }
        assertNotNull("收藏中需要已有可访问的公开帖子链接", selected);
        int requestsBefore = requests.size();
        int parses = controller.getParseCount();
        int readBefore = readPosition(preview.chat);
        long sourceDialog = preview.message.getDialogId();
        int sourceMessage = preview.message.getId();
        int savedId = selected.id;
        String originalText = selected.message;
        for (int i = 0; i < 20; i++) {
            bind(selected);
            assertEquals(sourceDialog, preview.message.getDialogId());
            assertEquals(sourceMessage, preview.message.getId());
            assertEquals(savedId, preview.reference.savedMessageId);
            assertEquals(originalText, selected.message);
        }
        assertEquals(requestsBefore, requests.size());
        assertEquals(parses, controller.getParseCount());
        assertEquals(readBefore, readPosition(preview.chat));
        Bundle status = new Bundle();
        status.putString("stream", "L2 真实普通账号：从已有收藏读取公开来源，正常读取请求=" + requests
                + "；重复订阅20次新增解析=0、来源请求=0、消息请求=0，来源及收藏身份分别保留，服务器已读位置不变\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(2, status);
    }

    private void bind(TLRPC.Message message) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        SavedLinkLoadingTest.main(() -> {
            if (subscription != null) {
                subscription.cancel();
            }
            preview = null;
            subscription = controller.subscribe(message, value -> {
                preview = value;
                if (!value.loading && (value.message != null || value.error != null)) {
                    done.countDown();
                }
            });
        });
        assertTrue("正式链接加载超时", done.await(45, TimeUnit.SECONDS));
        SavedLinkLoadingTest.idle();
    }

    private int readPosition(TLRPC.Chat chat) throws Exception {
        TLRPC.TL_channels_getFullChannel request = new TLRPC.TL_channels_getFullChannel();
        request.channel = MessagesController.getInputChannel(chat);
        TLObject response = request(request);
        assertTrue(response instanceof TLRPC.TL_messages_chatFull);
        TLRPC.TL_messages_chatFull full = (TLRPC.TL_messages_chatFull) response;
        assertEquals(chat.id, full.full_chat.id);
        for (TLRPC.Chat value : full.chats) {
            if (value.id == chat.id) {
                assertEquals(chat.left, value.left);
                assertEquals(chat.kicked, value.kicked);
            }
        }
        return full.full_chat.read_inbox_max_id;
    }

    private TLObject request(TLObject request) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        TLObject[] result = new TLObject[1];
        String[] error = new String[1];
        int id = ConnectionsManager.getInstance(account).sendRequest(request, (response, failure) -> {
            result[0] = response;
            error[0] = failure == null ? null : failure.text;
            done.countDown();
        });
        try {
            assertTrue(done.await(45, TimeUnit.SECONDS));
            assertNull(error[0]);
            return result[0];
        } finally {
            ConnectionsManager.getInstance(account).cancelRequest(id, true);
        }
    }
}
