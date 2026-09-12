# P3-01-a 实施报告：`mock-mall.v1.json` 源画像

- 泳道：**P3-01-a**（总控派单，白名单窄授权）
- 日期：2026-09-12
- 交付物：`analytics-server/source-profiles/mock-mall.v1.json`（真实源画像）
- **状态自评：`DONE_LIMITED`**（理由见文末「状态自评依据」；本泳道**不自行升级**为 DONE）
- 一句话结论：**画像文件存在且合规**（编译通过／画像相关既有测试全绿／端点浅检 7/7／语义断言 I1–I9 全 PASS）

> 严格措辞（总控指令）：本报告**不写**"P3-01 已完成"。
> 另外按 **D-139 §2.2**，`POST /api/v1/sources/1/test` 的 `ok=true` **只代表浅检通过**，
> **不得**表述为"画像语义已合规"。语义合规的证据是本泳道的对账脚本 `raw/11`（I1–I9）＋逐键举证。

---

## Aegis Visibility:

**目标**：让 V16 种子源（`source_registry` id=1，`source_code=mock-mall`）的 `profile_path`
指向的文件**真实存在且语义合规**，解掉 P2-02（多格式时间）与 P2-04-b（SourceProfile 投影）的硬阻塞。

**开工时的真实状态**（实测，非转述）：

| 项 | 读数 | 证据 |
|----|------|------|
| 存储态 | `status = ACTIVE`、`current = true`、`profilePath = analytics-server/source-profiles/mock-mall.v1.json` | `raw/05` §[2] |
| 端点 `/test` | `ok = false`；`profile_file_exists.passed = false`；其后 4 项 `applicable = false`「前置项未通过，无法评估」 | `raw/05` §[3] |
| `/activate` | 409 `SOURCE_PROFILE_INVALID`（**本泳道未调用**，D-139 §2.3） | 上文 README §19–26 |

⇒ 卡点**唯一且明确**：文件不存在。故本泳道只做一件事：**把这个文件按实测证据写出来并证明合规**。

**边界遵守**（逐条对照派单）：

| 约束 | 结果 |
|------|------|
| 只动 3 处白名单 | ✅ `git status --porcelain`：`?? mock-mall.v1.json`、`?? .../p3-01a-.../{draft,raw}/`、` M source-profiles/README.md`（补记 29 增 / **0 删**） |
| 不碰 `spark-jobs/**`、任何 `*.java`/`*.scala`、`warehouse/ddl/**`、看板 V2.2 | ✅ 未触碰（`git status` 无对应条目） |
| 无 git 写操作 | ✅ 只用 `git status/diff/log`（只读） |
| 无 DB DDL/DML、未调 `/activate` | ✅ 只读查询；端点只读调用 |
| 无证据不结论、不编时间戳 | ✅ 每条读数带 `Get-Date` 时间戳；每个「0」都配阳性/阴性对照 |
| 文件内容只用 write/edit 工具写下 | ✅ |

---

## Anti-Entropy Declaration:

本泳道**没有新增任何"真相的第二个属主"**，并有机器可检的断言钉住：

1. **`timezone` / `currency` 不进画像**（D-136）。二者的唯一属主是 `source_registry.timezone`（`Asia/Shanghai`）
   与 `.currency`（`CNY`）。设计 §4.2 的 9 个键**本就没有**这两个槽位。
   → 断言 **I9** 强制画像文本不含 `timezone`/`currency`/`Asia/Shanghai`/`CNY`。
2. **`sourceCode` / `profileVersion` 不另立一份**：画像里的这两个字面量与 DB 行的
   `source_code` / `profile_version` 是**同一事实的两处表达** —— 但这不是"第二属主"：
   校验器 `SourceProfileValidator` 正是以"登记值"为期望去**核对**画像，即 DB 是属主、画像是被核对方。
   → 断言 **I2** 钉住二者与 `raw/03` 的 DB 实测值一致。
3. **没有把"实测到的形态"扩写成"平台语义"**：契约 enum 之外的取值一律写 `null`，
   **不**擅自归一（详见 Gap Closure §G0 —— 这条是本泳道被自己的断言抓出来后才改对的）。
4. **不制造第二个"源清单"**：画像里不写 `coupon`/`refund`/`payment`/`session` 等无属主实体，
   也不写源侧根本不发的事件名（实测 0 次），因为 §4.2 L147 规定"缺失的映射项 = 该源没有这个语义" ——
   多写一条就是**宣称**一个不存在的语义。

---

## Retirement Decision:

**本泳道不淘汰任何代码。** 逐条说明为什么"没有淘汰动作"是正确答案而不是遗漏：

| 触碰到的东西 | 处置 | 理由 |
|-------------|------|------|
| `p1-03-probe-1/2.v1.json` | **保留**，不删不改 | 它们是 P1-03 的**负例夹具**（用于"登记／文件不一致 ⇒ 不可激活"），属主是 P1-03；本泳道交付真实画像**不使夹具失效**（`SourceProfileFixtureTest` 仍 4/4 通过）。删掉会摧毁 P1-03 的负例覆盖。 |
| `source-profiles/README.md` 第 17、19–26 行 | **保留原文**，只**追加**补记 | 原文是"落盘前"的历史状态记录。改掉它 = 抹掉"这里曾经缺文件"这一事实，而该事实正是 P3-01-a 存在的理由。已验证：`git diff --numstat` = **29 增 / 0 删**。 |
| `IngestionManifestSourceSchemaTest` 的红灯 | **不修、不放宽、不跳过** | ① 修它要改 `*.java`，**超出白名单**；② 放宽它正是"为绿而放宽测试"，明令禁止；③ 归因证明与本泳道无关（见 `draft/p3-01a-E2-failure-attribution.md`）。**上报总控**。 |
| `mock-mall.v1.json` 首版的 5 处归一映射 | **已淘汰**（`add_cart→cart_add`、`age18_24→18-24`、`age25_34→25-34`、`age35_44→35-44`、`age45_plus→45+` → 全部改为 `null`） | `delete-first`：这 5 条是**未获授权的语义断言**。Q4 未决期间它们不合法，留着就是留了一个"擅自决定"。已删除并留痕（`draft/p3-01a-profile-rationale.md` §10.7）。 |

**待淘汰建议（留给属主，本泳道不动手）**：
`surrogate-key.v1.json` 的向量 **V01** 把 `event_id` 当成 `entity=user` 的 `rawInput`，
并标注 `why="真实 landing 取值"`。实测该 UUID 是 `user_registered` 事件的 **`event_id`**，
同行的 `payload.user_id` 另有其值（对照自检通过，`raw/10c`）。
⇒ 建议属主**删除或修正 V01**：它给了一个"看起来有实测出处、其实指错字段"的向量，
比没有向量更危险（会被当成既有事实引用）。

---

## Verification Plan:

### E1 编译（`raw/12-E1-compile.log`，3,381 B）

```
### cmd: D:\apache-maven-3.9.14\bin\mvn.cmd -o -f analytics-server/pom.xml -pl connection-ingestion -am -DskipTests compile
[INFO] BUILD SUCCESS
[INFO] Total time:  9.817 s
### exit=0
```

**E1 判定：通过**（`exit=0`、`BUILD SUCCESS`、3 模块 `analytics-server → platform-common → connection-ingestion`）。

### E2 既有测试（`raw/13-E2-tests.log`，89,104 B）

```
### cmd: D:\apache-maven-3.9.14\bin\mvn.cmd -o -f analytics-server/pom.xml -pl platform-app -am test
[INFO] BUILD FAILURE
### exit=1
```

各模块逐字读数：

```
[INFO] Tests run: 41, Failures: 0, Errors: 0, Skipped: 0      ← platform-common
[INFO] Tests run: 156, Failures: 0, Errors: 0, Skipped: 0     ← connection-ingestion
[INFO] Tests run: 111, Failures: 0, Errors: 0, Skipped: 0     ← warehouse-pipeline
[INFO] Tests run: 38, Failures: 0, Errors: 0, Skipped: 0      ← metric-analysis
[INFO] Tests run: 91, Failures: 0, Errors: 0, Skipped: 0      ← ai-decision
[INFO] Tests run: 100, Failures: 1, Errors: 0, Skipped: 0     ← platform-app
```

**合计 537 个测试 / 536 通过 / 1 失败 / 0 错误 / 0 跳过。**

唯一失败（逐字）：

```
[ERROR] IngestionManifestSourceSchemaTest.allOnDiskManifestsStillValidate:168
        Expecting empty but was: ["40.json", "41.json", "42.json"]
```

**与画像有关的两处，逐字全绿：**

```
[INFO] Tests run: 8, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.analytics.source.SourceProfileValidatorTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.graduation.analytics.source.SourceProfileFixtureTest
```

**E2 判定：画像相关测试全绿；reactor 整体 1 红，该红为既有、与本泳道无关**（归因见
`draft/p3-01a-E2-failure-attribution.md`：红灯断言读的是 `landing/manifests/40|41|42.json`，
三者落盘时间 09:23 / 17:22 / 17:34，**均早于本泳道开工**；本泳道从未写入 `landing/`；
实测机制＝别的泳道真实入库产生了带 P1-05 新源字段的新代清单，使该测试"磁盘上不存在新代清单"的前提失效）。

### E3 真机端点（`raw/14-E3-post-state.txt`，2,433 B）

```
### [2] POST /api/v1/sources/1/test  (只读；**未调用 activate**) => HTTP 200
{"code":"OK","message":"success","data":{"sourceId":1,"sourceCode":"mock-mall","ok":true,"items":[{"name":"profile_path_policy","passed":true,"applicable":true,"detail":"profile_path 是仓库相对路径：analytics-server/source-profiles/mock-mall.v1.json"},{"name":"profile_file_exists","passed":true,"applicable":true,"detail":"画像文件存在且可读：analytics-server/source-profiles/mock-mall.v1.json"},{"name":"profile_json_object","passed":true,"applicable":true,"detail":"顶层是合法 JSON 对象：analytics-server/source-profiles/mock-mall.v1.json"},{"name":"profile_source_code_matches","passed":true,"applicable":true,"detail":"画像 sourceCode 与登记一致：mock-mall"},{"name":"profile_profile_version_matches","passed":true,"applicable":true,"detail":"画像 profileVersion 与登记一致：1.0"},{"name":"profile_required_top_level_keys","passed":true,"applicable":true,"detail":"设计 §4.2 的 9 个顶层必备键齐全"},{"name":"status_transition_allowed","passed":true,"applicable":true,"detail":"当前状态允许生命周期变更：ACTIVE"}]},"traceId":"be371d86-bb05-4f38-a0b4-1f089d708e32"}
```

阴性对照（证明该端点真的会校验，不是恒真）：

```
### [4] 阴性对照 POST /api/v1/sources/999999/test（不存在的源）=> HTTP 404
{"code":"SOURCE_NOT_FOUND","message":"源不存在: 999999","data":null,"traceId":"8a60aa77-191e-4274-8384-55cbde85c9f4"}
```

状态未被本泳道改动（`updatedAt` 与 `raw/05` 完全一致，仅画像相关项翻转）：

```
### [3] GET /api/v1/sources/1  (只读) => HTTP 200
{...,"status":"ACTIVE",...,"updatedAt":"2026-09-11T19:56:28.02",...}
```

**E3 判定：通过（7/7 项 `passed=true` 且 `applicable=true`，`ok=true`）。**
配套天然阴性对照：同一端点在画像缺失时（`raw/05`）返回 `ok=false` 且 `profile_file_exists=false`
⇒ 该端点的判定**随真实文件状态变化**，不是恒真。**按 D-139 §2.2，此结果只作浅检证据。**

### E4 语义对账（本泳道自建，`raw/11-profile-conformance-output.txt`）

```
   断言 9 条，PASS 9，FAIL 0
   verdict=ALL_PASS
## V. 对照（证明本脚本不是在「全 0 真空」上通过）
   [positive-control] 实测行数 = 2620776（期望 > 0）
   [positive-control] 实测 event_type distinct = 12（期望 == 12）
   [negative-control] 画像里 '__NO_SUCH_KEY__' 出现次数 = 0（期望 0）
   [negative-control] 实测 event_type == '__NO_SUCH_TYPE__' 的行 = 0（期望 0）
```

九条不变量（**双向**对账：画像→数据 防臆造；数据→画像 防漏登记）：

| # | 不变量 | 结果 |
|---|--------|------|
| I1 | 顶层恰 9 键，且与 `SourceProfileValidator.REQUIRED_TOP_LEVEL_KEYS` **同名同序**（键名表从 `.java` 源码**重新解析**后比对，防手抄） | PASS |
| I2 | `sourceCode`/`profileVersion` 与 `source_registry` 实测行一致 | PASS |
| I3 | `event_type` 全覆盖（实测 12 distinct，0 未映射），映射目标 ⊆ 契约 12 类 | PASS |
| I4 | `fieldMapping` 覆盖 payload 字段全集；`@keep` 仅用于契约外字段（契约外 = `{event_time_utc}` **恰好**等于 `@keep` 集） | PASS |
| I5 | `enumSemantics` 覆盖各域全部实测 distinct 值（0 未覆盖） | PASS |
| I6 | `null` 与"契约 enum 之外"**互为充要**（不漏判、不冤枉、不擅自归一） | PASS |
| I7 | `identityPolicy.rawField` 在数据里真实存在 | PASS |
| I8 | `timePolicy` 解析 **100%** 的 `event_time`（2,620,776/2,620,776），非 `+08:00` 的 = 0 | PASS |
| I9 | 画像不含 `timezone`/`currency`（不与 `source_registry` 争属主） | PASS |

---

## Gap Closure:

### G0（**最重要的一条**）：`enumSemantics` 里的 `null` —— 逐条给出设计书行号后的诚实答复

**D-139 §2.4 要求**：泳道须逐条给出设计书行号，证明"`null` 表示已知但无规范映射"是设计书定义的合法表达，
否则不得据此断言合规。**答复如下（以实测与原文为准，不替设计书补写）**：

1. **设计书 §4.2 L129–132** 给出的形状是
   `"enumSemantics": { "<domain>": { "<源取值>": "<平台语义标签>" } }`，
   示例 `{"wishlist":"favorite","browse":"view","purchased":"purchase"}` 与
   `{"SIGNED":"PAID","CANCELLED":"CANCELLED"}` —— **全部键值对都是"字符串 → 字符串"**。
   **设计书没有任何一行定义 `null` 的语义。**
2. **但设计书 L147 明确禁止"省略"这一替代写法**：
   「缺失的映射项 = "该源没有这个语义"（不是默认值），平台按"缺语义"处理而不是猜。」
   本源的 `purchase`(35)／`click`(6)／`add_cart`(6)／`web`(210)／`restock`(35)／
   `20-29`(900)／`age18_24`(132)／`45-54`(35)／`city_level="2"`(63) 等取值**实测确实存在**（计数见 `raw/09`）。
   ⇒ 若把它们**省略**，画像就在陈述"该源没有这个语义"—— 这是一句**被实测直接否证**的假话。
3. **因此**：`null` **不是**设计书词汇，而是**总控指令**（"写 `null` 并登记为未实测/待裁定"）引入的**扩展**。
   它之所以被选中，是因为 L147 堵死了设计书内唯一的替代写法（省略）。
4. **由此产生一个待裁定项**：`enumSemantics.<domain>.<值> = null` 这一表达**尚未冻结**。
   在总控裁定前，本画像的 8 类 `null` 应当被读作
   **"该取值存在，但其平台语义无属主可裁"**，而**不是**"已合规的合法表达"。
   建议二选一：① 在设计书 §4.2 增一行明文定义 `null`（＝"已知但待裁定"，与 L147 的"缺语义"区分）；
   ② 改由 `semantic_registry` 表承载（但该表**实测不存在**，见 R9）。

### G1：画像阻塞已解除（P2-02 / P2-04-b 可开工）

`profile_file_exists` 由 `false` → `true`，`ok` 由 `false` → `true`。
按 D-139 §2.1，P2-02 开工前**必须复测本文件 sha256 未变**，且其白名单**只读**该文件。

> ⚠️ **注意**：D-139 记录的文件读数是 **3,339 B / sha256 `FBA70E3C3B1D4D81…`**。
> 本泳道在 D-139 之后**又改了一次**（G0 的 I6 修正：5 处归一映射 → `null`），
> 故**最终**为 **3,323 B / sha256 `0bb8a05c8d5e466864dcb90b8d7b97105dc65497dc3ccb463d104f27f021170b`**。
> **D-139 里的 sha256 已过期，请以本报告为准。**

### G2：`surrogate-key.v1.json` 向量 V01 与真实数据不符（规格缺陷）

V01 声称 `entity=user`、`rawInput="89a6db2c-9ecb-4be0-a24b-9f697da00686"`、`why="真实 landing 取值"`。
实测（`raw/10c`，landing 下 **624 个文件**全扫，含对照自检）：

- 该 UUID 的真实字段位置 = **`event_id`**（`user_registered` 事件），命中 2 文件
  （`landing/events/2026091211.jsonl`、`landing/accepted/41/2026091211.jsonl`）；
- **同行** `payload.user_id = "2098607948334395394"` —— 即该规格**自己的 V02** 向量；
- 阳性对照 A（同行真实 `user_id`）= 2 > 0；阳性对照 B（UUID 头串）= 2 ≥ 目标；阴性对照 = **0**；
- 且 `event_id` 属该规格**自称范围之外**（D-055/D-093：事件粒度键不在本规格范围）。

⇒ 建议属主删除或修正 V01（理由见 Retirement Decision）。

### G3：`quarantinePolicy` 两键无法表达实际隔离原因

实测真实隔离原因是：① `schema_version` 不受支持（`9.9`/`2.0`/`1.1`）；
② 非法 `behavior_type`（`purchase`）；③ 缺 `event_id`；④ JSON 不可解析（41 行）。
而**实测 `event_type` 未知 = 0**（12 个取值全在契约内）⇒ `unknownEventType` 分支**从未被触发**。
两键策略表达不了上述四类原因，与 `contract-specs/README.md` **Q6** 同源。待裁定。

### G4：`fieldMapping` 扁平命名空间 vs 契约骨架按事件类型（结构冲突）

`amount`（`order_paid` 骨架字段，同时是 `behavior` 额外字段 15 行）、
`product_name`（`product_*` 骨架，`behavior` 额外 2,427 行）、
`reason`（`order_cancelled`/`refund_created` 骨架，`refund_completed` 额外 105 行）。
本画像只能取恒等（改 `@keep` 会把**骨架字段**误判成额外字段，错得更重）。待裁定。

### G5：`reason` 一个键名承载两个域

`order_cancelled.reason` 4 值（`out_of_stock` 646 / `user_cancel` 631 / `change_of_mind` 522 /
`payment_timeout` 512）与 `refund_created`+`refund_completed.reason` 另 6 值。
扁平 `enumSemantics.<domain>.<值>` 表达不了"同键两域"，故本画像**不写** `reason` 域并留全量实测值。待裁定。

### G6：`identityPolicy` 的实体词表缺口

`order` 见于设计 §4.2 L136 示例，但**不在** `surrogate-key.v1.json` 的 `entityEnum`
（`{user, product, category, brand, coupon}`）内；`refund_id`/`payment_id`/`session_id`
**实测存在**却两个词表都没有实体名；`coupon` 在词表内但实测 0 次出现。
`shape` 词表 `{UUID, PREFIX_NUMERIC, ANY, NUMERIC}` 也**无法表达"混合形态"**——
5 个实体的 id 实测全是"纯数字 + 前缀数字"混合（如 `user_id`：NUMERIC 2,602,348 ／ PREFIX_NUMERIC 8,664）。
待裁定。

### G7：`timePolicy` 单 `field` 槽位与多来源时间戳

信封的 `event_time` 与 `ingest_time` **都是源侧提供**，但 `timePolicy` 只有一个 `field` 槽位；
`ingest_time` 的形态族与 `event_time` 不同（7 位小数 2,336,220 行 vs 无小数 24,496 行）⇒ 无处声明。
另 `formats` 的**取值词表未冻结**（既有夹具混用 `ISO_OFFSET_DATE_TIME` 这类 Java 格式器名与
`yyyy-MM-dd HH:mm:ss` 这类字面 pattern）。待裁定。

### G8：设计书 §4.2 示例与冻结契约不一致（spec-vs-spec / spec-vs-contract）

1. **`eventTypeMapping` 的目标值**（L116–121）标为"规范事件类型"，但示例写的是
   `view`/`cart_add`/`order_paid`/`order_created` —— 其中 `view`、`cart_add` 是 **`behavior_type` 枚举值**，
   **不是**契约的 12 个事件类型。契约 12 类里没有 `view`/`cart_add`。
   本画像按**契约**（12 个真实事件类型）写，与实测 12 个 `event_type` 逐字一致。
2. **示例把 `purchase` 当作合法平台标签**（L130 `"purchased": "purchase"`），
   但冻结契约 `behavior.behavior_type` 的 enum **不含** `purchase`（而本源实测恰有 35 行 `purchase`）。
   ⇒ 与 Q4 同源，进一步说明"契约 enum 与设计书示例不一致"是**双向**的。
3. **域名大小写不一致**：§4.2 L131 写 `orderStatus`（驼峰），§4.3 L154 写 `order_status`（下划线）。
   本画像取 §4.2 的驼峰式（与既有画像夹具一致）。
4. **§4.3 的两张表实测不存在**：`analytics_meta` 的 23 张表里**没有** `semantic_registry` /
   `dimension_registry`（`raw/06`：`ERROR 1146`）⇒ `enumSemantics` 的标签词汇**无实现属主**，
   §4.2 L129 指向的"§4.3"目前是空的。

### G9：`shape`/`surrogate` 目前**没有消费者**

`surrogate-key.v1.json` 状态为 `DRAFT` 且**尚无任何实现读取**（D-055/D-093）⇒
本画像 `identityPolicy` 的 `shape`/`surrogate` 取值不会影响任何运行结果，
只是与既有画像夹具保持一致。**不得**据此声称代理键逻辑已验证。

---

## 未实测清单（诚实申报）

| 未测 | 原因 |
|------|------|
| `POST /api/v1/sources/1/activate` | D-139 §2.3：状态变更，不在只读授权内 ⇒ **不得**声称种子源已激活 |
| 种子源端到端 E3 全链路（入库→ODS→指标） | 需要 `/activate` 与集群，均超出本泳道授权 |
| `enumSemantics` 的 `null` 是否合法 | **设计书无对应定义**（G0），已交总控裁定 |
| `spark-jobs/**` 是否读取 `identityPolicy`/`timePolicy` | 白名单禁止触碰/深入；且 `surrogate-key.v1.json` 自称无实现读取（G9） |
| 多源／异构取值 | `source_registry` **实测只有 1 行** ⇒ 无第二个源可比对，"多源"能力**无法测量** |
| 去重后的事件数 | `landing/` 同时含源输入（`events/`）与入库后副本（`accepted/`、`quarantine/`） ⇒ 全树聚合计数是**出现位置数**，非去重事件数（已在 rationale §2 声明） |
| `landing/` 内两个目录的字节级对拍 | 本泳道复算 345 jsonl / 1,215,042,454 B 与总控一致；更细的逐文件对拍未做 |

---

## 文件清单（路径 + 字节 + sha256）

**交付物（白名单新增）**

| 路径 | 字节 | sha256 |
|------|------|--------|
| `analytics-server/source-profiles/mock-mall.v1.json` | 3,323 | `0bb8a05c8d5e466864dcb90b8d7b97105dc65497dc3ccb463d104f27f021170b` |

**白名单修改（纯追加）**

| 路径 | 字节 | sha256 | 说明 |
|------|------|--------|------|
| `analytics-server/source-profiles/README.md` | 4,430 | `61c74155f8888d156c712f573f1b98669cce2f0521e0690419db24b168fedbf1` | 追加补记 **29 增 / 0 删**（原文一字未改） |

**本报告与依据**

| 路径 | 字节 | sha256 |
|------|------|--------|
| `docs/acceptance/p3-01a-mock-mall-profile-20260912/IMPL-REPORT.md` | 见 `README.md` 索引 | 见 `README.md` 索引 |
| `docs/acceptance/p3-01a-mock-mall-profile-20260912/draft/p3-01a-profile-rationale.md` | 22,816 | `0e3859e4c2aa91bd877b14f6614534e510212c2c97d41cc145a44e5e551cc591` |
| `docs/acceptance/p3-01a-mock-mall-profile-20260912/draft/p3-01a-E2-failure-attribution.md` | 4,420 | `f76d10e5d16005bc83d164d15d823710d36ba6360aa6def98001a6ae1cb66614` |

**原始证据（`raw/`，全部为「命令 + 原始输出」）**

| 路径 | 字节 | sha256 |
|------|------|--------|
| `raw/00-timestamps.log` | 42 | `4971863cebd7d6a1caf6af4855b969fe4d05f1d8c431bc225dca24b46f8c6e17` |
| `raw/03-db-source-registry.txt` | 2,325 | `ac37b8d4347d0989b6edabca57defdcfdf59d4dccd7abd1f56727d8122bac461` |
| `raw/04-stats-script.py` | 9,732 | `df3bd0611f8d21938999ed0a5d046a9ca43e7780c668904c8777f5d52e69133c` |
| `raw/04-stats-output.txt` | 16,501 | `b8dfea33ad986da823a79bd392514d2333d1f6978588d5b59e00f6c9d08da90a` |
| `raw/04b-scan-inventory.txt` | 44,353 | `add1bbcb3fd7361e647bfd2e1e2e4d8c33d77c68b3cfab227b9072d3b94fec21` |
| `raw/05-E3-pre-state.txt` | 2,463 | `026e3a3617a99c9f3b8a40ccffe9a581159239a1c709ca8f51efa71eed7ab380` |
| `raw/06-db-semantic-registry.txt` | 1,607 | `24597186c36b602d594efdcebf9d2124180ad5d1c66df1f224e64130ecb78d21` |
| `raw/07-time-shape-probe.py` | 4,764 | `02baabffa7d0b8f2e6254100054c63e6ce00323918e5d3ce8cafb6dc05e29350` |
| `raw/07-time-shape-output.txt` | 9,405 | `b9f0a1fa1049e3078135e41cd0c645e0681e755c909e8d538c14fd66808e3bcd` |
| `raw/08-enum-identity-probe.py` | 7,678 | `b5d7d195df43ea0a34a437d715a676c6d36bcb5683f2cfc09ce1d08bc3cc3a54` |
| `raw/08-enum-identity-output.txt` | 17,721 | `0c4c56eee2f1dd19ff6de3d2274b1b55243d9fb516e476de2086ead58757410b` |
| `raw/09-skeleton-crosscheck.py` | 7,102 | `3dd97cbace7b6fcab6dea6c479bf75c408d31bc278ca8595f35071e4b793629d` |
| `raw/09-skeleton-crosscheck-output.txt` | 9,607 | `4c0cb1fae13e431e4616153247e8382f6ce6b89ebcb74572efb5c58148cf8789` |
| `raw/10-uuid-and-tree.txt` | 2,780 | `cbfbb6cd67642bb2e2cc53d509599f5633e7dbedfaf4234596ca17c3c02ee77c` |
| `raw/10-uuid-coverage-gap.txt` | 377 | `8bbb5deab19830bdda4d5c9b62c69de84821fbd913bc88632bcbdb91d73e24ff` |
| `raw/10b-uuid-search.py` | 4,232 | `7d0bcce3868675b797658e24318f95ce850d0d8b11f52fcc067cf36df65d05fa` |
| `raw/10c-uuid-search.txt` | 1,766 | `d3a773987b399fe441d06e0a523600fae5767e06dd0bb5046fdddde7e69fe8b5` |
| `raw/11-profile-conformance.py` | 13,226 | `c366db0bb00699554fa78ec5aca8bb993dc3c62121bb7ea9dcd8a89985a9fda9` |
| `raw/11-profile-conformance-output.txt` | 2,801 | `4b508726ec9290f3c4fe81c98d551b3aad2b41298dece58c446ad6bf82172cdd` |
| `raw/12-E1-compile.log` | 3,381 | `360f07716797ef13aeeda97e07acf2c0a9de2ee56eb19c48c72e004597e9dab6` |
| `raw/13-E2-tests.log` | 89,104 | `cf03bfa4770db96af91ed15797a442e9d48dbb99a86cc4771c8fceda6ad0077e` |
| `raw/14-E3-post-state.txt` | 2,433 | `f7067ae60aad9372f35b26d06051610209fb7fc2bc92ba3748be8c9d58c148b5` |

> `raw/10-uuid-coverage-gap.txt` 只有 4 行且**无输出**（原命令未产出结果），属**不完整证据**。
> 按"不删旧文件"原则保留原样，其内容已由 `raw/10b` + `raw/10c` **补齐并取代**。

**总控放置在本目录的裁决书**（非本泳道产物，一并列出以便校验）

| 路径 | 字节 | sha256 |
|------|------|--------|
| `RULINGS-P3-01A-20260912.md` | 4,954 | `d54017764739b44695f254d08e7479d8ae53c3bdc087c4bea9b39ca2ae8433b5` |
| `RULINGS-P3-01A-BATCH2-20260912.md` | 4,148 | `b854b158c53aeed919440fd3595f1a5b8a1b5dfb7be021bbbd19d9409b87fb66` |

---

## 规格 vs 代码/数据 不一致清单（汇总）

| # | 不一致 | 证据 |
|---|--------|------|
| X1 | §4.2 `eventTypeMapping` 示例的目标值是 `behavior_type` 枚举值（`view`/`cart_add`），非契约 12 事件类型 | G8-1 |
| X2 | §4.2 示例把 `purchase` 当合法平台标签，契约 enum 无 `purchase`；而本源实测有 35 行 `purchase` | G8-2 |
| X3 | 域名大小写：§4.2 `orderStatus` vs §4.3 `order_status` | G8-3 |
| X4 | §4.3 的 `semantic_registry` / `dimension_registry` **实测不存在**（`ERROR 1146`）⇒ `enumSemantics` 标签无属主 | `raw/06` |
| X5 | §4.2 L147（省略＝缺语义）与"实测存在但无规范标签"的取值之间**无合法写法** ⇒ `null` 属未冻结扩展 | G0 |
| X6 | `surrogate-key.v1.json` V01 把 `event_id` 当作 `entity=user` 的 `rawInput` | G2 / `raw/10c` |
| X7 | `identityPolicy` 的 `order` 不在 `entityEnum`；`refund`/`payment`/`session` 两词表皆无 | G6 |
| X8 | `shape` 词表无法表达"混合形态"（5 实体实测皆混合） | G6 |
| X9 | `quarantinePolicy` 两键无法表达实际隔离原因；`unknownEventType` 分支实测从未触发 | G3 |
| X10 | `timePolicy` 单 `field` 槽位 vs `event_time`+`ingest_time` 皆源侧提供；`formats` 词表未冻结 | G7 |
| X11 | `fieldMapping` 扁平命名空间 vs 契约骨架按事件类型（`amount`/`product_name`/`reason`） | G4 |
| X12 | `reason` 一个键名两个域，扁平结构表达不了 | G5 |
| X13 | `reason` 域重载与 `city_level` 数字漂移（`"1"`/`"2"`）**未登记**在 `contract-specs/README.md` Q4 | G5 / `raw/09` |
| X14 | 校验器 7 项为**浅检**，不校验 `formats` 语法/`fieldMapping` 取值/`null` 语义/`shape`·`surrogate` 合法性 | D-139 §2.2 |
| X15 | `IngestionManifestSourceSchemaTest` 的前提校验与"真实入库会产生新代清单"**可复现冲突**（跨泳道） | `draft/p3-01a-E2-failure-attribution.md` |

---

## 状态自评依据

**`DONE_LIMITED`**，不是 `DONE`：

- 派单规定"E1+E2 有原始日志 ⇒ 至多 `DONE_LIMITED`；E3 真通过 ⇒ `DONE`"。
- E1 ✅、E3 ✅（7/7）、语义断言 I1–I9 ✅ —— **按此口径 DONE 是允许的**。
- **但**本泳道主动不上调，因为 **reactor 整体并非全绿**（537 中 1 红）。
  该红虽已证明**既有且与本泳道无关**（`draft/p3-01a-E2-failure-attribution.md`），
  且在无 `*.java` 写权限的前提下**本泳道无法使其转绿**；
  在"既有测试全绿"这一自设门槛未真正满足时宣称 `DONE`，属**过度声称**。
- **升级路径**（交总控选）：① 认可"画像相关测试全绿 + E1 + E3"⇒ 可升 `DONE`；
  ② 要求 reactor 全绿 ⇒ 需 P1-05 属主修 `IngestionManifestSourceSchemaTest`
  （建议改为只校验"运行前已存在的清单子集"），本泳道不代劳。

**本泳道未曾声称**：种子源已激活、E3 全链路已通过、`ok=true` 等于画像语义已合规。
