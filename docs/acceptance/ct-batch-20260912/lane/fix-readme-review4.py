#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-review4.py —— 写入「缺陷 4：E1-a 子集口径／主检出 537/1 基线」这一条（append-only 追加 bullet）。

为什么单开一个脚本：fix-readme-review3.py 已经在上一轮把缺陷 1/2 写盘了，该脚本因此不再幂等
（旧串命中会变成 0）。本脚本只做「追加 §14.4 一条」这一件幂等的事：
  · 目标 bullet 已存在 ⇒ 报「已存在」并退出 0（不重复写）；
  · 不存在 ⇒ 在 D4_OLD 后追加 D4_NEW，并把旧串保留（append-only：不改旧行）。
行尾口径：README 为 CRLF；替换串里的 `\n` 统一转成 `\r\n`。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

D4_OLD = '- 字符串形态 `items` 的**解析归一**尚未实现（属 DWD，P2-04/P2-05）；本轮只把「契约接受」落成文字与 schema 分支。'
D4_NEW = (D4_OLD + '\n'
          '- **E1-a 是子集口径、`platform-app` 不在本批测试面内**：本批 E1-a 跑的模块集是 `platform-common,connection-ingestion`'
          '（`41 + 156 = 197` 条全绿）。总控在主检出按 **PLAN §3 的字面命令**（含 `-am`）跑出的**改动前**基线为'
          ' **7 个反应堆模块（6 个有测试）／合计 537 tests／1 failure／BUILD FAILURE**，唯一红点是**既有**的'
          ' `IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168`（断言原文 `Expecting empty but was: ["40.json"]`，'
          '`landing/manifests/40.json` 被回填，`landing/**` 属 gitignore 面，与 CT 无关）。⇒ ①PLAN §3 的字面判据「`Failures: 0` ＋ BUILD SUCCESS」'
          '**在主检出动工前即不成立**；②本批 197/0 只是该字面命令的**真子集**（差 `warehouse-pipeline`/`metric-analysis`/`ai-decision`/`platform-app` 共 340 条），'
          '**不得**被读成「PLAN 门禁已过」；③`platform-app` 另有 Windows 偶发项 `LocalProcessSparkSubmitterProcessTest`'
          '（`Failed to delete temp directory`），本批未纳入测试面。')


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    n_line = len(t.splitlines())
    print(f"README raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={raw.count(13)} LF={raw.count(10)}  行数={n_line}")
    if raw.count(13) != raw.count(10) or n_line < 300:
        print("[FAIL] 哨兵：行尾/行数异常 ⇒ 不写盘")
        return 2
    print(f"[OK ] 哨兵：CR == LF == {raw.count(10)}，行数 {n_line} ≥ 300")

    if D4_NEW.replace("\n", EOL) in t:
        print("[同 ] 缺陷 4 bullet **已存在**，无需重复写入（幂等）")
        print(f"[OK ] 判据：`537 tests` 命中 = {t.count('537 tests')}，`197` 子集口径命中 = {t.count('真子集')}，`BUILD FAILURE` 命中 = {t.count('BUILD FAILURE')}")
        return 0

    o = D4_OLD.replace("\n", EOL)
    if t.count(o) != 1:
        print(f"[FAIL] 目标 bullet 命中 = {t.count(o)}（应然 1）")
        return 2
    t = t.replace(o, D4_NEW.replace("\n", EOL), 1)
    nb = t.encode("utf-8")
    ok = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok:
        return 2
    # 验收判据：四个关键读数都落地，且旧行原文仍在（append-only）
    checks = [("537 tests", 1), ("41 + 156 = 197", 1), ("BUILD FAILURE", 1), ("D4 旧行保留", 1)]
    good = True
    for s, want in checks:
        c = t.count(D4_OLD) if s == "D4 旧行保留" else t.count(s)
        good = good and c >= want
        print(f"[{'OK ' if c >= want else 'FAIL'}] 判据「{s}」命中 = {c}（应然 ≥ {want}）")
    if not good:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)} LF={nb.count(10)}  行数={len(t.splitlines())}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
