# P2-04 规格草案 —— DWD 从 SourceProfile 投影

- 制品类型：**规格草案（spec draft）**，非冻结契约、非验收报告
- 对应看板行：`docs/项目实施进度与任务看板 V2.2.md` **L223**
- 起草时点：**2026-09-12 20:16**（`Get-Date` 实测）
- 取证依据：同目录 `RAW-READINGS.md` + `raw/RUN-20260912-2015.txt`
- 状态：**草案待总控裁定**。本文件**不自行裁定**任何越界事项；凡未获授权处一律列入 §5 待裁决清单。
- 声明边界：本泳道**未实施任何代码**。凡涉实现的段落均为**要求**，不是**现状**。

---

## 1. 任务行的原始要求拆解

看板 L223 原文：

> `P2-04 | DWD 从 SourceProfile 投影 | TODO | B | P2-02/03 | 缺字段显式 NULL+DQ；禁止通用 CAST BIGINT。现状 DwdSql.scala:31 仍 PARTITION BY event_id。`

拆为四条可判定的要求：

| 编号 | 要求（来自任务行逐字） | 可判定形式 |
|---|---|---|
| Rq-1 | 「DWD 从 SourceProfile 投影」 | DWD 的字段来源可追溯到源画像声明，而非硬编码 |
| Rq-2 | 「缺字段显式 NULL+DQ」 | 画像未声明／映射缺失的字段写出显式 NULL，并产生 DQ 记录 |
| Rq-3 | 「禁止通用 CAST BIGINT」 | 不得出现无形状约束的 `CAST(... AS BIGINT)` |
| Rq-4 | 「现状 `DwdSql.scala:31` 仍 `PARTITION BY event_id`」 | 去重键从单键 `event_id` 改为含源身份的复合键 |

**Rq-4 的负荷已由契约变更转移**：`docs/contracts/event-contract.md` L12/L165 与 `canonical-event.v1.schema.json` L10 现已写死
「ODS/DWD 按 `(source_instance_id, event_id)` 去重」（CT-2 = D-062 已落盘，`contract-specs/VERSION` = `2.2.0`）。
因此「要不要改」不再是待裁决项；**待裁决的是承载列名与改动归属**（见 §5 D-2）。

---

## 2. 权威依据（逐条给行号 + 原文）

### 2.1 唯一权威：`docs/项目完整实施指导书 V2.4.md`

blob `732abb9bf7c621828ac6f7d859b9226f4b7b1f46`，720 行。

**依据 A（跨源去重键）— L314，逐字：**
> 外部商城不能自行决定平台主键。平台创建 `source_instance_id`，它从 Landing manifest 传播到 ODS、DWD、DWS、ADS、指标快照和页面筛选。跨商城去重键为 `(source_instance_id, event_id)`，不能只按 `event_id` 去重。

**依据 B（幂等口径）— L426，逐字：**
> 8. Flume 是 at-least-once；平台只能通过 checkpoint、manifest 和 `(source_instance_id,event_id)` 去重提供幂等结果，不得宣称 exactly-once。

**依据 C（重跑稳定性排序三要素）— L452，逐字：**
> - 去重排序：同一业务键按 event_time、ingest_time、event_id 确定唯一结果，保证重跑稳定。

**依据 D（源→规范归一的归属）— L346 / L348 / L350，摘要（原文见 RAW-READINGS）：**
未知源版本、没有 ACTIVE mapping、必填字段缺失和不允许的转换全部进入 quarantine，并保存 source instance、mapping version、reason code 和原始位置；
映射的最终真相是版本化配置和审核记录，不是大模型对话；AI 草案不能直接变为 ACTIVE mapping。

**依据 E（权威顺序）— L37-45：** ① 冻结契约 → ② 指导书 → ③ 看板 → ④ 专项设计 → ⑤ 历史文档；「若实现与契约冲突，先提交契约变更决策，再修改实现」。

### 2.2 历史参考：`docs/项目完整实施指导书 V2.2.md`

> **按 D-091 属历史参考**（D-091 原文：权威入口已是指导书 V2.4，V2.2 及更早降为历史参考）。

blob `e606d10bd2e547776f0d60f94b62d138c1bb5ea0`，574 行。
**§6.1 L227，逐字：**
> node01–03 是一个可选运行环境，不是代码常量。不能再仅凭 `LOCAL / SINGLE_NODE / REMOTE_CLUSTER` 推断所有行为，因为“平台部署在哪里”“Landing 存在哪里”“Spark 由谁提交”“Spark 使用哪种集群管理器”是四个相互独立的维度。

引用理由（仅作 §6 级细节）：本条确立「源身份/运行环境是**数据与配置**，不是代码常量」的原则，正是 P2-04「从 SourceProfile 投影」而非硬编码的同一思路。**不作为裁决依据**。

### 2.3 已冻结契约（最高优先级）

| 制品 | 与本行的关系 |
|---|---|
| `docs/contracts/event-contract.md` L12 / L165 | 去重键 = `(source_instance_id, event_id)`（CT-2 落盘） |
| `contract-specs/schemas/canonical-event.v1.schema.json` L10 / L38-L41 | `event_id` 唯一性范围；`source_system` 只受形状约束、值域归注册表 |
| `contract-specs/specs/surrogate-key.v1.json` | `DRAFT-2026-09-12`，P2-03 交付面 |
| `contract-specs/specs/warehouse-namespace.v2.json` | `FROZEN-2026-09-12` |

### 2.4 上游裁决（P2-01 `RULINGS.md`，D-052…D-060）中与本行直接相关的三条

- **D-054**：`payload_json` 为载荷唯一所有者；`payload_hash` 仅诊断、**不得作去重键**；**DWD 切换到 `get_json_object(payload_json, …)` 排期到 P2-04**；并规定「两个 payload 所有者并存的过渡态必须在 P2-04 关闭」。
- **D-055**：登记 CT-2 契约任务；**混源守卫（硬条件）——若一张 ODS 表一旦出现多个源，必须切换为 `(source_system, event_id)` 复合键**。
- **D-059**：v2 新列追加在末尾；v1 十四列的**名称与物理序号冻结**。
- **D-060**：端到端 Hive 断言只认**真实 `spark-submit` 进程**；禁止改 `spark-jobs/pom.xml`。

> **注意 D-055 与 V2.4 L314 的措辞差异**：D-055 用的是 `(source_system, event_id)`，V2.4 与已落盘契约用的是 `(source_instance_id, event_id)`。
> 二者是否同一列的两个名字，**本泳道不裁定**，列 §5 D-2。

---

## 3. 现状实测摘要（详见 `RAW-READINGS.md`）

| 事实 | 读数 | 证据 |
|---|---|---|
| 去重键仍是单 `event_id` | worktree L46 / **HEAD L31** `ROW_NUMBER() OVER (PARTITION BY event_id ORDER BY ingest_time)` | R-04 / R-05 |
| `source_instance_id` 在数据面不存在 | `spark-jobs` + `warehouse` **零命中**（阳性对照 `source_system` 命中 8 文件） | R-07 |
| `SourceProfile` 在 `spark-jobs` 不存在 | **零命中**（阳性对照 `sourceSystem` 命中 4 文件） | R-13 |
| 无违规裸 CAST | `spark-jobs/src/main` 命中**全在注释** | R-12 |
| ODS v2 实为 **5** 列 | `raw_event_type`/`raw_source_system`/`landing_file`/`payload_json`/`payload_hash`；D-054 只冻结 4 列 | R-09 / R-10 |
| DWD 仍读 v1 `payload_*` 列 | 未切换到 `payload_json` | R-06 / R-08 |
| 排序只用了两要素 | `ORDER BY ingest_time`，V2.4 L452 要求三要素 | R-04 |

---

## 4. 规格草案（要求，非实现）

> 编号 `P2-04-S*` 是本草案自定编号，**不是既有决策号**；总控裁定后应替换为其正式编号。

### P2-04-S1 字段投影的唯一来源

DWD 的每个字段必须能回答「它来自源画像/映射的哪一条声明」。
- 投影定义必须来自**版本化配置**（源画像或字段映射），不得散落在 SQL 字面量里各自硬编码。
- 若某字段在当前映射中**无声明**，该字段写出 **显式 `NULL`**（而非省略、而非 `CAST('' AS …)` 静默 `NULL`），并产生一条 **DQ 记录**，字段级定位。
- **协调约束**：源→规范归一的**语义归属**按 V2.4 L346-L350 属 `EventNormalizer`（P3-02）。因此本行若只做「DWD 侧读取已归一结果」，
>   必须与 P3-01/P3-02 的接口边界一次性对齐；**不得**在 DWD 侧再造第二套归一逻辑（反熵：避免第二个所有者）。

### P2-04-S2 去重键改为复合键

- 去重键按已落盘契约取 **`(源身份列, event_id)`**（源身份列名见 §5 D-2）。
- 排序按 V2.4 L452 取 **`event_time, ingest_time, event_id`** 三要素，保证重跑稳定。
- **混源守卫**（D-055 硬条件）：实现必须能检测「一张 ODS 表内出现多个源身份」，命中即**失败并报错**，不得静默按新键继续。
- **向后兼容**：单源场景下新键与旧键结果应**逐行等价**；这必须作为负向验收的对照项（若不等价 ⇒ 说明旧键下确有跨源误去重被掩盖）。

### P2-04-S3 禁用通用 CAST

- 继续由 `IdCodec` 保持 id 形状转换的**唯一所有者**；`spark-jobs/src/main` 不得新增裸 `CAST(... AS BIGINT)`。
- 「缺字段」语义与「形状不合规」语义必须可分：前者是**缺失**，后者是**脏数据**。现状 `IdCodec` 把两者都折叠成 `NULL`（`REGEXP_EXTRACT` 不匹配 → 空串 → `CAST('' AS BIGINT)` → `NULL`），
  这与 Rq-2「缺字段显式 NULL + DQ」要求**必须能被区分**。如何在 `IdCodec` 单一所有者前提下区分二者，是本行的实质设计点；**否决在调用点手写 CAST**。

### P2-04-S4 payload 单一所有者收敛（来自 D-054）

- DWD 改从 `payload_json` 取值，关闭「`payload_*` 列与 `payload_json` 并存」的过渡态。
- `payload_hash` **不得**用作任何去重/连接键（D-054 明文）。

### P2-04-S5 ODS v2 列数口径对齐（遗留问题）

- 实现为 5 列、D-054 冻结 4 列。本行落地前需先把该差异**书面追平到裁决层**（现仅由看板 L460 ⑥ 追认）。
- 未追平前，DWD 投影**不得**依赖 `raw_source_system` 作为唯一承载（见 §5 D-2）。

---

## 5. 待裁决清单（**本泳道不自行裁定**）

### D-1（建议号，需总控裁定）投影对象不存在，本行是「实现」还是「定义接口」？

**事实**：真实 `SourceProfile`（`analytics-server/source-profiles/mock-mall.v1.json`）**不存在**；目录下只有 `README.md` 与 `p1-03-probe-1.v1.json`、`p1-03-probe-2.v1.json` 两个探针。
`spark-jobs/**` 全域对 `SourceProfile` **零命中**。真实画像驱动属 **P3-01**，归一属 **P3-02**。

**冲突点**：任务表把 P2-04 的依赖写作 `P2-02/03`，**不含 P3-01**。因此本行在字面上被要求在画像不存在时「从 SourceProfile 投影」。

**可选处置（请总控择一，并回填 D 号）**：
1. P2-04 本轮只**定义投影接口**（消费侧契约 + 读取口径），真实画像接入留到 P3-01；
2. P2-04 依赖调整为含 P3-01，本行**顺延**；
3. P2-04 以「当前可得的映射载体」（如 `source_registry` + 契约字段映射）作为投影源，画像文件后置替换。

> 本泳道**不建议**在无画像的情况下造一个 mock 画像充当「SourceProfile 投影」——那属于把 mock 当真实（纪律明令禁止）。

### D-2（建议号，需总控裁定）源身份列到底叫什么？

**事实**：V2.4 L314 / L426 与**已落盘契约**均写 `source_instance_id`；D-055 写 `(source_system, event_id)`；
数据面（`spark-jobs/**` + `warehouse/**`）**零命中** `source_instance_id`，实际列名是 `source_system`；
真库 `analytics_meta` / `analytics_metric` 的 `information_schema` 里**也没有** `source_instance_id` 列
（源身份相关列实测为 `source_registry.source_code`、`source_registry.id`、`runtime_profile.source_id`、`metric_snapshot.source`）。

**须裁定**：
1. `source_instance_id` 与 `source_system` 是**同一概念的别名**（则契约需登记别名，实现零改列），还是**两个不同粒度**（平台实例 vs 源编码，则需新增列并回填）？
2. V2.4 L314 要求 `source_instance_id` 传播到**指标快照**；`metric_snapshot` 实测**无**该列，其 `source` 列实测值为 `spark-ads`（发布方）。该缺口归属哪一行？
3. 若采用 `source_system`：它是否满足 L314 的「不能只按 `event_id` 去重」目的？（**单源单命名空间下二者等效，跨源不等效**——这正是 P2-06 出口判据所测。）

### D-3（建议号，需总控裁定）与 P2-03 代理键列的协调

**事实**：P2-03 已在 `dwd_user_behavior_detail` 加 `user_key`/`product_key`/`category_key`（`warehouse/ddl/01-dwd.sql` L29-31）；
P2-03 的 D-090 Q14 明文「`event_id`/`behavior_id` **不触碰**，唯一所有者仍是 `DwdSql.scala:31`（D-055）」。

**须裁定**：P2-04 改动去重键所在行，是否与 D-090 Q14「不触碰」冲突？
若冲突，是 P2-04 顺延至 P2-03 落盘后（P2-03 RULINGS 已规定两泳道**不得并发写 `spark-jobs/**`**），还是 D-090 Q14 的范围仅限「不改名」而不限制「改键」？

### D-4（建议号，需总控裁定）`IdCodec` 的「缺失」与「脏数据」如何区分？

**事实**：`IdCodec.toBIGINT` 把「字段缺失」与「形状不合规」都折叠为 `NULL`。
**须裁定**：区分责任落在 `IdCodec`（唯一所有者扩容）还是 DQ 规则侧（DWD 前置校验）？若需改 `IdCodec`，由哪一行承载？（D-090 已判 `IdCodec` 本轮**不删**、仍在主路径上承载行为。）

### D-5（建议号，需总控裁定）ODS v2「4 列 vs 5 列」是否先书面追平？

D-054 冻结 4 列、实现 5 列（多 `raw_source_system`），目前只有看板 L460 ⑥ 追认。
是否需在 P2-04 开工前补一条正式裁决，还是允许在看板追认下继续？

### D-6（建议号，需总控裁定）P2-06 出口判据的重测时点

看板已实测「出口判据当前不成立：`DwdSql.scala:31/38` 仍仅按 `event_id` 去重 ⇒ 跨源会互相去重」。
本行落地后由谁、在哪个证据级别重测 P2-06？（E3 真实链 or T2 回归？）

---

## 6. 本行证据级别与不可能项

| 级别 | 本行是否可能 | 说明 |
|---|---|---|
| E0 只读取证 | **已交付** | 本目录 |
| E1 编译 | 可能（实施轮） | 本泳道禁止 Maven |
| E2 模块自动化 | 可能（实施轮） | 本泳道禁止 Maven |
| E3 本地真实链 | 可能（实施轮） | 按 D-060 必须真实 `spark-submit` 进程 |
| **E4 集群 1,000 行** | **本行不可能** | 跨源去重需**至少两个源**；真库实测只有 1 个源（`source_registry` 仅 `mock-mall` 一行）。跨源样本无法在本行构造 |
| E5 页面 | 不适用 | 本行无页面交付面 |

> **E4 的不可能项是本行最重要的诚实边界**：P2-06 的出口判据「跨源 `eventId` 不互相去重」在**单源库上不可证伪**。
> 即便改完复合键，单源下新旧键结果等价 ⇒ 测不出差别。**必须显式登记这一验收盲区**，请总控决定是否需先造第二个源。

---

## 7. 未实测声明

本草案中所有「要求」均未实施；所有「现状」均来自只读取证（E0）。
**未实测**：E1/E2/E3/E4/E5 全部；改复合键后 DWD 行数变化；缺字段样本的 NULL+DQ 行为；`payload_json` 与 `payload_*` 取值等价性；跨源同名 `event_id` 的实际冲突规模。
