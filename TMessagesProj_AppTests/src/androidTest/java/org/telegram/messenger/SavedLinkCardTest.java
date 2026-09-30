package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Components.SavedLinkPreviewView;
import org.telegram.ui.Components.LocalSavedTagsLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkCardTest {
    final SavedLinkLoadingTest fixture = new SavedLinkLoadingTest();
    final LocalSavedTagsUiTest driver = new LocalSavedTagsUiTest();
    Instrumentation instrumentation;
    LocalSavedTagsTestActivity activity;
    LocalSavedTagsController tags;
    LocalSavedTagsStorage tagStorage;
    LocalSavedTagsFilterTest.Source tagSource;
    private LocalSavedTagsController previousTags;
    private Field tagField;
    private Theme.ThemeInfo originalTheme;
    private int originalFont;

    @Before
    public void setUp() throws Exception {
        fixture.setUp();
        SavedLinkLoadingTest.sql("DELETE FROM messages_v2 WHERE uid = " + SavedLinkLoadingTest.USER);
        instrumentation = InstrumentationRegistry.getInstrumentation();
        originalTheme = Theme.getCurrentTheme();
        originalFont = SharedConfig.fontSize;
        Field instances = SavedLinkPreviewController.class.getDeclaredField("instances");
        instances.setAccessible(true);
        ((SavedLinkPreviewController[]) instances.get(null))[0] = fixture.controller;
        tagField = MessagesController.class.getDeclaredField("localSavedTagsController");
        tagField.setAccessible(true);
        previousTags = (LocalSavedTagsController) tagField.get(MessagesController.getInstance(0));
        tagStorage = new LocalSavedTagsStorage(fixture.root, SavedLinkLoadingTest.USER, false);
        tagSource = new LocalSavedTagsFilterTest.Source();
        tagSource.userId = SavedLinkLoadingTest.USER;
        SavedLinkLoadingTest.main(() -> {
            tags = new LocalSavedTagsController(0, tagStorage, tagSource);
            fixture.source.chats.get(SavedLinkLoadingTest.C1).noforwards = false;
            fixture.source.chats.get(SavedLinkLoadingTest.C2).noforwards = false;
        });
        tagField.set(MessagesController.getInstance(0), tags);
        activity = LocalSavedTagsUiTest.startActivity(instrumentation, SavedLinkLoadingTest.USER);
        setDriver("instrumentation", instrumentation);
        setDriver("ui", instrumentation.getUiAutomation());
        setDriver("activity", activity);
        setDriver("controller", tags);
        source(301, "来源正文 #原话题", false);
    }

    @After
    public void tearDown() throws Exception {
        SavedLinkLoadingTest.main(() -> {
            if (activity != null) {
                activity.finish();
            }
            if (tags != null) {
                tags.cleanup();
            }
            SharedConfig.fontSize = originalFont;
            if (originalTheme != null) {
                Theme.applyTheme(originalTheme, false, false);
            }
        });
        SavedLinkLoadingTest.idle();
        if (tagField != null) {
            tagField.set(MessagesController.getInstance(0), previousTags);
        }
        if (tagStorage != null) {
            CountDownLatch done = new CountDownLatch(1);
            tagStorage.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
        fixture.tearDown();
    }

    @Test(timeout = 90000)
    public void l3_nativeCardShowsSourceAndExpandsWithoutChangingSavedText() throws Exception {
        String sourceText = String.join("\n", Collections.nCopies(12, "收藏页内可以展开的完整原文，包含 #原话题 和 https://example.com"));
        source(301, sourceText, false);
        MessageObject message = sample(501, "我保存的链接\nhttps://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(message);
        SavedLinkPreviewView card = card(501);
        TextView body = field(card, "body", TextView.class);
        assertEquals(sourceText, body.getText().toString());
        assertEquals(4, body.getMaxLines());
        int height = cell(501).getHeight();
        int top = cell(501).getTop();
        screenshot("l3-collapsed");
        click(LocaleController.getString(R.string.SavedLinkExpand));
        SavedLinkLoadingTest.waitFor(() -> body.getMaxLines() == Integer.MAX_VALUE && cell(501).getHeight() > height);
        assertTrue(body.isTextSelectable());
        assertEquals(message.messageOwner.message, cell(501).getMessageObject().messageOwner.message);
        assertEquals(501, card.getPreview().reference.savedMessageId);
        assertEquals(301, card.getPreview().message.getId());
        screenshot("l3-expanded");
        SavedLinkLoadingTest.main(() -> field(card, "expand", TextView.class).performClick());
        SavedLinkLoadingTest.waitFor(() -> body.getMaxLines() == 4 && cell(501).getHeight() == height);
        assertTrue("展开收起不能改变所定位的收藏", Math.abs(cell(501).getTop() - top) <= AndroidUtilities.dp(3));
    }

    @Test(timeout = 60000)
    public void l3_existingWebPreviewIsReplacedAndOriginalLinkStillOpens() throws Exception {
        String url = "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301";
        TLRPC.TL_messageMediaWebPage media = new TLRPC.TL_messageMediaWebPage();
        TLRPC.TL_webPage web = new TLRPC.TL_webPage();
        web.id = 555;
        web.url = web.display_url = url;
        web.site_name = "Telegram";
        web.title = "原有网页预览";
        web.description = "不应与原消息卡片重复显示";
        media.webpage = web;
        MessageObject message = sample(502, url, media);
        show(message);
        SavedLinkPreviewView card = card(502);
        assertFalse(field(cell(502), "hasLinkPreview", Boolean.class));
        assertSame(media, message.messageOwner.media);
        assertEquals(url, message.messageOwner.message);
        Intent[] opened = new Intent[1];
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override
            public Instrumentation.ActivityResult onStartActivity(Intent intent) {
                opened[0] = intent;
                return new Instrumentation.ActivityResult(0, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try {
            SavedLinkLoadingTest.main(() -> field(card, "original", TextView.class).performClick());
            SavedLinkLoadingTest.waitFor(() -> opened[0] != null);
            assertEquals(Intent.ACTION_VIEW, opened[0].getAction());
            assertEquals(url, opened[0].getDataString());
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        screenshot("l3-single-preview");
    }

    @Test(timeout = 120000)
    public void l3_singleAndBatchTagsStayBelowThePreviewAndKeepSavedIds() throws Exception {
        LocalSavedTag first = SavedLinkLoadingTest.await(callback -> tags.createTag("资料", callback));
        LocalSavedTag second = SavedLinkLoadingTest.await(callback -> tags.createTag("稍后查看", callback));
        MessageObject message = sample(503, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(message);
        card(503);
        invoke("openMessageEditor", new Class[]{int.class}, 503);
        click("资料");
        click("稍后查看");
        click(LocaleController.getString(R.string.LocalSavedTagsSave));
        invoke("waitEditorClosed", new Class[0]);
        SavedLinkLoadingTest.waitFor(() -> tags.getMessageTags(503).size() == 2);
        SavedLinkPreviewView card = card(503);
        SavedLinkLoadingTest.main(() -> {
            AccessibilityNodeInfo node = cell(503).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000);
            assertNotNull(node);
            Rect bounds = new Rect();
            node.getBoundsInParent(bounds);
            assertTrue("本地标签必须在预览之后", bounds.top >= card.getBottom());
        });
        MessageObject other = sample(504, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(message, other);
        card(504);
        invoke("selectMessages", new Class[]{MessageObject[].class}, (Object) new MessageObject[]{message, other});
        click(LocaleController.getString(R.string.LocalSavedTagsSet));
        click(LocaleController.getString(R.string.LocalSavedTagsAdd));
        click("资料 · 1/2");
        click(LocaleController.getString(R.string.LocalSavedTagsSave));
        invoke("waitEditorClosed", new Class[0]);
        SavedLinkLoadingTest.waitFor(() -> tags.getMessageTags(504).size() == 1);
        assertEquals(2, tags.getMessageTags(503).size());
        assertEquals(first.id, tags.getMessageTags(504).get(0).id);
        assertTrue(tags.getMessageTags(301).isEmpty());
        assertEquals(2, tags.getTags().stream().filter(tag -> tag.id == first.id).findFirst().get().messageCount);
        assertNotEquals(first.id, second.id);
        screenshot("l3-tags-after-preview");
    }

    @Test(timeout = 90000)
    public void l3_bothFiltersKeepSessionAndCursorWhenPreviewChanges() throws Exception {
        LocalSavedTag tag = SavedLinkLoadingTest.await(callback -> tags.createTag("可筛选", callback));
        MessageObject tagged = sample(505, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        MessageObject untagged = sample(506, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(tagged), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        show(tagged, untagged);
        card(506);
        for (String name : new String[]{LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "可筛选"), LocaleController.getString(R.string.LocalSavedTagsUntagged)}) {
            click(name);
            invoke("waitFilter", new Class[]{int.class}, 1);
            LocalSavedTagsController.FilterSession filter = field(activity.chat, "localTagFilter", LocalSavedTagsController.FilterSession.class);
            int id = filter.tagId == tag.id ? 505 : 506;
            SavedLinkPreviewView card = card(id);
            int history = tagSource.historyOffsets.size();
            int requests = tagSource.requests.size();
            SavedLinkLoadingTest.main(() -> field(card, "expand", TextView.class).performClick());
            SavedLinkLoadingTest.idle();
            assertSame(filter, field(activity.chat, "localTagFilter", LocalSavedTagsController.FilterSession.class));
            assertEquals(history, tagSource.historyOffsets.size());
            assertEquals(requests, tagSource.requests.size());
            assertEquals(id, card.getPreview().reference.savedMessageId);
            click(LocaleController.getString(R.string.LocalSavedTagsAll));
        }
        assertEquals(1, fixture.source.messageRequests());
        screenshot("l3-filters");
    }

    @Test(timeout = 90000)
    public void l3_recyclingAndLateResponseCannotAttachOldBody() throws Exception {
        fixture.source.auto = false;
        MessageObject first = sample(507, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(first);
        SavedLinkLoadingTest.waitFor(() -> fixture.source.pending.size() == 1);
        SavedLinkLoadingTest.Request old = fixture.source.requests.get(0);
        TLRPC.Message otherSource = SavedLinkLoadingTest.sourceMessage(SavedLinkLoadingTest.C2, 301);
        otherSource.noforwards = false;
        fixture.source.remote.put(SavedLinkLoadingTest.C2 + ":301", otherSource);
        MessageObject second = sample(508, "https://t.me/c/" + SavedLinkLoadingTest.C2 + "/301", null);
        show(second);
        SavedLinkLoadingTest.waitFor(() -> fixture.source.requests.size() == 2);
        SavedLinkLoadingTest.main(() -> {
            fixture.source.answer(old, fixture.source.response(old), null);
            SavedLinkLoadingTest.Request current = fixture.source.requests.get(1);
            fixture.source.answer(current, fixture.source.response(current), null);
        });
        SavedLinkPreviewView card = card(508);
        assertEquals(-SavedLinkLoadingTest.C2, card.getPreview().message.getDialogId());
        assertEquals(508, card.getPreview().reference.savedMessageId);
        MessageObject empty = sample(509, "没有消息链接的收藏", null);
        show(empty);
        SavedLinkLoadingTest.waitFor(() -> cell(509) != null);
        SavedLinkLoadingTest.idle();
        SavedLinkPreviewView recycled = cell(509).getSavedLinkPreviewView();
        assertTrue(recycled == null || recycled.getVisibility() == View.GONE);
    }

    @Test(timeout = 60000)
    public void l3_unchangedBindingKeepsTheInflightRequest() throws Exception {
        fixture.source.auto = false;
        MessageObject message = sample(520, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(message);
        SavedLinkLoadingTest.waitFor(() -> fixture.source.requests.size() == 1);
        for (int i = 0; i < 20; i++) {
            SavedLinkLoadingTest.main(() -> activity.chat.getChatListView().getAdapter().notifyDataSetChanged());
            SystemClock.sleep(60);
        }
        assertEquals(1, fixture.source.requests.size());
        assertEquals(0, fixture.source.cancelled);
        SavedLinkLoadingTest.main(() -> {
            SavedLinkLoadingTest.Request request = fixture.source.requests.get(0);
            fixture.source.answer(request, fixture.source.response(request), null);
        });
        assertEquals(301, card(520).getPreview().message.getId());
    }

    @Test(timeout = 120000)
    public void l3_themesFontsAndAccessibilityKeepContentAndTagBounds() throws Exception {
        LocalSavedTag tag = SavedLinkLoadingTest.await(callback -> tags.createTag("界面标签", callback));
        fixture.source.chats.get(SavedLinkLoadingTest.C1).title = "较长的来源频道名称，用于验证换行和读屏，不应盖住正文与本地标签";
        source(301, "原消息包含 #原话题\n第二行正文\n第三行正文\n第四行正文\n第五行正文", false);
        for (int i = 0; i < 2; i++) {
            final int style = i;
            SavedLinkLoadingTest.main(() -> {
                SharedConfig.fontSize = style == 0 ? 16 : 22;
                Theme.applyTheme(style == 0 ? originalTheme : Theme.getTheme("Dark Blue"), false, style != 0);
                activity.navigation.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
            });
            MessageObject message = sample(510 + i, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
            SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
            show(message);
            SavedLinkPreviewView card = card(message.getId());
            invoke("waitText", new Class[]{String.class}, LocaleController.getString(R.string.SavedLinkExpand));
            SavedLinkLoadingTest.waitFor(() -> cell(message.getId()).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000) != null);
            SavedLinkLoadingTest.main(() -> {
                TextView title = field(card, "title", TextView.class);
                TextView body = field(card, "body", TextView.class);
                assertTrue(title.getBottom() <= body.getTop());
                assertTrue(card.getTop() >= cell(message.getId()).getLayoutHeight());
                AccessibilityNodeInfo node = cell(message.getId()).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000);
                Rect bounds = new Rect();
                node.getBoundsInParent(bounds);
                assertTrue(bounds.top >= card.getBottom());
                assertTrue(bounds.bottom <= cell(message.getId()).getHeight());
                assertEquals(255, android.graphics.Color.alpha(body.getCurrentTextColor()));
            });
            screenshot(i == 0 ? "l3-light-16" : "l3-dark-22");
        }
    }

    @Test(timeout = 90000)
    public void l3_copyAndSelectionUseOriginalWhileProtectedBodyCannotBeCopied() throws Exception {
        String text = "#学习 https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301";
        MessageObject message = sample(512, text, null);
        show(message);
        card(512);
        invoke("longPressMessage", new Class[]{int.class}, 512);
        click(LocaleController.getString(R.string.Copy));
        SavedLinkLoadingTest.main(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(text, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        });
        assertEquals(text, message.messageOwner.message);
        source(302, "受保护的正文 #原话题", true);
        fixture.source.chats.get(SavedLinkLoadingTest.C1).noforwards = true;
        MessageObject protectedLink = sample(513, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/302", null);
        show(protectedLink);
        SavedLinkPreviewView protectedCard = card(513);
        SavedLinkLoadingTest.main(() -> {
            field(protectedCard, "expand", TextView.class).performClick();
            assertFalse(field(protectedCard, "body", TextView.class).isTextSelectable());
            assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0);
            assertFalse("收藏链接仍保持自身普通权限", protectedLink.messageOwner.noforwards);
        });
    }

    @Test(timeout = 90000)
    public void l3_photoCaptionAndAlbumKeepOneCardAndOriginalMedia() throws Exception {
        source(301, String.join("\n", Collections.nCopies(6, "相册来源正文")), false);
        LocalSavedTag tag = SavedLinkLoadingTest.await(callback -> tags.createTag("图片链接", callback));
        MessageObject first = sample(514, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", LocalSavedTagsSamples.media("photo", 514));
        SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(first), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        show(first);
        card(514);
        assertTrue(first.isPhoto());
        for (int layout = 0; layout < 2; layout++) {
            MessageObject a = sample(514, first.messageOwner.message, LocalSavedTagsSamples.media("photo", 514));
            MessageObject b = sample(515, "", LocalSavedTagsSamples.media("photo", 515));
            a.messageOwner.grouped_id = b.messageOwner.grouped_id = 1234567 + layout;
            if (layout == 1) {
                for (MessageObject item : new MessageObject[]{a, b}) {
                    TLRPC.PhotoSize size = item.messageOwner.media.photo.sizes.get(0);
                    size.h = size.w;
                    item.generateThumbs(false);
                }
            }
            show(a, b);
            SavedLinkLoadingTest.main(() -> activity.chat.getChatListView().scrollToPosition(0));
            SavedLinkLoadingTest.waitFor(() -> cell(514) != null && cell(515) != null);
            int bottom = 0;
            int visible = 0;
            int height = 0;
            for (int id : new int[]{514, 515}) {
                ChatMessageCell cell = cell(id);
                if ((cell.getCurrentPosition().flags & MessageObject.POSITION_FLAG_BOTTOM) != 0) {
                    SavedLinkPreviewView card = card(id);
                    assertEquals(514, card.getPreview().reference.savedMessageId);
                    assertEquals(cell.getCurrentPosition().last ? View.VISIBLE : View.INVISIBLE, card.getVisibility());
                    if (bottom++ == 0) {
                        height = card.getMeasuredHeight();
                    } else {
                        assertEquals("同一底行必须共同预留卡片高度", height, card.getMeasuredHeight());
                    }
                    visible += card.getVisibility() == View.VISIBLE ? 1 : 0;
                } else {
                    assertTrue(cell.getSavedLinkPreviewView() == null || cell.getSavedLinkPreviewView().getVisibility() == View.GONE);
                }
            }
            assertEquals(layout == 0 ? 1 : 2, bottom);
            assertEquals(1, visible);
            if (layout == 1) {
                SavedLinkPreviewView displayed = cell(515).getSavedLinkPreviewView();
                int collapsed = displayed.getHeight();
                click(LocaleController.getString(R.string.SavedLinkExpand));
                SavedLinkLoadingTest.waitFor(() -> displayed.getHeight() > collapsed);
                assertEquals(cell(514).getSavedLinkPreviewView().getMeasuredHeight(), displayed.getMeasuredHeight());
                click(LocaleController.getString(R.string.SavedLinkCollapse));
                SavedLinkLoadingTest.waitFor(() -> displayed.getHeight() == collapsed);
            }
            screenshot(layout == 0 ? "l3-album-vertical" : "l3-album-horizontal");
        }
        assertEquals(1, tags.getMessageTags(514).size());
        assertTrue(tags.getMessageTags(515).isEmpty());
        screenshot("l3-album-caption");
    }

    @Test(timeout = 60000)
    public void l3_otherChatKeepsItsExistingMessageBehavior() throws Exception {
        show();
        SavedLinkLoadingTest.idle();
        int requests = fixture.source.requests.size();
        SavedLinkLoadingTest.main(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = SavedLinkReferenceTest.USER_B;
            user.first_name = "另一个聊天";
            MessagesController.getInstance(0).putUser(user, false);
            Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            activity.chat = new ChatActivity(args);
            activity.chat.setCurrentAccount(0);
            activity.navigation.addFragmentToStack(activity.chat);
            activity.navigation.showLastFragment();
        });
        TLRPC.Message original = SavedLinkLoadingTest.saved(516, SavedLinkLoadingTest.C1, 301);
        original.peer_id = new TLRPC.TL_peerUser();
        original.peer_id.user_id = SavedLinkReferenceTest.USER_B;
        original.dialog_id = SavedLinkReferenceTest.USER_B;
        MessageObject message = new MessageObject(0, original, true, false);
        invoke("showSamples", new Class[]{ArrayList.class}, new ArrayList<>(Collections.singletonList(message)));
        SavedLinkLoadingTest.waitFor(() -> cell(516) != null);
        assertNull(cell(516).getSavedLinkPreviewView());
        SavedLinkLoadingTest.idle();
        assertEquals(requests, fixture.source.requests.size());
        assertNull("非收藏消息不能登记解析任务", field(fixture.controller, "references", android.util.SparseArray.class).get(516));
        System.out.println("L3 非收藏聊天：新增请求=0，目标未登记解析，父收藏页解析=" + fixture.controller.getParseCount());
    }

    @Test(timeout = 60000)
    public void l3_sourceSubdialogKeepsItsOriginalEntryAndTagNavigation() throws Exception {
        show();
        SavedLinkLoadingTest.idle();
        MessageObject message = sample(517, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        LocalSavedTag tag = SavedLinkLoadingTest.await(callback -> tags.createTag("来源入口", callback));
        SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        SavedLinkLoadingTest.main(() -> {
            Bundle args = new Bundle();
            args.putLong("user_id", SavedLinkLoadingTest.USER);
            args.putInt("chatMode", ChatActivity.MODE_SAVED);
            ChatActivity subdialog = new ChatActivity(args);
            subdialog.setCurrentAccount(0);
            subdialog.setSavedDialog(-SavedLinkLoadingTest.C1);
            activity.navigation.addFragmentToStack(subdialog);
            activity.navigation.showLastFragment();
            activity.chat = subdialog;
        });
        int requests = fixture.source.requests.size();
        show(message);
        SavedLinkLoadingTest.waitFor(() -> cell(517) != null);
        assertNull(cell(517).getSavedLinkPreviewView());
        assertEquals(requests, fixture.source.requests.size());
        assertNull("来源子会话不能登记解析任务", field(fixture.controller, "references", android.util.SparseArray.class).get(517));
        click(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "来源入口"));
        SavedLinkLoadingTest.waitFor(() -> activity.navigation.getFragmentStack().get(activity.navigation.getFragmentStack().size() - 1) != activity.chat);
        SavedLinkLoadingTest.main(() -> activity.chat = (ChatActivity) activity.navigation.getFragmentStack().get(activity.navigation.getFragmentStack().size() - 1));
        invoke("waitFilter", new Class[]{int.class}, 1);
        assertEquals(0, activity.chat.getSavedDialogId());
        card(517);
    }

    @Test(timeout = 60000)
    public void l3_forwardChooserUsesOnlyTheSavedLink() throws Exception {
        MessageObject message = sample(518, "#原收藏 https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        show(message);
        card(518);
        invoke("selectMessages", new Class[]{MessageObject[].class}, (Object) new MessageObject[]{message});
        click(LocaleController.getString(R.string.Forward));
        SavedLinkLoadingTest.waitFor(() -> activity.navigation.getFragmentStack().get(activity.navigation.getFragmentStack().size() - 1) instanceof DialogsActivity);
        assertEquals(518, message.getId());
        assertEquals("#原收藏 https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", message.messageOwner.message);
        screenshot("l3-forward-chooser");
    }

    @Test(timeout = 90000)
    public void l3_overflowTagsStayWithinTwoRowsBelowTheCard() throws Exception {
        ArrayList<Long> ids = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            String name = "较长的本地标签" + i;
            LocalSavedTag tag = SavedLinkLoadingTest.await(callback -> tags.createTag(name, callback));
            ids.add(tag.id);
        }
        MessageObject message = sample(519, "https://t.me/c/" + SavedLinkLoadingTest.C1 + "/301", null);
        SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(message), ids, Collections.emptyList(), callback));
        show(message);
        SavedLinkPreviewView card = card(519);
        SavedLinkLoadingTest.waitFor(() -> field(cell(519), "localSavedTagsLayout", LocalSavedTagsLayout.class).size() > 0);
        instrumentation.waitForIdleSync();
        SavedLinkLoadingTest.main(() -> {
            LocalSavedTagsLayout layout = field(cell(519), "localSavedTagsLayout", LocalSavedTagsLayout.class);
            assertTrue(layout.size() < 10);
            assertEquals(0, layout.getTagId(layout.size() - 1));
            java.util.HashSet<Float> rows = new java.util.HashSet<>();
            for (int i = 0; i < layout.size(); i++) {
                android.graphics.RectF bounds = layout.getBounds(i);
                assertTrue(bounds.top >= card.getBottom());
                assertTrue(bounds.bottom <= cell(519).getHeight());
                rows.add(bounds.top);
            }
            assertEquals(2, rows.size());
        });
        assertEquals(10, tags.getMessageTags(519).size());
        screenshot("l3-tags-overflow");
    }

    void source(int id, String text, boolean protect) {
        TLRPC.Message message = SavedLinkLoadingTest.sourceMessage(SavedLinkLoadingTest.C1, id);
        message.message = text;
        message.noforwards = protect;
        fixture.source.remote.put(SavedLinkLoadingTest.C1 + ":" + id, message);
    }

    MessageObject sample(int id, String text, TLRPC.MessageMedia media) throws Exception {
        return (MessageObject) invoke("sample", new Class[]{int.class, String.class, TLRPC.MessageMedia.class}, id, text, media);
    }

    void show(MessageObject... messages) throws Exception {
        tagSource.cached.clear();
        tagSource.history.clear();
        for (MessageObject message : messages) {
            tagSource.cached.put(message.getId(), message.messageOwner);
            tagSource.history.add(message.messageOwner);
        }
        invoke("showSamples", new Class[]{ArrayList.class}, new ArrayList<>(Arrays.asList(messages)));
    }

    ChatMessageCell cell(int id) {
        try {
            return (ChatMessageCell) invoke("cell", new Class[]{int.class}, id);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    SavedLinkPreviewView card(int id) throws Exception {
        SavedLinkLoadingTest.waitFor(() -> cell(id) != null && cell(id).getSavedLinkPreviewView() != null
                && cell(id).getSavedLinkPreviewView().getPreview() != null
                && cell(id).getSavedLinkPreviewView().getPreview().message != null
                && !cell(id).getSavedLinkPreviewView().getPreview().loading
                && cell(id).getSavedLinkPreviewView().getHeight() > 0);
        return cell(id).getSavedLinkPreviewView();
    }

    void click(String text) throws Exception {
        invoke("click", new Class[]{String.class}, text);
    }

    void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        SystemClock.sleep(150);
        assertEquals("受保护内容不能截屏取证", 0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(activity.getFilesDir(), "saved-link-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
    }

    private void setDriver(String name, Object value) throws Exception {
        Field field = LocalSavedTagsUiTest.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(driver, value);
    }

    Object invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = LocalSavedTagsUiTest.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(driver, values);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error) {
                throw (Error) e.getCause();
            }
            throw (Exception) e.getCause();
        }
    }

    static <T> T field(Object value, String name, Class<T> type) {
        try {
            Field field = value.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return (T) field.get(value);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
