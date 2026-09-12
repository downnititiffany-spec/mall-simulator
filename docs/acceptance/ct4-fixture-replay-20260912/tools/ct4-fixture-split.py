# -*- coding: utf-8 -*-
"""第二步：① 字节级无损切分验证；② 逐行**全部**错误枚举（CT-4 冲突登记表的对账基础）。ASCII-only 打印。"""
import json
from pathlib import Path

ROOT = Path(r"D:\Develop_code\GraduationProject")
FIX = ROOT / "tests/golden-dataset/events/golden-20260901.jsonl"
ACC = ROOT / "landing/accepted/30/r9-m1-123006.jsonl"
QUA = ROOT / "landing/quarantine/30/r9-m1-123006.jsonl"
SCHEMA = ROOT / "contract-specs/schemas/canonical-event.v1.schema.json"
OUT = ROOT / "docs/acceptance/ct4-fixture-replay-20260912/raw/all-errors.tsv"

fx, ac, qu = FIX.read_bytes(), ACC.read_bytes(), QUA.read_bytes()
print("sizes fix=%d accepted=%d quarantine=%d sum=%d" % (len(fx), len(ac), len(qu), len(ac) + len(qu)))
print("sum_equals_fixture=%s" % (len(ac) + len(qu) == len(fx)))
print("fixture_startswith_accepted=%s" % fx.startswith(ac))
print("fixture_endswith_quarantine=%s" % fx.endswith(qu))
print("fixture_is_concat=%s" % (fx == ac + qu))

acc_lines = ac.decode("utf-8").splitlines()
qua_lines = [l for l in qu.decode("utf-8").splitlines() if l.strip()]
fix_lines = [l for l in fx.decode("utf-8").splitlines() if l.strip()]
print("lines accepted=%d quarantine=%d fixture=%d" % (len(acc_lines), len(qua_lines), len(fix_lines)))
print("quarantine_is_tail=%s" % (fix_lines[len(acc_lines):] == qua_lines))

from jsonschema import Draft202012Validator
schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
v = Draft202012Validator(schema)
with OUT.open("w", encoding="utf-8", newline="\n") as fh:
    fh.write("line\tevent_id\tevent_type\terr_no\tjson_path\tvalidator\tschema_path\tmessage\n")
    for i, ln in enumerate(fix_lines, start=1):
        try:
            doc = json.loads(ln)
        except Exception as exc:  # noqa: BLE001
            fh.write("%d\t-\t-\t1\t<whole-line>\tjson-parse\t-\t%s\n" % (i, str(exc)[:120]))
            continue
        errs = sorted(v.iter_errors(doc), key=lambda e: list(e.absolute_path))
        for n, e in enumerate(errs, start=1):
            fh.write("%d\t%s\t%s\t%d\t%s\t%s\t%s\t%s\n" % (
                i, doc.get("event_id", "-"), doc.get("event_type", "-"), n,
                "/".join(str(x) for x in e.absolute_path) or "<root>",
                e.validator,
                "/".join(str(x) for x in e.absolute_schema_path) or "-",
                e.message.replace("\t", " ")[:160]))
        if not errs:
            fh.write("%d\t%s\t%s\t0\t-\t-\t-\tVALID\n" % (i, doc.get("event_id", "-"), doc.get("event_type", "-")))
print("wrote=%s" % OUT.name)

# 契约登记表声称的 6 行 order_created「items 字符串形态」：逐行看是否真含 items 相关错误
print("order_created_rows_detail:")
for i, ln in enumerate(fix_lines, start=1):
    try:
        doc = json.loads(ln)
    except Exception:  # noqa: BLE001
        continue
    if doc.get("event_type") != "order_created":
        continue
    errs = sorted(v.iter_errors(doc), key=lambda e: list(e.absolute_path))
    msgs = ["%s(%s)" % (e.validator, "/".join(str(x) for x in e.absolute_path) or "<root>") for e in errs]
    items_type = type(doc.get("payload", {}).get("items")).__name__
    print("  L%d event_id=%s errors=%d items_pytype=%s :: %s" % (
        i, doc.get("event_id", "-"), len(errs), items_type, ",".join(msgs)[:150]))
