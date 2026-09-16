# S3-36 设计差异登记 —— `pipeline_run.input_batch_id` 写入（阶段4 登记行 / E5-c 续）

* **轮次行来源**：`docs/PROJECT_STATUS.md` backlog 行（S3-34 实测新登记）「**`pipeline_run.input_batch_id` 恒 NULL**」。
  该行原文给出两个待办：①「在阶段链何处补写该列」②「是否迁移历史行」。**本轮只做①**（A 类/加性）；
  ②涉及正式库写入 ⇒ 属 HARD DECISION（第④门「写/迁移正式 3306 数据」），**明确不做、不认领**（见 §8 R-1）。
* 前置：S3-35（`/pipeline` 状态属主收敛 ＋ 缺失告知链路修复）已 commit/push，HEAD == origin == `05767af`。
* 本轮分支：`feature/v3-development`（禁止 merge main）；主检出 `D:\Develop_code\GraduationProject` **未改动**。

## §0 证据边界（先说清楚哪些是实测、哪些不是）

**本轮实测**：`PipelineServiceTest` 定向跑（26 条，JDK17/Maven，模块 `warehouse-pipeline`）＋ 三个源码变异探针（H/J/I）
＋ **统一门禁 `default` 档两轮**（量数轮 `s336-count-2` ＋ 收口轮 `s336-final-1`，见 §5；首轮量数 `s336-count-1` 因探针字节码作废）。
**本轮未测**：① 真实 MySQL（3306/3307）落库 —— 单测走 Mockito 内存替身，**没有任何 DB 连接**；
② 真实 Spark 小链（`spark-submit`）；③ `/pipeline-runs` 接口/前端展示；④ 历史行回填（不做）；⑤ `spark`／`isolated` 两档未重跑（本轮零改动）。
⇒ 本轮**只能**主张「写入点在单测层被证明会落库、且三条 fail-closed 分支有牙、default 档计数 MATCH 且无新增红」，
**不能**主张「生产库里批次可查」「运维页能看到批次」。

## §1 本轮行前提与开工前实测（F1–F9，全部真跑：`grep`/读源码，零推测）

| # | 事实 | 证据（file:line） |
| --- | --- | --- |
| F1 | 列由 V7 迁移建好，注释写明语义「来源批次（`ingestion_batch.id`）」 | `platform-app/src/main/resources/db/meta/V7__platform_runtime_profile.sql:64` `ADD COLUMN input_batch_id BIGINT NULL AFTER source_data_version,` |
| F2 | 实体已有同名字段（MyBatis-Plus 自动映射 ⇒ **不需要**改 mapper XML） | `entity/PipelineRun.java:34` `private Long inputBatchId;` |
| F3 | **生产代码零写入**：全仓 `grep` 只命中 DDL、实体、历史 schema 快照与两个验收脚本的 `SELECT` | `git grep -n "inputBatchId\|input_batch_id"`（见 §2 命令） |
| F4 | 输入批次在 `manifestForRun` 处被定下；重试时从**该 run 的 WAIT_LANDING 证据 JSON 正则**读回原批次（钉批次） | `PipelineService.java:1035` `manifestForRun(...)`、`:1040` `Long pinnedBatchId = null;`＋正则解析 |
| F5 | 选择器语义：钉住原批次优先，否则取**同源**最新 READY（`accepted+quarantined>0`）批次 | `LandingManifestSelector.java:58` `select(...)`、`:114` `long batchId = longOf(manifest.get("batchId"));` |
| F6 | WAIT_LANDING 证据与 LOAD_ODS 作业参数**各自**从同一 manifest 取 `batchId` | `PipelineService.java:466` `evidence.put("batchId", manifest.get("batchId"));`、`:501` `odsExtra.put("batchId", String.valueOf(...))` |
| F7 | 一次 `execute()` 内 `run` 被多次 `updateById`（RUNNING/快照/SUCCESS/FAILED），失败路径也用**同一内存对象**落库 | `PipelineService.java:413`/`:421`/`:663`/`:672`/`:680` |
| F8 | 选择器**不要求** manifest 里有 `batchId`：首个合格候选即 `best` ⇒ 缺 `batchId`／`batchId` 非数字的清单**也会被选中** | `LandingManifestSelector.java:115` `if (best.get() == null \|\| batchId > maxBatchId.get())` |
| F9 | 此前 run→批次的**可信链接只有**阶段证据 JSON；列虽在但恒空 | 既有结论文档 `docs/acceptance/t2rerun-golden55-post-ct-20260912/PARENT-VERIFY.md:72`；本项目 S3-34 复测确认 |

**前提结论**：F1+F2+F3 说明这一列是「**schema 已承诺、代码从未兑现**」的溯源字段；F4+F6 说明它**不必另找数据源**
（同一次 manifest 解析里就有）；F8 说明**不能**假定「选中清单必有可用 `batchId`」⇒ 写入侧必须 fail-closed（否则会写 0 号批次，即编造）。

## §2 开工前/复现命令（可复现）

```powershell
Set-Location 'D:\Develop_code\GraduationProject-wt\v3-dev'
# F3：列的生产者盘点（排除 target/ 与文档）
git grep -n "inputBatchId" -- ':!*/target/*' ':!docs/*'
git grep -n "input_batch_id" -- ':!*/target/*' ':!docs/*'
# F4/F5：批次从哪来
Select-String -Path 'analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/LandingManifestSelector.java' -Pattern 'select\(|longOf\(|best.get\(\)'
# 定向内环（非统一门禁；-Dtest 点名只用于开发内环，不作为门禁依据）
$env:JAVA_HOME='D:\Develop\JAVA17'
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -f analytics-server\pom.xml -pl warehouse-pipeline -am test `
    '-Dtest=PipelineServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'
# 统一门禁（量数轮/收口轮，见 §5）
& '.\scripts\run-tests.ps1' -Suite default -RunId 's336-count-1' -LogDir '.verify\s336-default-1' -Confirm
```

## §3 本轮冻结的口径声明

1. **写入点**：`manifestForRun(...)`（`PipelineService.java:428`）之后、`WAIT_LANDING` 之前。
   理由：`manifest != null` 即「本 run 的输入批次已定」，且**重试路径同一处重算仍得到被钉住的同一批次** ⇒
   写入是幂等覆盖，不存在「重试后批次漂移」。
2. **同源**：写入值与阶段证据 `evidence.batchId` 取自**同一次** `manifestForRun` 结果（同一 `manifest` 变量），
   ⇒ 两者不会各说各话（已用测试断言固定，见 §5 探针 I）。
3. **fail-closed（三条不写）**：`manifest == null`（无 READY 批次）／`manifest.get("batchId") == null`（清单缺批次号）／
   `longOf(batchId) <= 0`（非数字或 0）。**留 NULL 是「事实未知」，不是「缺失」**；写 0 等于宣称存在 0 号批次（编造）。
4. **不动读取路径**：`manifestForRun` 仍以**阶段证据 JSON**为钉批次的权威读源（`LandingManifestSelector` 未被修改）。
   本轮**不**把读取改成以列为准 —— 那会改重试钉批次机制，属更大改动，不在本行范围内（见 §8 R-4 记录）。
5. **不回填历史行**：历史 `pipeline_run` 行保持 NULL（第④门，不做）。

## §4 实现面（3 文件：生产 1 ＋ 测试 1 ＋ 门禁基线 1；全部 `analytics-server/warehouse-pipeline/**` 与 `scripts/run-tests.ps1`；代码 +14/−0，测试 +100/−0）

| 文件 | 改动 | 行 |
| --- | --- | --- |
| `src/main/java/.../pipeline/PipelineService.java` | 在 `manifestForRun` 之后加 14 行：口径注释 ＋ 三层 fail-closed 守卫 ＋ `run.setInputBatchId(...)` ＋ 立即 `runMapper.updateById(run)`（`S3-36：批次级溯源落库`） | L429–L441 |
| `src/test/java/.../pipeline/PipelineServiceTest.java` | 新增 4 条用例（写入／缺批次号／非数字／不可归属）＋ 在既有重试用例上追加 1 条「重试不漂移」断言 | L477–L560、L622 |
| `scripts/run-tests.ps1` | `analytics-server` 计数基线 952 → **956**（实测 +4）＋ 追加 S3-36 口径/探针/门禁坑注释块 | L111、L478–L495 |

新增用例与各自钉住的**分支**：

| 用例 | 场景 | 断言的主语 |
| --- | --- | --- |
| `waitLandingPersistsInputBatchIdOfPinnedBatch` | 正常清单 `b1.json`（`batchId=1`） | 有 `updateById` 带 1；内存对象为 1；**列值 == 证据里的 `batchId`** |
| `manifestWithoutUsableBatchIdDoesNotGuessInputBatch` | READY 可归属但**无 `batchId` 键** | 全部 `updateById` 的 `inputBatchId` 均为 `null`（且 run 仍 SUCCESS） |
| `nonNumericBatchIdIsNotWrittenAsZero` | `"batchId":"unknown"`（选择器宽松读得 0） | 同上（专门钉 `batchId > 0` 守卫） |
| `unattributableManifestLeavesInputBatchIdNull` | 清单缺 `sourceId` ⇒ `manifest == null` | 同上（钉 `manifest != null` 守卫） |
| `retryReexecutesOnlyFailedStage`（追加断言） | 首轮 LOAD_ODS 失败 → 补齐后重试 | 重试后 `inputBatchId` 仍为 1（**不漂移**） |

## §5 证据矩阵（全部本轮真跑；内环用 `-Dtest` 点名，仅作开发内环）

| 轮次 | 操作 | 结果 |
| --- | --- | --- |
| RED | 先写 2 条用例（写入／不可归属），未改实现 | `Tests run: 24, Failures: 1`，`exit=1`；**唯一红**＝`waitLandingPersistsInputBatchIdOfPinnedBatch:491`（列从未被写） |
| GREEN-1 | 实现写入（含 `batchId > 0` 守卫） | `Tests run: 24, Failures: 0`，`exit=0` |
| 补测 | 加「无 `batchId` 键」用例 ＋ 重试不漂移断言 | `Tests run: 25, Failures: 0`，`exit=0` |
| 探针 H（**首轮，作废**） | 用整行 `Replace` 插「无条件写 99」 | 4 红，但**该探针污染**：守卫行 `if (manifest != null && manifest.get("batchId") != null) {` 在 Java 源里**出现 2 次**（写入点 ＋ `:501` 附近 LOAD_ODS 站点），`Replace` 两处都改，后一处把值覆盖回 99 ⇒ 机制归因不成立，**作废重做** |
| 探针 H（干净版） | 以唯一注释行作锚点，**只**在写入点前无条件写 99 | `Tests run: 26, Failures: 3`，`exit=1`；3 红＝**三条 fail-closed 用例**（缺批次号／非数字／不可归属），正例与重试用例不受影响 ⇒ 负向守卫确有牙 |
| 探针 J（**首轮，暴露缺口**） | 去掉 `if (inputBatchId > 0)` 守卫 | **`Failures: 0`（0 红）** ⇒ 证明当时**没有任何测试**覆盖该分支（原「无 `batchId` 键」用例其实只覆盖外层 null 守卫）；该「只绿不红」的探针结果**如实登记**，并据此补第 4 条用例 |
| GREEN-2 | 加 `nonNumericBatchIdIsNotWrittenAsZero` 用例 | `Tests run: 26, Failures: 0`，`exit=0` |
| 探针 J（干净版） | 再去掉 `if (inputBatchId > 0)` | `Tests run: 26, Failures: 1`，`exit=1`；**唯一红**＝`nonNumericBatchIdIsNotWrittenAsZero` ⇒ 新用例补上了缺口 |
| 探针 I | 把证据写成固定 `42`（列仍写 1）。注：与首轮 H/J 同批执行，当时类内 **25** 条测试 ⇒ 读数 `Tests run: 25, Failures: 2`；其替换串（`evidence.put("batchId", ...)`）在源内唯一 ⇒ 未受污染 | `exit=1`；红＝本轮「同源」断言（`waitLanding...:497`）＋既有的他源用例（其断言也读阶段证据）⇒ 同源断言有牙 |
| 探针后复原 | `Get-FileHash -Algorithm SHA256` 与备份比对 | `2B6A6AAF4F71575C9E47109A17797B3B6135BCAE9532C628681D6DFAB6AE946C` **逐一相同（True）** |
| 量数轮①（**作废**，`s336-count-1`） | 探针后直接跑门禁（复原用 `Copy-Item`） | **`warehouse-pipeline FAILURE`**：`nonNumericBatchIdIsNotWrittenAsZero:545` 红且 `inputBatchId=0` ⇒ **不是实现缺陷**：`Copy-Item` 把源文件 mtime 带回旧值，maven 输出 `Nothing to compile - all classes are up to date` ⇒ 门禁实际跑的是**探针 J 的字节码**；源文件 hash 与备份相同（已验证）⇒ **该轮整体作废** |
| 量数轮②（有效，`s336-count-2`） | 先触碰两个源文件时间戳迫使重编译，再跑门禁（基线仍 952，**未加** `-AllowCountDrift`） | `warehouse-pipeline SUCCESS`；analytics `Tests run: 956 (F=1 E=0 S=1)`、明细 `93+350+169+93+94+157`、逐字 `tests=956 DRIFT(基线 952)`（**+4 ＝ 本轮新增 4 条用例，来源唯一且已知**）；mall `13 MATCH`、generator `106 MATCH`；`default 三棵树 合计 = 1075（基线 1071）`；`[FAIL exit=7]` **预期**（唯一红＝已登记环境性红） |
| 基线更新 | `scripts/run-tests.ps1`：`analytics-server` 952 → **956** ＋ S3-36 注释块 | 见 §4 |
| 收口轮（`s336-final-1`） | 基线更新后重跑门禁 | `tests=956 MATCH`、mall `13 MATCH`、generator `106 MATCH`、三棵树 `1075（基线 1075）`、`[FAIL exit=7]`（**计数 MATCH ＋ 唯一红＝该已登记环境性红** `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched:61` `expected: 43 but was: 0`，**不是 exit=0**）；逐模块 `93+350+169+93+94+157`，其中 `warehouse-pipeline 169/F=0`、`platform-app 157/F=1`（仅上述环境性红） |

**记录在案的工具坑（本轮实测，后续必须沿用）**：变异探针若用 `Copy-Item` 复原源文件，必须**同时触碰/重写源文件时间戳**（或 `mvn clean`），
否则 maven 增量编译会判定「无改动」而继续用**探针编译出的 class** ⇒ 门禁结论无效。本轮首轮量数即因此作废并重跑。

**终态指纹（`Get-FileHash -Algorithm SHA256` 前 16 位，提交前实测）**：
`PipelineService.java 2B6A6AAF4F71575C`、`PipelineServiceTest.java 69E3BD8F536CE9D9`。

## §6 类别判定与 11 门逐门核对

判定 **A 类（实现/加性）**：写入一个**既有**列，未新增/删除字段、未改任何契约与架构。逐门：

①DROP 表/列 ✗（列早在 V7 建好）②改已有字段类型或既有业务语义 ✗（`input_batch_id` 由恒 NULL 变为按事实写入，
是**兑现该列既有注释语义**，不改其它字段语义）③改已发布 Flyway migration ✗（**未触碰任何 `V*.sql`**）④写/迁移正式 3306 数据 ✗
（单测为内存替身，零连库；历史行回填明确不做）⑤切 ACTIVE ✗ ⑥改 `contract-specs/**` 已有契约语义 ✗（未触碰）⑦改 V3.0 总体架构 ✗
⑧改正式项目范围 ✗ ⑨删除已发布功能 ✗（纯加性，`+14/−0`／`+100/−0`）⑩引入 V3.0 未规划大型基础组件 ✗ ⑪重大长期架构分叉 ✗
（单一写入点、无第二种批次数源）。

## §7 未测与边界（不得越界表述）

1. **真实库未验**：单测用 Mockito `runMapper` 替身 ⇒ 「`updateById` 被调用且带值」**已证**，
   「MySQL 里这一列真的写进去了」**未证**（无 DB 连接、未跑小链）。
2. **统一门禁**：见 §5 表尾（量数轮/收口轮）——**只跑 `default` 档**；`spark`／`isolated` 档本轮未跑（改动不涉及）。
3. **历史行仍为 NULL**：不做回填（第④门）⇒ 现有库里既有 run 行**仍然查不到批次**，不得称「批次可追溯已补齐」。
4. **接口与前端未展示**：`/pipeline-runs` 返回实体（列会随之下发），但前端 `pipelineRunRows` 未映射 `inputBatchId`
   ⇒ **运维页看不到批次**；本轮未改前端（不越界扩范围）。
5. **`batchId > 0` 的边界语义**：`longOf` 对「字符串数字」兼容（`"007"`⇒7，与选择器同口径）；对 `<0` 同样被守卫拦下（无语义）。
6. **重试不漂移仅在单测层**：`manifestForRun` 的钉批次机制由既有测试覆盖，本轮只**追加**一条断言；
   真实环境「证据 JSON 被人工改动 / 证据缺失」时的行为未测。
7. **E5-c 行仍不关闭**：本行只是其「批次级溯源」一半的**写入侧**；业务源身份（`source_id`/`sourceCode`/per-source namespace）仍 **B 类、未做**。

## §8 遗留与待裁决

* **R-1（B/D 类，未做）**：历史 `pipeline_run` 行回填 `input_batch_id` —— 需写/迁移正式库数据，触发第④门 ⇒ 需总控裁决。
* **R-2（登记，未做）**：前端 `/pipeline` 未展示 `input_batch_id`（列已可下发）⇒ 若要「页面可见」，属另一 A 类小项。
* **R-3（沿用 S3-35 R-3）**：`web/node_modules` 缺失 ⇒ SFC/`useAnalysis` 层不可测（环境类，非本会话可解）。
* **R-4（设计记录，未做）**：是否把「钉批次」的权威读源从阶段证据 JSON 改为本列？**本轮不动**（会改重试机制，
  且证据 JSON 里还有 `acceptedUri`/`checksum` 等其它上下文），仅登记为后续可选收敛方向。
* **R-5（未实施，A 类候选）**：`web/src/composables/useAnalysis.js:33` 注释与实现口径不一致（S3-35 登记行）。

## §9 反熵声明（本轮）

* **是否新增了第二个「批次事实」的属主？** 是**同一次执行的派生投影**，不是第二个属主：
  值取自**同一个** `manifest` 变量（同一次解析），写入点唯一（`PipelineService.java:429-441`），
  未新增解析器/缓存/服务，也未改读取路径（§3.4）。列本身是 V7 schema 的**既定承诺**，此前是空承诺。
* **缺口是否应落在既有属主里？** 是。批次身份由 `LandingManifestSelector`/`manifestForRun` 拥有，
  本轮只在既有编排点把**已有值**落库，未引入新的选择逻辑，也未复制回退链。
* **测试是否覆盖了 fail-closed？** 三条不写分支各有专门用例，并用探针 H/J 证明其有牙（其中 J 首轮 0 红暴露了缺口并已补测）。
* **是否有删除/降级？** 无。`+14/−0`、`+100/−0`，既有行为与既有测试断言全部保留（唯一改动是**追加**断言）。
