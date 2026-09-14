<#
V25-E3 | 收尾：取证期间连接面 → 停 8091 → 第二次 3306 指纹 → 逐项比对 → 「零写入」结论。

只读 3306（仅 SELECT 指纹与 flyway 历史），不执行任何写。
用法：pwsh -File <this>
#>
param(
  [int]$Port = 8091,
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raw = Join-Path $EvidenceRoot 'raw'
$cmp = Join-Path $raw '33-zero-write-comparison.txt'
$before = Join-Path $raw 'baseline-3306-before.txt'
$after  = Join-Path $raw 'baseline-3306-after.txt'
$sqlFile = (Join-Path $EvidenceRoot 'scripts\fingerprint-3306.sql') -replace '\\', '/'

# ── 1) 取证期间：本应用进程的对外连接面 ──────────────────────────────
$conn = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
if (-not $conn) { Write-Host "警告：$Port 无监听（应用可能已退出）"; $appPid = $null } else { $appPid = $conn[0].OwningProcess }
$scan = Join-Path $raw '30-app-connections-during-window.txt'
if ($appPid) {
  "采样时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  app PID=$appPid  监听=127.0.0.1:$Port" | Set-Content $scan
} else {
  # 二次运行时应用已停：保留首次采样，只追加说明，避免覆盖取证期间的连接面证据
  "`n[二次运行 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] $Port 已无监听，本次未覆盖上面的采样" | Add-Content $scan
}
if ($appPid) {
  $est = Get-NetTCPConnection -OwningProcess $appPid -ErrorAction SilentlyContinue | Where-Object State -eq 'Established'
  ($est | Group-Object RemotePort | Select-Object @{n='remotePort';e={$_.Name}},@{n='connections';e={$_.Count}} | Format-Table -AutoSize | Out-String).Trim() | Add-Content $scan
  $to3306 = @($est | Where-Object RemotePort -eq 3306).Count
  "指向 3306 的已建立连接数 = $to3306" | Add-Content $scan
  "指向 3307 的已建立连接数 = $(@($est | Where-Object RemotePort -eq 3307).Count)" | Add-Content $scan
  "进程命令行 = " + (Get-CimInstance Win32_Process -Filter "ProcessId=$appPid").CommandLine | Add-Content $scan
}
Get-Content $scan

# ── 2) 日志里是否出现过 3306 ────────────────────────────────────────
$logPath = Join-Path $raw '10-app-8091-console.log'
$scanText = if (Test-Path $scan) { Get-Content $scan -Raw } else { '' }
if ($scanText -notmatch '应用控制台日志中 3306 出现次数') {
  $hits = @(Select-String -Path $logPath -Pattern '3306' -ErrorAction SilentlyContinue)
  "应用控制台日志中 3306 出现次数 = $($hits.Count)" | Add-Content $scan
  $hits | ForEach-Object { "  L$($_.LineNumber): $($_.Line)" } | Add-Content $scan
} else {
  "`n[二次运行 $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] 日志 3306 扫描已在上面记录，不重复追加" | Add-Content $scan
}

# ── 3) 停 8091 ──────────────────────────────────────────────────────
if ($appPid) {
  $p = Get-Process -Id $appPid -ErrorAction SilentlyContinue
  if ($p) {
    "停止时间: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  Stop-Process -Id $appPid（$($p.ProcessName)）" | Add-Content $scan
    Stop-Process -Id $appPid -Force
    Start-Sleep -Seconds 6
  }
}
$still = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
"停止后 $Port 监听: " + $(if ($still) { '仍存在（失败）' } else { '已释放' }) | Add-Content $scan
"残留 java 进程: " + ((Get-Process java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Id) -join ',') | Add-Content $scan

# ── 4) 第二次 3306 指纹（与 before 用同一 SQL 文件）──────────────────
$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
try {
  & $MysqlExe --host=127.0.0.1 --port=3306 --user=root -t -e "source $sqlFile" 2>&1 | Set-Content $after
} finally { $env:MYSQL_PWD = $old }

# ── 5) 逐项比对 ─────────────────────────────────────────────────────
function Parse([string]$path) {
  $map = [ordered]@{}
  foreach ($line in Get-Content $path) {
    if ($line -match '^\|\s*([A-Za-z0-9_]+)\s*\|\s*(.*?)\s*\|\s*$') {
      $k = $Matches[1]
      # 跳过 mysql -t 的表头行（形如 `| k | v |`）与分隔线，只取真实数据行
      if ($k -cin @('k', 'v')) { continue }
      if (-not $map.Contains($k)) { $map[$k] = $Matches[2] }
    }
  }
  return $map
}
$b = Parse $before; $a = Parse $after
$lines = @()
$lines += "V25-E3 宿主正式实例 3306 零写入比对  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$lines += "指纹算法（本泳道自定义，SQL 见 scripts/fingerprint-3306.sql）："
$lines += "  行指纹 h = MD5(CONCAT_WS('|', IFNULL(CAST(col AS CHAR),'\\N') ... 全列按 ORDINAL_POSITION))"
$lines += "  表指纹 fp = MD5(GROUP_CONCAT(h ORDER BY h SEPARATOR ''))  ⇒ 行序无关（集合语义）"
$lines += "  group_concat_max_len = 1073741824（避免静默截断）"
$lines += ""
$lines += "{0,-28} {1,-46} {2,-46} {3}" -f 'KEY','BEFORE','AFTER','判定'
$keys = @($b.Keys + $a.Keys | Select-Object -Unique)
$diff = 0
foreach ($k in $keys) {
  $bv = if ($b.Contains($k)) { [string]$b[$k] } else { '<缺失>' }
  $av = if ($a.Contains($k)) { [string]$a[$k] } else { '<缺失>' }
  $ok = if ($bv -ceq $av) { '一致' } else { $diff++; '**不一致**' }
  $lines += "{0,-28} {1,-46} {2,-46} {3}" -f $k, $bv, $av, $ok
}
$lines += ""
$lines += "不一致项数 = $diff"
$lines += $(if ($diff -eq 0) { "结论：取证窗口内宿主 3306 的 analytics_metric.metric_snapshot / metric_value 行数、全表内容指纹、ACTIVE 指针、flyway_schema_history 均逐字节一致 ⇒ 本轮对本实例**零写入**。" } else { "结论：存在不一致项，必须逐条排查后方可下结论。" })
$lines += ""
$lines += "注意（另一条独立结论）：3306 的 flyway_schema_history 记录数/最新记录在窗口前后一致，"
$lines += "只说明「本轮没有新的迁移被应用到 3306」，并不等于「本项目历史上从未有迁移能碰 3306」——"
$lines += "见 README 的独立结论段。"
$lines | Set-Content $cmp
$lines | ForEach-Object { Write-Host $_ }
if ($diff -ne 0) { exit 5 }
exit 0
