# 总控独立复核：F-88 隔离真链验收（2026-09-14）

- 复核对象：`b26e92d`（44 files / +3574，仅 `docs/acceptance/v26-f88-isolated-chain-20260914/`）
- 复核前基线：`37561dc`；工作区干净；`git status -sb` = `ahead 1`（未推，符合分工）
- 复核原则：**不采信泳道自述**，全部关键结论用总控自己的只读查询/亲验工件重取

## 1. 总控独立实测（我自己跑的，与泳道脚本无关）

### 1.1 3307 直连复取（`wsl mysql -h127.0.0.1 -P3307 -uroot < 总控自写 SQL`）

```
port=3307  uuid=de8ebbea-aff4-11f1-8037-00155d5dba47
id=1 AMOUNT_RECONCILE         LANDING BLOCKING/BLOCKING v1 compat-v1 fp8=6bc272d7 len=64 passed=1
id=2 REQUIRED_FIELD_NULL_RATE LANDING BLOCKING/BLOCKING v1 compat-v1 fp8=6bc272d7 len=64 passed=1
id=3 EVENT_ID_UNIQUE          LANDING WARN    /BLOCKING v1 compat-v1 fp8=6bc272d7 len=64 passed=0 rate=0.010989
id=4 ENUM_WHITELIST           LANDING BLOCKING/BLOCKING v1 compat-v1 fp8=6bc272d7 len=64 passed=1
rows_total=4  declared_ne_effective=1  fp_match=4  compat_match=4  rv_notnull=4  unregistered_shape=0
flyway_schema_history: ... V18 → V19 quality rule definition → V20 data quality result rule version（installed_on 2026-09-14 16:26:31）
information_schema: 四列 = 4 存在；quality_rule_definition 存在 = 1
```
⇒ 泳道 A1–A5 断言**逐项独立复现**；A5 那行「声明 WARN / 生效 BLOCKING」总控亲眼取到原值。

### 1.2 probe 库「V20 不回填」独立复测（3307）

`cols_total=18`、`rows_total=26`、**`rows_with_version_info=0`**、四列 `IS_NULLABLE=YES / COLUMN_DEFAULT=NULL`、历史 `severity='WARN'` 行 **3 行仍在**。
⇒ 「四列可空、无 DEFAULT、**不回填任何猜测值**」三条约束独立成立（V20 头注 `:26-33` 的要求被真 DDL 验证，而非只看脚本文字）。

### 1.3 被测 jar 身份与成分（亲验）

`analytics-server/platform-app/target/platform-app-0.1.0-SNAPSHOT.jar`
`sha256=86B39A592FE9689D9B300BDC2B1F7A749EA6A1B1356316931DDCF91FE8C61473`（与泳道自述一致）、33169747 B、构建 2026-09-14 16:24:59、内含 `BOOT-INF/classes/db/meta/V19__quality_rule_definition.sql` 与 `V20__data_quality_result_rule_version.sql`。
⇒ 「3307 上 V19/V20 落地」的**因果链**（新 jar 含迁移 → 启动时 `MetaFlywayInitializer` 落地）成立，不是巧合。

### 1.4 3306 只读独立取证（总控自己在窗口之后测得）

```
port=3306 uuid=85191145-1491-11f0-b4e2-60cf84d55629
dqr_rows=467   v20_cols_on_3306=0   max_flyway=18   qrd_table_on_3306=0   tables_meta=22
```
⇒ 3306 **无 V19/V20 痕迹**（四列不存在、定义表不存在、迁移最高仍 V18），且 `dqr_rows=467` 与 V20 头注 `:28`「本机实测 467 行」一致。
**边界说明（不夸大）**：我独立证明的是「窗口之后 3306 的 after 态」＋「after 态与泳道 before 态一致」；「窗口**期间**零写入」的实时证据仍来自泳道工件（其 before/after 采集为同一 SQL、同一窗口）。

### 1.5 泳道工件一致性核对（我做的比对，不是引用）

- `00-fingerprint-3306-before.txt` 与 `50-fingerprint-3306-after.txt` 各 35 行，`Compare-Object` **差异仅 4 行** = 两组 `phase`/`collected at` 头；27 项键**逐项全等**（`dqr_rows 467`、`dqr_column_count_on_3306 14`、`dqr_v20_columns_present_on_3306 0`、`qrd_table_present_on_3306 0`、`flyway_meta_count 17`、`metric_snapshot_rows 12`）。
- `raw/40-four-column-assertions.txt:21/:95` 与 `raw/http/03b-pipeline-run-final.json` 与我在 1.1 的直连结果三者一致。
- `b26e92d` 改动面：`git diff --name-only 37561dc b26e92d` 过滤后**无任何非本泳道目录文件**，`*.java|*.sql` 在 `docs/` 之外**0 个** ⇒ 「主代码 0 改动、未碰 V19/V20」成立。
- 收尾与卫生：8091 **无监听**；存活 java 全为 IDE（55804/66224 DataGrip、58580/76836 IntelliJ）；本泳道 raw 内 **JWT 命中 0**；`00-fingerprint-3306-readonly.ps1:30-31` 确认无明文默认口令、缺 `HOST3306_PWD` 即 throw。

## 2. 总控裁决（泳道 7 项待裁）

| # | 事项 | 裁决 |
|---|---|---|
| 1 | 终态 `FAILED/PIPELINE_QUALITY_FAILED` 是否满足「跑一次真实链路」口径 | **满足**。F-88 要证的是"结果行携带版本信息"，而唯一的 `声明≠生效` 样本恰由阻断产生；另造全过样本只会替换样本、不增信息。**但**「成功/发布路径（ADS_STAGING/PUB/MXP/MP 约 31 条规则）四列落库」登记为**未取证**，排在 S04/D-5 启动前门禁之后补。 |
| 2 | A5 一行是否足以认定 F-88 写侧契约闭合 | **足以认定「写侧契约已在真库落库层面成立」（限定验收）**；**不足以**认定「规则版本化闭合」——还差读侧 F-93、3306 真库迁移、未登记分支。两级判定不得合并。 |
| 3 | 是否要专门构造未登记码场景 | **不需要另做真链**（真链造未登记码须改 Spark 作业或目录，成本与风险不值）。原因：该分支**已有单测覆盖**——`QualityCheckerSeverityTest#unregisteredRuleCodeWritesNullSeverityAndStillCarriesVersionInfo`（断言 `severity isNull` ＋ `ruleVersion isNull` ＋ `effectiveSeverity==UNREGISTERED` ＋ 策略版本/指纹非空，并先断言别名前提）。 |
| 7 | 是否授权补「未登记码 ⇒ severity/rule_version 为 NULL」写侧单测 | **驳回为「无需」**：该测试在写侧泳道（`8853730`）已交付并经总控复跑（`Tests run: 134` 含该用例）。泳道未知情属信息不同步，此处纠正，不重复投入。 |
| 4 | 遗留库/账号是否立即清理 | **保留**（probe 库 + 隔离库 + 3 账号），作为可复核证据，登记进 V2.4 的隔离实例资产清单；统一清理时点定在 F-93 完成之后，且**必须走 `-AllowedCleanDbs` 白名单**，禁止手写 `DROP`。 |
| 5 | 对 E3 脚本的两处收紧是否认可 | **认可并采纳为基线**：①启动前 GATE 自检（D-5 雏形）②隔离准备不读 3306、改取仓库内工件真值＋sha256（更强）。**不回退**。 |
| 6 | `15-seed-runtime-profile.ps1` 首跑缺陷未独立留档 | 采认 `REPORT §8.2` 的披露**为足够**，不要求重跑（重跑只覆盖值、不增信息）；但登记为**证据残缺项**：「该脚本首跑错误值未独立留档」。该缺陷本身（PowerShell `-replace` 是正则替换 ⇒ 落库 4 个反斜杠）是**真实教训**，须写进 V2.4 的踩坑登记。 |

## 3. 结论分级（分项）

| 分项 | 判定 | 依据 |
|---|---|---|
| 提交完成 | **是** | `b26e92d`，44 文件，工作区干净（未推，待总控） |
| 测试通过 | **不适用** | 本泳道 `-DskipTests`，未跑任何测试；其所依赖的写侧测试已由 `8853730` ＋ 总控复跑覆盖 |
| 限定验收 | **是** | 「3307 上 V19/V20 真落地」＋「四列真落库，含 1 行声明/生效分离」＋「3306 无 V19/V20 痕迹、前后指纹全等」三条，总控已独立复现 |
| 完整验收 | **否** | 未覆盖：未登记码真链、多层多码（31 条规则）、成功/发布路径、3306 真库迁移、35 条定义 ↔ 写侧 version/checksum 全量映射 |

**显式声明**：本次「链路按设计在质量闸门阻断、四列确实落库」**不等于「F-88 已闭合」**。总控采认泳道的这一自我克制，并在此重申：**不得声称「规则版本化已闭合」**。

## 4. 未取证（不得当通过）

1. 未登记码分支的真库落库（本 run 0 行；仅单测覆盖）。
2. ADS_STAGING/PUB/MXP/MP 约 31 条规则的落库（本 run 仅 LANDING 4 条）。
3. 成功/发布路径 + `metrics/overview`（本次 `data=[]`）。
4. 3306 真库上 V19/V20 的执行结果（按铁律只读；须 D-5 门禁后另行裁决）。
5. V19 的 35 条定义与写侧 `rule_version`/`checksum` 的全量映射（仅核到本次 4 行为 v1）。
6. `catalog=qrc-1` 无落库承载列 ⇒ 无法从库内回查目录版本（只在日志）。
7. 3306 binlog 位点级零写入证明（未取 before 位点；`performance_schema` 为空仅为旁证）。
8. `analytics-server` 六模块真实测试数（仍阻塞于 D-5：platform-app 测试会触发 Flyway→3306）。
