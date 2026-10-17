# WP-F6b-FIX 修复规约：Invoke-MavenRun 离线参数数组展开缺陷（总控亲跑判不合格后派发）

## 不合格事实（总控 2026-10-17 巡检亲跑证据，不采信自述）
总控以 env 回退方式亲跑 `run-tests.ps1 -Suite default`（RunId wpf6bacc20261017a，日志 /tmp/v25tests-wpf6bacc-a 与 /tmp/wpf6b-default-console.log）：
- 解析层四行 `[CI-ADAPT]` 全部正确触发（MAVEN_CMD / MAVEN_REPO_LOCAL / JAVA_HOME_17_X64 / JAVA_HOME_8_X64），门禁通过——交付物 1 的改动 1/2/4 与交付物 2（tests.yml）验收合格，不动。
- 但三棵树 Maven 全部 BUILD FAILURE、合计 0 用例、`[FAIL exit=7]`：实际命令行被粘连为 `mvn -o-Dmaven.repo.local=<repo> -f <pom>test`（`-o` 与 `-D` 粘连、`pom.xml` 与 `test` 粘连），Maven 报「no POM in this directory」。

## 根因（总控已定位，执行方不得另找）
改动 3 引入：
```powershell
$offlineArgs = if ($Online) { @() } else { @('-o') }
$mvnArgs = $offlineArgs + @("-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
```
PowerShell 中 `if` 表达式赋值会把单元素数组 `@('-o')` **展开为标量字符串** `'-o'`；随后字符串 `+` 数组退化为字符串拼接（数组以 $OFS 连接且首元素与字符串直接粘连），`$mvnArgs` 变成单个字符串而非参数数组。缺省（`$Online=$false`）路径——即红线要求「现行离线行为不变」的路径——被破坏；`$Online=$true` 时 `$offlineArgs` 为空、恰好不触发，故 CI 路径表面无恙。此为 PowerShell 数组展开语义问题，与环境无关。

## 你只有文件读写工具：不运行任何命令、不连库、不改 PROJECT_STATUS.md / FULL-PROGRAM.md / WP-F6b-REPORT.md。

## 唯一允许的改动（`scripts/run-tests.ps1`，仅 `Invoke-MavenRun` 内两行）
改前：
```powershell
  $offlineArgs = if ($Online) { @() } else { @('-o') }
  $mvnArgs = $offlineArgs + @("-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
```
改后（用 `@(...)` 强制数组语义，两种开关状态下 `$offlineArgs` 恒为数组）：
```powershell
  $offlineArgs = @(if ($Online) { } else { '-o' })
  $mvnArgs = $offlineArgs + @("-Dmaven.repo.local=$MavenRepoLocal", '-f', $Pom) + $ExtraArgs + @('test') + $SysProps
```
- 除这两行中的第一行外，文件其余字节一律不动（含第二行原文保持不变）。
- 不得改默认值字面量、基线数字、比较函数、退出码；不得动 `.github/workflows/tests.yml`。

## 交付说明（回复正文）
- 写明改动前后对照与「`@(...)` 强制收集 `if` 输出、单元素不展开」的原理一句。
- 完成后在回复末尾输出一行：WPF6BFIX-DONE
