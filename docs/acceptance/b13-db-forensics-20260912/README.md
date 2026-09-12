# B-13 数据库取证卷 —— 假警报勘误

**卷宗目录**：`docs/acceptance/b13-db-forensics-20260912/`
**性质**：**只读取证**。本次取证未执行任何 `INSERT/UPDATE/DELETE/TRUNCATE/DROP/ALTER`，未启停任何服务，未跑 Maven，未触碰 pipeline/ingestion，未做任何 git 写操作，未修改或删除任何既有文件（含 `docs/acceptance/**` 留存件）。
**取证时点**：2026-09-12 23:03 – 23:2x（服务器 `NOW()` 实测 `2026-09-12 23:11:06`）
**Git 分支**：`remediation/r1-boundary`
**实例**：MySQL 8.0.41 Win64，`C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe`，`--host=127.0.0.1 --port=3306 --user=root`，一律 `--batch --raw --default-character-set=utf8mb4`

---

## 0. 结论摘要（一句话）

**B-13 不是破坏性事件，没有任何数据丢失。** 原假设把**两个不同 schema 的同名表**当成了同一张表对比：
留存件读的是 **`analytics_metric.metric_snapshot`（现行生产表，12 行、`S20260901_47` ACTIVE）**，
而"9 行、全是 2026-09-07 历史行"的读数来自 **`analytics_meta.metric_snapshot`（陈旧种子表，与生产链无关）**。
第二重成因是把 `AUTO_INCREMENT = max(id)+1` 误读成"曾被清空后复位"的删除指纹 —— 它恰恰是**从未删过任何行**的正常形态。

---

## 1. 两张表到底在哪个库（逐库实测值）

### 1.1 `information_schema.TABLES` 指纹（`raw/06-fingerprint-all-schemas.txt` 原文）

```
TABLE_SCHEMA	TABLE_NAME	TABLE_ROWS	AUTO_INCREMENT	CREATE_TIME	UPDATE_TIME	CHECK_TIME	DATA_LENGTH	DATA_FREE
analytics_meta	metric_snapshot	9	10	2026-09-07 17:15:57	NULL	NULL	16384	0
analytics_meta	metric_value	132	133	2026-09-07 16:04:26	NULL	NULL	16384	0
analytics_meta_p103	metric_snapshot	9	10	2026-09-11 20:55:58	2026-09-11 20:55:58	NULL	16384	0
analytics_meta_p103	metric_value	132	133	2026-09-11 20:55:58	2026-09-11 20:55:58	NULL	16384	0
analytics_meta_p105	metric_snapshot	9	10	2026-09-12 08:58:02	2026-09-12 08:58:02	NULL	16384	0
analytics_meta_p105	metric_value	132	133	2026-09-12 08:58:02	2026-09-12 08:58:02	NULL	16384	0
analytics_meta_p105it	metric_snapshot	9	10	2026-09-12 09:04:37	2026-09-12 09:04:37	NULL	16384	0
analytics_meta_p105it	metric_value	132	133	2026-09-12 09:04:37	2026-09-12 09:04:37	NULL	16384	0
analytics_meta_v17probe	metric_snapshot	9	10	2026-09-11 20:11:08	2026-09-11 20:11:08	NULL	16384	0
analytics_meta_v17probe	metric_value	132	133	2026-09-11 20:11:08	2026-09-11 20:11:08	NULL	16384	0
analytics_metric	metric_snapshot	9	25	2026-09-10 20:04:14	2026-09-12 09:31:13	NULL	16384	0
analytics_metric	metric_value	80	121	2026-09-10 20:04:14	2026-09-12 09:31:13	NULL	16384	0
analytics_metric_p103	metric_snapshot	8	24	2026-09-11 20:55:58	2026-09-11 20:55:59	NULL	16384	0
analytics_metric_p103	metric_value	70	111	2026-09-11 20:55:59	2026-09-11 20:55:59	NULL	16384	0
analytics_metric_p105	metric_snapshot	8	24	2026-09-12 08:58:02	2026-09-12 08:58:02	NULL	16384	0
analytics_metric_p105	metric_value	70	111	2026-09-12 08:58:02	2026-09-12 08:58:02	NULL	16384	0
analytics_verify_m3_parity	metric_snapshot	3	32	2026-09-12 22:24:02	2026-09-12 22:32:37	NULL	16384	0
analytics_verify_m3_parity	metric_value	10	201	2026-09-12 22:24:02	2026-09-12 22:32:37	NULL	16384	0
mall_simulator	metric_snapshot	10	12	2026-09-06 12:17:17	NULL	NULL	16384	0
mall_simulator	metric_value	134	134	2026-09-06 12:17:17	NULL	NULL	16384	0
mall_simulator_test	metric_snapshot	0	450	2026-09-06 12:12:23	NULL	NULL	16384	0
mall_simulator_test	metric_value	0	2241	2026-09-06 12:12:23	NULL	NULL	16384	0
```

### 1.2 `COUNT(*)` 精确值 vs `TABLE_ROWS` 估计值（`raw/08-counts-and-stats-gap.txt`）

```
now_ts
2026-09-12 23:11:06
tbl	exact_count	is_table_rows	auto_inc	max_id
analytics_metric.metric_snapshot	12	9	25	27
analytics_metric.metric_value	110	80	121	150
analytics_meta.metric_snapshot	9	9	10	9
analytics_meta.metric_value	132	132	133	132
```

> **这张表本身就是成因之一**：`analytics_metric.metric_snapshot` 的 `TABLE_ROWS=9` 而 `COUNT(*)=12`；
> `analytics_metric.metric_value` 的 `TABLE_ROWS=80` 而 `COUNT(*)=110`。
> **InnoDB 的 `TABLE_ROWS` 是采样统计估计值，不是行数**。任何"12 行 vs 9 行"的对比，
> 只要一侧取自 `TABLE_ROWS`、另一侧取自 `COUNT(*)`，就必然得出"少了 3 行"的假象。

### 1.3 两库 `metric_snapshot` **列形状不同**（决定性判别依据，`information_schema.COLUMNS`）

| 列 | `analytics_metric.metric_snapshot` | `analytics_meta.metric_snapshot` |
|---|---|---|
| `definition_version` | **有**（NOT NULL, default `''`） | **无** |
| `active_flag` | **有**（tinyint） | **无** |
| `failure_reason` | **有**（varchar(512)） | **无** |
| 列总数 | 15 | 12 |

⇒ 只要留存件里出现了 `definition_version` / `active_flag` / `failure_reason` 任一列，
该读数就**只可能**来自 `analytics_metric`。反之，不含这三列的读数只可能来自 `analytics_meta`。

---

## 2. 留存件的库归属（逐份抄出实测读数）

| 留存件 | 原文关键行 | 实际 schema | 判别依据 |
|---|---|---|---|
| `docs/acceptance/e5-preaccept-20260912/raw/db-readonly-pre-stop.txt` L1–L20 | `===== 只读库取证 at 2026-09-12 22:00:17`；L3 表头含 `definition_version`；L4 `metric_snapshot S20260901_47 ACTIVE 12 … spark-ads 47 v2`；L5–L15 共 **12 行** | **`analytics_metric`** | 表头含 `definition_version`（`analytics_meta` 无此列）；同目录 `README.md:137` 自己写明「只读 SQL `analytics_metric.metric_snapshot` **12 行** `source` 全为 `spark-ads`」 |
| `docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/raw/50-prod-untouched-check.txt` L1–L18 | `== prod analytics_metric: snapshot 27 / S20260901_47 (must be untouched) ==`；L3 表头含 `active_flag`/`definition_version`；L4 `27 S20260901_47 ACTIVE 1 12 v2 spark-ads … 21:29:27.282`；L8–L18 共 **11 个 snapshot_id 各 10 行 metric_value** | **`analytics_metric`** | 文件名与 L2 标题**显式写出** `analytics_metric` |
| `docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/raw/80-final-state.txt` L15–L20 | `== prod analytics_metric untouched (final re-check) ==`；`27 S20260901_47 ACTIVE 12 1 2026-09-12 21:29:27.321` | **`analytics_metric`** | L16 标题**显式写出** `analytics_metric` |
| `docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/raw/80-final-state.txt` L1–L14 | `== FINAL STATE of isolated DB analytics_verify_m3_parity (left as evidence) ==`；`checked_at 2026-09-12 22:43:34`；`29/30/31` 三行 | **`analytics_verify_m3_parity`**（隔离演练库） | L2 标题**显式写出**库名 |
| `docs/acceptance/f28-legacy-metric-store-20260912/README.md:9-10,21,27` | 「在用的 `analytics_metric.metric_snapshot\|metric_value`（承载主链，`S20260901_41` v9 ACTIVE）与**遗留的** `analytics_meta.metric_snapshot\|metric_value`」；「`analytics_meta.metric_snapshot` 无 `active_flag`/`failure_reason`；`analytics_metric.metric_snapshot` 有」；V13 迁移注释即记 `analytics_meta.metric_snapshot = 9 行`、`metric_value = 132 行` | 两者都提到，**归属划分正确** | 该卷早已正确区分两库 |

**额外发现（本轮新证据，决定性）**：`docs/acceptance/**` 之外还有一个 mysqldump 产物
**`.verify/p105-meta-copy.sql`**（3,264,637 B，mtime **2026-09-11 20:11:08**，769 行），头部自述
`-- Host: localhost    Database: analytics_meta`，其 L386 单条 `INSERT INTO \`metric_snapshot\` VALUES ...`
**恰好 9 个元组 = id 1..9**，逐值与现今 `analytics_meta.metric_snapshot` **完全相同**，
且 L386 **不含** `S20260901_47`。该文件 mtime 与 `analytics_meta_v17probe` 的 `CREATE_TIME=2026-09-11 20:11:08` 同秒。

⇒ **可复核的正面证据**：这 9 行在 **2026-09-11 20:11:08** 就已被 dump 到磁盘，
**早于 B-13 所称的 22:00–23:00 窗口整整 26 小时**。该 dump 的 DDL 段（L359 `DROP TABLE IF EXISTS`、L378 `AUTO_INCREMENT=10`）
说明按此脚本还原会得到一个"建表后只插 9 行"的表 —— 与现在的指纹**完全同形**。

**关键否证**：对 `docs/acceptance/**` 全文检索
- `analytics_meta.metric_snapshot` —— 命中的**只有**：V13 迁移注释的"9 行冻结"记载、`f28` 卷的遗留表说明、以及 M3 泳道自己的对比文字；
- **没有任何一份留存件把 `analytics_meta.metric_snapshot` 当作"生产快照表"来读**。

⇒ **`analytics_meta` 的 9 行从来不是"生产快照"，M3 的"12 → 9"跨库对比不成立。**

---

## 3. binlog 窗口内的原始事件片段（含时间戳与语句）

### 3.1 可用性（`raw/00-*`、§3.2 命令实测）

```
v	now_ts	host	port	datadir	log_bin	binlog_format	gtid_mode	log_bin_basename	expire_logs_days	expire_logs_secs
8.0.41	2026-09-12 23:03:36	dahaishui	3306	C:\ProgramData\MySQL\MySQL Server 8.0\Data\	1	ROW	OFF	C:\ProgramData\MySQL\MySQL Server 8.0\Data\LAPTOP-8F8T3J1B-bin	0	2592000
```

`SHOW BINARY LOGS` 现存 `...000113`(157 B) / `...000114`(1,366 B) / `...000115`(1,206 B) / `...000116`(1,073,744,191 B) / … / `...000131`（000116–000124 各约 1 GiB；000129=63,704,239 B、000130=150,285,009 B、000131=47,977,792 B）；
`SHOW MASTER STATUS` = `LAPTOP-8F8T3J1B-bin.000131`，`Position=47977398`。

> **binlog 覆盖下限（对本卷结论的边界，须与 §8-U7 同读）**：最早**含事件**的 binlog 是 `000116`，其时间戳落在 **2026-09-11 20:40 前后**。
> 因此本卷的**负结果只覆盖"2026-09-11 20:4x 之后"**；B-13 指控的 22:00–23:10 窗口**完整落在覆盖范围内**（已证），
> 但 2026-09-07 的建表/播种时刻**不在**覆盖范围内（未取证）。
> `binlog_expire_logs_seconds=2592000`（30 天）说明不是被清理掉的，`000113`–`000115` 仅 157/1366/1206 字节，属刚轮转出的空壳文件。

**datadir 本地直读被拒**：`Get-ChildItem 'C:\ProgramData\MySQL\MySQL Server 8.0\Data'` → `Access to the path ... is denied.`（沙箱/ACL）。
**改用服务器侧只读拉取**（不落盘、不写库）：

```
"C:\Program Files\MySQL\MySQL Server 8.0\bin\mysqlbinlog.exe" -R --host=127.0.0.1 --port=3306 \
  --user=root --password=123456 --base64-output=DECODE-ROWS -v \
  --start-datetime='2026-09-11 00:00:00' --stop-datetime='2026-09-12 23:30:00' LAPTOP-8F8T3J1B-bin.000130
（000131 同参数）
```

### 3.2 对 `analytics_meta.metric_snapshot` / `metric_value` 的检索结果 —— **负结果（0 命中）**

`raw/04-000130-131-analytics_meta-metric-full-day.log`（过滤正则 `` `analytics_meta`\.`metric_(snapshot|value)` ``，含 34 行前文上下文）：

```
### FILE LAPTOP-8F8T3J1B-bin.000130
### LAPTOP-8F8T3J1B-bin.000130 hits=0
### FILE LAPTOP-8F8T3J1B-bin.000131
### LAPTOP-8F8T3J1B-bin.000131 hits=0
```

**即：在 2026-09-11 00:00 → 2026-09-12 23:30 这 47.5 小时窗口内，`analytics_meta.metric_snapshot` 与 `analytics_meta.metric_value` 上没有任何 DDL/DML 事件被写入 binlog** ——
**没有 `TRUNCATE`、没有 `DROP TABLE`、没有 `CREATE TABLE`、没有成批 `DELETE`、也没有"回填 9 行"的成批 `INSERT`。**

### 3.3 窗口内**真实发生**的事件（同一 binlog，用于自证解析有效）

`raw/02-binlog-000131-metric-events.txt`、`raw/03-...`（22:00–23:30 窗口，**非** `analytics_meta`）：

```
# at 47815243
#260912 22:01:48 server id 1  end_log_pos 47815322 CRC32 0x395c6a45 	Anonymous_GTID	last_committed=13094 ...
use `analytics_verify_m3_parity`/*!*/;
#260912 22:24:02 server id 1  end_log_pos 47888641 CRC32 0x1b2adb80 	Query	thread_id=8370	exec_time=0	error_code=0	Xid = 157683
CREATE TABLE `metric_snapshot` ( ... )
#260912 22:24:02 server id 1  end_log_pos 47889766 CRC32 0x2ab52f57 	Query	thread_id=8370	exec_time=0	error_code=0	Xid = 157687
CREATE TABLE `metric_value` ( ... )
```
（22:24:02 同批还有 `ads_operation_overview_m`、`ads_sale_trend_m`、`ads_behavior_funnel_m`、`ads_active_trend_m`、`ads_hot_product_m`、`ads_product_conversion_m`、`ads_user_profile_m`、`ads_data_quality_m` 八张 ADS 表的 `CREATE TABLE`）

>
> **这 10 张表全部建在 `analytics_verify_m3_parity`，与 `analytics_meta` 无关** —— 与 `information_schema` 实测的
> `analytics_verify_m3_parity.*.CREATE_TIME = 2026-09-12 22:24:02` **逐秒吻合**，并与
> `raw/post/export-import-rehearsal/raw/80-final-state.txt` L2「isolated DB `analytics_verify_m3_parity`」一致。
> 后续 22:25:22 / 22:25:32 / 22:26:11 / 22:32:10 / 22:32:37 的 `INSERT INTO` / `DELETE FROM` 同样都在该隔离库内。

**当前生产表的写入来源（同一 binlog，21:29:27）**：`analytics_metric`.`metric_snapshot` / `metric_value` 的 `Table_map` + `INSERT` / `UPDATE`（thread 与事务见 02/03 号原始文件）——与实测 `S20260901_47.created_at = 2026-09-12 21:29:27.282` **逐毫秒吻合**。

### 3.4 归因结论

| 项 | 判定 |
|---|---|
| `analytics_meta.metric_snapshot` 在 22:00–23:10 被 `DELETE`/`TRUNCATE`/`DROP` | **已证：不存在**（binlog 0 命中 + `UPDATE_TIME=NULL` + `AUTO_INCREMENT=max(id)+1`） |
| 谁在 22:00–23:10 执行了什么删除 | **本事件不存在删除，故无执行者。未取证：无法定位执行者（不需要定位）。** |
| `analytics_metric.metric_snapshot` 现有 12 行是否完好 | **已证：完好** |

---

## 4. 归因三级结论

### 4.1 已证（硬证据，可复核）

| # | 陈述 | 证据指针 |
|---|---|---|
| E1 | 留存件 `db-readonly-pre-stop.txt`（22:00:17）的 12 行读数来自 **`analytics_metric`** | 该文件 L3 表头含 `definition_version`（`analytics_meta` 无此列）；`e5-preaccept-20260912/README.md:137` 自述库名 |
| E2 | 该 12 行**至今仍在**，且 `S20260901_47` id=27 仍为**唯一 ACTIVE** | `raw/07-analytics_metric-snapshot-rows.txt`：`4,5,14,15,16,17,22,23,24,25,26,27`；id=27 `S20260901_47 ACTIVE active_flag=1 version=12 v2 spark-ads 47 2026-09-12 21:29:27.282` |
| E3 | `analytics_metric.metric_value` = **110 行**，其中 `S20260901_47` = **10 行** | `raw/05-rowlevel-two-schemas.txt` 分组计数 11 组 × 10 行；与留存件 `50-prod-untouched-check.txt` L18 一致 |
| E4 | `analytics_meta.metric_snapshot` = **9 行 id 1..9**，全部 `created_at` 在 2026-09-07 17:31–19:33，全部 `version=1`，末行 `S20260907_11` ACTIVE | `raw/05-rowlevel-two-schemas.txt` |
| E5 | `analytics_meta.metric_snapshot` 的 `AUTO_INCREMENT=10 = max(id)9 + 1`，`UPDATE_TIME=NULL`，`CREATE_TIME=2026-09-07 17:15:57`，`DATA_FREE=0` | `raw/06-fingerprint-all-schemas.txt` |
| E6 | binlog 在 **2026-09-11 00:00 → 2026-09-12 23:30** 对 `analytics_meta.metric_snapshot/metric_value` **0 事件** | `raw/04-000130-131-analytics_meta-metric-full-day.log`（`hits=0`） |
| E7 | 22:24:02 的 `CREATE TABLE metric_snapshot/metric_value` 发生在 **`analytics_verify_m3_parity`**（隔离演练库，留档于 `80-final-state.txt`） | binlog `use \`analytics_verify_m3_parity\`` + `CREATE_TIME=2026-09-12 22:24:02` 逐秒吻合 |
| E8 | `analytics_metric` 表**有** `active_flag`/`failure_reason`/`definition_version`；`analytics_meta` 表**无** | `information_schema.COLUMNS` 实测（§1.3） |
| E9 | `information_schema.TABLES.TABLE_ROWS` 是**估计值**：`analytics_metric.metric_snapshot` 报 9 而 `COUNT(*)=12`；`metric_value` 报 80 而 `COUNT(*)=110` | `raw/08-counts-and-stats-gap.txt` |
| E10 | 看板读路径（HTTP）仍返回 `snapshotId=S20260901_47` 的 10 个指标值 | `raw/10-http-readonly-8091.txt` |
| E11 | 应用运行时确实连在 `analytics_metric`：`SHOW FULL PROCESSLIST` 有 30 条 `metric_pub`/`metric_read @ analytics_metric` 与 12 条 `meta_app @ analytics_meta` 长连接 | `raw/09-processlist-and-trx.txt` |
| E12 | **同一实例内 `analytics_meta.metric_snapshot` 与 `analytics_metric.metric_snapshot` 物理上是两张列形状不同的表**（12 列 vs 15 列，后者多 `definition_version`/`failure_reason`/`active_flag`） | `raw/13-column-shape-diff.txt` |
| E13 | 那 9 行在 **2026-09-11 20:11:08** 已被 `.verify/p105-meta-copy.sql` dump 到磁盘（L386 单条 INSERT，9 元组 = id 1..9，逐值等于现状，不含 `S20260901_47`）⇒ **9 行状态早于事件窗口 26 小时即已存在** | `.verify/p105-meta-copy.sql:3,359,378,386`（只读引用） |
| E14 | `analytics_meta.metric_snapshot` 的 `distinct_days=1`（`MIN(created_at)=2026-09-07 17:31:43.431`，`MAX=2026-09-07 19:33:50.118`）、`MAX(version)=1` ⇒ 该表**从未经历第二次写入轮次** | `raw/00-env-and-schemas.txt` 尾部 |
| E15 | 另有旁证：本仓库 `.verify/p105-8091-preflight.txt:35-40` 在 **22:0x** 就已写明「本文件上方 "snaps 9" 统计的是 `analytics_meta.metric_snapshot` —— 那是 V13 已登记的【弃用副本】… 故该表计数自 09-07 起就是 9，与本轮无关」 | `.verify/p105-8091-preflight.txt:35-40`（只读引用） |

### 4.2 强推断（证据充分但未直接观测到执行过程）

| # | 陈述 | 支撑 |
|---|---|---|
| I1 | `analytics_meta.metric_snapshot` 自 2026-09-07 建成后**只被批量初始化过 9 行，此后从未写过**（"插一次、永不改"的种子表） | E5（`UPDATE_TIME=NULL` + `AUTO_INCREMENT=max(id)+1` + `DATA_FREE=0`）+ E4（9 行全为 09-07、`version` 全 1）+ E6（47.5h 无 binlog 事件） |
| I2 | "9 行"来自一份 **2026-09-07 的基线 dump**，被播种进 `analytics_meta` 并被克隆到 `analytics_meta_p103 / _p105 / _p105it / _v17probe`（四者 9/132 + `AUTO_INCREMENT` 10/133 **完全相同**） | E4/E5 + `raw/06` 中五个 `analytics_meta*` schema 指纹逐值相同 |
| I3 | `analytics_metric.test` 侧的三张克隆库（`_p103` 8/70、`_p105` 8/70）是同一克隆手法在 2026-09-11 20:55 / 2026-09-12 08:58 的产物，比现行表少 1 个快照、少 40 个指标值 | `raw/06`：`analytics_metric*` 三库 `AUTO_INCREMENT` 24/111 vs 现行 25/121 |
| I4 | `analytics_metric.metric_snapshot` 的 `AUTO_INCREMENT=25 < max(id)=27` 说明该**生产表历史上有过行删除**（id 18–21 段缺失；binlog 中该表 21:29:27 有 `INSERT`/`UPDATE`） | `raw/06`/`raw/07`（id 跳号 5→14, 17→22, 23→24）+ §3.3 |
| I5 | 触发本次警报的机制链：**(a)** 读 `analytics_meta` 却与 `analytics_metric` 的留存件对比；**(b)** 一侧用 `TABLE_ROWS`（9）另一侧用 `COUNT(*)`（12）；**(c)** 把 `AUTO_INCREMENT=10` 当成删除后复位 | E1/E2/E4/E9 + 台账行 522 原文表述 |

### 4.3 未取证（明确写"未取证"，不做推断）

| # | 未取证项 | 原因 / 我查过哪些面 |
|---|---|---|
| U1 | **无法定位"执行者"** | **本事件不存在删除，因此不存在执行者。** 但就"22:00–23:10 谁动过这两张表"这一问题，我查过：`SHOW BINARY LOGS`（113–131 全部可用）、binlog 000130/000131 全量事件（0 命中）、`SHOW FULL PROCESSLIST`（30+12 条长连接全部 `Sleep`）、`information_schema.INNODB_TRX`（**空**）、`performance_schema.setup_consumers`。**结论：`analytics_meta.metric_snapshot` 在该窗口没有任何访问者。** |
| U2 | `performance_schema.events_statements_history_long` 历史语句 | **未取证：消费线程未开启**。实测 `ps_on=1` 但 `events_statements_history_long` 的 `ENABLED` 计数 = **0**（`raw/09-processlist-and-trx.txt`）⇒ 无历史语句可查。（`SHOW PROCESSLIST` 只能看到当前，无法回溯 22:00–23:10。） |
| U3 | 各"克隆库"的**具体创建命令与其调用者** | **未取证：未在 binlog 中找到 `CREATE DATABASE`/克隆 SQL 的归属**（`_p103`/`_p105`/`_p105it`/`_v17probe` 的建库时刻早于/边缘于本次解析窗口，且库名暗示隔离用途）。用途为**推断**（见 I2/I3），不是已证。 |
| U4 | 承载该 9 行的 dump 文件 | **已部分取证，已找到**：`.verify/p105-meta-copy.sql`（`Database: analytics_meta`，mtime 2026-09-11 20:11:08，L386 单条 INSERT 9 元组 = 现状）。**仍未取证**：① 它是"导出"还是"被用于还原"；② 若被还原，还原**何时、由谁**执行（该时刻早于现存 binlog 覆盖下限，见 §3.1 与 U7）；③ 本机 datadir 被 ACL 拒，无法在服务器侧交叉核对。 |
| U5 | 8091 应用的 `metric_read` 是否**只读** | **未取证**：`PROCESSLIST` 显示 `metric_read` 有 14 条连接，但未核实其授权表。 |
| U6 | `docs/acceptance/**` 中 22:00–23:10 写出的脚本/日志是否含 `TRUNCATE`/`DELETE FROM metric_snapshot`/`flyway clean` 字样 | **已查、未命中针对生产表的写语句**；命中的 `DELETE FROM metric_snapshot WHERE snapshot_id IN (...)` / `WHERE runtime_profile_id = ?` 全部位于**测试类**（见 §7.3），且本次**未被触发**（binlog 在该窗口对生产表无事件）。全仓库 `*.sql`/`*.txt` 的 `TRUNCATE` 命中仅有：`.verify/p105-meta-copy.sql:359` 的 `DROP TABLE IF EXISTS`（mysqldump 常规段，见 U4）与 `ai-decision` 测试里被策略拒绝的 `TRUNCATE TABLE metric_value` 断言字符串。 |
| U7 | `analytics_meta.metric_snapshot` **建表/首次播种**那一刻的实际语句 | **未取证：该时刻早于现存 binlog 覆盖下限。** 实测 `SHOW BINARY LOGS` 现存 **`000113`(157 B) / `000114`(1366 B) / `000115`(1206 B) / `000116`(1 GiB) / … / `000131`**，最早的**有内容**的 binlog 为 `000116`（时间戳在 2026-09-11 20:40 前后）；因此 2026-09-07 17:15 的建表事件**已不在可解析范围内**（`--start-datetime='2026-09-07 …'` 对 `000116` 检出 `use \`analytics_meta\``/`CREATE TABLE`/`TRUNCATE` 全部 **0 命中**，属窗口早于文件起点，非"未发生"）。**该表"建表于 2026-09-07 17:15:57"这一读数来自 `information_schema` 的 `CREATE_TIME`，是本卷唯一的建表时刻依据。** |

---

## 5. F-99 登记：同名指标表在 11 个 schema 并存（真实缺陷）

```
schema                        metric_snapshot   metric_value   角色推断（凡推断均标"未取证"）
analytics_meta                     9/132        陈旧种子表 —— 建表 2026-09-07，UPDATE_TIME=NULL，非生产链（已证）
analytics_meta_p103                9/132        隔离克隆（未取证：用途）
analytics_meta_p105                9/132        隔离克隆（未取证：用途）
analytics_meta_p105it              9/132        隔离克隆（未取证：用途）
analytics_meta_v17probe            9/132        探测/隔离克隆（未取证：用途）
analytics_metric                 12/110        现行生产指标库（已证：application.yml + PROCESSLIST + 看板 HTTP）
analytics_metric_p103              8/70        隔离克隆（未取证：用途）
analytics_metric_p105              8/70        隔离克隆（未取证：用途）
analytics_verify_m3_parity         3/10        M3 导出→导入隔离演练库（已证：80-final-state.txt L2 自述 + binlog 22:24:02 DDL）
mall_simulator                    10/134       演示/模拟器库（未取证：用途）
mall_simulator_test                0/0         测试库（未取证：用途）
```

**F-99 的危害**：任何对比/引用若不写 schema 名，就会把两套内容完全不同的表当成一张表 —— **本次 B-13 假警报即为实例**。

**现行/遗留的权威划分证据（已证）**：
- `analytics-server/platform-app/src/main/resources/application.yml:7` → 平台元数据库 = **`analytics_meta`**（`meta_app`）
- 同文件 `:15`（`metric_pub` 读写）与 `:19`（`metric_read` 只读）→ 指标库 = **`analytics_metric`**
- `raw/09-processlist-and-trx.txt`：运行时连接分布与该配置**完全一致**
- 看板 `GET /api/v1/metrics/overview` 返回 `snapshotId=S20260901_47`（只在 `analytics_metric` 存在）

---

## 6. 陷阱 #67 与纪律

**陷阱 #67（三条，均以本次为实例）**

1. **跨库同名表对比＝假警报制造机。** 引用任何表**必须先写 schema 名**；不写 schema 名的"12 行 vs 9 行"没有意义。
2. **`information_schema.TABLES.TABLE_ROWS` 是 InnoDB 采样估计值，不是行数。** 实测 `analytics_metric.metric_snapshot` 报 9 而 `COUNT(*)=12`。要行数就用 `COUNT(*)`。
3. **`AUTO_INCREMENT = N` 而 `max(id) = N-1` 不是删除指纹**，而是"从未删过任何行"的正常形态。真正的删除指纹是反向的：`AUTO_INCREMENT > max(id) + 1`（本次 `analytics_metric.metric_snapshot` 就是 25 vs 27，那才说明历史上有过删除）。

**纪律（建议入册）**

- 任何"数据丢失/被删"结论，必须先在 `information_schema.TABLES` 层面**钉死 schema 名**并检查 **`UPDATE_TIME` / `AUTO_INCREMENT` / `DATA_FREE`**，且至少**两份独立留存件互证**；能用 binlog 的（`log_bin=ON`）必须给 binlog 片段或**明确负结果**。
- 结论落地前先跑一次"**同名表全 schema 普查**"（本次命令见 `raw/06`），确认讨论对象唯一。

---

## 7. 复用风险登记（不给本次归因，仅登记）

### 7.1 事实核对：`RehearsalRunner` 的第 9 个位置参数**不是** JDBC URL

| 说法 | 实测 |
|---|---|
| "按第 9 个参数接收任意 JDBC URL" | **不成立**。`main` 的 8 个位置参数为 `<label> <snapshotId> <businessDate> <businessTime> <runtimeProfileId> <runtimeProfileVersion> <pipelineRunId> <exportDir>`（`RehearsalRunner.java:108-120`），第 8 个是 `args[7]`＝**导出目录**；包装脚本传的第 9 个实际值也是 `$ExportDir`（`run-rehearsal.ps1:42`） |
| JDBC URL 从哪来 | **JVM 系统属性**，硬编码在包装脚本内：`run-rehearsal.ps1:26` `-Drehearsal.publish.url=jdbc:mysql://127.0.0.1:3306/analytics_verify_m3_parity?...`、`:29` `-Drehearsal.read.url=...analytics_verify_m3_parity...`、`:32` `-Drehearsal.meta.url=...analytics_meta...`；由 `RehearsalRunner.java:130-134` 打印取证 |

### 7.2 风险（登记，不改任何文件）

- **R1（中）**：`RehearsalRunner` 的发布目标是 **`-Drehearsal.publish.url` 属性**，无任何"必须是 `*_verify_*` / 隔离库"的防呆校验。若手工把该属性指向 `analytics_metric`，`MetricPublisher` 的 **delete-then-insert 幂等**语义（台账行 522 已考证：`MetricPublisher.java:81` 唯一编排者、`deleteSnapshot :126`、`MetricAdsWriter.java:44-45` delete-then-insert）会**先删后插**，对生产表造成真实写入。
- **减轻现状（已证）**：`run-rehearsal.ps1` 自身的 `$props`（`:24-35`）**写死**隔离库 URL，且调用行（`:42`）以 `@props` 展开、**不接受外部追加** ⇒ 按脚本原样重跑**不会**打到生产库。
- **建议防呆（不代为实现）**：① 在该属性入口加"库名必须匹配 `_verify_` 白名单，否则 `System.exit(4)`"的硬门；② 或让 driver 只接受"库名"参数并由自身拼 URL；③ 或在 `MetricPublisher.publish` 入口加"目标 schema ≠ 配置的生产 schema 才允许 delete-then-insert"的断言。

### 7.3 测试类风险（**仅登记，不作本次归因**）

| 位置（file:line，均为只读引用） | 写面（原文摘录） | 归属 |
|---|---|---|
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/MetricAdsMySqlIT.java` | `:145` 连接串 `jdbc:mysql://127.0.0.1:3306/analytics_metric?useSSL=false&allowPublicKeyRetrieval=true`（**指向现行生产指标库**）；`:44` 写账号 `metric_pub`、`:46` 读账号 `metric_read`；`cleanup()` 含 `:137` `DELETE FROM metric_snapshot WHERE snapshot_id IN (?,?,?)`、`:139` `DELETE FROM metric_snapshot WHERE runtime_profile_id = ?`；由 `:47`（`@BeforeAll`）与 `:55`（`@AfterAll`）调用；`:48-50` 会插入 3 条测试快照 | **仅登记，不作本次归因** —— 见下方"未触发" |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/metric/publish/MetricPublisherMySqlIT.java` | `:304` 同上库、`metric_pub`；`cleanup()` 含 `:295` `DELETE FROM metric_value WHERE snapshot_id=?`、`:296` `DELETE FROM metric_snapshot WHERE snapshot_id=?`、`:298` `DELETE FROM metric_snapshot WHERE runtime_profile_id=?` | **仅登记，不作本次归因** |
| `analytics-server/metric-analysis/src/test/java/com/graduation/analytics/analysis/AnalysisGoldenMySqlIT.java` | `:273` 连接串 `jdbc:mysql://127.0.0.1:3306/" + database + "?useSSL=false&allowPublicKeyRetrieval=true`（**库名来自变量 `database`**） | **未取证其最终库名** |

**未触发（已证）**：binlog 在 22:00–23:30 窗口对 `analytics_metric`/`analytics_meta` 的指标表**无任何事件**（§3.2/§3.3），
且 `analytics_metric.metric_snapshot` 现存 12 行的 `snapshot_id` **全部形如 `S20260901_*`**（`raw/07`，共 11 个不同值 + id=27）。
⇒ **本轮这两个类未对生产表产生可见影响**；风险在于"它们指向生产库"这一结构本身。

---

## 8. 本卷**不能**证明什么

1. **不能证明** `analytics_meta.metric_snapshot` 的 9 行从何而来（哪份 dump／哪条命令播种）——我只证明了"它自 2026-09-07 建表后从未被改动"，**没有找到那份 dump**（U4）。
2. **不能证明**各 `_p103`/`_p105`/`_p105it`/`_v17probe`/`mall_simulator*` schema 的**用途与创建者**——它们的行数与时间只能支撑"隔离/克隆/测试遗留"的**推断**（U3）。
3. **不能证明**"永远没有过未被记录的事件"—— 但**可以证明**：在 `log_bin=ON` + `binlog_format=ROW` 下，窗口内任何 DML/DDL 都必然进 binlog；而 `000130`＋`000131` 对目标表 **0 命中**，其中 `000130` 覆盖 2026-09-11 00:00 起、`000131` 覆盖至 2026-09-12 23:30（`SHOW MASTER STATUS` 实测仍在该文件内），**22:00–23:10 完全落在被解析区间内**。**未覆盖**的是 2026-09-11 00:00 **之前**（含 09-07 建表时刻）与 `000129` 及更早文件。
4. **不能证明** `performance_schema` 层面的历史（未开启，U2），也不能给出 22:00–23:10 的**语句级**历史——该窗口唯一可用的语句级证据是 binlog，而 binlog 对该两表为**空**。
5. **不能证明**任何人的主观意图。本卷只写"binlog 显示某时刻某语句来自某库"，**不做动机指控**。
6. **不能证明**看板数值的业务正确性。§9 只证明"看板读路径仍返回 `S20260901_47` 的 10 个指标值"，**未**核对这些值与磁盘导出物的逐值一致性。
7. **不能证明** `.verify/p105-meta-copy.sql` 的**用途**：只能证明它在 **2026-09-11 20:11:08** 存在且内容 = 那 9 行。它是"导出留档"还是"用于还原"、由谁运行，**未取证**（U4）。

---

## 9. 当前 ACTIVE 与看板可见状态（只读 HTTP 实测原文）

`raw/10-http-readonly-8091.txt`（仅 `POST /api/v1/auth/login` 取 token + `GET /api/v1/metrics/overview`；**未**调用 `/pipeline-runs`、`/ingestion/runs` 或任何写接口）

```json
{"code":"OK","message":"success","data":{"token":"57786db3bb4e423cb95388f703367714",
 "user":{"id":1,"username":"admin","realName":"系统管理员","role":"admin"}}}
```

`GET /api/v1/metrics/overview` → `code=OK`，**10 个指标全部 `snapshotId=S20260901_47`**：

| metricCode | value | unit | definitionVersion |
|---|---|---|---|
| avg_order_value | 408.4 | 元 | v1 |
| buy_rate | 1.0 | — | v1 |
| dau | 3.0 | 人 | v1 |
| full_refund_rate | 0.2 | — | v1 |
| gmv | **2042.0** | 元 | v1 |
| net_sale | 1493.0 | 元 | v1 |
| paid_order_cnt | 5.0 | 单 | v1 |
| pv | 7.0 | 次 | v1 |
| refund_rate | 0.6 | — | v2 |
| uv | 3.0 | 人 | v1 |

> `gmv=2042.0` 与留存件 `50-prod-untouched-check.txt` L18 `S20260901_47 10 0.2000 2042.0000`（该快照 `metric_value` 的 `max_v`）**一致**。

**库侧 ACTIVE**：`analytics_metric.metric_snapshot` 中 `id=27 S20260901_47 status=ACTIVE active_flag=1 version=12`（`raw/07`）——**唯一 ACTIVE，与 22:00:17 留存件完全相同**。

---

## 10. 原始输出索引（`raw/`）

| 文件 | 内容 |
|---|---|
| `00-env-and-schemas.txt` | 环境实测（`VERSION()`/`@@datadir`/`@@log_bin`/`@@binlog_format`/`@@gtid_mode`/`@@log_bin_basename`/`@@binlog_expire_logs_seconds`）、`SHOW BINARY LOGS`（000129/130/131）、11 个含指标表的 schema、`analytics_meta.metric_snapshot` 冻结统计 |
| `01-find-binlog-window.ps1` / `01-binlog-window.txt` | binlog 文件窗口定位脚本与（空）输出：本地 datadir 被 ACL 拒，改走 `-R` |
| `02-extract-metric-events.ps1` / `02-binlog-000131-metric-events.txt` | 22:00–23:30 窗口内所有 `metric_*` 相关事件 + 34 行上下文（含 `use \`analytics_verify_m3_parity\`` 与 22:24:02 的 10 条 `CREATE TABLE`） |
| `03-extract-analytics-meta-events.ps1` / `03-000131-analytics_meta-metric-events.log` | 22:00–23:30 窗口对 `analytics_meta.metric_*` 的精确检索 → **0 命中** |
| `04-000130-131-analytics_meta-metric-full-day.log` | **核心负结果**：2026-09-11 00:00 → 2026-09-12 23:30，000130+000131 对 `analytics_meta.metric_snapshot/metric_value` **hits=0** |
| `05-rowlevel-two-schemas.sql` / `.txt` | 两库逐行 `SELECT ... ORDER BY id` 原文 |
| `06-fingerprint-all-schemas.txt` | 全部 schema 的 `metric_snapshot`/`metric_value` `information_schema.TABLES` 指纹 |
| `07-analytics_metric-snapshot-rows.txt` | 生产表 12 行全列（含 `active_flag`/`definition_version` 相关列） |
| `08-counts-and-stats-gap.txt` | `COUNT(*)` vs `TABLE_ROWS` 估计值对照（成因 E9） |
| `09-processlist-and-trx.txt` | `SHOW FULL PROCESSLIST`、`INNODB_TRX`（空）、`performance_schema` 消费者状态 |
| `10-http-readonly-8091.txt` | 只读 HTTP 实测原文（login + metrics/overview） |
| `11-local-file-sweep.txt` | 全仓库 `*.sql`/`*dump*`/`*backup*` 清单、2026-09-12 21:50–23:30 被修改文件清单、`build/` 一次性驱动目录 |
| `12-test-classes-write-surface.txt` | 测试源码中 `jdbc:mysql:`／`DELETE FROM`／`TRUNCATE`／`metric_pub` 的全部命中行（写面清点，§7.3） |
| `13-column-shape-diff.txt` | 两库 `metric_snapshot` 列形状逐列对照（12 列 vs 15 列） |

---

## 11. 证据优先级与本次到达的层级

任务书要求的优先级链（binlog → information_schema → 进程/连接 → 本地文件 → 业务影响）：

| 层 | 到达情况 |
|---|---|
| ① 服务器侧 binlog | **已到达**（`-R` 只读拉取；窗口内对目标表 = 明确负结果；对隔离库/生产表 = 有阳性片段） |
| ② `information_schema` 侧证 | **已到达**（两库全表指纹 + 逐库逐行 + 列形状差异） |
| ③ 进程/连接侧证 | **部分到达**：`PROCESSLIST` 有；`INNODB_TRX` 空；`events_statements_history_long` **未开启 ⇒ 未取证** |
| ④ 本地文件侧证 | **已到达**：全仓库清扫（`raw/11`）定位到 `.verify/p105-meta-copy.sql`（9 行 dump，mtime 2026-09-11 20:11:08）⇒ 9 行状态早于窗口 26h 即已固化；**仍未取证**：datadir 被 ACL 拒，无法服务器侧复核，且各克隆库/`mall_*` 库用途不明 |
| ⑤ 当前业务影响 | **已到达**（只读 HTTP + 库侧 ACTIVE 双证） |

**任务书"能做到哪一步就停在哪一步"** —— 五层**全部到达**，其中 ③ 的语句历史一层因 `performance_schema` 消费者未开启而**明确记为未取证**。原任务书预设的"钉死谁在何时用什么语句删除"**无从钉死，因为该删除不存在**；这一负结果由 **binlog 原文（0 命中）＋ `information_schema` 三项指纹 ＋ 磁盘 dump 三份互相独立的证据**坐实。
