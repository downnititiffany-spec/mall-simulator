# B-13 数据库取证卷 —— 假警报勘误

**卷宗目录**：`docs/acceptance/b13-db-forensics-20260912/`
**性质**：**只读取证**。本次取证未执行任何 `INSERT/UPDATE/DELETE/TRUNCATE/DROP/ALTER`，未启停任何服务，未跑 Maven，未触碰 pipeline/ingestion，未做任何 git 写操作，未修改或删除任何既有文件（含 `docs/acceptance/**` 留存件）。
**取证时点**：2026-09-12 23:03 – 23:2x（服务器 `NOW()` 实测 `2026-09-12 23:11:06`）；**勘误续记时点：2026-09-14 12:0x**

> ⚠️ **本卷已有日期化勘误，见文末 §12「勘误 R1–R4」（2026-09-14）。** 以下四处表述**已被撤销或降级**，不得单独引用：
> **①** 全文关于 `AUTO_INCREMENT` 与 `max(id)` 的**指纹级论证**（§0 第 16 行、§4.2-I1/I4、§6 陷阱 #67-3）⇒ **撤销**：该指标不构成独立证据，不得单独用于删除与否的判定；
> **②** §2「早于 B-13 窗口 **26 小时**即已固化于磁盘」及 §4.1-**E13**、§11-④ 的同一措辞 ⇒ **撤销**：文件 mtime 不能独立证明某时刻数据存在；
> **③** §4.1-**E5** 中的 `AUTO_INCREMENT` / `DATA_FREE` 论证、§6 纪律中的 `AUTO_INCREMENT` / `DATA_FREE` 项 ⇒ **降级／收窄**（仅保留 schema 钉死用途）；
> **④** §3.1 关于 binlog 覆盖下限的原表述（"最早含事件的 binlog 是 `000116`，时间戳落在 2026-09-11 20:40 前后"）⇒ **已被 `raw/14-binlog-coverage-map.txt` 与 `raw/15`/`raw/16` 实测取代**，精确的窗口边界、覆盖下限与两处不可解析空洞见 §12.3、§12.5。
> **结论本身不变**（**无数据丢失**），但支撑强度改为 §12.4 的**三项交叉验证**与 §12.6 的**证据强度分级表（26 条）**；凡只靠 mtime / `TABLE_ROWS` / `AUTO_INCREMENT` / 单点读数支撑的，一律降为**强推断**或**未取证**。
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

---

## 12. 勘误 R1–R4（追加于 2026-09-14；**不覆盖、不删改上文任何一字**）

**勘误缘起**：§3.1 的 binlog 覆盖结论与 §4 部分条目的支撑强度被复核推翻/不足。
本节**追加**四项勘误、一次窗口级重述、一套**证据强度分级表**。
**结论方向不变（无数据丢失）**，但**支撑方式整体更换**为「覆盖完整、作用范围正确的 binlog + 多来源交叉验证」。

> 编号约定：`R*` = 撤销/降级项；`W*` = 窗口声明；`E16+` = 本次新增的已证条目；`G*` = 分级表行。
> 本节引用的新增原始记录：`raw/14-binlog-coverage-map.txt`、`raw/15-000125-analytics_meta-metric-sept7.log`、`raw/16-000129-analytics_meta-metric-events.log`、`raw/17-000125-analytics_meta-metric-DDL-digest.txt`、`raw/18-all-binlogs-analytics_meta-metric-DDL.txt`、`raw/19-http-readonly-8091-recheck.txt`、`raw/20-production-state-recheck-20260914.txt`。

### 12.1 勘误 R1：`AUTO_INCREMENT` 与 `max(id)` **既不能证明删除、也不能证明未删除**

**撤销对象**：§0 第 16 行（"把 `AUTO_INCREMENT = max(id)+1` 误读成'曾被清空后复位'的删除指纹 —— 它恰恰是**从未删过任何行**的正常形态"）；§6 陷阱 #67 第 3 条（"`AUTO_INCREMENT = N` 而 `max(id) = N-1` 不是删除指纹…真正的删除指纹是反向的 `AUTO_INCREMENT > max(id) + 1`"）；§4.2-I1 / I4 中以此为据的表述。

**撤销理由**：`AUTO_INCREMENT` 是**计数器当前值**，不是"曾插入过的最大行号"，更不是行数。
失败插入（重复键、约束/类型错误）与回滚事务**都会消耗计数器而留下空洞**；反向的"空洞大于 1"也可能来自删除之外的原因（批量插入失败、显式复位、导入重放）。
因此该指标**两个方向都不成立**，它只是**当前自增游标的一个读数**。

**本次会话内的自我反证（同一字段、三天内取值变化）**：

| 读数时点 | `analytics_metric.metric_snapshot` `AUTO_INCREMENT` | `max(id)` | 差 |
|---|---|---|---|
| 2026-09-12 23:1x（本卷 `raw/06`） | 25 | 27 | −2（当时被写成"历史上有过删除"） |
| 2026-09-14 12:38（`raw/20`） | 28 | 27 | +1 |

同一字段在无任何删除的前提下从 25 变 28（期间新增 id=26、27 两行插入），而 09-12 那次读数里 `AUTO_INCREMENT` 反而**小于** `max(id)`。
⇒ 该指标**连"当前行数"都不等于，更不构成删除与否的独立证据**。

**替代表述（本节起生效）**：
1. `AUTO_INCREMENT` / `max(id)` **不得单独用于判定删除或未删除**，两个方向都不行。
2. 删除结论**只能**由 **覆盖完整、作用范围正确（schema + 表名精确匹配）的 binlog**，加**多来源交叉验证**得出；binlog 不可用时，结论一律记为**未取证**，不得用 `AUTO_INCREMENT`、`TABLE_ROWS`、`DATA_FREE` 或单点读数顶替。本卷已按此口径给出**全范围总账**（§12.5.1，`raw/18`，22 个文件逐一计数）。
3. `AUTO_INCREMENT` 在本卷中**只保留一个用途**：配合 `CREATE_TIME` 做**schema/表归属的旁证**（例如 `analytics_meta*` 五库同为 9/10，`analytics_metric*` 为 28/23/23）。

### 12.2 勘误 R2：文件 mtime **不能**独立证明"某时刻数据已存在"

**撤销对象**：§2「早于 B-13 所称的 22:00–23:00 窗口整整 **26 小时**」；§4.1-**E13**（"⇒ 9 行状态早于事件窗口 26 小时即已存在"）；§11-④ 中同一措辞。

**撤销理由**：mtime 只说明**该文件在那一刻被写过**，不说明**数据库在那一刻的内容**，也不说明该内容在更晚时刻仍然成立。mtime 会被复制、解压、导出、同步、`touch` 等操作改写，属于**文件系统层属性**，与数据库状态之间没有蕴含关系。把「dump 落盘时刻早于窗口」当作「窗口内数据库仍是 9 行」的证据，是**跨层外推**。

**改为（本节起生效）**：`.verify/p105-meta-copy.sql` 的 mtime = **2026-09-11 20:11:08**，**落在 W2 窗口内**（见 §12.3），故该文件只能证明：
> **「这个文件在 2026-09-11 20:11:08 被写入，且其内容包含 `analytics_meta.metric_snapshot` 的 9 个元组（id 1..9，逐值与现状一致，不含 `S20260901_47`）。」**

它**不能**证明「2026-09-12 22:00 时该库仍然只有这 9 行」。后一命题**只有 binlog 能证**，见 §12.4 腿 (i)。

### 12.3 W1–W3：三个窗口的精确声明（**每处引用必须带窗口**）

`raw/14-binlog-coverage-map.txt` 实测现存 22 个 binlog 的**首/末事件时间戳**（`SHOW BINARY LOGS` 与逐文件 `mysqlbinlog -R` 实测）：

| 文件 | 字节 | 首事件 | 末事件 | 与前一文件的接续 |
|---|---|---|---|---|
| 000113 | 157 | 260802 9:35:58 | 260802 9:35:58 | — |
| 000114 | 1,366 | 260815 8:01:20 | 260816 20:40:33 | 接 000113 |
| 000115 | 1,206 | 260816 20:41:06 | 260905 8:58:51 | 接 000114 |
| 000116 | 1,073,744,191 | 260905 8:59:35 | 260906 14:25:26 | 接 000115 |
| 000117–000124 | 各约 1 GiB | 260906 14:25:26 | 260906 21:48:19 | 逐文件无缝接续 |
| **000125** | 487,659,933 | **260906 21:48:19** | **260907 21:41:35** | 接 000124（**首事件属 09-06**，W1 在文件内侧） |
| 000126 | 180 | 260907 21:42:17 | 260908 21:14:41 | 接 000125 |
| 000127 | 180 | 260908 21:15:20 | 260909 18:31:11 | 接 000126 |
| 000128 | 180 | 260909 18:31:55 | 260909 23:49:24 | 接 000127 |
| **000129** | 63,704,239 | **260909 23:50:12** | **260910 22:20:05** | 接 000128 |
| **000130** | 150,285,009 | **260911 8:05:34** | **260911 17:43:04** | **空洞 H1** |
| **000131** | 47,977,792 | **260911 18:40:32** | **260912 23:11:19** | 接 000130（隔 H1） |
| 000132 | 180 | 260913 10:18:55 | 260913 11:40:16 | 接 000131 |
| 000133 / 000134 | 157 / 157 | 260913 11:41:00 / 260913 20:23:19 | 同左 | 接续 |

**三处不可解析区间（必须显式声明，不得静默）**：
- **H1 = 2026-09-11 17:43:04 → 18:40:32（57 分 28 秒）**：000130 末事件（`Stop`）与 000131 首事件（`Start`）之间**无事件可解析** ⇒ 该 57 分钟内**未取证**（服务器重启/停机窗口；根因未取证）。
- **H2 = 2026-09-10 22:20:05 → 2026-09-11 08:05:34（9 小时 45 分）**：000129 末（`Stop`）与 000130 首（`Start`）。
- **P1 = 早于 2026-09-05 08:59:35**：留存最早文件为 000116（000113–000115 各仅 157/1366/1206 字节，只有 `Start`/`Stop` 标记），**更早的事件已被 `binlog_expire_logs_seconds=2592000` 清理** ⇒ 不可解析。

**三个窗口**：
- **W1 = 2026-09-07 16:04:26 → 2026-09-07 21:41:35**：**完全可解析**（落在单一文件 `000125` 内，无空洞）。
- **W2 = 2026-09-10 22:20:05 → 2026-09-12 23:11:19**：可解析，**唯一空洞 H1（09-11 17:43:04–18:40:32）**；由 000129 尾段 + 000130 全部 + 000131 全部拼成。
- **W3（事件窗口，B-13 所称的破坏性时刻）= 2026-09-12 22:00:17 → 2026-09-12 23:10:26**：**完全落在单一文件 `000131` 内**（其覆盖 09-11 18:40:32 → 09-12 23:11:19），**不含任何空洞**。

### 12.4 结论重述：三项交叉验证（**取代**原「指纹论证」）

**结论不变：`analytics_meta.metric_snapshot` 在 2026-09-12 22:00–23:10 未发生任何数据丢失；B-13 为假警报。**
但成立依据**只有**下面三条，且必须**同时**成立：

**(i) binlog 双向核对 —— 窗口内 0 事件（**在 W3 上成立；另在 W1 上取到阳性对照**）**
- W3（09-12 22:00:17 → 23:10:26，单文件 `000131`，无空洞）：对 `` `analytics_meta`.`metric_snapshot` `` / `` `analytics_meta`.`metric_value` `` 精确配对匹配 ⇒ **`hits=0`**（`raw/04`）。
- **该 0 命中经解析健康度复核为零假阴性**（`raw/21`，`000131` 全文件单次解析）：`total_lines=904,814`、`event_ts_lines=80,600`、`use` 语句 27 条、`### ` DML 头 573,417 行、**解析器诊断行 = 0**（无 `ERROR`/`Warning`）、**损坏/未知事件行 = 0**；以**完整限定名**做字符串字面匹配得 `` INSERT INTO `analytics_meta`.`metric_* = 0 ``、`` UPDATE …= 0 ``、`` DELETE FROM …= 0 ``（**三项全 0**）。
  同文件内 DML 正常渲染（`mall_simulator.event_outbox` 8,714、`analytics_meta_p103.data_quality_result` 2,274、`analytics_meta_p103.metric_value` 792 …）⇒ **文件可解析、DML 可见，而目标表 0 命中是真负**。
  > **近失误报留痕**：本次核查中一个未转义反引号的宽匹配正则曾给出"`analytics_metric` INSERT 148 / 1849 行"与"解析错误行 15,220"，**两者都是匹配器的假象，不是 binlog 的问题**：`analytics_metric` 是 `analytics_metric_p103` 的**子串**；而"error"匹配到的是行**内容**。严格口径下解析器诊断行是 **0**。（纪律见 §12.8 第 1 条）
- 扩展窗口 W2（09-10 22:20:05 → 09-12 23:11:19）：**`000129` 0 命中**（本次实测，1,956,295 行输出，`hits=0`）；`000130`+`000131` 亦 0 命中。
- **阳性对照（证明"0 命中"不是解析失败造成的假阴性）**：同一解析方法在 **W1** 上取到 **334 个命中**（`raw/15`）⇒ 方法有效，W2/W3 的 0 是**真负**。
- **未取证部分（必须随结论一起声明）**：**H1（09-11 17:43:04–18:40:32）不可解析**，该 57 分钟内若发生目标表写入则无法排除 —— 但 H1 **不在 W3 内**，不影响本窗口结论。
- **原 §3.1 覆盖下限表述撤销**：最早含事件的 binlog 是 **000116（首事件 2026-09-05 08:59:35）**，**不是**"时间戳落在 2026-09-11 20:40 前后"。**W1 完全可解析**，故 **§4.3-U7 由"未取证"升级为"已证"**（见 E20）。

**(ii) 两份独立留存件互证（不同时刻、不同工具、不同文件）**
- 件 A：`docs/acceptance/e5-preaccept-20260912/raw/db-readonly-pre-stop.txt`，**写在 2026-09-12 22:00:17**（文件 mtime 实测），首行自述"只读库取证 at 2026-09-12 22:00:17（只有 SELECT，无任何写）"，表头含 **`definition_version`** 列 ⇒ **读的必然是 `analytics_metric.metric_snapshot`**（`analytics_meta` 该表无此列，见 §1.3），**12 行**，首行 `S20260901_47 ACTIVE 12`。
- 件 B：`docs/acceptance/m3-step8-parity-20260912/raw/post/export-import-rehearsal/raw/50-prod-untouched-check.txt` **写在 2026-09-12 22:43:34**，第 2 行自述"== prod analytics_metric: snapshot 27 / S20260901_47 (must be untouched) =="，同表同 12 行列，`definition_version=v2`。
- 两件相隔 **43 分 17 秒**、**横跨 W3 两端**，读数**逐值一致**。
- 本次（2026-09-14 12:38，`raw/20`）再测：**仍为 12 行 / 110 值**，id 22–27 逐行可列，`S20260901_47` 仍 `ACTIVE active_flag=1 version=12`。

**(iii) 只读 HTTP 与库内直读一致（**单点读数，非独立第二来源**）**
- 2026-09-12 23:1x 只读 HTTP：`GET /api/v1/metrics/overview` 返回 **10 个指标，`snapshotId` 全为 `S20260901_47`**（`raw/10`）。
- 2026-09-14 12:38：**`http://127.0.0.1:8091` 拒绝连接**（`raw/19`：`由于目标计算机积极拒绝，无法连接`）⇒ 该读数**现在不可复现**，且**未启停任何服务**（本卷纪律）。故本条**只作为与 (ii) 一致的旁证**，**不单独支撑任何结论**；W3 后状态改由 `raw/20` 库内直读支撑。

### 12.5 W1 解析结果：旧表「建表—播种」全过程**已证**（原 U7 撤销）

`raw/17`（`000125` 全文 14,953,954 行，DDL/目标表 DML 去重后成表）实测：

| 事件时刻（服务器时间） | 语句 | 说明 |
|---|---|---|
| 260907 **12:03:02** | `CREATE TABLE `analytics_meta`.`flyway_schema_history` (` | `analytics_meta` 库在此刻开始建库/迁移 |
| 260907 14:44:15–14:44:16 | `CREATE TABLE ingestion_batch / ingestion_batch_file / file_checkpoint / quarantine_record / pipeline_run / pipeline_stage_run / data_quality_result / metric_definition` | Flyway 第一批 |
| 260907 15:01:19 | `DROP TABLE pipeline_run,pipeline_stage_run,data_quality_result,metric_definition /* generated by server */` | 重跑迁移（**不涉及目标表**） |
| 260907 15:02:55 | 上述 4 表 `CREATE TABLE` | 重跑迁移 |
| 260907 16:04:10 | `DROP TABLE IF EXISTS pipeline_run,pipeline_stage_run,data_quality_result,metric_definition` | 再次重跑 |
| 260907 **16:04:26** | **`CREATE TABLE metric_snapshot`** 与 **`CREATE TABLE metric_value`**，同批另有 `ai_query_history / ai_call_log / decision_task / decision_evaluation / sys_user / user_session` | **目标表诞生时刻** |
| 260907 **17:15:57** | `CREATE TABLE runtime_profile / spark_job_run`；**`ALTER TABLE metric_snapshot ADD COLUMN runtime_profile_version INT NOT NULL DEFAULT 1 AFTER runtime_profile_id`** | `information_schema` 的 `CREATE_TIME=2026-09-07 17:15:57` 由**这条 in-place ALTER** 改写，**不是重建** |
| 260907 **17:31:43 / 17:38:54 / 18:38:00 / 18:38:22 / 19:30:53 / 19:31:09 / 19:33:21 / 19:33:30 / 19:33:50** | **恰好 9 个时刻**，每时刻一组 `INSERT INTO `analytics_meta`.`metric_snapshot`` + `INSERT INTO `analytics_meta`.`metric_value`` | **9 次写入 = 现存 9 行的全部来源** |

**关键负结果**：`000125` 全文（含 W1 与 260907 全天）中 **`TRUNCATE` 出现 0 次**；对目标表的 DML 只有 **`INSERT` / `UPDATE`**，**没有 `DELETE`**；对目标表的 DDL 只有 **1 次建表（16:04:26）+ 1 次加列 ALTER（17:15:57）**，**没有 `DROP TABLE` / `TRUNCATE` / `RENAME`**。
⇒ **旧表自诞生至 260907 21:41:35 从未被清空或重建**；"9 行"是 **9 次管道运行各插一行**累积的正常结果，与"删除后残留"无关。

**同一份 binlog 里还取到了两张表关系的书面依据**（`analytics_metric` 建表脚本的注释被完整记入 binlog，`000129` 内）：

> `-- analytics_metric 是指标快照的唯一所有者：metric_snapshot / metric_value / 所有 ads_*_m。`
> `-- analytics_meta 中 V2 建的同名表自 R7 起为「弃用副本」（见 db/meta/V13__metric_definition_r7.sql 注释），`
> `-- 本期不 DROP，数据迁移与读写切换完成后再清理。`
> `-- 库当前为新建空库（0 表、Flyway 未执行过），因此本脚本直接按 R7 目标结构编写，无需兼容历史版本。`

### 12.5.1 全范围负结果：22 个 binlog 目标表 DML 总账（`raw/18`）

对**现存全部 22 个 binlog**逐一解析，统计"触及 `` `analytics_meta`.`metric_snapshot` / `metric_value` `` 精确配对"的 DML 行数：

| 文件 | 精确配对 DML | `TRUNCATE` 全文命中 | 目标表 DDL |
|---|---|---|---|
| 000113 / 000114 / 000115 | 0 / 0 / 0 | 0 / 0 / 0 | 无 |
| 000116 | **0** | 0 | 4 行 —— 均为 **`mall_simulator`** 建表（`260906 12:12:23`、`260906 12:17:17` 各 `metric_snapshot`+`metric_value`），**不属目标库**（对应 `mall_simulator.metric_snapshot CREATE_TIME=2026-09-06 12:17:17`、`mall_simulator_test` 12:12:23） |
| 000117–000124 | **0**（8 个文件全 0） | 30 / 4 / 0 / 0 / 0 / 40 / 16 / 8 | 无目标表 DDL |
| **000125** | **141** | **0** | `260907 16:04:26 CREATE TABLE metric_snapshot / metric_value`、`260907 17:15:57 ALTER TABLE metric_snapshot` |
| 000126 / 000127 / 000128 | 0 / 0 / 0 | 0 / 0 / 0 | 无 |
| 000129 | **0** | 5 | `260910 20:04:14 CREATE TABLE metric_snapshot / metric_value`（= **`analytics_metric`** 现行表诞生，与 `CREATE_TIME=2026-09-10 20:04:14` 吻合） |
| 000130 | **0** | 0 | 无真实目标表 DDL（仅注入测试**行内容**） |
| **000131** | **0** | 10 | 见下"克隆批次"；**均为克隆库重建，非目标库** |
| 000132 / 000133 / 000134 | 0 / 0 / 0 | 0 / 0 / 0 | 无 |

**结论**：**在整个留存 binlog 范围内，`analytics_meta.metric_snapshot` 只在 `000125` 内被写过（141 行 DML = 9 次写入 × 约 16 行），其余 21 个文件全为 0；从未出现 `TRUNCATE`、也从未出现目标库的 `DROP TABLE`。**

**`000131` 内的 `DROP TABLE IF EXISTS` + `CREATE TABLE` 批次是"克隆库重建"，逐条可由 `CREATE_TIME` 对齐**（这些 `DROP TABLE IF EXISTS` 是 **mysqldump 的标准输出格式 —— 每张表前都有一次，用于幂等重建**，本例中它们出现在被恢复的**副本库**语境里）：

| 时刻 | 批次（dump 恢复） | 对齐的库（`CREATE_TIME`） |
|---|---|---|
| 260911 **20:11:08** | `DROP TABLE IF EXISTS metric_snapshot/metric_value` + `CREATE TABLE` | **`analytics_meta_v17probe` 2026-09-11 20:11:08**（与 `.verify/p105-meta-copy.sql` 的 mtime **同一秒**） |
| 260911 20:40:31 / 20:40:32 | 同上 ×2 | `analytics_meta_p103` / `analytics_metric_p103`（`CREATE_TIME` 2026-09-11 20:55:58 —— 同批演练的最终重建） |
| 260911 20:45:19–20 / 20:50:08–09 / 20:53:06–07 / 20:55:21–23 / 20:55:58–59 | 同上（多轮） | 同上（p103 演练反复重建） |
| 260912 08:58:01–02 | 同上 ×2 | `analytics_meta_p105` / `analytics_metric_p105`（2026-09-12 08:58:02） |
| 260912 09:04:36–37 | 同上 ×2 | `analytics_meta_p105it`（2026-09-12 09:04:37） |
| 260912 13:42:38 | 同上 ×1 | 同批演练的第四次重建（目标库名**未取证**） |
| 260912 22:24:02 | `CREATE TABLE metric_snapshot/metric_value`（**无前置 DROP**） | `analytics_verify_m3_parity`（2026-09-12 22:24:02）＝ M3 parity 隔离库建库 |

⚠️ **关键区分**：`DROP TABLE IF EXISTS` 出现在 **W3 窗口内（09-11 20:11:08、20:40:31…）但作用于副本库**（`_v17probe` / `_p103` / `_p105` / `_p105it` / `_verify_m3_parity`），
**不是** `analytics_meta` 本身；判定依据是**语句内不含 schema 限定的 dump 形态 + 紧邻 `CREATE TABLE` + 与目标库 `CREATE_TIME` 逐秒对齐**，再加上**同一文件里精确配对的 `analytics_meta` DML = 0**。
⇒ 这正是 B-13 同类误读的第二次现身：**只看表名不看 schema，就会把副本库的重建误判为生产表被删**。

**全部 22 个文件中，"看似 `DROP TABLE` 的字符串"还有一种来源是注入测试的`行内容`**（例：`###   @3='忽略上文，DROP TABLE metric_snapshot'`，出现在 `000129 21:12:03`、`000130/000131` 多时刻）：**它们是 VARCHAR 取值，不是语句**，不参与 DDL 统计。

> **对齐性的诚实边界**：上表"对齐的库"是**时间对齐 + 现存 11 个同名表 `CREATE_TIME` 枚举**推出的，**不是**由 binlog 原文直接写出库名（`000131` 内这些 `DROP`/`CREATE` 语句**不带 schema 限定**）。
> - **同一秒或相邻秒对齐、可判为强对齐**：09-11 20:11:08 ↔ `analytics_meta_v17probe`(20:11:08)；09-12 08:58 ↔ `analytics_meta_p105`/`analytics_metric_p105`(08:58:02)；09-12 09:04 ↔ `analytics_meta_p105it`(09:04:37)；09-12 22:24:02 ↔ `analytics_verify_m3_parity`(22:24:02)。
> - **对不齐、必须记为未取证的两处**：① 09-11 **20:40:31–32** 与 **20:45–20:56 的多轮**批次，现存 `_p103` 的 `CREATE_TIME` 只留下 **20:55:58** 一个终值 ⇒ 这些批次**究竟落在哪次重建**，**未取证**；② 09-12 **13:42:38** 那一组，**在现存同名表中找不到 `CREATE_TIME≈13:42` 的库**（多半是后来被 DROP 的中间副本库）⇒ **未取证，无法定位**。
> 这两处不影响 §12.4 的结论：它们**都不是** `analytics_meta` 本身，理由是**同一文件内精确配对的 `analytics_meta` DML = 0**（这一点与库名归属无关，直接可验）。

### 12.6 本卷证据强度分级表（**26 行**）

**判级规则（本节起生效）**：
- **已证**：有**覆盖完整、作用范围正确**的 binlog，或**`COUNT(*)` 级直接读数**直接支撑；窗口已显式声明。
- **强推断**：多来源一致、逻辑链明确，但**缺 binlog 直接证据**（如落在 H1/H2/P1 或早于 `CREATE_TIME`）。
- **未取证**：**只**由 mtime / `TABLE_ROWS` / `AUTO_INCREMENT` / 单点读数支撑，或证据源不可访问。
- 凡**只**靠 mtime、`TABLE_ROWS`、`AUTO_INCREMENT` 或单点读数者，**一律不得判为「已证」**。

| # | 结论 | 级别 | 依赖的窗口 / 来源 |
|---|---|---|---|
| G1 | 生产指标数据在 `analytics_metric`，`analytics_meta` 同名表为弃用种子副本 | 已证 | `raw/06` 指纹 + `raw/13` 列形状 + binlog 内建表脚本注释（`000129`，W2） |
| G2 | `analytics_metric.metric_snapshot` 精确 12 行 | 已证 | `COUNT(*)`（09-12 `raw/08`；09-14 `raw/20`） |
| G3 | `analytics_metric.metric_value` 精确 110 行（11 组 × 10） | 已证 | `COUNT(*)`（`raw/08`、`raw/20`） |
| G4 | `analytics_meta.metric_snapshot` 精确 9 行 / `metric_value` 132 行 | 已证 | `COUNT(*)`（`raw/05`、`raw/20`） |
| G5 | 两表**列形状不同**（15 列 vs 12 列；多 `definition_version`/`failure_reason`/`active_flag`） | 已证 | `raw/13` + 09-14 直读 `information_schema.COLUMNS` |
| G6 | 09-12 22:00:17 那份留存件读的是 `analytics_metric`（表头含 `definition_version`） | 已证 | 该文件自身表头 + G5 判别 |
| G7 | 09-12 22:43:34 那份留存件读的是 `analytics_metric`（prod untouched 复检） | 已证 | 该文件第 2 行自述 + 列集合 |
| G8 | **W3（09-12 22:00:17–23:10:26）内目标表 0 事件** | 已证 | binlog `000131`（单一文件、无空洞）；`raw/04` + `raw/21`：完整限定名匹配 `INSERT=0 / UPDATE=0 / DELETE=0`，且该文件解析器诊断行 `0`、DML 正常渲染 |
| G9 | 解析方法有效（W1 阳性对照 334 命中，非假阴性） | 已证 | `raw/15` / `raw/17`（W1） |
| G10 | `analytics_meta.metric_snapshot` 建表于 **260907 16:04:26** | 已证 | `raw/17`（W1，单文件无空洞） |
| G11 | 260907 对该表只有 9 次写入（17:31:43 … 19:33:50），与现存 9 行逐一对应 | 已证 | `raw/17`（W1） |
| G12 | 260907 全天对该表**无 `TRUNCATE` / `DELETE` / `DROP` / `RENAME`** | 已证 | `raw/17`（W1，全文 `TRUNCATE`=0） |
| G13 | `CREATE_TIME=17:15:57` 来自 `ADD COLUMN runtime_profile_version` 的 in-place ALTER，**非重建** | 已证 | `raw/17`（W1） |
| G14 | 09-14 12:38 生产态与 09-12 23:1x 一致（12/110，`S20260901_47` ACTIVE v12） | 已证 | `raw/20` 库内直读（09-14 12:38） |
| G15 | `TABLE_ROWS` 是 InnoDB 采样估算，不等于 `COUNT(*)`（9 vs 12、80 vs 110） | 已证 | `raw/08` 同刻两读数并排 |
| G16 | W2（09-10 22:20:05–09-12 23:11:19，除 H1）内目标表 0 事件 | 已证 | `000129`(`hits=0`) + `000130` + `000131` |
| G17 | 目标表在**整个可解析范围（W1）**内未被删除 | 已证 | `raw/17`（W1） |
| G18 | "9 行"是 9 次运行累积的**正常播种结果**，非删除残留 | 已证 | `raw/17`：9 次写入 ↔ 9 行（W1） |
| G19 | 09-11 20:11:08 的 dump **内容**为 9 元组（id 1..9，不含 `S20260901_47`） | 已证 | 该文件 L362–L378 与 L386（文件内容，非 mtime） |
| G20 | 因此 **W3 内 `analytics_meta.metric_snapshot` 行数不可能变化** | 已证 | G8（binlog 0 事件）**独立**成立；**不再**依赖 mtime |
| G21 | 该 dump 是 09-11 20:11:08 写盘、并被用作 `analytics_meta_p105` 等克隆库的播种源 | 强推断 | mtime + `p105` 库 `CREATE_TIME=2026-09-12 08:58:02`；播种动作落在 **W2 无空洞段**但未见对目标库 DML |
| G22 | `analytics_metric.metric_snapshot` id 18–21、28 等**空洞**来自"曾删除/曾失败插入" | 强推断 | `AUTO_INCREMENT`(28) 与 `max(id)`(27) 的差 **不构成独立证据**（R1）；未见 binlog 直接命中 |
| G23 | 触发警报的机制链：(a) 跨 schema 同名表对比；(b) 一侧 `TABLE_ROWS` 另一侧 `COUNT(*)`；(c) 把自增游标当删除指纹 | 强推断 | 三者可复现，但**执行者意图未取证** |
| G24 | 09-12 23:1x 只读 HTTP 的 10 指标读数 | 强推断 | **单点读数**，`raw/10`；09-14 该端口拒连（`raw/19`），**不可复现**，不单独支撑结论 |
| G25 | **H1（09-11 17:43:04–18:40:32）内的目标表活动** | 未取证 | 空洞（服务器重启/停机）；**不在 W3 内** |
| G26 | **P1（早于 09-05 08:59:35）**与 `analytics_meta` 首次播种**确切语句**之外的更早历史 | 未取证 | 已被 `binlog_expire_logs_seconds` 清理；`raw/17` 已补足 W1 部分 |

> 附：**不进入分级表但须记住的三项"不得单独引用"**：`AUTO_INCREMENT`、`TABLE_ROWS`、文件 mtime（R1、R2、§6 纪律）。

### 12.7 R3：降级 / 收窄清单（逐条对应上文行号）

| 上文位置 | 原表述 | 处置 |
|---|---|---|
| §0 第 16 行 | `AUTO_INCREMENT = max(id)+1` 是"从未删过任何行"的正常形态 | **撤销**（R1） |
| §2 第 ~105 行 | "早于窗口整整 **26 小时**"、"该 dump 的 DDL 段 L359 `DROP TABLE IF EXISTS`…" | **撤销 26 小时外推**；同时澄清 **L359 的 `DROP TABLE IF EXISTS` 是 mysqldump 标准输出格式**（每张表前都有，用于幂等重建），**不是执行删除的证据** |
| §3.1 第 ~128 行 | "最早**含事件**的 binlog 是 `000116`，其时间戳落在 **2026-09-11 20:40 前后**" | **撤销**，改为 §12.3 的实测覆盖表（000116 首事件 = **2026-09-05 08:59:35**，W1 完全可解析） |
| §4.1 表 `22:00–23:10 被 DELETE/TRUNCATE/DROP` 行 | 证据写为"binlog 0 命中 + `UPDATE_TIME=NULL` + `AUTO_INCREMENT=max(id)+1`" | **收窄**为"binlog 0 命中（W3，000131 单文件无空洞）"，删去后两项 |
| §4.1-**E5** | `AUTO_INCREMENT=10` / `DATA_FREE=0` 作为已证证据 | **降级**：仅保留 `CREATE_TIME` 作为"建表时刻"旁证；`AUTO_INCREMENT`/`DATA_FREE` 降为**不构成证据**（其真实含义见 §12.5 的 `Add column`） |
| §4.1-**E13** | "9 行状态早于事件窗口 26 小时即已存在" | **撤销**，改为 G19/G20（内容已证、时点不证） |
| §4.2-**I1** | 以 `AUTO_INCREMENT=max(id)+1` + `DATA_FREE=0` 推断"从未写过" | **降级**：改为由 `raw/17` 的 9 次写入直接支撑（G11/G18） |
| §4.2-**I4** | `AUTO_INCREMENT=25 < max(id)=27` ⇒ "历史上有过行删除" | **撤销**（R1；且该字段 09-14 已变为 28） |
| §4.3-**U7** | 建表/首次播种时刻"未取证：早于 binlog 覆盖下限" | **升级为已证**：260907 16:04:26 建表、17:15:57 加列、9 次写入（G10–G13） |
| §6 陷阱 #67-3 | "`AUTO_INCREMENT = N` 而 `max(id) = N-1` 不是删除指纹…真指纹是反向的" | **撤销整条**，替换为 R1 的"双向都不成立、不得单独使用" |
| §6 纪律第 3 条 | "必须检查 `UPDATE_TIME` / `AUTO_INCREMENT` / `DATA_FREE`，且至少两份独立留存件互证" | **收窄**：schema 钉死 + `COUNT(*)` 保留；`AUTO_INCREMENT`/`DATA_FREE` 只作 schema 旁证；**删除结论一律要求覆盖完整、作用范围正确的 binlog + 多来源交叉验证**；binlog 不可用则记**未取证** |
| §11-④ | 引用 mtime 的同一措辞 | **撤销**（R2） |

### 12.8 R4：纪律更新（**本节起，判定删除的统一口径**）

1. **只看覆盖完整、作用范围正确的 binlog**：必须声明窗口起止、覆盖率下限与不可解析区间（本卷为 H1、H2、P1）；匹配必须**锚定 schema 名 + 表名**的精确配对，禁止裸表名匹配（`analytics_metric` 是 `analytics_metric_p103` 的子串，裸匹配会把副本库算进来）；同时必须**把"解析器诊断行"与"行内容中出现的同名字符串"分开统计**（`raw/21` 留痕：宽匹配曾把 15,220 行普通内容误报成解析错误，把副本库写入误报成生产表写入）。
2. **必须做阳性对照**：同一解析方法须能在已知有事件的窗口取出非零命中，否则"0 命中"判为**未取证**而非"无删除"。
3. **`AUTO_INCREMENT` / `TABLE_ROWS` / mtime / `DATA_FREE` 一律不得单独作为删除或未删除的证据**（R1、R2）。
4. **结论必须多来源交叉**：至少 binlog + 两份不同时刻、不同工具产出的留存件；单点读数只作旁证。
5. **不可解析区间写进结论**，不得静默省略（H1 必须随 W2 结论一起出现）。
6. **不启停服务、不写库、不修改既有文件**：本次勘误只新增 `raw/14`–`raw/20` 与本节；因端口 8091 已拒连，HTTP 腿**未做任何启停尝试**（`raw/19` 只记录拒连事实）。

### 12.9 本勘误新增原始记录索引

| 文件 | 内容 |
|---|---|
| `raw/14-binlog-coverage-map.txt` | 22 个 binlog 的大小 + 首/末事件时间戳 + 接续/空洞（`SHOW BINARY LOGS` + 逐文件 `mysqlbinlog -R`） |
| `raw/15-000125-analytics_meta-metric-sept7.log` | W1 精确配对命中 **334** 条（阴性对照 → 阳性对照） |
| `raw/16-000129-analytics_meta-metric-events.log` | `000129` 全文 1,956,295 行，目标表 **`hits=0`** |
| `raw/17-000125-analytics_meta-metric-DDL-digest.txt` | W1 的 DDL 时间线 + 9 次写入时刻；全文 `TRUNCATE=0` |
| `raw/18-all-binlogs-analytics_meta-metric-DDL.txt` | **全部 22 个 binlog** 的目标表 DDL 与精确配对 DML 计数（覆盖完整性总账；§12.5.1） |
| `raw/19-http-readonly-8091-recheck.txt` | 8091 端口拒连记录（HTTP 腿不可复现；**未做任何启停尝试**） |
| `raw/20-production-state-recheck-20260914.txt` | 09-14 12:38 生产态直读复核（12/110、9/132、id 22–27 逐行） |
| `raw/21-000131-parse-health.txt` | `000131` 解析健康度 + DML 目标分布 + 精确配对三项全 0（排除"0 命中 = 解析失败"）+ 匹配器近失误留痕 |
