# M1-5 收口同步：按指导书 V2.3 §4.1.1 之 11（"待 M1-5 收口时按本节同步"）校正 contract-specs 的 x-unspecified 区域与锚点。
# 纪律：每个替换必须命中且仅命中 1 次；命中数不符即整脚本中止（不写盘）。加性变更，不改任何 shape 之外的历史文本，只追加带日期的补记。
$ErrorActionPreference = 'Stop'
$root = 'D:\Develop_code\GraduationProject'
Set-Location $root
$enc = [Text.UTF8Encoding]::new($false)
$log = [Collections.Generic.List[string]]::new()
function Say($s) { $log.Add($s); Write-Host $s }

$yaml = 'contract-specs\openapi\generator-api.v1.yaml'
$man = 'contract-specs\schemas\generation-artifact-manifest.v1.schema.json'
$readme = 'contract-specs\README.md'
$ver = 'contract-specs\VERSION'

Say ('== M1-5 收口同步 ' + (Get-Date).ToString('yyyy-MM-dd HH:mm:ss') + ' ==')
Say ''
Say '-- 1. 同步前 SHA-256 --'
foreach ($f in @($yaml, $man, $readme, $ver)) {
    Say ('   ' + $f + '  ' + (Get-FileHash $f -Algorithm SHA256).Hash)
}
Say ''

$pairs = @(
  # ---- generator-api.v1.yaml：§4.1 区域（指导书点名的两处 + 锚点） ----
  @{ f = $yaml; n = 'Y1 test 端点描述'; old = @'
        §4.4 L158：`POST /targets/{id}/test`（前缀按本文件规范化）。
        语义对应 §4.1 L107 的 `TargetCheckResult test(TargetConfig config)`；TargetCheckResult 与
        TargetConfig 的字段在指导书中均未定义，故响应只标 x-unspecified，不发明检查项字段。
'@; new = @'
        V2.3 §4.4 L304：`POST /targets/{id}/test`（前缀按本文件规范化）。
        语义对应 V2.3 §4.1 L120 的 `TargetCheckResult test(TargetConfig config)`。
        补记（2026-09-12，M1-5 收口同步）：字段表已由 V2.3 §4.1.1.3（L240）追认，响应 schema 按该表声明 properties；
        原文"TargetConfig/TargetCheckResult 字段均未定义、不发明检查项字段"**以此为准作废**（旧文本见 git 历史与 `VERSION` 1.2.0）。
'@ }
  @{ f = $yaml; n = 'Y2 路径参数锚点'; old = '          description: generator_target.id（§4.2 L132）。类型未冻结，故不声明 schema。'; new = '          description: generator_target.id（V2.3 §4.2 L278）。类型未冻结，故不声明 schema。' }
  @{ f = $yaml; n = 'Y3 200 响应标注'; old = @'
          description: 连通性检查结果。字段未冻结，待契约任务。指导书未规定状态码，按 200 记录。
          x-unspecified: true
'@; new = @'
          description: 连通性检查结果。字段按 V2.3 §4.1.1.3（L240）声明；指导书未规定成功状态码，按 200 记录（状态码仍属待决 Q12）。
'@ }
  @{ f = $yaml; n = 'Y4 RunId 参数锚点'; old = '        运行标识。§4.4 写作 `{id}`，§4.2 L134 的字段名为 run_id，§4.4 L153 的响应写作 runId；'; new = '        运行标识。V2.3 §4.4 写作 `{id}`（L300/§4.4 L304），V2.3 §4.2 L280 的字段名为 run_id，V2.3 §4.4 L299 的响应写作 runId；' }
  @{ f = $yaml; n = 'Y5 GeneratorTarget 标题'; old = '      title: 目标商城配置（§4.2 L132 generator_target）'; new = '      title: 目标商城配置（V2.3 §4.2 L278 generator_target）' }
  @{ f = $yaml; n = 'Y6 GeneratorTarget 字段来源'; old = '        字段名逐项取自 §4.2 L132：id、name、adapter_type、base_url、credential_ref、config_json、'; new = '        字段名逐项取自 V2.3 §4.2 L278：id、name、adapter_type、base_url、credential_ref、config_json、' }
  @{ f = $yaml; n = 'Y7 adapter_type 取值集合'; old = @'
        『每次运行冻结版本』（config_version）。adapter_type 的取值集合未冻结（§4.1 L126 只说明第一版实现
        ReferenceMallHttpAdapter 与 JsonlEventSink）。
'@; new = @'
        『每次运行冻结版本』（config_version）。adapter_type 的取值集合**按契约不冻结**（V2.3 §4.1.1.1 L158 明示
        "取值集合不冻结"）；V2.3 §4.1 L139 只说明第一版实现 ReferenceMallHttpAdapter 与 JsonlEventSink。
'@ }
  @{ f = $yaml; n = 'Y8 adapter_type 字段'; old = '          description: 适配器类型；取值集合未冻结（§4.1 L126 第一版为 ReferenceMallHttpAdapter）。'; new = '          description: 适配器类型；取值集合**按契约不冻结**（V2.3 §4.1.1.1 L158）；第一版为 ReferenceMallHttpAdapter（V2.3 §4.1 L139）。' }
  @{ f = $yaml; n = 'Y9 credential_ref 字段'; old = '          description: 凭据引用（不得为明文凭据）。§4.2 L132『凭据只存引用』；引用形态未冻结。'; new = '          description: 凭据引用（不得为明文凭据）。V2.3 §4.2 L278『凭据只存引用』；纪律见 V2.3 §4.1.1.2 之 4（日志/流水/异常里只允许出现引用名，值绝不出现）；引用形态未冻结。' }
  @{ f = $yaml; n = 'Y10 config_version 字段'; old = '          description: 配置版本，用于运行冻结（§4.2 L132『每次运行冻结版本』）。类型未冻结。'; new = '          description: 配置版本，用于运行冻结（V2.3 §4.2 L278『每次运行冻结版本』）。类型未冻结。' }
  @{ f = $yaml; n = 'Y11 capabilities 台账列'; old = @'
        capabilities:
          description: 能力集合（对应 §4.1 L108 capabilities()）。结构未冻结。
          x-unspecified: true
'@; new = @'
        capabilities:
          description: 能力**台账列**（V2.3 §4.1 L121 `capabilities()` / §4.2 L278 的 capabilities 列）。**不参与任何能力判定**（V2.3 §4.1.1.1 L216-218：判定一律取自 `capabilities(config)` 声明与 `test(config)` 实测，本列只是台账）；列内结构未冻结。
          x-unspecified: true
'@ }
  @{ f = $yaml; n = 'Y12 TargetCheckResult 空对象→字段表'; old = @'
    TargetCheckResult:
      title: 连通性检查结果（§4.1 L107 / §4.4 L158）
      type: object
      description: |
        §4.1 L107 `TargetCheckResult test(TargetConfig config)` 与 §4.4 L158 的连通性检查均已提出该对象，
        但指导书没有给出它的任何字段，故本契约不声明 properties（空对象 + x-unspecified），
        不发明『检查项/是否通过/耗时』等字段。
      additionalProperties: true
      x-unspecified: true
'@; new = @'
    TargetCheckResult:
      title: 连通性检查结果（V2.3 §4.1 L120 / §4.1.1.3 L240 / §4.4 L304）
      type: object
      description: |
        字段表由 **V2.3 §4.1.1.3（L240）追认**：`targetId`(long)、`reachable`(boolean)、`detail`(String)、
        `capabilities`(Map)。`detail` 只写过程说明，**不得含凭据值**（V2.3 §4.1.1.2 之 4）。
        `capabilities` 是本次探测的**实测**判定，与 `generator_target.capabilities` 台账列不是一回事
        （V2.3 §4.1.1.1 L216-218）。
        必填性未在指导书中明示：实现侧 `adapter/TargetCheckResult.java` 的四组件都是构造参数，其中
        `capabilities` 由构造器把 null/空表归一为 `Map.of()` ⇒ 本契约不声明 `required`（不发明来源未给的约束）。
        补记（2026-09-12，M1-5 收口同步）：本对象此前为「空对象 + x-unspecified、不声明 properties」
        （`VERSION` 1.2.0），该写法**以此为准作废**。
      properties:
        targetId:
          type: integer
          format: int64
          description: generator_target.id（V2.3 §4.2 L278）；一次检查内冻结。
        reachable:
          type: boolean
          description: 本次实测目标是否可达。
        detail:
          type: string
          description: 过程说明；只写过程，不得含凭据值。
        capabilities:
          type: object
          description: 实测能力判定表：键为能力名（V2.3 §4.1.1.3 L242），值为三态判定（L243）。
          additionalProperties:
            type: string
            enum: [SUPPORTED, ABSENT, UNDETERMINED]
      additionalProperties: true

    TargetCapabilities:
      title: 能力声明/判定集合（V2.3 §4.1.1.1 L165 / §4.1.1.3 L241）
      type: object
      description: |
        判定只有三态（V2.3 §4.1.1.2 之 5）：`SUPPORTED` / `ABSENT` / `UNDETERMINED`；缺键、未知、探测不到
        一律 `UNDETERMINED`，**不得**说成 `SUPPORTED`。`capabilities(config)` 是**声明**（不联网），
        `test(config)` 里的 `capabilities` 是**实测**。实现侧 `adapter/TargetCapabilities.java` = (`verdicts`, `declared`)，
        `none()` = 空表 + `declared=false`。能力名（`product`/`user`/`order`/`refund`/`behavior`/`reset_state`/`admin`）
        是生成器侧词汇、不是跨程序契约；新增能力名须先改 V2.3 §4.1.1.3（L242）。
      properties:
        verdicts:
          type: object
          description: 能力名 → 三态判定。
          additionalProperties:
            type: string
            enum: [SUPPORTED, ABSENT, UNDETERMINED]
        declared:
          type: boolean
          description: 该表是「声明」（true）还是「实测」（false）；`none()` 为 false。
      additionalProperties: true
'@ }
  @{ f = $yaml; n = 'Y13 Scenario 标题'; old = '      title: 场景定义与输入约束（§4.4 L157）'; new = '      title: 场景定义与输入约束（V2.3 §4.4 L303）' }
  @{ f = $yaml; n = 'Y14 Scenario 描述锚点'; old = '        §4.4 L157 要求返回『场景定义和输入约束』，§4.3（L140-L149）给出了算法维度但未冻结字段名，'; new = '        V2.3 §4.4 L303 要求返回『场景定义和输入约束』，V2.3 §4.3（L286-L295）给出了算法维度但未冻结字段名，' }
  @{ f = $yaml; n = 'Y15 GenerationArtifact 标题'; old = '      title: 生成制品（§4.2 L135 generation_artifact）'; new = '      title: 生成制品（V2.3 §4.2 L281 generation_artifact）' }
  @{ f = $yaml; n = 'Y16 GenerationArtifact 字段来源'; old = '        字段名逐项取自 §4.2 L135：run_id、uri、checksum、bytes、record_count、min_event_time、'; new = '        字段名逐项取自 V2.3 §4.2 L281：run_id、uri、checksum、bytes、record_count、min_event_time、' }
  # ---- 生成器制品清单 schema：合成标记落点已被 V2.3 L675 追认 + 追加 V2.3 证据锚点 ----
  @{ f = $man; n = 'M1 synthetic 标记落点'; old = '    "rationale": "V2.1 §3.3 B L90 只规定『该模式必须显示 synthetic=true』，未规定该标记落在制品清单、运行报告还是响应体；本文件选择制品清单作为载体，需契约任务确认。"'; new = '    "rationale": "V2.1 §3.3 B L90 只规定『该模式必须显示 synthetic=true』，未规定该标记落在制品清单、运行报告还是响应体；本文件选择制品清单作为载体，需契约任务确认。★补记（2026-09-12，M1-5 收口同步）：V2.3 L675 已明确『synthetic=true 放在 generation run、artifact manifest 和验收元数据中，不加入 8 字段事件信封』⇒ 本文件选择『制品清单』作为载体之一**已被追认**，不再是待确认项；随之而来的实现要求是**三处载体都要写**（generation run、artifact manifest、验收元数据），是否已满足属生成器侧取证范围，本契约不代为宣称。原文保留为历史（V2.1 已降为历史参考）。"' }
  @{ f = $man; n = 'M2 x-evidence 追加 V2.3 锚点'; old = @'
    "docs/项目完整实施指导书 V2.1.md §4.3 L149 (manifest 记录期望隔离数 → 与 §4.2 字段清单冲突)",
    "docs/contracts/event-contract.md §1 L25 (时间一律 ISO-8601 带时区)"
'@; new = @'
    "docs/项目完整实施指导书 V2.1.md §4.3 L149 (manifest 记录期望隔离数 → 与 §4.2 字段清单冲突)",
    "【补记 2026-09-12，M1-5 收口同步：以下 V2.3 锚点为当前权威；上方 V2.1 锚点保留为历史来源（V2.1 已降为历史参考），行号与 V2.3 不同】",
    "docs/项目完整实施指导书 V2.3.md §3.3 B L101-L103 (CANONICAL_EVENT_FILE 模式与 synthetic=true)",
    "docs/项目完整实施指导书 V2.3.md §4.1 L131-L136 (EventSink 四方法；V2.3 另在 §4.1.1.3 L258 追认 extends AutoCloseable / default close() 与返回类型更名 ArtifactManifest)",
    "docs/项目完整实施指导书 V2.3.md §4.2 L281 (generation_artifact 关键字段；对应 V2.1 §4.2 L135)",
    "docs/项目完整实施指导书 V2.3.md §4.3 L295 (manifest 记录期望隔离数 → 与 §4.2 字段清单冲突，冲突仍在)",
    "docs/项目完整实施指导书 V2.3.md L675 (synthetic=true 的三处载体：generation run / artifact manifest / 验收元数据)",
    "docs/contracts/event-contract.md §1 L25 (时间一律 ISO-8601 带时区)"
'@ }
  # ---- README：Q 条锚点校正 ----
  @{ f = $readme; n = 'R1 Q9 锚点'; old = '9. **Q9 「期望隔离数」字段缺失**：§4.3 L149 要求「在 manifest 中记录期望隔离数」，但 §4.2 L135 的 `generation_artifact` 字段清单里没有它。'; new = '9. **Q9 「期望隔离数」字段缺失**：V2.3 §4.3 L295 要求「在 manifest 中记录期望隔离数」，但 V2.3 §4.2 L281 的 `generation_artifact` 字段清单里没有它。' }
  @{ f = $readme; n = 'R2 Q10 锚点'; old = '10. **Q10 制品轮转与运行级清单**：§4.1 L120 `Optional<Artifact> rotateIfNeeded()` 意味着一次运行可能有多个制品文件，而 §4.2 L135 只有单制品字段。'; new = '10. **Q10 制品轮转与运行级清单**：V2.3 §4.1 L133 `Optional<Artifact> rotateIfNeeded()` 意味着一次运行可能有多个制品文件，而 V2.3 §4.2 L281 只有单制品字段。' }
  @{ f = $readme; n = 'R3 Q11 锚点'; old = '11. **Q11 生成器命名与类型未冻结**：§4.2 L134 写作 `started/finished`、L133 写作 `start/end`，均未给出完整键名（本目录按字面取 `started`/`finished`/`start`/`end`）；`run_id`（§4.2）与 `runId`（§4.4 L153）命名不一致；'; new = '11. **Q11 生成器命名与类型未冻结**：V2.3 §4.2 L280 写作 `started/finished`、L279 写作 `start/end`，均未给出完整键名（本目录按字面取 `started`/`finished`/`start`/`end`）；`run_id`（V2.3 §4.2 L280）与 `runId`（V2.3 §4.4 L299）命名不一致；' }
  @{ f = $readme; n = 'R4 Q3 部分解决'; old = '需决定：(a) 确认该落点，文件模式事件仍写 `source_system=mock-mall`；或 (b) 允许生成器使用另一个 `source_system` 值（等于改契约，需升版本）。'; new = '需决定：(a) 确认该落点，文件模式事件仍写 `source_system=mock-mall`；或 (b) 允许生成器使用另一个 `source_system` 值（等于改契约，需升版本）。**补记（2026-09-12，M1-5 收口同步）**：选项 (a) 的「落点」部分已被 V2.3 L675 追认（`synthetic=true` 放 generation run / artifact manifest / 验收元数据，不进 8 字段信封）；**仍未决**的是 `source_system` 的取值（文件模式事件是否仍写 `mock-mall`）⇒ 本条降级为「仅 `source_system` 取值待决」。' }
)

Say '-- 2. 逐条替换（每条断言命中 1 次） --'
$byFile = @{}
foreach ($p in $pairs) {
    if (-not $byFile.ContainsKey($p.f)) { $byFile[$p.f] = [IO.File]::ReadAllText((Join-Path $root $p.f), $enc) }
    $t = $byFile[$p.f]
    $c = ([regex]::Matches($t, [regex]::Escape($p.old))).Count
    if ($c -ne 1) { Say ('   [中止] ' + $p.n + ' 命中 ' + $c + ' 次（要求 1）'); Say ($log -join "`n") | Out-Null; throw ('锚点不唯一：' + $p.n + ' => ' + $c) }
    $byFile[$p.f] = $t.Replace($p.old, $p.new)
    Say ('   OK  ' + $p.n)
}
Say ('   合计 ' + $pairs.Count + ' 条替换全部唯一命中')
Say ''

Say '-- 3. VERSION 升版（加性：声明 TargetCheckResult 字段表 + 新增 TargetCapabilities） --'
$vOld = [IO.File]::ReadAllText((Join-Path $root $ver), $enc).Trim()
$vNew = 'contract-specs 1.3.0'
if ($vOld -ne 'contract-specs 1.2.0') { throw ('VERSION 意外：' + $vOld) }
Say ('   ' + $vOld + '  ->  ' + $vNew)
Say ''

Say '-- 4. 追加 README §11 补记 --'
$r = $byFile[$readme]
$anchor = '## 10. 冻结指纹（文件级，2026-09-11 总控复核时实测）'
if (([regex]::Matches($r, [regex]::Escape($anchor))).Count -ne 1) { throw 'README §10 锚点不唯一' }
$sec = @'
## 11. 补记：M1-5 收口同步（2026-09-12）

**为什么有本节**：指导书 V2.3 §4.1.1 之 11【新增要求】原文——「`contract-specs/openapi/generator-api.v1.yaml`
现在把这一区域标为 `x-unspecified`，待 M1-5 收口时按本节同步（含 `TargetCheckResult` 的空对象声明与
`adapter_type` 取值集合）」。本节记录该同步做了什么、依据是什么、还有什么没做。

**同步清单（只增不删；原文若被废止，均以「以此为准作废」+ 日期标注）**：

| # | 对象 | 同步前（`VERSION` 1.2.0） | 同步后（`VERSION` 1.3.0） | 依据 |
|---|---|---|---|---|
| 1 | `TargetCheckResult`（`generator-api.v1`） | 空对象 + `x-unspecified`，明写"指导书没有给出它的任何字段，不发明检查项字段" | 按 DTO 最小字段表声明 `targetId`/`reachable`/`detail`/`capabilities` 四个 properties；**不声明 `required`**（来源未给必填性，不发明约束） | V2.3 §4.1.1.3 L240；实现侧 `adapter/TargetCheckResult.java`（同一 schema 提交） |
| 2 | `TargetCapabilities` | **不存在**（`generator_target.capabilities` 只写"结构未冻结"） | 新增 schema：`verdicts`（能力名→三态）+ `declared` | V2.3 §4.1.1.3 L241、§4.1.1.2 之 5（三态、缺键⇒`UNDETERMINED`） |
| 3 | `adapter_type` 取值集合 | "取值集合未冻结（§4.1 L126 只说明第一版实现…）" | "**按契约不冻结**（V2.3 §4.1.1.1 L158 明示）"，第一版实现锚点改 V2.3 §4.1 L139 | 同上；语义由"来源漏规定"改成"契约明确留开"，两者不是一回事 |
| 4 | `generator_target.capabilities` 列 | "能力集合（对应 §4.1 L108 `capabilities()`）。结构未冻结" | "能力**台账列**，**不参与任何能力判定**"，判定取自 `capabilities(config)` 声明与 `test(config)` 实测 | V2.3 §4.1.1.1 L216-218【追认】 |
| 5 | `credential_ref` | "凭据只存引用；引用形态未冻结" | 追加 V2.3 §4.1.1.2 之 4 的凭据纪律（日志/流水/异常里只允许出现引用名） | V2.3 §4.1.1.2 之 4 |
| 6 | `generation-artifact-manifest.v1` 的 `x-synthetic-marker` | "未规定该标记落在制品清单、运行报告还是响应体；需契约任务确认" | **落点已被追认**（制品清单是三个载体之一），并要求三处载体都写；是否已满足留给生成器侧取证 | V2.3 L675 |
| 7 | 全部 `§x.y Lzzz` 锚点 | 指向 V2.1/V2.2 行号 | 加 `V2.3 ` 前缀并改为 V2.3 行号（本文件 §4.1 区域 + 逐条复核过的 §4.2/§4.3/§4.4 锚点） | 指导书已迭代到 V2.3，裸行号会指向错误段落 |

**未做 / 未取证（不得当作已完成）**：

- **未逐条重核全部锚点**：本目录 6 个文件中仍有若干 `§x.y Lzzz` 锚点未在 V2.3 中逐一比对（本轮只核了 `generator-api.v1` 的 §4.1 区域与 §4.2/§4.3/§4.4 已读到段落、README §7 的 Q9/Q10/Q11、制品清单 schema 的 `x-evidence`）⇒ 残留项 **R-M1-5-1**（见看板 M1-5 行）。
- **Q12 未被本节解决**：§4.1.1 覆盖的是 SPI/DTO，不覆盖 REST 约定（成功状态码、错误体形状、`/targets` 的动词与前缀、进度字段名）⇒ Q12 保持待决。
- **新增 Q16**：V2.3 §4.1.1.2 之 6【新增要求】要求运行流水/报告的路由取自 `operationRoutes(config)`，但**生成器对外 API 与页面尚未规定如何暴露该路由信息**（`GET /generation-runs/{id}` 无对应字段）⇒ 需决定：是否在运行详情/产物中增加路由字段（会改本 OpenAPI）。
- **平台侧 `IngestionManifestSourceSchemaTest` 类注释里的"目录级 `1.2.0`"未改**：那是 P1-05 加法扩展发生时的版本，属历史陈述；本轮升到 1.3.0 不改动测试文件（避免跨模块无谓改动），如需同步由平台侧泳道带出。
- **`VERSION` 升版未同步 `analytics-server` 侧任何断言**：已实测全仓唯一命中是上述注释一处（`contract-specs 1.2.0`/`"1.2.0"` 检索），故升版不影响 E2。

'@
$byFile[$readme] = $r.Replace($anchor, $sec + $anchor)
Say '   OK  §11 补记已插入（§10 之前）'
Say ''

Say '-- 5. 写盘 --'
foreach ($f in $byFile.Keys) { [IO.File]::WriteAllText((Join-Path $root $f), $byFile[$f], $enc); Say ('   写入 ' + $f) }
[IO.File]::WriteAllText((Join-Path $root $ver), $vNew + "`n", $enc)
Say ('   写入 ' + $ver + ' = ' + $vNew.Trim())
Say ''

Say '-- 6. 同步后 SHA-256 --'
foreach ($f in @($yaml, $man, $readme, $ver)) { Say ('   ' + $f + '  ' + (Get-FileHash $f -Algorithm SHA256).Hash) }
Say ''
Say '== 6 文件解析/校验（Python 3.14.5 + PyYAML 6.0.3 + jsonschema 4.26.0） =='
$py = @'
import json, yaml, sys
from jsonschema import Draft202012Validator
base = r"D:\Develop_code\GraduationProject\contract-specs"
o = yaml.safe_load(open(base + r"\openapi\generator-api.v1.yaml", encoding="utf-8"))
print("  openapi YAML 解析 OK；schemas =", len(o["components"]["schemas"]), "；paths =", len(o["paths"]))
for n in ("TargetCheckResult", "TargetCapabilities"):
    s = o["components"]["schemas"][n]
    print("    ", n, "properties =", sorted(s["properties"].keys()), "required =", s.get("required", "未声明"), "x-unspecified =", s.get("x-unspecified", "无"))
m = json.load(open(base + r"\schemas\generation-artifact-manifest.v1.schema.json", encoding="utf-8"))
Draft202012Validator.check_schema(m)
print("  generation-artifact-manifest.v1 check_schema OK；x-evidence 条数 =", len(m["x-evidence"]))
c = json.load(open(base + r"\schemas\canonical-event.v1.schema.json", encoding="utf-8")); Draft202012Validator.check_schema(c); print("  canonical-event.v1 check_schema OK（未改动，作对照）")
i = json.load(open(base + r"\schemas\ingestion-manifest.v1.schema.json", encoding="utf-8")); Draft202012Validator.check_schema(i); print("  ingestion-manifest.v1 check_schema OK（未改动，作对照）")
'@
$py | Out-File -FilePath (Join-Path $root '.verify\m1-5-sync\validate.py') -Encoding utf8
& python (Join-Path $root '.verify\m1-5-sync\validate.py') 2>&1 | ForEach-Object { Say ('   ' + $_) }
Say ''
Say '-- 7. 残留裸锚点扫描（未加 V2.3 前缀的 §x.y Lzzz，供 R-M1-5-1 用） --'
$bare = @()
foreach ($f in @($yaml, $man, 'contract-specs\schemas\canonical-event.v1.schema.json', 'contract-specs\schemas\ingestion-manifest.v1.schema.json', 'contract-specs\specs\warehouse-namespace.v1.json', $readme)) {
    $t = [IO.File]::ReadAllText((Join-Path $root $f), $enc) -split "`n"
    for ($i = 0; $i -lt $t.Count; $i++) {
        foreach ($mm in [regex]::Matches($t[$i], '(?<!V2\.3 )(?<!V2\.1 )§(\d+(?:\.\d+)*)\s*L(\d+)')) {
            $bare += ($f + ':' + ($i + 1) + '  §' + $mm.Groups[1].Value + ' L' + $mm.Groups[2].Value)
        }
    }
}
Say ('   残留 ' + $bare.Count + ' 处（正向对照：本扫描对已加前缀的锚点不匹配，故只列裸锚点）')
$bare | Select-Object -First 40 | ForEach-Object { Say ('     ' + $_) }
Say ''
$log | Out-File -FilePath (Join-Path $root '.verify\m1-5-sync\raw-sync-log-20260912.txt') -Encoding utf8
Write-Host 'LOG WRITTEN .verify/m1-5-sync/raw-sync-log-20260912.txt'
