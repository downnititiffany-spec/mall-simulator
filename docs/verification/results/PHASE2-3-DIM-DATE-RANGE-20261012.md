# 阶段2/3 DIM 日期维度生产链（D-061）— 2026-10-12

## 裁决与范围

用户批准的契约：日期维度使用非分区表；从四类 ODS 的实际 `dt` 行与已有 `dim_date.date_key` 取边界，生成闭区间 `[最小日期, 最大日期]` 的连续日历，保留/扩展历史并允许幂等重跑；周次与星期遵循 ISO-8601，星期一=1、星期日=7，周末 6/7；不设置固定日期上限。冻结的 V3.0 指导书与设计文档未修改。

## 实现

- `DimSql` 增加日期边界读取与日历范围重建 SQL。遇到非空但无法按声明格式解析的日期时作业失败关闭；先物化边界再覆盖目标表，避免同一 SQL 查询读写目标表。
- `DimensionBuildJob` 将 `dim_date` 纳入现有 `dim` 作业，在 DWD 前运行；空来源且现表为空时保持空表。任务输出计数/摘要包含日期维度行数，分区证据仍只采集 `dim_user`、`dim_product` 两张按 `dt` 分区表。
- 本地唯一 schema owner 增加与参考 DDL 一致的非分区 `dim_date`；S3-13 守卫现在核对其列序、类型、无分区、唯一写入入口。`dim_region`、`dim_metric` 保持未实现与显式白名单。
- 主链断言及 INIT_SCHEMA 总数守卫同步调整；所有新增日期值来自现有 ODS/维表输入，不连接商城数据库。

## 验证

Windows JDK 8 / Spark 3.5.1 local[1] / in-memory catalog：

- 定向 4 suite：`DateDimensionRangeSpec`、`DwdDimSchemaOwnerSpec`、`ProductDimensionAsOfSpec`、`DimDwdChainExecSpec`，37/37 PASS，0 failed/aborted。
- 全量命令：`mvn -o -f spark-jobs/pom.xml test`，333/333 PASS，43 suites completed，0 failed/aborted，BUILD SUCCESS。
- 日期回归覆盖四张 ODS 表分别提供边界、既有日期延展、连续日历、2020/2021 ISO 周年边界、星期/周末值以及同输入重跑结果不变。
- 全量测试是 local Spark/in-memory catalog 证据，不代表真实 Hive Metastore、HDFS Parquet、生产 Spark 集群或完整 task-owned pipeline 验收。

## 状态边界

`dim_date` 的代码生产链和本地 Spark 回归已补齐；需真实 Hive/Spark 环境中的物理表/覆盖验证仍未做。`dim_region` 内容/版本口径与 `dim_metric` 字典同步通道仍待裁决及实现。阶段2/3与项目总验收不因本条关闭。

证据：`spark-jobs/src/test/scala/com/graduation/analytics/DateDimensionRangeSpec.scala`、`DwdDimSchemaOwnerSpec.scala`、`DimDwdChainExecSpec.scala`、`scripts/run-tests.ps1`。动态文档编辑前备份后缀：`bak-20261012-dim-date`。