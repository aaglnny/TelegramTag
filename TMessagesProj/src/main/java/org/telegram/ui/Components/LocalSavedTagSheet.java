package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.util.LongSparseArray;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocalSavedTag;
import org.telegram.messenger.LocalSavedTagsController;
import org.telegram.messenger.LocalSavedTagsStorage;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Cells.TextCheckBoxCell;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.concurrent.CancellationException;

public class LocalSavedTagSheet extends BottomSheetWithRecyclerListView implements NotificationCenter.NotificationCenterDelegate {

    public enum Mode { EDIT, ADD, REMOVE }

    private final LocalSavedTagsController controller;
    private RecyclerListView.SelectionAdapter adapter;
    private ArrayList<LocalSavedTag> tags = new ArrayList<>();
    private boolean loading;
    private boolean released;
    private Exception loadError;
    private AlertDialog editor;
    private final ArrayList<MessageObject> messages;
    private final LongSparseArray<Integer> counts = new LongSparseArray<>();
    private final HashSet<Long> addTagIds = new HashSet<>();
    private final HashSet<Long> removeTagIds = new HashSet<>();
    private Exception saveError;
    private boolean saving;
    private final Runnable onApplied;
    private final Mode mode;

    public LocalSavedTagSheet(BaseFragment fragment) {
        this(fragment, null);
    }

    public LocalSavedTagSheet(BaseFragment fragment, ArrayList<MessageObject> messages) {
        this(fragment, messages, null);
    }

    public LocalSavedTagSheet(BaseFragment fragment, ArrayList<MessageObject> messages, Runnable onApplied) {
        this(fragment, messages, Mode.EDIT, onApplied);
    }

    public LocalSavedTagSheet(BaseFragment fragment, ArrayList<MessageObject> messages, Mode mode, Runnable onApplied) {
        super(fragment, false, false);
        this.messages = messages == null ? null : new ArrayList<>(messages);
        this.onApplied = onApplied;
        this.mode = mode;
        currentAccount = fragment.getCurrentAccount();
        AndroidUtilities.hideKeyboard(fragment.getFragmentView());
        controller = fragment.getMessagesController().getLocalSavedTagsController();
        if (actionBar != null) {
            actionBar.setTitle(getTitle());
        }
        topPadding = 0.3f;
        setShowHandle(true);
        fixNavigationBar();
        recyclerListView.setPadding(backgroundPaddingLeft, 0, backgroundPaddingLeft, dp(8));
        recyclerListView.setOnItemClickListener((view, position) -> {
            position--;
            if (saving) {
                return;
            }
            if (position == createRow()) {
                if (!loading && loadError == null) {
                    editTag(null);
                }
            } else if (this.messages != null && position == firstTagRow() - 1) {
                save();
            } else if (hasStatusRow() && position == firstTagRow()) {
                if (loadError != null) {
                    load();
                } else if (saveError != null) {
                    save();
                }
            } else {
                int index = position - firstTagRow() - (hasStatusRow() ? 1 : 0);
                if (index >= 0 && index < tags.size()) {
                    LocalSavedTag tag = tags.get(index);
                    if (this.messages != null) {
                        if (mode == Mode.ADD) {
                            if (!addTagIds.remove(tag.id)) {
                                addTagIds.add(tag.id);
                            }
                            adapter.notifyDataSetChanged();
                            return;
                        } else if (mode == Mode.REMOVE) {
                            if (!removeTagIds.remove(tag.id)) {
                                removeTagIds.add(tag.id);
                            }
                            adapter.notifyDataSetChanged();
                            return;
                        }
                        boolean checked = isChecked(tag);
                        if (checked) {
                            addTagIds.remove(tag.id);
                            removeTagIds.add(tag.id);
                        } else {
                            removeTagIds.remove(tag.id);
                            addTagIds.add(tag.id);
                        }
                        adapter.notifyDataSetChanged();
                        return;
                    }
                    editor = new AlertDialog.Builder(getContext(), resourcesProvider)
                            .setTitle(tag.name)
                            .setItems(new CharSequence[]{getString(R.string.LocalSavedTagsRename), getString(R.string.LocalSavedTagsDelete)},
                                    (dialog, which) -> {
                                        if (which == 0) {
                                            editTag(tag);
                                        } else {
                                            deleteTag(tag);
                                        }
                                    })
                            .show();
                }
            }
        });
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.localSavedTagsUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.appDidLogout);
        tags = controller.getTags();
        load();
    }

    private void load() {
        loading = true;
        loadError = null;
        adapter.notifyDataSetChanged();
        controller.loadTags((result, error) -> {
            if (released) {
                return;
            }
            loading = false;
            loadError = error;
            if (error == null) {
                tags = result;
                if (messages != null) {
                    loading = true;
                    ArrayList<Integer> ids = new ArrayList<>();
                    for (MessageObject message : messages) {
                        ids.add(message.getId());
                    }
                    controller.loadMessageTags(ids, (mapping, messageError) -> {
                        if (released) {
                            return;
                        }
                        loading = false;
                        loadError = messageError;
                        if (messageError == null) {
                            counts.clear();
                            for (LocalSavedTag tag : tags) {
                                int count = 0;
                                for (int i = 0; i < mapping.size(); i++) {
                                    for (LocalSavedTag item : mapping.valueAt(i)) {
                                        if (item.id == tag.id) {
                                            count++;
                                        }
                                    }
                                }
                                counts.put(tag.id, count);
                            }
                        }
                        adapter.notifyDataSetChanged();
                    });
                }
            }
            adapter.notifyDataSetChanged();
        });
    }

    private boolean hasStatusRow() {
        return loading || loadError != null || saveError != null || tags == null || tags.isEmpty();
    }

    private int firstTagRow() {
        return messages == null || mode == Mode.REMOVE ? 2 : 3;
    }

    private int createRow() {
        return mode == Mode.REMOVE ? -1 : 1;
    }

    private boolean isChecked(LocalSavedTag tag) {
        if (mode == Mode.ADD) {
            return addTagIds.contains(tag.id);
        } else if (mode == Mode.REMOVE) {
            return removeTagIds.contains(tag.id);
        }
        return addTagIds.contains(tag.id) || !removeTagIds.contains(tag.id)
                && counts.get(tag.id, 0) == messages.size();
    }

    private void save() {
        if (loading || loadError != null || saving) {
            return;
        }
        saving = true;
        saveError = null;
        adapter.notifyDataSetChanged();
        controller.applyTags(messages, new ArrayList<>(addTagIds), new ArrayList<>(removeTagIds), (result, error) -> {
            if (released) {
                return;
            }
            saving = false;
            saveError = error;
            if (error == null) {
                if (onApplied != null) {
                    onApplied.run();
                }
                dismiss();
            } else {
                adapter.notifyDataSetChanged();
            }
        });
    }

    private void editTag(LocalSavedTag tag) {
        Context context = getContext();
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        input.setHintText(getString(R.string.LocalSavedTagsNameHint));
        input.setHintColor(Theme.getColor(Theme.key_dialogTextHint, resourcesProvider));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);
        input.setLineColors(Theme.getColor(Theme.key_dialogInputField, resourcesProvider),
                Theme.getColor(Theme.key_dialogInputFieldActivated, resourcesProvider), Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        input.setBackground(null);
        if (tag != null) {
            input.setText(tag.name);
            input.setSelection(input.length());
        }
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 24, 8, 24, 0));
        TextView errorView = new TextView(context);
        errorView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        errorView.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        errorView.setVisibility(View.GONE);
        layout.addView(errorView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 24, 8, 24, 0));
        AlertDialog dialog = new AlertDialog.Builder(context, resourcesProvider)
                .setTitle(getString(tag == null ? R.string.LocalSavedTagsCreate : R.string.LocalSavedTagsRename))
                .setView(layout)
                .setPositiveButton(getString(R.string.Save), null)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        editor = dialog;
        dialog.setOnDismissListener(value -> AndroidUtilities.hideKeyboard(input));
        dialog.show();
        View save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setOnClickListener(view -> {
            save.setEnabled(false);
            errorView.setVisibility(View.GONE);
            Utilities.Callback2<LocalSavedTag, Exception> callback = (result, error) -> {
                if (released || !dialog.isShowing()) {
                    return;
                }
                save.setEnabled(true);
                if (error == null) {
                    if (messages != null && tag == null) {
                        addTagIds.add(result.id);
                        adapter.notifyDataSetChanged();
                    }
                    dialog.dismiss();
                } else {
                    errorView.setText(errorText(error));
                    errorView.setVisibility(View.VISIBLE);
                }
            };
            if (tag == null) {
                controller.createTag(input.getText().toString(), callback);
            } else {
                controller.renameTag(tag.id, input.getText().toString(), callback);
            }
        });
        input.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE && save.isEnabled()) {
                save.performClick();
                return true;
            }
            return false;
        });
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void deleteTag(LocalSavedTag tag) {
        AlertDialog dialog = new AlertDialog.Builder(getContext(), resourcesProvider)
                .setTitle(getString(R.string.LocalSavedTagsDelete))
                .setMessage(LocaleController.formatString(R.string.LocalSavedTagsDeleteInfo, tag.name))
                .setPositiveButton(getString(R.string.Delete), null)
                .setNegativeButton(getString(R.string.Cancel), null)
                .create();
        editor = dialog;
        dialog.show();
        View delete = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        delete.setOnClickListener(view -> {
            delete.setEnabled(false);
            controller.deleteTag(tag.id, (result, error) -> {
                if (released || !dialog.isShowing()) {
                    return;
                }
                delete.setEnabled(true);
                if (error == null) {
                    dialog.dismiss();
                } else {
                    dialog.setMessage(errorText(error));
                }
            });
        });
    }

    public static String errorText(Exception error) {
        if (error instanceof LocalSavedTagsStorage.TagException) {
            switch (((LocalSavedTagsStorage.TagException) error).code) {
                case LocalSavedTagsStorage.TagException.INVALID_NAME:
                    return getString(R.string.LocalSavedTagsInvalidName);
                case LocalSavedTagsStorage.TagException.NAME_EXISTS:
                    return getString(R.string.LocalSavedTagsNameExists);
                case LocalSavedTagsStorage.TagException.TAG_NOT_FOUND:
                    return getString(R.string.LocalSavedTagsMissing);
            }
        }
        return getString(error instanceof CancellationException ? R.string.LocalSavedTagsAccountChanged : R.string.LocalSavedTagsSaveFailed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout || !controller.isActive()) {
            dismiss();
        } else if (id == NotificationCenter.localSavedTagsUpdated && (Long) args[0] == controller.getUserId()) {
            tags = controller.getTags();
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void dismiss() {
        released = true;
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.localSavedTagsUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.appDidLogout);
        if (editor != null) {
            editor.dismiss();
        }
        super.dismiss();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(messages == null ? R.string.LocalSavedTagsTitle : mode == Mode.ADD ? R.string.LocalSavedTagsAdd
                : mode == Mode.REMOVE ? R.string.LocalSavedTagsRemove : R.string.LocalSavedTagsSet);
    }

    @Override
    protected RecyclerListView.SelectionAdapter createAdapter(RecyclerListView listView) {
        // 底部面板把观察者转给内部适配器，刷新也必须从这里发出。
        return adapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                int position = holder.getAdapterPosition() - 1;
                if (saving) {
                    return false;
                }
                if (position == createRow() || messages != null && position == firstTagRow() - 1) {
                    return !loading && loadError == null;
                }
                if (hasStatusRow() && position == firstTagRow()) {
                    return loadError != null || saveError != null;
                }
                return position >= firstTagRow() && !loading && loadError == null;
            }

            @Override
            public int getItemCount() {
                return firstTagRow() + (tags == null ? 0 : tags.size()) + (hasStatusRow() ? 1 : 0);
            }

            @Override
            public int getItemViewType(int position) {
                if (position == 0 || hasStatusRow() && position == firstTagRow()) {
                    return 1;
                }
                return messages != null && position >= firstTagRow() ? 2 : 0;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view = viewType == 2 ? new TextCheckBoxCell(getContext(), true, false)
                        : viewType == 1 ? new TextInfoPrivacyCell(getContext(), resourcesProvider)
                        : new TextSettingsCell(getContext(), resourcesProvider);
                if (view instanceof TextInfoPrivacyCell) {
                    // 说明没有链接，点击交给列表处理失败重试。
                    TextView textView = ((TextInfoPrivacyCell) view).getTextView();
                    textView.setMovementMethod(null);
                    textView.setClickable(false);
                    textView.setLongClickable(false);
                }
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (holder.itemView instanceof TextCheckBoxCell) {
                    LocalSavedTag tag = tags.get(position - firstTagRow() - (hasStatusRow() ? 1 : 0));
                    int count = counts.get(tag.id, 0);
                    String text = tag.name;
                    if (messages.size() > 1) {
                        text = LocaleController.formatString(R.string.LocalSavedTagsCoverage, tag.name, count, messages.size());
                    }
                    ((TextCheckBoxCell) holder.itemView).setTextAndCheck(text, isChecked(tag), true);
                    holder.itemView.setContentDescription(text);
                    holder.itemView.setAlpha(loading || saving || loadError != null ? 0.5f : 1f);
                } else if (holder.itemView instanceof TextInfoPrivacyCell) {
                    int text = position == 0 ? (messages == null ? R.string.LocalSavedTagsLocalOnly : mode == Mode.ADD ? R.string.LocalSavedTagsAddInfo
                            : mode == Mode.REMOVE ? R.string.LocalSavedTagsRemoveInfo : R.string.LocalSavedTagsChooseInfo) : loading ? R.string.Loading
                            : loadError != null ? R.string.LocalSavedTagsLoadFailed : R.string.LocalSavedTagsEmpty;
                    String value = position != 0 && saveError != null ? errorText(saveError) : getString(text);
                    ((TextInfoPrivacyCell) holder.itemView).setText(value);
                    holder.itemView.setContentDescription(value);
                } else {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == createRow()) {
                        cell.setText(getString(R.string.LocalSavedTagsCreate), true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
                        cell.setAlpha(loading || loadError != null ? 0.5f : 1f);
                    } else if (messages != null && position == firstTagRow() - 1) {
                        cell.setText(getString(saving ? R.string.Loading : R.string.LocalSavedTagsSave), true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider));
                        cell.setAlpha(loading || saving || loadError != null ? 0.5f : 1f);
                    } else {
                        LocalSavedTag tag = tags.get(position - firstTagRow() - (hasStatusRow() ? 1 : 0));
                        cell.setTextAndValue(tag.name, LocaleController.formatString(R.string.LocalSavedTagsMessageCount, tag.messageCount), true);
                        cell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
                        cell.setAlpha(1f);
                    }
                }
            }
        };
    }
}
