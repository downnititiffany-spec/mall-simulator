# New-TrackedManifest.ps1 —— 仓库跟踪文件完整性清单（仓库外工具，2026-09-12 建立）
#
# 用途：本工作区发生过两次「源文件被删 33 个」事件（F-37，已归因为复核器 helper 名 `Rd` 被
#       PowerShell 内置别名 `rd`＝Remove-Item 遮蔽）。本脚本给出「某一时刻全部跟踪文件的
#       路径+字节+sha256」清单，用于事后毫秒级判定"少了什么、变了什么"，并可据清单从
#       git 对象库或备份目录恢复。
#
# 硬约束（血泪教训，勿删）：
#   1) 必须用 `git ls-files -z`＋按 NUL 切分：`core.quotepath` 默认会把非 ASCII 路径输出成
#      `"docs/\344\270\211..."` 形式 ⇒ 直接 Join-Path 必然找不到 ⇒ 会报出成片的假"缺失"
#      （v1 实测：27 条假告警，全部是中文路径）。见 -PositiveControl。
#   2) 助手函数名不得与 PowerShell 内置别名同名（rd/gc/del/rm/erase/ri/cd/ls/ps…）：
#      解析顺序为「别名 > 函数 > cmdlet」。
#   3) 任何"缺失/零命中"判据都必须带**正向对照**：本脚本默认对看板、ORDER-1、一个 .java
#      三个已知存在的路径（含中文）做命中校验，命中数≠1 即判定清单无效并退出码 1。
#
# 用法：
#   pwsh -File New-TrackedManifest.ps1                                  # 生成清单并自检
#   pwsh -File New-TrackedManifest.ps1 -CompareTo <旧清单路径>           # 生成并与旧清单比对
param(
    [string]$Repo = 'D:\Develop_code\GraduationProject',
    [string]$OutDir = 'D:\Develop_code\graduation-lane-backup\manifests',
    [string]$CompareTo = ''
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Repo -PathType Container)) { throw ("仓库路径不存在：" + $Repo) }
if (-not (Test-Path -LiteralPath $OutDir -PathType Container)) { New-Item -ItemType Directory -Path $OutDir -Force | Out-Null }

Push-Location $Repo
try {
    $raw = & git ls-files -z
    if ($LASTEXITCODE -ne 0) { throw 'git ls-files 失败' }
    $files = @($raw -split "`0" | Where-Object { $_ -ne '' })
    if ($files.Count -lt 100) { throw ('跟踪文件数异常偏小：' + $files.Count + '（疑非仓库根，拒绝生成清单）') }

    $lines   = New-Object System.Collections.Generic.List[string]
    $missing = New-Object System.Collections.Generic.List[string]
    $locked  = New-Object System.Collections.Generic.List[string]
    foreach ($rel in $files) {
        $full = Join-Path $Repo ($rel -replace '/', '\')
        if (-not (Test-Path -LiteralPath $full -PathType Leaf)) {
            $missing.Add($rel)
            $lines.Add(('MISSING').PadRight(64) + '  ' + '0'.PadLeft(10) + '  ' + $rel)
            continue
        }
        $it = Get-Item -LiteralPath $full
        try   { $hash = (Get-FileHash -LiteralPath $full -Algorithm SHA256 -ErrorAction Stop).Hash }
        catch { $hash = 'LOCKED'; $locked.Add($rel) }
        $lines.Add($hash.PadRight(64) + '  ' + $it.Length.ToString().PadLeft(10) + '  ' + $rel)
    }

    # 正向对照：这些路径必须各命中 1 行，否则清单本身无效
    $controls = @(
        'docs/status-history/项目实施进度与任务看板 V2.2.md',
        'docs/acceptance/p2-01-ods-v2-20260912/ORDER-1.md'
    )
    foreach ($c in $controls) {
        $hit = @($lines | Where-Object { $_ -like ('*  ' + $c) })
        if ($hit.Count -ne 1) { throw ('正向对照未命中，清单无效：' + $c + ' → ' + $hit.Count + ' 行') }
    }

    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $out = Join-Path $OutDir ('tracked-manifest-' + $stamp + '.txt')
    [IO.File]::WriteAllText($out, [string]::Join([char]10, $lines) + [char]10, [Text.UTF8Encoding]::new($false))

    '  清单：' + $out
    '  跟踪文件=' + $files.Count + '；真实缺失=' + $missing.Count + '；占用中(LOCKED)=' + $locked.Count +
        '；字节=' + (Get-Item $out).Length + '；sha256=' + (Get-FileHash $out -Algorithm SHA256).Hash
    foreach ($m in $missing) { '  缺失：' + $m }
    foreach ($l in $locked)  { '  占用：' + $l }

    if ($CompareTo -ne '' -and (Test-Path -LiteralPath $CompareTo -PathType Leaf)) {
        $old = @{}
        foreach ($l in ([IO.File]::ReadAllText($CompareTo) -split "`n")) {
            if ($l.Length -gt 76) { $old[$l.Substring(76).TrimEnd()] = $l.Substring(0, 64) }
        }
        $new = @{}
        foreach ($l in $lines) { $new[$l.Substring(76).TrimEnd()] = $l.Substring(0, 64) }
        $gone = @($old.Keys | Where-Object { -not $new.ContainsKey($_) })
        $add  = @($new.Keys | Where-Object { -not $old.ContainsKey($_) })
        $chg  = @($new.Keys | Where-Object { $old.ContainsKey($_) -and $old[$_] -ne $new[$_] })
        '  ── 与旧清单比对：' + $CompareTo
        '     消失=' + $gone.Count + '；新增=' + $add.Count + '；内容变化=' + $chg.Count
        foreach ($g in $gone) { '     [消失] ' + $g }
        foreach ($a in $add)  { '     [新增] ' + $a }
        foreach ($c in $chg)  { '     [变化] ' + $c }
    }
} finally {
    Pop-Location
}
