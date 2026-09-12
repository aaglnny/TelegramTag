package org.telegram.messenger;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.RectF;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LocalSavedTagsLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsLayoutTest {

    @Test
    public void t7_07_themeLineEndingsKeepTagColorsReadable() throws Exception {
        File file = File.createTempFile("local_saved_tags_theme_", ".attheme",
                InstrumentationRegistry.getInstrumentation().getTargetContext().getCacheDir());
        try {
            String values = "windowBackgroundWhite=-14866637\nwindowBackgroundWhiteBlueText=-10177041\nactionBarDefaultTitle=#ffffff\n";
            for (String ending : new String[]{"\n", "\r\n"}) {
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write(values.replace("\n", ending).getBytes(StandardCharsets.UTF_8));
                }
                android.util.SparseIntArray colors = Theme.getThemeFileValues(file, null, null);
                assertEquals("主题背景颜色解析错误", -14866637, colors.get(Theme.key_windowBackgroundWhite));
                assertEquals("标签文字颜色解析错误", -10177041, colors.get(Theme.key_windowBackgroundWhiteBlueText));
                assertEquals("标题十六进制颜色解析错误", 0xffffffff, colors.get(Theme.key_actionBarDefaultTitle));
            }
        } finally {
            assertTrue(file.delete());
        }
    }

    @Test
    public void t3_05_twoRowsOverflowAndHitAreasReset() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LocalSavedTagsLayout layout = new LocalSavedTagsLayout();
            ArrayList<LocalSavedTagsLayout.Tag> tags = new ArrayList<>();
            for (int i = 1; i <= 12; i++) {
                tags.add(new LocalSavedTagsLayout.Tag(i, "较长的标签名称" + i, 1, 1));
            }
            layout.setTags(tags);
            layout.measure(AndroidUtilities.dp(160), true);
            assertTrue(layout.getHeight() > 0);
            assertTrue(layout.size() < tags.size());
            assertEquals(0, layout.getTagId(layout.size() - 1));
            Bitmap bitmap = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888);
            try {
                layout.draw(new Canvas(bitmap), 12, 18, true, null);
                float firstTop = layout.getBounds(0).top;
                float secondTop = layout.getBounds(layout.size() - 1).top;
                assertTrue(secondTop > firstTop);
                for (int i = 0; i < layout.size(); i++) {
                    RectF bounds = layout.getBounds(i);
                    assertTrue(bounds.top == firstTop || bounds.top == secondTop);
                    assertTrue(bounds.left >= 12);
                    assertTrue(bounds.right <= 12 + AndroidUtilities.dp(160));
                    assertEquals(i, layout.hit(bounds.centerX(), bounds.centerY()));
                    assertEquals(-1, layout.hit(bounds.centerX(), 0));
                }
                RectF old = layout.getBounds(0);
                layout.setTags(Collections.emptyList());
                layout.measure(AndroidUtilities.dp(160), true);
                assertEquals(0, layout.getHeight());
                assertEquals(0, layout.size());
                assertEquals(-1, layout.hit(old.centerX(), old.centerY()));
            } finally {
                bitmap.recycle();
            }
        });
    }

    @Test
    public void t3_04_partialCoverageAndLargeFontKeepFullAccessibleNames() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int fontSize = SharedConfig.fontSize;
            try {
                SharedConfig.fontSize = 24;
                LocalSavedTagsLayout layout = new LocalSavedTagsLayout();
                LocalSavedTagsLayout.Tag tag = new LocalSavedTagsLayout.Tag(1, "学习", 2, 4);
                layout.setTags(Collections.singletonList(tag));
                layout.measure(AndroidUtilities.dp(200), false);
                assertTrue(layout.getDescription(0).contains("2/4"));
                assertTrue(layout.getDescription(0).contains("学习"));
                assertTrue(layout.getHeight() >= AndroidUtilities.dp(32));
                assertEquals(1, layout.size());
            } finally {
                SharedConfig.fontSize = fontSize;
            }
        });
    }
}
