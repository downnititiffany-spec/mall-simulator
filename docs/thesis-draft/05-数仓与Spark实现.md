# 第五章 数仓与 Spark 设计实现（初稿）

> 对应实现：`warehouse/ddl/*.sql`（设计 DDL 29 张建表）、`spark-jobs/`（11 个 Scala 作业）、
> `warehouse/README.md`（分层与血缘说明）；运行证据见 `experiments/spark-chain-local-*.json`、
> `experiments/spark-scale-local-20260906.json` 与验收证据包
> `docs/acceptance/r9-20260911-fabc6cb-run22-S20260901_22/`。
>
> **表数口径必须区分**：设计 DDL 计 29 张（ODS 4 / DWD 3 / DIM 5 / DWS 7 / ADS 10），
> 而本地真实链由建表作业 `sci`（`LocalSchemaInitJob`）实建 **32 张**——DIM 实建 2 张、
> ADS 除 8 张正式表外另有 8 张 `__staging` 孪生表。正文写"29 张"须注明是设计口径，
> 写"32 张"须注明含暂存孪生表。

## 5.1 数仓分层设计

系统采用 ODS/DWD/DWS/ADS 四层数仓，设计 DDL 共 29 张表（ODS 4、DWD 3、DIM 5、DWS 7、ADS 10），
全部声明为 Parquet 存储并按业务日分区（dt=yyyyMMdd；ODS 另含小时分区 hour）。
**DDL 未指定 Snappy 等压缩编码，代码库中无任何 Snappy 配置，论文不应写"Parquet + Snappy"。**
分层职责与血缘如表 5-1。

| 层 | 库 | 职责 | 本系统要点 |
|---|---|---|---|
| ODS | dw_ods | Landing 之上外部表，仅增加审计字段 | schema_version 过滤；未知版本隔离 |
| DWD | dw_dwd | 统一明细：清洗、event_id 去重、维度补充、状态展开 | 拒绝记录 dwd_reject_record；金额校验 |
| DWS | dw_dws | 主题宽表复用 | 用户日/漏斗日/商品日/交易日/用户周期/地区 |
| ADS | dw_ads | 页面与 AI 直接消费 | 字段英文+中文语义（dim_metric）登记 |

血缘：`sci(建表) → odl(ODS装载) → bdw(DWD清洗) → tdw/usw/fna(主题与指标聚合) → dqc(质量) → pub(发布)`。
11 个作业（sci/odl/bdw/dim/tdw/usw/fna/dqc/pub/mxp/ljp）均在 `JobRegistry` 中注册并显式声明依赖，
供提交端拓扑校验；平台流水线以 8 个阶段驱动其中 7 个作业键
（WAIT_LANDING→INIT_SCHEMA→LOAD_ODS→BUILD_DWD→BUILD_DWS→BUILD_ADS→QUALITY_CHECK→PUBLISH_METRIC），
建表、维表与专题作业按需单独触发。

## 5.2 关键口径规则（指标字典 v1）

1. 金额一律 DECIMAL(18,2)，统计时以字符串在接口层透传，杜绝精度丢失；
2. 指标归属日 = event_time，链路延迟 = ingest_time − event_time；
3. 有效支付口径 final_paid_flag=1（取消/未支付不计）；完全退款 final_refunded_flag=1
   从"有效复购率"口径中排除；
4. 漏斗使用"宽松用户口径"：view/intent/order/pay 各阶段独立去重（§21.4）；
5. 所有比率分母为 0 时返回 NULL（页面显示"无可计算数据"），禁止 0% 或无穷值；
6. 商品热度 = 1·ln(1+PV)+2·ln(1+收藏)+3·ln(1+加购)+5·ln(1+支付件数) 的对数加权（§21.7）。

上述规则同时固化在 `docs/contracts/metric-dictionary.md` 与 MySQL 指标字典表，
AI 层与页面层只读该口径，禁止模型猜测。口径版本随指标值一并落库：同一快照内退款率
`definition_version=v2`（"存在已完成退款订单"口径，值 0.60）、完全退款率 v1（退款单/订单，值 0.20），
**两者不同义、不可互相替代**，论文引用时必须分别标注版本。

## 5.3 Spark 作业设计

作业统一入口契约（§24.5）：`--runtimeProfileId --jobCode --businessDate [--inputVersion]
[--outputSnapshotId] --attemptNo`，stdout 输出一行 JobResult JSON（输入/输出/隔离记录数、
快照、尝试号、状态、耗时），日志走 stderr——机器契约使提交端（SSH/Livy）可解析并入库。
业务算法与 SQL 模板抽为无 Spark 依赖的纯 Scala 对象（分位数、RFM、漏斗、热度、异常检测、
各层 SQL），可在无集群环境的本机以 ScalaTest 逐项断言数值与关键子句
（`spark-jobs` 模块的测试数量一律以 R9 全量测试日志为准，本稿不写具体数字）。

作业链在真实 Spark 3.5.1（Windows local[2] + 内嵌 Derby Hive）上完成 1.44 万~12.96 万
事件三档全量验证（见第八章 8.5）；生产集群仅需切换 master 与元数据配置，**集群侧尚未实测**。

## 5.4 数据质量机制

质量规则在流水线 QUALITY_CHECK 阶段执行，落 data_quality_result 表：金额对账
（支付金额=对应订单总额，失败=阻断发布，**核心规则**）、必要字段空值率≤0.1%、
event_id 重复率≤0.05%、枚举白名单。实际运行示例（快照 S20260901_22，证据包
`14-ads-quality-active.tsv`）：4 条规则 **3 条通过、1 条失败**，失败项为非核心规则
`EVENT_ID_UNIQUE`（重复 1 条）；核心规则全部通过，故本次发布未被阻断——
**核心规则全过才允许发布**。配合采集层逐行契约校验（隔离而非丢弃）与发布层
"快照版本 + 唯一活跃 + 失败保留旧版本"，构成采集—计算—发布三级质量防线。

## 5.5 本地验证与集群迁移

- LOCAL：本机 MySQL + 文件 Landing + 内嵌 Derby-Hive，同一套作业与 SQL 全链运行
  （**已实测，当前唯一已激活环境**）；
- SINGLE_NODE / REMOTE_CLUSTER：切换 RuntimeProfile（数据源/提交器），业务代码与作业零改动，
  但**尚未激活、未实测，待环境**；
- 迁移辅助脚本 `scripts/run-spark-chain.ps1` 属本地遗留脚本（跳过 `tdw`/`dim` 两个作业、
  默认仓为 `D:\Develop\tmp\spark-warehouse`，与平台仓不同），**不作为"全链一键复现"或集群提交证据**；
  集群提交以平台流水线提交器为准，部署说明见 `docs/deployment.md`。
