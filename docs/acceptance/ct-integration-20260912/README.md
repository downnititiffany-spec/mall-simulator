# CT 批次集成与 E1-a 复核（父侧独立判据）— 2026-09-12

> 本文件由**父侧（集成方）**撰写，独立于 CT 泳道自己的 `REPORT.md`。
> 泳道自述只作线索，不作判据；凡本文件给出的数字，均可在文末「复跑方式」按同一命令重算。

## 0. 本文回答什么、不回答什么

**回答**（三条）：
1. CT 批次交付的补丁**能不能安全落地**到主检出（`remediation/r1-boundary`，基线 `09661a0`）；
2. 落地过程**改了什么字节**（文件集合、增删行数、行尾、关键文件指纹）；
3. 落地前后 **E1-a 统计口径是否退化**（逐模块测试数、失败用例集合、BUILD 结果）。

**不回答**（不得据此声称）：
- 不构成「CT 批次完成」的结论；施工单 `PLAN.md` 的 E1-b/E1-c/E1-d、E2、E3/E4/E5 均**不在**本文件范围内；
- 不构成「通过 PLAN §3 字面判据」的结论 —— 主检出**改动前就已有 1 条既有红**，字面判据（"全绿"）在本仓库不可能成立，见 §2.2；
- 不构成对 `contract-specs` 语义正确性的背书；本文件只核**形状/指纹/裸锚点台账/测试口径**四类可机械判定的量。

## 1. 结论速览

| 项 | 判据 | 结果 | 证据 |
|---|---|---|---|
| 补丁内容与受控面一致 | 8 个受控文件，不碰红线面 | PASS | 门禁 A2/A3 |
| 补丁**形态**可用 | 补丁自身纯 LF ＋ 本检出 `git apply --check` | 原始产物 **FAIL**（见 §3）→ 行尾规范化后 **PASS** | 门禁 A0a/A0b |
| 落地**结果**可控 | 应用后 8 个受控文件 CR 字节 0 ＋ 改动集合恰为这 8 个 | **PASS**（须 `-c core.autocrlf=false`，见 §3.1） | 门禁 A4/A5 |
| 契约面（落地后应满足） | VERSION/指纹/`source_system` 去 `const`/`oneOf` 两分支 | 预检阶段 9 条 FAIL 属**预期**（契约尚未改） | 门禁 B1–B7 |
| E1-a 口径不退化 | 逐模块测试数不下降 ＋ 无新增失败 ＋ 模块集合一致 | 见文末「追加」段 | `e1a-compare.ps1` C1–C7 |
| 对比门禁自身可信 | 先证明它会判红，再信它判绿 | PASS（自测 3/3；人为做坏日志被抓到 4 条） | §4.2 |

## 2. 基线（改动前；主检出 `09661a0`，工作区对该批次干净）

### 2.1 统计口径表（PLAN §3 L75 字面命令）

命令：`mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test '-DforkCount=0'`
环境：`JAVA_HOME=D:\Develop\JAVA17`、`JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'`

`-am`（also-make）会把 `analytics-server` 聚合器下**全部**上游测试模块都拉进反应堆，实测 **7 个模块 / 6 个测试模块**：

| 模块 | Tests run | Failures |
|---|---:|---:|
| platform-common | 41 | 0 |
| connection-ingestion | 156 | 0 |
| warehouse-pipeline | 111 | 0 |
| metric-analysis | 38 | 0 |
| ai-decision | 91 | 0 |
| platform-app | 100 | 0 |
| **合计** | **537** | **1** |

`analytics-server` 本身是聚合 pom（`[1/7]`，无测试）。**BUILD FAILURE**（因下述既有红）。

### 2.2 既有红（与本批次无关；必须在对比中逐条保留）

- 用例：`com.graduation.analytics.ingestion.IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate`（`:168`）；
- **模块归属（实测）**：源文件在 `analytics-server/platform-app/src/test/java/com/graduation/analytics/ingestion/IngestionManifestSourceSchemaTest.java`，基线日志 L799–L801，所在构建段 `[7/7] platform-app` ⇒ **既有红只可能出现在 `platform-app` 段**；
- 断言原文：`Expecting empty but was: ["40.json"]`；
- 成因（已实测）：`landing/manifests/40.json`（mtime `2026-09-12 09:23:15`）是被**回填**进去的，而 `landing/**` 在 `.gitignore` 内 ⇒ 该用例在**任何**按当前工作区磁盘状态运行的检出上都会红，与 CT 改动无关；
- 现场量：`landing/manifests` = 40 个文件 / 36,733 B（泳道 worktree 内同一份拷贝，逐字节同）；
- 判据含义：**"改动前后 BUILD 均 FAILURE"不是退步**；退步的定义是「新增失败」或「逐模块测试数下降」，由 `e1a-compare.ps1` 的 C1/C2/C5/C6 判定。

### 2.3 基线可复现（两次独立测量，均 537 / 1）

| 日志 | 大小 | sha256 | 逐模块 | 失败 | BUILD |
|---|---|---|---|---|---|
| `ct-e1a-baseline-main.log`（首测） | 95,546 B | `5DCD54AD68C5F8960A92251F6FE3A6FE1A8455A2321204D0E5F66399F3821A53` | 41/156/111/38/91/100 | 1（同上） | FAILURE |
| `ct-e1a-baseline-run2.log`（用 `run-e1a.ps1` 复跑，日志自带口径头部） | 93,657 B | `05EEB3B1591C033227DF94BF17658909854DC7E7580C2A0F668903247493E439` | 同上 | 同上 | FAILURE |

两者经 `e1a-compare.ps1` 对比 = **8/8 PASS**（`537 → 537`、模块集合逐名相同、失败集合相同）⇒ **改动后的对比取 run2 为准**（与改动后那次同脚本、同环境记录方式，口径对称；首测作为旁证）。

### 2.4 口径勘误（以此为准）

本会话早前我（父侧）**口头**向泳道报过基线为「141 tests（41+100）」。该数字**错误**，成因是把 `-pl platform-common,platform-app` 误当作"只跑这两个模块"。
实测正确值：**537 tests / 1 failure / 6 个测试模块**（§2.1 表）。已向泳道发出更正消息。

泳道自己的 E1-a（`-pl platform-common,connection-ingestion`，197 = 41+156）与本基线**不矛盾**：197 是 537 的**精确子集**（同两个模块、同命令前缀），两侧可互相印证而不构成"两个不同基线"。

**但必须登记一处口径洞（防止把泳道的绿读成"仓库全绿"）**：泳道因 `platform-app` 存在预存在 Windows flaky（`LocalProcessSparkSubmitterProcessTest` 删除临时目录失败，其 REPORT ⑦-2 自陈两次触发）而**改跑了不含 `platform-app` 的模块集**，而那条既有红恰好**只在 `platform-app` 里** ⇒ 泳道的「197 tests / 0 failures / BUILD SUCCESS」在**其自身口径内为真**，但**覆盖不到**这条红，**不得**据此声称"仓库测试全绿"或"改动后无红"。
父侧本文件用的模块集是 PLAN 字面命令（含 `platform-app`），故两侧的关系是：**父侧是超集（看得见红），泳道是子集（看不见红）**。
同时登记为**风险提示**：父侧改动后那次 E1-a 也必须跑 `platform-app`；若出现 `Failed to delete temp directory …junit…` 之类临时目录删除失败，按**预存在环境 flaky**处置（重跑取证 ＋ 保留两次读数），不得当成 CT 改动引入的退步。

## 3. 补丁形态缺陷（CT 集成陷阱 #31）与处置

实测（只读）：

| 量 | 值 |
|---|---|
| 泳道产物 `ct-batch.patch` | 31,162 B，sha256 `B849C6092D7BD2306FFE5506FE2C5EAF078569AD16D4D975E6399F87BB229947`，mtime `16:54:54` |
| 该文件行尾 | **CRLF 260 行 / 纯 LF 0 行** |
| 主检出行尾 | LF（`contract-specs/VERSION` 21 B CR=0；`contract-specs/README.md` CR=0/LF=267；`scripts/check-bare-anchors.ps1` CR=0/LF=221）；`core.autocrlf=true`，**无** `.gitattributes` |
| `git apply --check`（原始补丁） | **8/8 文件 `patch does not apply`，16 条 error，exit 1** |
| 仅做行尾规范化后的补丁 | 30,902 B，sha256 `B4968FD34774B906C4434159274C9BCFFE40FF76769DF6DF16DB63E3048AD77C` |
| `git apply --check`（规范化后） | **8/8 `Checking patch` 通过，0 error，exit 0** |

**结论**：改动**内容**是对的，缺陷只在**补丁文件自身行尾**。典型成因：用 PowerShell 重定向 /`Set-Content` 把 `git diff` 输出落盘，PowerShell 默认按 CRLF 终止每行。
**误诊风险（登记）**：git 报错时把 CR 渲染为行尾 `?`，并把 "while searching for" 指向 hunk 上下文，**看起来像"内容对不上"**；实测只要统计"补丁文件内是否存在纯 LF 行"即可一眼分开。
**处置**：① 已把该缺陷退回泳道（要求以不转 CRLF 的写法重出补丁，并自证行尾）；② 父侧集成门禁新增 **A0a/A0b** 硬闸 —— 补丁自身非纯 LF、或在本检出 `git apply --check` 不为 0，即判不合格、不得集成。
**泳道复核后的终稿**：`ct-batch.patch` = 35,861 B，sha256 `D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495`，**CR 字节 0 / 纯 LF 272 行**，主检出正向预检 **0 error / exit 0** ⇒ 形态缺陷已修（凭据见 §4.5）。

### 3.1 行尾的第二面：**补丁干净 ≠ 落地干净**（新陷阱 #33，实测）

把上述规范化补丁在**干净 LF 预览检出**上跑**普通** `git apply`，落地后 8 个文件**全部变成纯 CRLF**（逐文件实测 `CR == LF`：README 328/328、schema 876/876、guard 225/225、`VERSION` 1/1…），而**补丁自身 CR 字节 = 0**。
成因：本仓库 `core.autocrlf=true`，`git apply` 会按该配置把**落盘结果**过一遍检出过滤器；补丁是"索引空间"内容（LF），检出空间因此被写成 CRLF。
后果（它必须被拦住的原因）：工作区 `contract-specs/VERSION` 会变成 **22 B / raw sha `5D349AFB…`**，而 §14 登记并作为口径的是 **21 B / LF sha `EB175583…`** ⇒ 指纹校验当场失真，任何"按 LF 形态读文件"的判据都会误判。
**处置（已在预览证明）**——应用时必须显式压掉该配置：

```powershell
git -c core.autocrlf=false apply '<补丁路径>'   # 实测：8 文件 CR 字节全 0；PostApply 门禁 26/26 全绿，exit 0
```

旁证（同一成因的另一面）：预览里一次 `git reset --hard` 会把**整棵检出**重新落成 CRLF（`contract-specs/VERSION` 22 B、README 328 CR）——`-c core.autocrlf=false` 只作用于它所在的那**一次**命令，**不写入配置**。
⇒ 判行尾必须**直接数字节**：`git status` 看不见这个差异（autocrlf 会掩盖，仓库 blob 始终是 LF）。故 **A0a（补丁自身）与 A4（落地结果）是两条独立的行尾闸，缺一不可**。

**口径勘误（泳道 REPORT ⑦-5 的「本仓工作区一律 CRLF」）**：该陈述对**checkout 出来的 worktree 成立**（泳道 worktree、预览 worktree 都是 CRLF，因为 `core.autocrlf=true`），但**不成立于主检出**——主检出 8 个受控文件的 CR 字节**全为 0（LF）**，`contract-specs/VERSION` 是 21 B 而非 22 B（实测见 §3 表格）。落地结果必须与主检出原本的 LF 形态一致，故本批次的应用命令一律带 `-c core.autocrlf=false`（§3.1）。

## 4. 门禁自证（先证红，再信绿）

### 4.1 集成门禁 `tools/ct-integration-verify.ps1`（父侧独立脚本）

- 对**原始 CRLF 补丁**跑 PreApply：断言 24 条（当时尚无 A4/A5），**PASS 13 / FAIL 11，exit 1**，其中 `A0a`/`A0b` 如期判红（`实际: exit 1 / error 16 条`）⇒ 新加的形态闸确实是"会响的"；
- 对**规范化补丁**跑 PreApply：断言 26 条，**PASS 17 / FAIL 9，exit 1** —— A 段 7 条（A0a/A0b/A1/A2/A3/**A4**/**A5**）**全 PASS**，9 条 FAIL 全在 B 段契约面，属**落地前应有的红**（契约尚未改）；
- `D1` 条数自检两侧均为 `26 = 26`（防漏跑/多跑，陷阱 #27）。逐段点名：A0a/A0b=2、A1–A3=3、A4=1、A5=1、B1–B6=14、B7=4、D1=1 ⇒ 26（传计数时 27）。
- 日志：`.verify\ct-integration-verify-preapply-crlf.log`、`.verify\ct-integration-verify-preapply-lf.log`。

**A0b 按阶段反向判定**（本项由一次预览试跑纠正，登记为门禁设计缺陷）：
初版 A0b 在任何阶段都跑正向 `git apply --check`，结果在**落地后必然失败**（补丁已在树上）——是在**预览 worktree**（`ct-preview`，`git -c core.autocrlf=false worktree add` 强制 LF 落盘）里试跑时暴露的。改为：PreApply 查正向（补丁可落地），PostApply 查**反向** `git apply --check -R`（通过 = 工作区确实是补丁后的字节，既没漏落也没多落，比正向更强）。
**A4/A5 的由来**：A4 = 8 个受控文件落地后 CR 字节必须为 0（§3.1 就是它抓出来的）；A5 = 改动集合必须**恰好**是这 8 个受控文件（落地前受控面 0 改动、落地后恰 8 个 M、0 删除）⇒ 防"多改/漏改/顺手删"。**A5 只在提交前成立**：一旦提交，工作区改动集合为空，A5 会按设计判红（故顺序固定为 落地 → PostApply → E1-a → 提交）。
预览终验（LF 预览 ＋ `-c core.autocrlf=false apply`）：**PostApply 26/26 PASS，exit 0**，其中 B7 守卫自报「裸锚点 **199 处 / 112 行**，全部在台账内；台账 113 行 ≤ 基线 113」，与 §6 预期一致。

### 4.2 对比门禁 `tools/e1a-compare.ps1`（父侧独立脚本）

三态实测：

| 态 | 应然 | 实际 | 退出码 |
|---|---|---|---|
| 反向自测（内部把"改动后"日志人为做坏） | S1 判绿、S2 判红 | S1 PASS、S2 FAIL；被抓到 C1(`537 → 499`)、C2(新增 `SyntheticNewFailureTest.mustBeCaught`)、C4(`platform-common` 模块消失)、C5(`platform-app 100→3`) | 0 |
| 同源对比（改动前 vs 改动前） | 全绿 | 8/8 PASS（含逐模块 6 行表与失败集合前后一致） | 0 |
| 地基错误（`-AfterLog` 指向不存在的文件） | 报基础设施错误 | `[基础设施错误] 日志不存在…` ＋ 位置/调用栈 | 9 |

⇒ 该脚本**已知会判红**，其判绿才有意义。判据 7 条：C1 总数不降 / C2 无新增失败 / C3 两侧都解析到模块汇总行（地基） / C4 模块集合逐名一致 / C5 逐模块测试数不降 / C6 既有红逐条保留（消失≠修好，须解释） / C7 BUILD 不得 SUCCESS→FAILURE。

### 4.3 本轮新增的 PowerShell 陷阱 #30（已规避，勿改回）

`@($list)` 数组子表达式作用在**元素为 `[pscustomobject]` 的 `System.Collections.Generic.List[object]`** 上，在本机 pwsh 7.6.6 抛
`System.ArgumentException: Argument types do not match`；异常类型看着像"函数调用参数绑定错"，实测与函数调用无关（单行 `@($c).Count` 即可复现，`Checks = $list` 则正常）。
规避：用「只返回对象的嵌套函数 ＋ 父作用域 `+=` 收集」代替 List；对 `List[string]` 用 `.ToArray()`。

### 4.4 本轮新增陷阱 #32：surefire「含失败的模块」汇总行前缀是 `[ERROR]`

`run-e1a.ps1` 初版只按 `^\[INFO\] Tests run: …` 收模块汇总行，结果**整个 platform-app 模块被漏报**（看着像"只有 5 个模块 / 437 tests"）。
实测成因：某模块只要有失败用例，surefire 的模块级汇总行前缀就是 `[ERROR]`（如 `[ERROR] Tests run: 100, Failures: 1, …`），不是 `[INFO]`。
⇒ 任何"逐模块计数"的实现都必须同时收 `[INFO]`/`[ERROR]` 两态；否则**失败越多的模块越容易被整块丢掉**，正是假绿的高发点。`e1a-compare.ps1` 从一开始即按行尾锚定两态（其 C3「两侧都解析到模块汇总行」即此闸）。

## 5. 施工单（`PLAN.md`）偏离登记

| # | PLAN 的写法 | 实测 | 处置 |
|---|---|---|---|
| D1 | 版本 `1.3.0`→`1.4.0` | 真实线为 `1.2.0`→`1.3.0`→`2.0.0`→`2.1.0`，本次 `2.1.0`→`2.2.0` | PLAN 是陈旧假设；以实测线为准，泳道按现状 +1 正确 |
| D2 | 「新建 `scripts/check-bare-anchors.ps1` ＋ 台账」 | 两者**均已存在**（自 `5189153` 起；脚本 225 行、台账 121 行 = 113 数据行） | 记为偏离；本批次只改脚本 1 处 5 行 |
| D3 | README §3.5/§14.1 自称"3 处裸锚点" | 实测 **2 处**（同一行的两处；`items[]` 行按裁决 ④-4 刻意保留） | 已退回泳道改正（含 §14.1 两行指同一处） |
| D4 | 施工单未授权改锚点守卫 | 泳道改了 `PadRight(58)` → `PadRight($nameW + 2)`（列宽自适应） | 泳道已在 §14.5 自陈"超范围"；父侧采纳但登记 |
| D5 | 要求逐句更新 `contract-specs/README.md:43`、`:71`（`RULINGS.md:183/:191`） | 首轮交付**漏改 `:43`**，且 §14.3 自称"保留原文以追加方式标注处置"与补丁实际（整句替换）不符 | 3 项缺陷已退回泳道返工；落地以**返工后**补丁为准 |

## 6. 两个锚点口径：不得互相印证

| 口径 | 工具 | 量 |
|---|---|---|
| 审计口径 | `scripts/audit-anchors.ps1` | **258 处** |
| 在册负债口径 | `scripts/check-bare-anchors.ps1` ＋ 台账 | 台账 **113 行**；扫得裸锚点 **201 处 / 113 行**（落地后应为 **199 处 / 112 行**，本批次 −2） |

两者统计对象与去重规则不同，**数字不同不是缺陷，也不得用其一去"验证"其二**。

## 7. 回滚路径（本批次）

1. 落地采用**单次提交**（父侧提交，泳道不做任何 git 写），`git revert <sha>` 即可整体回退；
2. 落地前冷备份：`D:\Develop_code\graduation-lane-backup\ct-batch\*.before`（泳道产出）；
3. 补丁级回退：`git apply -R docs/acceptance/ct-integration-20260912/ct-batch-lf.patch`（需在同一树状态）；
4. 前置条件：落地前工作区对该批次干净（`git status --porcelain` 只有本证据目录等未跟踪项）。

## 8. 复跑方式（命令清单）

```powershell
# 0) 基线（改动前）—— 必须先于任何改动，且与改动后同命令同环境
pwsh -NoProfile -File docs\acceptance\ct-integration-20260912\tools\run-e1a.ps1 -Out .verify\ct-e1a-baseline.log

# 1) 补丁形态闸（PreApply；A 段 7 条须全绿）
pwsh -NoProfile -File docs\acceptance\ct-batch-20260912\tools\ct-integration-verify.ps1 `
     -Phase PreApply -Repo <仓库> -Patch <LF 补丁>

# 2) 落地（父侧，单次）—— **必须**带 -c core.autocrlf=false（否则结果被写成 CRLF，见 §3.1）
git -c core.autocrlf=false apply --check <LF 补丁>
git -c core.autocrlf=false apply <LF 补丁>

# 3) 落地结果闸（PostApply；须全绿 26/26）。**必须在提交之前跑**（A5 依赖"工作区有且仅有这 8 个改动"）
pwsh -NoProfile -File docs\acceptance\ct-batch-20260912\tools\ct-integration-verify.ps1 `
     -Phase PostApply -Repo <仓库> -Patch <LF 补丁>

# 4) 改动后 E1-a ＋ 口径对比（对比基准取 run2：同脚本同环境记录方式）
pwsh -NoProfile -File docs\acceptance\ct-integration-20260912\tools\run-e1a.ps1 -Out .verify\ct-e1a-after.log
pwsh -NoProfile -File docs\acceptance\ct-integration-20260912\tools\e1a-compare.ps1 `
     -BaselineLog .verify\ct-e1a-baseline-run2.log -AfterLog .verify\ct-e1a-after.log

# 5) 对比门禁自身可信度（任何一次改动脚本后都要重跑）
pwsh -NoProfile -File docs\acceptance\ct-integration-20260912\tools\e1a-compare.ps1 -SelfTest -BaselineLog .verify\ct-e1a-baseline-run2.log

# 6) 预演（不动主检出）：强制 LF 落盘的预览 worktree
git -c core.autocrlf=false worktree add --detach <预览目录> HEAD
git -c core.autocrlf=false -C <预览目录> apply <LF 补丁>
#   ……验完 git worktree remove --force <预览目录>；注意：预览里 git reset --hard 会按仓库配置把文件落成 CRLF
```

## 9. 未取证 / 不得声称

- 不得声称「CT 批次完成」：E1-b/E1-c/E1-d、E2、E3/E4/E5 未在本文件取证；
- 不得声称「E1-a 通过 PLAN 字面判据」：基线即 `537 tests / 1 failure / BUILD FAILURE`（§2.2）；
- 不得声称「契约语义正确」：本文件只核形状与指纹；
- 不得用「泳道 REPORT 自述」替代本文件的实测数字；
- 不得用审计口径 258 处去印证台账口径 113 行/201 处（§6）。

---

## 追加：落地与改动后实测（2026-09-12 17:08–17:12；**以此为准**）

> append-only：上文 §0–§9 **一字未改**；本节把 L221 的「待补」占位替换为实测内容。**本节数字全部为父侧本轮现算**，命令与退出码同列，原始日志留档于 `raw/`。

### 10.1 落地动作与逐闸结果（父侧实测）

| 步 | 动作 | 实测结果 | 原始日志 |
|---|---|---|---|
| 1 | 钉住补丁：`Copy-Item` 泳道补丁 → `.verify\ct-batch-final.patch` | **35,861 B / CR 字节 0 / sha256 `D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495`** | `patch/ct-batch-final.patch` |
| 2 | PreApply 门禁 `tools/ct-integration-verify.ps1 -Phase PreApply` | 26 条断言：**17 PASS / 9 FAIL**；A 段 7/7 全绿，B 段 9 条红＝"契约尚未改"的**预期红**；exit 1 | `raw/ct-integration-verify-preapply-final.log`（3,993 B） |
| 3 | `git -c core.autocrlf=false apply --check` → `apply` | 两侧均 **exit 0**；`git status --porcelain` 恰好 **8 个 `M`**、0 删除/重命名；**HEAD 仍 `09661a0`**（落地不产生提交） | — |
| 4 | PostApply 门禁（同脚本 `-Phase PostApply`） | **26/26 PASS / exit 0**；A4：8 文件 CR 字节全 0；A5：修改集合恰为 8 文件；B7c 守卫自报 `结果 = PASS（裸锚点 199 处 / 112 行，全部在台账内；台账 113 行 ≤ 基线 113）` | `raw/ct-integration-verify-postapply.log`（3,387 B） |
| 5 | 改动后 E1-a `tools/run-e1a.ps1`（PLAN §3 L75 字面命令） | **537 tests / 1 failure / BUILD FAILURE**，耗时 107.5 s；失败集合与基线**逐字相同** | `raw/ct-e1a-after.log`（97,395 B） |
| 6 | 对比门禁 `tools/e1a-compare.ps1`（基线 ＝ §2.3 的 `run2`） | **8/8 PASS / exit 0**：537→537、无新增失败、6 模块逐名相同、逐模块数不降、既有红保留、两侧 BUILD 均 FAILURE | `raw/ct-e1a-compare-after.log`（1,505 B） |
| 7 | **独立实现复核** `tools/ct-contract-independent.py`（`json.loads` 真解析 ＋ `hashlib`，与门禁的正则/字节实现**是两条代码路径**） | **13/13 PASS / exit 0** | `raw/ct-contract-independent.log` |

**口径（必须连着读）**：步骤 6 **不声称**通过 PLAN §3 的字面判据 —— 字面要求 `Failures: 0` ＋ `BUILD SUCCESS`，而基线与改动后**都是** 537 / 1 / BUILD FAILURE。本批次证明的是**不劣化**：总数不减、无新增失败、既有红逐条保留、模块集合一致。

### 10.2 补丁两个版本的关系（**内容等价的证明**，非推断）

已落地的是 **`D103CC6C…`/35,861 B**；泳道随后以 `--full-index` 重导出为 **`DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945`/36,389 B**（泳道终稿）。差值恒为 **+528 B ＝ 8 × 66 B**，恰等于 8 条 `index` 行由 7 位缩写变 40 位（每条 `+33 × 2` 字符）—— 这是"差异都落在 `index` 行"的**必要条件而非充分条件**，故另做三条独立证明：

| # | 证明 | 实测 |
|---|---|---|
| P1 | 终稿补丁能**反向还原**当前工作区 | `git -c core.autocrlf=false apply --check -R .verify\ct-batch-final-fullindex.patch` ⇒ **exit 0** ⇒ 工作区内容 ≡ 终稿补丁的"之后态" |
| P2 | 逐文件 `git hash-object` vs 泳道登记的**新侧 blob** | **8/8 逐字符相同**（`bd6a4faf… / 31024eed… / f42a4e32… / 0095a4a6… / 8cc98171… / 1e4f2d85… / 73e5e913… / 65cf8596…`） |
| P3 | 泳道登记的**旧侧 blob**是否在本仓（即父侧 HEAD ＝ 补丁"之前态"） | **8/8 命中**（`git cat-file --batch-all-objects` 前缀匹配） |

**结论**：落地字节 **＝** 泳道终稿的内容；两版补丁的差异不触及任何被应用的文件字节。为可复核，**两版都已留档**于 `patch/`。附带承认一个取证精度缺陷：`D103CC6C…` 的 `index` 行只有 7 位（`core.abbrev` 所致），单独不足以证明"补丁 ≡ 哪个快照"，泳道自纠为 40 位后由 P1–P3 补齐；该缺陷**不影响落地结果**。

### 10.3 落地后逐文件实测（8/8；CR 字节**全为 0** ⇒ 纯 LF）

| 文件 | 字节 | CR | LF（行） | LF 规范化 sha256（前 16） | blob（前 12） |
|---|---|---|---|---|---|
| `SourceRegistryMigrationScriptTest.java` | 9,159 | 0 | 193 | `BDDE1FDB2E992198` | `bd6a4faf549c` |
| `EventContract.java` | 3,967 | 0 | 80 | `132B63F265067F37` | `31024eed3ec4` |
| `CanonicalEventSchemaParityTest.java` | 8,319 | 0 | 169 | `5893A600B4A87449` | `f42a4e32f32f` |
| `contract-specs/README.md` | 60,632 | 0 | 328 | `E53F09019C151275` | `0095a4a6b241` |
| `contract-specs/VERSION` | 21 | 0 | 1 | `EB1755838292C331` | `8cc98171e1ef` |
| `canonical-event.v1.schema.json` | 33,687 | 0 | 876 | `A70AF90110FBB5D3` | `1e4f2d8565c0` |
| `docs/contracts/event-contract.md` | 7,439 | 0 | 168 | `7F4A6E45FF5E6869` | `73e5e913f390` |
| `scripts/check-bare-anchors.ps1` | 13,586 | 0 | 225 | `4DF85A19C55344C6` | `65cf8596b5e0` |

- `git diff --numstat` 合计 **+99 / −21（8 个文件）**，与泳道 REPORT 登记一致。
- `contract-specs/README.md` 的 **60,632 B** 即泳道登记的 LF 规范化字节数（其 worktree 内 raw 60,960 B 是 CRLF **检出**形态，两者差 328 = CR 数，非内容差异）。
- `git` 会提示 `LF will be replaced by CRLF the next time Git touches it`（`core.autocrlf=true` 的正常提示）：**它不影响提交进索引的 blob**（仍是 LF 形态）。可复核判据：提交后 `git ls-tree HEAD -- <文件>` 的 blob 哈希应等于上表第 7 列。

### 10.4 落盘证据清单（`docs/acceptance/ct-integration-20260912/`，父侧现算）

| 文件 | 字节 | sha256 |
|---|---|---|
| `patch/ct-batch-final.patch` | 35,861 | `D103CC6C23064AD98F96FE91CF1E64075261BAF159116B27A59BE3178EA6E495` |
| `patch/ct-batch-final-fullindex.patch` | 36,389 | `DCC102A4BD765E34CF2A03AC77BECFB53AFF7850A9468CF3039F92C149609945` |
| `raw/ct-e1a-baseline-main.log` | 95,546 | `5DCD54AD68C5F8960A92251F6FE3A6FE1A8455A2321204D0E5F66399F3821A53` |
| `raw/ct-e1a-baseline-run2.log` | 93,657 | `05EEB3B1591C033227DF94BF17658909854DC7E7580C2A0F668903247493E439` |
| `raw/ct-e1a-after.log` | 97,395 | `2A92772F436A6A9FFC05A37EEB29325E96C5E4173F2DA24D37D263127A95165A` |
| `raw/ct-integration-verify-preapply-final.log` | 3,993 | `618618F115AD03EF…`（本目录内以文件为准） |
| `raw/ct-integration-verify-postapply.log` | 3,387 | `229ECC4FE9BAD8D3387CC27E86E3C450874FF6DC72808B1CBF36ED5DFA829686` |
| `raw/ct-integration-verify-postapply-preview.log` | 3,472 | `EB3EFA51FBC35573B00D166EA68B230AC573CCE6BB7FF737A338245F6868C97A` |
| `raw/ct-e1a-compare-after.log` | 1,505 | `460E47012C7D97EA…` |
| `raw/ct-e1a-compare-baseline-vs-run2.log` | 1,505 | `7D9BD4E61C00123B…` |
| `raw/ct-e1a-compare-selftest.log` | 1,505 | `73F57FCA549CDE44…` |
| `raw/ct-contract-independent.log` | 830 | `6456ACC79D4DF24F…` |
| `tools/ct-contract-independent.py` | 3,934 | `7551474A3C13941A…` |

> 门禁脚本 `ct-integration-verify.ps1` **单点归属**于 `docs/acceptance/ct-batch-20260912/tools/`（不在本目录再放一份，避免双份实现漂移）；本轮对其的改动＝补齐 `-LogPath` 真落盘（`Start-Transcript`）＋末尾**日志自检**（回读日志数 `[PASS]`/`[FAIL]` 条数必须等于脚本计数，并打印日志大小与 sha256），改动后三种模式（裸 CRLF 版 PreApply 红 / 纯 LF 版 PreApply 17-9 / 预览 PostApply 26-26）均已复跑复验。

### 10.5 本轮新增陷阱 #34（父侧自查出的测量缺陷）

`Where-Object { $_ -like '??*' }` 会把 ` M <文件>` **也**匹配上（`?` 匹配任意单字符，两个 `?` 就吃掉了 ` M`）⇒ 我自己的"未跟踪目录"统计因此把 8 条 `M` 一并打印，看着像"未跟踪面很大"。判未跟踪必须用 `-like '?? *'` 或正则 `^\\?\\?`。**教训同 #28/#29 家族：统计前先证明选择器选中的是你要的那一类。**

### 10.6 本节仍未取证（不得外推）

- **E1-b / E1-c / E1-d 与 E2 / E3 父侧本轮未复跑**：E1-b/c/d 沿用泳道留档原始日志；E1-c（42→36 / GONE=6 / NEW=0 / KEPT=36）与 E1-d（变异态 `BF141496…`）的对照**属泳道读数，父侧未独立复算**。
- **集群侧未重跑**：E4（1,000 行）与 `ljp` 均未在本批次复跑。本轮改动只碰契约与守卫，**未声称**对集群链无影响 —— 该结论需要一次 E4 复跑，本批次**未做**。
- `platform-app` 那 1 条红**本轮未修**（`analytics-server/platform-app/src/test/java/com/graduation/analytics/ingestion/IngestionManifestSourceSchemaTest.java:166-168`）：它在基线与改动后**同样出现**，故不构成回归，但**仍然是红的**。
- **契约语义正确性未证明**，只证了形态/形状（`source_system` 去 `const`、`items` 加性 `oneOf`、版本升 `2.2.0`）；`SOURCE_SYSTEM` 全仓 **57 处命中未清理**。
- **锚点台账未收敛**（`-WriteLedger` 未跑，2 条在册负债：`schema :: §2.4 L77` 可删减、`§2.4 L84` 台账 2 实测 1）；**CT-4 未裁决**。
- 两个锚点口径（审计 **258 处** vs 台账 **113 行 / 199 处**）**并存不互证**（见 §6）。
- 本节写作时**尚无落地提交**（HEAD 仍 `09661a0`）：提交 sha、推送结果与看板登记行见其后的「落地提交」小节（父侧随后追加）。
