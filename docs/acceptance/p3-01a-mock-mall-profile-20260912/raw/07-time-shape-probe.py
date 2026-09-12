# -*- coding: utf-8 -*-
"""P3-01-a 时间形态细分探针（第二版）。

第一版 04-stats-script.py 的时间正则在**小数秒与偏移的顺序**上写错了：
真实数据里存在 `<date>T<time>.<frac>+08:00`（小数秒在偏移之前，符合 ISO-8601），
而第一版正则把偏移写在小数秒之前 ⇒ 这些值全被误判成 OTHER。
本探针用**修正后的分类器**重算，并对每个桶给出原始样本，避免"看不出是什么就归 OTHER"。

只读。输出 stdout。
"""
import json
import os
import re
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")
GOLDEN = os.path.join(ROOT, "tests", "golden-dataset", "events")

# 修正：小数秒在偏移之前；偏移可为 +08:00 / 其它 offset / Z；小数秒位数 1-9
RE_ISO = re.compile(
    r"^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})"
    r"(\.\d{1,9})?"
    r"(Z|[+-]\d{2}:\d{2})?$")
RE_SPACE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?$")
RE_EPOCH = re.compile(r"^\d{10}$|^\d{13}$")


def classify(v):
    if not isinstance(v, str):
        return "NOT_STRING:" + type(v).__name__
    m = RE_ISO.match(v)
    if m:
        frac, off = m.group(7), m.group(8)
        if off is None:
            base = "ISO_LOCAL_NO_OFFSET"
        elif off == "Z":
            base = "ISO_UTC_Z"
        elif off == "+08:00":
            base = "ISO_OFFSET_PLUS08"
        else:
            base = "ISO_OFFSET_OTHER(" + off + ")"
        if frac:
            return base + "_WITH_FRACTION(%d位)" % (len(frac) - 1)
        return base + "_NO_FRACTION"
    if RE_SPACE.match(v):
        return "SPACE_SEPARATED"
    if RE_EPOCH.match(v):
        return "EPOCH_DIGITS"
    return "OTHER"


def group_of(path):
    rel = os.path.relpath(path, ROOT).replace("\\", "/")
    parts = rel.split("/")
    if parts[0] == "landing" and len(parts) > 1:
        return "landing/" + parts[1]
    if parts[0] == "tests":
        return "golden"
    return parts[0]


def main():
    files = []
    for d in (LANDING, GOLDEN):
        for base, _dirs, names in os.walk(d):
            for name in sorted(names):
                if name.endswith(".jsonl"):
                    files.append(os.path.join(base, name))
    files.sort()
    print("### scanned_files=%d total_bytes=%d" % (len(files), sum(os.path.getsize(f) for f in files)))
    for g, c in sorted(Counter(group_of(f) for f in files).items()):
        b = sum(os.path.getsize(f) for f in files if group_of(f) == g)
        print("### group=%-20s files=%d bytes=%d" % (g, c, b))

    for field in ("event_time", "ingest_time"):
        per_group = defaultdict(Counter)
        samples = defaultdict(list)
        for path in files:
            g = group_of(path)
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
                    k = classify(obj.get(field))
                    per_group[g][k] += 1
                    if len(samples[(g, k)]) < 4:
                        samples[(g, k)].append(repr(obj.get(field))[:70])
        print("\n## %s 形态分解（修正后的分类器）" % field)
        total = Counter()
        for g in sorted(per_group):
            print("   [%s] 行数=%d" % (g, sum(per_group[g].values())))
            for k, v in per_group[g].most_common():
                print("      %-34s %d" % (k, v))
                for s in samples[(g, k)]:
                    print("          sample: %s" % s)
                total[k] += v
        print("   ---- 合计 ----")
        for k, v in total.most_common():
            print("      %-34s %d" % (k, v))
        print("   [positive-control] 合计 = %d (期望 == 可解析总行数)" % sum(total.values()))

    # 阳性/阴性对照：已知必然命中的取值 vs 必然不存在的取值
    print("\n## 对照")
    hit = miss = 0
    for path in files:
        with open(path, "r", encoding="utf-8", errors="replace") as fh:
            for line in fh:
                if '"+08:00"' in line:
                    hit += 1
                if '"__NO_SUCH_FORMAT__"' in line:
                    miss += 1
    print("   [positive-control] 含 '+08:00' 的行 = %d (期望 > 0)" % hit)
    print("   [negative-control] 含 '__NO_SUCH_FORMAT__' 的行 = %d (期望 0)" % miss)


if __name__ == "__main__":
    main()
