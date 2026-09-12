package org.telegram.messenger;

import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.ArrayDeque;
import java.util.concurrent.CancellationException;
import java.io.IOException;

public class LocalSavedTagsController extends BaseController {

    public static final long UNTAGGED = -1;

    private final LocalSavedTagsStorage storage;
    private final long userId;
    private final boolean testBackend;
    private final ArrayList<LocalSavedTag> tags = new ArrayList<>();
    private volatile boolean closed;
    private boolean loaded;
    private Exception loadError;
    private final SparseArray<ArrayList<LocalSavedTag>> messageTags = new SparseArray<>();
    private final HashSet<Integer> requestedMessageTags = new HashSet<>();
    private final ArrayList<Integer> pendingMessageIds = new ArrayList<>();
    private int messageTagsVersion;
    private final MessageSource messageSource;
    private final HashSet<FilterSession> filterSessions = new HashSet<>();
    private final HashSet<Integer> deletionRequests = new HashSet<>();
    private final int deletionGuid = ConnectionsManager.generateClassGuid();

    interface MessageSource {
        void loadCached(ArrayList<Integer> ids, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback);
        int loadRemote(ArrayList<Integer> ids, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback);
        int loadHistory(TLRPC.TL_messages_getHistory request, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback);
        void loadCachedHistory(int beforeId, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback);
        void cancel(int requestId);
    }
    private final Runnable loadPendingMessageTags = () -> {
        ArrayList<Integer> ids = new ArrayList<>(pendingMessageIds);
        pendingMessageIds.clear();
        loadMessageTags(ids, (result, error) -> {
            if (error == null) {
                getNotificationCenter().postNotificationName(NotificationCenter.localSavedMessageTagsLoaded, getUserId());
            }
        });
    };

    public LocalSavedTagsController(int currentAccount) {
        this(currentAccount, new LocalSavedTagsStorage(currentAccount));
    }

    LocalSavedTagsController(int currentAccount, LocalSavedTagsStorage storage) {
        this(currentAccount, storage, null);
    }

    LocalSavedTagsController(int currentAccount, LocalSavedTagsStorage storage, MessageSource source) {
        super(currentAccount);
        this.storage = storage;
        userId = storage.getUserId();
        testBackend = getConnectionsManager().isTestBackend();
        messageSource = source != null ? source : new MessageSource() {
            @Override
            public void loadCached(ArrayList<Integer> ids, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
                getMessagesStorage().getMessagesByIds(userId, ids, callback);
            }

            @Override
            public int loadRemote(ArrayList<Integer> ids, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
                TLRPC.TL_messages_getMessages request = new TLRPC.TL_messages_getMessages();
                request.id.addAll(ids);
                int requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.messages_Messages) {
                        callback.run((TLRPC.messages_Messages) response, null);
                    } else {
                        callback.run(null, new IOException(error == null ? "收藏消息响应无效" : error.text));
                    }
                }));
                getConnectionsManager().bindRequestToGuid(requestId, classGuid);
                return requestId;
            }

            @Override
            public void cancel(int requestId) {
                getConnectionsManager().cancelRequest(requestId, true);
            }

            @Override
            public int loadHistory(TLRPC.TL_messages_getHistory request, int classGuid, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
                int requestId = getConnectionsManager().sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (response instanceof TLRPC.messages_Messages) {
                        callback.run((TLRPC.messages_Messages) response, null);
                    } else {
                        callback.run(null, new IOException(error == null ? "收藏历史响应无效" : error.text));
                    }
                }));
                getConnectionsManager().bindRequestToGuid(requestId, classGuid);
                return requestId;
            }

            @Override
            public void loadCachedHistory(int beforeId, Utilities.Callback2<TLRPC.messages_Messages, Exception> callback) {
                getMessagesStorage().getCachedSavedHistory(userId, beforeId, callback);
            }
        };
    }

    public long getUserId() {
        return userId;
    }

    public boolean isActive() {
        return !closed && getUserConfig().isClientActivated() && getUserConfig().getClientUserId() == userId
                && getConnectionsManager().isTestBackend() == testBackend;
    }

    public ArrayList<LocalSavedTag> getTags() {
        return new ArrayList<>(tags);
    }

    public boolean hasLoadedTags() {
        return loaded;
    }

    public Exception getLoadError() {
        return loadError;
    }

    public void loadTags(Utilities.Callback2<ArrayList<LocalSavedTag>, Exception> callback) {
        if (!checkActive(callback)) {
            return;
        }
        storage.loadTags((result, error) -> {
            if (!checkActive(callback)) {
                return;
            }
            loadError = error;
            if (error == null) {
                tags.clear();
                tags.addAll(result);
                loaded = true;
            }
            callback.run(error == null ? getTags() : null, error);
        });
    }

    public void createTag(String name, Utilities.Callback2<LocalSavedTag, Exception> callback) {
        if (checkActive(callback)) {
            storage.createTag(name, (tag, error) -> finishTagEdit(tag, error, callback));
        }
    }

    public void renameTag(long tagId, String name, Utilities.Callback2<LocalSavedTag, Exception> callback) {
        if (checkActive(callback)) {
            storage.renameTag(tagId, name, (tag, error) -> finishTagEdit(tag, error, callback));
        }
    }

    public void deleteTag(long tagId, Utilities.Callback2<Void, Exception> callback) {
        if (!checkActive(callback)) {
            return;
        }
        storage.deleteTag(tagId, (result, error) -> {
            if (!checkActive(callback)) {
                return;
            }
            if (error == null) {
                invalidateMessageTags();
                for (int i = tags.size() - 1; i >= 0; i--) {
                    if (tags.get(i).id == tagId) {
                        tags.remove(i);
                    }
                }
                getNotificationCenter().postNotificationName(NotificationCenter.localSavedTagsUpdated, userId);
            }
            callback.run(null, error);
        });
    }

    private void finishTagEdit(LocalSavedTag tag, Exception error, Utilities.Callback2<LocalSavedTag, Exception> callback) {
        if (!checkActive(callback)) {
            return;
        }
        if (error == null) {
            invalidateMessageTags();
            for (int i = tags.size() - 1; i >= 0; i--) {
                if (tags.get(i).id == tag.id) {
                    tags.remove(i);
                }
            }
            tags.add(tag);
            Collections.sort(tags, (a, b) -> {
                int count = Integer.compare(b.messageCount, a.messageCount);
                if (count != 0) {
                    return count;
                }
                int date = Long.compare(b.createdAt, a.createdAt);
                return date != 0 ? date : Long.compare(b.id, a.id);
            });
            getNotificationCenter().postNotificationName(NotificationCenter.localSavedTagsUpdated, userId);
        }
        callback.run(tag, error);
    }

    private <T> boolean checkActive(Utilities.Callback2<T, Exception> callback) {
        if (isActive()) {
            return true;
        }
        AndroidUtilities.runOnUIThread(() -> callback.run(null, new CancellationException("本地标签账号已切换或退出")));
        return false;
    }

    public boolean canTagMessage(MessageObject message) {
        return isActive() && message != null && message.currentAccount == currentAccount
                && message.getDialogId() == userId && message.getId() > 0 && message.messageOwner.date > 0
                && message.messageOwner instanceof TLRPC.TL_message && !message.isDateObject
                && !message.scheduled && message.messageOwner.send_state != MessageObject.MESSAGE_SEND_STATE_SENDING
                && message.messageOwner.send_state != MessageObject.MESSAGE_SEND_STATE_SEND_ERROR
                && (message.messageOwner.action == null || message.messageOwner.action instanceof TLRPC.TL_messageActionEmpty);
    }

    public void applyTags(List<MessageObject> messages, List<Long> addTagIds, List<Long> removeTagIds,
                          Utilities.Callback2<Void, Exception> callback) {
        if (!checkActive(callback)) {
            return;
        }
        SparseIntArray dates = new SparseIntArray();
        for (MessageObject message : messages) {
            if (!canTagMessage(message)) {
                AndroidUtilities.runOnUIThread(() -> callback.run(null, new IllegalArgumentException("只能标记已保存的收藏消息")));
                return;
            }
            dates.put(message.getId(), message.messageOwner.date);
        }
        if (dates.size() == 0) {
            AndroidUtilities.runOnUIThread(() -> callback.run(null, new IllegalArgumentException("没有可标记的收藏消息")));
            return;
        }
        storage.applyTags(dates, addTagIds, removeTagIds, (result, error) -> {
            if (!checkActive(callback)) {
                return;
            }
            if (error != null) {
                callback.run(null, error);
                return;
            }
            invalidateMessageTags();
            loadTags((updated, readError) -> {
                if (!checkActive(callback)) {
                    return;
                }
                // 写入已提交，刷新读取失败不能把成功写入报告成失败。
                getNotificationCenter().postNotificationName(NotificationCenter.localSavedTagsUpdated, userId);
                callback.run(null, null);
            });
        });
    }

    public void loadMessageTags(List<Integer> messageIds,
                                Utilities.Callback2<SparseArray<ArrayList<LocalSavedTag>>, Exception> callback) {
        if (!checkActive(callback)) {
            return;
        }
        int version = messageTagsVersion;
        storage.loadMessageTags(messageIds, (result, error) -> {
            if (!checkActive(callback)) {
                return;
            }
            if (version != messageTagsVersion) {
                callback.run(null, new CancellationException("消息标签已更新"));
                return;
            }
            if (error == null) {
                for (int i = 0; i < result.size(); i++) {
                    messageTags.put(result.keyAt(i), result.valueAt(i));
                }
            }
            callback.run(result, error);
        });
    }

    public ArrayList<LocalSavedTag> getMessageTags(int messageId) {
        ArrayList<LocalSavedTag> result = messageTags.get(messageId);
        return result == null ? new ArrayList<>() : new ArrayList<>(result);
    }

    public void requestMessageTags(List<MessageObject> messages) {
        for (MessageObject message : messages) {
            if (canTagMessage(message) && messageTags.indexOfKey(message.getId()) < 0 && requestedMessageTags.add(message.getId())) {
                pendingMessageIds.add(message.getId());
            }
        }
        if (!pendingMessageIds.isEmpty()) {
            AndroidUtilities.cancelRunOnUIThread(loadPendingMessageTags);
            AndroidUtilities.runOnUIThread(loadPendingMessageTags);
        }
    }

    private void invalidateMessageTags() {
        messageTagsVersion++;
        messageTags.clear();
        requestedMessageTags.clear();
        pendingMessageIds.clear();
        AndroidUtilities.cancelRunOnUIThread(loadPendingMessageTags);
    }

    public void cleanup() {
        closed = true;
        for (FilterSession session : new ArrayList<>(filterSessions)) {
            session.cancel();
        }
        for (int requestId : deletionRequests) {
            messageSource.cancel(requestId);
        }
        deletionRequests.clear();
        invalidateMessageTags();
        tags.clear();
        loaded = false;
        loadError = null;
        storage.close();
    }

    public void onDeleteResponse(long dialogId, TLObject request, TLObject response, TLRPC.TL_error error) {
        AndroidUtilities.runOnUIThread(() -> {
            if (!isActive() || dialogId != userId || error != null) {
                return;
            }
            if (request instanceof TLRPC.TL_messages_deleteMessages && response instanceof TLRPC.TL_messages_affectedMessages) {
                onMessagesDeleted(((TLRPC.TL_messages_deleteMessages) request).id);
            } else if (response instanceof TLRPC.TL_messages_affectedHistory && ((TLRPC.TL_messages_affectedHistory) response).offset == 0) {
                if (request instanceof TLRPC.TL_messages_deleteHistory) {
                    TLRPC.TL_messages_deleteHistory history = (TLRPC.TL_messages_deleteHistory) request;
                    if (!(history.peer instanceof TLRPC.TL_inputPeerSelf) && (history.peer == null || history.peer.user_id != userId)) {
                        return;
                    }
                    int maxId = history.max_id > 0 ? history.max_id : Integer.MAX_VALUE;
                    int minDate = (history.flags & 4) != 0 ? history.min_date : 0;
                    int maxDate = (history.flags & 8) != 0 ? history.max_date : 0;
                    if (maxId != Integer.MAX_VALUE && minDate == 0 && maxDate == 0) {
                        storage.removeHistory(maxId, this::finishMessageDeletion);
                    } else {
                        reconcileHistory(maxId, minDate, maxDate);
                    }
                } else if (request instanceof TLRPC.TL_messages_deleteSavedHistory) {
                    TLRPC.TL_messages_deleteSavedHistory history = (TLRPC.TL_messages_deleteSavedHistory) request;
                    if (history.parent_peer == null) {
                        // 标签库不保存来源；服务端逐个确认空消息，避免按不完整缓存猜测来源范围。
                        reconcileHistory(history.max_id > 0 ? history.max_id : Integer.MAX_VALUE,
                                (history.flags & 4) != 0 ? history.min_date : 0, (history.flags & 8) != 0 ? history.max_date : 0);
                    }
                }
            }
        });
    }

    public void onMessagesDeleted(List<Integer> messageIds) {
        if (!isActive() || messageIds.isEmpty()) {
            return;
        }
        storage.removeMessages(messageIds, this::finishMessageDeletion);
    }

    private void finishMessageDeletion(Void ignored, Exception error) {
        if (!isActive()) {
            return;
        }
        if (error != null) {
            FileLog.e(error);
            return;
        }
        invalidateMessageTags();
        loadTags((result, failure) -> {
            if (isActive()) {
                getNotificationCenter().postNotificationName(NotificationCenter.localSavedTagsUpdated, userId);
            }
        });
    }

    private void reconcileHistory(int maxId, int minDate, int maxDate) {
        storage.loadHistoryIds(maxId, minDate, maxDate, (ids, error) -> {
            if (!isActive()) {
                return;
            }
            if (error != null) {
                FileLog.e(error);
                return;
            }
            // 固定本次已打标编号集合；不扫描全部收藏，也不包含此后新加入的关联。
            verifyDeletedMessages(ids, 0);
        });
    }

    private void verifyDeletedMessages(ArrayList<Integer> ids, int offset) {
        if (!isActive() || offset >= ids.size()) {
            return;
        }
        ArrayList<Integer> batch = new ArrayList<>(ids.subList(offset, Math.min(offset + 100, ids.size())));
        int[] requestId = new int[1];
        requestId[0] = messageSource.loadRemote(batch, deletionGuid, (response, error) -> {
            deletionRequests.remove(requestId[0]);
            if (!isActive()) {
                return;
            }
            if (error != null) {
                FileLog.e(error);
                return;
            }
            ArrayList<Integer> missing = new ArrayList<>();
            for (TLRPC.Message message : response.messages) {
                if (batch.contains(message.id) && message instanceof TLRPC.TL_messageEmpty
                        && (message.peer_id == null || message.peer_id.user_id == userId)) {
                    missing.add(message.id);
                }
            }
            if (missing.isEmpty()) {
                verifyDeletedMessages(ids, offset + batch.size());
            } else {
                storage.removeMessages(missing, (ignored, failure) -> {
                    if (!isActive()) {
                        return;
                    }
                    finishMessageDeletion(null, failure);
                    if (failure == null) {
                        verifyDeletedMessages(ids, offset + batch.size());
                    }
                });
            }
        });
        deletionRequests.add(requestId[0]);
    }

    public FilterSession createFilterSession(int classGuid, long tagId, List<MessageObject> knownMessages, Runnable onChanged) {
        if (!isActive() || tagId <= 0 && tagId != UNTAGGED) {
            throw new IllegalArgumentException("无效的本地标签筛选");
        }
        FilterSession session = new FilterSession(classGuid, tagId, knownMessages, onChanged);
        filterSessions.add(session);
        return session;
    }

    public class FilterSession {
        public final long tagId;
        private final int tagsVersion = messageTagsVersion;
        private final int classGuid;
        private final Runnable onChanged;
        private final SparseArray<MessageObject> known = new SparseArray<>();
        private final SparseArray<MessageObject> resolved = new SparseArray<>();
        private final ArrayList<LocalSavedTagMessage> links = new ArrayList<>();
        private final HashSet<Integer> pending = new HashSet<>();
        private LocalSavedTagMessage before;
        private boolean indexEnded;
        private boolean loading;
        private boolean cancelled;
        private Exception error;
        private int requestId;
        private int version;
        private final ArrayList<MessageObject> historyMessages = new ArrayList<>();
        private final ArrayDeque<MessageObject> historyBuffer = new ArrayDeque<>();
        private final HashSet<Integer> seenHistory = new HashSet<>();
        private int historyBefore;
        private boolean historyEnded;
        private boolean scanLimitReached;
        private boolean cacheOnly;
        private boolean historyNeedsConfirmation;
        private int historyTargetCount;

        private FilterSession(int classGuid, long tagId, List<MessageObject> messages, Runnable onChanged) {
            this.classGuid = classGuid;
            this.tagId = tagId;
            this.onChanged = onChanged;
            for (MessageObject message : messages) {
                if (canTagMessage(message)) {
                    known.put(message.getId(), message);
                }
            }
        }

        public ArrayList<MessageObject> getMessages() {
            if (tagId == UNTAGGED) {
                return new ArrayList<>(historyMessages);
            }
            ArrayList<MessageObject> result = new ArrayList<>();
            for (LocalSavedTagMessage link : links) {
                MessageObject message = resolved.get(link.messageId);
                if (message != null) {
                    result.add(message);
                }
            }
            return result;
        }

        public boolean isLoading() {
            return loading;
        }

        public boolean isEndReached() {
            if (tagId == UNTAGGED) {
                return historyEnded && historyBuffer.isEmpty() && !loading && error == null;
            }
            return indexEnded && pending.isEmpty() && !loading && error == null;
        }

        public boolean isScanLimitReached() {
            return scanLimitReached;
        }

        public boolean isCacheOnly() {
            return cacheOnly;
        }

        public Exception getError() {
            return error;
        }

        public int getPendingCount() {
            return pending.size();
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public boolean hasTagChanges() {
            return tagsVersion != messageTagsVersion;
        }

        public void replaceMessages(List<MessageObject> messages) {
            if (!isCurrent(version)) {
                return;
            }
            boolean changed = false;
            for (MessageObject message : messages) {
                if (!canTagMessage(message)) {
                    continue;
                }
                int id = message.getId();
                known.put(id, message);
                if (tagId == UNTAGGED) {
                    for (int i = 0; i < historyMessages.size(); i++) {
                        if (historyMessages.get(i).getId() == id) {
                            message.stableId = historyMessages.get(i).stableId;
                            historyMessages.set(i, message);
                            changed = true;
                            break;
                        }
                    }
                    ArrayList<MessageObject> buffer = new ArrayList<>(historyBuffer);
                    for (int i = 0; i < buffer.size(); i++) {
                        if (buffer.get(i).getId() == id) {
                            buffer.set(i, message);
                            historyBuffer.clear();
                            historyBuffer.addAll(buffer);
                            break;
                        }
                    }
                } else if (resolved.indexOfKey(id) >= 0 || pending.contains(id)) {
                    MessageObject old = resolved.get(id);
                    if (old != null) {
                        message.stableId = old.stableId;
                    }
                    resolved.put(id, message);
                    pending.remove(id);
                    changed = true;
                }
            }
            if (changed) {
                onChanged.run();
            }
        }

        public void loadMore() {
            if (!isCurrent(version) || loading || isEndReached()) {
                return;
            }
            boolean continueHistoryPage = error != null || scanLimitReached;
            loading = true;
            error = null;
            scanLimitReached = false;
            cacheOnly = false;
            onChanged.run();
            int query = version;
            if (tagId == UNTAGGED) {
                if (!continueHistoryPage) {
                    historyTargetCount = historyMessages.size() + LocalSavedTagsStorage.PAGE_SIZE;
                }
                scanHistory(query, 0, historyTargetCount);
                return;
            }
            if (!pending.isEmpty()) {
                loadBodies(query);
                return;
            }
            storage.loadMessages(tagId, before, LocalSavedTagsStorage.PAGE_SIZE, (page, failure) -> {
                if (!isCurrent(query)) {
                    return;
                }
                if (failure != null) {
                    finish(failure);
                    return;
                }
                links.addAll(page.messages);
                indexEnded = !page.hasMore;
                if (!page.messages.isEmpty()) {
                    before = page.messages.get(page.messages.size() - 1);
                }
                for (LocalSavedTagMessage link : page.messages) {
                    MessageObject message = known.get(link.messageId);
                    if (canTagMessage(message)) {
                        resolved.put(link.messageId, message);
                    } else {
                        pending.add(link.messageId);
                    }
                }
                loadBodies(query);
            });
        }

        private void loadBodies(int query) {
            if (pending.isEmpty()) {
                finish(null);
                return;
            }
            ArrayList<Integer> ids = new ArrayList<>(pending);
            messageSource.loadCached(ids, (cached, failure) -> {
                if (!isCurrent(query)) {
                    return;
                }
                if (cached != null) {
                    accept(cached, ids, false);
                }
                if (pending.isEmpty()) {
                    finish(null);
                } else {
                    loadRemote(query);
                }
            });
        }

        private void loadRemote(int query) {
            ArrayList<Integer> ids = new ArrayList<>();
            for (Integer id : pending) {
                ids.add(id);
                if (ids.size() == 100) {
                    break;
                }
            }
            requestId = messageSource.loadRemote(ids, classGuid, (response, failure) -> {
                if (!isCurrent(query)) {
                    return;
                }
                requestId = 0;
                if (failure != null) {
                    finish(failure);
                    return;
                }
                ArrayList<Integer> missing = accept(response, ids, true);
                if (missing.isEmpty()) {
                    finish(pending.isEmpty() ? null : new IOException("部分收藏消息尚未加载，请重试"));
                } else {
                    // 只把本次请求明确返回的空消息当作删除，缺项和网络错误均保留关联。
                    storage.removeMessages(missing, (ignored, removeError) -> {
                        if (!isCurrent(query)) {
                            return;
                        }
                        if (removeError != null) {
                            finish(removeError);
                            return;
                        }
                        for (int id : missing) {
                            pending.remove(id);
                            known.remove(id);
                            resolved.remove(id);
                        }
                        for (int i = links.size() - 1; i >= 0; i--) {
                            if (missing.contains(links.get(i).messageId)) {
                                links.remove(i);
                            }
                        }
                        invalidateMessageTags();
                        loadTags((tags, readError) -> {
                            if (isCurrent(query)) {
                                finish(pending.isEmpty() ? null : new IOException("部分收藏消息尚未加载，请重试"));
                                getNotificationCenter().postNotificationName(NotificationCenter.localSavedTagsUpdated, userId, this);
                            }
                        });
                    });
                }
            });
        }

        private ArrayList<Integer> accept(TLRPC.messages_Messages response, List<Integer> requested, boolean remote) {
            ArrayList<Integer> missing = new ArrayList<>();
            ArrayList<TLRPC.Message> cache = new ArrayList<>();
            getMessagesController().putUsers(response.users, !remote);
            getMessagesController().putChats(response.chats, !remote);
            for (TLRPC.Message message : response.messages) {
                if (!requested.contains(message.id) || !pending.contains(message.id)) {
                    continue;
                }
                if (message instanceof TLRPC.TL_messageEmpty) {
                    if (remote && (message.peer_id == null || message.peer_id.user_id == userId)) {
                        missing.add(message.id);
                    }
                    continue;
                }
                if (message.peer_id == null || message.peer_id.user_id != userId) {
                    continue;
                }
                MessageObject object = new MessageObject(currentAccount, message, true, true);
                if (canTagMessage(object)) {
                    resolved.put(message.id, object);
                    pending.remove(message.id);
                    cache.add(message);
                }
            }
            if (remote && !cache.isEmpty()) {
                getMessagesStorage().putUsersAndChats(response.users, response.chats, true, true);
                getMessagesStorage().putMessages(cache, true, true, false, 0, false, 0, 0);
            }
            return missing;
        }

        private boolean isCurrent(int query) {
            return !cancelled && isActive() && version == query;
        }

        private void finish(Exception failure) {
            if (tagId == UNTAGGED) {
                Collections.sort(historyMessages, (a, b) -> {
                    int date = Integer.compare(b.messageOwner.date, a.messageOwner.date);
                    return date != 0 ? date : Integer.compare(b.getId(), a.getId());
                });
            }
            loading = false;
            error = failure;
            onChanged.run();
        }

        private void scanHistory(int query, int batches, int targetCount) {
            while (!historyBuffer.isEmpty() && historyMessages.size() < targetCount) {
                MessageObject message = historyBuffer.removeFirst();
                if (seenHistory.add(message.getId())) {
                    historyMessages.add(message);
                }
            }
            if ((historyMessages.size() >= targetCount || historyEnded) && !historyNeedsConfirmation) {
                finish(null);
                return;
            }
            if (batches == 5) {
                scanLimitReached = true;
                finish(null);
                return;
            }
            TLRPC.TL_messages_getHistory request = new TLRPC.TL_messages_getHistory();
            request.peer = getMessagesController().getInputPeer(userId);
            request.limit = 100;
            request.offset_id = historyBefore;
            requestId = messageSource.loadHistory(request, classGuid, (response, failure) -> {
                if (!isCurrent(query)) {
                    return;
                }
                requestId = 0;
                if (failure != null) {
                    loadCachedHistory(query, targetCount, failure);
                    return;
                }
                int next = Integer.MAX_VALUE;
                for (TLRPC.Message message : response.messages) {
                    if (message.id > 0 && (message.peer_id != null && message.peer_id.user_id == userId
                            || message instanceof TLRPC.TL_messageEmpty && message.peer_id == null)) {
                        next = Math.min(next, message.id);
                    }
                }
                if (!response.messages.isEmpty() && (next == Integer.MAX_VALUE || historyBefore != 0 && next >= historyBefore)) {
                    finish(new IOException("收藏历史没有向前推进，请重试"));
                    return;
                }
                int nextBefore = next;
                ArrayList<MessageObject> messages = historyObjects(response, true);
                ArrayList<Integer> ids = new ArrayList<>();
                for (MessageObject message : messages) {
                    ids.add(message.getId());
                }
                storage.loadMessageTags(ids, (mapping, tagError) -> {
                    if (!isCurrent(query)) {
                        return;
                    }
                    if (tagError != null) {
                        finish(tagError);
                        return;
                    }
                    historyNeedsConfirmation = false;
                    for (MessageObject message : messages) {
                        if (mapping.get(message.getId()).isEmpty() && !seenHistory.contains(message.getId())) {
                            historyBuffer.addLast(message);
                        }
                    }
                    // 游标跟随原始批次，不能使用排除已打标消息后的最后一个编号。
                    if (nextBefore != Integer.MAX_VALUE) {
                        historyBefore = nextBefore;
                    }
                    historyEnded = response instanceof TLRPC.TL_messages_messages || response.messages.size() < 100;
                    scanHistory(query, batches + 1, targetCount);
                });
            });
        }

        private void loadCachedHistory(int query, int targetCount, Exception failure) {
            messageSource.loadCachedHistory(historyBefore, (response, cacheError) -> {
                if (!isCurrent(query)) {
                    return;
                }
                cacheOnly = cacheError == null;
                historyNeedsConfirmation = cacheOnly;
                if (cacheError != null) {
                    finish(failure);
                    return;
                }
                ArrayList<MessageObject> messages = historyObjects(response, false);
                ArrayList<Integer> ids = new ArrayList<>();
                for (MessageObject message : messages) {
                    ids.add(message.getId());
                }
                storage.loadMessageTags(ids, (mapping, tagError) -> {
                    if (!isCurrent(query)) {
                        return;
                    }
                    if (tagError == null) {
                        for (MessageObject message : messages) {
                            if (historyMessages.size() >= targetCount) {
                                break;
                            }
                            if (mapping.get(message.getId()).isEmpty() && seenHistory.add(message.getId())) {
                                historyMessages.add(message);
                            }
                        }
                    }
                    // 缓存不是完整历史，不推进远端游标，也不把缓存终点当作查找结束。
                    finish(tagError == null ? failure : tagError);
                });
            });
        }

        private ArrayList<MessageObject> historyObjects(TLRPC.messages_Messages response, boolean remote) {
            ArrayList<MessageObject> messages = new ArrayList<>();
            ArrayList<TLRPC.Message> cache = new ArrayList<>();
            getMessagesController().putUsers(response.users, !remote);
            getMessagesController().putChats(response.chats, !remote);
            for (TLRPC.Message message : response.messages) {
                if (message.peer_id == null || message.peer_id.user_id != userId) {
                    continue;
                }
                MessageObject object = new MessageObject(currentAccount, message, true, true);
                if (canTagMessage(object)) {
                    messages.add(object);
                    cache.add(message);
                }
            }
            if (remote && !cache.isEmpty()) {
                getMessagesStorage().putUsersAndChats(response.users, response.chats, true, true);
                getMessagesStorage().putMessages(cache, true, true, false, 0, false, 0, 0);
            }
            Collections.sort(messages, (a, b) -> {
                int date = Integer.compare(b.messageOwner.date, a.messageOwner.date);
                return date != 0 ? date : Integer.compare(b.getId(), a.getId());
            });
            return messages;
        }

        public void cancel() {
            cancelled = true;
            version++;
            loading = false;
            if (requestId != 0) {
                messageSource.cancel(requestId);
                requestId = 0;
            }
            filterSessions.remove(this);
        }
    }
}
