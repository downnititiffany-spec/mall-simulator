# 隔离测试账号命名唯一所有者。
#
# MySQL 8.0 的账号名上限是 32 字符，而 testRunId 契约允许更长标识；不能再直接把
# `<runId>_<role>` 无条件当账号名。规则：
#   1) 原始名字 <= 32：完全保留，兼容已有对象/证据；
#   2) 超长：`<可读前缀>_<runId SHA256 前8hex>_<role>`，总长严格 <= 32；
#   3) 同一 runId + role 在 prepare / runner 中必须得到完全相同的结果。

function New-IsolationUserName {
  param(
    [Parameter(Mandatory = $true)][string]$RunId,
    [Parameter(Mandatory = $true)][string]$Role
  )

  $raw = "${RunId}_${Role}"
  if ($raw.Length -le 32) { return $raw }

  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($RunId)
    $digest = $sha.ComputeHash($bytes)
    $hash8 = -join ($digest[0..3] | ForEach-Object { $_.ToString('x2') })
  } finally {
    $sha.Dispose()
  }

  $suffix = "_${Role}"
  $prefixLength = 32 - 1 - $hash8.Length - $suffix.Length
  if ($prefixLength -lt 1) {
    throw "隔离账号角色名过长：Role='$Role' 无法在 MySQL 32 字符账号上限内编码。"
  }
  $prefix = $RunId.Substring(0, [Math]::Min($RunId.Length, $prefixLength))
  $name = "${prefix}_${hash8}${suffix}"
  if ($name.Length -gt 32) { throw "隔离账号名派生越界：$name" }
  return $name
}
