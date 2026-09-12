# -*- coding: utf-8 -*-
"""P3-01-a 枚举/主键/隔离区取证探针（第三版）。

修掉第二版里**我自己写错的阳性对照**：v2 用 `'"+08:00"' in line`，
而 JSON 里的形态是 `...+08:00"`（引号在**右**边），所以该对照恒定 0 命中 ——
对照写错会把"工具坏了"误报成"数据里没有"。本版用两个**互斥且必然成立**的对照：
  阳性：`'+08:00"' in line`（期望 > 0）
  阴性：`'__NO_SUCH_FORMAT__' in line`（期望 0）

只读。输出 stdout。
"""
import json
import os
import re
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")
GOLDEN = os.path.join(ROOT, "tests", "golden-dataset", "events")

RE_NUM = re.compile(r"^\d+$")
RE_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")


def group_of(path):
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    parts = rel.split("/")
    if parts[0] == "landing" and len(parts) > 1:
        return "landing/" + parts[1]
    if parts[0] == "tests":
        return "golden"
    return parts[0]


def prefix_of(v):
    if not isinstance(v, str):
        return "<non-string>"
    if RE_UUID.match(v):
        return "<UUID>"
    if RE_NUM.match(v):
        return "<pure-digits len=%d>" % len(v)
    m = re.match(r"^([A-Za-z][A-Za-z_\-]*)", v)
    return (m.group(1) if m else "<other>") + "..."


def main():
    files = []
    for d in (LANDING, GOLDEN):
        for base, _dirs, names in os.walk(d):
            for name in sorted(names):
                if name.endswith(".jsonl"):
                    files.append(os.path.join(base, name))
    files.sort()

    pos = neg = 0
    # 枚举按 event_type 分组
    enum_by_type = defaultdict(lambda: defaultdict(Counter))
    id_prefix = defaultdict(Counter)
    id_prefix_by_group = defaultdict(Counter)
    id_len = defaultdict(Counter)
    quarantine_objs = []
    missing_required = defaultdict(Counter)

    REQUIRED = {
        "user_registered": ["user_id", "age_group", "city_level", "member_level", "register_time"],
        "order_created": ["order_id", "user_id", "items", "total_amount", "status", "created_at"],
        "order_paid": ["order_id", "user_id", "payment_id", "amount", "paid_at"],
        "order_cancelled": ["order_id", "user_id", "reason", "cancelled_at"],
        "refund_created": ["refund_id", "order_id", "user_id", "amount", "reason", "created_at"],
        "refund_completed": ["refund_id", "order_id", "user_id", "amount", "completed_at"],
        "behavior": ["user_id", "product_id", "session_id", "behavior_type", "channel"],
        "product_created": ["product_id", "product_name", "category_id", "brand_id", "price", "cost", "status"],
        "product_updated": ["product_id", "product_name", "category_id", "brand_id", "price", "cost", "status"],
        "stock_reserved": ["product_id", "quantity", "order_id", "reserved_qty", "available_qty"],
        "stock_released": ["product_id", "quantity", "order_id", "reserved_qty", "available_qty"],
        "stock_changed": ["product_id", "change_type", "quantity", "available_qty"],
    }
    ENUM_KEYS = ["behavior_type", "channel", "age_group", "city_level", "member_level",
                 "status", "change_type", "reason", "schema_version", "event_type"]

    for path in files:
        g = group_of(path)
        is_quarantine = g == "landing/quarantine"
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if '+08:00"' in line:
                    pos += 1
                if "__NO_SUCH_FORMAT__" in line:
                    neg += 1
                line = line.strip()
                if not line:
                    continue
                try:
                    obj = json.loads(line)
                except Exception:
                    continue
                if not isinstance(obj, dict):
                    continue
                if is_quarantine and len(quarantine_objs) < 12:
                    quarantine_objs.append((os.path.relpath(path, ROOT), obj))
                et = obj.get("event_type")
                pl = obj.get("payload")
                if not isinstance(pl, dict):
                    continue
                for k in ENUM_KEYS:
                    if k == "event_type":
                        enum_by_type[et]["event_type"][repr(obj.get("event_type"))] += 1
                    elif k == "schema_version":
                        enum_by_type[et]["schema_version"][repr(obj.get("schema_version"))] += 1
                    elif k in pl:
                        enum_by_type[et][k][repr(pl[k])] += 1
                for k in ("user_id", "product_id", "order_id", "refund_id", "payment_id", "session_id"):
                    v = obj.get(k, pl.get(k))
                    if v is not None:
                        id_prefix[k][prefix_of(v)] += 1
                        id_prefix_by_group[(k, g)][prefix_of(v)] += 1
                        if isinstance(v, str):
                            id_len[k][len(v)] += 1
                for k in REQUIRED.get(et, []):
                    if k not in pl:
                        missing_required[et][k] += 1

    print("## 对照（本版修正：引号在右侧）")
    print("   [positive-control] 含 '+08:00\"' 的行 = %d (期望 > 0)" % pos)
    print("   [negative-control] 含 '__NO_SUCH_FORMAT__' 的行 = %d (期望 0)" % neg)

    print("\n## J. 各 event_type 的枚举取值域")
    for et in sorted(enum_by_type):
        print("   [%s]" % et)
        for k in sorted(enum_by_type[et]):
            vals = enum_by_type[et][k]
            print("      %-16s distinct=%-4d %s" % (k, len(vals),
                  ", ".join("%s=%d" % (v, c) for v, c in vals.most_common(20))))

    print("\n## K. 标识前缀形态（用于 identityPolicy.shape 与 rawField 出处）")
    for k in sorted(id_prefix):
        print("   %-12s %s" % (k, dict(id_prefix[k].most_common(8))))
    print("\n   -- 按 landing 分区（events vs accepted vs quarantine，看是否同分布）--")
    for (k, g) in sorted(id_prefix_by_group):
        print("   %-12s %-20s %s" % (k, g, dict(id_prefix_by_group[(k, g)].most_common(5))))
    print("\n   -- 标识长度分布（前 6）--")
    for k in sorted(id_len):
        print("   %-12s %s" % (k, dict(id_len[k].most_common(6))))

    print("\n## L. 契约必填字段缺失计数（缺字段 = 脏数据负例）")
    if missing_required:
        for et in sorted(missing_required):
            for k, c in missing_required[et].most_common():
                print("   %-18s 缺 %-16s %d 行" % (et, k, c))
    else:
        print("   （无缺失）")

    print("\n## M. quarantine 原始记录样本（前 12 条，截断）")
    for rel, obj in quarantine_objs:
        print("   file=%s" % rel)
        print("      %s" % json.dumps(obj, ensure_ascii=False)[:600])

    print("\n## N. landing/manifests 与 landing/logs 的全部文件（含非 jsonl）")
    for sub in ("manifests", "logs"):
        d = os.path.join(LANDING, sub)
        if not os.path.isdir(d):
            print("   %s: <不存在>" % sub)
            continue
        items = []
        for base, _dirs, names in os.walk(d):
            for n in sorted(names):
                p = os.path.join(base, n)
                items.append((os.path.relpath(p, ROOT), os.path.getsize(p)))
        print("   %s: files=%d" % (sub, len(items)))
        for rel, sz in items[:40]:
            print("      %s  %d B" % (rel, sz))


if __name__ == "__main__":
    main()
