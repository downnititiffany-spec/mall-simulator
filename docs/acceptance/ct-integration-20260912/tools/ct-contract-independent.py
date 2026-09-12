# -*- coding: utf-8 -*-
"""CT 落地后契约面 —— 父侧**独立实现**复核（不共用门禁里的正则/字节逻辑）。
口径：JSON 用 json.loads 真解析（不是文本正则）；哈希用 hashlib；行尾直接数字节。
每条都打印「应然 vs 实际」，任何不符即非零退出。
"""
import hashlib, json, pathlib, re, sys

REPO = pathlib.Path(r"D:\Develop_code\GraduationProject")
ok = fail = 0

def chk(name, cond, expect, actual):
    global ok, fail
    if cond:
        ok += 1; print(f"  [PASS] {name}")
    else:
        fail += 1; print(f"  [FAIL] {name}\n         应然: {expect}\n         实际: {actual}")

# 1) VERSION：内容 + LF 形态字节数 + LF 规范化 sha256
vpath = REPO / "contract-specs" / "VERSION"
raw = vpath.read_bytes()
txt = raw.decode("utf-8").replace("\r\n", "\n")
chk("I1 VERSION 文本 = 'contract-specs 2.2.0'", txt.strip() == "contract-specs 2.2.0",
    "contract-specs 2.2.0", repr(txt))
chk("I2 VERSION 落盘为 LF（CR 字节 0）", raw.count(b"\r") == 0, "CR = 0", f"CR = {raw.count(b'\r')}")
chk("I3 VERSION（LF）sha256 = EB175583…",
    hashlib.sha256(txt.encode("utf-8")).hexdigest().upper().startswith("EB175583"),
    "EB175583…", hashlib.sha256(txt.encode("utf-8")).hexdigest().upper()[:16] + "…")

# 2) canonical-event schema：JSON 真解析后逐条判（CT-1 / CT-3）
spath = REPO / "contract-specs" / "schemas" / "canonical-event.v1.schema.json"
sch = json.loads(spath.read_text(encoding="utf-8"))
ss = sch["properties"]["source_system"]
chk("I4 source_system 无 const 键", "const" not in ss, "无 const（CT-1）", ",".join(sorted(ss.keys())))
chk("I5 source_system 形状约束 type=string", ss.get("type") == "string", "string", repr(ss.get("type")))
chk("I6 正向对照：schema_version.const 仍为 '1.0'",
    sch["properties"]["schema_version"].get("const") == "1.0", "1.0",
    repr(sch["properties"]["schema_version"].get("const")))
items = sch["$defs"]["order_created"]["properties"]["items"]
oneof = items.get("oneOf")
chk("I7 order_created.items 有 oneOf 且恰 2 分支", isinstance(oneof, list) and len(oneof) == 2,
    "2 分支", repr(None if oneof is None else len(oneof)))
types = sorted([b.get("type") for b in (oneof or [])])
chk("I8 两分支 type = ['array','string']（加性接受）", types == ["array", "string"],
    "['array','string']", repr(types))
arr = [b for b in (oneof or []) if b.get("type") == "array"]
chk("I9 数组分支保留原 items 子定义", bool(arr) and "items" in arr[0],
    "array 分支含 items 子定义", "缺失" if not arr or "items" not in arr[0] else "在位")
chk("I10 规范形态：根 required 仍为 8 个字段",
    len(sch.get("required", [])) == 8, "8", repr(len(sch.get("required", []))))

# 3) 冻结契约warehouse-namespace.v1.json 不得被本批触碰（LF 规范化 sha 前 8 位）
wpath = REPO / "contract-specs" / "specs" / "warehouse-namespace.v1.json"
wtxt = wpath.read_bytes().decode("utf-8").replace("\r\n", "\n")
wsha = hashlib.sha256(wtxt.encode("utf-8")).hexdigest().upper()
chk("I11 冻结契约 warehouse-namespace.v1 指纹仍为 463D9DC3…", wsha.startswith("463D9DC3"),
    "463D9DC3…", wsha[:16] + "…")

# 4) 平台侧常量退休（源码文本扫描，独立于门禁实现）
java = (REPO / "analytics-server/platform-common/src/main/java/com/graduation/analytics/contracts/EventContract.java").read_text(encoding="utf-8")
chk("I12 平台侧 EventContract.SOURCE_SYSTEM 已退休（0 命中）",
    len(re.findall(r"\bSOURCE_SYSTEM\b", java)) == 0, "0 命中",
    f"{len(re.findall(r'SOURCE_SYSTEM', java))} 命中")
chk("I13 正向对照：SCHEMA_VERSION 仍在（≥1 命中）",
    len(re.findall(r"\bSCHEMA_VERSION\b", java)) >= 1, "≥1 命中",
    f"{len(re.findall(r'SCHEMA_VERSION', java))} 命中")

print(f"\n=== 独立复核汇总：断言 {ok + fail} 条；PASS {ok} / FAIL {fail} ===")
sys.exit(1 if fail else 0)
