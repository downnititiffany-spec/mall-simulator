# WP-F5 (F-93 清理) 条目盘点报告

> **盘点日期**：2026-10-16  
> **执行依据**：`~/workspace/projects/mall-simulator-notes/WP-F5-INVENTORY.md`  
> **盘点性质**：只读盘点（纯静态文档与代码核对，未运行任何命令，未连接任何数据库，未触碰 3306/3307 端口）  
> **产出目标**：将历史积压集合 `F-93` 的全部条目钉成一张可执行清单，给出客观证据与建议处置分类（[A] 本地可直接关闭、[B] 需小实施包、[C] 转外部依赖/长期登记），供总控逐项裁定。

---

## 1. 执行摘要 (Executive Summary)

历史积压集合 `F-93`（历史治理规划与积压集合编号）最初定义于 `docs/PROJECT_STATUS.md`（§6.7 L2101 起），并在正式发布的权威文件《项目完整实施指导书 V3.0》（§11 L257）与《项目设计文档 V3.0》（§12.4 L520）中被确立为非阻塞性开发 backlog。

经过对全仓文档（`docs/PROJECT_STATUS.md`、`docs/guidance/项目完整实施指导书 V3.0.md`、`docs/design/项目设计文档 V3.0.md`、`docs/README.md`）、调度脚本（`scripts/run-tests.ps1`、`scripts/run-isolated-tests.ps1`、`scripts/it-prepare-isolation.ps1`）、测试源码（`AnalyticsIsolationFlywayIT.java`、`TestIsolationGuard.java`、`IsolationGuardMySqlIT.java` 等）以及 2026-10-16 最新工作包（WP-F1～WP-F4、WP-F2b）成果的系统性逐行查验，本盘点确认：

1. **F-93 原始涵盖的条目已大量被后续工作闭环或裁定覆盖**：
   - **DEV-004 IT 收编部分**：WP-F2（`docs/PROJECT_STATUS.md#L3353-L3354`）已正式裁定 `SourceRegistryMigrationMySqlIT` 维持 P1 人工取证件，永久不收编进自动化套件。
   - **DEV-004 V19/V20 迁移覆盖缺口**：已由 `AnalyticsIsolationFlywayIT` 自动化真实覆盖，且在 WP-F2b 中版本钉已前移至 meta V38 / metric V13（`docs/PROJECT_STATUS.md#L3363-L3364`），缺口已完全闭合。
   - **分析侧写入型 IT 收编**：`MetricAdsMySqlIT`（2 例）、`MetricPublisherMySqlIT`（3 例）、`AdsDataQualityErrorRateNullableMySqlIT`（2 例）共 7 例写入 IT 已在 WP-F2b 中通过 `-IncludeAnalyticsWriteIts` 完成收编并实现 13/13 全绿验证（`docs/PROJECT_STATUS.md#L3364`）。
   - **前端统一入口**：已由 WP-F3 落地 `run-tests.ps1 -Suite web` 独立关账（`docs/PROJECT_STATUS.md#L3355-L3359`）。
   - **R-05 (`AnalysisGoldenMySqlIT`)**：已由总控裁决为 D 类历史只读复验件，永久排除出隔离统一入口（`docs/PROJECT_STATUS.md#L2103`、`scripts/run-tests.ps1#L45`）。
   - **R-06 (唯一权威键名表)**：已在 `application.yml` 与 `TestIsolationGuard` / `run-isolated-tests.ps1` 中完成标准化与映射闭环。
2. **唯一需要代码实施的实体缺口（需小实施包，类别 B）**：
   - **3307 隔离实例遗留对象清理机制 (`-AllowedCleanDbs`)**：虽然总控早已下达「走运行器 `-AllowedCleanDbs` 白名单清理，禁止手写 DROP」的裁决（`docs/PROJECT_STATUS.md#L2106`, `L2214`），但查阅当前 `scripts/run-isolated-tests.ps1`（第 66-82 行参数区）及 `scripts/run-tests.ps1`，该白名单清理开关**尚未在脚本中实现**。目前 3307 实例上登记在案的 `dev003c%`（4 库）及后续各阶段累积的历史隔离库与账号尚待安全清理。
3. **长期跟踪与外部依赖条目（类别 C）**：
   - **读侧历史版本复算**（《项目设计文档 V3.0》§12.4 L520）：读侧按特定规则版本动态重算门禁为扩展性架构设计，当前平台已通过只读快照与显式重算接口支撑生产链路，应转入长期架构演进储备。
   - **`SparkStageExecutorSmokeIT`**：包含真实 `spark-submit` 与 Windows 路径硬编码，已转入 Spark 独立专项冒烟 backlog，依赖外部/真实 Spark 集群环境。

---

## 2. F-93 条目全量溯源清单 (Comprehensive Traceability Table)

本表检索了 `docs/PROJECT_STATUS.md`、`docs/guidance/项目完整实施指导书 V3.0.md`、`docs/design/项目设计文档 V3.0.md` 及相关治理与脚本中的所有关联条目，逐项摘录原文要点并提供当前最新代码/状态证据：

| 条目编号 / 名称 | 原文出处（文件路径 + 行号） | 原始要求 / 核心要点摘录 | 当前代码/登记现状与证据 | 建议分类 |
|---|---|---|---|:---:|
| **F-93-01**<br>读侧按指定版本复算 | `docs/design/项目设计文档 V3.0.md#L520`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L257`<br>`docs/PROJECT_STATUS.md#L2101` | 《设计 V3.0》§12.4 L520:「DataQualityGate.decisionForRun(runId)仍采用QualityRuleCatalog.DEFAULT.freeze(null)...它不按结果行rule_fingerprint恢复历史规则。写侧四列已接入，不等于读侧历史版本复算已完成。该剩余工作归F-93及相关backlog」 | ① 写侧 V19/V20 四列已完全落库且不回填历史（`docs/PROJECT_STATUS.md#L2221-L2226`）；<br>② 读侧当前统一绑定快照 `definition_version` 读不可变数据（D-059/D-060, `docs/PROJECT_STATUS.md#L3081`）；<br>③ 动态按指定版本跨规则集重算属于数仓与规则引擎高级扩展能力。 | **C**<br>(长期架构储备) |
| **F-93-02**<br>DEV-004: `SourceRegistryMigrationMySqlIT` 双重必败与收编 | `docs/PROJECT_STATUS.md#L2136`<br>`docs/PROJECT_STATUS.md#L2273`<br>`docs/PROJECT_STATUS.md#L2311`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L252`<br>`scripts/run-tests.ps1#L43` | `PROJECT_STATUS.md` L2136:「`SourceRegistryMigrationMySqlIT` 双重必败 —— context():131-132 同库；EXPECTED_META_SCRIPTS:148-165 止于 V18，而树内已有 V19/V20...」<br>`run-tests.ps1` L43:「`SourceRegistryMigrationMySqlIT` → DEV-004（结构性双必败，未修前不得纳入）」 | ① 2026-10-16 WP-F2 亲跑与裁定（`docs/PROJECT_STATUS.md#L3353-L3354`）：「SourceRegistryMigrationMySqlIT 经查为 P1 期人工取证件（-Dp1.it=true + 副本库指向，fail-closed 设计），与 V25 收编不是同一套机制，裁定维持人工件、不收编」；<br>② 在 `run-tests.ps1` 中永久维持不自动纳入策略。 | **A**<br>(已按总控裁决关闭) |
| **F-93-03**<br>DEV-004: V19/V20 迁移自动化 IT 覆盖缺口 | `docs/PROJECT_STATUS.md#L2136`<br>`docs/PROJECT_STATUS.md#L2273`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L252` | `PROJECT_STATUS.md` L2136:「直接牵连 F-88：V20 迁移在仓库内没有任何 IT 覆盖，D-5『一次误启动即迁移 3306』目前只有静态守卫、无测试守卫。」<br>L2273:「补 V19/V20 的 IT 覆盖（DEV-004，随 F-93）」 | ① `AnalyticsIsolationFlywayIT.java`（`platform-app/src/test/.../AnalyticsIsolationFlywayIT.java#L64-L69`）通过 Flyway 对迁移脚本执行端到端自动化验证；<br>② 在 2026-10-16 WP-F2b 中，版本钉已前移至 meta V38 与 metric V13（`docs/PROJECT_STATUS.md#L3363-L3364`），全面涵盖且大幅超越 V19/V20；<br>③ `run-isolated-tests.ps1` L261-266 原生编排先跑该类，不绿则拒绝后续执行。缺口已 100% 闭合。 | **A**<br>(已被 WP-F2b 事实覆盖关闭) |
| **F-93-04**<br>DEV-003d: 分析侧写入型 IT 收编 (`MetricAdsMySqlIT` 等 3 类 7 例) | `docs/PROJECT_STATUS.md#L2100`<br>`docs/PROJECT_STATUS.md#L2214`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L253` | `PROJECT_STATUS.md` L2214:「DEV-003d DEFERRED（转开发 backlog）：MetricAdsMySqlIT／MetricPublisherMySqlIT...其余未打标 *IT 19 个仍无任何自动入口」<br>《指导书 V3.0》§11 L253:「MetricAdsMySqlIT / MetricPublisherMySqlIT 收编：写入型IT，未收编不允许直接连正式库跑」 | ① 2026-10-16 WP-F2 亲跑确认三类 IT 7/7 PASS（`docs/PROJECT_STATUS.md#L3351`）；<br>② 2026-10-16 WP-F2b（`docs/PROJECT_STATUS.md#L3360-L3366`）确认运行器自带 schema 供给能力，通过 `scripts/run-isolated-tests.ps1 -Module analytics -IncludeAnalyticsWriteIts` 完成收编关账，真跑 13/13 全绿无跳过。 | **A**<br>(已被 WP-F2b 关账覆盖关闭) |
| **F-93-05**<br>DEV-003d: 前端统一测试入口 | `docs/PROJECT_STATUS.md#L2214`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L260`<br>`scripts/run-tests.ps1#L46` | `PROJECT_STATUS.md` L2214:「前端项目级入口（CI 阶段）」<br>《指导书 V3.0》§11 L260:「前端统一测试入口：不妨碍现有模块测试和页面开发」<br>`run-tests.ps1` L46:「前端 web 项目级入口 → 整理阶段范围外」 | 2026-10-16 WP-F3（`docs/PROJECT_STATUS.md#L3355-L3359`）已成功落地 `scripts/run-tests.ps1 -Suite web` 独立档位，执行 `npm test`（450 用例 MATCH）+ `npm run build` 冒烟，退出码 0 关账。 | **A**<br>(已被 WP-F3 关账覆盖关闭) |
| **F-93-06**<br>R-05: `AnalysisGoldenMySqlIT` 库名参数化与处置 | `docs/PROJECT_STATUS.md#L2103`<br>`docs/PROJECT_STATUS.md#L2138`<br>`docs/PROJECT_STATUS.md#L2273`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L255`<br>`scripts/run-tests.ps1#L45` | `PROJECT_STATUS.md` L2103:「总控 2026-09-15 裁决改为 D 类处置 —— 该类依赖只在 3306 存在的历史快照，作为历史黄金值只读复验保留手工／专项执行能力，永久排除 unified isolated-tests；R-05 的参数化本身不再是收口前置」<br>`run-tests.ps1` L45 明确标为排除。 | ① 2026-09-15 总控已明确裁决定性为 D 类；<br>② 全类经查为纯只读验证；<br>③ 调度脚本已固定排除，无需做参数化改造。已具备关闭条件。 | **A**<br>(已按总控裁决关闭) |
| **F-93-07**<br>R-06: 唯一权威配置键名表 | `docs/PROJECT_STATUS.md#L2104`<br>`docs/PROJECT_STATUS.md#L2273` | `PROJECT_STATUS.md` L2104:「唯一权威配置键名表（R-06：`metric.publish.username|metric.read.username` vs `metric.read.user|meta.app.user`）」 | ① `analytics-server/platform-app/src/main/resources/application.yml`（第 10-26 行）已统一使用 `spring.datasource.meta` 与 `spring.datasource.metric` 规范命名，并注入 `PLATFORM_META_*` / `PLATFORM_METRIC_*` 环境变量；<br>② 隔离测试守卫统一映射为 `V25_IT_METRIC_DB`、`V25_IT_METRIC_READ_USER` 等标准契约（`scripts/run-isolated-tests.ps1#L283-L308`）。不一致历史隐患已解决。 | **A**<br>(已闭环可直接关闭) |
| **F-93-08**<br>3307 隔离实例遗留库与受限账号清理机制 (`-AllowedCleanDbs`) | `docs/PROJECT_STATUS.md#L2106`<br>`docs/PROJECT_STATUS.md#L2109`<br>`docs/PROJECT_STATUS.md#L2214`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L258` | `PROJECT_STATUS.md` L2106:「隔离实例遗留库／账号清理（总控已裁：F-93 完成后走 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）」<br>L2109:「3307 上共 4 个 dev003c% 库...仍按 :60 走 F-93 后的 -AllowedCleanDbs 白名单」<br>《指导书 V3.0》§11 L258:「3307 历史测试对象清理：需已批准白名单与范围，不手写无约束DROP」 | ① 当前 `scripts/run-isolated-tests.ps1`（第 66-82 行）与 `scripts/run-tests.ps1` **均未实现** `-AllowedCleanDbs` 参数；<br>② 历史 runId 在 3307 积累的对象处于未清理状态；<br>③ 需要一个小实施包落地严格受限的白名单清理机制。 | **B**<br>(需小实施包) |
| **F-93-09**<br>`SparkStageExecutorSmokeIT` 专项冒烟 | `docs/PROJECT_STATUS.md#L2100`<br>`docs/PROJECT_STATUS.md#L2214`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L254`<br>`scripts/run-tests.ps1#L44` | `PROJECT_STATUS.md` L2100/L2214:「SparkStageExecutorSmokeIT（Spark 专项冒烟 backlog）」<br>《指导书 V3.0》§11 L254:「SparkStageExecutorSmokeIT：真实spark-submit专项，不用111纯测试冒充」<br>`run-tests.ps1` L44:「SparkStageExecutorSmokeIT → Spark 专项冒烟 backlog（真实 spark-submit，Windows 路径硬编码）」 | ① 该类内部依赖真实 `spark-submit` 外部进程及 Windows 本地路径（`SparkStageExecutorSmokeTest.java:32,39,65`）；<br>② 属于独立 Spark 部署/集群验收范畴，不属于日常单元/隔离测试；<br>③ 已转外部依赖/专项 backlog。 | **C**<br>(外部依赖/专项积压) |
| **F-93-10**<br>其余未打标 `*IT` (约 12 个历史非数据库/架构边缘 IT) | `docs/PROJECT_STATUS.md#L2100`<br>`docs/PROJECT_STATUS.md#L2213`<br>`docs/PROJECT_STATUS.md#L2214`<br>`docs/guidance/项目完整实施指导书 V3.0.md#L251` | `PROJECT_STATUS.md` L2213:「其余未打标 `*IT` 19 个仍无任何自动入口（含结构性必败的 `SourceRegistryMigrationMySqlIT`，属 DEV-004）」<br>注：原 19 个中已扣除 7 个分析写入 IT（WP-F2b 已收编）与 `SourceRegistryMigrationMySqlIT`（WP-F2 裁定不收编）。 | 扣除已收编的 7 个写 IT（WP-F2b）、1 个 P1 人工件（WP-F2 DEV-004）、1 个 D 类黄金值 IT（R-05）和 1 个 Spark 冒烟 IT（F-93-09），剩余约 12 个历史未打标 IT（如各类 mock/离线环境 IT）。在 V3.0 体系下已由单元测试或平台新测试矩阵替代，维持非阻塞 backlog。 | **C**<br>(维持长期 Backlog) |

---

## 3. 四大关键线索逐项核实与证据链分析

### 线索 1：读侧按指定版本复算（历史规则版本解释）
- **核实结论**：**部分被现有只读快照机制吸收，完整动态复算属远期规划（类别 C）**。
- **原始出处与表述**：
  - 《项目设计文档 V3.0》§12.4 第 520 行：
    > `当前实现差异：DataQualityGate.decisionForRun(runId)仍采用QualityRuleCatalog.DEFAULT.freeze(null)，显式重载可接调用方冻结集；它不按结果行rule_fingerprint恢复历史规则。写侧四列已接入，不等于读侧历史版本复算已完成。该剩余工作归F-93及相关backlog；F-88仍限定验收，不阻塞无关业务开发。`
  - `docs/PROJECT_STATUS.md#L2101`, `L2273`。
- **现状代码证据与对比**：
  1. **写侧已闭环**：WP-F4 与 F-88 写侧闭环实证（`docs/PROJECT_STATUS.md#L2221-L2226`）证明，`data_quality_result` 表已稳定支持 `rule_version`、`rule_checksum`、`declared_severity`、`effective_severity` 4 列，且遵循不可变追加原则，不篡改历史行。
  2. **读侧现状**：在当前的指标查询契约中，指标快照与分析服务严格按不可变 `snapshotId` + `definition_version` 消费（如 D-059, D-060, `docs/PROJECT_STATUS.md#L3081`）。平台已支持重算触发接口（`POST /api/v1/admin/pipeline-runs/recalculate`，`docs/PROJECT_STATUS.md#L949`），但该重算是对新运行重新跑规则，并非在已有历史快照上按指定旧规则动态重算。
  3. **处置判定**：此项设计不阻塞当前业务与答辩要求，按《指导书 V3.0》第 11 节定性为长期架构演进需求，无需在本地小工包中改动。

---

### 线索 2：DEV-004 双重必败与 V19/V20 覆盖缺口
- **核实结论**：**已完全闭环，建议直接关闭（类别 A）**。
- **原始出处与表述**：
  - `docs/PROJECT_STATUS.md#L2136`：
    > `DEV-004（继续归入 F-93）：SourceRegistryMigrationMySqlIT 双重必败 —— context():131-132 同库；EXPECTED_META_SCRIPTS:148-165 止于 V18，而树内已有 V19__quality_rule_definition.sql / V20__data_quality_result_rule_version.sql。直接牵连 F-88：V20 迁移在仓库内没有任何 IT 覆盖，D-5「一次误启动即迁移 3306」目前只有静态守卫、无测试守卫。`
- **现状代码证据与总控裁定**：
  1. **关于 `SourceRegistryMigrationMySqlIT`（条目自身）**：
     - 在 2026-10-16 WP-F2 亲跑中，总控核查了该类设计并做出终局裁决（`docs/PROJECT_STATUS.md#L3353-L3354`）：
       > `SourceRegistryMigrationMySqlIT 经查为 P1 期人工取证件（-Dp1.it=true + 副本库指向，fail-closed 设计），与 V25 收编不是同一套机制，裁定维持人工件、不收编。`
     - 这一裁定直接关闭了将该测试收编入自动化套件的要求。
  2. **关于 V19/V20 迁移的自动化 IT 覆盖缺口（派生安全要求）**：
     - 在平台工程演进中，Flyway 迁移的自动化集成测试已由 `AnalyticsIsolationFlywayIT` 承接（位于 `analytics-server/platform-app/src/test/java/com/graduation/analytics/isolation/AnalyticsIsolationFlywayIT.java`）。
     - 2026-10-16 WP-F2b 关账记录（`docs/PROJECT_STATUS.md#L3363-L3364`）明确证实：
       > `迁移头版本钉纠正：AnalyticsIsolationFlywayIT 的 meta 版本钉 "34" 为 V34 时代所写，仓库迁移头已至 V38...钉更新为 "38"...metric 钉 "13" 仍为当前头未动...analytics-schema exit 0（Flyway IT 2/2）`
     - 查看代码 `AnalyticsIsolationFlywayIT.java` 第 64-69 行：
       ```java
       assertMigrationHead(
           metaDataSource,
           "analytics_meta",
           "38",
           "V38__business_report_instance.sql");
       ```
     - 自动化 IT 对 Flyway 迁移的覆盖不仅包含了 V19/V20，而且覆盖到了最新的 V38，且被 `scripts/run-isolated-tests.ps1` 原生编排在隔离测试的最前端执行（第 261-266 行）。
  3. **处置判定**：DEV-004 的两部分历史关切（IT 收编 + 迁移测试覆盖）已分别被总控裁决与 WP-F2b 事实覆盖，已具备完整关闭条件。

---

### 线索 3：3307 隔离实例遗留对象清单与 `-AllowedCleanDbs` 白名单机制
- **核实结论**：**缺少运行器清理参数，属于真实实施缺口（类别 B）**。
- **原始出处与表述**：
  - `docs/PROJECT_STATUS.md#L2106`：`隔离实例遗留库／账号清理（总控已裁：F-93 完成后走 -AllowedCleanDbs 白名单，禁止手写 DROP）。`
  - `docs/PROJECT_STATUS.md#L2109`：`隔离实例上按 runId 累积的库与受限账号（DEV-003c 收口轮新增 dev003c_20260915_1150／dev003c_20260915_1205，3307 上共 4 个 dev003c% 库）：未清理，仍按 :60 走 F-93 后的 -AllowedCleanDbs 白名单。`
  - `docs/PROJECT_STATUS.md#L2214`、`docs/guidance/项目完整实施指导书 V3.0.md#L258`。
- **登记在案的遗留库与账号清单（纯文档/脚本静态采集，禁止连库）**：
  根据 `docs/PROJECT_STATUS.md` 倒序工作记录及 `scripts/it-prepare-isolation.ps1` 命名规范，登记在案的 3307 历史隔离对象包括：
  1. **显式登记的 4 个 `dev003c%` 历史库（DEV-003c 遗留）**：
     - 数据库：`dev003c_20260915_1150_mall`、`dev003c_20260915_1150_generator`、`dev003c_20260915_1205_mall`、`dev003c_20260915_1205_generator`
     - 对应受限账号（按 `isolation-naming.ps1` 生成）：`dev003c_20260915_1150_mallapp`、`dev003c_20260915_1150_genapp`、`dev003c_20260915_1205_mallapp`、`dev003c_20260915_1205_genapp`
  2. **历次验收与修复登记的历史 RunId 关联库/账号**：
     - `dev002sem_20260914_2035_*`（`PROJECT_STATUS.md#L2115`）
     - `dev003a_20260915_1040_*`（`PROJECT_STATUS.md#L2127`）
     - `dev003_20260915_1110_*`（`PROJECT_STATUS.md#L2127`）
     - `stage7q1_20260918_152245_*`（`PROJECT_STATUS.md#L2346`）
     - `live20261016_*`（`PROJECT_STATUS.md#L3340`）
     - `acc20261016a` / `acc20261016b`（`PROJECT_STATUS.md#L3340`）
     - `wpf2acc20261016`（`PROJECT_STATUS.md#L3351`）
     - `wpf3fix20261016`（`PROJECT_STATUS.md#L3358`）
     - `fix4acc20261016`（`PROJECT_STATUS.md#L3364`）
     - 数据库名后缀：`*_mall`、`*_generator`、`*_analytics_meta`、`*_analytics_metric`
     - 用户名规则：`New-IsolationUserName -RunId <RunId> -Role <mallapp|genapp|metaapp|metricapp>`
- **运行器当前机制核查**：
  - 静态检查 `scripts/run-isolated-tests.ps1` 第 66-82 行参数声明：
    ```powershell
    param(
      [Parameter(Mandatory = $true)][string]$RunId,
      [ValidateSet('mall', 'generator', 'analytics', 'both', 'all')][string]$Module = 'both',
      [Alias('Host', 'HostName')][string]$DbHost = '127.0.0.1',
      [int]$Port = 3307,
      [int[]]$AllowedPorts = @(3307),
      [string]$InstanceUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
      [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
      [string]$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd',
      [string]$MavenRepoLocal = 'D:\maven_repository',
      [string]$LogDir = '',
      [switch]$IncludeAnalyticsWriteIts,
      [switch]$DryRun,
      [switch]$Confirm
    )
    ```
    脚本中**完全没有 `-AllowedCleanDbs` 参数**，亦无任何 `DROP DATABASE` 或 `DROP USER` 逻辑。
  - 静态检查 `scripts/it-prepare-isolation.ps1`：仅有基于 `IF NOT EXISTS` 的创建逻辑（第 103-125 行），没有删除逻辑。
- **白名单清理机制预期设计（为后续小实施包制定规约）**：
  1. 必须依循总控防误删红线：禁止在交互终端手写裸 SQL `DROP DATABASE`，禁止连接 3306。
  2. 在 `scripts/run-isolated-tests.ps1` 或独立辅助脚本（如 `scripts/it-clean-isolation.ps1`）中提供受控清理开关：
     - 入参：`-AllowedCleanDbs <string[]>` 或 `-CleanRunId <string>`。
     - 严格正则门禁：库名必须严格匹配 `^([a-zA-Z0-9_-]+)_(mall|generator|analytics_meta|analytics_metric)$`，账号名必须匹配隔离生成规范。
     - 端口严格门禁：端口必须且仅允许为 3307，拒绝在 3306 上执行任何清理。
     - 二次确认门禁：要求显式输入 `-Confirm`。

---

### 线索 4：未打标 *IT、SparkStageExecutorSmokeIT、R-05 与 R-06
- **核实结论**：
  1. **未打标 IT 中最具价值的 3 类 7 例写侧 IT**：已由 WP-F2b 成功收编入 `run-isolated-tests.ps1 -IncludeAnalyticsWriteIts`，在隔离双库上 13/13 全绿验证。
  2. **`SparkStageExecutorSmokeIT`**：包含真实集群执行与路径硬编码，已从日常统一套件中剥离，转入 Spark 专用专项冒烟 backlog（类别 C）。
  3. **R-05 (`AnalysisGoldenMySqlIT`)**：总控裁定为 D 类只读复验，永久排除出日常测试（`scripts/run-tests.ps1#L45`），无需改造（类别 A）。
  4. **R-06 (唯一权威键名表)**：`application.yml` 与环境上下文已完成收敛，键名冲突已消除（类别 A）。

---

## 4. 结论与分类处置矩阵 (Master Disposition Matrix)

根据对 F-93 涉及各条目的盘点与证据核实，建议将所有条目分类处置如下：

### 分类 A：本地可直接关闭（已有确凿证据 / 已被后续工作完整覆盖）

本类条目无需任何额外代码修改，可由总控依据已有事实与证据在治理台账中直接声明关闭。

| 条目编号 | 条目名称 | 闭环证据索引 | 最小处置动作 | 风险点评估 |
|---|---|---|---|---|
| **F-93-02** | DEV-004 `SourceRegistryMigrationMySqlIT` 自动化收编 | `docs/PROJECT_STATUS.md#L3353-L3354`<br>`scripts/run-tests.ps1#L43` | 在 F-93 台账中正式登记 WP-F2 裁决（作为 P1 手工件，永久不收编进自动化套件），关闭该项收编要求。 | 无代码风险。需确保任何人在修改统一测试入口时不得误将该测试重新引入。 |
| **F-93-03** | DEV-004 V19/V20 迁移自动化 IT 覆盖缺口 | `platform-app/src/test/.../AnalyticsIsolationFlywayIT.java#L64-L69`<br>`docs/PROJECT_STATUS.md#L3363-L3364` | 确认 Flyway 自动化 IT 覆盖已演进至 V38，登记关闭 V19/V20 覆盖缺口。 | 无风险。已有 `AnalyticsIsolationFlywayIT` 及其版本钉门禁守护。 |
| **F-93-04** | DEV-003d 分析侧写入型 IT 收编 (`MetricAdsMySqlIT` 等) | `docs/PROJECT_STATUS.md#L3364`<br>`scripts/run-isolated-tests.ps1#L79` | 确认 WP-F2b 已关账（通过 `-IncludeAnalyticsWriteIts` 实现 13/13 全绿），正式从 backlog 中注销。 | 无风险。功能已集成进运行器。 |
| **F-93-05** | DEV-003d 前端统一测试入口 | `docs/PROJECT_STATUS.md#L3355-L3359`<br>`scripts/run-tests.ps1#L35, L58` | 确认 WP-F3 已完成关账（`run-tests.ps1 -Suite web`），正式从 backlog 中注销。 | 无风险。已作为独立测试套件平稳运行。 |
| **F-93-06** | R-05 `AnalysisGoldenMySqlIT` 库名参数化 | `docs/PROJECT_STATUS.md#L2103`<br>`scripts/run-tests.ps1#L45` | 引用 2026-09-15 总控 D 类裁定（永久排除出统一隔离入口，保留手工专项能力），宣布该参数化要求关闭。 | 无代码风险。避免误删该测试源码。 |
| **F-93-07** | R-06 唯一权威配置键名表统一 | `application.yml#L10-L26`<br>`scripts/run-isolated-tests.ps1#L283-L308` | 确认配置与环境变量规范化已完成，宣布 R-06 治理项关闭。 | 无风险。生产与测试配置均已对齐。 |

---

### 分类 B：需小实施包 (Requires Small Work Package)

本类条目在代码/脚本实现层面存在真实缺口，需立项小工作包完成实施与受控验证。

| 条目编号 | 条目名称 | 实施缺口简述 | 最小处置动作清单 | 风险点与防范控制 |
|---|---|---|---|---|
| **F-93-08** | 3307 隔离实例遗留对象清理机制 (`-AllowedCleanDbs`) | 运行器 `scripts/run-isolated-tests.ps1` 尚无 `-AllowedCleanDbs` 白名单清理参数，3307 上积累了以 `dev003c%` 为代表的多个历史库与受限账号未清理。 | 1. **设计/编写清理功能**：在 `scripts/run-isolated-tests.ps1` 中新增 `-AllowedCleanDbs <string[]>` 开关（或创建配套的 `scripts/it-clean-isolation.ps1`）；<br>2. **实施硬门禁**：<br>   - 端口必须且只能为 3307，硬拒 3306；<br>   - 库名必须满足正则 `^[A-Za-z0-9_-]+_(mall|generator|analytics_meta|analytics_metric)$`；<br>   - 禁止删除系统库（`information_schema`、`mysql`、`performance_schema`、`sys`）；<br>   - 必须传 `-Confirm`；<br>3. **执行清理与验证**：指定白名单列表（如 `dev003c_20260915_1150_%`, `dev003c_20260915_1205_%`）实施清理并复验 3307 实例干净度。 | **高风险（误删防范）**：<br>- 严格锁定 3307 端口与实例指纹，彻底隔绝宿主 3306；<br>- 严格校验白名单前缀，绝不允许 `DROP DATABASE *` 或拼接任意未校验字符串；<br>- 执行前打印拟删除对象清单（What-If 模式）。 |

---

### 分类 C：转外部依赖 / 长期登记 (External Dependency / Long-Term Backlog)

本类条目由于依赖外部重型环境（如 Spark 集群）或属于远期架构规划，不适宜作为当前单机 VM 的阻塞项，建议明确边界后转入长期跟踪。

| 条目编号 | 条目名称 | 现状与性质 | 最小处置动作清单 | 风险点评估 |
|---|---|---|---|---|
| **F-93-01** | 读侧按指定版本复算 | 属于多版本历史数据治理与动态规则复算引擎能力。写侧四列已按契约落库，读侧当前通过不可变快照保证一致性。 | 在《设计文档》中保持 §12.4 现状登记，注记当前以不可变快照与显式触发作为替代方案，转为远期数仓特性。 | 无现实风险。不影响当前的毕业设计指标计算与 AI 证据生成。 |
| **F-93-09** | `SparkStageExecutorSmokeIT` 真实冒烟 | 强依赖真实 Spark 集群调度与 Windows 本地绝对路径，不适合纳入常规回归入口。 | 保持在 `run-tests.ps1` 中的排除注记，保留在 Spark 独立专项冒烟清单中。 | 无风险。日常测试由 111/111 或 372/372 ScalaTest 守护 Spark 计算逻辑。 |
| **F-93-10** | 其余历史未打标 IT (约 12 个) | 历史遗留边缘/离线用例，在 V3.0 多模块架构下已被各模块单元测试与统一测试矩阵覆盖。 | 在 `PROJECT_STATUS.md` 中注销其对主干验收的挂起状态，归入已废弃历史测试归档。 | 需确认无关键断言丢失（已由当前 1540 + 55 + 372 综合套件全面覆盖）。 |

---

## 5. 最终处置建议路径与执行建议

为彻底收敛历史积压 `F-93`，建议总控采取如下两步走执行策略：

1. **第一步（声明关闭与口径清算，无需写代码）**：
   - 依据本报告第 4 节清单，对**分类 A** 中的 6 个条目（F-93-02、F-93-03、F-93-04、F-93-05、F-93-06、F-93-07）正式在 `docs/PROJECT_STATUS.md` 中声明**关闭（CLOSED）**。
   - 对**分类 C** 中的 3 个条目（F-93-01、F-93-09、F-93-10）声明**转为外部/长期归档（DEFERRED / ARCHIVED）**，解除其对 F-93 整体进度的挂载。
2. **第二步（立项 WP-F5b 实施包，受控落地 `-AllowedCleanDbs`）**：
   - 为**分类 B**（F-93-08）立项微型工作包（如 `WP-F5b`），专门在 `scripts/run-isolated-tests.ps1` 或独立辅助脚本中实现白名单校验与受控清理逻辑。
   - 在总控亲验并清理 3307 历史 `dev003c%` 库及账号后，正式关闭 F-93 全部条目，实现历史治理的最终关账。
