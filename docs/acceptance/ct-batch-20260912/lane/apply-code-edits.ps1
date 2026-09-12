# apply-code-edits.ps1 —— CT-1 平台侧代码面（常量退休 ＋ 两处测试同步）
# 断言口径（PLAN §2）：每处先断言旧串命中 == 1，替换后断言新串 == 1 ∧ 旧串 == 0；任一不符即**整体不写盘**。
# 纪律：只改这三个文件；不跑 Maven（E1-a 由外部单独跑）。
[CmdletBinding()]
param([string]$Wt = 'D:\Develop_code\GraduationProject-wt\ct-batch',
      [switch]$Apply)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$enc = [Text.UTF8Encoding]::new($false)

$pMain = "$Wt\analytics-server\platform-common\src\main\java\com\graduation\analytics\contracts\EventContract.java"
$pParity = "$Wt\analytics-server\platform-common\src\test\java\com\graduation\analytics\contracts\CanonicalEventSchemaParityTest.java"
$pSrcReg = "$Wt\analytics-server\platform-app\src\test\java\com\graduation\analytics\source\SourceRegistryMigrationScriptTest.java"

$log = New-Object System.Collections.Generic.List[string]
function L { param([string]$s) $script:log.Add($s) }
$okAll = $true
function Assert1 { param([string]$name, [string]$hay, [string]$needle, [int]$expect)
  $n = ([regex]::Matches($hay, [regex]::Escape($needle))).Count
  $ok = ($n -eq $expect); $script:okAll = $script:okAll -and $ok
  L ("[{0}] {1}: 命中 {2}（应然 {3}）" -f $(if ($ok) { 'OK ' } else { 'FAIL' }), $name, $n, $expect)
}

# ---------------- 1) EventContract.java：整行删除常量 ----------------
$main = [IO.File]::ReadAllText($pMain, $enc)
$A_CONST = '    public static final String SOURCE_SYSTEM = "mock-mall";'
L ''
L "=== 1) EventContract.java（$([IO.Path]::GetFileName($pMain))）==="
Assert1 '旧常量行（应为 1）' $main (($A_CONST) + "`r`n") 1
Assert1 '退休占位注释（应为 0）' $main 'SOURCE_SYSTEM 已退休' 0

# ---------------- 2) CanonicalEventSchemaParityTest.java ----------------
$par = [IO.File]::ReadAllText($pParity, $enc)
$CRLF = "`r`n"
$A_DISPLAY = '    @DisplayName("schema_version / source_system 常量与 EventContract 一致")'
$N_DISPLAY = '    @DisplayName("结构守卫：schema_version 仍锁 const；source_system 只受形状约束、不得锁 const")'
$A_ASSERT = @(
'        assertEquals(EventContract.SCHEMA_VERSION,',
'                root.path("properties").path("schema_version").path("const").asText(),',
'                "schema_version 必须锁定为 EventContract.SCHEMA_VERSION");',
'        assertEquals(EventContract.SOURCE_SYSTEM,',
'                root.path("properties").path("source_system").path("const").asText(),',
'                "source_system 必须锁定为 EventContract.SOURCE_SYSTEM");'
) -join $CRLF
$N_ASSERT = @(
'        // 正向对照（D-061）：schema_version 仍然锁定 const —— 证明下面那条"不得带 const"的守卫不是空转。',
'        assertEquals(EventContract.SCHEMA_VERSION,',
'                root.path("properties").path("schema_version").path("const").asText(),',
'                "schema_version 必须锁定为 EventContract.SCHEMA_VERSION");',
'        JsonNode source = root.path("properties").path("source_system");',
'        assertFalse(source.has("const"),',
'                "D-061：source_system 不得再锁定 const（值域归 source_registry.source_code，契约只约束形状）");',
'        assertEquals("string", source.path("type").asText(),',
'                "D-061：source_system 必须是 string 形状约束");',
'        assertEquals(1, source.path("minLength").asInt(),',
'                "D-061：source_system 必须带 minLength=1（非空）形状约束");'
) -join $CRLF
L ''
L "=== 2) CanonicalEventSchemaParityTest.java ==="
Assert1 '旧 @DisplayName（应为 1）' $par $A_DISPLAY 1
Assert1 '旧断言块（应为 1）' $par $A_ASSERT 1
Assert1 '旧常量引用 EventContract.SOURCE_SYSTEM（应为 2 处：断言参数 + 失败消息）' $par 'EventContract.SOURCE_SYSTEM' 2
Assert1 '新守卫锚 assertFalse(source.has("const")（应为 0）' $par 'source.has("const")' 0

# ---------------- 3) SourceRegistryMigrationScriptTest.java ----------------
$sr = [IO.File]::ReadAllText($pSrcReg, $enc)
$A_AS = '.as("种子源编码必须等于冻结契约里的 source_system 取值 mock-mall")'
$N_AS = '.as("种子源编码必须等于首个源的 source_code（契约不再固定该值，D-061）")'
L ''
L "=== 3) SourceRegistryMigrationScriptTest.java ==="
Assert1 '旧 .as(...) 理由串（应为 1）' $sr $A_AS 1
Assert1 '旧断言本体 .contains("''mock-mall''")（应为 1，**不改**）' $sr '.contains("''mock-mall''")' 1

L ''
if (-not $okAll) {
  L 'SELFCHECK-FAIL：写前断言未通过 ⇒ **不写盘**'
  $log -join "`r`n" | Set-Content 'D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\apply-code-edits.log' -Encoding utf8
  $log -join "`r`n"
  exit 2
}

# ---- 应用 ----
$newMain = $main.Replace($A_CONST + $CRLF, '')
$newPar = $par.Replace($A_DISPLAY, $N_DISPLAY).Replace($A_ASSERT, $N_ASSERT)
$newSr = $sr.Replace($A_AS, $N_AS)

L ''
L '=== 写后复核（新串 == 1 ∧ 旧串 == 0）==='
function Assert2 { param([string]$name, [string]$hay, [string]$needle, [int]$expect)
  $n = ([regex]::Matches($hay, [regex]::Escape($needle))).Count
  $ok = ($n -eq $expect); $script:okAll = $script:okAll -and $ok
  L ("[{0}] {1}: {2}（应然 {3}）" -f $(if ($ok) { 'OK ' } else { 'FAIL' }), $name, $n, $expect)
}
Assert2 'main: 旧常量残留' $newMain 'SOURCE_SYSTEM = "mock-mall"' 0
Assert2 'main: SCHEMA_VERSION 仍在' $newMain 'public static final String SCHEMA_VERSION = "1.0";' 1
Assert2 'parity: 旧 @DisplayName 残留' $newPar $A_DISPLAY 0
Assert2 'parity: 新 @DisplayName' $newPar $N_DISPLAY 1
Assert2 'parity: 旧断言块残留' $newPar $A_ASSERT 0
Assert2 'parity: 常量引用残留' $newPar 'EventContract.SOURCE_SYSTEM' 0
Assert2 'parity: 正向对照（schema_version const 断言）' $newPar 'path("schema_version").path("const").asText()' 1
Assert2 'parity: 新守卫' $newPar 'assertFalse(source.has("const")' 1
Assert2 'srcreg: 旧理由串残留' $newSr $A_AS 0
Assert2 'srcreg: 新理由串' $newSr $N_AS 1
Assert2 'srcreg: 断言本体保留' $newSr '.contains("''mock-mall''")' 1

# 逐行差异
L ''
L '=== 行级差异（unified, n=0）==='
foreach ($pair in @(@('EventContract.java', $main, $newMain), @('CanonicalEventSchemaParityTest.java', $par, $newPar), @('SourceRegistryMigrationScriptTest.java', $sr, $newSr))) {
  $d = Compare-Object ($pair[1] -split $CRLF) ($pair[2] -split $CRLF) -IncludeEqual:$false
  L ("--- {0} ：变更行数 = {1} ---" -f $pair[0], $d.Count)
  foreach ($x in $d) { L ("    {0} {1}" -f $x.SideIndicator, $x.InputObject) }
}
L ''
foreach ($pair in @(@($pMain, $newMain), @($pParity, $newPar), @($pSrcReg, $newSr))) {
  $nb = $enc.GetBytes($pair[1])
  L ("{0}`r`n    sha256(新)={1} bytes={2} CR={3} LF={4}" -f $pair[0], (Get-FileHash -InputStream ([IO.MemoryStream]::new($nb)) -Algorithm SHA256).Hash, $nb.Length, ([regex]::Matches($pair[1], "`r")).Count, ([regex]::Matches($pair[1], "`n")).Count)
}

if (-not $okAll) {
  L 'SELFCHECK-FAIL：写后复核未通过 ⇒ **不写盘**'
  $log -join "`r`n" | Set-Content 'D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\apply-code-edits.log' -Encoding utf8
  $log -join "`r`n"
  exit 3
}
if ($Apply) {
  [IO.File]::WriteAllText($pMain, $newMain, $enc)
  [IO.File]::WriteAllText($pParity, $newPar, $enc)
  [IO.File]::WriteAllText($pSrcReg, $newSr, $enc)
  L '>>> 已写盘（-Apply）'
} else {
  L '>>> 干跑（未加 -Apply）；未写盘'
}
$log -join "`r`n" | Set-Content 'D:\Develop_code\GraduationProject-wt\ct-batch\.verify\ct-batch\apply-code-edits.log' -Encoding utf8
$log -join "`r`n"
