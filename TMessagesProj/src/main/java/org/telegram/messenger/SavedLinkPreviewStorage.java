package org.telegram.messenger;

import android.util.SparseArray;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.tgnet.ConnectionsManager;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;

public class SavedLinkPreviewStorage {

    static final String CREATE_TABLE = "CREATE TABLE saved_link_references ("
            + "saved_message_id INTEGER PRIMARY KEY CHECK(saved_message_id > 0 AND saved_message_id <= 2147483647), "
            + "content_signature TEXT NOT NULL CHECK(length(content_signature) = 64), "
            + "original_url TEXT, username TEXT, channel_id INTEGER CHECK(channel_id > 0), "
            + "source_message_id INTEGER CHECK(source_message_id > 0 AND source_message_id <= 2147483647), "
            + "parse_state INTEGER NOT NULL CHECK(parse_state IN (0, 1, 2)), "
            + "source_dialog_id INTEGER CHECK(source_dialog_id < 0), "
            + "state INTEGER NOT NULL CHECK(state IN (0, 1, 2, 3)), "
            + "last_success_at INTEGER NOT NULL CHECK(last_success_at >= 0), "
            + "CHECK((parse_state = 2 AND original_url IS NOT NULL AND source_message_id IS NOT NULL "
            + "AND ((username IS NULL AND channel_id IS NOT NULL) OR (username IS NOT NULL AND channel_id IS NULL))) "
            + "OR (parse_state <> 2 AND username IS NULL AND channel_id IS NULL AND source_message_id IS NULL "
            + "AND source_dialog_id IS NULL AND state = 0 AND last_success_at = 0)), "
            + "CHECK(source_dialog_id IS NOT NULL OR (state <> 1 AND last_success_at = 0)), "
            + "CHECK(channel_id IS NULL OR source_dialog_id IS NULL OR source_dialog_id = -channel_id))";
    static final String CREATE_INDEX = "CREATE INDEX saved_link_references_by_source "
            + "ON saved_link_references(source_dialog_id, source_message_id)";
    static final String COLUMNS = "saved_message_id, content_signature, original_url, username, channel_id, "
            + "source_message_id, parse_state, source_dialog_id, state, last_success_at";
    private static final String REPLACE = "REPLACE INTO saved_link_references(" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private interface DatabaseTask<T> {
        T run() throws Exception;
    }

    private final long userId;
    private final boolean testBackend;
    private final File databaseFile;
    private final DispatchQueue storageQueue;
    private final ArrayList<Runnable> closeCallbacks = new ArrayList<>();
    private SQLiteDatabase database;
    private boolean closing;
    private boolean closed;

    public SavedLinkPreviewStorage(int account) {
        this(ApplicationLoader.applicationContext.getNoBackupFilesDir(), accountUserId(account),
                ConnectionsManager.getInstance(account).isTestBackend());
    }

    SavedLinkPreviewStorage(File root, long userId, boolean testBackend) {
        if (userId <= 0) {
            throw new IllegalArgumentException("链接预览需要已登录的账号");
        }
        this.userId = userId;
        this.testBackend = testBackend;
        databaseFile = new File(root, "saved_link_previews/" + (testBackend ? "test/" : "prod/") + userId + ".db");
        storageQueue = new DispatchQueue("savedLinks_" + userId + (testBackend ? "_test" : ""));
    }

    private static long accountUserId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            throw new IllegalArgumentException("无效的账号槽位");
        }
        UserConfig config = UserConfig.getInstance(account);
        if (!config.isClientActivated() || config.getClientUserId() <= 0) {
            throw new IllegalStateException("链接预览账号尚未登录");
        }
        return config.getClientUserId();
    }

    public long getUserId() {
        return userId;
    }

    boolean isTestBackend() {
        return testBackend;
    }

    File getDatabaseFile() {
        return databaseFile;
    }

    public void load(Collection<Integer> messageIds, Utilities.Callback2<SparseArray<SavedLinkReference>, Exception> callback) {
        ArrayList<Integer> ids = messageIds == null ? null : new ArrayList<>(new HashSet<>(messageIds));
        post(false, () -> {
            checkIds(ids);
            SparseArray<SavedLinkReference> result = new SparseArray<>();
            for (int start = 0; start < ids.size(); start += 500) {
                int count = Math.min(500, ids.size() - start);
                StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM saved_link_references WHERE saved_message_id IN (");
                Object[] args = new Object[count];
                for (int i = 0; i < count; i++) {
                    if (i > 0) {
                        sql.append(',');
                    }
                    sql.append('?');
                    args[i] = ids.get(start + i);
                }
                SQLiteCursor cursor = database.queryFinalized(sql.append(')').toString(), args);
                try {
                    while (cursor.next()) {
                        SavedLinkReference reference = read(cursor);
                        result.put(reference.savedMessageId, reference);
                    }
                } finally {
                    cursor.dispose();
                }
            }
            return result;
        }, callback);
    }

    public void save(Collection<SavedLinkReference> references, Utilities.Callback2<Void, Exception> callback) {
        ArrayList<SavedLinkReference> values = references == null ? null : new ArrayList<>(references);
        post(true, () -> {
            if (values == null) {
                throw new IllegalArgumentException("引用集合不能为空");
            }
            for (SavedLinkReference value : values) {
                validate(value);
                execute(REPLACE, value.savedMessageId, value.contentSignature, value.originalUrl, value.username,
                        value.channelId == 0 ? null : value.channelId,
                        value.sourceMessageId == 0 ? null : value.sourceMessageId, value.parseState,
                        value.sourceDialogId == 0 ? null : value.sourceDialogId, value.state, value.lastSuccessAt);
            }
            return null;
        }, callback);
    }

    public void findBySource(long dialogId, int messageId, Utilities.Callback2<ArrayList<SavedLinkReference>, Exception> callback) {
        post(false, () -> {
            if (dialogId >= 0 || messageId <= 0) {
                throw new IllegalArgumentException("无效的来源消息身份");
            }
            ArrayList<SavedLinkReference> result = new ArrayList<>();
            SQLiteCursor cursor = database.queryFinalized("SELECT " + COLUMNS
                    + " FROM saved_link_references WHERE source_dialog_id = ? AND source_message_id = ? ORDER BY saved_message_id",
                    dialogId, messageId);
            try {
                while (cursor.next()) {
                    result.add(read(cursor));
                }
            } finally {
                cursor.dispose();
            }
            return result;
        }, callback);
    }

    public void update(Collection<SavedLinkReference> references, Utilities.Callback2<Void, Exception> callback) {
        ArrayList<SavedLinkReference> values = new ArrayList<>(references);
        post(true, () -> {
            for (SavedLinkReference value : values) {
                validate(value);
                // 迟到的状态提交不能重建已删除引用，也不能覆盖编辑后的定位。
                execute("UPDATE saved_link_references SET source_dialog_id = ?, state = ?, last_success_at = ? "
                                + "WHERE saved_message_id = ? AND content_signature = ?",
                        value.sourceDialogId == 0 ? null : value.sourceDialogId, value.state, value.lastSuccessAt,
                        value.savedMessageId, value.contentSignature);
            }
            return null;
        }, callback);
    }

    public void updateSource(long dialogId, int messageId, int state, long successAt, Utilities.Callback2<Void, Exception> callback) {
        post(true, () -> {
            if (dialogId >= 0 || messageId < 0 || state < SavedLinkReference.PENDING || state > SavedLinkReference.FAILED) {
                throw new IllegalArgumentException("无效的来源状态");
            }
            String where = " WHERE source_dialog_id = ?" + (messageId == 0 ? "" : " AND source_message_id = ?");
            String sql = "UPDATE saved_link_references SET state = ?"
                    + (successAt < 0 ? "" : ", last_success_at = ?") + where;
            ArrayList<Object> args = new ArrayList<>();
            args.add(state);
            if (successAt >= 0) {
                args.add(successAt);
            }
            args.add(dialogId);
            if (messageId != 0) {
                args.add(messageId);
            }
            execute(sql, args.toArray());
            return null;
        }, callback);
    }

    public void remove(Collection<Integer> messageIds, Utilities.Callback2<Void, Exception> callback) {
        ArrayList<Integer> ids = messageIds == null ? null : new ArrayList<>(messageIds);
        post(true, () -> {
            checkIds(ids);
            for (int id : ids) {
                execute("DELETE FROM saved_link_references WHERE saved_message_id = ?", id);
            }
            return null;
        }, callback);
    }

    public void loadIds(int maxId, Utilities.Callback2<ArrayList<Integer>, Exception> callback) {
        post(false, () -> {
            ArrayList<Integer> result = new ArrayList<>();
            SQLiteCursor cursor = database.queryFinalized("SELECT saved_message_id FROM saved_link_references "
                    + "WHERE saved_message_id <= ? ORDER BY saved_message_id", maxId);
            try {
                while (cursor.next()) result.add(cursor.intValue(0));
            } finally {
                cursor.dispose();
            }
            return result;
        }, callback);
    }

    public void clear(Utilities.Callback2<Void, Exception> callback) {
        post(true, () -> {
            execute("DELETE FROM saved_link_references");
            return null;
        }, callback);
    }

    private void checkIds(Collection<Integer> ids) {
        if (ids == null) {
            throw new IllegalArgumentException("收藏编号集合不能为空");
        }
        for (Integer id : ids) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException("仅支持已保存的收藏消息");
            }
        }
    }

    private void validate(SavedLinkReference value) {
        if (value == null || value.userId != userId || value.savedMessageId <= 0
                || value.contentSignature == null || value.contentSignature.length() != 64
                || value.parseState < SavedLinkReference.NO_LINK || value.parseState > SavedLinkReference.PARSED
                || value.state < SavedLinkReference.PENDING || value.state > SavedLinkReference.FAILED
                || value.sourceDialogId > 0 || value.channelId < 0 || value.lastSuccessAt < 0) {
            throw new IllegalArgumentException("无效的收藏链接引用");
        }
        if (value.parseState == SavedLinkReference.PARSED) {
            if (value.originalUrl == null || value.sourceMessageId <= 0
                    || (value.username == null) == (value.channelId == 0)
                    || value.sourceDialogId != 0 && value.channelId != 0 && value.sourceDialogId != -value.channelId
                    || value.sourceDialogId == 0 && (value.state == SavedLinkReference.AVAILABLE || value.lastSuccessAt != 0)) {
                throw new IllegalArgumentException("来源编号与解析结果不一致");
            }
        } else if (value.sourceMessageId != 0 || value.channelId != 0 || value.username != null
                || value.sourceDialogId != 0 || value.state != SavedLinkReference.PENDING || value.lastSuccessAt != 0) {
            throw new IllegalArgumentException("无链接或不支持状态不能携带来源缓存");
        }
    }

    private SavedLinkReference read(SQLiteCursor cursor) throws SQLiteException {
        SavedLinkReference value = new SavedLinkReference(userId, cursor.intValue(0), cursor.stringValue(1),
                cursor.isNull(2) ? null : cursor.stringValue(2), cursor.isNull(3) ? null : cursor.stringValue(3), cursor.longValue(4), cursor.intValue(5),
                cursor.intValue(6), cursor.longValue(7), cursor.intValue(8), cursor.longValue(9));
        validate(value);
        return value;
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
            for (Runnable item : callbacks) {
                AndroidUtilities.runOnUIThread(item);
            }
        });
    }

    private synchronized <T> void post(boolean write, DatabaseTask<T> task, Utilities.Callback2<T, Exception> callback) {
        if (closing) {
            deliver(callback, null, new IllegalStateException("链接引用存储已关闭"));
            return;
        }
        boolean posted = storageQueue.postRunnable(() -> {
            T result = null;
            Exception error = null;
            try {
                open();
                result = write ? transaction(task) : task.run();
            } catch (Exception e) {
                error = e;
                if (e instanceof SQLiteException) {
                    closeDatabase();
                }
            }
            deliver(callback, result, error);
        });
        if (!posted) {
            deliver(callback, null, new IllegalStateException("链接引用队列不可用"));
        }
    }

    private static <T> void deliver(Utilities.Callback2<T, Exception> callback, T result, Exception error) {
        AndroidUtilities.runOnUIThread(() -> callback.run(result, error));
    }

    private void open() throws Exception {
        if (database != null) {
            return;
        }
        File directory = databaseFile.getParentFile();
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建链接引用目录");
        }
        database = new SQLiteDatabase(databaseFile.getPath());
        try {
            int version = database.executeInt("PRAGMA user_version");
            if (version != 0 && version != 1) {
                throw new SQLiteException("不支持的链接引用数据库版本：" + version);
            }
            if (version == 0 && database.executeInt("SELECT COUNT(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'") != 0) {
                throw new SQLiteException("链接引用数据库结构未知，已保留文件");
            }
            if (version == 1) {
                String[] names = {"saved_link_references", "saved_link_references_by_source"};
                String[] expected = {CREATE_TABLE, CREATE_INDEX};
                for (int i = 0; i < names.length; i++) {
                    SQLiteCursor cursor = database.queryFinalized("SELECT sql FROM sqlite_master WHERE name = ?", names[i]);
                    try {
                        if (!cursor.next() || cursor.isNull(0) || !cursor.stringValue(0).replaceAll("\\s+", "").replace(";", "")
                                .equalsIgnoreCase(expected[i].replaceAll("\\s+", ""))) {
                            throw new SQLiteException("链接引用数据库结构不匹配");
                        }
                    } finally {
                        cursor.dispose();
                    }
                }
            }
            SQLiteCursor journal = database.queryFinalized("PRAGMA journal_mode = WAL");
            try {
                if (!journal.next() || !"wal".equalsIgnoreCase(journal.stringValue(0))) {
                    throw new SQLiteException("链接引用数据库未启用 WAL");
                }
            } finally {
                journal.dispose();
            }
            execute("PRAGMA synchronous = FULL");
            if (version == 0) {
                transaction(() -> {
                    execute(CREATE_TABLE);
                    execute(CREATE_INDEX);
                    execute("PRAGMA user_version = 1");
                    return null;
                });
            }
        } catch (Exception e) {
            closeDatabase();
            throw e;
        }
    }

    private <T> T transaction(DatabaseTask<T> task) throws Exception {
        execute("SAVEPOINT saved_links_write");
        try {
            T result = task.run();
            execute("RELEASE SAVEPOINT saved_links_write");
            return result;
        } catch (Exception e) {
            try {
                execute("ROLLBACK TO SAVEPOINT saved_links_write");
                execute("RELEASE SAVEPOINT saved_links_write");
            } catch (Exception rollback) {
                e.addSuppressed(rollback);
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
                if (value == null) {
                    statement.bindNull(i + 1);
                } else if (value instanceof String) {
                    statement.bindString(i + 1, (String) value);
                } else if (value instanceof Long) {
                    statement.bindLong(i + 1, (Long) value);
                } else {
                    statement.bindInteger(i + 1, (Integer) value);
                }
            }
            // JNI 的 SQLITE_BUSY 返回 -1，必须检查结果后才报告提交成功。
            int result = statement.step();
            if (result != 1) {
                throw new SQLiteException(result == -1 ? 5 : 0, "链接引用写入未完成：" + result);
            }
        } finally {
            statement.dispose();
        }
    }
}
