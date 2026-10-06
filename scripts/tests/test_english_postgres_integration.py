"""Opt-in test on a NEW disposable PostgreSQL database, with a mock TEI HTTP API."""

import importlib.util
import io
import json
import os
import sys
import threading
import unittest
from contextlib import redirect_stderr, redirect_stdout
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_english_data as importer

TEST_DSN = os.environ.get("ENGLISH_TEST_DSN")
HAS_PSYCOPG = importlib.util.find_spec("psycopg") is not None


@unittest.skipUnless(TEST_DSN and HAS_PSYCOPG, "requires isolated ENGLISH_TEST_DSN and psycopg")
class PostgreSQLIntegrationTests(unittest.TestCase):
    def test_real_files_import_rerun_http_failure_sql_rollback_and_cascade(self):
        import psycopg
        from psycopg.conninfo import conninfo_to_dict
        from psycopg.rows import dict_row

        options = conninfo_to_dict(TEST_DSN)
        self.assertIn(options.get("host"), ("127.0.0.1", "localhost", "::1"))
        self.assertEqual(options.get("dbname"), "vita_english_script_test")
        self.assertEqual(options.get("user"), "english_test")
        data = importer.load_files(importer.parser().parse_args([]))
        state = {"bad_vector": False, "calls": 0}

        class MockTEI(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_GET(self):
                self.send_json({"model_id": importer.MODEL})

            def do_POST(self):
                payload = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                if payload.get("normalize") is not True or payload.get("truncate") is not True:
                    self.send_error(400)
                    return
                state["calls"] += 1
                vectors = [[1.0] + [0.0] * 767 for _ in payload["inputs"]]
                if state["bad_vector"]:
                    vectors[-1] = [1.0]
                self.send_json(vectors)

            def send_json(self, value):
                encoded = json.dumps(value).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(encoded)))
                self.end_headers()
                self.wfile.write(encoded)

        server = ThreadingHTTPServer(("127.0.0.1", 0), MockTEI)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        endpoint = f"http://127.0.0.1:{server.server_port}"

        def run(mode):
            out, err = io.StringIO(), io.StringIO()
            with redirect_stdout(out), redirect_stderr(err):
                code = importer.main(["--mode", mode, "--dsn", TEST_DSN, "--embedding-url", endpoint])
            return code, out.getvalue(), err.getvalue()

        with psycopg.connect(TEST_DSN, autocommit=True, row_factory=dict_row) as conn:
            # Refuse an existing schema BEFORE any test mutation.
            self.assertEqual(conn.execute("SELECT count(*) AS count FROM pg_tables WHERE schemaname='public'").fetchone()["count"], 0)
            migration_dir = importer.BE_ROOT / "src/main/resources/db/migration"
            migrations = sorted(migration_dir.glob("V*.sql"), key=lambda path: int(path.name.split("__", 1)[0][1:]))
            self.assertEqual([int(path.name.split("__", 1)[0][1:]) for path in migrations], list(range(1, 19)))
            for migration in migrations[:-1]:
                with conn.transaction():
                    conn.execute(migration.read_text(encoding="utf-8"))
            self.assertEqual(conn.execute("SELECT count(*) AS count FROM plans WHERE status='ACTIVE'").fetchone()["count"], 15)
            with conn.transaction():
                for index, (key, row) in enumerate(data["ko"].items()):
                    conn.execute("INSERT INTO faqs (id, source_faq_id, category, subcategory, question, answer, source_policy_ids, status) VALUES (%s,%s,%s,%s,%s,%s,%s,'ACTIVE')",
                                 (1000 + index * 3, key, row["category"], row["subcategory"], row["question"], row["answer"], row["source_policy_ids"]))

            def snapshot(table, key="id"):
                return conn.execute(f"SELECT row_to_json(t)::text AS row FROM {importer.identifier(table)} t ORDER BY {importer.identifier(key)}").fetchall()

            original = {table: snapshot(table) for table in ("faqs", "plans")}
            with self.subTest("missing English tables"):
                code, _, error = run("check")
                self.assertEqual(code, 1)
                self.assertIn("English table not found", error)
                self.assertEqual(state["calls"], 0)

            with conn.transaction():
                conn.execute(migrations[-1].read_text(encoding="utf-8"))
            for table in ("faqs", "plans"):
                self.assertEqual(snapshot(table), original[table], "V18 changed Korean/shared data")
            with self.subTest("read-only preflight"):
                code, _, error = run("check")
                self.assertEqual(code, 0, error)
                self.assertEqual(state["calls"], 0)
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM faqs_en").fetchone()["count"], 0)

            with self.subTest("first import of all real files"):
                code, out, error = run("import")
                self.assertEqual(code, 0, error)
                self.assertTrue(json.loads(out)["committed"])
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM faqs_en WHERE embedding IS NOT NULL").fetchone()["count"], 1052)
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM plans_en WHERE embedding IS NOT NULL").fetchone()["count"], 15)
                linked = conn.execute("SELECT f.source_faq_id, e.question, e.answer FROM faqs_en e JOIN faqs f ON f.id=e.faq_id").fetchall()
                self.assertEqual({r["source_faq_id"]: (r["question"], r["answer"]) for r in linked}, {k: (v["question"], v["answer"]) for k, v in data["en"].items()})

            created = conn.execute("SELECT faq_id, created_at FROM faqs_en ORDER BY faq_id").fetchall()
            with self.subTest("rerun has no duplicates and preserves creation time"):
                code, _, error = run("import")
                self.assertEqual(code, 0, error)
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM faqs_en").fetchone()["count"], 1052)
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM plans_en").fetchone()["count"], 15)
                self.assertEqual(conn.execute("SELECT faq_id, created_at FROM faqs_en ORDER BY faq_id").fetchall(), created)

            english_before = {"faqs_en": snapshot("faqs_en", "faq_id"), "plans_en": snapshot("plans_en", "plan_id")}
            with self.subTest("invalid HTTP vector aborts without writes"):
                state["bad_vector"] = True
                code, _, error = run("import")
                self.assertEqual(code, 1)
                self.assertIn("expected 768 dimensions", error)
                for table, key in (("faqs_en", "faq_id"), ("plans_en", "plan_id")):
                    self.assertEqual(snapshot(table, key), english_before[table])
                state["bad_vector"] = False

            conn.execute("CREATE FUNCTION reject_english_plan() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'intentional test failure'; END $$")
            conn.execute("CREATE TRIGGER reject_plan BEFORE INSERT OR UPDATE ON plans_en FOR EACH ROW EXECUTE FUNCTION reject_english_plan()")
            with self.subTest("plan write failure rolls back FAQ writes too"):
                code, _, error = run("import")
                self.assertEqual(code, 1)
                self.assertIn("no partial English import committed", error)
                for table, key in (("faqs_en", "faq_id"), ("plans_en", "plan_id")):
                    self.assertEqual(snapshot(table, key), english_before[table])
            conn.execute("DROP TRIGGER reject_plan ON plans_en")
            for table in ("faqs", "plans"):
                self.assertEqual(snapshot(table), original[table], "Korean/shared data changed")

            with self.subTest("deleting a disposable parent cascades to its translation"):
                conn.execute("DELETE FROM faqs WHERE id=1000")
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM faqs_en WHERE faq_id=1000").fetchone()["count"], 0)
                self.assertEqual(conn.execute("SELECT count(*) AS count FROM faqs_en").fetchone()["count"], 1051)


if __name__ == "__main__":
    unittest.main()
