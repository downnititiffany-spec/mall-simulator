# P2-04 只读取证读数（RAW READINGS）

- 任务行（看板 V2.2 L223，原文逐字）：
  `P2-04 | DWD 从 SourceProfile 投影 | TODO | B | P2-02/03 | 缺字段显式 NULL+DQ；禁止通用 CAST BIGINT。现状 DwdSql.scala:31 仍 PARTITION BY event_id。`
- 取证时点：**2026-09-12 20:15:11**（脚本内 `Get-Date` 实测值，见 `raw/RUN-20260912-2015.txt` 首行）
- 仓库：`D:\Develop_code\GraduationProject`
- 分支：`remediation/r1-boundary`；HEAD：`052c6bc32f8369172f7c09eb4f25ce885d10903e`
- 证据级别：**E0（只读取证；未编译、未跑 Spark、未起服务、未写任何库表）**
- 可复算脚本：`raw/collect-evidence-p2-04.ps1`（纯只读）
- 原始输出：`raw/RUN-20260912-2015.txt`（27,493 B，sha256 `9514882B0D23920FD9F575888A4CB65578BCCC21647CC962F3F384F93375E6D7`，368 行）

> **并发写入声明（D-091 Q13/Q17 要求）**：本取证窗口内 `spark-jobs/**` 正被 P2-03 泳道并发写入。
> 下表中凡引用 `spark-jobs/**` 的行号均为**取证时点读数**，并同时给出该文件的 worktree blob sha 与 mtime。
> **实施前必须按 D-091 Q17 重取行号与指纹，不得直接把本报告行号当现状。**

---

## 一、前提核对（任务表所述 vs 实测）

| 编号 | 任务表/看板所述 | 实测结论 | 判定 |
|---|---|---|---|
| P-1 | `DwdSql.scala:31` 仍 `PARTITION BY event_id` | worktree 已漂移到 **L46**；**HEAD 版本恰在 L31** | **前提实质成立，行号漂移**（详见 R-04/R-05） |
| P-2 | 「禁止通用 `CAST BIGINT`」 | `IdCodec` 是唯一所有者且明确禁止手写 `CAST(... AS BIGINT)`；`spark-jobs/src/main` 现无违规裸调用 | **前提成立**（详见 R-11/R-12） |
| P-3 | 「DWD 从 SourceProfile 投影」 | `spark-jobs/**` 全域 **零命中** `SourceProfile`／`sourceProfile`／`source-profile`；真实 SourceProfile 属 P3-01 交付面 | **前提部分不成立**：投影源不存在（详见 R-13 与待裁决 D-1） |
| P-4 | 「缺字段显式 NULL+DQ」 | 未实测（本轮未跑作业、未构造缺字段样本） | **未实测** |

### P-1 行号漂移的确切读数（R-04 / R-05）

worktree（取证时点）：
```
L46: |         ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time) AS rn
L53: |WHERE rn.rn = 1                       -- event_id 去重
```
HEAD（D-091 Q17 规定的工作基线）：
```
L31: |         ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time) AS rn
L38: |WHERE rn.rn = 1                       -- event_id 去重
HEAD blob = 2a1582ac9ac59482b3eed8a1ef0fee588cac90e1
```

结论：**任务表与看板的 L31/L38 是对 HEAD 的准确描述**；漂移由 P2-03 泳道在 worktree 追加 `user_key`/`product_key`/`category_key` 造成。
**实质未被推翻**：去重键仍是单一 `event_id`，`source_system` 未进入去重键，跨源仍会互相去重。

---

## 二、关键读数逐项（命令 + 输出）

### R-03 `spark-jobs/**` 并发写入指纹（blob sha + mtime + 行数）

```
spark-jobs/.../sql/DwdSql.scala          blob=dc4d36a295326c1267367272417e0a26e808ee2a  mtime=2026-09-12 19:59:52
spark-jobs/.../sql/DimSql.scala          blob=03a54ac019020abfd8b13304b731408276e25b5c  mtime=(见 RUN 文件)
spark-jobs/.../job/EventOdsLoadJob.scala blob=fb43b45883dbc311a94fd8de7613bbe0bd802ae7
spark-jobs/.../sql/OdsV2Columns.scala    blob=2af7323276862bf83dee2d5fbfa825bf1d83b4c4
spark-jobs/.../sql/OdsLoadSql.scala      blob=5097cb32294aed04e61a94079e93aee9c9898065
spark-jobs/.../sql/IdCodec.scala         blob=6aad5bd8013d3a315b1c150ba70bd1e4733e7efc
spark-jobs/.../warehouse/WarehouseNamespace.scala  blob=562430cbeee1313cb57e6619458ab693636aa2d0
```
完整数值以 `raw/RUN-20260912-2015.txt` 的 R-03 段为准（本表为摘要，逐字读数不重抄以免二次转录误差）。

### R-07 `source_instance_id` 在数据面 **零命中**（含阳性对照）

命令：`git grep -n -I "source_instance_id" -- spark-jobs warehouse`
输出：`(零命中 —— 见紧随其后的阳性对照)`

**阳性对照**（同一命令形态，证明工具不假零）：
`git grep -c -I "source_system" -- spark-jobs` →
```
spark-jobs/.../job/EventOdsLoadJob.scala:4
spark-jobs/.../job/LocalJsonParquetJob.scala:1
spark-jobs/.../job/TradeDwdJob.scala:1
spark-jobs/.../sql/DimSql.scala:6
spark-jobs/.../sql/DwdSql.scala:2
spark-jobs/.../sql/OdsLoadSql.scala:7
spark-jobs/.../sql/OdsV2Columns.scala:5
```

> **本条是 P2-04 的核心矛盾证据**：契约与指导书要求按 `(source_instance_id, event_id)` 去重，但
> `source_instance_id` 这个标识在 `spark-jobs/**` 与 `warehouse/**` 里**根本不存在**；
> 数据面实际承载源身份的列名是 `source_system`（值 = `source_registry.source_code`）。
> 名称映射是否等价、由谁裁定，见待裁决 **D-2**。

### R-13 `SourceProfile` 在 `spark-jobs/**` **零命中**（含阳性对照）

命令：`git grep -n -I -E "SourceProfile|source-profile|sourceProfile" -- spark-jobs`
输出：`(零命中 —— 见阳性对照)`

**阳性对照**：`git grep -c -I "sourceSystem" -- spark-jobs` →
```
spark-jobs/.../job/EventOdsLoadJob.scala:9
spark-jobs/.../job/TradeDwdJob.scala:6
spark-jobs/.../sql/OdsLoadSql.scala:18
spark-jobs/src/test/.../IdCodecSpec.scala:2
```

### R-12 `CAST(... AS BIGINT)` 现状（spark-jobs/src/main）

命令：`git grep -n -I -E "CAST\([^)]*AS BIGINT" -- spark-jobs/src/main`

命中**全部位于注释**，无违规裸调用：
```
TradeDwdJob.scala:114:   规则只在 `IdCodec` 定义（DEF-05 / 决策 B-07 候选 ② / D-023），此处不得再手写 `CAST(... AS BIGINT)`；
IdCodec.scala:8:  * 而数仓 `dwd/dws/ads/dim` 的 id 列是 `BIGINT`；直接 `CAST(... AS BIGINT)` 会**静默返回 NULL**
IdCodec.scala:17:  * 纪律：该规则**只在此处定义**。任何地方都不得再手写 `CAST(payload_*_id AS BIGINT)`
IdCodec.scala:33:  * `REGEXP_EXTRACT` 不匹配时返回空串，`CAST('' AS BIGINT)` = `NULL`——即"形状不合规 → NULL"。
```
> 注意：`DECIMAL` 侧的 `CAST($expr AS DECIMAL(18,2))`（`OdsLoadSql.scala:156-157`）属金额转换，不在本禁令范围；但**其空值语义是显式 `CASE WHEN ... THEN NULL`**，正是 P2-04 要求的「缺字段显式 NULL」的既有范式。

### R-14 契约侧去重语义 —— **CT-2（D-062）已落盘，语义已改为复合键**

`docs/contracts/event-contract.md`（blob `73e5e913f390631ceb7ef03ecd89ab7ca16fd625`）：
```
L12:  "event_id": "UUID",   // 源命名空间内唯一，ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）
L165: * `event_id` 在源命名空间内唯一；重复投递由 DWD 按 `(source_instance_id, event_id)` 去重，因此允许 at-least-once。
```
`contract-specs/schemas/canonical-event.v1.schema.json`（blob `1e4f2d8565c0901067ceafafdc6116feb96b3b48`）：
```
L10: "description": "源命名空间内唯一的事件 ID，ODS/DWD 按 (source_instance_id, event_id) 去重（允许 at-least-once 投递）。…"
```
`contract-specs/VERSION` = `2.2.0`（D-092 版本规则的实际落点）。

> **这条改变了 P2-04 的裁决负担**：P2-01 当时的 D-055 写的是「P2-01 不改 DWD 去重键（仍单键 `event_id`）」并登记 CT-2 契约任务；
> 现在 **CT-2 已落盘**，契约口径已从单键变为复合键。
> 也就是说：**去重键该不该改，已由契约回答（该改）；剩下的是「用哪个列承载 source_instance_id」以及「改动落在哪一行」——这才是 P2-04 的待裁决面。**

### R-15 指导书 V2.4 权威条文

`docs/项目完整实施指导书 V2.4.md`（blob `732abb9bf7c621828ac6f7d859b9226f4b7b1f46`，720 行）：
```
L314: 外部商城不能自行决定平台主键。平台创建 `source_instance_id`，它从 Landing manifest 传播到 ODS、DWD、DWS、ADS、指标快照和页面筛选。跨商城去重键为 `(source_instance_id, event_id)`，不能只按 `event_id` 去重。
L426: 8. Flume 是 at-least-once；平台只能通过 checkpoint、manifest 和 `(source_instance_id,event_id)` 去重提供幂等结果，不得宣称 exactly-once。
L452: - 去重排序：同一业务键按 event_time、ingest_time、event_id 确定唯一结果，保证重跑稳定。
```

> **注意 L314 与现状的两处差距（均为待裁决，不由本泳道裁定）**：
> ① L314 要求 `source_instance_id` 从 Landing manifest **一路传播到 ODS、DWD、DWS、ADS、指标快照和页面筛选**；
> 实测 ODS 承载源身份的列是 `source_system`，且 `metric_snapshot` 表**没有** `source_instance_id` 列（其 `source` 列实测值是 `spark-ads`，发布方，非源身份）。
> ② L452 要求的排序三要素是 `event_time, ingest_time, event_id`，而现状 `PARTITION BY event_id ORDER BY ingest_time` 只用了两要素。

### R-08 DWD 目标表 DDL 列序（`warehouse/ddl/01-dwd.sql`）

- blob `652b7c63c20d061734c7c549d6419922ad199c30`，97 行（**已被 P2-03 并发修改**）
- `dwd_user_behavior_detail`（L16-36）：P2-03 已加 `user_key`/`product_key`/`category_key`（L29-31）
- `dwd_reject_record`（L87-97）：`reject_reason` 枚举 `EMPTY_FIELD/DUPLICATE_EVENT/BAD_ENUM/BAD_AMOUNT/FUTURE_TIME`
- 全文逐字读数见 `raw/RUN-20260912-2015.txt` 的 R-08 段

### R-09 / R-10 ODS v2 实际列集（「冻结 4 列 vs 实现 5 列」）

`OdsV2Columns.scala`（blob `2af7323276862bf83dee2d5fbfa825bf1d83b4c4`）是 ODS 列集的**类级唯一所有者**：
`V2NewColumns` 实为 **5 列** —— `raw_event_type`、`raw_source_system`、`landing_file`、`payload_json`、`payload_hash`。
而 P2-01 的 **D-054 冻结的是「新增 4 列」**（不含 `raw_source_system`）；该多出的一列在 P2-01 的 `RULINGS.md` 内**零命中**，仅由看板 L460 ⑥ 事后追认。
`OdsLoadSql.scala:97` 的注释字面写作「v2 新增段（5 列）：追加在 v1 列之后（D-059）」——**实现自述为 5 列，与 D-054 的 4 列不一致**。

---

## 三、P2-04 面前的结构性事实

1. **DWD 投影的数据源当前是 ODS 表列**，不是 SourceProfile。`DwdSql.scala` 直接读 `${ns.ods}.ods_*` 并用 `IdCodec.toBIGINT(...)` 转换 `payload_*_id`。
2. **「SourceProfile 投影」这一概念在数据面没有载体**：真实源画像文件（`source-profiles/mock-mall.v1.json`）不存在；
   `analytics-server/source-profiles/` 下只有 `README.md` 与两个 `p1-03-probe-*.v1.json` 探针；
   真实画像驱动属 **P3-01**，源→规范归一属 **P3-02 `EventNormalizer`**（V2.4 L346-L350）。
3. **跨源去重的出口判据当前不成立**：P-1 与 R-14 合并结论 —— 契约已要求复合键，实现仍是单键。
4. **`payload` 双所有者尚在**：D-054 明确「DWD 改用 `get_json_object(payload_json, …)` **排期到 P2-04**」，
   并规定「两个 payload 所有者并存的过渡态必须在 P2-04 关闭」。实测 `DwdSql.scala` 仍读 `payload_*` 列（v1 口径），未切到 `payload_json`。

---

## 四、未实测清单（不得当作已验证）

| 项 | 状态 | 原因 |
|---|---|---|
| E1 编译 | 未实测 | 本泳道禁止 Maven |
| E2 模块自动化 | 未实测 | 本泳道禁止 Maven |
| E3 本地真实链 | 未实测 | 本泳道禁止 `spark-submit` |
| E4 集群 1,000 行 | 未实测 | 本泳道禁止集群操作 |
| E5 页面 | 未实测 | 本泳道禁止起服务 |
| 改复合键后 DWD 行数是否变化 | 未实测 | 需真实链 |
| 缺字段样本的 NULL+DQ 行为 | 未实测 | 需构造样本并跑库 |
| `payload_json` 与 `payload_*` 取值等价性 | 未实测 | 需真实链比对 |
| 跨源同名 `event_id` 的实际冲突规模 | 未实测 | 当前库只有 1 个源，构造不出跨源样本 |

---

## 五、只读取证命令清单（总控一键复算）

```pwsh
cd D:\Develop_code\GraduationProject
pwsh -NoProfile -File docs/acceptance/p2-04-dwd-source-projection-20260912/raw/collect-evidence-p2-04.ps1
```

单点复算（每条独立可跑）：

| 结论 | 单条命令 |
|---|---|
| 去重键仍是单 `event_id` | `git show HEAD:spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala \| Select-String 'PARTITION BY'` |
| `source_instance_id` 数据面零命中 | `git grep -n -I "source_instance_id" -- spark-jobs warehouse`（零命中时须跑 `git grep -c -I "source_system" -- spark-jobs` 作阳性对照） |
| `SourceProfile` 零命中 | `git grep -n -I "SourceProfile" -- spark-jobs`（阳性对照 `git grep -c -I "sourceSystem" -- spark-jobs`） |
| 契约已改复合键 | `Select-String -LiteralPath docs/contracts/event-contract.md -Pattern '去重'` |
| 指导书 L314 原文 | `(Get-Content 'docs/项目完整实施指导书 V2.4.md' -Encoding UTF8)[313]` |
| ODS v2 实为 5 列 | `Select-String -LiteralPath spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala -Pattern 'V2NewColumns' -Context 0,8` |
| 无违规裸 CAST | `git grep -n -I -E "CAST\([^)]*AS BIGINT" -- spark-jobs/src/main` |
