<#
V26-F88 | S7 停 8091：先取证再停，停后复验（PID / 端口 / 句柄）

顺序（避免「先杀了就没证据」）：
  1) 停前取证：8091 监听 PID、命令行、启动时间、3307 上本 run 账号连接数；
  2) 停：Stop-Process 该 PID（必要时二次确认）；
  3) 停后复验：8091 无监听、PID 不存在、3307 上本 run 账号连接数 = 0、health 不可达；
  4) 复查 8090/8091/8092 全部无监听（不留僵尸）。
#>
param(
  [int]$Port = 8091,
  [int[]]$AlsoCheck = @(8090, 8091, 8092),
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutName = '60-stop-app-8091.txt',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw')
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir $OutName
$token = $RunId -replace '-', '_'
$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }
function Sql([string]$sql) {
  $old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
  try { return (& $MysqlExe --host=127.0.0.1 --port=3307 --user=root -N -B -e $sql 2>&1) }
  finally { $env:MYSQL_PWD = $old }
}

Emit "V26-F88 S7 停止 8091  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"

# ── 1) 停前取证 ─────────────────────────────────────────────────────
Emit "`n===== 1) 停前取证 ====="
$listeners = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
$pids = @($listeners | Select-Object -ExpandProperty OwningProcess -Unique)
Emit "8091 监听条数 = $($listeners.Count)   监听 PID = $($pids -join ',')"
foreach ($p in $pids) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  if ($proc) {
    Emit ("  PID={0} name={1} start={2} path={3}" -f $proc.Id, $proc.ProcessName, $proc.StartTime.ToString('yyyy-MM-dd HH:mm:ss'), $proc.Path)
    try {
      $wmi = Get-CimInstance Win32_Process -Filter "ProcessId=$p" -ErrorAction Stop
      Emit ("  cmdline={0}" -f $wmi.CommandLine)
    } catch { Emit "  cmdline 读取失败: $_" }
  } else { Emit "  PID=$p 进程对象不存在（可能已退出）" }
}
$connBefore = ((Sql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER LIKE '$token%';") | Out-String).Trim()
Emit "停前：3307 上本 run 账号连接数 = $connBefore"
Emit "停前：8091 health 探测 =>"
try { $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/v1/health" -SkipHttpErrorCheck -TimeoutSec 8; Emit "  HTTP $([int]$r.StatusCode) $($r.Content)" }
catch { Emit "  异常(预期停前应可访问): $_" }

if ($pids.Count -eq 0) {
  Emit "`n[WARN] 8091 当前无监听进程，无需停止（仍执行停后复验）。"
} else {
  # ── 2) 停 ────────────────────────────────────────────────────────
  Emit "`n===== 2) 停止 ====="
  foreach ($p in $pids) {
    $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
    if ($proc) {
      Emit "  Stop-Process -Id $p -Force"
      Stop-Process -Id $p -Force -ErrorAction SilentlyContinue
    }
  }
  $dl = (Get-Date).AddSeconds(40)
  while ((Get-Date) -lt $dl) {
    $still = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
    if ($still.Count -eq 0) { break }
    Start-Sleep -Milliseconds 700
  }
}

# ── 3) 停后复验 ─────────────────────────────────────────────────────
Emit "`n===== 3) 停后复验 ====="
$still = @(Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)
Emit "8091 监听条数（必须 0）= $($still.Count)"
foreach ($p in $pids) {
  $proc = Get-Process -Id $p -ErrorAction SilentlyContinue
  Emit ("  PID {0} 是否仍存在 = {1}" -f $p, $(if ($proc) { '是（未停净）' } else { '否（已退出）' }))
}
$connAfter = ((Sql "SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE USER LIKE '$token%';") | Out-String).Trim()
Emit "停后：3307 上本 run 账号连接数 = $connAfter"
Emit "停后：8091 health 探测 =>"
try { $r = Invoke-WebRequest -Uri "http://127.0.0.1:$Port/api/v1/health" -SkipHttpErrorCheck -TimeoutSec 8; Emit "  HTTP $([int]$r.StatusCode)（预期应失败）" }
catch { Emit "  不可达（预期）: $($_.Exception.Message)" }

Emit "`n===== 4) 端口占用总览（不得留僵尸）====="
foreach ($pt in $AlsoCheck) {
  $l = @(Get-NetTCPConnection -State Listen -LocalPort $pt -ErrorAction SilentlyContinue)
  Emit ("  端口 {0}: 监听条数 = {1} {2}" -f $pt, $l.Count, $(if ($l.Count -eq 0) { '(空闲)' } else { 'PID=' + (($l | Select-Object -ExpandProperty OwningProcess -Unique) -join ',') }))
}
Emit "`n===== 5) 相关 java 进程（说明归属，勿误杀 IDE）====="
Get-Process -Name java -ErrorAction SilentlyContinue | ForEach-Object {
  Emit ("  PID={0} start={1} path={2}" -f $_.Id, $_.StartTime.ToString('yyyy-MM-dd HH:mm:ss'), $_.Path)
}

$verdict = ($(if ($still.Count -eq 0) { 'PASS' } else { 'FAIL' }))
Emit "`n[结论] 8091 已停净 = $verdict ; 本 run 连接数 $connBefore -> $connAfter"
$lines | Set-Content $out -Encoding utf8
Write-Output "[60] 证据已写 $out"
if ($still.Count -eq 0) { exit 0 } else { exit 7 }
