# INVENTORY.md — P3-02 映射执行器 · 只读规格盘点

> 分支 `remediation/r1-boundary`。本轮**只读盘点 ＋ 规格判定**，未改任何生产/测试代码。
> 权威顺序（D-091）：① 冻结契约 `contract-specs/**`、`docs/contracts/**` → ② 指导书 `docs/项目完整实施指导书 V2.4.md` → ③ 看板 V2.2 → ④ 专项设计 → ⑤ 历史文档。
> 所有 `file:line` 均指**当前工作树**版本；每条均给出**原文**。
> 原始命令输出：`raw/static-contrast-inventory.txt`。

---

## 0. 一句话盘点结论

**「字段映射执行」的语义在规格里是「半定义」的**：
- **流水线层（顺序／白名单／原因码名／dry-run 计数）在计划书 `:196-199` 与 `:225` 有逐字定义**；
- **键级语义（9 个顶层键的方向与哨兵）在设计书 §4.2 有散文定义**；
- **但「把一行原始 JSON 变成规范事件」所需的执行语法（映射表取值形状／空缺判据／类型与单位／时间格式优先级与歧义处置／歧义态／canonical 骨架 owner／拒绝原因码对字段级失败的覆盖）全部未定义或自相矛盾** —— 详见 `SPEC-CHANGE-DRAFT.md` §2 的 A1–A10。

---

## 1. 源画像的 6 个语义键：确切定义与实际读取面

### 1.1 键的确切定义（权威原文）

| 键 | 定义出处（原文） | 语义 |
|---|---|---|
| `eventTypeMapping` | 设计书 `docs/superpowers/specs/2026-09-11-mall-agnostic-platform-design.md:116` | `// 源事件名 → 规范事件类型` |
| `fieldMapping` | 同 `:122` | `// 源字段 → 规范字段（规范字段是骨架，见 §4.4）` |
| `enumSemantics` | 同 `:129` | `// 源枚举取值 → 平台语义标签（§4.3）` |
| `identityPolicy` | 同 `:133` | `// 身份与主键策略（§4.5）` |
| `timePolicy` | 同 `:138-141` | `{ "field": "created_at", "formats": ["ISO_OFFSET_DATE_TIME", "EPOCH_MILLIS", "yyyy-MM-dd HH:mm:ss"] }` |
| `quarantinePolicy` | 同 `:142` | `{ "unknownEventType": "QUARANTINE", "unknownField": "KEEP_IN_PAYLOAD" }` |

设计书 `:146-149` 的三条**约束**（原文逐字）：

```
- 缺失的映射项 = "该源没有这个语义"（不是默认值），平台按"缺语义"处理而不是猜。
- `"@keep"` 表示字段不映射到骨架，仅保留在 `payload_json`。
- 画像文件受**版本化与评审**（进 git）；接入新商城的那次提交 = 新增一个 JSON，`git diff` 即接入证据。
```

`enumSemantics` 的 **`null` 第三态**由设计书**附录 A**（append-only，`:305-311`）定义，原文：

> `enumSemantics` 中形如 `"<源取值>": null` 的映射项，语义为**「该取值已被实测观测到，但平台尚未裁定其规范语义（待裁定）」**；
> 它与 §4.2 L147 的**省略键**（＝「该源没有这个语义」）是**两种不同陈述**，实现方**不得**把二者归一；
> 出现 `null` 的漂移族一律：(a) 不参与 ADS 口径的规范化，(b) 须登记为开放裁定项（本源即 Q4），(c) 在数据质量侧可被统计但不进 quarantine。

### 1.2 执行层规格（**本轮盘点最重要的发现**）

`docs/superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md` —— 设计书 `:4` 声明的**设计依据下游实施书**，其 `:194-199` 有 P3-02 的**逐字规格**：

```
### P3-02 EventNormalizer

- 顺序固定：解析信封→校验来源→事件类型映射→字段提取→枚举映射→时间/金额转换→canonical 校验→写 accepted/quarantine。
- 允许转换白名单：trim、case、decimal scale、epoch/timezone、受控 enum map、JSONPath 读取。
- 禁止脚本、反射、任意 SQL、网络调用。
- 失败原因码分别表示 UNKNOWN_EVENT_TYPE、FIELD_REQUIRED、ENUM_UNKNOWN、TIME_PARSE、AMOUNT_PARSE、PROFILE_VERSION。
```

同书 `:225`（P3-06 验收项）：

```
- profile dry-run 输出 input/accepted/quarantine/reason counts/field coverage/enum coverage。
```

指导书 V2.4 `:346-350`（§5.4 标准化与映射）：

```
采集只负责可靠移动和断点；`EventNormalizer` 负责源事件到 canonical event 的转换：事件类型映射、JSONPath 字段提取、枚举映射、时区转换、金额单位转换和允许的纯函数变换。禁止在映射表达式中执行任意脚本或 SQL。

未知源版本、没有 ACTIVE mapping、必填字段缺失和不允许的转换全部进入 quarantine，并保存 source instance、mapping version、reason code 和原始位置。映射激活必须先用样本 dry-run，并输出接受/隔离/错误计数。

映射的最终真相仍是版本化配置和审核记录，不是大模型对话。…
```

指导书 V2.4 `:348` 与计划书 `:199` 的**六类原因码**，再看板 `:229` 的 P3-02 验收口径「dry-run 覆盖率与**六类原因码**」——**三处一致，数目对得上**。

> ⇒ **「映射执行」这件事本身已被规格授权**：顺序、白名单、六类原因码、dry-run 计数口径都有属主。
> ⇒ 但**六类原因码里没有「字段类型不符」「金额单位转换」「空值 vs 缺失」「时间格式歧义/不在白名单」的位置**；这些恰是 P3-02 执行器必须裁决的判据（见 `SPEC-CHANGE-DRAFT.md` A5/A6/A8/A10）。

### 1.3 生产代码读取面：**为零**（与 F-89 一致，本轮复核）

`raw/static-contrast-inventory.txt` §[2]/§[3]/§[5] 的实测结果（检索范围 `*.java` / `*.scala`）：

| 检索项 | 生产代码（`src/main`）命中 | 说明 |
|---|---|---|
| `fieldMapping` | `SourceProfileValidator.java:40` **唯一 1 处** | 仅出现在 `REQUIRED_TOP_LEVEL_KEYS` 键名清单里 |
| `enumSemantics` | `SourceProfileValidator.java:41` **唯一 1 处** | 同上 |
| `identityPolicy` | `SourceProfileValidator.java:42` **唯一 1 处** | 同上 |
| `eventTypeMapping` | `SourceProfileValidator.java:39` **唯一 1 处** | 同上 |
| `timePolicy` | `SourceProfileValidator.java:43` **唯一 1 处** | 同上 |
| `quarantinePolicy` | `SourceProfileValidator.java:44` **唯一 1 处** | 同上 |
| `SourceProfile`（作为 DTO/类型） | **0 处** | 全仓只有 `SourceProfileValidator`（校验器）与其 `ProfileCheck` 记录 |

`SourceProfileValidator.java:35-44` 原文（6 个语义键的**唯一**生产命中点，全部只是字符串常量）：

```java
    public static final List<String> REQUIRED_TOP_LEVEL_KEYS = List.of(
            "profileVersion",
            "sourceCode",
            "canonical",
            "eventTypeMapping",
            "fieldMapping",
            "enumSemantics",
            "identityPolicy",
            "timePolicy",
            "quarantinePolicy");
```

`SourceProfileValidator.java:20-22` 自述其边界为**浅检**（原文）：

```java
 * <p>不做什么（如实登记的边界）：本类**不是**完整画像 Schema 校验器——
 * 每个键内部的子结构（{@code eventTypeMapping} 的枚举闭集、{@code identityPolicy} 的字段形状等）
 * 归 P3-01。本类只钉 P1-03 能钉的最小口径，键名逐字取自设计 §4.2 第 112–143 行，不自创、不缩写。</p>
```

**唯一的非校验器命中**是 `spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala:54`，且只是**注释**：

```scala
  /** 契约算法名（源画像 `identityPolicy.*.surrogate` 唯一允许值，D-089） */
```

> ⇒ 「画像 6 个语义键在生产代码读取面为零」**本轮独立复核成立**（与 F-89 §1 一致）。

### 1.4 缺失旋钮的实测（旋钮名全仓计数）

| 旋钮名 | 命中 | 其中生产代码 | 结论 |
|---|---|---|---|
| `missingField` | **0** | 0 | **不存在** |
| `nullValue`（画像语义） | 0 | 0 | **不存在** |
| `unknownField` | 4（全部在画像 JSON 文本内） | 0 | **有键无读者** |

`raw/static-contrast-inventory.txt` §[1] 另证明 `@keep` 在**生产代码 0 命中**：命中全在设计书 `:127`/`:148`、两份画像 JSON、以及 `src/test`／验收脚本。

---

## 2. `fieldMapping` 现状 schema

**没有机器可读 schema。** 冻结契约 `contract-specs/**` 对 6 个语义键 **0 命中**（`grep` 实测，见 `raw/static-contrast-inventory.txt` 的检索范围与 §[2]）。现状 schema 只有**三份互相不完全一致的散文/实例**：

| 来源 | `fieldMapping` 形状 | 是否权威 |
|---|---|---|
| 设计书 `:122-128` | 扁平 `{源字段: 规范字段}`，含 `"coupon_code": "@keep"` | 设计层权威（④） |
| `source-profiles/p1-03-probe-1.v1.json:11-17` | 5 条：4 条映射 + 1 条 `@keep` | **验收夹具** |
| `source-profiles/mock-mall.v1.json:21-53` | 31 条**恒等**映射 + `"event_time_utc": "@keep"` | **真实源画像**（P3-01-a） |
| `source-profiles/fixture-b.v1.json:23-71` | 41 条异构映射，**含 3 组「同目标多源」**（`status`×2、`quantity`×2、`amount`×2） | 异构源画像 |

**`fixture-b.v1.json` 的同目标组（实测，`SPEC-CHANGE-DRAFT.md` A2 的事实基础）**：

| 规范目标 | 源侧候选 | 出现在画像的行 |
|---|---|---|
| `status` | `sale_state`、`order_state` | `:44`、`:53` |
| `quantity` | `qty`、`hold_qty` | `:48`、`:60` |
| `amount` | `line_amount_fen`、`paid_fen` | `:51`、`:57` |

> 扁平 `fieldMapping` 的 key 是 JSON 对象键 ⇒ **同名键不可能并存**，value 重复**必然**发生。规格**没有任何一句**说重复 value 该怎么办。

**`identityPolicy` 现状形状**：`mock-mall.v1.json:109-115` 五行（`user`/`product`/`category`/`brand`/`order`），每行 `{rawField, shape, surrogate}`；`shape` 实测取值为 `ANY`（`mock-mall`、`fixture-b`）与 `UUID`/`PREFIX_NUMERIC`（`p1-03-probe-1`）。取值闭集**未在任何冻结契约中定义**（`SourceProfileValidator.java:21-22` 明确把它归 P3-01，而 P3-01 未交付）。

**`timePolicy` 现状形状**：`{field, formats[]}`。**`field` 的语义在两份画像里互相矛盾**（见 `SPEC-CHANGE-DRAFT.md` A4）：

| 画像 | `timePolicy.field` 取值 | 该值属于 |
|---|---|---|
| `p1-03-probe-1.v1.json:28` | `"created_at"` | **源侧**字段名（其 `fieldMapping.created_at → event_time`） |
| `fixture-b.v1.json:132` | `"occurred_ms"` | **源侧**字段名（其 `fieldMapping.occurred_ms → event_time`） |
| `mock-mall.v1.json`（`:116-119`） | `"event_time"` | **规范侧**字段名（其 `fieldMapping` 为恒等） |

---

## 3. `EventContractValidator` 的校验路径

**入口签名**（`EventContractValidator.java:35`）——只吃 `ObjectMapper`，**没有任何画像参数**：

```java
    public EventContractValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }
```

**硬编码词表**（`:21-28` 原文）：

```java
    private static final Pattern AMOUNT = Pattern.compile(EventContract.AMOUNT_PATTERN);
    private static final Set<String> KNOWN_TYPES = Set.of(
            EventContract.USER_REGISTERED, EventContract.PRODUCT_CREATED, EventContract.PRODUCT_UPDATED,
            EventContract.BEHAVIOR, EventContract.ORDER_CREATED, EventContract.ORDER_PAID,
            EventContract.ORDER_CANCELLED, EventContract.REFUND_CREATED, EventContract.REFUND_COMPLETED,
            EventContract.STOCK_RESERVED, EventContract.STOCK_RELEASED, EventContract.STOCK_CHANGED);
    private static final Set<String> KNOWN_VERSIONS = Set.of("1.0");
    private static final Set<String> BEHAVIOR_TYPES = Set.of("view", "favorite", "cart_add", "cart_remove", "search");
```

**逐行校验顺序**（`check(String jsonLine, int lineNo)`，`:42-86`）：
1. `:45` JSON 解析（失败 → `JSON 解析失败: …`）
2. `:49-51` 顶层必须是 JSON 对象
3. `:56-61` 信封 7 字段非空：`event_id / event_type / event_time / ingest_time / source_system / schema_version / trace_id`
4. `:62-64` `payload` 必须存在且为对象
5. `:65-67` `KNOWN_TYPES` 白名单
6. `:68-70` `KNOWN_VERSIONS` 白名单
7. `:71-74` 按事件类型的 payload 必填字段（`missingPayloadField`，`:89-111`）
8. `:75-80` `behavior` 事件的 `behavior_type` 白名单
9. `:81-84` 金额格式（`findBadAmount`，`:113-136`）

**已入账的四条闸门缺陷（本轮逐条复核，file:line 有微调）**：

| # | 缺陷 | 原文/实测 |
|---|---|---|
| G1 | 契约必需字段 **62** vs 闸门实查 **37** | 契约集合见 `canonical-event.v1.schema.json`（信封 `required` 8 项 `:57-66` ＋ 12 类 payload 的 `required`）；闸门侧 `:56-57` 查 7 ＋ `:62` 查 `payload` ＝ 8，payload 侧 `:89-111` 按类型查 1–4 个 ⇒ **远少于契约** |
| G2 | `event_time` **从不校验格式** | `:56-61` 只判非空；全文**无任何** `event_time` 格式分支（对照：金额有 `findBadAmount`） |
| G3 | 金额为 JSON **数字**时放行 | `:117` 仅当 `v.isTextual()` 才跑正则；`:120-122` 是 `else if` 的独立分支 ⇒ **数字金额直接放行**（`!isTextual() && !isNumber()` 才报错） |
| G4 | JSON `null` 金额被误报「类型异常」 | `:120` 的条件 `v != null && !v.isTextual() && !v.isNumber()`：`NullNode` 既非 textual 也非 number ⇒ 命中，产出 `key + " 类型异常"`；且 `:114-115` 的**规范字段名硬编码**意味着报错会引用**该源从未发送的规范名**（P5 实测 `金额格式违规: cost 类型异常`） |

**关键**：G1–G4 全部是「闸门**按规范词汇校验原始行**」这一架构前提的产物 —— 归一不存在时，闸门只能拿 canonical 词表去套源词表。**P3-02 的落点就是这个前提**。

---

## 4. `LocalFileIngestor` 摄取路径

**依赖注入面**（`LocalFileIngestor.java:60-63` 原文）——**无 normalizer、无 SourceProfile、无 SourceRegistryService**：

```java
    private final FileCheckpointMapper checkpointMapper;
    private final QuarantineRecordMapper quarantineRecordMapper;
    private final EventContractValidator validator;
    private final ObjectMapper objectMapper;
```

`LocalFileIngestor.java:47-50`（类注释，自述边界）：

```
 * <p><b>源从哪来</b>：本类不依赖源登记（不注入 {@code SourceRegistryService}）——源由调用方
 * 解析后**显式传入**，与 {@code runtimeProfileId} 同样的处理方式。…</p>
```

**行 → accepted 的唯一通道**（`:136` 原文）：

```java
                            EventContractValidator.Violation v = validator.check(text, 0);
```

四个事实在同一行上：
1. **行号写死 `0`**（`Violation.lineNo` 因此恒为 0，`quarantine_record` 也不存行号）；
2. **行文本 `text` 未经任何归一**直接进闸门 —— 行与 `acceptedOut.write(out)`（`:139`）之间**没有任何映射/归一语句**；
3. `v == null` 时**原样**写出（`:138` `byte[] out = (text + "\n").getBytes(...)`），即 accepted 内容 ≡ 源行内容；
4. `v != null` 时（`:146-157`）写 quarantine 并插 `QuarantineRecord`（`eventId`/`schemaVersion`/`reason` 直接取闸门回传值）。

> 行号复核记录：任务书与看板 V2.2 `:516` 记的 `LocalFileIngestor.java:136` **与当前工作树一致**（`:136`），无需订正。原始读数见 `raw/line-anchors-verify.txt`。

**批次清单的 `mappingVersion`**（`IngestionService.java:300` 原文）：

```java
        manifest.put("mappingVersion", null);
```

同段 `:279-300` 的 manifest 键全集实测**无任何**画像能力/映射状态键；`:295-296` 注释自述四个源身份键是 P1-05/D-037 追加项。

**采集幂等身份用创建时间 ms 而非内容哈希**（任务书 G 项）：由 `LocalFileIngestor.fileIdentity(Path)`（`:289-298`）承担，原文：

```java
    static String fileIdentity(Path file) {
        try {
            java.nio.file.attribute.BasicFileAttributes attrs =
                    Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes.class,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS);
            return String.valueOf(attrs.creationTime().toMillis());
        } catch (IOException e) {
            return "unknown";
        }
    }
```

其唯一键为 `checkpointKey(file)`（`:231`，用点 `:76`），即 `runtime_profile_id + source_id + 绝对路径 + file_identity`，**不含内容哈希**。类注释 `:39-40` 自述该语义为「Windows=创建时间戳」。本轮**未改**该行为（属另一族缺陷，登记未修）。

---

## 5. 判定的候选落点（供总控评估，**本轮未实施**）

按「唯一所有者 / 反熵」与现有分层：

| 关注点 | 候选落点 | 依据 |
|---|---|---|
| `SourceProfile` 不可变模型 ＋ 加载/缓存 | `analytics-server/connection-ingestion`（`com.graduation.analytics.source` 包，与 `SourceProfileValidator` 同包） | 计划书 `:191`「文件加载后生成不可变对象，并以 sourceCode+profileVersion 缓存」 |
| 归一执行器 `EventNormalizer` | 同上（**中立位置**）；**不得**进 `spark-jobs`／分析模块 | 指导书 `:346` 把转换归 `EventNormalizer`；计划书 `:196` 顺序固定 |
| 字段级原因码枚举 | 唯一一处（计划书 `:199` 的六类命名）；载体是既有文本列 `quarantine_record.reason` | D-116 第 1 条 |
| canonical 骨架（字段名集合） | **须先裁决 owner**（`platform-common` 或 `contract-specs` 派生） | 见 `SPEC-CHANGE-DRAFT.md` A1 |

---

## 6. 本轮**未做**（不得声称）

1. 未写任何生产代码/测试代码（本任务是 E0 规格判定；按硬性要求 1 走 ② 分支**立即停止实现**）。
2. 未跑真实链路、未跑 Spark、未连集群、**未写任何数据库**（本轮**未连接任何数据库**）。
3. 未启停任何服务（`analytics-server` PID 61104 未触碰）。
4. 未改冻结契约 / 指导书 / 看板 / `spark-jobs/**`；无 `git add/commit/push`；未生成指导书新版本。
5. **E3 真机链路 = 未做**（由总控安排）；本轮**不声称**端到端已通。
6. **E2 为红**（2 例 / 567 例，**均为既有基线缺陷**，见 `SPEC-CHANGE-DRAFT.md` §3bis）⇒ 硬性要求「E1＋E2 双绿」**当前无法达成**；E1 为绿。
7. 排序约束：看板台账行 525（人裁决 ⑥）把 **P3 排在三个前置项之后** ⇒ 本轮**未主张例外**。
