# 项目文档索引

## V3.0权威入口

| 文件 | 定位与权限 |
|---|---|
| [项目完整实施指导书 V3.0](guidance/项目完整实施指导书%20V3.0.md) | 项目决策权威：目标、范围、八阶段、任务/标准、权限；仅总控 |
| [项目设计文档 V3.0](design/项目设计文档%20V3.0.md) | 正式设计权威：实现基线、模块/数据/接口/算法、部署、安全；仅总控 |
| [PROJECT_STATUS.md](PROJECT_STATUS.md) | 动态开发事实：HEAD、进度、Bug、测试、证据与下一步；代码Agent可维护，同名无版本号 |

**历史整理阶段已结束，正式进入毕业设计功能开发。** V3.0发布后不可原地修改，下一指导书和设计文档分别为V3.1；V2.x结束，不创建V2.9。Code Agent不可自行改两正式文档、项目目标、架构、范围、backlog优先级或宣布完整验收。

本页与根README只是导航，不是第四份权威。目标不代表已实现；当前状态见PROJECT_STATUS，证据按对应代码/输入/环境理解。

## 阅读顺序

1. 指导书：决定做什么，按八个阶段推进。
2. 设计文档：落实模块、数据、接口、算法及测试边界。
3. PROJECT_STATUS：领取当前切片，核对已做/未做、阻塞与最新证据。

开发阶段：基线确认 → 采集/数仓 → Spark指标 → Spring Boot服务 → Vue页面 → AI → 业务实链联调 → 部署验收与论文答辩。

## 现行测试导航

统一入口`scripts/run-tests.ps1`，档位default-tests/isolated-tests/spark-tests/all-tests，CLI值分别default/isolated/spark/all。

fresh：default **751**（632+13+106），isolated **55**（30+19+6），spark **111**（JDK8/ScalaTest/TestSuite.txt）；all顺序执行，任一失败或0 tests即失败。来源：[DEV-003c报告](acceptance/dev003c-unified-test-entry-20260915/REPORT.md)。本轮未重跑；单机in-memory测试不等于Hive/集群验收。

## 历史只读资料

以下原文、原名、原路径全部保留；不再作为当前任务或裁决入口：

- 所有《项目完整实施指导书V2.x》《毕业设计指导书V2.6/V2.7/V2.8》及更早指导书。
- design/内V1/V2设计文稿、设计文档和讨论稿。
- 所有历史任务看板、旧开发事实/决策日志、remediation-status.md。
- acceptance/**、contract-specs/**（根目录）、contracts/**、superpowers/**及历史专项材料。
- deployment.md、api-overview.md、compatibility-matrix.md、acceptance-checklist.md、demo/。
- 论文草稿、论文参考资料、答辩材料、实验和旧交接说明。

旧验收脚本可能按路径读取旧文档，因此禁止以目录美观为由搬移、重命名或删文件。历史文件自称“唯一”“当前”不推翻V3.0；旧测试结果仅用于对应历史时点。历史契约的有效语义已纳入新设计；若代码变更需调整契约，先报总控，不在本轮修改。

## 子目录用途

| 位置 | 用途 |
|---|---|
| guidance/ | V3系列正式实施指导书，已发布版只读 |
| design/ | 正式设计与原位历史稿，当前入口见顶部 |
| PROJECT_STATUS.md | 唯一动态状态，不再建并行看板 |
| acceptance/ | 既有证据本轮只读；未来开发可在授权下新增必要脱敏evidence |
| contracts/、architecture/、superpowers/ | 历史契约/架构/专项参考，不是当前事实源 |
| thesis-draft/、thesis-materials/、presentation/ | 既有论文/答辩材料保留；阶段8按实际交付统一编写 |
| backups/ | 授权修改前的原件备份，不是权威版本 |
| demo/ | 历史演示流程，使用前按V3部署和安全规则复核 |

## 更新规则

- 项目“要不要做”由总控更新指导书后继；“具体怎么做”由总控更新设计后继；实际做到了什么由代码Agent更新PROJECT_STATUS。
- 正式V3.0只读，后继V3.1，禁止final/new/(2)平行版本。代码Agent不能自行发布后继。
- 普通调试、日志、实验不进入指导书；状态文件记录事实与证据，不能改写目标。
- 本轮索引/状态修改前三份原件在`backups/v3-release-20260915/`；其他历史材料完全不动。
- DEV-003已整理收口，DEV-003d等进入development backlog；F-88仍限定验收。backlog只由总控在确实阻塞阶段时提升。
