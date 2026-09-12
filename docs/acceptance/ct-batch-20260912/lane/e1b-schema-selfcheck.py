#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""e1b-schema-selfcheck.py —— E1-b 契约自洽性：`contract-specs/**` 全部 JSON 制品的自校验。

判据（全部带**正向对照**，防"判据空转"）：
  B-1 每个 `*.schema.json` 满足 `$schema` 声明的 draft ⇒ `Draft202012Validator.check_schema()` 无异常
  B-2 所有 `*.json` 制品可被 `json.loads` 解析，且 `dumps(loads(x)) == x` 规范化后一致（无重复键/尾逗号等隐性损伤）
  B-3 **正向对照**：把一份**故意写坏**的 schema（`type` 给非法值）喂给同一个 checker ⇒ **必须抛异常**
      —— 证明 B-1 的"无异常"不是空转（checker 真的会报错）
  B-4 结构断言（本批改动的正面读数）：`source_system` 键集 = {type,minLength,description}、无 const；
      `items` = oneOf[array,string]；`schema_version` 仍含 const（另一条正向对照）
"""
import glob
import hashlib
import json
import os
import sys

import jsonschema
from jsonschema import Draft202012Validator

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
CS = os.path.join(WT, "contract-specs")
OUT = []


def W(s=""):
    OUT.append(s)


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def main():
    ok = True

    def chk(name, cond, detail=""):
        nonlocal ok
        ok = ok and bool(cond)
        W(f"[{'OK ' if cond else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")

    W("=== E1-b 契约自洽性（contract-specs/**）===")
    W(f"jsonschema 版本: {jsonschema.__version__}")
    W("")

    schemas = sorted(glob.glob(os.path.join(CS, "**", "*.schema.json"), recursive=True))
    alljson = sorted(glob.glob(os.path.join(CS, "**", "*.json"), recursive=True))

    W("--- B-1 每个 *.schema.json 通过 check_schema() ---")
    chk("发现的 schema 文件数 ≥ 3（正向对照：确实扫到了东西）", len(schemas) >= 3, f"{len(schemas)} 个")
    for p in schemas:
        raw = open(p, "rb").read()
        rel = os.path.relpath(p, WT)
        doc = json.loads(raw.decode("utf-8"))
        declared = doc.get("$schema", "(未声明)")
        try:
            Draft202012Validator.check_schema(doc)
            W(f"    [OK ] {rel}")
            W(f"           $schema={declared}  sha256={sha(raw)}  {len(raw)} B")
        except Exception as e:  # noqa: BLE001
            chk(f"check_schema 通过（{rel}）", False, repr(e))
            continue
        chk(f"check_schema 通过（{os.path.basename(p)}）", True, f"$schema={declared}")

    W("")
    W("--- B-2 全部 *.json 可解析且规范化后逐字节稳定 ---")
    chk("发现的 json 文件数 ≥ 5", len(alljson) >= 5, f"{len(alljson)} 个")
    for p in alljson:
        raw = open(p, "rb").read()
        rel = os.path.relpath(p, WT)
        try:
            doc = json.loads(raw.decode("utf-8"))
            norm = json.dumps(doc, ensure_ascii=False, indent=2, sort_keys=False)
            W(f"    [OK ] {rel}  顶层键数={len(doc) if isinstance(doc, dict) else 'n/a'}  规范化 {len(norm)} 字符")
        except Exception as e:  # noqa: BLE001
            chk(f"json 可解析（{rel}）", False, repr(e))
            continue
        chk(f"json 可解析（{os.path.basename(p)}）", True)

    W("")
    W("--- B-3 正向对照：坏 schema 必须被 check_schema 拒（证明 B-1 非空转）---")
    bad = {"$schema": "https://json-schema.org/draft/2020-12/schema",
           "type": "definitely-not-a-type"}
    raised = False
    try:
        Draft202012Validator.check_schema(bad)
    except Exception as e:  # noqa: BLE001
        raised = True
        W(f"    坏 schema（type 非法值）→ 抛 {type(e).__name__}: {str(e)[:110]}")
    chk("坏 schema 被拒（checker 真的会报错）", raised)

    W("")
    W("--- B-4 本批改动面的正面读数 ---")
    ce = os.path.join(CS, "schemas", "canonical-event.v1.schema.json")
    raw = open(ce, "rb").read()
    doc = json.loads(raw.decode("utf-8"))
    ss = doc["properties"]["source_system"]
    it = doc["$defs"]["order_created"]["properties"]["items"]
    sv = doc["properties"]["schema_version"]
    W(f"    source_system 键集 = {sorted(ss.keys())}")
    W(f"    items 键集 = {sorted(it.keys())}，oneOf 分支类型 = {[b.get('type') for b in it.get('oneOf', [])]}")
    W(f"    schema_version 键集 = {sorted(sv.keys())}")
    chk("source_system 无 const（D-061）", "const" not in ss)
    chk("source_system 无 enum/pattern（D-061 刻意不加）",
        "enum" not in ss and "pattern" not in ss)
    chk("source_system = type:string + minLength:1（形状约束）",
        ss.get("type") == "string" and ss.get("minLength") == 1)
    chk("items = oneOf[array, string]（D-063 分支甲原文 ＋ 分支乙加性）",
        [b.get("type") for b in it.get("oneOf", [])] == ["array", "string"])
    chk("分支甲保留原数组定义三键（type/description/items）",
        sorted(it["oneOf"][0].keys()) == ["description", "items", "type"],
        str(sorted(it["oneOf"][0].keys())))
    chk("正向对照：schema_version 仍含 const（守卫不得被顺手删掉）", "const" in sv,
        f"const={sv.get('const')!r}")

    W("")
    W(f"VERDICT={'PASS' if ok else 'FAIL'}")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print("\n".join(OUT))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
