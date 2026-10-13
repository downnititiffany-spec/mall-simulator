# `dim_region` 静态城市等级字典契约（D-062）

状态：已批准，按方案 A 实施。批准依据：用户明确回复“A：保留三列”（2026-10-12）。本文件不修改 V3.0 冻结指导书和设计文档。

## 契约

1. `dim_region` 保留非分区三列：`region_code STRING, region_name STRING, city_level STRING`；不增加 `dictionary_version`。
2. 字典绑定 `canonical-event.v1` 的城市等级枚举，固定成员为 `tier1`、`tier2`、`tier3`、`other`、`unknown`；`unknown` 是唯一缺失/未知成员。
3. 每行三列取相同稳定代码值，不创造中文地理名称。该维度表示城市等级，不表示行政区、地图或真实城市。
4. 每次 DIM 构建完整幂等覆盖五行，与业务日期及 ODS 事件量无关；写入异常必须传播为任务失败，成功后必须核对五行。
5. canonical-event 规范版本演进时，评审成员、DDL、schema owner、Spark 写入投影、冻结快照及守卫测试。
6. D-058 的 ADS 仍按 `city_level` 汇总，并保留 `unknown` 销售事实；`dim_metric` 的 MySQL 字典同步不在本契约内。

## 边界

契约批准不等于生产 Hive/HDFS 验收、阶段验收或项目验收。`dim_region` 的真实集群回读及整条 ODS→DIM→DWS/ADS 核验仍需独立证据。
