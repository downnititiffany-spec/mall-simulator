# e1d-mutation.ps1 —— E1-d 变异验证（守卫非空转）
# 步骤：① 记现态 sha256 → ② 把 "const": "mock-mall" 塞回 source_system → ③ 跑对账测试（**必须失败**）
#       ④ 从内存字节恢复（不是从 git checkout）→ ⑤ 核对 sha256 与步骤①逐字符相同 → ⑥ 再跑一次（**必须通过**）
# 纪律：临时改动**不入库**；无论中途成败，finally 一律按字节恢复。
[CmdletBinding()]
param([string]$Wt = 'D:\Develop_code\GraduationProject-wt\ct-batch')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$ev = "$Wt\.verify\ct-batch"
$schema = "$Wt\contract-specs\schemas\canonical-event.v1.schema.json"
$log = New-Object System.Collections.Generic.List[string]
function L { param([string]$s) $script:log.Add($s) }

$orig = [IO.File]::ReadAllBytes($schema)
$origH = (Get-FileHash -InputStream ([IO.MemoryStream]::new($orig)) -Algorithm SHA256).Hash
$EXPECT = '8A8F8A432678CBEF180D34E16E9922147A9F46A5483977760DA16C2756A9A384'
L "=== E1-d 变异验证 ==="
L "schema 现态 : sha256=$origH  ($($orig.Length) B)"
L "期望值      : $EXPECT"
L ("[ {0} ] 现态与 E1-b 读数一致（变异前基线可信）" -f $(if ($origH -eq $EXPECT) { 'OK ' } else { 'FAIL' }))
L ""

$env:JAVA_HOME = 'D:\Develop\JAVA17'
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8'
$env:MAVEN_OPTS = '-Xmx1024m'
$repoArg = '-Dmaven.repo.local=D:\maven_repository'

function Run-Parity {
  param([string]$LogPath)
  & 'D:\apache-maven-3.9.14\bin\mvn.cmd' -o -f "$Wt\analytics-server\pom.xml" -pl platform-common -am test '-DforkCount=0' '-Dtest=CanonicalEventSchemaParityTest' '-DfailIfNoSpecifiedTests=false' "$repoArg" 2>&1 |
    Tee-Object -FilePath $LogPath | Out-Null
  return $LASTEXITCODE
}

$codeMut = $null
$codeOk = $null
try {
  # ---- ② 变异 ----
  $t = [Text.Encoding]::UTF8.GetString($orig)
  $anchor = '    "source_system": {' + "`r`n"
  $n = ([regex]::Matches($t, [regex]::Escape($anchor))).Count
  L "[$(if ($n -eq 1) { 'OK ' } else { 'FAIL' })] 变异锚 'source_system' 顶层属性命中 = $n（应然 1）"
  if ($n -ne 1) { throw "变异锚不唯一，放弃变异" }
  $mut = $t.Replace($anchor, $anchor + '      "const": "mock-mall",' + "`r`n", 1)
  $mb = [Text.Encoding]::UTF8.GetBytes($mut)
  [IO.File]::WriteAllBytes($schema, $mb)
  $mutH = (Get-FileHash -InputStream ([IO.MemoryStream]::new($mb)) -Algorithm SHA256).Hash
  $m = [Text.Encoding]::UTF8.GetString($mb)
  $hasMut = $m.Contains('"const": "mock-mall"')
  L "[$(if ($hasMut) { 'OK ' } else { 'FAIL' })] 变异后 schema 已含 const（读回确认）"
  L "[$(if ($mutH -ne $origH) { 'OK ' } else { 'FAIL' })] 变异后 sha256 已变：$mutH"
  L ""

  # ---- ③ 变异态跑测试 ----
  L "--- ③ 变异态跑 CanonicalEventSchemaParityTest（期望 **BUILD FAILURE**）---"
  $codeMut = Run-Parity "$ev\e1d-mutation.log"
  $mlog = Get-Content "$ev\e1d-mutation.log" -Raw
  $mutFailed = ($mlog -match 'BUILD FAILURE') -and ($mlog -match 'CanonicalEventSchemaParityTest')
  $mutPassed = ($mlog -match 'BUILD SUCCESS')
  L "[$(if ($mutFailed -and -not $mutPassed) { 'OK ' } else { 'FAIL' })] 变异态：mvn exit=$codeMut；BUILD FAILURE=$($mlog -match 'BUILD FAILURE')；BUILD SUCCESS=$mutPassed"
  $lines = Select-String -Path "$ev\e1d-mutation.log" -Pattern '^\[ERROR\].*(schema_version|source_system|不得再锁定|必须锁定)' |
    ForEach-Object { $_.Line }
  L "    变异态失败消息（守卫命中的断言）："
  foreach ($x in $lines) { L "      $x" }
  L ("[ {0} ] 失败消息明确指向 source_system 的 const 守卫" -f $(if ($lines -match 'source_system') { 'OK ' } else { 'FAIL' }))
  L ""
}
finally {
  # ---- ④ 恢复 ----
  [IO.File]::WriteAllBytes($schema, $orig)
  $backH = (Get-FileHash $schema -Algorithm SHA256).Hash
  L "--- ④⑤ 恢复并核对 ---"
  L "[$(if ($backH -eq $origH) { 'OK ' } else { 'FAIL' })] 已按字节恢复：sha256=$backH（与变异前逐字符相同：$($backH -eq $origH)）"
  L "[$(if ($backH -eq $EXPECT) { 'OK ' } else { 'FAIL' })] 恢复值与 E1-b/E1-c 登记的 8A8F8A43… 一致"
  L ""

  # ---- ⑥ 恢复态再跑 ----
  L "--- ⑥ 恢复态再跑（期望 **BUILD SUCCESS**）---"
  $codeOk = Run-Parity "$ev\e1d-mutation-restored.log"
  $rlog = Get-Content "$ev\e1d-mutation-restored.log" -Raw
  $okPassed = $rlog -match 'BUILD SUCCESS'
  L "[$(if ($okPassed) { 'OK ' } else { 'FAIL' })] 恢复态：mvn exit=$codeOk；BUILD SUCCESS=$okPassed"
  $tr = (Select-String -Path "$ev\e1d-mutation-restored.log" -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' | Select-Object -Last 1).Line
  L "    $tr"
  $tmut = (Select-String -Path "$ev\e1d-mutation.log" -Pattern 'Tests run: \d+, Failures: \d+, Errors: \d+, Skipped: \d+$' | Select-Object -Last 1).Line
  L "    变异态读数：$tmut"
}

$verdict = ($codeMut -ne 0 -and $codeOk -eq 0)
L ""
L "VERDICT=$(if ($verdict) { 'PASS' } else { 'FAIL' })  MUTATION_EXIT=$codeMut  RESTORED_EXIT=$codeOk"
L "TEMP_CHANGE_PERSISTED=$([IO.File]::ReadAllBytes($schema).Length -ne $orig.Length)"
$log -join "`r`n" | Set-Content "$ev\e1d-mutation.txt" -Encoding utf8
$log -join "`r`n"
