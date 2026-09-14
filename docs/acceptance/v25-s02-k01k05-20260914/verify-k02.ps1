# K-02 取证：真实跑 Maven 测试套件，把原始 stdout 原样落盘，并解析 surefire XML 得到
# classes/tests/failures/errors/skipped 与退出码（不靠控制台文本猜）。
#
# 为什么必须新增而不是改现有脚本：scripts/ 下所有脚本都是「验收/演示」入口，没有任何
# 「跑模块默认套件并按 class 采集 surefire 计数」的驱动。K-02 要求证明
#   ① 默认套件（无外部服务、不连库）真实通过；
#   ② 显式 IT profile 在缺隔离档案时**硬拒**（红），而不是 skip 后算绿；
#   ③ 被排除的 IT 并未消失（仍可被显式选中执行）。
# 这三点都需要同一支可重复驱动，故新增本脚本。本脚本只调用 Maven 并解析报告，不改被测代码。
#
# 用法：
#   pwsh -File verify-k02.ps1 -Phase default        # 默认套件（期望全绿、0 error）
#   pwsh -File verify-k02.ps1 -Phase isolated-hard-reject   # 显式 IT、无档案（期望红，硬拒）
#   pwsh -File verify-k02.ps1 -Phase isolated-run -Module synthetic-data-generator
param(
  [ValidateSet('default', 'isolated-hard-reject', 'isolated-run')][string]$Phase = 'default',
  [string[]]$Modules = @('mall-simulator', 'synthetic-data-generator'),
  [string]$Tag = 'k02-default',
  [string]$OutDir = (Join-Path $PSScriptRoot 'raw')
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$env:JAVA_HOME = 'D:\Develop\JAVA17'
$mvn = 'D:\apache-maven-3.9.14\bin\mvn.cmd'
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..\..')).Path

# 每次运行前清掉上一轮的 surefire 报告，避免把旧 XML 当成新结果（关键：不允许"陈旧绿"）
function Reset-Reports([string]$m) {
  $r = Join-Path $repo "$m\target\surefire-reports"
  if (Test-Path $r) { Remove-Item $r -Recurse -Force }
}

# 记录运行时的外部依赖面：默认套件应当没有任何应用端口在听
function PortSnapshot {
  $ports = 8090, 8091, 8092, 3306, 3307
  $listening = @()
  foreach ($p in $ports) {
    $c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue
    if ($c) { $listening += "$p(pid=$($c[0].OwningProcess))" }
  }
  if ($listening.Count -eq 0) { return 'none' } else { return ($listening -join ',') }
}

# 隔离档案存在性：hard-reject 场景必须**确认档案不存在**，
# 否则"红"可能只是因为档案里的目标写错了，而不是因为缺档案。
function IsolationArchiveSnapshot {
  $paths = @(
    'mall-isolation.local.properties'
    'it-guard.local.properties'
    'mall-simulator\mall-isolation.local.properties'
    'synthetic-data-generator\it-guard.local.properties'
  )
  $present = @()
  foreach ($rel in $paths) {
    $full = Join-Path $repo $rel
    if (Test-Path $full) { $present += "$rel($((Get-Item $full).Length)B)" }
  }
  if ($present.Count -eq 0) { return 'none' } else { return ($present -join ', ') }
}

# 注意（本轮实测踩坑，共四轮，必须记下来，否则下一个人会重踩）：
#   1) `mvn.cmd ... -Pisolated-tests` 在**交互式 pwsh** 里直接调用：OK。
#   2) 同一个开关放进数组 splat（@profArgs）或写成字面量字符串、由**本脚本**转交 mvn.cmd：
#      Maven 收到 `-` + `Pisolated-tests` 两个参数，报 `Unknown lifecycle phase "-"`。
#      同一脚本里不带该参数则完全正常（raw/tmp-probe-hr4-*.log 的 DEBUG-C = exit 0 / 8 用例全绿），
#      故确认是"pwsh 脚本上下文 -> .cmd"这一层的传参差异，**不是**被测 pom/脚本的缺陷。
#   3) 改用 surefire 直接目标 `:test -Dgroups=it`：又踩第二个坑——Maven **用户属性优先于插件配置**，
#      -DexcludedGroups 无论给空串还是别的值都清不掉 pom 里写死的 <excludedGroups>，
#      结果 "Tests run: 0 / BUILD SUCCESS" = 零用例假绿，正是本轮要消灭的形态。
#      （这也促成了 pom 的改造：excludedGroups 改由属性 ${v25.it.excluded.groups} 提供。）
#   4) 最终做法：把 profile 开关写在 **tools/mvn-isolated.cmd 自己的命令行**上，
#      由本驱动调用该 .cmd（不经 pwsh 再传参），效果与人工 `mvn ... -Pisolated-tests` 一致。
#      证据：raw/k02-after-hard-reject-*.log —— 30 个 IT 用例被真正选中并因缺隔离档案而红。
$profArgs = switch ($Phase) {
  'isolated-hard-reject' { @('-Pisolated-tests') }
  'isolated-run' { @('-Pisolated-tests') }
  default { @() }
}
# 走包装 .cmd 时 profile 已写在包装自己的命令行里，**不能再从 pwsh 传一遍**
# （传两遍会变成 `-Pisolated-tests -Pisolated-tests`，同样报 Unknown lifecycle phase）。
$mvnForPhase = if ($profArgs.Count -gt 0) { Join-Path $PSScriptRoot 'tools\mvn-isolated.cmd' } else { $mvn }
$cliArgs = @()

$summary = @()
foreach ($m in $Modules) {
  Reset-Reports $m
  $log = Join-Path $OutDir "$Tag-$m.log"
  $cmdLine = "mvn -o -Dmaven.repo.local=D:\maven_repository -f $m/pom.xml test  [driver=$mvnForPhase]".Trim()
  @(
    "### phase: $Phase"
    "### command: $cmdLine"
    "### cwd: $repo"
    "### java_home: $env:JAVA_HOME"
    "### listening ports before: $(PortSnapshot)"
    "### isolation archives present (CWD=repo root): $(IsolationArchiveSnapshot)"
    "### started: $((Get-Date).ToString('s'))"
  ) | Set-Content $log -Encoding utf8

  Push-Location $repo
  $out = & $mvnForPhase -o '-Dmaven.repo.local=D:\maven_repository' -f "$m/pom.xml" @cliArgs test 2>&1
  $code = $LASTEXITCODE
  Pop-Location

  "### exit code: $code" | Add-Content $log -Encoding utf8
  "### listening ports after: $(PortSnapshot)" | Add-Content $log -Encoding utf8
  "### finished: $((Get-Date).ToString('s'))" | Add-Content $log -Encoding utf8
  '### ---- raw output ----' | Add-Content $log -Encoding utf8
  $out | ForEach-Object { $_.ToString() } | Add-Content $log -Encoding utf8

  # surefire XML 汇总
  $reports = Join-Path $repo "$m\target\surefire-reports"
  $tests = 0; $fail = 0; $err = 0; $skip = 0; $classes = 0
  $perClass = @()
  if (Test-Path $reports) {
    Get-ChildItem $reports -Filter 'TEST-*.xml' | ForEach-Object {
      [xml]$x = Get-Content $_.FullName -Encoding utf8
      $s = $x.testsuite
      $classes++
      $tests += [int]$s.tests; $fail += [int]$s.failures; $err += [int]$s.errors; $skip += [int]$s.skipped
      $perClass += [pscustomobject]@{
        class = $s.name; tests = [int]$s.tests; failures = [int]$s.failures
        errors = [int]$s.errors; skipped = [int]$s.skipped
      }
    }
  }
  $summary += [pscustomobject]@{
    phase = $Phase; module = $m; exit = $code; classes = $classes
    tests = $tests; failures = $fail; errors = $err; skipped = $skip
  }
  $perClass | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir "$Tag-$m-perclass.json") -Encoding utf8
  Write-Host "== $m exit=$code classes=$classes tests=$tests failures=$fail errors=$err skipped=$skip"
}
$summary | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $OutDir "$Tag-summary.json") -Encoding utf8
$summary | Format-Table -AutoSize
