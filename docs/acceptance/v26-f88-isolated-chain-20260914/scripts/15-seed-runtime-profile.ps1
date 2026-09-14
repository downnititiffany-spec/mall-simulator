<#
V26-F88 | 隔离准备：把隔离 meta 库的 runtime_profile 行补成可运行的 ACTIVE 配置。

为什么必须做（不是「改小应用」）：
  V7 迁移播下的 local-dev profile 是 status='DRAFT' 且 spark_master/spark_submit_path/
  spark_job_jar_uri 全为 NULL；而 RuntimeProfileServiceImpl.findActive 要求存在 ACTIVE 的
  runtime_profile，否则 fail-closed，链路会在第一步就断。E3 泳道已实证并留下同源脚本
  (v25-e3-isolated-chain-20260914/raw/04b-mirror-runtime-profile.sql)。

与 E3 的**差异（本泳道刻意收紧）**：
  E3 从 3306 只读 mirror 这些字段；本泳道**完全不读 3306**（铁律 1 把 3306 的 SELECT
  限定为「只为出前后指纹对比」）。改为从**仓库内工件**取真值并记录 sha256：
    spark_submit_path = D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd
    spark_job_jar_uri = D:/Develop_code/GraduationProject/spark-jobs/target/spark-jobs-0.1.0-SNAPSHOT.jar
  两者存在性与 sha256 在运行前断言并落证。

唯一写入目标：127.0.0.1:3307（端口+uuid 前置核对，不符即 throw，不连接）。
#>
param(
  [Parameter(Mandatory = $true)][string]$RunId,
  [string]$MysqlExe = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe',
  [string]$OutDir = (Join-Path (Split-Path -Parent $PSScriptRoot) 'raw'),
  [string]$ExpectUuid = 'de8ebbea-aff4-11f1-8037-00155d5dba47',
  [int]$Port = 3307,
  [string]$SparkSubmit = 'D:\Develop\spark-3.5.1-bin-hadoop3\bin\spark-submit.cmd',
  [string]$SparkJobJar = 'D:\Develop_code\GraduationProject\spark-jobs\target\spark-jobs-0.1.0-SNAPSHOT.jar',
  [string]$SparkMaster = 'local[2]'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
if ($Port -ne 3307) { throw "本脚本只允许 3307（当前 $Port）" }
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$out = Join-Path $OutDir '15-seed-runtime-profile.txt'
$db = "analytics_meta_$($RunId -replace '-','_')"

$lines = New-Object System.Collections.Generic.List[string]
function Emit([string]$s) { $lines.Add($s); Write-Host $s }

# ── 前置：Spark 工件真值 + sha256 ─────────────────────────────────────
foreach ($p in @($SparkSubmit, $SparkJobJar)) {
  if (-not (Test-Path $p)) { throw "缺少 Spark 工件: $p" }
}
$sjHash = (Get-FileHash $SparkSubmit -Algorithm SHA256).Hash
$jjHash = (Get-FileHash $SparkJobJar -Algorithm SHA256).Hash
Emit "V26-F88 隔离准备：runtime_profile 激活  $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
Emit "spark_submit_path = $SparkSubmit"
Emit "  sha256 = $sjHash"
Emit "spark_job_jar_uri = $SparkJobJar"
Emit "  sha256 = $jjHash"
Emit "  （对照 E3 记录值 71C2BCCB54066996E7197DEA004E88F16B4DF912481CB723B4D84CC33CE0C966 ⇒ 相等: $($jjHash -eq '71C2BCCB54066996E7197DEA004E88F16B4DF912481CB723B4D84CC33CE0C966'))"
Emit "spark_master = $SparkMaster   目标库 = $db   实例 = 127.0.0.1:$Port"

$old = $env:MYSQL_PWD; $env:MYSQL_PWD = '123456'
function Q([string]$title, [string]$sql) {
  Emit "`n### $title"
  Emit "SQL: $sql"
  & $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -t -e $sql 2>&1 | ForEach-Object { Emit $_.ToString() }
}
try {
  $id = (& $MysqlExe --host=127.0.0.1 "--port=$Port" --user=root -N -B -e "SELECT CONCAT(@@port,'|',@@server_uuid);" 2>&1) | Out-String
  $id = $id.Trim()
  if ($id -notmatch "^3307\|$ExpectUuid$") { throw "目标不是隔离实例，拒绝写入：$id" }
  Emit "`n### 写前指纹核对通过（仅 3307）: $id"

  Q '写前：runtime_profile' "SELECT id, profile_code, status, spark_master, spark_submit_path, spark_job_jar_uri, source_id, version FROM $db.runtime_profile;"
  $jarUri = $SparkJobJar.Replace('\', '/')
  # MySQL 字符串字面量里反斜杠是转义引导符：单个 '\' 会被吞掉（'\D' → 'D'）。
  # 因此必须把每个 '\' 写成 '\\'（**恰好两个**）。用 .Replace 做字面替换，
  # 不要用 -replace（它是正则，替换串里的 '\' 不参与转义，会写出 4 个反斜杠，
  # 落库成 'D:\\Develop\\...' 这种非法路径 —— 本泳道首次运行即踩到此坑，已修正并复跑）。
  $subEsc = $SparkSubmit.Replace('\', '\\')
  Q '执行隔离准备 UPDATE（写 3307）' @"
UPDATE $db.runtime_profile
   SET status            = 'ACTIVE',
       spark_master      = '$SparkMaster',
       spark_submit_path = '$subEsc',
       spark_job_jar_uri = '$jarUri',
       source_id         = 1,
       version           = 3,
       updated_at        = NOW(6)
 WHERE id = 1 AND profile_code = 'local-dev';
"@
  Q '写后：runtime_profile' "SELECT id, profile_code, status, spark_master, spark_submit_path, spark_job_jar_uri, source_id, version FROM $db.runtime_profile;"
  Q 'ACTIVE profile 行数（必须 = 1）' "SELECT COUNT(*) AS active_profile_rows FROM $db.runtime_profile WHERE status='ACTIVE';"
  Q 'source_registry（source_id=1 必须 ACTIVE）' "SELECT id, source_code, status FROM $db.source_registry;"
} finally { $env:MYSQL_PWD = $old }
$lines | Set-Content $out -Encoding utf8
Write-Output "[15] 证据已写 $out"
exit 0
