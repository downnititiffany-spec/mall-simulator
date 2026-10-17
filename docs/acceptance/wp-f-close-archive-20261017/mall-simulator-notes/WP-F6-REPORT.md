# WP-F6 盘点与对齐报告：GitHub Actions 工作流（default + spark 两档）

> **报告日期**：2026-10-16  
> **依据规约**：`~/workspace/projects/mall-simulator-notes/WP-F6.md`  
> **执行模式**：纯静态代码与环境规范对齐分析（零命令执行、零数据库连接、零修改既有脚本与代码）  
> **最终结论**：**BLOCKED（阻塞）** —— 暂不建立 `.github/workflows/tests.yml`，等待总控就脚本侧极小环境变量适配做出裁决。

---

## 1. 执行摘要 (Executive Summary)

本报告针对 `scripts/run-tests.ps1` 的 `default` 与 `spark` 两档自动化测试体系在 GitHub Actions `windows-latest` 托管 runner 上的可行性进行了逐行盘点与环境对齐分析。

核心发现如下：
1. **工具定位机制写死本机绝对盘符路径**：`scripts/run-tests.ps1` 在参数声明区（第 68-71 行）将 Maven (`D:\apache-maven-3.9.14\bin\mvn.cmd`)、默认档 JDK17 (`D:\Develop\JAVA17`)、Spark 档 JDK8 (`D:\Develop\JDK1.8`) 及本地 Maven 仓库 (`D:\maven_repository`) 均硬编码为 Windows D 盘固定绝对路径，且在环境门禁（第 997-1003 行）用 `Test-Path` 强行校验文件存在性，缺失即 `exit 1` 失败退出。
2. **缺乏环境变量回退机制**：脚本**未读取**任何标准环境变量（如 `$env:JAVA_HOME`、`$env:JAVA_HOME_17_X64`、`$env:JAVA_HOME_8_X64`、`$env:M2_HOME` 或 `$env:PATH`）来作为参数的默认值或覆盖值；相反，脚本会在运行期将自身硬编码的 `$JdkDefault` / `$JdkSpark` 无条件赋给 `$env:JAVA_HOME` 并强行拼入 `$env:PATH` 前缀（第 1054-1055、1209、1275 行）。
3. **Maven 离线参数 `-o` 硬编码阻断托管依赖解析**：脚本在底层调用 `Invoke-MavenRun`（第 1058 行）时，硬编码了 `-o`（离线）选项以及 `-Dmaven.repo.local=$MavenRepoLocal`。GitHub 托管 runner 启动时不存在预热好的 `D:\maven_repository`，在 `-o` 约束下 Maven 无法从远程下载插件与依赖，必然立刻失败。
4. **托管 Runner 与 setup-* 动作无法满足硬编码路径**：GitHub Actions 官方 `actions/setup-java@v4` 将 JDK 安装在 `$RUNNER_TOOL_CACHE`（如 `C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\...`），并不支持将 JDK 安装到 `D:\Develop\...` 指定路径；官方 runner 预装的 Maven 也位于 `C:\ProgramData\chocolatey\bin\mvn.cmd`。
5. **规约判定路径触发**：根据 WP-F6 规约第 2 条（「*若脚本把某个工具路径写死为本机绝对路径，先判断 GitHub windows-latest runner 上能否用 setup-* 动作把工具装到该路径或用脚本已支持的环境变量覆盖；不能则不要硬造工作流，转入『阻塞报告』路径*」）与约束（「*不改 run-tests.ps1 或任何既有脚本/代码；若发现只需极小的脚本侧适配，不要动手改，只在报告中提出建议与影响面，留待总控裁决*」），本报告**不硬造无法跑通的工作流文件**，正式定性为 **BLOCKED**，并给出精确的脚本适配建议方案供总控裁定。

---

## 2. run-tests.ps1 工具定位机制逐项盘点（文件行号为据）

对 `scripts/run-tests.ps1`（全长 1469 行）全文检索与逐行静态分析结果如下：

### 2.1 JDK 17（Default 档）
- **参数声明与默认值**：
  - 第 70 行：`[string]$JdkDefault = 'D:\Develop\JAVA17',`
- **前置存在性门禁**：
  - 第 979 行：`function Resolve-JavaExe([string]$jdkHome) { Join-Path $jdkHome 'bin\java.exe' }`
  - 第 997 行：`if ($Suite -in @('default', 'isolated', 'spark', 'all')) {`
  - 第 999 行：`  if (-not (Test-Path -LiteralPath (Resolve-JavaExe $JdkDefault))) { Fail 1 ("找不到默认档 JDK：{0}" -f $JdkDefault) }`
  - 第 1000 行：`}`
- **运行期注入与执行**：
  - 第 1054 行：`$env:JAVA_HOME = $JdkHome`
  - 第 1055 行：`$env:PATH = (Join-Path $JdkHome 'bin') + ';' + $env:PATH`
  - 第 1181-1182 行：
    ```powershell
    $r = Invoke-MavenRun -Name $t.name -Pom (Join-Path $RepoRoot $t.pom) -ExtraArgs $t.extra `
      -JdkHome $JdkDefault -SysProps @() -LogName ("default-{0}.log" -f $t.name)
    ```
- **控制变量与来源**：由命令行参数 `-JdkDefault` 单一控制。**无任何环境变量回退或自动探测机制**。如果不显式传入参数，恒定读取 `D:\Develop\JAVA17`。

### 2.2 JDK 8（Spark 档）
- **参数声明与默认值**：
  - 第 71 行：`[string]$JdkSpark = 'D:\Develop\JDK1.8',`
- **前置存在性门禁**：
  - 第 1001-1003 行：
    ```powershell
    if ($Suite -in @('spark', 'all') -and -not (Test-Path -LiteralPath (Resolve-JavaExe $JdkSpark))) {
      Fail 1 ("找不到 spark 档 JDK8：{0}" -f $JdkSpark)
    }
    ```
- **运行期验证与注入**：
  - 第 1275 行：`$env:JAVA_HOME = $JdkSpark`
  - 第 1276-1280 行：直接通过 `& $MavenCmd -version` 取证，正则匹配 `Java version:\s*1\.8`，不匹配则后续判据判失败（第 1312 行 `$jdkOk`）。
  - 第 1285-1286 行：
    ```powershell
    $r = Invoke-MavenRun -Name 'spark-jobs' -Pom (Join-Path $RepoRoot 'spark-jobs\pom.xml') -ExtraArgs @() `
      -JdkHome $JdkSpark -SysProps @("-Dp2.test.runId=$RunId") -LogName 'spark-jobs.log'
    ```
- **控制变量与来源**：由命令行参数 `-JdkSpark` 单一控制。**无任何环境变量回退机制**。

### 2.3 Maven（两档共用）
- **参数声明与默认值**：
  - 第 68 行：`[string]$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd',`
  - 第 69 行：`[string]$MavenRepoLocal = 'D:\maven_repository',`
- **前置存在性门禁**：
  - 第 998 行：`if (-not (Test-Path -LiteralPath $MavenCmd)) { Fail 1 ("找不到 Maven：{0}" -f $MavenCmd) }`
- **运行期调度与参数拼接**：
  - 第 1056 行：`Remove-Item Env:\MAVEN_ARGS -ErrorAction SilentlyContinue`（强行移除环境中的 `MAVEN_ARGS`）
  - 第 1058 行：
    ```powershell
    $mvnArgs = @('-o', "-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
    ```
  - 第 1063 行：`& $MavenCmd @mvnArgs 2>&1 | Tee-Object -FilePath $log | Out-Host`
- **控制变量与来源**：由 `-MavenCmd` 与 `-MavenRepoLocal` 两个参数分别控制。**硬编码了 `-o`（离线）选项**，并且删除了外部可能传入的 `MAVEN_ARGS`。

### 2.4 Node / npm（仅 Web 档）
- **参数声明与默认值**：
  - 第 72 行：`[string]$NpmCmd = 'npm',`
- **门禁与调用范围**：
  - 第 1007-1013 行：
    ```powershell
    if ($Suite -eq 'web') {
      $webDir = Join-Path $RepoRoot 'web'
      if (-not (Test-Path -LiteralPath $webDir)) { Fail 1 ("找不到 web 目录：{0}" -f $webDir) }
      if (-not (Get-Command $NpmCmd -ErrorAction SilentlyContinue) -and -not (Test-Path -LiteralPath $NpmCmd)) {
        Fail 1 ("找不到 npm：{0}" -f $NpmCmd)
      }
    }
    ```
- **两档判定**：`default` 档（Java 三树）与 `spark` 档（ScalaTest）在运行期**完全不调用 Node/npm**，第 1007 行明确限定仅在 `$Suite -eq 'web'` 时才检查并调用 npm。故两档工作流无需配置 Node.js 环境。

### 2.5 RunId 注入与环境隔离机制
- **格式校验正则**：第 84 行 `$RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$'`
- **自生成逻辑**（S3-51 并发健壮性改进）：
  - 第 984-990 行：若未传入 `-RunId`，自动生成：
    `'dev003c_' + (Get-Date -Format 'yyyyMMdd_HHmmss') + '_' + ([guid]::NewGuid().ToString('N').Substring(0, 6))`
  - 格式完全符合正则要求。
- **两档注入方式**：
  - `spark` 档（第 1286 行）：通过 Maven 系统属性显式注入 `-Dp2.test.runId=$RunId`，作为测试产物与运行期隔离标识。
  - `default` 档（第 1181 行）：`-SysProps @()`（不直接透传给单测，但用于确定第 994 行的日志目录 `$LogDir = Join-Path $env:TEMP ("v25tests-{0}" -f $RunId)` 与第 1402 行的命名并发互斥量 `Local\v25tests-<hash>`）。
- **CI 传值口径**：由于脚本已具备符合契约的自生成机制，且 GitHub Actions 单 job 内为独占运行环境，无需在 CLI 显式传递形如 `ci<yyyyMMddHHmm>` 的值，缺省即可安全自生成；若显式传入，只要符合 `^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$` 亦完全受支持。

### 2.6 退出码契约 (Exit Code Contract)
依据第 48-55 行与源码各退出分支：
- `0`：所选档全部通过（用例数 > 0、F=0、E=0、测试产物本轮新写、用例数与登记基线 MATCH）。
- `1`：参数/环境错误（找不到 `mvn`、找不到 JDK17/JDK8、找不到 npm，第 998, 999, 1002, 1005, 1011 行）。
- `5`：执行前拒绝（RunId 格式非法第 992 行、缺 `-Confirm` 第 1018 行、缺隔离口令第 1024 行、并发锁冲突第 1412 行）。
- `6`：隔离档只读探针取数失败（由子脚本透传）。
- `7`：套件执行失败（Maven 非 0 退出、零用例、F/E 非 0、产物时间戳陈旧、计数与基线漂移，第 1201, 1326, 1464 行）。

---

## 3. 工作流设计与脚本口径对齐表

| 组件 / 依赖项 | GitHub `windows-latest` Runner 现状 / setup 动作能力 | `run-tests.ps1` 脚本既有口径期望 | 对齐结论与状态 | 差异影响与脚本行号 |
|---|---|---|:---:|---|
| **JDK 17**<br>(Default 档) | `actions/setup-java@v4`<br>`distribution: 'temurin'`<br>`java-version: '17'`<br>安装在 `C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\17.x.x\x64`，导出环境变量 `JAVA_HOME` 和 `JAVA_HOME_17_X64`。 | 第 70 行默认参数：<br>`$JdkDefault = 'D:\Develop\JAVA17'`<br>第 999 行校验：<br>`Test-Path 'D:\Develop\JAVA17\bin\java.exe'` | ❌ **不匹配**<br>(BLOCKED) | 脚本未读取 `JAVA_HOME` 或 `JAVA_HOME_17_X64`，直接校验 `D:\Develop\JAVA17`。Runner 缺失该路径时直接 `Fail 1` 退出（第 999 行）。`setup-java` 无法指定安装到 D 盘。 |
| **JDK 8**<br>(Spark 档) | `actions/setup-java@v4`<br>`distribution: 'temurin'`<br>`java-version: '8'`<br>安装在 `C:\hostedtoolcache\windows\Java_Temurin-Hotspot_jdk\8.x.x\x64`，导出环境变量 `JAVA_HOME_8_X64`。 | 第 71 行默认参数：<br>`$JdkSpark = 'D:\Develop\JDK1.8'`<br>第 1001-1002 行校验：<br>`Test-Path 'D:\Develop\JDK1.8\bin\java.exe'` | ❌ **不匹配**<br>(BLOCKED) | 脚本未读取 `JAVA_HOME_8_X64`，直接校验 `D:\Develop\JDK1.8`。Runner 缺失该路径时直接 `Fail 1` 退出（第 1002 行）。 |
| **Maven 可执行程序** | `windows-latest` 预装 Maven（位于 `C:\ProgramData\chocolatey\bin\mvn.cmd`），且位于系统 `PATH` 中。 | 第 68 行默认参数：<br>`$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd'`<br>第 998 行校验：<br>`Test-Path $MavenCmd` | ❌ **不匹配**<br>(BLOCKED) | 脚本未执行 `Get-Command mvn`，也未读取 `$env:M2_HOME`，直接校验 `D:\apache-maven-3.9.14\bin\mvn.cmd`。Runner 上缺失此路径，立即 `Fail 1`（第 998 行）。 |
| **Maven 本地仓库与离线模式** | Runner 初始本地仓库为空（通常在 `C:\Users\runneradmin\.m2\repository`）；依赖需在首次构建时联网拉取。 | 第 69 行默认参数：<br>`$MavenRepoLocal = 'D:\maven_repository'`<br>第 1058 行硬编码参数：<br>`@('-o', "-Dmaven.repo.local=$MavenRepoLocal", ...)` | ❌ **严重不匹配**<br>(BLOCKED) | 脚本强制携带 `-o`（离线）运行，且本地仓库指向 `D:\maven_repository`。即使解决了 Maven 路径，Maven 也会因为无本地缓存且禁止联网而失败。脚本未提供取消 `-o` 的开关。 |
| **Node.js / npm** | Runner 预装 Node/npm，但本工作流不涉及 Web 档。 | 第 1007 行：`if ($Suite -eq 'web')` |  **无需配置** | `default` 与 `spark` 档完全不触发 Node/npm 检查与执行。 |
| **工作目录与 Shell** | `working-directory: .`<br>`shell: pwsh` | 脚本在仓库根目录下运行，显式使用 `pwsh` 调用。 |  **对齐** | 符合 CI 设计规范。 |
| **基线与漂移放行** | 不配置 `-AllowCountDrift`，基线漂移直接 `Fail 7`。 | 第 1153-1164 行比较函数，漂移直接判错。 |  **对齐** | 严格保障质量门禁有效性。 |

---

## 4. isolated 档为何不入 CI 的说明

在 `scripts/run-tests.ps1` 与 `scripts/run-isolated-tests.ps1` 中，`isolated` 档专门用于运行有数据库写入行为的真实持久化集成测试（`IsolationGuardMySqlIT`、`MetricAdsMySqlIT`、`MetricPublisherMySqlIT`、`AdsDataQualityErrorRateNullableMySqlIT` 等）。

该档不纳入 CI 工作流具有不可动摇的客观环境限制与合规红线，绝不能在 CI 中尝试绕过：
1. **依赖本机私有 3307 独立 MySQL 实例与端口硬隔离**：
   - 隔离门禁唯一所有者 `TestIsolationGuard.java`（第 38-42 行）与各调度脚本严格锁定测试只能运行在 `127.0.0.1:3307` 实例上；任何使用默认 `3306` 端口的连接都会被写前校验直接拒绝（`stage7-http-isolated.ps1` 第 147 行、`TestIsolationGuard.java` 第 41 行）。
2. **实例指纹与主机名探针校验契约**：
   - `TestIsolationGuard.verifyBeforeWrite` 会通过实际 JDBC 连接查询 `SELECT DATABASE()`、`@@port`、`@@hostname`、`@@server_uuid`，并核验环境变量 `DEV001_IT_FINGERPRINT` 所绑定的主机与实例指纹。GitHub 托管 runner 无法满足该私有实例指纹契约。
3. **隔离双库与最小权限账号供给机制**：
   - 测试运行必须依赖预先由 `it-prepare-isolation.ps1` 基于本轮 RunId 创建的一组隔离数据库（`${RunId}_analytics_meta` 与 `${RunId}_analytics_metric`）及 `metaapp`、`metricapp`、`metricread` 专属受限账号，拒绝使用 `root` 运行（`TestIsolationGuard.java` 第 43 行）。
4. **凭据安全与零 Secrets 约束**：
   - 隔离档执行前置门禁（`run-tests.ps1` 第 1020-1028 行）要求必须存在环境变量 `$env:IT_GUARD_PASSWORD_MALL`、`$env:IT_GUARD_PASSWORD_GENERATOR`、`$env:V25_IT_META_PASSWORD`、`$env:V25_IT_METRIC_PUBLISH_PASSWORD`，缺一即 `Fail 5` 拒绝。
   - WP-F6 规约明文规定：「*不配置任何 secrets，不引用任何数据库/3307*」。在无口令、无 3307 实例的环境下，隔离档必然无法执行。

因此，`isolated` 档完全不具备进入托管 CI 的条件，仅应在具备私有 3307 隔离实例与受控环境变量的开发机/专有测试 VM 上运行。

---

## 5. 阻塞点深度剖析 (Blockers Breakdown)

### 阻塞点 1：脚本参数写死绝对盘符路径且无环境变量回退（脚本行号：68, 70, 71, 998, 999, 1002）
- **现象**：
  若在 CI 中执行规约规定的原生命令：
  `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default`
  `scripts/run-tests.ps1` 评估默认参数：
  - `$MavenCmd` 为 `D:\apache-maven-3.9.14\bin\mvn.cmd`
  - `$JdkDefault` 为 `D:\Develop\JAVA17`
- **结果**：
  执行至第 998 行时：
  ```powershell
  if (-not (Test-Path -LiteralPath $MavenCmd)) { Fail 1 ("找不到 Maven：{0}" -f $MavenCmd) }
  ```
  立即输出 `[REFUSE exit=1] 找不到 Maven：D:\apache-maven-3.9.14\bin\mvn.cmd` 并异常退出。
  若通过命令行为 `$MavenCmd` 赋值，则紧接着在第 999 行遭遇：
  `[REFUSE exit=1] 找不到默认档 JDK：D:\Develop\JAVA17`。
  Spark 档同样在第 1002 行遭遇：
  `[REFUSE exit=1] 找不到 spark 档 JDK8：D:\Develop\JDK1.8`。

### 阻塞点 2：底层 Maven 执行硬编码离线选项 `-o`（脚本行号：69, 1058）
- **现象**：
  即使在 CLI 显式传递所有路径参数，`Invoke-MavenRun` 在第 1058 行构造 Maven 命令行时强制加上了 `-o`（offline）参数：
  `$mvnArgs = @('-o', "-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps`
- **结果**：
  在 GitHub 托管 runner 上，本地 Maven 仓库是干净的，既无 Spring Boot、Surefire 插件缓存，也无 Scala 编译器与测试依赖。在离线模式下，Maven 构建会立即抛出 `Cannot access central in offline mode and the artifact has not been downloaded from it before`，导致三棵树全部 BUILD FAILURE。脚本中既无开关取消 `-o`，也无环境变量可影响该行为。

### 阻塞点 3：CLI 执行命令不可侵入与规约约束
- **现象**：
  WP-F6 交付物规约要求：
  - default job 必须执行 `pwsh -NoProfile -File scripts/run-tests.ps1 -Suite default`
  - spark job 必须执行同脚本 `-Suite spark`
  约束要求：「*不改 run-tests.ps1 或任何既有脚本/代码；若发现只需极小的脚本侧适配，不要动手改，只在报告中提出建议与影响面，留待总控裁决*」。
- **结论**：
  在既有脚本保持原样、CLI 调用命令保持原样的前提下，工作流在 Windows runner 上首步必死（exit code 1），任何强行伪造软链接（如 `mklink /J D:\Develop\JAVA17 ...`）的 Workaround 均严重违背规约第 2 条的设计初衷。

---

## 6. 需要总控裁决的适配建议与影响面分析 (Options for Controller Ruling)

为了使 `scripts/run-tests.ps1` 具备在 GitHub Actions 与本地/VM 环境无缝运行的能力，建议总控审阅以下三个选项并予以裁决：

### 方案 A（推荐）：脚本侧增加非破坏性环境变量与动态回退（极小改动）
- **改动位置**：`scripts/run-tests.ps1` 参数声明区（第 68-71 行）与 `Invoke-MavenRun`（第 1058 行）。
- **具体建议**：
  1. **Maven 定位回退**：
     ```powershell
     [string]$MavenCmd = if ($env:MAVEN_CMD) { $env:MAVEN_CMD }
                         elseif (Test-Path 'D:\apache-maven-3.9.14\bin\mvn.cmd') { 'D:\apache-maven-3.9.14\bin\mvn.cmd' }
                         elseif (Get-Command mvn -ErrorAction SilentlyContinue) { (Get-Command mvn).Source }
                         else { 'D:\apache-maven-3.9.14\bin\mvn.cmd' }
     ```
  2. **JDK 17 定位回退**：
     ```powershell
     [string]$JdkDefault = if ($env:JAVA_HOME_17_X64) { $env:JAVA_HOME_17_X64 }
                           elseif ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\java.exe')) -and (& (Join-Path $env:JAVA_HOME 'bin\java.exe') -version 2>&1 | Select-String '17\.')) { $env:JAVA_HOME }
                           elseif (Test-Path 'D:\Develop\JAVA17') { 'D:\Develop\JAVA17' }
                           else { 'D:\Develop\JAVA17' }
     ```
  3. **JDK 8 定位回退**：
     ```powershell
     [string]$JdkSpark = if ($env:JAVA_HOME_8_X64) { $env:JAVA_HOME_8_X64 }
                         elseif (Test-Path 'D:\Develop\JDK1.8') { 'D:\Develop\JDK1.8' }
                         else { 'D:\Develop\JDK1.8' }
     ```
  4. **本地仓库与离线标志支持按需放开**：
     新增可选开关 `[switch]$Online`（或判断 `$env:CI -eq 'true'`），当未指定离线或在 CI 环境时，不追加 `-o`，且 `$MavenRepoLocal` 回退至 `$HOME/.m2/repository`。
- **影响面与兼容性**：
  - **对本地与云端 VM 零破坏**：原有 `D:\` 路径仍享有最高优先级或默认生效，既有使用习惯与批处理命令完全不受影响。
  - **契约测试联动**：改动需经 `AnalyticsIsolationScriptsContractTest` 验证，确保没有破坏既有参数块结构。
  - **CI 收益**：GitHub Actions 工作流可直接使用标准的 `actions/setup-java@v4`，命令无需拼接长参数即可全绿运行。

### 方案 B：脚本不改，由 CI 工作流传参并在运行前执行依赖预热
- **方案描述**：
  保持 `run-tests.ps1` 字节不改。总控批准工作流使用扩展命令行传参，并在执行前通过原生 `mvn dependency:go-offline` 将依赖下载到指定目录，但这依然无法绕过脚本第 1058 行 `-o` 强行指定 `$MavenRepoLocal` 的矛盾。
- **评价**：脆弱且侵入 CI 脚本，维护成本高，不推荐。

### 方案 C：采用具备预置目录的 Self-Hosted Windows Runner
- **方案描述**：
  部署专有的 Self-Hosted Runner，其宿主机环境完全按照原开发机布局配置 `D:\Develop\JAVA17`、`D:\Develop\JDK1.8`、`D:\apache-maven-3.9.14` 与全量缓存的 `D:\maven_repository`。
- **评价**：违背了使用标准托管 runner 验证工程可移植性的目的，成本过高。

---

## 7. 终局结论

根据 WP-F6 规约的明确指引：
> *「若脚本把某个工具路径写死为本机绝对路径，先判断 GitHub windows-latest runner 上能否用 setup-* 动作把工具装到该路径或用脚本已支持的环境变量覆盖；不能则不要硬造工作流，转入『阻塞报告』路径（见交付物 2）。」*
> *「不可行：不建工作流文件，在报告中逐项写明阻塞点（脚本行号 + runner 差异 + 需要总控裁决的选项），结论段明确写『BLOCKED』。」*

本任务结论判定为：

**BLOCKED**

（未新建 `.github/workflows/tests.yml`，阻塞报告已归档至 `~/workspace/projects/mall-simulator-notes/WP-F6-REPORT.md`，等待总控就方案 A 的脚本适配做出裁定）。
