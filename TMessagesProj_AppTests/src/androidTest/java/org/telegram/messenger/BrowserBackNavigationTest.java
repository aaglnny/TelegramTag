package org.telegram.messenger;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ArticleViewer;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.web.WebActionBar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BrowserBackNavigationTest {
    private Instrumentation instrumentation;
    private Activity activity;
    private BaseFragment fragment;
    private ArticleViewer viewer;
    private ServerSocket server;
    private ExecutorService requests;
    private String baseUrl;

    @Before
    public void setUp() throws Exception {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
            assertTrue("网页测试不得修改真实账号", user == null || user.id == LocalSavedTagsTestActivity.USER_A);
        }
        instrumentation.runOnMainSync(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = LocalSavedTagsTestActivity.USER_A;
            user.self = true;
            user.first_name = "浏览器隔离测试";
            user.phone = "";
            UserConfig.getInstance(0).setCurrentUser(user);
            UserConfig.getInstance(0).syncContacts = false;
            UserConfig.selectedAccount = 0;
            MessagesController.getInstance(0).putUser(user, false);
        });
        server = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        baseUrl = "http://127.0.0.1:" + server.getLocalPort();
        requests = Executors.newSingleThreadExecutor();
        requests.submit(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    socket.setSoTimeout(3000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                    String request = reader.readLine();
                    if (request == null) continue;
                    String path = request.split(" ")[1];
                    String header;
                    while ((header = reader.readLine()) != null && !header.isEmpty()) {}
                    byte[] body = ("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>"
                        + "<title>本地页面" + path + "</title></head><body><h1>本地页面" + path + "</h1>"
                        + "<a id='next' href='/b'>下一页</a><div style='height:2400px'>浏览器返回测试</div></body></html>").getBytes(StandardCharsets.UTF_8);
                    OutputStream output = socket.getOutputStream();
                    output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "
                        + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                    output.write(body);
                } catch (Exception e) {
                    if (!server.isClosed()) throw new RuntimeException(e);
                }
            }
        });
        String activityName = "org.telegram.ui.LaunchActivity";
        Intent intent = new Intent().setClassName(instrumentation.getTargetContext(), activityName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(activityName, null, false);
        try {
            instrumentation.getTargetContext().startActivity(intent);
            activity = instrumentation.waitForMonitorWithTimeout(monitor, 15000);
        } finally {
            instrumentation.removeMonitor(monitor);
        }
        assertNotNull("Telegram 主界面未启动", activity);
        await(activity::hasWindowFocus);
        instrumentation.runOnMainSync(() -> {
            fragment = ((INavigationLayout) field(activity, "actionBarLayout")).getLastFragment();
            assertNotNull(fragment);
            viewer = fragment.createArticleViewer(false);
            assertTrue(viewer.open(baseUrl + "/a"));
        });
        awaitPage("/a");
        await(() -> field(viewer, "pageSwitchAnimation") == null && viewer.sheet.isShown());
        SystemClock.sleep(500);
    }

    @After
    public void tearDown() throws Exception {
        if (instrumentation != null) {
            instrumentation.runOnMainSync(() -> {
                if (viewer != null && viewer.sheet.isShown()) viewer.sheet.dismissInstant();
                if (activity != null) activity.finish();
                if (UserConfig.getInstance(0).getClientUserId() == LocalSavedTagsTestActivity.USER_A) {
                    UserConfig.getInstance(0).clearConfig();
                }
            });
        }
        if (server != null) server.close();
        if (requests != null) requests.shutdownNow();
    }

    @Test
    public void toolbarReturnsThroughHistoryBeforeClosing() {
        navigate("/b");
        navigate("/c");
        assertState("/c", 2, 1);
        instrumentation.runOnMainSync(() -> {
            assertTrue("当前应为普通网页", page().isWeb());
            assertTrue("WebView 应有历史", page().getWebView().canGoBack());
            assertTrue("页面应允许返回", page().hasBackButton());
            assertTrue("有历史应显示返回箭头", bar().isBackButton());
        });
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        awaitPage("/b");
        assertState("/b", 1, 1);
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        awaitPage("/a");
        assertState("/a", 0, 1);
        instrumentation.runOnMainSync(() -> assertFalse(bar().isBackButton()));
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        await(() -> !viewer.sheet.isShown());
    }

    @Test
    public void systemBackReturnsOneWebPage() {
        navigate("/b");
        navigate("/c");
        instrumentation.runOnMainSync(() -> activity.onBackPressed());
        awaitPage("/b");
        assertState("/b", 1, 1);
    }

    @Test
    public void preImeBackReturnsOneWebPage() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> assertTrue(window().dispatchKeyEventPreIme(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))));
        awaitPage("/a");
        assertState("/a", 0, 1);
    }

    @Test
    public void legacyBackEntryReturnsWebHistory() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> viewer.close(true, false));
        awaitPage("/a");
        assertState("/a", 0, 1);
    }

    @Test
    public void swipeReturnsHistoryWithoutMovingWindow() {
        navigate("/b");
        navigate("/c");
        swipe(.65f, MotionEvent.ACTION_UP, false);
        awaitPage("/b");
        assertState("/b", 1, 1);
    }

    @Test
    public void cancelledSwipeDoesNotNavigate() {
        navigate("/b");
        swipe(.65f, MotionEvent.ACTION_CANCEL, false);
        SystemClock.sleep(400);
        assertState("/b", 1, 1);
    }

    @Test
    public void shortSwipeDoesNotNavigate() {
        navigate("/b");
        swipe(.2f, MotionEvent.ACTION_UP, false);
        SystemClock.sleep(400);
        assertState("/b", 1, 1);
    }

    @Test
    public void pointerUpDoesNotNavigate() {
        navigate("/b");
        swipe(.65f, MotionEvent.ACTION_POINTER_UP, false);
        SystemClock.sleep(400);
        assertState("/b", 1, 1);
    }

    @Test
    public void verticalMovementDoesNotNavigate() {
        navigate("/b");
        swipe(.15f, MotionEvent.ACTION_UP, true);
        SystemClock.sleep(400);
        assertState("/b", 1, 1);
    }

    @Test
    public void swipeWithoutHistoryClosesBrowser() {
        swipe(.65f, MotionEvent.ACTION_UP, false);
        await(() -> !viewer.sheet.isShown());
    }

    @Test
    public void cancelledSwipeWithoutHistoryKeepsBrowser() {
        swipe(.65f, MotionEvent.ACTION_CANCEL, false);
        await(() -> !(boolean) field(viewer, "closeAnimationInProgress"));
        assertState("/a", 0, 1);
    }

    @Test
    public void webHistoryPrecedesTelegramPageStack() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> assertTrue(viewer.open(baseUrl + "/x")));
        awaitPage("/x");
        await(() -> field(viewer, "pageSwitchAnimation") == null);
        navigate("/y");
        swipe(.65f, MotionEvent.ACTION_UP, false);
        awaitPage("/x");
        assertState("/x", 0, 2);
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        awaitPage("/b");
        await(() -> field(viewer, "pageSwitchAnimation") == null);
        assertState("/b", 1, 1);
    }

    @Test
    public void forwardNavigationRestoresBackArrow() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        awaitPage("/a");
        instrumentation.runOnMainSync(() -> page().getWebView().goForward());
        awaitPage("/b");
        assertState("/b", 1, 1);
        instrumentation.runOnMainSync(() -> assertTrue(bar().isBackButton()));
    }

    @Test
    public void searchClosesBeforeWebHistory() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> bar().showSearch(true, false));
        SystemClock.sleep(400);
        instrumentation.runOnMainSync(() -> AndroidUtilities.hideKeyboard(window()));
        await(() -> !(boolean) field(viewer, "keyboardVisible"));
        instrumentation.runOnMainSync(() -> bar().backButton.performClick());
        instrumentation.runOnMainSync(() -> assertFalse(bar().isSearching()));
        assertState("/b", 1, 1);
    }

    @Test
    public void addressEditingClosesBeforeWebHistory() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> bar().showAddress(true, false));
        SystemClock.sleep(400);
        instrumentation.runOnMainSync(() -> AndroidUtilities.hideKeyboard(window()));
        await(() -> !(boolean) field(viewer, "keyboardVisible"));
        instrumentation.runOnMainSync(() -> activity.onBackPressed());
        instrumentation.runOnMainSync(() -> assertFalse(bar().isAddressing()));
        assertState("/b", 1, 1);
    }

    @Test
    public void swipeFallsBackToTelegramPageStack() {
        instrumentation.runOnMainSync(() -> assertTrue(viewer.open(baseUrl + "/x")));
        awaitPage("/x");
        await(() -> field(viewer, "pageSwitchAnimation") == null);
        swipe(.65f, MotionEvent.ACTION_UP, false);
        awaitPage("/a");
        await(() -> !(boolean) field(viewer, "closeAnimationInProgress"));
        assertState("/a", 0, 1);
    }

    @Test
    public void fastSwipeReturnsHistory() {
        navigate("/b");
        instrumentation.runOnMainSync(() -> {
            page().swipeContainer.allowThisScroll(true, true);
            long down = SystemClock.uptimeMillis();
            float y = window().getHeight() / 2f;
            touch(down, down, MotionEvent.ACTION_DOWN, 10, y);
            touch(down, down + 10, MotionEvent.ACTION_MOVE, 100, y);
            touch(down, down + 20, MotionEvent.ACTION_MOVE, 200, y);
            touch(down, down + 30, MotionEvent.ACTION_UP, 300, y);
        });
        awaitPage("/a");
        assertState("/a", 0, 1);
    }

    @Test
    public void dispatchedTouchSequenceReturnsHistory() {
        navigate("/b");
        View[] target = new View[1];
        int[] position = new int[2];
        float[] size = new float[2];
        instrumentation.runOnMainSync(() -> {
            target[0] = page().getWebView();
            target[0].getLocationOnScreen(position);
            size[0] = target[0].getWidth();
            size[1] = target[0].getHeight();
        });
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 15; i++) {
            int action = i == 0 ? MotionEvent.ACTION_DOWN : i == 15 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                position[0] + 10 + size[0] * .7f * i / 15f, position[1] + size[1] * .3f, 0);
            event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
            try {
                instrumentation.sendPointerSync(event);
            } finally {
                event.recycle();
            }
            SystemClock.sleep(40);
        }
        awaitPage("/a");
        assertState("/a", 0, 1);
    }

    private void navigate(String path) {
        CountDownLatch ready = new CountDownLatch(1);
        float[] point = new float[2];
        instrumentation.runOnMainSync(() -> page().getWebView().evaluateJavascript(
            "(()=>{let a=document.getElementById('next');a.href='" + baseUrl + path + "';let r=a.getBoundingClientRect();return [r.x+r.width/2,r.y+r.height/2]})()", result -> {
                try {
                    JSONArray bounds = new JSONArray(result);
                    int[] position = new int[2];
                    page().getWebView().getLocationOnScreen(position);
                    float scale = page().getWebView().getScale();
                    point[0] = position[0] + (float) bounds.getDouble(0) * scale;
                    point[1] = position[1] + (float) bounds.getDouble(1) * scale;
                } catch (Exception e) {
                    throw new AssertionError(e);
                } finally {
                    ready.countDown();
                }
            }));
        try {
            assertTrue("网页链接坐标未返回", ready.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
        long down = SystemClock.uptimeMillis();
        MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, point[0], point[1], 0);
        MotionEvent release = MotionEvent.obtain(down, down + 80, MotionEvent.ACTION_UP, point[0], point[1], 0);
        press.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        release.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            instrumentation.sendPointerSync(press);
            SystemClock.sleep(80);
            instrumentation.sendPointerSync(release);
        } finally {
            press.recycle();
            release.recycle();
        }
        awaitPage(path);
    }

    private void awaitPage(String path) {
        await(() -> page().getWebView() != null && (baseUrl + path).equals(page().getWebView().getUrl())
            && ("本地页面" + path).equals(page().getWebView().getTitle()) && page().getWebView().getProgress() == 100);
        instrumentation.waitForIdleSync();
    }

    private void assertState(String path, int historyIndex, int stackSize) {
        instrumentation.runOnMainSync(() -> {
            assertTrue("浏览器应保持打开", viewer.sheet.isShown());
            assertEquals(baseUrl + path, page().getWebView().getUrl());
            assertEquals(historyIndex, page().getWebView().copyBackForwardList().getCurrentIndex());
            assertEquals(stackSize, ((List<?>) field(viewer, "pagesStack")).size());
            assertEquals(0f, viewer.sheet.getBackProgress(), 0f);
            assertEquals(0f, ((View) field(viewer, "containerView")).getTranslationX(), 0f);
        });
    }

    private void swipe(float fraction, int endAction, boolean vertical) {
        instrumentation.runOnMainSync(() -> {
            View window = window();
            float width = window.getWidth();
            float y = window.getHeight() / 2f;
            boolean history = page().hasBackButton();
            page().swipeContainer.allowThisScroll(true, true);
            long down = SystemClock.uptimeMillis();
            touch(down, down, MotionEvent.ACTION_DOWN, 10, y);
            for (int i = 1; i <= 10; i++) {
                touch(down, down + i * 100, MotionEvent.ACTION_MOVE, 10 + width * fraction * i / 10f, y + (vertical ? i * 60 : 0));
                if (history) {
                    assertEquals("网页回退手势不能拖出窗口", 0f, viewer.sheet.getBackProgress(), 0f);
                    assertEquals(0f, page().getTranslationX(), 0f);
                }
            }
            touch(down, down + 1100, endAction, 10 + width * fraction, y + (vertical ? 600 : 0));
        });
    }

    private void touch(long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        try {
            Method method = window().getClass().getDeclaredMethod("handleTouchEvent", MotionEvent.class);
            method.setAccessible(true);
            method.invoke(window(), event);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        } finally {
            event.recycle();
        }
    }

    private void await(BooleanSupplier condition) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        boolean[] result = new boolean[1];
        do {
            instrumentation.runOnMainSync(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        if (viewer != null) {
            instrumentation.runOnMainSync(() -> System.out.println("浏览器等待超时：url=" + page().getWebView().getUrl()
                + "，title=" + page().getWebView().getTitle() + "，history=" + page().getWebView().copyBackForwardList().getCurrentIndex()
                + "，keyboard=" + field(viewer, "keyboardVisible") + "，search=" + bar().isSearching() + "，address=" + bar().isAddressing()
                + "，tracking=" + field(window(), "startedTracking") + "，closeAnimation=" + field(viewer, "closeAnimationInProgress")
                + "，pageAnimation=" + field(viewer, "pageSwitchAnimation") + "，fullscreen=" + ((View) field(viewer, "fullscreenVideoContainer")).getVisibility()));
        }
        fail("等待网页或浏览器状态超时");
    }

    private ArticleViewer.PageLayout page() {
        return ((ArticleViewer.PageLayout[]) field(viewer, "pages"))[0];
    }

    private View window() {
        return (View) field(viewer, "windowView");
    }

    private WebActionBar bar() {
        return (WebActionBar) field(viewer, "actionBar");
    }

    private static Object field(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
