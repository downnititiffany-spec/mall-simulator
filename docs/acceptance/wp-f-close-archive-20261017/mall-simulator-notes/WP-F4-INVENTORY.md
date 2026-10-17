# WP-F4 盘点规约（只读盘点，先盘点后动）

## 任务
为 WP-F4（canonical-event.v1 契约修订）做影响面盘点。背景：Q6 裁决（见仓库 docs 与 mall-simulator-notes/Q6-RULING.md）要求将 canonical-event.v1 契约中 Group-A 生命周期字段降为可选，契约保持 DRAFT 并附变更记录。FULL-PROGRAM 预计「零代码影响面」，本盘点负责证实或推翻该预计。

## 必查（逐项给出文件路径 + 行号/原文摘录，不许凭印象）
1. canonical-event.v1 契约文件本体在哪（JSON Schema 或文档）、当前版本与 DRAFT 标记、Group-A 生命周期字段的完整清单（字段名、当前必填/可选、类型）。
2. 哪些代码读取/校验该契约：EventContractValidator 及一切引用契约字段必填性的类、测试（含断言 Group-A 字段必填会失败的用例），逐个列出。
3. 两个真实来源（mock-mall 金标集、fixture-shop-b profile）的事件数据里 Group-A 字段的实际出现情况（结合 WP-N1A/N1B 既有证据与仓库内夹具/样本文件核对）：降为可选后，现有数据的校验结论会不会变。
4. 契约的变更记录（changelog）现在写在哪、什么格式；仓库里引用该契约版本号的其他文档/代码点。
5. 结论：修订的最小动作清单（改哪个文件哪几行、要不要动代码、要不要动测试），以及每个动作的风险点。若发现预计之外的代码影响面，明确标红并给出证据。

## 约束
- 只读盘点：不改仓库任何文件；唯一产出是盘点报告文件 `~/workspace/projects/mall-simulator-notes/WP-F4-INVENTORY-REPORT.md`（写在 notes 目录，不进仓库）。
- 你只有文件读写工具，不运行任何命令。
- 报告完成后在回复末尾输出一行：WPF4INV-DONE
