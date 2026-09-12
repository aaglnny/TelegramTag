package org.telegram.messenger;

import android.os.Looper;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLiteException;
import org.telegram.SQLite.SQLitePreparedStatement;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LocalSavedTagsStorageTest {

    private static final long USER_A = 4294967311L;
    private static final long USER_B = 4294967333L;
    private final ArrayList<LocalSavedTagsStorage> stores = new ArrayList<>();
    private final ArrayList<SQLiteDatabase> connections = new ArrayList<>();
    private File root;
    private LocalSavedTagsStorage storage;

    private static class Response<T> {
        T value;
        Exception error;
        boolean mainThread;
        final AtomicInteger calls = new AtomicInteger();
    }

    @Before
    public void setUp() throws Exception {
        assertNotEquals("测试不能阻塞主线程", Looper.getMainLooper(), Looper.myLooper());
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "local_saved_tags_tests/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        storage = newStorage(USER_A, false);
    }

    @After
    public void tearDown() throws Exception {
        for (SQLiteDatabase connection : connections) {
            connection.close();
        }
        for (LocalSavedTagsStorage item : stores) {
            close(item);
        }
        if (root != null) {
            deleteFixture(root);
        }
    }

    @Test
    public void t1_01_emptySchemaAndConnection() throws Exception {
        assertTrue(tags().isEmpty());
        SQLiteDatabase db = raw();
        assertEquals(1, number(db, "PRAGMA user_version"));
        assertEquals("wal", text(db, "PRAGMA journal_mode"));
        assertEquals(0, number(db, "SELECT COUNT(*) FROM local_saved_message_tags"));
        assertEquals(2, number(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"));
        assertEquals(1, number(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'local_saved_message_tags_by_tag'"));
        System.out.println("本地标签项目测试 SQLite 版本：" + text(db, "SELECT sqlite_version()"));
        assertTrue(storage.getDatabaseFile().isFile());
        assertTrue(tags().isEmpty());
    }

    @Test
    public void t1_02_normalizationAndCodePointBoundaries() throws Exception {
        LocalSavedTag tag = create(" \t\u3000学习\u00a0 ");
        assertEquals("学习", tag.name);
        assertEquals("学习", tag.nameKey);
        assertEquals("#学习", create("#学习").name);
        String emoji = new String(Character.toChars(0x1f600));
        assertEquals(emoji, create(emoji).name);
        String thirtyTwo = String.join("", Collections.nCopies(32, emoji));
        assertEquals(thirtyTwo, create(thirtyTwo).name);
        for (String invalid : new String[]{null, "", " \t\u3000\u00a0", "学\n习", "学\r习",
                "学" + (char) 0x2028 + "习", thirtyTwo + emoji}) {
            tagError(LocalSavedTagsStorage.TagException.INVALID_NAME, callback -> storage.createTag(invalid, callback));
        }
        assertEquals(4, tags().size());
    }

    @Test
    public void t1_03_normalizedNamesAreUniqueAcrossLocales() throws Exception {
        create("Android");
        tagError(LocalSavedTagsStorage.TagException.NAME_EXISTS, callback -> storage.createTag(" android ", callback));
        LocalSavedTag combined = create("e\u0301");
        assertEquals("é", combined.name);
        tagError(LocalSavedTagsStorage.TagException.NAME_EXISTS, callback -> storage.createTag("é", callback));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(new Locale("tr", "TR"));
            assertEquals("index", create("INDEX").nameKey);
            tagError(LocalSavedTagsStorage.TagException.NAME_EXISTS, callback -> storage.createTag("index", callback));
        } finally {
            Locale.setDefault(previous);
        }
        SQLiteDatabase db = raw();
        assertThrows(SQLiteException.class,
                () -> execute(db, LocalSavedTagsStorage.INSERT_TAG_SQL, "其他显示名", "android", 1000L));
        assertEquals(3, tags().size());
    }

    @Test
    public void t1_04_renamePreservesIdentityAndRelations() throws Exception {
        long[] ids = seed();
        LocalSavedTag before = tag(ids[0]);
        List<String> relations = relations();
        LocalSavedTag renamed = await(callback -> storage.renameTag(ids[0], "课程", callback));
        assertEquals(before.id, renamed.id);
        assertEquals(before.createdAt, renamed.createdAt);
        assertEquals(2, renamed.messageCount);
        assertEquals("课程", renamed.name);
        tagError(LocalSavedTagsStorage.TagException.NAME_EXISTS,
                callback -> storage.renameTag(ids[0], "项目资料", callback));
        assertEquals("课程", tag(ids[0]).name);
        LocalSavedTag unchanged = await(callback -> storage.renameTag(ids[0], "课程", callback));
        assertEquals(renamed.id, unchanged.id);
        assertEquals(relations, relations());
        tagError(LocalSavedTagsStorage.TagException.TAG_NOT_FOUND,
                callback -> storage.renameTag(999999L, "不存在", callback));
    }

    @Test
    public void t1_05_deleteCascadesAndDoesNotReuseId() throws Exception {
        long[] ids = seed();
        this.<Void>await(callback -> storage.deleteTag(ids[2], callback));
        assertEquals(new HashSet<>(Arrays.asList(ids[1])), tagIds(102));
        LocalSavedTag newTag = create("新标签");
        assertTrue(newTag.id > ids[2]);
        assertEquals(0, newTag.messageCount);
        this.<Void>await(callback -> storage.deleteTag(ids[2], callback));
        assertEquals(4, relations().size());
        assertEquals(3, tags().size());
    }

    @Test
    public void t1_06_appendPreservesExistingTags() throws Exception {
        long[] ids = seed();
        for (int i = 0; i < 2; i++) {
            apply(dates(101, 102), Arrays.asList(ids[2], ids[2]), Collections.emptyList());
        }
        assertEquals(new HashSet<>(Arrays.asList(ids[0], ids[2])), tagIds(101));
        assertEquals(new HashSet<>(Arrays.asList(ids[1], ids[2])), tagIds(102));
        assertEquals(2, tag(ids[2]).messageCount);
    }

    @Test
    public void t1_07_removeOnlySelectedRelations() throws Exception {
        long[] ids = seed();
        for (int i = 0; i < 2; i++) {
            apply(dates(102, 103, 999), Collections.emptyList(), Arrays.asList(ids[1]));
        }
        assertEquals(Collections.singleton(ids[2]), tagIds(102));
        assertEquals(Collections.singleton(ids[0]), tagIds(103));
        assertEquals(0, tag(ids[1]).messageCount);
        assertEquals(3, relations().size());
    }

    @Test
    public void t1_08_addAndRemoveInOneOperation() throws Exception {
        long[] ids = seed();
        apply(dates(101), Arrays.asList(ids[2]), Arrays.asList(ids[0]));
        assertEquals(Collections.singleton(ids[2]), tagIds(101));
        assertEquals(1, tag(ids[0]).messageCount);
        assertEquals(2, tag(ids[2]).messageCount);
    }

    @Test
    public void t1_09_invalidInputAndForeignKeysFail() throws Exception {
        long[] ids = seed();
        List<String> before = relations();
        for (int invalid : new int[]{0, -1}) {
            Response<Void> response = this.<Void>call(callback ->
                    storage.applyTags(dates(101, invalid), Arrays.asList(ids[2]), Collections.emptyList(), callback));
            assertTrue(response.error instanceof IllegalArgumentException);
            assertEquals(before, relations());
        }
        Response<Void> missing = this.<Void>call(callback -> storage.applyTags(
                dates(101, 104), Arrays.asList(ids[2], 999999L), Arrays.asList(ids[0]), callback));
        assertTrue(missing.error instanceof SQLiteException);
        assertEquals(before, relations());
        Response<Void> conflict = this.<Void>call(callback -> storage.applyTags(
                dates(101), Arrays.asList(ids[0]), Arrays.asList(ids[0]), callback));
        assertTrue(conflict.error instanceof IllegalArgumentException);
        assertEquals(before, relations());
    }

    @Test
    public void t1_10_countsAndTagOrder() throws Exception {
        long[] ids = seed();
        LocalSavedTag emptyA = create("空标签甲");
        LocalSavedTag emptyB = create("空标签乙");
        SQLiteDatabase db = raw();
        execute(db, "UPDATE local_saved_tags SET created_at = 1000");
        ArrayList<LocalSavedTag> tags = tags();
        assertEquals(Arrays.asList(ids[1], ids[0], ids[2], emptyB.id, emptyA.id), ids(tags));
        assertEquals(Arrays.asList(2, 2, 1, 0, 0), Arrays.asList(
                tags.get(0).messageCount, tags.get(1).messageCount, tags.get(2).messageCount,
                tags.get(3).messageCount, tags.get(4).messageCount));
        execute(db, "UPDATE local_saved_tags SET created_at = 2000 WHERE id = ?", ids[0]);
        assertEquals(ids[0], tags().get(0).id);
    }

    @Test
    public void t1_11_batchLookupHandlesMissingDuplicateAndEmptyIds() throws Exception {
        long[] ids = seed();
        SparseArray<ArrayList<LocalSavedTag>> result = await(callback ->
                storage.loadMessageTags(Arrays.asList(101, 102, 104, 999, 101), callback));
        assertEquals(4, result.size());
        assertEquals(Collections.singleton(ids[0]), new HashSet<>(ids(result.get(101))));
        assertEquals(new HashSet<>(Arrays.asList(ids[1], ids[2])), new HashSet<>(ids(result.get(102))));
        assertTrue(result.get(104).isEmpty());
        assertTrue(result.get(999).isEmpty());
        SparseArray<ArrayList<LocalSavedTag>> empty = await(callback -> storage.loadMessageTags(Collections.emptyList(), callback));
        assertEquals(0, empty.size());

        SparseIntArray messages = new SparseIntArray();
        ArrayList<Integer> requested = new ArrayList<>();
        for (int i = 1001; i <= 1601; i++) {
            messages.put(i, 1700000000);
            requested.add(i);
        }
        apply(messages, Arrays.asList(ids[0]), Collections.emptyList());
        SparseArray<ArrayList<LocalSavedTag>> multipleBatches = await(callback -> storage.loadMessageTags(requested, callback));
        assertEquals(601, multipleBatches.size());
        for (int i = 1001; i <= 1601; i++) {
            assertEquals(Collections.singleton(ids[0]), new HashSet<>(ids(multipleBatches.get(i))));
        }
    }

    @Test
    public void t1_12_pagingAndLookaheadBoundaries() throws Exception {
        for (int count : new int[]{0, 1, 49, 50, 51, 100, 101, 123}) {
            long tagId = history(count);
            ArrayList<Integer> actual = new ArrayList<>();
            ArrayList<Integer> sizes = new ArrayList<>();
            LocalSavedTagMessage before = null;
            while (true) {
                LocalSavedTagsStorage.MessagePage page = page(tagId, before);
                sizes.add(page.messages.size());
                for (LocalSavedTagMessage item : page.messages) {
                    assertEquals(tagId, item.tagId);
                    actual.add(item.messageId);
                }
                if (!page.hasMore) {
                    break;
                }
                assertEquals(50, page.messages.size());
                before = page.messages.get(page.messages.size() - 1);
            }
            ArrayList<Integer> expected = new ArrayList<>();
            for (int id = 1000 + count; id >= 1001; id--) {
                expected.add(id);
            }
            assertEquals(expected, actual);
            if (count == 123) {
                assertEquals(Arrays.asList(50, 50, 23), sizes);
            }
            this.<Void>await(callback -> storage.deleteTag(tagId, callback));
        }
    }

    @Test
    public void t1_13_pagingAfterCursorDeletion() throws Exception {
        long id = history(123);
        LocalSavedTagsStorage.MessagePage first = page(id, null);
        LocalSavedTagMessage cursor = first.messages.get(49);
        assertEquals(1074, cursor.messageId);
        apply(dates(1074, 1022), Collections.emptyList(), Arrays.asList(id));
        SparseIntArray newer = new SparseIntArray();
        newer.put(9000, 1800000000);
        apply(newer, Arrays.asList(id), Collections.emptyList());
        LocalSavedTagsStorage.MessagePage second = page(id, cursor);
        LocalSavedTagsStorage.MessagePage third = page(id, second.messages.get(49));
        ArrayList<Integer> actual = new ArrayList<>();
        for (LocalSavedTagMessage item : second.messages) {
            actual.add(item.messageId);
        }
        for (LocalSavedTagMessage item : third.messages) {
            actual.add(item.messageId);
        }
        ArrayList<Integer> expected = new ArrayList<>();
        for (int messageId = 1073; messageId >= 1001; messageId--) {
            if (messageId != 1022) {
                expected.add(messageId);
            }
        }
        assertEquals(expected, actual);
        assertFalse(third.hasMore);
        assertEquals(9000, page(id, null).messages.get(0).messageId);
    }

    @Test
    public void t1_14_namesAreBoundParameters() throws Exception {
        LocalSavedTag tag = create("资料'\";--");
        assertEquals("资料'\";--", tag.name);
        LocalSavedTag renamed = await(callback -> storage.renameTag(tag.id, "#学习", callback));
        assertEquals("#学习", renamed.name);
        this.<Void>await(callback -> storage.deleteTag(tag.id, callback));
        assertTrue(tags().isEmpty());
    }

    @Test
    public void t1_15_longIdsAndSavedDatesArePreserved() throws Exception {
        assertEquals(USER_A, storage.getUserId());
        tags();
        SQLiteDatabase db = raw();
        execute(db, "INSERT INTO local_saved_tags(id, name, name_key, created_at) VALUES (?, ?, ?, ?)",
                4294967400L, "编号占位", "编号占位", 1800000000000L);
        LocalSavedTag tag = create("学习");
        assertTrue(tag.id > Integer.MAX_VALUE);
        SparseIntArray dates = new SparseIntArray();
        dates.put(101, 1700000003);
        apply(dates, Arrays.asList(tag.id), Collections.emptyList());
        dates.put(101, 1900000000);
        apply(dates, Arrays.asList(tag.id), Collections.emptyList());
        LocalSavedTagMessage item = page(tag.id, null).messages.get(0);
        assertEquals(1700000003, item.messageDate);
        assertEquals(tag.id, item.tagId);
        assertEquals(Collections.singleton(tag.id), tagIds(101));
    }

    @Test
    public void t1_16_midBatchAbortRollsBackAndRetries() throws Exception {
        long[] ids = seed();
        List<String> before = relations();
        SQLiteDatabase db = raw();
        execute(db, "CREATE TRIGGER fail_insert BEFORE INSERT ON local_saved_message_tags "
                + "WHEN NEW.message_id = 104 BEGIN SELECT RAISE(ABORT, '测试中断'); END");
        Response<Void> response = this.<Void>call(callback -> storage.applyTags(
                dates(101, 104), Arrays.asList(ids[2]), Arrays.asList(ids[0]), callback));
        assertTrue(response.error instanceof SQLiteException);
        assertNull(response.value);
        assertEquals(before, relations());
        execute(db, "DROP TRIGGER fail_insert");
        apply(dates(101, 104), Arrays.asList(ids[2]), Arrays.asList(ids[0]));
        assertEquals(Collections.singleton(ids[2]), tagIds(101));
        assertEquals(Collections.singleton(ids[2]), tagIds(104));
        assertEquals(3, tag(ids[2]).messageCount);
    }

    @Test
    public void t1_17_busyIsFailureAndCanRetry() throws Exception {
        create("已有标签");
        SQLiteDatabase writer = raw();
        SQLiteDatabase probe = raw();
        execute(writer, "BEGIN IMMEDIATE");
        try {
            SQLitePreparedStatement statement = probe.executeFast(
                    "INSERT INTO local_saved_tags(name, name_key, created_at) VALUES ('探测', '探测', 1)");
            try {
                assertEquals(-1, statement.step());
            } finally {
                statement.dispose();
            }
            Response<LocalSavedTag> response = this.<LocalSavedTag>call(callback -> storage.createTag("等待写锁", callback));
            assertTrue(response.error instanceof SQLiteException);
            assertEquals(5, ((SQLiteException) response.error).errorCode);
            assertNull(response.value);
        } finally {
            execute(writer, "ROLLBACK");
        }
        assertEquals(1, tags().size());
        assertEquals("等待写锁", create("等待写锁").name);
        assertEquals(2, tags().size());
    }

    @Test
    public void t1_18_autoRollbackDiscardsConnectionAndRecovers() throws Exception {
        long[] ids = seed();
        List<String> before = relations();
        SQLiteDatabase db = raw();
        execute(db, "CREATE TRIGGER fail_rollback BEFORE INSERT ON local_saved_message_tags "
                + "WHEN NEW.message_id = 104 BEGIN SELECT RAISE(ROLLBACK, '测试自动回滚'); END");
        Response<Void> response = this.<Void>call(callback -> storage.applyTags(
                dates(101, 104), Arrays.asList(ids[2]), Arrays.asList(ids[0]), callback));
        assertTrue(response.error instanceof SQLiteException);
        assertTrue(response.error.getSuppressed().length > 0);
        assertEquals(before, relations());
        execute(db, "DROP TRIGGER fail_rollback");
        apply(dates(101, 104), Arrays.asList(ids[2]), Collections.emptyList());
        assertEquals(3, tag(ids[2]).messageCount);
    }

    @Test
    public void t1_19_closeAndReopenKeepsData() throws Exception {
        long[] ids = seed();
        List<String> before = relations();
        File file = storage.getDatabaseFile();
        for (SQLiteDatabase db : connections) {
            db.close();
        }
        close(storage);
        assertTrue(file.isFile());
        storage = newStorage(USER_A, false);
        assertEquals(3, tags().size());
        assertEquals(before, relations());
        Response<Void> response = this.<Void>call(callback -> storage.applyTags(
                dates(104), Arrays.asList(999999L), Collections.emptyList(), callback));
        assertTrue(response.error instanceof SQLiteException);
        assertEquals(2, tag(ids[0]).messageCount);
        assertEquals("wal", text(raw(), "PRAGMA journal_mode"));
    }

    @Test
    public void t1_20_usersAndBackendEnvironmentsAreIsolated() throws Exception {
        LocalSavedTag tagA = create("相同名称");
        apply(dates(101), Arrays.asList(tagA.id), Collections.emptyList());
        LocalSavedTagsStorage b = newStorage(USER_B, false);
        LocalSavedTagsStorage test = newStorage(USER_A, true);
        LocalSavedTag tagB = await(callback -> b.createTag("相同名称", callback));
        LocalSavedTag testTag = await(callback -> test.createTag("相同名称", callback));
        assertEquals(0, tagB.messageCount);
        assertEquals(0, testTag.messageCount);
        this.<Void>await(callback -> b.applyTags(dates(101), Arrays.asList(tagB.id), Collections.emptyList(), callback));
        this.<Void>await(callback -> b.deleteTag(tagB.id, callback));
        assertEquals(1, tag(tagA.id).messageCount);
        assertEquals(new File(root, "local_saved_tags/prod/" + USER_A + ".db"), storage.getDatabaseFile());
        assertEquals(new File(root, "local_saved_tags/prod/" + USER_B + ".db"), b.getDatabaseFile());
        assertEquals(new File(root, "local_saved_tags/test/" + USER_A + ".db"), test.getDatabaseFile());
        close(b);
        LocalSavedTagsStorage restored = newStorage(USER_A, false);
        ArrayList<LocalSavedTag> restoredTags = await(restored::loadTags);
        assertEquals(1, restoredTags.get(0).messageCount);
    }

    @Test
    public void t1_21_invalidIdentityDoesNotCreateDatabase() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new LocalSavedTagsStorage(root, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new LocalSavedTagsStorage(root, -1, false));
        assertThrows(IllegalArgumentException.class, () -> new LocalSavedTagsStorage(-1));
        assertFalse(new File(root, "local_saved_tags/prod/0.db").exists());
        assertFalse(storage.getDatabaseFile().exists());
        assertEquals(USER_A, storage.getUserId());
        boolean foundInactive = false;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            if (!UserConfig.getInstance(i).isClientActivated()) {
                final int account = i;
                assertThrows(IllegalStateException.class, () -> new LocalSavedTagsStorage(account));
                foundInactive = true;
                break;
            }
        }
        assertTrue("需要含未登录槽位的隔离测试环境", foundInactive);
    }

    @Test
    public void t1_22_higherVersionKeepsOriginalData() throws Exception {
        create("保留数据");
        close(storage);
        SQLiteDatabase db = raw();
        execute(db, "PRAGMA user_version = 99");
        db.close();
        storage = newStorage(USER_A, false);
        Response<ArrayList<LocalSavedTag>> response = this.<ArrayList<LocalSavedTag>>call(storage::loadTags);
        assertTrue(response.error instanceof SQLiteException);
        assertNull(response.value);
        assertEquals(99, number(raw(), "PRAGMA user_version"));
        assertEquals("保留数据", text(raw(), "SELECT name FROM local_saved_tags"));
    }

    @Test
    public void t1_22_unknownSchemaIsNotRebuilt() throws Exception {
        create("保留数据");
        close(storage);
        SQLiteDatabase db = raw();
        execute(db, "DROP INDEX local_saved_message_tags_by_tag");
        db.close();
        storage = newStorage(USER_A, false);
        Response<ArrayList<LocalSavedTag>> response = this.<ArrayList<LocalSavedTag>>call(storage::loadTags);
        assertTrue(response.error instanceof SQLiteException);
        assertEquals("保留数据", text(raw(), "SELECT name FROM local_saved_tags"));
        assertEquals(0, number(raw(), "SELECT COUNT(*) FROM sqlite_master WHERE name='local_saved_message_tags_by_tag'"));
        execute(raw(), "PRAGMA user_version = 0");
        Response<ArrayList<LocalSavedTag>> unknownVersion = this.<ArrayList<LocalSavedTag>>call(storage::loadTags);
        assertTrue(unknownVersion.error instanceof SQLiteException);
        assertEquals(0, number(raw(), "PRAGMA user_version"));
        assertEquals("保留数据", text(raw(), "SELECT name FROM local_saved_tags"));
    }

    @Test
    public void t1_22_corruptFileIsPreserved() throws Exception {
        tags();
        close(storage);
        File file = storage.getDatabaseFile();
        byte[] invalid = new byte[512];
        Arrays.fill(invalid, (byte) 0x5a);
        Files.write(file.toPath(), invalid);
        storage = newStorage(USER_A, false);
        Response<ArrayList<LocalSavedTag>> response = this.<ArrayList<LocalSavedTag>>call(storage::loadTags);
        assertTrue(response.error instanceof SQLiteException);
        assertTrue(file.isFile());
        assertArrayEquals(invalid, Files.readAllBytes(file.toPath()));
    }

    @Test
    public void t1_24_queueCloseAndCallbackOrder() throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(5);
        storage.createTag("甲", (tag, error) -> {
            if (error != null || tag == null || Looper.myLooper() != Looper.getMainLooper()) {
                failure.compareAndSet(null, new AssertionError("第一个写入回调异常", error));
            }
            events.add("甲");
            done.countDown();
        });
        storage.createTag("乙", (tag, error) -> {
            if (error != null || tag == null || Looper.myLooper() != Looper.getMainLooper()) {
                failure.compareAndSet(null, new AssertionError("第二个写入回调异常", error));
            }
            events.add("乙");
            done.countDown();
        });
        storage.loadTags((tags, error) -> {
            if (error != null || tags == null || tags.size() != 2) {
                failure.compareAndSet(null, new AssertionError("队列读取未看到前面的写入", error));
            }
            events.add("读取");
            done.countDown();
        });
        storage.close(() -> {
            events.add("关闭甲");
            done.countDown();
        });
        storage.close(() -> {
            events.add("关闭乙");
            done.countDown();
        });
        assertTrue("队列操作未完成", done.await(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertNull(failure.get());
        assertEquals(5, events.size());
        assertEquals(Arrays.asList("甲", "乙", "读取"), events.subList(0, 3));
        assertTrue(events.contains("关闭甲"));
        assertTrue(events.contains("关闭乙"));
        Response<ArrayList<LocalSavedTag>> closedResult = this.<ArrayList<LocalSavedTag>>call(storage::loadTags);
        assertTrue(closedResult.error instanceof IllegalStateException);
        storage = newStorage(USER_A, false);
        assertEquals(2, tags().size());
    }

    @Test
    public void t1_24_inputsAreSnapshottedBeforeEnqueue() throws Exception {
        LocalSavedTag first = create("原选择");
        LocalSavedTag second = create("后续选择");
        SparseIntArray messages = dates(101);
        ArrayList<Long> chosen = new ArrayList<>(Arrays.asList(first.id));
        Response<Void> response = this.<Void>call(callback -> {
            storage.applyTags(messages, chosen, Collections.emptyList(), callback);
            messages.clear();
            messages.put(102, 1900000000);
            chosen.clear();
            chosen.add(second.id);
        });
        assertNull(response.error);
        assertEquals(Collections.singleton(first.id), tagIds(101));
        assertTrue(tagIds(102).isEmpty());
        assertEquals(1700000003, page(first.id, null).messages.get(0).messageDate);
    }

    @Test
    public void t1_25_queriesUseIndexes() throws Exception {
        long id = history(10000);
        SQLiteDatabase db = raw();
        String pages = queryPlan(db, LocalSavedTagsStorage.SELECT_OLDER_MESSAGES_SQL, id, 1700001000, 8000, 51);
        assertTrue(pages, pages.contains("local_saved_message_tags_by_tag"));
        String messages = queryPlan(db, LocalSavedTagsStorage.SELECT_MESSAGE_TAGS_SQL
                + "?,?,?" + LocalSavedTagsStorage.MESSAGE_TAGS_ORDER_SQL, 1001, 1002, 1003);
        assertTrue(messages, messages.contains("sqlite_autoindex_local_saved_message_tags"));
    }

    @Test
    public void t1_26_noPreviewRecordsAreCreated() throws Exception {
        assertTrue(tags().isEmpty());
        create("正式标签");
        assertEquals(1, tags().size());
        assertEquals("正式标签", tags().get(0).name);
        assertTrue(relations().isEmpty());
        close(storage);
        storage = newStorage(USER_A, false);
        assertEquals(1, tags().size());
        assertEquals("正式标签", tags().get(0).name);
        assertTrue(relations().isEmpty());
    }

    private LocalSavedTagsStorage newStorage(long userId, boolean test) {
        LocalSavedTagsStorage result = new LocalSavedTagsStorage(root, userId, test);
        stores.add(result);
        return result;
    }

    private <T> Response<T> call(Consumer<Utilities.Callback2<T, Exception>> request) throws Exception {
        Response<T> result = new Response<>();
        CountDownLatch done = new CountDownLatch(1);
        request.accept((value, error) -> {
            result.value = value;
            result.error = error;
            result.mainThread = Looper.myLooper() == Looper.getMainLooper();
            result.calls.incrementAndGet();
            done.countDown();
        });
        assertTrue("本地标签操作超时", done.await(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertEquals("回调次数错误", 1, result.calls.get());
        assertTrue("结果没有交付主线程", result.mainThread);
        return result;
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> request) throws Exception {
        Response<T> result = call(request);
        if (result.error != null) {
            throw new AssertionError(result.error);
        }
        return result.value;
    }

    private void tagError(int code, Consumer<Utilities.Callback2<LocalSavedTag, Exception>> request) throws Exception {
        Response<LocalSavedTag> result = call(request);
        assertTrue(String.valueOf(result.error), result.error instanceof LocalSavedTagsStorage.TagException);
        assertEquals(code, ((LocalSavedTagsStorage.TagException) result.error).code);
        assertNull(result.value);
    }

    private void close(LocalSavedTagsStorage item) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        item.close(done::countDown);
        assertTrue("本地标签存储未关闭", done.await(15, TimeUnit.SECONDS));
    }

    private LocalSavedTag create(String name) throws Exception {
        return await(callback -> storage.createTag(name, callback));
    }

    private ArrayList<LocalSavedTag> tags() throws Exception {
        return await(storage::loadTags);
    }

    private LocalSavedTag tag(long id) throws Exception {
        for (LocalSavedTag tag : tags()) {
            if (tag.id == id) {
                return tag;
            }
        }
        throw new AssertionError("标签不存在：" + id);
    }

    private ArrayList<Long> ids(List<LocalSavedTag> tags) {
        ArrayList<Long> result = new ArrayList<>();
        for (LocalSavedTag tag : tags) {
            result.add(tag.id);
        }
        return result;
    }

    private Set<Long> tagIds(int messageId) throws Exception {
        SparseArray<ArrayList<LocalSavedTag>> tags = await(callback ->
                storage.loadMessageTags(Arrays.asList(messageId), callback));
        return new HashSet<>(ids(tags.get(messageId)));
    }

    private void apply(SparseIntArray messages, List<Long> added, List<Long> removed) throws Exception {
        this.<Void>await(callback -> storage.applyTags(messages, added, removed, callback));
    }

    private SparseIntArray dates(int... ids) {
        SparseIntArray result = new SparseIntArray();
        for (int id : ids) {
            int date = id == 101 ? 1700000003 : id == 104 ? 1700000001 : 1700000002;
            result.put(id, date);
        }
        return result;
    }

    private long[] seed() throws Exception {
        long a = create("学习").id;
        long b = create("项目资料").id;
        long c = create("待处理").id;
        apply(dates(101, 103), Arrays.asList(a), Collections.emptyList());
        apply(dates(102, 103), Arrays.asList(b), Collections.emptyList());
        apply(dates(102), Arrays.asList(c), Collections.emptyList());
        return new long[]{a, b, c};
    }

    private long history(int count) throws Exception {
        long tagId = create("分页" + count).id;
        SparseIntArray messages = new SparseIntArray();
        for (int id = 1001; id < 1001 + count; id++) {
            messages.put(id, 1700000000 + (id - 1001) / 7);
        }
        apply(messages, Arrays.asList(tagId), Collections.emptyList());
        return tagId;
    }

    private LocalSavedTagsStorage.MessagePage page(long tagId, LocalSavedTagMessage before) throws Exception {
        return await(callback -> storage.loadMessages(tagId, before, 50, callback));
    }

    private SQLiteDatabase raw() throws Exception {
        SQLiteDatabase db = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        connections.add(db);
        return db;
    }

    private void execute(SQLiteDatabase db, String sql, Object... args) throws Exception {
        SQLiteCursor cursor = db.queryFinalized(sql, args);
        try {
            assertFalse(cursor.next());
        } finally {
            cursor.dispose();
        }
    }

    private long number(SQLiteDatabase db, String sql, Object... args) throws Exception {
        SQLiteCursor cursor = db.queryFinalized(sql, args);
        try {
            assertTrue(cursor.next());
            return cursor.longValue(0);
        } finally {
            cursor.dispose();
        }
    }

    private String text(SQLiteDatabase db, String sql) throws Exception {
        SQLiteCursor cursor = db.queryFinalized(sql);
        try {
            assertTrue(cursor.next());
            return cursor.stringValue(0);
        } finally {
            cursor.dispose();
        }
    }

    private List<String> relations() throws Exception {
        SQLiteCursor cursor = raw().queryFinalized(
                "SELECT message_id, tag_id, message_date FROM local_saved_message_tags ORDER BY message_id, tag_id");
        try {
            ArrayList<String> rows = new ArrayList<>();
            while (cursor.next()) {
                rows.add(cursor.intValue(0) + "/" + cursor.longValue(1) + "/" + cursor.intValue(2));
            }
            return rows;
        } finally {
            cursor.dispose();
        }
    }

    private String queryPlan(SQLiteDatabase db, String sql, Object... args) throws Exception {
        SQLiteCursor cursor = db.queryFinalized("EXPLAIN QUERY PLAN " + sql, args);
        StringBuilder result = new StringBuilder();
        try {
            while (cursor.next()) {
                result.append(cursor.stringValue(3)).append('\n');
            }
        } finally {
            cursor.dispose();
        }
        return result.toString();
    }

    private void deleteFixture(File file) throws Exception {
        File target = file.getCanonicalFile();
        if (!target.equals(root) && !target.getPath().startsWith(root.getPath() + File.separator)) {
            throw new AssertionError("停止清理测试目录以外的文件");
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteFixture(child);
            }
        }
        assertTrue("未能清理测试文件：" + target, target.delete());
    }
}
