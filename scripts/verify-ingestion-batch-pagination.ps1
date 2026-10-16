[CmdletBinding()]
param(
  [string]$BaseUrl = 'http://127.0.0.1:8091',
  [ValidateRange(1, 50)]
  [int]$PageSize = 10
)

$ErrorActionPreference = 'Stop'
$password = [Environment]::GetEnvironmentVariable('GRADUATION_TEST_ADMIN_PASSWORD', 'Process')
if ([string]::IsNullOrWhiteSpace($password)) {
  throw 'GRADUATION_TEST_ADMIN_PASSWORD is not available in this process; no request was sent.'
}

try {
  $baseUri = [Uri]$BaseUrl
} catch {
  throw 'BaseUrl must be a valid loopback HTTP(S) URL.'
}
if (-not $baseUri.IsLoopback -or $baseUri.Scheme -notin @('http', 'https') -or
    $baseUri.UserInfo -or $baseUri.Query -or $baseUri.Fragment) {
  throw 'For credential safety, BaseUrl must be a loopback HTTP(S) URL without user info, query, or fragment.'
}
$base = $baseUri.AbsoluteUri.TrimEnd('/')
$token = $null
$summary = $null

try {
  $loginBody = @{ username = 'admin'; password = $password } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" `
    -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
  $token = [string]$login.data.token
  if ([string]::IsNullOrWhiteSpace($token)) {
    throw 'Login response did not contain a bearer token.'
  }

  $headers = @{ Authorization = "Bearer $token" }
  $readPage = {
    param([long]$Page, [string]$Direction)
    $uri = "$base/api/v1/ingestion/batches/page?page=$Page&size=$PageSize&sort=id,$Direction"
    Invoke-RestMethod -Method Get -Uri $uri -Headers $headers -TimeoutSec 20
  }
  $assertSorted = {
    param([object[]]$Items, [string]$Direction)
    $ids = @($Items | ForEach-Object { [long]$_.id })
    for ($i = 1; $i -lt $ids.Count; $i++) {
      if ($Direction -eq 'desc' -and $ids[$i - 1] -lt $ids[$i]) {
        throw 'The page is not sorted by id descending.'
      }
      if ($Direction -eq 'asc' -and $ids[$i - 1] -gt $ids[$i]) {
        throw 'The page is not sorted by id ascending.'
      }
    }
    return ,$ids
  }
  $assertPageShape = {
    param([object]$Data, [object[]]$Items, [long]$RequestedPage, [string]$Direction)
    $total = [long]$Data.total
    $expectedPages = [long][Math]::Ceiling([decimal]$total / [decimal]$PageSize)
    if ([long]$Data.totalPages -ne $expectedPages) {
      throw 'The page totalPages metadata does not match total and size.'
    }
    if ([string]$Data.sort -ne "id,$Direction") {
      throw 'The response sort metadata does not match the requested sort.'
    }
    $offset = ($RequestedPage - 1) * [long]$PageSize
    $expectedItems = [int][Math]::Min([long]$PageSize, [Math]::Max([long]0, $total - $offset))
    if ($Items.Count -ne $expectedItems) {
      throw 'The page item count does not match its total, size, and page.'
    }
  }

  $descending = & $readPage 1 'desc'
  $descData = $descending.data
  $descItems = @($descData.items)
  if ([long]$descData.total -le 0 -or $descItems.Count -eq 0) {
    throw 'The authorized endpoint returned no ingestion batches; non-empty pagination is not verified.'
  }
  if ($descItems.Count -gt $PageSize) {
    throw 'The endpoint returned more items than the requested page size.'
  }
  if ([long]$descData.page -ne 1 -or [int]$descData.size -ne $PageSize) {
    throw 'The first-page response metadata does not match the request.'
  }
  & $assertPageShape $descData $descItems 1 'desc'
  $descIds = & $assertSorted $descItems 'desc'

  $ascending = & $readPage 1 'asc'
  $ascData = $ascending.data
  $ascItems = @($ascData.items)
  if ($ascItems.Count -eq 0 -or [long]$ascData.total -le 0) {
    throw 'The authorized ascending page returned no ingestion batches.'
  }
  if ([long]$ascData.page -ne 1 -or [int]$ascData.size -ne $PageSize) {
    throw 'The ascending first-page response metadata does not match the request.'
  }
  & $assertPageShape $ascData $ascItems 1 'asc'
  $ascIds = & $assertSorted $ascItems 'asc'

  $pageBoundaryVerified = $false
  if ([long]$descData.total -gt $PageSize) {
    $second = & $readPage 2 'desc'
    $secondData = $second.data
    $secondItems = @($secondData.items)
    if ([long]$secondData.page -ne 2 -or $secondItems.Count -eq 0) {
      throw 'The second page did not return the requested page with data.'
    }
    if ([int]$secondData.size -ne $PageSize) {
      throw 'The second-page response size does not match the request.'
    }
    & $assertPageShape $secondData $secondItems 2 'desc'
    $secondIds = & $assertSorted $secondItems 'desc'
    $firstPageIds = @{}; foreach ($id in $descIds) { $firstPageIds[[long]$id] = $true }
    foreach ($id in $secondIds) {
      if ($firstPageIds.ContainsKey([long]$id)) {
        throw 'The first and second pages contain a duplicate batch ID.'
      }
    }
    if ([long]$secondIds[0] -ge [long]$descIds[-1]) {
      throw 'The descending page boundary is not strictly ordered.'
    }
    $pageBoundaryVerified = $true
  }

  $summary = [pscustomobject][ordered]@{
    result = if ($pageBoundaryVerified) { 'PASS' } else { 'PASS_WITH_LIMITATION' }
    endpoint = '/api/v1/ingestion/batches/page'
    totalDescending = [long]$descData.total
    totalAscending = [long]$ascData.total
    descendingItems = $descItems.Count
    ascendingItems = $ascItems.Count
    pageBoundaryVerified = $pageBoundaryVerified
    limitation = if ($pageBoundaryVerified) { $null } else { 'Fewer than two pages are available; cross-page boundary was not verified.' }
  }
} catch {
  throw "Pagination verification failed: $($_.Exception.GetType().Name). Response bodies and credentials are not emitted."
} finally {
  if ($token) {
    try {
      Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/logout" `
        -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10 | Out-Null
    } catch {
      Write-Warning 'The temporary verification session could not be closed; no token was printed.'
    }
  }
  $password = $null
  $token = $null
  $loginBody = $null
}

$summary | ConvertTo-Json -Compress
