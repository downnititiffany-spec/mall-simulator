#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""append-progress-review.py —— PROGRESS.md **只追加**本轮（父侧审查回执）记录。

纪律：append-only —— 只在文件末尾追加，**不改写任何历史行**；脚本自证「写盘后内容以原字节为前缀」。
PROGRESS.md 现态为纯 LF（CR=0 / LF=75，与 REPORT.md 同）；追加段沿用文件现有行尾，不引入 CRLF 混杂。
幂等：若本轮小标题已存在则跳过。
"""
import hashlib
import sys

PG = r"D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\PROGRESS.md"


BLOCK = """
---

## 追加（父侧集成预检回执轮，2026-09-12）：5 条缺陷全部核实并处置

- **纪律前置**：父侧判据逐条自核，**无一条以"实测反驳"结案——5 条全部属实、全部已改**。
  缺陷 2 明确选择 **(b)：按实测把「3 处」改成「2 处」**；(a)「保留 3 处并改判据」被否——
  判据本无错，错的是我先前把台账里 `§2.4 L84` 的**出现次数 2** 误当成"3 个独立裸锚点行"。
- **缺陷 1（README `:43` / `:71` 假陈述）**：`RULINGS.md:183` / `:191` 明令这两句必须逐句更新。
  `README.md:43` 就地改写为当前态（含「已退休 / 未决」边界）；**原句以批次补记引用块逐字保留**
  （`README.md:45-46`）＋ 追加日期化处置标注 ⇒ 历史行不被改写。
- **缺陷 2（计数 3 → 2）**：实测本批补前缀 = **2 处**（`§2.4 L77` ×1 ＋ `§2.4 L84-L90` ×1，
  同在 `items` 容器那一行描述里），与守卫读数 201→199 处、113→112 行逐项相符。
- **缺陷 3（§14.3 措辞）**：经逐字比对 `git diff`，补丁对 §5 那条决策句是**同行 `-`/`+` 整句替换**，
  原措辞暗示的「原句保留 ＋ 追加标注」与事实不符 ⇒ 改写为与 diff 形态一致，并补记
  schema / `event-contract.md` 的历史冲突陈述属**就地改写**（原句同样以引用块逐字保留）。
- **缺陷 4（E1-a 口径）**：总控在主检出按 PLAN §3 字面命令（含 `-am`）实测改动前基线
  = **537 tests / 1 failure / BUILD FAILURE**（platform-common 41/0、connection-ingestion 156/0、
  warehouse-pipeline 111/0、metric-analysis 38/0、ai-decision 91/0、platform-app 100/1）。
  唯一红点为**既有** `IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`——
  本轮**独立复核到行号**：该断言在 `platform-app/src/test/.../IngestionManifestSourceSchemaTest.java:166-168`，
  即 `assertThat(backfilled).as("前提校验：历史清单一个都不许被回填/改写（D-037 裁决 6）…").isEmpty()`，
  与父侧给出的断言原文 `Expecting empty but was: ["40.json"]` 完全对应 ⇒ **父侧判据复核通过**。
  已写入 REPORT ② E1-a 与 README §14.4，含三条防误读声明：①PLAN §3 字面判据在主检出动工前即不成立；
  ②本批 `41 + 156 = 197 / 0` 只是该字面命令的**真子集**（差 4 模块共 340 条），**不得**读成「门禁已过」；
  ③那 340 条**本批未跑、且 worktree 内不可跑**，无读数、不声称。
- **缺陷 5（补丁行尾）**：首版 `ct-batch.patch` 是 **CRLF 260 行 / 纯 LF 0 行**（用 PowerShell 重定向落盘所致），
  父侧 LF 检出上 `git apply --check` **8/8 `patch does not apply`**。本轮改用
  `$t = (git diff -- <8 文件> | Out-String) -replace "``r``n","``n"` ＋ `[IO.File]::WriteAllText(…, UTF8Encoding($false))`
  重导出，**内容一字未改**。自证读数：**CRLF 0 行 / 纯 LF 272 行 / CR 总数 0**，
  口径为 `[IO.File]::ReadAllText` ＋ `[regex]::Matches`（**刻意不用** `ReadAllLines`/`Get-Content`，
  它们会吃掉行尾并给出"带 CR=0"的假结论）；并加一条**回读复核**：把整个补丁按 CRLF→LF 规范化后**逐字节不变**。
- **本轮自查又发现 2 个问题（都属我自己的取证/交付缺陷，已修）**：
  ① **补丁 blob 哈希只有 7 位**：首版用 `git diff`（受 `core.abbrev` 影响）⇒ `index` 行写的是 **7 位**缩写哈希，
    不足以构成「补丁 ≡ 哪个快照」的证明。改用 **`git diff --full-index`** ⇒ `index` 行 **40 位**；
    再逐段把「补丁 `index` 新侧哈希」与「`git hash-object` 对当前工作区文件算出的 blob 哈希」比对
    ⇒ **8/8 逐字符相同**（每段的两个哈希现值都留在 `dbg-blob-vs-patch.log`）。**终态：36,389 B /
    sha256 `DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945` / 8 段 / CRLF 0 / 纯 LF 272**。
    副产物：这条同时**证实**了双口径——git 记录的是 **LF 形态 blob**（README 的 LF 规范化 60,632 B 才是 blob 口径，
    raw 60,960 B 是检出 CRLF 口径）。
  ② **核对脚本自身的假 FAIL**：`dbg-blob-vs-patch.py` 首版的正则**漏了 `re.M`**，`^` 只匹配整份补丁的第 1 个 `index` 行，
    其余 7 段取不到值 ⇒ 输出「**0/8 段相符**」这样一个**看起来很严重的 FAIL**——实际上它**只检查了 1 段**，
    而当时的真问题是「拿 7 位缩写哈希去比 64 位 sha256」的**口径错配**，不是内容错。
    处置：① 正则加 `re.M`；② 改比同域的 40 位 blob 哈希；③ 加断言「`index` 行恰 8 条 ∧ 位宽 == 40」。
    **教训（值得进方法论）：这类核对脚本无论 FAIL 还是 PASS，都要先自证"到底检查了几条"**，
    否则会把"没查到"当成"查出错"，或者把"只查了一条"当成"全查过了"。
- **本轮新踩的 3 个工具坑（全部属取证脚本缺陷，非产品缺陷，逐条留痕）**：
  ① **Python universal-newlines 静默改写**：`open(p, encoding="utf-8")` 默认 `newline=None`，把 `\\r\\n`
     静默翻译成 `\\n` —— 读数短 **321** 字符，`split("\\r\\n")` 只切出 1 段，`count("\\r")` 为 0，**且不报错**。
     已逐个审计早前 7 个 README 改写脚本（全部 `open(RM,"rb")`，**未受影响**）；改用
     `open(p,"rb").read().decode("utf-8")` ＋ `splitlines()` ＋ 哨兵「CR == LF ∧ 行数 ≥ 300」。
  ② **`EOL.join(lines) + EOL` 多一个行终止符**：源 7,271 B → split 得 80 元素（末元素 `''`）→ join+EOL = 7,273 B，
     把台账尾巴撑成 `\\r\\n\\r\\n`。正确往返是 `EOL.join(lines)`（**不加** EOL）；台账已整份重建
     （`refresh-fingerprints2.py`，7,973 B / CR=LF=78 / 行数 79，二次运行报「幂等：逐字节相同」）。
  ③ **命名组里写字面空格恒不匹配 ＋ 自哈希悖论**：`(?P<ind>\\s*LF  \\s+)`（组内两个字面空格）在
     CPython 3.14.5 上恒不匹配，换 `\\s+` 即命中（复现 `dbg-rx6.py`）；登记"文件自身 sha256"**不收敛**
     （12 轮长度稳定 7,260 B 而 sha 每轮都变，复现 `dbg-fp.py`），旧值 `2F8BDB12… 5,626 B` 与实际
     7,257 B 不符、读者无法复核 ⇒ 改用**置零摘要**口径（自身行哈希挖空成 64 个 `0` 后算 sha256 =
     `C737A317B5094F1CB80C201A7C5E0D933DBD1BF0A74B1D198524B1558D481556`），复核步骤写进台账首部。
- **锚点门禁第 4 次拦下（本轮新引入，已修）**：缺陷 1 重写稿含无前缀 `§1 L16「固定值：mock-mall」` ⇒
  `[门禁失败] 台账内锚点出现次数升高 1 处——README.md :: §1 L16 台账=2 实测=3`、`EXITCODE=1`。
  只给**本轮新增的**那处（§14.3 引文行）补文件名前缀；§8 第 3 条 Q3 那处（`README.md:101`，历史基线）
  **刻意不动**。修后复位 `PASS（裸锚点 199 处 / 112 行，全部在台账内；台账 113 行 ≤ 基线 113）`、`EXITCODE=0`。
  **四次拦下全部是真实缺陷，非门禁误报。**
- **终态**：README raw `5896AD0EE9C1C6BBA7EE033CCB3FD55724D2944B366689F2A080EB428AE6B73C`（60,960 B，CR=LF=328）；
  `--numstat` 合计 **+99 / −21**（首版 +91 / −20，增量全在 README）；`git status` 仍**恰好 8 个文件**、无未跟踪、
  无删除、无重命名；**全程无任何 git 写操作**（只读命令：`status/diff/log/show/ls-files/hash-object`）；
  本轮**未重跑 Maven**（父侧明令），E1-a/E1-b/E1-c/E1-d 读数沿用已留档原始日志。
- **仍未取证（照旧显式标注，未因本轮修改变动）**：`landing/events/r9-m1-123006.jsonl` 泳道内取不到（CT-4 未裁决）；
  T2 未重跑；字符串形态 `items` 解析归一未实现；未收敛台账、未跑 `-WriteLedger`；`D-064 ②③` / `B-06` 未裁决；
  无 E3/E4/E5。
"""


def main():
    raw = open(PG, "rb").read()
    t = raw.decode("utf-8")
    eol = "\r\n" if raw.count(13) else "\n"
    print(f"PROGRESS.md = {len(raw):,} B  CR={raw.count(13)} LF={raw.count(10)}  行数={len(t.splitlines())}  行尾={'CRLF' if eol == chr(13)+chr(10) else 'LF'}")
    head = "## 追加（父侧集成预检回执轮，2026-09-12）"
    if head in t:
        print("[同 ] 本轮追加段已存在（幂等），跳过")
        return 0
    body = BLOCK.replace("\n", eol) if eol != "\n" else BLOCK
    nt = t + body
    nb = nt.encode("utf-8")
    # append-only 断言：新内容必须以原字节为前缀
    if not nb.startswith(raw):
        print("[FAIL] append-only 断言失败：新内容未以原字节为前缀")
        return 2
    print(f"[OK ] append-only 断言：新内容以原 {len(raw):,} B 为前缀")
    if nb.count(13) != raw.count(13) + body.count("\r"):
        print("[FAIL] 行尾一致性断言失败")
        return 2
    print(f"[OK ] 行尾一致：CR {raw.count(13)} → {nb.count(13)}")
    open(PG, "wb").write(nb)
    print(f">>> 已追加写盘  {len(nb):,} B（+{len(nb)-len(raw):,} B）  行数={len(nt.splitlines())}  sha256={hashlib.sha256(nb).hexdigest().upper()}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
