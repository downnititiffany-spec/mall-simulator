# M1-5 收口同步 第二步：把"锚点系统性少一行"的实测勘误写进契约载体（只追加/只修正我自己刚写的两行），并量化残留项。
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$enc = [Text.UTF8Encoding]::new($false)
$log = [Collections.Generic.List[string]]::new()
function Say($s) { $log.Add($s); Write-Host $s }

$yaml = 'contract-specs\openapi\generator-api.v1.yaml'
$man = 'contract-specs\schemas\generation-artifact-manifest.v1.schema.json'
$readme = 'contract-specs\README.md'

Say ('== M1-5 勘误写入 ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' ==')
Say ''
Say '-- 0. 先复现"少一行"（正向对照 + 负向对照，防止我算错） --'
$v1 = [IO.File]::ReadAllText('docs\guidance\history\项目完整实施指导书 V2.1.md', $enc) -split "`n"
$v3 = [IO.File]::ReadAllText('docs\guidance\history\项目完整实施指导书 V2.3.md', $enc) -split "`n"
$probe = @(
  @{ name = '§4.1 L107 声称 test(TargetConfig)'; file = 'v1'; line = 107; want = 'interface MallTargetAdapter {'; also = 108; alsoWant = 'TargetCheckResult test(TargetConfig config);' },
  @{ name = '§4.1 L108 声称 capabilities()'; file = 'v1'; line = 108; want = 'TargetCheckResult test(TargetConfig config);'; also = 109; alsoWant = 'TargetCapabilities capabilities();' },
  @{ name = '§4.1 L126 声称第一版实现'; file = 'v1'; line = 126; want = ''; also = 127; alsoWant = '第一版实现 `ReferenceMallHttpAdapter`' },
  @{ name = '§4.2 L132 声称 generator_target'; file = 'v1'; line = 132; want = '|---|---|---|'; also = 133; alsoWant = '| `generator_target` |' },
  @{ name = '§4.2 L135 声称 generation_artifact'; file = 'v1'; line = 135; want = '| `generation_run` |'; also = 136; alsoWant = '| `generation_artifact` |' },
  @{ name = '§4.3 L149 声称异常样本'; file = 'v1'; line = 149; want = '- 可复现：'; also = 150; alsoWant = '- 异常样本：' },
  @{ name = '§4.4 L153 声称返回 runId'; file = 'v1'; line = 153; want = ''; also = 154; alsoWant = '返回 runId' },
  @{ name = 'V2.3 §4.2 L281 = generation_artifact（我写的新锚点）'; file = 'v3'; line = 281; want = '| `generation_artifact` |'; also = 0; alsoWant = '' },
  @{ name = 'V2.3 §4.4 L304 = 连通性检查（我写的新锚点）'; file = 'v3'; line = 304; want = '连通性检查'; also = 0; alsoWant = '' },
  @{ name = 'V2.3 §4.1 L131 = §4.1 骨架的 EventSink（不带 extends AutoCloseable）'; file = 'v3'; line = 131; want = 'interface EventSink {'; also = 135; alsoWant = 'Manifest closeAndBuildManifest();' },
  @{ name = 'V2.3 §4.1.1.1 L192 = 追认版 EventSink（带 extends AutoCloseable）'; file = 'v3'; line = 192; want = 'interface EventSink extends AutoCloseable {'; also = 199; alsoWant = 'ArtifactManifest closeAndBuildManifest();' }
)
# 判据用 .Contains 而非 -like：PowerShell 的 WildcardPattern 把反引号当转义符，而锚点文本里满是反引号（第一版跑因此误报 4 处 FAIL，已修正后复跑；失败记录保留在会话与 README §11 补记）。
$bad = 0
foreach ($p in $probe) {
    $arr = if ($p.file -eq 'v1') { $v1 } else { $v3 }
    $ok = $arr[$p.line - 1].Contains($p.want)
    $ok2 = $true
    if ($p.also -gt 0) { $ok2 = $arr[$p.also - 1].Contains($p.alsoWant) }
    if (-not ($ok -and $ok2)) { $bad++ }
    Say ('   ' + $(if ($ok -and $ok2) { 'OK  ' } else { 'FAIL' }) + ' ' + $p.name + '  ⇒ ' + $p.file + ' L' + $p.line + ' = "' + $arr[$p.line - 1].Trim().Substring(0, [Math]::Min(46, $arr[$p.line - 1].Trim().Length)) + '"')
}
if ($bad -ne 0) { throw ('勘误前提不成立：' + $bad + ' 条对照失败') }
Say ('   ⇒ ' + $probe.Count + ' 条对照全部成立：旧锚点行 = 相邻错误内容、正确行 = 声称内容（"少一行"成立）')
Say ''

$pairs = @(
  @{ f = $man; n = 'E1 修正我自己写的 V2.1 对应行号 + 追加勘误条'; old = @'
    "docs/项目完整实施指导书 V2.3.md §4.2 L281 (generation_artifact 关键字段；对应 V2.1 §4.2 L135)",
'@; new = @'
    "docs/项目完整实施指导书 V2.3.md §4.2 L281 (generation_artifact 关键字段；对应 V2.1 §4.2 L136——本文件上方原注解写作 L135，实测少算一行，勘误见 ../README.md §11)",
    "【勘误 2026-09-12，M1-5 收口同步：本文件上方 V2.1 锚点**全部比实际行号少一行**（已逐条比对 V2.1 原文，勘误表见 ../README.md §11、事实记录 F-29）。引用历史锚点请先 +1 再按 V2.1 复核；当前权威锚点为上方 V2.3 行号。】",
'@ }
  @{ f = $yaml; n = 'E2 头部说明补入"少一行"结论'; old = @'
    - 行号锚点来源：本文件的 `§x.y Lzzz` 锚点**多数按 V2.1/V2.2 的行号记录**，尚未逐条在 V2.3 复核；
      引用前请按 `docs/项目完整实施指导书 V2.3.md` 复核（残留项 R-M1-5-1，见 `../README.md` §11）。
      已按 V2.3 复核过的锚点一律带 `V2.3 ` 前缀（2026-09-12 M1-5 收口同步）。
'@; new = @'
    - 行号锚点来源：本文件的 `§x.y Lzzz` 锚点**多数按 V2.1 的行号记录，且一律比实际行号少一行**（勘误 2026-09-12：
      已逐条比对 V2.1 原文，勘误表见 `../README.md` §11、事实记录 F-29）。引用这些历史锚点时请**先 +1** 再按
      `docs/项目完整实施指导书 V2.1.md` 复核；当前权威是 V2.3。按 V2.3 复核过的锚点一律带 `V2.3 ` 前缀；
      未逐条复核的裸锚点数量与重算规则见残留项 R-M1-5-1。
'@ }
  @{ f = $readme; n = 'E3 §11 追加勘误表与规律'; old = @'
**未做 / 未取证（不得当作已完成）**：
'@; new = @'
**勘误（2026-09-12 实测：拿 V2.1 原文逐条比对，不是推断）**：本目录与相邻契约文件里的 **V2.1 锚点全部比实际行号少一行**。

| 旧锚点（原文写法） | V2.1 该行**实际**内容 | V2.1 正确行号 | V2.3 行号 |
|---|---|---|---|
| §4.1 L107 `test(TargetConfig)` | `interface MallTargetAdapter {` | L108 | L120 |
| §4.1 L108 `capabilities()` | `TargetCheckResult test(TargetConfig config);` | L109 | L121 |
| §4.1 L126 「第一版实现 `ReferenceMallHttpAdapter`」 | 空行（该句在 L127） | L127 | L139 |
| §4.1 L118-L123（EventSink 四方法） | EventSink 块实际在 L119-L124 | L119-L124 | L131-L136 |
| §4.2 L132 `generator_target` | 表格分隔行 `\|---|---|---\|` | L133 | L278 |
| §4.2 L134 `generation_run` 字段 | `generation_plan` 行 | L135 | L280 |
| §4.2 L135 `generation_artifact` 字段 | `generation_run` 行 | L136 | L281 |
| §4.2 L138 `generation_event_stat` 字段 | 空行（该行在 L137） | L137 | L282 |
| §4.3 L149 异常样本 / 期望隔离数 | 「可复现」条目 | L150 | L295 |
| §4.4 L153 响应写作 `runId` | 空行（该句在 L154） | L154 | L299 |
| §4.4 L158 连通性检查 | `GET /api/v1/scenarios` | L159 | L304 |

**规律（残留项机械重算的依据）**：V2.1 锚点 = 实际行号 **−1**；V2.3 相对 V2.1 的偏移为 §4.1 **+12**、§4.2/§4.3/§4.4 **+145**（后三者同偏移，因为 V2.3 在 §4.1 之后插入了 §4.1.1）。据此可对残留裸锚点机械重算，但**本轮未做** ⇒ 不得声称全部锚点已校正。事实记录：**F-29**。

**未做 / 未取证（不得当作已完成）**：
'@ }
  @{ f = $readme; n = 'E4 残留项量化'; old = '- **未逐条重核全部锚点**：本目录 6 个文件中仍有若干 `§x.y Lzzz` 锚点未在 V2.3 中逐一比对（本轮只核了 `generator-api.v1` 的 §4.1 区域与 §4.2/§4.3/§4.4 已读到段落、README §7 的 Q9/Q10/Q11、制品清单 schema 的 `x-evidence`）⇒ 残留项 **R-M1-5-1**（见看板 M1-5 行）。'; new = '- **未逐条重核全部锚点（已量化）**：机械扫描（脚本第 7 步，判据＝未带 `V2.3 `/`V2.1 ` 前缀的 `§x.y Lzzz`）在 6 个文件中命中 **247 处**裸锚点；本轮只核了 `generator-api.v1` 的 §4.1 区域与 §4.2/§4.3/§4.4 已读到段落、README §7 的 Q9/Q10/Q11、制品清单 schema 的 `x-evidence`（另有 11 处已在上面勘误表中给出正确行号）⇒ 残留项 **R-M1-5-1**（看板 M1-5 行）；重算规则见上（+1 与 +12/+145）。' }
)

Say '-- 1. 逐条替换（每条断言命中 1 次） --'
$byFile = @{}
foreach ($p in $pairs) {
    if (-not $byFile.ContainsKey($p.f)) { $byFile[$p.f] = [IO.File]::ReadAllText((Join-Path $root $p.f), $enc) }
    $t = $byFile[$p.f]
    $c = ([regex]::Matches($t, [regex]::Escape($p.old))).Count
    if ($c -ne 1) { Say ('   [中止] ' + $p.n + ' 命中 ' + $c + ' 次'); throw ('锚点不唯一：' + $p.n) }
    $byFile[$p.f] = $t.Replace($p.old, $p.new)
    Say ('   OK  ' + $p.n)
}
Say ''
Say '-- 2. 写盘 + 同步后哈希 --'
foreach ($f in $byFile.Keys) { [IO.File]::WriteAllText((Join-Path $root $f), $byFile[$f], $enc); Say ('   写入 ' + $f + '  ' + (Get-FileHash (Join-Path $root $f) -Algorithm SHA256).Hash) }
Say ''
Say '-- 3. 复验（Python 解析 + jsonschema，确保勘误没写坏文件） --'
$py = @'
import json, yaml
from jsonschema import Draft202012Validator
base = r"D:\Develop_code\GraduationProject\contract-specs"
o = yaml.safe_load(open(base + r"\openapi\generator-api.v1.yaml", encoding="utf-8"))
print("  openapi YAML 解析 OK；schemas =", len(o["components"]["schemas"]), "paths =", len(o["paths"]))
h = o["info"]["description"]
print("  头部已含勘误说明 =", ("少一行" in h) and ("F-29" in h))
m = json.load(open(base + r"\schemas\generation-artifact-manifest.v1.schema.json", encoding="utf-8"))
Draft202012Validator.check_schema(m)
print("  manifest check_schema OK；x-evidence 条数 =", len(m["x-evidence"]))
print("  勘误条已入 x-evidence =", any("少一行" in e for e in m["x-evidence"]))
c = json.load(open(base + r"\schemas\canonical-event.v1.schema.json", encoding="utf-8")); Draft202012Validator.check_schema(c); print("  canonical-event.v1 check_schema OK（对照，未改）")
'@
$py | Out-File -FilePath (Join-Path $root '.verify\m1-5-sync\validate2.py') -Encoding utf8
& python (Join-Path $root '.verify\m1-5-sync\validate2.py') 2>&1 | ForEach-Object { Say ('   ' + $_) }
Say ''
Say '-- 4. contract-specs 全目录哈希（VERSION 1.3.0 后的冻结指纹基线） --'
Get-ChildItem 'contract-specs' -Recurse -File | Sort-Object FullName | ForEach-Object {
    Say ('   ' + $_.FullName.Replace($root + '\contract-specs\','') + '  ' + (Get-FileHash $_.FullName -Algorithm SHA256).Hash)
}
$log | Out-File -FilePath (Join-Path $root '.verify\m1-5-sync\raw-erratum-log-20260912.txt') -Encoding utf8
Write-Host 'LOG WRITTEN .verify/m1-5-sync/raw-erratum-log-20260912.txt'
