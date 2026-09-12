#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""apply-schema-edits.py —— CT 批次 canonical-event.v1.schema.json 的三处机械改写（先断言后写盘）。

三处：
  S1  CT-2 第 3 点：event_id.description 首句去重语义改写
  S2  CT-1 第 2/3 点：source_system 的 const → 形状约束 ＋ description 改写
  S3  CT-3 第 1/2 点 ＋ §2.5 第 4 点：order_created.items 改 oneOf（分支甲＝原数组定义，**原文逐字符保留**）
      ＋ order_created.description 之后**追加**处置句（原文保留）

断言口径（PLAN §2）：每处先断言"旧串命中 == 1（且新串命中 == 0）"，再断言"新串命中 == 1 且旧串命中 == 0"；
另断言：除这三处外其余行**逐行不变**、CRLF=EOL 不变、JSON 可解析、写法仅动必要的键。
本脚本不写任何仓内文件以外的东西；只有 --apply 才写 schema。
"""
import argparse
import hashlib
import json
import sys

# ---- 精确锚（逐字） ----
EOL = "\r\n"

A_EVENT_ID = '"description": "全局唯一事件 ID，ODS/DWD 按此去重（允许 at-least-once 投递）。'
N_EVENT_ID = '"description": "源命名空间内唯一的事件 ID，ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）。'

A_SRC_CONST = '      "const": "mock-mall",'
N_SRC_SHAPE = '      "type": "string",' + EOL + '      "minLength": 1,'
A_SRC_DESC = '"description": "固定值：mock-mall。来源：event-contract.md §1 L16『固定值：mock-mall』；与 EventContract.SOURCE_SYSTEM（EventContract.java:17）及 mall-simulator 侧 EventContract.SOURCE_SYSTEM 一致，并由 CanonicalEventSchemaParityTest 锁定为 const。'
N_SRC_DESC = ('"description": "形状约束（type: string, minLength: 1）：取值 = 该事件所属源的 source_registry.source_code'
              '（D-056 经 spark-submit --extra 注入通道下发）；**值域非契约所有**（D-061：刻意不加 pattern/enum，'
              '否则每接入一个源都要改契约，且契约会成为第二个命名规则所有者——命名规则的单一所有者是 source_registry 侧校验，D-035）。'
              'mock-mall 为首个源的取值，非契约固定值。来源：event-contract.md §1 L16；'
              '平台侧常量 EventContract.SOURCE_SYSTEM 已退休（D-064 ①），mall-simulator / 生成器侧同名常量属 B-06 未决（D-064 ②③）。')

A_ITEM_DESC = '"description": "订单项数组。§2.4 L77；子表字段见 §2.4 L84-L90。"'
N_ITEM_DESC = '"description": "订单项数组（规范形态；字符串形态为外部源兼容，见 oneOf 分支乙）。docs/contracts/event-contract.md §2.4 L77；子表字段见 docs/contracts/event-contract.md §2.4 L84-L90。"'

A_OC_DESC_TAIL = '（如 \\"[{\\\\\\"product_id\\\\\\":\\\\\\"2\\\\\\",...}]\\"）而不是数组；(2) 缺 status；(3) 缺 created_at。见 README Q5、Q6。"'
ADD_OC_DESC = '处置（D-063）：契约加性接受字符串形态；规范形态仍为数组；解析归一属 DWD（P2-04/P2-05）。'

NEW_STRING_BRANCH = [
    '          {',
    '            "type": "string",',
    '            "description": "承载 JSON 编码数组的字符串（外部源兼容形态，D-063）：真实夹具 landing/events/r9-m1-123006.jsonl 的 6 行 order_created（L18/L20/L22/L39/L43/L47）均如此且被采集层接受；契约**加性**接受该形态（不放宽必填），规范形态仍为数组，解析归一属 DWD（P2-04/P2-05）。"',
    '          }',
]


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--file", required=True)
    ap.add_argument("--before", required=False)
    ap.add_argument("--apply", action="store_true")
    args = ap.parse_args()

    raw = open(args.file, "rb").read()
    text = raw.decode("utf-8")
    lines = text.split(EOL)
    log = []
    log.append(f"file       : {args.file}")
    log.append(f"sha256(原) : {sha(raw)}  bytes={len(raw)}  lines={len(lines)}  CR={raw.count(13)}  LF={raw.count(10)}")
    log.append("")

    def assert_hit(name, hay, needle, expect):
        n = hay.count(needle)
        ok = (n == expect)
        log.append(f"[{'OK ' if ok else 'FAIL'}] {name}: 命中 {n}（应然 {expect}）")
        return ok

    allok = True
    allok &= assert_hit("S1 旧串 event_id.description 首句", text, A_EVENT_ID, 1)
    allok &= assert_hit("S1 新串（写前必须 0）", text, N_EVENT_ID, 0)
    allok &= assert_hit("S2 旧串 const", text, A_SRC_CONST, 1)
    allok &= assert_hit("S2 新串（写前必须 0）", text, EOL + N_SRC_SHAPE + EOL, 0)
    allok &= assert_hit("S2 旧串 description", text, A_SRC_DESC, 1)
    allok &= assert_hit("S2 新串 description（写前必须 0）", text, N_SRC_DESC, 0)
    allok &= assert_hit("S3 旧串 items.description", text, A_ITEM_DESC, 1)
    allok &= assert_hit("S3 新串 items.description（写前必须 0）", text, N_ITEM_DESC, 0)
    allok &= assert_hit("S3 旧串 order_created.description 尾（含 Q5/Q6）", text, A_OC_DESC_TAIL, 1)
    allok &= assert_hit("S3 处置句（写前必须 0）", text, ADD_OC_DESC, 0)
    # CT-3 防误用裸 items 计数：带上下文锚（8 空格 items + array）
    allok &= assert_hit('S3 带上下文锚 \'        "items": {\' + array（唯一）', text, '        "items": {' + EOL + '          "type": "array",', 1)
    # 正向对照（F-38 ①）：裸 "items" 关键字必然多次出现 ⇒ 证明裸计数不可用
    bare = text.count('"items": {')
    log.append(f"[对照] 裸 '\"items\": {{' 命中 {bare} 处 ⇒ 远大于 1，证明 CT-3 **不得**用裸 items 计数（判据非空转）")
    allok &= (bare > 1)
    if not allok:
        log.append("")
        log.append("SELFCHECK-FAIL：写前断言未通过 ⇒ **不写盘**")
        print("\n".join(log))
        return 2

    new = text
    new = new.replace(A_EVENT_ID, N_EVENT_ID, 1)
    new = new.replace(A_SRC_CONST, N_SRC_SHAPE, 1)
    new = new.replace(A_SRC_DESC, N_SRC_DESC, 1)
    new = new.replace(A_ITEM_DESC, N_ITEM_DESC, 1)
    new = new.replace(A_OC_DESC_TAIL, A_OC_DESC_TAIL[:-1] + ADD_OC_DESC + '"', 1)
    # S3 结构：把 items 的数组分支包进 oneOf
    new = new.replace(
        '        "items": {' + EOL + '          "type": "array",',
        '        "items": {' + EOL + '          "oneOf": [' + EOL + '          {' + EOL + '            "type": "array",',
        1)
    # 该分支结束处（"          }" 缩进 10 空格 + 原字段闭合 "        }," 缩进 8 空格）
    old_tail = '          }' + EOL + '        },' + EOL + '        "total_amount": {'
    new_tail = '          }' + EOL + '          },' + EOL + "".join(b + EOL for b in NEW_STRING_BRANCH) + '          ]' + EOL + '        },' + EOL + '        "total_amount": {'
    old_tail_n = new.count(old_tail)
    log.append(f"[{'OK ' if old_tail_n == 1 else 'FAIL'}] S3 数组分支尾部锚（10 空格闭括号 + 8 空格闭括号逗号 + total_amount）: 命中 {old_tail_n}（应然 1）")
    if old_tail_n != 1:
        log.append("SELFCHECK-FAIL：S3 尾部锚不唯一 ⇒ 不写盘")
        print("\n".join(log))
        return 2
    new = new.replace(old_tail, new_tail, 1)

    log.append("")
    log.append("=== 写后复核（新串 == 1 ∧ 旧串 == 0）===")
    checks = [
        ("S1 新串", new.count(N_EVENT_ID), 1), ("S1 旧串", new.count(A_EVENT_ID), 0),
        ("S2 新串 const→shape", new.count(EOL + N_SRC_SHAPE + EOL), 1), ("S2 旧串 const", new.count(A_SRC_CONST), 0),
        ("S2 新串 desc", new.count(N_SRC_DESC), 1), ("S2 旧串 desc", new.count(A_SRC_DESC), 0),
        ("S3 新串 items.desc", new.count(N_ITEM_DESC), 1), ("S3 旧串 items.desc", new.count(A_ITEM_DESC), 0),
        ("S3 处置句", new.count(ADD_OC_DESC), 1),
        ('S3 oneOf 分支甲 \'          "oneOf": [\'', new.count('          "oneOf": ['), 1),
        ('S3 分支乙字符串形态', new.count('"承载 JSON 编码数组的字符串（外部源兼容形态，D-063）'), 1),
        ('S3 原数组定义原文保留（"type": "array" @ items）', new.count('            "type": "array",'), 1),
    ]
    ok2 = True
    for name, got, exp in checks:
        ok = (got == exp)
        ok2 &= ok
        log.append(f"[{'OK ' if ok else 'FAIL'}] {name}: {got}（应然 {exp}）")

    # 逐行差异（证明改动仅限预期区域）
    nlines = new.split(EOL)
    diffs = []
    import difflib
    for d in difflib.unified_diff(lines, nlines, lineterm="", n=0):
        diffs.append(d)
    log.append("")
    log.append(f"=== 行级差异（unified, n=0）共 {len(diffs)} 行 ===")
    log.extend(diffs)

    nb = new.encode("utf-8")
    log.append("")
    log.append(f"sha256(新) : {sha(nb)}  bytes={len(nb)}  lines={len(nlines)}  CR={nb.count(13)}  LF={nb.count(10)}")
    # JSON 合法性 ＋ 结构断言（正向对照）
    try:
        obj = json.loads(new)
        log.append("[OK ] json.loads 通过")
        ss = obj["properties"]["source_system"]
        oc = obj["$defs"]["order_created"]["properties"]["items"]
        log.append(f"[读数] source_system 键集 = {sorted(ss.keys())}  const 存在? {'const' in ss}")
        log.append(f"[读数] items 键集 = {sorted(oc.keys())}  oneOf 分支数 = {len(oc.get('oneOf', []))}")
        if 'const' in ss or sorted(ss.keys()) != ['description', 'minLength', 'type']:
            ok2 = False
            log.append("[FAIL] source_system 形状不符（应 type/minLength/description，无 const）")
        if [b.get('type') for b in oc.get('oneOf', [])] != ['array', 'string']:
            ok2 = False
            log.append("[FAIL] items.oneOf 分支不是 [array, string]")
        # 正向对照：schema_version 仍含 const
        if 'const' not in obj["properties"]["schema_version"]:
            ok2 = False
            log.append("[FAIL] 正向对照：schema_version 应仍含 const")
        else:
            log.append("[OK ] 正向对照：schema_version 仍含 const（守卫非空转）")
    except Exception as e:  # noqa: BLE001
        ok2 = False
        log.append(f"[FAIL] json.loads 抛异常: {e!r}")

    log.append("")
    if not ok2:
        log.append("SELFCHECK-FAIL：写后复核未通过 ⇒ **不写盘**")
        print("\n".join(log))
        return 3

    if args.apply:
        open(args.file, "wb").write(nb)
        log.append(">>> 已写盘（--apply）")
    else:
        log.append(">>> 干跑（未加 --apply）；未写盘")
    print("\n".join(log))
    return 0


if __name__ == "__main__":
    sys.exit(main())
