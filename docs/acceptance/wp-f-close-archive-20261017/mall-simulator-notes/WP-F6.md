# WP-F6 规约：GitHub Actions（default + spark 两档）

## 背景
- FULL-PROGRAM 队列 WP-F6：为 `scripts/run-tests.ps1` 的 default 与 spark 两档建 GitHub Actions 工作流，JDK/Node 环境与本机脚本口径对齐；推送后由总控观察首跑并修正至绿（或登记外部限制）。
- 你只有文件读写工具：不运行任何命令、不连库、不改 PROJECT_STATUS.md。工作流的推送与首跑观察由总控执行。

## 动手前必读
1. `scripts/run-tests.ps1` 全文：两档分别如何定位 JDK（17/8 的路径或环境变量名）、Maven、Node；各自的环境变量与 RunId 注入方式；退出码契约。
2. 若脚本把某个工具路径写死为本机绝对路径（如盘符路径），先判断 GitHub windows-latest runner 上能否用 setup-* 动作把工具装到该路径或用脚本已支持的环境变量覆盖；不能则不要硬造工作流，转入「阻塞报告」路径（见交付物 2）。

## 交付物
1. 报告 `~/workspace/projects/mall-simulator-notes/WP-F6-REPORT.md`：
   - run-tests.ps1 两档的工具定位方式逐项盘点（文件行号为据）：JDK17、JDK8、Maven、Node 各自从哪来、被哪个环境变量/参数控制；
   - 工作流设计与脚本口径的对齐表（每个 setup 动作对应脚本的哪一处期望）；
   - isolated 档为何不入 CI 的说明（依赖本机私有 3307 隔离实例与指纹契约，托管 runner 无此环境——只登记，不尝试绕过）。
2. 二选一：
   - 可行：新建 `.github/workflows/tests.yml`（仅此 1 个新文件放仓库内）：
     - 触发：push 到 `codex/cloud-v3-development`、pull_request、workflow_dispatch；
     - 两个独立 job：`default` 与 `spark`，均 runs-on: windows-latest，shell 显式 pwsh，工作目录仓库根；
     - 用 actions/checkout@v4、actions/setup-java@v4（distribution temurin）按对齐表装齐两档所需 JDK；Maven/Node 仅当脚本口径需要时用 setup 动作或 runner 预装（在报告中注明依据）；
     - default job 执行 `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default`；spark job 执行同脚本 `-Suite spark`（RunId 由脚本自生成或按脚本口径注入形如 `ci<yyyyMMddHHmm>` 的值，二选一并在报告中写明与脚本源码的对应关系）；
     - 不配置任何 secrets，不引用任何数据库/3307，不加 -AllowCountDrift（计数漂移必须让 CI 变红）；
     - job 超时按脚本在本机量级合理设置并在报告中注明取值理由。
   - 不可行：不建工作流文件，在报告中逐项写明阻塞点（脚本行号 + runner 差异 + 需要总控裁决的选项），结论段明确写「BLOCKED」。

## 约束
- 不改 run-tests.ps1 或任何既有脚本/代码；若发现只需极小的脚本侧适配（如新增一个可选环境变量覆盖），不要动手改，只在报告中提出建议与影响面，留待总控裁决。
- YAML 必须语法合法、缩进一致；动作版本只用上面钉的版本，不自创版本号。
- 交付说明（回复正文）列出：盘点结论摘要、交付物清单、对齐表要点。
- 完成后在回复末尾输出一行：WPF6-DONE
