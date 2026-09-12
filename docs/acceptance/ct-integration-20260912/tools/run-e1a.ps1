<#
  run-e1a.ps1 —— 以 PLAN §3 L75 的**字面命令**跑 E1-a（analytics-server 受影响面单元测试），把完整输出落盘。

  字面命令：mvn -o -f analytics-server/pom.xml -pl platform-common,platform-app -am test '-DforkCount=0'
  环境    ：JAVA_HOME=D:\Develop\JAVA17 ；JAVA_TOOL_OPTIONS='-Dfile.encoding=UTF-8 -XX:+UseSerialGC'

  为什么要有这个脚本：
    · 日志头部**自带**运行口径（时刻/仓库/HEAD/工作区脏文件/字面命令/环境变量），使日志可以被独立复核，
      不必依赖"我当时是这么跑的"这种口头记录（陷阱 #27：证据要自证）；
    · 前后两次跑必须是同一口径（陷阱 #28），故两次都由本脚本产出。

  已知未固定项（如实登记，不得当作已对齐）：MAVEN_OPTS 本脚本**显式清空**（不设 = 默认堆），
  目的是让两次跑一致；若历史日志曾设过 MAVEN_OPTS，则"堆大小"这一物理量两侧未对齐，
  但本门禁的对比量是**逐模块测试数 / 失败用例集合**，不受堆设置影响。
  其它：本脚本不修改仓库任何文件，只写 -Out 指向的日志。

  退出码：0 = 日志已产出（Maven 自身成败见日志与 -ReportExitCode）；9 = 基础设施错误。
#>
param(
  [string]$Repo = 'D:\Develop_code\GraduationProject',
  [Parameter(Mandatory = $true)][string]$Out,
  [string]$Maven = 'D:\apache-maven-3.9.14\bin\mvn.cmd',
  [string]$JavaHome = 'D:\Develop\JAVA17'
)
$ErrorActionPreference = 'Stop'
function Die([string]$m) { Write-Host ('  [基础设施错误] ' + $m) -ForegroundColor Red; exit 9 }

if (-not (Test-Path -LiteralPath $Repo)) { Die ('仓库不存在：' + $Repo) }
if (-not (Test-Path -LiteralPath $Maven)) { Die ('mvn 不存在：' + $Maven) }
if (-not (Test-Path -LiteralPath $JavaHome)) { Die ('JAVA_HOME 不存在：' + $JavaHome) }

# 统一写盘：UTF-8 无 BOM（避免 PS 重定向写 CRLF/UTF-16，见 CT 集成陷阱 #31）
function Save([string]$path, [string]$text) {
  $abs = $(if ([IO.Path]::IsPathRooted($path)) { $path } else { Join-Path $Repo $path })
  $dir = Split-Path -Parent $abs
  if ($dir -and -not (Test-Path -LiteralPath $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
  [IO.File]::WriteAllText($abs, $text, (New-Object Text.UTF8Encoding($false)))
  return $abs
}

$env:JAVA_HOME = $JavaHome
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -XX:+UseSerialGC'
$env:MAVEN_OPTS = $null

$head = (git -C $Repo rev-parse HEAD)
$dirty = @(git -C $Repo -c core.quotepath=false status --porcelain)
$mvnArgs = @('-o', '-f', (Join-Path $Repo 'analytics-server\pom.xml'), '-pl', 'platform-common,platform-app', '-am', 'test', '-DforkCount=0')

$header = @()
$header += '==== E1-a 运行日志（run-e1a.ps1 产出；口径自带）===='
$header += ('时刻            = ' + (Get-Date -Format 'yyyy-MM-dd HH:mm:ss'))
$header += ('仓库            = ' + $Repo)
$header += ('HEAD            = ' + $head)
$header += ('工作区脏文件    = ' + $(if ($dirty.Count -eq 0) { '（干净）' } else { ($dirty -join ' | ') }))
$header += ('字面命令        = mvn ' + ($mvnArgs -join ' '))
$header += ('JAVA_HOME       = ' + $env:JAVA_HOME)
$header += ('JAVA_TOOL_OPTIONS = ' + $env:JAVA_TOOL_OPTIONS)
$header += ('MAVEN_OPTS      = （显式清空，默认堆）')
$header += ('mvn 路径        = ' + $Maven)
$header += ('PASS 判据       = 本文件只负责产出日志；对比判据见 tools\e1a-compare.ps1（不声称通过 PLAN 字面判据）')
$header += '==== 以下为 mvn 原始输出 ===='

Write-Host ('  运行：mvn ' + ($mvnArgs -join ' '))
$sw = [Diagnostics.Stopwatch]::StartNew()
$body = (& $Maven @mvnArgs 2>&1 | Out-String)
$code = $LASTEXITCODE
$sw.Stop()

$logText = ($header -join "`n") + "`n" + ($body -replace "`r`n", "`n")
$abs2 = Save $Out $logText
[IO.File]::AppendAllText($abs2, ("`n==== mvn 退出码 = " + $code + '（非 0 不等于不合格：本仓库存在既有红，见 e1a-compare.ps1 的 C6）' + "`n"), (New-Object Text.UTF8Encoding($false)))

$rawBytes = (Get-Item -LiteralPath $abs2).Length
$sha = (Get-FileHash -LiteralPath $abs2 -Algorithm SHA256).Hash
$lines = ([IO.File]::ReadAllText($abs2) -split "`n").Count
Write-Host ('  日志 = {0}' -f $abs2)
Write-Host ('        {0} B / {1} 行 / sha256 {2} / 耗时 {3:N1}s / mvn 退出码 {4}' -f $rawBytes, $lines, $sha, $sw.Elapsed.TotalSeconds, $code)
Write-Host '  逐模块汇总与失败清单：'
# 注意：**含失败的模块**其汇总行前缀是 `[ERROR]` 而不是 `[INFO]`（surefire 行为），故两态都要收，
# 否则会漏报最后一个模块（实测踩过：platform-app 100 条被漏掉，看着像"只有 5 个模块"）。
$mods = [regex]::Matches(($body -replace "`r`n", "`n"), '(?m)^\[(?:INFO|ERROR)\] Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)\s*$')
$sumT = 0; $sumF = 0
foreach ($m in $mods) {
  $t = [int]$m.Groups[1].Value; $f = [int]$m.Groups[2].Value; $sumT += $t; $sumF += $f
  Write-Host ('    Tests run: {0,4}, Failures: {1}, Errors: {2}, Skipped: {3}' -f $t, $f, $m.Groups[3].Value, $m.Groups[4].Value)
}
Write-Host ('    == {0} 个测试模块 / 合计 {1} tests / {2} failures ==' -f $mods.Count, $sumT, $sumF)
$fails = @([regex]::Matches(($body -replace "`r`n", "`n"), '(?m)^\[ERROR\]\s{2,}([A-Za-z0-9_.$]+)\.([A-Za-z0-9_$]+)(?::(\d+))?\s*(.*)$') | ForEach-Object { $_.Groups[1].Value + '.' + $_.Groups[2].Value })
if ($fails.Count -gt 0) { Write-Host ('    失败用例：' + ($fails -join ' | ')) } else { Write-Host '    失败用例：无' }
exit 0
