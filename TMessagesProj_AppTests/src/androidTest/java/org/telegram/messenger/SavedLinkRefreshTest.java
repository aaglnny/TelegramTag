package org.telegram.messenger;

import android.util.SparseArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.telegram.messenger.SavedLinkLoadingTest.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkRefreshTest {
    final SavedLinkLoadingTest f = new SavedLinkLoadingTest();

    @Before
    public void setUp() throws Exception {
        f.setUp();
    }

    @After
    public void tearDown() throws Exception {
        f.tearDown();
    }

    @Test
    public void l5_freshnessBoundaryClockRollbackAndInflightRefreshMerge() throws Exception {
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        long confirmed = first.value.reference.lastSuccessAt;
        f.source.time = confirmed + SavedLinkPreviewController.FRESHNESS_MS - 1;
        Watch second = f.watch(saved(102, C1, 301));
        waitFor(second::finished);
        assertEquals(1, f.source.messageRequests());
        f.source.auto = false;
        f.source.time++;
        Watch expired = f.watch(saved(103, C1, 301));
        waitFor(() -> f.source.pending.size() == 1);
        assertNotNull(expired.value.message);
        assertTrue(expired.value.loading);
        main(() -> {
            for (int i = 0; i < 20; i++) {
                first.subscription.retry();
                expired.subscription.retry();
            }
        });
        assertEquals(2, f.source.messageRequests());
        Request refresh = f.source.requests.get(1);
        main(() -> f.source.answer(refresh, null, "TIMEOUT"));
        waitFor(() -> first.finished() && first.value.error != null);
        assertEquals(confirmed, first.value.reference.lastSuccessAt);
        for (int i = 0; i < 20; i++) {
            Watch rebound = f.watch(saved(101, C1, 301));
            waitFor(rebound::finished);
            main(rebound.subscription::cancel);
        }
        assertEquals(2, f.source.messageRequests());
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request retried = f.source.requests.get(2);
        main(() -> f.source.answer(retried, f.source.response(retried), null));
        waitFor(() -> first.finished() && first.value.error == null);
        long latest = first.value.reference.lastSuccessAt;
        f.source.time = latest - 1;
        f.watch(saved(104, C1, 301));
        waitFor(() -> f.source.pending.size() == 1);
        assertEquals(4, f.source.messageRequests());
        Request rollback = f.source.requests.get(3);
        main(() -> f.source.answer(rollback, f.source.response(rollback), null));
        waitFor(first::finished);
        f.source.time += SavedLinkPreviewController.FRESHNESS_MS + 1;
        f.watch(saved(105, C1, 301));
        waitFor(() -> f.source.messageRequests() == 5);
        assertEquals(1, f.source.pending.size());
        System.out.println("L5 时钟：窗口内0次，到期合并1次，20次手动刷新不增加在途；失败不延长时间，回拨及过期重新确认");
    }

    @Test
    public void l5_editNotificationUpdatesSharedReferencesAndRejectsOldResponse() throws Exception {
        TLRPC.Message original = saved(101, C1, 301);
        Watch first = f.watch(original);
        Watch second = f.watch(saved(102, C1, 301));
        waitFor(() -> first.finished() && second.finished());
        MessageObject old = first.value.message;
        f.source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request pending = f.source.requests.get(1);
        TLRPC.Message edited = sourceMessage(C1, 301);
        edited.message = "服务器确认的新正文";
        edited.edit_date = 1700000020;
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.replaceMessagesObjects,
                -C1, new ArrayList<>(Collections.singletonList(new MessageObject(0, edited, false, false)))));
        assertEquals(edited.message, first.value.message.messageOwner.message);
        assertSame(first.value.message, second.value.message);
        assertTrue(old.savedLinkInvalidated);
        main(() -> f.source.answer(pending, f.source.response(pending), null));
        idle();
        assertEquals(edited.message, first.value.message.messageOwner.message);
        assertEquals(edited.message, f.source.cache.get(-C1 + ":301").message);
        assertEquals("https://t.me/c/" + C1 + "/301", original.message);
        assertEquals(101, original.id);
        assertEquals(USER, original.dialog_id);
        SparseArray<SavedLinkReference> rows = await(cb -> f.storage.load(Arrays.asList(101, 102), cb));
        assertEquals(SavedLinkReference.AVAILABLE, rows.get(101).state);
        assertEquals(rows.get(101).lastSuccessAt, rows.get(102).lastSuccessAt);
    }

    @Test
    public void l5_missingEmptyOptimisticAndConfirmedDeletionStayDistinct() throws Exception {
        Watch first = f.watch(saved(101, C1, 301));
        Watch other = f.watch(saved(103, C2, 301));
        waitFor(() -> first.finished() && other.finished());
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted,
                new ArrayList<>(Collections.singletonList(301)), C1, false));
        assertNotNull(first.value.message);
        f.source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request missing = f.source.pending.values().iterator().next();
        main(() -> f.source.answer(missing, new TLRPC.TL_messages_messages(), null));
        waitFor(() -> first.finished() && "MESSAGE_MISSING".equals(first.value.error));
        assertNotNull(first.value.message);
        assertEquals(SavedLinkReference.FAILED, first.value.reference.state);
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request removed = f.source.pending.values().iterator().next();
        TLRPC.TL_messages_messages response = new TLRPC.TL_messages_messages();
        TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
        empty.id = 301;
        response.messages.add(empty);
        main(() -> f.source.answer(removed, response, null));
        waitFor(() -> first.finished() && first.value.message == null);
        assertEquals(SavedLinkReference.UNAVAILABLE, first.value.reference.state);
        assertNotNull(other.value.message);
        Watch second = f.watch(saved(102, C1, 302));
        waitFor(() -> f.source.pending.size() == 1);
        Request current = f.source.pending.values().iterator().next();
        main(() -> f.controller.onMessagesDeleted(-C1, Collections.singletonList(302)));
        main(() -> f.source.answer(current, f.source.response(current), null));
        idle();
        assertNull(second.value.message);
        assertEquals("MESSAGE_DELETED", second.value.error);
        SparseArray<SavedLinkReference> rows = await(cb -> f.storage.load(Arrays.asList(101, 102, 103), cb));
        assertEquals(3, rows.size());
        assertEquals(SavedLinkReference.UNAVAILABLE, rows.get(102).state);
        assertEquals(SavedLinkReference.AVAILABLE, rows.get(103).state);
    }

    @Test
    public void l5_permissionFailureHidesWholeChannelAndExplicitRetryRestoresIt() throws Exception {
        Watch first = f.watch(saved(101, C1, 301));
        Watch second = f.watch(saved(102, C1, 302));
        waitFor(() -> first.finished() && second.finished());
        f.source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request denied = f.source.pending.values().iterator().next();
        main(() -> f.source.answer(denied, null, "CHANNEL_PRIVATE"));
        waitFor(() -> first.value.message == null && second.value.message == null);
        assertEquals(SavedLinkReference.UNAVAILABLE, first.value.reference.state);
        assertNotNull(f.source.cache.get(-C1 + ":301"));
        Watch rebound = f.watch(saved(101, C1, 301));
        waitFor(rebound::finished);
        assertNull(rebound.value.message);
        assertTrue(f.source.pending.isEmpty());
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request restored = f.source.pending.values().iterator().next();
        main(() -> f.source.answer(restored, f.source.response(restored), null));
        waitFor(() -> first.finished() && first.value.message != null);
        assertEquals(SavedLinkReference.AVAILABLE, first.value.reference.state);
        assertNull(second.value.message);
    }

    @Test
    public void l5_savedEditDeleteAndAccountChangeRejectLateWork() throws Exception {
        f.source.auto = false;
        Watch before = f.watch(saved(101, C1, 301));
        waitFor(() -> f.source.pending.size() == 1);
        Request old = f.source.pending.values().iterator().next();
        Watch edited = f.watch(saved(101, C2, 301));
        waitFor(() -> f.source.pending.size() == 1 && f.source.messageRequests() == 2);
        Request current = f.source.pending.values().iterator().next();
        main(() -> f.source.answer(old, f.source.response(old), null));
        main(() -> f.source.answer(current, f.source.response(current), null));
        waitFor(edited::finished);
        assertEquals(-C2, edited.value.message.getDialogId());
        main(edited.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request deleted = f.source.pending.values().iterator().next();
        main(() -> f.controller.onMessagesDeleted(USER, Collections.singletonList(101)));
        main(() -> f.source.answer(deleted, f.source.response(deleted), null));
        SparseArray<SavedLinkReference> rows = await(cb -> f.storage.load(Collections.singletonList(101), cb));
        assertEquals(0, rows.size());
        Watch account = f.watch(saved(102, C1, 302));
        waitFor(() -> f.source.pending.size() == 1);
        Request previousAccount = f.source.pending.values().iterator().next();
        int calls = account.calls;
        main(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = SavedLinkReferenceTest.USER_B;
            UserConfig.getInstance(0).setCurrentUser(user);
            f.source.answer(previousAccount, f.source.response(previousAccount), null);
        });
        idle();
        assertEquals(calls, account.calls);
        assertFalse(f.controller.isActive());
    }

    @Test
    public void l5_fixedUsernameCannotBeReboundAfterAnotherChannelTakesIt() throws Exception {
        TLRPC.Chat firstChat = f.source.chats.get(C1);
        firstChat.username = "fixed_source";
        main(() -> MessagesController.getInstance(0).putChat(firstChat, false));
        TLRPC.Message link = saved(101, C1, 301);
        link.message = "https://t.me/fixed_source/301";
        Watch first = f.watch(link);
        waitFor(first::finished);
        SparseArray<SavedLinkReference> rows = await(cb -> f.storage.load(Collections.singletonList(101), cb));
        assertEquals(-C1, rows.get(101).sourceDialogId);
        main(f.controller::cleanup);
        CountDownLatch closed = new CountDownLatch(1);
        f.storage.close(closed::countDown);
        assertTrue(closed.await(15, TimeUnit.SECONDS));
        TLRPC.Chat renamed = channel(C1);
        renamed.username = "renamed_source";
        TLRPC.Chat replacement = channel(C2);
        replacement.username = "fixed_source";
        main(() -> {
            MessagesController.getInstance(0).putChat(renamed, false);
            MessagesController.getInstance(0).putChat(replacement, false);
            f.storage = new SavedLinkPreviewStorage(f.root, USER, false);
            f.controller = new SavedLinkPreviewController(0, f.storage, f.source);
        });
        Watch after = f.watch(link);
        waitFor(after::finished);
        assertEquals(-C1, after.value.message.getDialogId());
        assertEquals("https://t.me/fixed_source/301", after.value.reference.originalUrl);
        assertEquals(0, f.controller.getParseCount());
        assertEquals(1, f.source.messageRequests());
    }

    @Test
    public void l5_newProtectionAndMissingSourceBeatOldNetworkResponse() throws Exception {
        f.source.chats.get(C1).noforwards = false;
        TLRPC.Message plain = sourceMessage(C1, 301);
        plain.noforwards = false;
        f.source.remote.put(C1 + ":301", plain);
        Watch first = f.watch(saved(101, C1, 301));
        Watch second = f.watch(saved(102, C1, 301));
        waitFor(() -> first.finished() && second.finished());
        MessageObject oldMedia = first.value.message;
        assertFalse(first.value.isProtected());
        f.source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request old = f.source.pending.values().iterator().next();
        TLRPC.Chat protectedChat = channel(C1);
        main(() -> {
            MessagesController.getInstance(0).putChat(protectedChat, false);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT);
        });
        waitFor(() -> first.value.isProtected() && second.value.isProtected());
        assertTrue(first.value.isProtected());
        assertTrue(second.value.isProtected());
        assertTrue(oldMedia.savedLinkInvalidated);
        main(() -> f.source.answer(old, f.source.response(old), null));
        idle();
        assertTrue(first.value.isProtected());
        Field field = MessagesController.class.getDeclaredField("chats");
        field.setAccessible(true);
        main(() -> {
            try {
                ((Map<Long, TLRPC.Chat>) field.get(MessagesController.getInstance(0))).remove(C1);
            } catch (Exception e) { throw new AssertionError(e); }
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_CHAT);
        });
        waitFor(() -> first.value.chat == null);
        assertTrue(first.value.isProtected());
        assertTrue(first.value.message.savedLinkProtected);
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request recovered = f.source.pending.values().iterator().next();
        assertEquals(C1, ((TLRPC.TL_channels_getMessages) recovered.object).channel.channel_id);
        main(() -> f.source.answer(recovered, f.source.response(recovered), null));
        waitFor(() -> first.finished() && first.value.chat != null);
    }

    @Test
    public void l5_conditionalStateWritesCannotRestoreDeletedOrEditedReferences() throws Exception {
        SavedLinkReference old = SavedLinkPreviewController.parse(USER, saved(101, C1, 301));
        SavedLinkReference newer = SavedLinkPreviewController.parse(USER, saved(101, C2, 302));
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.save(Collections.singletonList(old), cb));
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.save(Collections.singletonList(newer), cb));
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.update(Collections.singletonList(old.withSource(-C1, SavedLinkReference.AVAILABLE, 100)), cb));
        SparseArray<SavedLinkReference> rows = await(cb -> f.storage.load(Collections.singletonList(101), cb));
        assertEquals(newer.contentSignature, rows.get(101).contentSignature);
        assertEquals(0, rows.get(101).sourceDialogId);
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.remove(Collections.singletonList(101), cb));
        SavedLinkLoadingTest.<Void>await(cb -> f.storage.update(Collections.singletonList(old.withSource(-C1, SavedLinkReference.AVAILABLE, 100)), cb));
        rows = await(cb -> f.storage.load(Collections.singletonList(101), cb));
        assertEquals(0, rows.size());
    }

    @Test
    public void l5_knownProtectionWinsEvenBeforeTheUiNotificationArrives() throws Exception {
        f.source.chats.get(C1).noforwards = false;
        TLRPC.Message message = sourceMessage(C1, 301);
        message.noforwards = false;
        f.source.remote.put(C1 + ":301", message);
        Watch first = f.watch(saved(101, C1, 301));
        waitFor(first::finished);
        f.source.auto = false;
        main(first.subscription::retry);
        waitFor(() -> f.source.pending.size() == 1);
        Request old = f.source.pending.values().iterator().next();
        main(() -> {
            MessagesController.getInstance(0).putChat(channel(C1), false);
            f.source.answer(old, f.source.response(old), null);
        });
        waitFor(() -> !first.value.loading);
        assertTrue(first.value.isProtected());
        assertTrue(MessagesController.getInstance(0).getChat(C1).noforwards);
    }
}
