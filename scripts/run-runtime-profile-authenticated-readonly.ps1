[CmdletBinding()]
param(
    [ValidateSet('runtime-profile', 'decision-pagination', 'ingestion-batch-pagination')]
    [string]$Verifier = 'runtime-profile',
    [string]$BaseUrl,
    [ValidateRange(1, 50)]
    [int]$PageSize = 10
)

$ErrorActionPreference = 'Stop'
$secretName = 'GRADUATION_TEST_ADMIN_PASSWORD'

try {
    if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
        $BaseUrl = switch ($Verifier) {
            'runtime-profile' { 'http://127.0.0.1:5173' }
            default { 'http://127.0.0.1:8091' }
        }
    }
    $parsedBaseUrl = [Uri]$BaseUrl
    if ($parsedBaseUrl.Scheme -notin @('http', 'https') -or
        -not $parsedBaseUrl.IsLoopback -or
        $parsedBaseUrl.UserInfo -or $parsedBaseUrl.Query -or $parsedBaseUrl.Fragment) {
        throw 'BaseUrl must be loopback HTTP(S), without credentials, query, or fragment.'
    }
} catch {
    [Console]::Error.WriteLine($_.Exception.Message)
    $global:LASTEXITCODE = 2
    return
}

$oldPassword = [Environment]::GetEnvironmentVariable($secretName, 'Process')
$securePassword = $null
$passwordPointer = [IntPtr]::Zero
$passwordText = $null
$resultCode = 0

try {
    if ([string]::IsNullOrEmpty($oldPassword)) {
        $securePassword = Read-Host '隔离环境管理员密码（输入不回显）' -AsSecureString
        if ($securePassword.Length -eq 0) {
            [Console]::Error.WriteLine('No password entered; verifier was not started.')
            $resultCode = 3
        } else {
            $passwordPointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
            $passwordText = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPointer)
            [Environment]::SetEnvironmentVariable($secretName, $passwordText, 'Process')
        }
    }

    if ($resultCode -eq 0) {
        try {
            switch ($Verifier) {
                'runtime-profile' {
                    $python = Get-Command python -ErrorAction SilentlyContinue
                    if (-not $python) {
                        [Console]::Error.WriteLine('Python was not found; verifier was not started.')
                        $resultCode = 4
                    } else {
                        $verifierPath = Join-Path $PSScriptRoot 'verify-runtime-profile-authenticated-readonly.py'
                        & $python.Name $verifierPath --base-url $BaseUrl
                        $resultCode = $LASTEXITCODE
                    }
                }
                'decision-pagination' {
                    $verifierPath = Join-Path $PSScriptRoot 'verify-decision-pagination.ps1'
                    & $verifierPath -BaseUrl $BaseUrl -PageSize $PageSize
                    if (-not $?) { $resultCode = 1 }
                }
                'ingestion-batch-pagination' {
                    $verifierPath = Join-Path $PSScriptRoot 'verify-ingestion-batch-pagination.ps1'
                    & $verifierPath -BaseUrl $BaseUrl -PageSize $PageSize
                    if (-not $?) { $resultCode = 1 }
                }
            }
        } catch {
            [Console]::Error.WriteLine("Verifier failed ($($_.Exception.GetType().Name)); no response body or credential was printed.")
            $resultCode = 1
        }
    }
} finally {
    [Environment]::SetEnvironmentVariable($secretName, $oldPassword, 'Process')
    $passwordText = $null
    if ($passwordPointer -ne [IntPtr]::Zero) {
        [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPointer)
    }
    if ($securePassword) {
        $securePassword.Dispose()
    }
}

$global:LASTEXITCODE = $resultCode
return
