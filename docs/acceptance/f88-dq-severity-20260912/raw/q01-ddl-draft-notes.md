# q01 DDL 草案说明（f88-dq-severity 泳道）

**未执行声明**：本次未执行任何 DDL/DML，未运行 mysql/任何 SQL 客户端/Maven 目标，未连接任何数据库；本文件与同目录 `q01-quality-rule-definition-ddl-draft.sql` 均为**文本草案**（NOT AUTHORIZED / NOT EXECUTED），迁移号由总控分配。

## 一、依据的指导书原文（`docs/项目完整实施指导书 V2.5.md`）

- **line 501（§7.3）**：「建立 `quality_rule_definition` 和版本/生效区间，严重度行为如下：BLOCKING/ERROR 阻断发布，WARN/INFO 持久化并展示但不阻断。」
- **line 516（§7.3）**：「每条 BLOCKING 规则必须有一条『构造失败→流水线失败→不产生新 ACTIVE→旧 ACTIVE 可读』的负向验收。」
- **line 520（§7.3.1）**：「quality_rule_definition 需含 rule_code、version、source_scope、stage、severity、threshold_json、enabled、effective_from/to、checksum；(scope,rule_code,version) 唯一。一次 run 冻结完整规则版本与指纹，结果记录该版本、实际值、阈值、passed、原始及有效严重度、兼容策略版本。」
- **line 522（§7.3.1）**：「执行顺序：选择作用域/版本 → 计算指标与阈值判定 → 决定严重度 → 汇总门禁。必填/主键/金额对账失败不能被全局规则码映射降 WARN。原始重复事件仅在确定性去重已证且重复率不超批准阈值时为观察项；超过阈值阻断。EVENT_ID_UNIQUE 历史阈值 0.0005 未经新裁决不修改，测试不得为通过把高重复率直接放行。」
- **line 524（§7.3.1）**：「历史兼容需 (source_id,rule_code,rule_version 或明确批次范围,compatPolicyVersion)，没有版本且不在批准范围时不得自动降级。保留原始结果字段，不回填历史结论；接口同时展示兼容解释。未知规则码不可盲信传来的 WARN，应停止发布并报未登记规则。」
- **line 526（§7.3.1）**：「付款 vs 订单总额、订单项公式、DWD↔DWS 金额是三种独立校验，不能用一个 AMOUNT_RECONCILE 测试替代全部。」
- **line 528（§7.3.1）**：「每条 BLOCKING/ERROR 验一次：构造失败 → 本次流水线失败 → 不生成新 ACTIVE → 原 ACTIVE 数值/指纹可读且不变。失败必须可定位到 stage/rule，不只显示 RUN_JOB_FAILED。」
- **line 536（§7.4）**：「唯一性按发布作用域（至少 source_id + runtime_profile_id；如再按 businessDate 切分，先与现有快照契约统一）建立数据库约束，不凭全库最新一行选择。」
- **line 663-667（§9.4）**：测试默认关闭外部写入；先校验隔离白名单后执行 DDL/DML；不用 root/正式写账号；cleanup 用本次 testRunId；跑前后记录计数与关键行 checksum。

## 二、决策清单

| # | 决策 | 说明 |
|---|---|---|
| D1 | **新增表，不是改表** | `quality_rule_definition` 全仓 0 命中（`docs/acceptance/guideline-v24-coverage-20260912/raw/07-platform-entity-greps.txt:12`、`docs/v2-completeness-audit.md:340,518`）。未发现同名既有表 ⇒ 无冲突可言。 |
| D2 | **schema 归属留给总控** | `db/meta` 脚本一律不写库名，靠连接默认库（`warehouse/migrations/init-three-dbs.sql:6` 的 `analytics_meta`）。草案沿用房内风格写非限定表名；落地建议 `analytics_meta.quality_rule_definition`。 |
| D3 | 列顺序 = line 520 字段枚举顺序 | 便于逐字段对照审阅。额外加 `severity_mode`：没有它就无法表达 EVENT_ID_UNIQUE 的「原始 WARN、超阈值转阻断」。 |
| D4 | `threshold_json` 用 `JSON` | 阈值是结构化判定契约（`{"dupRateMax":0.0005,"dedupDeterministic":true}`）；现库 `threshold varchar(64)` 的 `"<=0.0005"` 文本会丢结构。JSON 列写入即校验合法性（对照 `db/meta/V15__stage_evidence_mediumtext.sql:5` 的非法 JSON 事故）。 |
| D5 | `checksum CHAR(64)`、`enabled TINYINT(1)`、区间 `DATETIME(3) NULL` | 与代码侧已固定的 SHA-256 hex 64 字符一致（`QualityRuleDefinition.java:112-133`）；`TINYINT(1)` 让驱动映射 Boolean（房内已知，见 `SourceRegistryMigrationMySqlIT.java:190`）；`DATETIME(3)` 与平台一致。 |
| D6 | 唯一键 `uk_qrd_scope_rule_version`、解析索引 `idx_qrd_resolve(source_scope,stage,enabled,effective_from)` | 前者是 line 520 硬要求；后者支撑 line 522 第 1 步「解析 T 时刻该作用域启用版本」，范围列放最后。 |
| D7 | 种子 4 条：AMOUNT_RECONCILE / REQUIRED_FIELD_NULL_RATE / ENUM_WHITELIST = BLOCKING；EVENT_ID_UNIQUE = WARN + THRESHOLD_OBSERVATION（0.0005） | 严重度与阈值已有代码侧单一所有者（`RuleSeverity.java:94,97,100`、`QualityRuleCatalog.java:60-77`）；0.0005 出自设计文稿 V2.2 §5.4.2（`docs/design/...V2.2.md:584`），按 line 522 原样搬运不放宽。种子 `INSERT` **整块注释掉**。 |
| D8 | 结果侧给出**两个方案**均注释掉，不预设立场 | 方案 A 在 `data_quality_result` 加列（rule_version/declared_severity/effective_severity/compat_policy_version/actual_value/threshold_json）；方案 B 建伴生表 `data_quality_result_rule_version`。取舍一句话见 SQL §3。 |
| D9 | 房内风格照抄 | 裸 `CREATE TABLE`（非 IF NOT EXISTS，见 `V2__platform_pipeline_quality.sql:36`）、`ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci`（`V16__source_registry.sql:35`）、`uk_*`/`idx_*` 命名、每列 `COMMENT`。 |
| D10 | 未删任何旧列 | line 524「保留原始结果字段」⇒ 草案明确不 DROP `threshold`/`severity`。 |

## 三、未测 / 未验证

1. **MySQL 版本 = UNVERIFIED**：假设 8.0，依据是 `utf8mb4_0900_ai_ci` 已存在于 `V16:35` 与真实转储 `pre-v17-schema.sql:94`，以及 `SourceRegistryMigrationMySqlIT.java:21` 注释；**未连接复核**。
2. **`analytics_meta` 是否接受新表**：UNVERIFIED。`init-three-dbs.sql:15` 授 `ALL PRIVILEGES ON analytics_meta.*`，但未实测该账号能否 `CREATE TABLE`。
3. **是否存在「11 个库同名表」冲突**：未核查该数量说法。已核实的同类实例是 `metric_snapshot` 同名跨库（`docs/acceptance/b13-db-forensics-20260912/raw/05-rowlevel-two-schemas.sql:15-19`：`analytics_meta` 陈旧种子表 vs `analytics_metric` 生产表）。`quality_rule_definition` 本身 0 命中。
4. DDL 语法未在任何 MySQL 上解析过（未执行 `CREATE`/`EXPLAIN`）；`idx_qrd_resolve` 选择性未测。
5. 种子行 `checksum` 未计算，须由代码侧 `QualityRuleDefinition.checksum()` 生成后回填（禁止手填猜测值）。
