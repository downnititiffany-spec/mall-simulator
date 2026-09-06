# 第五章 数仓与 Spark 设计实现（初稿）

> 对应实现：`warehouse/ddl/*.sql`（29 张建表）、`spark-jobs/`（6 个 Scala 作业）、
> `docker`——无；运行证据见 `experiments/spark-chain-local-*.json`。

## 5.1 数仓分层设计

系统采用 ODS/DWD/DWS/ADS 四层数仓，共 29 张表（ODS 4、DWD 3、DIM 5、DWS 7、ADS 10），
全部 Parquet + Snappy 存储并按业务日分区（dt=yyyyMMdd；ODS 另含小时分区）。
分层职责与血缘如表 5-1。

| 层 | 库 | 职责 | 本系统要点 |
|---|---|---|---|
| ODS | dw_ods | Landing 之上外部表，仅增加审计字段 | schema_version 过滤；未知版本隔离 |
| DWD | dw_dwd | 统一明细：清洗、event_id 去重、维度补充、状态展开 | 拒绝记录 dwd_reject_record；金额校验 |
| DWS | dw_dws | 主题宽表复用 | 用户日/漏斗日/商品日/交易日/用户周期/地区 |
| ADS | dw_ads | 页面与 AI 直接消费 | 字段英文+中文语义（dim_metric）登记 |

血缘：`odl(ODS装载) → bdw(DWD清洗) → usw(DWS聚合) → fna(ADS指标)`，
依赖顺序在 `JobRegistry` 中显式声明，供提交端拓扑校验。

## 5.2 关键口径规则（指标字典 v1）

1. 金额一律 DECIMAL(18,2)，统计时以字符串在接口层透传，杜绝精度丢失；
2. 指标归属日 = event_time，链路延迟 = ingest_time − event_time；
3. 有效支付口径 final_paid_flag=1（取消/未支付不计）；完全退款 final_refunded_flag=1
   从"有效复购率"口径中排除；
4. 漏斗使用"宽松用户口径"：view/intent/order/pay 各阶段独立去重（§21.4）；
5. 所有比率分母为 0 时返回 NULL（页面显示"无可计算数据"），禁止 0% 或无穷值；
6. 商品热度 = 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数) 的对数加权（§21.7）。

上述规则同时固化在 `docs/contracts/metric-dictionary.md` 与 MySQL 指标字典表，
AI 层与页面层只读该口径，禁止模型猜测。

## 5.3 Spark 作业设计

作业统一入口契约（§24.5）：`--runtimeProfileId --jobCode --businessDate [--inputVersion]
[--outputSnapshotId] --attemptNo`，stdout 输出一行 JobResult JSON（输入/输出/隔离记录数、
快照、尝试号、状态、耗时），日志走 stderr——机器契约使提交端（SSH/Livy）可解析并入库。
业务算法与 SQL 模板抽为无 Spark 依赖的纯 Scala 对象（分位数、RFM、漏斗、热度、异常检测、
各层 SQL），可在无集群环境的本机单测（22 项断言验证数值与关键子句）。

作业链在真实 Spark 3.5.1（Windows local[2] + 内嵌 Derby Hive）上完成 1.4 万~13 万
事件三档全量验证（见第八章 8.5），生产集群仅需切换 master 与元数据配置。

## 5.4 数据质量机制

质量规则在流水线 QUALITY_CHECK 阶段执行，落 data_quality_result 表：金额对账
（支付金额=对应订单总额，失败=阻断发布，核心规则）、必要字段空值率≤0.1%、
event_id 重复率≤0.05%、枚举白名单。配合采集层逐行契约校验（隔离而非丢弃），
与发布层"快照版本+唯一活跃+失败保留旧版本"，构成采集—计算—发布三级质量防线。

## 5.5 本地验证与集群迁移

- LOCAL：本机 MySQL + 文件 Landing + 内嵌 Derby-Hive，同一套作业与 SQL 全链运行；
- SINGLE_NODE / REMOTE_CLUSTER：切换 RuntimeProfile（数据源/提交器），作业与口径零改动；
  集群提交脚本 `scripts/run-spark-chain.ps1` 与部署文档 `docs/deployment.md` 已交付。