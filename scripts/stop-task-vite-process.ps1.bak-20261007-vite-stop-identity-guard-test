param(
  [Parameter(Mandatory = $true)][string]$PidFile,
  [Parameter(Mandatory = $true)][string]$ExpectedNodePath,
  [Parameter(Mandatory = $true)][string]$RunId,
  [Parameter(Mandatory = $true)][ValidateSet(5173, 5174)][int]$Port,
  [ValidateSet('verify', 'stop')][string]$Action = 'stop'
)

$ErrorActionPreference = 'Stop'
if ($RunId -notmatch '^[a-zA-Z0-9][a-zA-Z0-9_-]{5,40}$') {
  throw 'Invalid task RunId.'
}
if (-not (Test-Path -LiteralPath $PidFile -PathType Leaf)) {
  if ($Action -eq 'verify') {
    throw 'Windows Vite PID file is absent; refusing to claim process identity.'
  }
  Write-Output 'Windows Vite PID file is absent; process is already stopped.'
  exit 0
}
$rawPid = (Get-Content -LiteralPath $PidFile -Raw).Trim()
$processId = 0
if (-not [int]::TryParse($rawPid, [ref]$processId) -or $processId -le 0) {
  throw 'Windows Vite PID file is invalid; refusing to stop a process.'
}
$process = Get-CimInstance Win32_Process -Filter "ProcessId = $processId"
if ($null -eq $process) {
  if ($Action -eq 'verify') {
    throw 'Windows Vite process is not running; refusing to claim process identity.'
  }
  Remove-Item -LiteralPath $PidFile -Force
  Write-Output 'Windows Vite process is already stopped.'
  exit 0
}
$expectedPath = [IO.Path]::GetFullPath($ExpectedNodePath)
$actualPath = [IO.Path]::GetFullPath([string]$process.ExecutablePath)
$marker = "cloud-single-node-run-id:$RunId"
$commandLine = [string]$process.CommandLine
$portArgumentPresent = $commandLine -match "(?:--port\s+$Port(?:\s|$)|(?:^|\s)$Port(?:\s|$))"
if ($process.Name -ine 'node.exe' -or $actualPath -ine $expectedPath -or
    $commandLine.IndexOf($marker, [StringComparison]::Ordinal) -lt 0 -or
    $commandLine.IndexOf('node_modules/vite/bin/vite.js', [StringComparison]::OrdinalIgnoreCase) -lt 0 -or
    -not $portArgumentPresent) {
  throw "PID $processId does not match this task's Node/Vite identity; refusing to stop it."
}
if ($Action -eq 'verify') {
  Write-Output "Verified task-owned Vite PID $processId on port $Port."
  exit 0
}
Stop-Process -Id $processId -Force
Remove-Item -LiteralPath $PidFile -Force
Write-Output "Stopped task-owned Vite PID $processId on port $Port."
