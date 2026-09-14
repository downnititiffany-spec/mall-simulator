# V25-Q01 版本作用域与过渡形态说明（raw 证据件）

- 泳道：**V25-Q01「F-88 质量门严重度口径」**（Owner＝总控泳道）
- 仓库：`D:\Develop_code\GraduationProject`，分支 `remediation/r1-boundary`，HEAD `8983616`
- 记录时间：2026-09-14
- 口径纪律：**只写实测事实；未测项显式标「未测」，不得用估计代替测量。**

---

## 0. 一页结论（先说不能声称什么）

| 问题 | 实测结论 |
|---|---|
| `quality_rule_definition` 表是否已落地？ | **否**。全仓 grep 该表名 **0 命中**；库中亦无此表（未执行任何 DDL，见 §5）。 |
| 「版本化已闭合」能否声称？ | **不能**。冻结集来自**代码内快照**，不是从库读的；`data_quality_result` 也无 `rule_version` / `effective_severity` / `compat_policy_version` / `rule_fingerprint` 列，故「该 run 实际用了哪一版」**没有持久化载体**。 |
| 当前能声称什么？ | 仅能声称：**同一次 run 内**严重度只解析一次、fingerprint 恒定，且写侧/读侧/证据留痕三条路径共用同一份规则集。这是「run 内一致性」，**不等于**「跨 run 版本可追溯」。 |
| 迁移文件是否已建？ | **否**。DDL 仅为草案（`raw/q01-quality-rule-definition-ddl-draft.sql`），未编号、未建迁移文件、未对任何库执行。**迁移号向总控申请中**。 |

---

## 1. 冻结规则集从哪里来（当前＝过渡形态）

### 1.1 实现事实

唯一入口是 `PipelineService.rulesFor(PipelineRun)`：

```java
private QualityRuleCatalog.FrozenRules rulesFor(PipelineRun run) {
    return QualityRuleCatalog.DEFAULT.freeze(run.getPipelineCode());
}
```

`QualityRuleCatalog.DEFAULT` 是 `platform-common` 里的**进程内常量目录**：

- `CATALOG_VERSION = "qrc-1"`
- `COMPAT_POLICY_VERSION = "compat-v1"`
- `DEFINITIONS` 共 **35** 条 = 33 条 `RuleSeverity.REGISTERED` 中的已登记码 ＋ `ORDER_ITEM_AMOUNT_FORMULA` ＋ `DWD_DWS_AMOUNT_RECONCILE`
- `fingerprint()`：对全部定义算 SHA-256（排除 `rationale`）

`freeze(sourceScope)` 按作用域过滤后返回 `FrozenRules`，并携带 `catalogVersion()` / `compatPolicyVersion()` / `fingerprint()`。

### 1.2 它保证了什么（可声称）

`PipelineService.execute(PipelineRun)` 在**进入七阶段之前**取一次：

```java
QualityRuleCatalog.FrozenRules rules = rulesFor(run);
```

此后 run 内全部判定都用这一个引用：

| 位置 | 用途 |
|---|---|
| `QualityChecker.check(events, runId, batchOrderTotals, rules)` | 写侧内联规则严重度 |
| `persistQuality(run.getId(), snapshotIdRef, "LANDING", quality.results(), rules)` | 落库前再解析有效严重度 |
| `persistChecks(run.getId(), snapshotIdRef, ex.checks(), rules)`（`QUALITY_CHECK` / `PUBLISH_METRIC` 两处） | ADS 暂存层 dqc 检查落库 |
| `jobEvidence(ex, rules)` | 阶段证据里的 `normalizedSeverity` / `blockingFailed` |
| `qualityGate.blockingFailuresForRun(run.getId(), rules)` | **发布前门断言** |

⇒ **run 内不存在两套口径**：因为 run 期间不再重新解析目录，同一 `(ruleCode, passed)` 必然得到同一 `effectiveSeverity`。

### 1.3 它**没有**保证什么（不得声称）

目录是**随代码发布而变**的常量。因此：

- 换一次代码部署（哪怕只改了 `DEFINITIONS` 的 `severity`）就会改变后续 run 的口径，而**已发布的历史 run 结论不会随之改变**，也没有字段记录「那次 run 用的是哪一版」；
- 「不同 run 之间随库中版本变化」这一层**完全未实现** —— 这正是 §7.3.1 line 520「一次 run 冻结**完整规则版本**与指纹，**结果记录该版本**」中「记录该版本」尚未落地之处；
- `rule_version` 目前恒为 `1`（33 条已登记码均按既有档位登记为 version 1）。**档位变动必须发新 version 并显式裁决**（§7.3.1 line 524），当前无任何机读载体去承载 version 2。

**⇒ 因此「版本化已闭合」不可声称。** 诚实表述是：**「run 内一致性已实现并有测试钉住；跨 run 版本可追溯未实现，阻塞在表与迁移号。」**

---

## 2. `rule_version` / 批次范围的取值与依据

### 2.1 取值（实测事实）

| 项 | 取值 | 依据 |
|---|---|---|
| `rule_version`（全部 33 条已登记码） | `1` | 这 33 个码在改造前已有固定档位（12 BLOCKING ＋ 14 MP_ BLOCKING ＋ 3 WARN ＋ 3 INFO ＋ 1 catalog-only `MP_ADS_WRITE_MATCH`）。登记为 version 1 ＝「照现状冻结」，**不改档位**，故无需新 version。 |
| 新增 2 个金额码 | `1` | `ORDER_ITEM_AMOUNT_FORMULA`（DWD）、`DWD_DWS_AMOUNT_RECONCILE`（DWS）为本泳道新登记，无历史档位，故 version 1。 |
| `source_scope` | `"*"`（`QualityRuleDefinition.SCOPE_ALL`） | 这些规则的判定逻辑不随 source 变化；作用域留待表落地后按 `source_scope` 收紧。 |
| `compat_policy_version` | `"compat-v1"` | 见 §3。 |
| `CATALOG_VERSION` | `"qrc-1"` | 目录整体版本。 |

### 2.2 批次范围：**未使用，也不应使用**

§7.3.1 line 524 允许两种历史兼容依据之一：`(source_id, rule_code, rule_version)` **或**「明确批次范围」。

本泳道**只用前者（rule_version）**，理由实测：

1. 批次范围需要一个稳定可列举的批次标识。实测 `analytics_meta.data_quality_result` 共 **`COUNT(*) = 467`** 行，跨 run 号 11–47，**没有 `batch_id` 列**（列集见 `raw/db-a-rule-severity-inventory.txt`），无法机读界定批次边界；
2. 用 run 号区间当批次，等价于把「哪几次 run 豁免」写死在代码里，属**隐式永久降级**，比版本号更难审计；
3. `data_quality_result` 现有列里唯一接近版本语义的是 `severity`（字面）与 `passed`，两者都不是版本。

⇒ **明确声明：本泳道不采用批次范围口径；任何历史兼容都必须落到 `rule_version`。**

### 2.3 ⚠ 「未登记码」的爆炸半径（必须显式裁决的既有事实）

实测 `analytics_meta.data_quality_result` 里出现的、**不在** `RuleSeverity.REGISTERED`（33 码）里的码约有 13 个：

```
ADS_DWS_FUNNEL_RECONCILE, ADS_STAGING_KEY_NOT_NULL, ADS_STAGING_PRESENT,
ADS_STAGING_SNAPSHOT_ISOLATION, AMOUNT_RECONCILE, ENUM_WHITELIST, EVENT_ID_UNIQUE,
MXP_*, PUB_DQ_*, PUB_FORMAL_PARTITION_MATCH, PUB_STAGING_READY, REQUIRED_FIELD_NULL_RATE
```

**这 13 个码在重建目录时全部被登记为 version 1 定义，档位取该码改造前的既有档位。**

这**不是**自动降级，而是把「代码里早已硬编码的档位」如实登记进目录；但它是**一处需要总控显式裁决的动作**，理由：若不登记，它们会全部落入「未登记 ⇒ 停止发布」（§7.3.1 line 524），使历史 run 集体变 FAIL。登记后行为与改造前一致，且 `EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE` 两个码**另行收窄**（见 §4）。

**⇒ 请总控裁决：这 13 个码按既有档位登记为 version 1 是否正确；若认为其中某些码本应阻断，需发新 version 并显式裁决。**

---

## 3. `compatPolicyVersion` 的取值与语义

- 取值：**`"compat-v1"`**（`QualityRuleCatalog.COMPAT_POLICY_VERSION`）
- 语义（当前实现，共 3 条）：
  1. **保留原始结果字段**：`data_quality_result.severity` 仍是落库时写入的字面值，不回填、不改写历史结论（§7.3.1 line 524）。
  2. **不回填历史结论**：48 行历史 `severity IS NULL` 的行**保持 NULL**（总控裁决 1），读取时由 `resolve` 现算，不写回库。
  3. **接口同时展示兼容解释**：`GateDecision.reason()` 对**每一条**规则记录 `ruleCode(declared→effective): explanation`，使「为什么这条被升/降」可读。

`compat-v1` 是**行为策略**的版本，不是规则版本；规则版本是 `rule_version`。两者正交，均由 `FrozenRules` 一并携带。

---

## 4. 一次收窄：`EVENT_ID_UNIQUE` / `PUB_DQ_EVENT_ID_UNIQUE`

**这是本泳道唯一的档位语义变化，且是收紧（不是放松），必须显式记录。**

- 改造前：这两个码被**无条件**映射为 `WARN` ⇒ 未通过也不阻断。
- 实测证据（只读 SELECT `analytics_meta.data_quality_result`）：

| run | rule_code | error_rate | threshold | passed |
|---|---|---|---|---|
| 24, 47 | `EVENT_ID_UNIQUE` | `0.020408` | `<=0.0005` | 0 |
| 24, 47 | `PUB_DQ_EVENT_ID_UNIQUE` | `0.071429` | `0.0005` | 0 |
| 11 | `EVENT_ID_UNIQUE` | `0.333333` | `<=0.0005` | 0 |

⇒ 均为**真实超阈值**（约 40 倍 / 143 倍 / 667 倍），`passed=0` 不是误判。旧口径等于把超阈值的高重复率**直接放行**，违反 §7.3.1 line 522「测试不得为通过把高重复率直接放行」。

- 改造后：登记为 `SeverityMode.THRESHOLD_OBSERVATION` —— 未超阈值（`passed=1`）＝ 观察项不阻断；**超阈值（`passed!=1`，含 `null`）＝ 升为 `BLOCKING`**。
- **阈值本身未改**：`QualityChecker.DUP_RATE_MAX = 0.0005`（＝批准阈值「主键重复率 ≤0.05%」），`EVENT_ID_UNIQUE` 历史阈值 **0.0005 未经新裁决不修改**（§7.3.1 line 522 逐字要求）。

### 4.1 直接后果（必须主动告知，不得静默应用）

**历史 run 24 与 run 47 在新口径下合法地翻为 FAIL。** 旧 F-94 结论「读侧归一化后 run 24/47 PASS」**已被实测证伪**，不得再引用。

当前 `analytics_metric.metric_snapshot` 恰好只有 1 行 ACTIVE ＝ `S20260901_47`（run 47，发布时刻 `2026-09-12 21:29:27.321`），其黄金值与 run 24 完全一致（gmv 2042.0000 / net_sale 1493.0000 / paid_order_cnt 5.0000 / pv 7.0000 / refund_rate 0.6000）。**该行未被本泳道修改**（本泳道未执行任何 DML）；但按新口径其 `qualityStatus` 会读成 FAIL —— 即**用户可见的质量卡片会变红**。

**⇒ 请总控裁决是否接受该后果。** 这是「把过松的契约收紧」的必然结果；若不接受，替代方案是把阈值上调到高于历史实测重复率（0.020408），但那与「批准阈值 0.0005 未经新裁决不修改」直接冲突，本泳道**不建议**且**未实施**。

---

## 5. 迁移与 DDL：本泳道做了什么、没做什么

### 5.1 已做（仅文档）

产出 DDL **草案**两份（均在 `raw/`，**不是迁移文件**）：

| 文件 | 内容 |
|---|---|
| `raw/q01-quality-rule-definition-ddl-draft.sql` | `quality_rule_definition` 建表草案（含 `rule_code` / `version` / `source_scope` / `stage` / `severity` / `threshold_json` / `enabled` / `effective_from` / `effective_to` / `checksum`，唯一键 `(source_scope, rule_code, version)`）＋ `data_quality_result` 缺失列的**两种**方案（注释形式） |
| `raw/q01-ddl-draft-notes.md` | 草案说明与取舍理由 |

### 5.2 明确**未做**（纪律）

- **未新建任何迁移文件**，**未自行编号**；
- **未对任何数据库执行 DDL 或 DML**（全泳道对数据库只有只读 `SELECT`）；
- 未改 `spark-jobs/**`、未改冻结契约、未改指导书、未改看板。

### 5.3 需要总控提供

1. **迁移号**（本泳道不自取）；
2. 裁决 `data_quality_result` 增列采用哪种方案（两种草案已注明取舍：单列 `effective_severity` 最小改动 vs. 同时落 `rule_version` ＋ `compat_policy_version` ＋ `rule_fingerprint` 以真正满足「结果记录该版本」——本泳道**推荐后者**，否则 §1.3 的缺口无法闭合）；
3. 裁决 §2.3 的 13 个码登记与 §4.1 的历史 run 翻转后果。

---

## 6. 表落地后如何取冻结集（迁移路径，尚未实施）

表落地并批准迁移号后，`rulesFor` 应改为「从 `quality_rule_definition` 按 `(source_scope, rule_code, version, enabled, effective_from/to)` 装载并冻结」，并：

1. 在 `data_quality_result` 落 `rule_version` / `effective_severity` / `compat_policy_version` / `rule_fingerprint`，使「该 run 用了哪一版」可查；
2. 保留 `QualityRuleCatalog` 作为**默认/兜底与测试夹具**，但**不得**再作为生产判定来源（否则构成 §7.3.1 要消除的「双所有者」）；
3. 版本变更走「新 version ＋ 显式裁决 ＋ `checksum` 变化」，`fingerprint` 随之变化。

**前提未满足前，本节全部为计划，不构实验收证据。**

---

## 7. 本文件对应的实测命令与结果

| 目的 | 命令 | 结果 |
|---|---|---|
| `quality_rule_definition` 是否入仓 | grep 全仓该表名 | **0 命中** |
| 规则码清单 | 只读 SELECT `analytics_meta.data_quality_result` | `COUNT(*) = 467`；`severity` 分布：BLOCKING 296 / ERROR 120 / NULL 48 / INFO 3 / WARN **0** |
| 超阈值事实 | 只读 SELECT（见 §4 表） | 见 §4.1 |
| 快照状态 | 只读 SELECT `analytics_metric.metric_snapshot` | 12 行，`MAX(id)=27`，ACTIVE 恰 1 行 ＝ `S20260901_47` |

原始输出见同目录 `raw/db-*.txt`。

---

## 8. 未测项（显式声明）

- **未测**：`quality_rule_definition` 表落地后的装载逻辑（表不存在，无从测）。
- **未测**：`data_quality_result` 增列后的写入与回读（列不存在，无从测）。
- **未测**：跨 run 版本可追溯性（无载体，无从测）。
- **未执行**：任何 DDL/DML、任何连接数据库的集成测试（`-Dmetric.it=true` 未使用）、任何服务启停。
- **未验证**：`platform-app` 的跨模块回归护栏 `RuleSeverityPathConsistencyTest` 此时**尚未编译或执行过**（阻塞于另一泳道未入库文件），故**不构成证据**；详见该文件类注释。
