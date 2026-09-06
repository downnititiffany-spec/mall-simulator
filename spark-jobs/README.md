# spark-jobs — Hive 四层数仓 Spark 离线任务（Scala）

> 依据文稿 §4.4（Spark 主任务 Scala）、§24.5（作业入口契约）、§24.6（推荐作业清单）。
> 本工程与 analytics-server / mall-simulator 完全解耦（§19.2），由阶段 6 的
> `SshSparkSubmitter`（SSH + spark-submit）提交；第二阶段可切换到 Livy（接口不变）。

## 构建与测试（本机无 Spark 也可验证逻辑）

```bash
mvn verify        # 编译 + scalatest 全绿 + 打包 job jar（Spark 依赖 provided 不打入）
```

## 作业清单（首批）

| code | 作业 | 输入 → 输出 | 依赖 |
|---|---|---|---|
| `odl` | EventOdsLoadJob | Landing JSON → dw_ods.ods_behavior_event | — |
| `bdw` | BehaviorDwdJob | ods_behavior_event → dwd_user_behavior_detail + dwd_reject_record | odl |
| `usw` | UserProductDwsJob | dwd 行为 → dws_user_behavior_day / dws_behavior_funnel_day / dws_product_behavior_day | bdw |
| `fna` | FunnelAdsJob | dws → ads_operation_overview / ads_active_trend / ads_hot_product / ads_product_conversion / ads_sale_trend / ads_behavior_funnel | usw |

依赖图：`odl → bdw → usw → fna`（§5.3.2）。

## 提交契约（§24.5）

```bash
spark-submit --master yarn --deploy-mode client \
  --class com.graduation.analytics.job.JobRunner \
  target/spark-jobs-0.1.0-SNAPSHOT.jar \
  --runtimeProfileId=1 --jobCode=bdw --businessDate=20260901 \
  --inputVersion=batch-2088 --outputSnapshotId=S20260901_01 --attemptNo=1 \
  --shufflePartitions=200 --master=yarn
```

stdout 输出一行机器契约定 **JobResult JSON**（jobCode/inputRecords/outputRecords/rejectedRecords/
snapshotId/attemptNo/status/elapsedMs），供 SparkSubmitter 解析；日志走 stderr。

## 算法与口径（纯函数，单测覆盖）

* `algorithm/`：分位数/RFM 八类/漏斗/热度/异常 z-score+IQR/维度贡献/清洗规则 —— 无 Spark 依赖。
* `sql/`：ODS/DWD/DWS/ADS SQL 模板 —— 关键子句（event_id 去重、有效支付口径、分母 0 → NULL、TopN）
  由 scalatest 断言，无需 Spark 运行即可回归。
* 口径唯一来源：`../docs/contracts/metric-dictionary.md`；表结构与 `../warehouse/ddl/` 一致。

## 性能实验参数（§7.9）

`--shufflePartitions`、`--master`、`spark.executor.memory/cores` 经 spark-submit 注入；
JobResult.elapsedMs 与阶段 6 记录的输入/输出记录数构成论文性能对比的最小可复现单元。