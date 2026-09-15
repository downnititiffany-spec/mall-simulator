# M1-5 收口同步：状态复核 + 证据再生成（替换动作是一次性的，本脚本只做"读当前状态并复验"，可重复运行）。
# 修复点：调用 python 前把控制台输出编码固定为 UTF-8——第一版日志里嵌入的 python 中文输出被按 OEM 码页解码成乱码（原始日志保留不动，另存本文件为可用证据）。
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$OutputEncoding = [Text.UTF8Encoding]::new($false)
$enc = [Text.UTF8Encoding]::new($false)
$log = [Collections.Generic.List[string]]::new()
function Say($s) { $log.Add([string]$s); Write-Host $s }

$yaml = 'contract-specs\openapi\generator-api.v1.yaml'
$man = 'contract-specs\schemas\generation-artifact-manifest.v1.schema.json'
$readme = 'contract-specs\README.md'
$ver = 'contract-specs\VERSION'

Say ('== M1-5 收口同步：状态复核 ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + '（可重复运行） ==')
Say ''
Say '-- 1. VERSION 内容 --'
Say ('   ' + ([IO.File]::ReadAllText((Join-Path $root $ver), $enc)).Trim())
Say ''
Say '-- 2. 11 条锚点对照（正向：正确行确实含声称内容；负向：旧锚点行确实是相邻内容） --'
$v1 = [IO.File]::ReadAllText('docs\guidance\history\项目完整实施指导书 V2.1.md', $enc) -split "`n"
$v3 = [IO.File]::ReadAllText('docs\guidance\history\项目完整实施指导书 V2.3.md', $enc) -split "`n"
$probe = @(
  @{ name = 'V2.1 §4.1 L107 声称 test(TargetConfig) → 实为 interface 行'; a = 'v1'; l = 107; w = 'interface MallTargetAdapter {'; a2 = 108; w2 = 'TargetCheckResult test(TargetConfig config);' },
  @{ name = 'V2.1 §4.1 L108 声称 capabilities() → 实为 test 行'; a = 'v1'; l = 108; w = 'TargetCheckResult test(TargetConfig config);'; a2 = 109; w2 = 'TargetCapabilities capabilities();' },
  @{ name = 'V2.1 §4.1 L126 声称第一版实现 → 实为空行'; a = 'v1'; l = 126; w = ''; a2 = 127; w2 = '第一版实现 `ReferenceMallHttpAdapter`' },
  @{ name = 'V2.1 §4.2 L132 声称 generator_target → 实为分隔行'; a = 'v1'; l = 132; w = '|---|---|---|'; a2 = 133; w2 = '| `generator_target` |' },
  @{ name = 'V2.1 §4.2 L135 声称 generation_artifact → 实为 generation_run'; a = 'v1'; l = 135; w = '| `generation_run` |'; a2 = 136; w2 = '| `generation_artifact` |' },
  @{ name = 'V2.1 §4.3 L149 声称异常样本 → 实为「可复现」'; a = 'v1'; l = 149; w = '- 可复现：'; a2 = 150; w2 = '- 异常样本：' },
  @{ name = 'V2.1 §4.4 L153 声称返回 runId → 实为空行'; a = 'v1'; l = 153; w = ''; a2 = 154; w2 = '返回 runId' },
  @{ name = 'V2.3 §4.2 L281 = generation_artifact 行'; a = 'v3'; l = 281; w = '| `generation_artifact` |' },
  @{ name = 'V2.3 §4.4 L304 = 连通性检查行'; a = 'v3'; l = 304; w = '连通性检查' },
  @{ name = 'V2.3 §4.1 L131 = §4.1 骨架 EventSink（不带 extends）'; a = 'v3'; l = 131; w = 'interface EventSink {'; a2 = 135; w2 = 'Manifest closeAndBuildManifest();' },
  @{ name = 'V2.3 §4.1.1.1 L192 = 追认版 EventSink（带 extends AutoCloseable）'; a = 'v3'; l = 192; w = 'interface EventSink extends AutoCloseable {'; a2 = 199; w2 = 'ArtifactManifest closeAndBuildManifest();' }
)
$bad = 0
foreach ($p in $probe) {
    $arr = if ($p.a -eq 'v1') { $v1 } else { $v3 }
    $ok = $arr[$p.l - 1].Contains($p.w)
    $ok2 = $true
    if ($p.a2) { $ok2 = $arr[$p.a2 - 1].Contains($p.w2) }
    if (-not ($ok -and $ok2)) { $bad++ }
    Say ('   ' + $(if ($ok -and $ok2) { 'PASS' } else { 'FAIL' }) + '  ' + $p.name)
}
Say ('   ⇒ ' + ($probe.Count - $bad) + '/' + $probe.Count + ' 通过（全部通过才说明"旧锚点 = 实际行号 −1"这一勘误前提成立）')
Say ''
Say '-- 3. 契约文件解析/结构复验（Python 3.14 + PyYAML + jsonschema） --'
$py = @'
import json, yaml
from jsonschema import Draft202012Validator
base = r"D:\Develop_code\GraduationProject\contract-specs"
o = yaml.safe_load(open(base + r"\openapi\generator-api.v1.yaml", encoding="utf-8"))
print("openapi YAML 解析 OK；components.schemas =", len(o["components"]["schemas"]), "；paths =", len(o["paths"]))
for n in ("TargetCheckResult", "TargetCapabilities"):
    s = o["components"]["schemas"][n]
    print("  ", n, "properties =", sorted(s["properties"].keys()), "| required =", s.get("required", "未声明"), "| x-unspecified =", s.get("x-unspecified", "无"))
print("  头部锚点说明含“少一行”与 F-29 =", ("少一行" in o["info"]["description"]) and ("F-29" in o["info"]["description"]))
m = json.load(open(base + r"\schemas\generation-artifact-manifest.v1.schema.json", encoding="utf-8"))
Draft202012Validator.check_schema(m)
print("generation-artifact-manifest.v1 check_schema OK；x-evidence 条数 =", len(m["x-evidence"]), "；含勘误条 =", any("少一行" in e for e in m["x-evidence"]))
r = open(base + r"\README.md", encoding="utf-8").read()
print("README 含 §11 补记 =", "## 11. 补记：M1-5 收口同步" in r, "；含勘误表 =", "V2.1 锚点全部比实际行号少一行" in r, "；残留计数 258 =", "258 处" in r)
c = json.load(open(base + r"\schemas\canonical-event.v1.schema.json", encoding="utf-8")); Draft202012Validator.check_schema(c)
i = json.load(open(base + r"\schemas\ingestion-manifest.v1.schema.json", encoding="utf-8")); Draft202012Validator.check_schema(i)
print("对照（本轮未改动）：canonical-event.v1 与 ingestion-manifest.v1 check_schema 均 OK")
'@
$outF = Join-Path $root '.verify\m1-5-sync\validate3.out.txt'
# 两个取证坑的修法：① 不捕获原生 stdout（Windows 会按 OEM 码页解码 ⇒ 中文乱码）；
# ② 不在脚本末尾替换 sys.stdout（会丢掉此前缓冲的 print ⇒ 0 字节输出）。
# 改为：在 python 模块头把 print 重定向到内存列表，尾部自己用 encoding='utf-8' 写文件，PowerShell 再按 UTF-8 读回。
$hdr = "import io`nOUT = r`"$outF`"`n_L = []`ndef print(*a, **k):`n    _L.append(' '.join(str(x) for x in a))`n"
$ftr = "`nopen(OUT, 'w', encoding='utf-8').write('\n'.join(_L) + '\n')`n"
[IO.File]::WriteAllText((Join-Path $root '.verify\m1-5-sync\validate3.py'), ($hdr + $py + $ftr), $enc)
$null = & python (Join-Path $root '.verify\m1-5-sync\validate3.py') 2>&1
foreach ($line in ([IO.File]::ReadAllText($outF, $enc) -split "`r?`n")) { if ($line.Trim().Length -gt 0) { Say ('   ' + $line.TrimEnd()) } }
Say ''
Say '-- 4. 本轮改动范围（git diff --numstat，含未提交） --'
Say ((& git diff --numstat -- contract-specs) | ForEach-Object { '   ' + $_ })
Say ''
Say '-- 5. contract-specs 全目录 SHA-256（VERSION 1.3.0 后的基线） --'
Get-ChildItem 'contract-specs' -Recurse -File | Sort-Object FullName | ForEach-Object { Say ('   ' + $_.FullName.Replace($root + '\contract-specs\','') + '  ' + (Get-FileHash $_.FullName -Algorithm SHA256).Hash) }
Say ''
Say '-- 6. 残留裸锚点复算（判据：未带 V2.3 /V2.1 前缀的 §x.y Lzzz） --'
$bare = 0
foreach ($f in @($yaml, $man, 'contract-specs\schemas\canonical-event.v1.schema.json', 'contract-specs\schemas\ingestion-manifest.v1.schema.json', 'contract-specs\specs\warehouse-namespace.v1.json', $readme)) {
    $t = [IO.File]::ReadAllText((Join-Path $root $f), $enc)
    $bare += ([regex]::Matches($t, '(?<!V2\.3 )(?<!V2\.1 )§\d+(?:\.\d+)*\s*L\d+')).Count
}
Say ('   残留裸锚点 = ' + $bare + ' 处（残留项 R-M1-5-1；重算规则：V2.1 锚点 +1，再按 §4.1 +12 / §4.2-§4.4 +145 映射到 V2.3）')
Say ''
$log | Out-File -FilePath (Join-Path $root '.verify\m1-5-sync\raw-verify-log-20260912.txt') -Encoding utf8
Write-Host 'LOG WRITTEN .verify/m1-5-sync/raw-verify-log-20260912.txt'
