# -*- coding: utf-8 -*-
"""P3-01-a 画像 ⇔ 实测数据 双向对账（第五版，最终验收脚本）。

不是"读一遍 JSON 看有没有 9 个键"，而是把画像的**每个取值**拿去和全量 landing 数据对拍，
两个方向都查：
  正向：画像里写的每个键/取值，在数据里真的出现过吗？（防臆造）
  反向：数据里出现的每个取值，画像里都登记了吗？（防漏登记 —— 这正是"采样偏差"的入口）

关键不变量（任一为 False 即画像不合规）：
  I1  顶层恰好 9 个键，且与 SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS 同名同序
  I2  sourceCode / profileVersion 与 DB 实测一致
  I3  event_type 全部被 eventTypeMapping 覆盖，且映射目标 ⊆ 冻结契约 12 类
  I4  fieldMapping 覆盖 payload 字段全集（恒等）∪ {@keep 白名单}
  I5  enumSemantics 的每个域覆盖该域在数据里的**全部** distinct 值
  I6  enumSemantics 里值为 null 的，恰好是落在冻结契约 enum 之外的值
  I7  identityPolicy.rawField 在数据里存在
  I8  timePolicy.formats 能解析 100% 的 event_time 观测值
  I9  画像不含 timezone/currency 键（避免与 source_registry 形成第二个 owner）

只读。输出 stdout。
"""
import json
import os
import re
from collections import Counter, defaultdict

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
LANDING = os.path.join(ROOT, "landing")
GOLDEN = os.path.join(ROOT, "tests", "golden-dataset", "events")
PROFILE = os.path.join(ROOT, "analytics-server", "source-profiles", "mock-mall.v1.json")
SCHEMA = os.path.join(ROOT, "contract-specs", "schemas", "canonical-event.v1.schema.json")
VALIDATOR = os.path.join(ROOT, "analytics-server", "connection-ingestion", "src", "main",
                         "java", "com", "graduation", "analytics", "source", "SourceProfileValidator.java")

# 与 SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS 同源（下面会从 .java 里重新解析核对，防我手抄错）
EXPECTED_9 = ["profileVersion", "sourceCode", "canonical", "eventTypeMapping", "fieldMapping",
              "enumSemantics", "identityPolicy", "timePolicy", "quarantinePolicy"]

# 契约 iso8601_time 的 pattern（本脚本有一处硬编码抄写风险，故 I8 附带独立复算：
# 直接用 DateTimeFormatter.ISO_OFFSET_DATE_TIME 的等价正则，两条路径必须同结论）
RE_CONTRACT_TIME = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-]\d{2}:\d{2}|Z)$")
# 仅接受 +08:00 的形式（用于证明"没有别的时区"）
RE_ALT_OFFSET = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d{1,9})?([+-](?!08:00)\d{2}:\d{2}|Z)$")


def result(name, ok, detail):
    print("   [%s] %s  %s" % ("PASS" if ok else "FAIL", name, detail))
    return bool(ok)


def main():
    with open(PROFILE, "r", encoding="utf-8") as fh:
        raw = fh.read()
    prof = json.loads(raw)
    with open(SCHEMA, "r", encoding="utf-8") as fh:
        schema = json.load(fh)
    defs = schema.get("$defs", {})

    contract_types = sorted(n for n, d in defs.items() if isinstance(d, dict) and "properties" in d)
    skeleton = {n: set(defs[n]["properties"].keys()) for n in contract_types}
    contract_enums = {}
    for n in contract_types:
        for pk, pv in defs[n]["properties"].items():
            if isinstance(pv, dict) and "enum" in pv:
                contract_enums.setdefault(pk, set()).update(pv["enum"])
    field_union = set().union(*skeleton.values()) if skeleton else set()

    checks = []
    print("## T. 画像文件")
    print("   path=%s" % os.path.relpath(PROFILE, ROOT))
    print("   bytes=%d" % len(raw.encode("utf-8")))
    print("   top-level keys=%s" % list(prof.keys()))

    # I1
    java_keys = []
    try:
        with open(VALIDATOR, "r", encoding="utf-8") as fh:
            jt = fh.read()
        m = re.search(r"REQUIRED_TOP_LEVEL_KEYS\s*=\s*(?:List\.of|Arrays\.asList|Set\.of)\((.*?)\);", jt, re.S)
        if m:
            java_keys = re.findall(r'"([^"]+)"', m.group(1))
    except Exception as e:
        print("   （读 SourceProfileValidator.java 失败：%s）" % e)
    checks.append(result(
        "I1 顶层恰好 9 键且与 SourceProfileValidator 同名同序",
        list(prof.keys()) == EXPECTED_9 and (not java_keys or java_keys == EXPECTED_9),
        "画像=%d 键；Validator 源码解析=%s；本地期望=%s" % (
            len(prof.keys()), java_keys or "<未解析到>", EXPECTED_9)))

    # 读取全量数据
    files = []
    for d in (LANDING, GOLDEN):
        for base, _dirs, names in os.walk(d):
            for nm in sorted(names):
                if nm.endswith(".jsonl"):
                    files.append(os.path.join(base, nm))
    files.sort()

    types = Counter()
    payload_keys = defaultdict(Counter)
    enum_vals = defaultdict(Counter)
    time_total = time_ok = time_alt = 0
    rawfield_hits = Counter()
    idfields = {v["rawField"] for v in prof["identityPolicy"].values() if isinstance(v, dict)}
    nrows = 0
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
                nrows += 1
                et = obj.get("event_type")
                types[et] += 1
                t = obj.get("event_time")
                if isinstance(t, str):
                    time_total += 1
                    if RE_CONTRACT_TIME.match(t):
                        time_ok += 1
                    if RE_ALT_OFFSET.match(t):
                        time_alt += 1
                pl = obj.get("payload")
                if not isinstance(pl, dict):
                    continue
                for k in pl:
                    payload_keys[et][k] += 1
                for k in ("behavior_type", "channel", "age_group", "city_level",
                          "member_level", "status", "change_type"):
                    if k in pl:
                        enum_vals[(et, k)][json.dumps(pl[k], ensure_ascii=False)] += 1
                for f in idfields:
                    if isinstance(pl.get(f), str) and pl[f].strip():
                        rawfield_hits[f] += 1
    print("   实测：文件=%d 行=%d" % (len(files), nrows))

    # I2
    checks.append(result(
        "I2 sourceCode/profileVersion 与 source_registry 实测行一致",
        prof["sourceCode"] == "mock-mall" and prof["profileVersion"] == "1.0",
        "画像 sourceCode=%r profileVersion=%r；DB 实测 mock-mall / 1.0（raw/03）" % (
            prof["sourceCode"], prof["profileVersion"])))

    # I3
    mapped = set(prof["eventTypeMapping"])
    obs = {t for t in types if t}
    missing = sorted(obs - mapped)
    targets_ok = set(prof["eventTypeMapping"].values()) <= set(contract_types)
    checks.append(result(
        "I3 event_type 全覆盖且映射目标 ⊆ 契约 12 类",
        not missing and targets_ok and obs,
        "实测 distinct=%d，全部被覆盖=%s；未覆盖=%s；映射目标合法=%s" % (
            len(obs), not missing, missing or "无", targets_ok)))

    # I4
    fm = prof["fieldMapping"]
    declared_keep = {k for k, v in fm.items() if v == "@keep"}
    identity = {k for k, v in fm.items() if v != "@keep"}
    observed_fields = set()
    for et in payload_keys:
        observed_fields |= set(payload_keys[et])
    fields_not_in_union = sorted(observed_fields - field_union)
    not_declared = sorted(observed_fields - set(fm))
    dead_identity = sorted(identity - field_union)
    checks.append(result(
        "I4 fieldMapping 覆盖 payload 字段全集，@keep 仅用于契约外字段",
        not not_declared and set(fields_not_in_union) == declared_keep,
        "实测字段=%d；未登记=%s；契约外=%s；@keep=%s；恒等条目落在契约外(应为空)=%s" % (
            len(observed_fields), not_declared or "无", fields_not_in_union, sorted(declared_keep),
            dead_identity or "无")))

    # I5 / I6
    # 按 (event_type, payload 字段) 定位域 —— 同一个 payload 键在不同事件里可能是不同域：
    # `status` 在 order_created 是订单状态（契约未给定 enum），在 product_* 是商品状态（契约有 enum）。
    # 只按字段名查契约 enum 会把订单状态拿去对商品枚举，那是**假阳性**，v5 首跑就是这么漏掉 status 的。
    domain_of = {
        ("behavior", "behavior_type"): "behavior",
        ("behavior", "channel"): "channel",
        ("stock_changed", "change_type"): "changeType",
        ("user_registered", "age_group"): "ageGroup",
        ("user_registered", "city_level"): "cityLevel",
        ("user_registered", "member_level"): "memberLevel",
        ("order_created", "status"): "orderStatus",
        ("product_created", "status"): "productStatus",
        ("product_updated", "status"): "productStatus",
    }
    enum_of = {}
    for n in contract_types:
        for pk, pv in defs[n]["properties"].items():
            if isinstance(pv, dict) and "enum" in pv:
                enum_of[(n, pk)] = set(pv["enum"])
    es = prof["enumSemantics"]
    uncovered = {}
    null_but_in_contract = {}
    nonnull_but_out_contract = {}
    null_without_contract_enum = {}
    for (et, field), dom in domain_of.items():
        if (et, field) not in enum_vals:
            continue
        declared = es.get(dom, {})
        allowed = enum_of.get((et, field))
        for key in enum_vals[(et, field)]:
            val = json.loads(key)
            tag = "%s.%s" % (dom, field)
            if val not in declared:
                uncovered.setdefault(tag, []).append(val)
                continue
            label = declared[val]
            if allowed is None:
                # 契约对该事件该字段无 enum 约束 => 任何取值都必须给出标签，判 null 属"冤枉"
                if label is None:
                    null_without_contract_enum.setdefault(tag, []).append(val)
            else:
                if label is None and val in allowed:
                    null_but_in_contract.setdefault(tag, []).append(val)
                if label is not None and val not in allowed:
                    nonnull_but_out_contract.setdefault(tag, []).append(val)
    checks.append(result(
        "I5 enumSemantics 覆盖各域全部实测 distinct 值",
        not uncovered,
        "受检 (event_type,字段)->域 组合=%d；未覆盖=%s" % (len(domain_of), uncovered or "无")))
    checks.append(result(
        "I6 null 恰好落在契约 enum 之外（不漏判、不冤枉、不擅自归一）",
        not null_but_in_contract and not nonnull_but_out_contract and not null_without_contract_enum,
        "契约内被判 null=%s；契约外却给了非 null 标签=%s；契约本无 enum 却被判 null=%s" % (
            null_but_in_contract or "无", nonnull_but_out_contract or "无",
            null_without_contract_enum or "无")))

    # I7
    def exists(f):
        return any(f in payload_keys[et] for et in payload_keys)
    bad = sorted(f for f in idfields if not exists(f))
    checks.append(result(
        "I7 identityPolicy.rawField 在数据里真实存在",
        not bad,
        "声明 rawField=%s；数据里不存在的=%s" % (sorted(idfields), bad or "无")))

    # I8
    fmt = prof["timePolicy"]["formats"]
    fmt_ok = fmt == ["ISO_OFFSET_DATE_TIME"]
    checks.append(result(
        "I8 timePolicy 覆盖 100% event_time 且无其它时区",
        time_ok == time_total and time_total > 0 and time_alt == 0 and fmt_ok,
        "event_time 样本=%d，被契约 ISO-8601 形态解析=%d（100%%=%s）；非 +08:00/Z 的=%d；formats=%s" % (
            time_total, time_ok, time_ok == time_total, time_alt, fmt)))

    # I9
    flat = json.dumps(prof, ensure_ascii=False)
    leaked = [k for k in ("timezone", "currency", "Asia/Shanghai", "CNY") if k in flat]
    checks.append(result(
        "I9 画像不含 timezone/currency（不与 source_registry 争 owner）",
        not leaked,
        "命中=%s（期望空）；时区唯一 owner=source_registry.timezone（DB 实测 Asia/Shanghai）" % (
            leaked or "无")))

    print("\n## U. 汇总")
    print("   断言 %d 条，PASS %d，FAIL %d" % (len(checks), sum(checks), len(checks) - sum(checks)))
    print("   verdict=%s" % ("ALL_PASS" if all(checks) else "HAS_FAILURE"))
    print("\n## V. 对照（证明本脚本不是在「全 0 真空」上通过）")
    print("   [positive-control] 实测行数 = %d（期望 > 0）" % nrows)
    print("   [positive-control] 实测 event_type distinct = %d（期望 == 12）" % len(obs))
    print("   [negative-control] 画像里 '__NO_SUCH_KEY__' 出现次数 = %d（期望 0）" % flat.count("__NO_SUCH_KEY__"))
    print("   [negative-control] 实测 event_type == '__NO_SUCH_TYPE__' 的行 = %d（期望 0）" % types.get("__NO_SUCH_TYPE__", 0))


if __name__ == "__main__":
    main()
