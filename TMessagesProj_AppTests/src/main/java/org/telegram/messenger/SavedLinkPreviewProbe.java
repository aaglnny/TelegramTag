package org.telegram.messenger;

import android.net.Uri;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SavedLinkPreviewProbe {

    private static final Pattern POST = Pattern.compile("^/(?:c/([1-9][0-9]*)|([A-Za-z0-9_]+))/([1-9][0-9]*)$");

    interface Transport {
        int send(TLObject request, RequestDelegate callback);
        void cancel(int requestId);
    }

    private final int account;
    private final long userId;
    private final Transport transport;
    private final boolean live;
    private int generation;
    private int requestId;
    private Utilities.Callback2<MessageObject, String> callback;

    public final ArrayList<String> requests = new ArrayList<>();
    public TLRPC.Chat source;

    public SavedLinkPreviewProbe(int account) {
        this(account, null);
    }

    SavedLinkPreviewProbe(int account, Transport transport) {
        this.account = account;
        userId = UserConfig.getInstance(account).getClientUserId();
        live = transport == null;
        this.transport = live ? new Transport() {
            @Override
            public int send(TLObject request, RequestDelegate callback) {
                return ConnectionsManager.getInstance(account).sendRequest(request, callback);
            }

            @Override
            public void cancel(int requestId) {
                ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
            }
        } : transport;
    }

    public void load(String link, Utilities.Callback2<MessageObject, String> callback) {
        cancel();
        this.callback = callback;
        source = null;
        if (UserConfig.getInstance(account).getClientUserId() != userId) {
            finish(null, "测试账号已改变，请重新打开原型");
            return;
        }
        if (live && (!UserConfig.getInstance(account).isClientActivated()
                || userId == LocalSavedTagsTestActivity.USER_A || userId == LocalSavedTagsTestActivity.USER_B)) {
            finish(null, "需要已登录的真实测试账号");
            return;
        }

        Uri uri = Uri.parse(link.trim());
        String host = uri.getHost();
        String query = uri.getEncodedQuery();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !("t.me".equalsIgnoreCase(host) || "telegram.me".equalsIgnoreCase(host))
                || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getFragment() != null
                || query != null && !query.equals("single")) {
            finish(null, "原型不支持此链接");
            return;
        }
        Matcher match = POST.matcher(uri.getEncodedPath() == null ? "" : uri.getEncodedPath());
        if (!match.matches()) {
            finish(null, "原型不支持此链接");
            return;
        }
        long channelId;
        int messageId;
        try {
            channelId = match.group(1) == null ? 0 : Long.parseLong(match.group(1));
            messageId = Integer.parseInt(match.group(3));
        } catch (NumberFormatException e) {
            finish(null, "消息或频道编号超出范围");
            return;
        }

        if (channelId != 0) {
            TLRPC.Chat cached = MessagesController.getInstance(account).getChat(channelId);
            if (cached != null && cached.access_hash != 0) {
                read(cached, messageId);
            } else {
                TLRPC.TL_channels_getChannels request = new TLRPC.TL_channels_getChannels();
                TLRPC.TL_inputChannel input = new TLRPC.TL_inputChannel();
                input.channel_id = channelId;
                request.id.add(input);
                // 与原私有消息链接入口相同，由服务器确认当前账号能否取得来源资料。
                send(request, (response, error) -> {
                    if (error != null) {
                        finish(null, "来源读取失败：" + error.text);
                    } else if (response instanceof TLRPC.messages_Chats) {
                        TLRPC.Chat chat = find(((TLRPC.messages_Chats) response).chats, channelId);
                        read(chat, messageId);
                    } else {
                        finish(null, "来源响应不完整");
                    }
                });
            }
        } else {
            TLRPC.TL_contacts_resolveUsername request = new TLRPC.TL_contacts_resolveUsername();
            request.username = match.group(2);
            send(request, (response, error) -> {
                if (error != null) {
                    finish(null, "来源读取失败：" + error.text);
                } else if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer result = (TLRPC.TL_contacts_resolvedPeer) response;
                    if (result.peer == null || result.peer.channel_id == 0) {
                        finish(null, "链接目标不是频道帖子");
                        return;
                    }
                    read(find(result.chats, result.peer.channel_id), messageId);
                } else {
                    finish(null, "来源响应不完整");
                }
            });
        }
    }

    private void read(TLRPC.Chat chat, int messageId) {
        if (!ChatObject.isChannel(chat) || !chat.broadcast || chat.megagroup || chat.forum
                || chat instanceof TLRPC.TL_channelForbidden || chat.access_hash == 0) {
            finish(null, "来源不可用或不是受支持的频道");
            return;
        }
        source = chat;
        MessagesController.getInstance(account).putChat(chat, false);
        TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
        TLRPC.TL_inputChannel channel = new TLRPC.TL_inputChannel();
        channel.channel_id = chat.id;
        channel.access_hash = chat.access_hash;
        request.channel = channel;
        request.id.add(messageId);
        send(request, (response, error) -> {
            if (error != null) {
                finish(null, "消息读取失败：" + error.text);
                return;
            }
            if (!(response instanceof TLRPC.messages_Messages)) {
                finish(null, "消息响应不完整");
                return;
            }
            TLRPC.messages_Messages result = (TLRPC.messages_Messages) response;
            TLRPC.Chat updated = find(result.chats, chat.id);
            if (updated instanceof TLRPC.TL_channelForbidden) {
                finish(null, "来源已不可访问");
                return;
            }
            if (updated != null && !updated.min) {
                source = updated;
            }
            for (TLRPC.Message message : result.messages) {
                if (message.id != messageId) {
                    continue;
                }
                if (message instanceof TLRPC.TL_messageEmpty) {
                    finish(null, "服务器返回空消息");
                    return;
                }
                if (message.peer_id == null || message.peer_id.channel_id != chat.id
                        || message.dialog_id != 0 && message.dialog_id != -chat.id) {
                    finish(null, "消息来源与链接不一致");
                    return;
                }
                MessagesController.getInstance(account).putUsers(result.users, false);
                MessagesController.getInstance(account).putChats(result.chats, false);
                MessagesController.getInstance(account).putChat(source, false);
                message.dialog_id = -chat.id;
                finish(new MessageObject(account, message, false, false), null);
                return;
            }
            finish(null, "响应未包含目标消息，尚不能判断已删除");
        });
    }

    private TLRPC.Chat find(ArrayList<TLRPC.Chat> chats, long channelId) {
        for (TLRPC.Chat chat : chats) {
            if (chat.id == channelId) {
                return chat;
            }
        }
        return null;
    }

    private void send(TLObject request, RequestDelegate completion) {
        int version = ++generation;
        requests.add(request.getClass().getSimpleName());
        requestId = transport.send(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
            if (generation != version) {
                return;
            }
            if (UserConfig.getInstance(account).getClientUserId() != userId) {
                cancel();
                return;
            }
            requestId = 0;
            completion.run(response, error);
        }));
    }

    private void finish(MessageObject message, String error) {
        Utilities.Callback2<MessageObject, String> completion = callback;
        callback = null;
        requestId = 0;
        generation++;
        completion.run(message, error);
    }

    public void cancel() {
        generation++;
        callback = null;
        if (requestId != 0) {
            transport.cancel(requestId);
            requestId = 0;
        }
    }
}
