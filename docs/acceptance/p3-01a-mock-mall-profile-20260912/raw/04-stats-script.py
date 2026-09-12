# -*- coding: utf-8 -*-
"""P3-01-a 真实数据统计：从 landing/events + tests/golden-dataset 全量扫描得出源画像所需取值。

只读。不做任何写库/写数据操作。输出 stdout（由调用方 tee 到 04-stats-output.txt）。
阳性/阴性对照内置：
  - 阳性对照：事件类型计数总和必须 == 可解析行数（工具确实读到了内容）
  - 阳性对照：'user_id' 键必须出现在 > 0 行
  - 阴性对照：不存在的枚举值 '__NO_SUCH_VALUE__' 必须命中 0
"""
import json
import os
import re
import sys
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")          # 全树：events/ accepted/ quarantine/
GOLDEN = os.path.join(ROOT, "tests", "golden-dataset", "events")


def group_of(path):
    """按仓库相对路径的第 2 段分组：landing/events、landing/accepted、landing/quarantine、golden"""
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    parts = rel.split("/")
    if parts[0] == "landing" and len(parts) > 1:
        return "landing/" + parts[1]
    if parts[0] == "tests":
        return "golden"
    return parts[0]

RE_TIME = re.compile(r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})([+-]\d{2}:\d{2}|Z)?(\.\d+)?$")
RE_SPACE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}")
RE_EPOCH = re.compile(r"^\d{10}$|^\d{13}$")
RE_NUM = re.compile(r"^\d+$")
RE_PREFIX_NUM = re.compile(r"^[A-Za-z][A-Za-z_\-]*\d+$")
RE_UUID = re.compile(r"^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
RE_DECIMAL = re.compile(r"^\d+(\.\d{1,2})?$")


def time_shape(v):
    if not isinstance(v, str):
        return "NOT_STRING:" + type(v).__name__
    m = RE_TIME.match(v)
    if m:
        off = m.group(7)
        frac = m.group(8)
        if off is None:
            return "ISO_LOCAL_NO_OFFSET"
        if off == "Z":
            return "ISO_UTC_Z"
        if frac:
            return "ISO_OFFSET_WITH_FRACTION" + ("_PLUS08" if off == "+08:00" else "_OTHER_OFFSET")
        return "ISO_OFFSET_SECONDS_PLUS08" if off == "+08:00" else "ISO_OFFSET_SECONDS_OTHER"
    if RE_SPACE.match(v):
        return "SPACE_SEPARATED"
    if RE_EPOCH.match(v):
        return "EPOCH_DIGITS"
    return "OTHER"


def id_shape(v):
    if not isinstance(v, str):
        return "NOT_STRING:" + type(v).__name__
    if RE_UUID.match(v):
        return "UUID"
    if RE_NUM.match(v):
        return "NUMERIC"
    if RE_PREFIX_NUM.match(v):
        return "PREFIX_NUMERIC"
    return "ANY"


ID_KEYS = ["user_id", "product_id", "order_id", "session_id", "payment_id",
           "refund_id", "category_id", "brand_id", "trace_id", "event_id", "items"]

ENUM_MAX_DISTINCT = 80


def main():
    files = []
    for d in (LANDING, GOLDEN):
        for base, _dirs, names in os.walk(d):
            for name in sorted(names):
                if name.endswith(".jsonl"):
                    files.append(os.path.join(base, name))
    files.sort()
    total_bytes = sum(os.path.getsize(f) for f in files)
    print("### scanned_files=%d total_bytes=%d" % (len(files), total_bytes))
    per_group_files = Counter(group_of(f) for f in files)
    per_group_bytes = Counter()
    for f in files:
        per_group_bytes[group_of(f)] += os.path.getsize(f)
    for g in sorted(per_group_files):
        print("### group=%-20s files=%d bytes=%d" % (g, per_group_files[g], per_group_bytes[g]))
    zero_byte = [f for f in files if os.path.getsize(f) == 0]
    print("### empty_files=%d" % len(zero_byte))

    lines_total = 0
    lines_parsed = 0
    lines_badjson = 0
    env_keys = Counter()
    event_type = Counter()
    source_system = Counter()
    schema_version = Counter()
    event_time_shape = Counter()
    ingest_time_shape = Counter()
    payload_keys_by_type = defaultdict(Counter)
    top_level_types = Counter()
    value_counter = defaultdict(Counter)
    val_shape = defaultdict(Counter)
    decimal_bad = Counter()
    pos_control_keys = Counter()
    neg_control = 0

    badjson_samples = []
    for path in files:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                line = line.strip()
                if not line:
                    continue
                lines_total += 1
                try:
                    obj = json.loads(line)
                except Exception:
                    lines_badjson += 1
                    if len(badjson_samples) < 3:
                        badjson_samples.append((os.path.basename(path), line[:120]))
                    continue
                if not isinstance(obj, dict):
                    lines_badjson += 1
                    continue
                lines_parsed += 1
                for k in obj:
                    env_keys[k] += 1
                et = obj.get("event_type")
                if isinstance(et, str):
                    event_type[et] += 1
                ss = obj.get("source_system")
                if isinstance(ss, str):
                    source_system[ss] += 1
                sv = obj.get("schema_version")
                if isinstance(sv, str):
                    schema_version[sv] += 1
                event_time_shape[time_shape(obj.get("event_time"))] += 1
                ingest_time_shape[time_shape(obj.get("ingest_time"))] += 1
                pl = obj.get("payload")
                if isinstance(pl, dict):
                    payload_keys_by_type[et if isinstance(et, str) else "<none>"].update(pl.keys())
                    for pk, pv in pl.items():
                        if isinstance(pv, (str, int, float, bool)) or pv is None:
                            value_counter[pk][repr(pv)] += 1
                        else:
                            top_level_types[pk] += 1
                    # 阳性对照：payload 里必须出现 user_id
                    if "user_id" in pl:
                        pos_control_keys["payload.user_id"] += 1
                    if pl.get("behavior_type") == "__NO_SUCH_VALUE__":
                        neg_control += 1
                    for k in ("total_amount", "amount", "unit_price", "discount", "price", "cost",
                              "available_qty", "reserved_qty"):
                        if k in pl and isinstance(pl[k], str) and not RE_DECIMAL.match(pl[k]):
                            decimal_bad[k] += 1
                for k in ID_KEYS:
                    v = obj.get(k)
                    if v is None and isinstance(pl, dict):
                        v = pl.get(k)
                    if isinstance(v, str):
                        val_shape[k][id_shape(v)] += 1

    print("### lines_total=%d lines_parsed=%d lines_badjson=%d" % (lines_total, lines_parsed, lines_badjson))
    for s in badjson_samples:
        print("### badjson_sample file=%s head=%s" % s)

    print("\n## A. 信封顶层键（出现次数 / 总行数 %d）" % lines_parsed)
    for k, c in env_keys.most_common():
        print("   %-16s %d" % (k, c))

    print("\n## B. event_type 全部取值（源事件名）")
    for k, c in event_type.most_common():
        print("   %-20s %d" % (k, c))
    print("   [positive-control] event_type 计数总和 = %d ; 可解析行数 = %d ; 相等 = %s"
          % (sum(event_type.values()), lines_parsed, sum(event_type.values()) == lines_parsed))
    print("   [negative-control] event_type == '__NO_SUCH_VALUE__' 命中 = %d (期望 0)"
          % event_type.get("__NO_SUCH_VALUE__", 0))

    print("\n## C. source_system / schema_version")
    for k, c in source_system.most_common():
        print("   source_system=%-16s %d" % (k, c))
    for k, c in schema_version.most_common():
        print("   schema_version=%-16s %d" % (k, c))

    print("\n## D. 时间形态（event_time 列 / ingest_time 列）")
    for k, c in event_time_shape.most_common():
        print("   event_time   %-32s %d" % (k, c))
    for k, c in ingest_time_shape.most_common():
        print("   ingest_time  %-32s %d" % (k, c))

    print("\n## E. payload 键（按 event_type）")
    for et in sorted(payload_keys_by_type):
        print("   [%s]" % et)
        for k, c in payload_keys_by_type[et].most_common():
            print("      %-20s %d" % (k, c))

    print("\n## F. 低基数 payload 取值域（distinct <= %d）" % ENUM_MAX_DISTINCT)
    for k in sorted(value_counter):
        distinct = value_counter[k]
        if len(distinct) <= ENUM_MAX_DISTINCT:
            print("   %-18s distinct=%d : %s" % (k, len(distinct),
                  ", ".join("%s=%d" % (v, c) for v, c in distinct.most_common())))
        else:
            print("   %-18s distinct=%d (高基数，前 5: %s)" % (k, len(distinct),
                  ", ".join("%s=%d" % (v, c) for v, c in distinct.most_common(5))))
    for k in sorted(top_level_types):
        print("   %-18s 非标量（数组/对象）出现 %d 次" % (k, top_level_types[k]))

    print("\n## G. 主键/标识形态（用于 identityPolicy.shape）")
    for k in sorted(val_shape):
        print("   %-14s %s" % (k, dict(val_shape[k])))

    print("\n## H. 金额十进制形态负例计数（非 ^\\d+(\\.\\d{1,2})?$ 的次数，期望全 0）")
    if decimal_bad:
        for k, c in decimal_bad.most_common():
            print("   %-16s %d" % (k, c))
    else:
        print("   （无违例）")

    print("\n## I. 对照")
    print("   [positive-control] payload.user_id 命中行数 = %d (期望 > 0)" % pos_control_keys["payload.user_id"])
    print("   [negative-control] payload.behavior_type == '__NO_SUCH_VALUE__' 命中 = %d (期望 0)"
          % neg_control)


if __name__ == "__main__":
    main()
