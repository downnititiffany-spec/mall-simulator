# N31-02 腿 B 结果：同源第二批、no-op 与失败保旧

- 日期：2026-09-29
- 产品行为：**PASS；最终结果标记 PASS_WITH_HARNESS_CORRECTION**
- 运行根：`target/v25-it/n3102iso_20260929_095610/`
- 边界：同一隔离 run/schema 与 `sourceId=1`；仅新增唯一一条受控行为事件；无源夹具改写；无重复 Flume 投递；3306 零接触。

## 第二批与快照对账

新增单条 view 事件 `n3102-b2-view-0001`（输入 SHA-256 `2a7f75d95bee5d7bbe0f14aa4b203b790c89d6693830be6787ead9396b2b178f`），通过 Flume 写入同一 HDFS landing。raw 行数 99→100，输入字节与 HDFS 事件逐字节核对；摄取得到 `sourceId=1 / batchId=2`、accepted 1、quarantine 0。第二次管线 `runId=2` SUCCESS 并发布 `S20260918_2`。独立 Python oracle 在发布前以 100 行组合输入计算期望；14/14 指标在 ADS 导出、metric_value、API 与 oracle 之间容差 0.0005 全等，PV 由 30 增至 31。

同一 manifest 重放为 `runId=3` SUCCESS，`noNewInput=true / reason=ALREADY_CONSUMED`；无新快照，ACTIVE 仍为 `S20260918_2`，快照数 2，消费台账数 2。

## 受控失败与旧快照保留

在显式重算批次 2 前临时移出 Spark job jar，制造可恢复的作业提交故障。重算 `runId=4` 以 `RUN_JOB_FAILED` 在 `INIT_SCHEMA` 失败；请求钉住的 `input_batch_id=2` 保留。Spark jar 在 finally 中恢复，SHA 回到基线 `71c0fc88b1c093df3e2e828d0c00b827a5bd51bca1d0ca0ab4a378e37bc00e57`。

失败后隔离库只读证据：消费台账仍 2 行；快照数仍 2；唯一 ACTIVE 仍 `S20260918_2`；该快照 `metric_value` 为 14 行。受控平台重启后，`GET /api/v1/metrics/overview?snapshotId=S20260918_2` 返回 14 项，逐项 snapshotId 均为 `S20260918_2`。平台按身份清理停止、8091 释放，日志与清扫证据均为零 `:3306`。

## 验收驱动误报及结论限定

原 B 驱动最终退出为 FAIL，原始文件 `evidence/leg-b-state.json` 保留不改。失败断言把 MySQL CLI 的全部输出直接与 `CONS|2` 做全串比较；CLI 输出包含列标题 `CONCAT('CONS|',COUNT(*))` 和正确数据行 `CONS|2`，因此脚本产生假失败。第一次实时只读复核器又把 PowerShell 对 API 数组的成员访问当成单对象，造成快照 id 断言假红；当时完整 HTTP JSON 已保存。随后按显式逐行迭代重新解析该原始 JSON，14 行均有 `snapshotId=S20260918_2`；并与原始 SQL 输出、故障 run 和快照行数一起由 `verify-leg-b-captured-evidence.ps1` 独立校验通过，生成 `evidence/leg-b-postfault-readonly-verification.json`。这证明产品行为判据通过，但不把原始驱动 exit=FAIL 改写成其原样全绿；本腿以 **PASS_WITH_HARNESS_CORRECTION** 登记，并保留 harness 缺陷作为修复事项。

首轮驱动曾错误地在平台摄取创建 manifest 前读取 HDFS manifest，未触发任何平台/数据库运行；该失败 JSON 已单独保留 `evidence/leg-b-state-harness-order-failure.json`。恢复执行从已有 Flume 成功点继续，没有重复投递。

## 证据

- `evidence/leg-b-state.json`（原始驱动结果，FAIL 原貌保留）
- `evidence/metric-compare-b2.json`、`evidence/lineage-b2.json`、`evidence/manifest-b2.json`
- `evidence/leg-b-postfault-readonly-verification.json`、`evidence/api-overview-postfault-response.json`
- `logs/sql-b-fault-consumption-after.out.txt`、`logs/sql-b-fault-active-after.out.txt`、`logs/sql-b-fault-run-after.out.txt`
- `evidence/cleanup-after-leg-b.json`、`evidence/cleanup-postfault-readonly.json`

