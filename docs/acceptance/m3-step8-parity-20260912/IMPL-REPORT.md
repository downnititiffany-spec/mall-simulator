# M3 §6.4 步骤 6 + 步骤 8 合并执行 — 实施报告（专职执行泳道）

- 分支：`remediation/r1-boundary`
- 执行窗口：2026-09-12 20:53 – 21:29
- **结论一句话**：本地链路在 `BUILD_DWD`/`tdw` 被 P2-03 构建缺陷阻断；根因经**双实验钉死**为
  `dwd_order_detail` 的 **INSERT 与 DDL 位置错位**（`t.dt` 未随表增列右移），按总控授权完成
  **P2-03-m2 最小位置修复并重建 jar（第三代指纹已对照）**，**run 47 八阶段全绿**，
  本地 golden 快照 `S20260901_47` 与 10 个指标已产出。**逐值比对表 §5.5 仍为「未执行，无值」**
  （集群侧未提交）；集群侧未提交。

---

## 1. 判定（verdicts）

| 项 | 判定 | 证据 |
|---|---|---|
| 映射表运行前冻结 | ✅ PASS | sha256 `E80B357C…42F52F`，mtime 21:01:13 **早于**本地链路启动 21:03 |
| 输入隔离（61 文件移出） | ✅ PASS | 移出/复原 sha256 全等，0 mismatch |
| 摄取运行 | ✅ PASS | batch 43，accepted 51 / quarantine 4 |
| 本地链路 run 45 | ❌ FAILED | `BUILD_DWD` `RUN_JOB_FAILED`，80.2 s |
| 假设一（`nullSafe` 裸 NULL） | ❌ **已证伪（hypothesis-1 / FALSIFIED）** | 修复落包后 run 46 仍**逐字相同**报错；修复保留理由**仅限**类型确定性 |
| 假设二（INSERT/DDL 位置错位） | ✅ **已证实（钉死）** | 双实验复现+修好；run 47 全绿。详见 §3.5 与 `raw/post/p2-03-m2-rootcause-evidence.txt` |
| 叙述改正（工单 A，3 处） | ✅ **已完成并回读** | `raw/post/narration-fix-readback.txt`；**「第 19 项」已改为「`t.dt` 是第 20 项」**，「旧版静默错位」断言已删除 |
| P2-03-m 定型修复（E1/E2） | ✅ PASS | E1 编译 0 错误；E2 **111/111 通过**（JDK8） |
| P2-03-m2 位置修复（E1/E2） | ✅ PASS | E1 0 错误；E2 **111/111 通过**；**守卫证伪测试**精确命中（§4.4） |
| 新 jar 构建（m2） | ✅ PASS | **296,340 B** / 21:26:10 / sha256 `F9882E6A…`（对照见 §2.3）⚠️**该构建物已不存在**（§2.6 F） |
| 注释改正后重建 jar | 🟡 行为等价 | 296,359 B / `71C2BCCB…`；**仅 1 行注释**差异 + E2 111/111（§2.6 F） |
| 本地链路 run 46（m 修复后） | ❌ FAILED | `tdw` 同一错误码、同一列名、同一文案 |
| **本地链路 run 47（m2 修复后）** | ✅ **SUCCESS** | **八阶段全绿**，`tdw` in18/out139/rej0，21:29:29 终态 |
| **本地 golden 快照** | ✅ **已产出** | `S20260901_47`，`metric_snapshot` id=27，ACTIVE v12 |
| 集群链路 | ⛔ **未执行（本轮暂停令 D-142）** | 见 §6；前置门禁 G1–G4 未闭合，清单 `raw/post/cluster-gate-prep.txt` |
| 集群上游隔离（关键缺口） | ⚠️ **待裁定** | 脚本沿用 `--hiveDatabasePrefix=dw` ⇒ 会覆盖 E4 的 `dw_*` 证据；建议改 `m3s8`（§9 第 17 条） |
| **逐值比对表 §5.5** | ⛔ **未执行，无值** | 本地产物已有，但**集群侧未产出**⇒无右侧可比对象（见 §7） |
| 平台还原 | ✅ PASS | §5 |

### 1.1 实测 misfire（如实记录，不掩）
run 44 = 我的漏步运行（未先做 ingestion）。总控指示：自然跑完、记为 misfire、**排除出比对**。已照办。
- run 44 FINAL = FAILED（21:03:40.760 → 21:06:07.874），失败点同 run 45/46（`BUILD_DWD`）。
- **ACTIVE 指针未变**：仍为 `S20260901_43`（`version=11`，`active_flag=1`，published 17:39:51.953）。
- `metric_snapshot` 中 `S20260901_44` / `_45` / `_46` 行数均 = **0**（`COUNT(*)=0` 实测）。

---

## 2. 关键指纹（key fingerprints）

### 2.1 映射表（冻结证据）
- 路径：`docs/acceptance/m3-step8-parity-20260912/MAPPING-20260912.md`
- 49,517 B / 482 行 / mtime `2026-09-12 21:01:13`
- sha256 `E80B357CD8FCE13CD69D6DC0919FEBDB40127EB1974FF5F1A2F50CDED642F52F`
- 前一次抓取（44,800 B / sha256 `B3A249B1…E04445`，21:00:03）一并留档于 `raw/mapping-fingerprint-before-run.txt`。

### 2.2 输入（55 行 golden）
- 源：`hdfs://node01:8020/graduation/landing/m3s8-55/golden-20260901.jsonl`，18,430 B
- 落地副本与源**逐字节相同**，sha256 `2351BCC35E04CCD278638F07247BC7E9C4D402CC4736CD2A4D4B70F4232B11C`
  （该副本已按工序单步骤 6 **删除**；`file_checkpoint` 行保留为痕迹）

### 2.3 jar 指纹对照（四代，变量只有 jar）
| | ① 旧（缺陷版） | ② P2-03-m 定型修复 | **③ P2-03-m2 位置修复** | ④ 注释改正后重建 |
|---|---|---|---|---|
| 字节数 | 296,327 B | 296,334 B（+7） | **296,340 B**（+6） | 296,359 B（+19，**纯注释**） |
| mtime | 2026-09-12 20:52:06 | 2026-09-12 21:16:11 | **2026-09-12 21:26:10** | 2026-09-12 21:58:08 |
| sha256 | `B3E0F8F565AF262304F4677BE00DD35DC2DC3E3952160763B5DC009722417E3A` | `E8E8C9372E0190BA86CF5B8464FDD730F51911E60CCB57939E02DC6E10E1B1A3` | **`F9882E6A9A7662BEC2167D6037FB2917672B69890C5ACC0271B0A427B9432A0B`** | `71C2BCCB54066996E7197DEA004E88F16B4DF912481CB723B4D84CC33CE0C966` |
| 抽查 class major | 52 | 52 | **52**（JDK8 target，未漂移） | 52 |
| 修复是否在包内 | 否 | `SurrogateKey$.class` 含 `THEN CAST(NULL AS BIGINT) ELSE` | `TradeDwdJob$.class` 含 `category_key … t.dt` 末位形态 | 同 ③（`category_key … t.dt` 末位） |
| 对应 run | run 43 SUCCESS（位置正确、无错位） | run 44/45/46 FAILED | **run 47 SUCCESS** | **未跑链**（行为与 ③ 等价，见 §2.6 F） |

证据：`raw/post/p2-03-m-new-jar-fingerprint.txt`、`raw/post/p2-03-m2-new-jar-fingerprint.txt`、
`raw/post/narration-fix-readback.txt` §5。

> **④ 的性质**：与 ③ 的差异**仅** `TradeDwdJob.scala:176` 一行 SQL 行内注释（`git diff -U0` 全文 1 行）；
> 成员签名逐一未变，E2 = 111/111。**③ 的构建物已被 `clean` 删除**（未事先备份，操作失误已登记）。
> ⚠️ 另实测：**jar 整包 sha256 不可复现**（ZIP entry 内嵌时刻）⇒ 整包哈希只作落包记录，
> 稳定标识用**逐 entry 内容哈希 + class major**。此口径**待总控裁定**（见 §9 第 13/14 条）。

### 2.4 平台侧 jar
`analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`
= 33,135,937 B / mtime 17:21:07 / sha256 `C099F307D80135E2B657974F36D7AFB2CCA0F820A36A6EA9077D2A0ACBC8FDC6`
（未重建、未重启；jar 由 `spark-submit` 每作业新起进程读取，故修复无需重启平台）

### 2.5 run 45 / 46 / 47 阶段级对照
| 阶段 | run 45（修复前） | run 46（m 修复后） | **run 47（m2 修复后）** |
|---|---|---|---|
| `WAIT_LANDING` | SUCCESS records=51 | SUCCESS records=51 | **SUCCESS records=51**（≈51 预判命中） |
| `INIT_SCHEMA` | SUCCESS records=37 | SUCCESS records=37 | SUCCESS records=37 |
| `LOAD_ODS` | SUCCESS records=51 | SUCCESS records=51 | SUCCESS records=51 |
| `BUILD_DWD` `bdw` | SUCCESS in15/out14/rej1 | SUCCESS in15/out14/rej1 | SUCCESS in15/out14/rej1 |
| `BUILD_DWD` `dim` | SUCCESS in18/out7 | SUCCESS in18/out7 | SUCCESS in18/out7 |
| `BUILD_DWD` `tdw` | **FAILED** in0/out0 | **FAILED** in0/out0 | ✅ **SUCCESS in18/out139/rej0**（29 分区） |
| `BUILD_DWS` | — | — | SUCCESS records=3 |
| `BUILD_ADS` | — | — | SUCCESS records=22 |
| `QUALITY_CHECK` | — | — | SUCCESS records=6 |
| `PUBLISH_METRIC` | — | — | SUCCESS records=44 |
| 终态 | FAILED（80.2 s） | FAILED（21:17:06→21:18:16） | ✅ **SUCCESS**（21:26:36→21:29:29） |

- run 46 提交：`sourceDataVersion=m3s8-golden55c-202609122117`，`runId=46`（异步）。
  证据：`raw/post/run46-submit.txt`、`raw/post/run46-final.json`。
- run 47 提交：`sourceDataVersion=m3s8-golden55d-202609122126`，`runId=47`（异步）。
  证据：`raw/post/run47-submit.txt`、`raw/post/run47-final.json`。

### 2.6 run 47 证据绑定（**唯一权威汇总点**：阶段 / 快照 / 指标 / 运行期 jar 指纹）

> 本节把「run 47 八阶段 + `S20260901_47` + 10 个指标 + 运行期 jar 指纹」**绑定在一处**，
> 供索引与核对；细节展开见 §5A，本表为其**指纹级摘要**。

**A. 运行（run 47）**

| 项 | 值 | 证据 |
|---|---|---|
| runId | **47** | `raw/post/run47-final.json` |
| `sourceDataVersion` | `m3s8-golden55d-202609122126` | `raw/post/run47-submit.txt` |
| 起止 | **21:26:36 → 21:29:29** | `raw/post/run47-final.json` |
| 终态 | ✅ **SUCCESS**（八阶段全绿，`RUN_JOB_FAILED` 消失） | 同上 |

**B. 八阶段 records**（同一 run 47）
`WAIT_LANDING` 51 → `INIT_SCHEMA` 37 → `LOAD_ODS` 51 → `BUILD_DWD` 160 →
`BUILD_DWS` 3 → `BUILD_ADS` 22 → `QUALITY_CHECK` 6 → `PUBLISH_METRIC` 44，全部 SUCCESS。

**C. 快照**
`S20260901_47`；`metric_snapshot` id=**27** / `pipeline_run_id=47` / `status=ACTIVE` /
`version=**12**` / `active_flag=1` / `source=spark-ads` / `published_at=2026-09-12 21:29:27.321`。
存在性：`_43` 存在（ARCHIVED v11）、`_44`/`_45`/`_46` **不存在**、`_47` 存在。
证据：`raw/post/run47-snapshot-metrics.txt`。

**D. 10 个指标**（`metric_value` where `snapshot_id='S20260901_47'`，`COUNT(*)=10`，均 `period=day:2026-09-01`）
`avg_order_value` 408.4000 元 / `buy_rate` 1.0000 / `dau` 3.0000 人 / `full_refund_rate` 0.2000 /
`gmv` **2042.0000** 元 / `net_sale` **1493.0000** 元 / `paid_order_cnt` 5.0000 单 /
`pv` 7.0000 次 / `refund_rate` 0.6000（定义 v2）/ `uv` 3.0000 人。
证据：`raw/post/run47-snapshot-metrics.txt`。

**E. 运行期 jar 指纹（关键：run 47 实际用的那一版）**

| 项 | 值 |
|---|---|
| path | `spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar` |
| bytes | **296,340** |
| mtime | **2026-09-12 21:26:10** |
| sha256 | `F9882E6A9A7662BEC2167D6037FB2917672B69890C5ACC0271B0A427B9432A0B` |
| class major | **52**（JDK 8 目标；`SurrogateKey$` 与 `TradeDwdJob$` 均为 52） |

⇒ run 47 的「八阶段全绿」是在**上述指纹**的构建物上取得的。

**F. ⚠️ 该 296,340 B 构建物现已不存在；当前发布物指纹如下（须总控裁定口径）**

| 项 | 值 |
|---|---|
| bytes | **296,359** |
| mtime | 2026-09-12 21:58:08 |
| sha256 | `71C2BCCB54066996E7197DEA004E88F16B4DF912481CB723B4D84CC33CE0C966` |
| `TradeDwdJob$.class` | bytes 6316 / sha256 `CD979288A93A40C7FFDB8A1176C9C11AE905E449FB78158FAD7F173C090C34DD` / major **52** |
| 变化内容 | **仅 1 行 SQL 行内注释**（非业务改动，`git diff -U0` 全文见 `raw/post/narration-fix-readback.txt` §4） |
| 行为等价性 | `orderDetailInsertSql`/`OUT_SCHEMA` 等成员签名逐一未变；E2 = **111 tests / 15 suites / 0 aborted / BUILD SUCCESS** |

**G. 构建可复现性（实测，影响指纹口径）**
同一源码连续 3 次 `clean package` 得 **3 个不同 jar sha256**（296,359 B 相同），
原因是 jar（ZIP）为**每个 entry 内嵌写入时刻**（122 entries / 3 个不同时间戳）
⇒ **jar 整包 sha256 不适合作稳定构建物标识**；稳定标识应取**逐 entry 内容哈希 + class major**。
详见 `raw/post/narration-fix-readback.txt` §5。

---

## 3. 阻断根因（实测，含一次被证伪的假设）

### 3.1 tdw 报错原文（run 45 与 run 46 完全相同）
```
进程退出码非0(exit=1): [INCOMPATIBLE_DATA_FOR_TABLE.CANNOT_SAFELY_CAST] Cannot write incompatible
data for the table `spark_catalog`.`dw_dwd`.`dwd_order_detail`: Cannot safely cast `user_key`
"STRING" to "BIGINT".
```

### 3.2 已排除的嫌疑
1. **表结构旧** ✗：只读 `DESCRIBE dw_dwd.dwd_order_detail` = 23 列，列序与 `LocalSchemaInitJob.scala:63-72`
   **完全一致**，`user_key/product_key/category_key` **均为 bigint**；run 43 旧 jar 也曾成功写入该表 138 行。
   证据 `raw/post/describe-dwd-order-detail.txt`。
2. **代码字面没定型** ✗：`IdCodec.toBIGINT` = `CAST(REGEXP_EXTRACT(…) AS BIGINT)`；
   `keyFormula` → `GREATEST(cast(conv(…) AS BIGINT), 1)`，字面都是 BIGINT。
3. **新 jar 没被加载** ✗：jar mtime `21:16:11`，`tdw` 提交于 `21:18:02`（`lp-1789219082585-2b979c`）
   ⇒ 提交晚于重建 ⇒ 读到的确是修复版。

### 3.3 被证伪的假设（诚实记录）
原假设：`SurrogateKey.nullSafe` 的 `THEN NULL` 裸字面量使 `CASE` 被推断为 STRING ⇒ 写 BIGINT 被拒。
**证伪**：按总控 D-141 授权做**最小定型修复**（`THEN CAST(NULL AS BIGINT)`），E1/E2 全绿、jar 重建并
确认修复在包内，**run 46 复跑仍以完全相同的错误失败** ⇒ `nullSafe` 的裸 `NULL` **不是（或不只是）**该错误的原因。

**该修复的去留（总控裁定）**：**保留**为正确的类型卫生（`CASE` 显式定型是应有形态，E1/E2 全绿），
但它**不是根因**，**不得**据此声称 P2-03 已修好。P2-03 在总控口径下维持 **`REGRESSED`**。

### 3.4 时间线（钉死「什么变了」）
| 时刻 | 事件 |
|---|---|
| 17:37:56 | run 43 `tdw` **SUCCESS**（138 行 / 30 分区）← 旧 jar，**位置正确、无错位**（见 §3.5） |
| **20:48:56** | commit `ccc1dff` P2-03（代理键 + DDL 对齐，白名单 11 文件） |
| **20:52:06** | 重建 jar 296,327 B ← 缺陷版 |
| 21:03:40 / 21:06:48 | run 44 / run 45 `BUILD_DWD` FAILED |
| 21:16:11 | P2-03-m 修复版 jar 296,334 B（假设一） |
| 21:17:06 → 21:18:16 | **run 46 FAILED（同一错误）** ⇒ 假设一证伪 |
| 21:19–21:25 | 取证：`ccc1dff` 溯源 + 23 行位置对照 + **实验 1 复现 / 实验 2 修好** |
| **21:26:10** | P2-03-m2 位置修复版 jar 296,340 B（假设二） |
| **21:26:36 → 21:29:29** | **run 47 SUCCESS（八阶段全绿）**，快照 `S20260901_47` |

受控对照（变量只有 jar）：run 43 旧 jar SUCCESS（1,000 行 batch 42）↔ run 44 新 jar FAILED（同一 1,000 行输入）
⇒ `ccc1dff` 引入构建缺陷成立（总控 D-141 采纳）。

### 3.5 真根因（**已由双实验钉死**，不再是假设）
`TradeDwdJob.orderDetailInsertSql`：INSERT **不写列名**，与 DDL 全靠**位置**对齐。
而 `INSERT OVERWRITE TABLE … PARTITION (dt)` 未给分区值时，Spark **仍要求 SELECT 提供该分区列，
且它必须落在 SELECT 列表的最末位**。本 SQL 因此必须凑满 23 项（19 个数据字段 + 3 个代理键 + `dt`）。

**关键事实：`ccc1dff` 之前没有错位。**
旧表 = **19 个普通列 ＋ 动态分区列 `dt`**（20 列）；旧 SELECT = **19 个普通值 ＋ `t.dt`**（20 项）
⇒ `t.dt` 前面已有 **19 个普通字段**，它是**第 20 项**，正好对上分区位 ⇒ **位置正确**。

`ccc1dff` 给表**追加 3 个 BIGINT 列**（普通列 19→22，总列数 20→23），
并把 3 个代理键 SELECT 项**追加在 `t.dt` 之后**，而 `t.dt` 的 SELECT 位置**没动**（仍是第 20 项）
⇒ 按位置匹配时，第 20 个**普通列**已变成 `user_key`：

| 序 | 新表列 | 实际落在该位的 SELECT 项 | 类型 | 结果 |
|---|---|---|---|---|
| **20** | `user_key` **BIGINT** | **`t.dt`（STRING）** | STRING→BIGINT **不安全** | ❌ **阶段失败** |
| 21 | `product_key` BIGINT | `$userSurrogate`（BIGINT） | BIGINT→BIGINT | ⚠️ 类型侥幸安全，但**语义错列** |
| 22 | `category_key` BIGINT | `$productSurrogate`（BIGINT） | BIGINT→BIGINT | ⚠️ 同上 |
| 23 | `dt`（分区） | `p.category_key`（BIGINT） | BIGINT→分区 STRING | ⚠️ 未及诊断即被第 20 列拦下 |

**正确顺序**应为：19 个数据字段 → `user_key` → `product_key` → `category_key` → `t.dt`（分区列最末位），
即**与 DDL 顺序一致**。原代码把代理键插到 `t.dt` 之后，**恰好把 DDL 顺序写反了**。

⇒ 报错里的 STRING **就是 `t.dt`**；「位置错位」与「代理键」恰在第 20 列相撞，
故错误信息把两者显示在一起——这正是最初把注意力误导向 `user_key` 表达式的原因。
⇒ 也解释了「旧 jar run 43 SUCCESS / 新 jar run 44+ FAILED」而变量只有 jar：
**本缺陷在旧 jar 上根本不触发**（旧 SELECT 位置本就正确）。

> **撤回（父侧授权改正）**：
> 1. 早先本报告写「旧表 20 列时 `t.dt` 落在 `final_refunded_flag`(INT) ⇒ 静默写错」——**不成立，已删除**。
>    旧 SELECT 的第 19 项是 `final_refunded_flag` 本身，第 20 项才是 `t.dt`；旧表第 19 列也正是它 ⇒ 逐列对齐。
> 2. run 43（batch 42 / 1,000 行）与 run 47（batch 43 / 55 行）**输入批次不同**
>    ⇒ 其**行数/分区数差异不得**用作「旧版数据损坏」的证据。

> **假说一（`nullSafe` 裸 `NULL`）状态 = FALSIFIED（已证伪）**：标为 **hypothesis-1 / FALSIFIED**，
> **必要但不充分**；修复保留的**唯一**理由是「改善类型确定性」，**不得**与真因混写、**不得**作为根因。

**双实验（一次性表 `dw_exp.t_align_bad`，建后即删，全程未碰 `dw_dwd.dwd_order_detail`）**
- 实验 1（复刻错位形态：19 个普通值后紧跟 STRING 的 `t.dt`，使 `t.dt` 为**第 20 项**）
  ⇒ **逐字复现**同一错误：
  `Cannot safely cast user_key "STRING" to "BIGINT"`。证据 `raw/post/align-experiment-1-*.{sql,txt}`
- 实验 2（同一 SELECT，**仅**把 `dt` 移到末位）⇒ 写入成功并读回
  `EXPERIMENT2_OK 111 222 333 20260901 0`，逐列落位正确。证据 `raw/post/align-experiment-2-*.{sql,txt}`
- 收尾：`DROP TABLE dw_exp.t_align_bad; DROP DATABASE dw_exp;` 并二次确认
  （metastore `NoSuchObjectException`、warehouse 目录不存在）。证据 `raw/post/align-experiment-cleanup-*.txt`

**`user_key` 取值表达式已单独定型核查（总控最怀疑的一条）**：出口确为 BIGINT——
`SurrogateKey.toBIGINT` → `nullSafe` → `CASE WHEN … THEN CAST(NULL AS BIGINT) ELSE <keyExpr> END`，
`keyExpr` → `keyFormula` = `GREATEST(cast(conv(…) AS BIGINT), 1)`。
`clearSignBit` 的中间 STRING 始终被外层 `cast AS BIGINT` 吞掉。
⇒ **「`user_key` 被换成 STRING 形态」不成立**；STRING 来源另有其物 = `t.dt`。

完整证据（含 23 行逐列位置对照表、`ccc1dff` 溯源、机制图解）：`raw/post/p2-03-m2-rootcause-evidence.txt`。

---

## 3A. P2-03-m2 位置修复（最小改动）

### 3A.1 `TradeDwdJob.scala`（仅 1 处位置调整）
```diff
        |  t.final_paid_flag, t.final_refunded_flag,
-       |  t.dt,                                 -- 静态分区列（DDL 里在列尾，位置必须对齐）
        |  $userSurrogate AS user_key,
        |  $productSurrogate AS product_key,
-       |  p.category_key AS category_key
+       |  p.category_key AS category_key,
+       |  t.dt                                  -- 静态分区列**必须在最后一个位置**（见下）
        |FROM tdw_tmp t
```
**只调位置**：未改任何表达式（含 `user_key`/`product_key` 代理键）、未改 JOIN、未改 DDL、
未动 D-116 枚举护栏、D-087 NULL 语义与任何 SQL 语义。另加缺陷说明注释。
（源码旧注释本就写着「DDL 里在列尾，位置必须对齐」——**说对了却没做到**，这正是缺陷。）

### 3A.2 `IdCodecSpec.scala`（回归守卫 + 注释订正）
旧断言只有 `order should include("t.dt")`，**抓不到错位**（本缺陷正是这样逃过 E2 的）。新增**次序断言**：
```scala
pos("user_key") should be < pos("product_key")
pos("product_key") should be < pos("category_key")
pos("category_key") should be < pos("t.dt")                                     // 代理键都在 dt 之前
flat.substring(pos("t.dt"), flat.indexOf("FROM tdw_tmp")).trim shouldBe "t.dt"   // dt 是末位取值项
```
同时订正一处与新代码不符的旧注释（`THEN NULL` → `THEN CAST(NULL AS BIGINT)`）。

### 3A.3 守卫的**证伪测试**（必做，已做）
把 `t.dt` 临时放回「19 个普通值之后、代理键之前」（即错位形态，`t.dt` 为第 20 项）重跑 E2 ⇒
`1852 was not less than 753 (IdCodecSpec.scala:157)`，
**精确命中 `category_key` < `t.dt` 这条断言**；恢复修复形态后 E2 回到 111/111。
日志 `raw/post/p2-03-m2-falsification-probe.log`。⇒ 该守卫**确实能拦住本缺陷**，不是「写了就算」。

### 3A.4 验证口径（E1 / E2）
- **E1 编译 ✓**：`compiling 36 Scala sources`（JDK8 实跑 / `release 8` 门禁），0 错误。
- **E2 测试 ✓**：JDK8（`D:\Develop\JDK1.8`，1.8.0_202），
  `Total number of tests run: 111` / `Suites: completed 15, aborted 0` / `Tests: succeeded 111, failed 0` / **`All tests passed.`**
  （JDK17 跑测试会 `RUN ABORTED`：`sun.nio.ch.DirectBuffer` 模块访问 ⇒ **环境/口径问题，非代码问题**。）
- 证据：`raw/post/p2-03-m2-build-e2.log`。

---

## 4. P2-03-m 修复 diff 摘要

`git diff --stat` = **2 文件，24 insertions(+)，3 deletions(-)**；完整 diff：`raw/post/p2-03-m-fix.diff`。

### 4.1 `spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala`（代码 1 行）
```diff
-    s"CASE WHEN $cond THEN NULL ELSE $expr END"
+    s"CASE WHEN $cond THEN CAST(NULL AS BIGINT) ELSE $expr END"
```
- 调用点仅 **2 处**（`toBIGINT` :282、`literalSource` :301），均在 `SurrogateKey` 内且 `expr` 均为 BIGINT ⇒ 同族不变量。
- 另加缺陷说明注释 21 行（记录 run 44/45 报错原文与修法理由）。
- **未动**：D-116 枚举护栏、D-087 NULL 语义、`keyFormula` 公式、任何 SQL 语义、`*_key` 的 `-1` 兜底。

### 4.2 `spark-jobs/src/test/scala/com/graduation/analytics/SurrogateKeyVectorSupport.scala`（夹具 1 行）
```diff
-       |     THEN NULL ELSE ${SurrogateKey.keyFormula(sourceExpr, entity, rawIdExpr)} END
+       |     THEN CAST(NULL AS BIGINT) ELSE ${SurrogateKey.keyFormula(sourceExpr, entity, rawIdExpr)} END
```
理由：该夹具自带**内联**判空包装，若不与生产同形态就会漂移（正是该夹具注释警告的形态）。

### 4.3 验证口径（E1 / E2）
- **E1 编译 ✓**：JDK17，`compiling 36 Scala sources`，`compile in 12.1 s`，0 错误。
- **E2 测试 ✓**：JDK8（`D:\Develop\JDK1.8`，1.8.0_202），
  `Total number of tests run: 111` / `Suites: completed 15, aborted 0` / `Tests: succeeded 111, failed 0` / **`All tests passed.`**
  - 注：首次用 JDK17 跑测试曾 `RUN ABORTED`（`sun.nio.ch.DirectBuffer` 模块访问），改用 JDK8 后全绿 ⇒ **环境问题，非代码问题**。
  - `SurrogateKeySpec` 23 条契约向量逐条 KEY/NULL_KEY 全部相等（V13/V14/V15 三条 NULL_KEY 得 NULL 正确）。
- 证据：`raw/post/p2-03-m-build.log`、`raw/post/p2-03-m-e2-tests.log`。

---

## 5. 平台是否还原（证据）

### 5.1 输入隔离已完整回滚
- 61 文件 `landing/_m3s8-parked/events/` → `landing/events/`
- 复原后：**61 文件 / 406,496,411 B**；与移出前 `raw/park-move-out.txt` 逐文件 sha256 **0 mismatch**；
  文件名集合完全相同；park 目录残留 **0**；golden 副本已删。证据 `raw/park-move-back.txt`。

### 5.2 `/api/v1/ingestion/status` 与基线逐字段一致
| 字段 | 基线 20:57:47 | 还原后 | 一致 |
|---|---|---|---|
| `profileState` | ACTIVE | ACTIVE | ✅ |
| `landingUri` | `file://./landing` | `file://./landing` | ✅ |
| `eventsDir` | `…\landing\events` | `…\landing\events` | ✅ |
| `pendingFiles` | 61 | 61 | ✅ |
| `pendingBytes` | 406,496,411 | 406,496,411 | ✅ |
| `checkpointFiles` | 61 | 61 | ✅ |
| `newFileCount` | 0 | 0 | ✅ |
| `lastArrivalAt` | `…15:05:39.09632` | `…15:05:39.09632` | ✅ |
| `latestBatch` | 42 / SUCCESS / 1000 | **43 / QUARANTINED / 51** | ⚠️ 预期变化 |

证据：`raw/post/post-restore-ingestion-status.txt`、`raw/pre-run-ingestion-status.txt`。

### 5.3 平台未重启、边界未越
- PID **61104**，启动 19:17:49，命令行与看板 `docs/项目实施进度与任务看板.md:462` 一致，CWD=仓库根。
- 未碰 `e4c1000`、`/graduation/export/e4c1000`、`/graduation/export/m3s8-55`；未写 MySQL 业务数据
  （仅只读 SELECT + 平台自身 run 记录）；未改平台配置；landing 未再停放/未再采集；未复用 runId。

---

## 5A. run 47 结果：本地 golden 快照与 10 个指标

### 5A.1 快照
`metric_snapshot` **id=27** / `snapshot_id=**S20260901_47**` / `pipeline_run_id=47` / `status=ACTIVE` /
`version=**12**` / `active_flag=1` / `source=spark-ads` / `published_at=2026-09-12 21:29:27.321`
⇒ **ACTIVE 指针已从 `S20260901_43`（v11）切到 `S20260901_47`（v12）**。

### 5A.2 十个指标（`metric_value` 中 `snapshot_id='S20260901_47'`，`COUNT(*)=10`）
| metric_code | value | unit | period | def_ver |
|---|---|---|---|---|
| `avg_order_value` | 408.4000 | 元 | day:2026-09-01 | v1 |
| `buy_rate` | 1.0000 | — | day:2026-09-01 | v1 |
| `dau` | 3.0000 | 人 | day:2026-09-01 | v1 |
| `full_refund_rate` | 0.2000 | — | day:2026-09-01 | v1 |
| `gmv` | **2042.0000** | 元 | day:2026-09-01 | v1 |
| `net_sale` | **1493.0000** | 元 | day:2026-09-01 | v1 |
| `paid_order_cnt` | 5.0000 | 单 | day:2026-09-01 | v1 |
| `pv` | 7.0000 | 次 | day:2026-09-01 | v1 |
| `refund_rate` | 0.6000 | — | day:2026-09-01 | **v2** |
| `uv` | 3.0000 | 人 | day:2026-09-01 | v1 |

**支点确认**：`gmv=2042.00` / `net_sale=1493.00` 正是 R9 修正（D-R9-2）后的**正确值**
（未修复前放大值分别为 8168.00 / 5972.00，见 `TradeDwdJob.scala:74-76` 注释）
⇒ **位置修复没有破坏 JOIN 的维度分区谓词**（`p.dt`/`u.dt` 仍生效），否则金额会被放大。

### 5A.3 本地产物（8 张 ADS 行数）
目录 `metric-staging/S20260901_47/`：**18 文件 / 6,304 B**（`_export.json` + 8×`*.jsonl` + 8×`.crc`），
逐文件 sha256 见 `raw/post/run47-local-artifacts.txt`。
`_export.json` 3,648 B / sha256 `CD071B18E39DC58DFC7506D407397EC6DB75EAF8BC0D85B77482B2EEB553D207` /
`snapshotId=S20260901_47` / `totalRows=22`：

| Hive 表 | MySQL 表 | rowCount | jsonl 字节 |
|---|---|---|---|
| `dw_ads.ads_operation_overview` | `ads_operation_overview_m` | 1 | 160 |
| `dw_ads.ads_sale_trend` | `ads_sale_trend_m` | 1 | 81 |
| `dw_ads.ads_behavior_funnel` | `ads_behavior_funnel_m` | 4 | 332 |
| `dw_ads.ads_active_trend` | `ads_active_trend_m` | 1 | 30 |
| `dw_ads.ads_hot_product` | `ads_hot_product_m` | 4 | 441 |
| `dw_ads.ads_product_conversion` | `ads_product_conversion_m` | 4 | 276 |
| `dw_ads.ads_user_profile` | `ads_user_profile_m` | 3 | 720 |
| `dw_ads.ads_data_quality` | `ads_data_quality_m` | 4 | 476 |
| **合计** | | **22** | |

### 5A.4 DWD 代理键真实落数（新增取证：证明键**真落地**，不是 NULL 占位）
| 查询 | 值 |
|---|---|
| `dwd_order_detail` `dt='20260901'` 行数 | 7 |
| 其中 `user_key` 非 NULL | **7/7** |
| 其中 `product_key` 非 NULL | **7/7** |
| 其中 `category_key` 非 NULL | **7/7** |
| `dwd_user_behavior_detail` `dt='20260901'` 行数 | 14 |
| 其中 `user_key` 非 NULL | **14/14** |

证据：`raw/post/a8-t2-run47-third-check.sql` / `.txt`。

### 5A.5 `QUALITY_CHECK` 明细（如实记录，不隐藏）
`businessDayEvents=49`；四条规则：

| ruleCode | passed | errorCount | checkCount |
|---|---|---|---|
| `AMOUNT_RECONCILE` | 1 | 0 | 5 |
| `REQUIRED_FIELD_NULL_RATE` | 1 | 0 | 15 |
| **`EVENT_ID_UNIQUE`** | **0** | **1** | 49 |
| `ENUM_WHITELIST` | 1 | 0 | 15 |

`landingCorePassed=true`。`EVENT_ID_UNIQUE` 有 1 条非唯一 ⇒ **登记为观察项**（属既有 golden fixture
形态，非本次修复引入），是否走质量规则口径裁定**待总控**；本轮未处置。

---

## 6. 集群链路：未执行（显式声明）

**集群侧未提交**（按总控 21:20 令：本轮只查 DWD 入表链，集群链路本轮不跑；
随后 **D-142 前置门禁 + 21:3x 暂停令**再次明确：**门禁未过前不得提交**）。
「同一 jar ⇒ 集群必然同样失败」始终是**推断**，非实测；在假设一被证伪、真根因（位置错位）
被实验钉死之后，该推断有了机制支撑，但**仍未经集群实测**，故不作为结论。
本地链已修复且 run 47 全绿，若后续跑集群侧，那才是有意义的 E4 证据；
`$LAND` / 新导出目录 / 新 `outputSnapshotId` 不变。
`tools/m3s8-run-cluster.ps1` 已就绪（仅 3 处强制改动：`$LAND`→`…/landing/m3s8-55`、
`mxp --exportDir`→`…/export/m3s8-55`、`$SnapshotId` 默认 `S20260901M3S8`）。

### 6.1 本轮新增的两项集群前置结论（均为只读分析，**未提交作业**）

1. **⚠️ 上游隔离缺口（头号）**：脚本沿用 `--hiveDatabasePrefix=dw` ⇒ 新链将写入**与 E4 相同的**
   `dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads`，且 E4 与本次**同为 `--businessDate=20260901`**、
   各作业大量 `INSERT OVERWRITE`（ADS 层无日期分区 ⇒ 整表替换）
   ⇒ **会用 55 行数据覆盖 E4 的 1,000 行集群证据，且不可逆**。
   实测依据：库名**全部**由该参数派生（`WarehouseNamespace.scala:28-46,68,126-127`），
   建库/建表/写入语句全为 `ns.` 派生（`LocalSchemaInitJob.scala:38-42,44+`），
   且全量扫描 `spark-jobs/src/main/scala` **无任何硬编码 `dw_` 库名字面量**
   ⇒ **改前缀即整体换库**。建议 `--hiveDatabasePrefix=m3s8`（合法通过白名单校验）。
   **须总控裁定（G2）**，本泳道不自行选择。
   注：`$SnapshotId` **只**影响 `mxp --exportDir` 与指标快照 id，**不隔离上游表**——
   这正是门禁要求「除新目录外还须核实目标 Hive 表／分区／写入范围确实隔离」的原因。
2. **⚠️ HDFS 只读核实未完成**：`hdfs dfs -ls /graduation/export` 等**两次超时失败**
   （180 s / 600 s 上限，exit 1）⇒ 跑前基线（`e4c1000` 导出物、`dw_*` 表/分区行数）**尚未取得读数**。

完整清单、参数差异表、逐条只读证据计划（E-1…E-10）与门禁项 G1–G8：
见 `raw/post/cluster-gate-prep.txt`。

---

## 7. §5.5 逐值比对表

### 7.1 状态：**未执行，无值**（显式声明，逐行留空）

比对要求**两侧**各产出一份同构产物（`_export.json` + 8 个 `ads_*_m.jsonl`）后逐值对照。
本轮本地侧**已产出**（§5A.3，`metric-staging/S20260901_47/`），但**集群侧未提交**（§6）
⇒ **右侧无对象**，比对仍**无值**。按裁决 D-137 与映射表 §6「不得在看到数字后修改映射或口径」，
**不填任何值、不作任何推断**（尤其**不得**用本地值反推集群值）。
映射表本身未被修改（sha256 同 §2.1）。

### 7.2 逐行登记表（**每一行均为「未执行 ⇒ 无值」**）

| 行 | 比对对象（映射表冻结口径） | 本地（`S20260901_47`） | 集群（新 `outputSnapshotId`） | 判定 |
|---|---|---|---|---|
| **L-A1** | 指标 `gmv` | 已产出 | **未产出** | **未执行，无值** |
| **L-A2** | 指标 `net_sale` | 已产出 | **未产出** | **未执行，无值** |
| **L-A3** | 指标 `paid_order_cnt` | 已产出 | **未产出** | **未执行，无值** |
| **L-B1** | `ads_operation_overview` | 已产出（1 行） | **未产出** | **未执行，无值** |
| **L-B2** | `ads_sale_trend` | 已产出（1 行） | **未产出** | **未执行，无值** |
| **L-B3** | `ads_behavior_funnel` | 已产出（4 行） | **未产出** | **未执行，无值** |
| **L-B4** | `ads_active_trend` | 已产出（1 行） | **未产出** | **未执行，无值** |
| **L-B5** | `ads_hot_product` | 已产出（4 行） | **未产出** | **未执行，无值** |
| **C0 回读** | HDFS 导出物回读（行数/校验和/`_export.json`） | 本地产物已回读 | **未回读** | **未执行，无值** |

> 说明：L-A1/A2/A3 只列三个代表指标以示意「指标行」口径，映射表内**其余 7 个指标行同样全部未执行、无值**
> （本报告**不**在此逐值展开任何一侧数字）。L-B1…L-B5 同理为示意；8 张 ADS 表**全部**未执行。
> 集群侧**哪怕一个值**都还没有 ⇒ 本表**不得**出现任何集群侧数值，也**不得**出现「本地值 ⇒ 集群应同值」的推断。

### 7.3 执行前置条件（未满足前不得开跑）

见 `raw/post/cluster-gate-prep.txt`：新 `$LAND` / 新导出目录 / 新 `outputSnapshotId` /
**上游 Hive 库表与分区隔离核实** / 两侧输入 sha256 / 映射与指标定义版本 / 业务日期一致性；
并受裁决 **D-142 前置门禁**约束（F-88 未实现 ⇒ 平台侧代码可能还要改 ⇒ 届时 jar 指纹须重算）。

---

## 8. A8 T2 四个计数（实测，**三连一致**）

`dw_dim.dim_product`，`dt='20260901'`：

| 指标 | 第 1 次 | 第 2 次（E2 内独立复现） | **第 3 次（run 47）** |
|---|---|---|---|
| 总行数（阳性对照） | 4 | 4 | **4** |
| `product_key` 非 NULL | 4 / 4 | 4 / 4 | **4 / 4** |
| `category_key` 非 NULL | 4 / 4 | 4 / 4 | **4 / 4** |
| `brand_key` 非 NULL | 4 / 4 | 4 / 4 | **4 / 4** |
| `parent_category_key` 非 NULL | **0 / 4** ⚠️ | **0 / 4** ⚠️ | **0 / 4** ⚠️ |
| `COUNT(DISTINCT product_key)` | 4 | — | **4** |
| `COUNT(DISTINCT parent_category_key)` | — | — | **0** |

阳性对照为 4（非 0）⇒ 查询确实扫到分区。第 2 次：E2 的 `SurrogateKeySpec`「真实 warehouse + 黄金 55 行」
用例打印 `[A7] dim_product 行数 = 4 / product_key 非 NULL = 4 / category_key 非 NULL = 4 /
parent_category_key 非 NULL = 0 / brand_key 非 NULL = 4`。第 3 次证据：
`raw/post/a8-t2-run47-third-check.sql` / `.txt`。**三轮完全一致（4/4/4/0）。**

**发现（必须上报，不得当作通过）**：`parent_category_key` 全为 NULL。
读码解释：`DimSql.scala:77` 用 `SurrogateKey.toBIGINT(src,'category','p.payload_parent_category_id')`，
golden fixture 不含 `parent_category_id` ⇒ 表达式 NULL ⇒ 键 NULL。
注意 `DimSql.scala:67` 对 id 列有 `-1` 兜底，但 `*_key` **代理键列无同样兜底**。
总控已登记为待裁定，明确**不在 P2-03-m / m2 范围内**；本轮**未动**（不得给 `*_key` 加 `-1` 兜底）。

---

## 9. 未完成 / 未验证项

1. **本地 golden 快照**：✅ **已产出** = `S20260901_47`。存在性逐个写清：
   `_43` **存在**（ARCHIVED, v11）、`_44` **不存在**、`_45` **不存在**、`_46` **不存在**、`_47` **存在**（ACTIVE, v12）。
2. **`tdw` 阻断**：✅ **已解除**。根因 = INSERT/DDL 位置错位（**已实测钉死**）；
   `nullSafe`（假设一）**已证伪**但修复保留为类型卫生；m2 修复后 run 47 八阶段全绿。
3. **集群链路**：⛔ 未提交（§6）。「同一 jar ⇒ 集群同样失败」始终是**推断**，非实测。
4. **§5.5 比对表**：⛔ 未执行、无值（§7）。
5. **L-A1/A2/A3、L-B1..B5、C0 HDFS 回读**：无产物，未执行。
6. **P2-03 状态**：总控已改判 `REGRESSED`（D-141）；**本泳道不自行改判**。
   建议依据：run 47 全绿 + 代理键真实落数（§5A.4）已具备充分证据，是否撤回归档由总控裁定。
7. **`EVENT_ID_UNIQUE` 1 条非唯一**：观察项（§5A.5），待口径裁定。
8. **`parent_category_key` 全 NULL**：待裁定（§8），本轮未动。
9. 输入口径差异留档（**入账差异，非数值不等**）：集群 `odl` 读全部 55 行；本地侧经平台
   accept/quarantine 后为 51（**49×2026-09-01 + 2×2026-09-02**），4 行被隔离，身份已定位：
   - `golden-evt-038` —— 非法枚举 `purchase`
   - `not-valid-json-line-with-no-braces-{{{` —— 非 JSON 行
   - `golden-evt-054` —— `schema_version=2.0`
   - `golden-evt-055` —— 缺 `event_id`
10. **口径订正**：`LOAD_ODS records=51` 是阶段**入参回显**，业务日切片实为 **49**
    （由 run 47 的 `QUALITY_CHECK.businessDayEvents=49` 独立确认）。
11. `raw/post/` 全部数字已隔离，映射泳道未见（满足「映射泳道不得看到数字」）。
12. **叙述改正（总控 21:3x 工单 A）**：✅ 已改 **3 处**（`p2-03-m2-rootcause-evidence.txt` §2.1/§2.2/§2.3/§6.2、
    `IMPL-REPORT.md` §3.5、`p2-03-regression-evidence.txt` §6）+ `TradeDwdJob.scala` 行内注释 1 行；
    回读证据：`raw/post/narration-fix-readback.txt`。**「第 19 项」措辞已全部改为
    「前面已有 19 个普通字段，`t.dt` 是第 20 项」**；「旧版静默错位 / run 43 静默写错」断言**已删除并显式撤回**；
    假说一已标 **hypothesis-1 / FALSIFIED**。
13. **⚠️ jar 构建不可复现（新发现，未裁定）**：同一源码连续 3 次 `clean package` 得 3 个不同整包 sha256
    （jar 内每个 entry 内嵌写入时刻）⇒ 整包 sha256 不适合作稳定构建物标识。详见 §2.6 G。
14. **⚠️ run 47 认证用 jar（296,340 B / `F9882E6A…`）已不存在**：叙述改正改动了被编译源文本（1 行注释）
    ⇒ jar 已重建；`clean` 删除了旧产物且**未事先备份**（本泳道操作失误，如实登记）。
    当前 jar = 296,359 B / `71C2BCCB…`，行为等价性证据见 §2.6 F。
15. **⚠️ HDFS 只读核实未完成**：`hdfs dfs -ls /graduation/export` 等**两次超时失败**
    （180 s / 600 s 上限，exit 1）⇒ `e4c1000` / `m3s8-55` / `dw_*` 的**跑前基线未取得读数**。
16. **集群前置门禁未闭合**：G1（jar 指纹基准）/ G2（上游 Hive 隔离方案）**待总控裁定**；
    G3（HDFS 可达）**未完成**；G4（F-88 `ERROR` 是否阻断）**未实现** ⇒ **不得提交**。
    清单：`raw/post/cluster-gate-prep.txt`。
17. **上游隔离缺口（重要）**：集群脚本沿用 `--hiveDatabasePrefix=dw` ⇒ 新链会写入**与 E4 相同的
    `dw_ods/dw_dwd/dw_dim/dw_dws/dw_ads`**，且同为 `--businessDate=20260901`、大量 `INSERT OVERWRITE`
    ⇒ **会覆盖 E4 集群证据且不可逆**。建议改 `--hiveDatabasePrefix=m3s8`（库名由此参数整体派生，源码无硬编码 `dw_`）。
    **须总控裁定（G2）**，本泳道不自行选择。
18. **F-88（质量门严重度口径）**：不在 P2-03 内顺手改；落地后 jar 必然再变 ⇒ jar 指纹须重算。

---

## 10. 交付物索引

| 文件 | 内容 |
|---|---|
| `raw/post/p2-03-m2-rootcause-evidence.txt` | **主专章**：真根因溯源 + 23 行位置对照表 + 双实验 + run 47 全量结果 |
| `raw/post/narration-fix-readback.txt` | **本轮**：三处叙述改正**回读** + jar 重建的行为等价性证据 + 构建不可复现实测 |
| `raw/post/cluster-gate-prep.txt` | **本轮**：集群执行**前置检查清单**（参数差异 / 上游隔离核实证据计划 E-1…E-10 / 门禁 G1–G8） |
| `raw/post/p2-03-regression-evidence.txt` | 前序专章：时间线 + 代码/DDL 排除证据 + 两 run 对照 |
| `raw/post/align-experiment-1-select19-string.sql` / `-result.txt` | 实验 1：复刻错位形态（`t.dt` 为第 20 项）⇒ **逐字复现**报错 |
| `raw/post/align-experiment-2-dt-last.sql` / `-result.txt` | 实验 2：`t.dt` 移末位 ⇒ 写入成功、逐列落位正确 |
| `raw/post/align-experiment-cleanup.sql` / `-result.txt` | 实验表/库彻底删除并二次确认 |
| `raw/post/ccc1dff-tdw-schema-diff.txt` | `ccc1dff` 对 `TradeDwdJob`/`LocalSchemaInitJob`/DDL 的改动 |
| `raw/post/describe-dwd-order-detail.txt` | 目标表只读 DESCRIBE（运行时口径，非陈旧 DDL 文件） |
| `raw/post/p2-03-m2-falsification-probe.log` | 守卫**证伪测试**日志（放回错位 ⇒ 断言精确失败） |
| `raw/post/p2-03-m2-build-e2.log` | m2 的 E1 编译 + E2 111/111 通过 |
| `raw/post/p2-03-m2-new-jar-fingerprint.txt` | m2 jar 字节数/mtime/sha256/class major |
| `raw/post/run47-submit.txt` | run 47 提交请求与响应、`WAIT_LANDING` |
| `raw/post/run47-final.json` | run 47 终态 8 阶段 + 各作业 evidence |
| `raw/post/run47-snapshot-metrics.txt` | `S20260901_47` 快照行 + 10 指标 + 8 张 ADS 表行数 |
| `raw/post/run47-local-artifacts.txt` | `metric-staging/S20260901_47/` 逐文件 sha256 |
| `raw/post/a8-t2-run47-third-check.sql` / `.txt` | A8 T2 第三轮 + DWD 代理键落数 |
| `raw/post/p2-03-m-fix.diff` | m 修复完整 diff（`SurrogateKey` + 夹具） |
| `raw/post/p2-03-m-new-jar-fingerprint.txt` | m 版 jar 指纹（二代） |
| `raw/post/run46-submit.txt` / `run46-final.json` | run 46 提交与终态（假设一证伪的关键对照） |
| `raw/park-move-back.txt` | 61 文件复原清单与 sha256 |
| `raw/post/post-restore-ingestion-status.txt` | 还原后摄取状态（与基线逐字段一致） |
