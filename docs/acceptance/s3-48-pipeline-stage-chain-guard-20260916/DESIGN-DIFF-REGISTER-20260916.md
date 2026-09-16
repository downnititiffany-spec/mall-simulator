# S3-48 设计差异登记：八阶段编排链「整链不变量」守卫

- **日期**：2026-09-16
- **轮次**：S3-48（阶段6 反熵）
- **分支／worktree**：`feature/v3-development` / `D:\Develop_code\GraduationProject-wt\v3-dev`
- **起点 HEAD**：`308ce5c`（S3-47 文档提交；S3-47 代码为 `a3a2842`）
- **代码提交**：`320f7bc`（测试 +120/−0；门禁基线脚本 +33/−1）
- **来源**：`docs/PROJECT_STATUS.md` backlog 行 L520（**S3-32 实测新登记**的 `INIT_SCHEMA` 守卫残余面①②③）
- **类别判定**：**A 类（IMPLEMENTATION/ADDITIVE）** —— 纯测试新增 3 条用例，零生产 Java 改动、零 DDL／迁移、
  零连库、零外网、零契约变更、零 `contract-specs/**` 触碰
- **交付面**：`analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java`
  （+120/−0）、`scripts/run-tests.ps1`（+33/−1，仅基线数字＋注释块）

---

## §1 开工前实测（改前取证，真跑／真读）

| 编号 | 实测事实 | 取证方式 |
| --- | --- | --- |
| F1 | 标尺常量 `PipelineService.STAGE_ORDER`（`PipelineService.java:227-229`）＝ `WAIT_LANDING → INIT_SCHEMA → LOAD_ODS → BUILD_DWD → BUILD_DWS → BUILD_ADS → QUALITY_CHECK → PUBLISH_METRIC`，**8** 个阶段 | 读生产源码 |
| F2 | 阶段记录统一由 `stage()`（`PipelineService.java:952-957`）插入；`WAIT_LANDING` 走本地 READY 门（`:460`，**不**经 executor），`QUALITY_CHECK`（`:585`）与 `PUBLISH_METRIC`（`:619`）经 `executor.executeStage` ⇒ executor 提交面 = 常量去掉 `WAIT_LANDING`（**7** 个） | 读生产源码 |
| F3 | 改前测试树对「阶段次序」的全部断言只有两条单边比较：`waitLandingSuccessThenLoadOdsInStageOrder:317`（`WAIT_LANDING < LOAD_ODS`）与 S3-32 的 `initSchemaIsSubmittedBeforeLoadOds:369-372`（`INIT_SCHEMA < LOAD_ODS`）⇒ 其余 **6** 条边无任何断言 | 读测试源码 + grep |
| F4 | 失败路径只有 **3** 处零散断言：`stageFailureMarksRunFailed:334-341`（`INIT_SCHEMA` SUCCESS、恰好 1 次 `executeStage(INIT_SCHEMA)`、`BUILD_DWD`／`PUBLISH_METRIC` 无记录），无「整链前缀」不变量 | 读测试源码 |
| F5 | 续跑跳过规则**两处实现**：`runSparkStage:714-716`（`completedStages.contains(stageCode) ⇒ return null`）与 `stage():948-951`（同样的门 + 日志）⇒ 只改一处**不改变行为**（探针 P2 实测 0 红，见 §5.2） | 读生产源码 + 探针 |
| F6 | 自举证据门（`PipelineService.java:491`）＝ `initOutcome != null && !completedStages.contains("INIT_SCHEMA")`；`contracted` 键在 `:493` 写入 | 读生产源码 |
| F7 | 既有 `retryReexecutesOnlyFailedStage:595-625` 只断言「`WAIT_LANDING` 插入 1 次／`LOAD_ODS` 插入 2 次／输入批次不漂移」，**不涉及 `INIT_SCHEMA`** ⇒ 「续跑时自举证据怎么办」此前无断言、未测 | 读测试源码 |
| F8 | 改前 `PipelineServiceTest.java` = **1105** 行、**26** 条 `@Test`；测试类提供 `stageList()`／`stageOf()`／`stageStatus()`／`failedExecution(stage, job)` 既有工具（复用，不新造） | `ReadAllLines` 计数 + grep |
| F9 | 改前计数基线：analytics-server **987**（明细 `93+353+169+97+118+157`，warehouse-pipeline＝**169**）、mall-simulator **13**、synthetic-data-generator **110**、`default 三棵树` **1110** | `scripts/run-tests.ps1` 基线与 S3-47 收口轮记录 |
| F10 | 唯一已登记环境性红：`IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`（module `platform-app`，`F=1`，`expected: 43 but was: 0`；根因＝`landing/` 被 `.gitignore` 排除） | S3-47 收口轮 + 本轮两轮日志 |

---

## §2 11 条 HARD DECISION GATE 逐条判定

| 门 | 判定 | 依据 |
| --- | --- | --- |
| ① DROP TABLE/COLUMN | **否** | 本轮零 DDL |
| ② 改已有字段类型／既有业务语义 | **否** | 零生产代码改动；只新增断言（被守行为本就在生产实现中，本轮不改它） |
| ③ 改已发布 Flyway migration | **否** | 未触碰 `V*.sql` |
| ④ 写／迁移正式 3306 数据 | **否** | 零连库（`@SpringBootTest` 未引入，全部 Mockito ＋ 内存替身 store） |
| ⑤ 切 ACTIVE | **否** | 发布端口为 mock，不产生快照、不切指针 |
| ⑥ 改 `contract-specs/**` 已有契约语义 | **否** | 未触碰 `contract-specs/**`，亦未改 `docs/contracts/**` |
| ⑦ 改 V3.0 总体架构 | **否** | 只加断言 |
| ⑧ 改正式项目范围 | **否** | 未扩张范围：只覆盖既有 backlog 行已登记的残余面①②③ |
| ⑨ 删除已发布功能 | **否** | 未删任何代码／测试／文档行 |
| ⑩ 引入 V3.0 未规划大型基础组件 | **否** | 无新依赖、无新模块 |
| ⑪ 两种方案造成重大长期架构分叉 | **否** | 无方案分叉 |

**结论**：11 门全部**未触发** ⇒ 本轮按 A 类自主实施，无需暂停。

---

## §3 口径（本轮冻结，后续同类守卫沿用）

1. **标尺＝生产常量本身**：断言直接引用 `PipelineService.STAGE_ORDER`，**禁止**在测试里另抄一份阶段清单
   （手写镜像＝第二 owner，正是本行要消灭的形态）；派生集合（executor 提交面）也从常量算，不写死。
2. **`WAIT_LANDING` 的定位**：它是本地 READY 门，不经 executor ⇒ 期望的 executor 提交面 = 常量去掉
   `WAIT_LANDING`；该事实由 F2 实测支撑。
3. **失败链判据是「恰好前缀」**：落库阶段必须 `containsExactlyElementsOf(STAGE_ORDER.subList(0, idx+1))`，
   **不是**「包含若干」也不是仅「后缀为空」的单侧断言（单侧断言对「跳过中间阶段」不敏感）。
4. **续跑判据三条**：①不重新自举（同阶段记录数仍为 1、行 id 不变）②首跑证据字符串**原样相等**
   （既不丢、也不被空证据覆盖）③续跑提交面不含 `INIT_SCHEMA`。
5. **防空转**：每条断言带 `.as(... + 实测值)` 诊断；T2 另有标尺自检（若常量不含被注入失败的阶段或它位于首位
   ⇒ 直接红，而不是静默通过）。
6. **测试面边界**：只测「服务层编排 ＋ 内存替身 store」；真库查询语义、真 Spark 执行**不在本轮断言面内**。
7. **探针纪律**：每个探针锚点必须**唯一命中**（命中数≠1 ⇒ 明写「探针未执行，不得据此声称任何结论」，
   见 P4 首轮）；探针后逐次字节还原并核对 sha256。
8. **诚实性**：本守卫是 **characterization guard**（被守性质在写断言之前已成立）⇒ **无经典 RED**，
   非恒真性只能由变异探针证明，不得表述为「先失败后通过」。

---

## §4 实现面（改了什么）

| 文件 | 改动 | 说明 |
| --- | --- | --- |
| `analytics-server/warehouse-pipeline/src/test/java/com/graduation/analytics/pipeline/PipelineServiceTest.java` | **+120/−0**（1105→1225 行；26→29 条 `@Test`） | 新增 3 条：`stageChainMatchesDeclaredStageOrderForTheWholeChain`、`failedStageStopsEveryLaterStageOnTheWholeChain`、`resumeDoesNotRebootstrapAndKeepsBootstrapEvidence`；复用既有 `stageList()`／`stageOf()`／`stageStatus()`／`failedExecution()`，未新造工具、未新开 walk-up |
| `scripts/run-tests.ps1` | **+33/−1**（965→998 行） | 只改 `$BaselineDefault['analytics-server']` 987→990 并在 `$BaselineIsolated` 前插入 S3-48 注释块；AST 解析 0 错误、无裸 LF、命令语义未动 |

**零生产 Java 改动**：`PipelineService.java` 探针前后 sha256 同为 `2B6A6AAF4F71575C9E47109A17797B3B6135BCAE9532C628681D6DFAB6AE946C`。

---

## §5 证据

### 5.1 诚实表述（先写清楚能证什么、不能证什么）

- 本守卫**没有经典 RED**：三条被守性质在写断言之前都已在生产实现里成立 ⇒ 本轮不存在「先红后绿」。
- 非恒真性由**变异探针**证明（§5.2）。**量数轮因计数漂移记 FAIL，只作量数依据、不作通过证据**。
- 收口轮 `[FAIL exit=7]` 的**唯一红**是已登记环境性用例（§5.3），**不得**表述为「门禁已通过」。

### 5.2 变异探针（真跑；逐次字节还原 ＋ sha256 核对）

| 探针 | 变异内容（生产代码 `PipelineService.java`） | 锚点命中 | `mvn` 退出 | 失败用例（实测逐字） | 期望 | 还原 |
| --- | --- | --- | --- | --- | --- | --- |
| P1 | `executor, snapshot, "LOAD_ODS"` → `"BUILD_DWS"`（阶段码改错 ⇒ 实际落库次序 ≠ 声明常量） | 1 | 1 | `Tests run: 29, Failures: 8`：`waitLandingSuccessThenLoadOdsInStageOrder`、**`stageChainMatchesDeclaredStageOrderForTheWholeChain`**、`failedStageStopsEveryLaterStageOnTheWholeChain`、`initSchemaIsSubmittedBeforeLoadOds`、`resumeDoesNotRebootstrapAndKeepsBootstrapEvidence`、`stageFailureMarksRunFailed`、`sparkStageFailureFailsFastWithoutRunningDependentStages`、`retryReexecutesOnlyFailedStage` | 守卫①红 | `还原一致=True` |
| P2 | 只改 `runSparkStage:714` 的跳过门（豁免 `INIT_SCHEMA`） | 1 | 0 | `Tests run: 29, Failures: 0`（**0 红**） | T3 红 | `还原一致=True` |
| P2b | **两处**跳过门同时豁免 `INIT_SCHEMA`（`:714` 与 `:948`） | 1 ＋ 1 | 1 | `Tests run: 29, Failures: 1`：**`resumeDoesNotRebootstrapAndKeepsBootstrapEvidence`** | T3 红 | `还原一致=True` |
| P3 | `if (ex.failed())` → `if (ex.failed() && !"BUILD_DWS".equals(stageCode))`（`BUILD_DWS` 失败不再阻断） | 1 | 1 | `Tests run: 29, Failures: 1`：**`failedStageStopsEveryLaterStageOnTheWholeChain`** | T2 红 | `还原一致=True` |
| P4 | 首轮锚点 `evidence.put("contracted", ` | **5** | — | **探针未执行**（锚点不唯一 ⇒ 按纪律不据此声称任何结论） | — | 未变异 |
| P4b | 唯一锚点整行 `evidence.put("contracted", "sci: …");` → 改名 `bootstrap` | 1 | 1 | `Tests run: 29, Failures: 2`：`initSchemaEvidenceCarriesSelfBootstrapContract`、**`resumeDoesNotRebootstrapAndKeepsBootstrapEvidence`** | T3 前置断言红 | `还原一致=True` |

**逐条对应的非恒真性**：守卫①←P1；守卫②←P3；守卫③←P2b＋P4b。**P2 的 0 红是一个真发现**：
「已成功则跳过」规则在 `runSparkStage:714` 与 `stage():948` **各实现一次**（同一规则两个 owner），
只改一处行为不变 ⇒ 已登记为待裁决（收敛需改生产代码，本轮不擅动）。

### 5.3 门禁（`default` 档两轮）

- **量数轮 `s348-1`**（基线更新前）：
  `analytics-server exit=1 Tests run: 990 (F=1 E=0 S=1)` 明细 `93+353+172+97+118+157` ⇒ `DRIFT(基线 987)`，
  **+3 全部落在 warehouse-pipeline**（169→172，其余模块一字未变）；`mall-simulator 13 MATCH`；
  `synthetic-data-generator 110 MATCH`；`default 三棵树 = 1113（基线 1110）`；`[FAIL exit=7]`。
- **收口轮 `s348-final`**（基线 987→990 之后）：
  `990 MATCH`／`13 MATCH`／`110 MATCH`／`default 三棵树 = 1113（基线 1113）`；`[FAIL exit=7]`，
  唯一红＝已登记环境性用例：`[ERROR] com.graduation.analytics.ingestion.IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched -- Time elapsed: 0.012 s <<< FAILURE!`
  （console 日志 L1218）。
- 日志目录：`.verify/s348-1`、`.verify/s348-final`（控制台：`.verify/s348-1-console.log`、`.verify/s348-final-console.log`）。

---

## §6 契约变更

**无。** 未触碰 `contract-specs/**`，亦未改 `docs/contracts/**`（本轮不涉及对外契约语义）。

---

## §7 未测与边界（不得越界表述）

1. **没有**经典 RED（characterization guard）⇒ 不得表述为「先失败后通过」。
2. 探针 P2 的 0 红说明跳过规则**两处实现未收敛**；本轮**只登记不收敛**，两处 owner 仍在。
3. 只钉**编排不变量**（阶段被提交／落库的次序与集合），**不证明 Spark 侧作业真实执行次序**：
   `spark` 档本轮**未重跑**（新用例无 `@Tag("it")`，不在 isolated 选择面内）。
4. 真库 `completedStages` 查询语义**未测**：内存替身 store 用 `status == SUCCESS` 过滤模拟，真库 SQL 语义未验。
5. 续跑只测**重试一次**的路径；多次续跑、平台重启后恢复、`retryFrom` 等其它续跑入口**未覆盖**。
6. 失败注入点只有 `BUILD_DWS`（作业失败）＋既有 `LOAD_ODS`（预检失败）；其余阶段的失败形态未逐点覆盖。
7. 不证明「八阶段划分本身合理」（阶段划分是设计既定，本轮只守其执行次序）。
8. 探针只在 `warehouse-pipeline` 模块内真跑；其它模块的阶段编排未覆盖。
9. 因此**不得**表述为「流水线不可能乱序」或「续跑语义已被完整证明」，只能说：
   在本轮断言的 8 阶段次序、失败前缀、单次续跑三个面上，行为已被真跑钉住。

---

## §8 顺带台账（不删行、不改判类）

1. **新登记（本轮探针发现）**：「已成功则跳过」规则在 `PipelineService.runSparkStage:714` 与
   `PipelineService.stage():948` **各实现一次** ⇒ 同一规则两个 owner（只改一处行为不变，P2 实测 0 红）。
   判类＝**待裁决**（收敛需改生产代码，非测试面）。
2. **新登记**：S3-48 后继残余面（真库 `completedStages` 语义未测／多次续跑与平台重启未测／`retryFrom`
   等其它续跑入口未覆盖／Spark 真实执行次序未测且 `spark` 档未重跑）。
3. `docs/PROJECT_STATUS.md`：滚动执行位置块（5 段／9 行）改为 S3-48 口径；阶段6 区块新增 7 条 S3-48 记录；
   backlog 行 L520（S3-32 残余面）在原行末单元格内追加**已实施**说明（不删行、不改判类，原措辞与判类保留）。
4. `docs/status-history/开发过程事实与决策记录.md`：追加 F-81（append-only，diff `+12/−0`）。
5. 未改生产代码、未动 `contract-specs/**`、未新增依赖、未触碰正式库与 ACTIVE 指针。

---

## §9 复现命令

```powershell
$wt='D:\Develop_code\GraduationProject-wt\v3-dev'
$env:JAVA_HOME='D:\Develop\JAVA17'

# 定向（单类）内环
& 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o '-Dmaven.repo.local=D:\maven_repository' `
  -f "$wt\analytics-server\pom.xml" -pl warehouse-pipeline -am test `
  '-Dtest=PipelineServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'

# 门禁（default 档，两轮）
& pwsh -NoProfile -File "$wt\scripts\run-tests.ps1" -Suite default -RunId s348-1     -LogDir "$wt\.verify\s348-1"     -Confirm
& pwsh -NoProfile -File "$wt\scripts\run-tests.ps1" -Suite default -RunId s348-final -LogDir "$wt\.verify\s348-final" -Confirm

# 变异探针（真跑，逐次字节还原，含 P4 首轮锚点不唯一的“未执行”记录）
& pwsh -NoProfile -File "$wt\.verify\s348_probes.ps1"
& pwsh -NoProfile -File "$wt\.verify\s348_probes2.ps1"
```
