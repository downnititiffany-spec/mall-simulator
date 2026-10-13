# 阶段2/3：QUARANTINED 批次与 READY manifest 语义复核（2026-10-10）

## 结论

- 对照 `IngestionService` 与 `LandingManifestSelector` 源码复核：批次 `QUARANTINED` 表示至少有隔离行，不代表 accepted 记录为零。`recordCount` 汇总 accepted 行，`quarantineCount` 独立汇总隔离行；无系统错误时仍发布 `status=READY` 的 manifest。pipeline 只读取其 `acceptedUri`，并在 WAIT_LANDING 证据中保留 accepted/quarantine 计数。
- RunId `cloud_261010_040051_b2ab54` 的 batch 1 记录为 `recordCount=1, quarantineCount=1`，表示一条 accepted 加一条 quarantined。selector 选择该 READY manifest 符合现有契约，不是 selector 缺陷。
- 该 Run 首次 Pipeline 失败根因是 Spark 使用了错误/旧 Hive 配置；随后当前 RunId 成功链由 `scripts/cloud-single-node.sh` 为平台进程绑定当前 RunId 的 `HADOOP_CONF_DIR` 与 `SPARK_CONF_DIR`，Spark 日志证实加载当前配置。

## 证据与范围

- 源码：`analytics-server/connection-ingestion/src/main/java/com/graduation/analytics/ingestion/IngestionService.java` 的计数与 READY manifest 发布；`analytics-server/warehouse-pipeline/src/main/java/com/graduation/analytics/pipeline/LandingManifestSelector.java` 的源归属、READY 与非空规则；`PipelineService.java` 的 accepted 目录读取。
- 既有现场报告：`PHASE2-3-7-PIPELINE-SPARK-HIVE-METASTORE-MISMATCH-20261010.md`、`PHASE2-3-7-SPARK-CONF-ISOLATION-AND-PAID-ORDER-CHAIN-20261010.md`。
- 本次仅源码语义复核；未更改代码、未执行测试、未消费/重放批次、未操作服务或数据库。3306 未连接。阶段2/3/7及总验收仍开放。
