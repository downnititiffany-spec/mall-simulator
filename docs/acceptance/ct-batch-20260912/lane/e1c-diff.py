#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""e1c-diff.py —— E1-c「真实数据差异」判定器（先断言后结论，判据非空转）。

输入：e1c-before.txt / e1c-after.txt（e1c-validate.py 产物）
判定：
  D1 两文件声明的夹具 sha256 必须相同（否则不是同一份真实数据，结论无效）
  D2 失败**行集**差 = ∅ 且交 = 全集 ⇒ 行级无变化（PLAN 字面判据"failure line set"）
  D3 失败**错误行集**差：消失集必须**恰等于** items 相关集合，新增集必须 = ∅
      —— 这才是能区分"改对了"与"根本没生效"的判据
  D4 消失的每一条必须命中 path 含 properties/items
  D5 保留集必须逐行逐字符相同（正向对照：除 items 外一个字都不许动）
  D6 正向对照（F-38 ①）：items 相关集合必须**非空**（否则 D3 是空转判据）
输出：机器可读 trailer，供 REPORT 直接引用。
"""
import hashlib
import re
import sys

EOL = "\n"


def load(path):
    raw = open(path, "rb").read()
    text = raw.decode("utf-8")
    lines = text.splitlines()
    meta = {"sha256": None, "bytes": None, "data_sha256": None, "data_bytes": None,
            "faillines": None, "detail": {}, "itemskw": None}
    cur = None
    section = None
    for ln in lines:
        if ln.startswith("schema :"):
            section = "schema"
        elif ln.startswith("data   :"):
            section = "data"
        m = re.match(r"^\s+sha256=([0-9A-F]{64})\s+bytes=(\d+)$", ln)
        if m and section == "schema" and meta["sha256"] is None:
            meta["sha256"] = m.group(1)
            meta["bytes"] = int(m.group(2))
        if m and section == "data" and meta["data_sha256"] is None:
            meta["data_sha256"] = m.group(1)
            meta["data_bytes"] = int(m.group(2))
        m = re.match(r"^\[读数\] 失败行号集合 = \[(.*)\]$", ln)
        if m:
            meta["faillines"] = [int(x) for x in m.group(1).split(",") if x.strip()]
        m = re.match(r"^  --- 行 (\d+) ---$", ln)
        if m:
            cur = int(m.group(1))
            meta["detail"][cur] = []
        elif cur is not None and ln.startswith("      "):
            meta["detail"][cur].append(ln.strip())
        elif ln.startswith("  --- ") or ln.startswith("=== ") or ln.startswith("[") and "关键词" in ln:
            cur = None
        m = re.match(r"^ITEMSKEYWORDS=(.*)$", ln)
        if m:
            meta["itemskw"] = m.group(1)
    return meta, raw


def main():
    b_path, a_path = sys.argv[1], sys.argv[2]
    b, braw = load(b_path)
    a, araw = load(a_path)
    out = []
    W = out.append
    ok = True

    def chk(name, cond, detail=""):
        nonlocal ok
        ok = ok and bool(cond)
        W(f"[{'OK ' if cond else 'FAIL'}] {name}{(' — ' + detail) if detail else ''}")

    W("=== E1-c 差异判定 ===")
    W(f"before : {b_path}")
    W(f"         schema sha256={b['sha256']} bytes={b['bytes']}")
    W(f"         data   sha256={b['data_sha256']} bytes={b['data_bytes']}")
    W(f"after  : {a_path}")
    W(f"         schema sha256={a['sha256']} bytes={a['bytes']}")
    W(f"         data   sha256={a['data_sha256']} bytes={a['data_bytes']}")
    W("")

    W("--- D1 同一份真实数据 ---")
    chk("夹具字节级相同（data sha256 一致）", b["data_sha256"] == a["data_sha256"] and b["data_sha256"],
        str(b["data_sha256"]))
    chk("夹具字节数一致", b["data_bytes"] == a["data_bytes"], f"{b['data_bytes']} vs {a['data_bytes']}")
    chk("两次读的是**不同**的 schema（否则差异无意义）", b["sha256"] != a["sha256"],
        f"{b['sha256'][:16]}… vs {a['sha256'][:16]}…")

    W("")
    W("--- D2 失败行集（PLAN 字面判据）---")
    sb, sa = set(b["faillines"]), set(a["faillines"])
    W(f"before 失败行集（{len(sb)} 行）= {sorted(sb)}")
    W(f"after  失败行集（{len(sa)} 行）= {sorted(sa)}")
    W(f"消失行（before \\ after）= {sorted(sb - sa)}")
    W(f"新增行（after \\ before）= {sorted(sa - sb)}")
    chk("行级失败集无变化（差 = ∅ ∧ 交 = 全集）", sb == sa,
        f"|before|={len(sb)} |after|={len(sa)} |交|={len(sb & sa)}")
    W("说明：本夹具里 6 行字符串 items 的 order_created **同时**缺 status/created_at，")
    W("      故 items 修好之后这些行仍因 required 继续失败 ⇒ 行级差异必然为空。")
    W("      ⇒ PLAN 的「行级差集」判据在本夹具上**无法区分改对与没生效**；改用下面的错误级判据。")

    W("")
    W("--- D3 失败错误行集（可区分判据）---")
    eb = {(ln, e) for ln, arr in b["detail"].items() for e in arr}
    ea = {(ln, e) for ln, arr in a["detail"].items() for e in arr}
    gone = sorted(eb - ea, key=lambda t: (t[0], t[1]))
    new = sorted(ea - eb, key=lambda t: (t[0], t[1]))
    W(f"before 错误条数 = {len(eb)}；after 错误条数 = {len(ea)}")
    W(f"消失错误 {len(gone)} 条 / 新增错误 {len(new)} 条")
    W("消失明细：")
    for ln, e in gone:
        W(f"    行 {ln}: {e}")
    W("新增明细：")
    for ln, e in new:
        W(f"    行 {ln}: {e}")
    chk("新增错误 = ∅（没有引入新失败）", not new, f"{len(new)} 条")
    chk("消失错误全部 path 命中 properties/items",
        all("properties', 'items'" in e or "properties/items" in e for _, e in gone),
        f"{len(gone)} 条")
    chk("消失错误全部是 items 的 'type' 关键字",
        all("'items', 'type'" in e for _, e in gone), f"{len(gone)} 条")
    chk("消失错误条数 = 6（真实夹具 6 行字符串 items 各 1 条）", len(gone) == 6, f"{len(gone)} 条")

    W("")
    W("--- D4 保留集逐字符相同（正向对照：除 items 外一个字都不许动）---")
    keepb = sorted(eb - set(gone), key=lambda t: (t[0], t[1]))
    keepa = sorted(ea, key=lambda t: (t[0], t[1]))
    same = (keepb == keepa)
    chk("保留错误集逐条相同", same, f"before 保留 {len(keepb)} 条 / after {len(keepa)} 条")
    if not same:
        for x in keepb[:5]:
            if x not in ea:
                W(f"    before 独有：行 {x[0]}: {x[1]}")
        for x in keepa[:5]:
            if x not in eb:
                W(f"    after  独有：行 {x[0]}: {x[1]}")
    W(f"保留集第一条：行 {keepb[0][0]}: {keepb[0][1]}" if keepb else "保留集为空")

    W("")
    W("--- D5 正向对照（判据非空转）---")
    chk("items 相关错误集合非空（D3 不是空转）", len(gone) > 0, f"{len(gone)} 条")
    chk("夹具 order_created 字符串形态行数 = 6（与 PLAN §5 基线登记一致）", len(gone) == 6)

    W("")
    W(f"VERDICT={'PASS' if ok else 'FAIL'}")
    W(f"ROWSET_UNCHANGED={sb == sa}")
    W(f"GONE={len(gone)} NEW={len(new)} KEPT={len(keepb)}")
    txt = EOL.join(out)
    W("")
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    print(txt)
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
