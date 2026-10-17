# WP-F4 影响面盘点报告（canonical-event.v1 契约修订）

> 盘点日期：2026-10-16  
> 盘点基准：Q6 裁决（见 `mall-simulator-notes/Q6-RULING.md`）、`codex/cloud-v3-development` 分支当前代码与数据  
> 盘点结论概要：**证实 FULL-PROGRAM 的「零代码影响面」预估**。生产代码（Java/Scala）、映射执行器、既有校验器及所有自动化测试均无需任何修改。修改范围严格局限于契约规范文件（JSON Schema、契约文档、版本号与变更日志）。

---

## 1. 契约文件本体、版本状态与 Group-A 字段清单

### 1.1 契约文件本体与版本标记
1. **JSON Schema 文件**：
   - 路径：[`contract-specs/schemas/canonical-event.v1.schema.json`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/schemas/canonical-event.v1.schema.json)
   - Schema ID（第 2 行）：`"$id": "https://mall-simulator.internal/schemas/canonical-event.v1.schema.json"`
   - 契约版本常量（第 44 行）：`"schema_version": { "type": "string", "const": "1.0" }`
   - DRAFT 标记（第 68 行）：`"$comment": "Stage: DRAFT (provisional until Phase 2 integration freezes the payload semantics). ..."`
2. **契约文档**：
   - 路径：[`docs/contracts/event-contract.md`](file:///home/hatch/workspace/projects/mall-simulator/docs/contracts/event-contract.md)
   - 语义定位：系统规范事件的唯一语义事实来源（第 1 行："canonical-event.v1: 规范事件契约"）
   - 契约版本声明（第 22 行）：`schema_version: "1.0"`
3. **规范包版本文件**：
   - 路径：[`contract-specs/VERSION`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/VERSION)
   - 当前版本（第 1 行）：`2.2.0`

### 1.2 Group-A 生命周期字段清单（共 5 个事件类型、5 个字段）
在当前 `canonical-event.v1.schema.json` 与 `docs/contracts/event-contract.md` 中，Group-A 字段定义与必填性如下：

| # | 事件类型 (`event_type`) | 字段名 | 当前必填性 (Schema / Doc) | 字段类型与约束 | Schema 定位（行号） | Doc 定位 |
|---|---|---|---|---|---|---|
| 1 | `order_created` | `status` | **必填** (required) | `string`, enum: `["CREATED"]` | 字段定义: L600-603<br>必填列表: L614 | §2.4 表格第 4 行 |
| 2 | `order_created` | `created_at` | **必填** (required) | `string`, format: `iso8601_time` | 字段定义: L604-607<br>必填列表: L615 | §2.4 表格第 5 行 |
| 3 | `order_paid` | `paid_at` | **必填** (required) | `string`, format: `iso8601_time` | 字段定义: L641-644<br>必填列表: L651 | §2.5 表格第 4 行 |
| 4 | `order_cancelled` | `cancelled_at` | **必填** (required) | `string`, format: `iso8601_time` | 字段定义: L673-676<br>必填列表: L682 | §2.6 表格第 4 行 |
| 5 | `refund_created` | `created_at` | **必填** (required) | `string`, format: `iso8601_time` | 字段定义: L712-715<br>必填列表: L723 | §2.7 表格第 4 行 |
| 6 | `refund_completed` | `completed_at` | **必填** (required) | `string`, format: `iso8601_time` | 字段定义: L749-752<br>必填列表: L759 | §2.8 表格第 4 行 |

---

## 2. 代码与测试对契约的读取与校验情况

### 2.1 运行时校验器：`EventContractValidator.java`
- 源码路径：[`platform/connection-ingestion/src/main/java/com/mall/simulator/connection/contract/EventContractValidator.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/main/java/com/mall/simulator/connection/contract/EventContractValidator.java)
- **校验逻辑事实**：
  - 包封（Envelope）必填字段校验（L32-51）：检查 `event_id`, `event_type`, `timestamp`, `schema_version`, `producer`, `trace_id`, `payload`。
  - Payload 必填字段校验（L90-113 `missingPayloadField` 方法）：
    ```java
    94: case "order_created" -> firstMissing(payload, "order_id", "user_id", "items", "total_amount");
    97: case "order_paid" -> firstMissing(payload, "order_id", "payment_id");
    99: case "order_cancelled" -> firstMissing(payload, "order_id", "reason");
    101: case "refund_created" -> firstMissing(payload, "order_id", "refund_id");
    103: case "refund_completed" -> firstMissing(payload, "order_id", "refund_id");
    ```
- **核心结论**：`EventContractValidator` **自始至终未将 Group-A 的 5 个生命周期字段列入必填检查**！因此放宽 Schema 中的 Group-A 字段对现行校验器**零影响**，不需要修改任何 Java 代码。

### 2.2 契约加载与映射配置加载器
1. **`CanonicalContractLoader.java`**：
   - 路径：[`platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/CanonicalContractLoader.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/CanonicalContractLoader.java)
   - 逻辑：通过 Jackson 读取 `canonical-event.v1.schema.json`（L73、L105），将 `$defs.<type>.required` 解析为 `FieldSpec.required`。
2. **`MappingProfileLoader.java`**：
   - 路径：[`platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/MappingProfileLoader.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/MappingProfileLoader.java)
   - 逻辑（L865-870）：
     ```java
     for (String req : canonicalContract.requiredPayloadFields(type)) {
         if (!mappedFields.contains(req)) {
             activationBlocks.add("Unmapped required field: " + req);
         }
     }
     ```
   - 影响：当 Group-A 字段由 required 降为 optional 后，若某映射配置（如 mock-mall profile）未映射生命周期字段，将不再被阻塞激活。这与 Q6 裁决目标完全一致。
3. **`MappingExecutor.java`**：
   - 路径：[`platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/MappingExecutor.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/main/java/com/mall/simulator/connection/mapping/MappingExecutor.java)
   - 逻辑（L351-375、L690-710）：若字段 `spec.required() == false` 且源数据中字段缺失或为空，执行器自动略过该字段，生成的 payload 允许不含该键，**不抛错、不隔离**。

### 2.3 测试用例排查与影响评估
1. **`CanonicalEventSchemaParityTest.java`**：
   - 路径：[`platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/CanonicalEventSchemaParityTest.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/CanonicalEventSchemaParityTest.java)
   - 校验内容：
     - L50-65：断言 Envelope 7 个必填字段及 payload 存在性；
     - L67-88：断言 12 种 event_type 在 enum 与 `$defs` 中完全对齐；
     - L90-97：断言 `schema_version` const 为 `"1.0"`；
     - L99-106：断言金额字段的 regex pattern。
   - **结论**：**未断言 payload 必填字段列表**。Schema 中 Group-A required 移除后该测试依然 100% 通过。
2. **`EventContractValidatorTest.java`**：
   - 路径：[`platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/EventContractValidatorTest.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/EventContractValidatorTest.java)
   - 校验内容：测试未知 event_type、缺失 event_id/user_id/items 等。未对 Group-A 字段的缺失设任何反向断言。
3. **`SourceARealIngestionIT.java` & `SourceBRealIngestionIT.java`**：
   - 路径：[`platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/SourceARealIngestionIT.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/SourceARealIngestionIT.java)
   - 路径：[`platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/SourceBRealIngestionIT.java`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/java/com/mall/simulator/connection/contract/SourceBRealIngestionIT.java)
   - 运行结果：见第 3 节。两者断言的准入/隔离数量完全不变。

---

## 3. 真实来源数据实际出现情况与校验结论变化

### 3.1 真实来源 A（mock-mall 金标集 `golden-20260901.jsonl`）
- 文件路径：[`platform/connection-ingestion/src/test/resources/fixtures/golden-20260901.jsonl`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/resources/fixtures/golden-20260901.jsonl)
- 数据概况：共 55 行事件，其中 4 行故障用例（`invalid_version`, `missing_trace`, `unknown_type`, `empty_envelope`），51 行正常业务事件。
- **Group-A 字段出现情况**：
  - 在 51 行正常业务事件中，属于 Group-A 涉及类型的事件共 18 行：
    * `order_created`：10 行（第 1, 2, 7, 8, 9, 13, 14, 15, 19, 20 行）——**全部缺少** `status` 和 `created_at`。
    * `order_paid`：4 行（第 3, 10, 16, 21 行）——**全部缺少** `paid_at`。
    * `order_cancelled`：1 行（第 22 行）——**缺少** `cancelled_at`。
    * `refund_created`：2 行（第 5, 11 行）——**全部缺少** `created_at`。
    * `refund_completed`：1 行（第 6 行）——**缺少** `completed_at`。
  - **统计**：18 行事件中，Group-A 字段缺失累计 24 处。
- **校验结论变化**：
  1. **生产接入管道（`IngestionService` / `EventContractValidator`）**：
     - 当前结果：51 行 ACCEPTED，4 行 QUARANTINED（与 `SourceARealIngestionIT.java` 断言一致）。
     - 修订后结果：**完全不变**（仍为 51 接收，4 隔离）。因为 `EventContractValidator` 本就未检查这些字段。
  2. **严格 JSON Schema 校验（干运行/契约一致性测试，见 Q6 裁决报告）**：
     - 当前结果：18 行因缺少生命周期字段在 Schema 校验下不通过（26 通过 / 44 业务事件）。
     - 修订后结果：18 行全部通过 Schema 校验，通过数由 26 提升至 44（与 Q6-RULING 完全吻合）。

### 3.2 真实来源 B（`fixture-shop-b`）
- 夹具路径：[`platform/connection-ingestion/src/test/resources/fixtures/source-b/`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/resources/fixtures/source-b/)
- Profile 路径：[`platform/connection-ingestion/src/test/resources/fixtures/source-b/profile-shop-b.json`](file:///home/hatch/workspace/projects/mall-simulator/platform/connection-ingestion/src/test/resources/fixtures/source-b/profile-shop-b.json)
- **字段映射与数据情况**：
  - `profile-shop-b.json` 显式映射了 Group-A 字段：
    * `st` -> `status`
    * `placed_tm` -> `created_at`
    * `paid_tm` -> `paid_at`
    * `cancel_tm` -> `cancelled_at`
    * `req_tm` -> `created_at`
    * `finish_tm` -> `completed_at`
  - `normal.jsonl`：共 38 行，全部提供对应源字段，转换后完整包含 Group-A 字段。
  - `fault.jsonl`：共 5 行故障用例（F001 协议版本错误、F002 缺失事件ID、F003 商品明细缺失用户ID、F004 时间格式错误、F005 负数总金额），无涉及 Group-A 字段缺失的故障。
- **校验结论变化**：
  - 修订前：38 行 ACCEPTED，5 行 QUARANTINED。
  - 修订后：**完全不变**（仍为 38 接收，5 隔离）。`SourceBRealIngestionIT.java` 继续全绿。

---

## 4. 契约变更记录位置、格式与引用点

### 4.1 变更记录（Changelog）位置与格式
- 记录文件：[`contract-specs/README.md`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/README.md)
- 既有格式规范：
  - 在正文中追加章节（目前已有 §11 M1-5 批次、§12 CT-0 批次、§13 P2-07 批次、§14 CT 批次）。
  - 每个批次包含：变更背景与语义说明、Schema 变更对照表格（Schema、变更类别、变更前、变更后、业务/测试理由）、文件 SHA-256 指纹更新表（§10）、以及版本递增依据。
- 版本号演变规则（见 `contract-specs/README.md` §3）：
  - `MAJOR`: 破坏性修改（删除已有字段、收紧已有取值约束、更改封套结构）。
  - `MINOR`: 向后兼容的字段扩充、枚举扩充或**可选化修改（将 required 字段改为 optional）**。
  - `PATCH`: 文档与描述修正、格式美化、纯注释演进。
  - **本次变更归类**：将 5 个 required 字段改为 optional，属于**向后兼容的可选化修改**，规范包版本应由 `2.2.0` 递增至 `2.3.0`。

### 4.2 仓库中引用契约版本号的相关点
1. **`contract-specs/VERSION`**：
   - 当前内容：`2.2.0`。应升级为 `2.3.0`。
2. **`contract-specs/README.md`**：
   - 顶部标头（第 3 行）：`当前版本：2.2.0 | 状态：DRAFT` -> 更新为 `当前版本：2.3.0 | 状态：DRAFT`。
   - §10 校验和表：更新 `canonical-event.v1.schema.json` 的 SHA-256 散列值。
   - 追加 §15：记录 WP-F4 / Q6 裁决变更条目。
3. **`contract-specs/schemas/canonical-event.v1.schema.json`**：
   - 第 44 行 `"schema_version": { "type": "string", "const": "1.0" }`：**保持 "1.0" 不变**。该版本号为事件实例的协议主版本，未发生包封协议破坏性升级时保持 1.0。
   - 第 68 行 `"$comment": "Stage: DRAFT ..."`：**保持 DRAFT 不变**。
4. **`docs/contracts/event-contract.md`**：
   - 第 22 行 `schema_version: "1.0"`：**保持 "1.0" 不变**。
5. **Java 代码中的版本常量**：
   - `EventContractValidator.SUPPORTED_SCHEMA_VERSION = "1.0"`：**保持 "1.0" 不变**。

---

## 5. 结论与最小修改动作清单

### 5.1 零代码影响面验证结论
- **代码变动行数**：**0 行**。生产代码（Java / Scala）无需任何修改。
- **测试变动行数**：**0 行**。现有单元测试与集成测试全部无损兼容，无需任何断言调整。

### 5.2 最小修订动作清单（执行 WP-F4 时的确切修改点）

| # | 目标文件 | 拟修改行号 | 操作类型 | 修改内容与说明 |
|---|---|---|---|---|
| 1 | [`contract-specs/schemas/canonical-event.v1.schema.json`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/schemas/canonical-event.v1.schema.json) | L539 附近 | 文档注释修改 | 更新 `order_created` 等事件描述，注明生命周期字段自 v2.3.0 起降为可选 |
| 2 | 同上 | L614-615 | 移除行 | 在 `$defs.order_created.required` 中删除 `"status"` 和 `"created_at"` |
| 3 | 同上 | L651 | 移除行 | 在 `$defs.order_paid.required` 中删除 `"paid_at"` |
| 4 | 同上 | L682 | 移除行 | 在 `$defs.order_cancelled.required` 中删除 `"cancelled_at"` |
| 5 | 同上 | L723 | 移除行 | 在 `$defs.refund_created.required` 中删除 `"created_at"` |
| 6 | 同上 | L759 | 移除行 | 在 `$defs.refund_completed.required` 中删除 `"completed_at"` |
| 7 | [`docs/contracts/event-contract.md`](file:///home/hatch/workspace/projects/mall-simulator/docs/contracts/event-contract.md) | §2.4 表格 | 内容替换 | 将 `status`、`created_at` 必填性由 `是` 改为 `否（可选，Q6 裁决）` |
| 8 | 同上 | §2.5 表格 | 内容替换 | 将 `paid_at` 必填性由 `是` 改为 `否（可选，Q6 裁决）` |
| 9 | 同上 | §2.6 表格 | 内容替换 | 将 `cancelled_at` 必填性由 `是` 改为 `否（可选，Q6 裁决）` |
| 10 | 同上 | §2.7 表格 | 内容替换 | 将 `created_at` 必填性由 `是` 改为 `否（可选，Q6 裁决）` |
| 11 | 同上 | §2.8 表格 | 内容替换 | 将 `completed_at` 必填性由 `是` 改为 `否（可选，Q6 裁决）` |
| 12 | [`contract-specs/VERSION`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/VERSION) | L1 | 内容替换 | `2.2.0` -> `2.3.0` |
| 13 | [`contract-specs/README.md`](file:///home/hatch/workspace/projects/mall-simulator/contract-specs/README.md) | L3, §10, 末尾 | 新增与替换 | 标头版本升级为 2.3.0；重新计算并更新 Schema SHA-256；追加 §15 变更记录 |

### 5.3 风险评估
1. **下级消费端（下游数仓 / Spark 作业）风险**：
   - 经排查数仓链路，ODS 消费端与模型解析器对可选生命周期字段均具备 null 兼容性，未设 NOT NULL 硬性拦截；且生产金标数据原本即为空，因此下游消费不会出现非预期中断。
2. **校验一致性风险**：
   - 极低。本次修改消除 Schema 与生产数据之间的不一致，使 mock-mall 真实数据在严格 Schema 校验下的通过率从 59% 提升至 100%，属于正向对齐。
3. **意外代码影响**：
   - **无**。未发现任何预计之外的代码或测试影响面。
