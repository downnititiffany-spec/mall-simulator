#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""normalize-readme-eol.py —— 把 README.md 的 3 个**裸 LF** 归一为 CRLF（本仓工作区一律 CRLF）。

成因（必须留痕）：我对 README 的两次补写（apply-readme-final.py / fix-readme-anchors.py）都用
`open(..., 'rb')` 读、`open(..., 'wb')` 写，**本不引入裸 LF**；但更早那次 apply-doc-edits.py 之后
有一步用 PowerShell `Get-Content` → `Set-Content` 读回校对，**PowerShell 的文本读写会在边界上把
部分行尾折成 LF**。结果 README 出现 CR=311 / LF=314（3 处裸 LF），其余 7 个改动文件 CR==LF 一致。

判据：① 归一只改换行、**逐字符内容（删掉所有 CR/LF 后）必须完全相同**；② 归一后 CR == LF。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"


def main():
    raw = open(RM, "rb").read()
    cr, lf = raw.count(13), raw.count(10)
    print(f"写前 raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={cr} LF={lf} 裸LF={lf - cr}")
    t = raw.decode("utf-8")
    body_before = t.replace("\r\n", "").replace("\r", "").replace("\n", "")
    fixed = t.replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\r\n")
    nb = fixed.encode("utf-8")
    body_after = fixed.replace("\r\n", "").replace("\r", "").replace("\n", "")
    ok1 = body_before == body_after
    ok2 = nb.count(13) == nb.count(10)
    ok3 = nb.count(10) == lf
    print(f"[{'OK ' if ok1 else 'FAIL'}] 换行归一未改动任何正文（去掉所有 CR/LF 后逐字符相同）")
    print(f"[{'OK ' if ok2 else 'FAIL'}] 归一后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    print(f"[{'OK ' if ok3 else 'FAIL'}] 逻辑行数不变：{lf} → {nb.count(10)}")
    if not (ok1 and ok2 and ok3):
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    if cr == lf:
        print(">>> 已是全 CRLF，无需归一")
        return 0
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)} LF={nb.count(10)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
