# 生成器 8092 凭据注入重启 + 目标探针实测（MALL_API 前置）
$ErrorActionPreference = 'Continue'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$rawDir = Join-Path $root 'docs\acceptance\m1-4-u1u3-20260912\raw'
if (-not (Test-Path $rawDir)) { New-Item -ItemType Directory -Path $rawDir -Force | Out-Null }
$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$outFile = Join-Path $rawDir ("u1u3-gen-cred-restart-$stamp.log")
$L = New-Object System.Collections.Generic.List[string]
function W([string]$s) { $L.Add($s); Write-Host $s }
function Flush() { [System.IO.File]::WriteAllLines($outFile, $L, (New-Object System.Text.UTF8Encoding($false))) }

W ("=== M1-4 U1/U3 前置：8092 凭据注入重启 + 目标探针 · 起始 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
W ''
W '--- §1 缺口证据：MALL_API 运行被 409 拒绝（凭据环境变量在 8092 进程里取不到值）---'
$resp409 = Join-Path $root '.verify\u1u3-post-resp.txt'
if (Test-Path $resp409) {
  $keep = Join-Path $rawDir 'u1u3-mallapi-409-invalid-state-20260912.txt'
  Copy-Item $resp409 $keep -Force
  W ('  原文已落盘：' + $keep.Replace($root + '\', ''))
  W ('  内容：' + (Get-Content $resp409 -Raw))
}
W '  机制（源码）：ReferenceMallHttpAdapter 的 credential_ref 是**环境变量名**，取值走 System::getenv；取不到 -> 三条路由全 UNDETERMINED -> GenerationRunService 前置校验 409 INVALID_STATE，不降级、不跳过。'
W '  启动脚本缺口：scripts/start-all.ps1 第 82-89 行启动生成器时**不注入** GENERATOR_TARGET_TOKEN（全仓库仅本泳道脚本提到该名字）。'
Flush

W ''
W '--- §2 重启前身份 ---'
$c0 = Get-NetTCPConnection -State Listen -LocalPort 8092 -ErrorAction SilentlyContinue | Select-Object -First 1
W ('  8092 pid=' + $c0.OwningProcess + ' 启动=' + (Get-Process -Id $c0.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
W ('  在飞运行检查（应全为终态）：')
$my = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
& $my -uroot -p123456 -N --raw --default-character-set=utf8mb4 -e "SELECT CONCAT('    non_terminal=',COUNT(*)) FROM generator_meta.generation_run WHERE status IN ('QUEUED','RUNNING');" 2>$null | ForEach-Object { W $_ }
Flush

W ''
W '--- §3 取 8090 新会话令牌（只注入进程环境，不落盘、不打印值）---'
$login = Invoke-RestMethod -Uri 'http://127.0.0.1:8090/api/v1/auth/login' -Method Post -ContentType 'application/json' -Body '{"username":"admin","password":"admin123"}' -TimeoutSec 20
$tok = $login.data.token
W ('  code=' + $login.code + ' token长度=' + ($tok | Measure-Object -Character).Characters + '（值不落盘）')
$login.data.PSObject.Properties | Where-Object { $_.Name -ne 'token' } | ForEach-Object {
  $v = if ($_.Value -is [string]) { $_.Value } else { ($_.Value | ConvertTo-Json -Compress -Depth 4) }
  W ('  data.' + $_.Name + ' = ' + $v)
}
$env:GENERATOR_TARGET_TOKEN = $tok
W ('  已设置环境变量 GENERATOR_TARGET_TOKEN 于本会话（长度=' + $env:GENERATOR_TARGET_TOKEN.Length + '）')
Flush

W ''
W '--- §4 终止旧 8092 并等待端口释放 ---'
$oldPid = $c0.OwningProcess
Stop-Process -Id $oldPid -Force
$freed = $false
for ($i = 0; $i -lt 30; $i++) {
  Start-Sleep -Milliseconds 500
  if (-not (Get-NetTCPConnection -State Listen -LocalPort 8092 -ErrorAction SilentlyContinue)) { $freed = $true; break }
}
W ('  旧 pid=' + $oldPid + ' 已终止；端口释放=' + $freed)
Flush

W ''
W '--- §5 以注入环境启动新 8092（同一 jar、工作目录 synthetic-data-generator）---'
$jar = Join-Path $root 'synthetic-data-generator\target\synthetic-data-generator-0.1.0-SNAPSHOT.jar'
$wd = Join-Path $root 'synthetic-data-generator'
$p = Start-Process -FilePath 'D:\Develop\JAVA17\bin\java.exe' `
  -ArgumentList '-Dfile.encoding=UTF-8', '-jar', $jar `
  -WorkingDirectory $wd -PassThru -WindowStyle Hidden
W ('  新 pid=' + $p.Id + '  jar=' + (Get-Item $jar).Length + ' B  jar_mtime=' + (Get-Item $jar).LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss') + '  CWD=' + $wd)
$ok = $false
for ($i = 1; $i -le 40; $i++) {
  Start-Sleep -Seconds 2
  try { $s = Invoke-WebRequest -Uri 'http://127.0.0.1:8092/api/v1/scenarios' -UseBasicParsing -TimeoutSec 5; if ($s.StatusCode -eq 200) { $ok = $true; break } } catch { }
}
$c1 = Get-NetTCPConnection -State Listen -LocalPort 8092 -ErrorAction SilentlyContinue | Select-Object -First 1
W ('  就绪=' + $ok + '  监听 pid=' + $c1.OwningProcess + ' 启动=' + (Get-Process -Id $c1.OwningProcess).StartTime.ToString('yyyy-MM-dd HH:mm:ss'))
Flush

W ''
W '--- §6 目标 118 定义（库内行）---'
& $my -uroot -p123456 -N --raw --default-character-set=utf8mb4 -e "SELECT CONCAT('  id=',id,' name=',name,' adapter=',adapter_type,' base_url=',base_url,' credential_ref=',IFNULL(credential_ref,'NULL'),' status=',status,' version=',version) FROM generator_meta.generator_target WHERE id=118;" 2>$null | ForEach-Object { W $_ }
W '  注：capabilities 列不入库回写（探针是即时判定），下面用接口实测。'
Flush

W ''
W '--- §7 目标探针实测 POST /api/v1/targets/118/test ---'
try {
  $t = Invoke-WebRequest -Uri 'http://127.0.0.1:8092/api/v1/targets/118/test' -Method Post -ContentType 'application/json' -Body '{}' -UseBasicParsing -TimeoutSec 60
  W ('  http=' + $t.StatusCode)
  W ('  响应体=' + $t.Content)
} catch {
  W ('  异常：' + $_.Exception.Message)
  if ($_.Exception.Response) { W ('  状态码=' + [int]$_.Exception.Response.StatusCode) }
}
Flush
W ''
W ("=== 日志结束 " + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss.fff') + " ===")
Flush
Write-Host ('[写出] ' + $outFile + '  (' + $L.Count + ' 行)')
