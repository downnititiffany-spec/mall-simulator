# N31-02 LEG C 分类/地区供数复验补充（2026-09-30）

## 结论与边界

- 本补充复验关闭原 `BATCH-N31-02-LEGC-RESULT.md` 中坏样本、unknown 维度、分类名称、真实 MySQL 发布失败恢复与 `WAIT_LANDING.acceptedStorageUri` 落库证据的缺口。LEG C 现为 **PASS_WITH_LIMITATION**：业务数值与浏览器数据校验通过，但销售页截图仍是单个视口，未完整展示所有图表；该证据不升级为 N31-02 整批签收。
- 复验限于当前 WSL 单节点环境、RunId 隔离 MySQL 3307 schema、当前工作树重建制品。它不证明远程/多节点集群、共享生产 HMS、真实 LLM 或生产数据验收。
- 本轮无业务源码、测试源码、runner 或历史 acceptance 证据改动；只编辑本补充结果和动态状态。无 commit/push。V3.0 冻结正文未改，V3.1 仍未发布。

## 分类/地区连续链

- 新 RunId：`n3102cat_20260930_2235_6f3a`。构建制品 SHA-256：platform jar `a55eb2c9d8c40e02dd0f96658c41e205996e55b421b0831624317179f5312f1d`；Spark jar `110ea2a18d216a5a9ec5fdbe0ebca98aa41385b5cb8c7fced384f79de7988cc5`（JDK 8 / class major 52）。
- Flume→HDFS→平台摄取：`samples/legc-events.jsonl` 共 41 行；HDFS 输入与原始字节/行数一致。摄取记录 36 行、隔离 5 行坏数据；`sourceId=1`、`manifestBatchId=1`、`pipelineRunId=1`。平台 run `ODS_TO_ADS` 为 SUCCESS，快照 `S20260918_1` 唯一 ACTIVE。`WAIT_LANDING` 的完整存储 URI 另由 `BATCH-N31-02-WAIT-LANDING-URI-DB-PROOF-20260930.md` 的隔离库实测补证。
- Spark/ADS 与独立 oracle：类别与地区各 4 行，GMV 合计均为 450.00、净销售均为 370.00。明确覆盖 `category_id=-1` 的“未分类”桶和 `region=unknown` 桶；有效类别含名称，不再是此前 `UNKNOWN`。独立 oracle 在流水线执行前建立，SHA-256 `5dc903967d0025ac8d27b63d2daeb764fdfcedfe955fb0e52478d46eeb661909`。
- 浏览器 A8：销售页 API 200、14 项 overview 指标、overview 与 sales 的 `snapshotId` 一致；分类/地区数据和标题均通过，存在 3 个图表 canvas，页面错误数组为空。截图 `evidence-a8-corrected/page-sales-category-region.png` 是单视口证据，未覆盖整页所有图表。
- 独立只读 MySQL/API/oracle 对账 A9：API 两个维度各 4 行，MySQL 服务库逐行相同；40 个 API cells、17 项检查全部通过，容差 0.0005。

分类 ADS 行（类别、计数、GMV、净销售）：

```text
-1 | 未分类   | 1 |  60.00 |  60.00
100 | 数码设备 | 2 | 190.00 | 190.00
200 | 数码配件 | 2 | 150.00 |  70.00
300 | 家居日用 | 1 |  50.00 |  50.00
```

地区 ADS 行（城市等级、GMV、净销售）：

```text
tier1   | 230.00 | 150.00
tier2   |  90.00 |  90.00
tier3   |  70.00 |  70.00
unknown |  60.00 |  60.00
```

## MySQL 发布失败恢复隔离 IT

- 新 RunId：`n3102pub_20260930_2335_f3a`；身份为 WSL MySQL 8.0.41 `127.0.0.1:3307`，server UUID `de8ebbea-aff4-11f1-8037-00155d5dba47`。通过官方隔离准备与 `run-isolated-tests.ps1` 入口执行；运行前目标 schema 为 0 张表，运行后 37 张基表均完成精确行数回读。隔离 schema 保留，无 DROP/清理。
- 官方 analytics isolated suite **13/13 PASS**：Flyway 2/2、IsolationGuard 6/6、MetricAds 2/2、MetricPublisher 3/3；`MetricPublisherMySqlIT` 的发布成功与激活后验证失败恢复断言均通过，验证失败时旧 ACTIVE 快照及分类/地区 ADS 仍可读，失败快照不会遗留。
- 凭据经安全环境和临时忽略目录传入，口令未落入报告或版本控制；测试后临时凭据已清除。没有连接或执行 SQL 于 3306。
- 执行前有两次 harness-only 纠正，均无业务写入：首个 MySQL 准备预检将参数拆成错误 host `127`，在 schema 创建前失败；更正参数传递并重新执行官方预检后通过。N31-02 driver 调用 A8 脚本时引用了错误文件名，随后直接运行现存脚本；其第一次断言沿用了旧夹具的 1010/930 期望，已在忽略的本轮 target harness 中改为本次 oracle 的 450/370，并保留原失败件。平台行为和受版本控制代码未因此更改。

## 可复核证据

- 持久证据副本：`v3-archive/n3102/legc-revalidation-20260930-2235-6f3a/`，18 个原样拷贝文件，复制前后 SHA-256 零差异，`MANIFEST-SHA256SUMS.txt` 核验 **18/18**，归档文本敏感模式扫描 0 命中。该目录只保存腿级证据，不含完整源码快照/Git bundle；N31-02 全批收口时仍需按 D-048 汇总 A–D 并完成全批归档。
- 连续链原始运行根（本机忽略目录）：`target/v25-it/n3102cat_20260930_2235_6f3a/`
- A8/A9：`evidence-a8-corrected/page-evidence-a8.json`、`a9-summary.json`、`sales-api-a9.json`、`category-ads-mysql-a9.txt`、`region-ads-mysql-a9.txt`、`dimension-oracle-compare-a9.json`、`lineage-chain-a9.json`、`page-sales-category-region.png`。
- 关键 SHA-256：oracle `5dc903967d0025ac8d27b63d2daeb764fdfcedfe955fb0e52478d46eeb661909`；page evidence `d4a90303730414cb028642b051971363d7275a42338130e6915668dd2d0c92c0`；A9 summary `8a96c53441d0053542b333e489c45e05f3f9653b4717e9645f44d797af5993f6`。
- MySQL 隔离 IT 根（本机忽略目录）：`target/v25-it/n3102pub_20260930_2335_f3a/`；结果 `run-summary.json` SHA-256 `d198e8fa8e69eb795df48ca7c5da3fcce5dce275df03dd442841e527894256c`；日志 `logs/run-isolated-tests-console.log`，精确行数 `logs/after-it-exact-row-counts.txt`。该 run-summary 哈希应以文件本体为准，文档副本仅作索引。
- Surefire 报告：`analytics-server/metric-analysis/target/surefire-reports/TEST-com.graduation.analytics.metric.publish.MetricPublisherMySqlIT.xml`；报告结果 3 tests, 0 failures, 0 errors, 0 skipped。

## 后续

1. 将 LEG C 状态按本补充更新为 `PASS_WITH_LIMITATION`，并保留单视口截图这一证据限制。
2. 由总控复核 N31-02 A–D 各腿证据与阶段完成口径后决定是否签收；本报告不代替该裁决。
3. 真实 LLM 和 REMOTE_CLUSTER 仍是独立未验收范围，不能由本次通过推断为完成。
