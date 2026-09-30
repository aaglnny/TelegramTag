package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SavedLinkPreviewController;
import org.telegram.messenger.SavedLinkReference;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.PhotoViewer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;

public class SavedLinkPreviewView extends LinearLayout implements NotificationCenter.NotificationCenterDelegate {
    private final Theme.ResourcesProvider resourcesProvider;
    private final TextView title;
    private final TextView date;
    private final TextView body;
    private final TextView status;
    private final TextView expand;
    private final TextView retry;
    private final TextView original;
    private final BackupImageView image;
    private final TextView mediaInfo;
    private final TextView mediaAction;
    private MessageObject owner;
    private ChatActivity parent;
    private Runnable onChanged;
    private SavedLinkPreviewController.Subscription subscription;
    private SavedLinkPreviewController.Preview preview;
    private FlagSecureReason secure;
    private boolean display = true;
    private int version;
    private String signature;
    private MessageObject mediaMessage;
    private int observerAccount = -1;
    private boolean mediaFailed;
    private boolean thumbnailRequested;

    public SavedLinkPreviewView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setOrientation(VERTICAL);
        setClickable(true);
        setPadding(dp(12), dp(10), dp(12), dp(4));
        setVisibility(GONE);
        title = new TextView(context);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        addView(title, new LayoutParams(-1, -2));
        date = new TextView(context);
        LayoutParams time = new LayoutParams(-1, -2);
        time.topMargin = dp(2);
        addView(date, time);
        body = new TextView(context);
        body.setLineSpacing(dp(2), 1f);
        body.setMovementMethod(LinkMovementMethod.getInstance());
        LayoutParams content = new LayoutParams(-1, -2);
        content.topMargin = dp(8);
        addView(body, content);
        image = new BackupImageView(context);
        image.setRoundRadius(dp(8));
        image.setOnClickListener(view -> openMedia());
        LayoutParams picture = new LayoutParams(-1, dp(160));
        picture.topMargin = dp(8);
        addView(image, picture);
        mediaInfo = new TextView(context);
        mediaInfo.setMaxLines(3);
        mediaInfo.setEllipsize(TextUtils.TruncateAt.END);
        addView(mediaInfo, new LayoutParams(-1, -2));
        mediaAction = new TextView(context);
        mediaAction.setGravity(Gravity.CENTER);
        mediaAction.setMinHeight(dp(44));
        mediaAction.setOnClickListener(view -> openMedia());
        addView(mediaAction, new LayoutParams(-1, -2));
        status = new TextView(context);
        LayoutParams hint = new LayoutParams(-1, -2);
        hint.topMargin = dp(5);
        addView(status, hint);
        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        addView(actions, new LayoutParams(-1, -2));
        expand = action(context, actions);
        retry = action(context, actions);
        original = action(context, actions);
        expand.setOnClickListener(view -> {
            if (owner != null && parent != null) {
                parent.setSavedLinkExpanded(owner.getId(), !parent.isSavedLinkExpanded(owner.getId()));
            }
        });
        retry.setOnClickListener(view -> {
            if (subscription != null) {
                if (preview == null || preview.reference == null) {
                    start();
                } else {
                    subscription.retry();
                }
            }
        });
        original.setText(getString(R.string.SavedLinkOpenOriginal));
        original.setOnClickListener(view -> {
            if (preview != null && preview.reference != null && preview.reference.originalUrl != null) {
                Browser.openUrl(getContext(), preview.reference.originalUrl);
            }
        });
    }

    private TextView action(Context context, LinearLayout row) {
        TextView view = new TextView(context);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(44));
        view.setFocusable(true);
        row.addView(view, new LayoutParams(0, -2, 1));
        return view;
    }

    public void bind(ChatActivity parent, MessageObject message, boolean display, Runnable onChanged) {
        String signature = message == null ? null : SavedLinkPreviewController.contentSignature(message.getDialogId(), message.messageOwner);
        boolean same = this.parent == parent && owner != null && message != null
                && owner.currentAccount == message.currentAccount && owner.getDialogId() == message.getDialogId()
                && owner.getId() == message.getId() && TextUtils.equals(this.signature, signature);
        if (same && subscription != null) {
            owner = message;
            this.display = display;
            this.onChanged = onChanged;
            render(preview);
            return;
        }
        stop();
        this.signature = signature;
        this.parent = parent;
        this.owner = message;
        this.display = display;
        this.onChanged = onChanged;
        body.setText("");
        image.clearImage();
        mediaMessage = null;
        mediaFailed = false;
        preview = null;
        setVisibility(GONE);
        if (message != null && isAttachedToWindow()) {
            start();
        }
    }

    private void start() {
        stop();
        if (parent == null || parent.isFinished || owner == null || parent.getParentActivity() == null
                || parent.getParentActivity().isFinishing()
                || UserConfig.getInstance(owner.currentAccount).getClientUserId() != owner.getDialogId()) {
            render(null);
            mediaMessage = null;
            return;
        }
        observerAccount = owner.currentAccount;
        NotificationCenter notifications = NotificationCenter.getInstance(observerAccount);
        notifications.addObserver(this, NotificationCenter.fileLoaded);
        notifications.addObserver(this, NotificationCenter.fileLoadFailed);
        notifications.addObserver(this, NotificationCenter.messagePlayingDidStart);
        notifications.addObserver(this, NotificationCenter.messagePlayingDidReset);
        notifications.addObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
        notifications.addObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
        notifications.addObserver(this, NotificationCenter.savedLinkReferencesCleared);
        secure = new FlagSecureReason(parent.getParentActivity().getWindow(), () -> preview != null && preview.isProtected());
        secure.attach();
        final int request = ++version;
        subscription = SavedLinkPreviewController.getInstance(owner.currentAccount).subscribe(owner.messageOwner, value -> {
            if (request == version && owner != null) {
                if (getVisibility() == GONE && value.reference != null && value.reference.parseState != SavedLinkReference.PARSED) {
                    // 无预览的消息没有高度变化，不触发列表重排打断原消息手势。
                    render(value);
                } else {
                    update(() -> render(value));
                }
            }
        });
    }

    private void stop() {
        version++;
        thumbnailRequested = false;
        if (observerAccount >= 0) {
            NotificationCenter notifications = NotificationCenter.getInstance(observerAccount);
            notifications.removeObserver(this, NotificationCenter.fileLoaded);
            notifications.removeObserver(this, NotificationCenter.fileLoadFailed);
            notifications.removeObserver(this, NotificationCenter.messagePlayingDidStart);
            notifications.removeObserver(this, NotificationCenter.messagePlayingDidReset);
            notifications.removeObserver(this, NotificationCenter.messagePlayingPlayStateChanged);
            notifications.removeObserver(this, NotificationCenter.messagePlayingProgressDidChanged);
            notifications.removeObserver(this, NotificationCenter.savedLinkReferencesCleared);
            observerAccount = -1;
        }
        if (subscription != null) {
            subscription.cancel();
            subscription = null;
        }
        if (secure != null) {
            secure.detach();
            secure = null;
        }
    }

    private void update(Runnable action) {
        if (parent == null) {
            return;
        }
        parent.updateSavedLinkPreview(() -> {
            action.run();
            if (onChanged != null) {
                onChanged.run();
            }
        });
    }

    private void render(SavedLinkPreviewController.Preview value) {
        preview = value;
        if (secure != null) {
            secure.invalidate();
        }
        boolean visible = value != null && (value.reference != null && value.reference.parseState == SavedLinkReference.PARSED
                || value.reference == null && value.error != null);
        setVisibility(visible ? display ? VISIBLE : INVISIBLE : GONE);
        if (!visible) {
            body.setText("");
            image.clearImage();
            return;
        }
        boolean out = owner.isOutOwner();
        int textColor = Theme.getColor(out ? Theme.key_chat_messageTextOut : Theme.key_chat_messageTextIn, resourcesProvider);
        int linkColor = Theme.getColor(out ? Theme.key_chat_outReplyNameText : Theme.key_chat_inReplyNameText, resourcesProvider);
        setBackground(Theme.createRoundRectDrawable(dp(12), Theme.getColor(out ? Theme.key_chat_outBubble : Theme.key_chat_inBubble, resourcesProvider)));
        title.setTextColor(linkColor);
        body.setTextColor(textColor);
        body.setLinkTextColor(linkColor);
        date.setTextColor(textColor);
        status.setTextColor(date.getCurrentTextColor());
        mediaInfo.setTextColor(textColor);
        mediaAction.setTextColor(linkColor);
        mediaInfo.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13 * SharedConfig.fontSize / 16f);
        mediaAction.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14 * SharedConfig.fontSize / 16f);
        float scale = SharedConfig.fontSize / 16f;
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14 * scale);
        body.setTextSize(TypedValue.COMPLEX_UNIT_DIP, SharedConfig.fontSize);
        date.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12 * scale);
        status.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12 * scale);
        for (TextView button : new TextView[]{expand, retry, original}) {
            button.setTextColor(linkColor);
            button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12 * scale);
            button.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector, resourcesProvider), 2));
        }
        title.setText(value.chat == null ? getString(R.string.SavedLinkPreviewTitle) : value.chat.title);
        date.setVisibility(value.message == null ? GONE : VISIBLE);
        body.setVisibility(value.message == null ? GONE : VISIBLE);
        expand.setVisibility(value.message == null ? GONE : VISIBLE);
        if (value.message != null) {
            boolean expanded = parent.isSavedLinkExpanded(owner.getId());
            date.setText(LocaleController.formatDateAudio(value.message.messageOwner.date, false));
            String text = value.message.messageOwner.message;
            SpannableStringBuilder content = new SpannableStringBuilder(TextUtils.isEmpty(text) ? getString(R.string.SavedLinkNoText) : text);
            if (!TextUtils.isEmpty(text)) {
                MessageObject.addLinks(false, content);
                for (TLRPC.MessageEntity entity : value.message.messageOwner.entities) {
                    if (entity instanceof TLRPC.TL_messageEntityTextUrl && entity.url != null && entity.offset >= 0
                            && entity.length > 0 && entity.offset <= content.length() - entity.length) {
                        content.setSpan(new URLSpanNoUnderline(entity.url), entity.offset, entity.offset + entity.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            body.setTextIsSelectable(expanded && !value.isProtected());
            body.setMovementMethod(LinkMovementMethod.getInstance());
            body.setMaxLines(expanded ? Integer.MAX_VALUE : 4);
            body.setEllipsize(expanded ? null : TextUtils.TruncateAt.END);
            body.setText(content);
            expand.setText(getString(expanded ? R.string.SavedLinkCollapse : R.string.SavedLinkExpand));
        } else {
            body.setText("");
        }
        renderMedia(value.message);
        if (value.loading) {
            status.setText(getString(value.message == null ? R.string.SavedLinkLoading : R.string.SavedLinkUpdating));
        } else if (value.error != null) {
            status.setText(getString(value.reference != null && value.reference.state == SavedLinkReference.UNAVAILABLE
                    ? R.string.SavedLinkUnavailable : R.string.SavedLinkFailed));
        } else {
            status.setText(getString(R.string.SavedLinkCached));
        }
        retry.setText(getString(value.error == null ? R.string.SavedLinkRefresh : R.string.SavedLinkRetry));
        retry.setEnabled(!value.loading);
        retry.setAlpha(value.loading ? 0.5f : 1f);
        setContentDescription(getString(R.string.SavedLinkPreviewTitle));
    }

    private boolean ordinaryMedia(MessageObject message) {
        return !message.isSecretMedia() && !message.needDrawBluredPreview() && !message.isVoiceOnce()
                && !message.isRoundOnce() && !message.isPaid() && !message.hasMediaSpoilers()
                && !message.isHiddenSensitive() && message.messageOwner.ttl == 0
                && (message.messageOwner.media == null || message.messageOwner.media.ttl_seconds == 0);
    }

    private void renderMedia(MessageObject message) {
        boolean hasMedia = message != null && (message.messageOwner.media instanceof TLRPC.TL_messageMediaPhoto
                || message.getDocument() != null || message.isPaid());
        mediaInfo.setVisibility(hasMedia ? VISIBLE : GONE);
        mediaAction.setVisibility(hasMedia ? VISIBLE : GONE);
        boolean ordinary = hasMedia && ordinaryMedia(message);
        image.setVisibility(ordinary && (message.isPhoto() || message.isVideo()) ? VISIBLE : GONE);
        if (message != mediaMessage) {
            mediaMessage = message;
            mediaFailed = false;
            thumbnailRequested = false;
            image.clearImage();
        }
        if (image.getVisibility() == VISIBLE && display && !thumbnailRequested) {
            thumbnailRequested = true;
            image.getImageReceiver().setCurrentAccount(message.currentAccount);
            TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(message.isPhoto()
                    ? message.messageOwner.media.photo.sizes : message.getDocument().thumbs, 160);
            if (thumb != null) {
                image.setImage(message.isPhoto() ? ImageLocation.getForPhoto(thumb, message.messageOwner.media.photo)
                                : ImageLocation.getForDocument(thumb, message.getDocument()),
                        "320_180", (android.graphics.drawable.Drawable) null, message.isPhoto() ? thumb.size : 0, message);
            }
            loadThumbnail();
        }
        if (!hasMedia) {
            return;
        }
        if (TextUtils.isEmpty(message.messageOwner.message)) {
            body.setVisibility(GONE);
            expand.setVisibility(GONE);
        }
        String name;
        if (!ordinary) {
            name = getString(R.string.SavedLinkSpecialMedia);
        } else if (message.isPhoto()) {
            name = getString(R.string.SavedLinkPhoto);
        } else if (message.isVideo()) {
            name = getString(R.string.SavedLinkVideo) + " · " + AndroidUtilities.formatDuration((int) message.getDuration(), false);
        } else if (message.isVoice()) {
            name = getString(R.string.SavedLinkVoice);
        } else {
            name = message.getDocumentName();
        }
        if (ordinary && message.getDocument() != null) {
            name += "\n" + message.getDocument().mime_type + " · " + AndroidUtilities.formatFileSize(message.getDocument().size);
        }
        if (message.messageOwner.grouped_id != 0) {
            name += "\n" + getString(R.string.SavedLinkAlbumMember);
        }
        mediaInfo.setText(name);
        image.setContentDescription(name);
        updateMediaAction();
    }

    private void loadThumbnail() {
        MessageObject message = mediaMessage;
        if (message == null || !display || image.getVisibility() != VISIBLE || !SavedLinkPreviewController.isMediaValid(message)) {
            return;
        }
        int request = version;
        ArrayList<TLRPC.PhotoSize> sizes = new ArrayList<>(message.isPhoto()
                ? message.messageOwner.media.photo.sizes : message.getDocument().thumbs);
        Utilities.globalQueue.postRunnable(() -> {
            FileLoader loader = FileLoader.getInstance(message.currentAccount);
            File cached = null;
            int side = 0;
            // 精简占位图不能挡住其他尺寸的清晰缓存，路径查询也不能阻塞列表线程。
            for (TLRPC.PhotoSize size : sizes) {
                if (size == null || size instanceof TLRPC.TL_photoStrippedSize || size instanceof TLRPC.TL_photoPathSize
                        || size instanceof TLRPC.TL_photoSizeEmpty || size.location == null || Math.max(size.w, size.h) <= side) {
                    continue;
                }
                File file = loader.getPathToAttach(size);
                if (!file.isFile()) {
                    file = loader.getPathToAttach(size, true);
                }
                if (file.isFile()) {
                    cached = file;
                    side = Math.max(size.w, size.h);
                }
            }
            String path = cached == null ? null : cached.getAbsolutePath();
            if (path == null) {
                if (message.isVideo()) {
                    TLRPC.Document document = message.getDocument();
                    File cover = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE), "q_" + document.dc_id + "_" + document.id + ".jpg");
                    if (cover.isFile()) {
                        path = cover.getAbsolutePath();
                    }
                }
                if (path == null) {
                    File file = TextUtils.isEmpty(message.messageOwner.attachPath) ? null : new File(message.messageOwner.attachPath);
                    if (file == null || !file.isFile()) {
                        file = loader.getPathToMessage(message.messageOwner);
                    }
                    if (file.isFile()) {
                        // 原生 vthumb 只取本地视频的静态帧，不启动播放或下载。
                        path = (message.isVideo() ? "vthumb://0:" : "") + file.getAbsolutePath();
                    }
                }
            }
            if (path == null) {
                return;
            }
            ImageLocation location = ImageLocation.getForPath(path);
            AndroidUtilities.runOnUIThread(() -> {
                if (request != version || mediaMessage != message || preview == null || preview.message != message
                        || !display || !isAttachedToWindow() || image.getVisibility() != VISIBLE
                        || !SavedLinkPreviewController.isMediaValid(message)) {
                    return;
                }
                TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(sizes, 160);
                ImageLocation placeholder = message.isPhoto() ? ImageLocation.getForPhoto(thumb, message.messageOwner.media.photo)
                        : ImageLocation.getForDocument(thumb, message.getDocument());
                image.setImage(location, "320_180", placeholder, "320_180", 0, message);
            });
        });
    }

    private void updateMediaAction() {
        if (preview == null || preview.message == null || mediaAction.getVisibility() != VISIBLE) {
            return;
        }
        MessageObject message = preview.message;
        boolean supported = ordinaryMedia(message) && (message.isPhoto() || message.isVideo() || message.isVoice()
                || message.isMusic() || message.canPreviewDocument() || message.getDocument() != null && !preview.isProtected());
        mediaAction.setEnabled(supported);
        if (!supported) {
            mediaAction.setText(getString(R.string.SavedLinkMediaAtSource));
        } else if (mediaFailed) {
            mediaAction.setText(getString(R.string.SavedLinkMediaRetry));
        } else if (message.isVoice() || message.isMusic()) {
            MediaController player = MediaController.getInstance();
            boolean playing = player.isPlayingMessage(message) && !player.isMessagePaused();
            MessageObject current = player.getPlayingMessageObject();
            int position = current != null && current.currentAccount == message.currentAccount && current.getDialogId() == message.getDialogId()
                    && current.getId() == message.getId() ? current.audioProgressSec : 0;
            mediaAction.setText(getString(playing ? R.string.SavedLinkPause : R.string.SavedLinkPlay)
                    + " · " + AndroidUtilities.formatDuration(position, false) + " / " + AndroidUtilities.formatDuration((int) message.getDuration(), false));
        } else if (message.getDocument() != null && FileLoader.getInstance(message.currentAccount).isLoadingFile(FileLoader.getAttachFileName(message.getDocument()))) {
            mediaAction.setText(getString(R.string.SavedLinkMediaLoading));
        } else {
            mediaAction.setText(getString(R.string.SavedLinkViewMedia));
        }
    }

    public boolean openMedia() {
        if (preview == null || preview.message == null || parent == null || !isAttachedToWindow()
                || !SavedLinkPreviewController.isMediaValid(preview.message)
                || owner == null || owner.currentAccount != UserConfig.selectedAccount
                || preview.reference.userId != UserConfig.getInstance(owner.currentAccount).getClientUserId()
                || !mediaAction.isEnabled()) {
            return false;
        }
        MessageObject message = preview.message;
        mediaFailed = false;
        if (message.isPhoto() || message.isVideo() || message.canPreviewDocument()) {
            PhotoViewer viewer = PhotoViewer.getInstance();
            viewer.setParentActivity(parent.getParentActivity());
            return viewer.openPhoto(new ArrayList<>(Collections.singletonList(message)), 0, message.getDialogId(), 0, 0,
                    new PhotoViewer.EmptyPhotoViewerProvider());
        }
        if (message.isVoice() || message.isMusic()) {
            MediaController player = MediaController.getInstance();
            boolean result;
            if (player.isPlayingMessage(message)) {
                result = player.isMessagePaused() ? player.playMessage(message) : player.pauseMessage(message);
            } else if (message.isMusic()) {
                result = player.setPlaylist(new ArrayList<>(Collections.singletonList(message)), message, 0, false, null);
            } else {
                player.setVoiceMessagesPlaylist(new ArrayList<>(Collections.singletonList(message)), false);
                result = player.playMessage(message);
            }
            mediaFailed = !result;
            updateMediaAction();
            return result;
        }
        FileLoader loader = FileLoader.getInstance(message.currentAccount);
        File file = TextUtils.isEmpty(message.messageOwner.attachPath) ? null : new File(message.messageOwner.attachPath);
        if (file == null || !file.exists()) {
            file = loader.getPathToMessage(message.messageOwner);
        }
        if (!file.exists()) {
            loader.loadFile(message.getDocument(), message, FileLoader.PRIORITY_NORMAL, 0);
            updateMediaAction();
            return true;
        }
        try {
            return AndroidUtilities.openForView(message, parent.getParentActivity(), resourcesProvider, false);
        } catch (Exception e) {
            FileLog.e(e);
            mediaFailed = true;
            updateMediaAction();
            return false;
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.savedLinkReferencesCleared && account == observerAccount
                && owner != null && owner.getDialogId() == (Long) args[0]) {
            start();
            return;
        }
        if (account != observerAccount || mediaMessage == null) {
            return;
        }
        if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
            boolean document = mediaMessage.getDocument() != null
                    && FileLoader.getAttachFileName(mediaMessage.getDocument()).equals(args[0]);
            boolean thumbnail = false;
            if (mediaMessage.isPhoto() || mediaMessage.isVideo()) {
                ArrayList<TLRPC.PhotoSize> sizes = mediaMessage.isPhoto()
                        ? mediaMessage.messageOwner.media.photo.sizes : mediaMessage.getDocument().thumbs;
                for (TLRPC.PhotoSize size : sizes) {
                    if (FileLoader.getAttachFileName(size).equals(args[0])) {
                        thumbnail = true;
                        break;
                    }
                }
            }
            if (!document && !thumbnail) {
                return;
            }
            if (document) {
                mediaFailed = id == NotificationCenter.fileLoadFailed;
            }
            if (id == NotificationCenter.fileLoaded) {
                loadThumbnail();
            }
        }
        updateMediaAction();
    }

    public boolean hasPreview() {
        return preview != null && preview.reference != null && preview.reference.parseState == SavedLinkReference.PARSED;
    }

    public SavedLinkPreviewController.Preview getPreview() {
        return preview;
    }

    public void updateExpanded(int savedMessageId) {
        if (owner != null && owner.getId() == savedMessageId && preview != null) {
            update(() -> render(preview));
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        start();
    }

    @Override
    protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
