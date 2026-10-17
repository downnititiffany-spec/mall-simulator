# WP-F8c（F-88 真库探针类关账）总控亲跑报告

> 执行：2026-10-17 巡检轮，总控亲跑（非 agy 包）。范围：F88-GAP-01 未登记码真库落库探针、F88-GAP-02 成功 run 全层 V20 四列抽验、F88-GAP-06 目录版本可追溯性查验，外加 WP-F9a 遗留的 AnalyticsIsolationFlywayIT V14 真跑。红线遵守：仅 3307、未碰 3306、未用 Docker、未拨 DeepSeek API、无密钥落盘/打印。

## GAP-02 全层四列抽验（live 库 live20261016_analytics_meta，成功 run 3/4/5/7）——通过（判据经总控勘误）

- 成功 run 的 `data_quality_result` 共 121 行（run 3/4/5 各 30 行、run 7 共 31 行；每 run 分层：LANDING 4、DWS 1、ADS_STAGING 9、PUBLISH 16，run 7 的 PUBLISH 为 17）。
- 121 行的 V20 四列（rule_version / effective_severity / compat_policy_version / rule_fingerprint）全部非空。
- 以 (rule_code, rule_version) 与 `quality_rule_definition` 做 join：**0 失配**；FIXED 模式规则的 effective_severity 与定义 severity **0 失配**；全部行 compat_policy_version='compat-v1'、rule_fingerprint 为单一值 `92dc30c6b1901d8a569b79fe48780ca03d204bfbee542f5ac6957da12f2c72c9`（64 位 hex）。
- **勘误**：盘点报告的判据写作「rule_version="v1"、rule_checksum 长度 64」与实现不符——rule_version 落的是逐规则整数版本（"1"/"2"/"3"，如 ADS_STAGING_PRESENT 已升至 v3、MXP_EXPORT_COMPLETE 为 v2，与定义表多版本行精确对应）；64 位校验串是目录级 rule_fingerprint（逐规则 checksum 在定义表 checksum 列，同为 64 位）。按修正判据抽验全过，GAP-02 关账。

## GAP-06 目录版本可追溯性（catalog=qrc-1）——通过（证据载体经总控勘误后裁定关闭）

- **勘误**：盘点假设「阶段证据 JSON 含 ruleFingerprint/catalog」不成立——总控查全部 run 的 `pipeline_stage_run.evidence`，无任何阶段证据含 fingerprint/qrc 字样。
- 实际可追溯链（双载体）：① 每条落库结果行自带 rule_fingerprint + compat_policy_version + rule_version，且可 join 定义表逐条复算；② 平台日志每 run 有「规则冻结」行绑定三者：`PipelineService: pipeline N: 规则冻结 ruleFingerprint=92dc30c6…72c9 catalog=qrc-1 compatPolicy=compat-v1`（当前 /tmp/platform.log 内含 run 6、run 7 两条；run 3–5 的日志已随平台重启轮换，但其落库指纹与现行值逐字相同，证明同一目录版本）。
- **总控裁定**：目录版本可追溯性成立；为一个目录常量新增加列属无收益的破坏性迁移，不做。GAP-06 以本裁定关账。

## GAP-01 未登记码真库落库探针——通过（判据经总控按源码勘误）

- 探针方式：总控以生产写侧唯一实现原样驱动——`QualityRuleCatalog.DEFAULT.freeze("DAILY_CORE")` → `RuleSeverity.resolve(rules, "UNREGISTERED_PROBE_F8C", 1)` → `QualityChecker.applyVersionedSeverity(...)`（persistChecks/persistQuality/QualityChecker.rule 三写点共用此实现），所得实体行经 JDBC 落入本轮隔离 meta 库 `f8cb20261017_analytics_meta.data_quality_result`（run_id=900001 探针行，留存于该一次性隔离库备查）。
- 探针输出：catalogVersion=qrc-1、compatPolicyVersion=compat-v1、fingerprint=92dc30c6…72c9（与 live 成功 run 落库指纹逐字相同）；verdict.registered=false、declared=null、ruleVersion 内存哨兵 0。
- 库内回读断言：`severity IS NULL` ✓、`rule_version IS NULL`（哨兵 0 已按契约转 NULL）✓、`compat_policy_version='compat-v1'` ✓、`rule_fingerprint` 与目录指纹一致 ✓。
- **勘误（关键）**：盘点判据写作 `effective_severity='UNREGISTERED'` 与源码不符——`RuleSeverity.java:71` 定义 `UNREGISTERED = BLOCKING`（别名常量），`applyVersionedSeverity` 的 javadoc 明示落库字面为 "BLOCKING"，「未登记」的库内识别签名是 `severity IS NULL AND rule_version IS NULL` 且 compat/fingerprint 照写。本探针行 effective_severity='BLOCKING'，与源码契约精确一致。GAP-01 按修正判据关账；FULL-PROGRAM 与后续登记均按此口径表述。

## FlywayIT V14 真跑（隔离收编腿）——通过

- 以项目既定隔离运行器亲跑（RunId `f8cb20261017`，prepare -IncludeAnalytics exit 0 → run-isolated-tests.ps1 -Module analytics -IncludeAnalyticsWriteIts -Confirm）：**[PASS exit=0]**。
- `AnalyticsIsolationFlywayIT` **2/2 PASS**（32.65s）：全新隔离双库首轮真迁移、第二轮 0 迁移；总控另直查 flyway_schema_history 复核——meta 头 **V38**、metric 头 **V14**（"ads user retention"，success=1），且 `ads_user_retention_m` 表实存于隔离 metric 库。WP-F9a 遗留的「FlywayIT V14 真跑」就此实证销项。
- 同腿写入型 IT 全绿：IsolationGuardMySqlIT 6/6、MetricAdsMySqlIT 2/2、MetricPublisherMySqlIT 3/3（运行器汇总 analytics exit=0，含 AdsDataQualityErrorRateNullableMySqlIT 所属套件全过）。
- 日志：/tmp/test-logs/f8cb20261017/（isolated-analytics-schema.log 等）。

## 治理裁决（BK-01～03 / INV-12，F8b+F8c 关账后总控一并裁定）

- **BK-03 完成条件裁定**：F-88 本地完成条件 = 全部 B 类项关账——已达成（WP-F8b 测试类 + 本包真库探针类 + INV-12 台账）。完整验收另需 GAP-04（3306 真库 V19/V20 执行）在正式环境解冻授权后执行。
- **BK-01 口径裁定**：F-88 **维持「限定验收」**——唯一未关项 GAP-04 为外部依赖，不构成完整验收表述条件。
- **BK-02**：backlog 抽象挂账由本包与 WP-F8/F8b 的具象处置取代，销号。
- **INV-12**：17 规则码台账已于 WP-F8 盘点验收时经总控抽验（16 码行号精确命中，余 1 码 AMOUNT_RECONCILE 行号勘误 L95→L98 已登记），关账。

## 环境注记

- 首轮运行器在 analytics-schema 步失败：ai-decision 的 OpenAiCompatLlmProviderTest 34 例红（42 中 26F+8E）。总控定位为本 VM 既知网络怪癖（TOOLS.md）：fork 出的测试 JVM 未带 `-Djava.net.preferIPv4Stack=true` 时，HttpURLConnection 连 127.0.0.1 被 IPv6 映射拦截（总控最小复现：无该参数 status=-1、有则 200；单测加 argLine 后 42/42 绿）。处置：本轮以 JAVA_TOOL_OPTIONS 注入该参数后重跑（与既往亲跑口径一致），非仓库缺陷，不改脚本。
