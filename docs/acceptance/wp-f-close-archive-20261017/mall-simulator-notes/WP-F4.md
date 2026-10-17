# WP-F4 canonical-event.v1 契约修订（实施规约）

## 背景与依据
- Q6 裁决第 3 条（notes/Q6-RULING.md）：A 组生命周期字段是契约与数据源现实不符，在 canonical-event.v1 下一修订中降为可选；契约保持 DRAFT、不冻结。
- 盘点报告 notes/WP-F4-INVENTORY-REPORT.md 已经总控亲验：生产代码与测试零影响面（EventContractValidator 本就未校验这些字段；CanonicalEventSchemaParityTest 未断言 payload 必填列表）。盘点报告中的文件路径前缀有误，本规约一律以下列真实路径为准。

## 只许改这 4 个文件（仓库内），其他任何文件不得动
1. `contract-specs/schemas/canonical-event.v1.schema.json`
2. `docs/contracts/event-contract.md`
3. `contract-specs/VERSION`
4. `contract-specs/README.md`

不得改任何 Java/Scala/测试/脚本文件；不得碰仓库根的未跟踪目录 `sample-data/`。

## 改动明细
### 1. schema（仅删 required 条目，字段定义 properties 原样保留）
从 `$defs` 的 required 数组中删除且仅删除以下 6 个条目：
- `order_created.required`：删 `"status"`、`"created_at"`（保留 order_id/user_id/items/total_amount）
- `order_paid.required`：删 `"paid_at"`（保留 order_id/user_id/payment_id/amount）
- `order_cancelled.required`：删 `"cancelled_at"`（保留 order_id/user_id/reason）
- `refund_created.required`：删 `"created_at"`（保留 refund_id/order_id/user_id/amount/reason）
- `refund_completed.required`：删 `"completed_at"`（保留 refund_id/order_id/user_id/amount）
schema 其余内容一概不动：`schema_version` 的 const 仍为 `"1.0"`、`x-contract-status` 仍为 `DRAFT`、不新增任何文字（避免触发裸锚点门禁）。

### 2. event-contract.md（仅改 6 行的必填标记）
下列 6 行把「必填」列的 `是` 改为 `否`，并在「说明」列末尾追加 `（Q6 裁决降为可选，2026-10-16）`：
- 2.4 order_created 表：`status` 行、`created_at` 行
- 2.5 order_paid 表：`paid_at` 行
- 2.6 order_cancelled 表：`cancelled_at` 行
- 2.7 refund_created 表：`created_at` 行
- 2.8 refund_completed 表：`completed_at` 行
警告：2.2 product_created 表的 `status` 行（on_sale/off_sale/pending）不是 A 组字段，绝对不要改。文档其余内容不动，`schema_version: "1.0"` 不动。

### 3. VERSION
文件全文现为 `contract-specs 2.2.0`，改为 `contract-specs 2.3.0`（格式不变，仅版本号）。

### 4. contract-specs/README.md
(a) 第 3 行长行中：`版本：` 后反引号内的 `2.2.0` 改为 `2.3.0`；并在该行版本沿革链中 `2.1.0 → 2.2.0` 对应 CT 批次的那一条之后、`，均按` 之前，插入一条：`，`2.2.0 → 2.3.0` 对应 WP-F4（Q6 裁决第 3 条落地：canonical-event.v1 的 A 组生命周期字段降为可选，见第 15 节）`。该行其余文字一字不动。
(b) 在文件末尾追加第 15 节，沿用第 14 节的写法与结构，内容须包括：
- 标题：`## 15. WP-F4 落地：当前态版本串 2.2.0 → 2.3.0（2026-10-16）`
- 背景：Q6 裁决第 3 条（A 组 5 个字段/6 处：order_created 的 status 与 created_at、order_paid 的 paid_at、order_cancelled 的 cancelled_at、refund_created 的 created_at、refund_completed 的 completed_at）；商城侧结构性不产出这些字段；黄金集 51 行接受事件中 18 行缺这些字段。
- 变更对照表：逐文件列出改了什么（schema 删 6 个 required 条目、event-contract.md 改 6 行必填标记、VERSION 升版、本节新增），并明确「未改」项：任何代码与测试（校验器本就只校验字段子集、结构对账测试未断言 payload 必填列表，故零影响）、schema_version 仍为 1.0、契约仍为 DRAFT 不冻结。
- 影响：采集门结论不变（A 源 51 接受/4 隔离、B 源 38 接受/5 隔离）；严格 schema 口径下 A 组 18 行不再因缺生命周期字段失败；本次属加法/放宽兼容变更，按第 3 节目录级规则 minor 递增。
- 指纹小节：为 schema 文件留一行，格式仿第 14 节指纹表，哈希与字节数先写占位符 `__LEAD_FILL_RAW_SHA256__`、`__LEAD_FILL_LF_SHA256__`、`__LEAD_FILL_BYTES__`（你没有命令工具算不了哈希，总控验收时亲算回填，占位符不得改写或省略）。
(c) 裸锚点门禁（硬约束）：新增的第 15 节正文中禁止出现 `§` 字符（门禁扫描 contract-specs 目录，新增裸锚点即失败）；引用章节一律写「第 N 节」中文形式，引用其他文件写全路径文件名但不带 `§`。

## 完成标准
- git diff 只含上述 4 个文件、只含上述改动。
- 完成后在回复末尾输出一行：WPF4-DONE
- 你只有文件读写工具，不运行任何命令；构建与测试由总控亲跑验收。
