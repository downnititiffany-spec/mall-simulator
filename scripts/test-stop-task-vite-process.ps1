$ErrorActionPreference = 'Stop'

$helper = Join-Path $PSScriptRoot 'stop-task-vite-process.ps1'
if (-not (Test-Path -LiteralPath $helper -PathType Leaf)) {
  throw 'Task Vite process identity helper is missing.'
}

$expectedNodePath = 'C:\codex-test-fixture\node.exe'
$expectedRunId = 'phase8guard_20261007'
$pidFile = Join-Path ([IO.Path]::GetTempPath()) ("stop-task-vite-identity-{0}.pid" -f [guid]::NewGuid())
$global:stopTaskViteFakeProcess = [pscustomobject]@{
  Name = 'node.exe'
  ExecutablePath = $expectedNodePath
  CommandLine = "node_modules/vite/bin/vite.js --port 5173 cloud-single-node-run-id:$expectedRunId"
}
$global:stopTaskViteStoppedIds = [System.Collections.Generic.List[int]]::new()

function global:Get-CimInstance {
  param([string]$Class, [string]$Filter)
  return $global:stopTaskViteFakeProcess
}

function global:Stop-Process {
  param([int]$Id, [switch]$Force)
  $global:stopTaskViteStoppedIds.Add($Id)
}

function Assert-IdentityRejected {
  param([string]$CaseName)
  $rejected = $false
  try {
    & $helper -PidFile $pidFile -ExpectedNodePath $expectedNodePath `
      -RunId $expectedRunId -Port 5173 -Action verify | Out-Null
  } catch {
    if ($_.Exception.Message -notmatch 'does not match this task') {
      throw "Unexpected error in identity case '$CaseName': $($_.Exception.Message)"
    }
    $rejected = $true
  }
  if (-not $rejected) {
    throw "Identity case '$CaseName' was not rejected."
  }
}

try {
  Set-Content -LiteralPath $pidFile -Value '424242' -NoNewline
  $positive = & $helper -PidFile $pidFile -ExpectedNodePath $expectedNodePath `
    -RunId $expectedRunId -Port 5173 -Action verify
  if ($positive -notmatch 'Verified task-owned Vite PID 424242 on port 5173') {
    throw 'Matching task-owned Vite identity was not accepted.'
  }

  $global:stopTaskViteFakeProcess.CommandLine =
    'node_modules/vite/bin/vite.js --port 5173 cloud-single-node-run-id:other_20261007'
  Assert-IdentityRejected 'wrong RunId'

  $global:stopTaskViteFakeProcess.CommandLine =
    "node_modules/vite/bin/vite.js --port 5174 cloud-single-node-run-id:$expectedRunId"
  Assert-IdentityRejected 'wrong port'

  $global:stopTaskViteFakeProcess.CommandLine =
    "node.exe --port 5173 cloud-single-node-run-id:$expectedRunId"
  Assert-IdentityRejected 'missing Vite entry point'

  $global:stopTaskViteFakeProcess.CommandLine =
    "node_modules/vite/bin/vite.js --port 5173 cloud-single-node-run-id:$expectedRunId"
  $global:stopTaskViteFakeProcess.ExecutablePath = 'C:\other\node.exe'
  Assert-IdentityRejected 'wrong executable path'

  $global:stopTaskViteFakeProcess.ExecutablePath = $expectedNodePath
  $global:stopTaskViteFakeProcess.Name = 'other.exe'
  Assert-IdentityRejected 'wrong process name'

  $global:stopTaskViteFakeProcess.Name = 'node.exe'
  $global:stopTaskViteFakeProcess.CommandLine =
    'node_modules/vite/bin/vite.js --port 5173 cloud-single-node-run-id:other_20261007'
  $stopRejected = $false
  try {
    & $helper -PidFile $pidFile -ExpectedNodePath $expectedNodePath `
      -RunId $expectedRunId -Port 5173 -Action stop | Out-Null
  } catch {
    if ($_.Exception.Message -notmatch 'does not match this task') { throw }
    $stopRejected = $true
  }
  if (-not $stopRejected -or $global:stopTaskViteStoppedIds.Count -ne 0) {
    throw 'Mismatched process identity reached the process-stop command.'
  }

  Set-Content -LiteralPath $pidFile -Value '424242' -NoNewline
  $global:stopTaskViteFakeProcess.CommandLine =
    "node_modules/vite/bin/vite.js --port 5173 cloud-single-node-run-id:$expectedRunId"
  $stopped = & $helper -PidFile $pidFile -ExpectedNodePath $expectedNodePath `
    -RunId $expectedRunId -Port 5173 -Action stop
  if ($global:stopTaskViteStoppedIds.Count -ne 1 -or $global:stopTaskViteStoppedIds[0] -ne 424242) {
    throw 'Matching identity did not target exactly the registered PID.'
  }
  if (Test-Path -LiteralPath $pidFile) {
    throw 'PID registration file was not removed after the mocked stop succeeded.'
  }
  if ($stopped -notmatch 'Stopped task-owned Vite PID 424242 on port 5173') {
    throw 'Matching stop branch did not report its registered task PID.'
  }

  Write-Output 'Matching verify and stop branches: PASS; five invalid identities rejected; invalid stop calls=0; valid stop targets registered PID only (mocked).'
} finally {
  Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
  Remove-Item Function:\Get-CimInstance -ErrorAction SilentlyContinue
  Remove-Item Function:\Stop-Process -ErrorAction SilentlyContinue
  Remove-Item Function:\Assert-IdentityRejected -ErrorAction SilentlyContinue
  Remove-Variable stopTaskViteFakeProcess -Scope Global -ErrorAction SilentlyContinue
  Remove-Variable stopTaskViteStoppedIds -Scope Global -ErrorAction SilentlyContinue
}
