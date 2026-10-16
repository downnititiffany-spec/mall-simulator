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
$hadToken = $false
$logoutSucceeded = $false
$summary = $null

try {
  $loginBody = @{ username = 'admin'; password = $password } | ConvertTo-Json -Compress
  $login = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/login" `
    -ContentType 'application/json' -Body $loginBody -TimeoutSec 15
  if ([string]$login.code -ne 'OK') {
    throw 'Login response was not successful.'
  }
  $token = [string]$login.data.token
  if ([string]::IsNullOrWhiteSpace($token)) {
    throw 'Login response did not contain a bearer token.'
  }
  $hadToken = $true

  $headers = @{ Authorization = "Bearer $token" }
  $readPage = {
    param([long]$Page, [string]$SortField, [string]$Direction)
    $sort = "$SortField,$Direction"
    $uri = "$base/api/v1/decisions/page?page=$Page&size=$PageSize&sort=$sort"
    $rawResponse = Invoke-WebRequest -Method Get -Uri $uri -Headers $headers -TimeoutSec 20
    $response = ConvertFrom-Json -InputObject $rawResponse.Content
    if ([string]$response.code -ne 'OK') {
      throw 'Decision page response was not successful.'
    }
    if ($SortField -eq 'createdAt') {
      $items = @($response.data.items)
      $dateMatches = [regex]::Matches($rawResponse.Content,
        '"createdAt"\s*:\s*"(?<value>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?)"')
      if ($dateMatches.Count -ne $items.Count) {
        throw 'The createdAt values could not be read at full precision.'
      }
      for ($i = 0; $i -lt $items.Count; $i++) {
        $items[$i].createdAt = $dateMatches[$i].Groups['value'].Value
      }
    }
    return $response
  }
  $parseCreatedAt = {
    param([string]$Value)
    $match = [regex]::Match($Value,
      '^(?<whole>\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(?<fraction>\d{1,9}))?$')
    if (-not $match.Success) {
      throw 'The decision page has an invalid createdAt sort value.'
    }
    $fraction = $match.Groups['fraction'].Value.PadRight(9, '0')
    return "$($match.Groups['whole'].Value).$fraction"
  }
  $validatePage = {
    param([object]$Data, [long]$RequestedPage, [string]$SortField, [string]$Direction)
    if ($null -eq $Data -or $null -eq $Data.items) {
      throw 'The decision page response is missing data.items.'
    }
    $items = @($Data.items)
    $total = [long]$Data.total
    $totalPages = [long][Math]::Max(1, [Math]::Ceiling([decimal]$total / [decimal]$PageSize))
    if ([long]$Data.page -ne $RequestedPage -or [int]$Data.size -ne $PageSize) {
      throw 'The decision page metadata does not match the requested page and size.'
    }
    if ([long]$Data.totalPages -ne $totalPages) {
      throw 'The decision totalPages metadata does not match total and size.'
    }
    $normalizedSort = "$($SortField.ToLowerInvariant()),$Direction"
    if ([string]$Data.sort -ne $normalizedSort) {
      throw 'The decision sort metadata does not match the requested sort.'
    }
    $offset = ($RequestedPage - 1) * [long]$PageSize
    $expectedItems = [int][Math]::Min([long]$PageSize, [Math]::Max([long]0, $total - $offset))
    if ($items.Count -ne $expectedItems) {
      throw 'The decision item count does not match total, size, and page.'
    }
    $ids = @($items | ForEach-Object { [long]$_.id })
    $createdAt = @()
    if ($SortField -eq 'createdAt') {
      foreach ($item in $items) {
        if ([string]::IsNullOrWhiteSpace([string]$item.createdAt)) {
          throw 'The decision page is missing a createdAt sort value.'
        }
        $createdAt += & $parseCreatedAt ([string]$item.createdAt)
      }
    }
    for ($i = 1; $i -lt $ids.Count; $i++) {
      if ($SortField -eq 'id') {
        if ($Direction -eq 'desc' -and $ids[$i - 1] -lt $ids[$i]) {
          throw 'The decision page is not sorted by id descending.'
        }
        if ($Direction -eq 'asc' -and $ids[$i - 1] -gt $ids[$i]) {
          throw 'The decision page is not sorted by id ascending.'
        }
      } elseif ($SortField -eq 'createdAt' -and $Direction -eq 'desc') {
        $timeOrder = [string]::CompareOrdinal($createdAt[$i - 1], $createdAt[$i])
        if ($timeOrder -lt 0 -or ($timeOrder -eq 0 -and $ids[$i - 1] -lt $ids[$i])) {
          throw 'The decision page is not sorted by createdAt descending with stable ID order.'
        }
      } else {
        throw 'The requested sort is not covered by this verifier.'
      }
    }
    return ,@{ total = $total; totalPages = $totalPages; items = $items; ids = $ids; createdAt = $createdAt }
  }

  $descending = & $readPage 1 'id' 'desc'
  $descData = & $validatePage $descending.data 1 'id' 'desc'
  if ($descData.total -le 0 -or $descData.items.Count -eq 0) {
    throw 'The authorized decision endpoint returned no records; non-empty pagination is not verified.'
  }

  $ascending = & $readPage 1 'id' 'asc'
  $ascData = & $validatePage $ascending.data 1 'id' 'asc'
  if ($ascData.total -ne $descData.total -or $ascData.totalPages -ne $descData.totalPages) {
    throw 'Decision totals changed between the ascending and descending reads.'
  }

  $pageBoundaryVerified = $false
  if ($descData.totalPages -gt 1) {
    $second = & $readPage 2 'id' 'desc'
    $secondData = & $validatePage $second.data 2 'id' 'desc'
    if ($secondData.total -ne $descData.total -or $secondData.totalPages -ne $descData.totalPages) {
      throw 'Decision totals changed between the first and second pages.'
    }
    $firstIds = @{}; foreach ($id in $descData.ids) { $firstIds[[long]$id] = $true }
    foreach ($id in $secondData.ids) {
      if ($firstIds.ContainsKey([long]$id)) {
        throw 'The first and second decision pages contain a duplicate ID.'
      }
    }
    if ($secondData.ids.Count -gt 0 -and [long]$secondData.ids[0] -ge [long]$descData.ids[-1]) {
      throw 'The descending decision page boundary is not strictly ordered.'
    }
    $pageBoundaryVerified = $true
  }

  $createdAtFirst = & $readPage 1 'createdAt' 'desc'
  $createdAtFirstData = & $validatePage $createdAtFirst.data 1 'createdAt' 'desc'
  if ($createdAtFirstData.total -ne $descData.total -or
      $createdAtFirstData.totalPages -ne $descData.totalPages) {
    throw 'Decision totals changed before the createdAt sort check.'
  }
  $createdAtBoundaryVerified = $false
  if ($createdAtFirstData.totalPages -gt 1) {
    $createdAtSecond = & $readPage 2 'createdAt' 'desc'
    $createdAtSecondData = & $validatePage $createdAtSecond.data 2 'createdAt' 'desc'
    if ($createdAtSecondData.total -ne $createdAtFirstData.total -or
        $createdAtSecondData.totalPages -ne $createdAtFirstData.totalPages) {
      throw 'Decision totals changed between createdAt sorted pages.'
    }
    $lastFirst = $createdAtFirstData.items[-1]
    $firstSecond = $createdAtSecondData.items[0]
    $lastFirstAt = & $parseCreatedAt ([string]$lastFirst.createdAt)
    $firstSecondAt = & $parseCreatedAt ([string]$firstSecond.createdAt)
    $boundaryTimeOrder = [string]::CompareOrdinal($firstSecondAt, $lastFirstAt)
    if ($boundaryTimeOrder -gt 0 -or
        ($boundaryTimeOrder -eq 0 -and [long]$firstSecond.id -ge [long]$lastFirst.id)) {
      throw 'The createdAt descending sort boundary is not strictly ordered.'
    }
    $createdAtBoundaryVerified = $true
  }

  $summary = [pscustomobject][ordered]@{
    result = if ($pageBoundaryVerified -and $createdAtBoundaryVerified) { 'PASS' } else { 'PASS_WITH_LIMITATION' }
    endpoint = '/api/v1/decisions/page'
    total = $descData.total
    totalPages = $descData.totalPages
    pageSize = $PageSize
    descendingItems = $descData.items.Count
    ascendingItems = $ascData.items.Count
    pageBoundaryVerified = $pageBoundaryVerified
    createdAtSortVerified = $true
    createdAtPageBoundaryVerified = $createdAtBoundaryVerified
    limitation = if ($pageBoundaryVerified -and $createdAtBoundaryVerified) { $null } else { 'At least one cross-page boundary was unavailable because only one page exists for that sort.' }
  }
} catch {
  throw "Decision pagination verification failed: $($_.Exception.GetType().Name). Response bodies and credentials are not emitted."
} finally {
  if ($token) {
    try {
      $logout = Invoke-RestMethod -Method Post -Uri "$base/api/v1/auth/logout" `
        -Headers @{ Authorization = "Bearer $token" } -TimeoutSec 10
      if ([string]$logout.code -ne 'OK') {
        throw 'Logout response was not successful.'
      }
      $logoutSucceeded = $true
    } catch {
      Write-Warning 'The temporary verification session could not be closed; no token was printed.'
    }
  }
  $password = $null
  $token = $null
  $loginBody = $null
  $login = $null
  $logout = $null
  $headers = $null
  $readPage = $null
  $parseCreatedAt = $null
  $validatePage = $null
  $descending = $null
  $ascending = $null
  $second = $null
  $createdAtFirst = $null
  $createdAtSecond = $null
  $createdAtFirstData = $null
  $createdAtSecondData = $null
  $rawResponse = $null
  $response = $null
  $descData = $null
  $ascData = $null
  $secondData = $null
}

if ($hadToken -and -not $logoutSucceeded) {
  throw 'Decision pagination verification failed: session logout was not confirmed.'
}

$summary | ConvertTo-Json -Compress
