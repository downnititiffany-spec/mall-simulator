# V25-S02/K-04 evidence tool: fake MySQL client (stub).
#
# Why: K-04's criterion is "when the clean-up gate refuses, the production database must not
# be touched". Testing that with the real mysql.exe is itself unsafe: if the gate ever failed,
# the test would really delete rows on host 3306. This stub makes "was the DB client invoked,
# and with which command line" observable, while opening NO connection at all.
#
# Behaviour (env-controlled; statements are classified because the preview is a SELECT while
# the delete is a DELETE, and the two must be constructible independently):
#   K04_STUB_MODE         = ok | fail            force failure regardless of statement
#   K04_STUB_FAIL_ON      = '' | DELETE | COUNT  in ok mode, fail only that statement class
#   K04_STUB_ROWS         = output for generic/read-only/fingerprint statements
#   K04_STUB_PREVIEW_ROWS = output when the statement is SELECT COUNT(*)
#   K04_STUB_DELETE_ROWS  = output when the statement is DELETE (the ROW_COUNT line)
#   K04_STUB_EXIT         = failure exit code (default 1)
#   K04_STUB_LOG          = call log (tab separated; NEVER records the password value,
#                           only whether MYSQL_PWD was set). Defaults to a FIXED path under
#                           %TEMP%; the driver cannot pass an env var to us, see below.
#
# Why the log path is fixed rather than injected: run-demo.ps1 launches the MySQL client in a
# way that does NOT inherit the driver's process environment (reproduced: the gate verdicts
# arrive, but a driver-set K04_STUB_LOG is invisible here and the log stayed empty).
# A fixed location plus "driver deletes it before each case" gives the same evidence without
# depending on inheritance.
#
# NOTE: this is an evidence tool, not product code; only the scripts under
# docs/acceptance/... invoke it.
#
# The parameter is deliberately NOT named $Args: $Args is an automatic variable and, when the
# wrapper passed an argument beginning with `-e` (as run-demo.ps1 does), parameter binding
# failed with "参数名称 'e' 存在歧义" and the stub exited 1 without logging anything.
param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
$ErrorActionPreference = 'Continue'
# argv 由 tools/mysql-stub.cmd 以"每行一个参数"的临时文件交过来（见该 .cmd 顶部注释：
# 经命令行转交会被 pwsh/cmd 二次解析，mysql 的 `-e` 与含 `;` 的 SQL 都过不去）。
if ($env:K04_STUB_ARGV -and (Test-Path $env:K04_STUB_ARGV)) {
  $Rest = @(Get-Content -Path $env:K04_STUB_ARGV -Encoding Default)
}
$joined = ($Rest -join ' ')
$mode = if ($env:K04_STUB_MODE) { $env:K04_STUB_MODE } else { 'ok' }
$failOn = $env:K04_STUB_FAIL_ON
$log = if ($env:K04_STUB_LOG) { $env:K04_STUB_LOG } else { Join-Path $env:TEMP 'k04-stub-invocations.tsv' }
if ($log) {
  $pwdFlag = if ($env:MYSQL_PWD) { 'MYSQL_PWD=<set>' } else { 'MYSQL_PWD=<unset>' }
  Add-Content -Path $log -Encoding utf8 -Value ("{0}`t{1}" -f $joined, $pwdFlag)
}
# Order matters: the fingerprint statement is SELECT CONCAT(...), the delete is
# `DELETE ...; SELECT ROW_COUNT();`, and only the preview is SELECT COUNT(*).
# The fingerprint check must precede the COUNT check or it is mistaken for a preview.
$kind = if ($joined -match 'DELETE') { 'DELETE' } elseif ($joined -match 'CONCAT\(@@port') { 'READ' } elseif ($joined -match 'COUNT\(\*\)') { 'COUNT' } else { 'READ' }
$shouldFail = ($mode -eq 'fail') -or ($failOn -and $failOn -eq $kind)
if ($shouldFail) {
  $msg = if ($env:K04_STUB_MSG) { $env:K04_STUB_MSG } else { "ERROR 1045 (28000): Access denied for user (stub, kind=$kind)" }
  [Console]::Error.WriteLine($msg)
  exit ([int]$(if ($env:K04_STUB_EXIT) { $env:K04_STUB_EXIT } else { 1 }))
}
switch ($kind) {
  'DELETE' { Write-Output $(if ($env:K04_STUB_DELETE_ROWS) { $env:K04_STUB_DELETE_ROWS } else { '7' }) }
  'COUNT' { Write-Output $(if ($env:K04_STUB_PREVIEW_ROWS) { $env:K04_STUB_PREVIEW_ROWS } else { '7' }) }
  default { Write-Output $(if ($env:K04_STUB_ROWS) { $env:K04_STUB_ROWS } else { '0' }) }
}
exit 0
