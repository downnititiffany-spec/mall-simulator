# S2-06（DWS/ADS）设计差异请求与事实登记

- 任务：S2-06（DWS/ADS 主链）｜分支：`feature/v3-development`｜施工前 HEAD：`461a172`
- 本文件性质：**只登记事实 + 请求裁决**，不改动两正式文档（指导书 §12 L266-275 规则 4：Code Agent 只提交设计差异请求）。
- 证据口径：`已实测` = 有本轮真实运行的日志/断言；`只读代码事实` = 逐行读代码/DDL 得到（附文件与行号），未运行验证。
- 边界声明：下列第 1 项涉及**已发布正式能力的列集变更**，属真决策门（门 ②/⑥）邻域，本轮**不自行实施**，仅登记并请求裁决；第 2、3 项经**逐行核对代码**后已**修正为"分工核对"而非"缺口"**（本轮原拟按缺口登记，核对后不成立，更正保留在案）；第 4 项不触碰 `contract-specs/**`，为跨模块加法实现，已在下方给出拟定方案。

---

## 请求 1（门 ②/⑥ 邻域）：`ads_user_profile` 缺 R/F/M 原值与观察窗口列

**设计依据（只读文档事实）**：
- 设计文档 §11.4 L447：「必须展示评分规则，**记录 R/F/M 原值、score、segment、窗口、rule_version，不仅存标签**」。

**现状（只读代码事实）**：
- `warehouse/ddl/04-ads.sql` L105-118 `ads_user_profile` 列集 = `user_id, r, f, m, value_group, active_level, favorite_category, last_active_date, last_buy_date, lifecycle_state, rule_version, calc_date` —— **无 R/F/M 原值列，无 `period_start`/`period_end`**。
- `spark-jobs/src/main/scala/com/graduation/analytics/sql/AdsSql.scala` `userProfile` 只产出上述列，`rule_version` 固定 `'rfm-v1'`（`calc_date` 为业务日期，不是观察窗口）。
- 原值与窗口在**下一层已经存在**：`DwsSql.userTradePeriod`（`spark-jobs/src/main/scala/com/graduation/analytics/sql/DwsSql.scala` L141-147）写 `last_buy_date, order_count, sale_amount, period_start, period_end`。
- 导出侧列集由 `MetricAdsSpec`（`spark-jobs/.../metric/MetricAdsSpec.scala`）固定，Java 侧 `MetricExportManifest.TableExport.columns` 逐列消费（`analytics-server/metric-analysis/.../MetricExportManifest.java` L18-24）。
- **指标库镜像同样没有这些列**（`MetricAdsCatalog.java` L44-47：`ads_user_profile_m` = `user_id,r,f,m,value_group,active_level,favorite_category,last_active_date,last_buy_date,lifecycle_state,rule_version,calc_date`，与 Hive 侧 12 列逐一对应）。
- **服务层已把这个缺失显式降级**：`RfmService.java` L53「`ads_user_profile_m` 只有 m **分**（1..5）没有消费额列，用 m 分求和冒充金额属于造数」；`AnalysisViewModel.java` L49「八类消费额无法从指标库取值（不用 m 分冒充金额）」。⇒ 缺列**不是**靠"不冒充"就能消除的表征问题，而是"画像明细不可追溯原值"。

**为何不自改**：补齐这 5 列需同时改 Hive DDL、`MetricAdsSpec` 导出列、Java 发布清单/写入器、MySQL `ads_user_profile_m`（含 NOT NULL 约束）与 Flyway 迁移 —— 其中 MySQL 镜像属**已发布正式能力**（改列集 = 门 ②/⑥ 邻域）。

**请求裁决**：(a) 是否将上述 5 列作为 V3.0 正式列集补齐（含新 Flyway 迁移与镜像列）；(b) 若补齐，`period_start`/`period_end` 是否沿用 `userTradePeriod` 的观察窗口口径（默认 `dt` 与显式窗口两种调用路径，见 `FunnelAdsJob.scala` L32-33/L47）。

**本轮影响**：不阻断 DWS/ADS 主链（DWS 已保存原值与窗口，ADS 缺失只是"画像明细不可追溯原值"）。若裁决为"补齐"，建议独立子任务（跨 Hive/MySQL/Java/Scala 五处 + 迁移）实施。

---

## 请求 2（**核对后修正：不是缺口，是跨层所有者问题**）：§12.3 L512 三项独立检查

**设计依据（只读文档事实）**：设计文档 §12.3 L512：「付款 vs 订单、订单项公式、DWD/DWS 对账三者独立，不能用一个 `AMOUNT_RECONCILE` 覆盖」；同义原文另见 §7.3.1 L526（被 `QualityRuleCatalog` KDoc L54-59 逐字引用）。

**核对结果（只读代码事实，本轮逐一核对）**：
- 三项**已各自一个独立规则码**并已在**平台层**落地：`QualityRuleCatalog.java` L61/L63/L65 —— ①`AMOUNT_RECONCILE`（付款 vs 订单总额，Landing）、②`ORDER_ITEM_AMOUNT_FORMULA`（订单项公式，DWD）、③`DWD_DWS_AMOUNT_RECONCILE`（DWD↔DWS 金额，DWS）；KDoc L54-59 明写「三个码不可合并、不可互相替代，也不能共用一个测试用例」。
- `RuleSeverityTest.java` L249-274 断言三者**独立存在**且逐码有独立测试（该套件在本轮 default 门禁中通过）。
- 另有 `ADS_DWS_FUNNEL_RECONCILE`（ADS↔DWS 跨层漏斗对账；`QualityRuleCatalog.java` L79/L164、`RuleSeverity.java` L129 = BLOCKING）。
- Spark ADS 侧 `AdsSql.dataQuality` 亦产出同码 `AMOUNT_RECONCILE`（逐订单 `order_amount` vs `paid_amount`）。

**修正说明（如实留痕）**：本轮起初拟登记为「ADS 大盘只有一项 ⇒ 应补两个规则码」。**该结论不成立**——它是在未核对平台 catalog 前写下的推断，核对后确认三项已独立实现。真实待裁决点随之变为**所有者/归属**问题。

**请求裁决**：(a) 「①平台 catalog 定义规则码 + ②各层（Landing/DWD/DWS/ADS）分别产出事实行」这一分工是否即为设计本意？(b) 若是，同一个 `AMOUNT_RECONCILE` 由平台 catalog 定义、又由 Spark ADS 大盘产出，是否需要**归属声明**以免被读成"ADS 侧只有一项 ⇒ 缺两项"？本项若仅为文档落点，则**不需新增规则码**。

---

## 请求 3（**核对后修正：已在平台层实现，需确认分工**）：§12.4 L518「超阈值升阻断」

**设计依据（只读文档事实）**：设计文档 §12.4 L518 对 `EVENT_ID_UNIQUE` 规定历史 `dupRateMax=0.0005`（金标测试不得抬高），**超阈值升阻断**。

**核对结果（只读代码事实，本轮逐一核对）**：
- 平台质量门**已实现超阈值升阻断**：`QualityChecker.java` L129-132 —— `withinThreshold = rate <= DUP_RATE_MAX`，未超记「观察项」、超阈值记「阻断」。
- 有配套测试且本轮通过：`QualityCheckerSeverityTest.java` L66（未超不阻断）/ L86（超阈值阻断）、`DataQualityGateTest.java` L252/L266、`PipelineServiceTest.java` L605（超阈值内观察项仍发布）/ L631（超阈值阻断发布）。
- 阈值已进规则目录：`QualityRuleCatalog.java` L152-154 —— `PUB_DQ_EVENT_ID_UNIQUE` 的 `severity_mode = THRESHOLD_OBSERVATION`、`threshold_json = {"dupRateMax":0.0005,"dedupDeterministic":true}`；`QualityRuleDefinition.java` L93 强制「THRESHOLD_OBSERVATION 必须给出 thresholdJson」。
- Spark ADS 侧 `AdsQualityJob` **刻意**把 `PUB_DQ_EVENT_ID_UNIQUE` 记为 `ERROR` 非阻断（KDoc 注明与 Java `QualityChecker.corePassed` 口径一致）。

**修正说明（如实留痕）**：本轮起初拟登记为「§12.4 未实现」。**该结论不成立**——同样是在未核对平台质量门前写下的推断，核对后确认实现完整且有测试。

**请求裁决**：确认「阈值升级只在平台质量门执行、Spark ADS 侧只报事实不阻断」为设计本意（以免 Spark 侧也升阻断而形成**两个阻断所有者**）。若确认，本项**无需改动**。

---

## 请求 4（非决策门，跨模块加法实现；仅请求确认无契约冲突）：§12.5 L528 的 `checksum` 未实现

**设计依据（只读文档事实）**：设计文档 §12.5 L528 发布链：「`mxp` 导出 + **manifest/checksum** → MySQL BUILDING/staging → …」。

**现状（只读代码事实）**：
- Spark 侧 `MetricExportJob`（L93-105）写出的 `_export.json` 字段 = `snapshotId, businessDate, dt, source, generatedAt, totalRows, tables[{hiveTable, mysqlTable, rowCount, columns, hivePath, exportFile}]` —— **无任何 checksum/摘要字段**。
- Java 侧 `MetricExportManifest`（L18-24）与之同形，`read()`（L31-60）只做"必备字段存在性"校验；`MetricPublishValidator` 现有的导出文件相关校验**只有存在性与行数**：`MP_EXPORT_FILES` 判 `Files.isRegularFile(t.exportFile())`（L86-88，文件缺失计数）、发布前 `written != t.rowCount()`（L114-118）、发布后 `db != t.rowCount()`（L211-214）。⇒ **全链路无任何内容摘要**：文件内容被改动而字节数/行数不变时，任何现有检查都不会发现。
- `contract-specs/` 现有制品（`README.md`/`VERSION` + `openapi/generator-api.v1.yaml` + `schemas/`{canonical-event.v1, generation-artifact-manifest.v1, ingestion-manifest.v1} + `specs/`{surrogate-key.v1, warehouse-namespace.v1, warehouse-namespace.v2}）**均不覆盖** `_export.json`（逐个核对目录内容）⇒ 本项**不触碰** `contract-specs/**` 既有正式契约语义。另已核对 `ingestion-manifest.v1` 的 `checksum` 口径 = `Long.toHexString(CRC32)`、正则 `^[0-9a-f]{1,8}$`（不补前导零），与下列方案 1 的摘要口径一致（仅借用算法，不改该契约）。

**拟定方案（加法，不改既有键语义）**：
1. Spark `mxp` 逐表计算导出 JSONL 的内容摘要（按行读取字节流，CRC32 十六进制，与已有 `ingestion-manifest.v1` 的 `checksum` 口径一致），写入清单该表的 `checksum` 键；
2. Java `MetricExportManifest.TableExport` 增 `checksum` 字段，`read()` 保持"缺失即 IOException"；
3. `MetricPublishValidator` 新增发布前校验：重算导出文件摘要与清单比对，不一致 = BLOCKING（不写库、不切 ACTIVE）；
4. 两侧各有失败用例（改一个字节 → 发布被阻断）。

**请求**：确认第 3 步的"摘要不一致即阻断发布"落在 `MetricPublishValidator`（发布侧唯一所有者）无异议；若无异议，本方案由 Code Agent 在后续子任务按 TDD 实施（属加法能力，非决策门）。

---

## 附：本文件未涵盖的内容

- S2-06 的两处**已实测**缺陷（ADS 稳定次序键缺失）与本轮修复结果，见 `docs/status-history/开发过程事实与决策记录.md` 的 F-33 记录与 `.verify/v3-stage2/s2-06/**` 原始日志。
- 请求 1 在裁决前不影响现有主链运行（DWS 已存原值与窗口），但会使"评分规则不可追溯"永久化；请求 2/3 经核对**不构成缺口**，仅需总控确认分工归属；请求 4 为加法能力，待确认后按 TDD 实施。
- **口径备忘**：请求 2、请求 3 的初版写成"缺口"，是在未核对平台 catalog／质量门前写下的推断。本轮已按「未实测不写结论」更正，并把更正过程留在本条与 F-33 中（不隐藏、不重写历史）。
