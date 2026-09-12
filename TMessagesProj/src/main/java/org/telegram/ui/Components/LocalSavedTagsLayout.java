package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.LocalSavedTag;
import org.telegram.messenger.LocalSavedTagsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

public class LocalSavedTagsLayout {

    public static class Tag {
        public final long id;
        public final String name;
        public int count;
        public final int total;

        public Tag(long id, String name, int count, int total) {
            this.id = id;
            this.name = name;
            this.count = count;
            this.total = total;
        }

        public String text() {
            return count < total ? LocaleController.formatString(R.string.LocalSavedTagsCoverage, name, count, total) : name;
        }
    }

    private static class Chip {
        long id;
        String text;
        String description;
        final RectF bounds = new RectF();
        int row;
    }

    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<Chip> chips = new ArrayList<>();
    private final ArrayList<Tag> tags = new ArrayList<>();
    private int height;
    private float width;
    private float x;
    private float y;

    public static ArrayList<Tag> collect(List<MessageObject> messages, LocalSavedTagsController controller) {
        LinkedHashMap<Long, Tag> result = new LinkedHashMap<>();
        for (MessageObject message : messages) {
            for (LocalSavedTag tag : controller.getMessageTags(message.getId())) {
                Tag item = result.get(tag.id);
                if (item == null) {
                    item = new Tag(tag.id, tag.name, 0, messages.size());
                    result.put(tag.id, item);
                }
                item.count++;
            }
        }
        return new ArrayList<>(result.values());
    }

    public void setTags(List<Tag> values) {
        tags.clear();
        if (values != null) {
            tags.addAll(values);
        }
        chips.clear();
        height = 0;
        width = 0;
    }

    public void measure(int availableWidth, boolean alignRight) {
        chips.clear();
        height = 0;
        width = 0;
        if (tags.isEmpty() || availableWidth <= dp(24)) {
            return;
        }
        textPaint.setTextSize(dp(13) * SharedConfig.fontSize / 16f);
        float rowHeight = Math.max(dp(24), (float) Math.ceil(textPaint.descent() - textPaint.ascent()) + dp(8));
        float[] rowWidths = new float[2];
        int row = 0;
        for (Tag tag : tags) {
            String suffix = tag.count < tag.total ? " · " + tag.count + "/" + tag.total : "";
            String text = TextUtils.ellipsize(tag.name, textPaint, Math.max(0, availableWidth - dp(20) - textPaint.measureText(suffix)), TextUtils.TruncateAt.END) + suffix;
            float chipWidth = Math.min(availableWidth, textPaint.measureText(text) + dp(20));
            if (rowWidths[row] > 0 && rowWidths[row] + dp(4) + chipWidth > availableWidth) {
                if (++row == 2) {
                    break;
                }
            }
            Chip chip = new Chip();
            chip.id = tag.id;
            chip.text = text;
            chip.description = LocaleController.formatString(R.string.LocalSavedTagsAccessibility, tag.text());
            chip.row = row;
            float left = rowWidths[row] == 0 ? 0 : rowWidths[row] + dp(4);
            chip.bounds.set(left, row * (rowHeight + dp(4)), left + chipWidth, row * (rowHeight + dp(4)) + rowHeight);
            rowWidths[row] = chip.bounds.right;
            chips.add(chip);
        }
        if (chips.size() < tags.size()) {
            int remaining = tags.size() - chips.size();
            float moreWidth;
            while (true) {
                moreWidth = textPaint.measureText("+" + remaining) + dp(20);
                if (rowWidths[1] + dp(4) + moreWidth <= availableWidth || chips.isEmpty() || chips.get(chips.size() - 1).row == 0) {
                    break;
                }
                chips.remove(chips.size() - 1);
                remaining++;
                rowWidths[1] = chips.isEmpty() || chips.get(chips.size() - 1).row == 0 ? 0 : chips.get(chips.size() - 1).bounds.right;
            }
            Chip more = new Chip();
            more.text = "+" + remaining;
            more.description = LocaleController.formatString(R.string.LocalSavedTagsMore, remaining);
            more.row = 1;
            float left = rowWidths[1] == 0 ? 0 : rowWidths[1] + dp(4);
            more.bounds.set(left, rowHeight + dp(4), left + moreWidth, rowHeight * 2 + dp(4));
            chips.add(more);
            rowWidths[1] = more.bounds.right;
        }
        width = Math.max(rowWidths[0], rowWidths[1]);
        for (Chip chip : chips) {
            if (alignRight) {
                chip.bounds.offset(width - rowWidths[chip.row], 0);
            }
        }
        height = (int) Math.ceil((rowWidths[1] > 0 ? rowHeight * 2 + dp(4) : rowHeight) + dp(8));
    }

    public void draw(Canvas canvas, float left, float top, boolean outgoing, Theme.ResourcesProvider resourcesProvider) {
        x = left;
        y = top;
        int color = Theme.getColor(outgoing ? Theme.key_chat_outReplyNameText : Theme.key_chat_inReplyNameText, resourcesProvider);
        textPaint.setColor(color);
        backgroundPaint.setColor(ColorUtils.blendARGB(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), color, 0.12f));
        canvas.save();
        canvas.translate(x, y);
        for (Chip chip : chips) {
            canvas.drawRoundRect(chip.bounds, dp(12), dp(12), backgroundPaint);
            canvas.drawText(chip.text, chip.bounds.left + dp(10), chip.bounds.centerY() - (textPaint.ascent() + textPaint.descent()) / 2, textPaint);
        }
        canvas.restore();
    }

    public int hit(float touchX, float touchY) {
        for (int i = 0; i < chips.size(); i++) {
            if (chips.get(i).bounds.contains(touchX - x, touchY - y)) {
                return i;
            }
        }
        return -1;
    }

    public int getHeight() {
        return height;
    }

    public float getWidth() {
        return width;
    }

    public int size() {
        return chips.size();
    }

    public long getTagId(int index) {
        return chips.get(index).id;
    }

    public String getDescription(int index) {
        return chips.get(index).description;
    }

    public RectF getBounds(int index) {
        RectF result = new RectF(chips.get(index).bounds);
        result.offset(x, y);
        return result;
    }
}
