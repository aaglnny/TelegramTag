import argparse
from contextlib import closing, contextmanager
from datetime import datetime
import hashlib
import io
import json
from pathlib import Path
import re
import sqlite3
import sys
import tempfile
import unittest


STORAGE_SOURCE = (
    Path(__file__).resolve().parents[1]
    / "TMessagesProj/src/main/java/org/telegram/messenger/LocalSavedTagsStorage.java"
)


def read_sql():
    source = STORAGE_SOURCE.read_text(encoding="utf-8")
    statements = {}
    for match in re.finditer(r"static final String (\w+)\s*=\s*(.*?);", source, re.S):
        name, expression = match.groups()
        tokens = re.findall(r'"(?:\\.|[^"\\])*"|[A-Z][A-Z0-9_]*', expression)
        remainder = re.sub(r'"(?:\\.|[^"\\])*"|[A-Z][A-Z0-9_]*|[\s+]', "", expression)
        if remainder:
            raise ValueError(f"无法读取正式 SQL 常量：{name}")
        statements[name] = "".join(
            json.loads(token) if token.startswith('"') else statements[token]
            for token in tokens
        )
    required = {
        "CREATE_TAGS_SQL", "CREATE_MESSAGE_TAGS_SQL", "CREATE_MESSAGE_TAGS_INDEX_SQL",
        "INSERT_TAG_SQL", "RENAME_TAG_SQL", "DELETE_TAG_SQL", "ADD_MESSAGE_TAG_SQL",
        "REMOVE_MESSAGE_TAG_SQL", "SELECT_TAGS_SQL", "SELECT_TAG_SQL",
        "SELECT_MESSAGES_SQL", "SELECT_OLDER_MESSAGES_SQL",
        "SELECT_MESSAGE_TAGS_SQL", "MESSAGE_TAGS_ORDER_SQL",
    }
    if not required.issubset(statements):
        raise ValueError(f"缺少正式 SQL：{sorted(required - statements.keys())}")
    return statements


SQL = read_sql()


@contextmanager
def savepoint(connection, name="local_tags_write"):
    connection.execute(f"SAVEPOINT {name}")
    try:
        yield
        connection.execute(f"RELEASE SAVEPOINT {name}")
    except Exception:
        connection.execute(f"ROLLBACK TO SAVEPOINT {name}")
        connection.execute(f"RELEASE SAVEPOINT {name}")
        raise


class LocalSavedTagsSqlTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix="local_saved_tags_sql_")
        self.root = Path(self.directory.name).resolve()
        self.path = self.root / "tags.db"
        self.db = sqlite3.connect(self.path, isolation_level=None, timeout=0)
        self.db.execute("PRAGMA journal_mode = WAL")
        self.db.execute("PRAGMA synchronous = FULL")
        self.db.execute("PRAGMA foreign_keys = ON")
        with savepoint(self.db, "local_tags_schema"):
            self.db.execute(SQL["CREATE_TAGS_SQL"])
            self.db.execute(SQL["CREATE_MESSAGE_TAGS_SQL"])
            self.db.execute(SQL["CREATE_MESSAGE_TAGS_INDEX_SQL"])
            self.db.execute("PRAGMA user_version = 1")

    def tearDown(self):
        self.db.close()
        if Path(self.directory.name).resolve() != self.root:
            raise RuntimeError("测试临时目录发生变化，停止清理")
        self.directory.cleanup()

    def tag(self, name, created=1000, key=None):
        cursor = self.db.execute(SQL["INSERT_TAG_SQL"], (name, key if key is not None else name, created))
        return cursor.lastrowid

    def seed(self):
        a = self.tag("学习", 1000)
        b = self.tag("项目资料", 2000)
        c = self.tag("待处理", 3000)
        self.db.executemany(SQL["ADD_MESSAGE_TAG_SQL"], [
            (101, a, 1700000003), (102, b, 1700000002),
            (102, c, 1700000002), (103, a, 1700000002), (103, b, 1700000002),
        ])
        return a, b, c

    def relations(self):
        return self.db.execute(
            "SELECT message_id, tag_id, message_date FROM local_saved_message_tags ORDER BY message_id, tag_id"
        ).fetchall()

    def history(self, count):
        tag_id = self.tag("分页")
        with savepoint(self.db):
            self.db.executemany(SQL["ADD_MESSAGE_TAG_SQL"], [
                (message_id, tag_id, 1700000000 + (message_id - 1001) // 7)
                for message_id in range(1001, 1001 + count)
            ])
        return tag_id

    def page(self, tag_id, before=None):
        if before is None:
            rows = self.db.execute(SQL["SELECT_MESSAGES_SQL"], (tag_id, 51)).fetchall()
        else:
            rows = self.db.execute(
                SQL["SELECT_OLDER_MESSAGES_SQL"], (tag_id, before[1], before[0], 51)
            ).fetchall()
        return rows[:50], len(rows) > 50

    def test_t1_01_schema_and_connection(self):
        """T1-01：结构、连接参数及空库，不生成测试标签。"""
        self.assertEqual(1, self.db.execute("PRAGMA user_version").fetchone()[0])
        self.assertEqual("wal", self.db.execute("PRAGMA journal_mode").fetchone()[0])
        self.assertEqual(2, self.db.execute("PRAGMA synchronous").fetchone()[0])
        self.assertEqual(1, self.db.execute("PRAGMA foreign_keys").fetchone()[0])
        tables = self.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name"
        ).fetchall()
        self.assertEqual([("local_saved_message_tags",), ("local_saved_tags",)], tables)
        self.assertEqual([], self.db.execute(SQL["SELECT_TAGS_SQL"]).fetchall())
        self.assertEqual([], self.relations())
        columns = self.db.execute("PRAGMA table_info(local_saved_message_tags)").fetchall()
        self.assertEqual(["message_id", "tag_id", "message_date"], [row[1] for row in columns])
        self.assertEqual([1, 2, 0], [row[5] for row in columns])
        foreign_key = self.db.execute("PRAGMA foreign_key_list(local_saved_message_tags)").fetchone()
        self.assertEqual(("local_saved_tags", "tag_id", "id", "CASCADE"),
                         (foreign_key[2], foreign_key[3], foreign_key[4], foreign_key[6]))

    def test_t1_03_unique_normalized_key(self):
        """T1-03：验证数据库唯一键；不冒充 Java 规范化测试。"""
        self.tag("Android", key="android")
        with self.assertRaises(sqlite3.IntegrityError):
            self.tag("android", key="android")
        self.tag("é", key="é")
        with self.assertRaises(sqlite3.IntegrityError):
            self.tag("e\u0301", key="é")
        self.assertEqual(2, len(self.db.execute(SQL["SELECT_TAGS_SQL"]).fetchall()))

    def test_t1_04_rename_preserves_identity(self):
        """T1-04：改名保留编号、时间和关联，冲突失败保留原值。"""
        a, b, _ = self.seed()
        before = self.relations()
        self.db.execute(SQL["RENAME_TAG_SQL"], ("课程", "课程", a))
        tag = self.db.execute(SQL["SELECT_TAG_SQL"], (a,)).fetchone()
        self.assertEqual((a, "课程", "课程", 1000, 2), tag)
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(SQL["RENAME_TAG_SQL"], ("项目资料", "项目资料", a))
        self.assertEqual(tag, self.db.execute(SQL["SELECT_TAG_SQL"], (a,)).fetchone())
        self.db.execute(SQL["RENAME_TAG_SQL"], ("课程", "课程", a))
        self.assertEqual(before, self.relations())
        self.assertEqual(2, self.db.execute(SQL["SELECT_TAG_SQL"], (b,)).fetchone()[4])

    def test_t1_05_delete_cascades_without_reusing_id(self):
        """T1-05：删除只级联对应关联，编号不复用。"""
        a, b, c = self.seed()
        self.db.execute(SQL["DELETE_TAG_SQL"], (c,))
        new_id = self.tag("新标签")
        self.assertGreater(new_id, c)
        self.assertEqual({a, b}, {row[1] for row in self.relations()})
        self.assertEqual(0, self.db.execute(SQL["SELECT_TAG_SQL"], (new_id,)).fetchone()[4])
        self.db.execute(SQL["DELETE_TAG_SQL"], (c,))
        self.assertEqual(4, len(self.relations()))

    def test_t1_06_append_is_idempotent(self):
        """T1-06：批量追加保留旧关联，重复添加不重复计数。"""
        a, b, c = self.seed()
        for _ in range(2):
            with savepoint(self.db):
                self.db.executemany(SQL["ADD_MESSAGE_TAG_SQL"], [(101, c, 1700000003), (102, c, 1700000002)])
        self.assertEqual({a, c}, {row[1] for row in self.relations() if row[0] == 101})
        self.assertEqual({b, c}, {row[1] for row in self.relations() if row[0] == 102})
        self.assertEqual(2, self.db.execute(SQL["SELECT_TAG_SQL"], (c,)).fetchone()[4])

    def test_t1_07_remove_only_requested_relations(self):
        """T1-07：移除指定关联，重复移除无副作用。"""
        a, b, c = self.seed()
        for _ in range(2):
            with savepoint(self.db):
                self.db.executemany(SQL["REMOVE_MESSAGE_TAG_SQL"], [(102, b), (103, b), (999, b)])
        self.assertEqual([(101, a, 1700000003), (102, c, 1700000002), (103, a, 1700000002)], self.relations())
        self.assertEqual(0, self.db.execute(SQL["SELECT_TAG_SQL"], (b,)).fetchone()[4])

    def test_t1_08_add_and_remove_together(self):
        """T1-08：同一保存点应用增删。"""
        a, _, c = self.seed()
        with savepoint(self.db):
            self.db.execute(SQL["REMOVE_MESSAGE_TAG_SQL"], (101, a))
            self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (101, c, 1700000003))
        self.assertEqual([(101, c, 1700000003)], [row for row in self.relations() if row[0] == 101])

    def test_t1_09_invalid_keys_are_not_ignored(self):
        """T1-09：仅忽略主键重复，其他约束失败并整批回滚。"""
        a = self.tag("学习")
        for invalid in [(0, a, 1), (-1, a, 1), (104, a + 999, 1), (104, a, None)]:
            with self.subTest(invalid=invalid), self.assertRaises(sqlite3.IntegrityError):
                with savepoint(self.db):
                    self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (101, a, 1))
                    self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], invalid)
            self.assertEqual([], self.relations())

    def test_t1_10_counts_and_stable_tag_order(self):
        """T1-10：计数及三级排序，空标签仍然可读。"""
        a, b, c = self.seed()
        d = self.tag("空标签甲", 4000)
        e = self.tag("空标签乙", 4000)
        rows = self.db.execute(SQL["SELECT_TAGS_SQL"]).fetchall()
        self.assertEqual([b, a, c, e, d], [row[0] for row in rows])
        self.assertEqual([2, 2, 1, 0, 0], [row[4] for row in rows])

    def test_t1_11_batch_message_query(self):
        """T1-11：按消息批量读取，不混入未请求消息。"""
        a, b, c = self.seed()
        ids = (101, 102, 101, 104, 999)
        statement = SQL["SELECT_MESSAGE_TAGS_SQL"] + ",".join("?" for _ in ids) + SQL["MESSAGE_TAGS_ORDER_SQL"]
        rows = self.db.execute(statement, ids).fetchall()
        self.assertEqual([(101, a), (102, c), (102, b)], [(row[0], row[1]) for row in rows])

    def test_t1_12_paging_and_lookahead(self):
        """T1-12：123 条跨同秒分页与所有数量边界。"""
        for count in (0, 1, 49, 50, 51, 100, 101, 123):
            with self.subTest(count=count):
                self.db.execute("DELETE FROM local_saved_tags")
                tag_id = self.history(count)
                before = None
                found = []
                sizes = []
                while True:
                    page, more = self.page(tag_id, before)
                    found.extend(row[0] for row in page)
                    sizes.append(len(page))
                    if not more:
                        break
                    self.assertEqual(50, len(page))
                    before = page[-1]
                self.assertEqual(list(range(1000 + count, 1000, -1)), found)
                if count == 123:
                    self.assertEqual([50, 50, 23], sizes)

    def test_t1_13_cursor_survives_deletion(self):
        """T1-13：删除页尾和未读记录后，旧游标仍然有效。"""
        tag_id = self.history(123)
        first, more = self.page(tag_id)
        self.assertTrue(more)
        self.assertEqual(1074, first[-1][0])
        self.db.executemany(SQL["REMOVE_MESSAGE_TAG_SQL"], [(1074, tag_id), (1022, tag_id)])
        self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (9000, tag_id, 1800000000))
        second, more = self.page(tag_id, first[-1])
        third, last_more = self.page(tag_id, second[-1])
        self.assertTrue(more)
        self.assertFalse(last_more)
        self.assertEqual([i for i in range(1073, 1000, -1) if i != 1022], [row[0] for row in second + third])
        self.assertEqual(9000, self.page(tag_id)[0][0][0])

    def test_t1_14_bound_name_is_literal(self):
        """T1-14：名称中的 SQL 符号保持普通文本。"""
        name = "资料'\";--"
        tag_id = self.tag(name)
        self.assertEqual(name, self.db.execute(SQL["SELECT_TAG_SQL"], (tag_id,)).fetchone()[1])
        self.db.execute(SQL["RENAME_TAG_SQL"], ("#学习", "#学习", tag_id))
        self.assertEqual("#学习", self.db.execute(SQL["SELECT_TAG_SQL"], (tag_id,)).fetchone()[1])
        self.db.execute(SQL["DELETE_TAG_SQL"], (tag_id,))
        self.assertEqual([], self.db.execute(SQL["SELECT_TAGS_SQL"]).fetchall())

    def test_t1_15_large_tag_id_and_saved_date(self):
        """T1-15：补充核对 SQLite 大编号和收藏时间存储。"""
        self.db.execute("INSERT INTO local_saved_tags(id, name, name_key, created_at) VALUES (?, ?, ?, ?)",
                        (4294967311, "学习", "学习", 1800000000000))
        self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (101, 4294967311, 1700000003))
        self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (101, 4294967311, 1900000000))
        self.assertEqual([(101, 4294967311, 1700000003)], self.relations())
        self.assertEqual(4294967311, self.db.execute(SQL["SELECT_TAGS_SQL"]).fetchone()[0])

    def test_t1_16_mid_batch_failure_rolls_back_all_changes(self):
        """T1-16：前一条已写入后中止，增删全部回滚并可重试。"""
        a, _, c = self.seed()
        before = self.relations()
        self.db.execute("CREATE TRIGGER fail_insert BEFORE INSERT ON local_saved_message_tags "
                        "WHEN NEW.message_id = 104 BEGIN SELECT RAISE(ABORT, '测试中断'); END")
        with self.assertRaises(sqlite3.IntegrityError):
            with savepoint(self.db):
                self.db.execute(SQL["REMOVE_MESSAGE_TAG_SQL"], (101, a))
                self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (101, c, 1700000003))
                self.db.execute(SQL["ADD_MESSAGE_TAG_SQL"], (104, c, 1700000001))
        self.assertEqual(before, self.relations())
        self.db.execute("DROP TRIGGER fail_insert")
        with savepoint(self.db):
            self.db.execute(SQL["REMOVE_MESSAGE_TAG_SQL"], (101, a))
            self.db.executemany(SQL["ADD_MESSAGE_TAG_SQL"], [(101, c, 1700000003), (104, c, 1700000001)])
        self.assertEqual(3, self.db.execute(SQL["SELECT_TAG_SQL"], (c,)).fetchone()[4])

    def test_t1_19_reopen_file(self):
        """T1-19：关闭并重开文件，数据和 WAL 保留。"""
        self.seed()
        before = self.relations()
        self.db.close()
        self.db = sqlite3.connect(self.path, isolation_level=None)
        self.db.execute("PRAGMA foreign_keys = ON")
        self.db.execute("PRAGMA synchronous = FULL")
        self.assertEqual(before, self.relations())
        self.assertEqual("wal", self.db.execute("PRAGMA journal_mode").fetchone()[0])
        self.assertEqual(1, self.db.execute("PRAGMA user_version").fetchone()[0])
        self.assertEqual([], self.db.execute("PRAGMA foreign_key_check").fetchall())

    def test_t1_23_schema_failure_is_atomic(self):
        """T1-23：建表和版本设置失败时不留下半套结构。"""
        with closing(sqlite3.connect(self.root / "schema_failure.db", isolation_level=None)) as other:
            with self.assertRaises(sqlite3.OperationalError):
                with savepoint(other, "local_tags_schema"):
                    other.execute(SQL["CREATE_TAGS_SQL"])
                    other.execute(SQL["CREATE_MESSAGE_TAGS_SQL"])
                    other.execute("PRAGMA user_version = 1")
                    other.execute(SQL["CREATE_MESSAGE_TAGS_INDEX_SQL"])
                    other.execute(SQL["CREATE_MESSAGE_TAGS_INDEX_SQL"])
            self.assertEqual(0, other.execute("PRAGMA user_version").fetchone()[0])
            self.assertEqual([], other.execute("SELECT name FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'").fetchall())

    def test_t1_25_queries_use_indexes(self):
        """T1-25：分页与按消息读取使用对应索引。"""
        tag_id = self.history(10000)
        page_plan = self.db.execute(
            "EXPLAIN QUERY PLAN " + SQL["SELECT_OLDER_MESSAGES_SQL"], (tag_id, 1700001000, 8000, 51)
        ).fetchall()
        self.assertIn("local_saved_message_tags_by_tag", " ".join(row[3] for row in page_plan))
        statement = SQL["SELECT_MESSAGE_TAGS_SQL"] + "?,?,?" + SQL["MESSAGE_TAGS_ORDER_SQL"]
        message_plan = self.db.execute("EXPLAIN QUERY PLAN " + statement, (1001, 1002, 1003)).fetchall()
        self.assertIn("sqlite_autoindex_local_saved_message_tags", " ".join(row[3] for row in message_plan))


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser(description="验证正式本地标签 SQL，不编译或操作设备。")
    parser.add_argument("--report-dir", type=Path)
    args = parser.parse_args()
    output = io.StringIO()
    suite = unittest.defaultTestLoader.loadTestsFromTestCase(LocalSavedTagsSqlTest)
    result = unittest.TextTestRunner(stream=output, verbosity=2).run(suite)
    report = output.getvalue()
    print(report, end="")
    metadata = {
        "scope": "仅主机 SQL 测试，不代表 Java、JNI 或界面验证",
        "time": datetime.now().astimezone().isoformat(),
        "python": sys.version,
        "python_executable": sys.executable,
        "sqlite": sqlite3.sqlite_version,
        "source": str(STORAGE_SOURCE),
        "source_sha256": hashlib.sha256(STORAGE_SOURCE.read_bytes()).hexdigest(),
        "test_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "tests_run": result.testsRun,
        "failures": len(result.failures),
        "errors": len(result.errors),
        "successful": result.wasSuccessful(),
    }
    print(json.dumps(metadata, ensure_ascii=False, indent=2))
    if args.report_dir is not None:
        args.report_dir.mkdir(parents=True, exist_ok=True)
        (args.report_dir / "host-sql-tests.txt").write_text(report, encoding="utf-8")
        (args.report_dir / "host-sql-tests.json").write_text(
            json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
    sys.exit(0 if result.wasSuccessful() else 1)
