# WP-F9b-FIX 规约：首轮验收不合格修复（总控亲跑证据钉死）

执行方：agy Flash（仅文件读写；构建/测试由总控亲跑）。仓库：mall-simulator，分支 codex/cloud-v3-development。
背景：WP-F9b 首轮总控亲跑判不合格——spark 门禁 RunId wpf9bacc20261017 exit 7（TestSuite.txt：389 跑 16 红）；default 定向亲跑 MetricAdsDaoTest 11 跑 2 红。**生产代码经总控逐项复核无误**（AdsSql.channelSummary 与 DwsSql.funnelDay 同式同精度；V15 DDL 与目录一致性 3/3 绿；AnalysisServiceTest 45/45 含新用例绿）。红因全部在测试侧：①新测试自身期望算错；②冻结计数/清单连带漏改（WP-F9a 教训重演）。本轮只许改下列测试文件与报告，**严禁改任何生产代码**（含 AdsQualityJob、AdsSql）。

## 修复项（逐项钉死，不得扩大）

### A. AdsChannelSummarySpec.scala——期望按种子数据独立复算改正
种子事实：intent = behavior_type IN ('favorite','cart_add') 去重用户。
- APP：U1 view×2+cart_add×1、U2 view×1 ⇒ pv=3、view_users=2、**intent_users=1**（仅 U1）、cart_users=1、**intent_rate=0.5000**、cart_rate=0.5000。
- H5：U1 view×1、U3 cart_add×1 ⇒ pv=1、view_users=1、**intent_users=1**（仅 U3）、cart_users=1、**intent_rate=1.0000**、cart_rate=1.0000。
- PC：不变（pv=0、view=0、intent=1、cart=1、两率 NULL）。
- UNKNOWN：U5 view、U6 view ⇒ pv=2、view_users=2、**intent_users=0**、cart_users=0、**intent_rate=0.0000**、cart_rate=0.0000。
- 正式表用例：APP intent_users 改 1（其余不变）。
- 文件头种子注释块同步改正为上述数字。

### B. 冻结计数连带（仅计数/清单/标题/注释）
1. WarehouseNamespaceSpec.scala L159 附近：`statements.size should be(45)` → 47；标题「45 条…（5 建库 + 40 建表）」→「47 条…（5 建库 + 42 建表）」。
2. OdsV2SchemaOwnerSpec.scala L181：`should be(45)` → 47；行内注释「5 建库 + 40 建表」→「5 建库 + 42 建表」。
3. AdsHotProductHeatRuleVersionSpec.scala L160：`TABLES.size should be(11)` → 12；标题「11 张」→「12 张」。
4. AdsQualityRuleVersionSpec.scala L194：同上 11 → 12（含标题）。
5. AdsCartRateSpec.scala L249：`TABLES.size should be(11)` → 12；注释改为「8 张既有表 + 分类/城市等级 2 张 + 留存表 1 张 + 渠道表 1 张」。
6. FixtureWriteShapeSpec.scala L435 冻结图：新增一项 `"AdsChannelSummarySpec.scala" -> 1`（与实测图一致；其余项不动）。

### C. AdsSaleTrendNetSaleSpec.scala 规则 3 用例——按 WP-F9a 留存先例豁免登记
断言改为：`(predicates.keySet + AdsSql.staging(ns, "ads_user_retention") + AdsSql.staging(ns, "ads_channel_summary")) should be(FunnelAdsJob.stagingTables(ns).toSet)`，并加注释说明渠道表与留存表同例不入 keyPredicates（关键列由 DDL NOT NULL + 归一化保证）。**不得改 AdsQualityJob 生产代码**；标题「覆盖全部 10 张暂存表」不改（谓词仍 10 张）。

### D. MetricExportZeroRowSpec.scala——渠道表按 0 行专题连带（本夹具纯交易、无行为事实 ⇒ 渠道汇总合法 0 行）
1. 夹具 `rows` 新增一条：`"ads_channel_summary" -> "SELECT 'APP', 0L, 0L, 0L, 0L, CAST(0.0000 AS DECIMAL(8,4)), CAST(0.0000 AS DECIMAL(8,4)) FROM (SELECT 1) dual WHERE 1 = 0"`（列序 = channel, pv, view_users, intent_users, cart_users, intent_rate, cart_rate）；「铺 11 张暂存分区」注释改 12 张。
2. `ZeroRowTables` 追加 `"ads_channel_summary"`；`ZeroRowMysql` 追加 `"ads_channel_summary_m"`。
3. mxp 用例：标题「11 表导出」→「12 表导出」；`rows should have size 11` → 12。manifest 总账用例标题「11 表齐全」→「12 表齐全」。nonEmpty size 6、totalRows=RealRowTables.size 不动。

### E. MetricAdsDaoTest.java（Java 侧连带）
1. `catalogContainsAllCurrentAdsTables`：DisplayName「11 张」→「12 张」；`hasSize(11)` → 12；`containsExactly(...)` 末尾追加 `"ads_channel_summary_m"`（目录 ALL 中渠道表在留存之后，顺序逐字对齐）。
2. `deleteSnapshotCoversAllTables`：DisplayName「全部 11 张表」→「全部 12 张表」；`times(11)` → 12；`deleted` 期望 22 → 24。

### F. 报告更正
WP-F9b-REPORT.md：§五中 APP/H5/UNKNOWN 期望数字按 A 节改正；§四冻结清单补入本 FIX 的 B/C/D/E 全部项。另写 WP-F9b-FIX-REPORT.md（改动逐项清单 + 未做项声明），末行 `WPF9BFIX-DONE`。

## 红线
- 不改任何 `src/main` 生产文件；不改 AdsSql.channelSummary 逻辑；不碰 3306/3307；不用 Docker；不拨 DeepSeek。
- 完成标记：WP-F9b-FIX-REPORT.md 末行 `WPF9BFIX-DONE`。
