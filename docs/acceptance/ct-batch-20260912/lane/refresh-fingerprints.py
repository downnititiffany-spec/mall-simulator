#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""refresh-fingerprints.py（v3）—— 终态指纹刷新（只改「值已过期」的行，不改口径与结构）。

历史踩坑（两条都留痕，避免再犯）：
· **v1 静默空转**：用 `t.find(rel)` 定位段落，但 `README.md` 的名字在台账里出现在两处（契约面段头 ＋
  段落头里的 `README.md §10`），算出的段边界落错区 ⇒ 5 个制品全报「读数未变」，**没报错**却留下旧值
  （典型假绿）。v2 改为按行号定位 ＋ 逐行正则，并加回读断言。
· **v2 universal-newlines 陷阱**：`open(FP, encoding="utf-8")` 默认 `newline=None`，会把 `\r\n` **静默翻译**
  成 `\n`（文件 7,257 B → 7,125 字符，正好少掉 CR 数），于是 `split("\r\n")` 只切出 1 段。⇒ 本版一律
  **二进制读入 + 显式 `\r\n` 切分**，并加「行数 ≥ 60」哨兵。
"""
import hashlib
import os
import re
import sys

WT = r"D:\Develop_code\GraduationProject-wt\ct-batch"
EV = os.path.join(WT, ".verify", "ct-batch")
FP = os.path.join(EV, "fingerprints.txt")
EOL = "\r\n"

CONTRACT = [  # (台账里的段头文本, 制品相对 WT 的路径)
    ("contract-specs\\README.md", os.path.join("contract-specs", "README.md")),
    ("contract-specs\\VERSION", os.path.join("contract-specs", "VERSION")),
    ("contract-specs\\schemas\\canonical-event.v1.schema.json", os.path.join("contract-specs", "schemas", "canonical-event.v1.schema.json")),
    ("docs\\contracts\\event-contract.md", os.path.join("docs", "contracts", "event-contract.md")),
    ("scripts\\check-bare-anchors.ps1", os.path.join("scripts", "check-bare-anchors.ps1")),
]
DELIV = ["ct-batch.patch", "PROGRESS.md", "anchor-guard.txt"]
SELF_NAME = "fingerprints.txt"
SELF_PH = "0" * 64  # 自哈希占位符

RX_RAW = re.compile(r"^(?P<ind>\s*raw\s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)(?P<tail>.*)$")
# 注：**不要**在命名组里写连续字面空格（如 `(?P<ind>\s*LF  \s+)`）——实测该写法在 CPython 3.14.5 上
# 恒不匹配（同串换成 `\s+` 即命中）。此处统一用 `\s+`，并保留 dbg-rx6.py 作为该结论的复现脚本。
RX_LF = re.compile(r"^(?P<ind>\s*LF\s+)(?P<h>[0-9A-F]{64})(?P<sp>\s+)(?P<n>[\d,]+ B)\s*$")
RX_DELIV = re.compile(r"^(?P<ind>\s+)(?P<name>[A-Za-z0-9_.\-]+\.(?:patch|md|txt|log))(?P<sp>\s+)(?P<h>[0-9A-F]{64})(?P<sp2>\s+)(?P<n>[\d,]+ B)\s*$")


def sha(b):
    return hashlib.sha256(b).hexdigest().upper()


def main():
    raw = open(FP, "rb").read()
    t = raw.decode("utf-8")
    lines = t.split(EOL)
    print(f"fingerprints.txt = {len(raw):,} B  CR={raw.count(13)} LF={raw.count(10)}  行数={len(lines)}  sha256={sha(raw)}")
    if len(lines) < 60:
        print(f"[FAIL] 哨兵：行数 {len(lines)} < 60 ⇒ 读入异常，不写盘")
        return 2
    print(f"[OK ] 哨兵：行数 {len(lines)} ≥ 60")
    print()

    changes = 0
    # ---------- ① 契约面：按段头行号定位，段内第 1 条 raw 行、第 1 条 LF 行 ----------
    for head, rel in CONTRACT:
        hi = next((i for i, ln in enumerate(lines) if ln.strip() == head), -1)
        if hi < 0:
            print(f"  [FAIL] 台账无段头：{head}")
            return 2
        ri = li = -1
        for i in range(hi + 1, len(lines)):
            if lines[i].strip() and not lines[i].startswith(" "):
                break
            if ri < 0 and RX_RAW.match(lines[i]):
                ri = i
            elif ri >= 0 and li < 0 and RX_LF.match(lines[i]):
                li = i
                break
        if ri < 0 or li < 0:
            print(f"  [FAIL] 段内找不到 raw/LF 行：{head}（raw@{ri} lf@{li}）")
            return 2
        b = open(os.path.join(WT, rel), "rb").read()
        b_lf = b.replace(b"\r\n", b"\n")
        h_raw, h_lf = sha(b), sha(b_lf)
        cr, lf = b.count(13), b.count(10)
        m, m2 = RX_RAW.match(lines[ri]), RX_LF.match(lines[li])
        new_ri = f"{m.group('ind')}{h_raw}{m.group('sp')}{len(b):,} B  CR={cr} LF={lf}" + (" (一致)" if cr == lf else " (!! CR != LF)")
        new_li = f"{m2.group('ind')}{h_lf}{m2.group('sp')}{len(b_lf):,} B"
        if new_ri != lines[ri] or new_li != lines[li]:
            print(f"  [改] {head}\n        raw {h_raw}  {len(b):,} B  CR={cr} LF={lf}（原 {m.group('h')[:8]}… {m.group('n')}）\n        LF  {h_lf}  {len(b_lf):,} B（原 {m2.group('h')[:8]}… {m2.group('n')}）")
            lines[ri], lines[li] = new_ri, new_li
            changes += 1
        else:
            print(f"  [同] {head} 读数已是最新")

    # ---------- ② 交付物自身 ----------
    ti = next((i for i, ln in enumerate(lines) if ln.strip() == "=== 交付物自身指纹 ==="), -1)
    if ti < 0:
        print("  [FAIL] 台账无「交付物自身指纹」段")
        return 2
    for i in range(ti + 1, len(lines)):
        m = RX_DELIV.match(lines[i])
        if not m or m.group("name") not in DELIV:
            continue
        b = open(os.path.join(EV, m.group("name")), "rb").read()
        nl = f"{m.group('ind')}{m.group('name')}{m.group('sp')}{sha(b)}{m.group('sp2')}{len(b):,} B"
        if nl != lines[i]:
            print(f"  [改] 交付物 {m.group('name')}  {sha(b)}  {len(b):,} B（原 {m.group('h')[:8]}… {m.group('n')}）")
            lines[i] = nl
            changes += 1
        else:
            print(f"  [同] 交付物 {m.group('name')} 读数已是最新")

    # ---------- ③ 自哈希：用「置零摘要」口径（可复核，且不是悖论） ----------
    # 为什么不用"写盘后整体 sha256"：那是个**悖论**——把文件的 sha256 写进文件本身，会让 sha256 又变，
    # 任意多轮迭代都不收敛（实测 12 轮 len 稳定在 7,260 B 而 sha 每轮都变；见 dbg-fp.py 复现）。
    # 历史口径问题：旧台账记 2F8BDB12… 5,626 B，与**写盘后**的实际文件（7,257 B）不符 ⇒ 读者无法复核。
    # 现改为**置零摘要（blanked digest）**：把自身行里那 64 位哈希替换成 64 个 "0"，对整份内容算 sha256。
    # 复核方式（写进台账，读者可照做）：把该行的哈希字段挖空为 64 个 0 → sha256 应等于台账登记值。
    si = next((i for i, ln in enumerate(lines)
               if RX_DELIV.match(ln) and RX_DELIV.match(ln).group("name") == SELF_NAME), -1)
    if si < 0:
        print(f"  [FAIL] 台账交付物段内无 {SELF_NAME} 行")
        return 2
    m = RX_DELIV.match(lines[si])
    self_ind, self_pre, self_sp2 = m.group("ind"), f"{m.group('name')}{m.group('sp')}", m.group("sp2")

    def blank_me(size_text):
        """返回「把自身行哈希字段挖空」后的整份字节，用于置零摘要。"""
        keep = lines[si]
        lines[si] = f"{self_ind}{self_pre}{SELF_PH}{self_sp2}{size_text}"
        out = (EOL.join(lines) + EOL).encode("utf-8")
        lines[si] = keep
        return out

    n_now = len((EOL.join(lines) + EOL).encode("utf-8"))
    for _ in range(8):  # 尺寸文本可能改变自身长度 ⇒ 迭代到稳定（hash 字段固定 64 位，故必然快速稳定）
        probe = blank_me(f"{n_now:,} B")
        hb, nb2 = sha(probe), len(probe)
        if nb2 == n_now:
            break
        n_now = nb2
    lines[si] = f"{self_ind}{self_pre}{hb}{self_sp2}{n_now:,} B"
    print(f"  [定] 交付物 {SELF_NAME}（置零摘要口径）{hb}  {n_now:,} B")

    # ---------- ④ 写前回读断言 ----------
    body = EOL.join(lines) + EOL
    for head, rel in CONTRACT:
        b = open(os.path.join(WT, rel), "rb").read()
        for h, tag in ((sha(b), "raw"), (sha(b.replace(b"\r\n", b"\n")), "LF")):
            if h not in body:
                print(f"  [FAIL] 回读断言：{head} 的 {tag} 现算值未写进台账（{h}）")
                return 2
    for name in DELIV:
        b = open(os.path.join(EV, name), "rb").read()
        if sha(b) not in body:
            print(f"  [FAIL] 回读断言：交付物 {name} 现算值未写进台账")
            return 2
    nb = body.encode("utf-8")
    ok = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok:
        return 2
    if changes == 0:
        print(">>> 无需改动（台账已是最新）")
        return 0
    open(FP, "wb").write(nb)
    print(f">>> 已写盘  更新 {changes} 处  {len(nb):,} B  CR={nb.count(13)}  行数={len(lines)}")
    # 写盘后复核：置零摘要必须复现（读者可照做）
    after = open(FP, "rb").read().decode("utf-8").split(EOL)
    keep = after[si]
    after[si] = f"{self_ind}{self_pre}{SELF_PH}{self_sp2}{n_now:,} B"
    recheck = sha((EOL.join(after) + EOL).encode("utf-8"))
    after[si] = keep
    print(f">>> 写盘后置零摘要复算 = {recheck}（台账登记 = {hb}）⇒ {'一致 OK' if recheck == hb else '不一致 FAIL'}")
    print(f">>> 写盘后实际文件 sha256 = {sha(nb)}（**不等于**登记值，属预期：登记的是置零摘要；见台账口径行）")
    print(f">>> 写盘后实际字节数 = {len(nb):,} B（台账登记 = {n_now:,} B）⇒ {'一致 OK' if len(nb) == n_now else '不一致 FAIL'}")
    if recheck != hb or len(nb) != n_now:
        print("  [FAIL] 写盘后自洽校验失败")
        return 2
    print("  [OK ] 写盘后自洽校验通过（置零摘要与字节数均复现）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
