package org.telegram.messenger;

import android.app.Instrumentation;
import android.graphics.Bitmap;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ChatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkLoadingUiTest {
    private final SavedLinkLoadingTest fixture = new SavedLinkLoadingTest();
    private Instrumentation instrumentation;
    private SavedLinkPreviewTestActivity activity;
    private SavedLinkPreviewController.Subscription subscription;
    private SavedLinkPreviewController.Preview preview;
    private LocalSavedTagsController tags;
    private LocalSavedTagsController previousTags;
    private LocalSavedTagsStorage tagStorage;
    private Field tagField;

    @Before
    public void setUp() throws Exception {
        fixture.setUp();
        Field instances = SavedLinkPreviewController.class.getDeclaredField("instances");
        instances.setAccessible(true);
        ((SavedLinkPreviewController[]) instances.get(null))[0] = fixture.controller;
        instrumentation = InstrumentationRegistry.getInstrumentation();
        tagField = MessagesController.class.getDeclaredField("localSavedTagsController");
        tagField.setAccessible(true);
        previousTags = (LocalSavedTagsController) tagField.get(MessagesController.getInstance(0));
        LocalSavedTagsFilterTest.Source source = new LocalSavedTagsFilterTest.Source();
        source.userId = SavedLinkLoadingTest.USER;
        for (int id : new int[]{101, 102}) {
            TLRPC.Message message = SavedLinkLoadingTest.saved(id, SavedLinkLoadingTest.C1, 301);
            source.cached.put(id, message);
            source.history.add(message);
        }
        tagStorage = new LocalSavedTagsStorage(fixture.root, SavedLinkLoadingTest.USER, false);
        SavedLinkLoadingTest.main(() -> tags = new LocalSavedTagsController(0, tagStorage, source));
        tagField.set(MessagesController.getInstance(0), tags);
        activity = SavedLinkPreviewUiTest.start(instrumentation);
    }

    @After
    public void tearDown() throws Exception {
        SavedLinkLoadingTest.main(() -> {
            if (subscription != null) {
                subscription.cancel();
            }
            if (activity != null) {
                activity.finish();
            }
            if (tags != null) {
                tags.cleanup();
            }
        });
        instrumentation.waitForIdleSync();
        if (tagField != null) {
            tagField.set(MessagesController.getInstance(0), previousTags);
        }
        if (tagStorage != null) {
            CountDownLatch closed = new CountDownLatch(1);
            tagStorage.close(closed::countDown);
            assertTrue(closed.await(15, TimeUnit.SECONDS));
        }
        fixture.tearDown();
    }

    @Test(timeout = 120000)
    public void l2_twentyTagSwitchesAndReopenedSavedPagesReuseOneSource() throws Exception {
        LocalSavedTag first = SavedLinkLoadingTest.await(callback -> tags.createTag("链接甲", callback));
        LocalSavedTag second = SavedLinkLoadingTest.await(callback -> tags.createTag("链接乙", callback));
        for (int i = 0; i < 2; i++) {
            int id = 101 + i;
            long tag = i == 0 ? first.id : second.id;
            MessageObject message = new MessageObject(0, SavedLinkLoadingTest.saved(id, SavedLinkLoadingTest.C1, 301), false, false);
            SavedLinkLoadingTest.<Void>await(callback -> tags.applyTags(Collections.singletonList(message),
                    Collections.singletonList(tag), Collections.emptyList(), callback));
        }
        Method select = ChatActivity.class.getDeclaredMethod("selectLocalSavedTag", long.class);
        select.setAccessible(true);
        Field filterField = ChatActivity.class.getDeclaredField("localTagFilter");
        filterField.setAccessible(true);
        int parses = 0;
        for (int i = 0; i < 22; i++) {
            if (i > 0 && i % 5 == 0) {
                SavedLinkLoadingTest.main(() -> {
                    subscription.cancel();
                    activity.finish();
                });
                instrumentation.waitForIdleSync();
                activity = SavedLinkPreviewUiTest.start(instrumentation);
            }
            long id = i % 2 == 0 ? first.id : second.id;
            SavedLinkLoadingTest.main(() -> {
                try {
                    select.invoke(activity.chat, id);
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            SavedLinkLoadingTest.waitFor(() -> {
                try {
                    LocalSavedTagsController.FilterSession filter = (LocalSavedTagsController.FilterSession) filterField.get(activity.chat);
                    return filter != null && !filter.isLoading() && filter.getMessages().size() == 1;
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            });
            LocalSavedTagsController.FilterSession filter = (LocalSavedTagsController.FilterSession) filterField.get(activity.chat);
            TLRPC.Message original = filter.getMessages().get(0).messageOwner;
            SavedLinkLoadingTest.main(() -> bind(original));
            SavedLinkLoadingTest.waitFor(() -> preview != null && preview.message != null && !preview.loading);
            assertEquals(i % 2 == 0 ? 101 : 102, preview.reference.savedMessageId);
            assertEquals(SavedLinkLoadingTest.USER, original.dialog_id);
            assertEquals(filter, filterField.get(activity.chat));
            if (i == 1) {
                parses = fixture.controller.getParseCount();
            } else if (i > 1) {
                assertEquals(parses, fixture.controller.getParseCount());
            }
        }
        assertEquals(1, fixture.source.messageRequests());
        assertEquals(1, fixture.source.requests.size());
        assertEquals(1, fixture.source.writes);
        assertEquals(2, tags.getTags().size());
        for (LocalSavedTag tag : tags.getTags()) {
            assertEquals(1, tag.messageCount);
        }
        assertTrue(activity.body.getText().toString().contains("#原话题"));
        System.out.println("L2 界面宿主：实际标签筛选切换 20 次、收藏页重开 4 次，读取结果持续正确，解析与来源请求增量均为 0；正式卡片在 L3 接入");
    }

    @Test(timeout = 60000)
    public void l2_offlineStateStaysUntilRetryButtonIsPressed() throws Exception {
        fixture.source.auto = false;
        TLRPC.Message original = SavedLinkLoadingTest.saved(101, SavedLinkLoadingTest.C1, 301);
        SavedLinkLoadingTest.main(() -> bind(original));
        SavedLinkLoadingTest.waitFor(() -> fixture.source.pending.size() == 1);
        SavedLinkLoadingTest.main(() -> fixture.source.answer(fixture.source.requests.get(0), null, "NETWORK_FAILED"));
        SavedLinkLoadingTest.waitFor(() -> preview != null && !preview.loading && preview.error != null);
        assertEquals("暂时无法加载 · 点击重试", activity.status.getText().toString());
        for (int i = 0; i < 20; i++) {
            SavedLinkLoadingTest.main(() -> bind(original));
            SavedLinkLoadingTest.waitFor(() -> preview != null && !preview.loading && preview.error != null);
        }
        assertEquals(1, fixture.source.requests.size());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        File directory = new File(activity.getFilesDir(), "saved-link-evidence");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "l2-offline.png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        bitmap.recycle();
        Button[] retry = new Button[1];
        SavedLinkLoadingTest.main(() -> {
            retry[0] = new Button(activity);
            retry[0].setText("重试");
            retry[0].setOnClickListener(view -> subscription.retry());
            ((LinearLayout) activity.status.getParent()).addView(retry[0]);
            fixture.source.auto = true;
            retry[0].performClick();
        });
        SavedLinkLoadingTest.waitFor(() -> preview != null && preview.message != null && !preview.loading);
        assertEquals(2, fixture.source.requests.size());
        assertTrue(activity.body.getText().toString().contains("#原话题"));
    }

    private void bind(TLRPC.Message message) {
        if (subscription != null) {
            subscription.cancel();
        }
        preview = null;
        subscription = fixture.controller.subscribe(message, value -> {
            preview = value;
            if (value.message != null) {
                activity.show(value.chat, value.message);
            } else {
                activity.body.setText(message.message);
                activity.status.setText(value.loading ? "正在读取原消息" : "暂时无法加载 · 点击重试");
            }
        });
    }
}
