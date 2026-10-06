import copy
import io
import json
import sys
import tempfile
import unittest
from contextlib import contextmanager, redirect_stderr
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import import_english_data as importer


def fixture():
    ko = {"faq_id": "FAQ-1", "category": "모바일", "subcategory": "요금제",
          "question": "질문", "answer": "답변", "source_policy_ids": ["TERMS:한글"]}
    en = dict(ko, question="Question?", answer="Answer.")
    plan = {"plan_code": "PLAN-1", "name": "Plan", "summary": "Summary", "description": "Description"}
    plan.update({field: None if field in importer.NUMERIC else "LIMITED" for field in importer.STRUCTURED})
    plan.update(monthly_fee=10000, status="ACTIVE")
    return {"ko": {"FAQ-1": ko}, "en": {"FAQ-1": en}, "plans": {"PLAN-1": plan}}


def db_rows(data):
    faq = dict(data["ko"]["FAQ-1"], id=10, source_faq_id="FAQ-1")
    plan = dict(data["plans"]["PLAN-1"], id=20)
    return [faq], [plan]


class FileTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(dir=Path(__file__).resolve().parent)
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.data = fixture()
        self.args = importer.parser().parse_args([])
        for attr, kind in (("faq_ko", "ko"), ("faq_en", "en"), ("plans_en", "plans")):
            path = self.root / (kind + ".jsonl")
            path.write_text("\n".join(json.dumps(row, ensure_ascii=False) for row in self.data[kind].values()) + "\n", encoding="utf-8")
            setattr(self.args, attr, path)
        self.args.expected_faq_count = self.args.expected_plan_count = 1
        self.args.taxonomy = self.root / "taxonomy.json"
        self.args.taxonomy.write_text(json.dumps({"categories": [{"category": "모바일", "subcategories": ["요금제"]}]}), encoding="utf-8")

    def rewrite_en(self, transform):
        row = transform(copy.deepcopy(self.data["en"]["FAQ-1"]))
        self.args.faq_en.write_text(json.dumps(row, ensure_ascii=False) + "\n", encoding="utf-8")

    def test_korean_categories_and_korean_source_ids_are_preserved(self):
        self.assertEqual(importer.load_files(self.args), self.data)

    def test_english_category_must_match_korean_source(self):
        self.rewrite_en(lambda row: dict(row, category="Mobile"))
        with self.assertRaisesRegex(importer.ImportFailure, "category differs"):
            importer.load_files(self.args)

    def test_duplicate_faq_id_rejected(self):
        self.args.faq_en.write_text(self.args.faq_en.read_text(encoding="utf-8") * 2, encoding="utf-8")
        with self.assertRaisesRegex(importer.ImportFailure, "duplicate"):
            importer.load_files(self.args)

    def test_unmatched_id_rejected(self):
        self.rewrite_en(lambda row: dict(row, faq_id="FAQ-2"))
        with self.assertRaisesRegex(importer.ImportFailure, "ID mismatch"):
            importer.load_files(self.args)

    def test_changed_sources_rejected(self):
        self.rewrite_en(lambda row: dict(row, source_policy_ids=["different"]))
        with self.assertRaisesRegex(importer.ImportFailure, "source_policy_ids differ"):
            importer.load_files(self.args)

    def test_untranslated_question_rejected(self):
        self.rewrite_en(lambda row: dict(row, question="한국어 질문"))
        with self.assertRaisesRegex(importer.ImportFailure, "untranslated"):
            importer.load_files(self.args)

    def test_original_list_bullet_is_preserved(self):
        self.rewrite_en(lambda row: dict(row, answer="ㆍOriginal bullet preserved."))
        importer.load_files(self.args)

    def test_default_mode_validates_without_database_dependency(self):
        argv = ["--faq-ko", str(self.args.faq_ko), "--faq-en", str(self.args.faq_en), "--plans-en", str(self.args.plans_en),
                "--taxonomy", str(self.args.taxonomy), "--expected-faq-count", "1", "--expected-plan-count", "1"]
        with patch("builtins.__import__", wraps=__import__) as imported, patch("sys.stdout", new_callable=io.StringIO) as output:
            self.assertEqual(importer.main(argv), 0)
        self.assertFalse(any(call.args[0] == "psycopg" for call in imported.call_args_list))
        self.assertEqual(json.loads(output.getvalue())["mode"], "validate")


class DatabaseGuardTests(unittest.TestCase):
    def test_matching_baseline_maps_stable_db_ids(self):
        data = fixture()
        self.assertEqual(importer.compare_database(data, *db_rows(data)), {"faqs": {"FAQ-1": 10}, "plans": {"PLAN-1": 20}})

    def test_old_active_faqs_block_import(self):
        data = fixture()
        faqs, plans = db_rows(data)
        faqs.append(dict(faqs[0], source_faq_id="OLD-FAQ", id=11))
        with self.assertRaisesRegex(importer.ImportFailure, "ID mismatch"):
            importer.compare_database(data, faqs, plans)

    def test_korean_text_drift_blocks_import(self):
        data = fixture()
        faqs, plans = db_rows(data)
        faqs[0]["answer"] = "다른 답변"
        with self.assertRaisesRegex(importer.ImportFailure, "differs in answer"):
            importer.compare_database(data, faqs, plans)

    def test_plan_price_drift_blocks_import(self):
        data = fixture()
        faqs, plans = db_rows(data)
        plans[0]["monthly_fee"] += 1
        with self.assertRaisesRegex(importer.ImportFailure, "monthly_fee"):
            importer.compare_database(data, faqs, plans)

    def test_invalid_schema_identifier_rejected(self):
        for schema in ('public"; DELETE FROM faqs;--', "public.faqs", "", "a" * 64):
            with self.subTest(schema=schema):
                with self.assertRaises(importer.ImportFailure):
                    importer.table_name(schema, "faqs_en")

    def test_missing_english_table_stops_before_update(self):
        class Result:
            def fetchall(self):
                return []
        class Connection:
            def execute(self, query, params):
                if not query.startswith("SELECT"):
                    raise AssertionError("Unexpected DB mutation")
                return Result()
        with self.assertRaisesRegex(importer.ImportFailure, "English table not found"):
            importer.inspect_english_tables(Connection(), "public")

    def test_upsert_targets_only_english_table_with_parent_id(self):
        class Connection:
            def execute(self, query, values):
                self.query, self.values = query, values
                return type("Result", (), {"rowcount": 1})()
        conn = Connection()
        importer.upsert_english(conn, "public", "faqs", 10, fixture()["en"]["FAQ-1"], [1.0] + [0.0] * 767)
        self.assertTrue(conn.query.startswith('INSERT INTO "public"."faqs_en"'))
        self.assertIn('ON CONFLICT ("faq_id") DO UPDATE', conn.query)
        for field in ("category", "source_policy_ids", "status"):
            self.assertNotIn('"' + field + '"', conn.query)
        self.assertNotIn('INSERT INTO "public"."faqs"', conn.query)
        self.assertEqual(conn.values[0], 10)
        self.assertEqual(conn.values[1:3], ["Question?", "Answer."])
        self.assertEqual(conn.query.count("%s"), len(conn.values))

    def test_plan_upsert_uses_shared_parent_without_copying_price(self):
        class Connection:
            def execute(self, query, values):
                self.query, self.values = query, values
                return type("Result", (), {"rowcount": 1})()
        conn = Connection()
        importer.upsert_english(conn, "public", "plans", 20, fixture()["plans"]["PLAN-1"], [1.0] + [0.0] * 767)
        self.assertTrue(conn.query.startswith('INSERT INTO "public"."plans_en"'))
        self.assertIn('ON CONFLICT ("plan_id") DO UPDATE', conn.query)
        self.assertNotIn('"monthly_fee"', conn.query)
        self.assertEqual(conn.values[0], 20)
        self.assertEqual(conn.values[-1], "v1")
        self.assertEqual(conn.query.count("%s"), len(conn.values))

    def test_unknown_target_cannot_write_to_arbitrary_table(self):
        with self.assertRaises(importer.ImportFailure):
            importer.upsert_english(None, "public", "users", 1, {}, [])

    def test_second_table_failure_rolls_back_first_table(self):
        class Result:
            def fetchall(self):
                return [{"id": 10, "question": "Question?", "answer": "Answer.", "has_embedding": True,
                         "embedding_model": importer.MODEL, "embedding_version": "v2"}]
        class Connection:
            def __init__(self):
                self.pending = []
                self.committed = []
                self.rolled_back = False
            @contextmanager
            def transaction(self):
                try:
                    yield
                except Exception:
                    self.pending.clear()
                    self.rolled_back = True
                    raise
                else:
                    self.committed.extend(self.pending)
            def execute(self, query):
                return Result()
        data, conn = fixture(), Connection()
        ids = {"faqs": {"FAQ-1": 10}, "plans": {"PLAN-1": 20}}
        def update(connection, schema, table, *args):
            if table == "plans":
                raise importer.ImportFailure("simulated plan write failure")
            connection.pending.append(table)
        with patch.object(importer, "inspect_english_tables"), patch.object(importer, "check_database", return_value=ids), patch.object(importer, "upsert_english", side_effect=update):
            with self.assertRaisesRegex(importer.ImportFailure, "plan write failure"):
                importer.save_embeddings(conn, "public", data, ids, {"faqs": {"FAQ-1": []}, "plans": {"PLAN-1": []}})
        self.assertTrue(conn.rolled_back)
        self.assertEqual(conn.committed, [])

    def test_changed_parent_ids_abort_before_any_english_write(self):
        class Connection:
            @contextmanager
            def transaction(self):
                yield
            def execute(self, query):
                return None
        ids = {"faqs": {"FAQ-1": 10}, "plans": {"PLAN-1": 20}}
        changed = {"faqs": {"FAQ-1": 99}, "plans": {"PLAN-1": 20}}
        with patch.object(importer, "inspect_english_tables"), patch.object(importer, "check_database", return_value=changed), patch.object(importer, "upsert_english") as write:
            with self.assertRaisesRegex(importer.ImportFailure, "DB row IDs changed"):
                importer.save_embeddings(Connection(), "public", fixture(), ids, {})
        write.assert_not_called()


class EmbeddingTests(unittest.TestCase):
    def test_http_request_uses_existing_baseline_template_and_normalization(self):
        calls = []
        def request(url, payload, timeout):
            calls.append((url, payload))
            if url.endswith("/info"):
                return {"model_id": importer.MODEL}
            return [[1.0] + [0.0] * 767 for _ in payload["inputs"]]
        with patch.object(importer, "request_json", side_effect=request), redirect_stderr(io.StringIO()):
            result = importer.embed_documents(fixture(), "http://localhost:8081", 32, 60)
        payload = calls[1][1]
        self.assertEqual(payload, {"inputs": ["passage: 질문: Question?\n답변: Answer.", "passage: Description"], "normalize": True, "truncate": True})
        self.assertEqual(set(result["faqs"]), {"FAQ-1"})
        self.assertEqual(set(result["plans"]), {"PLAN-1"})

    def test_wrong_model_stops_before_embedding(self):
        with patch.object(importer, "request_json", return_value={"model_id": "other-model"}) as request:
            with self.assertRaises(importer.ImportFailure):
                importer.embed_documents(fixture(), "http://localhost:8081", 32, 60)
        self.assertEqual(request.call_count, 1)

    def test_invalid_embedding_responses_rejected(self):
        valid = [1.0] + [0.0] * 767
        invalid = ([], [[1.0]], [[float("nan")] + [0.0] * 767], [[True] + [0.0] * 767], [[0.0] * 768])
        for response in invalid:
            with self.subTest(response_length=len(response)):
                with self.assertRaises(importer.ImportFailure):
                    importer.validate_vectors(response, 1)
        self.assertEqual(importer.validate_vectors([valid], 1), [valid])


if __name__ == "__main__":
    unittest.main()
