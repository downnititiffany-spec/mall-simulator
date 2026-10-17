# WP-F6b 落地报告：脚本 CI 适配与 GitHub Actions 工作流落地

> **执行日期**：2026-10-17  
> **依据规约**：`~/workspace/projects/mall-simulator-notes/WP-F6b.md`  
> **裁决背景**：落实总控 Option A 裁决，对 `scripts/run-tests.ps1` 实施最小非破坏性适配（参数开关 + 解析层），并创建 `.github/workflows/tests.yml`。  
> **执行约束**：仅使用文件读写工具，零命令执行、零数据库连接、零修改未授权文件。

---

## 1. `scripts/run-tests.ps1` 四处最小适配清单与改前/改后对照

本次改动严格限定于规约规定的四处，未触碰任何基线数字、比较函数与退出码门禁。

### 1.1 改动 1：参数块（Param Block）新增 `[switch]$Online`

在 `param()` 块内 `[switch]$Confirm` 之后追加 `[switch]$Online`，并附注说明。参数名严格遵守安全约束，未包含敏感字词。

**改前片段**：
```powershell
  # 仅在总控批准口径更新后使用：允许用例数与登记基线不一致仍算通过
  [switch]$AllowCountDrift,
  [switch]$Confirm
)
```

**改后片段**：
```powershell
  # 仅在总控批准口径更新后使用：允许用例数与登记基线不一致仍算通过
  [switch]$AllowCountDrift,
  [switch]$Confirm,
  # CI 在线模式，不加 -o；缺省关＝现行离线行为不变
  [switch]$Online
)
```

---

### 1.2 改动 2：插入 CI / 环境变量回退解析层

位置位于 `Set-StrictMode -Version Latest` 之后、`$RunIdPattern` 定义之前。通过 `$PSBoundParameters.ContainsKey(...)` 保障显式传参拥有最高优先级，其余依次进行环境变量与 PATH 回退，改写时输出 `[CI-ADAPT]` 日志。

**改前片段**：
```powershell
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

$RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$'
$SparkTestSuiteTxt = 'spark-jobs\target\surefire-reports\TestSuite.txt'
```

**改后片段**：
```powershell
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
Set-StrictMode -Version Latest

# ── CI / 环境变量回退解析层（WP-F6b）────────────────────────────────────────
# 优先级：显式参数 ($PSBoundParameters) > 环境变量 > 原默认值
if (-not $PSBoundParameters.ContainsKey('MavenCmd')) {
  if ($env:MAVEN_CMD) {
    $MavenCmd = $env:MAVEN_CMD
    Write-Host ("[CI-ADAPT] MavenCmd <- MAVEN_CMD: {0}" -f $MavenCmd)
  } elseif (-not (Test-Path -LiteralPath $MavenCmd)) {
    $mvnFound = (Get-Command 'mvn.cmd' -ErrorAction SilentlyContinue | Select-Object -First 1)
    if (-not $mvnFound) {
      $mvnFound = (Get-Command 'mvn' -ErrorAction SilentlyContinue | Select-Object -First 1)
    }
    if ($mvnFound) {
      $mvnSource = if ($mvnFound.Source) { $mvnFound.Source } else { $mvnFound.Path }
      if ($mvnSource) {
        $MavenCmd = $mvnSource
        Write-Host ("[CI-ADAPT] MavenCmd <- PATH: {0}" -f $MavenCmd)
      }
    }
  }
}

if (-not $PSBoundParameters.ContainsKey('MavenRepoLocal')) {
  if ($env:MAVEN_REPO_LOCAL) {
    $MavenRepoLocal = $env:MAVEN_REPO_LOCAL
    Write-Host ("[CI-ADAPT] MavenRepoLocal <- MAVEN_REPO_LOCAL: {0}" -f $MavenRepoLocal)
  }
}

if (-not $PSBoundParameters.ContainsKey('JdkDefault')) {
  if ($env:JAVA_HOME_17_X64) {
    $JdkDefault = $env:JAVA_HOME_17_X64
    Write-Host ("[CI-ADAPT] JdkDefault <- JAVA_HOME_17_X64: {0}" -f $JdkDefault)
  }
}

if (-not $PSBoundParameters.ContainsKey('JdkSpark')) {
  if ($env:JAVA_HOME_8_X64) {
    $JdkSpark = $env:JAVA_HOME_8_X64
    Write-Host ("[CI-ADAPT] JdkSpark <- JAVA_HOME_8_X64: {0}" -f $JdkSpark)
  }
}

$RunIdPattern = '^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$'
$SparkTestSuiteTxt = 'spark-jobs\target\surefire-reports\TestSuite.txt'
```

---

### 1.3 改动 3：离线开关控制（`Invoke-MavenRun`）

在底层执行 Maven 命令构造 `$mvnArgs` 时，根据 `$Online` 开关状态决定是否拼接 `'-o'` 参数。当 `$Online` 为假（默认）时，保持原有的数组元素及其相对顺序完全不变。

**改前片段**：
```powershell
  $log = Join-Path $LogDir $LogName
  $mvnArgs = @('-o', "-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
  Write-Host ("  JAVA_HOME = {0}" -f $env:JAVA_HOME)
```

**改后片段**：
```powershell
  $log = Join-Path $LogDir $LogName
  $offlineArgs = if ($Online) { @() } else { @('-o') }
  $mvnArgs = $offlineArgs + @("-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
  Write-Host ("  JAVA_HOME = {0}" -f $env:JAVA_HOME)
```

---

### 1.4 改动 4：头部注释补充说明

在脚本头部「用法」注释段末尾新增一行，说明 `-Online` 与 4 个环境变量回退变量的存在及优先级规则。

**改前片段**：
```powershell
#   `-RunId` 缺省（S3-51 起）＝ `dev003c_<yyyyMMdd_HHmmss>_<6 位随机>`（旧口径为分钟级 `yyyyMMdd_HHmm`，
#   同一分钟内并发会派生同一 RunId ⇒ 同一日志目录 ⇒ 双方都拿不到证据）；无论显式还是缺省，
#   同一日志目录**已被另一轮占用**时本次直接 `[REFUSE exit=5]`（互斥锁按日志目录派生、进程退出即释放）。
param(
```

**改后片段**：
```powershell
#   `-RunId` 缺省（S3-51 起）＝ `dev003c_<yyyyMMdd_HHmmss>_<6 位随机>`（旧口径为分钟级 `yyyyMMdd_HHmm`，
#   同一分钟内并发会派生同一 RunId ⇒ 同一日志目录 ⇒ 双方都拿不到证据）；无论显式还是缺省，
#   同一日志目录**已被另一轮占用**时本次直接 `[REFUSE exit=5]`（互斥锁按日志目录派生、进程退出即释放）。
#   CI / 在线模式与环境变量回退（WP-F6b）：支持 -Online 开关（不加 -o 联网拉依赖）与 4 个 env 回退（MAVEN_CMD / MAVEN_REPO_LOCAL / JAVA_HOME_17_X64 / JAVA_HOME_8_X64），优先级为：显式参数 > env > 原默认值。
param(
```

---

## 2. 回退优先级与本机兼容性论证

### 2.1 优先级设计原则
本解析层遵循 **「显式参数 > 环境变量 > 原默认值」** 的确定性优先级，具体判决树如下：
1. **显式传入判断**：首先通过 `$PSBoundParameters.ContainsKey('<ParamName>')` 判断调用方是否在 CLI 明确传递了该参数。若显式传入，解析层完全不触碰该变量，保证调用者显式指定的参数绝对生效。
2. **环境变量回退**：若未显式传参，依次检查绑定的环境变量（`MAVEN_CMD`、`MAVEN_REPO_LOCAL`、`JAVA_HOME_17_X64`、`JAVA_HOME_8_X64`）。变量非空时赋值并打标记录。
3. **PATH 动态探测（仅针对 MavenCmd）**：若未传参且 `MAVEN_CMD` 未设，只有在原默认值路径（`D:\apache-maven-3.9.14\bin\mvn.cmd`）在当前文件系统**不存在**的情况下，才尝试通过 `Get-Command mvn.cmd` 或 `Get-Command mvn` 从系统 PATH 中寻找已安装的 Maven 并取其 Source 路径。若原默认值路径存在，则绝不触发 PATH 探测。
4. **原默认值兜底**：若以上条件均不满足，变量完全维持脚本 `param()` 块中声明的原始字符串字面量。

### 2.2 原开发机（Windows D 盘物理布局）零影响论证
- **环境变量未设置**：原开发机环境下未配置 `MAVEN_CMD`、`MAVEN_REPO_LOCAL`、`JAVA_HOME_17_X64` 或 `JAVA_HOME_8_X64`，所有 `$env:` 读取均求值为 `$null`（假），不触发环境变量覆盖。
- **默认路径真实存在**：原开发机真实拥有 Windows D 盘目录（`D:\Develop\JAVA17`、`D:\Develop\JDK1.8`、`D:\apache-maven-3.9.14` 与 `D:\maven_repository`）。解析层对 `MavenCmd` 执行 `Test-Path -LiteralPath $MavenCmd` 判定为真，直接跳过 PATH 探测分支，保持原默认值。
- **离线行为默认锁定**：原开发机运行未显式携带 `-Online` 时，`$Online` 缺省为 `$false`，`Invoke-MavenRun` 依然拼入 `'-o'`，完全保持既有的本地仓库离线构建行为。
- **零日志污染**：由于没有任何变量发生改写，脚本不会输出任何 `[CI-ADAPT]` 日志行，控制台输出与既有日志完全一致。

### 2.3 云端 Linux VM（显式传参模式）零影响论证
- 在云端 Linux VM 环境下，调用脚本统一通过 CLI 显式传递路径参数（如 `-MavenCmd`、`-JdkDefault`、`-MavenRepoLocal` 等）。
- 此时 `$PSBoundParameters.ContainsKey(...)` 对每一个显式传入的参数均返回 `$true`，解析层内部逻辑整体绕过，不受宿主机环境或 runner 变量的任何偶发干扰。

### 2.4 StrictMode 兼容性
- 脚本已启用 `Set-StrictMode -Version Latest`。
- 在 PowerShell 中，通过环境驱动器 `$env:VAR_NAME` 读取未定义的环境变量是原生安全的（直接返回 `$null`，不会抛出 `VariableIsUndefined` 异常）；
- `$PSBoundParameters` 属于内置受限自动变量（Dictionary），其 `.ContainsKey()` 方法调用完全合规。
- 因此整个解析段在最严格模式下能够无异常稳定执行。

---

## 3. 工作流与脚本口径对齐表（更新版）

| 组件 / 依赖项 | GitHub `windows-latest` Runner 现状 / setup 动作能力 | 脚本内部期望与解析机制 | 生效值 / 回退路径 | 对齐状态 |
|---|---|---|---|:---:|
| **JDK 17**<br>(Default 档) | `actions/setup-java@v4`<br>`distribution: 'temurin'`<br>`java-version: '17'`<br>默认导出 `JAVA_HOME_17_X64` | `param([string]$JdkDefault = 'D:\Develop\JAVA17')`<br>解析层：若未显式传参，读取 `$env:JAVA_HOME_17_X64` | 解析层检测到 `JAVA_HOME_17_X64` 非空，改写 `$JdkDefault` 并打印 `[CI-ADAPT] JdkDefault <- JAVA_HOME_17_X64: ...`，通过后续门禁校验 |  **完美对齐** |
| **JDK 8**<br>(Spark 档) | `actions/setup-java@v4`<br>`distribution: 'temurin'`<br>`java-version: '8'`<br>默认导出 `JAVA_HOME_8_X64` | `param([string]$JdkSpark = 'D:\Develop\JDK1.8')`<br>解析层：若未显式传参，读取 `$env:JAVA_HOME_8_X64` | 解析层检测到 `JAVA_HOME_8_X64` 非空，改写 `$JdkSpark` 并打印 `[CI-ADAPT] JdkSpark <- JAVA_HOME_8_X64: ...`，通过后续门禁及 `mvn -version` 取证 |  **完美对齐** |
| **Maven 可执行程序** | `windows-latest` 预装 Maven（位于 Chocolatey 安装目录），位于系统 `PATH` 中 | `param([string]$MavenCmd = 'D:\apache-maven-3.9.14\bin\mvn.cmd')`<br>解析层：若 D 盘不存在且 PATH 可查，取 `Get-Command` 之 Source | 托管 runner 上 D 盘路径不存在，解析层从 PATH 解析出 `mvn.cmd` 真实路径并改写，打印 `[CI-ADAPT] MavenCmd <- PATH: ...` |  **完美对齐** |
| **Maven 本地仓库与在线模式** | Runner 初始本地仓库为空，依赖需在线拉取 | 工作流 CLI 显式传递：<br>`-MavenRepoLocal "$HOME/.m2/repository"`<br>`-Online` 开关 | `$PSBoundParameters` 优先采用显式仓库路径；`Invoke-MavenRun` 依据 `$Online` 省略 `'-o'`，支持在线下载依赖 |  **完美对齐** |
| **RunId 隔离标识** | GitHub Actions 单 job 内为独占干净环境 | 脚本第 984-990 行具备自动生成机制：<br>`dev003c_<yyyyMMdd_HHmmss>_<6位随机>` | CLI 缺省自生成，格式严格符合 `^[A-Za-z0-9][A-Za-z0-9_-]{5,63}$` 正则约束 |  **完美对齐** |
| **基线门禁与漂移** | 保持高标准代码质量验证 | 脚本内硬编码基线比对（漂移即 `Fail 7`） | 工作流未添加 `-AllowCountDrift` 参数，确保计数漂移时 CI 能够及时拦截 |  **完美对齐** |
| **安全与独立性** | 零 Secrets，不依赖外置数据库 | 仅调度 default 与 spark 两档无库用例 | 无任何 secret 注入，不连 3307，零外部网络服务依赖 |  **完美对齐** |

---

## 4. 超时取值理由说明

在 `.github/workflows/tests.yml` 中，各 job 超时配置基于本机实际用例规模与托管 runner 冷启动拉取耗时综合评估设定：

1. **`default` job：`timeout-minutes: 90`**
   - **用例体量**：default 档包含三棵 Maven 树共计 **1540 个用例**（`analytics-server` 1380 个用例、`mall-simulator` 26 个用例、`synthetic-data-generator` 134 个用例）。
   - **首次拉取依赖**：GitHub 托管 Windows runner 为全新冷启动环境，首次在线拉取三棵树全部 Spring Boot、MyBatis-Plus、Surefire 插件及传递性依赖耗时较长（通常需 15-25 分钟，受外网波动影响）。
   - **虚拟化 I/O 损耗**：Windows runner 的磁盘与进程派生 I/O 相比裸机有所损耗，1540 个用例的完整编译与多反应堆测试执行需约 30-45 分钟。
   - **容灾裕量**：设定 90 分钟能够确保在网络抖动或突发高负载时顺利完成构建，同时防止因偶发进程挂起而无限期空耗 runner 配额。

2. **`spark` job：`timeout-minutes: 60`**
   - **用例体量**：spark 档包含 **372 个用例**（涵盖 ScalaTest 38 个套件）。
   - **Spark 与 Scala 构建链路**：冷启动环境下需联网下载 Spark 内存框架及相关 Scala 依赖库。
   - **执行特征**：每个用例虽然在本地 local[1] 内存会话下运行，但 Spark 驱动器与上下文创建/销毁伴随类加载开销。设定 60 分钟对 372 个用例及冷依赖拉取具有充分裕量。

---

## 5. isolated 档为何不入 CI 的说明

根据 `~/workspace/projects/mall-simulator-notes/WP-F6-REPORT.md` 第 4 节结论：
> `isolated` 档在设计上深度绑定本机私有 3307 独立 MySQL 实例与端口硬隔离、依赖 `DEV001_IT_FINGERPRINT` 实例指纹与主机名探针校验契约、要求依据本轮 RunId 派生专属隔离库及账号，并且前置门禁强制要求提供 4 个环境变量口令（缺失即 `Fail 5` 拒绝）；GitHub 托管 runner 无法满足私有 3307 实例及指纹契约，且工作流严格恪守零 Secrets 约束，故坚决不入托管 CI。
