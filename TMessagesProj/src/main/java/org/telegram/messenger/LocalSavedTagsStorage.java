package org.telegram.messenger;

import android.util.SparseArray;
import android.util.SparseIntArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.IOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;

public class LocalSavedTagsStorage {

    public static final int PAGE_SIZE = 50;
    private static final int QUERY_BATCH_SIZE = 500;

    static final String CREATE_TAGS_SQL = "CREATE TABLE local_saved_tags ("
            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
            + "name TEXT NOT NULL CHECK(length(name) > 0), "
            + "name_key TEXT NOT NULL UNIQUE CHECK(length(name_key) > 0), "
            + "created_at INTEGER NOT NULL)";
    static final String CREATE_MESSAGE_TAGS_SQL = "CREATE TABLE local_saved_message_tags ("
            + "message_id INTEGER NOT NULL CHECK(message_id > 0), "
            + "tag_id INTEGER NOT NULL, message_date INTEGER NOT NULL, "
            + "PRIMARY KEY (message_id, tag_id), "
            + "FOREIGN KEY (tag_id) REFERENCES local_saved_tags(id) ON DELETE CASCADE)";
    static final String CREATE_MESSAGE_TAGS_INDEX_SQL = "CREATE INDEX local_saved_message_tags_by_tag "
            + "ON local_saved_message_tags(tag_id, message_date DESC, message_id DESC)";
    static final String INSERT_TAG_SQL = "INSERT INTO local_saved_tags(name, name_key, created_at) VALUES (?, ?, ?)";
    static final String RENAME_TAG_SQL = "UPDATE local_saved_tags SET name = ?, name_key = ? WHERE id = ?";
    static final String DELETE_TAG_SQL = "DELETE FROM local_saved_tags WHERE id = ?";
    static final String ADD_MESSAGE_TAG_SQL = "INSERT INTO local_saved_message_tags(message_id, tag_id, message_date) "
            + "VALUES (?, ?, ?) ON CONFLICT(message_id, tag_id) DO NOTHING";
    static final String REMOVE_MESSAGE_TAG_SQL = "DELETE FROM local_saved_message_tags WHERE message_id = ? AND tag_id = ?";
    static final String TAG_SELECT_SQL = "SELECT t.id, t.name, t.name_key, t.created_at, COUNT(mt.message_id) "
            + "FROM local_saved_tags t LEFT JOIN local_saved_message_tags mt ON mt.tag_id = t.id ";
    static final String SELECT_TAGS_SQL = TAG_SELECT_SQL
            + "GROUP BY t.id ORDER BY COUNT(mt.message_id) DESC, t.created_at DESC, t.id DESC";
    static final String SELECT_TAG_SQL = TAG_SELECT_SQL + "WHERE t.id = ? GROUP BY t.id";
    static final String SELECT_MESSAGES_SQL = "SELECT message_id, message_date FROM local_saved_message_tags "
            + "WHERE tag_id = ? ORDER BY message_date DESC, message_id DESC LIMIT ?";
    static final String SELECT_OLDER_MESSAGES_SQL = "SELECT message_id, message_date FROM local_saved_message_tags "
            + "WHERE tag_id = ? AND (message_date, message_id) < (?, ?) "
            + "ORDER BY message_date DESC, message_id DESC LIMIT ?";
    static final String SELECT_MESSAGE_TAGS_SQL = "SELECT mt.message_id, t.id, t.name, t.name_key, t.created_at "
            + "FROM local_saved_message_tags mt JOIN local_saved_tags t ON t.id = mt.tag_id "
            + "WHERE mt.message_id IN (";
    static final String MESSAGE_TAGS_ORDER_SQL = ") ORDER BY mt.message_id, t.created_at DESC, t.id DESC";

    public static class TagException extends Exception {
        public static final int INVALID_NAME = 1;
        public static final int NAME_EXISTS = 2;
        public static final int TAG_NOT_FOUND = 3;

        public final int code;

        private TagException(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    public static class MessagePage {
        public final ArrayList<LocalSavedTagMessage> messages = new ArrayList<>();
        public boolean hasMore;
    }

    private interface DatabaseTask<T> {
        T run() throws Exception;
    }

    private final long userId;
    private final File databaseFile;
    private final DispatchQueue storageQueue;
    private final ArrayList<Runnable> closeCallbacks = new ArrayList<>();
    private SQLiteDatabase database;
    private boolean closing;
    private boolean closed;

    public LocalSavedTagsStorage(int currentAccount) {
        this(ApplicationLoader.applicationContext.getNoBackupFilesDir(), getAccountUserId(currentAccount),
                ConnectionsManager.getInstance(currentAccount).isTestBackend());
    }

    LocalSavedTagsStorage(File noBackupDir, long userId, boolean testBackend) {
        if (userId <= 0) {
            throw new IllegalArgumentException("本地标签需要已登录的真实账号");
        }
        this.userId = userId;
        File directory = new File(noBackupDir, "local_saved_tags/" + (testBackend ? "test" : "prod"));
        databaseFile = new File(directory, userId + ".db");
        storageQueue = new DispatchQueue("localSavedTags_" + userId + (testBackend ? "_test" : ""));
    }

    private static long getAccountUserId(int currentAccount) {
        if (currentAccount < 0 || currentAccount >= UserConfig.MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("无效的账号槽位");
        }
        UserConfig config = UserConfig.getInstance(currentAccount);
        long id = config.getClientUserId();
        if (!config.isClientActivated() || id <= 0) {
            throw new IllegalStateException("账号尚未登录");
        }
        return id;
    }

    public long getUserId() {
        return userId;
    }

    File getDatabaseFile() {
        return databaseFile;
    }

    public void loadTags(Utilities.Callback2<ArrayList<LocalSavedTag>, Exception> callback) {
        postTask(false, () -> {
            ArrayList<LocalSavedTag> tags = new ArrayList<>();
            SQLiteCursor cursor = database.queryFinalized(SELECT_TAGS_SQL);
            try {
                while (cursor.next()) {
                    LocalSavedTag tag = readTag(cursor, 0);
                    tag.messageCount = cursor.intValue(4);
                    tags.add(tag);
                }
            } finally {
                cursor.dispose();
            }
            return tags;
        }, callback);
    }

    public void createTag(String name, Utilities.Callback2<LocalSavedTag, Exception> callback) {
        postTask(true, () -> {
            String normalized = normalizeName(name);
            String key = normalized.toLowerCase(Locale.ROOT);
            checkNameAvailable(key, 0);
            execute(INSERT_TAG_SQL, normalized, key, System.currentTimeMillis());
            return readTag(readLong("SELECT last_insert_rowid()"));
        }, callback);
    }

    public void renameTag(long tagId, String name, Utilities.Callback2<LocalSavedTag, Exception> callback) {
        postTask(true, () -> {
            String normalized = normalizeName(name);
            String key = normalized.toLowerCase(Locale.ROOT);
            readTag(tagId);
            checkNameAvailable(key, tagId);
            execute(RENAME_TAG_SQL, normalized, key, tagId);
            return readTag(tagId);
        }, callback);
    }

    public void deleteTag(long tagId, Utilities.Callback2<Void, Exception> callback) {
        postTask(true, () -> {
            if (tagId <= 0) {
                throw new IllegalArgumentException("无效的标签编号");
            }
            execute(DELETE_TAG_SQL, tagId);
            return null;
        }, callback);
    }

    public void applyTags(SparseIntArray messageDates, Collection<Long> addTagIds, Collection<Long> removeTagIds,
                          Utilities.Callback2<Void, Exception> callback) {
        // 入队前固定输入，避免界面修改选择集合后影响正在等待的操作。
        SparseIntArray messages = messageDates == null ? null : messageDates.clone();
        HashSet<Long> added = addTagIds == null ? null : new HashSet<>(addTagIds);
        HashSet<Long> removed = removeTagIds == null ? null : new HashSet<>(removeTagIds);
        postTask(true, () -> {
            if (messages == null || added == null || removed == null) {
                throw new IllegalArgumentException("消息和标签集合不能为空");
            }
            for (int i = 0; i < messages.size(); i++) {
                if (messages.keyAt(i) <= 0) {
                    throw new IllegalArgumentException("只能标记已保存的收藏消息");
                }
            }
            for (Long id : added) {
                if (id == null || id <= 0 || removed.contains(id)) {
                    throw new IllegalArgumentException("无效或相互冲突的标签操作");
                }
            }
            for (Long id : removed) {
                if (id == null || id <= 0) {
                    throw new IllegalArgumentException("无效的标签编号");
                }
            }
            if (messages.size() == 0 || added.isEmpty() && removed.isEmpty()) {
                return null;
            }

            SQLitePreparedStatement statement = database.executeFast(REMOVE_MESSAGE_TAG_SQL);
            try {
                for (int i = 0; i < messages.size(); i++) {
                    for (long id : removed) {
                        statement.requery();
                        statement.bindInteger(1, messages.keyAt(i));
                        statement.bindLong(2, id);
                        step(statement);
                    }
                }
            } finally {
                statement.dispose();
            }
            statement = database.executeFast(ADD_MESSAGE_TAG_SQL);
            try {
                for (int i = 0; i < messages.size(); i++) {
                    for (long id : added) {
                        statement.requery();
                        statement.bindInteger(1, messages.keyAt(i));
                        statement.bindLong(2, id);
                        statement.bindInteger(3, messages.valueAt(i));
                        step(statement);
                    }
                }
            } finally {
                statement.dispose();
            }
            return null;
        }, callback);
    }

    public void loadMessageTags(Collection<Integer> messageIds,
                                Utilities.Callback2<SparseArray<ArrayList<LocalSavedTag>>, Exception> callback) {
        ArrayList<Integer> ids = messageIds == null ? null : new ArrayList<>(new HashSet<>(messageIds));
        postTask(false, () -> {
            if (ids == null) {
                throw new IllegalArgumentException("消息编号集合不能为空");
            }
            SparseArray<ArrayList<LocalSavedTag>> result = new SparseArray<>();
            for (Integer id : ids) {
                if (id == null || id <= 0) {
                    throw new IllegalArgumentException("无效的收藏消息编号");
                }
                result.put(id, new ArrayList<>());
            }
            for (int start = 0; start < ids.size(); start += QUERY_BATCH_SIZE) {
                int count = Math.min(QUERY_BATCH_SIZE, ids.size() - start);
                StringBuilder sql = new StringBuilder(SELECT_MESSAGE_TAGS_SQL);
                Object[] args = new Object[count];
                for (int i = 0; i < count; i++) {
                    if (i != 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                    args[i] = ids.get(start + i);
                }
                sql.append(MESSAGE_TAGS_ORDER_SQL);
                SQLiteCursor cursor = database.queryFinalized(sql.toString(), args);
                try {
                    while (cursor.next()) {
                        result.get(cursor.intValue(0)).add(readTag(cursor, 1));
                    }
                } finally {
                    cursor.dispose();
                }
            }
            return result;
        }, callback);
    }

    public void loadMessages(long tagId, LocalSavedTagMessage before, int limit,
                             Utilities.Callback2<MessagePage, Exception> callback) {
        boolean firstPage = before == null;
        int beforeDate = firstPage ? 0 : before.messageDate;
        int beforeId = firstPage ? 0 : before.messageId;
        long beforeTagId = firstPage ? tagId : before.tagId;
        postTask(false, () -> {
            if (tagId <= 0 || limit < 1 || limit > PAGE_SIZE || !firstPage && (beforeId <= 0 || beforeTagId != tagId)) {
                throw new IllegalArgumentException("无效的标签分页参数");
            }
            SQLiteCursor cursor = firstPage
                    ? database.queryFinalized(SELECT_MESSAGES_SQL, tagId, limit + 1)
                    : database.queryFinalized(SELECT_OLDER_MESSAGES_SQL, tagId, beforeDate, beforeId, limit + 1);
            MessagePage page = new MessagePage();
            try {
                while (cursor.next()) {
                    if (page.messages.size() == limit) {
                        page.hasMore = true;
                        break;
                    }
                    LocalSavedTagMessage message = new LocalSavedTagMessage();
                    message.messageId = cursor.intValue(0);
                    message.messageDate = cursor.intValue(1);
                    message.tagId = tagId;
                    page.messages.add(message);
                }
            } finally {
                cursor.dispose();
            }
            return page;
        }, callback);
    }

    public void close() {
        close(null);
    }

    public void removeMessages(Collection<Integer> messageIds, Utilities.Callback2<Void, Exception> callback) {
        ArrayList<Integer> ids = new ArrayList<>(new HashSet<>(messageIds));
        postTask(true, () -> {
            SQLitePreparedStatement statement = database.executeFast("DELETE FROM local_saved_message_tags WHERE message_id = ?");
            try {
                for (Integer id : ids) {
                    if (id == null || id <= 0) {
                        throw new IllegalArgumentException("无效的收藏消息编号");
                    }
                    statement.requery();
                    statement.bindInteger(1, id);
                    step(statement);
                }
            } finally {
                statement.dispose();
            }
            return null;
        }, callback);
    }

    public void removeHistory(int maxId, Utilities.Callback2<Void, Exception> callback) {
        postTask(true, () -> {
            if (maxId <= 0 || maxId == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("历史清理需要确定的消息上限");
            }
            SQLitePreparedStatement statement = database.executeFast("DELETE FROM local_saved_message_tags WHERE message_id <= ?");
            try {
                statement.bindInteger(1, maxId);
                step(statement);
            } finally {
                statement.dispose();
            }
            return null;
        }, callback);
    }

    public void loadHistoryIds(int maxId, int minDate, int maxDate, Utilities.Callback2<ArrayList<Integer>, Exception> callback) {
        postTask(false, () -> {
            if (maxId <= 0 || minDate < 0 || maxDate < 0 || maxDate != 0 && minDate > maxDate) {
                throw new IllegalArgumentException("无效的收藏历史范围");
            }
            ArrayList<Integer> result = new ArrayList<>();
            SQLiteCursor cursor = database.queryFinalized("SELECT DISTINCT message_id FROM local_saved_message_tags "
                            + "WHERE message_id <= ? AND (? = 0 OR message_date >= ?) AND (? = 0 OR message_date <= ?) ORDER BY message_id",
                    maxId, minDate, minDate, maxDate, maxDate);
            try {
                while (cursor.next()) {
                    result.add(cursor.intValue(0));
                }
            } finally {
                cursor.dispose();
            }
            return result;
        }, callback);
    }

    public synchronized void close(Runnable callback) {
        if (closed) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(callback);
            }
            return;
        }
        if (callback != null) {
            closeCallbacks.add(callback);
        }
        if (closing) {
            return;
        }
        closing = true;
        storageQueue.postRunnable(() -> {
            closeDatabase();
            ArrayList<Runnable> callbacks;
            synchronized (this) {
                closed = true;
                callbacks = new ArrayList<>(closeCallbacks);
                closeCallbacks.clear();
            }
            storageQueue.recycle();
            for (Runnable runnable : callbacks) {
                AndroidUtilities.runOnUIThread(runnable);
            }
        });
    }

    private synchronized <T> void postTask(boolean write, DatabaseTask<T> task, Utilities.Callback2<T, Exception> callback) {
        if (closing) {
            deliver(callback, null, new IllegalStateException("本地标签存储已关闭"));
            return;
        }
        boolean posted = storageQueue.postRunnable(() -> {
            T result = null;
            Exception error = null;
            try {
                openDatabase();
                result = write ? transaction("local_tags_write", task) : task.run();
            } catch (Exception e) {
                error = e;
                if (e instanceof SQLiteException) {
                    closeDatabase();
                }
            }
            deliver(callback, result, error);
        });
        if (!posted) {
            deliver(callback, null, new IllegalStateException("本地标签存储队列不可用"));
        }
    }

    private static <T> void deliver(Utilities.Callback2<T, Exception> callback, T result, Exception error) {
        AndroidUtilities.runOnUIThread(() -> callback.run(result, error));
    }

    private void openDatabase() throws Exception {
        if (database != null) {
            return;
        }
        File directory = databaseFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建本地标签目录");
        }
        database = new SQLiteDatabase(databaseFile.getPath());
        try {
            long version = readLong("PRAGMA user_version");
            if (version == 0) {
                if (readLong("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") != 0) {
                    throw new SQLiteException("本地标签数据库版本未知，已保留原文件");
                }
            } else if (version == 1) {
                checkSchema();
            } else {
                throw new SQLiteException("不支持的本地标签数据库版本：" + version);
            }

            SQLiteCursor cursor = database.queryFinalized("PRAGMA journal_mode = WAL");
            try {
                if (!cursor.next() || !"wal".equalsIgnoreCase(cursor.stringValue(0))) {
                    throw new SQLiteException("本地标签数据库未启用 WAL");
                }
            } finally {
                cursor.dispose();
            }
            execute("PRAGMA synchronous = FULL");
            execute("PRAGMA foreign_keys = ON");
            if (readLong("PRAGMA synchronous") != 2 || readLong("PRAGMA foreign_keys") != 1) {
                throw new SQLiteException("本地标签数据库连接参数不正确");
            }
            if (version == 0) {
                transaction("local_tags_schema", () -> {
                    execute(CREATE_TAGS_SQL);
                    execute(CREATE_MESSAGE_TAGS_SQL);
                    execute(CREATE_MESSAGE_TAGS_INDEX_SQL);
                    execute("PRAGMA user_version = 1");
                    return null;
                });
            }
        } catch (Exception e) {
            closeDatabase();
            throw e;
        }
    }

    private void checkSchema() throws SQLiteException {
        String[] names = {"local_saved_tags", "local_saved_message_tags", "local_saved_message_tags_by_tag"};
        String[] expected = {CREATE_TAGS_SQL, CREATE_MESSAGE_TAGS_SQL, CREATE_MESSAGE_TAGS_INDEX_SQL};
        for (int i = 0; i < names.length; i++) {
            SQLiteCursor cursor = database.queryFinalized("SELECT sql FROM sqlite_master WHERE name = ?", names[i]);
            try {
                // 首版结构固定；未知结构必须报错，不能按消息缓存的方式删库重建。
                if (!cursor.next() || cursor.isNull(0)
                        || !cursor.stringValue(0).replaceAll("\\s+", "").replace(";", "")
                        .equalsIgnoreCase(expected[i].replaceAll("\\s+", "").replace(";", ""))) {
                    throw new SQLiteException("本地标签数据库结构不匹配：" + names[i]);
                }
            } finally {
                cursor.dispose();
            }
        }
    }

    private <T> T transaction(String name, DatabaseTask<T> task) throws Exception {
        execute("SAVEPOINT " + name);
        try {
            T result = task.run();
            execute("RELEASE SAVEPOINT " + name);
            return result;
        } catch (Exception e) {
            try {
                execute("ROLLBACK TO SAVEPOINT " + name);
                execute("RELEASE SAVEPOINT " + name);
            } catch (Exception rollbackError) {
                e.addSuppressed(rollbackError);
                closeDatabase();
            }
            throw e;
        }
    }

    private void closeDatabase() {
        if (database != null) {
            database.close();
            database = null;
        }
    }

    private void execute(String sql, Object... args) throws SQLiteException {
        SQLitePreparedStatement statement = database.executeFast(sql);
        try {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof String) {
                    statement.bindString(i + 1, (String) value);
                } else if (value instanceof Long) {
                    statement.bindLong(i + 1, (Long) value);
                } else if (value instanceof Integer) {
                    statement.bindInteger(i + 1, (Integer) value);
                } else {
                    throw new IllegalArgumentException("不支持的本地标签数据库参数");
                }
            }
            step(statement);
        } finally {
            statement.dispose();
        }
    }

    private static void step(SQLitePreparedStatement statement) throws SQLiteException {
        int result = statement.step();
        if (result != 1) {
            // JNI 把 SQLITE_BUSY 转为 -1，不能使用忽略返回值的 stepThis()。
            throw new SQLiteException(result == -1 ? 5 : 0, "本地标签写入未完成：" + result);
        }
    }

    private long readLong(String sql) throws SQLiteException {
        SQLiteCursor cursor = database.queryFinalized(sql);
        try {
            if (!cursor.next()) {
                throw new SQLiteException("本地标签数据库未返回查询结果");
            }
            return cursor.longValue(0);
        } finally {
            cursor.dispose();
        }
    }

    private LocalSavedTag readTag(long id) throws Exception {
        SQLiteCursor cursor = database.queryFinalized(SELECT_TAG_SQL, id);
        try {
            if (!cursor.next()) {
                throw new TagException(TagException.TAG_NOT_FOUND, "标签不存在");
            }
            LocalSavedTag tag = readTag(cursor, 0);
            tag.messageCount = cursor.intValue(4);
            return tag;
        } finally {
            cursor.dispose();
        }
    }

    private static LocalSavedTag readTag(SQLiteCursor cursor, int offset) throws SQLiteException {
        LocalSavedTag tag = new LocalSavedTag();
        tag.id = cursor.longValue(offset);
        tag.name = cursor.stringValue(offset + 1);
        tag.nameKey = cursor.stringValue(offset + 2);
        tag.createdAt = cursor.longValue(offset + 3);
        return tag;
    }

    private void checkNameAvailable(String key, long exceptId) throws Exception {
        SQLiteCursor cursor = database.queryFinalized("SELECT id FROM local_saved_tags WHERE name_key = ? AND id != ?", key, exceptId);
        try {
            if (cursor.next()) {
                throw new TagException(TagException.NAME_EXISTS, "标签名称已存在");
            }
        } finally {
            cursor.dispose();
        }
    }

    private static String normalizeName(String name) throws TagException {
        if (name == null || name.indexOf('\n') >= 0 || name.indexOf('\r') >= 0
                || name.indexOf(0x2028) >= 0 || name.indexOf(0x2029) >= 0) {
            throw new TagException(TagException.INVALID_NAME, "标签名称不能为空或包含换行");
        }
        int start = 0;
        int end = name.length();
        while (start < end) {
            int codePoint = name.codePointAt(start);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (end > start) {
            int codePoint = name.codePointBefore(end);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        String result = Normalizer.normalize(name.substring(start, end), Normalizer.Form.NFC);
        int length = result.codePointCount(0, result.length());
        if (length < 1 || length > 32) {
            throw new TagException(TagException.INVALID_NAME, "标签名称需要 1～32 个字符");
        }
        return result;
    }
}
