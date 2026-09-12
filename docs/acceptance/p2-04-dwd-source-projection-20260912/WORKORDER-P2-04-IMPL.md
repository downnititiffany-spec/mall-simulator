# 施工单草案 —— P2-04（DWD 从 SourceProfile 投影）

- 制品类型：**施工单草案（work order draft）**
- 对应看板行：`docs/项目实施进度与任务看板 V2.2.md` **L223**
- 起草时点：**2026-09-12 20:16**（`Get-Date` 实测）
- 依据：同目录 `draft/p2-04-spec-draft.md`、`RAW-READINGS.md`、`raw/RUN-20260912-2015.txt`
- **本单是「要求清单」，不含实现。** 本泳道未写任何代码。
- **开工前置**：§5 待裁决清单中 D-1、D-2、D-3 必须先获总控裁定；未裁定前**不得开工**。

---

## 1. 施工范围（只碰这些）

| 允许改 | 说明 |
|---|---|
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala` | 去重键、排序键、字段投影 |
| `spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala` | **仅在 D-4 裁定为「需扩容」时**；否则不动 |
| `spark-jobs/src/test/scala/**` 中 DWD 相关用例 | 新增/调整断言 |
| `warehouse/ddl/01-dwd.sql` | **仅在 D-2 裁定为「需新增列」时** |

**明确禁止改**（命中即停，见 §6）：
`contract-specs/**`（已冻结本体）、`analytics-server/**`（除总控另行裁定）、`docs/项目实施进度与任务看板 V2.2.md`、`docs/项目完整实施指导书 V2.4.md`、
`spark-jobs/pom.xml`（D-060 明文禁止）、`docs/contracts/event-contract.md`（CT-2 已落盘，属契约本体）。

**模块单写者规则**（P2-03 RULINGS D-091）：本行实施期间 **P2-03 泳道不得并发写 `spark-jobs/**`**；开工前须确认 P2-03 已落盘。

---

## 2. 施工项 A1…A5

> 等级标注：`E1` 编译 / `E2` 模块自动化 / `E3` 本地真实链（真实 `spark-submit` 进程，D-060）/ `E4` 集群 1,000 行 / `E5` 页面。

### A1 源身份列名对齐（**阻塞项**，依赖 D-2）

**要求**：按总控裁定确认 DWD 去重键中的源身份列名；若为新增列，须同步 `warehouse/ddl/01-dwd.sql` 与 ODS 投影位（`INSERT OVERWRITE` **按位置对齐**，新列必须排在 SELECT 列表末尾，见 `DimSql.scala:15` 的既有纪律）。
**验收判据**：`DwdSql.scala` 的去重键中出现源身份列；`warehouse/ddl/01-dwd.sql` 中该列存在且物理序号在两处一致。
**证据级别**：E1 + E2（结构断言）。**E3 需要在 D-2 裁定后才能判定是否可行。**
**本行不可能**：E4（跨源样本构造不出，见规格草案 §6）。

### A2 去重键改为复合键 + 排序三要素

**要求**：
1. `ROW_NUMBER() OVER (PARTITION BY <源身份列>, event_id ORDER BY ...)` —— 去重键加入源身份；
2. 排序按 V2.4 L452 取 `event_time, ingest_time, event_id` 三要素；
3. 保留 `WHERE rn.rn = 1` 的唯一性截断语义。

**验收判据**：
- E1：编译通过；
- E2：SQL 模板断言用例（参照既有 `OdsV2SqlContractSpec.scala:183` 的「只认 `INSERT OVERWRITE … SELECT … FROM` 一种形态」范式）能断言新键；
- **负向验收（必需）**：构造「同一 `event_id`、两个不同源身份」的输入，断言输出为 **2 行**（旧键下为 1 行）。此项对应 P2-06 出口判据。
- **等价性对照（必需）**：单源输入下，新键与旧键输出**逐行等价**（行数 + 关键字段）。若不等价 ⇒ 说明旧键确有被掩盖的误去重，须记录为发现而非忽略。

**证据级别**：E1 + E2 必需；**E3 建议**（真实 `spark-submit`，D-060）；**E4 本行不可能**。

### A3 缺字段显式 NULL + DQ（依赖 D-4）

**要求**：映射未声明的字段写出**显式 `NULL`**，并产生字段级 **DQ 记录**。
不得用 `CAST('' AS BIGINT)` 这类「静默 NULL」路径代替显式缺失。
**验收判据**：构造缺字段样本 → 目标字段为 `NULL` **且** DQ 表有对应记录（记录须能定位到字段）。
**证据级别**：E1 + E2；E3 建议。**当前 DQ 表落点未实测**（本泳道未查 `dwd_reject_record` 与质量表的写入路径细节），列为开工前置再确认项。

### A4 payload 收敛到 `payload_json`（来自 D-054）

**要求**：DWD 字段取值改走 `get_json_object(payload_json, …)`，关闭 `payload_*` 与 `payload_json` 的双所有者过渡态。
`payload_hash` **不得**用作连接/去重键。
**验收判据**：E2 断言 DWD SQL 中不再出现 `payload_*` 列引用；E3 实测新旧取值**逐行一致**（这是「收敛不改语义」的唯一可信证据）。
**证据级别**：E1 + E2 + **E3 必需**（D-054 明文把切换排期到本行，等价性必须真链证明）。

### A5 禁止通用 CAST BIGINT（回归守卫）

**要求**：新增/修改后，`spark-jobs/src/main` 内不得出现裸 `CAST(... AS BIGINT)`；id 转换仍只经 `IdCodec`。
**验收判据**：`git grep -n -I -E "CAST\([^)]*AS BIGINT" -- spark-jobs/src/main` 命中**全部位于注释**（现状基线，见 RAW-READINGS R-12）。
**证据级别**：E0（只读扫描）+ E2。

---

## 3. 证据级别总表

| 项 | E1 | E2 | E3 | E4 | E5 |
|---|---|---|---|---|---|
| A1 列名对齐 | 必需 | 必需 | 待 D-2 裁定 | **不可能** | N/A |
| A2 复合键 | 必需 | 必需（含负向） | 建议 | **不可能** | N/A |
| A3 缺字段 NULL+DQ | 必需 | 必需 | 建议 | **不可能** | N/A |
| A4 payload 收敛 | 必需 | 必需 | **必需** | 可能（集群） | N/A |
| A5 禁 CAST 回归 | — | 必需 | — | — | N/A |

**E4 不可能的原因（须书面留档）**：真库 `source_registry` 实测**只有 1 行**（`mock-mall`）。
跨源去重在单源库上**不可证伪**：新键与旧键结果必然等价，测不出差别。
请总控裁定 P2-06 出口判据的取证方式（是否需先造第二个源）。

---

## 4. 硬约束

1. 不得改 `spark-jobs/pom.xml`（D-060）。
2. 不得改 `contract-specs/**` 已冻结本体；契约变更须先走契约任务（V2.4 §2.1 L37-45）。
3. 不得改 `docs/contracts/event-contract.md`（CT-2 已落盘）。
4. 不得引入第二套源→规范归一逻辑（V2.4 L346-L350 归属 `EventNormalizer`）。
5. `INSERT OVERWRITE` **按位置对齐**：新增列必须在 SELECT 列表末尾（`DimSql.scala:15` 既有纪律；`DwdSql.scala` 同受约束）。
6. 不得改既有 CLI 参数名/语义（P2-03 RULINGS 停止条件 2）。
7. 不得重启 8090/8091/8092，不得对真库做 DDL/DML（P2-03 RULINGS 停止条件 3）。
8. 端到端 Hive 断言只认真实 `spark-submit` 进程（D-060）。
9. `spark-jobs/**` 模块单写者：开工前确认 P2-03 已落盘（D-091）。

---

## 5. 停下报告条件（命中即停并回报）

1. **D-1/D-2/D-3 未获裁定**而需开始实现 → 停。
2. 需要改 `contract-specs/**` 已冻结规则本体才能实现 → 停。
3. 需要改 `docs/contracts/event-contract.md` 才能实现 → 停。
4. 需要改 `spark-jobs/**` 既有 CLI 参数名/语义 → 停（P2-03 停止条件 2）。
5. 需要重启 8090/8091/8092 或对真库做 DDL/DML → 停（P2-03 停止条件 3）。
6. 发现实现与契约冲突 → 按 V2.4 §2.1「先提交契约变更决策，再修改实现」，停。
7. 发现需在 DWD 侧新建一套源→规范归一逻辑（与 P3-02 重复所有者）→ 停。
8. **单源库下无法构造跨源样本** → 停（这是本行的结构性盲区，不得用 mock 顶替，也不得声称已验证）。
9. 发现 P2-03 泳道正在并发写 `spark-jobs/**` → 停（D-091 模块单写者）。
10. 发现 `IdCodec` 之外存在第二条 id 转换路径 → 停（反熵，避免第二所有者）。

---

## 6. 开工前置检查清单（逐条打勾后才可开工）

- [ ] D-1 已裁定（投影对象是否存在）→ 裁定号：______
- [ ] D-2 已裁定（源身份列名）→ 裁定号：______
- [ ] D-3 已裁定（与 P2-03 的协调）→ 裁定号：______
- [ ] D-4 已裁定（缺失 vs 脏数据如何区分）→ 裁定号：______
- [ ] D-5 已裁定（4 列 vs 5 列是否先追平）→ 裁定号：______
- [ ] P2-03 泳道已落盘、`spark-jobs/**` 无并发写入者
- [ ] 已按 D-091 Q17 重取 `DwdSql.scala` / `IdCodec.scala` / `warehouse/ddl/01-dwd.sql` 的行号与 blob 指纹（**不得沿用本报告行号**）
- [ ] 已确认 P2-06 出口判据的取证方式（E4 盲区如何处理）

---

## 7. 只读取证命令（供总控复算本单的现状依据）

```pwsh
cd D:\Develop_code\GraduationProject
pwsh -NoProfile -File docs/acceptance/p2-04-dwd-source-projection-20260912/raw/collect-evidence-p2-04.ps1
```

---

## 8. 本单的未实测声明

本施工单**未实施任何一项**。全部验收判据均为**待执行**。
**未实测**：E1/E2/E3/E4/E5 全部；DQ 表落点；`payload_json` 与 `payload_*` 等价性；缺字段样本行为。
状态：**规格与施工单草案已产出（E0 级，非验收）**。
