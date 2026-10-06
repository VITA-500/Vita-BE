#!/usr/bin/env python3
"""Validate, embed and upsert translations into local faqs_en/plans_en tables."""

import argparse
import hashlib
import json
import math
import os
import re
import sys
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

MODEL = "intfloat/multilingual-e5-base"
DIMENSIONS = 768
ROOT = Path(__file__).resolve().parents[2]
BE_ROOT = Path(__file__).resolve().parents[1]
STRUCTURED = (
    "monthly_fee", "network_type", "target_group", "min_age", "max_age",
    "data_policy", "base_data_mb", "exhausted_speed_kbps", "voice_policy",
    "voice_minutes", "sms_policy", "sms_count", "status",
)
NUMERIC = {"monthly_fee", "min_age", "max_age", "base_data_mb",
           "exhausted_speed_kbps", "voice_minutes", "sms_count"}
ENGLISH_TABLES = {"faqs": "faqs_en", "plans": "plans_en"}
PARENT_KEYS = {"faqs": "faq_id", "plans": "plan_id"}
TEXT_FIELDS = {"faqs": ("question", "answer"), "plans": ("name", "summary", "description")}
IDENTIFIER = re.compile(r"[a-zA-Z_][a-zA-Z_0-9]{0,62}\Z")
# The source uses U+318D (ㆍ) as a list bullet; preserve it as formatting.
HANGUL = re.compile(r"[\u1100-\u11ff\u3131-\u3163\uac00-\ud7a3]")


class ImportFailure(Exception):
    pass


def require(condition, message):
    if not condition:
        raise ImportFailure(message)


def identifier(value):
    require(isinstance(value, str) and IDENTIFIER.fullmatch(value), f"Invalid SQL identifier: {value!r}")
    return '"' + value + '"'


def table_name(schema, table):
    return f"{identifier(schema)}.{identifier(table)}"


def nonempty(row, field, context):
    value = row.get(field)
    require(isinstance(value, str) and value.strip(), f"{context}: missing/empty {field}")
    return value


def read_jsonl(path, key, expected):
    records = {}
    try:
        with path.open(encoding="utf-8-sig") as stream:
            for line_no, line in enumerate(stream, 1):
                require(line.strip(), f"{path.name}:{line_no}: blank JSONL record")
                row = json.loads(line)
                require(isinstance(row, dict), f"{path.name}:{line_no}: expected JSON object")
                record_id = nonempty(row, key, f"{path.name}:{line_no}")
                require(record_id not in records, f"{path.name}:{line_no}: duplicate {key}: {record_id}")
                records[record_id] = row
    except (OSError, ValueError) as exc:
        raise ImportFailure(f"Cannot read {path.name}: {exc}") from exc
    require(len(records) == expected, f"{path.name}: expected {expected} records, found {len(records)}")
    return records


def same_ids(expected, actual, context):
    missing, extra = set(expected) - set(actual), set(actual) - set(expected)
    require(not missing and not extra,
            f"{context}: ID mismatch; missing={len(missing)} {sorted(missing)[:5]}, extra={len(extra)} {sorted(extra)[:5]}")


def load_files(args):
    ko = read_jsonl(args.faq_ko, "faq_id", args.expected_faq_count)
    en = read_jsonl(args.faq_en, "faq_id", args.expected_faq_count)
    plans = read_jsonl(args.plans_en, "plan_code", args.expected_plan_count)
    same_ids(ko, en, "Korean/English FAQ")
    taxonomy = json.loads(args.taxonomy.read_text(encoding="utf-8-sig"))
    allowed = {(c["category"], sub) for c in taxonomy["categories"] for sub in c["subcategories"]}
    for faq_id, korean in ko.items():
        english = en[faq_id]
        for language, row in (("ko", korean), ("en", english)):
            for field in ("category", "subcategory", "question", "answer"):
                value = nonempty(row, field, f"FAQ {faq_id} ({language})")
                if language == "en" and field in ("question", "answer"):
                    require(not HANGUL.search(value), f"FAQ {faq_id}: untranslated Hangul in {field}")
            sources = row.get("source_policy_ids")
            require(isinstance(sources, list) and sources and
                    all(isinstance(s, str) and s.strip() for s in sources), f"FAQ {faq_id}: invalid source_policy_ids")
        require((korean["category"], korean["subcategory"]) in allowed, f"FAQ {faq_id}: unknown Korean category pair")
        for field in ("category", "subcategory"):
            require(korean[field] == english[field], f"FAQ {faq_id}: Korean/English {field} differs; keep Korean category names")
        require(korean["source_policy_ids"] == english["source_policy_ids"], f"FAQ {faq_id}: source_policy_ids differ")
    for code, row in plans.items():
        for field in ("name", "summary", "description"):
            require(not HANGUL.search(nonempty(row, field, f"Plan {code}")), f"Plan {code}: untranslated Hangul in {field}")
        for field in STRUCTURED:
            require(field in row, f"Plan {code}: missing {field}")
            if field in NUMERIC:
                value = row[field]
                require(value is None or (type(value) is int and value >= 0), f"Plan {code}: invalid {field}")
            else:
                nonempty(row, field, f"Plan {code}")
        require(type(row["monthly_fee"]) is int, f"Plan {code}: monthly_fee must be an integer")
        require(row["status"] == "ACTIVE", f"Plan {code}: expected ACTIVE status")
    return {"ko": ko, "en": en, "plans": plans}


def inspect_english_tables(conn, schema):
    for parent, table in ENGLISH_TABLES.items():
        rows = conn.execute(
            "SELECT a.attname, a.attnum, a.attnotnull, format_type(a.atttypid, a.atttypmod) AS type "
            "FROM pg_attribute a JOIN pg_class c ON c.oid=a.attrelid "
            "JOIN pg_namespace n ON n.oid=c.relnamespace "
            "WHERE n.nspname=%s AND c.relname=%s AND a.attnum>0 AND NOT a.attisdropped",
            (schema, table),
        ).fetchall()
        available = {r["attname"]: r for r in rows}
        require(available, f"English table not found: {schema}.{table}. Apply Flyway V18 before importing English data.")
        required = (PARENT_KEYS[parent], *TEXT_FIELDS[parent], "embedding", "embedding_model", "embedding_version", "embedded_at", "created_at", "updated_at")
        for column in required:
            require(column in available, f"{table}: missing {column}; see V18__create_english_translation_tables.sql")
            column_type = available[column]["type"]
            if column == PARENT_KEYS[parent]:
                require(column_type == "bigint", f"{table}.{column}: expected bigint")
            elif column == "embedding":
                require(column_type == f"vector({DIMENSIONS})", f"{table}.embedding: expected vector({DIMENSIONS}), found {column_type}")
            elif column in ("embedded_at", "created_at", "updated_at"):
                require(column_type.startswith("timestamp"), f"{table}.{column}: expected timestamp")
            else:
                require(column_type == "text" or column_type.startswith("character varying"), f"{table}.{column}: expected text/varchar")
            if column in (PARENT_KEYS[parent], *TEXT_FIELDS[parent]):
                require(available[column]["attnotnull"], f"{table}.{column}: expected NOT NULL")
        constraints = conn.execute(
            "SELECT c.contype, c.conkey, c.confdeltype, p.relname AS parent_table, "
            "n.nspname AS parent_schema, "
            "ARRAY(SELECT a.attname::text FROM unnest(c.confkey) WITH ORDINALITY AS k(num, ord) "
            "JOIN pg_attribute a ON a.attrelid=c.confrelid AND a.attnum=k.num ORDER BY k.ord) AS parent_columns "
            "FROM pg_constraint c LEFT JOIN pg_class p ON p.oid=c.confrelid "
            "LEFT JOIN pg_namespace n ON n.oid=p.relnamespace WHERE c.conrelid=%s::regclass",
            (table_name(schema, table),),
        ).fetchall()
        key_number = available[PARENT_KEYS[parent]]["attnum"]
        require(any(c["contype"] == "p" and c["conkey"] == [key_number] for c in constraints), f"{table}: expected primary key on {PARENT_KEYS[parent]}")
        require(any(c["contype"] == "f" and c["conkey"] == [key_number] and
                    c["parent_schema"] == schema and c["parent_table"] == parent and
                    c["parent_columns"] == ["id"] and c["confdeltype"] == "c" for c in constraints),
                f"{table}: expected foreign key to {parent}(id) ON DELETE CASCADE")


def compare_database(data, faq_rows, plan_rows):
    faqs, plans = {}, {}
    for row in faq_rows:
        key = row["source_faq_id"]
        require(isinstance(key, str) and key and key not in faqs, "Active DB FAQ has missing/duplicate source_faq_id")
        faqs[key] = row
    for row in plan_rows:
        key = row["plan_code"]
        require(isinstance(key, str) and key and key not in plans, "Active DB plan has missing/duplicate plan_code")
        plans[key] = row
    same_ids(data["ko"], faqs, "Active DB FAQ / Korean baseline")
    same_ids(data["plans"], plans, "Active DB plans / English plans")
    for key, source in data["ko"].items():
        for field in ("question", "answer", "category", "subcategory", "source_policy_ids"):
            require(faqs[key][field] == source[field], f"DB FAQ {key}: Korean baseline differs in {field}; import the correct Korean baseline first")
    for key, source in data["plans"].items():
        for field in STRUCTURED:
            require(plans[key][field] == source[field], f"DB plan {key}: structured value differs in {field}")
    return {"faqs": {key: row["id"] for key, row in faqs.items()}, "plans": {key: row["id"] for key, row in plans.items()}}


def check_database(conn, schema, data):
    faqs = conn.execute(f"SELECT id, source_faq_id, category, subcategory, question, answer, source_policy_ids FROM {table_name(schema, 'faqs')} WHERE status='ACTIVE'").fetchall()
    fields = ", ".join(identifier(field) for field in STRUCTURED)
    plans = conn.execute(f"SELECT id, plan_code, {fields} FROM {table_name(schema, 'plans')} WHERE status='ACTIVE'").fetchall()
    return compare_database(data, faqs, plans)


def request_json(url, payload, timeout):
    body = None if payload is None else json.dumps(payload, ensure_ascii=False).encode("utf-8")
    request = Request(url, data=body, headers={"Content-Type": "application/json"})
    try:
        with urlopen(request, timeout=timeout) as response:
            return json.load(response)
    except (HTTPError, URLError, OSError, ValueError) as exc:
        # Do not echo endpoint credentials, response bodies, or document text.
        raise ImportFailure(f"Embedding service request failed ({type(exc).__name__})") from exc


def validate_vectors(vectors, count):
    require(isinstance(vectors, list) and len(vectors) == count, "Embedding response count mismatch")
    for index, vector in enumerate(vectors):
        require(isinstance(vector, list) and len(vector) == DIMENSIONS, f"Embedding {index}: expected {DIMENSIONS} dimensions")
        require(all(type(v) in (int, float) and math.isfinite(v) for v in vector), f"Embedding {index}: non-finite/non-numeric value")
        norm = math.sqrt(sum(v * v for v in vector))
        require(abs(norm - 1.0) <= 0.01, f"Embedding {index}: expected normalized vector, norm={norm:.4f}")
    return vectors


def embed_documents(data, endpoint, batch_size, timeout):
    endpoint = endpoint.rstrip("/")
    info = request_json(endpoint + "/info", None, timeout)
    require(isinstance(info, dict) and info.get("model_id") == MODEL, f"Embedding service must use {MODEL}")
    # Match the existing Java document template; only the FAQ content is translated.
    documents = [("faqs", key, f"passage: 질문: {row['question']}\n답변: {row['answer']}") for key, row in data["en"].items()]
    documents += [("plans", key, "passage: " + row["description"]) for key, row in data["plans"].items()]
    embeddings = {"faqs": {}, "plans": {}}
    for start in range(0, len(documents), batch_size):
        batch = documents[start:start + batch_size]
        response = request_json(endpoint + "/embed", {"inputs": [doc[2] for doc in batch], "normalize": True, "truncate": True}, timeout)
        for (table, key, _), vector in zip(batch, validate_vectors(response, len(batch))):
            embeddings[table][key] = vector
        print(f"Embedded {min(start + batch_size, len(documents))}/{len(documents)}", file=sys.stderr)
    return embeddings


def upsert_english(conn, schema, parent, row_id, row, vector):
    require(parent in ENGLISH_TABLES, "Unknown English import target")
    table, key = ENGLISH_TABLES[parent], PARENT_KEYS[parent]
    fields = TEXT_FIELDS[parent]
    columns = (key, *fields, "embedding", "embedding_model", "embedding_version", "embedded_at", "created_at", "updated_at")
    values = [row_id] + [row[field] for field in fields]
    values += ["[" + ",".join(str(value) for value in vector) + "]", MODEL, "v2" if parent == "faqs" else "v1"]
    placeholders = ["%s"] * (1 + len(fields)) + ["%s::vector", "%s", "%s", "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP"]
    updated = (*fields, "embedding", "embedding_model", "embedding_version", "embedded_at", "updated_at")
    assignments = ", ".join(f"{identifier(field)}=EXCLUDED.{identifier(field)}" for field in updated)
    result = conn.execute(
        f"INSERT INTO {table_name(schema, table)} ({', '.join(identifier(c) for c in columns)}) "
        f"VALUES ({', '.join(placeholders)}) ON CONFLICT ({identifier(key)}) DO UPDATE SET {assignments}", values)
    require(result.rowcount == 1, f"{table}: expected one English row upserted for parent id={row_id}")


def save_embeddings(conn, schema, data, ids, embeddings):
    # HTTP work is already complete. These writes commit together or all roll back.
    with conn.transaction():
        conn.execute("SET LOCAL lock_timeout = '5s'")
        locked = ", ".join(table_name(schema, table) for table in ("faqs", "plans", "faqs_en", "plans_en"))
        conn.execute(f"LOCK TABLE {locked} IN SHARE ROW EXCLUSIVE MODE")
        inspect_english_tables(conn, schema)
        require(check_database(conn, schema, data) == ids, "DB row IDs changed during embedding; retry")
        for parent, source in (("faqs", data["en"]), ("plans", data["plans"])):
            table = ENGLISH_TABLES[parent]
            for key, row in source.items():
                upsert_english(conn, schema, parent, ids[parent][key], row, embeddings[parent][key])
            text_fields = TEXT_FIELDS[parent]
            parent_key = PARENT_KEYS[parent]
            selected = ", ".join([f"e.{identifier(parent_key)} AS id"] + [f"e.{identifier(field)}" for field in text_fields] + ["e.embedding IS NOT NULL AS has_embedding", "e.embedding_model", "e.embedding_version"])
            stored = {r["id"]: r for r in conn.execute(f"SELECT {selected} FROM {table_name(schema, table)} e JOIN {table_name(schema, parent)} p ON p.id=e.{identifier(parent_key)} WHERE p.status='ACTIVE'").fetchall()}
            require(set(stored) == set(ids[parent].values()), f"{table}: post-import row count differs")
            for key, row in source.items():
                result = stored[ids[parent][key]]
                require(result["has_embedding"] and result["embedding_model"] == MODEL and
                        result["embedding_version"] == ("v2" if parent == "faqs" else "v1") and
                        all(result[field] == row[field] for field in text_fields), f"{table}: post-import verification failed for {key}")


def positive(value):
    number = int(value)
    if number <= 0:
        raise argparse.ArgumentTypeError("must be greater than zero")
    return number


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--mode", choices=("validate", "check", "import"), default="validate")
    result.add_argument("--faq-ko", type=Path, default=ROOT / "uplus_faq_all_cleaned_ko.jsonl")
    result.add_argument("--faq-en", type=Path, default=ROOT / "uplus_faq_all_cleaned_en.jsonl")
    result.add_argument("--plans-en", type=Path, default=ROOT / "plans_all_cleaned_en.jsonl")
    result.add_argument("--taxonomy", type=Path, default=BE_ROOT / "src/main/resources/data/faq/category/faq_generation_categories.json")
    result.add_argument("--expected-faq-count", type=positive, default=1052)
    result.add_argument("--expected-plan-count", type=positive, default=15)
    result.add_argument("--dsn", default=os.environ.get("ENGLISH_IMPORT_DSN", "postgresql://vita@localhost:5432/vita_local"))
    result.add_argument("--schema", default="public")
    result.add_argument("--embedding-url", default="http://localhost:8081")
    result.add_argument("--batch-size", type=positive, default=32)
    result.add_argument("--timeout", type=positive, default=60)
    return result


def main(argv=None):
    args = parser().parse_args(argv)
    try:
        identifier(args.schema)
        data = load_files(args)
        report = {"mode": args.mode, "faq_count": len(data["en"]), "plan_count": len(data["plans"]),
                  "sha256": {key: hashlib.sha256(path.read_bytes()).hexdigest() for key, path in
                             (("faq_ko", args.faq_ko), ("faq_en", args.faq_en), ("plans_en", args.plans_en))}}
        if args.mode != "validate":
            try:
                import psycopg
                from psycopg.rows import dict_row
            except ImportError as exc:
                raise ImportFailure("Install scripts/requirements-english.txt before using check/import mode") from exc
            try:
                with psycopg.connect(args.dsn, autocommit=True, row_factory=dict_row, connect_timeout=10) as conn:
                    require(conn.info.host in ("localhost", "127.0.0.1", "::1"), "This script supports local PostgreSQL only")
                    inspect_english_tables(conn, args.schema)
                    ids = check_database(conn, args.schema, data)
                    report.update(tables=ENGLISH_TABLES)
                    if args.mode == "import":
                        embeddings = embed_documents(data, args.embedding_url, args.batch_size, args.timeout)
                        save_embeddings(conn, args.schema, data, ids, embeddings)
                        report.update(embedding_model=MODEL, dimensions=DIMENSIONS, committed=True)
            except psycopg.Error as exc:
                raise ImportFailure(f"Database operation failed ({type(exc).__name__}); no partial English import committed") from exc
        print(json.dumps(report, ensure_ascii=False, indent=2))
        return 0
    except (ImportFailure, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
