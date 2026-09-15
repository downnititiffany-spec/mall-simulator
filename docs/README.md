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

## 历史资料导航

| 目录 | 内容 |
|---|---|
| [guidance/history/](guidance/history/) | 10份V1/V2指导书，历史只读 |
| [design/history/](design/history/) | 7份早期讨论稿及历史设计版本，历史只读 |
| [status-history/](status-history/) | 5份旧看板、事实决策记录及remediation-status |
| [audit/](audit/) | 4份完整性、兼容性、验证策略及验收参考 |
| [handover/](handover/) | 2份历史会话交接单 |
| [reference/](reference/) | 2份接口与部署参考，不代表最新实现 |

当前docs根目录仅保留README.md和PROJECT_STATUS.md；不存在原位兼容锚点。指导书目录仅V3.0为正式当前版；设计目录中历史版本全部进入history。根目录contract-specs保持不动。

## 历史证据与当前路径

V3.0 目录重构前形成的历史 acceptance / thesis evidence
可能引用重构前路径；
复核历史证据时应结合对应 Git commit 使用，
不得用当前 HEAD 的目录布局反推历史路径错误。

历史commit同时保留当时文件路径与脚本路径。历史资料正文及其相对链接不批量改写；查看历史互链时使用对应commit。当前导航全部指向迁移后目录。已发布V3.0正文不可改，正文内的历史路径同样按其发布commit解释。

## 当前脚本迁移适配

扫描当前scripts入口及全仓ps1/sh/py/js/ts/java/scala/xml/yaml等文本，包含隐藏.verify；排除.git、依赖/编译产物与备份。当前构建、启动、统一测试入口未发现直接读取被迁移Markdown的路径；代码中的说明性提及不是读取依赖。

以下8个保留在HEAD的取证工具仍含文件读取、存在检查或Git路径检查，已定点适配13行路径。其余历史报告和脚本内用于重放旧文本的字符串不批量替换。此适配不等于授权重跑旧任务；脚本可能包含旧时点断言、写入或环境依赖，本轮均未执行。被忽略的.verify一次性历史修补脚本不作为现行工具，不修改也不运行。

| 脚本（相对docs/acceptance/） | 用途 | 旧路径 → 新路径 |
|---|---|---|
| m1-5-contract-sync-20260912/scripts/verify-state.ps1 | 读取原指导书核对契约 | docs/项目完整实施指导书 V2.1.md、V2.3.md → docs/guidance/history/同名文件 |
| m1-5-contract-sync-20260912/scripts/erratum.ps1 | 读取原指导书作历史勘误 | 同上；仅ReadAllText路径适配，不改旧文本替换条件 |
| e4-cluster-1000-20260912/tools/e4-precommit-verify.ps1 | 看板读取及Git变更路径检查 | docs/项目实施进度与任务看板 V2.2.md → docs/status-history/同名文件 |
| graduation-lane-backup-20260912/manifests/New-TrackedManifest.ps1 | 文件指纹正向对照 | 同上 |
| p2-03-surrogate-key-20260912/raw/fingerprint-cited-files.ps1 | 引用文件指纹采集 | 同上 |
| p2-04-dwd-source-projection-20260912/raw/collect-evidence-p2-04.ps1 | 读取指导书取证 | docs/项目完整实施指导书 V2.4.md → docs/guidance/history/同名文件 |
| p2-05-ods-rebuild-guard-20260912/raw/collect-evidence-p2-05.ps1 | 读取重建授权条文 | 同上 |
| p2-07-source-prefix-20260912/raw/e0-backup.ps1 | 备份前存在检查 | docs/deployment.md → docs/reference/deployment.md；docs/开发过程事实与决策记录.md → docs/status-history/开发过程事实与决策记录.md |

## 本轮范围与维护规则

本轮累计30次git mv（包含前轮5次），历史文档正文不变；只修改两个README和上述8个脚本的路径。PROJECT_STATUS、两份V3.0、业务代码、测试逻辑、历史验收结论、contract-specs均不改。不commit、不push、不amend、不force push，不开始新的开发或历史复验任务。

修改前索引及适配脚本备份位于docs/backups/head-layout-20260915/，被现有Git规则忽略。前轮备份继续保留。本页是导航，不是第四份权威文档；正式指导书与设计的后继版本均为V3.1。
