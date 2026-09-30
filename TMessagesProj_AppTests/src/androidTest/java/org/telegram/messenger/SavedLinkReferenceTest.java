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
import org.telegram.tgnet.TLRPC;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class SavedLinkReferenceTest {

    static final long USER_A = LocalSavedTagsTestActivity.USER_A;
    static final long USER_B = LocalSavedTagsTestActivity.USER_B;
    static final long CHANNEL_A = 4294967601L;
    static final long CHANNEL_B = 4294967602L;
    private final ArrayList<SavedLinkPreviewStorage> stores = new ArrayList<>();
    private final ArrayList<SavedLinkPreviewController> controllers = new ArrayList<>();
    private final ArrayList<SQLiteDatabase> connections = new ArrayList<>();
    private File root;
    private SavedLinkPreviewStorage storage;
    private boolean installedUser;

    private static class Result<T> {
        T value;
        Exception error;
        final AtomicInteger calls = new AtomicInteger();
        boolean mainThread;
    }

    private static class HeldStorage extends SavedLinkPreviewStorage {
        final ArrayList<Runnable> reads = new ArrayList<>();
        boolean hold = true;
        boolean fail;

        HeldStorage(File root) {
            super(root, USER_A, false);
        }

        @Override
        public void load(Collection<Integer> ids, Utilities.Callback2<SparseArray<SavedLinkReference>, Exception> callback) {
            ArrayList<Integer> copy = new ArrayList<>(ids);
            if (fail) {
                fail = false;
                AndroidUtilities.runOnUIThread(() -> callback.run(null, new SQLiteException("隔离读取失败")));
            } else if (hold) {
                reads.add(() -> super.load(copy, callback));
            } else {
                super.load(copy, callback);
            }
        }
    }

    @Before
    public void setUp() throws Exception {
        main(ApplicationLoader::postInitApplication);
        assertTrue(ApplicationLoader.isAndroidTestEnvironment());
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            assertFalse("本套件只能运行在未登录的隔离环境", UserConfig.getInstance(account).isClientActivated());
        }
        root = new File(ApplicationLoader.applicationContext.getNoBackupFilesDir(),
                "saved_links_tests/" + UUID.randomUUID()).getCanonicalFile();
        assertTrue(root.mkdirs());
        main(() -> {
            user(USER_A);
            installedUser = true;
        });
        storage = store(USER_A, false);
    }

    @After
    public void tearDown() throws Exception {
        main(() -> {
            for (SavedLinkPreviewController controller : controllers) {
                controller.cleanup();
            }
        });
        for (SQLiteDatabase connection : connections) {
            connection.close();
        }
        for (SavedLinkPreviewStorage item : stores) {
            close(item);
        }
        if (installedUser) {
            main(() -> UserConfig.getInstance(0).clearConfig());
        }
        if (root != null) {
            delete(root);
        }
    }

    @Test
    public void l1_publicPrivateAliasesAndLongIds() {
        for (String url : new String[]{"https://t.me/Some_Channel/301", "HTTPS://T.ME/Some_Channel/301?single",
                "https://telegram.me/Some_Channel/301", "https://telegram.me/Some_Channel/301?single"}) {
            SavedLinkReference value = parse(" \t" + url + "\n");
            assertEquals(url, SavedLinkReference.PARSED, value.parseState);
            assertEquals("some_channel", value.username);
            assertEquals(301, value.sourceMessageId);
            assertEquals(101, value.savedMessageId);
            assertEquals(USER_A, value.userId);
            assertEquals(url, value.originalUrl);
            assertEquals(0, value.sourceDialogId);
        }
        SavedLinkReference value = parse("https://t.me/c/9223372036854775807/2147483647?single");
        assertEquals(SavedLinkReference.PARSED, value.parseState);
        assertEquals(Long.MAX_VALUE, value.channelId);
        assertEquals(Integer.MAX_VALUE, value.sourceMessageId);
        assertNull(value.username);
    }

    @Test
    public void l1_unsupportedLinksDoNotBecomeReferences() {
        for (String url : new String[]{"https://t.me/c/123", "https://t.me/s/123", "https://t.me/joinchat/123",
                "https://t.me/preview/0", "https://t.me/preview/-1", "https://t.me/preview/01",
                "https://t.me/preview/2147483648", "https://t.me/c/0/301", "https://t.me/c/-1/301",
                "https://t.me/c/9223372036854775808/301", "https://t.me/preview/301/302",
                "https://t.me/preview/301?comment=12", "https://t.me/preview/301?thread=2",
                "https://t.me/preview/301?t=10", "https://t.me/preview/301?single&single",
                "https://t.me/preview/301?single=1", "https://t.me/preview/301?unknown=1",
                "https://t.me/preview/301#fragment", "https://t.me/preview%2F301",
                "https://t.me/preview/%33%30%31", "http://t.me/preview/301", "ftp://t.me/preview/301",
                "https://user@t.me/preview/301", "https://t.me:443/preview/301",
                "https://t.me.evil.example/preview/301", "https://evil.example/t.me/preview/301",
                "https://t.me/+invite", "https://t.me/preview/s/301", "https://t.me/preview?start=301"}) {
            TLRPC.TL_message message = message(101, "查看");
            hidden(message, 0, 2, url);
            SavedLinkReference value = SavedLinkPreviewController.parse(USER_A, message);
            assertNotEquals(url, SavedLinkReference.PARSED, value.parseState);
            assertEquals(0, value.sourceMessageId);
            assertEquals(0, value.sourceDialogId);
            assertEquals("查看", message.message);
            assertEquals(url, message.entities.get(0).url);
        }
    }

    @Test
    public void l1_utf16HiddenLinkMasksDisplayedUrl() {
        String label = "https://t.me/displayed/777";
        TLRPC.TL_message message = message(101, "😀 " + label + " 查看");
        hidden(message, 3, label.length(), "https://t.me/actual/301");
        SavedLinkReference value = SavedLinkPreviewController.parse(USER_A, message);
        assertEquals("actual", value.username);
        assertEquals(301, value.sourceMessageId);
        message.entities.get(0).url = "https://example.com/other";
        assertEquals(SavedLinkReference.NO_LINK, SavedLinkPreviewController.parse(USER_A, message).parseState);
        message.entities.get(0).offset = Integer.MAX_VALUE;
        assertEquals("displayed", SavedLinkPreviewController.parse(USER_A, message).username);
    }

    @Test
    public void l1_captionEntitiesAndPunctuation() {
        TLRPC.TL_message message = message(101, "图片说明： https://t.me/preview/301。 #原话题");
        message.media = new TLRPC.TL_messageMediaPhoto();
        SavedLinkReference value = SavedLinkPreviewController.parse(USER_A, message);
        assertEquals(301, value.sourceMessageId);
        assertEquals("https://t.me/preview/301", value.originalUrl);
        assertTrue(message.message.endsWith("#原话题"));
        message.message = "😀 https://telegram.me/c/4294967601/302 后文";
        TLRPC.TL_messageEntityUrl entity = new TLRPC.TL_messageEntityUrl();
        entity.offset = 3;
        entity.length = "https://telegram.me/c/4294967601/302".length();
        message.entities.add(entity);
        value = SavedLinkPreviewController.parse(USER_A, message);
        assertEquals(CHANNEL_A, value.channelId);
        assertEquals(302, value.sourceMessageId);
    }

    @Test
    public void l1_firstSupportedLinkWins() {
        SavedLinkReference value = parse("https://example.com/a https://t.me/a/301?comment=2 "
                + "https://t.me/c/4294967601/301 https://t.me/next/302");
        assertEquals(CHANNEL_A, value.channelId);
        assertEquals(301, value.sourceMessageId);
        assertEquals(SavedLinkReference.NO_LINK, parse("没有链接 #标签").parseState);
        assertEquals(SavedLinkReference.UNSUPPORTED, parse("https://t.me/a/301?comment=2").parseState);
    }

    @Test
    public void l1_signatureIncludesHiddenTargetAndOffsets() {
        TLRPC.TL_message message = message(101, "😀 查看 查看");
        hidden(message, 3, 2, "https://t.me/first/301");
        String first = SavedLinkPreviewController.parse(USER_A, message).contentSignature;
        assertEquals(64, first.length());
        message.entities.get(0).url = "https://t.me/second/302";
        String second = SavedLinkPreviewController.parse(USER_A, message).contentSignature;
        assertNotEquals(first, second);
        message.entities.get(0).offset = 6;
        assertNotEquals(second, SavedLinkPreviewController.parse(USER_A, message).contentSignature);
        message.entities.clear();
        message.message = "https://t.me/first/301 https://t.me/second/302";
        first = SavedLinkPreviewController.parse(USER_A, message).contentSignature;
        message.message = "https://t.me/second/302 https://t.me/first/301";
        assertNotEquals(first, SavedLinkPreviewController.parse(USER_A, message).contentSignature);
    }

    @Test
    public void l1_rejectsNonSavedAndUnstableMessages() throws Exception {
        for (int id : new int[]{0, -1}) {
            assertThrows(IllegalArgumentException.class, () -> SavedLinkPreviewController.parse(USER_A, message(id, "https://t.me/a/301")));
        }
        TLRPC.TL_message message = message(101, "https://t.me/a/301");
        message.peer_id.user_id = USER_B;
        assertThrows(IllegalArgumentException.class, () -> SavedLinkPreviewController.parse(USER_A, message));
        message.peer_id.user_id = USER_A;
        message.dialog_id = -CHANNEL_A;
        assertThrows(IllegalArgumentException.class, () -> SavedLinkPreviewController.parse(USER_A, message));
        assertThrows(IllegalArgumentException.class, () -> new SavedLinkPreviewStorage(root, 0, false));
        assertThrows(IllegalArgumentException.class, () -> new SavedLinkPreviewStorage(-1));
        assertFalse(storage.getDatabaseFile().exists());
    }

    @Test
    public void l1_realSchemaIsSeparateAndIndexed() throws Exception {
        assertEquals(0, load(101).size());
        SQLiteDatabase db = raw();
        assertEquals(1, number(db, "PRAGMA user_version"));
        assertEquals(1, number(db, "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'"));
        assertEquals(1, number(db, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'saved_link_references_by_source'"));
        assertEquals(0, number(db, "SELECT COUNT(*) FROM sqlite_master WHERE name LIKE 'local_saved_%'"));
        assertEquals(new File(root, "saved_link_previews/prod/" + USER_A + ".db"), storage.getDatabaseFile());
        SQLiteCursor cursor = db.queryFinalized("EXPLAIN QUERY PLAN SELECT saved_message_id FROM saved_link_references "
                + "WHERE source_dialog_id = ? AND source_message_id = ?", -CHANNEL_A, 301);
        try {
            assertTrue(cursor.next());
            assertTrue(cursor.stringValue(3).contains("saved_link_references_by_source"));
        } finally {
            cursor.dispose();
        }
    }

    @Test
    public void l1_sourceLookupAndOverwriteKeepSavedIdentity() throws Exception {
        SavedLinkReference first = reference(101, CHANNEL_A, 301).withSource(-CHANNEL_A, SavedLinkReference.AVAILABLE, 1700000000000L);
        SavedLinkReference duplicate = reference(102, CHANNEL_A, 301).withSource(-CHANNEL_A, SavedLinkReference.AVAILABLE, 1700000000000L);
        SavedLinkReference other = reference(103, CHANNEL_B, 301).withSource(-CHANNEL_B, SavedLinkReference.AVAILABLE, 1700000000000L);
        save(first, duplicate, other);
        ArrayList<SavedLinkReference> found = await(callback -> storage.findBySource(-CHANNEL_A, 301, callback));
        assertEquals(2, found.size());
        assertEquals(101, found.get(0).savedMessageId);
        assertEquals(102, found.get(1).savedMessageId);
        save(reference(101, CHANNEL_B, 302));
        assertEquals(0, load(101).get(101).lastSuccessAt);
        assertEquals(0, load(101).get(101).sourceDialogId);
        this.<Void>await(callback -> storage.remove(Collections.singleton(102), callback));
        found = await(callback -> storage.findBySource(-CHANNEL_A, 301, callback));
        assertTrue(found.isEmpty());
        assertEquals(2, load(101, 102, 103).size());
    }

    @Test
    public void l1_largeLookupAndSqlConstraints() throws Exception {
        ArrayList<SavedLinkReference> values = new ArrayList<>();
        ArrayList<Integer> ids = new ArrayList<>();
        for (int i = 1000; i < 1601; i++) {
            values.add(reference(i, CHANNEL_A, 301));
            ids.add(i);
        }
        this.<Void>await(callback -> storage.save(values, callback));
        ids.add(1000);
        ids.add(9999);
        SparseArray<SavedLinkReference> loaded = await(callback -> storage.load(ids, callback));
        assertEquals(601, loaded.size());
        assertNull(loaded.get(9999));
        SQLiteDatabase db = raw();
        assertThrows(SQLiteException.class, () -> execute(db, "UPDATE saved_link_references SET saved_message_id = 2147483648 WHERE saved_message_id = 1000"));
        assertThrows(SQLiteException.class, () -> execute(db, "UPDATE saved_link_references SET state = 1 WHERE saved_message_id = 1000"));
        assertThrows(SQLiteException.class, () -> execute(db, "UPDATE saved_link_references SET source_message_id = 0 WHERE saved_message_id = 1000"));
        Result<Void> result = call(callback -> storage.remove(Arrays.asList(1000, 0), callback));
        assertTrue(result.error instanceof IllegalArgumentException);
        assertNotNull(load(1000).get(1000));
    }

    @Test
    public void l1_closeReopenPreservesStatesAndNullableSources() throws Exception {
        SavedLinkReference ready = reference(101, CHANNEL_A, 301).withSource(-CHANNEL_A, SavedLinkReference.FAILED, 1700000000000L);
        SavedLinkReference none = SavedLinkPreviewController.parse(USER_A, message(102, "无链接"));
        SavedLinkReference unsupported = SavedLinkPreviewController.parse(USER_A, message(103, "https://t.me/a/301?comment=2"));
        save(ready, none, unsupported);
        close(storage);
        storage = store(USER_A, false);
        SparseArray<SavedLinkReference> values = load(101, 102, 103);
        assertEquals(3, values.size());
        assertEquals(ready.contentSignature, values.get(101).contentSignature);
        assertEquals(-CHANNEL_A, values.get(101).sourceDialogId);
        assertEquals(SavedLinkReference.FAILED, values.get(101).state);
        assertEquals(1700000000000L, values.get(101).lastSuccessAt);
        assertEquals(SavedLinkReference.NO_LINK, values.get(102).parseState);
        assertNull(values.get(102).originalUrl);
        assertEquals(SavedLinkReference.UNSUPPORTED, values.get(103).parseState);
    }

    @Test
    public void l1_accountAndEnvironmentFilesAreIsolated() throws Exception {
        save(reference(101, CHANNEL_A, 301));
        SavedLinkPreviewStorage other = store(USER_B, false);
        SavedLinkPreviewStorage test = store(USER_A, true);
        TLRPC.TL_message message = message(101, "https://t.me/other/302");
        message.peer_id.user_id = USER_B;
        message.dialog_id = USER_B;
        SavedLinkReference value = SavedLinkPreviewController.parse(USER_B, message);
        this.<Void>await(callback -> other.save(Collections.singleton(value), callback));
        assertEquals(0, this.<SparseArray<SavedLinkReference>>await(callback -> test.load(Collections.singleton(101), callback)).size());
        assertEquals(301, load(101).get(101).sourceMessageId);
        assertEquals(302, this.<SparseArray<SavedLinkReference>>await(callback -> other.load(Collections.singleton(101), callback)).get(101).sourceMessageId);
        Result<Void> invalid = call(callback -> storage.save(Collections.singleton(value), callback));
        assertTrue(invalid.error instanceof IllegalArgumentException);
        assertEquals(301, load(101).get(101).sourceMessageId);
    }

    @Test
    public void l1_midBatchFailureRollsBackAndRecovers() throws Exception {
        save(reference(101, CHANNEL_A, 301));
        SQLiteDatabase db = raw();
        execute(db, "CREATE TRIGGER fail_ref BEFORE INSERT ON saved_link_references "
                + "WHEN NEW.saved_message_id = 104 BEGIN SELECT RAISE(ABORT, '隔离中断'); END");
        Result<Void> result = call(callback -> storage.save(Arrays.asList(reference(101, CHANNEL_B, 302), reference(104, CHANNEL_B, 303)), callback));
        assertTrue(result.error instanceof SQLiteException);
        assertEquals(301, load(101, 104).get(101).sourceMessageId);
        assertNull(load(104).get(104));
        execute(db, "DROP TRIGGER fail_ref");
        save(reference(101, CHANNEL_B, 302), reference(104, CHANNEL_B, 303));
        assertEquals(302, load(101).get(101).sourceMessageId);
        assertEquals(303, load(104).get(104).sourceMessageId);
    }

    @Test
    public void l1_busyIsReportedAndExplicitRetryWorks() throws Exception {
        save(reference(101, CHANNEL_A, 301));
        SQLiteDatabase writer = raw();
        execute(writer, "BEGIN IMMEDIATE");
        try {
            Result<Void> result = call(callback -> storage.save(Collections.singleton(reference(102, CHANNEL_B, 302)), callback));
            assertTrue(result.error instanceof SQLiteException);
            assertEquals(5, ((SQLiteException) result.error).errorCode);
        } finally {
            execute(writer, "ROLLBACK");
        }
        assertNull(load(102).get(102));
        save(reference(102, CHANNEL_B, 302));
        assertEquals(302, load(102).get(102).sourceMessageId);
    }

    @Test
    public void l1_unknownVersionAndSchemaKeepExistingRecords() throws Exception {
        save(reference(101, CHANNEL_A, 301));
        close(storage);
        SQLiteDatabase db = raw();
        execute(db, "PRAGMA user_version = 99");
        storage = store(USER_A, false);
        Result<SparseArray<SavedLinkReference>> result = call(callback -> storage.load(Collections.singleton(101), callback));
        assertTrue(result.error instanceof SQLiteException);
        assertEquals(99, number(db, "PRAGMA user_version"));
        assertEquals(1, number(db, "SELECT COUNT(*) FROM saved_link_references"));
        execute(db, "PRAGMA user_version = 1");
        execute(db, "DROP INDEX saved_link_references_by_source");
        result = call(callback -> storage.load(Collections.singleton(101), callback));
        assertTrue(result.error instanceof SQLiteException);
        assertEquals(1, number(db, "SELECT COUNT(*) FROM saved_link_references"));
        execute(db, SavedLinkPreviewStorage.CREATE_INDEX);
        assertEquals(301, load(101).get(101).sourceMessageId);
    }

    @Test
    public void l1_damagedDatabaseIsNotSilentlyReplaced() throws Exception {
        File file = storage.getDatabaseFile();
        assertTrue(file.getParentFile().mkdirs());
        byte[] damaged = "隔离损坏文件".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file.toPath(), damaged);
        Result<SparseArray<SavedLinkReference>> result = call(callback -> storage.load(Collections.singleton(101), callback));
        assertTrue(result.error instanceof SQLiteException);
        assertArrayEquals(damaged, Files.readAllBytes(file.toPath()));
        assertTrue(file.delete());
        save(reference(101, CHANNEL_A, 301));
        assertEquals(301, load(101).get(101).sourceMessageId);
    }

    @Test
    public void l1_inputCollectionsAreSnapshotted() throws Exception {
        ArrayList<SavedLinkReference> values = new ArrayList<>(Collections.singleton(reference(101, CHANNEL_A, 301)));
        this.<Void>await(callback -> {
            storage.save(values, callback);
            values.clear();
            values.add(reference(102, CHANNEL_B, 302));
        });
        ArrayList<Integer> ids = new ArrayList<>(Collections.singleton(101));
        SparseArray<SavedLinkReference> result = await(callback -> {
            storage.load(ids, callback);
            ids.clear();
            ids.add(102);
        });
        assertEquals(1, result.size());
        assertNotNull(result.get(101));
        assertNull(load(102).get(102));
    }

    @Test
    public void l1_queuedWritesReadsAndCloseCompleteOnce() throws Exception {
        CountDownLatch done = new CountDownLatch(4);
        ArrayList<String> order = new ArrayList<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        main(() -> {
            storage.save(Collections.singleton(reference(101, CHANNEL_A, 301)), (ignored, failure) -> {
                error.set(failure);
                order.add("写入");
                done.countDown();
            });
            storage.load(Collections.singleton(101), (values, failure) -> {
                if (failure != null || values == null || values.get(101) == null) {
                    error.set(failure == null ? new Exception("排队读取未看到写入") : failure);
                }
                order.add("读取");
                done.countDown();
            });
            storage.close(() -> { order.add("关闭甲"); done.countDown(); });
            storage.close(() -> { order.add("关闭乙"); done.countDown(); });
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals(Arrays.asList("写入", "读取", "关闭甲", "关闭乙"), order);
        Result<SparseArray<SavedLinkReference>> result = call(callback -> storage.load(Collections.singleton(101), callback));
        assertTrue(result.error instanceof IllegalStateException);
        storage = store(USER_A, false);
        assertNotNull(load(101).get(101));
    }

    @Test
    public void l1_referenceOperationsDoNotChangeTags() throws Exception {
        LocalSavedTagsStorage tags = new LocalSavedTagsStorage(root, USER_A, false);
        try {
            LocalSavedTag tag = await(callback -> tags.createTag("本地标签", callback));
            SparseIntArray dates = new SparseIntArray();
            dates.put(101, 1700000000);
            this.<Void>await(callback -> tags.applyTags(dates, Collections.singleton(tag.id), Collections.emptyList(), callback));
            save(reference(101, CHANNEL_A, 301));
            this.<Void>await(callback -> storage.remove(Collections.singleton(101), callback));
            ArrayList<LocalSavedTag> values = await(tags::loadTags);
            assertEquals(1, values.size());
            assertEquals(1, values.get(0).messageCount);
            assertEquals(tag.id, values.get(0).id);
        } finally {
            CountDownLatch done = new CountDownLatch(1);
            tags.close(done::countDown);
            assertTrue(done.await(15, TimeUnit.SECONDS));
        }
    }

    @Test
    public void l1_controllerCachesAllParseStatesAcrossReopen() throws Exception {
        SavedLinkPreviewController controller = controller(storage);
        TLRPC.TL_message message = message(101, "没有链接");
        for (int i = 0; i < 20; i++) {
            assertEquals(SavedLinkReference.NO_LINK, reference(controller, message).parseState);
        }
        message.message = "https://t.me/a/301?comment=1";
        for (int i = 0; i < 20; i++) {
            assertEquals(SavedLinkReference.UNSUPPORTED, reference(controller, message).parseState);
        }
        message.message = "https://t.me/a/301";
        assertEquals(301, reference(controller, message).sourceMessageId);
        assertEquals(3, controller.getParseCount());
        main(controller::cleanup);
        close(storage);
        storage = store(USER_A, false);
        SavedLinkPreviewController restored = controller(storage);
        assertEquals(301, reference(restored, message).sourceMessageId);
        assertEquals(0, restored.getParseCount());
    }

    @Test
    public void l1_editInvalidatesOldSourceAndSuccessTime() throws Exception {
        TLRPC.TL_message message = message(101, "😀 查看");
        hidden(message, 3, 2, "https://t.me/first/301");
        SavedLinkReference before = SavedLinkPreviewController.parse(USER_A, message).withSource(-CHANNEL_A, SavedLinkReference.AVAILABLE, 1700000000000L);
        save(before);
        SavedLinkPreviewController controller = controller(storage);
        assertEquals(before.lastSuccessAt, reference(controller, message).lastSuccessAt);
        message.entities.get(0).url = "https://t.me/second/302";
        SavedLinkReference after = reference(controller, message);
        assertEquals(302, after.sourceMessageId);
        assertEquals(0, after.sourceDialogId);
        assertEquals(0, after.lastSuccessAt);
        assertNotEquals(before.contentSignature, after.contentSignature);
        assertEquals("😀 查看", message.message);
        message.entities.clear();
        after = reference(controller, message);
        assertEquals(SavedLinkReference.NO_LINK, after.parseState);
        assertEquals(SavedLinkReference.NO_LINK, load(101).get(101).parseState);
    }

    @Test
    public void l1_lateReadsDoNotRestoreEditedTarget() throws Exception {
        HeldStorage held = new HeldStorage(root);
        stores.add(held);
        SavedLinkPreviewController controller = controller(held);
        TLRPC.TL_message message = message(101, "查看");
        hidden(message, 0, 2, "https://t.me/first/301");
        CountDownLatch done = new CountDownLatch(2);
        Result<SavedLinkReference> old = new Result<>();
        Result<SavedLinkReference> current = new Result<>();
        main(() -> {
            controller.loadReference(message, (value, error) -> { old.value = value; old.error = error; old.calls.incrementAndGet(); done.countDown(); });
            message.entities.get(0).url = "https://t.me/second/302";
            controller.loadReference(message, (value, error) -> { current.value = value; current.error = error; current.calls.incrementAndGet(); done.countDown(); });
            held.reads.get(1).run();
            held.reads.get(0).run();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertTrue(old.error instanceof CancellationException);
        assertNull(old.value);
        assertNull(current.error);
        assertEquals(302, current.value.sourceMessageId);
        assertEquals(1, old.calls.get());
        assertEquals(1, current.calls.get());
        assertEquals(1, controller.getParseCount());
        assertEquals(302, load(101).get(101).sourceMessageId);
    }

    @Test
    public void l1_failedReadDoesNotPretendToParseOrSucceed() throws Exception {
        HeldStorage held = new HeldStorage(root);
        held.hold = false;
        held.fail = true;
        stores.add(held);
        SavedLinkPreviewController controller = controller(held);
        Result<SavedLinkReference> result = call(callback -> controller.loadReference(message(101, "https://t.me/a/301"), callback));
        assertTrue(result.error instanceof SQLiteException);
        assertNull(result.value);
        assertEquals(0, controller.getParseCount());
        assertEquals(301, reference(controller, message(101, "https://t.me/a/301")).sourceMessageId);
        assertEquals(1, controller.getParseCount());
    }

    @Test
    public void l1_accountChangeCancelsPendingResult() throws Exception {
        HeldStorage held = new HeldStorage(root);
        stores.add(held);
        SavedLinkPreviewController controller = controller(held);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        main(() -> {
            controller.loadReference(message(101, "https://t.me/a/301"), (value, error) -> { failure.set(error); done.countDown(); });
            user(USER_B);
            held.reads.get(0).run();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertTrue(failure.get() instanceof CancellationException);
        assertEquals(0, controller.getParseCount());
        assertNull(load(101).get(101));
        main(() -> user(USER_A));
    }

    @Test
    public void l1_cachedCallbackCannotEscapeAfterEditOrClose() throws Exception {
        SavedLinkPreviewController controller = controller(storage);
        TLRPC.TL_message message = message(101, "https://t.me/a/301");
        reference(controller, message);
        Result<SavedLinkReference> result = call(callback -> {
            controller.loadReference(message, callback);
            controller.cleanup();
        });
        assertTrue(result.error instanceof CancellationException);
        assertNull(result.value);
    }

    @Test
    public void l1_parallelBindingsShareOneParse() throws Exception {
        HeldStorage held = new HeldStorage(root);
        stores.add(held);
        SavedLinkPreviewController controller = controller(held);
        CountDownLatch done = new CountDownLatch(20);
        ArrayList<SavedLinkReference> values = new ArrayList<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        main(() -> {
            for (int i = 0; i < 20; i++) {
                controller.loadReference(message(101, "https://t.me/a/301"), (value, error) -> {
                    if (error != null) {
                        failure.set(error);
                    }
                    values.add(value);
                    done.countDown();
                });
            }
            assertEquals(1, held.reads.size());
            held.reads.get(0).run();
        });
        assertTrue(done.await(15, TimeUnit.SECONDS));
        assertNull(failure.get());
        assertEquals(20, values.size());
        assertEquals(1, controller.getParseCount());
        for (SavedLinkReference value : values) {
            assertSame(values.get(0), value);
        }
    }

    static TLRPC.TL_message message(int id, String text) {
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = id;
        message.message = text;
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = USER_A;
        message.dialog_id = USER_A;
        message.date = 1700000000;
        return message;
    }

    static void hidden(TLRPC.Message message, int offset, int length, String url) {
        TLRPC.TL_messageEntityTextUrl entity = new TLRPC.TL_messageEntityTextUrl();
        entity.offset = offset;
        entity.length = length;
        entity.url = url;
        message.entities.add(entity);
    }

    private static void user(long id) {
        TLRPC.TL_user user = new TLRPC.TL_user();
        user.id = id;
        user.self = true;
        user.first_name = "链接引用隔离测试";
        user.phone = "";
        UserConfig.getInstance(0).setCurrentUser(user);
        UserConfig.selectedAccount = 0;
        MessagesController.getInstance(0).putUser(user, false);
    }

    private SavedLinkReference parse(String text) {
        return SavedLinkPreviewController.parse(USER_A, message(101, text));
    }

    private SavedLinkReference reference(int id, long channel, int sourceId) {
        return SavedLinkPreviewController.parse(USER_A, message(id, "https://t.me/c/" + channel + "/" + sourceId));
    }

    private SavedLinkReference reference(SavedLinkPreviewController controller, TLRPC.Message message) throws Exception {
        return await(callback -> controller.loadReference(message, callback));
    }

    private SavedLinkPreviewStorage store(long user, boolean test) {
        SavedLinkPreviewStorage value = new SavedLinkPreviewStorage(root, user, test);
        stores.add(value);
        return value;
    }

    private SavedLinkPreviewController controller(SavedLinkPreviewStorage store) {
        SavedLinkPreviewController[] value = new SavedLinkPreviewController[1];
        main(() -> {
            value[0] = new SavedLinkPreviewController(0, store);
            controllers.add(value[0]);
        });
        return value[0];
    }

    private void save(SavedLinkReference... values) throws Exception {
        this.<Void>await(callback -> storage.save(Arrays.asList(values), callback));
    }

    private SparseArray<SavedLinkReference> load(Integer... ids) throws Exception {
        return await(callback -> storage.load(Arrays.asList(ids), callback));
    }

    private <T> Result<T> call(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        Result<T> result = new Result<>();
        CountDownLatch done = new CountDownLatch(1);
        main(() -> action.accept((value, error) -> {
            result.value = value;
            result.error = error;
            result.mainThread = Looper.myLooper() == Looper.getMainLooper();
            result.calls.incrementAndGet();
            done.countDown();
        }));
        assertTrue("链接引用回调超时", done.await(15, TimeUnit.SECONDS));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        assertTrue("回调必须在主线程", result.mainThread);
        assertEquals(1, result.calls.get());
        return result;
    }

    private <T> T await(Consumer<Utilities.Callback2<T, Exception>> action) throws Exception {
        Result<T> result = call(action);
        if (result.error != null) {
            throw new AssertionError(result.error);
        }
        return result.value;
    }

    private void close(SavedLinkPreviewStorage value) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        value.close(done::countDown);
        assertTrue(done.await(15, TimeUnit.SECONDS));
    }

    private SQLiteDatabase raw() throws Exception {
        SQLiteDatabase value = new SQLiteDatabase(storage.getDatabaseFile().getPath());
        connections.add(value);
        return value;
    }

    private void execute(SQLiteDatabase db, String sql) throws Exception {
        SQLiteCursor cursor = db.queryFinalized(sql);
        try {
            assertFalse(cursor.next());
        } finally {
            cursor.dispose();
        }
    }

    private long number(SQLiteDatabase db, String sql) throws Exception {
        SQLiteCursor cursor = db.queryFinalized(sql);
        try {
            assertTrue(cursor.next());
            return cursor.longValue(0);
        } finally {
            cursor.dispose();
        }
    }

    private static void main(Runnable runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(runnable);
    }

    private void delete(File file) throws Exception {
        String path = file.getCanonicalPath();
        assertTrue(path.equals(root.getCanonicalPath()) || path.startsWith(root.getCanonicalPath() + File.separator));
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) {
                delete(child);
            }
        }
        assertTrue("隔离样本未清理", file.delete());
    }
}
