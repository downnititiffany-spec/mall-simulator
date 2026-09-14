# 项目文档索引

此目录只保存项目自身的设计、实施、论文和答辩材料。

## 当前权威文档

- `项目完整实施指导书 V2.5.md`：唯一当前实施指导书。明确 WSL 单节点 HDFS/Hive Metastore/Spark 主环境、P3-02 映射裁决、质量规则版本、安全 IT、阶段验收；2026-09-14 生效，文档目标不代表已实现。
- `项目实施进度与任务看板 V2.5.md`：唯一当前执行队列，38 个任务包，含状态、依赖、允许范围、验收及反馈字段。旧编号通过 V25-R01 保留交叉映射，不能遗漏旧要求。
- `contracts/` 与 `contract-specs/`：已冻结业务契约；优先于实现。V2.5 新映射语法须先执行 V25-C01 冻结载体，不能就地改变旧 profile 的含义。
- `superpowers/specs/2026-09-11-mall-agnostic-platform-design.md`、`superpowers/plans/2026-09-11-mall-agnostic-platform-implementation.md`：既有商城无关化专项与文件导航，和 V2.5 冲突处按新版裁决先更新契约。
- `superpowers/specs/2026-09-11-ai-assisted-warehouse-onboarding-design-v1.0.md`、对应 implementation-v1.0：AIW-001～020 详细计划继续保留，生产接线等待 P5。
- `design/基于Spark大数据平台和智能分析模型的电商用户行为分析系统设计与实现——项目设计文稿 V2.2.md`：总体设计历史基线；当前执行变更见 V2.5。本次不修改原设计文稿。
- 指导书 V2.0～V2.4、看板 V2.2 与未编号旧看板、旧事实记录：只读历史，不再用于追加新任务或新裁决。
- `acceptance/local-readiness-20260914.md`：09-14 实测与 WSL 准备度；`acceptance/v25-document-release-20260914.md`：本次文档备份与发布检查。
- `remediation-status.md`、`deployment.md`、历史验收清单：辅助历史信息，尚未全部同步 WSL；不作为 WSL 已部署或全项目完成证明。
- `acceptance/`：独立、脱敏的验收证据。旧覆盖表229项不等于原269项复查，错误指针与结论由 V25-R01 修复。

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
| `superpowers/plans/` | 实施计划；是否有效看文件头状态，按 V2.5 看板重新确认前置，不继承旧 READY |

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
4. 当前结论以 **V2.5**、V2.5 看板和可复跑证据为准；269 条统计是 `34f37a8` 的冻结快照，不随代码变化自动更新。
5. 文件名使用清楚的主题和版本；测试日志、临时 JSON、token 不进入 `docs/`。
6. 文档中引用的证据必须能在仓库内定位（`.verify/` 报告、`docs/acceptance/` 导出、测试报告）；
   仓库外材料不进入本索引，也不作为完成证据。
7. 新的重大决策和开发事实使用独立 ADR/验收文件，不继续扩写旧的巨型事实日志；当前阶段不写论文正文。没有同环境基线时不得声称提升百分比。
