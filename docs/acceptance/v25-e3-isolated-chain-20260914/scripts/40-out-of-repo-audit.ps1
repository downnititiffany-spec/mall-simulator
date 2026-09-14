# V25-E3 收尾合规核查：仓库外产物审计 + 工件身份 + 变更面
# 只读；不改任何仓库文件。输出 raw/40-out-of-repo-audit.txt
param(
  [string]$RepoRoot = 'D:\Develop_code\GraduationProject',
  [string]$RunId = 'v25it-20260914-1358-l4e3',
  [string]$EvidenceRoot = 'D:\Develop_code\GraduationProject\docs\acceptance\v25-e3-isolated-chain-20260914'
)
$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$raw = Join-Path $EvidenceRoot 'raw'
$out = Join-Path $raw '40-out-of-repo-audit.txt'
$L = New-Object System.Collections.Generic.List[string]
function A([string]$s) { $L.Add($s); Write-Host $s }

A "V25-E3 收尾合规核查  $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"

A ""
A "### 1) 被实测工件身份（不可变引用）"
foreach ($rel in @('analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar',
                   'spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar')) {
  $p = Join-Path $RepoRoot $rel
  if (Test-Path $p) {
    $i = Get-Item $p
    $h = (Get-FileHash $p -Algorithm SHA256).Hash
    A ("  {0}`n    bytes={1}  mtime={2:yyyy-MM-dd HH:mm:ss}  sha256={3}" -f $rel, $i.Length, $i.LastWriteTime, $h)
  } else { A "  $rel  <缺失>" }
}
$mig = Join-Path $RepoRoot 'analytics-server\platform-app\src\main\resources\db\meta'
$inJar = @()
$zip = [System.IO.Compression.ZipFile]::OpenRead((Join-Path $RepoRoot 'analytics-server\platform-app\target\platform-app-0.1.0-SNAPSHOT.jar'))
try { $inJar = @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/classes/db/meta/*' } | ForEach-Object { Split-Path $_.FullName -Leaf } | Sort-Object) }
finally { $zip.Dispose() }
A ("  jar 内 db/meta 迁移脚本 {0} 个: {1}" -f $inJar.Count, ($inJar -join ', '))
$srcMig = @(Get-ChildItem $mig -Filter '*.sql' | Sort-Object Name)
A ("  源码 db/meta 迁移脚本 {0} 个: {1}" -f $srcMig.Count, (($srcMig | ForEach-Object Name) -join ', '))
$onlySrc = @($srcMig | Where-Object { $inJar -notcontains $_.Name })
A ("  **源码有、jar 内没有**（即晚于被实测工件，未被本次实测覆盖）: {0}" -f ($(if ($onlySrc.Count) { ($onlySrc | ForEach-Object { "$($_.Name) (mtime $($_.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss')))" }) -join '; ' } else { '无' })))

A ""
A "### 2) 本泳道允许写入的两处，实际路径"
A "  证据目录 : $EvidenceRoot"
A "  运行目录 : $(Join-Path $RepoRoot "target\e3-run\$RunId")"
$runDir = Join-Path $RepoRoot "target\e3-run\$RunId"
A ("  运行目录存在={0}；其父目录 target\e3-run 下条目：" -f (Test-Path $runDir))
Get-ChildItem (Join-Path $RepoRoot 'target\e3-run') | ForEach-Object { A ("    - {0}  (mtime {1:yyyy-MM-dd HH:mm:ss})" -f $_.Name, $_.LastWriteTime) }

A ""
A "### 3) 仓库外产物审计（硬约束：仓库外不得新建任何文件/目录）"
A "  3.1 已放弃的候选路径（应不存在）"
foreach ($p in @("$env:USERPROFILE\.graduation", 'D:\e3-run', 'D:\e3-evidence', 'D:\e3-artifacts', 'D:\maven_jars')) {
  A ("    {0} -> {1}" -f $p, $(if (Test-Path $p) { '**存在（需说明）**' } else { '不存在 OK' }))
}
A "  3.2 D:\ 根目录下匹配 e3 / p*-e3 的条目"
$hit = @(Get-ChildItem 'D:\' -Force -ErrorAction SilentlyContinue | Where-Object { $_.Name -match '(?i)^e3' -or $_.Name -match '(?i)e3$' })
A ("    命中 {0} 条{1}" -f $hit.Count, $(if ($hit.Count) { '：' + (($hit | ForEach-Object Name) -join ', ') } else { ' OK' }))
A "  3.3 D:\ 根目录下今天新建的 *.dump / *.sql / *.jar / *.log / *.txt"
$today = (Get-Date).Date
$hit2 = @(Get-ChildItem 'D:\' -Force -File -ErrorAction SilentlyContinue |
  Where-Object { $_.LastWriteTime -ge $today -and $_.Extension -in '.dump', '.sql', '.jar', '.log', '.txt', '.csv', '.jsonl' })
A ("    命中 {0} 条{1}" -f $hit2.Count, $(if ($hit2.Count) { '：' + (($hit2 | ForEach-Object { "$($_.Name)@$($_.LastWriteTime.ToString('HH:mm:ss'))" }) -join ', ') } else { ' OK' }))
A "  3.4 C:\ 根目录下今天新建的目录"
$hit3 = @(Get-ChildItem 'C:\' -Force -Directory -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -ge $today })
A ("    命中 {0} 条{1}（注意：系统目录自身 mtime 会被无关程序触碰，仅作提示）" -f $hit3.Count, $(if ($hit3.Count) { '：' + (($hit3 | ForEach-Object Name) -join ', ') } else { ' OK' }))
A "  3.5 本泳道**已披露**的唯一仓库外副作用：Spark/Derby 自建 OS 临时目录"
$sparkTmp = @(Get-ChildItem "$env:LOCALAPPDATA\Temp" -Force -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -like 'spark-*' -or $_.Name -like 'derby*' })
A ("    {0}\Temp 下 spark-*/derby* 残留 {1} 个" -f $env:LOCALAPPDATA, $sparkTmp.Count)
$sparkTmp | Sort-Object LastWriteTime | Select-Object -Last 6 | ForEach-Object { A ("      - {0}  (mtime {1:yyyy-MM-dd HH:mm:ss})" -f $_.Name, $_.LastWriteTime) }
A "    说明：这些由 spark-submit / Derby 自身在 OS 临时目录创建，路径非本泳道选择，作业日志里有其自删告警（ShutdownHookManager 清理失败告警，见 raw 作业日志），不作为「主动写在仓库外」计。"

A ""
A "### 4) 仓库内变更面（本泳道未改任何已提交文件）"
Push-Location $RepoRoot
try {
  A "  4.1 git status --porcelain（含其它泳道并行改动，逐条归属见下）"
  (git status --porcelain 2>&1) | ForEach-Object { A "    $_" }
  A "  4.2 本泳道声明「零修改」的关键已提交文件，逐个做 git diff --stat（空=未改）"
  foreach ($f in @('analytics-server/platform-app/src/main/resources/application.yml',
                   'analytics-server/platform-app/src/main/resources/db/meta',
                   'analytics-server/platform-app/src/main/resources/db/metric',
                   'analytics-server/warehouse-pipeline/src/main/java',
                   'analytics-server/platform-common/src/main/java',
                   'spark-jobs/src',
                   'mall-simulator/src',
                   'contract-specs')) {
    $d = (git diff --stat -- $f 2>&1 | Out-String).Trim()
    A ("    {0,-70} diff: {1}" -f $f, $(if ([string]::IsNullOrWhiteSpace($d)) { '<空> 未改' } else { '**有改动** -> ' + $d }))
  }
  A "  4.3 本泳道新增文件（未跟踪）过滤"
  (git status --porcelain --untracked-files=all 2>&1) | Where-Object { $_ -match 'v25-e3-isolated-chain-20260914' } | ForEach-Object { A "    $_" }
} finally { Pop-Location }

$L -join "`r`n" | Set-Content -Path $out -Encoding UTF8
Write-Host "`n证据已写 $out"
