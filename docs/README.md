# 项目文档索引

此目录只保存项目自身的设计、实施、论文和答辩材料。外部论文与第三方项目统一放在 `../references/`。

## 当前权威文档

- `项目完整实施指导书 V2.0.md`：当前代码审计、目标架构、数据结构、算法、R6～R9 工作清单（**最高依据**）。
- `remediation-status.md`：按实际证据更新的整改状态（每个阶段含"如实登记的边界"）。
- `design/项目设计文稿 V2.2.md`：完整毕业设计方案；V1.0、V2.1 为历史版本。
- `contracts/`：冻结口径（事件契约、指标字典、指标血缘、R7-4 看板信封、R8 证据/安全/决策契约）；
  代码与文档冲突时**先改契约再改代码**。
- `deployment.md`：LOCAL 真实链路（本机 MySQL + 文件 Landing + 本机 spark-submit + 嵌入式 Derby Hive）
  两进程部署、配置、跑链与验收命令。

## 子目录

| 目录 | 内容 |
|---|---|
| `design/` | 设计讨论稿和历版项目设计文稿 |
| `architecture/` | 架构评审与边界摘要 |
| `contracts/` | 事件契约、指标字典等唯一业务口径 |
| `demo/` | 演示流程；R7-4 已拆成"分析平台 8091 + 模拟商城 8090"两进程，演示前按 `deployment.md` 第 3/5 节核对步骤 |
| `thesis-draft/` | 论文各章节草稿 |
| `thesis-materials/` | 论文提纲、实验表和截图清单 |
| `presentation/` | 答辩网页 PPT |
| `backups/` | 每次修改文稿前的原文备份，不作为当前依据 |
| `superpowers/plans/` | 历史实施计划，用于追溯，不代表当前完成状态 |

## 根目录单篇文档

- `acceptance-checklist.md`：验收项目，但旧证据需要按独立平台重新核验。
- `api-overview.md`：API 概览。
- `compatibility-matrix.md`：技术版本与兼容性记录。
- `deployment.md`：部署说明（两进程 LOCAL 真实链路）。
- `r6-verification-strategy.md`：R6 分级测试策略。
- `session-handover-2026-09-07.md`、`session-handover-2026-09-10.md`：历史交接记录（含各自的下一步与遗留边界）。

## 文档规则

1. 修改任何文稿前，复制原文件到 `backups/` 并带上日期或阶段名。
2. 当前结论以 V2.0 指导书和最新整改状态为准，历史计划不可直接当作完成证据。
3. 文件名使用清楚的主题和版本；测试日志、临时 JSON、token 不进入 `docs/`。
4. 引用外部论文时使用 `../references/papers/` 中的索引或 BibTeX。
