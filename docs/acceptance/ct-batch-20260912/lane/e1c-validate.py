#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""E1-c 真实数据对照：以给定 canonical-event schema 校验黄金数据集 JSONL，输出失败行号集合与失败明细。

用法：
    python e1c-validate.py <schema.json> <data.jsonl> <out.txt>

纪律（陷阱 #27 自检）：脚本先算"应然值"（行数、全行可解析数、schema 文档自检结论），
再与实际读数对照；对照不符即在输出里写 SELFCHECK-FAIL。
只读输入，不写任何仓内文件（输出路径由调用方指定）。
"""
import hashlib
import json
import sys

import jsonschema
from jsonschema import Draft202012Validator

CAP = 25  # 每行最多记录的校验错误条数（只影响明细长度，不影响"是否失败"的判定）


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest().upper()


def main():
    schema_path, data_path, out_path = sys.argv[1], sys.argv[2], sys.argv[3]
    out = []
    out.append("=== E1-c 真实数据对照 ===")
    out.append(f"schema : {schema_path}")
    out.append(f"        sha256={sha256(schema_path)}  bytes={__import__('os').path.getsize(schema_path)}")
    out.append(f"data   : {data_path}")
    out.append(f"        sha256={sha256(data_path)}  bytes={__import__('os').path.getsize(data_path)}")
    out.append(f"jsonschema 版本: {__import__('importlib.metadata', fromlist=['version']).version('jsonschema')}")
    out.append("")

    with open(schema_path, "r", encoding="utf-8") as f:
        schema = json.load(f)

    # 应然值 1：schema 文档本身合法
    check_err = None
    try:
        Draft202012Validator.check_schema(schema)
    except Exception as e:  # noqa: BLE001
        check_err = repr(e)
    out.append(f"[自检] Draft202012Validator.check_schema: {'OK' if check_err is None else 'FAIL ' + check_err}")
    # 应然值 2：source_system 节点形态（CT-1 前后各不同，如实打印）
    src = schema.get("properties", {}).get("source_system", {})
    out.append(f"[读数] properties.source_system 键集 = {sorted(src.keys())}")
    defs = schema.get("$defs", {}).get("order_created", {})
    items = defs.get("properties", {}).get("items", {})
    out.append(f"[读数] $defs.order_created.properties.items 键集 = {sorted(items.keys())}")
    out.append("")

    with open(data_path, "r", encoding="utf-8") as f:
        raw_lines = f.read().split("\n")
    if raw_lines and raw_lines[-1] == "":
        raw_lines.pop()
    out.append(f"[读数] 非空行（逻辑行）数 = {len(raw_lines)}")
    out.append("")

    validator = Draft202012Validator(schema)
    failures = {}          # 行号 -> 明细列表
    unparsable = []        # 行号（JSON 本身不可解析）
    keywords = {}          # 关键词 -> 行号集合
    for i, line in enumerate(raw_lines, start=1):
        if line.strip() == "":
            failures[i] = ["<空行>"]
            continue
        try:
            obj = json.loads(line)
        except Exception as e:  # noqa: BLE001
            unparsable.append(i)
            failures[i] = [f"JSON 不可解析: {e}"]
            keywords.setdefault("UNPARSABLE", set()).add(i)
            continue
        errs = sorted(validator.iter_errors(obj), key=lambda e: list(e.absolute_path))
        if not errs:
            continue
        det = []
        for e in errs[:CAP]:
            path = "/".join(str(p) for p in e.absolute_path) or "<root>"
            det.append(f"{list(e.absolute_schema_path)} @ {path}: {e.message[:200]}")
            kws = {list(e.absolute_schema_path)[0]} if e.absolute_schema_path else set()
            for kw in kws:
                keywords.setdefault(str(kw), set()).add(i)
            if "oneOf" in [str(p) for p in e.absolute_schema_path]:
                keywords.setdefault("oneOf", set()).add(i)
        if len(errs) > CAP:
            det.append(f"... 另有 {len(errs) - CAP} 条错误未列出")
        failures[i] = det

    out.append(f"[读数] 失败行数 = {len(failures)} / {len(raw_lines)}；不可解析行 = {unparsable}")
    out.append(f"[读数] 失败行号集合 = {sorted(failures.keys())}")
    out.append("")
    out.append("[读数] 关键词 → 涉及行号（用于判定失败是否为 items 相关）：")
    for kw in sorted(keywords.keys()):
        out.append(f"    {kw}: {sorted(keywords[kw])}")
    out.append("")
    out.append("[明细] 逐失败行（最多 %d 条/行）：" % CAP)
    for i in sorted(failures.keys()):
        out.append(f"  --- 行 {i} ---")
        for d in failures[i]:
            out.append(f"      {d}")
    out.append("")
    out.append("FAILSET=" + ",".join(str(x) for x in sorted(failures.keys())))
    out.append("ITEMSKEYWORDS=" + ",".join(sorted(keywords.keys())))

    text = "\n".join(out) + "\n"
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(text)
    print(text)


if __name__ == "__main__":
    main()
