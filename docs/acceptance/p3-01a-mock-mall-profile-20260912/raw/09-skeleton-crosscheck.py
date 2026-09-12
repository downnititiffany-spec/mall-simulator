# -*- coding: utf-8 -*-
"""P3-01-a 契约骨架 × 实测取值 对账探针（第四版）。

目的：fieldMapping / enumSemantics 的每个取值都必须有出处，
不能凭印象写"源字段就是规范字段"。本探针把**冻结契约**（canonical-event.v1.schema.json）
的 $defs 属性名与 enum 抽出来，与**实测** landing 取值做交叉表：

  - 实测字段 ∈ 契约骨架 且 名称相同  => 恒等映射（有证据）
  - 实测字段 ∉ 契约骨架              => 需 @keep（有证据）
  - 实测枚举值 ∈ 契约 enum           => 恒等（有证据）
  - 实测枚举值 ∉ 契约 enum           => 待裁定，不得自行映射

只读。输出 stdout。
"""
import json
import os
import re
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")
GOLDEN = os.path.join(ROOT, "tests", "golden-dataset", "events")
SCHEMA = os.path.join(ROOT, "contract-specs", "schemas", "canonical-event.v1.schema.json")

RE_NUM = re.compile(r"^\d+$")


def group_of(path):
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    parts = rel.split("/")
    if parts[0] == "landing" and len(parts) > 1:
        return "landing/" + parts[1]
    if parts[0] == "tests":
        return "golden"
    return parts[0]


def main():
    with open(SCHEMA, "r", encoding="utf-8") as fh:
        schema = json.load(fh)
    defs = schema.get("$defs", {})

    # 信封字段（根 properties）
    envelope = sorted(schema.get("properties", {}).keys())
    print("## O. 冻结契约 canonical-event.v1 骨架")
    print("   根对象字段: %s" % envelope)

    skeleton = {}
    enums = {}
    for name, d in sorted(defs.items()):
        if not isinstance(d, dict) or "properties" not in d:
            continue
        skeleton[name] = sorted(d["properties"].keys())
        for pk, pv in d["properties"].items():
            if isinstance(pv, dict) and "enum" in pv:
                enums[(name, pk)] = pv["enum"]
    for name in sorted(skeleton):
        print("   [%s] %s" % (name, skeleton[name]))
    print("\n   契约枚举（字段 => 允许值）:")
    for (name, pk) in sorted(enums):
        print("   %-18s %-14s %s" % (name, pk, enums[(name, pk)]))
    if not enums:
        print("   （$defs 内无 enum；枚举可能只写在文档表格里）")

    # ---- 实测 ----
    files = []
    for d in (LANDING, GOLDEN):
        for base, _dirs, names in os.walk(d):
            for nm in sorted(names):
                if nm.endswith(".jsonl"):
                    files.append(os.path.join(base, nm))
    files.sort()

    payload_keys = defaultdict(Counter)
    enum_vals = defaultdict(Counter)
    id_samples = defaultdict(list)
    id_shape = defaultdict(Counter)
    v11 = []
    unparsed_any = Counter()

    for path in files:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if not isinstance(obj, dict):
                    continue
                et = obj.get("event_type")
                pl = obj.get("payload")
                if obj.get("schema_version") == "1.1" and len(v11) < 6:
                    v11.append((os.path.relpath(path, ROOT), json.dumps(obj, ensure_ascii=False)[:400]))
                if not isinstance(pl, dict):
                    continue
                for k in pl:
                    payload_keys[et][k] += 1
                for k in ("behavior_type", "channel", "age_group", "city_level",
                          "member_level", "status", "change_type", "reason"):
                    if k in pl:
                        # 用 json.dumps 而非 repr：键必须能被 json.loads 还原（v4 首跑就是 repr 导致 JSONDecodeError）
                        enum_vals[k][json.dumps(pl[k], ensure_ascii=False)] += 1
                for k in ("user_id", "product_id", "order_id", "refund_id", "payment_id",
                          "category_id", "brand_id", "session_id"):
                    v = pl.get(k)
                    if isinstance(v, str):
                        if RE_NUM.match(v):
                            sh = "NUMERIC"
                        elif re.match(r"^[A-Za-z][A-Za-z_\-]*[-_]?\d+$", v):
                            sh = "PREFIX_NUMERIC"
                        elif re.match(r"^[0-9a-fA-F]{8}-", v):
                            sh = "UUID"
                        else:
                            sh = "ANY"
                        id_shape[k][sh] += 1
                        if sh != "NUMERIC" and len(id_samples[k]) < 6:
                            id_samples[k].append(v)

    print("\n## P. 实测 payload 字段 × 契约骨架（判定 fieldMapping 是恒等还是有 @keep）")
    for et in sorted(payload_keys):
        sk = set(skeleton.get(et, []))
        meas = set(payload_keys[et])
        ident = sorted(meas & sk)
        extra = sorted(meas - sk)
        absent = sorted(sk - meas)
        print("   [%s]" % et)
        print("      恒等映射(%d): %s" % (len(ident), ident))
        print("      契约有但本事件未实测(%d): %s" % (len(absent), absent))
        print("      >>> 需 @keep 的源侧额外字段(%d): %s" % (len(extra), extra))

    print("\n## Q. 实测枚举值 × 契约 enum（判定 enumSemantics 哪些是恒等、哪些待裁定）")
    all_enum_fields = sorted({pk for (_n, pk) in enums})
    for k in sorted(enum_vals):
        allowed = None
        for (n, pk) in sorted(enums):
            if pk == k:
                allowed = enums[(n, pk)]
                break
        vals = enum_vals[k]
        print("   %-14s 实测 distinct=%d" % (k, len(vals)))
        if allowed is None:
            print("        契约 enum: <本字段在 canonical-event.v1 中无 enum 约束>")
            for v, c in vals.most_common(20):
                print("          %-22s %d" % (v, c))
        else:
            inn = [(v, c) for v, c in vals.most_common() if json.loads(v) in allowed]
            out = [(v, c) for v, c in vals.most_common() if json.loads(v) not in allowed]
            print("        契约 enum: %s" % allowed)
            print("        命中(%d): %s" % (sum(c for _, c in inn), inn))
            print("        >>> 出界(%d): %s" % (sum(c for _, c in out), out))

    print("\n## R. 标识形态判定（shape 该写什么）")
    for k in sorted(id_shape):
        print("   %-14s %s" % (k, dict(id_shape[k].most_common())))
        print("        非纯数字样本: %s" % id_samples[k])

    print("\n## S. schema_version=1.1 的实际行（前 %d 条）" % len(v11))
    for rel, s in v11:
        print("   file=%s" % rel)
        print("      %s" % s)
    if not v11:
        print("   （未取到；说明 1.1 出现在未被 os.walk 覆盖的位置）")


if __name__ == "__main__":
    main()
