# `dim_region` 静态城市等级字典契约草案

状态：待总控裁决；不是新的实施授权，不修改 V3.0 冻结文档。

## 1. 依据

- V3.0 设计 §9.2 要求 `dim_region` 包含地区未知成员，且维度不能只建空表。
- D-058 已批准地区首版含义为 `city_level`，只称“城市等级销售分布”，不得表示为行政区；当前规范枚举为 `tier1`、`tier2`、`tier3`、`other`。
- `warehouse/ddl/02-dims.sql` 现有非分区列为 `region_code STRING, region_name STRING, city_level STRING`，没有版本字段。
- D-061 明确 `dim_region` 的静态字典内容和版本字段尚待裁决。

## 2. 推荐契约（方案 A）

1. 固定字典成员为 `tier1`、`tier2`、`tier3`、`other`、`unknown`，其中 `unknown` 是唯一缺失/未知成员。该闭集由 `canonical-event.v1` 的 `city_level` 枚举加 D-058 的 unknown 语义组成。
2. 每行 `region_code = city_level`；`region_name` 使用相同稳定代码，不自行创造未批准的中文地理名称；`city_level` 写入该成员值。`unknown` 三列均写 `unknown`。
3. 继续使用已有三列、非分区表，不新增 `dictionary_version` 列；静态字典随 `canonical-event.v1` 规范版本演进。规范版本变更时必须同步评审成员、DDL owner、Spark 写入投影、冻结快照和测试守卫。
4. `dim` 构建每次以完整五行集合幂等覆盖，不能依赖某个业务日期或当天是否有事件；写入失败由 Spark 任务失败传播，不用空表或空结果冒充成功。
5. D-058 的 ADS 仍从 `dws_region_sale_day` 按 `city_level` 聚合；`unknown` 销售事实必须保留并能与 DWS/交易金额对账。此 DIM 不代表行政区、地图或真实城市名。

## 3. 备选方案 B：新增显式版本列

成员及代码语义同方案 A，但在 `dim_region` 增加 `dictionary_version STRING`，所有行写入固定版本（例如 `city-level-v1`）。这要求同步修改 `warehouse/ddl/02-dims.sql`、唯一 schema owner、冻结 schema 快照、列序/写入投影及守卫测试，并重新核对所有 Hive 侧消费者。版本字段只能表示字典成员版本，不得与事件 schema、指标 definition version 混用。

## 4. 裁决问题

请批准 **A**（复用 `canonical-event.v1` 版本，不加列）或 **B**（加 `dictionary_version` 列）；也可指出需要调整的成员/unknown 映射。批准后再实现生产写入和独立回归；裁决前 `dim_region` 保持无生产 owner/写入路径，不把 D-058 的 ADS 裁决扩大成 DIM 字典裁决。

## 5. 不在本草案内

`dim_metric` 的 MySQL→Spark 同步通道、真实 Hive/HDFS 验收、N31-02 整链验收、V3.1 发布和项目最终验收。
