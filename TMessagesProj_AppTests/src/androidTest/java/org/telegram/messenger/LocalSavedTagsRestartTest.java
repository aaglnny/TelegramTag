package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Process;
import android.os.SystemClock;
import android.util.SparseIntArray;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.ui.Components.LocalSavedTagSheet;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsRestartTest {

    private static final String NAME = "进程重启保留";
    private Instrumentation instrumentation;
    private LocalSavedTagsTestActivity activity;
    private LocalSavedTagsController controller;
    private LocalSavedTagsStorage storage;
    private SharedPreferences preferences;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        activity = LocalSavedTagsUiTest.startActivity(instrumentation, LocalSavedTagsTestActivity.USER_A);
        instrumentation.runOnMainSync(() -> controller = MessagesController.getInstance(0).getLocalSavedTagsController());
        Field field = LocalSavedTagsController.class.getDeclaredField("storage");
        field.setAccessible(true);
        storage = (LocalSavedTagsStorage) field.get(controller);
        preferences = instrumentation.getTargetContext().getSharedPreferences("local_saved_tags_test_restart", Context.MODE_PRIVATE);
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            instrumentation.runOnMainSync(() -> {
                activity.finish();
                controller.cleanup();
                UserConfig.getInstance(0).clearConfig();
            });
            CountDownLatch done = new CountDownLatch(1);
            storage.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
    }

    @Test
    public void t2_05a_writeBeforeProcessRestart() throws Exception {
        for (LocalSavedTag tag : this.<ArrayList<LocalSavedTag>>await(controller::loadTags)) {
            if (NAME.equals(tag.name)) {
                this.<Void>await(callback -> controller.deleteTag(tag.id, callback));
            }
        }
        LocalSavedTag tag = await(callback -> controller.createTag(NAME, callback));
        SparseIntArray dates = new SparseIntArray();
        dates.put(801, 1700000003);
        dates.put(802, 1700000002);
        this.<Void>await(callback -> storage.applyTags(dates, Arrays.asList(tag.id), Collections.emptyList(), callback));
        assertTrue(preferences.edit().putInt("writer_pid", Process.myPid()).putLong("tag_id", tag.id).commit());
        showAndCapture("p2-before-process-restart");
        System.out.println("重启前进程=" + Process.myPid() + "，标签=" + tag.id);
    }

    @Test
    public void t2_05b_readAfterProcessRestart() throws Exception {
        int writer = preferences.getInt("writer_pid", 0);
        assertTrue("必须先单独运行写入步骤", writer > 0);
        assertNotEquals("两步骤必须运行在不同的应用进程", writer, Process.myPid());
        long id = preferences.getLong("tag_id", 0);
        LocalSavedTag restored = null;
        for (LocalSavedTag tag : this.<ArrayList<LocalSavedTag>>await(controller::loadTags)) {
            if (tag.id == id) {
                restored = tag;
            }
        }
        assertNotNull(restored);
        assertEquals(NAME, restored.name);
        assertEquals(2, restored.messageCount);
        LocalSavedTagsStorage.MessagePage page = await(callback -> storage.loadMessages(id, null, 50, callback));
        assertEquals(2, page.messages.size());
        assertEquals(801, page.messages.get(0).messageId);
        assertEquals(802, page.messages.get(1).messageId);
        showAndCapture("p2-after-process-restart");
        System.out.println("重启后进程=" + Process.myPid() + "，之前进程=" + writer + "，标签=" + id);
        this.<Void>await(callback -> controller.deleteTag(id, callback));
        assertTrue(preferences.edit().remove("writer_pid").remove("tag_id").commit());
    }

    private void showAndCapture(String name) throws Exception {
        instrumentation.runOnMainSync(() -> activity.chat.showDialog(new LocalSavedTagSheet(activity.chat)));
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean shown = false;
        do {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && !root.findAccessibilityNodeInfosByText(NAME).isEmpty()) {
                shown = true;
                break;
            }
            Thread.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        assertTrue("重开页面没有显示持久化标签", shown);
        File directory = new File(instrumentation.getTargetContext().getFilesDir(), "local-saved-tags-tests");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            bitmap.recycle();
        }
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        instrumentation.runOnMainSync(() -> action.accept((result, failure) -> {
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
