# -*- coding: utf-8 -*-
"""第三步：切分归属映射（夹具行 <-> accepted/quarantine）+ order_created 缺字段名。ASCII-only 打印。"""
import json
from pathlib import Path

ROOT = Path(r"D:\Develop_code\GraduationProject")
FIX = ROOT / "tests/golden-dataset/events/golden-20260901.jsonl"
ACC = ROOT / "landing/accepted/30/r9-m1-123006.jsonl"
QUA = ROOT / "landing/quarantine/30/r9-m1-123006.jsonl"
OUT = ROOT / "docs/acceptance/ct4-fixture-replay-20260912/raw/partition-map.tsv"


def ids_of(path):
    out = []
    for ln in path.read_bytes().decode("utf-8").splitlines():
        if not ln.strip():
            continue
        try:
            doc = json.loads(ln)
        except Exception:  # noqa: BLE001
            out.append("<UNPARSEABLE>")
            continue
        out.append(doc.get("event_id", "<NO_EVENT_ID>"))
    return out


acc_ids, qua_ids = ids_of(ACC), ids_of(QUA)
fix_ids = ids_of(FIX)
print("accepted_ids=%d quarantine_ids=%d fixture_ids=%d" % (len(acc_ids), len(qua_ids), len(fix_ids)))
print("accepted_has_UNPARSEABLE=%s" % ("<UNPARSEABLE>" in acc_ids))
print("quarantine_rows=%s" % qua_ids)

acc_set, qua_set = set(acc_ids), set(qua_ids)
rows = []
missing = []
for i, eid in enumerate(fix_ids, start=1):
    if eid in acc_set:
        where = "accepted"
    elif eid in qua_set:
        where = "quarantine"
    elif eid == "<UNPARSEABLE>":
        where = "quarantine"
    else:
        where = "UNMATCHED"
        missing.append((i, eid))
    rows.append((i, eid, where))
print("unmatched=%d %s" % (len(missing), missing))
print("accepted=%d quarantine=%d" % (sum(1 for r in rows if r[2] == "accepted"),
                                     sum(1 for r in rows if r[2] == "quarantine")))
with OUT.open("w", encoding="utf-8", newline="\n") as fh:
    fh.write("fixture_line\tevent_id\tpartition\n")
    for r in rows:
        fh.write("%d\t%s\t%s\n" % r)
print("wrote=%s" % OUT.name)

# order_created 6 行的 required 缺字段名
SCHEMA = ROOT / "contract-specs/schemas/canonical-event.v1.schema.json"
from jsonschema import Draft202012Validator
v = Draft202012Validator(json.loads(SCHEMA.read_text(encoding="utf-8")))
print("order_created_required_missing:")
for i, ln in enumerate(FIX.read_bytes().decode("utf-8").splitlines(), start=1):
    if not ln.strip():
        continue
    try:
        doc = json.loads(ln)
    except Exception:  # noqa: BLE001
        continue
    if doc.get("event_type") != "order_created":
        continue
    errs = [e for e in v.iter_errors(doc) if e.validator == "required"]
    print("  L%d %s missing=%s" % (i, doc.get("event_id"),
                                   [e.message.split("'")[1] for e in errs]))
