# F-28 指标存储双所有者：遗留 `analytics_meta.metric_*` 与在用 `analytics_metric.metric_*`

> 本文件是**发现记录（finding record）**，不是轮次验收 README，因此不套用 12 节模板；
> 内容按「结论 → 实测证据 → 风险 → 处置 → 未取证」组织。
> 发现时间：2026-09-12；发现方式：总控在**独立复核 P1-06 的 T2 声称**（第一手查库）时顺带发现。

## 1. 一句话结论

同一 MySQL 实例里存在**两套同名的快照/指标值表**：在用的 `analytics_metric.metric_snapshot|metric_value`
（承载主链，`S20260901_41` v9 ACTIVE）与**遗留的** `analytics_meta.metric_snapshot|metric_value`
（承载 2026-09-07 那一族，最后写入 2026-09-07 19:33:50.123，其中 `S20260907_11` **至今仍是 ACTIVE**）。
遗留侧没有机器可读的「已废弃/非权威」标记，而平台侧 **AI 生成 SQL** 的 scope 相关类**同时提到两个 schema** ⇒
存在「读到遗留表、拿到 5 天前的旧值、并把陈旧 ACTIVE 当成当前快照」的通道（与 F-18「静默陈旧」同族）。

## 2. 实测证据（全部为 2026-09-12 只读查询原始输出）

原始输出：`raw/mysql-both-schemas-20260912.txt`（含命令、时间戳与 `[exit=0]`）。

| # | 事实 | 证据 |
|---|---|---|
| E1 | 同名两表并存，**列形状不同** | `analytics_meta.metric_snapshot` 无 `active_flag`/`failure_reason`；`analytics_metric.metric_snapshot` 有 `active_flag`/`failure_reason`/`definition_version`。`analytics_metric.metric_value` 多 `dimension_json`/`dimension_key` |
| E2 | 在用侧行数 | `CNT_METRIC_SNAP=9`、`CNT_METRIC_MV=80` |
| E3 | 遗留侧行数 | `CNT_META_SNAP=9`、`CNT_META_MV=132` |
| E4 | 在用侧承载主链（**本轮第一手复核 P1-06 的 T2 声称**） | `METRIC_S S20260901_41 ACTIVE 9 1`、`S20260901_39 ARCHIVED 8`、`S20260901_38 FAILED 7`；`MV_METRIC S20260901_41 10 10`、`S20260901_39 10 10` |
| E5 | 运行行与快照一致 | `RUN41 41 SUCCESS 2026-09-12 09:26:27.592 → 09:31:13.403 S20260901_41`（时长 4 min 46 s，与验收记述一致）；`JOB41 SUCCESS 10`（run 41 的 10 条作业全 SUCCESS）；`RUN41 40 FAILED 09:23:15.867 → 09:23:26.098`（失败未留痕于数仓） |
| E6 | 遗留侧最后写入 | `META_S S20260907_11 ACTIVE 1 2026-09-07 19:33:50.123`（其后无新行）；该族共 9 个快照，8 个 ARCHIVED + 1 个 **陈旧 ACTIVE** |
| E7 | 行数自 V13 起冻结 | `analytics-server/platform-app/src/main/resources/db/meta/V13__metric_definition_r7.sql:24-25` 的注释自己记 `analytics_meta.metric_snapshot = 9 行`、`analytics_meta.metric_value = 132 行`，与 E3 今日实测**逐值相同** ⇒ 自 V13 起无写入 |
| E8 | 主代码不引用遗留表 | 全仓 `analytics_meta\.metric_(value\|snapshot)` 仅 4 处命中，**全部**是 V13 迁移的注释与其 `target/classes` 副本；`analytics_metric\.metric_(value\|snapshot)` 仅 1 处命中，是 `AnalysisGoldenMySqlIT.java:46` 的注释 |
| E9 | 平台侧 AI 生成 SQL 的 scope 同时覆盖两个 schema | `analytics-server/ai-decision/src/main/java/com/graduation/analytics/ai/sql/AiScopeResolver.java` 与 `.../sql/SqlExecutor.java` 均同时命中 `analytics_metric` 与 `analytics_meta` |

## 3. 风险

- **R1 静默旧数**：任何落到 `analytics_meta.metric_value` 的查询（临时 SQL、报表、AI 生成 SQL）会拿到 2026-09-07 的旧值，
  且「当前快照」会解析成 `S20260907_11`——**它自己的 status 是 ACTIVE**，看不出陈旧。失败模式与 F-18 同族：看起来成功、其实是旧数。
- **R2 双所有者无标记**：同一事实（快照与指标值）在两处存在，遗留侧没有 `active_flag`，也没有任何机器可读的 deprecated 标记，
  「谁是权威」只存在于人的记忆与文档里——正是本项目「每个事实只能有一个所有者（可被机器判定）」要防的形态。

## 4. 处置建议（本轮**只登记，不执行**）

| 编号 | 动作 | 破坏性 | 建议 |
|---|---|---|---|
| N1 | 加**扫描门测试**：断言主代码不引用 `analytics_meta.metric_value\|metric_snapshot`（仿 P1-04 的 `WarehouseNameLiteralGateTest` 先例） | 否 | 建议尽早做，可并入 P2 |
| N2 | AI scope 白名单/提示词里把这两张遗留表标注为「历史遗留，不得作为指标来源」 | 否 | 建议并入 M2-AIW 或 P4 |
| N3 | 退休遗留两表（改名加 `_legacy_20260907` 或删除） | **是** | **须用户按范围明确确认后另开任务**；本文件不建议在本轮做，也不执行 |

## 5. 未取证与边界

- **未取证**：库名常量与数据源配置未在本轮定位 ⇒ 「平台只连 `analytics_metric`」目前只有**三线旁证**
  （E7 行数冻结 + E8 无代码引用 + E4 主链数据只在 `analytics_metric`），**没有**代码级/配置级直证。不得写成「已确认平台只读 `analytics_metric`」。
- **未取证**：AI 问答实际能否命中遗留表未实测（需要跑一次问答并对生成的 SQL 取证）。
- **边界**：本轮只做只读查询；未做任何写操作、未启停任何服务、未改任何表结构；`docs/**` 之外无改动。
- **转录≠实测（本轮自查）**：旧看板 P1-06 行记 `raw/` **33 文件**，本日实测 **34 文件** ⇒ 看板里的计数是**当时**的记述，
  引用时必须重新实测或标注「转录」。

## 6. 复现命令（只读）

```powershell
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $my -uroot -p123456 -N --raw --default-character-set=utf8mb4 -e @"
SELECT 'CNT_META_MV', COUNT(*) FROM analytics_meta.metric_value;
SELECT 'CNT_METRIC_MV', COUNT(*) FROM analytics_metric.metric_value;
SELECT 'META_S', snapshot_id, status, version, published_at FROM analytics_meta.metric_snapshot ORDER BY id;
SELECT 'METRIC_S', snapshot_id, status, version, active_flag FROM analytics_metric.metric_snapshot ORDER BY id;
"@
```

> 说明：`-N` 去表头、`--raw` 不转义（否则 `\n` 会被改写）；`Using a password on the command line` 是 mysql 的常规告警，
> 与查询结果无关，**不得**用 `2>$null` 吞掉 stderr（会同时吞掉真正的错误）。
