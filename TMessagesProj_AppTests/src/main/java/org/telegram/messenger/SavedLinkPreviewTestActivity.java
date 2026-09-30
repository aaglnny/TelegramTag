package org.telegram.messenger;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

public class SavedLinkPreviewTestActivity extends Activity {

    public INavigationLayout navigation;
    public ChatActivity chat;
    public SavedLinkPreviewProbe probe;
    public MessageObject message;
    public TextView status;
    public TextView body;
    public Button mediaButton;
    private int account;

    @Override
    protected void onCreate(Bundle state) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTheme(R.style.Theme_TMessages);
        super.onCreate(state);
        ApplicationLoader.postInitApplication();
        if (!ApplicationLoader.isAndroidTestEnvironment()) {
            throw new IllegalStateException("预览原型只能运行在测试应用");
        }
        Theme.createCommonChatResources();
        Theme.createDialogsResources(this);
        AndroidUtilities.fillStatusBarHeight(this, false);
        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
        account = getIntent().getIntExtra("account", UserConfig.selectedAccount);
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || account != UserConfig.selectedAccount) {
            throw new IllegalArgumentException("测试账号必须与当前选择一致");
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        setContentView(root);
        if (UserConfig.getInstance(account).isClientActivated()) {
            navigation = INavigationLayout.newLayout(this, false);
            navigation.setFragmentStack(new ArrayList<>());
            root.addView(navigation.getView(), new LinearLayout.LayoutParams(-1, 0, 1));
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(account).getClientUserId());
            args.putInt("message_id", getIntent().getIntExtra("message_id", 0));
            chat = new ChatActivity(args);
            chat.setCurrentAccount(account);
            navigation.addFragmentToStack(chat);
            navigation.showLastFragment();
        }

        if (getIntent().getBooleanExtra("production_card", false)) {
            return;
        }

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        status.setText("收藏链接预览：L0 读取原型");
        panel.addView(status);
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setHint("粘贴指定的频道消息链接");
        panel.addView(input);
        Button read = new Button(this);
        read.setText("读取原消息");
        panel.addView(read);
        ScrollView scroll = new ScrollView(this);
        body = new TextView(this);
        body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        body.setTextSize(16);
        body.setGravity(Gravity.START);
        body.setTextIsSelectable(false);
        scroll.addView(body);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(160)));
        mediaButton = new Button(this);
        mediaButton.setText("查看图片或视频");
        mediaButton.setEnabled(false);
        panel.addView(mediaButton);
        probe = new SavedLinkPreviewProbe(account);
        read.setOnClickListener(view -> load(input.getText().toString()));
        mediaButton.setOnClickListener(view -> openMedia());
        if (chat == null) {
            status.setText("请先在隔离测试应用登录普通账号");
            read.setEnabled(false);
        }
    }

    public void load(String link) {
        message = null;
        body.setText("");
        mediaButton.setEnabled(false);
        status.setText("正在读取指定原消息");
        probe.load(link, (result, error) -> {
            if (error != null) {
                status.setText(error);
            } else {
                show(probe.source, result);
            }
        });
    }

    public void show(TLRPC.Chat source, MessageObject message) {
        this.message = message;
        boolean protectedContent = source.noforwards || message.messageOwner.noforwards;
        if (protectedContent) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        }
        body.setText(source.title + "\n" + message.messageText);
        status.setText(protectedContent ? "原消息已读取，内容保护生效" : "原消息已读取");
        boolean ordinary = !message.isSecretMedia() && !message.needDrawBluredPreview()
                && message.messageOwner.ttl == 0
                && (message.messageOwner.media == null || message.messageOwner.media.ttl_seconds == 0);
        mediaButton.setEnabled(ordinary && (message.isPhoto() || message.isVideo()));
    }

    public boolean openMedia() {
        if (message == null || !mediaButton.isEnabled() || account != UserConfig.selectedAccount) {
            return false;
        }
        PhotoViewer viewer = PhotoViewer.getInstance();
        viewer.setParentActivity(this);
        ArrayList<MessageObject> messages = new ArrayList<>();
        messages.add(message);
        return viewer.openPhoto(messages, 0, message.getDialogId(), 0, 0, new PhotoViewer.EmptyPhotoViewerProvider());
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (navigation != null) {
            navigation.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (navigation != null) {
            navigation.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (probe != null) {
            probe.cancel();
        }
        if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().getParentActivity() == this) {
            PhotoViewer.getInstance().destroyPhotoViewer();
        }
        if (navigation != null) {
            navigation.removeAllFragments();
        }
        super.onDestroy();
    }
}
