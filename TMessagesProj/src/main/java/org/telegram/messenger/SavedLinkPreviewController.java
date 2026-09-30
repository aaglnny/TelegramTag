package org.telegram.messenger;

import android.net.Uri;
import android.util.SparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SavedLinkPreviewController extends BaseController implements NotificationCenter.NotificationCenterDelegate {

    public static final long FRESHNESS_MS = 30 * 60 * 1000L;
    private static final int BATCH_SIZE = 100;
    private static final int MAX_REQUESTS = 2;
    private static final SavedLinkPreviewController[] instances = new SavedLinkPreviewController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Pattern POST = Pattern.compile("^/(?:c/([1-9][0-9]*)|([A-Za-z0-9_]+))/([1-9][0-9]*)$");
    private static final Pattern URLS = Pattern.compile(AndroidUtilities.WEB_URL.pattern(), AndroidUtilities.WEB_URL.flags() | Pattern.CASE_INSENSITIVE);
    private static final HashSet<String> RESERVED = new HashSet<>(Arrays.asList("c", "s", "joinchat", "addstickers",
            "addemoji", "proxy", "socks", "share", "iv", "bg", "login", "invoice", "giftcode", "boost", "m", "contact"));

    private final SavedLinkPreviewStorage storage;
    private final long userId;
    private final boolean testBackend;
    private final Backend backend;
    private final SparseArray<ReferenceRequest> references = new SparseArray<>();
    private final HashMap<String, Resolution> resolutions = new HashMap<>();
    private final HashMap<Key, Entry> entries = new HashMap<>();
    private final ArrayList<Subscription> subscriptions = new ArrayList<>();
    private final LinkedHashSet<Resolution> pendingSources = new LinkedHashSet<>();
    private final LinkedHashMap<Long, LinkedHashSet<Entry>> pendingReads = new LinkedHashMap<>();
    private final LinkedHashMap<Long, LinkedHashSet<Entry>> pendingCache = new LinkedHashMap<>();
    private final ArrayList<Job> jobs = new ArrayList<>();
    private final ArrayDeque<ArrayList<Integer>> pendingSavedChecks = new ArrayDeque<>();
    private final HashSet<Integer> deletedSavedIds = new HashSet<>();
    private final Runnable pumpRunnable = this::pump;
    private final Runnable cacheRunnable = this::loadCache;
    private boolean closed;
    private int parseCount;

    static class Backend {
        final int account;

        Backend(int account) {
            this.account = account;
        }

        int send(TLObject request, RequestDelegate callback) {
            return ConnectionsManager.getInstance(account).sendRequest(request, callback);
        }

        void cancel(int requestId) {
            ConnectionsManager.getInstance(account).cancelRequest(requestId, true);
        }

        void chat(long id, Utilities.Callback<TLRPC.Chat> callback) {
            MessagesStorage storage = MessagesStorage.getInstance(account);
            storage.getStorageQueue().postRunnable(() -> {
                TLRPC.Chat chat = storage.getChat(id);
                AndroidUtilities.runOnUIThread(() -> callback.run(chat));
            });
        }

        void load(long dialogId, ArrayList<Integer> ids, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
            MessagesStorage.getInstance(account).getMessagesByIds(dialogId, ids, callback);
        }

        void save(long dialogId, TLRPC.messages_Messages result, Utilities.Callback<Exception> callback) {
            MessagesStorage.getInstance(account).putSavedLinkMessages(dialogId, result, callback);
        }

        long now() {
            return System.currentTimeMillis();
        }
    }

    public static final class Preview {
        public final SavedLinkReference reference;
        public final MessageObject message;
        public final TLRPC.Chat chat;
        public final boolean loading;
        public final boolean cached;
        public final String error;

        Preview(SavedLinkReference reference, MessageObject message, TLRPC.Chat chat, boolean loading, boolean cached, String error) {
            this.reference = reference;
            this.message = message;
            this.chat = chat;
            this.loading = loading;
            this.cached = cached;
            this.error = error;
            if (message != null) {
                message.isSavedLinkPreview = true;
                message.savedLinkProtected = chat == null || chat.noforwards || message.messageOwner.noforwards;
                message.savedLinkUserId = reference.userId;
                message.savedLinkTestBackend = ConnectionsManager.getInstance(message.currentAccount).isTestBackend();
            }
        }

        public boolean isProtected() {
            return message != null && (chat == null || chat.noforwards || message.messageOwner.noforwards);
        }
    }

    private static final class Key {
        final long dialogId;
        final int messageId;

        Key(long dialogId, int messageId) {
            this.dialogId = dialogId;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object value) {
            return value instanceof Key && ((Key) value).dialogId == dialogId && ((Key) value).messageId == messageId;
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(dialogId) + messageId;
        }
    }

    private static class Resolution {
        long channelId;
        String username;
        TLRPC.Chat chat;
        boolean done;
        String error;
        Job job;
        final ArrayList<Subscription> subscribers = new ArrayList<>();
    }

    private static class Entry {
        Key key;
        TLRPC.Chat chat;
        TLRPC.InputChannel inputChannel;
        MessageObject message;
        boolean cacheLoaded;
        boolean cacheLoading;
        boolean loading;
        boolean cached;
        int state;
        long lastSuccessAt;
        String error;
        Job job;
        int generation;
        boolean protectedSource;
        boolean deniedSource;
        final ArrayList<Subscription> subscribers = new ArrayList<>();
        final ArrayList<Utilities.Callback2<MessageObject, String>> mediaCallbacks = new ArrayList<>();
        final ArrayList<WeakReference<MessageObject>> mediaObjects = new ArrayList<>();

        boolean needed() {
            return !subscribers.isEmpty() || !mediaCallbacks.isEmpty();
        }
    }

    private static class Job {
        int id;
        boolean active = true;
        Runnable timeout;
        Resolution resolution;
        ArrayList<Integer> savedIds;
        final ArrayList<Entry> entries = new ArrayList<>();
        final HashMap<Entry, Integer> versions = new HashMap<>();
    }

    public final class Subscription {
        private final int savedMessageId;
        private final String signature;
        private final Utilities.Callback<Preview> callback;
        private SavedLinkReference reference;
        private Resolution resolution;
        private Entry entry;
        private boolean cancelled;

        private Subscription(Input input, Utilities.Callback<Preview> callback) {
            savedMessageId = input.messageId;
            signature = input.signature;
            this.callback = callback;
        }

        public void cancel() {
            if (cancelled) {
                return;
            }
            cancelled = true;
            subscriptions.remove(this);
            if (resolution != null) {
                resolution.subscribers.remove(this);
                if (resolution.subscribers.isEmpty() && !resolution.done) {
                    pendingSources.remove(resolution);
                    cancelJob(resolution.job);
                    resolutions.values().remove(resolution);
                }
            }
            if (entry != null) {
                entry.subscribers.remove(this);
                if (!entry.needed()) {
                    LinkedHashSet<Entry> pending = pendingReads.get(entry.key.dialogId);
                    if (pending != null) {
                        pending.remove(entry);
                    }
                    if (entry.job != null) {
                        boolean needed = false;
                        for (Entry item : entry.job.entries) {
                            needed |= item.needed();
                        }
                        if (!needed) {
                            Job job = entry.job;
                            for (Entry item : job.entries) {
                                item.job = null;
                                item.loading = false;
                            }
                            cancelJob(job);
                        }
                    } else {
                        entry.loading = false;
                    }
                }
            }
        }

        public void retry() {
            if (!valid(this)) {
                return;
            }
            if (entry != null) {
                queueRead(entry, true);
            } else if (resolution != null && resolution.done) {
                resolution.done = false;
                resolution.error = null;
                pendingSources.add(resolution);
                publish(this);
                schedule();
            }
        }
    }

    private static class Link {
        int offset;
        int length;
        boolean hidden;
        String url;
    }

    private static class Input {
        long userId;
        int messageId;
        String text;
        String signature;
        final ArrayList<Link> entities = new ArrayList<>();
    }

    private static class ReferenceRequest {
        Input input;
        SavedLinkReference value;
        boolean loading;
        final ArrayList<Utilities.Callback2<SavedLinkReference, Exception>> callbacks = new ArrayList<>();
    }

    public SavedLinkPreviewController(int account) {
        this(account, new SavedLinkPreviewStorage(account), new Backend(account));
    }

    SavedLinkPreviewController(int account, SavedLinkPreviewStorage storage) {
        this(account, storage, new Backend(account));
    }

    SavedLinkPreviewController(int account, SavedLinkPreviewStorage storage, Backend backend) {
        super(account);
        this.storage = storage;
        this.backend = backend;
        userId = storage.getUserId();
        testBackend = storage.isTestBackend();
        getNotificationCenter().addObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.didClearDatabase);
    }

    public static SavedLinkPreviewController getInstance(int account) {
        SavedLinkPreviewController controller = instances[account];
        if (controller == null || !controller.isActive()) {
            if (controller != null) {
                controller.cleanup();
            }
            instances[account] = controller = new SavedLinkPreviewController(account);
        }
        return controller;
    }

    public static void cleanupAccount(int account) {
        SavedLinkPreviewController controller = instances[account];
        instances[account] = null;
        if (controller != null) {
            AndroidUtilities.runOnUIThread(controller::cleanup);
        }
    }

    public Subscription subscribe(TLRPC.Message message, Utilities.Callback<Preview> callback) {
        Input input = snapshot(userId, message);
        if (!isActive() || deletedSavedIds.contains(input.messageId)) {
            throw new IllegalStateException("收藏链接订阅已失效");
        }
        Subscription subscription = new Subscription(input, callback);
        subscriptions.add(subscription);
        loadReference(message, (reference, error) -> {
            if (!valid(subscription)) {
                return;
            }
            subscription.reference = reference;
            if (error != null) {
                callback.run(new Preview(null, null, null, false, false, "REFERENCE_FAILED"));
            } else if (reference.parseState != SavedLinkReference.PARSED) {
                publish(subscription);
            } else {
                resolve(subscription);
            }
        });
        return subscription;
    }

    private boolean valid(Subscription subscription) {
        ReferenceRequest request = references.get(subscription.savedMessageId);
        return !subscription.cancelled && isActive() && request != null && subscription.signature.equals(request.input.signature);
    }

    private void resolve(Subscription subscription) {
        SavedLinkReference reference = subscription.reference;
        long channelId = reference.sourceDialogId != 0 ? -reference.sourceDialogId : reference.channelId;
        String key = channelId != 0 ? "c:" + channelId : "u:" + reference.username;
        Resolution resolution = resolutions.get(key);
        boolean created = resolution == null;
        if (created) {
            resolution = new Resolution();
            resolution.channelId = channelId;
            resolution.username = reference.username;
            resolutions.put(key, resolution);
        }
        subscription.resolution = resolution;
        resolution.subscribers.add(subscription);
        if (resolution.done) {
            if (resolution.chat != null) {
                attach(subscription, resolution.chat);
            } else {
                publish(subscription);
            }
            return;
        }
        publish(subscription);
        if (!created) {
            return;
        }
        Resolution pending = resolution;
        TLObject peer = channelId != 0 ? getMessagesController().getChat(channelId)
                : getMessagesController().getUserOrChat(reference.username);
        if (peer instanceof TLRPC.Chat && usable((TLRPC.Chat) peer)) {
            resolved(pending, (TLRPC.Chat) peer, null);
        } else if (channelId != 0) {
            backend.chat(channelId, chat -> {
                if (!isActive() || pending.subscribers.isEmpty() || pending.done) {
                    return;
                }
                if (usable(chat)) {
                    resolved(pending, chat, null);
                } else {
                    pendingSources.add(pending);
                    schedule();
                }
            });
        } else {
            pendingSources.add(pending);
            schedule();
        }
    }

    private static boolean usable(TLRPC.Chat chat) {
        return ChatObject.isChannel(chat) && chat.broadcast && !chat.megagroup && !chat.forum
                && !(chat instanceof TLRPC.TL_channelForbidden) && !chat.kicked && !chat.min && chat.access_hash != 0;
    }

    private void resolved(Resolution resolution, TLRPC.Chat chat, String error) {
        resolution.done = true;
        resolution.job = null;
        resolution.error = error;
        resolution.chat = chat;
        if (chat != null) {
            getMessagesController().putChat(chat, false);
        }
        for (Subscription subscription : new ArrayList<>(resolution.subscribers)) {
            if (valid(subscription)) {
                if (chat != null) {
                    attach(subscription, chat);
                } else {
                    publish(subscription);
                }
            }
        }
    }

    private void attach(Subscription subscription, TLRPC.Chat chat) {
        Key key = new Key(-chat.id, subscription.reference.sourceMessageId);
        Entry entry = entries.get(key);
        if (entry == null) {
            entry = new Entry();
            entry.key = key;
            entry.chat = chat;
            entry.inputChannel = MessagesController.getInputChannel(chat);
            entry.protectedSource = chat.noforwards;
            entry.deniedSource = !usable(chat);
            entry.state = subscription.reference.state;
            entry.lastSuccessAt = subscription.reference.lastSuccessAt;
            if (entry.state == SavedLinkReference.UNAVAILABLE) {
                entry.error = "CHANNEL_PRIVATE";
            } else if (entry.state == SavedLinkReference.FAILED) {
                entry.error = "RETRY_REQUIRED";
            }
            entries.put(key, entry);
        }
        subscription.entry = entry;
        subscription.reference = subscription.reference.withSource(key.dialogId, entry.state, entry.lastSuccessAt);
        entry.subscribers.add(subscription);
        if (!entry.cacheLoaded && !entry.cacheLoading) {
            entry.cacheLoading = true;
            entry.loading = true;
            pendingCache.computeIfAbsent(key.dialogId, ignored -> new LinkedHashSet<>()).add(entry);
            AndroidUtilities.cancelRunOnUIThread(cacheRunnable);
            AndroidUtilities.runOnUIThread(cacheRunnable, 16);
        } else if (entry.cacheLoaded) {
            queueRead(entry, false);
        }
        persist(entry);
        publish(subscription);
    }

    private void loadCache() {
        ArrayList<Map.Entry<Long, LinkedHashSet<Entry>>> batches = new ArrayList<>(pendingCache.entrySet());
        pendingCache.clear();
        for (Map.Entry<Long, LinkedHashSet<Entry>> batch : batches) {
            ArrayList<Entry> values = new ArrayList<>(batch.getValue());
            HashMap<Entry, Integer> versions = new HashMap<>();
            ArrayList<Integer> ids = new ArrayList<>();
            for (Entry entry : values) {
                ids.add(entry.key.messageId);
                versions.put(entry, entry.generation);
            }
            backend.load(batch.getKey(), ids, (result, error) -> {
                if (!isActive()) {
                    return;
                }
                for (Entry entry : values) {
                    if (entry.cacheLoaded || entry.generation != versions.get(entry)) {
                        continue;
                    }
                    entry.cacheLoaded = true;
                    entry.cacheLoading = false;
                    entry.loading = false;
                    if (error == null && result != null && entry.state != SavedLinkReference.UNAVAILABLE) {
                        for (TLRPC.Message message : result.messages) {
                            if (matches(message, entry.key)) {
                                message.dialog_id = entry.key.dialogId;
                                setMessage(entry, message);
                                entry.cached = true;
                                break;
                            }
                        }
                    }
                    queueRead(entry, false);
                    publish(entry);
                }
            });
        }
    }

    private static boolean matches(TLRPC.Message message, Key key) {
        return message != null && !(message instanceof TLRPC.TL_messageEmpty) && message.id == key.messageId
                && message.peer_id != null && message.peer_id.channel_id == -key.dialogId
                && message.peer_id.chat_id == 0 && message.peer_id.user_id == 0
                && (message.dialog_id == 0 || message.dialog_id == key.dialogId);
    }

    private void queueRead(Entry entry, boolean force) {
        if (entry.loading || !entry.needed()) {
            return;
        }
        long age = backend.now() - entry.lastSuccessAt;
        if (!force && (entry.error != null || entry.message != null && entry.lastSuccessAt > 0 && age >= 0 && age < FRESHNESS_MS)) {
            return;
        }
        entry.loading = true;
        entry.generation++;
        entry.error = null;
        pendingReads.computeIfAbsent(entry.key.dialogId, ignored -> new LinkedHashSet<>()).add(entry);
        publish(entry);
        schedule();
    }

    private void schedule() {
        AndroidUtilities.cancelRunOnUIThread(pumpRunnable);
        AndroidUtilities.runOnUIThread(pumpRunnable, 16);
    }

    private void pump() {
        if (!isActive()) {
            cleanup();
            return;
        }
        while (jobs.size() < MAX_REQUESTS) {
            if (!pendingSources.isEmpty()) {
                Resolution resolution = pendingSources.iterator().next();
                pendingSources.remove(resolution);
                if (resolution.subscribers.isEmpty()) {
                    continue;
                }
                Job job = new Job();
                job.resolution = resolution;
                resolution.job = job;
                TLObject request;
                if (resolution.channelId != 0) {
                    TLRPC.TL_channels_getChannels channels = new TLRPC.TL_channels_getChannels();
                    TLRPC.TL_inputChannel channel = new TLRPC.TL_inputChannel();
                    channel.channel_id = resolution.channelId;
                    channels.id.add(channel);
                    request = channels;
                } else {
                    TLRPC.TL_contacts_resolveUsername username = new TLRPC.TL_contacts_resolveUsername();
                    username.username = resolution.username;
                    request = username;
                }
                send(job, request);
            } else if (!pendingReads.isEmpty()) {
                Map.Entry<Long, LinkedHashSet<Entry>> batch = pendingReads.entrySet().iterator().next();
                Job job = new Job();
                for (Entry entry : new ArrayList<>(batch.getValue())) {
                    batch.getValue().remove(entry);
                    if (entry.needed()) {
                        entry.job = job;
                        job.entries.add(entry);
                        job.versions.put(entry, entry.generation);
                        if (job.entries.size() == BATCH_SIZE) {
                            break;
                        }
                    }
                }
                if (batch.getValue().isEmpty()) {
                    pendingReads.remove(batch.getKey());
                }
                if (job.entries.isEmpty()) {
                    continue;
                }
                TLRPC.TL_channels_getMessages request = new TLRPC.TL_channels_getMessages();
                request.channel = job.entries.get(0).inputChannel;
                for (Entry entry : job.entries) {
                    request.id.add(entry.key.messageId);
                }
                send(job, request);
            } else if (!pendingSavedChecks.isEmpty()) {
                Job job = new Job();
                job.savedIds = pendingSavedChecks.removeFirst();
                TLRPC.TL_messages_getMessages request = new TLRPC.TL_messages_getMessages();
                request.id.addAll(job.savedIds);
                send(job, request);
            } else {
                break;
            }
        }
    }

    private void send(Job job, TLObject request) {
        jobs.add(job);
        job.timeout = () -> {
            if (job.active) {
                backend.cancel(job.id);
                complete(job, null, "TIMEOUT");
            }
        };
        job.id = backend.send(request, (response, error) -> ApplicationLoader.applicationHandler.post(() -> {
            if (job.active) {
                complete(job, response, error == null ? null : error.text);
            }
        }));
        AndroidUtilities.runOnUIThread(job.timeout, 30000);
    }

    private void complete(Job job, TLObject response, String error) {
        job.active = false;
        jobs.remove(job);
        AndroidUtilities.cancelRunOnUIThread(job.timeout);
        if (!isActive()) {
            cleanup();
            return;
        }
        if (job.savedIds != null) {
            if (error == null && response instanceof TLRPC.messages_Messages) {
                ArrayList<Integer> removed = new ArrayList<>();
                for (TLRPC.Message message : ((TLRPC.messages_Messages) response).messages) {
                    if (message instanceof TLRPC.TL_messageEmpty && job.savedIds.contains(message.id)
                            && (message.peer_id == null || message.peer_id.user_id == userId)) {
                        removed.add(message.id);
                    }
                }
                if (!removed.isEmpty()) onMessagesDeleted(userId, removed);
            }
        } else if (job.resolution != null) {
            Resolution resolution = job.resolution;
            TLRPC.Chat chat = null;
            long channelId = resolution.channelId;
            ArrayList<TLRPC.Chat> chats = null;
            if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                TLRPC.TL_contacts_resolvedPeer result = (TLRPC.TL_contacts_resolvedPeer) response;
                channelId = result.peer == null ? 0 : result.peer.channel_id;
                chats = result.chats;
            } else if (response instanceof TLRPC.messages_Chats) {
                chats = ((TLRPC.messages_Chats) response).chats;
            }
            if (error == null && chats != null && channelId > 0) {
                for (TLRPC.Chat item : chats) {
                    if (item.id == channelId) {
                        if (usable(item)) {
                            chat = item;
                        } else if (item instanceof TLRPC.TL_channelForbidden || item.kicked) {
                            error = "CHANNEL_PRIVATE";
                        }
                        break;
                    }
                }
            }
            resolved(resolution, chat, chat != null ? null : error == null ? "SOURCE_UNAVAILABLE" : error);
        } else {
            readResult(job, response, error);
        }
        schedule();
    }

    private void readResult(Job job, TLObject response, String error) {
        TLRPC.messages_Messages result = response instanceof TLRPC.messages_Messages ? (TLRPC.messages_Messages) response : null;
        long dialogId = job.entries.get(0).key.dialogId;
        // 界面通知可能被动画或防抖延后，已知的新权限仍必须先于旧响应生效。
        didReceivedNotification(NotificationCenter.updateInterfaces, currentAccount);
        TLRPC.Chat chat = job.entries.get(0).chat;
        if (result != null) {
            for (TLRPC.Chat item : result.chats) {
                if (item.id == -dialogId) {
                    if (item instanceof TLRPC.TL_channelForbidden || item.kicked) {
                        error = "CHANNEL_PRIVATE";
                    } else if (usable(item)) {
                        chat = item;
                    }
                }
            }
        }
        if (denied(error)) {
            boolean current = false;
            for (Entry entry : job.entries) {
                current |= entry.job == job && entry.generation == job.versions.get(entry);
            }
            if (current) {
                for (Entry entry : entries.values()) {
                    if (entry.key.dialogId != dialogId) continue;
                    invalidate(entry);
                    entry.message = null;
                    entry.state = SavedLinkReference.UNAVAILABLE;
                    entry.error = error;
                    persist(entry);
                    publish(entry);
                }
                storage.updateSource(dialogId, 0, SavedLinkReference.UNAVAILABLE, -1, (ignored, failure) -> {
                    if (failure != null) FileLog.e(failure);
                });
            }
            return;
        }
        TLRPC.TL_messages_messages accepted = new TLRPC.TL_messages_messages();
        accepted.chats.add(chat);
        if (result != null) {
            accepted.users.addAll(result.users);
        }
        ArrayList<Entry> found = new ArrayList<>();
        for (Entry entry : job.entries) {
            if (entry.job != job || entry.generation != job.versions.get(entry)) {
                continue;
            }
            entry.job = null;
            TLRPC.Message value = null;
            boolean empty = false;
            if (error == null && result != null) {
                for (TLRPC.Message message : result.messages) {
                    if (matches(message, entry.key)) {
                        value = message;
                        break;
                    }
                    empty |= message instanceof TLRPC.TL_messageEmpty && message.id == entry.key.messageId
                            && (message.peer_id == null || message.peer_id.channel_id == -dialogId);
                }
            }
            if (value != null) {
                value.dialog_id = dialogId;
                if (entry.message != null && (entry.protectedSource != chat.noforwards
                        || entry.message.messageOwner.noforwards != value.noforwards)) {
                    invalidateMedia(entry);
                }
                entry.chat = chat;
                entry.inputChannel = MessagesController.getInputChannel(chat);
                entry.protectedSource = chat.noforwards;
                entry.deniedSource = false;
                setMessage(entry, value);
                entry.cached = false;
                accepted.messages.add(value);
                found.add(entry);
            } else {
                entry.loading = false;
                entry.error = error == null ? empty ? "MESSAGE_DELETED" : "MESSAGE_MISSING" : error;
                entry.state = empty || denied(entry.error) ? SavedLinkReference.UNAVAILABLE : SavedLinkReference.FAILED;
                if (entry.state == SavedLinkReference.UNAVAILABLE) {
                    invalidateMedia(entry);
                    entry.message = null;
                }
                persist(entry);
                publish(entry);
                finishMedia(entry, entry.error);
            }
        }
        if (!found.isEmpty()) {
            HashMap<Entry, Integer> versions = new HashMap<>();
            for (Entry entry : found) {
                versions.put(entry, entry.generation);
            }
            getMessagesController().putUsers(accepted.users, false);
            getMessagesController().putChat(chat, false);
            didReceivedNotification(NotificationCenter.updateInterfaces, currentAccount);
            backend.save(dialogId, accepted, failure -> {
                if (!isActive()) {
                    return;
                }
                for (Entry entry : found) {
                    if (entry.generation != versions.get(entry)) {
                        continue;
                    }
                    entry.loading = false;
                    entry.error = failure == null ? null : "CACHE_WRITE_FAILED";
                    entry.state = failure == null ? SavedLinkReference.AVAILABLE : SavedLinkReference.FAILED;
                    if (failure == null) {
                        entry.lastSuccessAt = backend.now();
                    }
                    persist(entry);
                    publish(entry);
                    finishMedia(entry, entry.error);
                }
            });
        }
    }

    private static boolean denied(String error) {
        return "CHANNEL_PRIVATE".equals(error) || "CHANNEL_INVALID".equals(error) || "USER_BANNED_IN_CHANNEL".equals(error);
    }

    private void persist(Entry entry) {
        ArrayList<SavedLinkReference> values = new ArrayList<>();
        for (Subscription subscription : entry.subscribers) {
            if (valid(subscription)) {
                subscription.reference = subscription.reference.withSource(entry.key.dialogId, entry.state, entry.lastSuccessAt);
                ReferenceRequest request = references.get(subscription.savedMessageId);
                request.value = subscription.reference;
                values.add(subscription.reference);
            }
        }
        if (!values.isEmpty()) {
            int generation = entry.generation;
            storage.update(values, (ignored, error) -> {
                if (error != null && isActive() && entry.generation == generation) {
                    entry.error = "REFERENCE_WRITE_FAILED";
                    publish(entry);
                }
            });
        }
        storage.updateSource(entry.key.dialogId, entry.key.messageId, entry.state, entry.lastSuccessAt, (ignored, error) -> {
            if (error != null) {
                FileLog.e(error);
            }
        });
    }

    private void publish(Entry entry) {
        for (Subscription subscription : new ArrayList<>(entry.subscribers)) {
            publish(subscription);
        }
    }

    private void publish(Subscription subscription) {
        if (!valid(subscription)) {
            return;
        }
        Entry entry = subscription.entry;
        Resolution resolution = subscription.resolution;
        if (entry != null) {
            subscription.reference = subscription.reference.withSource(entry.key.dialogId, entry.state, entry.lastSuccessAt);
            subscription.callback.run(new Preview(subscription.reference,
                    entry.state == SavedLinkReference.UNAVAILABLE ? null : entry.message, entry.chat, entry.loading, entry.cached, entry.error));
        } else {
            subscription.callback.run(new Preview(subscription.reference, null, null,
                    resolution != null && !resolution.done, false, resolution == null ? null : resolution.error));
        }
    }

    private void cancelJob(Job job) {
        if (job == null || !job.active) {
            return;
        }
        job.active = false;
        jobs.remove(job);
        AndroidUtilities.cancelRunOnUIThread(job.timeout);
        backend.cancel(job.id);
        schedule();
    }

    private void setMessage(Entry entry, TLRPC.Message message) {
        entry.message = new MessageObject(currentAccount, message, false, false);
        entry.message.isSavedLinkPreview = true;
        entry.message.savedLinkProtected = entry.chat == null || entry.chat.noforwards || message.noforwards;
        entry.message.savedLinkUserId = userId;
        entry.message.savedLinkTestBackend = testBackend;
        entry.mediaObjects.removeIf(item -> item.get() == null);
        entry.mediaObjects.add(new WeakReference<>(entry.message));
    }

    private void invalidateMedia(Entry entry) {
        for (WeakReference<MessageObject> reference : entry.mediaObjects) {
            MessageObject message = reference.get();
            if (message != null) {
                message.savedLinkInvalidated = true;
                message.savedLinkProtected = true;
            }
        }
        entry.mediaObjects.clear();
        getNotificationCenter().postNotificationNameInternal(NotificationCenter.savedLinkPreviewInvalidated,
                true, entry.key.dialogId, entry.key.messageId);
    }

    private void invalidate(Entry entry) {
        entry.generation++;
        entry.loading = false;
        entry.cacheLoaded = true;
        entry.cacheLoading = false;
        LinkedHashSet<Entry> queued = pendingReads.get(entry.key.dialogId);
        if (queued != null) {
            queued.remove(entry);
        }
        Job job = entry.job;
        entry.job = null;
        if (job != null) {
            boolean needed = false;
            for (Entry item : job.entries) {
                needed |= item.job == job && item.needed();
            }
            if (!needed) {
                cancelJob(job);
            }
        }
        invalidateMedia(entry);
        finishMedia(entry, "SOURCE_CHANGED");
    }

    public static boolean isMediaValid(MessageObject message) {
        return message != null && (!message.isSavedLinkPreview || !message.savedLinkInvalidated
                && UserConfig.getInstance(message.currentAccount).getClientUserId() == message.savedLinkUserId
                && ConnectionsManager.getInstance(message.currentAccount).isTestBackend() == message.savedLinkTestBackend);
    }

    public void refreshMedia(MessageObject message, Utilities.Callback2<MessageObject, String> callback) {
        Entry entry = entries.get(new Key(message.getDialogId(), message.getId()));
        if (!isActive() || message.currentAccount != currentAccount || !isMediaValid(message)
                || entry == null || entry.state == SavedLinkReference.UNAVAILABLE) {
            callback.run(null, "SOURCE_UNAVAILABLE");
            return;
        }
        entry.mediaCallbacks.add(callback);
        queueRead(entry, true);
    }

    private void finishMedia(Entry entry, String error) {
        ArrayList<Utilities.Callback2<MessageObject, String>> callbacks = new ArrayList<>(entry.mediaCallbacks);
        entry.mediaCallbacks.clear();
        for (Utilities.Callback2<MessageObject, String> callback : callbacks) {
            callback.run(error == null ? entry.message : null, error);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || !isActive()) {
            return;
        }
        if (id == NotificationCenter.didClearDatabase) {
            for (Entry entry : entries.values()) {
                invalidate(entry);
                entry.message = null;
                entry.cached = false;
                entry.cacheLoaded = false;
                if (entry.state != SavedLinkReference.UNAVAILABLE) {
                    entry.state = SavedLinkReference.PENDING;
                    entry.error = null;
                }
                if (entry.needed()) {
                    entry.loading = true;
                    entry.cacheLoading = true;
                    pendingCache.computeIfAbsent(entry.key.dialogId, ignored -> new LinkedHashSet<>()).add(entry);
                }
                persist(entry);
                publish(entry);
            }
            AndroidUtilities.cancelRunOnUIThread(cacheRunnable);
            AndroidUtilities.runOnUIThread(cacheRunnable, 16);
        } else if (id == NotificationCenter.replaceMessagesObjects) {
            long dialogId = (Long) args[0];
            ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
            for (MessageObject message : messages) {
                if (message.currentAccount != currentAccount || message.getDialogId() != dialogId) {
                    continue;
                }
                if (dialogId == userId) {
                    if (!deletedSavedIds.contains(message.getId())) {
                        loadReference(message.messageOwner, (value, error) -> {});
                    }
                    continue;
                }
                Entry entry = entries.get(new Key(dialogId, message.getId()));
                if (entry == null || !matches(message.messageOwner, entry.key)) {
                    continue;
                }
                invalidate(entry);
                if (entry.state != SavedLinkReference.UNAVAILABLE) {
                    setMessage(entry, message.messageOwner);
                    entry.state = SavedLinkReference.AVAILABLE;
                    entry.error = null;
                    entry.lastSuccessAt = backend.now();
                    TLRPC.TL_messages_messages result = new TLRPC.TL_messages_messages();
                    result.messages.add(message.messageOwner);
                    if (entry.chat != null) {
                        result.chats.add(entry.chat);
                    }
                    backend.save(dialogId, result, error -> {
                        if (error != null) {
                            FileLog.e(error);
                        }
                    });
                }
                persist(entry);
                publish(entry);
            }
        } else if (id == NotificationCenter.chatInfoDidLoad || id == NotificationCenter.updateInterfaces) {
            for (Entry entry : new ArrayList<>(entries.values())) {
                TLRPC.Chat chat = getMessagesController().getChat(-entry.key.dialogId);
                boolean protectedSource = chat == null || chat.noforwards;
                boolean deniedSource = chat instanceof TLRPC.TL_channelForbidden || chat != null && chat.kicked;
                if (entry.protectedSource == protectedSource && entry.deniedSource == deniedSource) {
                    if (entry.chat != chat) {
                        entry.chat = chat;
                        if (usable(chat)) entry.inputChannel = MessagesController.getInputChannel(chat);
                        publish(entry);
                    }
                    continue;
                }
                TLRPC.Message previous = entry.message == null ? null : entry.message.messageOwner;
                invalidate(entry);
                entry.chat = chat;
                if (usable(chat)) entry.inputChannel = MessagesController.getInputChannel(chat);
                entry.protectedSource = protectedSource;
                entry.deniedSource = deniedSource;
                if (deniedSource) {
                    entry.message = null;
                    entry.state = SavedLinkReference.UNAVAILABLE;
                    entry.error = "CHANNEL_PRIVATE";
                } else if (previous != null && entry.state != SavedLinkReference.UNAVAILABLE) {
                    setMessage(entry, previous);
                }
                persist(entry);
                publish(entry);
            }
        }
    }

    public void onMessagesDeleted(long dialogId, List<Integer> messageIds) {
        ArrayList<Integer> ids = new ArrayList<>(messageIds);
        AndroidUtilities.runOnUIThread(() -> {
            if (!isActive()) {
                return;
            }
            if (dialogId == 0 || dialogId == userId) {
                deletedSavedIds.addAll(ids);
                for (int messageId : ids) {
                    ReferenceRequest request = references.get(messageId);
                    references.remove(messageId);
                    if (request != null) {
                        finish(request, null, new CancellationException("收藏已删除"));
                    }
                }
                for (Subscription subscription : new ArrayList<>(subscriptions)) {
                    if (ids.contains(subscription.savedMessageId)) {
                        subscription.cancel();
                    }
                }
                storage.remove(ids, (ignored, error) -> {
                    if (error != null) {
                        FileLog.e(error);
                    }
                });
            } else if (dialogId < 0) {
                for (int messageId : ids) {
                    Entry entry = entries.get(new Key(dialogId, messageId));
                    if (entry != null) {
                        invalidate(entry);
                        entry.message = null;
                        entry.state = SavedLinkReference.UNAVAILABLE;
                        entry.error = "MESSAGE_DELETED";
                        persist(entry);
                        publish(entry);
                    }
                    storage.updateSource(dialogId, messageId, SavedLinkReference.UNAVAILABLE, -1, (ignored, error) -> {
                        if (error != null) {
                            FileLog.e(error);
                        }
                    });
                }
            }
        });
    }

    public void onDeleteResponse(long dialogId, TLObject request, TLObject response, TLRPC.TL_error error) {
        if (error != null) {
            return;
        }
        if (response instanceof TLRPC.TL_messages_affectedHistory && ((TLRPC.TL_messages_affectedHistory) response).offset == 0) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!isActive() || dialogId != userId) return;
                int maxId;
                boolean check;
                if (request instanceof TLRPC.TL_messages_deleteHistory) {
                    TLRPC.TL_messages_deleteHistory history = (TLRPC.TL_messages_deleteHistory) request;
                    if (!(history.peer instanceof TLRPC.TL_inputPeerSelf) && (history.peer == null || history.peer.user_id != userId)) return;
                    maxId = history.max_id > 0 ? history.max_id : Integer.MAX_VALUE;
                    check = (history.flags & 12) != 0 || history.max_id <= 0 || history.max_id == Integer.MAX_VALUE;
                } else if (request instanceof TLRPC.TL_messages_deleteSavedHistory) {
                    TLRPC.TL_messages_deleteSavedHistory history = (TLRPC.TL_messages_deleteSavedHistory) request;
                    if (history.parent_peer != null) return;
                    maxId = history.max_id > 0 ? history.max_id : Integer.MAX_VALUE;
                    check = true;
                } else return;
                ArrayList<Integer> known = new ArrayList<>();
                for (int i = 0; i < references.size(); i++) {
                    if (references.keyAt(i) <= maxId) known.add(references.keyAt(i));
                }
                storage.loadIds(maxId, (ids, failure) -> {
                    if (!isActive()) return;
                    if (failure != null) {
                        FileLog.e(failure);
                        return;
                    }
                    LinkedHashSet<Integer> all = new LinkedHashSet<>(ids);
                    all.addAll(known);
                    ids = new ArrayList<>(all);
                    if (!check) {
                        onMessagesDeleted(userId, ids);
                    } else {
                        // 仅检查本地已有引用，缺项和失败不能被当作已删除收藏。
                        for (int offset = 0; offset < ids.size(); offset += BATCH_SIZE) {
                            pendingSavedChecks.add(new ArrayList<>(ids.subList(offset, Math.min(offset + BATCH_SIZE, ids.size()))));
                        }
                        schedule();
                    }
                });
            });
        } else if (!(response instanceof TLRPC.TL_messages_affectedMessages)) {
            return;
        } else if (request instanceof TLRPC.TL_channels_deleteMessages) {
            onMessagesDeleted(-((TLRPC.TL_channels_deleteMessages) request).channel.channel_id,
                    ((TLRPC.TL_channels_deleteMessages) request).id);
        } else if (request instanceof TLRPC.TL_messages_deleteMessages) {
            onMessagesDeleted(dialogId, ((TLRPC.TL_messages_deleteMessages) request).id);
        }
    }

    public void clearReferenceCache(Utilities.Callback2<Void, Exception> callback) {
        if (!isActive()) {
            callback.run(null, new CancellationException("链接预览账号已失效"));
            return;
        }
        if (instances[currentAccount] == this) instances[currentAccount] = null;
        storage.clear((ignored, error) -> {
            if (getUserConfig().getClientUserId() == userId && getConnectionsManager().isTestBackend() == testBackend) {
                getNotificationCenter().postNotificationNameInternal(NotificationCenter.savedLinkReferencesCleared, true, userId);
            }
            callback.run(null, error);
        });
        // 清理排在已有写入之后，立即失效回调，避免旧解析在清理完成后重新插入。
        cleanup();
    }

    public boolean isActive() {
        return !closed && getUserConfig().isClientActivated() && getUserConfig().getClientUserId() == userId
                && getConnectionsManager().isTestBackend() == testBackend;
    }

    public long getUserId() {
        return userId;
    }

    int getParseCount() {
        return parseCount;
    }

    public void loadReference(TLRPC.Message message, Utilities.Callback2<SavedLinkReference, Exception> callback) {
        Input input;
        try {
            if (!isActive()) {
                throw new CancellationException("链接预览账号已失效");
            }
            input = snapshot(userId, message);
            if (deletedSavedIds.contains(input.messageId)) {
                throw new CancellationException("收藏消息已确认删除");
            }
        } catch (Exception e) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null, e));
            return;
        }
        ReferenceRequest old = references.get(input.messageId);
        if (old != null && input.signature.equals(old.input.signature)) {
            if (old.value != null) {
                deliver(old, callback, old.value, null);
                return;
            }
            if (old.loading) {
                old.callbacks.add(callback);
                return;
            }
        }
        if (old != null) {
            finish(old, null, new CancellationException("收藏链接内容已更新"));
            for (Subscription subscription : new ArrayList<>(subscriptions)) {
                if (subscription.savedMessageId == input.messageId && !subscription.signature.equals(input.signature)) {
                    subscription.cancel();
                }
            }
        }
        ReferenceRequest request = new ReferenceRequest();
        request.input = input;
        request.loading = true;
        request.callbacks.add(callback);
        references.put(input.messageId, request);
        storage.load(Collections.singleton(input.messageId), (values, error) -> {
            if (!current(request)) {
                return;
            }
            if (error != null) {
                finish(request, null, error);
                return;
            }
            SavedLinkReference value = values.get(input.messageId);
            if (value != null && input.signature.equals(value.contentSignature)) {
                finish(request, value, null);
                return;
            }
            parseCount++;
            SavedLinkReference parsed = parse(input);
            storage.save(Collections.singleton(parsed), (ignored, failure) -> {
                if (current(request)) {
                    finish(request, failure == null ? parsed : null, failure);
                }
            });
        });
    }

    private boolean current(ReferenceRequest request) {
        if (references.get(request.input.messageId) != request) {
            return false;
        }
        if (!isActive()) {
            finish(request, null, new CancellationException("链接预览账号已失效"));
            return false;
        }
        return true;
    }

    private void finish(ReferenceRequest request, SavedLinkReference value, Exception error) {
        request.loading = false;
        request.value = value;
        ArrayList<Utilities.Callback2<SavedLinkReference, Exception>> callbacks = new ArrayList<>(request.callbacks);
        request.callbacks.clear();
        for (Utilities.Callback2<SavedLinkReference, Exception> callback : callbacks) {
            deliver(request, callback, value, error);
        }
    }

    private void deliver(ReferenceRequest request, Utilities.Callback2<SavedLinkReference, Exception> callback,
                         SavedLinkReference value, Exception error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (error == null && (!isActive() || references.get(request.input.messageId) != request)) {
                callback.run(null, new CancellationException("收藏链接请求已失效"));
            } else {
                callback.run(value, error);
            }
        });
    }

    public void cleanup() {
        if (closed) {
            return;
        }
        closed = true;
        getNotificationCenter().removeObserver(this, NotificationCenter.replaceMessagesObjects);
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.didClearDatabase);
        for (Entry entry : entries.values()) {
            invalidateMedia(entry);
            finishMedia(entry, "ACCOUNT_CHANGED");
        }
        for (Subscription subscription : new ArrayList<>(subscriptions)) {
            subscription.cancel();
        }
        for (Job job : new ArrayList<>(jobs)) {
            cancelJob(job);
        }
        AndroidUtilities.cancelRunOnUIThread(pumpRunnable);
        AndroidUtilities.cancelRunOnUIThread(cacheRunnable);
        pendingCache.clear();
        pendingReads.clear();
        pendingSources.clear();
        pendingSavedChecks.clear();
        entries.clear();
        resolutions.clear();
        for (int i = 0; i < references.size(); i++) {
            finish(references.valueAt(i), null, new CancellationException("链接预览已关闭"));
        }
        references.clear();
        storage.close(null);
    }

    public static SavedLinkReference parse(long userId, TLRPC.Message message) {
        return parse(snapshot(userId, message));
    }

    public static String contentSignature(long userId, TLRPC.Message message) {
        return snapshot(userId, message).signature;
    }

    private static Input snapshot(long userId, TLRPC.Message message) {
        if (userId <= 0 || message == null || message.id <= 0 || message.peer_id == null
                || message.peer_id.user_id != userId || message.peer_id.channel_id != 0 || message.peer_id.chat_id != 0
                || message.dialog_id != 0 && message.dialog_id != userId) {
            throw new IllegalArgumentException("只能解析当前账号已保存的收藏消息");
        }
        Input input = new Input();
        input.userId = userId;
        input.messageId = message.id;
        input.text = message.message == null ? "" : message.message;
        for (TLRPC.MessageEntity entity : message.entities) {
            if (entity instanceof TLRPC.TL_messageEntityUrl || entity instanceof TLRPC.TL_messageEntityTextUrl) {
                Link link = new Link();
                link.offset = entity.offset;
                link.length = entity.length;
                link.hidden = entity instanceof TLRPC.TL_messageEntityTextUrl;
                link.url = link.hidden ? entity.url : null;
                input.entities.add(link);
            }
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeText(out, input.text);
            out.writeInt(input.entities.size());
            for (Link link : input.entities) {
                out.writeInt(link.offset);
                out.writeInt(link.length);
                out.writeBoolean(link.hidden);
                writeText(out, link.url);
            }
            out.flush();
            input.signature = Utilities.bytesToHex(Utilities.computeSHA256(bytes.toByteArray()));
        } catch (IOException e) {
            throw new IllegalStateException("无法生成收藏链接内容签名", e);
        }
        return input;
    }

    private static void writeText(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeInt(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        }
    }

    private static SavedLinkReference parse(Input input) {
        TreeMap<Integer, String> links = new TreeMap<>();
        ArrayList<Link> validEntities = new ArrayList<>();
        for (Link link : input.entities) {
            if (link.offset < 0 || link.length <= 0 || link.offset > input.text.length() - link.length) {
                continue;
            }
            validEntities.add(link);
            links.put(link.offset, link.hidden ? link.url : input.text.substring(link.offset, link.offset + link.length));
        }
        Matcher matcher = URLS.matcher(input.text);
        while (matcher.find()) {
            boolean covered = false;
            for (Link link : validEntities) {
                if (matcher.start() < link.offset + link.length && matcher.end() > link.offset) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                String url = matcher.group();
                while (!url.isEmpty() && ".,;)]}，。；！）、】》”’".indexOf(url.charAt(url.length() - 1)) >= 0) {
                    url = url.substring(0, url.length() - 1);
                }
                links.put(matcher.start(), url);
            }
        }
        String unsupported = null;
        for (String value : links.values()) {
            if (value == null) {
                continue;
            }
            String url = value.trim();
            Uri uri = Uri.parse(url);
            if (!"t.me".equalsIgnoreCase(uri.getHost()) && !"telegram.me".equalsIgnoreCase(uri.getHost())) {
                continue;
            }
            if (unsupported == null) {
                unsupported = url;
            }
            String query = uri.getEncodedQuery();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null || uri.getPort() != -1
                    || uri.getFragment() != null || query != null && !query.equals("single")) {
                continue;
            }
            Matcher post = POST.matcher(uri.getEncodedPath() == null ? "" : uri.getEncodedPath());
            if (!post.matches()) {
                continue;
            }
            String username = post.group(2) == null ? null : post.group(2).toLowerCase(Locale.ROOT);
            if (username != null && RESERVED.contains(username)) {
                continue;
            }
            try {
                long channelId = post.group(1) == null ? 0 : Long.parseLong(post.group(1));
                int messageId = Integer.parseInt(post.group(3));
                return new SavedLinkReference(input.userId, input.messageId, input.signature, url, username,
                        channelId, messageId, SavedLinkReference.PARSED, 0, SavedLinkReference.PENDING, 0);
            } catch (NumberFormatException ignored) {
                // 链接中的编号不能截断后再请求另一个来源。
            }
        }
        return new SavedLinkReference(input.userId, input.messageId, input.signature, unsupported, null,
                0, 0, unsupported == null ? SavedLinkReference.NO_LINK : SavedLinkReference.UNSUPPORTED,
                0, SavedLinkReference.PENDING, 0);
    }
}
