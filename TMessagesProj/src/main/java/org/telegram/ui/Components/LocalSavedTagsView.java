package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocalSavedTag;
import org.telegram.messenger.LocalSavedTagsController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import java.util.List;

public class LocalSavedTagsView extends LinearLayout {
    private final LinearLayout chips;
    private final TextView status;
    private final Utilities.Callback<Long> onSelected;
    private final Theme.ResourcesProvider resourcesProvider;
    private int backgroundColor;
    private long selectedId;

    public LocalSavedTagsView(Context context, Theme.ResourcesProvider resourcesProvider, Utilities.Callback<Long> onSelected) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.onSelected = onSelected;
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        HorizontalScrollView scroll = new HorizontalScrollView(context);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(true);
        chips = new LinearLayout(context);
        chips.setGravity(Gravity.CENTER_VERTICAL);
        chips.setPadding(AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8), 0);
        scroll.addView(chips, new HorizontalScrollView.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));
        addView(scroll, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44));
        status = new TextView(context);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        status.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        status.setVisibility(GONE);
        addView(status, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        updateColors();
    }

    public void setTags(List<LocalSavedTag> tags, long selectedId) {
        this.selectedId = selectedId;
        boolean rebuild = chips.getChildCount() != tags.size() + 2;
        for (int i = 0; !rebuild && i < tags.size(); i++) {
            rebuild = (Long) chips.getChildAt(i + 2).getTag() != tags.get(i).id;
        }
        if (rebuild) {
            chips.removeAllViews();
            addChip(0, LocaleController.getString(R.string.LocalSavedTagsAll), null);
            addChip(LocalSavedTagsController.UNTAGGED, LocaleController.getString(R.string.LocalSavedTagsUntagged), null);
            for (LocalSavedTag tag : tags) {
                addChip(tag.id, tag.name + " · " + tag.messageCount,
                        LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, tag.name));
            }
        } else {
            // 同一列表的延迟刷新不能移除正在按下的标签。
            for (int i = 0; i < tags.size(); i++) {
                LocalSavedTag tag = tags.get(i);
                TextView chip = (TextView) chips.getChildAt(i + 2);
                String name = tag.name + " · " + tag.messageCount;
                if (!TextUtils.equals(chip.getText(), name)) {
                    chip.setText(name);
                    chip.setContentDescription(LocaleController.formatString(R.string.LocalSavedTagsFilterAccessibility, tag.name));
                }
            }
        }
        updateColors();
    }

    private void addChip(long id, String name, String description) {
        TextView view = new TextView(getContext());
        view.setText(name);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setSingleLine(true);
        view.setPadding(AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12), 0);
        view.setTag(id);
        view.setSelected(id == selectedId);
        view.setContentDescription(description == null ? name : description);
        view.setOnClickListener(v -> onSelected.run(id));
        chips.addView(view, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 32, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));
    }

    public void setStatus(String text, Runnable action) {
        if (!TextUtils.equals(status.getText(), text)) {
            // 加载完成可能把同一位置改为刷新，旧手势不能执行更新后的操作。
            status.cancelPendingInputEvents();
            MotionEvent cancel = MotionEvent.obtain(0, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            status.dispatchTouchEvent(cancel);
            cancel.recycle();
        }
        status.setText(text);
        status.setContentDescription(text);
        status.setVisibility(text == null ? GONE : VISIBLE);
        status.setOnClickListener(action == null ? null : view -> action.run());
        status.setClickable(action != null);
    }

    public void updateColors() {
        backgroundColor = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
        setBackgroundColor(backgroundColor);
        int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
        status.setTextColor(color);
        for (int i = 0; i < chips.getChildCount(); i++) {
            TextView chip = (TextView) chips.getChildAt(i);
            boolean selected = (Long) chip.getTag() == selectedId;
            chip.setSelected(selected);
            chip.setTextColor(selected ? backgroundColor : color);
            chip.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(16),
                    selected ? color : Theme.getColor(Theme.key_graySection, resourcesProvider)));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (backgroundColor != Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider)) {
            updateColors();
        }
        super.onDraw(canvas);
    }
}
