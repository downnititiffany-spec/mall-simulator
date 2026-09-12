# P2-02 规格草案：多格式时间与 quarantine 原因码

- 文档性质：**规格草案（DRAFT）**。`draft/` 前缀表示**尚未裁决**，不得据以开工；开工以总控落单的裁决与施工单为准。
- 泳道：P2-02 规格／施工单起草。**本泳道未实现任何代码、未改任何既有文件、未做任何 git 写操作。**
- 取数时点：2026-09-12 20:04 ～ 20:13（+08:00）。开工 HEAD `3dfe94e`；取数时 HEAD **`ce36065`**。
- 权威序（本草案据此）：① 冻结契约 `contract-specs/**` ② 指导书 V2.4 ③ 看板 V2.2 ④ 专项设计／实施书 ⑤ 旧版本。
- 需求原文出处（**两句都要看，缺一不可**）：

| 出处 | 原文 |
|------|------|
| 看板 V2.2 L221（出口证据列） | 格式、未知版本、坏时间负例 |
| `docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` L153-157（文件级步骤，§5 P2-02「Landing 解析」） | ① 显式 StructType 只解析信封和原始 payload 字符串；不可依赖 Spark 自动推断。② `event_time` 依 source profile 的格式列表依次解析；最终统一 UTC，保留 source timezone 元数据。③ 未知版本／无法解析时间／缺 eventId 进入 quarantine，保留原因码和原位置。 |

> **关键发现（先读这一条）**：「多格式时间」这个词组在整个仓库里**只出现在看板**（3 处：现行看板 L221、旧看板 L190、备份 L182），三处文字完全相同、都只有「格式、未知版本、坏时间负例」9 个字。**真正可施工的需求定义在 plan L153-157**，而看板没有链接到它。⇒ 若只读看板，P2-02 的需求是**不可施工**的（说不出"支持哪些格式"）。本草案以 plan L153-157 为准，并把其中的空白点全部登记为待裁决。

---

## §1 目标与范围

### 1.1 目标（可验收陈述）

P2-02 要在 **landing 解析这一环**建立三件事：

| 编号 | 目标 | 可观测产物 |
|------|------|-----------|
| G1 | `event_time` 按**源画像声明的格式列表依次解析**（确定性顺序，首个成功即用），并把解析结果归一到**单一约定时刻表示** | 归一后时间列；解析轨迹（命中第几项格式） |
| G2 | 解析不出绝对时刻的行**不进入 ODS 正常数据**，进入 quarantine 并带**稳定的原因码**与**原位置** | quarantine 行 + 原因码 + 原位置 |
| G3 | 上述行为有**负例验收**：每种失败各一批构造样本，逐条可判 | 负例夹具 + 逐条断言 |

G1 的「单一约定时刻表示」是本规格最大的未决点（见 §2.3 与待裁决 D-101）——plan 写的是「最终统一 UTC」，而冻结契约 D-053 要求 `event_time` 保持 `STRING`。二者可以同时满足（原列不动 + 追加派生列），但**必须由总控裁决**，本草案不自行认定。

### 1.2 范围内

- landing 解析阶段的时间语义：格式列表的取值语法、尝试顺序、时区解释、归一表示。
- 「无法解析时间」的**判据**（含「解析失败」与「无法判定」的区分，见 §2.4）。
- quarantine 原因码的**枚举、判据、稳定性规则**及其与 `data_quality_result` 的边界。
- 缺 `event_id`、未知 `schema_version` 两个既有隔离原因的原因码化。
- 出口判据与负例证据形态。

### 1.3 **不变什么**（显式非变更；本规格不授权任何一项变动）

| 冻结项 | 依据 | 本规格的处置 |
|--------|------|-------------|
| `event_time` 物理类型保持 `STRING` | D-053；`00-ods.sql:21/48/80/107`；`OdsV2Columns.scala:42` | 不动。归一结果只能落在**追加的派生列**上 |
| 12 个 ODS 公共列不增不减 | D-052；`OdsV2Columns.CommonColumns`（13 项含 `ingest_batch_id`） | 不动。新增派生列按 D-059 追加在**末尾** |
| `event_id` 的语义与去重键属主 | D-055 / D-062 / D-090 | 不动。本规格只改**缺 `event_id` 的行**的处置，不改 `event_id` 的含义 |
| DWD 去重键 `(source_instance_id, event_id)` | 指导书 §5.1 L314；D-062 | 不动 |
| `payload_json` 的唯一属主与双写 | D-054 | 不动 |
| `source_system` 的注入通道 | D-056 | 不动（但**源时区若要按源注入，必须复用同一通道**，见 D-109） |
| `landing_file` 的既有语义 | D-057 | 不动 |
| `spark-jobs/**` 的既有 CLI 入参名 | D-092 停止条件 | 不动 |
| 契约 `rule` 主体 | D-092 停止条件 | 不动 |
| `quarantine_record` 既有 108 行 / 9 类 `reason` 文本 | 真库只读实测（R2） | **不改写、不回填、不迁移** |
| `data_quality_result` 既有 15+ 个 `rule_code` 语义 | 真库只读实测（R3） | 不动 |
| `dwd_reject_record` 既有枚举与 `DUPLICATE_EVENT` 唯一写入者 | `01-dwd.sql:90`；`DwdSql.scala:64` | 不动。`FUTURE_TIME` **不在 P2-02 落地**（见 D-107） |
| 看板、契约、源码文件本身 | 本泳道边界 | 均未修改 |

---

## §2 时间语义

### 2.1 现状（全部为只读取数；证据见 `RAW-READINGS.md` 与 `raw/`）

| 环节 | 事实 | 证据 |
|------|------|------|
| 契约定义 | `event_time` / `ingest_time` 均 `$ref` 到 `$defs.iso8601_time`：`type: string`、`format: date-time`、pattern 要求**必须带偏移或 Z** | `canonical-event.v1.schema.json:30-37, 323-327`；`iso8601_time` 被 **8 个字段** `$ref` 引用（另有 1 处定义）⇒ 本规格影响 **8 个时间字段**，不止 `event_time` |
| 契约文档 | 「时间一律 ISO-8601 带时区（业务统一 Asia/Shanghai，`+08:00`）；解析失败视为脏数据」 | `docs/contracts/event-contract.md:25` |
| 采集侧校验 | `event_time` **只做非空检查**；全文件无 ISO-8601／偏移／时区／可解析性校验 | `EventContractValidator.java:56-61`；token 扫描 `BAD_TIME`/`TIME_PARSE`/`UNPARSEABLE`/`BAD_FORMAT`/`INVALID_TIME` **全 0 命中**（阳性对照有效） |
| ODS 装载 | `event_time` **透传**，分区 `dt`/`hour` 由**位置切片**得出：`REGEXP_REPLACE(SUBSTR(event_time,1,10),'-','')` 与 `SUBSTR(event_time,12,2)` | `OdsLoadSql.scala:90, 168, 169, 174` |
| ODS 丢弃 | `AND event_id IS NOT NULL AND event_time IS NOT NULL`，**静默丢弃**；唯一的拒绝模板 `rejectedSelect()` 只产字面量 `BAD_VERSION_OR_KEY` 且**无生产调用**（死 SQL，仅测试引用） | `OdsLoadSql.scala:181, 269-276`；`SqlTemplateSpec.scala:80` |
| DWD 侧 | **同一 SELECT 内两套口径**：`event_time` 走解析式 `FROM_UTC_TIMESTAMP(FROM_UNIXTIME(UNIX_TIMESTAMP(...)), 'Asia/Shanghai')`，而 `event_date`/`event_hour` 仍走**原始字符串位置切片** | `DwdSql.scala:34, 35, 36`。已核对 `git show HEAD:` ⇒ 该不一致**早于本泳道**（非新引入） |
| 既有「多格式」实现 | `Cleaners.normalizeTime`：ISO 带偏移走 `OffsetDateTime.parse`，否则 `yyyy-MM-dd HH:mm:ss` 走 `LocalDateTime.parse`，**输出 `yyyy-MM-dd HH:mm:ss`（丢掉偏移、不做 UTC 换算）** | `Cleaners.scala:16-31`；其测试断言 `"2026-09-01T10:15:31+08:00" -> Some("2026-09-01 10:15:31")`（`FunnelHeatAnomalySpec.scala:56-58`） |
| 该实现是否在用 | **生产调用 0**（4 命中 = 1 定义 + 3 测试断言） | token 扫描 `normalizeTime` |
| 真实语料形态 | 349 个 `.jsonl` / 1,215,989,873 B 全量扫描，**2,622,616** 个 `event_time` 取值 **100% 为 `yyyy-MM-ddTHH:mm:ss+08:00`**；其它 10 类形态**全 0** | `raw/scan-event-time-shapes-20260912.txt`；阳性对照 54/54 命中，阴性对照 0 |
| 格式列表载体 | `timePolicy.formats`（设计 §4.2 L138-141），**只被检查键是否存在**，内部完全不校验 | `SourceProfileValidator.java:20-22, 35-44` |
| 格式列表消费者 | **没有任何生产代码读取 `timePolicy`**（8 命中：1 键名 + 7 测试／夹具） | token 扫描 `timePolicy` |
| 真实源画像 | `mock-mall.v1.json` **不存在**（`source_registry.profile_path` 指向它），是 **P3-01 交付物**；种子源因此**不可激活** | 真库只读（R4.2）+ 目录枚举 + `source-profiles/README.md:17,21-25` |
| 时区可写点 | **4 个**：`source_registry.timezone`(varchar64)、`runtime_profile.timezone`(varchar32)、`SparkSessionFactory.scala:14` 写死 `Asia/Shanghai`、`RuntimeProfileServiceImpl.java:72` 兜底 `Asia/Shanghai` | 真库只读（R4.1/R4.2）+ 源码 |

### 2.2 支持哪些输入格式（**待裁决 D-102**）

**今天没有任何权威定义。** 可核实的只有两件事：① 载体的键名是 `timePolicy.formats`（数组）；② 夹具里出现过 3 个取值，且**混用两种词汇体系**：

```json
"formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"]
```

- `ISO_OFFSET_DATE_TIME`、`EPOCH_MILLIS` 是 **Java 风格 token**；
- `yyyy-MM-dd HH:mm:ss` 是 **`DateTimeFormatter` pattern 字符串**；
- 数组**有序**，但设计书与 plan 都**没有定义顺序语义**（plan 只说「依次解析」，没说"首个成功即用"，也没说失败后的行为）。

**本草案建议的词法（供裁决，非认定）**：

1. `formats` = **有序数组**，元素是「受控 token」**或**「显式 pattern 字符串」。
2. 受控 token（闭集，建议先定 6 个）：

| token | 语义 | 是否自带绝对时刻 |
|-------|------|-----------------|
| `ISO_OFFSET_DATE_TIME` | `yyyy-MM-ddTHH:mm:ss[.f{1,9}](+HH:MM 或 Z)` | **是**（自带偏移） |
| `ISO_INSTANT` | 仅 `Z` 结尾 | **是** |
| `EPOCH_MILLIS` | 整数毫秒（10 或 13 位） | **是**（绝对时刻，UTC 语义） |
| `EPOCH_SECONDS` | 整数秒 | **是** |
| `SQL_DATETIME` | `yyyy-MM-dd HH:mm:ss[.f]`（无偏移） | **否**（需源时区） |
| `SQL_DATE` | `yyyy-MM-dd`（无时间部分） | **否**（需源时区，且**日内时刻未定** ⇒ 建议始终判为「无法判定」，见 §2.4） |

3. **顺序语义**：严格**从左到右**尝试，**首个成功即用**并记录命中下标；**不做"最宽松者优先"的隐式重排**。
4. **闭合性**：未列入 `formats` 的形态**必须**落到 quarantine，**不得**回退到「尽力解析」或 Spark 自动推断（plan L155 明令「不可依赖 Spark 自动推断」）。
5. **`formats` 非空**：为空或缺失 ⇒ 该源**不可用于解析**，属配置错误，应**阻断**而不是逐行 quarantine（否则会静默产生全量隔离）。

**必须由裁决回答的三个问题**（本草案不自行认定）：

- Q-a：`formats` 是否允许**自由 pattern 字符串**（`DateTimeFormatter` 语法）？允许则需定义**非法 pattern 的失败态**（阻断还是 quarantine）。**建议**：P2-02 只接受受控 token（闭集），自由 pattern 延后。
- Q-b：`formats` 的**首项是否必须是自带绝对时刻的项**？**建议**：是。否则「时区缺失」将无法在**配置期**发现，只能逐行失败。
- Q-c：`formats` 的顺序与内容属于**契约**（需版本化 + 评审）还是**运行时配置**（可改而不留痕）？**建议**：属契约级（进 git，改动 = 一次提交 = 接入证据），与设计 §4.2 L149「画像文件受版本化与评审」一致。

### 2.3 时区口径（**待裁决 D-101 / D-103**）

**冲突本体**：plan L156 要求「最终统一 UTC」，而契约与文档的业务口径是 `Asia/Shanghai`（`event-contract.md:25`），且 `event_time` 的 pattern **接受任意偏移**（`+05:30` 也合法）。

**三种可选的解释**（各有代价，必须择一裁决）：

| 方案 | 含义 | 代价 |
|------|------|------|
| P-A | `event_time` **就地改写为 UTC 字符串**（`...Z` 或 `+00:00`） | 改变既有 2,622,616 行的**可见取值**；下游所有以「源本地时间」为直觉的读数（含既有 55/1000 夹具期望）都要重算；`dt`/`hour` 若仍按源业务日则必须另存原值 ⇒ 信息丢失风险 |
| P-B | `event_time` **原样保留**（源给出的规范字符串），**追加** `event_time_utc` 派生列承载 UTC | 满足 D-053／D-058（只加不改）；但"统一 UTC"落在**新列**上，需要裁决**哪一列是权威时刻** |
| P-C | 不新增列，**只在解析期校验**并记录命中的偏移，不做任何换算 | 最省；但**不满足** plan L156 的「统一 UTC」字面要求 |

**本草案建议 P-B**，理由：唯一能同时满足 D-053（`STRING` 不变）、D-058（只加不改）、D-059（追加在末尾）与 plan L156（统一 UTC）的方案。

**无论选哪个，下面三条必须一并裁决**：

1. **`dt` / `hour` 分区的口径**（**D-103**）：按**源时区业务日**，还是按 **UTC 日**？
   - 现状是**位置切片**，等价于"源字符串前 10 位"；在今天全 `+08:00` 的语料下等于源业务日。
   - 若选 UTC 日：`2026-08-31T17:00:00Z` 会落到 `dt=20260831`，而其在 `Asia/Shanghai` 是 `2026-09-01 01:00` ⇒ **跨日错位**，既有报表与对账口径全部位移。
   - **本草案建议：`dt`/`hour` 保持"源时区业务日"**，并把 UTC 时刻放在派生列里；这样既有读数不变，同时获得绝对时刻。
2. **源时区的唯一 owner**（**D-103**）：4 个可写点中选 1 个为权威。
   - **本草案建议**：`source_registry.timezone` 为**唯一权威**（它随源登记走、是契约化实体）；`runtime_profile.timezone` 降级为运行环境默认值；**禁止**再用 `SparkSessionFactory` 的写死值参与**业务语义**（它只应影响 Spark 内部类型转换）。
   - 并且：源时区必须与 `source_system` **同一注入通道**（D-056 的 `--extra`），否则会出现「源标识正确但时区缺失」的错配（**D-109**）。
3. **无偏移格式的解释基准**：命中 `SQL_DATETIME` 时用哪个时区解释？
   - **本草案建议**：用该源的 `source_registry.timezone`；**源时区为空/未知时不得兜底**，直接判为「无法判定」并入 quarantine（见 §2.4）。

### 2.4 「解析失败」与「无法判定」的区别（本规格的核心贡献）

现状把一切都叫「解析失败」（`event-contract.md:25`），但**这两类失败的成因、后果、修复动作完全不同**，混在一起会让运维看到"时间坏了"却不知道是数据脏还是配置缺。

| 类别 | 代号 | 判据（必须可机械判定） | 成因 | 修复动作 | 处置 |
|------|------|----------------------|------|---------|------|
| **解析失败** | `TIME_PARSE_FAILED` | 值非空，且 `formats` 中**每一项都尝试失败** | 数据脏（乱码、截断、非法日期、越界偏移） | 改数据／补格式 | quarantine |
| **无法判定** | `TIME_ZONE_UNDETERMINED` | 值**成功命中**了一个**不自带绝对时刻**的格式（`SQL_DATETIME`/`SQL_DATE`），而该源的 `source_registry.timezone` 为空、缺失或非法 | **配置缺**（不是数据脏） | 补源时区 | quarantine（且**必须与上一类分开计数**，否则会长期掩盖配置缺失） |
| **格式不在白名单** | `TIME_FORMAT_NOT_ALLOWED` | 值可被某个**未列入 `formats` 的**已知 token 解析 | 源改了格式但画像没更新 | 更新画像 | quarantine（**建议**单列，便于区分"脏数据"与"画像滞后"） |
| **字段缺失** | `MISSING_REQUIRED_FIELD` | `event_time` 为空或缺失 | 数据／上游 | — | **今天**：采集侧已隔离（实测 21 行 `缺失必要字段: event_id` 同型）；**ODS 装载侧**：`OdsLoadSql.scala:181` **静默丢弃** |
| **语义非法但词法命中** | 归 `TIME_PARSE_FAILED` | pattern 命中但语义不成立（如 `2026-02-30T10:00:00+08:00`、偏移 `+25:00`、`24:00:01` 在严格模式下） | 数据脏 | 改数据 | quarantine |
| **语法合法但业务可疑** | **不属 P2-02** | 如 `event_time` 远晚于 `ingest_time`（未来时间） | — | — | 属 DWD 清洗语义，`FUTURE_TIME`（见 D-107） |

**三条必须写进实现的判据细则**：

1. **「无法判定」不得用 session timezone 兜底。** 用 `spark.sql.session.timeZone`（今天写死 `Asia/Shanghai`）解释无偏移值是**把猜测写进数据**。写死值与"该源的时区"在语义上不是一回事。
2. **词法命中 ≠ 解析成功。** 契约 pattern 是**词法**的（正则），`2026-02-30T10:00:00+08:00` 能通过正则但不是合法时刻。实现必须走**语义解析**（`DateTimeFormatter` 严格模式）而不是只跑正则。
3. **`SQL_DATE`（只有日期）建议恒判「无法判定」。** 日期能解析出"哪一天"，但**定不出日内时刻**；静默补 `00:00:00` 等于发明数据（违反 A 类硬约束「不得发明证据」）。

### 2.5 `event_time` 与新增派生列的唯一 owner（必答项）

| 对象 | **唯一 owner** | 现状 | 本规格要求 |
|------|---------------|------|-----------|
| `event_time` **列的存在与类型** | `spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsV2Columns.scala`（`Column("event_time","STRING")` L42；`CommonColumns` L39-53；物理列序由 `dataColumns` 决定） | 已是唯一 owner | 保持唯一；**不得**在 DDL 与 Scala 两处各写一份而漂移（现状 `00-ods.sql:21/48/80/107` 与 L42 是一致的手工镜像 ⇒ 建议把「一致性」列为一条可测守卫） |
| `event_time` 的**写入表达式** | `OdsLoadSql.envelopeSelect`（L90 直通） | 已是唯一写入点 | 保持唯一 |
| **新增派生时间列**（如 `event_time_utc`、`time_format_hit`） | **列集合**归 `OdsV2Columns.scala`；**派生表达式**归**单一函数**（建议是 P2-02 新建的唯一解析模块，`OdsLoadSql` 只调用它，不内联表达式） | **今天不存在任何派生时间列**（`CommonColumns` 13 项、`V2NewColumns` 5 项、`dataColumns` 内均无时间派生物） | 必须新增前先裁决（D-101）；新增后**禁止**第二处再写同一派生 |
| `dt` / `hour` 的**派生表达式** | `OdsLoadSql.scala:168-169` | 位置切片 | 若改为解析式，**唯一**在此处改；**禁止** DWD/ADS 再各写一套 |
| DWD 的 `event_time` / `event_date` / `event_hour` | `DwdSql.behaviorClean`（L34-36） | **同表两套口径**（L34 解析式、L35-36 位置切片） | 列为施工项**消除**（不得让"新口径在 ODS、旧口径在 DWD"长期并存） |
| 源时区 | 见 §2.3 建议：`source_registry.timezone` | 4 个可写点 | 收敛为 1 个权威 + 明确其余角色的**降级**定义 |

> **为什么强调这一点**：本项目的既有故障模式就是「同一语义两个 owner」（`IdCodec` 与 `SurrogateKey` 并存问题即属此类，见 D-083…D-092 的处置）。时间语义今天已经有**至少三处**独立实现（`Cleaners.normalizeTime`、`OdsLoadSql` 位置切片、`DwdSql` 解析式），这正是 P2-02 要收敛的对象。

---

## §3 quarantine 原因码

### 3.1 现状与三个通道的边界

**四个彼此不同的通道**（今天都已存在，混用会让"隔离"失去意义）：

| 通道 | 存储 | 粒度 | 载荷 | 今天的内容 | 是否阻断 |
|------|------|------|------|-----------|---------|
| `analytics_meta.quarantine_record` | MySQL | **行级** | `reason VARCHAR(255)` **自由文本** | 108 行 / 9 类原因 / **0 条时间相关** | 不阻断（仅隔离该行） |
| `analytics_meta.data_quality_result` | MySQL | **批次级规则** | `rule_code` + `severity` + `check_count`/`error_count`/`error_rate`/`threshold`/`passed` | 445 行 / 15+ 个 `rule_code` / **0 条时间相关**；BLOCKING 会阻断发布 | **BLOCKING 阻断，WARN/INFO 不阻断** |
| `<prefix>_dwd.dwd_reject_record` | Hive（DWD） | **清洗阶段行级** | `reject_reason STRING`，DDL 注释声明 5 值枚举 | **只写 `DUPLICATE_EVENT`**；`FUTURE_TIME` 已声明未实现 | 不阻断 |
| ODS 装载的 `WHERE` 过滤 | Hive（ODS） | **静默丢弃** | 无 | `OdsLoadSql.scala:181` 丢 `event_id`/`event_time` 空行，**不留任何痕迹** | — |

**现有的「原因」不是原因码，是自由文本**，且**同一语义有多种写法**（实测 9 类里 `未支持 schema_version: 2.0` 与 `未支持 schema_version: 9.9` 是同一原因的不同取值）。可机械归并的原因只有 **7 类**（见 §3.2）。

**本规格的边界主张（建议，待裁决 D-105）**：

1. **行级 → quarantine；批次级比率／计数 → `data_quality_result`。** 这是唯一清晰的判据。
2. **`dwd_reject_record` 继续只承载「DWD 清洗阶段」的拒绝**（重复事件、非法枚举、金额异常、未来时间），**不与 landing 解析混用**。
3. **`OdsLoadSql.scala:181` 的静默丢弃必须消除**：要么改为进入 quarantine（留痕迹），要么改为进入「拒绝记录」。**建议**改为 quarantine，因为它发生在解析环节，且与 plan L157「缺 eventId 进入 quarantine」字面一致。**但这是行为变更，需裁决**（D-104）。
4. 死 SQL `rejectedSelect()`（`OdsLoadSql.scala:269-276`，字面量 `BAD_VERSION_OR_KEY`，**无生产调用**）**必须择一处置**：接线（并改用原因码枚举）或删除。**今天的第三种状态——留在代码里不接线——是最坏的**（读者会以为它生效）。

### 3.2 原因码枚举

**既有 9 类自由文本 → 7 个原因码**（仅**映射**，不改写历史数据）：

| 原因码 | 判据 | 既有文本（原样，仅作映射依据） | 实测条数 |
|--------|------|------------------------------|---------|
| `JSON_PARSE_ERROR` | 行不是合法 JSON | `JSON 解析失败: <Jackson 原文>` | 27 |
| `PAYLOAD_NOT_OBJECT` | 顶层非对象，或 `payload` 缺失/非对象 | `payload 缺失或非对象`、`非 JSON 对象` | 0（未观测到） |
| `MISSING_REQUIRED_FIELD` | 信封 7 个必填字段任一为空 | `缺失必要字段: event_id` | 21 |
| `MISSING_PAYLOAD_FIELD` | 按事件类型的 payload 必填字段缺失 | `payload 缺失必要字段: session_id` | 4 |
| `UNKNOWN_EVENT_TYPE` | `event_type` 不在 12 类内 | `未知事件类型: <v>` | 0（未观测到） |
| `UNSUPPORTED_SCHEMA_VERSION` | `schema_version` 不在受支持集内 | `未支持 schema_version: 2.0` / `9.9` / `1.1` | 31 |
| `BAD_BEHAVIOR_ENUM` | 行为事件 `behavior_type` 不在 5 值白名单 | `非法 behavior_type: purchase` / `add_cart` | 25 |
| `BAD_AMOUNT_FORMAT` | 金额不匹配 `^\d+(\.\d{1,2})?$` | `金额格式违规: <v>` | 0（未观测到） |

（实测 108 = 27+21+4+31+25，另有 0 条落在未观测到的 3 类。）

**P2-02 新增（建议，待裁决 D-106）**：

| 原因码 | 判据 | 触发点 | 证据形态 |
|--------|------|--------|---------|
| `TIME_PARSE_FAILED` | 值非空且 `formats` 全项失败（含词法命中但语义非法） | landing 解析 | 负例夹具逐条命中 |
| `TIME_ZONE_UNDETERMINED` | 命中的格式不自带绝对时刻，且源时区为空/缺失/非法 | landing 解析 | 构造源时区为空的负例 |
| `TIME_FORMAT_NOT_ALLOWED` | 可被已知但未列入 `formats` 的形态解析 | landing 解析 | 负例夹具（**可选**，D-106 中标注为可合并项） |
| `MISSING_EVENT_ID` | `event_id` 为空或缺失（**专属码**，与 `MISSING_REQUIRED_FIELD` 并存需裁决） | landing 解析 | 既有 21 行同型；负例夹具 |
| `TIME_AMBIGUOUS_LOCAL` | 无偏移值落在 DST 重叠区间（同一本地时刻对应两个绝对时刻） | landing 解析 | **仅当源时区有 DST 时才可能发生**；今天 `Asia/Shanghai` 无 DST ⇒ 建议**先不定义**，登记为未来项 |

**`MISSING_EVENT_ID` 的取舍**（**D-106 的子问题**）：plan L157 把「缺 eventId」与「未知版本／无法解析时间」并列为三类隔离原因，**暗示**它应是一个独立原因码；但既有 `MISSING_REQUIRED_FIELD`（带字段名变量）已能表达它，且**实测已有 21 行在用**。**建议**：保留 `MISSING_REQUIRED_FIELD` 作为**机器可判**的码，把 `eventId` 作为**结构化字段**单独存（见 §3.4），**不新增** `MISSING_EVENT_ID`，以免同一现象两个码。

### 3.3 稳定性与兼容规则

| 规则 | 内容 |
|------|------|
| R-1 **可加** | 新原因码**可以追加**（additive）；消费方遇到未知码必须按「未知码」降级处理，**不得崩溃、不得当成通过** |
| R-2 **不可改** | 既有原因码的**判据与语义不得改变**；不得删除；不得把旧码复用成新语义 |
| R-3 **不可回填** | 既有 108 行的 `reason` 自由文本**不得就地改写**。若要提供码，只能**新增列**并允许历史行为 `NULL`（「未知/历史」而非"猜一个"） |
| R-4 **命名** | `UPPER_SNAKE_CASE`、ASCII、无空格；长度 ≤ 64（为新增列留余量，与 `schema_version varchar(16)`、`reason varchar(255)` 的既有尺度相容） |
| R-5 **大小写敏感** | 码为**大小写敏感**的闭集；比较**不得**用 `LOWER()` 归一（否则未来出现 `Bad_Time` 会静默等价） |
| R-6 **唯一 owner** | 枚举的**唯一定义处**只能有一份（见施工单 A4 的落点选择）；**禁止**在 Java 与 Scala 各写一份字面量集合 |
| R-7 **契约级** | 原因码一旦冻结，**新增即 additive、改语义即 breaking** ⇒ 按 `contract-specs/README.md` §3 走 minor／major（由总控执行，本泳道不改契约） |
| R-8 **`reason` 保留** | 自由文本 `reason` **必须继续写**（既有 108 行、Java 写入者、以及人可读排错都依赖它）。原因码是**追加**的结构化维度，**不替代**自由文本 |

### 3.4 原位置（plan L157「保留原因码和原位置」）

「原位置」今天**部分存在**：`quarantine_record.raw_path VARCHAR(500) NOT NULL`（隔离文件路径），但**没有行号**、没有源文件路径、没有字节偏移。

**建议的结构化字段（待裁决 D-108）**：

| 字段 | 含义 | 现状 |
|------|------|------|
| 源文件仓库相对路径 | 该行来自哪个 landing 文件 | 可用 `landing_file`（D-057 已真实化） |
| 行号 或 字节偏移 | 文件内位置 | `Violation.lineNo` **已存在**（`EventContractValidator.java:30`），但当前调用点写死 `check(text, 0)` ⇒ **丢掉了行号**（`LocalFileIngestor.java:136-157` 一带） |
| 原因码 | 见 §3.2 | **不存在** |

> **注意 D-060 的实测事实**：`_metadata.file_path` 非空，而 Spark 的 `input_file_name()` **返回空字符串**。⇒ 取「原位置」时**不得**依赖 `input_file_name()`；应使用 `landing_file`（D-057）或 `_metadata.file_path`。

### 3.5 与 `data_quality_result` 的边界

| 问题 | 答案 |
|------|------|
| 同一现象（如"坏时间"）能否**同时**进两个通道？ | **能，且应该**，但**粒度不同**：每一坏行进 quarantine（行级、可逐一复查）；坏行占比进 DQ（批次级、可阻断发布）。二者**不是重复**，是两个不同的问题：「哪一行坏了」与「这批还能不能发」 |
| quarantine 能否阻断整批？ | **不能**。阻断是 `severity` 的语义，属 `data_quality_result`。让 quarantine 阻断会把两个通道的语义搅在一起 |
| 建议新增的时间类 DQ 规则（**待裁决 D-108**） | ① `TIME_PARSE_FAILED_RATE`（ERROR，记录不阻断）② `LATE_EVENT_RATE`（WARN），对应指导书 §7.3 规则 #11 的「`ingest_time - event_time` P95 与迟到率」——该规则**隐含要求时间可解析**，是 P2-02 的**外部参照物** |
| 既有 `rule_code` 是否要动？ | **不动**。新增规则是 additive；且新增规则必须遵守指导书 §7.3 L480「每条 BLOCKING 规则须有失败负向验收」 |

---

## §4 可度量出口判据

每条判据给：**表达式**（可外部复算）+ **外部参照物**（不依赖本实现的独立真值）+ **证据文件名**。

| 编号 | 判据 | 表达式（示意，施工时按实际列名落实） | 外部参照物 | 证据文件名 |
|------|------|-----------------------------------|-----------|-----------|
| C1 | **多格式接受**：`formats` 中每个 token 各 ≥1 行构造样本，归一结果正确 | 对每个 token，构造 1 行；断言 ODS 中该 `event_id` 存在，且归一后时刻 = 手算期望值（逐 token 给出期望常量） | 手算期望值由**夹具生成时刻**独立给出（不读实现输出） | `evidence/C1-multiformat-accept.md` + `raw/C1-*.txt` |
| C2 | **坏时间负例（逐码）**：每个新增原因码 ≥1 行构造样本，落在 quarantine 且码命中 | 断言 quarantine 中存在该 `event_id`，且码 = 期望码；**且** ODS 中该 `event_id` **不存在** | 夹具中逐行标注的期望码 | `evidence/C2-badtime-negatives.md` |
| C3 | **未知版本负例**（看板明列） | 构造 `schema_version` 不在受支持集的行 ⇒ 隔离且码 = `UNSUPPORTED_SCHEMA_VERSION` | 既有 31 行实测同型（真库） | `evidence/C3-version-negatives.md` |
| C4 | **缺 eventId 负例** | 构造 `event_id` 缺失行 ⇒ 隔离（**不得**静默丢弃、**不得**静默通过） | plan L157；既有 21 行实测同型 | `evidence/C4-missing-id.md` |
| C5 | **零误伤守卫**（最强的一条） | 对**真实语料**（349 文件 / 1,215,989,873 B / 2,622,616 取值）跑一次，断言 `TIME_PARSE_FAILED = 0` 且 `TIME_ZONE_UNDETERMINED = 0` | **今天的全量扫描实测：100% 合法**（`raw/scan-event-time-shapes-20260912.txt`）⇒ 任一新失败计数 > 0 即证明实现引入误判 | `evidence/C5-no-regression.md` |
| C6 | **契约一致**：归一后 `event_time` 仍命中 `iso8601_time` pattern | 用契约自带的 pattern 逐行校验，断言 0 违规 | `canonical-event.v1.schema.json:323-327` 的 pattern **逐字** | `evidence/C6-contract-conformance.md` |
| C7 | **既有链路读数不变** | 重跑 golden-55 与 gen-s3b-1000 本地链，与 P2-01 冻结读数逐值比对 | `docs/acceptance/p2-01-ods-v2-20260912/README.md`（`FILES=2006`、`SHA256=3c7e3dea…` 的 `spark-warehouse` 口径） | `evidence/C7-baseline-unchanged.md` |
| C8 | **原因码可复算** | 由 quarantine 表按码分组，与夹具期望逐项比对 | 夹具期望表 | `evidence/C8-reasoncode-recompute.md` |
| C9 | **多格式解析向量由总控复算**（沿用 D-088 的做法） | 提交一组 `(输入串, 源时区, 期望归一值, 期望码)` 向量，总控**独立**复算 | 向量表本身（不含实现输出） | `evidence/C9-time-vectors.md` |

**判据的元要求（避免"自证"）**：

1. C1/C2/C3/C4/C9 的**期望值必须来自夹具标注或契约**，**不得**来自实现输出（否则是自证）。
2. C5 是**唯一**使用真实数据的判据，且它是**反向**的（断言"没有失败"），因此天然抗自证。
3. 所有计数类断言必须给出**口径**（WHERE 子句原文）与**读数时点**。
4. **Mock 不算真**：凡涉及"支持某格式"的结论，必须指明是**构造夹具**；真实语料里多格式**零发生**（C5 已证），任何"真实数据已验证多格式"的说法都是**不成立的**。

---

## §5 相容性核对表（对既有裁决）

| 裁决 | 主题 | 判定 | 理由 |
|------|------|------|------|
| D-052 | 12 个 ODS 公共列 | **相容** | 本规格不新增公共列；原因码若落库只落 MySQL `quarantine_record`，不进 ODS 公共列 |
| **D-053** | `event_time` 保持 `STRING` | **冲突（需裁决 D-101）** | plan L156 要求「最终统一 UTC」；若解释为"就地改成 TIMESTAMP 或改写为 UTC 字符串"则违反 D-053／D-058。**建议**按 P-B（原列不动 + 追加派生列）解释 ⇒ 相容，但**必须裁决**，本草案不自行认定 |
| D-054 | 双写、`payload_json` 唯一 owner、DWD 读源开关归 P2-04 | **相容** | 本规格不碰 `payload_json`；时间派生不改 DWD 读源开关（开关变更属 P2-04） |
| D-055 | 业务键属主 = 契约；P2-01 不改 DWD 去重键；混源守卫 | **相容** | 时间不涉及业务键；本规格明确 `event_id` 语义与去重键不动。混源守卫与「源时区按源取值」同向 |
| D-056 | `source_system` 由 `spark-submit --extra` 注入 | **相容但需补一条（D-109）** | 源时区必须与 `source_system` **同通道**注入，否则出现「源标识正确而时区缺失」的错配，且该错配会被误报成 `TIME_ZONE_UNDETERMINED`（掩盖真实成因） |
| D-057 | 新增 `landing_file`；`source_file` 变真值 | **相容（且被依赖）** | 「原位置」的源文件路径建议直接用 `landing_file`，不再新造字段 |
| D-058 | 只加不改 | **相容** | 本规格全部建议按加法：追加派生列、追加码、追加结构化字段、追加 DQ 规则 |
| D-059 | 加法列追加在末尾 | **相容** | 新增时间派生物必须追加在 `dataColumns` 末尾；物理列序由 `OdsV2Columns.dataColumns` 决定 |
| D-060 | E3 本地真链隔离规则 + `_metadata.file_path` 非空而 `input_file_name()` 为空 | **相容（且约束证据取法）** | 取「原位置」**不得**依赖 `input_file_name()`（实测为空字符串），须用 `landing_file` 或 `_metadata.file_path` |
| D-061…D-065（CT 批次） | `source_system` de-`const`、`event_id` 去重语义收归源命名空间内等 | **相容** | 与时间无关；且 `source_system` 去 `const` 后，「按源取时区」在结构上才可行 |
| D-083…D-086 | 代理键材料与归一化 | **无关** | 属 P2-03 |
| **D-087** | 空/缺失 id ⇒ NULL 键 + 记 DQ + **不丢行**；**不进 quarantine** | **相容，但边界必须写死** | 空 **id** 的处置是「记 DQ + 不丢行」；坏**时间**的处置是「进 quarantine」。**两者不得互相套用**：不得因为"坏时间也应保留原行"就不隔离，也不得因为"id 为空"就隔离整行 |
| D-088 | 向量由总控独立复算 | **相容（并被沿用）** | C9 沿用该做法：时间解析向量交总控复算 |
| D-089 | 算法名收敛为 `HASH64` | **无关** | 属 P2-03 |
| **D-090** | `event_id` / `behavior_id` 唯一 owner = `DwdSql.scala`（原引用 L31） | **相容但引用需重取** | 本规格不改 `event_id`。**注意**：`DwdSql.scala` 正被 P2-03 改写（读时 sha256 `A38EC916…`，71 净行），**原 L31 已漂移** ⇒ 实施前必须按 D-091 重取行号与指纹 |
| **D-091** | P2-01 先；**两条泳道不得并发写 `spark-jobs`**；取 HEAD 基线；实施前重取行号/指纹 | **相容（且直接约束本泳道施工单）** | 施工单 §2 已把「不得与 P2-03 并发写 `spark-jobs/**`」写成硬约束；且已登记 HEAD 从 `3dfe94e` 漂到 `ce36065` |
| D-092 | 复用既有 guard／ledger；停止条件含改契约 `rule` 主体、改既有 CLI 入参名、重启 8090/8091/8092 或 DB DDL/DML、泳道外文件消失 | **相容（且构成停止条件）** | 施工单 A 项**不得**触碰这些。**其中「DB DDL」直接影响 §3.2 的新增列**：`reason_code` 若要落库需 Flyway 迁移 ⇒ 属停止条件 ⇒ **必须总控放行**（施工单 A4 已标注） |
| D-095 | P2-03 口径更正为「只加不改」+ 11 文件范围白名单 | **相容** | 与 D-058 同向；时序上 P2-02 应在 P2-03 之后或按其白名单避让 |

**冲突项汇总**：**只有 D-053 一处真冲突**（且可用 P-B 化解）；D-056／D-090／D-092 属"相容但需补条件"。其余全部相容或无关。

---

## §6 显式非目标（Non-goals）

1. **不做 DWD 投影**（属 P2-04）。本规格只到 landing 解析与 ODS 落位。
2. **不做代理键**（属 P2-03）；不碰 `SurrogateKey`／`Hash64`／实体枚举。
3. **不实现 `FUTURE_TIME` 检测**（"未来时间"属 DWD 清洗语义，见 D-107）。
4. **不新增 ODS 信封列**（D-052 的 12 列不动）；新增派生列须先裁决（D-101）并追加在末尾（D-059）。
5. **不改契约 schema 正文、不改契约版本号**——若判据需要契约化（如 `formats` 语法进契约），走 CT 批次 + 版本 bump，**由总控执行**；本泳道**不得**改 `contract-specs/**`。
6. **不改写既有 108 行 quarantine 历史文本、不回填原因码**（R-3）。
7. **不改 `EventContractValidator` 既有自由文本措辞**（它们已被 108 行历史与测试依赖）。
8. **不追求"支持所有可能的格式"**：只支持 `formats` 显式列出的；**不做时区自动探测**、不做"尽力解析"、不依赖 Spark 自动推断（plan L155）。
9. **不做集群档（T4）验收**；不做 E4 集群链路。
10. **不重启 8090／8091／8092，不做 DB DDL/DML**（D-092 停止条件）。
11. **不修改看板**（本泳道未改；行 221 的状态更新归总控）。
12. **不在 `spark-jobs/**` 落任何改动**——在 P2-03 完成或总控裁决之前（D-091）。

---

## §7 未测 / 证据不足（本草案中不得当作已测的部分）

| 编号 | 项 | 影响 |
|------|----|------|
| U1 | `UNIX_TIMESTAMP('2026-09-01T09:00:00+08:00')` 在 Spark 3 的真实返回值 | 决定 `DwdSql.scala:34` 是否**已经**在产 NULL；**未测 ⇒ 全部相关表述均为"未测"** |
| U2 | 非 `+08:00` 形态进入 ODS 后 `dt`/`hour` 的实际错分区结果 | §2.3 的跨日错位是**从位置切片代码推出**的风险，**未实测** |
| U3 | 源 A 的真实 `timePolicy.formats` | 画像文件不存在（P3-01）⇒ 只能由裁决给定 |
| U4 | 多格式构造夹具的落点与可复用基座 | 未做 `tests/fixtures/**` 盘点 ⇒ 施工单列 A7 前置 |
| U5 | `dwd_reject_record` 历史分区内是否真出现过 `FUTURE_TIME` | 未读实盘 parquet ⇒ 只到「源码从不写」 |
| U6 | 采集侧日志里是否已有时间类失败 | 只扫了 DB 两张表 ⇒ 「今天无时间类故障」的证据**不覆盖日志** |
| U7 | `spark.sql.session.timeZone` 对 ODS 装载的实际影响面 | 列为「候选 owner」，**不是**已生效 owner |
| U8 | 归一后 `event_time` 端到端是否仍命中 `iso8601_time` | 无实现 ⇒ 列为 C6，当前未测 |
| U9 | `formats` 顺序语义在设计书／契约里是否有更早的权威定义 | 已扫描 `docs/superpowers/**` 与 `contract-specs/**`（`多格式` 仅 3 处看板命中）；**若总控另有出处，请覆盖本草案** |

**取证边界声明**：本草案**未运行任何测试、未构建、未提交**；所有「实现现状」均来自静态只读（源码 + DDL + 契约 + 真库只读查询）。所有读数见 `RAW-READINGS.md` 与 `raw/`（含 sha256 与对照）。
