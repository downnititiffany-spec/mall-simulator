# WP-F2b 修复轮（总控亲跑验收不合格，2026-10-16 22:2x）

## 不合格事实（总控亲跑，RunId wpf2b20261016）
- prepare（-IncludeAnalytics）exit 0、门禁 6 四探针全过后，运行器在 WP-F2b 新增的 schema 供给步崩溃，runner exit=1，供给步与三类写 IT 均未执行。
- 错误原文：`Find-MavenArtifactJar: ...run-isolated-tests.ps1:463 ... The property 'Count' cannot be found on this object.`
- 根因：`Find-MavenArtifactJar` 内 `$jars = Get-ChildItem ... | Where-Object ... | Sort-Object ...` 当某版本目录恰好只有 1 个 jar 时，管道结果是单个 FileInfo 标量（StrictMode 下无 `.Count` 属性），`if ($jars.Count -gt 0)` 抛错。`$matchedJars` 的兜底分支有同一缺陷。

## 修复要求（仅限 scripts/run-isolated-tests.ps1 的 Find-MavenArtifactJar 函数）
1. 把 `$jars` 与 `$matchedJars` 两处赋值都用 `@( ... )` 包裹，强制数组语义；其余逻辑（glob 规则、版本排序、过滤 -sources/-javadoc/-tests、Fail 5 点名缺失、FlyMigrate 调用、步数契约、日志）一律不动。
2. 不改任何其他文件；不改基线数字；未带 -IncludeAnalyticsWriteIts 时行为仍须与现版逐字一致。
3. 你只有文件读写权限，不要运行任何命令；验收由总控以新 RunId 亲跑 `-Module analytics -IncludeAnalyticsWriteIts` 收编腿（要求 analytics-schema meta/metric 步数 >0 且三类写 IT 全绿）。

## 完成标准
上述修复落码；完成后在回复末尾输出一行：WPF2BFIX-DONE
