# 平台启动前的环境变量门禁（G31-01 / 指导书 5.1.2 + 5.1.3 的 F1 抽取版）
#
# 解决什么：BATCH-W 之前，启动守卫只存在于 stage7w driver 脚本内部（无法复用、
# 无法对负例单独演练）。本脚本把「必填变量核对 + URL 作用域核对 + 库名前缀核对 +
# 禁止 3306」抽成**独立可调用**的一步，供：
#   * 平台启动 driver 在 detached 启动**之前**调用（先于任何 JVM/连接池/Flyway）；
#   * 负例演练以子进程方式调用（缺变量 / 写成 3306 / 错库名前缀 都必须在这里被拒，
#     exit 12，**绝不会走到启动平台那一步，更不会真的连上 3306**）。
#
# 5.1.2 原则：任一必填变量缺失即拒绝启动，**不回落 application.yml 中的 3306 默认值**
#（application.yml 已在 G31-01 去掉这些兜底默认——那是启动期的最后一道内层防线，
# 本脚本是外层防线，更早触发、且不启动 JVM）。
# 5.1.3 原则：F2b（启动后日志核查，driver 内保留）只是第二道检查，不能替代本门禁。
#
# 口令纪律：本脚本**只读变量名并核对形状**，任何变量的值都不回显、不写日志、
# 不进参数（零 argv）。URL 值不含口令，失败时可以打印以辅助定位。
#
# 用法：
#   # 正例（driver 内）：要求 13 个 PLATFORM_* 变量齐备，URL 指向 3307 且库前缀正确
#   pwsh -NoProfile -File scripts/assert-platform-env.ps1 -RequirePlatform `
#        -AllowedSchemaPrefixes @('stage7q1_20260918_152245_')
#   # 负例（缺变量）：子进程删除一个变量后调用 ⇒ 必须以 12 退出
#   pwsh -NoProfile -File scripts/assert-platform-env.ps1 -RequirePlatform
#
# 退出码：0 = 全部通过；12 = 门禁拒绝（缺失/形状不符/禁连标记）；1 = 参数/自身错误
param(
  # 校验平台 13 个必填变量（PLATFORM_META_* / PLATFORM_METRIC_PUBLISH_* /
  # PLATFORM_METRIC_READ_* / LANDING_LOCAL_ROOT / SOURCE_PROFILE_ROOT /
  # SPARK_WAREHOUSE_DIR / SPARK_METASTORE_DIR）。变量清单是**当前**的"恰好 13"，
  # 不是永久充分：新增数据源（指导书 5.1.1）必须加入清单，用 -ExtraRequired 扩展。
  [switch]$RequirePlatform,
  # 额外必填变量（未来新数据源接入时追加，不改本脚本本体）
  [string[]]$ExtraRequired = @(),
  # URL 类变量必须以其开头（host:port 白名单）；3307 隔离实例
  [string]$UrlPrefix = 'jdbc:mysql://127.0.0.1:3307/',
  # URL 中 host:port 之后的 schema（库名）必须以其中之一开头；
  # 为空则跳过库名前缀核对（负例 N3 用它验证「错前缀启动前失败」）
  [string[]]$AllowedSchemaPrefixes = @(),
  # 任何变量值中出现该标记即拒绝（宿主正式实例端口，永久冻结，一次误连都不可接受）
  [string]$ForbiddenMarker = ':3306',
  # 拒绝时的退出码（driver 契约沿用 stage7w 的 12 = F1 门禁失败）
  [int]$FailExit = 12
)

$ErrorActionPreference = 'Stop'

$urlKeys = @('PLATFORM_META_URL', 'PLATFORM_METRIC_PUBLISH_URL', 'PLATFORM_METRIC_READ_URL')
$platformKeys = @(
  'PLATFORM_META_URL', 'PLATFORM_META_USER', 'PLATFORM_META_PASSWORD',
  'PLATFORM_METRIC_PUBLISH_URL', 'PLATFORM_METRIC_PUBLISH_USER', 'PLATFORM_METRIC_PUBLISH_PASSWORD',
  'PLATFORM_METRIC_READ_URL', 'PLATFORM_METRIC_READ_USER', 'PLATFORM_METRIC_READ_PASSWORD',
  'PLATFORM_LANDING_LOCAL_ROOT', 'PLATFORM_SOURCE_PROFILE_ROOT',
  'PLATFORM_SPARK_WAREHOUSE_DIR', 'PLATFORM_SPARK_METASTORE_DIR'
)

if (-not $RequirePlatform -and $ExtraRequired.Count -eq 0) {
  Write-Host '用法错误：需要 -RequirePlatform（13 个平台变量）或 -ExtraRequired（自定义清单）。'
  exit 1
}

$required = @()
if ($RequirePlatform) { $required += $platformKeys }
$required += $ExtraRequired

$errors = @()
$values = @{}
foreach ($key in $required) {
  $v = [Environment]::GetEnvironmentVariable($key, 'Process')
  if ([string]::IsNullOrWhiteSpace($v)) {
    $errors += ("缺失必填变量 {0}（不允许回落任何配置文件默认值，5.1.2）" -f $key)
    continue
  }
  $values[$key] = $v
  if ($v.Contains($ForbiddenMarker)) {
    # URL 值不含口令，可打印；其余变量只报变量名，不回显值（口令纪律）
    if ($urlKeys -contains $key) {
      $errors += ("{0} 指向禁连目标（含 '{1}'）：{2}" -f $key, $ForbiddenMarker, $v)
    } else {
      $errors += ("{0} 的值含禁连标记 '{1}'（拒绝；值不回显）" -f $key, $ForbiddenMarker)
    }
  }
}

foreach ($key in $urlKeys) {
  if (-not $values.ContainsKey($key)) { continue }
  $v = $values[$key]
  if (-not $v.StartsWith($UrlPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    $errors += ("{0} 不在允许的实例作用域内（要求以 '{1}' 开头）：{2}" -f $key, $UrlPrefix, $v)
    continue
  }
  if ($AllowedSchemaPrefixes.Count -gt 0) {
    $schema = $v.Substring($UrlPrefix.Length)
    $q = $schema.IndexOf('?')
    if ($q -ge 0) { $schema = $schema.Substring(0, $q) }
    $ok = $false
    foreach ($p in $AllowedSchemaPrefixes) {
      if ($schema.StartsWith($p, [System.StringComparison]::Ordinal)) { $ok = $true; break }
    }
    if (-not $ok) {
      $errors += ("{0} 的库名 '{1}' 不在允许前缀内（要求 {2}）：错库即写穿隔离边界" -f $key, $schema, ($AllowedSchemaPrefixes -join ' | '))
    }
  }
}

if ($errors.Count -gt 0) {
  Write-Host ("assert-platform-env：门禁拒绝（{0} 项）：" -f $errors.Count)
  foreach ($e in $errors) { Write-Host ("  - {0}" -f $e) }
  Write-Host '拒绝启动：请以本次 runId 的隔离账号/隔离库显式提供全部必填环境变量后重试。'
  exit $FailExit
}

Write-Host ("assert-platform-env：OK（{0}/{0} 个必填变量；URL 作用域 {1}；库名前缀 {2}；无 '{3}' 标记）" -f `
    $required.Count, $UrlPrefix, $(if ($AllowedSchemaPrefixes.Count -gt 0) { ($AllowedSchemaPrefixes -join '|') } else { '（未启用核对）' }), $ForbiddenMarker)
exit 0
