package org.telegram.messenger;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;

import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;

import java.util.ArrayList;

public class LocalSavedTagsTestActivity extends Activity {

    public static final long USER_A = 4294967511L;
    public static final long USER_B = 4294967533L;
    public INavigationLayout navigation;
    public ChatActivity chat;

    @Override
    protected void onCreate(Bundle state) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setTheme(R.style.Theme_TMessages);
        super.onCreate(state);
        ApplicationLoader.postInitApplication();
        Theme.createCommonChatResources();
        Theme.createDialogsResources(this);
        AndroidUtilities.fillStatusBarHeight(this, false);
        long id = getIntent().getLongExtra("user_id", USER_A);
        if (id != USER_A && id != USER_B) {
            throw new IllegalArgumentException("仅允许本地标签测试身份");
        }
        TLRPC.User existing = UserConfig.getInstance(0).getCurrentUser();
        if (existing != null && existing.id != USER_A && existing.id != USER_B) {
            throw new IllegalStateException("测试宿主不能覆盖真实账号");
        }
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "本地标签测试";
        user.phone = "";
        UserConfig.getInstance(0).setCurrentUser(user);
        UserConfig.selectedAccount = 0;
        MessagesController.getInstance(0).putUser(user, false);
        AndroidUtilities.checkDisplaySize(this, getResources().getConfiguration());
        navigation = INavigationLayout.newLayout(this, false);
        navigation.setFragmentStack(new ArrayList<>());
        setContentView(navigation.getView());
        Bundle args = new Bundle();
        args.putLong("user_id", id);
        chat = new ChatActivity(args);
        chat.setCurrentAccount(0);
        navigation.addFragmentToStack(chat);
        navigation.showLastFragment();
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
        if (navigation != null) {
            navigation.removeAllFragments();
        }
        super.onDestroy();
    }
}
