package org.telegram.messenger;

import android.app.Instrumentation;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.view.MotionEvent;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkReferenceUiTest {

    private Instrumentation instrumentation;
    private SavedLinkPreviewTestActivity activity;
    private TextView linkView;
    private Intent opened;
    private boolean installedUser;

    @Before
    public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        instrumentation.runOnMainSync(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            assertFalse("界面验证只能运行在合成账号环境", UserConfig.getInstance(account).isClientActivated());
        }
        instrumentation.runOnMainSync(() -> {
            TLRPC.TL_user user = new TLRPC.TL_user();
            user.id = SavedLinkReferenceTest.USER_A;
            user.self = true;
            user.first_name = "链接界面隔离测试";
            user.phone = "";
            UserConfig.getInstance(0).setCurrentUser(user);
            UserConfig.selectedAccount = 0;
            MessagesController.getInstance(0).putUser(user, false);
            installedUser = true;
        });
        activity = SavedLinkPreviewUiTest.start(instrumentation);
        instrumentation.runOnMainSync(() -> {
            assertNull("隔离测试不应存在真实主界面", LaunchActivity.instance);
            ContextWrapper context = new ContextWrapper(activity) {
                @Override
                public void startActivity(Intent intent) {
                    opened = intent;
                }

                @Override
                public void startActivity(Intent intent, Bundle options) {
                    opened = intent;
                }
            };
            ScrollView parent = (ScrollView) activity.body.getParent();
            parent.removeView(activity.body);
            linkView = new TextView(context);
            linkView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            linkView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
            linkView.setTextSize(20);
            linkView.setMovementMethod(LinkMovementMethod.getInstance());
            parent.addView(linkView);
            activity.status.setText("L1：不支持的预览链接保留原点击行为");
        });
    }

    @After
    public void tearDown() {
        instrumentation.runOnMainSync(() -> {
            if (activity != null) {
                activity.finish();
            }
            if (installedUser) {
                UserConfig.getInstance(0).clearConfig();
            }
        });
    }

    @Test(timeout = 60000)
    public void l1_unsupportedLinkRemainsClickableWithOriginalParameters() throws Exception {
        for (String url : new String[]{"https://t.me/preview/301?comment=42", "https://t.me/preview/301?t=10",
                "https://t.me/c/4294967601/99/301"}) {
            TLRPC.TL_message original = SavedLinkReferenceTest.message(101, "查看原链接");
            SavedLinkReferenceTest.hidden(original, 0, original.message.length(), url);
            SavedLinkReference parsed = SavedLinkPreviewController.parse(SavedLinkReferenceTest.USER_A, original);
            assertNotEquals(SavedLinkReference.PARSED, parsed.parseState);
            instrumentation.runOnMainSync(() -> {
                assertFalse(MessagesController.getInstance(0).isWebBrowserOpenInApp(url));
                opened = null;
                SpannableString text = new SpannableString(original.message);
                text.setSpan(new URLSpanNoUnderline(original.entities.get(0).url), 0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                linkView.setText(text);
            });
            SavedLinkPreviewUiTest.waitFor(instrumentation, () -> linkView.getLayout() != null && linkView.getWidth() > 0, 5000);
            instrumentation.runOnMainSync(() -> {
                float x = linkView.getTotalPaddingLeft() + linkView.getLayout().getPrimaryHorizontal(2);
                float y = linkView.getTotalPaddingTop() + (linkView.getLayout().getLineTop(0) + linkView.getLayout().getLineBottom(0)) / 2f;
                long time = SystemClock.uptimeMillis();
                MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
                MotionEvent up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, x, y, 0);
                linkView.dispatchTouchEvent(down);
                linkView.dispatchTouchEvent(up);
                down.recycle();
                up.recycle();
                assertNotNull("原链接点击未产生原生打开请求", opened);
                assertEquals(Intent.ACTION_VIEW, opened.getAction());
                assertEquals(url, opened.getDataString());
                assertEquals(url, original.entities.get(0).url);
                assertEquals("查看原链接", original.message);
            });
        }
        SavedLinkPreviewUiTest.waitFor(instrumentation, () -> linkView.isShown() && linkView.getLayout() != null, 5000);
        SystemClock.sleep(150);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        File directory = activity.getExternalFilesDir("saved-link-evidence");
        assertNotNull(directory);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(directory, "l1-native-link.png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
        screenshot.recycle();
        System.out.println("L1 原链接界面：三类不支持链接实际点击后均保留完整参数，启动请求被测试上下文截获，未向来源发请求");
    }
}
