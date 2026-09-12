# P2-03 交付自检脚本（父侧可复算）
#
# 用途：父侧**只读**复算本泳道交付面。每一步都打印「判据 / 期望 / 实测」，不依赖我的记忆。
# 用法（仓库根 D:\Develop_code\GraduationProject）：
#     pwsh -NoProfile -File docs\acceptance\p2-03-surrogate-key-20260912\impl\selfcheck.ps1
#
# 本脚本**不改任何文件**（只有 Maven 会在 target/ 下写产物 + 需手动清嵌套残留，见 §6）。

$ErrorActionPreference = 'Continue'
$fail = 0
function Chk($name, $expected, $actual) {
  $ok = if ($expected -eq $actual) { 'PASS' } else { $fail++; 'FAIL' }
  Write-Host ("  [{0}] {1}`n        期望 = {2}`n        实测 = {3}" -f $ok, $name, $expected, $actual)
}

Write-Host "`n=== §0 契约文件未被改动（严禁改）==="
git diff --quiet -- contract-specs
Chk 'contract-specs 无 diff（git diff --quiet 退出码）' 0 $LASTEXITCODE
Chk 'surrogate-key.v1.json sha256' `
  '14385528205886CB3D90E89C4207054234F332D8946662F90E8C1EE8ECF75320' `
  (Get-FileHash 'contract-specs/specs/surrogate-key.v1.json' -Algorithm SHA256).Hash

Write-Host "`n=== §1 未删/未改的既有所有者（自证，非凭记忆）==="
git diff --quiet -- 'spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala'
Chk 'IdCodec.scala 与 HEAD 逐字节相同' 0 $LASTEXITCODE
Chk 'IdCodec.scala sha256（= D-091 指纹）' `
  'DB310E4A0D1214963AAD8B6A111C8FB9EA7AD58EAE290ED0F07179BDB5EDDA48' `
  (Get-FileHash 'spark-jobs/src/main/scala/com/graduation/analytics/sql/IdCodec.scala' -Algorithm SHA256).Hash
git diff --quiet -- 'spark-jobs/src/main/scala/com/graduation/analytics/sql/OdsLoadSql.scala'
Chk 'OdsLoadSql.scala 与 HEAD 逐字节相同（sourceCode 唯一属主未被碰）' 0 $LASTEXITCODE

Write-Host "`n=== §2 D-096 撤回：OdsV2SchemaOwnerSpec 已还原 HEAD ==="
git diff --quiet -- 'spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala'
Chk 'OdsV2SchemaOwnerSpec.scala 与 HEAD 逐字节相同' 0 $LASTEXITCODE
Chk 'OdsV2SchemaOwnerSpec.scala sha256（工作区）' `
  'EBBB5998A0D8249BE383B8E90F1081B15F3CF3CF75BC34598D467C62EA4E3E2F' `
  (Get-FileHash 'spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala' -Algorithm SHA256).Hash
Chk 'statements 计数断言回到 37' `
  'statements.size should be(37)' `
  ((Select-String -LiteralPath 'spark-jobs/src/test/scala/com/graduation/analytics/OdsV2SchemaOwnerSpec.scala' `
      -Pattern 'statements\.size should be\((\d+)\)').Matches.Groups[0].Value)

Write-Host "`n=== §3 D-096 撤回：新表名「可执行语句」归零（含阳性对照）==="
$src = Get-ChildItem -Path 'spark-jobs/src' -Recurse -File -Include '*.scala','*.sql' |
  Where-Object { $_.FullName -notmatch '\\target\\' }
$ex = $src | Select-String -Pattern '(CREATE\s+(EXTERNAL\s+)?TABLE[^\n]*dwd_surrogate_key_quality|INSERT\s+(OVERWRITE|INTO)[^\n]*dwd_surrogate_key_quality)' -ErrorAction SilentlyContinue
Chk 'dwd_surrogate_key_quality 可执行语句命中数' 0 (@($ex).Count)
$pos = $src | Select-String -Pattern '(CREATE\s+(EXTERNAL\s+)?TABLE[^\n]*dwd_reject_record|INSERT\s+(OVERWRITE|INTO)[^\n]*dwd_reject_record)' -ErrorAction SilentlyContinue
Chk '阳性对照 dwd_reject_record 可执行语句命中数（须 >0，证明正则有效）' 2 (@($pos).Count)
Write-Host "  [INFO] 字面出现总数（含注释，见 IMPL-REPORT §3.4 待裁决③）:"
$lit = $src | Select-String -Pattern 'dwd_surrogate_key_quality' -ErrorAction SilentlyContinue
Write-Host ("         {0} 处" -f @($lit).Count)
$lit | ForEach-Object { Write-Host ("         {0}:{1}" -f (Split-Path $_.Path -Leaf), $_.LineNumber) }

Write-Host "`n=== §4 白名单 11 文件 sha256 对拍 ==="
$expect = [ordered]@{
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/SurrogateKey.scala'                  = '22DEFB38F2ADCE1387A7CCFA5020AEACD81D0C2F1E8244A494B8FB45576C76AF'
  'spark-jobs/src/test/scala/com/graduation/analytics/SurrogateKeyVectorSupport.scala'          = 'B679A24641D562E2E087178437B38AC274F489AA821BB1D70CF52FF2007A4F7C'
  'spark-jobs/src/test/scala/com/graduation/analytics/sql/SurrogateKeySpec.scala'               = 'BB07F4EDAA5C81A80DE4D6CC1AAAD139D3671F21AEE1771A4E1407617C210A82'
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/DwdSql.scala'                         = 'A38EC916C547D5317F2A89041BBAA8EBB65EDFFA1CD66B7B38534031066BD9D3'
  'spark-jobs/src/main/scala/com/graduation/analytics/sql/DimSql.scala'                         = 'C4C3482A1689E85FB10C89FF9F8F8CC383E4CD621BE28BFABE4B19CE1F353D8E'
  'spark-jobs/src/main/scala/com/graduation/analytics/job/TradeDwdJob.scala'                    = '96E4A167BFD6637C7FBDC5182A844C5CB8273B524F22EE77CBDA6E9935DB06BF'
  'spark-jobs/src/main/scala/com/graduation/analytics/job/LocalSchemaInitJob.scala'             = 'FBE7865E7F8AD7E03C546F83FB4E0807556224EECB8AED7C169F07233F749E37'
  'warehouse/ddl/01-dwd.sql'                                                                    = 'B5C571585EDA3ACEBFB89ACA6948C82AAEF9E9611C3A7AA14DAF0C46C700F75A'
  'warehouse/ddl/02-dims.sql'                                                                   = 'C031CBD5EAF95ECEE72CC1FEEE9A92C9F947FCD21913BF44161B2882C86D8499'
  'spark-jobs/src/test/scala/com/graduation/analytics/IdCodecSpec.scala'                        = '3BC5253F9D833CE9C842EA473424EAD394972AE04A7F5A23AE5E056B5B1F2FF4'
  'spark-jobs/src/test/scala/com/graduation/analytics/WarehouseNamespaceSpec.scala'             = '73A0D532C040D44ADA42945885CD0EC74579C830701001A710B2D87F42FBDCB3'
}
foreach ($k in $expect.Keys) {
  $a = if (Test-Path $k) { (Get-FileHash -LiteralPath $k -Algorithm SHA256).Hash } else { '(文件不存在)' }
  Chk (Split-Path $k -Leaf) $expect[$k] $a
}

Write-Host "`n=== §5 越界审计：porcelain 里我的文件 ==="
$st = git status --porcelain
$mine = $st | Where-Object { $_ -match 'SurrogateKey|DwdSql|DimSql|TradeDwdJob|LocalSchemaInitJob|01-dwd\.sql|02-dims\.sql|IdCodecSpec|WarehouseNamespaceSpec|p2-03-surrogate-key-20260912' }
$outsiders = $st | Where-Object { $_ -notmatch 'SurrogateKey|DwdSql|DimSql|TradeDwdJob|LocalSchemaInitJob|01-dwd\.sql|02-dims\.sql|IdCodecSpec|WarehouseNamespaceSpec|p2-03-surrogate-key-20260912' }
Write-Host ("  我的条目 {0} 项；他泳道/父侧条目 {1} 项" -f @($mine).Count, @($outsiders).Count)
Write-Host "  --- 他泳道/父侧（仅供辨识别人的东西，不属我）---"
$outsiders | ForEach-Object { Write-Host "    $_" }
Write-Host "  --- 残留探针检查（须 0）---"
$probe = Get-ChildItem -Path 'spark-jobs/src' -Recurse -File -Filter 'P2*Probe*.scala' -ErrorAction SilentlyContinue
Chk 'P2*Probe*.scala 残留' 0 (@($probe).Count)
Chk '嵌套残留目录 spark-jobs/spark-jobs 不存在' $false (Test-Path 'spark-jobs/spark-jobs')

Write-Host "`n=== §6 全套测试复跑（唯一会写盘的一步；~6 分钟）==="
Write-Host "  命令见 IMPL-REPORT §2。要点：-Dmaven.repo.local 在 PowerShell 下【必须加引号】。"
Write-Host "  跑完后 §5 的嵌套目录检查会由 False 变 True ⇒ 请手动删除 spark-jobs\spark-jobs\。"
Write-Host "  期望：Total number of tests run: 111 / Suites: completed 15, aborted 0 / succeeded 111, failed 0"
Write-Host "  并应在输出中看到 23 行 [vector] V**、4 行 [A5]、5 行 [A7]。`n"

Write-Host "=== 结论 ==="
if ($fail -eq 0) { Write-Host "  全部只读判据 PASS（§6 需人工执行）" }
else { Write-Host ("  $fail 项 FAIL —— 交付面已漂移，请对照 IMPL-REPORT §1 排查") }
exit $fail
