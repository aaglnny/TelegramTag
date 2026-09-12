package org.telegram.messenger;

import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.StaticLayout;
import android.util.SparseIntArray;
import android.util.SparseArray;
import android.view.View;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.collection.LongSparseArray;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_update;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.LocalSavedTagsLayout;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsUiTest {

    private Instrumentation instrumentation;
    private UiAutomation ui;
    private LocalSavedTagsTestActivity activity;
    private LocalSavedTagsController controller;
    private ArrayList<TLRPC.Message> savedMessages;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        ui = instrumentation.getUiAutomation();
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        start(LocalSavedTagsTestActivity.USER_A);
        clearTags();
    }

    @After
    public void tearDown() throws Exception {
        if (savedMessages != null) {
            MessagesStorage messagesStorage = MessagesStorage.getInstance(0);
            this.<Void>await(callback -> messagesStorage.getStorageQueue().postRunnable(() -> {
                Exception error = null;
                try {
                    SQLitePreparedStatement statement = messagesStorage.getDatabase().executeFast("DELETE FROM messages_v2 WHERE uid = ? AND mid = ?");
                    try {
                        for (TLRPC.Message message : savedMessages) {
                            statement.requery();
                            statement.bindLong(1, LocalSavedTagsTestActivity.USER_A);
                            statement.bindInteger(2, message.id);
                            statement.stepThis();
                        }
                    } finally {
                        statement.dispose();
                    }
                } catch (Exception e) {
                    error = e;
                }
                Exception result = error;
                AndroidUtilities.runOnUIThread(() -> callback.run(null, result));
            }));
        }
        finish();
        long id = UserConfig.getInstance(0).getClientUserId();
        if (id == LocalSavedTagsTestActivity.USER_A || id == LocalSavedTagsTestActivity.USER_B) {
            main(() -> UserConfig.getInstance(0).clearConfig());
        }
    }

    @Test(timeout = 120000)
    public void t2_01_and_06_standardAccountCanManageTags() throws Exception {
        assertFalse(UserConfig.getInstance(0).isPremium());
        TLRPC.TL_messages_messages history = new TLRPC.TL_messages_messages();
        savedMessages = history.messages;
        for (int id : new int[]{101, 103}) {
            TLRPC.TL_message message = new TLRPC.TL_message();
            message.id = id;
            message.date = id == 101 ? 1700000003 : 1700000002;
            message.peer_id = new TLRPC.TL_peerUser();
            message.peer_id.user_id = LocalSavedTagsTestActivity.USER_A;
            message.from_id = message.peer_id;
            message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
            message.out = true;
            message.message = "离线收藏样本 " + id;
            savedMessages.add(message);
        }
        MessagesStorage messagesStorage = MessagesStorage.getInstance(0);
        messagesStorage.putMessages(history, LocalSavedTagsTestActivity.USER_A, MessagesController.LOAD_BACKWARD, 0, false, 0, 0);
        for (TLRPC.Message message : savedMessages) {
            assertNotNull("离线样本没有进入原消息缓存", messagesStorage.getMessage(LocalSavedTagsTestActivity.USER_A, message.id));
        }
        openManager();
        create("学习");
        create("备用");
        LocalSavedTag tag = tag("学习");
        SparseIntArray dates = new SparseIntArray();
        dates.put(101, 1700000003);
        dates.put(103, 1700000002);
        LocalSavedTagsStorage storage = storage();
        this.<Void>await(callback -> storage.applyTags(dates, Arrays.asList(tag.id), Collections.emptyList(), callback));
        main(() -> activity.chat.getVisibleDialog().dismiss());
        openManager();
        waitText("2 条消息");
        screenshot("p2-manager-counts");
        click("学习");
        click(text(R.string.LocalSavedTagsRename));
        setInput("课程");
        click(text(R.string.Save));
        waitText("课程");
        assertEquals(tag.id, tag("课程").id);
        assertEquals(2, tag("课程").messageCount);
        click("课程");
        click(text(R.string.LocalSavedTagsDelete));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsDeleteInfo, "课程"));
        screenshot("p2-delete-confirmation");
        click(text(R.string.Delete));
        waitText("备用");
        ArrayList<LocalSavedTag> tags = await(controller::loadTags);
        assertEquals(1, tags.size());
        assertEquals("备用", tags.get(0).name);
        for (TLRPC.Message expected : savedMessages) {
            TLRPC.Message actual = messagesStorage.getMessage(LocalSavedTagsTestActivity.USER_A, expected.id);
            assertNotNull("删除标签不能删除收藏消息", actual);
            assertEquals(expected.id, actual.id);
            assertEquals(expected.date, actual.date);
            assertEquals(expected.message, actual.message);
        }
        assertFalse(UserConfig.getInstance(0).isPremium());
    }

    @Test
    public void t2_02_inputErrorsAndCancelAreVisible() throws Exception {
        openManager();
        click(text(R.string.LocalSavedTagsCreate));
        setInput("");
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsInvalidName));
        setInput(" \u3000");
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsInvalidName));
        setInput(String.join("", Collections.nCopies(33, "字")));
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsInvalidName));
        setInput("e\u0301");
        click(text(R.string.Save));
        waitText("é");
        click(text(R.string.LocalSavedTagsCreate));
        setInput("é");
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsNameExists));
        screenshot("p2-duplicate-name");
        click(text(R.string.Cancel));
        waitText(text(R.string.LocalSavedTagsLocalOnly));
        click("é");
        click(text(R.string.LocalSavedTagsRename));
        setInput("取消的名称");
        click(text(R.string.Cancel));
        ArrayList<LocalSavedTag> tags = await(controller::loadTags);
        assertEquals(1, tags.size());
        assertEquals("é", tags.get(0).name);
    }

    @Test
    public void t2_04_readAndWriteErrorsCanRetry() throws Exception {
        openManager();
        waitText(text(R.string.LocalSavedTagsEmpty));
        create("原有标签");
        main(() -> activity.chat.getVisibleDialog().dismiss());
        LocalSavedTagsStorage old = storage();
        main(controller::cleanup);
        close(old);
        File file = old.getDatabaseFile();
        SQLiteDatabase db = new SQLiteDatabase(file.getPath());
        try {
            db.executeFast("PRAGMA user_version = 99").stepThis().dispose();
            openManager();
            main(() -> controller = MessagesController.getInstance(0).getLocalSavedTagsController());
            waitText(text(R.string.LocalSavedTagsLoadFailed));
            assertNull(find(text(R.string.LocalSavedTagsEmpty)));
            screenshot("p2-read-error");
            db.executeFast("PRAGMA user_version = 1").stepThis().dispose();
            click(text(R.string.LocalSavedTagsLoadFailed));
            waitText("原有标签");
            db.executeFast("CREATE TRIGGER fail_ui_insert BEFORE INSERT ON local_saved_tags BEGIN SELECT RAISE(ABORT, '测试写入失败'); END").stepThis().dispose();
            click(text(R.string.LocalSavedTagsCreate));
            setInput("重试保留输入");
            click(text(R.string.Save));
            waitText(text(R.string.LocalSavedTagsSaveFailed));
            assertNotNull(find("重试保留输入"));
            screenshot("p2-write-error");
            ArrayList<LocalSavedTag> beforeRetry = await(controller::loadTags);
            assertEquals(1, beforeRetry.size());
            assertEquals("原有标签", beforeRetry.get(0).name);
            db.executeFast("DROP TRIGGER fail_ui_insert").stepThis().dispose();
            click(text(R.string.Save));
            waitText(text(R.string.LocalSavedTagsLocalOnly));
            waitText("重试保留输入");
            waitText("原有标签");
            assertEquals("重试保留输入", tag("重试保留输入").name);
            screenshot("p2-retry-restored");
        } finally {
            db.executeFast("PRAGMA user_version = 1").stepThis().dispose();
            db.executeFast("DROP TRIGGER IF EXISTS fail_ui_insert").stepThis().dispose();
            db.close();
        }
    }

    @Test
    public void t2_05_switchingAccountsRestoresTheirOwnTags() throws Exception {
        openManager();
        create("账号甲的标签");
        finish();
        start(LocalSavedTagsTestActivity.USER_B);
        clearTags();
        openManager();
        waitText(text(R.string.LocalSavedTagsEmpty));
        create("账号乙的标签");
        finish();
        start(LocalSavedTagsTestActivity.USER_A);
        openManager();
        waitText("账号甲的标签");
        assertNull(find("账号乙的标签"));
        assertEquals(1, this.<ArrayList<LocalSavedTag>>await(controller::loadTags).size());
        screenshot("p2-account-a-restored");
    }

    @Test
    public void t3_01_singleEditShowsCommittedTagsAndCanClear() throws Exception {
        this.<LocalSavedTag>await(callback -> controller.createTag("学习", callback));
        this.<LocalSavedTag>await(callback -> controller.createTag("资料", callback));
        MessageObject message = sample(501, "#学习 原始正文", null);
        showSamples(new ArrayList<>(Collections.singletonList(message)));
        longPressMessage(501);
        click(text(R.string.LocalSavedTagsSet));
        waitText(text(R.string.LocalSavedTagsChooseInfo));
        click("学习");
        click("资料");
        click(text(R.string.LocalSavedTagsSave));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习"));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "资料"));
        screenshot("p3-single-two-tags");
        openMessageEditor(501);
        click("学习");
        click(text(R.string.LocalSavedTagsCreate));
        setInput("待处理");
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsChooseInfo));
        SQLiteDatabase db = new SQLiteDatabase(storage().getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_ui_link BEFORE INSERT ON local_saved_message_tags BEGIN SELECT RAISE(ABORT, '测试关联失败'); END").stepThis().dispose();
            click(text(R.string.LocalSavedTagsSave));
            waitText(text(R.string.LocalSavedTagsSaveFailed));
            SparseArray<ArrayList<LocalSavedTag>> unchanged = await(callback -> controller.loadMessageTags(Collections.singletonList(501), callback));
            assertEquals(2, unchanged.get(501).size());
            assertTrue(unchanged.get(501).stream().anyMatch(tag -> tag.name.equals("学习")));
            screenshot("p3-edit-write-error");
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_ui_link").stepThis().dispose();
            db.close();
        }
        click(text(R.string.LocalSavedTagsSave));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "待处理"));
        assertNull(find(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习")));
        openMessageEditor(501);
        click("资料");
        click("待处理");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        waitCell(501);
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (find(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "待处理")) != null && SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(60);
        }
        assertNull(find(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "待处理")));
        SparseArray<ArrayList<LocalSavedTag>> empty = await(callback -> controller.loadMessageTags(Collections.singletonList(501), callback));
        assertTrue(empty.get(501).isEmpty());
        assertEquals("#学习 原始正文", message.messageOwner.message);
        main(() -> assertNull(cell(501).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000)));
        screenshot("p3-cleared-message");
    }

    @Test
    public void t3_08_untaggedMessageHasNoPreviewOrTouchArea() throws Exception {
        showSamples(new ArrayList<>(Collections.singletonList(sample(502, "没有标签的收藏消息", null))));
        waitCell(502);
        Thread.sleep(300);
        main(() -> {
            ChatMessageCell cell = cell(502);
            assertEquals(0, cell.getAdditionalPaddingHeight());
            assertNull(cell.getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000));
        });
        assertNull(find("本地测试"));
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        screenshot("p3-empty-tags");
    }

    @Test
    public void t3_03_mediaThemesAndFontsKeepTagsBelowContent() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("媒体标签", callback));
        Theme.ThemeInfo original = Theme.getCurrentTheme();
        int fontSize = SharedConfig.fontSize;
        Theme.ThemeInfo dark = Theme.getTheme("Dark Blue");
        assertNotNull(dark);
        try {
            for (int style = 0; style < 2; style++) {
                final int currentStyle = style;
                main(() -> {
                    SharedConfig.fontSize = currentStyle == 0 ? 16 : 22;
                    Theme.applyTheme(currentStyle == 0 ? original : dark, false, currentStyle != 0);
                    activity.navigation.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                    assertEquals("主题中的标签文字不能透明", 255,
                            android.graphics.Color.alpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText)));
                    org.telegram.ui.Components.LocalSavedTagsView view = (org.telegram.ui.Components.LocalSavedTagsView) chatField("localSavedTagsView");
                    android.widget.LinearLayout chips = (android.widget.LinearLayout) ((android.widget.HorizontalScrollView) view.getChildAt(0)).getChildAt(0);
                    for (int i = 0; i < chips.getChildCount(); i++) {
                        android.widget.TextView chip = (android.widget.TextView) chips.getChildAt(i);
                        assertEquals("筛选条文字不可见：" + chip.getText(), 255, android.graphics.Color.alpha(chip.getCurrentTextColor()));
                    }
                });
                int id = 600 + style * 100;
                for (String type : LocalSavedTagsSamples.TYPES) {
                    String caption = type.equals("long-text") ? String.join("\n", Collections.nCopies(5, "较长的收藏正文，用于核对换行与标签间距。")) : "收藏样本：" + type;
                    MessageObject message = sample(++id, caption, LocalSavedTagsSamples.media(type, id));
                    if (type.equals("reactions")) {
                        message.messageOwner.reactions = new TLRPC.TL_messageReactions();
                        TLRPC.TL_reactionCount reaction = new TLRPC.TL_reactionCount();
                        TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
                        emoji.emoticon = "👍";
                        reaction.reaction = emoji;
                        reaction.count = 3;
                        message.messageOwner.reactions.results.add(reaction);
                    }
                    if (type.equals("voice")) assertTrue(message.isVoice());
                    if (type.equals("music")) assertTrue(message.isMusic());
                    if (type.equals("sticker")) assertTrue(message.isSticker());
                    if (type.equals("round-video")) assertTrue(message.isRoundVideo());
                    this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
                    showSamples(new ArrayList<>(Collections.singletonList(message)));
                    waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "媒体标签"));
                    Thread.sleep(250);
                    main(() -> {
                        ChatMessageCell cell = cell(message.getId());
                        assertNotNull("媒体单元格未显示：" + type, cell);
                        AccessibilityNodeInfo node = cell.getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000);
                        assertNotNull(node);
                        Rect bounds = new Rect();
                        node.getBoundsInParent(bounds);
                        assertTrue(type + " 标签覆盖内容", bounds.top >= cell.getLayoutHeight());
                        assertTrue(type + " 标签超出单元格", bounds.bottom <= cell.getHeight());
                        assertTrue(node.isClickable());
                    });
                    screenshot("p3-media-" + (style == 0 ? "light-" : "dark-") + type);
                }
            }
        } finally {
            main(() -> {
                SharedConfig.fontSize = fontSize;
                Theme.applyTheme(original, false, false);
            });
        }
    }

    @Test
    public void t3_04_albumPartialSingleAndWholeGroupEditing() throws Exception {
        LocalSavedTag a = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag b = await(callback -> controller.createTag("待处理", callback));
        ArrayList<MessageObject> album = new ArrayList<>();
        for (int id = 301; id <= 304; id++) {
            MessageObject message = sample(id, "", LocalSavedTagsSamples.media("photo", id));
            message.messageOwner.grouped_id = 8300;
            album.add(message);
        }
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(album.get(0), album.get(2)), Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(album.get(3)), Collections.singletonList(b.id), Collections.emptyList(), callback));
        showSamples(album);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习 · 2/4"));
        screenshot("p3-album-partial");
        waitCell(301);
        main(() -> cell(301).performAccessibilityAction(R.id.acc_action_msg_options, null));
        click(text(R.string.LocalSavedTagsSet));
        click("学习");
        click(text(R.string.LocalSavedTagsSave));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习 · 1/4"));
        main(() -> cell(304).performAccessibilityAction(R.id.acc_action_msg_options, null));
        click(text(R.string.LocalSavedTagsSetGroup));
        click("学习 · 1/4");
        click(text(R.string.LocalSavedTagsSave));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习"));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "待处理 · 1/4"));
        screenshot("p3-album-whole");
    }

    @Test
    public void t3_05_overflowAndRecycledRowsDoNotKeepOldTags() throws Exception {
        ArrayList<Long> tagIds = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            final String name = "较长的标签名称" + i;
            LocalSavedTag tag = await(callback -> controller.createTag(name, callback));
            tagIds.add(tag.id);
        }
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int id = 1000; id <= 1030; id++) {
            messages.add(sample(id, "滚动回收样本 " + id, null));
        }
        MessageObject tagged = messages.get(0);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(tagged), tagIds, Collections.emptyList(), callback));
        showSamples(messages);
        main(() -> activity.chat.getChatListView().scrollToPosition(30));
        waitCell(1000);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "较长的标签名称12"));
        AtomicReference<String> more = new AtomicReference<>();
        main(() -> {
            try {
                Field field = ChatMessageCell.class.getDeclaredField("localSavedTagsLayout");
                field.setAccessible(true);
                LocalSavedTagsLayout layout = (LocalSavedTagsLayout) field.get(cell(1000));
                assertTrue(layout.size() > 0);
                assertTrue(layout.size() < 12);
                assertEquals(0, layout.getTagId(layout.size() - 1));
                more.set(layout.getDescription(layout.size() - 1));
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        screenshot("p3-two-rows-overflow");
        click(more.get());
        waitText(text(R.string.LocalSavedTagsChooseInfo));
        waitText(text(R.string.LocalSavedTagsSave));
        screenshot("p3-complete-tags-sheet");
        main(() -> activity.chat.getVisibleDialog().dismiss());
        waitEditorClosed();
        Thread.sleep(400);
        main(() -> activity.chat.getChatListView().scrollToPosition(0));
        waitCell(1030);
        Thread.sleep(300);
        main(() -> {
            for (int i = 0; i < activity.chat.getChatListView().getChildCount(); i++) {
                View view = activity.chat.getChatListView().getChildAt(i);
                if (view instanceof ChatMessageCell) {
                    ChatMessageCell cell = (ChatMessageCell) view;
                    if (cell.getMessageObject().getId() != 1000) {
                        assertEquals(0, cell.getAdditionalPaddingHeight());
                        assertNull(cell.getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000));
                    }
                }
            }
        });
        screenshot("p3-recycled-untagged-rows");
    }

    @Test
    public void t3_02_forwardedCopiesAndOtherChatStayIndependent() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("副本标签", callback));
        MessageObject a = sample(1201, "重复收藏的频道正文", null);
        MessageObject b = sample(1202, "重复收藏的频道正文", null);
        a.messageOwner.fwd_from = new TLRPC.TL_messageFwdHeader();
        a.messageOwner.fwd_from.from_name = "离线频道样本";
        a.messageOwner.fwd_from.channel_post = 700;
        a.messageOwner.fwd_from.date = 1500000000;
        b.messageOwner.fwd_from = a.messageOwner.fwd_from;
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(a), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Arrays.asList(a, b)));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "副本标签"));
        main(() -> {
            assertNotNull(cell(1201).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000));
            assertNull(cell(1202).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000));
        });
        screenshot("p3-forwarded-copies");
        main(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = LocalSavedTagsTestActivity.USER_B;
            user.first_name = "其他会话测试";
            MessagesController.getInstance(0).putUser(user, false);
            activity.navigation.removeAllFragments();
            Bundle args = new Bundle();
            args.putLong("user_id", user.id);
            activity.chat = new ChatActivity(args);
            activity.chat.setCurrentAccount(0);
            activity.navigation.addFragmentToStack(activity.chat);
            activity.navigation.showLastFragment();
        });
        MessageObject foreign = sample(1201, "其他会话相同编号", null);
        foreign.messageOwner.peer_id.user_id = LocalSavedTagsTestActivity.USER_B;
        foreign.messageOwner.dialog_id = LocalSavedTagsTestActivity.USER_B;
        showSamples(new ArrayList<>(Collections.singletonList(foreign)));
        waitCell(1201);
        longPressMessage(1201);
        Thread.sleep(300);
        assertNull(find(text(R.string.LocalSavedTagsSet)));
        main(() -> assertNull(cell(1201).getAccessibilityNodeProvider().createAccessibilityNodeInfo(1000000)));
        screenshot("p3-other-chat-no-local-tags");
    }

    @Test
    public void t3_04_documentGroupsKeepSeparateTagRows() throws Exception {
        LocalSavedTag a = await(callback -> controller.createTag("文件甲", callback));
        LocalSavedTag b = await(callback -> controller.createTag("文件乙", callback));
        MessageObject first = sample(1301, "文件一", LocalSavedTagsSamples.media("file", 1301));
        MessageObject second = sample(1302, "文件二", LocalSavedTagsSamples.media("file", 1302));
        first.messageOwner.grouped_id = second.messageOwner.grouped_id = 81300;
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(first), Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(second), Collections.singletonList(b.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Arrays.asList(first, second)));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "文件甲"));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "文件乙"));
        screenshot("p3-document-group");
    }

    @Test
    public void t3_06_hashtagLinksSelectionAndCopyKeepTheirOriginalContent() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("学习", callback));
        String text = "#学习 资料链接 原始正文";
        MessageObject message = sample(1401, text, null);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(message)));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习"));
        tapText(1401, "#学习");
        waitText("#学习");
        main(() -> assertEquals("#学习", chatField("searchingHashtag")));
        screenshot("p3-hashtag-search");
        main(() -> activity.chat.getActionBar().closeSearchField());
        showSamples(new ArrayList<>(Collections.singletonList(message)));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "学习"));
        tapText(1401, "资料链接");
        waitText(text(R.string.Cancel));
        screenshot("p3-original-link-dialog");
        assertEquals(text, message.messageOwner.message);
        assertEquals(2, message.messageOwner.entities.size());
        click(text(R.string.Cancel));
        longPressMessage(1401);
        click(text(R.string.Copy));
        main(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertEquals(text, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        });
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] hintVisible = new boolean[1];
        do {
            main(() -> hintVisible[0] = ((View) chatField("undoView")).getVisibility() == View.VISIBLE);
            if (!hintVisible[0]) {
                break;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        assertFalse("复制反馈尚未退出，不能点击其下方的正文", hintVisible[0]);
        tapText(1401, "#学习");
        waitText("#学习");
        screenshot("p3-hashtag-after-copy");
    }

    @Test
    public void t3_07_accessibleTagActionOpensTheLocalFilter() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("读屏标签", callback));
        MessageObject message = sample(1501, "标签点击与读屏测试", null);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(message)));
        AccessibilityNodeInfo node = waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "读屏标签"));
        assertTrue(node.isClickable());
        assertTrue(node.performAction(AccessibilityNodeInfo.ACTION_CLICK));
        waitFilter(1);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "读屏标签"));
        screenshot("p3-accessibility-filter");
    }

    @Test
    public void t4_01_and_02_batchAppendAndRemoveKeepExistingTags() throws Exception {
        LocalSavedTag study = await(callback -> controller.createTag("学习", callback));
        LocalSavedTag project = await(callback -> controller.createTag("项目资料", callback));
        LocalSavedTag pending = await(callback -> controller.createTag("待处理", callback));
        MessageObject a = sample(101, "第一条收藏", null);
        MessageObject b = sample(102, "第二条收藏", null);
        MessageObject c = sample(103, "第三条收藏", null);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(a), Collections.singletonList(study.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(b, c), Arrays.asList(project.id, pending.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Arrays.asList(a, b, c)));
        selectMessages(a, b);
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        waitText("待处理 · 1/2");
        screenshot("p4-add-partial");
        click("待处理 · 1/2");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        assertSelection(0);
        assertEquals(3, tag("待处理").messageCount);
        selectMessages(a, b);
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        click("待处理 · 2/2");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        assertEquals(3, tag("待处理").messageCount);
        selectMessages(b, c);
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsRemove));
        waitText(text(R.string.LocalSavedTagsRemoveInfo));
        assertNull(find(text(R.string.LocalSavedTagsCreate)));
        screenshot("p4-remove-specific");
        click("项目资料 · 2/2");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(101, 102, 103), callback));
        assertEquals(2, mapping.get(101).size());
        assertEquals(pending.id, mapping.get(102).get(0).id);
        assertEquals(1, mapping.get(102).size());
        assertEquals(1, mapping.get(103).size());
        assertEquals(0, tag("项目资料").messageCount);
        screenshot("p4-batch-result");
    }

    @Test
    public void t4_03_batchAcrossAlbumsKeepsUnselectedMembers() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("选中成员", callback));
        ArrayList<MessageObject> samples = new ArrayList<>();
        for (int id = 301; id <= 306; id++) {
            MessageObject message = sample(id, "", LocalSavedTagsSamples.media("photo", id));
            message.messageOwner.grouped_id = id <= 304 ? 800 : 900;
            samples.add(message);
        }
        MessageObject a = samples.get(0);
        MessageObject b = samples.get(4);
        showSamples(samples);
        selectMessages(b, a);
        assertSelection(2);
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        click("选中成员 · 0/2");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        SparseArray<ArrayList<LocalSavedTag>> mapping = await(callback -> controller.loadMessageTags(Arrays.asList(301, 302, 303, 304, 305, 306), callback));
        for (int id = 301; id <= 306; id++) {
            assertEquals(id == 301 || id == 305 ? 1 : 0, mapping.get(id).size());
        }
        screenshot("p4-album-selected-members");
        main(activity.chat::clearSelectionMode);
        selectMessages(b);
        click(text(R.string.LocalSavedTagsSet));
        waitText(text(R.string.LocalSavedTagsChooseInfo));
        click("选中成员");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        assertEquals(1, tag("选中成员").messageCount);
    }

    @Test
    public void t4_04_failedBatchKeepsSelectionForRetry() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("批量重试", callback));
        MessageObject a = sample(201, "失败前的第一条", null);
        MessageObject b = sample(202, "失败前的第二条", null);
        showSamples(new ArrayList<>(Arrays.asList(a, b)));
        selectMessages(a, b);
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        click("批量重试 · 0/2");
        SQLiteDatabase db = new SQLiteDatabase(storage().getDatabaseFile().getPath());
        try {
            db.executeFast("CREATE TRIGGER fail_ui_batch BEFORE INSERT ON local_saved_message_tags WHEN NEW.message_id = 202 BEGIN SELECT RAISE(ABORT, '测试批次中断'); END").stepThis().dispose();
            click(text(R.string.LocalSavedTagsSave));
            waitText(text(R.string.LocalSavedTagsSaveFailed));
            assertSelection(2);
            assertEquals(0, tag("批量重试").messageCount);
            screenshot("p4-failure-keeps-selection");
        } finally {
            db.executeFast("DROP TRIGGER IF EXISTS fail_ui_batch").stepThis().dispose();
            db.close();
        }
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        assertSelection(0);
        assertEquals(2, tag("批量重试").messageCount);
        screenshot("p4-retry-success");
    }

    @Test
    public void t4_05_selectionLimitCancelAndOriginalActions() throws Exception {
        this.<LocalSavedTag>await(callback -> controller.createTag("取消不保存", callback));
        ArrayList<MessageObject> samples = new ArrayList<>();
        for (int id = 1601; id <= 1701; id++) {
            samples.add(sample(id, "上限测试 " + id, null));
        }
        showSamples(samples);
        selectMessages(samples.toArray(new MessageObject[0]));
        assertSelection(100);
        waitText(text(R.string.Copy));
        waitText(text(R.string.Forward));
        waitText(text(R.string.Delete));
        screenshot("p4-selection-limit");
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        click("取消不保存 · 0/100");
        main(() -> activity.chat.getVisibleDialog().dismiss());
        waitEditorClosed();
        assertSelection(100);
        assertEquals(0, tag("取消不保存").messageCount);
        main(activity.chat::clearSelectionMode);
        assertSelection(0);
        MessageObject a = samples.get(0);
        MessageObject b = samples.get(1);
        showSamples(new ArrayList<>(Arrays.asList(a, b)));
        selectMessages(a, b);
        click(text(R.string.Copy));
        main(() -> {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(copied.contains(a.messageOwner.message));
            assertTrue(copied.contains(b.messageOwner.message));
        });
        assertSelection(0);
        assertEquals(0, tag("取消不保存").messageCount);
    }

    @Test
    public void t5_01_and_02_bothEntrancesLoadAll123HistoricalMessages() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        LocalSavedTag tag = await(callback -> controller.createTag("历史学习", callback));
        ArrayList<MessageObject> history = new ArrayList<>();
        savedMessages = new ArrayList<>();
        for (int id = 20001; id <= 20123; id++) {
            MessageObject message = sample(id, "历史收藏 " + id, null);
            history.add(message);
            savedMessages.add(message.messageOwner);
            source.remote.put(id, message.messageOwner);
            if (id < 20021) {
                source.cached.put(id, message.messageOwner);
            }
        }
        this.<Void>await(callback -> controller.applyTags(history, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        ArrayList<MessageObject> visible = new ArrayList<>(history.subList(120, 123));
        showSamples(visible);
        waitCell(20123);
        for (int entry = 0; entry < 2; entry++) {
            if (entry == 0) {
                click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "历史学习"));
            } else {
                click(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "历史学习"));
            }
            waitFilter(50);
            screenshot("p5-history-first-" + entry);
            click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 50));
            waitFilter(100);
            click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 100));
            waitFilter(123);
            waitText(LocaleController.formatString(R.string.LocalSavedTagsFilterEnd, 123));
            main(() -> {
                ArrayList<MessageObject> result = filter().getMessages();
                for (int i = 0; i < result.size(); i++) {
                    assertEquals(20123 - i, result.get(i).getId());
                }
                assertTrue(filter().isEndReached());
                activity.chat.getChatListView().scrollToPosition(122);
            });
            waitCell(20001);
            screenshot("p5-history-oldest-" + entry);
            click(text(R.string.LocalSavedTagsAll));
            main(() -> assertNull(filter()));
            waitCell(20123);
        }
        assertFalse(source.requests.isEmpty());
        assertEquals(123, tag("历史学习").messageCount);
        screenshot("p5-restored-all");
    }

    @Test
    public void t5_03_failureAndMissingResponseRemainRetryableOnScreen() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        LocalSavedTag tag = await(callback -> controller.createTag("缺失正文", callback));
        MessageObject history = sample(21001, "补回的收藏消息", null);
        savedMessages = new ArrayList<>(Collections.singletonList(history.messageOwner));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(history), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(sample(21002, "当前页面的无标签消息", null))));
        source.failure = new IOException("测试断网");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "缺失正文"));
        waitFilter(0);
        waitText(text(R.string.LocalSavedTagsFilterRetry));
        assertEquals(1, tag("缺失正文").messageCount);
        screenshot("p5-network-error");
        source.failure = null;
        click(text(R.string.LocalSavedTagsFilterRetry));
        waitFilter(0);
        main(() -> assertEquals(1, filter().getPendingCount()));
        screenshot("p5-response-omission");
        source.remote.put(21001, history.messageOwner);
        click(text(R.string.LocalSavedTagsFilterRetry));
        waitFilter(1);
        waitCell(21001);
        assertEquals(1, tag("缺失正文").messageCount);
        screenshot("p5-retry-restored");
    }

    @Test
    public void t5_05_filteredAlbumsUseOnlyMatchingMembersAndRestoreWholeGroup() throws Exception {
        LocalSavedTag a = await(callback -> controller.createTag("相册两张", callback));
        LocalSavedTag b = await(callback -> controller.createTag("相册单张", callback));
        ArrayList<MessageObject> album = new ArrayList<>();
        for (int id = 22001; id <= 22004; id++) {
            MessageObject message = sample(id, "", LocalSavedTagsSamples.media("photo", id));
            message.messageOwner.grouped_id = 22000;
            album.add(message);
        }
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(album.get(0), album.get(2)), Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(album.get(1)), Collections.singletonList(b.id), Collections.emptyList(), callback));
        showSamples(album);
        AtomicReference<MessageObject.GroupedMessages> original = new AtomicReference<>();
        main(() -> original.set(activity.chat.getGroup(22000)));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "相册两张"));
        waitFilter(2);
        waitCell(22001);
        main(() -> {
            assertEquals(2, activity.chat.getGroup(22000).messages.size());
            assertNotSame(original.get(), activity.chat.getGroup(22000));
            assertEquals(4, original.get().messages.size());
            for (MessageObject message : filter().getMessages()) {
                assertTrue(message.getId() == 22001 || message.getId() == 22003);
                assertEquals(22000, message.messageOwner.grouped_id);
            }
        });
        screenshot("p5-album-two-matches");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "相册单张"));
        waitFilter(1);
        waitCell(22002);
        main(() -> assertNull(activity.chat.getValidGroupedMessage(filter().getMessages().get(0))));
        screenshot("p5-album-single-match");
        click(text(R.string.LocalSavedTagsAll));
        waitCell(22004);
        main(() -> {
            assertSame(original.get(), activity.chat.getGroup(22000));
            assertEquals(4, activity.chat.getGroup(22000).messages.size());
        });
        screenshot("p5-album-restored");
    }

    @Test
    public void t5_06_and_09_switchesIgnoreLateResponsesAndKeepHashtagSearchSeparate() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        LocalSavedTag a = await(callback -> controller.createTag("互斥甲", callback));
        LocalSavedTag b = await(callback -> controller.createTag("互斥乙", callback));
        MessageObject first = sample(23001, "迟到的甲结果", null);
        MessageObject second = sample(23002, "#学习 当前乙结果", null);
        savedMessages = new ArrayList<>(Arrays.asList(first.messageOwner, second.messageOwner));
        source.remote.put(23001, first.messageOwner);
        source.remote.put(23002, second.messageOwner);
        source.hold = true;
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(first), Collections.singletonList(a.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(second), Collections.singletonList(b.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(sample(23003, "普通收藏", null))));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "互斥甲"));
        waitRequests(source, 1);
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "互斥乙"));
        waitRequests(source, 2);
        main(() -> source.replies.get(1).run());
        main(() -> assertTrue(filter().getMessages().isEmpty()));
        main(() -> source.replies.get(2).run());
        waitFilter(1);
        waitCell(23002);
        main(() -> assertEquals(b.id, filter().tagId));
        screenshot("p5-fast-switch");
        tapText(23002, "#学习");
        waitText("#学习");
        main(() -> {
            assertNull(filter());
            assertEquals("#学习", chatField("searchingHashtag"));
            activity.chat.getActionBar().closeSearchField();
        });
        showSamples(new ArrayList<>(Collections.singletonList(sample(23003, "普通收藏", null))));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "互斥甲"));
        waitRequests(source, 3);
        main(() -> activity.chat.setTagFilter(ReactionsLayoutInBubble.VisibleReaction.fromEmojicon("❤")));
        main(() -> source.replies.get(3).run());
        main(() -> assertNull(filter()));
        screenshot("p5-native-filter-cancels-local");
    }

    @Test
    public void t5_08_committedChangesRefreshFilterAndLocateInAllMessages() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("整理中", callback));
        MessageObject first = sample(24001, "定位第一条", null);
        MessageObject second = sample(24002, "保留第二条", null);
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(first, second), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Arrays.asList(first, second)));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "整理中"));
        waitFilter(2);
        openMessageEditor(24001);
        click("整理中");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        waitFilter(1);
        main(() -> assertEquals(24002, filter().getMessages().get(0).getId()));
        openManager();
        click("整理中");
        click(text(R.string.LocalSavedTagsRename));
        setInput("已经整理");
        click(text(R.string.Save));
        waitText("已经整理");
        main(() -> activity.chat.getVisibleDialog().dismiss());
        waitEditorClosed();
        waitFilter(1);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "已经整理"));
        screenshot("p5-renamed-filter");
        waitCell(24002);
        main(() -> cell(24002).performAccessibilityAction(R.id.acc_action_msg_options, null));
        click(text(R.string.LocalSavedTagsLocate));
        main(() -> assertNull(filter()));
        waitCell(24002);
        screenshot("p5-located-in-all");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "已经整理"));
        waitFilter(1);
        openManager();
        click("已经整理");
        click(text(R.string.LocalSavedTagsDelete));
        click(text(R.string.Delete));
        waitText(text(R.string.LocalSavedTagsEmpty));
        main(() -> activity.chat.getVisibleDialog().dismiss());
        waitEditorClosed();
        main(() -> assertNull(filter()));
        waitCell(24001);
        assertTrue(this.<ArrayList<LocalSavedTag>>await(controller::loadTags).isEmpty());
        screenshot("p5-deleted-filter-returns-all");
    }

    @Test
    public void t5_09_sourceSubdialogTagOpensGlobalSavedFilter() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        LocalSavedTag tag = await(callback -> controller.createTag("跨来源", callback));
        MessageObject a = sample(25001, "来源甲收藏", null);
        MessageObject b = sample(25002, "来源乙收藏", null);
        savedMessages = new ArrayList<>(Arrays.asList(a.messageOwner, b.messageOwner));
        source.remote.put(25001, a.messageOwner);
        source.remote.put(25002, b.messageOwner);
        this.<Void>await(callback -> controller.applyTags(Arrays.asList(a, b), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        main(() -> {
            TLRPC.TL_user sender = new TLRPC.TL_user();
            sender.id = LocalSavedTagsTestActivity.USER_B;
            sender.first_name = "来源甲";
            MessagesController.getInstance(0).putUser(sender, false);
            Bundle args = new Bundle();
            args.putLong("user_id", LocalSavedTagsTestActivity.USER_A);
            args.putInt("chatMode", ChatActivity.MODE_SAVED);
            ChatActivity subdialog = new ChatActivity(args);
            subdialog.setCurrentAccount(0);
            subdialog.setSavedDialog(LocalSavedTagsTestActivity.USER_B);
            activity.chat.presentFragment(subdialog);
            activity.chat = subdialog;
        });
        Thread.sleep(600);
        showSamples(new ArrayList<>(Collections.singletonList(a)));
        waitCell(25001);
        click(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "跨来源"));
        Thread.sleep(600);
        main(() -> {
            List<org.telegram.ui.ActionBar.BaseFragment> stack = activity.navigation.getFragmentStack();
            ChatActivity next = (ChatActivity) stack.get(stack.size() - 1);
            assertNotSame(activity.chat, next);
            activity.chat = next;
            assertEquals(0L, activity.chat.getSavedDialogId());
        });
        waitFilter(2);
        waitCell(25002);
        screenshot("p5-source-to-global");
    }

    @Test
    public void t6_01_and_05_historyBufferShowsTwoPagesBeforeReportingEnd() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 30001, 100);
        showSamples(new ArrayList<>(Collections.singletonList(history.get(99))));
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(50);
        main(() -> assertFalse(filter().isEndReached()));
        screenshot("p6-first-page");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 50));
        waitFilter(100);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsUntaggedEnd, 100));
        main(() -> {
            assertTrue(filter().isEndReached());
            assertEquals(Collections.singletonList(0), source.historyOffsets);
            for (int i = 0; i < 100; i++) {
                assertEquals(30100 - i, filter().getMessages().get(i).getId());
            }
            activity.chat.getChatListView().scrollToPosition(99);
        });
        waitCell(30001);
        screenshot("p6-buffer-consumed");
    }

    @Test
    public void t6_02_scanLimitOffersContinueAndFinds150OlderMessages() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 31001, 650);
        LocalSavedTag tag = await(callback -> controller.createTag("已分类", callback));
        this.<Void>await(callback -> controller.applyTags(history.subList(150, 650), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(history.get(649))));
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(0);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsContinueHistory, 0));
        main(() -> {
            assertFalse(filter().isEndReached());
            assertTrue(filter().isScanLimitReached());
            assertEquals(5, source.historyOffsets.size());
        });
        screenshot("p6-scan-limit");
        click(LocaleController.formatString(R.string.LocalSavedTagsContinueHistory, 0));
        waitFilter(50);
        waitCell(31150);
        screenshot("p6-continue-found");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 50));
        waitFilter(100);
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 100));
        waitFilter(150);
        waitText(LocaleController.formatString(R.string.LocalSavedTagsUntaggedEnd, 150));
        main(() -> {
            assertEquals(7, source.historyOffsets.size());
            for (int i = 0; i < 150; i++) {
                assertEquals(31150 - i, filter().getMessages().get(i).getId());
            }
        });
        assertEquals(500, tag("已分类").messageCount);
        screenshot("p6-older-history-complete");
    }

    @Test
    public void t6_04_offlineCacheShowsIncompleteRangeAndRetriesSameCursor() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 32001, 120);
        source.realCache = true;
        source.historyFailure = new IOException("测试收藏历史断网");
        ArrayList<TLRPC.Message> cached = new ArrayList<>(source.history.subList(117, 120));
        main(() -> MessagesStorage.getInstance(0).putMessages(cached, true, true, false, 0, false, 0, 0));
        showSamples(new ArrayList<>(Collections.singletonList(history.get(119))));
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(3);
        waitText(text(R.string.LocalSavedTagsCacheIncomplete));
        main(() -> {
            assertTrue(filter().isCacheOnly());
            assertFalse(filter().isEndReached());
            assertNotNull(filter().getError());
        });
        screenshot("p6-offline-incomplete");
        source.historyFailure = null;
        click(text(R.string.LocalSavedTagsCacheIncomplete));
        waitFilter(50);
        main(() -> {
            assertEquals(Arrays.asList(0, 0), source.historyOffsets);
            assertFalse(filter().isCacheOnly());
            assertNull(filter().getError());
        });
        screenshot("p6-online-restored");
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 50));
        waitFilter(100);
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterMore, 100));
        waitFilter(120);
        main(() -> assertTrue(filter().isEndReached()));
    }

    @Test
    public void t6_06_tagChangesNewMessagesRefreshAndLateResponsesStayConsistent() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 33001, 3);
        LocalSavedTag tag = await(callback -> controller.createTag("整理完成", callback));
        showSamples(new ArrayList<>(history));
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(3);
        openMessageEditor(33003);
        click("整理完成");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        waitFilter(2);
        main(() -> assertEquals(33002, filter().getMessages().get(0).getId()));
        screenshot("p6-tagged-result-removed");
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(history.get(2)), Collections.emptyList(), Collections.singletonList(tag.id), callback));
        waitFilter(3);
        MessageObject incoming = sample(33004, "刚加入的未分类收藏", null);
        source.history.add(incoming.messageOwner);
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.didReceiveNewMessages,
                LocalSavedTagsTestActivity.USER_A, new ArrayList<>(Collections.singletonList(incoming)), false, 0));
        waitFilter(4);
        waitCell(33004);
        main(() -> assertEquals(33004, filter().getMessages().get(0).getId()));
        screenshot("p6-new-message-refresh");
        source.history.add(sample(33005, "主动刷新补入的收藏", null).messageOwner);
        click(LocaleController.formatString(R.string.LocalSavedTagsUntaggedEnd, 4));
        waitFilter(5);
        waitCell(33005);
        screenshot("p6-manual-refresh");
        source.hold = true;
        click(text(R.string.LocalSavedTagsUntagged));
        AtomicReference<LocalSavedTagsController.FilterSession> previous = new AtomicReference<>();
        main(() -> previous.set(filter()));
        click(text(R.string.LocalSavedTagsAll));
        main(() -> {
            source.replies.get(10000 + source.historyOffsets.size()).run();
            assertTrue(previous.get().isCancelled());
            assertNull(filter());
        });
        screenshot("p6-late-response-ignored");
    }

    @Test
    public void t7_02_partialHistoryAndConfirmedClearRefreshCountsAndKeepEmptyTag() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 34001, 5);
        for (MessageObject message : history) {
            source.remote.put(message.getId(), message.messageOwner);
        }
        LocalSavedTag tag = await(callback -> controller.createTag("删除范围", callback));
        this.<Void>await(callback -> controller.applyTags(history, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(history));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "删除范围"));
        waitFilter(5);
        main(() -> NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.messagesDeleted,
                new ArrayList<>(Arrays.asList(34001, 34002)), 0L, false));
        assertEquals(5, tag("删除范围").messageCount);
        TLRPC.TL_messages_deleteHistory request = new TLRPC.TL_messages_deleteHistory();
        request.peer = new TLRPC.TL_inputPeerSelf();
        request.max_id = 34002;
        main(() -> controller.onDeleteResponse(LocalSavedTagsTestActivity.USER_A, request, new TLRPC.TL_messages_affectedHistory(), null));
        waitFilter(3);
        assertEquals(3, tag("删除范围").messageCount);
        screenshot("p7-partial-history-confirmed");
        for (int id = 34003; id <= 34005; id++) {
            TLRPC.TL_messageEmpty empty = new TLRPC.TL_messageEmpty();
            empty.id = id;
            source.remote.put(id, empty);
        }
        request.max_id = Integer.MAX_VALUE;
        main(() -> controller.onDeleteResponse(LocalSavedTagsTestActivity.USER_A, request, new TLRPC.TL_messages_affectedHistory(), null));
        waitFilter(0);
        assertEquals(0, tag("删除范围").messageCount);
        screenshot("p7-cleared-history-empty-tag");
        openManager();
        waitText("删除范围");
        assertEquals(1, this.<ArrayList<LocalSavedTag>>await(controller::loadTags).size());
        screenshot("p7-empty-tag-manager");
    }

    @Test
    public void t7_03_remoteResultEditsAndChannelDeletionKeepSavedTags() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        MessageObject history = sample(35001, "编辑前的历史收藏", null);
        savedMessages = new ArrayList<>(Collections.singletonList(history.messageOwner));
        source.remote.put(history.getId(), history.messageOwner);
        LocalSavedTag tag = await(callback -> controller.createTag("编辑保留", callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(history), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(sample(35002, "当前页面消息", null))));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "编辑保留"));
        waitFilter(1);
        waitCell(35001);
        MessageObject edited = sample(35001, "编辑后的历史收藏 #学习", null);
        source.remote.put(35001, edited.messageOwner);
        main(() -> {
            MessagesStorage.getInstance(0).putMessages(new ArrayList<>(Collections.singletonList(edited.messageOwner)), true, true, false, 0, false, 0, 0);
            NotificationCenter.getInstance(0).postNotificationName(NotificationCenter.replaceMessagesObjects,
                    LocalSavedTagsTestActivity.USER_A, new ArrayList<>(Collections.singletonList(edited)));
        });
        waitCondition(() -> cell(35001) != null && cell(35001).getMessageObject().messageOwner.message.equals(edited.messageOwner.message));
        assertEquals(1, tag("编辑保留").messageCount);
        screenshot("p7-edited-filtered-message");
        TL_update.TL_updateDeleteChannelMessages origin = new TL_update.TL_updateDeleteChannelMessages();
        origin.channel_id = 909;
        origin.messages.add(35001);
        main(() -> MessagesController.getInstance(0).processUpdateArray(new ArrayList<>(Collections.singletonList(origin)), null, null, false, 1700000000));
        waitFilter(1);
        assertEquals(1, tag("编辑保留").messageCount);
        assertEquals(1, source.requests.size());
        screenshot("p7-channel-copy-deletion-preserved");
    }

    @Test
    public void t7_04_mediaAndMessageCacheClearKeepTagsAndAllowBodyRetry() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        source.realCache = true;
        ArrayList<MessageObject> history = historySamples(source, 36001, 3);
        for (MessageObject message : history) {
            source.remote.put(message.getId(), message.messageOwner);
        }
        LocalSavedTag tag = await(callback -> controller.createTag("缓存保留", callback));
        this.<Void>await(callback -> controller.applyTags(history, Collections.singletonList(tag.id), Collections.emptyList(), callback));
        MessagesStorage cache = MessagesStorage.getInstance(0);
        main(() -> cache.putMessages(source.history, true, true, false, 0, false, 0, 0));
        this.<Void>await(callback -> cache.getStorageQueue().postRunnable(() -> {
            Exception error = null;
            try {
                cache.getDatabase().executeFast("REPLACE INTO dialogs(did, last_mid, last_mid_i) VALUES(" + LocalSavedTagsTestActivity.USER_A + ",36003,36003)").stepThis().dispose();
            } catch (Exception failure) {
                error = failure;
            }
            Exception result = error;
            AndroidUtilities.runOnUIThread(() -> callback.run(null, result));
        }));
        File media = LocalSavedTagsSamples.audioFile(activity.getCacheDir(), "p7-cache-media.wav");
        assertTrue(media.isFile());
        main(() -> FileLoader.getInstance(0).deleteFiles(new ArrayList<>(Collections.singletonList(media)), 2));
        waitCondition(() -> !media.exists());
        main(cache::clearLocalDatabase);
        TLRPC.messages_Messages remaining = await(callback -> cache.getMessagesByIds(LocalSavedTagsTestActivity.USER_A,
                new ArrayList<>(Arrays.asList(36001, 36002, 36003)), callback));
        assertEquals(1, remaining.messages.size());
        assertEquals(3, tag("缓存保留").messageCount);
        assertTrue(storage().getDatabaseFile().isFile());
        source.failure = new IOException("测试清理缓存后的离线恢复");
        showSamples(new ArrayList<>(Collections.singletonList(history.get(2))));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "缓存保留"));
        waitFilter(1);
        waitText(text(R.string.LocalSavedTagsFilterRetry));
        screenshot("p7-cache-clear-pending-body");
        source.failure = null;
        click(text(R.string.LocalSavedTagsFilterRetry));
        waitFilter(3);
        assertEquals(3, tag("缓存保留").messageCount);
        waitCell(36001);
        screenshot("p7-cache-clear-body-restored");
    }

    @Test
    public void t7_05_logoutReloginAndSlotReuseRestoreTaggedMessageOnScreen() throws Exception {
        LocalSavedTag tag = await(callback -> controller.createTag("重登保留", callback));
        MessageObject saved = sample(39001, "重新登录后仍有标签的收藏", null);
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(saved), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        finish();
        main(() -> UserConfig.getInstance(0).clearConfig());
        start(LocalSavedTagsTestActivity.USER_B);
        clearTags();
        this.<LocalSavedTag>await(callback -> controller.createTag("另一个账号的标签", callback));
        assertEquals(1, this.<ArrayList<LocalSavedTag>>await(controller::loadTags).size());
        assertEquals(LocalSavedTagsTestActivity.USER_B, controller.getUserId());
        openManager();
        waitText("另一个账号的标签");
        screenshot("p7-reused-account-slot");
        finish();
        main(() -> UserConfig.getInstance(0).clearConfig());
        start(LocalSavedTagsTestActivity.USER_A);
        assertEquals(1, tag("重登保留").messageCount);
        showSamples(new ArrayList<>(Collections.singletonList(saved)));
        waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "重登保留"));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "重登保留"));
        waitFilter(1);
        waitCell(39001);
        screenshot("p7-relogged-message-restored");
    }

    @Test
    public void t7_06_premiumUsesTheSameManagerBatchAndBothFilters() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 38001, 2);
        main(() -> UserConfig.getInstance(0).getCurrentUser().premium = true);
        assertTrue(UserConfig.getInstance(0).isPremium());
        openManager();
        create("本地同路径");
        main(() -> activity.chat.getVisibleDialog().dismiss());
        waitEditorClosed();
        showSamples(new ArrayList<>(history));
        selectMessages(history.toArray(new MessageObject[0]));
        click(text(R.string.LocalSavedTagsSet));
        click(text(R.string.LocalSavedTagsAdd));
        click("本地同路径 · 0/2");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        assertSelection(0);
        assertEquals(2, tag("本地同路径").messageCount);
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "本地同路径"));
        waitFilter(2);
        screenshot("p7-premium-local-filter");
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(0);
        waitText(text(R.string.LocalSavedTagsUntaggedEmpty));
        click(text(R.string.LocalSavedTagsAll));
        openMessageEditor(38001);
        click("本地同路径");
        click(text(R.string.LocalSavedTagsSave));
        waitEditorClosed();
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(1);
        main(() -> assertEquals(38001, filter().getMessages().get(0).getId()));
        screenshot("p7-premium-untagged-filter");
    }

    @Test
    public void t7_07_actualAudioPlaybackKeepsTagActionsAvailable() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        savedMessages = new ArrayList<>();
        int id = 37001;
        for (String type : new String[]{"voice", "music"}) {
            File file = LocalSavedTagsSamples.audioFile(activity.getCacheDir(), "p7-play-" + type + ".wav");
            String name = type.equals("voice") ? "语音播放" : "音频播放";
            LocalSavedTag tag = await(callback -> controller.createTag(name, callback));
            MessageObject message = playableAudioSample(id++, type, file);
            savedMessages.add(message.messageOwner);
            source.remote.put(message.getId(), message.messageOwner);
            this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
            try {
                showSamples(new ArrayList<>(Collections.singletonList(message)));
                waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, name));
                waitCell(message.getId());
                main(() -> assertTrue(cell(message.getId()).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)));
                waitCondition(() -> MediaController.getInstance().isPlayingMessage(message) && MediaController.getInstance().getCurrentPosition() > 300);
                long[] pausedAt = new long[1];
                main(() -> {
                    assertTrue(cell(message.getId()).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
                    assertTrue(MediaController.getInstance().isMessagePaused());
                    pausedAt[0] = MediaController.getInstance().getCurrentPosition();
                });
                screenshot("p7-" + type + "-paused");
                main(() -> assertTrue(cell(message.getId()).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)));
                waitCondition(() -> !MediaController.getInstance().isMessagePaused()
                        && MediaController.getInstance().getCurrentPosition() > pausedAt[0] + 250);
                click(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, name));
                waitFilter(1);
                main(() -> assertTrue(MediaController.getInstance().isPlayingMessage(filter().getMessages().get(0))));
                screenshot("p7-" + type + "-playing-filter");
                assertEquals(1, tag(name).messageCount);
                click(text(R.string.LocalSavedTagsAll));
            } finally {
                main(() -> MediaController.getInstance().cleanupPlayer(true, true));
                assertTrue(!file.exists() || file.delete());
            }
            this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
        }
    }

    @Test
    public void t7_07_videoPlaybackReturnsToTheTaggedMessage() throws Exception {
        File file = LocalSavedTagsSamples.videoFile(activity.getCacheDir(), "p7-play-video.mp4");
        MessageObject message = sample(37501, "实际视频播放", LocalSavedTagsSamples.media("video", 37501));
        message.messageOwner.attachPath = file.getAbsolutePath();
        message.attachPathExists = true;
        message.mediaExists = true;
        message.getDocument().size = file.length();
        LocalSavedTag tag = await(callback -> controller.createTag("视频播放", callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        try {
            showSamples(new ArrayList<>(Collections.singletonList(message)));
            waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "视频播放"));
            waitCell(37501);
            main(() -> assertTrue(cell(37501).performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null)));
            waitCondition(() -> PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()
                    && photoPlayer() != null && photoPlayer().getCurrentPosition() > 300);
            main(() -> {
                assertTrue(photoPlayer().isPlaying());
                assertTrue(photoPlayer().getDuration() >= 7000);
            });
            screenshot("p7-video-playing");
            main(() -> PhotoViewer.getInstance().closePhoto(false, false));
            waitCondition(() -> !PhotoViewer.getInstance().isVisible());
            waitCell(37501);
            waitText(LocaleController.formatString(R.string.LocalSavedTagsAccessibility, "视频播放"));
            assertEquals(1, tag("视频播放").messageCount);
            screenshot("p7-video-returned-to-tags");
        } finally {
            main(() -> {
                if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isVisible()) {
                    PhotoViewer.getInstance().closePhoto(false, false);
                }
                MediaController.getInstance().cleanupPlayer(true, true);
            });
            assertTrue(!file.exists() || file.delete());
        }
    }

    @Test
    public void t7_07_forwardChooserOpensFromTheFilteredMessage() throws Exception {
        MessageObject message = sample(39501, "只验证转发选择入口 #学习", null);
        LocalSavedTag tag = await(callback -> controller.createTag("原功能回归", callback));
        this.<Void>await(callback -> controller.applyTags(Collections.singletonList(message), Collections.singletonList(tag.id), Collections.emptyList(), callback));
        showSamples(new ArrayList<>(Collections.singletonList(message)));
        click(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, "原功能回归"));
        waitFilter(1);
        selectMessages(message);
        click(text(R.string.Forward));
        waitCondition(() -> {
            List<org.telegram.ui.ActionBar.BaseFragment> stack = activity.navigation.getFragmentStack();
            return stack.get(stack.size() - 1) instanceof DialogsActivity;
        });
        assertEquals(1, tag("原功能回归").messageCount);
        screenshot("p7-original-forward-chooser");
    }

    @Test
    public void t7_07_statusChangeWhilePressedDoesNotTurnLoadMoreIntoRefresh() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 39601, 100);
        showSamples(new ArrayList<>(Collections.singletonList(history.get(99))));
        click(text(R.string.LocalSavedTagsUntagged));
        waitFilter(50);
        AtomicReference<LocalSavedTagsController.FilterSession> original = new AtomicReference<>();
        main(() -> {
            original.set(filter());
            View status = ((android.widget.LinearLayout) chatField("localSavedTagsView")).getChildAt(1);
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, status.getWidth() / 2f, status.getHeight() / 2f, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, status.getWidth() / 2f, status.getHeight() / 2f, 0);
            try {
                assertTrue(status.dispatchTouchEvent(down));
                filter().loadMore();
                assertTrue(filter().isEndReached());
                status.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        });
        Thread.sleep(200);
        main(() -> {
            assertSame("按下继续加载后，列表完成时不能把抬手变成刷新", original.get(), filter());
            assertEquals(Collections.singletonList(0), source.historyOffsets);
            assertEquals(100, filter().getMessages().size());
        });
        screenshot("p7-status-press-keeps-session");
    }

    private MessageObject playableAudioSample(int id, String type, File file) {
        TLRPC.MessageMedia media = LocalSavedTagsSamples.media(type, id);
        media.document.mime_type = "audio/wav";
        media.document.size = file.length();
        for (TLRPC.DocumentAttribute attribute : media.document.attributes) {
            if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                attribute.duration = 8;
            } else if (attribute instanceof TLRPC.TL_documentAttributeFilename) {
                attribute.file_name = file.getName();
            }
        }
        MessageObject message = sample(id, "", media);
        message.messageOwner.attachPath = file.getAbsolutePath();
        message.attachPathExists = true;
        message.mediaExists = true;
        return message;
    }

    @Test
    public void t7_07_sameTagsRefreshWhilePressedKeepsChipClick() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 39801, 1);
        showSamples(history);
        waitCell(39801);
        waitText(text(R.string.LocalSavedTagsUntagged));
        main(() -> {
            org.telegram.ui.Components.LocalSavedTagsView view = (org.telegram.ui.Components.LocalSavedTagsView) chatField("localSavedTagsView");
            android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) view.getChildAt(0);
            android.widget.LinearLayout chips = (android.widget.LinearLayout) scroll.getChildAt(0);
            View chip = chips.getChildAt(1);
            int[] origin = new int[2];
            int[] position = new int[2];
            view.getLocationOnScreen(origin);
            chip.getLocationOnScreen(position);
            float x = position[0] - origin[0] + chip.getWidth() / 2f;
            float y = position[1] - origin[1] + chip.getHeight() / 2f;
            long time = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
            MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, x, y, 0);
            try {
                assertTrue(view.dispatchTouchEvent(down));
                view.setTags(controller.getTags(), 0);
                view.dispatchTouchEvent(up);
            } finally {
                down.recycle();
                up.recycle();
            }
        });
        waitFilter(1);
        main(() -> assertEquals(Collections.singletonList(0), source.historyOffsets));
        screenshot("p7-chip-press-keeps-click");
    }

    @Test
    public void t7_07_delayedTagChangeDoesNotRestartFreshFilter() throws Exception {
        LocalSavedTagsFilterTest.Source source = installSource();
        ArrayList<MessageObject> history = historySamples(source, 39901, 1);
        showSamples(history);
        int[] animation = new int[1];
        main(() -> animation[0] = NotificationCenter.getInstance(0).setAnimationInProgress(0, null, false));
        try {
            this.<LocalSavedTag>await(callback -> controller.createTag("延迟通知", callback));
            click(text(R.string.LocalSavedTagsUntagged));
            waitFilter(1);
            main(() -> {
                LocalSavedTagsController.FilterSession original = filter();
                assertEquals(Collections.singletonList(0), source.historyOffsets);
                NotificationCenter.getInstance(0).onAnimationFinish(animation[0]);
                assertSame("筛选已包含提交后的数据，延迟通知不能重新发起同一查询", original, filter());
                assertEquals(Collections.singletonList(0), source.historyOffsets);
            });
            screenshot("p7-delayed-notification-keeps-session");
        } finally {
            main(() -> NotificationCenter.getInstance(0).onAnimationFinish(animation[0]));
        }
    }

    private VideoPlayer photoPlayer() {
        try {
            Field field = PhotoViewer.class.getDeclaredField("videoPlayer");
            field.setAccessible(true);
            return (VideoPlayer) field.get(PhotoViewer.getInstance());
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void waitCondition(BooleanSupplier condition) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        boolean[] done = new boolean[1];
        do {
            main(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        } while (SystemClock.uptimeMillis() < end);
        screenshot("p7-unexpected-screen");
        fail("界面状态等待超时");
    }

    private ArrayList<MessageObject> historySamples(LocalSavedTagsFilterTest.Source source, int firstId, int count) {
        ArrayList<MessageObject> messages = new ArrayList<>();
        for (int id = firstId; id < firstId + count; id++) {
            MessageObject message = sample(id, "待整理收藏 " + id, null);
            source.history.add(message.messageOwner);
            messages.add(message);
        }
        savedMessages = source.history;
        return messages;
    }

    private LocalSavedTagsFilterTest.Source installSource() throws Exception {
        LocalSavedTagsStorage previous = storage();
        main(controller::cleanup);
        close(previous);
        LocalSavedTagsFilterTest.Source source = new LocalSavedTagsFilterTest.Source();
        source.userId = LocalSavedTagsTestActivity.USER_A;
        main(() -> {
            controller = new LocalSavedTagsController(0, new LocalSavedTagsStorage(0), source);
            try {
                Field field = MessagesController.class.getDeclaredField("localSavedTagsController");
                field.setAccessible(true);
                field.set(MessagesController.getInstance(0), controller);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
        return source;
    }

    private LocalSavedTagsController.FilterSession filter() {
        return (LocalSavedTagsController.FilterSession) chatField("localTagFilter");
    }

    private void waitFilter(int count) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        boolean[] done = new boolean[1];
        do {
            main(() -> done[0] = filter() != null && !filter().isLoading() && filter().getMessages().size() == count);
            if (done[0]) {
                return;
            }
            Thread.sleep(40);
        } while (SystemClock.uptimeMillis() < end);
        screenshot("p5-unexpected-filter");
        AtomicReference<String> state = new AtomicReference<>();
        main(() -> state.set(filter() == null ? "没有筛选会话" : "数量=" + filter().getMessages().size()
                + "，加载中=" + filter().isLoading() + "，错误=" + filter().getError()));
        fail("期望 " + count + " 条筛选结果，实际 " + state.get());
    }

    private void waitRequests(LocalSavedTagsFilterTest.Source source, int count) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        boolean[] done = new boolean[1];
        do {
            main(() -> done[0] = source.requests.size() >= count);
            if (done[0]) {
                return;
            }
            Thread.sleep(40);
        } while (SystemClock.uptimeMillis() < end);
        fail("没有发出预期的消息补载请求 " + count);
    }

    private void openMessageEditor(int messageId) throws Exception {
        waitCell(messageId);
        main(() -> cell(messageId).performAccessibilityAction(R.id.acc_action_msg_options, null));
        click(text(R.string.LocalSavedTagsSet));
        waitText(text(R.string.LocalSavedTagsChooseInfo));
    }

    private void selectMessages(MessageObject... messages) throws Exception {
        longPressMessage(messages[0].getId());
        waitText(text(R.string.LocalSavedTagsSet));
        main(() -> {
            try {
                Method select = ChatActivity.class.getDeclaredMethod("addToSelectedMessages", MessageObject.class, boolean.class);
                select.setAccessible(true);
                SparseArray<MessageObject>[] selected = (SparseArray<MessageObject>[]) chatField("selectedMessagesIds");
                // 菜单可能选中相册整组；逐条切换回本用例的实际选择。
                ArrayList<MessageObject> initial = new ArrayList<>();
                for (int i = 0; i < selected[0].size(); i++) {
                    initial.add(selected[0].valueAt(i));
                }
                for (MessageObject item : initial) {
                    if (!Arrays.asList(messages).contains(item)) {
                        select.invoke(activity.chat, item, false);
                    }
                }
                for (MessageObject item : messages) {
                    if (selected[0].get(item.getId()) == null) {
                        select.invoke(activity.chat, item, false);
                    }
                }
                Method title = ChatActivity.class.getDeclaredMethod("updateActionModeTitle");
                title.setAccessible(true);
                title.invoke(activity.chat);
                activity.chat.getChatListView().getAdapter().notifyDataSetChanged();
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        });
    }

    private void assertSelection(int count) {
        main(() -> {
            SparseArray<MessageObject>[] selected = (SparseArray<MessageObject>[]) chatField("selectedMessagesIds");
            assertEquals(count, selected[0].size() + selected[1].size());
        });
    }

    private void tapText(int id, String text) throws Exception {
        waitCell(id);
        Thread.sleep(350);
        float[] coordinates = new float[2];
        main(() -> {
            ChatMessageCell cell = cell(id);
            assertFalse(activity.chat.getActionBar().isActionModeShowed());
            assertFalse(AndroidUtilities.isAccessibilityScreenReaderEnabled());
            StaticLayout layout = cell.getMessageObject().textLayoutBlocks.get(0).textLayout;
            int offset = layout.getText().toString().indexOf(text) + 1;
            assertTrue(offset > 0);
            int line = layout.getLineForOffset(offset);
            int[] location = new int[2];
            cell.getLocationOnScreen(location);
            coordinates[0] = location[0] + cell.getTextX() + layout.getPrimaryHorizontal(offset) + 2;
            coordinates[1] = location[1] + cell.getTextY() + (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f;
        });
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, coordinates[0], coordinates[1], 0);
        MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, coordinates[0], coordinates[1], 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(ui.injectInputEvent(down, true));
            Thread.sleep(40);
            main(() -> {
                try {
                    ChatMessageCell cell = cell(id);
                    Field field = ChatMessageCell.class.getDeclaredField("pressedLink");
                    field.setAccessible(true);
                    Field animation = ChatMessageCell.class.getDeclaredField("animationRunning");
                    animation.setAccessible(true);
                    int[] position = new int[2];
                    cell.getLocationOnScreen(position);
                    assertNotNull("话题未命中，坐标=" + Arrays.toString(coordinates) + "，单元格=" + Arrays.toString(position)
                            + "，正文=" + cell.getTextX() + "," + cell.getTextY() + "，动画=" + animation.get(cell)
                            + "，按下单元格=" + activity.chat.getChatListView().getPressedChildView(), field.get(cell));
                } catch (ReflectiveOperationException error) {
                    throw new AssertionError(error);
                }
            });
            assertTrue(ui.injectInputEvent(up, true));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private MessageObject sample(int id, String text, TLRPC.MessageMedia media) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.date = 1700000000 + id;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = LocalSavedTagsTestActivity.USER_A;
        message.from_id = message.peer_id;
        message.flags = TLRPC.MESSAGE_FLAG_HAS_FROM_ID;
        message.out = true;
        message.message = text;
        message.media = media;
        if (text.contains("#学习")) {
            TLRPC.TL_messageEntityHashtag hashtag = new TLRPC.TL_messageEntityHashtag();
            hashtag.offset = text.indexOf("#学习");
            hashtag.length = 3;
            message.entities.add(hashtag);
        }
        if (text.contains("资料链接")) {
            TLRPC.TL_messageEntityTextUrl link = new TLRPC.TL_messageEntityTextUrl();
            link.offset = text.indexOf("资料链接");
            link.length = 4;
            link.url = "https://example.com";
            message.entities.add(link);
        }
        if (!message.entities.isEmpty()) {
            message.flags |= TLRPC.MESSAGE_FLAG_HAS_ENTITIES;
        }
        ImageLoader.saveMessageThumbs(message);
        if (media != null && media.document != null && MessageObject.isStickerDocument(media.document)) {
            message.attachPath = FileLoader.getInstance(0).getPathToAttach(media.document, true).getAbsolutePath();
        }
        MessageObject object = new MessageObject(0, message, true, false);
        object.attachPathExists = !message.attachPath.isEmpty();
        object.stableId = id;
        return object;
    }

    private Object chatField(String name) {
        try {
            Field field = ChatActivity.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(activity.chat);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void showSamples(ArrayList<MessageObject> samples) {
        main(() -> {
            activity.chat.messages.clear();
            samples.sort((a, b) -> Integer.compare(b.getId(), a.getId()));
            activity.chat.messages.addAll(samples);
            SparseArray<MessageObject>[] dict = (SparseArray<MessageObject>[]) chatField("messagesDict");
            dict[0].clear();
            dict[1].clear();
            LongSparseArray<MessageObject.GroupedMessages> groups = (LongSparseArray<MessageObject.GroupedMessages>) chatField("groupedMessagesMap");
            groups.clear();
            for (MessageObject message : samples) {
                dict[0].put(message.getId(), message);
                if (message.getGroupId() != 0) {
                    MessageObject.GroupedMessages group = groups.get(message.getGroupId());
                    if (group == null) {
                        group = new MessageObject.GroupedMessages();
                        group.groupId = message.getGroupId();
                        groups.put(group.groupId, group);
                    }
                    group.messages.add(message);
                }
            }
            for (int i = 0; i < groups.size(); i++) {
                groups.valueAt(i).messages.sort((a, b) -> Integer.compare(a.getId(), b.getId()));
                groups.valueAt(i).calculate();
            }
            for (String name : new String[]{"endReached", "cacheEndReached", "forwardEndReached"}) {
                Arrays.fill((boolean[]) chatField(name), true);
            }
            try {
                for (String name : new String[]{"loading", "firstLoading", "loadingForward"}) {
                    Field field = ChatActivity.class.getDeclaredField(name);
                    field.setAccessible(true);
                    field.setBoolean(activity.chat, false);
                }
            } catch (Exception error) {
                throw new AssertionError(error);
            }
            activity.chat.getChatListView().setVisibility(View.VISIBLE);
            View progress = (View) chatField("progressView");
            progress.animate().cancel();
            progress.setVisibility(View.INVISIBLE);
            AndroidUtilities.hideKeyboard(activity.getCurrentFocus());
            activity.chat.getChatListView().getAdapter().notifyDataSetChanged();
        });
    }

    private void waitEditorClosed() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] visible = new boolean[1];
        do {
            main(() -> visible[0] = activity.chat.getVisibleDialog() != null
                    && activity.chat.getVisibleDialog().isShowing());
            if (!visible[0]) {
                return;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("标签提交后面板没有关闭");
    }

    private ChatMessageCell cell(int id) {
        for (int i = 0; i < activity.chat.getChatListView().getChildCount(); i++) {
            View view = activity.chat.getChatListView().getChildAt(i);
            if (view instanceof ChatMessageCell && ((ChatMessageCell) view).getMessageObject() != null
                    && ((ChatMessageCell) view).getMessageObject().getId() == id) {
                return (ChatMessageCell) view;
            }
        }
        return null;
    }

    private ChatMessageCell waitCell(int id) throws Exception {
        AtomicReference<ChatMessageCell> result = new AtomicReference<>();
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            main(() -> result.set(cell(id)));
            if (result.get() != null && result.get().getHeight() > 0) {
                return result.get();
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        screenshot("p3-missing-message");
        throw new AssertionError("未显示收藏样本 " + id);
    }

    private void longPressMessage(int id) throws Exception {
        ChatMessageCell cell = waitCell(id);
        Thread.sleep(400);
        int[] location = new int[2];
        main(() -> cell.getLocationOnScreen(location));
        float x = location[0] + cell.getWidth() - AndroidUtilities.dp(55);
        float y = location[1] + Math.min(AndroidUtilities.dp(28), cell.getHeight() / 2f);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 700, MotionEvent.ACTION_UP, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(ui.injectInputEvent(down, true));
            Thread.sleep(700);
            assertTrue(ui.injectInputEvent(up, true));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void start(long userId) throws Exception {
        activity = startActivity(instrumentation, userId);
        main(() -> controller = MessagesController.getInstance(0).getLocalSavedTagsController());
    }

    static LocalSavedTagsTestActivity startActivity(Instrumentation instrumentation, long userId) throws Exception {
        Intent intent = new Intent(instrumentation.getTargetContext(), LocalSavedTagsTestActivity.class);
        intent.putExtra("user_id", userId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        // 收藏空态有持续动画，按实际窗口就绪等待，避免把消息队列空闲误当成启动条件。
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(LocalSavedTagsTestActivity.class.getName(), null, false);
        LocalSavedTagsTestActivity activity;
        try {
            instrumentation.getTargetContext().startActivity(intent);
            activity = (LocalSavedTagsTestActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        assertNotNull("测试页面未启动", activity);
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] focused = new boolean[1];
        do {
            instrumentation.runOnMainSync(() -> focused[0] = activity.hasWindowFocus());
            if (focused[0]) {
                return activity;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("测试页面没有获得窗口焦点");
    }

    private void finish() throws Exception {
        if (controller != null) {
            LocalSavedTagsStorage storage = storage();
            main(() -> {
                if (activity != null) {
                    activity.finish();
                }
                controller.cleanup();
            });
            close(storage);
        }
        activity = null;
        controller = null;
    }

    private LocalSavedTagsStorage storage() throws Exception {
        Field field = LocalSavedTagsController.class.getDeclaredField("storage");
        field.setAccessible(true);
        return (LocalSavedTagsStorage) field.get(controller);
    }

    private void close(LocalSavedTagsStorage storage) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        storage.close(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    private void clearTags() throws Exception {
        for (LocalSavedTag tag : this.<ArrayList<LocalSavedTag>>await(controller::loadTags)) {
            this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
        }
    }

    private void openManager() throws Exception {
        click(text(R.string.AccDescrMoreOptions));
        click(text(R.string.LocalSavedTagsTitle));
        waitText(text(R.string.LocalSavedTagsLocalOnly));
    }

    private void create(String name) throws Exception {
        click(text(R.string.LocalSavedTagsCreate));
        setInput(name);
        click(text(R.string.Save));
        waitText(text(R.string.LocalSavedTagsLocalOnly));
        waitText(name);
        assertEquals(name, tag(name).name);
    }

    private LocalSavedTag tag(String name) throws Exception {
        for (LocalSavedTag tag : this.<ArrayList<LocalSavedTag>>await(controller::loadTags)) {
            if (tag.name.equals(name)) {
                return tag;
            }
        }
        throw new AssertionError("未找到标签：" + name);
    }

    private String text(int id) {
        return LocaleController.getString(id);
    }

    private AccessibilityNodeInfo find(String text) {
        return find(ui.getRootInActiveWindow(), text);
    }

    private AccessibilityNodeInfo find(AccessibilityNodeInfo node, String text) {
        if (node == null) {
            return null;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        boolean matches = text.equalsIgnoreCase(String.valueOf(node.getContentDescription()));
        for (String line : String.valueOf(node.getText()).split("\n")) {
            matches |= text.equalsIgnoreCase(line);
        }
        if (matches && node.isVisibleToUser() && !bounds.isEmpty()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = find(node.getChild(i), text);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private AccessibilityNodeInfo waitText(String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 15000;
        do {
            AccessibilityNodeInfo node = find(text);
            if (node != null) {
                return node;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        screenshot("p2-unexpected-screen");
        StringBuilder tree = new StringBuilder();
        main(() -> tree.append("控制器缓存数量=").append(controller.getTags().size())
                .append("，有效=").append(controller.isActive()).append('\n'));
        appendTree(ui.getRootInActiveWindow(), tree, 0);
        throw new AssertionError("界面未出现：" + text + "，当前节点：\n" + tree);
    }

    private void click(String text) throws Exception {
        Rect bounds = new Rect();
        Rect previous = new Rect();
        int stable = 0;
        long deadline = SystemClock.uptimeMillis() + 5000;
        do {
            waitText(text).getBoundsInScreen(bounds);
            stable = bounds.equals(previous) ? stable + 1 : 0;
            previous.set(bounds);
            if (stable == 8) {
                break;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        assertEquals("点击目标仍在动画中：" + text, 8, stable);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
        MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(ui.injectInputEvent(down, true));
            Thread.sleep(40);
            assertTrue(ui.injectInputEvent(up, true));
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private void appendTree(AccessibilityNodeInfo node, StringBuilder tree, int depth) {
        if (node == null) {
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        tree.append(depth).append(' ').append(node.getClassName()).append(' ').append(bounds)
                .append(" 文本=").append(node.getText()).append(" 描述=").append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            appendTree(node.getChild(i), tree, depth + 1);
        }
    }

    private AccessibilityNodeInfo editable(AccessibilityNodeInfo node) {
        if (node == null || node.isEditable()) {
            return node;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = editable(node.getChild(i));
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private void setInput(String text) throws Exception {
        waitText(text(R.string.Save));
        AccessibilityNodeInfo input = null;
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (input == null && SystemClock.uptimeMillis() < deadline) {
            input = editable(ui.getRootInActiveWindow());
            if (input == null) {
                Thread.sleep(60);
            }
        }
        assertNotNull("界面没有标签名称输入框", input);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
        assertTrue(input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    }

    private void screenshot(String name) throws Exception {
        // 页面切换和列表布局完成后再记录像素，避免只截到上一帧。
        Thread.sleep(500);
        File directory = new File(instrumentation.getTargetContext().getFilesDir(), "local-saved-tags-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = ui.takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private void main(Runnable runnable) {
        instrumentation.runOnMainSync(runnable);
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        main(() -> action.accept((result, failure) -> {
            value.set(result);
            error.set(failure);
            done.countDown();
        }));
        assertTrue(done.await(15, TimeUnit.SECONDS));
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return value.get();
    }
}
