# WP-F6b 规约：脚本 CI 适配（总控裁决 Option A）+ GitHub Actions 工作流落地

## 总控裁决（2026-10-17 巡检，总控亲验后裁定，执行方不得再议）
- WP-F6 的 BLOCKED 报告**采信关账**：总控亲验 `scripts/run-tests.ps1` L68-71（Maven/JDK/仓库默认值写死 D 盘）、L998-1002（Test-Path 硬门禁 Fail 1）、L1058（`Invoke-MavenRun` 硬编码 `-o`）、全脚本无 `JAVA_HOME_17_X64`/`JAVA_HOME_8_X64`/`MAVEN_CMD` 回退读取，与报告逐项一致；原规约「命令原样 + 不改脚本」约束下 BLOCKED 成立。
- 方案 B（CI 预热绕行）**否决**：脆弱且与 `-o`+写死仓库路径自相矛盾。方案 C（自托管 runner）**否决**：成本与可移植性目的相悖。
- 采纳**方案 A（精化版）**：给 `run-tests.ps1` 加非破坏性解析层 + `-Online` 开关，再按原 WP-F6 规约建工作流。本包即该裁决的实施包。

## 你只有文件读写工具：不运行任何命令、不连库、不改 PROJECT_STATUS.md、不改 FULL-PROGRAM.md。构建/测试/推送/首跑观察由总控执行。

## 交付物 1：`scripts/run-tests.ps1` 最小适配（只许动下列四处，其余字节不动）
1. **参数块**：在 `[switch]$Confirm` 后新增 `[switch]$Online`（含一行中文注释：CI 在线模式，不加 `-o`；缺省关＝现行离线行为不变）。参数名不得含 `Password`。
2. **解析层**：在 param 块与 `$ErrorActionPreference` 之后、`$RunIdPattern` 定义之前（或紧随其后、任何门禁之前）插入解析段，规则严格如下（显式传参最高优先，用 `$PSBoundParameters.ContainsKey(...)` 判定）：
   - `MavenCmd` 未显式传入时：`$env:MAVEN_CMD` 非空则用之；否则若现行默认值路径不存在且 `Get-Command mvn.cmd`/`mvn` 可得，则用其 Source；否则保持默认值。
   - `MavenRepoLocal` 未显式传入时：`$env:MAVEN_REPO_LOCAL` 非空则用之；否则保持默认值。
   - `JdkDefault` 未显式传入时：`$env:JAVA_HOME_17_X64` 非空则用之；否则保持默认值。（不要引入 JAVA_HOME 猜测逻辑，保持确定性。）
   - `JdkSpark` 未显式传入时：`$env:JAVA_HOME_8_X64` 非空则用之；否则保持默认值。
   - 每项实际改写时用 `Write-Host` 打一行解析记录（如 `[CI-ADAPT] MavenCmd <- MAVEN_CMD: <值>`），未改写不打。
   - 必须兼容 `Set-StrictMode -Version Latest`：所有 `$env:` 读取对未设变量安全（返回 $null）。
3. **离线开关**：`Invoke-MavenRun` 内 `$mvnArgs` 构造改为：`$Online` 为真时不含 `'-o'`，为假时与现行完全一致（含顺序）。其余参数（`-Dmaven.repo.local` 等）不变。
4. **头部注释**：在脚本头部用法注释区补一行说明 `-Online` 与四个 env 回退变量（MAVEN_CMD / MAVEN_REPO_LOCAL / JAVA_HOME_17_X64 / JAVA_HOME_8_X64）的存在与优先级（显式参数 > env > 原默认值）。
   - 红线：不得触碰 `Test-BaselineComparison` 函数与任何计数比较点；不得改任何默认值字面量；不得改门禁退出码；解析层不得连网、不得建目录。

## 交付物 2：`.github/workflows/tests.yml`（仓库内唯一新文件）
- 触发：push 到 `codex/cloud-v3-development`、`pull_request`、`workflow_dispatch`。
- 两个独立 job，均 `runs-on: windows-latest`、默认 shell pwsh、工作目录仓库根：
  - `default`：`actions/checkout@v4` → `actions/setup-java@v4`（temurin 17）→ 执行 `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default -Online -MavenRepoLocal "$HOME/.m2/repository"`（JDK 与 Maven 走 env/PATH 回退：setup-java 导出 JAVA_HOME_17_X64、runner 预装 Maven 在 PATH）；`timeout-minutes: 90`。
  - `spark`：`actions/checkout@v4` → `actions/setup-java@v4`（temurin 8）→ 执行 `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite spark -Online -MavenRepoLocal "$HOME/.m2/repository"`（JAVA_HOME_8_X64 回退）；`timeout-minutes: 60`。
- 不配置任何 secrets；不引用数据库/3307；不加 `-AllowCountDrift`；动作版本只用 checkout@v4 与 setup-java@v4。
- YAML 语法合法、缩进一致（2 空格）。

## 交付物 3：报告 `~/workspace/projects/mall-simulator-notes/WP-F6b-REPORT.md`
- 逐项列出对 run-tests.ps1 的四处改动（改前/改后片段对照）；
- 回退优先级与本机兼容性论证（原开发机 D 盘布局、云端 VM 显式传参两种既有用法为何零影响）；
- 工作流与脚本口径对齐表（更新版，逐项对应解析层/env/开关）；
- 超时取值理由（按本机量级：default 1540 用例、spark 372 用例，叠加托管 runner 首次在线拉依赖）；
- isolated 档不入 CI 的说明沿用 WP-F6 报告第 4 节结论，一句话引用即可。

## 约束
- 除交付物 1 的四处与交付物 2 的新文件外，不改任何既有文件（含 run-isolated-tests.ps1、契约测试、基线数字）。
- 交付说明（回复正文）列出：改动清单、对齐表要点、兼容性结论。
- 完成后在回复末尾输出一行：WPF6B-DONE
