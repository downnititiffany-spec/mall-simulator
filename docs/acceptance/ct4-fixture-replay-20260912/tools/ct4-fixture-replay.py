# -*- coding: utf-8 -*-
"""CT-4 前提物对账：55 条黄金夹具 x canonical-event.v1 契约（逐行复算）。

口径（写死，避免事后各说各话）：
  - 校验器 = jsonschema Draft202012Validator（不启用 format 断言，与契约文档自身口径一致）
  - 夹具    = tests/golden-dataset/events/golden-20260901.jsonl（逐行 JSON）
  - 只做**契约合规**判定，不重跑 Java 侧 EventContractValidator（后者是**子集**校验，属另一口径）
输出：ASCII-only 打印（控制台 GBK）；明细写 TSV，供 CT-4 逐行核对。
"""
import hashlib
import json
import sys
from pathlib import Path

ROOT = Path(r"D:\Develop_code\GraduationProject")
SCHEMA = ROOT / "contract-specs/schemas/canonical-event.v1.schema.json"
FIXTURE = ROOT / "tests/golden-dataset/events/golden-20260901.jsonl"
LANDING = ROOT / "landing/events/r9-m1-123006.jsonl"
OUT = ROOT / "docs/acceptance/ct4-fixture-replay-20260912/raw/per-line-verdict.tsv"

# 契约自身登记的 8 处"真实数据冲突"锚点（schema 行号 -> 断言文本，用于逐项核对）
REGISTERED = {
    347: 'fixture L54 age_group="45-54"',
    490: '6 rows behavior channel="web" (accepted)',
    513: 'fixture L38 behavior_type="purchase" (isolated)',
    538: '6 rows order_created items as string (accepted)',
    614: '5 rows order_paid missing paid_at (accepted)',
    650: 'only order_cancelled row (L21) missing cancelled_at (accepted)',
    681: '3 rows refund_created missing created_at (accepted)',
    722: '3 rows refund_completed missing completed_at (accepted)',
}


def sha256(p: Path) -> str:
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()


def main() -> int:
    from jsonschema import Draft202012Validator
    from referencing import Registry, Resource

    schema_text = SCHEMA.read_text(encoding="utf-8")
    schema = json.loads(schema_text)
    validator = Draft202012Validator(schema)

    raw = FIXTURE.read_bytes()
    lines = [ln for ln in raw.decode("utf-8").splitlines() if ln.strip()]
    print("fixture_lines=%d" % len(lines))
    print("fixture_sha256=%s" % sha256(FIXTURE)[:16])
    print("schema_sha256=%s" % sha256(SCHEMA)[:16])
    landing_same = LANDING.exists() and LANDING.read_bytes() == raw
    print("landing_byte_equal=%s" % landing_same)

    rows = []
    for idx, ln in enumerate(lines, start=1):
        try:
            doc = json.loads(ln)
        except Exception as exc:  # noqa: BLE001
            rows.append((idx, "-", "-", "PARSE_ERROR", str(exc)[:120]))
            continue
        errs = sorted(validator.iter_errors(doc), key=lambda e: list(e.absolute_path))
        if errs:
            e0 = errs[0]
            path = "/".join(str(x) for x in e0.absolute_path) or "<root>"
            rows.append((idx, doc.get("event_id", "-"), doc.get("event_type", "-"),
                         "INVALID", "%s @ %s :: %s" % (len(errs), path, e0.message[:140])))
        else:
            rows.append((idx, doc.get("event_id", "-"), doc.get("event_type", "-"),
                         "VALID", ""))

    valid = [r for r in rows if r[3] == "VALID"]
    invalid = [r for r in rows if r[3] == "INVALID"]
    print("valid=%d invalid=%d total=%d" % (len(valid), len(invalid), len(rows)))

    # 按 event_type 统计不合规分布
    dist = {}
    for r in invalid:
        dist[r[2]] = dist.get(r[2], 0) + 1
    for k in sorted(dist):
        print("invalid_by_type %s=%d" % (k, dist[k]))

    # 契约侧登记的冲突锚点逐项核对（用 schema 行号取该行文本，验证锚点仍在）
    schema_lines = schema_text.splitlines()
    print("registered_anchor_check:")
    for ln_no in sorted(REGISTERED):
        text = schema_lines[ln_no - 1] if ln_no <= len(schema_lines) else "<out of range>"
        has_conflict = "真实数据冲突" in text
        print("  schema_L%d conflict_marker=%s" % (ln_no, has_conflict))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open("w", encoding="utf-8", newline="\n") as fh:
        fh.write("line\tevent_id\tevent_type\tverdict\tdetail\n")
        for r in rows:
            fh.write("%d\t%s\t%s\t%s\t%s\n" % r)
    print("wrote=%s" % OUT.name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
