# 项目文档索引

此目录只保存项目自身的设计、实施、论文和答辩材料。

## 当前权威文档

- `项目完整实施指导书 V2.2.md`：三程序边界、商城无关化、集群、数据结构、算法、测试，以及受控 AI 数仓设计规则（**最高实施依据**）。
- `项目实施进度与任务看板 V2.2.md`：唯一任务执行入口；记录 P1–P5 与 AIW-001～020 的状态、Owner、依赖、反馈时间和证据。
- `superpowers/specs/2026-09-11-mall-agnostic-platform-design.md`：已批准的商城无关化设计。
- `superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md`：P1–P5 文件级实施与验收计划。
- `superpowers/specs/2026-09-11-ai-assisted-warehouse-onboarding-design-v1.0.md`：AI 辅助多商城接入、数仓蓝图、校验、sandbox、审批与回滚的专项设计。
- `superpowers/plans/2026-09-11-ai-assisted-warehouse-onboarding-implementation-v1.0.md`：AIW-001～020 文件/接口/算法/测试级实施计划；生产接线等待 P5。
- `项目完整实施指导书 V2.1.md`、`项目实施进度与任务看板.md`、`开发过程事实与决策记录.md`：V2.2 更新前历史快照；不再作为当前执行入口。
- `项目完整实施指导书 V2.0.md`、`项目整改实施指导书 V1.0.md`：更早历史依据；与 V2.2 冲突时以 V2.2 为准。
- `remediation-status.md`：按实际证据更新的整改状态（每个阶段含"如实登记的边界"）。
- `design/基于Spark大数据平台和智能分析模型的电商用户行为分析系统设计与实现——项目设计文稿 V2.2.md`：
  完整毕业设计方案；同目录 V1.0、V2.1 与 `毕业设计讨论稿.md` 为历史版本。
- `contracts/`：冻结口径（事件契约、指标字典、指标血缘、R7-4 看板信封、R8 证据/安全/决策契约）；
  代码与文档冲突时**先改契约再改代码**。
- `deployment.md`：LOCAL 真实链路的历史部署说明；当前已形成分析平台 8091、参考商城 8090、生成器 8092 三程序，部署文档尚待按三程序更新。
- `acceptance/`：R9 真机验收证据目录（`r9-<日期>-<commit>-run<id>-<快照>/`，含库导出、API 响应、页面截图与黄金对账表）。

## 子目录

| 目录 | 内容 |
|---|---|
| `acceptance/` | R9 验收证据（每个目录一份 `README.md` 说明文件清单与关键结论） |
| `design/` | 设计讨论稿和历版项目设计文稿（V1.0 / V2.1 / V2.2） |
| `architecture/` | `毕业设计架构评审摘要.md`：架构评审与边界摘要 |
| `contracts/` | 事件契约、指标字典等唯一业务口径（5 份契约文件） |
| `demo/` | `demo-script.md`：演示流程；R7-4 已拆成"分析平台 8091 + 模拟商城 8090"两进程，演示前按 `deployment.md` 第 3/5 节核对步骤 |
| `thesis-draft/` | 历史论文草稿；当前状态 `DEFERRED`，项目完成前不继续撰写 |
| `thesis-materials/` | 历史论文材料；当前只允许追加真实证据，不提前组织论文结论 |
| `presentation/` | 答辩网页 PPT（`index.html` + `motion.min.js` + `images/`） |
| `backups/` | 每次修改文稿前的原文备份，不作为当前依据 |
| `superpowers/plans/` | 实施计划；是否有效看文件头状态，当前 P1–P5 实施书为 `READY` |

## 根目录单篇文档

- `acceptance-checklist.md`：验收项目；部分条目仍指向已删除的旧文件（如旧流水线测试、根目录 PPT），
  核对前先看 `remediation-status.md` 与 `acceptance/` 的真机证据。
- `api-overview.md`：API 概览（已按 R8 真实实现对齐）。
- `compatibility-matrix.md`：技术版本与兼容性记录；集群相关行未实跑，以 `remediation-status.md` 为准。
- `deployment.md`：部署说明（两进程 LOCAL 真实链路）。
- `r6-verification-strategy.md`：R6 分级测试策略。
- `session-handover-2026-09-07.md`、`session-handover-2026-09-10.md`：历史交接记录（含各自的下一步与遗留边界）。

## 文档规则

1. 修改任何文稿前，复制原文件到 `backups/` 并带上日期或阶段名。
2. 已编号指导书不覆盖、不在文末无限追加新阶段；同一建设阶段用 `V2.x` 迭代。V2 系列出口完成且核心架构/主数据模型/建设阶段发生大变化时建立 `V3.0`。
3. 指导书与进度看板版本一致；需求范围变化建立新版本。日常执行只修改当前看板的状态和证据字段，并在修改前备份。
4. 当前结论以 V2.2、V2.2 看板和可复跑证据为准；269 条统计是 `34f37a8` 的冻结快照，不随代码变化自动更新。
5. 文件名使用清楚的主题和版本；测试日志、临时 JSON、token 不进入 `docs/`。
6. 文档中引用的证据必须能在仓库内定位（`.verify/` 报告、`docs/acceptance/` 导出、测试报告）；
   仓库外材料不进入本索引，也不作为完成证据。
7. 新的重大决策和开发事实使用独立 ADR/验收文件，不继续扩写旧的巨型事实日志；当前阶段不写论文正文。没有同环境基线时不得声称提升百分比。
