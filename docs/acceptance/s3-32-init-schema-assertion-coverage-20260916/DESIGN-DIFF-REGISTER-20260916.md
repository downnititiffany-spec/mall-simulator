# S3-32 设计差异登记：`INIT_SCHEMA` 阶段断言覆盖缺口（2026-09-16）

- **项名与来源**：backlog 行（原措辞，append-only **不删行**）「`PipelineServiceTest` 从不校验
  `INIT_SCHEMA` 阶段（断言覆盖缺口，INIT_SCHEMA 存在性只有间接证据）」。
- **判类**：**A 类**（既有测试内**新增断言**）。零生产代码改动、零 DDL、零迁移、零前端、零新依赖、
  零连库；`contract-specs/**` 与 `docs/contracts/**` 一字未动。
- **判类口径**：新增断言只钉**编排不变量**（次序、对外可见证据），不引入新 API／不改返回体／不改契约语义。

---

## §0 边界（不得越界表述）

1. 本项**只**给 `PipelineServiceTest` 加断言；**未**改任何生产代码（两处变异探针均字节还原，
   `git diff` 对 `PipelineService.java` 为空）。
2. 本项**不**证明"真 Spark 会建表"：Java 侧执行器在单测里是 Mockito 替身 ⇒ 本项只证**编排次序与阶段证据**，
   真建表能力仍只有 spark-jobs 侧证据（见 §2-F2）与"未跑真 `spark-submit`"的边界（§7）。
3. backlog 行括号里的措辞「**从不**校验」经实测**部分陈旧**（§2-F1）：按实测登记，**不改写**原行措辞。
4. 探针结论必须按**直接/间接**区分（§2-F3）：不得写成"既有断言对该变异完全不可见"。
5. 次序断言读的是**执行器 mock 的实际调用序列**，不是源码文本镜像（避免"实现改了、守卫跟着镜像改"的自证）。
6. 守卫须**非恒真**：先断言两个阶段都被提交，再做 `indexOf` 比较（否则"哪个都没提交"会让比较空转）。
7. 测试内新增断言 = A 类；**未**触碰 11 门中的任何一门（§5 逐门否）。

---

## §1 锚点

| 锚 | 位置 | 内容 |
| --- | --- | --- |
| A1 | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/PipelineService.java:474-481` | INIT_SCHEMA 提交块（`runSparkStage(..., "INIT_SCHEMA", ...)` ＋ `evidence.put("contracted", "sci: CREATE DATABASE/TABLE IF NOT EXISTS（四层库表自举，可重复执行）")` ＋ `updateStageEvidence`） |
| A2 | 同上 `:483-513` | LOAD_ODS 提交块（紧随其后 ⇒ 次序在**代码结构**上成立） |
| A3 | 同上 `:475-476` | 自举调用先于装载调用（同一 `confs`、`completedStages` 传入） |
| A4 | `PipelineServiceTest.java:334-337` | 既有断言：INIT_SCHEMA 状态 `SUCCESS` ＋ 恰好提交 1 次（`eq("INIT_SCHEMA")`）——在**失败路径**用例 `stageFailureMarksRunFailed` 内 |
| A5 | `PipelineServiceTest.java:771` / `:777-779` | 既有断言：INIT_SCHEMA 状态 `SUCCESS`；失败前恰好提交 3 个阶段 |
| A6 | `PipelineServiceTest.java:364` | 既有断言：未过 WAIT_LANDING ⇒ `stageOf("INIT_SCHEMA")` 为 **null**（不得提前自举） |
| A7 | `spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala:156-159` | INIT_SCHEMA 的 **37** 条 DDL（5 建库 + 32 建表）全部只引用当前命名空间 |

---

## §2 改前实测（F1–F6，全部实跑）

### F1　行措辞「从不校验」不成立（部分陈旧）

`PipelineServiceTest` 改前**已有 5 处** INIT_SCHEMA 断言：A4（状态 SUCCESS ＋ 恰好 1 次调用）、
A5（状态 SUCCESS ＋ 失败前恰好 3 个阶段）、A6（WAIT_LANDING 未过 ⇒ 自举不得提交）。
⇒ 成立的表述是「**只校验存在性/次数，不校验提交次序，也不校验自举证据**」，而不是「从不校验」。

### F2　四层库表自举的**实质**已在 spark-jobs 侧覆盖

`WarehouseNamespaceSpec.scala:156-159` 断言 `LocalSchemaInitJob.statements(ns)` **恰好 37 条**
（5 建库 + 32 建表）且全部只引用当前命名空间；`SurrogateKeySpec.scala:280`、`AdsCartRateSpec.scala:67`、
`AdsFavCartCountSpec.scala:71` 会**真执行**这批 DDL。⇒ Java 侧要守的不是"DDL 内容"，而是**编排不变量**。

### F3　次序不变量：改前对**成功路径**零断言（探针 A）

变异＝把 A1 整块（8 行）移到 A2 之后（其余不动），实跑 `s332_probeA1.log`：
`Tests run: 22, Failures: 2, Errors: 0` ⇒

- ①**新增**次序守卫红（`initSchemaIsSubmittedBeforeLoadOds:367`），诊断逐字打印真实提交次序
  `[LOAD_ODS, INIT_SCHEMA, BUILD_DWD, BUILD_DWS, BUILD_ADS, QUALITY_CHECK, PUBLISH_METRIC]`；
- ②**既有**用例 `stageFailureMarksRunFailed:334` 也红 —— 该用例走**失败路径**
  （LOAD_ODS 抛 `RUN_EMPTY_DATA`），对调后自举根本不会被提交，A4 的状态断言因此失败。

**如实登记**：既有断言对"对调"有**间接**敏感（依赖失败路径场景），但对**成功路径**的次序
**零断言** —— 不得写成"既有断言完全不可见"。新增守卫补的正是这条**直接**不变量。

### F4　自举证据：改前**零**断言（探针 B）

变异＝删掉 `evidence.put("contracted", "sci: …")` 一行，实跑 `s332_probeB1.log`：
`Tests run: 22, Failures: 1`，**唯一红 = 新增**证据守卫
`initSchemaEvidenceCarriesSelfBootstrapContract:391`；**既有 20 条全绿**
⇒ 该对外可见事实（阶段证据写明「幂等 `CREATE … IF NOT EXISTS`，可重复执行」）此前**无任何守卫**，
删掉不会被任何既有用例发现。

### F5　定向轮（GREEN）

`s332_green4.log`：`Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`
（改前 20 ⇒ **+2**），四模块（analytics-server / platform-common / connection-ingestion / warehouse-pipeline）
全 `SUCCESS`、`BUILD SUCCESS`、`mvn exit=0`。

**参数/环境错误轮（不计入 RED/GREEN，如实留档）**：

- `s332_green1.log`：`-pl warehouse-pipeline` 单模块跑 ⇒ 本地仓缺 `platform-common:jar:tests`
  （该 test-jar 只在反应堆内产出）⇒ 需 `-am`；
- `s332_green2.log`：`-am` 后 `platform-common` 无 `PipelineServiceTest` 匹配 ⇒ 需
  `-Dsurefire.failIfNoSpecifiedTests=false`；
- `s332_green3.log`：`-Dsurefire.failIfNoSpecifiedTests=false` **未加引号**被 PowerShell 拆成
  `-Dsurefire` ＋ `.failIfNoSpecifiedTests=false` ⇒ Maven 报 `Unknown lifecycle phase`。

### F6　环境教训：两个检出同名不同版本（本轮实测，已立规矩）

主检出 `D:\Develop_code\GraduationProject`（分支 `remediation/r1-boundary`，HEAD `78eacf8`）与
工作树 `D:\Develop_code\GraduationProject-wt\v3-dev`（分支 `feature/v3-development`）**同名文件内容不同**：

| 文件 | 主检出 | 工作树 |
| --- | --- | --- |
| `PipelineService.java` | **1231** 行，`StageOutcome initOutcome` 在 **L447** | **1187** 行，在 **L475** |

本轮一次**未生效的 `cd`** 使某次 pwsh 读到了主检出，导致行号整体偏移 28 行、一度以为"文件被改"。
⇒ **本轮起的规矩**：文件工具一律**绝对路径**；pwsh 一律 `Set-Location` ＋
`git rev-parse --abbrev-ref HEAD` 分支守卫；变异探针一律 `try/finally` ＋ `git checkout --` 还原并核对 `diff` 行数 0。
本轮两处探针还原后 `git diff --numstat` 对 `PipelineService.java` **均为 0**。

---

## §3 口径

1. **"缺口"以变异探针度量**，不以读代码下结论；探针须让**新增守卫**变红才成立；若旧用例同时变红，
   必须区分**直接**（守的就是这条）与**间接**（场景副作用）。
2. **行措辞与实测不符 ⇒ 按实测登记，不改写原行措辞**（append-only）。
3. 守卫钉**不变量**，不钉实现文本镜像。
4. 守卫**不得恒真**：先证两阶段都被提交，再比较 `indexOf`。
5. 计数口径：analytics-server **950 → 952**（`warehouse-pipeline` 163 → **165**，+2）。
6. 唯一红口径不变：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（`expected: 43 but was: 0`，`:61`）。
7. **反熵声明**：本轮**无退役、无删除、无所有者收敛**；`PipelineService` 仍是 INIT_SCHEMA 编排的唯一所有者，
   本轮只给它加了守卫。⇒ 不适用 `delete-first`。

---

## §4 实现面

| 文件 | 改动 | 性质 |
| --- | --- | --- |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java` | 新增 2 个 `@Test`：`initSchemaIsSubmittedBeforeLoadOds`（提交次序：`INIT_SCHEMA` 必须先于 `LOAD_ODS`，且首个提交阶段即自举）、`initSchemaEvidenceCarriesSelfBootstrapContract`（阶段证据含 `contracted` 且写明幂等 `CREATE DATABASE/TABLE IF NOT EXISTS`） | 纯测试新增 |
| `scripts/run-tests.ps1` | `$BaselineDefault` 的 `analytics-server` **950 → 952** ＋ S3-32 溯源注释 | 门禁基线 |
| 生产代码 | **无** | 两处探针均字节还原（`diff` = 0） |

---

## §5 11 门逐门否

| 门 | 判定 |
| --- | --- |
| ① DROP TABLE/COLUMN | **否**（零 DDL） |
| ② 改已有字段类型或既有业务语义 | **否**（只加测试断言；INIT_SCHEMA 的既有语义一字未改） |
| ③ 改已发布 Flyway migration | **否**（零迁移） |
| ④ 写/迁移正式 3306 数据 | **否**（零连库） |
| ⑤ 切 ACTIVE | **否** |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否**（未动） |
| ⑦ 改 V3.0 总体架构 | **否** |
| ⑧ 改正式项目范围 | **否** |
| ⑨ 删除已发布功能 | **否**（无删除） |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否**（零新依赖） |
| ⑪ 两种方案造成重大长期架构分叉 | **否** |

---

## §6 证据

### §6.1 定向轮

| 轮 | RunId/日志 | 结果 |
| --- | --- | --- |
| 定向 GREEN | `s332_green4.log` | `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`；`BUILD SUCCESS`；`mvn exit=0` |

### §6.2 变异探针（真跑，均字节还原）

| 探针 | 变异内容 | 结果 | 结论 |
| --- | --- | --- | --- |
| A | `PipelineService.java`：INIT_SCHEMA 块移到 LOAD_ODS 之后 | `s332_probeA1.log`：`Failures: 2` = 新增次序守卫（直接）＋ `stageFailureMarksRunFailed:334`（间接） | 成功路径次序此前零断言；新守卫有牙齿且打印真实次序 |
| B | `PipelineService.java`：删 `evidence.put("contracted", …)` | `s332_probeB1.log`：`Failures: 1` = **仅**新增证据守卫 | 自举证据此前**无任何**守卫（旧 20 条全绿） |

### §6.3 档级（default）

**只 fresh 重跑 default 档**（本项改动面 = `analytics-server/warehouse-pipeline` 测试树 ＋ 门禁基线常数；
零 `spark-jobs`／`mall-simulator`／`synthetic-data-generator` 改动 ⇒ `spark`/`isolated` 两档未跑）：

| 轮 | RunId（日志目录） | 实测 |
| --- | --- | --- |
| 量数轮（基线仍 950，**未加** `-AllowCountDrift`） | `s332_20260916_def1`（`.verify/s332_def1/`） | analytics-server `exit=1  Tests run: 952 (F=1 E=0 S=1)`、6 模块明细 **`93+350+165+93+94+157`**（warehouse-pipeline 163→**165**）；逐字 `tests=952 DRIFT(基线 950) ⇒ 基线漂移`（**+2**＝本项新增 2 条，来源唯一且已知）；mall `13 MATCH`；generator `106 MATCH`；`default  FAIL （1071 个用例）`；`[FAIL exit=7]` **预期** |
| 收口轮（基线 950 → **952**） | `s332_20260916_def2`（`.verify/s332_def2/`） | analytics-server 模块汇总行 **`93 + 350(S=1) + 165 + 93 + 94 + 157(F=1)` ＝ 952**（`warehouse-pipeline Tests run: 165, Failures: 0` 绿；platform-app `Tests run: 157, Failures: 1`）；逐字 **`default 三棵树 合计 = 1071（基线 1071）`** ⇒ **计数 MATCH**；`default, FAIL （1071 个用例）`、失败项仅 `analytics-server`、`[FAIL exit=7]`（**预期**：唯一红未修） |

**唯一红**（两轮同一条，逐字来自日志）：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`
（`-- in com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest`，`Failures: 1`
⇒ 即已登记的环境性红，`expected: 43 but was: 0`，`:61`），本轮**未修、未复制 manifest、未用开关掩盖**。

---

## §7 未测（不得越界表述）

1. **真 `spark-submit`／真 Hive metastore 未跑**：本项 Java 侧执行器是 Mockito 替身 ⇒
   只证**编排次序与阶段证据**，不证"自举真的建了 37 张表"。
2. **真库零连接**：未触碰 3306/3307/ACTIVE；未跑任何 `*MySqlIT`。
3. **`isolated` 档未跑**（本项未触碰隔离档文件）。
4. **`spark` 档未重跑**（零 Scala 改动）；spark 侧 37 条 DDL 的既有覆盖引用自 S3-31 实测头，未在本轮重跑。
5. 唯一红（已登记环境性红）**未修**，本轮未复制 manifest、未使用 `-AllowCountDrift`。
6. **续跑/重试路径未守**：`completedStages` 已含 `INIT_SCHEMA` 时是否仍写 `contracted` 证据，本轮未立守卫、未测。
7. **只钉一条边**：本轮只守 `INIT_SCHEMA → LOAD_ODS`；`WAIT_LANDING → INIT_SCHEMA`、
   `LOAD_ODS → BUILD_DWD → …` 的整链次序仍无守卫。

---

## §8 结论与后续

- 行「`PipelineServiceTest` 从不校验 `INIT_SCHEMA` 阶段」**按实测 append**：措辞部分陈旧（已有 5 处断言，
  见 F1），残余的两条真缺口（**提交次序**、**自举证据**）本轮已补守卫，并用两处变异探针分别证其**此前无守卫**。
- 行**不标清零式的"不存在"**表述：保留"只校验存在性/次数"这一实测描述，并登记残余开放项（§7-6、§7-7）。
- **相关判类（另案）**：backlog 中「DWD 去重 `ORDER BY ingest_time` 无稳定平局裁决」一行本轮**判 B 类**
  （同 `ingest_time` 时留哪一行＝**既有业务语义**的取舍，属门②邻域）⇒ 只登记、不擅自实现，待总控裁决；
  详见 `docs/status-history/开发过程事实与决策记录.md` F-65。
