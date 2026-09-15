# PROJECT_STATUS

> 当前阶段：**历史整理阶段已结束；项目正式进入毕业设计功能开发阶段。**
> 最后更新时间：2026-09-15，总控批准V3.0正式发布与状态提交。
> 当前代码基线：`e3c1070c02964512cdd3444c76e3e39ec833bcc8`（业务/测试/runner基线，不随本轮文档提交变化）。
> 当前治理基线（V3_RELEASE_COMMIT）：`7e8de648f1ff0d7306bbecdedeca985218229fe6`。
> 当前指导书：`docs/guidance/项目完整实施指导书 V3.0.md`，见[指导书](guidance/项目完整实施指导书%20V3.0.md)。
> 当前设计：`docs/design/项目设计文档 V3.0.md`，见[设计文档](design/项目设计文档%20V3.0.md)。
> 发布状态：V3.0正式文档已提交并冻结；本状态文件单独提交后统一推送origin/main。业务代码、测试、runner及历史acceptance不变；最终状态提交哈希与远端确认见本轮交付报告。

## 当前摘要（V3.0发布后适用）

本节是当前状态；下方折叠区保留发布前状态原文及全部问题、证据、工作记录。历史原文中的“当前”“本轮未提交”“DEV-003开启”“等待创建状态文件”等只代表其原记录时点，不覆盖本节及V3.0总控裁决。

指导书和设计V3.0仅总控有决策/发布权，发布后只读；下一版各为V3.1。代码Agent可在工作包内改业务/测试、跑测试、新建必要脱敏evidence及更新本文件；不可自行改变目标/范围/架构、提升backlog或宣布完整验收。本文件不设版本号，不建并行看板。

### 当前阶段与下一工作

按八阶段功能开发推进：1基线与核心链确认 → 2采集/数仓 → 3Spark指标 → 4Spring Boot服务 → 5Vue → 6AI → 7业务实链联调 → 8部署/验收/论文答辩。

下一工作先确认阶段1的运行档案和允许范围，复用现有fresh测试基线，不重开历史测试整理。首选业务切片为来源确定性映射/采集到数仓，任务规格见设计§7–10；冻结接口后服务/页面可并行。新一轮代码任务尚未在本发布轮启动。

### 阶段2 进展（代码Agent 实测登记，2026-09-15）

- 已交付（分支 `feature/v3-development`，**未 merge main**）：S2-01A `26b083d`、S2-01B `76e3032`、S2-02A `23d781b`、S2-02B `a673e32`、S2-03 `beddc3e`、S2-03.1 `849ef66`、S2-04A `a754a0e`、S2-04B `bb383ae`、**S2-05 `3c357fb`（ODS/DIM/DWD 主链：DWD 去重键复合化 + `bdw` 阶段序 `dim` 先 + Kahn 真检环，本轮）**；映射核心/干跑预览/真实采集接线/批次重放口径/预览→激活五段、"预览→激活"落库闭环、"输入归属"、"采集面"、以及"DWD 去重键与阶段序"均已提交。另：计数口径修复 `7f49055`、`origin/main` 文档重组同步 `d89bd20`。
- **S2-05 本轮实测（fresh，双档真跑；spark 日志 `.verify/v3-stage2/s2-05/s205-dwd-dedup-key/gate-spark/console.log`、default 同目录 `gate-default/console.log`）**：**spark 档 `[PASS exit=0]`** —— `TestSuite.txt`：`Total number of tests run: 143`、`Suites: completed 17, aborted 0`、`succeeded 143, failed 0`、`新写=True`、`JDK8=True`、`tests=143 MATCH`。**default 档计数全 MATCH、`[FAIL exit=7]`** —— analytics-server `872 (F=1 E=0 S=1)` 明细 `90+350+148+48+91+145`、mall `13`、generator `106`、三棵树 `991`（基线 991）、无 DRIFT。基线登记 `871/990/spark=111` ⇒ **`872/991/spark=143`**（+1/+1/+32，全部落在 `warehouse-pipeline` +1 与 `spark-jobs` +32）。唯一红仍是上述已登记环境性用例（platform-app F=1），本轮**未修、未复制 manifest、未用开关掩盖**（`-AllowCountDrift` 只用于改基线前的量数那两次），无新增红。
- **S2-05 修掉的两个真缺陷（各带独立 RED，见 F-32）**：① **DWD 去重键是单键** ⇒ 契约逐字写的 `(source_instance_id, event_id)` 复合键**未实现**：同 `event_id` 分属两个源实例时互相去重丢行，跨源同名被**误判为重复**丢进拒绝记录（RED：跨源同名 `1 was not equal to 2`、`oracle=17 actual=14`、拒绝记录 `5 was not equal to 2`）。② **`bdw` 跑在 `dim` 之前**（执行器阶段序 `bdw,tdw,dim` + 注册表前置缺 `dim`）⇒ 行为明细**14/14 行全部丢失维度补全**（`city_level` NULL、`category_id` −1、`category_key` NULL），而**行数/代理键/拒绝数/订单指标一律不变** ⇒ **静默降级**；`JobRegistry` 的 `hasCycle` 原为 `false // 首批无环` 恒假断言，与设计 §10.1 **L375**「扩展 DAG 时必须用 Kahn 拓扑排序或 DFS 检测真实环，不能沿用假定」直接冲突，已换为真实现。
- **能力边界（S2-05 新增，不得越界表述）**：① **跨源去重正确性的上限＝`DONE_LIMITED`**（裁决 D-126）：真库是**单源**，跨源性质只由**构造夹具 + 本地链**证明 —— 黄金 55 行只读夹具真跑 `odl`，再把 3 个行为行整行复制成第二个源实例（同 `event_id`、只覆盖注入列 `source_system`）；**不得**据此声称单源真库的跨源去重正确。② 全部证据是 Scala `local[1]` + `catalogImplementation=in-memory`：**未跑真实 `spark-submit`、未连 Hive metastore**。③ **DWD 表不加 `source_system` 列**（裁决 L81「本裁决不授权任何 DDL」＋D-122「不新增列」），该边界由双向断言钉住（静态 DDL 与 `LocalSchemaInitJob` 派生 DDL 逐列一致、两侧都不含该列）。④ `hasCycle`/`topologicalOrder` **无生产调用点**（不新增启动期失败面），真检环的证据是环/自环/未知前置/合法 DAG 四类夹具的真跑。⑤ `tdw` 夹具**无法**演示跨日迟到重写（run 业务日 == 订单日）。⑥ `isolated` 档本轮**未跑**（3307 无监听）；**仍无 ingestion→pipeline 端到端 IT**。⑦ 本轮 **0 次连库**，未触碰 3306/3307/ACTIVE、未改 `contract-specs/**`、未改已发布迁移、未删任何已发布能力。⑧ 设计 §10.1 L365「`bdw` 前置＝`odl`（不含 `dim`）」与已落地 JOIN 事实冲突 ⇒ 已按指导书 §12 L271 提交**设计差异请求** `docs/acceptance/s2-05-dwd-chain-20260916/DESIGN-DIFF-REQUEST-20260916.md`（**未改 V3.0 正文**）。

- **治理基线（2026-09-16 起，权威）**：**默认自主连续开发 + 仅破坏性变更停工**。旧门「新增 Flyway migration／新表 ⇒ 暂停等总控」**已废止**（F-28 中"持久化子项暂停"的结论自本轮起作废）；真决策门收窄为 11 条破坏性变更（DROP TABLE/COLUMN、改既有字段类型或业务语义、改已发布迁移、写正式 3306、切 ACTIVE、改 `contract-specs/**` 语义、改 V3.0 架构、扩缩项目范围、删已发布能力、长期架构分叉、引入未规划大型组件）。V21 属**加性**迁移，经总控明确批准后落地。详见 F-29。
- 本轮实测（fresh，全量 default 档真 Maven，日志 `.verify/v3-stage2/s2-03.1/r14-default-suite/raw-console.log`）：analytics 六模块 90 / 320（跳过1）/ 134 / 48 / 91 / 138 = **821**；`connection-ingestion` 320 = 上一版 311 + **9**（`MappingProfileLoaderTest` 删 1 加 2、`MappingActivationServiceTest` +1、`JdbcActiveMappingPointerStoreTest` +4、`MappingActivationPersistenceConfigTest` +3）、`platform-app` 138 = 127 + **11**（`MappingActivationControllerTest` +1、`SourceMappingActiveMigrationScriptTest` +5、`SourceMappingActivePersistenceContractTest` +5）；default 三棵树 **940** = 821 + 13 + 106。**唯一红**仍是已登记环境性 `IngestionManifestRuntimePatrolTest.realHistoryOnDiskIsUntouched`，本轮未修、未复制 manifest、未用开关掩盖失败（`-AllowCountDrift` 只在量数那一次用）、无新增红。
- **S2-04A 本轮实测（fresh，全量 default 档真 Maven，两次；日志 `.verify/v3-stage2/s2-04/s204a-manifest-source-scope/raw/`：`r4` 改基线前、`r8` 最终修订）**：analytics 六模块 90 / 320（跳过1）/ **147** / 48 / 91 / 138 = **834**；`tests=834 MATCH`、mall 13 MATCH、generator 106 MATCH、default 三棵树 **953**（基线 953）、无 DRIFT。**+13 全部落在 warehouse-pipeline（134→147）**：`LandingManifestSelectorTest` +9、`PipelineServiceTest` +4；其余五模块逐一复核未变。唯一红仍是上述已登记环境性用例（platform-app 138 F=1），本轮未修、未复制 manifest、**未使用 `-AllowCountDrift`**（改基线前后各跑一次全量，漂移与收敛都留原始日志），无新增红。RED→GREEN 与两个变异探针（关掉按源过滤 ⇒ 2 红；把执行器构造提到判定之前 ⇒ 1 红）的原始日志同目录 `r1/r2/r6/r7`，还原后的字节一致性与探针残留自检见 `r9`。
- **能力边界（S2-04A 新增）**：Landing 输入清单现在**只认属于本轮运行源的批次**（清单 `sourceId` == `runtime_profile.source_id`），缺 `sourceId` 的存量清单判为不可归属（不猜、不接受被钉住），他源清单永不入选，本源无可用批次即 `RUN_EMPTY_LANDING`（宁可空跑不装错源）；`runtime_profile.source_id` 为空时以稳定码 `SOURCE_NOT_BOUND` 停在执行器构造之前。**为什么必须如此**：ODS 库名与 `source_system` 唯一来自该源，且 `source_system` 是按 source_code 注入的常量字面量 ⇒ 装错源**事后不可对账**。**边界（不得越界表述）**：①该结论是**选择与编排边界**证据，`stageExecutorFactory` 在单测里是 Mockito 替身，**未跑真实 `spark-submit`、未观测任何 ODS 表内容**；②**无 ingestion→pipeline 端到端 IT**（没有"采集真写清单 → 流水线真装载 → ODS 内容核对"的实链）；③`isolated`（55）与 `spark`（111）两档本轮**未跑**；④本轮 **0 次连库**，未触碰 3306/3307/ACTIVE。
- 计数口径链（2026-09-16）：当前基线 analytics = **872**、default = **991**、spark = **143**；上一版 871/990/111（S2-04B）、834/953（S2-04A）、821/940（S2-03.1）、801/920（S2-03）、773/892（S2-02B）、772/891、764/883（S2-01B 746 + 18）的来历与 `run-tests.ps1` 汇总行漏计解析器缺陷的修复见 F-26/F-29/F-30/F-31/F-32；`scripts/run-tests.ps1` 登记基线已同步为 872/991/143。§测试与验收当前口径表中 default-tests 行（analytics632 … = 751）保留为 2026-09-15 发布轮历史口径，**不再作为当前基线**。
- **S2-04B 本轮实测（fresh，全量 default 档真 Maven，两次；日志 `.verify/v3-stage2/s2-04/s204b-landing-layout/raw/`：`r13` 改基线前（`-AllowCountDrift` 量数）、`r14` 改基线后的门禁跑）**：analytics 六模块 90 / **350**（跳过1）/ 147 / 48 / 91 / **145** = **871**；`tests=871 MATCH`、mall 13 MATCH、generator 106 MATCH、default 三棵树 **990**（基线 990）、无 DRIFT。**+37 只落在两个模块**：`connection-ingestion` 320→350（+30：`LandingInputScannerTest` 6、`LandingLayoutTest` 5、`IngestionLandingLayoutTest` 4、`RuntimeProfileLandingLayoutWriteTest` 6、`FlumeSpoolConfigTest` 9）、`platform-app` 138→145（+7：`RuntimeProfileLandingLayoutMigrationScriptTest`）；其余四模块逐一复核未变（`warehouse-pipeline` 147 保持 S2-04A 口径）。唯一红仍是上述已登记环境性用例（platform-app F=1），**未修、未复制 manifest、未用开关掩盖**（`-AllowCountDrift` 只用于量数那一次），无新增红。**本轮修掉一个自己引入的回归**：`status()` 起初用"完成文件"口径算 `lastArrivalAt`/`pendingFiles`，使既有 `IngestionServiceStatusTest.emptyFileIsNotNew` 变红（零字节文件确实"到达了"）⇒ 拆成候选集（观测）⊋ 完成集（采集）两个口径，`r11` 红 → `r12` 绿。
- **能力边界（S2-04B 新增）**：采集输入面从"只认 `events/*.jsonl`（一层）"放宽为**布局登记表**：`runtime_profile.landing_layout`（V22 加性列，NULL=未配置 ⇒ `ROLLING_LOG`，与 V2 逐字节一致）决定输入根（`ROLLING_LOG`= `events/`；`FLUME_RAW`= `raw/`、递归）；未登记值在**读与写两条路径**都 fail-closed（`PARAM_INVALID`），**不做静默回落**。**完成文件规则**（设计 §8.2 规则 4）由 `LandingInputScanner` 单点拥有：隐藏名、`_` 前缀（**任意路径段**，含 Flume 检查点目录 `.flumespool/`）、`*.tmp`、非普通文件、零字节一律不进**采集**输入；两种布局共用该规则，`ROLLING_LOG` 额外只认 `*.jsonl` 且不递归（设计 L268：Flume 目标 spool 与运行中文件的尾读**不得混为一种配置**，残行等待仍是 `LocalFileIngestor` 既有实现）。`ingestion/flume/flume-spooldir.conf` 已把 §8.2 规则 1/2/3/6 写成可执行配置（Sink 只按 ingest 时间分区到**源级** raw 区、禁 `%{eventType}`、fileSuffix=`.COMPLETED`、绝不删源文件、checkpoint/data 目录分离）。**边界（不得越界表述）**：①**Flume 从未运行**：`flume-spooldir.conf` 的正确性只有**静态门禁**证据（9 例断言配置文本），**没有**任何一次真实 Flume 采集、也没有 WSL spool→HDFS 的实测；②V22 **只存在于代码与测试**，正式库 `analytics_meta` 仍是 V18（本轮未连库），该列在真 MySQL 8 上**未应用过**；③`status()` 的观测字段是同一份枚举的**候选集**，不是"数据源进程还活着"的探活（平台依旧只知道自己多久没收到数据）；④`FLUME_RAW` 下清单 `files[].file` 仍是**文件名**（契约写的是「文件名（非绝对路径）」，改相对路径属契约语义变更 ⇒ 见 F-31 的开放契约问题），因此同名不同分区文件在清单里**会重名**，批次账用相对键不会撞；⑤`isolated`（mall30/generator19/analytics6）与 `spark`（111）两档本轮**未跑**；⑥本轮 **0 次连库**，未触碰 3306/3307/ACTIVE。
- 能力边界（S2-03.1 后，仍有效）：激活生命周期闭环**已具备真实落库能力** —— 干跑报告 → `POST /api/v1/sources/{sourceId}/mappings/activate`（只收 `reportId` + `expectedProfileChecksum`，**不接受新画像**）→ active mapping pointer **写入 MySQL 表 `source_mapping_active`（V21，一源一行、PK `source_id`、FK → `source_registry(id)`）** → `SourceMapper` **只认 active**（磁盘上有 v2 画像但没激活 ⇒ `MAPPING_NOT_ACTIVE`；active 的 checksum 与磁盘字节不符 ⇒ `MAPPING_ACTIVE_PROFILE_DRIFT`；**没有 latest-wins**）。装配由 `MappingActivationPersistenceConfig` 单点决定：有 mapper ⇒ `JdbcActiveMappingPointerStore`，无 ⇒ fail-closed 兜底 + WARN（`MAPPING_ACTIVATION_PERSISTENCE_UNAVAILABLE` = 501 **只剩装配缺失这一种成因**）。幂等判断走 `SELECT … FOR UPDATE`（REPEATABLE READ 快照坑）。信封必填缺陷已修：`CanonicalContractLoader` 改从契约**根节点** `required` 取信封必填表 ⇒ 信封漏映射现在会阻断激活。**边界（不得越界表述）**：①V21 **只存在于代码**，正式库 `analytics_meta` 仍是 V18（本轮未连库、未迁移、未写 3306）；②真库往返/结构/FK **未实测**，新写的 `SourceMappingActiveMySqlIT` **本轮未运行**；③并发 activate 的真实互斥**未测**；④"生产装配下 activate 返回 200"**未做真机（8091）端到端观测**，现有证据是装配层 + L0 语义证据的合成；⑤v1 只读兼容画像（`mock-mall.v1.json`）直通行为**不变**。本轮全部为 L0（未连库），未触碰 3306/3307/ACTIVE，未改 `contract-specs/**` 已发布语义。

### 已完成

| 项 | 当前判定 |
|---|---|
| DEV-001 / DEV-002 | 已修复并实测关闭，原证据保留 |
| DEV-003a | generator新runId先门禁、迁移再测试，首跑问题已关闭 |
| DEV-003b | metric-analysis隔离IT接入统一隔离入口，已关闭 |
| DEV-003c | scripts/run-tests.ps1统一四档入口与fresh基线，已关闭 |
| **DEV-003整体** | **整理阶段收口完成**，不再因DEV-003d后置保持OPEN |
| 三程序基础 | 独立平台、商城、生成器已存在，不等于多源全部完成 |
| F-88写侧/3307证据 | 四列落库与质量阻断已有证据，仍限定验收 |
| V3.0正式文档 | 指导书、设计及两个README已由7e8de648f1ff0d7306bbecdedeca985218229fe6发布；两份正文冻结，后继V3.1 |

### 当前可运行功能及证据边界

历史证据支持本地/隔离采集、Spark八阶段链、MySQL发布、查询和部分页面/AI决策基础。当前HEAD的统一测试已fresh通过；本轮仅文档核对，未重跑业务链、未复查在线端口、不声明三服务正在运行。

分析平台8091、商城8090、生成器8092；WSL隔离MySQL3307。平台无需依赖商城在线才能读取已有快照。Spark local[1]+in-memory测试不是Hive/集群验收；真实模型效果与完整第二来源仍需业务开发取证。

### 测试与验收当前口径

| 档位 | fresh基线 | 证据/限制 |
|---|---|---|
| default-tests | analytics632 + mall13 + generator106 = **751** | JDK17；不含前端和Spark |
| isolated-tests | mall30 + generator19 + metric-analysis IT6 = **55** | 新runId、3307；依赖common89不重复计 |
| spark-tests | **143** | JDK8/ScalaTest，以本轮新写TestSuite.txt为准 |
| all-tests | default→isolated→spark，fresh exit0 | 任一档失败或0 tests整体失败 |

来源：`docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md`及raw。上述是既有fresh结果，不是文档轮复跑。旧303/46/54等不再是当前基线。统一入口实际CLI为-Suite default|isolated|spark|all，计数漂移须总控批准，不能自行放宽。表中 default-tests/isolated-tests 两行（751/55）仍是 2026-09-15 发布轮历史口径；spark-tests 行已按 S2-05 的 fresh 实测更新为 **143**（发布轮值为 111），当前三项基线见上方"计数口径链"。

**F-88仍限定验收；完整验收未宣布。** 将完整剩余转backlog不等于证据补齐，也不应阻止无关功能开发。旧五条硬理由/八项未取证留历史区按各自时点理解，后续逐条补证时不得简单清空。

### Development backlog（非正式开发前置）

| 项 | 状态 |
|---|---|
| DEV-003d | development backlog |
| DEV-004 | development backlog，迁移IT（现覆盖 V19/V20/V21）与真库往返 |
| S2-03.1 真库取证未跑（`SourceMappingActiveMySqlIT`：表结构/FK/upsert 覆盖语义/锁定读） | development backlog，需副本库 + 受限账号，禁止直连正式库 |
| V21/V22 真库未应用（正式库仍 V18；`source_mapping_active`、`runtime_profile.landing_layout` 在真 MySQL 8 上**未观测过**） | development backlog，归 DEV-004；需副本库 + 受限账号，禁止直连正式库 |
| Flume **从未运行**（`flume-spooldir.conf` 只有静态门禁证据；WSL spool→HDFS 的 §8.2 规则 1/2/6 未实测） | development backlog，需 WSL Flume 环境；不得以配置文本推断"采集可用" |
| `FLUME_RAW` 下清单 `files[].file` 仍是**文件名**（同名不同分区会重名；批次账已用相对键） | **开放契约问题**，需总控裁定：契约写「文件名（非绝对路径）」且引用 `LocalFileIngestor.java:189`，改相对键属契约语义变更（F-30 口径 ⑥）⇒ 未擅自改；回退是单表达式改动 |
| 遗留 `ingestion/flume/flume-taildir.conf` 的 Sink 路径含 `%{eventType}`（与平台侧扁平 `events/*.jsonl` 读取口径不一致，且不得改写进 `raw/` 与 spool 语义混淆） | development backlog，处置（退役/重写）待定；本轮**未改**该文件，S2-04B 的合规配置是新文件 `flume-spooldir.conf` |
| `runtime_profile.update` 无法清空可空列（`updateById` 跳过 null）⇒ `landing_layout` 一旦设置无法改回"未配置" | development backlog，既有实现口径，本轮未改 |
| 并发 activate 的真库互斥未测（`FOR UPDATE` 只有 L0 替身证据） | development backlog，同上，归 DEV-004 下一步 |
| 激活不校验画像自声明的 `sourceCode`（采集侧会以 `MAPPING_PROFILE_INVALID` 拒绝） | development backlog，属已登记能力边界 |
| repo 根查找已有第四份本地实现（`RepoRoot` 提到 test-jar 后删除四处副本） | development backlog |
| MetricAdsMySqlIT / MetricPublisherMySqlIT收编 | development backlog，禁止未经隔离直连正式库运行 |
| SparkStageExecutorSmokeIT | development backlog，真实Spark专项 |
| AnalysisGoldenMySqlIT历史复验 | development backlog，D类手工/专项只读，排除统一isolated |
| F-88完整验收剩余项 | development backlog，维持限定验收 |
| F-93清理 | development backlog，历史读侧规则等未完成 |
| 3307历史测试对象清理 | development backlog，未执行清理 |
| GitHub Actions | development backlog |
| 前端统一测试入口 | development backlog，现有模块测试仍可用 |
| 设计 §10.1 L365「`bdw` 前置＝`odl`（不含 `dim`）」与已落地 JOIN 事实冲突（P2-03 起 `behaviorClean` LEFT JOIN `dim_user`/`dim_product`） | **已提交设计差异请求**（`docs/acceptance/s2-05-dwd-chain-20260916/DESIGN-DIFF-REQUEST-20260916.md`），待总控裁定是否更正 L365 或确立"隐式依赖必须显式登记"规则；实现已按 §10.1 L375 授权口径落地 |
| 契约别名映射「`source_instance_id` ⇔ 物理 `source_system`」（裁决 D-122 L34）目前只在实现 KDoc 里 | development backlog；若要进 **契约文本** 属门 ⑥（改已发布契约语义）⇒ 只能由总控侧改，Code Agent 不擅改 |
| `DwdSql.duplicateReject` 的 population 缺 `schema_version='1.0'` / 枚举白名单 / 非空过滤（与 `behaviorClean` 不一致）⇒ 拒绝记录会把 DWD 从未考虑过的行也算成重复 | development backlog（**口径漂移**），本轮未改；修它须同时定义"拒绝数"的对账语义 |
| DWD 去重 `ORDER BY ingest_time` **无稳定平局裁决**（同 `ingest_time` 时留哪一行不确定） | development backlog，本轮未改（实测未观测到非确定性，但缺 tiebreaker 是事实） |
| `PipelineServiceTest` 从不校验 `INIT_SCHEMA` 阶段（断言覆盖缺口，INIT_SCHEMA 存在性只有间接证据） | development backlog，本轮未改 |
| `JobRegistry.hasCycle`/`topologicalOrder` 已真实现但**无生产调用点**（编排入口未做启动期 fail-fast） | development backlog，本轮刻意不加（不新增启动期失败面），待总控定"是否在编排入口 fail-fast" |

仅总控可在真正阻塞当前开发阶段时提升。代码Agent先报告受影响交付物、复现和最小修复，不自行返回治理泳道。

### 当前阻塞与安全约束

- **无新增阻塞本轮文档发布的问题。** 功能任务的实际阻塞在领取时按环境/输入核查，不虚构全部就绪。
- 3306新写入/迁移/ACTIVE切换仍冻结；D-5启动前全DataSource门禁未完整，不因测试入口已完成而解除。
- 3307历史库/账号不自动清理；旧F-93、P-01及其他问题全部保留在历史记录中，不因文档收敛删除。
- G-01～G-07既有待裁决：WSL独立验收门、AIW学校范围、profile换源历史隔离、漏斗粒度、RFM退款窗口、Linux主目录切换、学校期限/模型预算/外部样例授权。触及才提交总控，不阻塞无关任务。
- AI阶段6只消费稳定ADS/指标快照，不扫描DWD；AIW未来方向不能作为扩大权限的依据。

### 本版本代码/设计变更

本轮**零代码变更、零测试/runner变更、零历史acceptance变更**。新增两份V3.0正式文档，更新README.md、docs/README.md与本文件；修改前三份原件已复制到`docs/backups/v3-release-20260915/`。

设计范围按总控指令切至八阶段功能开发；保留有效数据/模块规格，区分目标与当前实现；将历史测试治理残留移入非阻塞backlog。未移动、删除、覆盖任何历史指导书、设计稿、看板或证据。总控现已批准两次显式提交及统一推送；V3.0正文不再改动。本轮临时backup不提交，仅在推送成功且远端确认后按授权清理；清理结果见交付报告。

### 后续每轮更新格式

任务ID/所属阶段；输入及允许文件；完成/未完成；代码HEAD与工作区；测试档位/用例数/失败跳过/证据；可运行范围；问题与待裁决；下一小步。只报告事实，完整验收由总控决定。正式设计改变需提交差异请求，不自行修改V3.0。

## 最近工作记录（V3.0起）

- 2026-09-15｜总控批准发布｜Commit 1为7e8de648f1ff0d7306bbecdedeca985218229fe6，仅README.md、docs/README.md及两份V3.0正式文档；Commit 2仅本状态文件，区分代码基线与治理基线。DEV-003整理阶段收口、DEV-003d development backlog、F-88限定验收不变；不改历史验收结论，不启动任何开发或历史整理任务。
- 2026-09-15｜总控文档发布轮｜核对HEAD/origin/main=e3c1070c02964512cdd3444c76e3e39ec833bcc8，初始工作区干净；发布指导书V3.0/设计V3.0和三文件入口；DEV-003整理收口、DEV-003d等转backlog、F-88限定不变；无代码/测试/runner/历史证据修改；不提交不推送。

## 发布前历史状态与问题（只作追溯）

<details>
<summary>展开V3.0发布前PROJECT_STATUS原文（全部问题和工作记录保留，不作为当前阶段指令）</summary>

# PROJECT_STATUS

指导书基线：V2.8（`docs/毕业设计指导书 V2.8.md`，300 行 / 30,565 B / sha256 `00CE7AA9EAEB57A46B709A7B4D93C35CD2D282353E85532E76C6940E3344AF6F`）
项目设计基线：V2.5（`docs/design/项目设计文档 V2.5.md`，1,041 行 / 100,726 B / sha256 `400131173C2059F26A93E5B5ED75113CABAC8330E52E6B5E8ACE8650DF6A09BB`）
当前代码 commit：`db77654`（＝ `db776547f6158a540430807b8262ef12e6498171`，`fix(it-guard): close DEV-001 DEV-002 isolation guard defects`，2026-09-15；上一代码提交 `8853730` F-88 写侧闭环；当前治理基线 `11919ed` ＝ `docs(治理): 发布指导书 V2.8 与项目设计文档 V2.5 —— 权威索引与冲突收口`，2026-09-14 19:28 +0800）
工作区状态：DEV-001／DEV-002 修复已由 `db77654` 落库（代码/测试/工具 10 路径）；本文件与泳道证据 `docs/acceptance/dev001-dev002-gate-fix-20260914/` 随本次 `docs(acceptance)` 提交入库（自指哈希 EVIDENCE_COMMIT 不写入本文件，沿用 `5180baf` 先例）。见「最近工作记录」09-15 落库条与 08:52／20:44／20:18／20:03 条、`REPORT.md` §11、§12
最后更新时间：2026-09-15 11:51 +0800（**DEV-003c 收口轮 —— 项目级统一测试入口落地并 fresh 验收**：新增 `scripts/run-tests.ps1`（412 行 / 27,091 B，`-Suite default|isolated|spark|all`；Maven 管模块内测试选择、PowerShell 管跨模块/跨 JDK/环境与汇总；**未新增根 pom.xml、未建 GitHub Actions、未改动 `scripts/run-isolated-tests.ps1`**）。fresh 实测：`default-tests` **751** ＝ 632（analytics-server，逐模块 89＋156＋134＋48＋91＋114）＋ 13（mall-simulator）＋ 106（synthetic-data-generator），F/E/S 全 0；`isolated-tests` **55** ＝ mall 30 ＋ generator 19 ＋ metric-analysis IT 6（全新 runId `dev003c_20260915_1205` 首跑即全绿，runner exit 0；同 reactor 的 platform-common 89 为依赖构建、不计入）；`spark-tests` **111**（显式 JDK8：`mvn -version` ⇒ `Java version: 1.8.0_202, runtime: D:\Develop\JDK1.8\jre`，`-Dp2.test.runId=dev003c_20260915_1205`，只认 `TestSuite.txt` 且已复核**本轮新写**：mtime 11:50:26/启动 11:50:00、sha256 `B4CAEC36…`；同一日志的 surefire 汇总行 `Tests run: 0` **不作为成功依据**）；`-Suite all` **exit 0**。自查发现并修复调度器自身缺陷 3 项（`$home` 只读变量、`bad` 空数组致 `.Count` 抛错、隔离计数未按模块归集），详见泳道 `docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md`。**本轮未提交、未 push**（先返回总控复核）。上一动作（10:34–10:45）＝ DEV-003 子项 1／2 收口轮，CODE `deba29f` ＋ EVIDENCE `451e8e2`，详见「最近工作记录」）

> 阅读口径：本文件只登记**可回溯到证据的实测事实**与**尚未裁决的冲突**；不解释、不合并、不替总控裁决。「未取证」一律显式标注，不得当作通过。
> 本文件**不设 `Vx.x` / `Sxxx` 等版本号**，始终一份，历史由 Git 保存；不得创建 `PROJECT_STATUS_V2.md`、`final.md` 等副本。
> 自 `11919ed` 起，指导书 V2.8 与项目设计文档 V2.5 为**正式已发布版本，禁止再次修改**；二者对本 Agent 只读，本文件是代码 Agent 唯一允许持续维护的状态文件。

## 当前阶段

- 阶段定位：`V2.x` 收口阶段 —— 代码主链已具规模，当前重心是**测试与验收的证据闭合**，而不是新增业务功能。
- 治理状态：三文件治理已生效。指导书 V2.8＝项目决策权威；项目设计文档 V2.5＝正式设计权威；本文件＝实际开发状态报告。
- 四级分级现状（四条判据独立，不合并）：
  - 提交完成 ✅（治理基线 `11919ed` 已入库；DEV-001／DEV-002 三轮收口见 `db77654`（CODE）＋`451e8e2`（EVIDENCE）；**DEV-003c 的 `scripts/run-tests.ps1` 与泳道证据本轮尚未提交**，按总控要求先复核后落库）
  - 测试通过 ✅（**DEV-003c 收口轮 fresh 三档全绿**：`default-tests` **751** ＝ 632＋13＋106，F/E/S 全 0、三个目标 exit 0；`isolated-tests` **55** ＝ mall 30＋generator 19＋metric-analysis IT 6，**全新 runId 首跑**，F/E/S 全 0、runner exit 0；`spark-tests` **111**，`TestSuite.txt` **本轮新写**、failed 0／aborted 0、JDK8 已取证；`-Suite all` **exit 0**。证据 `docs/acceptance/dev003c-unified-test-entry-20260915/`。**等级本身仍由总控确认**；此前的 generator 5 error（DEV-003 子项 1）与「须同库二次跑才 19 全绿」口径已在 DEV-003a 关闭，本轮全新 runId 首跑即 `19/0/0/0`）
  - 限定验收 ✅（E3 隔离真链；F-88 限定验收）
  - 完整验收 ❌（`v26-real-testcounts-20260914` 五条硬理由；F-88 另有 8 项未取证，并受 DEV-003／DEV-004 相关缺口约束）

## 当前可运行功能

取证时点 2026-09-14；来源见「关键证据」。

- 分析后端 `analytics-server`（Spring Boot，六子模块：`platform-common`、`connection-ingestion`、`warehouse-pipeline`、`metric-analysis`、`ai-decision`、`platform-app`），本地端口 8091；前端 `web/`。
- 商城模拟器 `mall-simulator`（端口 8090）＋ `mall-frontend`；合成数据生成器 `synthetic-data-generator`；Spark 作业树 `spark-jobs`（Scala，Spark 3.5.1 / Hadoop 3.3.4）。
- 已实测跑通的链路：
  - 隔离实例 `127.0.0.1:3307`：ingestion → pipeline → metric 全链（F-88 链，终态 `FAILED / PIPELINE_QUALITY_FAILED`，**按设计**被质量闸门阻断）。来源 `docs/acceptance/v26-f88-isolated-chain-20260914/`，2026-09-14 16:2x。
  - 隔离真链（E3，单机 `local[2]`，仅启动期 env 覆盖）：`docs/acceptance/v25-e3-isolated-chain-20260914/`，2026-09-14 14:22。
  - 正式库 3306 侧历史真实跑动：`docs/acceptance/r9-20260911-*/`（run 22/25/30）等，属历史时点。
- **未取证**：以上业务链路均未在当前代码基线 `8853730` 上重新跑过；本次整理不运行任何测试。

## 已完成

- 代码规模（2026-09-14 19:2x 实测，tracked 计数）：主源码 `*.java` 366 个；测试 `*.java` 148 个（`*Test.java` 131、`*IT.java` 5）。Maven 目标＝`analytics-server`（6 子模块）＋ `mall-simulator` / `synthetic-data-generator` / `spark-jobs` 三个独立 POM。**仓库无根 POM**。
- F-88 写侧闭环（提交 `8853730`，总控复核 `37561dc`）：`data_quality_result` 四列按「声明／生效」契约落库；`warehouse-pipeline` 实测 `Tests run: 134, F:0, E:0, S:0`，`connection-ingestion` 156、`platform-common` 81，`BUILD SUCCESS`。
- F-88 隔离真链（提交 `b26e92d`，总控复核 `064b733`）：3307 上 V19/V20 真实落地、四列真落库（含 1 行「声明 WARN／生效 BLOCKING」）、3306 前后指纹 27 项全等。
- 默认档测试全绿，且取得本项目第一份「默认档不碰正式库」的**位点级零写入 3306** 实测证明（`v26-real-testcounts-20260914` 裁决 R-01）。
- 治理：指导书 V2.8／项目设计文档 V2.5 于 `11919ed` 正式发布 —— 三文件治理、作者与写入权限、状态文档职责、冲突处理规则与权威索引收敛；`K-xx`（仅总控可在指导书／设计文档定义）与 `DEV-xxx`（本文件）编号分流建立（见「历史编号勘误」）。

## 正在进行

- 隔离门禁修复：DEV-001／DEV-002 **已修复并实测**（2026-09-14 20:03）；DEV-002 的 N-3 残留**已于 20:10–20:18 收口**（analytics 侧唯一权威身份 `hostname:port`）；DEV-002 的**语义一致性残留已于 20:28–20:44 收口**（mall／generator 侧 `fingerprintMatches` 统一为同一判据、两处陈旧提示订正、8 条反例实测）；等总控复核后提交。
- D-5「启动前门禁」：E3 脚本的两处收紧已被总控认可为基线（启动前 GATE 自检；隔离准备不读 3306、改取仓库内工件真值＋sha256），但**门禁本身未落地**（登记为 V25-S04）。
- F-88 完整验收缺口的收敛（受 DEV-001～DEV-004 约束，见「测试与验收状态」）。

## 待实现

按**证据缺口**列出（不是设计愿望清单）：

- 迁移 IT 覆盖 V19/V20（DEV-004，**继续归入 F-93** 一并整改）。
- **DEV-003c CLOSED（2026-09-15 11:5x）**：项目级统一测试入口 `scripts/run-tests.ps1` 已落地并 fresh 验收（default 751／isolated 55／spark 111／all exit 0）⇒ `spark-jobs` 111 个 ScalaTest 与 `IsolationGuardMySqlIT` 6 例**已有标准自动入口**。**DEV-003d DEFERRED（转开发 backlog）**：其余历史／专项 IT（`MetricAdsMySqlIT`、`MetricPublisherMySqlIT`、`SourceRegistryMigrationMySqlIT`〔DEV-004〕、`SparkStageExecutorSmokeIT`、`AnalysisGoldenMySqlIT`〔D 类，永久排除〕）与前端项目级入口不再作为整理阶段阻塞项；**其余未打标 `*IT` 19 个仍无任何自动入口**。
- 隔离档的 **Flyway 编排**（DEV-003 子项）：隔离库必须在跑用例**之前**完成迁移，否则新 runId 上 `GeneratorMetaStoreTest` 的 5 个裸 JDBC 用例必红（`GeneratorMetaStoreTest` 无 Spring 上下文、不触发 Flyway；本轮新 runId 首跑已复现 5E，见「已知实现问题」DEV-003 子项 1）。
- `IsolationGuardMySqlIT` 的执行入口（DEV-003 子项；否则永远只能点名跑）。
- `AnalysisGoldenMySqlIT` 库名参数化（R-05）：**总控 2026-09-15 裁决改为 D 类处置** —— 该类依赖只在 3306 存在的历史快照，作为**历史黄金值只读复验**保留手工／专项执行能力，**永久排除** unified isolated-tests；R-05 的参数化本身不再是收口前置（若日后要恢复其自动化，须另行裁决）。
- 唯一权威配置键名表（R-06：`metric.publish.username|metric.read.username` vs `metric.read.user|meta.app.user`）。
- 3306 真库 V19/V20 迁移（当前**冻结**，条件见「当前阻塞」）。
- 隔离实例遗留库／账号清理（总控已裁：F-93 完成后走 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）。
- P-01 的 append-only 勘误证据（见「已知实现问题」）。
- **README 测试矩阵陈旧（仅登记，本轮不重写）**：`README.md:35-44` 仍是 2026-09-11 口径 —— `analytics-server 303/303`（`:37`）、`spark-jobs 46/46`（`:38`，命令写作 `mvn -f spark-jobs/pom.xml package`）、`web 74/74`（`:39`）、`mall-simulator 54/54`（`:40`）＋ 4 条 `.verify/*` 真机／DOM 套件（`:41-44`）；与现行 632／111／13／106 **全部不一致**。总控 2026-09-15 裁决：正式收口放到下一轮「指导书重写／文档发布」。
- 隔离实例上按 runId 累积的库与受限账号（DEV-003c 收口轮新增 `dev003c_20260915_1150`／`dev003c_20260915_1205`，3307 上共 4 个 `dev003c%` 库）：**未清理**，仍按 `:60` 走 F-93 后的 `-AllowedCleanDbs` 白名单。

## 当前阻塞

- **3306（总控裁决冻结）**：DEV-001／DEV-002 已修复并实测、3307 隔离真跑已复跑成功，但 **D-5 门禁仍未落地**，因此**继续不进行新的 3306 迁移、切换或写入型验收**。本轮全程 3306 零写入：DEV-001／002 轮 15 项 pre/post 判据全一致，N-3 收口轮做了只读复取（16/16 行逐行一致），**语义一致性收口轮（20:4x）再做一次只读复取，10 个判据项（实例 uuid/port/host/version、两条 Flyway max_rank、三处计数、ACTIVE 快照/版本/行数、5 张表 CHECKSUM）与 post 快照差异 0**，证据 `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-3306-readonly-recheck.txt`、`raw/n3-3306-readonly-recheck.txt`。
- DEV-001（阻断级）：**已修复并实测关闭**（2026-09-14 20:03 泳道；总控 20:1x 复核通过）。旧实现 `TestIsolationGuard` 用 `SELECT @@version_major`，在宿主 3306 与 WSL 3307（均 8.0.41）上实测 `ERROR 1193 Unknown system variable 'version_major'` ⇒ 端口／账号／权限／指纹四项判据一次都不执行。现改为 `SELECT VERSION()` 解析主版本号且解析不出即 fail-closed；真 3307 上四判据全部真实执行（`docs/acceptance/dev001-dev002-gate-fix-20260914/`）。同轮附带修掉同一调用链上两处被真实授权形态暴露的误判（`GRANT USAGE ON *.*` 被当成全局非只读权限；反引号库名导致本库写权限被算成越界写权限）——总控已裁定**纳入 DEV-001，不另立编号**。**代码提交：`db77654`**（commit 1，含 `TestIsolationGuard.java`／`TestIsolationGuardTest.java`／`IsolationGuardMySqlIT.java`／`run-isolated-tests.ps1`）；**总控 2026-09-15 复核裁决：已修复并实测关闭。**
- DEV-002（阻断级）：**已修复并实测关闭**（2026-09-14 20:03 修复 ＋ 20:10–20:18 N-3 收口 ＋ **20:28–20:44 语义一致性收口**；总控裁定「主体修复通过，残留修完才可关闭」）。原为隔离指纹／配置词汇表不对称（runner 注入 `server_uuid`，而 mall/generator 侧 itguard `fingerprintMatches` 不认 server_uuid）⇒ mall 30/30 假红。修法：runner 门禁6 探针在真实例上取 `@@hostname:@@port` 作为**唯一实例身份**（现场 `dahaishui:3307`）注入 `IT_GUARD_SERVERFINGERPRINT`；generator 模块补注入 `SPRING_DATASOURCE_URL/USERNAME/PASSWORD`（其 `@SpringBootTest` 原本走主 `application.yml` 的 3306 `createDatabaseIfNotExist=true`，属真实 3306 写入风险）。**N-3 收口（20:10–20:18）**：analytics 侧 `TestIsolationGuard.fingerprintMatches` 改为 `(expected, hostname, port)`，只有登记值与实连 `@@hostname:@@port` 规范化后完全相等才通过。**语义一致性收口（20:28–20:44）**：① mall／generator 侧两份 `com.graduation.itguard.IsolationGuard` 的 `fingerprintMatches`（`:420-425`）改为与 analytics **同一判据**，两文件仍逐字节相同（sha256 前 16 位 `35006DE37D34E8D4`，459 行），失败消息（`:382`）同步改为显式列出被拒形态；② 新增同包反例集 `IsolationGuardFingerprintTest`（两模块各一份、逐字节相同，5 `@Test`／25 断言、**不带 `@Tag` ⇒ 只进默认档**）；③ `scripts/it-prepare-isolation.ps1:199` 与 `scripts/it-isolation.env.template:31-34` 的陈旧提示统一为 canonical `IT_GUARD_SERVERFINGERPRINT = hostname:port`（例 `dahaishui:3307`），并写明 `@@server_uuid` 是独立漂移事实、不是 fingerprint 替代值。`@@server_uuid` 不再作为替代合法值（登记值填 uuid 一律拒绝），但仍是独立 fail-closed 漂移事实（取不到即拒绝，且计入 `LiveFacts.sha1()`）。端口白名单（analytics `ALLOWED_INSTANCE_PORTS={3307}`；mall/generator `assertPortAllowed`／`HOST_FORMAL_PORT=3306`）**未改动**，两层约束互不兜底。**关闭口径不含 runner 级「`-Module both` 全绿」**——该绿灯被 DEV-003 子项 1 挡着（见下条）。**代码提交：`db77654`**（commit 1，含 mall／generator 两份 `IsolationGuard.java` 与两份 `IsolationGuardFingerprintTest.java`、`it-prepare-isolation.ps1`、`it-isolation.env.template`）；**总控 2026-09-15 复核裁决：已修复并实测关闭。**
- 隔离档 runner `scripts/run-isolated-tests.ps1` **语义一致性收口轮退出 7**（新 runId `dev002sem_20260914_2035`，首个 run）：**mall 30 run / 0F / 0E / 0S 通过**；**generator 19 run / 0F / 5E / 0S**，5 个 error 全在 `GeneratorMetaStoreTest`（4 张表 `doesn't exist`），非 DEV-003 的 14 例（`GeneratorApiSmokeTest` 5、`MallApiGenerationSmokeTest` 9）全绿；runner 门禁 6 探针注入的 `dahaishui:3307` 被三层守卫**全部放行 ⇒ 指纹假红 0**。5 error 的真实机制是**执行顺序**而非「Flyway 从未执行」：同一进程内 `GeneratorMetaStoreTest`（无 Spring 上下文）先跑（首次 `doesn't exist` 在日志第 56 行），Flyway 直到 `@SpringBootTest` 类才执行（第 284 行起，`20:32:18.241 Successfully applied 1 migration`，5 张业务表 `CREATE_TIME` 全为 `20:32:18`）⇒ **新 runId 首跑必红、同库二次跑才绿**，属 DEV-003 子项 1，本文件不按「已修好」记。证据 `raw/n4-isolated-mall-newrunid.log`、`raw/n4-isolated-generator-newrunid.log`、`raw/n4-generator-dev003-flyway-order.txt`、`raw/n4-newrunid-instance-facts.txt`。
- **DEV-003 子项 1／2 收口轮（2026-09-15 10:34–10:45，代码 Agent）**：上条 `exit 7` 的现场已被修掉 —— **全新 runId `dev003_20260915_1110` 首跑** `-Module all -Confirm` 三档全绿（mall 30／generator 19／analytics `IsolationGuardMySqlIT` 6，F／E／S 全 0），runner **exit 0**。generator 那 5 个 error 的机制是「Flyway 执行得太晚」而非「从未执行」，修法为**在 `GeneratorMetaStoreTest` 执行前由测试侧扩展完成 门禁 → Flyway → 表存在性自检**（不预建表、不复用旧库、不 catch「表不存在」、不跳过、不写死执行顺序）；`IsolationGuardMySqlIT` 已由 `metric-analysis` 的 `isolated-tests` profile 自动收集，并在 runner 内加了「被点名隔离类必须真跑到」的零用例硬门禁。证据 `docs/acceptance/dev003-isolated-entry-20260915/`（`REPORT.md` ＋ `raw/`）。**DEV-003 主条目的另一半（`spark-jobs` 111 个 ScalaTest 自动入口）本轮仍未做。**
- D-5 门禁未落地 ⇒ 3306 真库迁移在当前冻结裁决下不可执行。
- `analytics-server` 六模块真实测试数曾因 platform-app 测试会触发 Flyway→3306 而受阻（`v26-f88-isolated-chain-20260914/CONTROLLER-VERIFICATION-20260914.md` §4-8）；该条已被 `v26-real-testcounts` 的默认档 624 部分覆盖（口径关系见「测试与验收状态」）。

## 已知实现问题

普通缺陷、测试失败与实现缺口登记在此，**不升级为总控议题**。

- DEV-003：`spark-jobs` 是独立 POM，既不在 `analytics-server` reactor 内，也不在 `run-isolated-tests.ps1`（只覆盖 mall/generator）内 ⇒ 111 个 ScalaTest 与 19 个 DB 用例都没有自动执行入口。本轮新增两个已知子项（总控 20:1x 裁定归入 DEV-003，**不新立 DEV-005**）：
  - **子项 1（generator 隔离库建表与 `GeneratorMetaStoreTest` 无编排）**：`_generator` 库的 schema 只由 generator Spring 上下文启动时的 Flyway 创建，而 `GeneratorMetaStoreTest` 走裸 JDBC 直查表（该类只有 `@Tag("it")` ＋ 静态门禁 ＋ 自建 `DriverManagerDataSource`，**无 Spring 上下文 ⇒ 从不触发 Flyway**），同一 Maven 进程内该类**先于**所有 `@SpringBootTest` 类执行 ⇒ **新 runId 首跑必现 5 个 error**。两次实测：2026-09-14 19:57（`dev12fix_20260914_1945_generator`）与 **20:32（新 runId `dev002sem_20260914_2035_generator`）**，错误均为 `Table '…_generator.<generation_run|generation_event_stat|generator_target|generation_plan>' doesn't exist`；同一次运行内 Flyway 随后才在 `20:32:18.241` 完成 `Successfully applied 1 migration … now at version v1`（首次 `doesn't exist` 在日志第 56 行，首次 Flyway 在第 284 行）。同库二次跑则全绿（态依赖），**不得记作 DEV-003 已修**。
    - **DEV-003a（＝子项 1）状态（2026-09-15 10:3x）：已修复并实测关闭**（**总控 2026-09-15 复核裁决：DEV-003a ＝ 已修复并实测关闭**）。修法＝新增测试侧扩展 `synthetic-data-generator/src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java`（7110 B／128 行，`implements BeforeAllCallback`：`requireEnabled`／`require(url,user,password)`／`assertUrlAllowed` → `IsolationGuard.verifyBeforeWrite(ds, expectedDb, …)`（**门禁在 DDL 之前**）→ `Flyway.migrate()`（`classpath:db/generator`）→ `information_schema` 复核 5 张表），在 `GeneratorMetaStoreTest` 上 `@ExtendWith(com.graduation.itguard.IsolatedSchemaInitializer.class)`（`:74`）；**未改任何 main 源码、未改 surefire 顺序、未加 `runOrder`**。**全新 runId `dev003_20260915_1110` 首跑即绿**：首跑前取数 `_generator` 0 表／0 条 `flyway_schema_history`、`_mall` 0 表／0 条；日志 `:38` `Successfully applied 1 migration to schema … now at version v1`、`:39` `[DEV-003a] GeneratorMetaStoreTest 前置编排完成 … flyway 本次执行迁移数=1 目标版本=1`、`:40` `flyway_schema_history: rank=1 version=1 description=generator meta installed_on=2026-09-15 10:42:04 success=true`、`:46` `GeneratorMetaStoreTest Tests run: 5, Failures: 0, Errors: 0, Skipped: 0`、`:153` 模块汇总 `19/0/0/0`；首跑后 `_generator` 6 表。修前同一缺陷在**全新 runId `dev003a_20260915_1040`** 复现：`Tests run: 19, Failures: 0, Errors: 5`、runner `exit 7`、首个 `doesn't exist` 在日志 `:56`（`generation_event_stat`）／`:67`（`generation_run`）、Flyway 直到 `:284` 才执行。证据 `docs/acceptance/dev003-isolated-entry-20260915/raw/{00-prefix-RED-generator-firstrun-no-schema-init.log,01-runner-module-all-console-dev003_20260915_1110.txt,03-isolated-instance-facts.txt,11-isolated-generator.log}`。
  - **子项 2（`IsolationGuardMySqlIT` 无自动入口）**：analytics-server 无 surefire 配置，默认档 `*IT` 执行 0 个；该 IT 只能 `-Dtest=... -Dsurefire.failIfNoSpecifiedTests=false` 点名执行，否则只是「没人跑的绿」。
    - **DEV-003b（＝子项 2）状态（2026-09-15 10:3x）：已修复并实测关闭**（**总控 2026-09-15 复核裁决：DEV-003b ＝ 已修复并实测关闭**）。修法＝`analytics-server/metric-analysis/pom.xml` 新增 `<properties><v25.it.excluded.groups>it</v25.it.excluded.groups></properties>`（`:78`）＋ surefire `<excludedGroups>${v25.it.excluded.groups}</excludedGroups>`（`:90`）＋ profile `isolated-tests`（`:100`：清空该属性（`:103`）＋ `<groups>it</groups>`（`:113`）＋ **必须显式** `<includes><include>**/*IT.java</include></includes>`（`:117`）——类名 `IsolationGuardMySqlIT` 不在 surefire 默认包含式内，缺 `includes` 即 `Tests run: 0 / BUILD SUCCESS` 假绿）；`IsolationGuardMySqlIT.java` 加 `import org.junit.jupiter.api.Tag;`（`:6`）与 `@Tag("it")`（`:47`），类 javadoc 入口段（`:35`）由「必须 `-Dtest=` 点名」改写为「隔离档自动收集」；`scripts/run-isolated-tests.ps1` 增 `-Module analytics|all`（`ValidateSet` `:69`）与 analytics 目标（`:139-147`，**复用** `${RunId}_mall`／`${RunId}_mallapp`、**不新建 3307 对象**、命令 `-f analytics-server\\pom.xml -pl metric-analysis -am test` ＋ `-Pisolated-tests`），循环内按本 runId 注入该 IT 唯一读取的 `DEV001_IT_URL/USER/PASSWORD/RUNID/FINGERPRINT`（`:312`；非 analytics 目标清除，`:319`），并加**零用例硬门禁**（`:334-342`：日志内找不到 `-- in …IsolationGuardMySqlIT` 的 `Tests run:` 行即置模块退出码 7）。实测：标准入口 `-Module all -Confirm`（**全程无 `-Dtest=`**）自动执行该 IT **6 run / 0F / 0E / 0S**，runner **exit 0**；同 reactor 内 `platform-common` 89 亦绿。**默认档不受影响**：analytics-server `mvn test` 6 模块合计 **632**（89＋156＋134＋48＋91＋114），其中 `metric-analysis` **48** 与改动前一致 ⇒ 默认档未把真库 IT 带进来（三棵树默认档 `*IT` 选中数均为 0）。证据 `raw/{12-isolated-analytics.log,20-default-analytics-reactor.log}`。
  - **DEV-003 总体状态（总控 2026-09-15 复核，以此为准）：部分收口 —— a／b 已关闭；整体尚未关闭。** 内部子项编号（**DEV-003 内部编号，不新立主编号，不碰 DEV-004／DEV-005**）：
    - **DEV-003a**：generator fresh schema／Flyway 前置初始化 → **CLOSED**
    - **DEV-003b**：`IsolationGuardMySqlIT` 接入标准 `isolated-tests` 入口 → **CLOSED**
    - **DEV-003c**：`spark-jobs` **111 个 ScalaTest 缺少项目级统一自动执行入口** → **OPEN**
    - **DEV-003d**：**其余未打标 `*IT` 尚未纳入标准自动执行入口**（含结构性必败的 `SourceRegistryMigrationMySqlIT`，其双重必败本体仍归 DEV-004） → **OPEN**
    - 读法约束：**不得**由「DEV-003a／b 已关闭」推出「DEV-003 已结束」；c／d 关闭前 DEV-003 保持开启。
- DEV-004（**继续归入 F-93**）：`SourceRegistryMigrationMySqlIT` **双重必败** —— `context():131-132` 同库；`EXPECTED_META_SCRIPTS:148-165` 止于 V18，而树内已有 `V19__quality_rule_definition.sql` / `V20__data_quality_result_rule_version.sql`。直接牵连 F-88：V20 迁移在仓库内**没有任何 IT 覆盖**，D-5「一次误启动即迁移 3306」目前只有静态守卫、无测试守卫。
- **P-01 MATRIX 指纹不一致（已裁决为已知证据问题）**：`docs/acceptance/v25-r01-coverage-20260914/MATRIX.md:5` 记录看板 V2.5 ＝ 122 行 / 16,593 B / sha256 `9E9CC765…73AE`；实物为 167 行 / 80,547 B / sha256 `8DDE2255AE41394BC019DDC7D8EEC72514640F90BF5073E7A3FBF5D5319208B1`（末次提交 `ad7b338`）。**旧 MATRIX 不修改**；后续建立 **append-only 勘误证据**，说明同路径看板在 MATRIX 生成以后发生过变化。
- `AnalysisGoldenMySqlIT` 库名硬编码（R-05）：黄金值依赖只在 3306 存在的历史快照 ⇒ 不可重定向到隔离库。该类经核**全类无写语句**（纯只读）。
- 隔离安全风险面（`docs/acceptance/v25-s01-it-safety-20260914/`，只报不改，R-1～R-6；本条为看板 V2.5 摘录，本次整理未逐文件复核）：`mall-simulator/src/test/resources/application-test.yml:3-4` 指向宿主 3306＋`root`＋`createDatabaseIfNotExist=true`＋Flyway enabled；`SourceRegistryMigrationMySqlIT.java:55` 默认值即正式 `analytics_meta`；`GeneratorMetaStoreTest.java:58` 用 `assumeTrue(false, …)`（skip 后 PASS）；`SparkStageExecutorSmokeTest.java:32,39,65` 无门禁且真实 `spark-submit`＋递归删目录；`scripts/run-demo.ps1:80`、`scripts/smoke-pipeline.ps1:39-42` 硬编码 `root/123456`。
- 「`V19`/`V20` 存在 ≠ 版本化已闭合」：持久化载体虽补齐，跨 run 闭环与并发发布原子性**仍未取证**；`catalog=qrc-1` 无落库承载列，目录版本只能从应用日志回查。
- `15-seed-runtime-profile.ps1` 首跑缺陷（真实教训）：PowerShell `-replace` 是**正则**替换，`'\\'→'\\\\'` 落库成 4 个反斜杠；已改字面替换并复跑，最终值与历史值逐字节相同。**首跑错误值未独立归档**（总控已采认为证据残缺项，不要求重跑）。
- `platform-common` 曾在 2026-09-14 12:06–12:38 主代码不可编译（`QualityRuleDefinition.java:84` 找不到 `isKnownSeverity`，并阻塞另两条泳道）；其后默认档复算为 `BUILD SUCCESS`，说明已可编译，但**期间修复提交未逐一取证**。
- 判据口径局限（实测发现，非文档抄录）：单看 `effective_severity` 一列无法区分「未登记码按保守默认阻断」与「已登记且声明即阻断」；可靠判据＝`severity IS NULL AND rule_version IS NULL` 组合（已写入 `QualityChecker.java:248-254` 注释）。
- `GeneratorMetaStoreTest` 之类的「skip 后 PASS」模式使 `0 failure` 不能直接读成「已验证」。

## 测试与验收状态

### 事实（实测数字，含来源）

| 口径 | 数字 | 判定 | 来源（时点） |
|---|---|---|---|
| `analytics-server` 默认档 | **624**，0F / 0E / 0S，exit 0（**本轮修复前口径**） | 全绿 | `docs/acceptance/v26-real-testcounts-20260914/REPORT.md`＋`raw/`（2026-09-14） |
| 默认档四棵树合计 | **844** = 624（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs）（**本轮修复前口径**） | 全绿，退出码均 0 | 同上 |
| `analytics-server` 默认档（**DEV-001／002 修复轮口径**） | **628** = 85＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0 | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/default-tier-analytics-server.log`（2026-09-14 20:0x）；增量全部来自 `TestIsolationGuardTest` 20 → 24 |
| 默认档四棵树合计（DEV-001／002 修复轮口径） | **848** = 628（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs） | 全绿 | 同上 |
| `analytics-server` 默认档（**N-3 收口轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0 | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n3-default-tier-analytics-server.log`（2026-09-14 20:10–20:18）；增量**全部**来自 `TestIsolationGuardTest` 24 → 28（platform-common 85 → 89），其余五个模块逐模块不变 |
| 默认档四棵树合计（**N-3 收口前口径，已被下一条取代，不回改**） | **852** = 632（analytics-server）＋ 8（mall-simulator）＋ 101（synthetic-data-generator）＋ 111（spark-jobs） | 全绿 | 同上；**差额 10 ＝ 语义一致性收口轮新增的 2×5 个反例单测**（mall／generator `IsolationGuardFingerprintTest` 各 5 `@Test`，不带 `@Tag` ⇒ 只进默认档；逐类原文见下两条） |
| `analytics-server` 默认档（**语义一致性收口轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0（与 N-3 轮**逐模块相同**，本轮未改 analytics 侧任何代码） | 全绿 | `docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-analytics-default-tier-maven.log`（2026-09-14 20:3x） |
| `mall-simulator` 默认档（**语义一致性收口轮**） | **13**，0F / 0E / 0S，exit 0（8 → 13，+5 ＝ 新增反例集 `IsolationGuardFingerprintTest`） | 全绿 | `raw/n4-mall-default-tier-maven.log`（同上）；逐类原文：`IsolationGuardFingerprintTest 5`（:30）＋ `OrderStateMachineTest 2`（:32）＋ `GoldenDatasetTest 6`（:34），汇总行（:38）13 |
| `synthetic-data-generator` 默认档（**语义一致性收口轮**） | **106**，0F / 0E / 0S，exit 0（101 → 106，+5 ＝ 同名反例集） | 全绿 | `raw/n4-generator-default-tier-maven.log`（同上）；17 个测试类，含 `IsolationGuardFingerprintTest 5`（:73），汇总行（:77）106 |
| 默认档四棵树合计（**语义一致性收口轮，以此为准**） | **862** = 632（analytics-server）＋ 13（mall-simulator）＋ 106（synthetic-data-generator）＋ 111（spark-jobs）；**862 − 852 = 10 ＝ 本轮新增 2×5 反例单测**（总控 2026-09-15 裁决：保留 862，852 为 N-3 收口前口径） | 全绿 | 同上 |
| `analytics-server` 默认档（**DEV-003 轮口径**） | **632** = 89＋156＋134＋48＋91＋114，0F / 0E / 0S，exit 0（与语义一致性收口轮**逐模块相同**；`metric-analysis` 48 未变 ⇒ 新增 surefire 配置未改默认档选中集合） | 全绿 | `docs/acceptance/dev003-isolated-entry-20260915/raw/20-default-analytics-reactor.log`（2026-09-15 10:4x） |
| 隔离档（**DEV-003 轮，全新 runId `dev003_20260915_1110`**） | mall **30** ／ generator **19（首跑即 19，无二次跑补绿）** ／ analytics `IsolationGuardMySqlIT` **6**；F / E / S 全 0；runner `exit 0`；`spark-jobs` 111 **本轮未复跑（沿用既有口径）** | 全绿 | 同 lane `raw/{10-isolated-mall.log,11-isolated-generator.log,12-isolated-analytics.log,01-runner-module-all-console-dev003_20260915_1110.txt}` |
| `spark-jobs` | **111**（ScalaTest） | 通过 | 同上；**口径**：只认 `TestSuite.txt` 的 `Total number of tests run:` 与逐套件 XML，任何用 `Tests run:` 汇总该模块的做法必得 0 或漏算（裁决 R-C2） |
| `default-tests`（**DEV-003c 收口轮 fresh，`-Suite default`**） | **751** = 632（analytics-server）＋ 13（mall-simulator）＋ 106（synthetic-data-generator）；三目标 F / E / S 全 0、exit 0；**不含 `spark-jobs`** | 全绿 | `docs/acceptance/dev003c-unified-test-entry-20260915/raw/{20-default-analytics-reactor.log,21-default-mall.log,22-default-generator.log}`（2026-09-15 11:49） |
| `isolated-tests`（**DEV-003c 收口轮 fresh，全新 runId `dev003c_20260915_1205`**） | mall **30** ／ generator **19（该 runId 首跑即 19）** ／ `IsolationGuardMySqlIT` **6**；F / E / S 全 0；runner `exit 0`；合计 **55** | 全绿 | 同 lane `raw/{24-isolated-mall.log,24-isolated-generator.log,24-isolated-analytics.log,23-isolated-console.txt}` |
| `spark-tests`（**DEV-003c 收口轮 fresh，本轮新写**） | **111**（ScalaTest；`Suites: completed 15, aborted 0`、`failed 0`、`All tests passed.`）；`JAVA_HOME=D:\Develop\JDK1.8`（`mvn -version` ⇒ `Java version: 1.8.0_202, runtime: D:\Develop\JDK1.8\jre`）；`-Dp2.test.runId=dev003c_20260915_1205`；`TestSuite.txt` mtime `11:50:26`（启动 11:50:00）sha256 `b4caec3667c59211c797b16b38dbbff028d5d9351444cb9d4d2d8ab87a3c3ad1` | 全绿（**仅限 `local[1]` ＋ in-memory catalog**，不等于在产 Hive／Spark 集群通过） | 同 lane `raw/{25-spark-jobs-maven.log,26-spark-jdk-version.log,27-all-ScalaTest-TestSuite.txt}`；独立 `-Suite spark` 另见 `raw/{10-spark-console.txt,13-ScalaTest-TestSuite.txt}`（runId `…1155s`，mtime 11:44:45 sha256 `c1cfc973…`） |
| `-Suite all` 总退出码（**DEV-003c 收口轮 fresh**） | `default` PASS ＋ `isolated` PASS ＋ `spark` PASS ⇒ **exit 0**（三档独立摘要，不合并计数） | 三档全绿 | 同 lane `raw/20-all-console.txt`（143,077 B，11:49:0x–11:50:27） |
| 历史 `local-readiness` 口径 | 后端 **566**：564 通过、2 失败、0 错误、0 跳过；另有 分析前端 74、商城前端 6（均 0 失败） | 2 失败 | `docs/acceptance/local-readiness-20260914.md:23`；同口径见 `docs/项目完整实施指导书 V2.5.md:22`（2026-09-14） |
| 完整验收 | ❌ | 五条硬理由 | `v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md:88` |

**862 默认档汇总口径（必须逐项拆开写；禁止只写 `862` 而不标 111 的旧口径；其中「禁止把 spark-jobs 111 表述成本轮 fresh 实测」**只适用于 DEV-003 轮（2026-09-15 10:4x）**——DEV-003c 收口轮已 fresh 实测 111，见下方「DEV-003c 收口轮口径」与新增表行）**：

```text
862
= 632  analytics-server（本轮实测）
+  13  mall（本轮实测）
+ 106  generator（本轮实测）
+ 111  spark-jobs（沿用既有证据口径，本轮未复跑）
```

**DEV-003c 收口轮口径（2026-09-15 11:5x，以此为准）**：

```text
862（四棵树，全部本轮 fresh）
= 632  analytics-server（本轮 fresh，逐模块 89+156+134+48+91+114）
+  13  mall-simulator（本轮 fresh）
+ 106  synthetic-data-generator（本轮 fresh）
+ 111  spark-jobs（**本轮 fresh**：TestSuite.txt 本轮新写，不再沿用 2026-09-14 旧产物）

另：default-tests = 751（前三棵树，不含 spark-jobs）
    isolated-tests = 55（mall 30 + generator 19 + metric-analysis IT 6，全新 runId 首跑）
    -Suite all = default → isolated → spark，**exit 0**
```

### 证据口径说明（本轮总控裁决）

- `566 / 564 通过 + 2 失败` ＝ 历史 `local-readiness` 口径；`624 / 0F / 0E / 0S` ＝ 后续 `analytics-server` reactor 独立复算口径。**当前没有逐测试项映射**，因此**禁止合并、禁止相减后解释、禁止覆盖，也禁止声称其中一个推翻另一个**。
- 历史多个 HEAD 值（`8983616`、`6062434`、`2a10483`、`064b733`、`f26be26`、`198e0c5` 等）**属于不同取证时点**，**不回写历史证据**。当前状态**只认当前治理基线（`11919ed`）与当前代码基线（`8853730`）**。

### IT 是否真正执行（写**实际执行数量**，不以 `BUILD SUCCESS` 替代）

- 默认档：`*IT.java` **执行 0 个**（surefire 默认 include 不含 `*IT`）。`analytics-server/pom.xml` 内 surefire/failsafe 命中 **0 处**（本次实测）。`*IT.java` **6 个**、IT 用例 **25 个**（含 `IsolationGuardMySqlIT`，DEV-001 真链证据用），默认档仍执行 0 个。
- 点名执行（默认档 profile）：「4 个 IT ＝ 绿构建·零执行（被跳过，退出码 0）」＋「`SparkStageExecutorSmokeIT` ＝ `Tests run: 1, Errors: 1`，exit 1」。
- **DEV-003b 后的新增事实（2026-09-15 10:4x）**：`IsolationGuardMySqlIT` **不再需要人工点名** —— 项目唯一隔离入口 `scripts/run-isolated-tests.ps1 -RunId <runId> -Module analytics|all -Confirm` 会自动收集并执行它（实测 **6/0/0/0**，runner exit 0），且入口在跑完后**强制核对**「该类确实被执行到」（零用例／未被选中 ⇒ 模块退出码 7），不再依赖人工记忆类名。默认档仍执行 **0** 个 `*IT`（本轮三棵树默认档 `*IT` 选中数均为 0）。
  - 历史事实不回改：`-Dtest=IsolationGuardMySqlIT` 的两次点名执行（2026-09-14 20:1x／20:3x，均 6/0F/0E/0S）仍是 DEV-001 真链的原始证据。
  - 4 个 IT 的静默跳过是 `IsolationProfileCondition:35-38` 的**设计行为**（V2.5 §9.4），**不判为门禁缺陷**（裁决 R-09）。
  - `SparkStageExecutorSmokeIT` 走 `SparkItGuard`，无配置时 `MissingConfigurationException` **硬失败**。
  - `IsolationGuardMySqlIT` **必须点名**才跑（`-Dtest=IsolationGuardMySqlIT -Dsurefire.failIfNoSpecifiedTests=false`）：2026-09-14 20:1x（N-3 收口后）与 20:3x（**语义一致性收口轮**）两次在真 3307 上点名执行，均为 **6 run / 0F / 0E / 0S**，全绿；注入的登记指纹就是 runner 探针的 `dahaishui:3307`（证据 `raw/n4-realchain-3307-maven.log`、`raw/n4-realchain-3307-IsolationGuardMySqlIT-surefire.txt`）。
- 隔离档（唯一入口 `scripts/run-isolated-tests.ps1`）：**修复前** exit 7 —— `@Tag("it")` 共 49 个用例 **0 通过**（44 error / 5 failure），mall 30/30 假红（DEV-002）、generator 19 个未过、写入型 IT 被 `@@version_major` 卡死（DEV-001）⇒ 0 个真实业务断言被执行。**DEV-001／002 修复轮** 49 用例 44 通过 / 5 error（`mall 30/0F/0E/0S` exit 0；`generator 19/0F/5E/0S` exit 1）。**N-3 收口轮** 49 用例 49 通过（**但 generator 5 例属态依赖**：隔离库表由 19:57 上一轮 Flyway 建好，本轮日志为 `No migration necessary`）、runner `exit 0`。**语义一致性收口轮（新 runId `dev002sem_20260914_2035`）** 49 用例 **44 通过 / 5 error / 0 failure**：`mall 30/0F/0E/0S` exit 0；`generator 19/0F/5E/0S` exit 1（5 error 全在 `GeneratorMetaStoreTest`，非 DEV-003 的 14 例全绿）⇒ runner `exit 7`；**指纹假红 0**（`dahaishui:3307` 被三层守卫放行）。证据：`raw/n4-runner-both-newrunid.log`、`raw/n4-isolated-mall-newrunid.log`、`raw/n4-isolated-generator-newrunid.log`、`raw/n4-generator-dev003-flyway-order.txt`、`raw/n4-newrunid-instance-facts.txt`，以及 19:57 首跑日志 `raw/layer3-generator-maven.log`、20:12 复跑 `raw/n3-layer3-generator-maven.log`。
- 未入默认档的用例合计 **74 个**（`*IT` 25 ＋ mall `@Tag("it")` 30 ＋ generator `@Tag("it")` 19），**语义一致性收口轮 50 通过**（点名的 `IsolationGuardMySqlIT` 6 ＋ mall 30 ＋ generator 14），5 error（generator `GeneratorMetaStoreTest`，DEV-003 子项 1），其余 19 未过（未执行的 `*IT`）。
- 对外统一写法：「默认档 **632**（语义一致性收口轮）＋ 四棵树 **862** ＋ 未入默认档的隔离档 49 与点名 IT 用例」；**禁止**把 624／628／632 说成「全部测试」（裁决 R-C1），**禁止**「全量全绿」；引用 generator 隔离档数字时必须写明「新 runId 首跑 14 通过 / 5 error（DEV-003 子项 1）、同库二次跑才 19 全绿」。
- **口径更新（2026-09-15 10:4x，DEV-003 子项 1／2 收口轮，以此为准）**：① 「generator 隔离档 19 例须靠同库二次跑才全绿」的说法**作废** —— 全新 runId 首跑即 `19/0/0/0`（`GeneratorMetaStoreTest` 5/0/0/0），修法与证据见「已知实现问题」DEV-003 子项 1 状态条；② 默认档 **632**（analytics）＋ **13**（mall）＋ **106**（generator）＋ **111**（spark-jobs，沿用）＝ **862**，本轮三棵树默认档与基线**逐模块一致**；③ 原「未入默认档的用例合计 **74** ＝ `*IT` 25 ＋ mall `@Tag("it")` 30 ＋ generator `@Tag("it")` 19」**总数与分类不变**，变的是**能否自动执行**：其中 analytics `IsolationGuardMySqlIT` **6 例已进入隔离档自动执行**，其余未打标 `*IT` **19 例仍无任何自动入口**（含结构性必败的 `SourceRegistryMigrationMySqlIT`，属 DEV-004）；④ **F-88 仍为限定验收**，本轮不因 DEV-003 子项收口而升级；⑤ DEV-003 **未整条闭合**：`spark-jobs` 111 个 ScalaTest 与上述 19 个 `*IT` 的自动入口仍缺。⑥ **862 汇总必须逐项拆开写**：862 ＝ 632（analytics-server，**本轮实测**）＋ 13（mall，**本轮实测**）＋ 106（generator，**本轮实测**）＋ 111（`spark-jobs`，**沿用既有证据口径，本轮未复跑，不属于本轮 fresh 实测结果**）。⑦ DEV-003 内部子项最终状态（总控 2026-09-15 复核）：**a ＝ CLOSED**（generator fresh schema／Flyway 前置初始化）、**b ＝ CLOSED**（`IsolationGuardMySqlIT` 标准 `isolated-tests` 入口）、**c ＝ OPEN**（`spark-jobs` 111 缺项目级统一自动执行入口）、**d ＝ OPEN**（其余未打标 `*IT` 未纳入标准自动执行入口）⇒ **DEV-003 部分收口，整体仍开启**。
⑧ **DEV-003c 收口轮（2026-09-15 11:5x，总控 2026-09-15 裁决，以此为准）**：新增项目级统一测试入口 `scripts/run-tests.ps1`（`-Suite default|isolated|spark|all`；**Maven 负责模块内测试选择**〔surefire `include`／`groups`／`excludedGroups` 与 `isolated-tests` profile 仍留在各模块 `pom.xml`〕，**PowerShell 负责跨模块／跨 JDK／环境与汇总**；**未新增根 `pom.xml`**、**未创建 GitHub Actions**、**未改动 `scripts/run-isolated-tests.ps1`**〔`git diff --stat` 为空〕、**未修改六个 `*IT.java`**、不使用 `-Dtest=` 点名）。fresh 实测：`default-tests` **751**（632＋13＋106，0F/0E/0S）、`isolated-tests` **55**（mall 30＋generator 19＋metric-analysis IT 6，**全新 runId `dev003c_20260915_1205` 首跑**，runner exit 0；同 reactor 的 `platform-common` 89 为依赖构建，**不计入**）、`spark-tests` **111**（**本轮新写**，JDK8 已由 `mvn -version` 直接取证）、`-Suite all` ⇒ **exit 0**。**子项状态更新**：**c ＝ CLOSED**（`spark-jobs` 111 已有标准自动入口；判据只认 `TestSuite.txt` 的 `Total number of tests run` ＋ **文件 mtime ≥ 本轮启动时刻**，明确拒绝 surefire 的 `Tests run: 0 / BUILD SUCCESS`——本轮的 JDK8 日志内**同一份证据**即出现该 surefire 行而真实 111 全过，已把 `v25-r01-coverage-20260914/VERIFY-T01-T02.md:60-67` 的「矛盾·未取证」钉成 fresh 事实）；**d ＝ DEFERRED（转开发 backlog）**：`MetricAdsMySqlIT`／`MetricPublisherMySqlIT`（后续测试完善）、`SourceRegistryMigrationMySqlIT`（DEV-004）、`SparkStageExecutorSmokeIT`（Spark 专项冒烟）、`AnalysisGoldenMySqlIT`（**D 类，永久排除** unified isolated-tests）、前端项目级入口（CI 阶段）。**DEV-003 总体**：整理阶段测试入口收口完成；剩余历史／专项 IT 已转开发 backlog，**不再阻塞新版指导书发布**。**不得**据此声称「所有历史 IT 已自动化」——其余未打标 `*IT` **19 个仍无任何自动入口**；**F-88 继续限定验收**，完整验收仍 ❌。新增局限登记：计数基线（632/13/106/111 与 30/19/6）**硬编码**于脚本，漂移即失败（除显式 `-AllowCountDrift`，口径更新须总控批准）；`spark` 档「本轮新写」判据依赖 `TestSuite.txt` mtime ≥ 启动时刻（时钟回拨会失效，未做防护）；每次 `-Suite all` 会在 3307 上按 runId 新建库与受限账号（本轮新增 `dev003c_20260915_1150`／`dev003c_20260915_1205` 共 4 库，**未清理**，仍按 `:60` 走 F-93 后的 `-AllowedCleanDbs` 白名单）。**自查发现并修复调度器自身缺陷 3 项**（`$home` 只读变量、`bad` 空数组导致 `.Count` 缺失、隔离 analytics 计数未按模块归集），明细见泳道 `docs/acceptance/dev003c-unified-test-entry-20260915/REPORT.md`。

### F-88 状态（**必须拆分，不得合并**）

**正式状态：限定验收。**

**已取得的真实写侧／隔离链证据**

1. 写侧代码闭环：`8853730`（总控复核 `37561dc`）—— 四列按「声明／生效」契约落库；`warehouse-pipeline` 实测 134 全绿，含反证用例 `DataQualityGateTest#gateIgnoresBothSeverityColumns:297-333`（两列同时说谎、门禁结论不变）与 `:283-284`（`getEffectiveSeverity()` 不被读侧回填）。语义迁移，非削弱断言。
2. 真库落库：3307 上 `data_quality_result` 四列 **4/4 行非空**，含 1 行实证「声明 WARN／生效 BLOCKING」；V19/V20 真落地（`flyway_schema_history` 19 条、版本 20、`installed_on 2026-09-14 16:26:31`）。
3. 不回填：probe 库四列 `IS_NULLABLE=YES`、`COLUMN_DEFAULT=NULL`、`rows_with_version_info=0`，历史 `severity='WARN'` 3 行仍在。
4. 3306 零写入：泳道前后指纹 27 项逐项全等；总控独立只读复取 `dqr_rows=467`、`v20_cols_on_3306=0`、`max_flyway=18`、`qrd_table_on_3306=0`（即 3306 无 V19/V20 痕迹）。
5. 判定依据：「3307 上 V19/V20 真落地」＋「四列真落库」＋「3306 前后指纹全等」三条，总控已独立复现。

**仍然存在的完整验收缺口（❌，共 8 项，均属未取证；并受 DEV-001～DEV-004 相关缺口约束）**

1. 未登记码分支的真库落库（本 run 0 行；仅有单测覆盖）。
2. ADS_STAGING / PUB / MXP / MP 约 31 条规则的落库（本 run 仅 LANDING 4 条）。
3. 成功／发布路径：链路终态为 `FAILED`，未进入 PUBLISH / METRIC_PUBLISH，`GET /api/v1/metrics/overview` 返回 `data=[]`。
4. 3306 真库上 V19/V20 的执行结果（当前 3306 已冻结，须冻结解除后另行裁决）。
5. V19 的 35 条定义 ↔ 写侧 `rule_version`/`checksum` 的全量映射（仅核到本次 4 行为 v1）。
6. `catalog=qrc-1` 无落库承载列 ⇒ 无法从库内回查目录版本（只在应用日志）。
7. 3306 binlog 位点级零写入证明（未取 before 位点；`performance_schema` 为空仅为旁证）。
8. `analytics-server` 六模块真实测试数（曾阻塞于 D-5；现由默认档 **632** 部分覆盖，口径关系见上）。

**表述边界（总控裁决）**：正式状态保持 **限定验收**；**允许**写「写侧闭环已有实证。」；**禁止**任何「F-88 已通过完整验收」的同义表述，也禁止「规则版本化已闭合」。「写侧闭环有后续证据」≠「F-88 完整验收完成」；「链路按设计在质量闸门阻断、四列确实落库」≠「F-88 已闭合」。

## 关键证据

| 证据 | 证明什么 | 时点 |
|---|---|---|
| `docs/acceptance/dev001-dev002-gate-fix-20260914/`（`REPORT.md`＋`HASH-REPRODUCIBILITY-NOTE.md`＋`raw/` **52 文件**；含 N-3 轮 `n3-*` 11 文件、语义一致性轮 `n4-*` 18 文件〔内含证据清单 `n4-evidence-manifest.txt`，逐份 sha256，其中 14 份与原始日志**逐字节相同、漂移 0**〕、第二套口径清单 `n4-evidence-gitblob-manifest.txt`〔Git blob/LF 口径，52 行，不替代原清单〕） | DEV-001／DEV-002 修复实测 ＋ DEV-002 N-3 收口 ＋ **DEV-002 语义一致性收口**：三层守卫判据统一为 canonical `hostname:port`（mall/generator 两文件逐字节相同）＋真 3307 门禁真链 6 例全绿＋单测 24→**28** 全绿＋mall 30 假红清零＋8 条强制反例实测＋**1 条真跑反例（裸 hostname 在真实链上 fail-closed，30 例全 ERROR 无 DDL/DML）**＋默认档 632／13／106 无回归＋**3306 零写入**（本轮 10 判据项差异 0） | 2026-09-14 20:03／20:10–20:18／20:28–20:44 |
| `docs/acceptance/v26-real-testcounts-20260914/`（`REPORT.md`＋`CONTROLLER-VERIFICATION-20260914.md`＋`raw/`，含 `raw/g1b-03-*.log`、`raw/g3-03-run-isolated-tests.log`、`raw/g4-surefire/TestSuite.txt`） | 默认档 844 实测；IT 实际执行数量；隔离档 exit 7；完整验收五条硬理由；裁决 R-01～R-12、R-C1～R-C3（**隔离档数字为本轮修复前的历史口径**） | 2026-09-14 |
| `docs/acceptance/v26-f88-isolated-chain-20260914/`（`REPORT.md` 33 KB＋`CONTROLLER-VERIFICATION-20260914.md`＋`raw/` 44 文件） | 3307 上 V19/V20 落地；四列落库；V20 不回填；3306 零写入；8 项未取证；jar sha256 `86B39A59…1473` | 2026-09-14 16:2x |
| `docs/acceptance/v26-f88-writeside-20260914/`（`REPORT.md`＋`CONTROLLER-VERIFICATION-20260914.md`） | 写侧闭环 `8853730`；81/156/134 实测全绿；语义迁移非削弱 | 2026-09-14 16:19 |
| `docs/acceptance/v25-e3-isolated-chain-20260914/`（53 文件 / 377 KB） | 隔离真链限定验收；工件 sha256 `7BE717A9…7561`；仅启动期 env 覆盖 | 2026-09-14 14:22 |
| `docs/acceptance/v25-s01-it-safety-20260914/` | IT 直连正式库隐患已消除（拒绝发生在建立连接**之前**）；R-1～R-6 风险面；3306 内容指纹 `452b7223a4a2b9dd0df7f3c883cdb74b` | 2026-09-14 12:30 |
| `docs/acceptance/v25-r01-coverage-20260914/`（`MATRIX.md`＋`README.md`＋`raw/01-identity-git.txt`） | 覆盖矩阵；P-01 指纹不一致（**已裁决：旧 MATRIX 不修改，另建 append-only 勘误证据**） | 2026-09-14 12:08–12:12 |
| `docs/acceptance/local-readiness-20260914.md` | 566 / 564+2 口径；前端 74 / 商城前端 6 | 2026-09-14 |
| `docs/acceptance/v26-authority-adoption-20260914/`、`docs/acceptance/v25-document-release-20260914.md` | 权威采纳对照；V2.5 文档发布逐项 SHA-256 校验 | 2026-09-14 |
| `docs/acceptance/e4-cluster-1000-20260912/`、`m1-5-contract-sync-20260912/`、`ct-batch-20260912/`、`r9-20260911-*` | 集群 1000 事件、契约同步、批处理、历史真实 run（历史时点证据） | 2026-09-11～09-12 |
| 正式库 3306 冻结态（**本次整理未重新连库复核**）：`analytics_metric.metric_snapshot` 12 行、`metric_value` 110 行、ACTIVE 快照 `S20260901_47` version 12、内容指纹 `452b7223a4a2b9dd0df7f3c883cdb74b`、`data_quality_result` 467 行 / 14 列、`flyway` 最高 V18。**总控裁决：暂不切换；后续必须重新申请。** | 生产数据未被迁移/污染；`dqr_rows` 与 V20 头注一致 | 2026-09-14 12:30 / 16:3x |

## 待总控裁决

只放**真正需要项目级决策**的问题：

1. **F-88 完整验收的完成条件**：正式状态保持**限定验收**；完整验收何时、以 8 项未取证中的哪些为必需项判定，须在 DEV-003／DEV-004 相关缺口收敛后另行裁决。
2. **DEV-003 两个新子项的处理时序**：（a）隔离库 Flyway 编排（先迁移再跑用例）；（b）`IsolationGuardMySqlIT` 接入隔离档执行入口。两者是否与「`spark-jobs`／IT 聚合入口」同批，请总控排期。**（已裁决并关闭：a／b 随 DEV-003a／b CLOSED，c 随 DEV-003c CLOSED，d 转开发 backlog；本条保留为历史排期记录。）**
3. **模块内两处陈旧提示是否随提交一并订正**（`mall-simulator/src/test/resources/application-test.yml:32`、**入库文件** `synthetic-data-generator/src/test/resources/it-guard.local.properties:26`，两者仍写 `serverFingerprint=<@@hostname 或 host:port>`）：**本轮授权边界只到 `scripts/` 两个文件，故未改**。按新语义这两处会误导（裸 hostname 一律被拒），方向 fail-closed，不影响运行。
4. **陈旧构建产物是否清理**（`mall-simulator/target/test-classes/mall-isolation.local.properties`，gitignore 覆盖、非源码，内容仍是 L5 负向验证留下的 `enabled=true` / `serverFingerprint=127.0.0.1:3306` / `testRunId=l5probe-20260914-x1`）：本轮 B 层隔离档绿证明运行期以环境变量为准，该文件不是生效来源，且在新判据下双重被拒（裸地址 ＋ 3306）；`target/` 不在本轮边界，未清理。

（已裁决、不再列为待裁决项：566/624 口径关系、历史多 HEAD 时点、P-01 MATRIX、F-88 表述边界、DEV-001/002 修复批准、**DEV-002 语义一致性收口批准（含 mall/generator 判据收紧与两处 `scripts/` 提示订正）**、DEV-004 归入 F-93、3306 冻结、生产 ACTIVE 暂不切换、治理基线提交时点。）

## 下一步建议

1. ~~修 DEV-001 / DEV-002~~**已完成并实测关闭**（2026-09-14 20:03 修复 ＋ 20:10–20:18 N-3 收口 ＋ 20:28–20:44 语义一致性收口）：`SELECT VERSION()` 解析 ＋ 唯一实例身份 `dahaishui:3307`（三处守卫同一判据 `fingerprintMatches(expected, hostname, port)`，uuid 不可替代；裸 hostname／裸端口／`127.0.0.1:port`／`localhost:port` 一律拒）＋ generator `SPRING_DATASOURCE_*` ＋ 8 条反例集。下一步：把隔离库 Flyway 迁移纳入隔离档编排（DEV-003 子项 1），使**新 runId** 上 generator 5 例不再依赖上一轮的残留 schema。
2. ~~建 `spark-jobs` ＋ IT 的聚合执行入口（DEV-003），使默认档之外的 74 个用例可一条命令执行 —— 否则它们永远只会出现在证据目录里；新增的真链 IT（`IsolationGuardMySqlIT`）也应在这一步接入 `-Pisolated-tests`。~~**已完成（DEV-003c CLOSED，2026-09-15 11:5x）**：`pwsh scripts/run-tests.ps1 -Suite all -Confirm` 一条命令顺序跑 `default`（751）＋ `isolated`（55，含 `IsolationGuardMySqlIT` 6）＋ `spark`（111），**exit 0**。**剩余（转开发 backlog，DEV-003d）**：`MetricAdsMySqlIT`／`MetricPublisherMySqlIT`／`SourceRegistryMigrationMySqlIT`（DEV-004）／`SparkStageExecutorSmokeIT`／`AnalysisGoldenMySqlIT`（D 类）／前端项目级入口；未打标 `*IT` 19 个仍无自动入口。
3. 为 P-01 建 append-only 勘误证据（不改旧 MATRIX），说明同路径看板在 MATRIX 生成后发生过变化。
4. 补 V19/V20 的 IT 覆盖（DEV-004，随 F-93）＋ `AnalysisGoldenMySqlIT` 库名参数化（R-05）＋ 唯一权威键名表（R-06）；并按总控裁定决定是否订正模块内两处陈旧提示（见「待总控裁决」第 3 条）。
5. 对外一律使用「默认档 **632** ＋ 四棵树 **862** ＋ 未入默认档的隔离档 49 ／ 点名 IT 用例」的写法，禁止「624／628／632 即全部测试」或「全量全绿」；引用 generator 隔离档数字必须写明「新 runId 首跑 14 通过 / 5 error（DEV-003 子项 1），同库二次跑才 19 全绿」。**（口径已更新：DEV-003c 收口轮起对外统一写法＝「默认档 751（＝632＋13＋106，不含 spark-jobs）＋ 四棵树 862（632／13／106／111，**全部本轮 fresh**）＋ isolated 55（mall 30／generator 19／metric-analysis IT 6）＋ `-Suite all` exit 0」；其中「新 runId 首跑 14 通过 / 5 error、同库二次跑才 19 全绿」已由 2026-09-15 10:4x 口径更新①作废——全新 runId 首跑即 19/0/0/0；「禁止全量全绿」「禁止把 624／628／632 说成全部测试」仍然有效。）**

## 最近工作记录

（倒序；只记可回溯的事实与提交）

- 2026-09-15 11:43–11:51 ｜ 代码 Agent（**未提交、未 push**；本轮唯一动作 **DEV-003c**）｜ **项目级统一测试入口落地并 fresh 验收**：新增 `scripts/run-tests.ps1`（**412 行 / 27,091 B / sha256 `373333C7CC570FE51446AD06457EC70901A50C5838B390CBD7731F86D80956B7`**，`-Suite default|isolated|spark|all`，退出码契约 `0/1/5/6/7` 与既有 runner 对齐）。① **分层**＝「Maven 负责模块内测试选择〔surefire `include`／`groups`／`excludedGroups` 与 `isolated-tests` profile 仍留在各模块 `pom.xml`〕；PowerShell 负责跨模块／跨 JDK／环境与汇总」；**未新增根 `pom.xml`**（仓库仍无根聚合 POM）、**未创建 `.github/**`**、**未改动 `scripts/run-isolated-tests.ps1`**（`git diff --stat` 为空）、**未修改六个 `*IT.java`**、**不使用 `-Dtest=` 点名**（避免「入口自动收集」退化为人工记忆类名）。② **`default` 档**＝顺序 `mvn -f <pom> test`（JDK17，命令语义与既有默认入口逐字一致）跑 analytics-server／mall-simulator／synthetic-data-generator，逐模块硬判据「汇总行 `Tests run` > 0 且 F ＝ 0 且 E ＝ 0 且 exit 0」⇒ **751**（**632**〔89＋156＋134＋48＋91＋114〕＋ **13**〔5＋2＋6〕＋ **106**〔17 个类〕，0F/0E/0S），**不含 `spark-jobs`**。③ **`isolated` 档**＝**直接调度**既有 `scripts/run-isolated-tests.ps1 -RunId <runId> -Module all -Confirm`（不重实现任何隔离逻辑），只做独立复核：**全新 runId `dev003c_20260915_1205` 首跑即全绿** —— mall **30**、generator **19**（`GeneratorMetaStoreTest` 5/0/0/0）、`IsolationGuardMySqlIT` **6**，F/E/S 全 0，runner **exit 0**，合计 **55**；并按「当前构建模块」归集，把同 reactor 依赖构建 `platform-common` 的 **89** 显式排除、打印为被排除项。④ **`spark` 档**＝**显式 JDK8**（`JAVA_HOME=D:\Develop\JDK1.8` ＋ `PATH` 前置，脚本自检 `mvn -version` 含 `Java version: 1.8`）＋ 注入本轮 `-Dp2.test.runId=<runId>`；成功判据**只认** `spark-jobs/target/surefire-reports/TestSuite.txt`：`Total number of tests run` > 0、`failed = 0`、`aborted = 0`、`All tests passed.`，且该文件 **mtime ≥ 本轮启动时刻**；两次跑动均为**本轮新写**且均为 **111**（`Suites: completed 15, aborted 0`）——独立 `-Suite spark`（runId `…1155s`）mtime `11:44:45` sha256 `C1CFC973…`，`-Suite all` 内（runId `…1205`）mtime `11:50:26` sha256 `B4CAEC36…`；JDK8 取证原文 `Java version: 1.8.0_202, vendor: Oracle Corporation, runtime: D:\Develop\JDK1.8\jre`。⑤ **`surefire 假绿` 的 fresh 实证**：同一次 JDK8 运行内 surefire 汇总行为 `[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0`（默认 include `*Test` 只捞到 `MetricAdsSpecTest`，JUnit3 provider 误配 ⇒ 0 用例），而 ScalaTest 真实 **111** 全过——该项把 `docs/acceptance/v25-r01-coverage-20260914/VERIFY-T01-T02.md:60-67` 登记的「矛盾·未取证」钉成 fresh 事实，也是本入口**拒绝**用 surefire 汇总当成功依据的直接原因。⑥ **`-Suite all` ⇒ exit 0**（`default` → `isolated` → `spark`，三档独立摘要不合并计数；11:49:0x–11:50:27，控制台 `raw/20-all-console.txt` 143,077 B）。⑦ **自查发现并当场修复的调度器自身缺陷 3 项**（均在真实验收跑动中被触发；只影响调度器，不影响业务代码与被调度入口）：**(1)** 辅助函数参数名用了 PowerShell 只读自动变量 `$home` ⇒ `无法覆盖变量 home，因为它是只读变量或常量`（脚本 `:107`，`[spark-solo exit=1]`；该次控制台日志被同名文件覆盖，**未留档**，仅本记录引用其原文）；**(2)** `bad = $(if (...) { @() } else { ... })` 的空数组被子表达式展开 ⇒ 属性成 `$null`，StrictMode 下 `$r.bad.Count` 抛「在此对象上找不到属性"Count"」（spark 档本身已判 PASS 却被判 exit 1；留档 `raw/09-spark-console-SELFBUG-rbad-count.txt`）；**(3)** 隔离 analytics 目标为 `-pl metric-analysis -am`，调度器原按全文相加（89＋6＝95）与基线 6 不符 ⇒ 误判 **DRIFT** 并使 `all` `exit 7`，而**被调度的 runner 本身 `exit 0` 且三档全绿**（留档 `raw/19-all-console-SELFBUG-attempt1.txt`＋`raw/19-isolated-analytics-SELFBUG-attempt1.log`）；修法＝按当前构建模块归集计数。⑧ **未收编（不计入通过总数，总控 2026-09-15 裁决）**：`MetricAdsMySqlIT`／`MetricPublisherMySqlIT`（后续测试完善 backlog）、`SourceRegistryMigrationMySqlIT`（DEV-004）、`SparkStageExecutorSmokeIT`（Spark 专项冒烟 backlog）、`AnalysisGoldenMySqlIT`（**D 类，永久排除** unified isolated-tests，保留手工／专项能力）、前端项目级入口（整理阶段范围外）；其余未打标 `*IT` **19 个仍无任何自动入口**（本记录**不声称**「所有历史 IT 已自动化」）。⑨ **边界**：`spark` 档只证明「Scala `local[1]` ＋ in-memory catalog」（`P2TestSupport`）下 111 个 ScalaTest 通过，**不等于**在产 Hive／Spark 集群通过（`spark-hive` 为 `provided`、无 hive-exec，`enableHiveSupport()` 不可用）；3306 **全程只读、未触碰**；未触碰 `contract-specs/**`、其它 `docs/acceptance/**` 泳道、指导书 V2.8、设计 V2.5。⑩ **README 陈旧数字仅登记不重写**（`README.md:35-44`：`303/303`、`46/46`、`74/74`、`54/54` ＋ 4 条 `.verify/*`，与现行口径全部不一致；正式收口放下轮「指导书重写／文档发布」）。⑪ 3307 上本轮遗留 **4** 个 `dev003c%` 库与对应受限账号**未清理**（仍按 `:60` 走 F-93 后的 `-AllowedCleanDbs` 白名单，禁止手写 `DROP`）。⑫ 证据：`docs/acceptance/dev003c-unified-test-entry-20260915/`（`REPORT.md` ＋ `raw/` 20 文件，含 `evidence-manifest.txt`；新增脚本敏感串扫描 **0 命中**、脚本不提供 `-Password`）。**F-88 仍为限定验收，完整验收仍 ❌。**
- 2026-09-15 10:30 ｜ 代码 Agent ｜ **证据复现口径补记（纯证据说明 commit，不改任何测试／验收结论）**：`core.autocrlf=true` 且仓库无 `.gitattributes` ⇒ 库内 blob 行尾归一为 **LF**；泳道 52 文件中 **50 份**入库前工作区为 **CRLF** ⇒ blob 字节比工作区少 CRLF 字节（差异合计 **5,548 B**，**内容差异 0**、行一一对应），**2 份**（`REPORT.md`、`raw/layer4-3307-guard-facts.txt`）blob 字节 = 工作区字节。因此原 `raw/n4-evidence-manifest.txt` 的 SHA256 是 **Windows 工作区（`autocrlf=true` 检出）口径**，**不是跨平台 Git blob 口径**。新增：① `HASH-REPRODUCIBILITY-NOTE.md`（写明两套口径、跨平台复核规则 A／B〔A：在 `autocrlf=true` 检出复算原清单，本泳道 50/52 逐份应一致；B：对 `git cat-file blob` 内容算 SHA256 或取 `git rev-parse` 对象 id 并注明「Git blob/LF 口径」，不得与原 worktree 哈希混用〕、以及不得仅凭哈希不等判定证据被篡改）；② `raw/n4-evidence-gitblob-manifest.txt`（第二套清单，52 行，`work_bytes`／`work_sha256` 与 `blob_bytes`／`blob_sha1`／`blob_sha256` 并列，基线 `ffbd996`，**不覆盖原清单**）。往返实测（删除工作区文件后 `git checkout -- <path>` 还原）5 个样本 SHA256 与原清单逐份一致：`n4-3306-readonly-recheck.txt` `F9363A01…C3EDBA`、`n4-mall-default-tier-maven.log` `EF0C31D8…4C7494`、`n4-isolated-mall-newrunid.log` `43263CA1…2035DDE`、`n4-isolated-generator-newrunid.log` `F8BBE025…EB0844`、`n4-3307-inventory-before.txt` `1267BDAA…9F87EC`。**实测发现的例外（已登记）**：LF 原样文件会被 `autocrlf=true` 的 checkout 写成 CRLF（`REPORT.md` 47,289 B／`B9C9C47D…`），故这 2 份须按 B 口径复核；该副作用**已还原**为 46,872 B／`6D53F5A5A51135615625BE1B56B580CAE15595C5ECC2AD697BF11E731579D29027`（blob id `ea1bf615…` 前后未变、`git diff` 为空、stat 刷新后状态干净），还原后 **52/52** 工作区字节与登记值复核一致。**未改动**：原 `REPORT.md`、原 `n4-evidence-manifest.txt`、其余 `raw/**`、任何代码、runner、指导书 V2.8、项目设计文档 V2.5。**DEV-001／DEV-002 结论不变。**
- 2026-09-15 10:34–10:45 ｜ 代码 Agent（**已按总控批准分两个 commit 落库**：CODE_COMMIT `deba29f`（5 个代码/配置路径）＋ EVIDENCE_COMMIT（本文件与泳道 16 文件）；`git status --porcelain` 落库后 0 行）｜ **DEV-003 子项 1／2 收口轮（本轮唯一动作，5 路径：4 改 1 新）**：① **DEV-003a（generator 首跑 schema 编排）** —— 修前先在全新 runId `dev003a_20260915_1040` 复现红案（`19 run / 5 error`、runner exit 7、首个 `doesn't exist` 日志 `:56`、Flyway 直到 `:284` 才跑 ⇒ 归因「执行得太晚」）；修法＝新增 `src/test/java/com/graduation/itguard/IsolatedSchemaInitializer.java`（`BeforeAllCallback`：门禁 → Flyway → 表存在性自检，**不预建表／不复用旧库／不 catch／不跳过／不写死顺序**）＋ `GeneratorMetaStoreTest` 上 `@ExtendWith`（`:74`）＋ DEV-003a 说明（`:57`）；**未改 main 源码**。② **DEV-003b（analytics 真库 IT 接入统一入口）** —— `metric-analysis/pom.xml` 加 `<properties>v25.it.excluded.groups=it</properties>`（`:78`）＋ surefire `excludedGroups`（`:90`）＋ profile `isolated-tests`（`:100`，`<groups>it</groups>`（`:113`）＋ `<includes>**/*IT.java</includes>`（`:117`））；`IsolationGuardMySqlIT.java` 加 `@Tag("it")`（`:47`）与入口 javadoc（`:35`）；`scripts/run-isolated-tests.ps1` 加 `-Module analytics|all`、analytics 目标（复用 `${RunId}_mall`／`${RunId}_mallapp`）、`DEV001_IT_*` 注入（`:312`）与**零用例硬门禁**（`:334-342`）。③ **实测（全新 runId `dev003_20260915_1110`，pre 取数两库均 0 表／0 历史）**：标准入口 `-Module all -Confirm`（**无 `-Dtest=`**）三档全绿 —— mall **30**、generator **19**（首跑即 19，`GeneratorMetaStoreTest` 5/0/0/0）、analytics `IsolationGuardMySqlIT` **6**，F／E／S 全 0，runner **exit 0**；首跑后 `_generator` 6 表、`flyway_schema_history` ＝ `1 | generator meta | 2026-09-15 10:42:04 | success 1`。④ **回归（默认档）**：analytics-server **632**（89＋156＋134＋48＋91＋114，逐模块与上一轮一致）、generator **106**、mall **13**，退出码均 0，三棵树默认档 `*IT` 选中数 0；`spark-jobs` **111 本轮未复跑（沿用既有口径）**。⑤ 3306 **全程只读、零写入**；未新建 `.github/workflows/**`、未接 CI；未触碰 `contract-specs/**`、已有 `docs/acceptance/**` 其它泳道、指导书 V2.8、设计 V2.5。⑥ 证据：`docs/acceptance/dev003-isolated-entry-20260915/`（`REPORT.md` ＋ `raw/` 12 文件，含 `evidence-manifest.txt` 与 `05-sensitive-scan.txt`：**明文口令 0 命中**）。⑦ 自查中当场修掉的自身缺陷 3 处（锚点手抄 0 匹配／多写一个 `}` 致 `Parser` 报错／`-Module all` 初版漏选 mall+generator），均已在正式取证前修掉并复检。**DEV-003 主条目（`spark-jobs` 111 自动入口）与 19 个未打标 `*IT` 仍未闭合；F-88 仍为限定验收。总控 2026-09-15 复核裁决：DEV-003a／b 已关闭，DEV-003c（`spark-jobs` 111 自动入口）／DEV-003d（其余未打标 `*IT`）登记为 OPEN，DEV-003 总体保持开启。**
- 2026-09-15 10:13 ｜ 代码 Agent ｜ **DEV-001／DEV-002 泳道正式落库（两个 commit，`git add -A` 未使用、逐路径显式 add）**：**commit 1 ＝ `db77654`**（`fix(it-guard): close DEV-001 DEV-002 isolation guard defects`，10 路径／＋1102 −50：analytics `TestIsolationGuard.java`＋`TestIsolationGuardTest.java`＋新增 `IsolationGuardMySqlIT.java`、`scripts/run-isolated-tests.ps1`、mall／generator 两份 `IsolationGuard.java`、mall／generator 两份新增 `IsolationGuardFingerprintTest.java`、`scripts/it-prepare-isolation.ps1`、`scripts/it-isolation.env.template`）；**commit 2 ＝ 本文件 ＋ `docs/acceptance/dev001-dev002-gate-fix-20260914/**`（52 文件）**。落库前自查：① lane **52 文件全量**敏感词扫描（`password`／`passwd`／`pwd=`／`MYSQL_PWD`／`Authorization`／`Bearer`／`token`／`secret`／`123456`／`apiKey`／`access_key`／私钥头 等）——**无明文口令／token／secret，无需脱敏、无需改标 [脱敏副本]**（命中仅掩码形态 `<3******…长度 24>`、引用名 `credref:…`、权限名 `APPLICATION_PASSWORD_ADMIN`、JVM 属性）；② `git check-ignore` lane 内**无一被忽略**；③ 「862」经总分项复算确认非笔误（＝852 ＋ 本轮 2×5 个不带 `@Tag` 的反例单测，逐类原文已登记入「测试与验收状态」），总控裁决保留 862、852 标为 N-3 收口前口径不回改；④ 两个 commit 后 `git status --porcelain` 为 **0 行**，随后 push 到 `origin/main`。**总控 2026-09-15 复核裁决：DEV-001 已修复并实测关闭；DEV-002 已修复并实测关闭；DEV-003 ＝ 下一轮主任务；F-88 继续限定验收。**
- 2026-09-15 08:52–08:55 ｜ 代码 Agent（**未提交、未 push**）｜ **证据完整性自查（只补证据、不改结论）**：① `raw/n4-*` **18 文件**中 14 份原样入库日志与 `%TEMP%\dev002n4-logs` 逐份 `Get-FileHash -Algorithm SHA256` 比对 —— **14/14 相同、漂移 0**；`n4-3306-readonly-recheck.txt` 恢复为机器输出原样（433 B，sha `F9363A01…C3EDBA`），09-14 追写的比对结论行**已撤回**，结论移入清单与 `REPORT.md` §12.10。② 新增 `raw/n4-evidence-manifest.txt`：逐份「文件名｜字节｜sha256｜入库方式（[原样]/[节选]/[生成]）」，并声明原始负例日志 871,033 B 未入库、入库的是节选。③ **报告数字 vs 原始证据逐条回查 25 项判据＝24 PASS / 1 FAIL**，FAIL 是**匹配式写错**而非结论错：多模块 `mvn test` **没有 reactor 级汇总行**，「632」只能由 6 条逐模块 `Results:` 汇总行相加得到 —— 已复算 `89＋156＋134＋48＋91＋114＝632`（F/E/S 全 0），且与 N-3 轮日志 `Compare-Object` **逐模块差异 0**。④ 未取证项**不升级**（8 条反例中 7 条仅单测级；跨库写权限未在真 3307 实测；`IsolationGuardMySqlIT` 无标准入口；`spark-jobs` 111 未复跑）。修改文件：`docs/PROJECT_STATUS.md`、`REPORT.md` §12.10、`raw/n4-evidence-manifest.txt`（新增）。
- 2026-09-14 20:28–20:44 ｜ 代码 Agent（**未提交**）｜ **DEV-002 语义一致性收口（本轮唯一动作，6 文件：4 改 2 新增）**：① mall／generator 两份 `com.graduation.itguard.IsolationGuard.fingerprintMatches`（`:420-425`）由「`hostname` 或 `hostname:port` 或裸 `port`／`127.0.0.1:port`／`localhost:port`」收紧为**与 analytics 侧同一判据**——登记值与实连 `@@hostname:@@port` 规范化（trim ＋ hostname 段忽略大小写）后必须完全相等，缺值／空值／`port<=0` 一律 fail-closed；两文件仍**逐字节相同**（sha256 前 16 位 `35006DE37D34E8D4`，459 行），失败消息（`:382`）与文档头（`:39`）同步订正；端口白名单未动（两层互不兜底）。② 新增同包反例集 `IsolationGuardFingerprintTest`（mall／generator 各一份、逐字节相同、5 `@Test`／25 断言、**不带 `@Tag` ⇒ 只进默认档，不污染隔离档 30／19 口径**）。③ `scripts/it-prepare-isolation.ps1:199` 与 `scripts/it-isolation.env.template:31-34` 陈旧提示统一为 canonical `IT_GUARD_SERVERFINGERPRINT = hostname:port`（例 `dahaishui:3307`），并写明 `@@server_uuid` 是门禁 6 的独立漂移事实、**不是** fingerprint 替代值；两文件 `Parser` 解析错误数 0。**实测**：`TestIsolationGuardTest` **28/0F/0E/0S**；新 runId `dev002sem_20260914_2035` 隔离档 mall **30/0F/0E/0S** exit 0、generator **19 run / 0F / 5E / 0S**（5 error 全在 `GeneratorMetaStoreTest`，非 DEV-003 的 14 例全绿）⇒ runner `exit 7`，**指纹假红 0**；真 3307 真链 `IsolationGuardMySqlIT` **6/0F/0E/0S**；默认档 analytics-server **632/0F/0E/0S**（逐模块与 N-3 轮相同）＋ mall **13**（8→13）＋ generator **106**（101→106）；8 条强制反例逐条实测通过 ＋ **真跑反例**（`IT_GUARD_SERVERFINGERPRINT=dahaishui` 裸 hostname 在真实链上 `[flyway-before-migrate]` 即 fail-closed，30 例全 ERROR、无一条进入 DDL/DML）；3306 只读复取 **10 判据项差异 0**。**DEV-003 归因修正**：新 runId 首跑 5E 的机制不是「Flyway 未执行」，而是「Flyway 执行得太晚」——`GeneratorMetaStoreTest`（无 Spring 上下文）先跑（首次 `doesn't exist` 日志第 56 行），Flyway 直到 `@SpringBootTest` 才跑（第 284 行起，`20:32:18.241` 完成迁移，5 张表 `CREATE_TIME` 全为 20:32:18）⇒ 首跑必红、同库二次跑才绿，归 **DEV-003 子项 1**。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n4-*`（18 文件，含证据清单 `n4-evidence-manifest.txt`）＋ `REPORT.md` §12（含 §12.10 证据完整性自查，**09-15 08:5x 补记**）。**未提交、未 push。**
- 2026-09-14 20:10–20:18 ｜ 代码 Agent（**未提交**）｜ **DEV-002 N-3 收口（2 文件）**：`TestIsolationGuard.fingerprintMatches` 由 `(expected, serverUuid, hostname)` 改为 `(expected, hostname, port)` —— 唯一权威实例身份固定为 `@@hostname:@@port`，规范化（trim ＋ hostname 段忽略大小写）后必须完全相等；**`@@server_uuid` 不再是替代合法值**（登记值填 uuid、即便实际 uuid 逐字相同也拒绝），改由 `requireServerUuid()` 作独立 fail-closed 漂移事实（取不到即拒；仍计入 `LiveFacts.sha1()`，实测 3307 的 `fingerprintSha1=f228772c…` 与本轮前一致）。端口白名单未动。新增/改写单测：`TestIsolationGuardTest` 24 → **28**（含四条强制反例：同机 3306／异机 3307／uuid 冒充指纹／缺 uuid fail-closed，及「端口门禁与指纹门禁两层独立」用例）。复跑：单测 **28/0F/0E/0S**；mall 隔离档 **30/0F/0E/0S**；generator 隔离档 **19/0F/0E/0S**、runner **exit 0**（**态依赖**：隔离库表为 19:57 上一轮 Flyway 所建，本轮 `No migration necessary`，新 runId 仍会 5 error）；真 3307 真链 **6/0F/0E/0S**；默认档 **632/0F/0E/0S**（628＋4，全部来自 platform-common 85→89）；3306 只读复取 **16/16 行与 post 快照逐行一致**。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/raw/n3-*`（11 文件）＋ `REPORT.md` §11。**未提交、未 push。**
- 2026-09-14 20:03 ｜ 代码 Agent（**未提交**）｜ **DEV-001／DEV-002 门禁修复泳道交付**：改 3 文件（`TestIsolationGuard.java`、`TestIsolationGuardTest.java`、`scripts/run-isolated-tests.ps1`）＋ 新增 1 文件（`analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/IsolationGuardMySqlIT.java`）。实测：单测 **24/0F/0E/0S**；mall 隔离档 **30/0F/0E/0S**（30 个假红清零，exit 0）；generator 隔离档 **19/0F/5E/0S**（14 转绿，5 个 `GeneratorMetaStoreTest` 因隔离库表不存在）；真 3307 门禁真链 **6/0F/0E/0S**（四类判据真执行；旧写法 1193 现场复现；3306 URL 与禁止库在建连前即拒）；默认档回归 **628/0F/0E/0S**（624＋4 个新单测）；**3306 零写入**（15 项 pre/post 判据全一致）。证据：`docs/acceptance/dev001-dev002-gate-fix-20260914/`（`REPORT.md`＋`raw/`）。未取证项与越界项已显式登记（真库跨库写权限反例未测；两侧指纹词表仍两套；DEV-001b/001c 属越界附带修复待裁）。（总控 20:1x 复核：DEV-001 通过可关闭；DEV-001b/001c 纳入 DEV-001；DEV-002 待 N-3 收口。）
- 2026-09-14 19:29 ｜ `docs(status): 建立 PROJECT_STATUS 当前项目状态单一入口` ｜ 本文件首版终态：按总控裁决落实 566/624 口径说明、多 HEAD 时点说明、P-01 移入已知证据问题、F-88 表述边界（限定验收）、DEV-001／DEV-002 批准立即修复、DEV-004 归入 F-93、3306 冻结、生产 ACTIVE 暂不切换。（提交哈希见 `git log`；沿用 `5180baf` 先例，不把自指哈希写入本文件。）
- 2026-09-14 19:28 ｜ `11919ed` ｜ `docs(治理): 发布指导书 V2.8 与项目设计文档 V2.5 —— 权威索引与冲突收口` —— 仅 4 个文件（`README.md`、`docs/README.md`、`docs/毕业设计指导书 V2.8.md`、`docs/design/项目设计文档 V2.5.md`）。**自此指导书 V2.8 与项目设计文档 V2.5 为正式已发布版本，禁止再次修改。**
- 2026-09-14 19:2x ｜ 代码 Agent ｜ 创建 `docs/PROJECT_STATUS.md` 首版；采集 HEAD／工作区、两份治理基线指纹、`*IT` 与 surefire 配置实况、566/624 冲突原文、F-88 8 项未取证、P-01 与多 HEAD 冲突。未跑测试、未改代码、未动 `docs/acceptance/**`。
- 2026-09-14 18:41 ｜ `198e0c5` ｜ 治理：指导书 V2.7 与设计文档 V2.4 —— 三文件治理与写入权限。
- 2026-09-14 17:12 ｜ `d991fea` ｜ 真实测试数泳道交付 ＋ 总控独立复核裁决（844 / 624 口径来源）。
- 2026-09-14 16:38 ｜ `064b733` ｜ 总控独立复核 F-88 隔离真链（直连复取四列 / 不回填复测 / 3306 只读 / jar 成分；裁决 7 项）。
- 2026-09-14 16:36 ｜ `b26e92d` ｜ F-88 隔离真链交付（3307 上 V19/V20 落地 ＋ 四列真落库 ＋ 3306 零写入）。
- 2026-09-14 16:22 ｜ `37561dc` ｜ 总控独立复核 F-88 写侧（复跑 81/156/134 全绿；8 项待裁）。
- 2026-09-14 16:19 ｜ `8853730` ｜ `fix(dq)`：F-88 写侧闭环 —— V20 四列按「声明/生效」契约落库。
- 2026-09-14 15:52 ｜ `00a38e8` ｜ `feat(v26-s02)`：隔离档模板化落库 ＋ 环境变量唯一真相源。
- 2026-09-14 14:22 / 15:2x ｜ E3 隔离真链（L4）交付 ＋ 总控独立核验（`v25-e3-isolated-chain-20260914/`）。
- 2026-09-14 12:08–12:38 ｜ V25-R01 覆盖矩阵 ＋ V25-S01 IT 安全取证（DEV-001 / DEV-002 的首次登记来源 K-10 / K-11）。

## 历史编号勘误

- 编号空间（自本文件首版生效）：`K-xx` ＝正式设计／治理问题编号，**仅总控**可在指导书／项目设计文档中定义；`DEV-xxx` ＝本文件（代码 Agent）使用。**历史 acceptance 中的旧编号原文不回改**，也不得据历史 `K-10…K-13` 认为指导书／设计文档的 `K-xx` 号段被占用。
- 映射（本轮首次建立，**以此为准**）：

| 历史临时编号 | 现编号 | 事项 | 首次登记来源 |
|---|---|---|---|
| K-10 | **DEV-001** | MySQL 8 隔离门禁预检查失败（`TestIsolationGuard:511 SELECT @@version_major` 必败 ⇒ 安全判据 `:521/:524/:531/:534` 在真库上从未执行） | `docs/acceptance/v26-real-testcounts-20260914/CONTROLLER-VERIFICATION-20260914.md` R-02 |
| K-11 | **DEV-002** | 隔离指纹／配置词汇表不对称（runner 注入 `server_uuid`，mall 门禁不认 ⇒ 30/30 假红） | 同上 R-03 |
| K-12 | **DEV-003** | IT／`spark-jobs` 自动执行入口缺失（111 个 ScalaTest 与 19 个 DB 用例无自动入口） | 同上 R-08 |
| K-13 | **DEV-004** | 迁移 IT 与 V19/V20 覆盖缺口（`SourceRegistryMigrationMySqlIT` 双重必败；V20 迁移无 IT 覆盖）→ **继续归入 F-93** | 同上 R-04 |

- 其他勘误与口径订正：
  - 「K-08 可关闭」（R-07）：`spark-jobs` 主树实测 111/15，但任何以 `Tests run:` 汇总该模块的做法必得 0 或漏算 ⇒ 口径并入本文件「测试与验收状态」。
  - 总控自我勘误（R-09 / `CONTROLLER-VERIFICATION` §4-1）：4 个 IT「绿构建·零执行」是 `IsolationProfileCondition` 的**设计行为**，**不是**门禁失效；真正的门禁失效是 DEV-001。
  - 证据目录日期 ≠ 跑动日期 ≠ 工作树（R-C3）：`GraduationProject-wt\m3-jdk8fix` 的 09-12 旧跑动（日志 `Finished at: 2026-09-12T16:12:17`）**不得引用为当前主树值**。
  - 「首跑错误值被复跑覆盖」（`15-seed-runtime-profile.ps1`）＝已采认的证据残缺项，不得据此推断脚本当前行为。
  - 本文件中所有「未取证」条目，在取得证据前**不得**改写为通过。

</details>
