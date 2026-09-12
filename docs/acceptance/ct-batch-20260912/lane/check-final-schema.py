#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""check-final-schema.py —— 终态核对：schema 三处改动的逐字现状（供 REPORT 引用）。"""
import json
import sys

P = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\schemas\canonical-event.v1.schema.json"

d = json.load(open(P, encoding="utf-8"))
oc = d["$defs"]["order_created"]
print("=== ① properties.event_id.description ===")
print(repr(d["properties"]["event_id"]["description"]))
print()
print("=== ② properties.source_system（整节点）===")
print(json.dumps(d["properties"]["source_system"], ensure_ascii=False, indent=2))
print()
print("=== ③ $defs.order_created.description 末段（末 460 字）===")
print(oc["description"][-460:])
print()
print("=== ③ $defs.order_created.properties.items ===")
print(json.dumps(oc["properties"]["items"], ensure_ascii=False, indent=2))
print()
print("=== 正向对照：schema_version 仍锁 const ===")
print(json.dumps(d["properties"]["schema_version"], ensure_ascii=False, indent=2))
print()
print("=== 关键词计数（终态）===")
c = open(P, encoding="utf-8").read()
for k in ('"pattern"', '"enum"', '"const"', '"minLength"', '"oneOf"', '"type": "string",'):
    print(f'  {k:22} = {c.count(k)}')
