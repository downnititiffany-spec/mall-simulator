#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""fix-readme-review3.py —— 处置总控复核提出的 3 处文字缺陷（只改 README，不动 schema/代码）。

缺陷 1（阻断收口）：`contract-specs/README.md` §3「禁止跨程序 Java 依赖」那句仍写「`analytics-server` 与
  `mall-simulator` 各有一份 `EventContract.java` 常量副本…两侧 `SOURCE_SYSTEM="mock-mall"`」——CT-1 落地后
  平台侧常量已删 ⇒ 该句成为**假陈述**，正是 `RULINGS.md:183/:191` 预判的「README 与 schema 互相矛盾」。
  处置：改写为当前态 ＋ **追加式**保留原句引文与日期补记（与 §14.3 的 append-only 口径一致）。

缺陷 2：§14.3 自称「§5 那句…（均以追加方式标注处置，不删原文）」，但补丁是**同行 `-`/`+` 整句替换** ⇒ 自述与 diff 矛盾。
  处置：选 **(b)** —— 保留 §5 的当前态改写真句，把 §14.3 的措辞改成事实，并把**逐字原句**落在 §14.3 引文块里（历史不丢）。

缺陷 3：「3 处裸锚点」实为 **2 处**（同一行内两个锚点：`§2.4 L77` ＋ `§2.4 L84-L90`）。本脚本写盘前先复核现文，
  若已收敛为「2 处」则不重复改（避免把已修项再改一次）。

每条替换都先断言「旧串命中 = 1」→ 替换 → 断言「新串 = 1 ∧ 旧串 = 0」；全文 CR==LF 自检后才写盘。

⚠ **踩坑留痕（本脚本 v1 的真实失败）**：v1 用 `open(RM, encoding="utf-8")` 读入——Python 文本模式默认
`newline=None`，会做 **universal newlines** 转换：把 `\r\n` **静默翻译成 `\n`**。于是 `t.split("\r\n")` 只切出
**1 段**（整文件成一"行"），行级判据全部失真（本该 322 段 → 得到 1～7 段），而 `len(t)` 也少了刚好等于 CR 数
（35868 → 35547，差 321 = CR 总数）的字符。同目录其余 README 编辑脚本都走 `open(RM, "rb")` 才没中招。
⇒ 本脚本改为**二进制读入 + 显式 `\r\n` 切分**，并加「行数 ≥ 300」的哨兵断言，一旦再被静默翻译就直接失败。
"""
import hashlib
import sys

RM = r"D:\Develop_code\GraduationProject-wt\ct-batch\contract-specs\README.md"
EOL = "\r\n"

# ---------- 缺陷 1：§3 跨程序依赖那句 ----------
D1_OLD = '当前仓库里 `analytics-server` 与 `mall-simulator` 各有一份 `EventContract.java` 常量副本，就是本目录要消除的耦合形态（两侧常量值经核对一致：`SCHEMA_VERSION="1.0"`、`SOURCE_SYSTEM="mock-mall"`、金额正则相同）。'
D1_NEW = ('当前仓库里 `analytics-server` 与 `mall-simulator` 各有一份 `EventContract.java` 常量副本，就是本目录要消除的耦合形态；'
          '其中 `analytics-server` 侧那份的 `SOURCE_SYSTEM` 常量已随 CT 批次（`D-064 ①`，2026-09-12）**退休**（常量行已删），'
          '故现存同名副本只剩 `mall-simulator` 侧 `EventContract.java` 与生成器 `ContractFormat.SOURCE_SYSTEM`——'
          '后两者是否退休属 `D-064 ②③`／`B-06`，**本轮未决、本批一字未改**'
          '（历史陈述「两侧常量值经核对一致：`SCHEMA_VERSION="1.0"`、`SOURCE_SYSTEM="mock-mall"`、金额正则相同」'
          '作为 CT 批次前的归档结论保留于本节「批次补记」）。')

D1_NOTE = ('\n\n  > **批次补记（2026-09-12 CT 批次 `D-061`…`D-065`）**：本 bullet 的历史原句为——'
           '「当前仓库里 `analytics-server` 与 `mall-simulator` 各有一份 `EventContract.java` 常量副本，就是本目录要消除的耦合形态'
           '（两侧常量值经核对一致：`SCHEMA_VERSION="1.0"`、`SOURCE_SYSTEM="mock-mall"`、金额正则相同）。」\n'
           '  > 该结论在 CT-1 落地后**部分失效**：平台侧 `EventContract.SOURCE_SYSTEM` 已按 `D-064 ①` 退休，故'
           '「两侧同值」不再成立（对账测试中的「两侧常量一致」断言也就地改为**结构守卫**：`source_system` 不得含 `const`）。'
           '原句照上保留、不删，改动缘由见 [`RULINGS.md`](../docs/acceptance/ct-batch-20260912/RULINGS.md)（`D-061`／`D-064`）与本节 §14。')

# ---------- 缺陷 2：§14.3 自述与 diff 矛盾 ----------
D2_OLD = '- §5 建模决定里那句「`source_system` 取 `const`」的原句、schema 与 `event-contract.md` 中所有「固定值：mock-mall」「按 `event_id` 去重」的**历史冲突陈述**（均以追加方式标注处置，不删原文）；'
D2_NEW = ('- §5 建模决定里 `source_system` 那条**决策句已随 `D-061` 改写为当前态**（`const` → 形状约束；补丁为**同行 `-`/`+` 整句替换**，'
          '**不是**「原句保留 ＋ 追加标注」——原措辞易被读成后者，故按 diff 事实更正）。逐字原句与日期补记一并落在本 §14.3 引文块内，'
          '**历史陈述不因改写而丢失**：\n'
          '  > 原句（`2.1.0` 期，2026-09-12 前）：「- **`source_system` 取 `const: "mock-mall"`**：§1 L16「固定值：mock-mall」，'
          '且 `EventContract.SOURCE_SYSTEM` 两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B 的 `synthetic=true` 与它的关系未定（Q3）。」\n'
          '  > 处置（2026-09-12 CT 批次）：`const` 与「parity 测试要求 `const`」双双反转（`D-061`）；平台侧常量退休（`D-064 ①`）。'
          '现文见 §5 对应 bullet。\n'
          '- `schemas/canonical-event.v1.schema.json` 与 `docs/contracts/event-contract.md` 中「固定值：mock-mall」「按 `event_id` 去重」这类'
          '**历史冲突陈述亦为就地改写**（同样不是「原句保留 ＋ 追加标注」）；本批改后的当前态表述见 §14.1 逐项表；');

# ---------- 缺陷 4：登记父侧「主检出 E1-a 基线」环境事实（总控复核追加） ----------
D4_OLD = '- 字符串形态 `items` 的**解析归一**尚未实现（属 DWD，P2-04/P2-05）；本轮只把「契约接受」落成文字与 schema 分支。'
D4_NEW = (D4_OLD + '\n'
          '- **E1-a 是子集口径、`platform-app` 不在本批测试面内**：本批 E1-a 跑的模块集是 `platform-common,connection-ingestion`'
          '（`41 + 156 = 197` 条全绿）。总控在主检出按 **PLAN §3 的字面命令**（含 `-am`）跑出的**改动前**基线为'
          ' **7 个反应堆模块（6 个有测试）/ 合计 537 tests / 1 failure / BUILD FAILURE**，唯一红点是**既有**的'
          ' `IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168`（断言原文 `Expecting empty but was: ["40.json"]`，'
          '`landing/manifests/40.json` 被回填，`landing/**` 属 gitignore 面，与 CT 无关）。⇒ ①PLAN §3 的字面判据「`Failures: 0` ＋ BUILD SUCCESS」'
          '**在主检出动工前即不成立**；②本批 197/0 只是 PLAN 字面命令的**真子集**（差 `warehouse-pipeline`/`metric-analysis`/`ai-decision`/`platform-app` 共 340 条），'
          '**不得**被读成「PLAN 门禁已过」；③`platform-app` 同时存在 Windows 偶发项 `LocalProcessSparkSubmitterProcessTest`'
          '（`Failed to delete temp directory`），本批未纳入测试面。');


def main():
    raw = open(RM, "rb").read()
    t = raw.decode("utf-8")
    n_line = len(t.splitlines())
    print(f"README 写前 raw sha256 = {hashlib.sha256(raw).hexdigest().upper()}  {len(raw)} B  CR={raw.count(13)} LF={raw.count(10)}  行数={n_line}")
    # 哨兵：若读入被做过 universal-newlines 转换，行级判据会静默失真 ⇒ 直接失败。
    if raw.count(13) != raw.count(10) or n_line < 300:
        print(f"[FAIL] 哨兵：CR({raw.count(13)}) != LF({raw.count(10)}) 或行数 {n_line} < 300 ⇒ 读入异常，不写盘")
        return 2
    print(f"[OK ] 哨兵：CR == LF == {raw.count(10)}，行数 {n_line} ≥ 300")
    print()
    print("--- 缺陷 3 复核（先证现文是否已是 2 处）---")
    for s in D3_CHECK:
        print(f"    现文「{s}」命中 = {t.count(s)}")
    if t.count("3 处裸锚点") != 0 or t.count("2 处裸锚点") != 1:
        print("    [FAIL] 「3 处」未收敛 ⇒ 需另行处理")
        return 2
    print("    [OK ] 已收敛为「2 处裸锚点」（同一行内两个锚点），本轮无需再改")
    print()

    ok = True
    # 注意：缺陷 1 是「改写正文」＋「**另起一段**追加日期补记」两步，第一步的替换串里**不含**补记
    # （早前一版误把补记定义成变量却从未拼进替换串 ⇒ 补记根本没落盘，被下面的判据拦下）。
    for tag, old, new in (("缺陷 1", D1_OLD, D1_NEW),
                          ("缺陷 1 补记", "", ""),
                          ("缺陷 2", D2_OLD, D2_NEW),
                          ("缺陷 4", D4_OLD, D4_NEW)):
        if tag == "缺陷 1 补记":
            anchor = D1_NEW.replace("\n", EOL)
            c1 = t.count(anchor)
            good = (c1 == 1 and t.count(D1_NOTE.replace("\n", EOL)) == 0)
            ok = ok and good
            print(f"[{'OK ' if good else 'FAIL'}] {tag}　插入锚命中={c1}（应然 1） 补记命中={t.count(D1_NOTE.replace(chr(10), EOL))}（应然 0）")
            t = t.replace(anchor, anchor + D1_NOTE.replace("\n", EOL), 1)
            continue
        o, n = old.replace("\n", EOL), new.replace("\n", EOL)
        c1, c2 = t.count(o), t.count(n)
        good = (c1 == 1 and c2 == 0)
        ok = ok and good
        print(f"[{'OK ' if good else 'FAIL'}] {tag}　旧串命中={c1}（应然 1） 新串命中={c2}（应然 0）")
        if tag == "缺陷 2":
            print(f"       [留痕] 被替换行 136 字 → 685 字；尾串 = {o[-30:]!r}")
        if not good:
            print(f"       旧串前 120 字：{o[:120]}")
        t = t.replace(o, n, 1)

    if not ok:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2

    # 缺陷 1 的验收判据：把全文件按**三种行**分桶计数，每桶给独立期望值（v1 把期望值拍成"引文行 2 处"，
    # 但 §14.3 的引文行并不含 `SOURCE_SYSTEM="mock-mall"`（它引的是 §5 的原句，只含 `SOURCE_SYSTEM`），
    # 所以正确期望是 引文行 1 处 ＋ §3 改写句 1 处，其余 0 处）：
    #   ① 补记/引文行：`批次补记（2026-09-12 CT 批次` 或 `原句（… `2.1.0` 期`
    #   ② §3 改写句（含「历史陈述」限定语）：允许 1 处归档引述 —— 这是 append-only 的"历史不删"，不是漏改
    #   ③ 其余行：必须 0 处；且不得出现无限定语的假陈述形态
    lines = t.splitlines()
    n_note = n_rewrite = n_other = 0
    for ln in lines:
        k = ln.count('SOURCE_SYSTEM="mock-mall"')
        if ("批次补记（2026-09-12 CT 批次" in ln) or ("原句（" in ln and "`2.1.0` 期" in ln):
            n_note += k
        elif "历史陈述" in ln and "归档结论" in ln:
            n_rewrite += k
        else:
            n_other += k
    print(f"[{'OK ' if n_note == 1 else 'FAIL'}] ① 补记/引文行内命中 = {n_note}（应然 1：§3 补记行；§14.3 引文行引的是 §5 原句，不含 `SOURCE_SYSTEM=\"mock-mall\"`）")
    print(f"[{'OK ' if n_rewrite == 1 else 'FAIL'}] ② §3 改写句（带「历史陈述」限定语）命中 = {n_rewrite}（应然 1：append-only 的归档引述）")
    print(f"[{'OK ' if n_other == 0 else 'FAIL'}] ③ 其余行命中 = {n_other}（应然 0：不得再有无限定语的假陈述）")
    # 附加判据：假陈述形态必须已被「引述化」——出现处必须带限定语
    fake_raw = t.count("各有一份 `EventContract.java` 常量副本") + t.count("两侧实现同值，`CanonicalEventSchemaParityTest` 也要求 `const`。§3.3 B")
    quoted = t.count("（历史陈述「两侧常量值经核对一致") + t.count('原句（`2.1.0` 期，2026-09-12 前）：「- **`source_system` 取 `const: "mock-mall"`**')
    print(f"[{'OK ' if quoted == 2 and fake_raw == 3 else 'FAIL'}] ④ 假陈述形态 = {fake_raw}（3 = ①改写句1 + ②补记1 + ③引文1），其中带引述限定 = {quoted}（应然 2）")
    if n_note != 1 or n_rewrite != 1 or n_other != 0 or quoted != 2:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2

    # 缺陷 2 的验收判据：不得再出现与 diff 矛盾的「均以追加方式标注处置」
    c = t.count("均以追加方式标注处置")
    print(f"[{'OK ' if c == 0 else 'FAIL'}] 与 diff 矛盾的措辞「均以追加方式标注处置」= {c}（应然 0）")
    if c != 0:
        print("SELFCHECK-FAIL ⇒ 不写盘")
        return 2

    nb = t.encode("utf-8")
    ok2 = nb.count(13) == nb.count(10)
    print(f"[{'OK ' if ok2 else 'FAIL'}] 写后 CR == LF：{nb.count(13)} == {nb.count(10)}")
    if not ok2:
        return 2
    open(RM, "wb").write(nb)
    print(f">>> 已写盘  raw sha256 = {hashlib.sha256(nb).hexdigest().upper()}  {len(nb)} B  CR={nb.count(13)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
