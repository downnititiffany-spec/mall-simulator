# 关键路径交付计划（总控裁决，2026-10-16，用户批准）

用户裁决：按答辩关键路径交付，剩余项列成后续清单。F-88 维持「限定验收」，
关键路径完成后登记一次「关键路径验收」，不宣称 V3.0 全量验收。

## 关键路径（IN，按序执行）
1. WP-2A Mapper + dry-run（a 切片全部：引擎 + 接口）— 进行中（2A-1 修复轮）。
2. 切片 (b)：两源（mock-mall、fixture-b）采集接入、双清单、重放，含 Q4/Q5/Q6 按既定裁决落地。
3. 切片 (c)：两源入仓 ODS→DIM/DWD→DWS→ADS，逐层对账数字留痕。
4. 阶段 3：核心指标集（答辩演示口径）由 Spark 基于真实两源数据计算，发布 MySQL 指标快照。
5. 阶段 4/5：服务层接已发布快照；演示页面打通核心链路（指标看板、AI 解释、决策跟踪的既有骨架页面）。
   - 前置包 WP-UI（用户 2026-10-16 插入，排在阶段 4/5 之前、WP-N1B 之后）：WebUI 统一换肤。用户理由：现界面零优化且后续越复杂越难改。方向已定 = **方案 B「云白极简」**（浅色 SaaS 风：白/浅灰底、单一品牌蓝、柔和阴影、大留白；四方案效果图存 Drive「Muse 生成文件」与 ~/workspace/ui-concepts/）。做法：设计变量（主题色/圆角/阴影/字体/间距）+ 应用外壳布局 + 公共组件（AnalysisContext/BaseChart/DataTable/SnapshotSelector 等）统一改造，各视图继承，不逐页重写；web 450 测试为行为护栏。仅视觉层，不改数据与交互契约。
6. 阶段 6：AI 解释接真实 LLM，只消费已发布快照；产出 DECISION_EFFECT 所需的解释与记录。
   - 前置决策（用户）：LLM 通道选定（账号/成本事项）。候选：Gemini API（与现有 Google AI Pro 同体系）或其他可用 API。需在切片 (c) 完成前定。
   - ✅ 已定（2026-10-16）：DeepSeek 官方 API（零代码改动，配置 llm.base-url/api-key/model）。Key 由用户提供，仅存本机 ~/workspace/toolchain/llm-deepseek.env（600，不入仓不入备份）。用户成本纪律：不得用真 key 反复测试；接入验收时至多一次最小冒烟调用，超出先告知用户。截至定版时零 API 调用。
7. 阶段 7：本机端到端联调一轮完整链路，对账数字齐全。
8. 阶段 8（关键路径部分）：本机部署 + 关键路径验收登记 + 答辩证据包（链路截图、关键数字、测试基线）。

## 更正（2026-10-16，WP-N2 盘点复核）
- 旧记「PipelineService/SparkStageExecutor 的 BUILD_DWD 排序缺陷」**不存在于现行生产代码**：SparkStageExecutor.java 现行序为 dim→bdw→tdw 且有专门注释，提交 3c357fb（2026-09-15）早已修好；缺陷说法只残留在 DimDwdChainExecSpec 的过期头注释里，WP-N1 盘点引其自述未复核 Java 侧。此项自后续清单移除；阶段 3–5 可走生产编排（PipelineService）。WP-N1B 当时绕开编排直驱 JobRegistry 仍是有效证据，只是其理由作废。
- mxp→发布器接缝已接线：PipelineService PUBLISH_METRIC 阶段进程内跑 pub+mxp（spark-submit），再调 MetricPublisherPort.publish()，接缝为同机本地文件（JSONL + _export.json 清单）。详见 WP-N2-INVENTORY.md。

## 后续清单（OUT，本轮不做，保留在册）
- 远程集群验证、共享 HMS 验证（F-88 挂账项）。
- F-88 其余未验项中非关键路径部分；F-93 清理；DEV-003d、DEV-004 收编类待办。
- 核心指标集之外的全量指标扩展；两源之外的新数据源接入。
- 前端打磨与前端统一测试入口；GitHub Actions。
- 性能/规模化验证（大数据量压测类）。

## 估算（关键路径口径）
约 5–7 个有效开发日。阶段 4/5 完成度审计在阶段 2 收尾时做，届时修订本估算。

## 测试节奏裁决（总控，2026-10-16，用户提出后采纳）
本项目是毕业设计，不按生产级回归标准投入。三档节奏：
- 包级验收：只跑受影响模块的测试全绿（约 1 分钟级）。
- 切片级验收：default 全量一轮。计数漂移不再逐包改脚本基线——用总控专属
  -AllowCountDrift 放行并在 PROJECT_STATUS 本轮记录中写明漂移构成；
  基线数字在阶段门统一重登记。
- 阶段级/最终验收：三档全量（default + spark + isolated）。
新功能只写行为测试，不再新增字节冻结巡逻一类的重型验证；
既有巡逻测试保留在 default 档内随跑，不扩展、不删除。
