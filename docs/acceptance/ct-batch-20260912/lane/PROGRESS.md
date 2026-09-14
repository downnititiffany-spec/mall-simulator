# CT 批次施工进度（泳道 worktree `ct-batch`，detached HEAD = f8f1ea3）

> 本文件由施工泳道边做边追加；父级用它判断是否卡住。**只追加，不改写历史行。**

## 2026-09-12 16:32–16:36　开工与实测前置

- 已读规格：`docs/acceptance/ct-batch-20260912/PLAN.md`、同目录 `RULINGS.md`（D-061…D-065 引用面）、
  `docs/acceptance/p2-01-ods-v2-spec-draft-20260912/RULINGS.md` §8。
- **实测发现（与 PLAN 字面不符，已在开工时登记，详见 REPORT.md 偏离栏）**：
  1. `contract-specs/VERSION` 现为 **`contract-specs 2.1.0`**，不是 PLAN 假定的 `1.3.0`
     ⇒ 目标版本按 §3 规则「加法 → minor 递增」定为 **`2.2.0`**（另：`2.1.0` 这一版在 README §10 指纹表已登记、
     但 §1 状态行版本串仍写 `2.0.0` ⇒ 本批一并把 §1 同步为 `2.2.0`）。
  2. `scripts/check-bare-anchors.ps1` 与 `scripts/contract-bare-anchors.allowlist.txt` **已存在且已入库**
     （提交 `5189153`，D-065 裁决 3 交付物，台账 **113 行 / 实测裸锚点 201 处**）⇒ 本批**不重建**，
     只做「登记现存 + 规则文字」并跑一次门禁核对；PLAN §2.5 第 2/3 项的"新建"口径**未执行**（避免覆盖既有实现）。
- 冷备份完成：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`（7 个文件）＋ `sha256-before.txt`；
  逐一 `Get-FileHash` 与在库文件对照 **7/7 相符**（正向对照）。
- 开工首测（PLAN §4）已跑：
  - 第 1 项：生成器 `CanonicalPayloads.orderCreated:82` 与商城 `EventPayloadFactory.orderCreated:90` **双方都写数组**
    ⇒ CT-3 的"规范形态 = 数组"与自产源一致。证据 `pre-measure-1-items-shape.txt`。
  - 第 2 项：`EventContractValidator.missingPayloadField:95` 要求 `items` **存在且非空**；
    `findBadAmount:124-134` 只对 **isArray** 的 items 做金额校验 ⇒ **字符串形态在采集层不触发任何类型校验**（与实测一致：
    6 行字符串 items 全被接受）。证据 `pre-measure-2-validator.txt`。
  - 第 3 项：`landing/events/r9-m1-123006.jsonl` **不在本 worktree**（`landing/` 被 `.gitignore:30` 忽略，
    该文件从未入库；主检出侧存在，18430 B / sha256 `2351BCC35E04CCD2`——与 README §2 登记值逐字符相符）。
    ⇒ PLAN §4 第 3 项**无法在泳道内取得逐行内容**，照实登记为未取证；本批亦**不做 CT-4 裁决**。
  - 第 4 项：全仓扫描（1439 个入库文件，含 `docs/acceptance/**`）已出，证据 `pre-measure-scans.txt`；
    5 组正面对照 **5/5 OK**。
  - 第 5 项：`SOURCE_SYSTEM` 全仓 **57 处命中**（含 javadoc/注释/文档/`.sql`），证据同上文件。

## 2026-09-12 16:36–16:44　E1-a 基线（改动前）

- 第 1 次（模块集 `platform-common,platform-app`，PLAN §3 字面命令）：**BUILD FAILURE**。
  两处**预存在环境性失败** `IngestionManifestSourceSchemaTest`：worktree 缺 `landing/manifests/**`
  （`.gitignore:30` 忽略、未入库；测试自述"历史清单是真实产物，不是夹具"）。
  另 1 处 flaky：`LocalProcessSparkSubmitterProcessTest` 临时目录删除失败（Windows 文件锁）。
  ⇒ 已把主检出侧 `landing/manifests`（40 文件 / 36733 B）**复制**入泳道 `landing/`（仍被 gitignore，不入补丁）。
- 第 2 次（模块集 `platform-common,connection-ingestion`）：以 PLAN §3 命令的**真实模块集**取基线。

## 2026-09-12 16:44–17:20　改动落地 ＋ E1 全绿

- 契约文档、schema、代码三处按 PLAN 顺序改完，每步都是「先断言旧串命中 = 1 → 替换 → 新串 = 1 ∧ 旧串 = 0」，
  日志分别在 `apply-doc-edits.log` / `apply-schema-edits.log` / `apply-code-edits.log`。
- E1-a 改后：`platform-common Tests run: 41` / `connection-ingestion Tests run: 156`，均 `Failures: 0, Errors: 0`，
  `CanonicalEventSchemaParityTest Tests run: 5`，`BUILD SUCCESS`（20.476 s；基线 8.175 s，差值为机器负载，非性能结论）。
- E1-b `VERDICT=PASS`（3 个 schema 过 `check_schema`；6 个 JSON 可解析；坏 schema 正向对照被拒；B-4 结构读数全对）。
- E1-c `VERDICT=PASS`：错误条目 42 → 36，`GONE=6 / NEW=0 / KEPT=36`（逐字符相同），
  消失项 6/6 命中 `properties/items` 的 `type`；`ROWSET_UNCHANGED=True`。
  **如实登记**：被判 6 行同时缺 `status`/`created_at` ⇒ 行集合两侧均 29、未按 PLAN 字面缩减；
  有区分度的证据是错误条目级差集。已重跑一次复核，读数一致（`e1c-diff-rerun.txt`）。
- E1-d `VERDICT=PASS`：变异态 `mvn exit=1` + `BUILD FAILURE` ＋ `Failures: 1`，失败消息精确指向新加的 const 守卫；
  恢复后 sha256 逐字符相同、`mvn exit=0`、`Tests run: 5, Failures: 0`；`TEMP_CHANGE_PERSISTED=False`。
- 升版与指纹：`VERSION 2.1.0 → 2.2.0`；README §1/§3 同步、新增 §14（14.1–14.5）；§14.2 双口径指纹现算。
- 开工首测第 3 项**未取证**（`landing/events/r9-m1-123006.jsonl` 不在泳道内）——已写进 REPORT ④/⑦，不做 CT-4 裁决。

## 2026-09-12 17:20–17:45　收尾、门禁复位与自证

- **锚点门禁一度被我自己的补写打红**：新写的 README §3.5 与 §14.1 直接罗列了 `§2.4 L77` / `§2.4 L84` /
  `§2.4 L84-L90` 的裸写法 ⇒ 门禁报「新增裸锚点 1 处」＋「台账出现次数升高 1 处」。
  处置：`fix-readme-anchors.py` 改成不罗列裸写法的表述（带自检：3 种裸写法命中归零才写盘）。
  **门禁行为正确**，这是施工自踩坑记录，不是守卫缺陷。
- 门禁复位：`结果 = PASS（裸锚点 199 处 / 112 行，全部在台账内；台账 113 行 ≤ 基线 113）`，退出码 0；
  正向对照在位。台账 2 条在册负债原文保留，`-WriteLedger` 未跑（看板登记属总控面）。
- 独立复算对齐：`ledger-vs-scan.py` 用守卫同款规则重写扫描，逐文件读数与门禁相符
  （README 42 / yaml 65 / schema 91 / manifest 1 = 199 处 / 112 行）；与台账（201/113）的差异逐条可解释
  （补前缀 −2；台账 2 条现态归零/降低；台账不含区间形态锚点）。
- EOL 自证：README 曾出现 3 处裸 LF（早前用 PowerShell 文本读写校对所致），
  `normalize-readme-eol.py` 归一为全 CRLF（正文逐字符不变 + 逻辑行数不变双自检通过）；
  8 个改动文件逐一核对 `CR == LF`。
- `anchor-guard.txt` 首写为空（2 B）：守卫走 `Write-Host`（Information 流），`$out = & script 2>&1` 接不到，
  改用 `6>&1` 后得 1,269 B / 18 行。属取证脚本缺陷，不是门禁问题。
- 终态：`git status` **恰好 8 个文件**（含 PLAN §2.5 未列出的 `scripts/check-bare-anchors.ps1` 列宽修，
  已在 README §14.5 **单列**为超范围改动）；禁改面逐条守住；无任何 git 写操作。
- 交付：`ct-batch.patch`（8 文件统一差异）＋ `REPORT.md`（①–⑦）＋ `fingerprints.txt` ＋全部原始读数。
  **未提交、未推送**——等总控接手。

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
  ① **Python universal-newlines 静默改写**：`open(p, encoding="utf-8")` 默认 `newline=None`，把 `\r\n`
     静默翻译成 `\n` —— 读数短 **321** 字符，`split("\r\n")` 只切出 1 段，`count("\r")` 为 0，**且不报错**。
     已逐个审计早前 7 个 README 改写脚本（全部 `open(RM,"rb")`，**未受影响**）；改用
     `open(p,"rb").read().decode("utf-8")` ＋ `splitlines()` ＋ 哨兵「CR == LF ∧ 行数 ≥ 300」。
  ② **`EOL.join(lines) + EOL` 多一个行终止符**：源 7,271 B → split 得 80 元素（末元素 `''`）→ join+EOL = 7,273 B，
     把台账尾巴撑成 `\r\n\r\n`。正确往返是 `EOL.join(lines)`（**不加** EOL）；台账已整份重建
     （`refresh-fingerprints2.py`，7,973 B / CR=LF=78 / 行数 79，二次运行报「幂等：逐字节相同」）。
  ③ **命名组里写字面空格恒不匹配 ＋ 自哈希悖论**：`(?P<ind>\s*LF  \s+)`（组内两个字面空格）在
     CPython 3.14.5 上恒不匹配，换 `\s+` 即命中（复现 `dbg-rx6.py`）；登记"文件自身 sha256"**不收敛**
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

---

## 勘误（2026-09-14 补记；上文不改写，以本节为准）

- 上文 L16 记录的离仓冷备份 `D:\Develop_code\graduation-lane-backup\ct-batch\*`（7 个 `.before` ＋ `sha256-before.txt`）
  为当时实际做法；按 **2026-09-14 用户硬约束**（产物不得在仓库之外创建，只允许仓库内与 `%TEMP%`），
  该目录**冻结、不得新增**，后续备份一律落在仓库内 `backups/`（已 gitignore）或 `%TEMP%`。
- 该仓外目录**未删除、未迁移**（待用户逐项批准），本次仅登记事实。

### 第二条勘误（2026-09-14 15:0x 补记；上文不改写，以本节为准）

- 用户已逐项批准：`D:\Develop_code\graduation-lane-backup` **已迁入仓库**，**新位置＝
  `docs/acceptance/graduation-lane-backup-20260912/`**（182 文件 / 3.04 MB，逐字节校验 `diff=0`），
  **仓外原件已删除**。上文 L16 的离仓路径**不再是证据位置**。
- 本文件上方第一条勘误中「未迁移」的表述**已被本节取代**（写就时属事实，不改写）。
- 过程证据：`docs/acceptance/v25-stray-consolidation-20260914/EXECUTION-20260914.md`。
