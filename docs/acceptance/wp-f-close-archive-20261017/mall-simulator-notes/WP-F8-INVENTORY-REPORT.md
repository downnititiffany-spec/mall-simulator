# WP-F8 (F-88 剩余项盘点) 综合盘点报告

> **盘点日期**：2026-10-17  
> **执行依据**：`~/workspace/projects/mall-simulator-notes/WP-F8.md` 与 `FULL-PROGRAM.md`  
> **盘点性质**：只读盘点（纯静态文档与代码核对，未运行任何外部命令，未连接任何数据库，未修改仓库内任何文件，未触碰 3306/Docker/DeepSeek）  
> **状态边界（总控裁定，表述边界严格受控）**：**F-88 正式状态保持「限定验收」**；禁止任何「F-88 已通过完整验收」的同义表述。本轮仅做剩余项盘点与分类对账，不作终局验收结论。

---

## 1. 执行摘要 (Executive Summary)

本报告系根据全量完成纲领（`FULL-PROGRAM.md`）WP-F8 任务要求，对项目历史关键治理项 **F-88（质量门严重度口径与规则版本化）** 遗留的全部未验项、未取证项、硬理由及待裁决项进行的全景式静态对账。

盘点全面通读并交叉比对了以下一手权威材料：
1. `docs/PROJECT_STATUS.md` 中「F-88 状态（必须拆分，不得合并）」整节（L2216–L2240）；
2. 同文件 L2066–L2067 的完整验收 ❌ 理由（`v26-real-testcounts-20260914` 五条硬理由 + F-88 另有 8 项未取证）、L1909 / L1929 的 backlog 登记、L2261 待总控裁决第 1 项；
3. `docs/acceptance/v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md`（L88 五条硬理由）与 `REPORT.md`（L309, L320–L353）；
4. `docs/acceptance/f88-dq-severity-20260912/INVENTORY.md` 与 `IMPL-REPORT.md`（规则码台账、待裁项 1～11、未做清单 1～11、未测清单 1～10、逐项状态 18 条）；
5. `docs/acceptance/v26-f88-isolated-chain-20260914/CONTROLLER-VERIFICATION-20260914.md`（L74–L83 8 项未取证）；
6. 2026-09-14 至 2026-10-17 期间的后续工作（DEV-001～DEV-004、WP-S0、WP-N1A/B、WP-N2A/B/C、WP-F1～WP-F7d、WP-F5/F5b 等）的实际关账记录。

### 核心盘点结论

经过系统化解构与去重，本次盘点共梳理提取出 **28 项** 关联事项，分为四大来源族谱：
- **第一组 (F88-GAP-01～08)**：`PROJECT_STATUS.md` L2228–L2238 明确载明的 F-88 8 项未取证缺口；
- **第二组 (F88-HR-01～05)**：`v26-real-testcounts-20260914` 阻断完整验收的 5 条硬理由；
- **第三组 (F88-BK-01～03)**：`PROJECT_STATUS.md` L1909/L1929 的 backlog 登记与 L2261 待裁决第 1 项；
- **第四组 (F88-INV-01～12)**：`f88-dq-severity-20260912` 泳道衍生待裁项、未做未测项与快照隔离性条款。

对照 2026-10-16 / 10-17 的最新生产与测试基线对账结果：
- **A 类（已被后续工作实际关闭 / 总控裁决闭环）**：共 **15 项**（占比 53.6%）。包括完整验收 5 条硬理由全数闭环（DEV-001/002 门禁修复、DEV-003c 统一测试入口、WP-F2/F2b 隔离写 IT 收编 13/13 全绿、WP-F5 DEV-004/F-93 终局）、成功发布路径实跑（WP-N2B run 3、WP-F1 run 4、WP-F7c run 5 八阶段全绿）、六模块测试数（WP-S0 1380 PASS）、3306 零写入证明（R-01 裁决与 VM 环境隔离）、金额三项校验（L1942 确认）等。
- **B 类（本地可关，待立小实施包）**：共 **12 项**（占比 42.9%）。主要包括未登记码分支真库落库校验、31 条规则全量落库抽验、35 条定义 checksum 映射对齐、`catalog=qrc-1` 证据链终审、`activeSnapshotId` 多 profile 参数测试、ARCHIVED 快照隔离性测试、护栏变异测试探针、0.0005 临界阈值单测、17 规则码代码锚点终审、以及待总控下达的 F-88 backlog 终局销号与完成条件裁决。本报告已为这 12 项逐一制定了具体的执行、验证与判据标准，可供总控直接设立 `WP-F8-IMPL` 实施包关账。
- **C 类（外部依赖）**：共 **1 项**（占比 3.5%）。即「3306 真库上 V19/V20 的执行结果」（F88-GAP-04）。云端 Linux VM 已实现 3306 完全解耦，该项严格依赖外部正式生产库解冻与上线授权，对应 `FULL-PROGRAM.md` 外部依赖节「生产部署与核对清单」。

---

## 2. 盘点分类与汇总统计 (Summary & Categorization)

| 类别代码 | 类别定义 | 项数 | 占比 | 简述与去向 |
|:---:|---|:---:|:---:|---|
| **A** | **已被后续工作关闭** | **15** | 53.6% | 历史遗留缺口已被 DEV-001～DEV-003、WP-F1～WP-F5b 及相关裁定在事实层面闭环或销号，引用 `PROJECT_STATUS.md` 最新记录为据。 |
| **B** | **本地可关** | **12** | 42.9% | 本地环境（Linux VM + 3307 隔离库 + 离线测试套件）具备完全验证条件，已给出具体关账步骤（跑什么、验什么、判据是什么），供总控立实施包执行。 |
| **C** | **外部依赖** | **1** | 3.5% | 本机沙箱环境不可达，依赖真实生产 MySQL 3306 实例的解冻与部署授权，转入 `FULL-PROGRAM.md` 外部依赖节。 |
| **合计** | **全部盘点事项** | **28** | **100.0%** | **全项账实相符，无模糊项，无遗漏项。** |

---

## 3. F-88 全项盘点清单表 (Comprehensive Inventory Table)

> **阅读指引**：  
> - **A 类**：给出关闭证据的一手出处文件与章节行号锚点。  
> - **B 类**：明确列出「跑什么、验什么、判据是什么」三要素实施规约。  
> - **C 类**：注明外部依赖对象及 `FULL-PROGRAM.md` 对应节。

| 编号 | 事项名称 | 一手材料出处锚点 | 现状判定（对账 2026-10-16/17 事实） | 分类 | 关账步骤（B类）或关闭证据/依赖说明（A/C类） |
|---|---|---|---|:---:|---|
| **F88-GAP-01** | 未登记码分支的真库落库 | `docs/PROJECT_STATUS.md#L2230`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L56,L75` | 写侧单测已有覆盖（`QualityCheckerSeverityTest`），但真库 3307 层面在历次 run 中尚未落入未登记码样本。总控曾裁决「不需另做真链」，但表项本身仍在缺口区挂账。 | **B** | **跑什么**：在 3307 隔离库环境运行一个最小化定向集成用例（如在 `metric-analysis` 或 `warehouse-pipeline` 执行包含未登记码的落库调用）；<br>**验什么**：构造未在 `QualityRuleCatalog` 登记的码（如 `UNREGISTERED_PROBE`），调用 `PipelineService.persistChecks`；<br>**判据**：3307 `data_quality_result` 表写入该行，断言 `severity IS NULL`、`rule_version IS NULL`、`effective_severity='UNREGISTERED'`、`compat_policy_version='compat-v1'`、`rule_fingerprint` 正确。 |
| **F88-GAP-02** | ADS_STAGING / PUB / MXP / MP 约 31 条规则的落库 | `docs/PROJECT_STATUS.md#L2231`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L54,L76`<br>`f88-dq-severity/INVENTORY.md#L45-L79` | 2026-09-14 run 1 因 LANDING 阻断未推进；后续 WP-N2B (run 3)、WP-F1 (run 4)、WP-F7c (run 5) 均已实现 8 阶段全 SUCCESS 并正常发布，`/metrics/quality` 达 20 行，MP_* 校验全过。需专项抽验证明全层规则行均携带 V20 四列。 | **B** | **跑什么**：针对 3307 已有成功 run（如 run 4 或 run 5）运行只读 SQL 抽验脚本；<br>**验什么**：查询 `data_quality_result` 中 stage 为 `ADS_STAGING`、`PUBLISH` 的规则行；<br>**判据**：断言已落库规则行的 `rule_version="v1"`、`rule_checksum` 长度为 64、`effective_severity` 与 `RuleSeverity` 目录一致，无未初始化脏数据。 |
| **F88-GAP-03** | 成功／发布路径 + `metrics/overview` 读出真实数据 | `docs/PROJECT_STATUS.md#L2232`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L77` | 2026-09-14 终态为 FAILED 致未发布；后续工作已在云端 VM 多次完整跑通全发布链并返回真实指标。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L3326-L3328`（WP-N2B/C run 3 8 阶段全 SUCCESS，发布 S20260918_3，overview 返回 14 指标）；<br>② `docs/PROJECT_STATUS.md#L3345-L3349`（WP-F1 run 4 全 SUCCESS，发布 S20260918_4，ACTIVE 轮换验证）；<br>③ `docs/PROJECT_STATUS.md#L3404-L3407`（WP-F7c run 5 全 SUCCESS，发布 S20260918_5，MP_* 全过）。 |
| **F88-GAP-04** | 3306 真库上 V19/V20 的执行结果 | `docs/PROJECT_STATUS.md#L2233`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L78` | 3306 全程处于冻结状态；VM 环境已完全脱离 3306，且实施严格执行「不碰 3306」铁律。 | **C** | **外部依赖说明**：依赖正式环境生产 MySQL 3306 实例的解冻与部署授权，对应 `FULL-PROGRAM.md` 外部依赖节「生产部署与核对清单」。本机环境无 3306，不可关。 |
| **F88-GAP-05** | V19 的 35 条定义 ↔ 写侧 `rule_version`/`checksum` 全量映射 | `docs/PROJECT_STATUS.md#L2234`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L79` | S3-25 已通过 `QualityRuleVersionMigrationScriptTest` 证 SQL 种子与 Java 目录一致；`RuleSeverityTest` (14/14) 与 `RuleSeverityPathConsistencyTest` (4/4) 覆盖全目录，但缺少一份显式的 35/35 全量映射台账证据。 | **B** | **跑什么**：运行 `mvn test -pl platform-common -Dtest=RuleSeverityTest,QualityRuleVersionMigrationScriptTest` 与 `mvn test -pl platform-app -Dtest=RuleSeverityPathConsistencyTest`；<br>**验什么**：提取 `V19__quality_rule_definition.sql` 的 35 条 INSERT 与 `QualityRuleCatalog.DEFINITIONS` 35 条记录逐条比对；<br>**判据**：35 条规则码的 version 全为 "v1"、checksum 全等、两端映射差异为 0。 |
| **F88-GAP-06** | `catalog=qrc-1` 无落库承载列（回查目录版本缺口） | `docs/PROJECT_STATUS.md#L2235`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L80` | V20 DDL 未设 `catalog_version` 列，后续迁移（至 V38）保持稳定未增列；`catalog=qrc-1` 与规则指纹已完整记录于各 run 的阶段证据 JSON 与应用日志。 | **B** | **跑什么**：检查一次成功 run（如 run 4 或 run 5）的 `pipeline_run_stage.stage_evidence` JSON 与日志；<br>**验什么**：查验 `QUALITY_CHECK` 证据中的 `ruleFingerprint`、`catalog` 与兼容策略字段；<br>**判据**：总控确认阶段证据 JSON 与应用日志足以支撑目录版本可追溯性，无需也不宜为单日常量做加列破坏性迁移，正式出具裁定记录关闭。 |
| **F88-GAP-07** | 3306 binlog 位点级零写入证明 | `docs/PROJECT_STATUS.md#L2236`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L81` | 2026-09-14 历史取证未取 before 位点；后续总控 R-01 裁决已正式采纳零写入证明，且当前 VM 环境全程物理脱离 3306。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2086`（总控 R-01 裁决采纳默认档不碰正式库证明）；<br>② `docs/PROJECT_STATUS.md#L2113`（N-4 轮只读复取 10 项零写入证明）；<br>③ `docs/PROJECT_STATUS.md#L3287`（VM 环境事实：仅使用 3307，3306 全程未触碰）。 |
| **F88-GAP-08** | `analytics-server` 六模块真实测试数（曾阻塞于 D-5） | `docs/PROJECT_STATUS.md#L2237`<br>`v26-f88-isolated-chain/CONTROLLER-VERIFICATION#L82` | 2026-09-14 因平台误触 3306 风险而阻塞；后续 DEV-003c 测得 632，WP-S0 在 Linux VM 完整实跑六模块离线 reactor 达到 1380 PASS（F0/E0/S2）。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2053,L2162,L2280`（DEV-003c fresh 实测 632 且 3306 零写入）；<br>② `docs/PROJECT_STATUS.md#L3295`（WP-S0 亲跑实测 analytics-server 六模块 1380 PASS，总计 1540 MATCH exit 0）。 |
| **F88-HR-01** | 5 个 `*IT.java` 默认档 0 执行（include 缺口） | `docs/PROJECT_STATUS.md#L2067,L2170`<br>`v26-real-testcounts/CONTROLLER-VERIFICATION#L88` | 属于分层测试设计要求。`*IT` 已被正式收编入隔离档或由总控做出分级裁定，不再作为默认档缺口。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2129`（DEV-003b 收编 `IsolationGuardMySqlIT`）；<br>② `docs/PROJECT_STATUS.md#L3350-L3366`（WP-F2/F2b 收编 `MetricAdsMySqlIT`、`MetricPublisherMySqlIT`、`AdsDataQualityErrorRateNullableMySqlIT` 三类 7 例，13/13 全绿）；<br>③ `docs/PROJECT_STATUS.md#L3353-L3354`（WP-F2 裁定 `SourceRegistryMigrationMySqlIT` 为 P1 人工件）；<br>④ `docs/PROJECT_STATUS.md#L2103`（裁定 `AnalysisGoldenMySqlIT` 为 D 类人工件）。 |
| **F88-HR-02** | 4 个 IT 点名执行时绿构建零执行（静默 skip），1 个硬失败 | `docs/PROJECT_STATUS.md#L2067,L2170`<br>`v26-real-testcounts/CONTROLLER-VERIFICATION#L88` | 缺少环境配置导致 `IsolationProfileCondition` skip；`SparkStageExecutorSmokeIT` 缺配置报错。后续已建立完整隔离环境供给。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L3364`（WP-F2b 运行器 schema 供给步就绪，三类写 IT 真实执行 13/13 零跳过）；<br>② `docs/PROJECT_STATUS.md#L3375`（WP-F5 裁定 `SparkStageExecutorSmokeIT` 转独立 Spark 冒烟 backlog）。 |
| **F88-HR-03** | 隔离档 runner `exit 7`（mall 30 假红、generator 19 未过） | `docs/PROJECT_STATUS.md#L2067,L2170`<br>`v26-real-testcounts/CONTROLLER-VERIFICATION#L88` | DEV-002 统一指纹消除 mall 假红；DEV-003a 编排 Flyway 消除 generator 5 error；DEV-003c 统一调度；WP-S0 / WP-F2b 全面绿。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2115`（DEV-002 语义一致性收口）；<br>② `docs/PROJECT_STATUS.md#L2127`（DEV-003a 首跑 19/19 全绿）；<br>③ `docs/PROJECT_STATUS.md#L2214,L2280`（DEV-003c 隔离档 55 全绿 exit 0）；<br>④ `docs/PROJECT_STATUS.md#L3295,L3364`（WP-S0 与 WP-F2b 原版脚本 PASS exit 0）。 |
| **F88-HR-04** | 写入型 IT 被 `@@version_major` 卡死，0 个真实断言被执行 | `docs/PROJECT_STATUS.md#L2067,L2170`<br>`v26-real-testcounts/CONTROLLER-VERIFICATION#L88` | `TestIsolationGuard` 缺陷阻断 MySQL 8 建连。DEV-001 已彻底修复，写 IT 断言全部真实跑通。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2114,L2283`（DEV-001 提交 `db77654` 并经总控实测关闭）；<br>② `docs/PROJECT_STATUS.md#L3351,L3364`（WP-F2 / WP-F2b 写入型 IT 7/7 真实执行并全绿）。 |
| **F88-HR-05** | `SourceRegistryMigrationMySqlIT` 结构性必败 | `docs/PROJECT_STATUS.md#L2067,L2170`<br>`v26-real-testcounts/CONTROLLER-VERIFICATION#L88` | 同库传参且断言止于 V18。WP-F2 查明其为 P1 人工件并裁定不收编；WP-F5 终局销号；迁移 IT 缺口已由 `AnalyticsIsolationFlywayIT` 覆盖至 V38。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L3353-L3354`（WP-F2 终局裁决维持人工件）；<br>② `docs/PROJECT_STATUS.md#L3363-L3364`（WP-F2b `AnalyticsIsolationFlywayIT` 覆盖至 V38）；<br>③ `docs/PROJECT_STATUS.md#L3374`（WP-F5 F-93-02 正式关闭）。 |
| **F88-BK-01** | F-88 维持限定验收、完整验收未宣布（口径约束） | `docs/PROJECT_STATUS.md#L1909,L2239-L2240` | 表述边界严格受控，禁止任何「F-88 已通过完整验收」的同义表述。本轮盘点严格遵守，作为规约待总控审定。 | **B** | **跑什么**：对照本盘点报告梳理结果进行总控审定；<br>**验什么**：核对 B 类项实施包完成后是否满足表述调整条件；<br>**判据**：总控正式更新 `PROJECT_STATUS.md` 中 F-88 状态口径，在未完成 B/C 类前严格保持「限定验收」。 |
| **F88-BK-02** | F-88 完整验收剩余项转入 Development backlog 挂账 | `docs/PROJECT_STATUS.md#L1929` | 历史表格登记项。当前已在 WP-F8 细化拆解为 28 项具象事实并建立对账。 | **B** | **跑什么**：总控完成 WP-F8 实施包关账后更新 `PROJECT_STATUS.md` 表格；<br>**验什么**：将 L1929 的抽象挂账行替换为具象的已关闭与外部依赖状态；<br>**判据**：`PROJECT_STATUS.md` 中的 backlog 状态更新为「WP-F8 已盘点并分类处置完毕」。 |
| **F88-BK-03** | F-88 完整验收的完成条件待总控裁决（待裁决第 1 项） | `docs/PROJECT_STATUS.md#L2261` | 原定前置条件「DEV-003/DEV-004 缺口收敛」已于 2026-10-16/17 彻底完成，裁决条件完全成熟。 | **B** | **跑什么**：总控调阅本盘点报告，审定 B 类实施包与 C 类外部依赖；<br>**验什么**：裁定 8 项未取证中哪些必须在本地关闭、哪些转入外部依赖；<br>**判据**：正式发布总控决策，明确 F-88 在答辩与全量纲领中的终局判定标准。 |
| **F88-INV-01** | `activeSnapshotId()` 全库查询未带 profile 条件 (F-92) | `f88-dq-severity/INVENTORY.md#L234,L249`<br>`f88-dq-severity/IMPL-REPORT.md#L590,L668` | WP-F7c 已实现在两 profile（档案 2 与 3）共存不同 ACTIVE 快照；但底层 `MySqlMetricStore` 与 `MetricAdsReader` 的无参方法仍为全库 LIMIT 1。 | **B** | **跑什么**：运行 `mvn test -pl metric-analysis -Dtest=MetricAdsReaderTest,MySqlMetricStoreTest` 或增加定向单测；<br>**验什么**：验证各查询入口优先调用带 `runtimeProfileId` 的重载版本；<br>**判据**：在多 profile 均有 ACTIVE 快照时，传入 profile A 必得快照 A、传入 profile B 必得快照 B，断言 profile 隔离性。 |
| **F88-INV-02** | `qualityStatus` 读取时现算导致历史快照结论随口径变化 (F-93-01) | `f88-dq-severity/INVENTORY.md#L97,L255`<br>`f88-dq-severity/IMPL-REPORT.md#L681` | 读侧现算与快照不可变存在口径漂移；WP-F5 已正式定性为 C 类长期架构储备，不阻塞答辩。 | **A** | **关闭证据出处**：<br>`docs/PROJECT_STATUS.md#L3375`（WP-F5 裁定：F-93-01 转长期架构储备，现状以不可变快照 + 显式重算接口替代，就此关闭当前挂账）。 |
| **F88-INV-03** | 48 行 legacy `NULL` severity（run 1–15）处置 | `f88-dq-severity/INVENTORY.md#L242-L248`<br>`f88-dq-severity/IMPL-REPORT.md#L611,L756` | 历史 run 1–15 存在 NULL severity。总控裁决 1 已明确「不回填、历史结论不改、兜底按阻断」，`RuleSeverity` 已实现该兜底。 | **A** | **关闭证据出处**：<br>`docs/acceptance/f88-dq-severity-20260912/IMPL-REPORT.md#L756`（总控裁决 1 终局确认）。 |
| **F88-INV-04** | `MetricPublishValidator` severity 大小写敏感性 | `f88-dq-severity/INVENTORY.md#L267`<br>`f88-dq-severity/IMPL-REPORT.md#L759` | `"BLOCKING".equals(...)` 存在潜在大小写不一致。F-88 写侧已改为统一走 `RuleSeverity`，测试 10/10 PASS。 | **A** | **关闭证据出处**：<br>`docs/acceptance/f88-dq-severity-20260912/IMPL-REPORT.md#L759`（总控裁决 4 确认关闭，`MetricPublishValidatorSeverityTest` 10 例全绿）。 |
| **F88-INV-05** | `spark-jobs` 侧 severity 字面量（Scala 禁改，全仓 0 个 "WARN" 字面量） | `f88-dq-severity/INVENTORY.md#L273`<br>`f88-dq-severity/IMPL-REPORT.md#L606,L760` | 作业侧回传 ERROR，但平台层通过 `RuleSeverity` 在落库归一化（`effective_severity`）与读侧统一所有者承接，确立了平台口径单一真相源。 | **A** | **关闭证据出处**：<br>`docs/acceptance/f88-dq-severity-20260912/IMPL-REPORT.md#L606,L760`（平台落库归一化架构治理定案）。 |
| **F88-INV-06** | `ARCHIVED` 快照不可变性与历史快照入口权限/不混入默认查询 | `f88-dq-severity/INVENTORY.md#L236,L284`<br>`f88-dq-severity/IMPL-REPORT.md#L589,L625` | WP-F1 实测了快照轮换（S20260918_3 转 ARCHIVED、S20260918_4 为 ACTIVE）；需专项单测验证默认查询不混入 ARCHIVED。 | **B** | **跑什么**：运行针对 `AnalysisService` / `MetricController` 的只读集成单测；<br>**验什么**：对比默认查询与显式 `?snapshotId=S20260918_3` 历史查询的响应信封；<br>**判据**：默认查询严格返回唯一 ACTIVE 快照数据，显式查询返回指定快照数据，状态为 ARCHIVED 且指标值不互相渗透。 |
| **F88-INV-07** | 变异测试（跨模块护栏 `RuleSeverityPathConsistencyTest` 判别力未测） | `f88-dq-severity/INVENTORY.md#L288`<br>`f88-dq-severity/IMPL-REPORT.md#L622` | `platform-app` 70 次比对 4/4 PASS 真实运行，但尚未进行过人为变异探针测试以证明其具备「红案捕获能力」。 | **B** | **跑什么**：变异探针测试。在测试环境临时修改 `RuleSeverity` 中任一规则的有效严重度，执行 `mvn test -pl platform-app -Dtest=RuleSeverityPathConsistencyTest`；<br>**验什么**：断言测试是否如期失败红案；<br>**判据**：变异时必红，复原后必绿（捕获率 100%，无空泛通过）。 |
| **F88-INV-08** | 版本化载体落地与 DDL 迁移（`quality_rule_definition` 与增列） | `f88-dq-severity/INVENTORY.md#L289`<br>`f88-dq-severity/IMPL-REPORT.md#L614,L763` | 2026-09-12 仅为草案；2026-09-14 已正式分配 V19/V20 入库并在 3307 落地，WP-F2b 迁移钉已更新至 V38。 | **A** | **关闭证据出处**：<br>① `docs/PROJECT_STATUS.md#L2084-L2085`（F-88 写侧闭环与隔离真链 3307 真实落地 V19/V20）；<br>② `docs/PROJECT_STATUS.md#L3363-L3364`（WP-F2b `AnalyticsIsolationFlywayIT` 自动化覆盖至 V38）。 |
| **F88-INV-09** | `THRESHOLD_OBSERVATION` 阈值边界值未测（恰好等于 `0.0005`） | `f88-dq-severity/INVENTORY.md#L290`<br>`f88-dq-severity/IMPL-REPORT.md#L629,L750` | 此前仅测试了 0.000000（不阻断）与 0.5（阻断），未测试恰好等于 0.0005 的临界点。 | **B** | **跑什么**：在 `QualityCheckerSeverityTest` 或 `RuleSeverityTest` 中追加精确边界测试用例；<br>**验什么**：传入 `errorRate = 0.0005` 与 `errorRate = 0.0005001`；<br>**判据**：`errorRate = 0.0005`（≤ 阈值）判定为通过（观察项/WARN），`errorRate = 0.0005001`（> 阈值）判定为阻断（BLOCKING）。 |
| **F88-INV-10** | 指导书 §7.3.1 line 526 三项金额校验独立验证 | `f88-dq-severity/IMPL-REPORT.md#L726`<br>`docs/PROJECT_STATUS.md#L1942` | 历史报告曾误报为缺口；`PROJECT_STATUS.md#L1942` 已正式澄清三者已独立实现且不构成缺口。 | **A** | **关闭证据出处**：<br>`docs/PROJECT_STATUS.md#L1942`（正式核对定案：平台层已实现独立规则码与断言独立性，不构成缺口）。 |
| **F88-INV-11** | 待裁-11: 接受 run 24/47 翻 FAIL 及线上看板质量卡转红 | `f88-dq-severity/IMPL-REPORT.md#L766` | 收紧契约后历史 run 24/47 翻 FAIL 属正确行为；后续新运行数据均正常通过质量检查。 | **A** | **关闭证据出处**：<br>① `docs/acceptance/f88-dq-severity-20260912/IMPL-REPORT.md#L274-L281`（总控采纳严格契约）；<br>② `docs/PROJECT_STATUS.md#L3326-L3328`（新运行 run 3/4/5 质检全部正常通过）。 |
| **F88-INV-12** | 17 个规则码在 `spark-jobs` 源码里的确切逐码 `file:line` 记录 | `f88-dq-severity/INVENTORY.md#L65,L286`<br>`f88-dq-severity/IMPL-REPORT.md#L628` | 历史报告未记录 17 规则码的确切源码行号；本次盘点已通过只读检索全量完成行号精准定位。 | **B** | **跑什么**：静态代码位置审查；<br>**验什么**：核对 17 个规则码在 `spark-jobs` 与 `analytics-server` 的具体定义点；<br>**判据**：本报告第 4.4 节已完整给出 17 规则码精准的 `文件路径:行号` 台账，总控确认即可直接闭环。 |

---

## 4. 四大核心来源逐项深度对账与证据链分析

### 4.1 F-88 8 项未取证缺口深度对账 (F88-GAP-01～08)

根据 `docs/PROJECT_STATUS.md` L2228–L2238 及 `docs/acceptance/v26-f88-isolated-chain-20260914/CONTROLLER-VERIFICATION-20260914.md` L74–L83 的原文登记，对 8 项未取证逐项对账：

1. **未登记码分支的真库落库（GAP-01，分类 B）**：
   - *历史背景*：2026-09-14 隔离真链中未登记码分支为 0 行，总控裁决（`CONTROLLER-VERIFICATION#L56`）认为单测已足够覆盖，不需专门造真链。但在未取证列表中仍保留登记。
   - *当前现状*：代码中 `RuleSeverity.resolve()` 的 `UNREGISTERED` 兜底逻辑已经由单测 `QualityCheckerSeverityTest` 验证（断言 `severity isNull` 且 `effectiveSeverity==UNREGISTERED`）。但在真实的 3307 数据库中，尚无包含未登记码的真实数据行。
   - *处置定案*：判为 **B 类**。总控可通过立一个轻量级集成用例，向隔离库真实写入一条未登记规则，核验 `data_quality_result` 四列后销号。

2. **ADS_STAGING / PUB / MXP / MP 约 31 条规则的落库（GAP-02，分类 B）**：
   - *历史背景*：2026-09-14 跑动仅推进至 LANDING 阶段（4 条规则），其余阶段规则未落库。
   - *当前现状*：2026-10-16 / 10-17 期间，流水线在 VM 环境已成功完成多次全链 8 阶段生产实跑：WP-N2B (run 3)、WP-F1 (run 4)、WP-F7c (run 5) 均取得全 SUCCESS，`/metrics/quality` 稳定返回 20 行，MP_* 校验逐项全过。
   - *处置定案*：判为 **B 类**。虽然生产链已实际跑通，但尚未形成一份针对 `data_quality_result` 中全阶段规则行 V20 四列完整性的专项抽验报告。由总控在 3307 现有库执行一次 SELECT 抽验并留痕后即可关账。

3. **成功／发布路径 + `metrics/overview`（GAP-03，分类 A）**：
   - *历史背景*：2026-09-14 跑动终态为 FAILED，未进入发布阶段，overview 接口为空。
   - *当前现状*：WP-N2B/C（`PROJECT_STATUS.md#L3326-L3328`）run 3 发布 S20260918_3 且 overview 返回 14 指标；WP-F1（`#L3345-L3349`）run 4 发布 S20260918_4 且完成轮换；WP-F7c（`#L3404-L3407`）run 5 发布 S20260918_5 且 MP_* 全过。
   - *处置定案*：判为 **A 类（已由后续工作实际关闭）**。

4. **3306 真库上 V19/V20 的执行结果（GAP-04，分类 C）**：
   - *历史背景*：3306 处于总控硬冻结，V19/V20 仅在 3307 隔离库验证。
   - *当前现状*：当前开发主线已迁移至云端 Linux VM（`PROJECT_STATUS.md#L3287`），本机环境完全没有 3306 实例，且所有开发与测试包均坚决贯彻「不碰 3306」铁律。
   - *处置定案*：判为 **C 类（外部依赖）**。依赖真实生产库解冻授权与生产部署核验，对应 `FULL-PROGRAM.md` 外部依赖节。

5. **V19 的 35 条定义 ↔ 写侧全量映射（GAP-05，分类 B）**：
   - *历史背景*：2026-09-14 仅核对了本次落库的 4 行。
   - *当前现状*：S3-25（`PROJECT_STATUS.md#L1700`）已通过 `QualityRuleVersionMigrationScriptTest`（11/11 PASS）证明 SQL 迁移脚本与 Java 目录零漂移；`RuleSeverityTest` 与 `RuleSeverityPathConsistencyTest` 覆盖了全部 35 条定义。
   - *处置定案*：判为 **B 类**。只需通过一份只读脚本比对 SQL 与 Java 常量，出具 35/35 全量映射台账即可关闭。

6. **`catalog=qrc-1` 无落库承载列（GAP-06，分类 B）**：
   - *历史背景*：无法从 `data_quality_result` 表结构本身回查目录版本。
   - *当前现状*：V20 DDL 已经冻结，且后续至 V38 均保持表结构稳定。流水线执行证据已完整记录 `ruleFingerprint`、`catalog`、`compatPolicy`。
   - *处置定案*：判为 **B 类**。总控确认阶段证据链已满足可追溯性、无需修改 DDL，以裁决形式正式关闭。

7. **3306 binlog 位点级零写入证明（GAP-07，分类 A）**：
   - *历史背景*：2026-09-14 缺少 before 位点。
   - *当前现状*：总控 R-01 裁决（`PROJECT_STATUS.md#L2086`）已采纳零写入证明；N-4 轮再次以 10 项指标确认零差异；当前 VM 环境物理无 3306。
   - *处置定案*：判为 **A 类（已由后续裁决与环境迁移关闭）**。

8. **`analytics-server` 六模块真实测试数（GAP-08，分类 A）**：
   - *历史背景*：曾因担心 platform-app 触发 3306 迁移而阻塞。
   - *当前现状*：DEV-003c 测得 632；WP-S0（`PROJECT_STATUS.md#L3295`）在 Linux VM 真实运行离线全量 reactor 测得 1380 PASS（F0/E0/S2），全默认档 1540 PASS exit 0。
   - *处置定案*：判为 **A 类（已由后续工作关闭）**。

---

### 4.2 完整验收 ❌ 5 条硬理由深度对账 (F88-HR-01～05)

根据 `docs/acceptance/v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md` L88 与 `REPORT.md` L309 的原文登记，对 5 条硬理由逐项对账：

1. **5 个 `*IT.java` 默认档 0 执行（HR-01，分类 A）**：
   - *对账结果*：属于分层测试设计规范，IT 不进默认档。DEV-003b 已收编 `IsolationGuardMySqlIT`；WP-F2 / WP-F2b（`PROJECT_STATUS.md#L3350-L3366`）已通过 `-IncludeAnalyticsWriteIts` 将 `MetricPublisherMySqlIT`、`MetricAdsMySqlIT`、`AdsDataQualityErrorRateNullableMySqlIT` 正式收编入隔离运行器，全绿 13/13；其余两个 IT 已被裁定为人工件（P1/D类）。硬理由消灭，判定为 **A 类关闭**。

2. **4 个 IT 点名静默 skip、1 个硬失败（HR-02，分类 A）**：
   - *对账结果*：WP-F2b（`PROJECT_STATUS.md#L3364`）已为运行器补齐完整环境契约与前置 Flyway 供给，写入型 IT 真实执行 13/13 零跳过；`SparkStageExecutorSmokeIT` 已在 WP-F5（`#L3375`）裁定转入独立 Spark 冒烟 backlog。硬理由消灭，判定为 **A 类关闭**。

3. **隔离档 runner `exit 7`（HR-03，分类 A）**：
   - *对账结果*：DEV-002 统一指纹解决了 mall 30 假红；DEV-003a 编排初始化解决了 generator 5 error；DEV-003c 统一调度；WP-S0（`PROJECT_STATUS.md#L3295`）与 WP-F2b（`#L3364`）实跑隔离档 runner 均取得 `[PASS exit=0]`。硬理由消灭，判定为 **A 类关闭**。

4. **写入型 IT 被 `@@version_major` 卡死（HR-04，分类 A）**：
   - *对账结果*：DEV-001 提交 `db77654` 彻底修复该缺陷，总控复核确认 DEV-001 关闭（`PROJECT_STATUS.md#L2114`）；WP-F2 / WP-F2b 写入型 IT 真实运行 7/7 PASS。硬理由消灭，判定为 **A 类关闭**。

5. **`SourceRegistryMigrationMySqlIT` 结构性必败（HR-05，分类 A）**：
   - *对账结果*：WP-F2（`PROJECT_STATUS.md#L3353-L3354`）查明该测试为 P1 期人工取证件并裁定不收编；WP-F5（`#L3374`）正式关闭 F-93-02；其关切的迁移 IT 覆盖缺口已被 `AnalyticsIsolationFlywayIT` 覆盖至 V38。硬理由消灭，判定为 **A 类关闭**。

---

### 4.3 Backlog 与待裁决登记项深度对账 (F88-BK-01～03)

1. **F-88 维持限定验收口径（BK-01，分类 B）**：
   - 处于严格受控状态。本报告严守口径边界，不宣布通过完整验收。待总控完成实施包后进行终局审定。
2. **F-88 完整验收剩余项转入 backlog（BK-02，分类 B）**：
   - 本报告已将 `PROJECT_STATUS.md#L1929` 的抽象挂账彻底落实为 28 项具象事实，待总控实施包关账后销号。
3. **F-88 完整验收完成条件裁决（BK-03，分类 B）**：
   - 前置依赖 DEV-003 / DEV-004 已完全闭合，总控可根据本报告结论正式出具 F-88 终局验收裁决。

---

### 4.4 F-88 泳道遗留项与 17 个规则码精确源码行号台账 (F88-INV-01～12)

#### 17 规则码在 `spark-jobs` 与平台源码中的精准代码锚点（响应 F88-INV-12）

通过对全仓源码进行精确静态检索，本报告首次完整确立 17 个规则码的一手代码定义锚点台账：

| # | 规则码 (rule_code) | 所属阶段 | 定义文件路径 | 精确行号锚点 | 核心逻辑与严重度定义说明 |
|:---:|---|---|---|:---:|---|
| 1 | `AMOUNT_RECONCILE` | LANDING | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | **L95** | 金额对账：支付订单总额 vs 订单项金额，BLOCKING |
| 2 | `REQUIRED_FIELD_NULL_RATE` | LANDING | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | **L116** | 必需字段空值率：行为事件 user_id/product_id，BLOCKING |
| 3 | `EVENT_ID_UNIQUE` | LANDING | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | **L130** | 重复率：条件观察项（阈值 0.0005，超阈值升 BLOCKING） |
| 4 | `ENUM_WHITELIST` | LANDING | `analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/QualityChecker.java` | **L147** | 行为枚举白名单：非法行为类型阻断，BLOCKING |
| 5 | `ADS_STAGING_PRESENT` | ADS_STAGING | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L59** | 暂存分区存在性且 Location 可读校验，BLOCKING |
| 6 | `ADS_STAGING_SNAPSHOT_ISOLATION` | ADS_STAGING | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L71** | 快照隔离：历史 staging 存在校验（观察项/WARN） |
| 7 | `ADS_STAGING_KEY_NOT_NULL` | ADS_STAGING | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L92** | 关键列非空：关键字段 NULL 计数为 0，BLOCKING |
| 8 | `PUB_DQ_BLOCKING_RULES` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L112** | 发布侧阻断规则汇总汇总检验，BLOCKING |
| 9 | `PUB_DQ_EVENT_ID_UNIQUE` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L120** | 发布侧 event_id 重复率复检（条件观察项） |
| 10 | `ADS_DWS_FUNNEL_RECONCILE` | ADS_STAGING | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsQualityJob.scala` | **L141** | 跨层对账：ADS 漏斗 vs DWS 漏斗，BLOCKING |
| 11 | `PUB_STAGING_READY` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala` | **L55** | 发布前预检：暂存分区必须全部就绪，BLOCKING |
| 12 | `PUB_FORMAL_PARTITION_MATCH` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala` | **L85** | 发布后校验：正式分区行数 = 暂存分区行数，BLOCKING |
| 13 | `PUB_POINTER_SWITCH` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala` | **L89** | 发布操作审计：本次元数据指针切换表数，INFO |
| 14 | `PUB_STAGING_PRUNE` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/AdsPublishJob.scala` | **L115** | 暂存清理审计：历史暂存快照清理计数，INFO |
| 15 | `MXP_SNAPSHOT_PINNED` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala` | **L66** | 快照钉住校验：正式分区必须指向本次快照，BLOCKING |
| 16 | `MXP_EXPORT_ROWS` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala` | **L103** | 导出回读校验：导出文件行数 = Hive 分区行数，BLOCKING |
| 17 | `MXP_EXPORT_COMPLETE` | PUBLISH | `spark-jobs/src/main/scala/com/graduation/analytics/job/MetricExportJob.scala` | **L114** | 完整性校验：全部指标宽表导出完成且行数一致，BLOCKING |

*附录：指标库发布对账（Java 侧 `MetricPublishValidator.java`）核心代码锚点*：
- `MP_MANIFEST_TABLES`: `analytics-server/metric-analysis/src/main/java/com/graduation/analytics/metric/publish/MetricPublishValidator.java:73`
- `MP_ADS_ROWS_MATCH`: 同文件 **L153**
- `MP_METRIC_VALUE_COUNT`: 同文件 **L212**
- `MP_ACTIVE_SNAPSHOT`: 同文件 **L235**
- `MP_OLD_ACTIVE_ARCHIVED`: 同文件 **L261**

---

## 5. 下一步建议与实施包规划建议 (Next Steps & Implementation Plan)

为确保 F-88 能够平稳有序地完成技术收口，建议总控设立 **`WP-F8-IMPL`（F-88 剩余项关账实施包）**，集中处置上述 **12 项 B 类条目**。

### 建议实施包结构划分

实施包可分为三个原子任务依次推进：

1. **Task 1: 契约测试与边界校验（纯 Java/Maven 离线执行，耗时约 2 分钟）**
   - 覆盖条目：`F88-GAP-05`（35 规则映射对齐）、`F88-INV-07`（护栏变异测试探针）、`F88-INV-09`（0.0005 临界阈值单测）。
   - 动作：在 `platform-common` 与 `platform-app` 中补充上述单测并执行变异验证，确保变异必红、复原必绿。

2. **Task 2: 隔离环境真库只读抽验与探针（需 3307 隔离库，耗时约 3 分钟）**
   - 覆盖条目：`F88-GAP-01`（未登记码落库探针）、`F88-GAP-02`（全层规则 V20 四列落库抽验）、`F88-GAP-06`（证据 JSON 目录版本查验）、`F88-INV-01`（profile 参数测试）、`F88-INV-06`（ARCHIVED 隔离测试）。
   - 动作：编写一个轻量级 Python 或 PowerShell 检验脚本，直连 3307 实例已有的成功 run 数据，并执行一次未登记码落库注入，生成抽验日志。

3. **Task 3: 治理登记与终局裁决（文档操作，耗时约 1 分钟）**
   - 覆盖条目：`F88-BK-01`、`F88-BK-02`、`F88-BK-03`、`F88-INV-12`。
   - 动作：总控在 `PROJECT_STATUS.md` 中引用 Task 1/2 的验收日志，正式更新 F-88 状态章节，将 backlog 销号，并将 `F88-GAP-04`（3306 迁移）正式挂账至 `FULL-PROGRAM.md` 外部依赖节。

---

## 6. 结论与表述边界重申

1. 本盘点报告已完整执行 `WP-F8.md` 规约的全部任务，提取并对账了 F-88 的全部历史未验项（28 项：A类 15 项、B类 12 项、C类 1 项）。
2. **严格坚守状态表述边界**：本报告为只读盘点，**不改变 F-88 当前「限定验收」的正式状态口径**，不作任何超越总控授权的完整验收结论。
3. 盘点成果表明，F-88 在核心链路、阻断机制、写侧契约及隔离运行方面已高度收敛，不存在未定位的盲目缺陷；剩余的 12 项本地可关项均具备清晰明确的验证判据，可在总控立项后迅速闭环。
