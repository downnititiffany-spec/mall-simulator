// 接入向导（02.5）纯逻辑：不依赖 Vue/axios，node:test 可直接测。
// 原则（D-030/D-031）：向导只复用既有端点；预览是 advisory preflight，
// 激活门槛以 Loader/激活接口的实际结果为准；不开放任意服务器路径
// （sampleRef 由服务端 sample-root fail-close 校验，此处仅提供预置引用与提示）。

// 预置受控样本引用（仓库相对路径，落在服务端 platform.mapping.sample-root 下）。
// 这里只列运行环境真实存在的样本；列表为展示建议，不是安全边界（边界在服务端）。
// 预置清单只收 fixture-shop-b 受控集（02.5 向导演示面）；旧 A-B-A 双腿样本属 02.4 切源
// 验证产物，不进预置清单（边界守卫：分析前端不出现商城字样），仍可经手工 sampleRef 输入引用。
export const PRESET_SAMPLE_REFS = [
  { ref: 'fixture-shop-b/normal.jsonl', label: 'fixture-shop-b 正常样本（8 类事件全量覆盖）' },
  { ref: 'fixture-shop-b/fault.jsonl', label: 'fixture-shop-b 故障样本（5 类映射违规）' },
  { ref: 'fixture-shop-b/empty.jsonl', label: 'fixture-shop-b 空样本（fail-closed 演示）' }
]

export const PROFILE_PLACEHOLDER =
  '在此粘贴候选映射画像 JSON 原文（profileText）。服务端对提交字节做 sha256 作为权威 profileChecksum，' +
  '不读取服务器本地文件——接入新源不要求编辑服务器文件。'

/**
 * 预览步可用性：选源 + 样本引用 + 画像原文齐备才允许调 dry-run。
 * @returns {{ok: boolean, reason: string}} reason 为中文缺失说明（ok=true 时空串）
 */
export function canPreview(state) {
  const s = state || {}
  if (!s.sourceId) return { ok: false, reason: '请先在第 1 步选择要接入/调整的源' }
  const sampleRef = (s.sampleRef || '').trim()
  if (!sampleRef) return { ok: false, reason: '请选择预置受控样本或输入受控样本引用' }
  if (sampleRef.startsWith('/') || sampleRef.includes('..') || /^[a-zA-Z]+:/.test(sampleRef)) {
    return { ok: false, reason: '样本引用必须是 sample-root 下的仓库相对路径（不接受绝对路径、..、协议前缀）' }
  }
  const profileText = (s.profileText || '').trim()
  if (!profileText) return { ok: false, reason: '请粘贴候选映射画像 JSON 原文' }
  const limit = Number(s.limit)
  if (!Number.isInteger(limit) || limit < 1 || limit > 100) {
    return { ok: false, reason: '样本行数上限必须是 1..100 的整数（服务端契约）' }
  }
  return { ok: true, reason: '' }
}

/**
 * 画像原文的轻量客户端校验（advisory：真正的装载校验在服务端 Loader）。
 * 只做「能 parse、是对象、能看出 fieldMappings 形状」三层，帮助管理员在提交前发现粘贴错误。
 */
export function validateProfileText(text) {
  const t = (text || '').trim()
  if (!t) return { ok: false, error: '画像原文为空' }
  let obj
  try {
    obj = JSON.parse(t)
  } catch (e) {
    return { ok: false, error: '不是合法 JSON：' + e.message }
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) {
    return { ok: false, error: '画像必须是 JSON 对象' }
  }
  if (!obj.fieldMappings || typeof obj.fieldMappings !== 'object' || Array.isArray(obj.fieldMappings)) {
    return { ok: false, error: '缺少 fieldMappings 对象（v2 画像必备键之一，Loader 会拒绝）' }
  }
  return { ok: true, error: '' }
}

/** 覆盖率展示：分母为 0 时服务端给 null，展示「未观察到」而不是 0%（口径见 MappingDryRunReport） */
export function formatCoverage(v) {
  if (v === null || v === undefined) return '未观察到'
  return (v * 100).toFixed(1) + '%'
}

export function shortChecksum(sha) {
  if (!sha) return '—'
  return String(sha).slice(0, 12)
}

/**
 * 把 dry-run 报告折叠成预览步的展示行（顺序固定，便于 E2E 断言）。
 * 只引用报告里真实存在的字段（MappingDryRunReport），不编造。
 */
export function summarizeReport(report) {
  if (!report) return null
  const rows = [
    { label: '画像版本', value: report.profileVersion || '—' },
    { label: '画像语法', value: report.profileSyntax || '—' },
    { label: 'profileChecksum', value: shortChecksum(report.profileChecksum) },
    { label: '画像装载', value: report.profileAccepted ? '成功' : '失败' },
    { label: '处理行数', value: String(report.processedCount) },
    { label: '接受 / 隔离', value: `${report.acceptedCount} / ${report.quarantinedCount}` },
    { label: '系统异常', value: String((report.systemErrors || []).length) },
    { label: '必填覆盖率', value: formatCoverage(report.requiredCoverage) },
    { label: '枚举覆盖率', value: formatCoverage(report.enumCoverage) },
    { label: '样本引用', value: report.sampleRef || '—' }
  ]
  return {
    rows,
    eligible: !!report.activationEligible,
    blocks: report.activationBlocks || [],
    gaps: report.capabilityGaps || [],
    ineligibleReasons: report.activationIneligibleReasons || [],
    profileIssues: report.profileIssues || []
  }
}

/** 违规计数表展示行（reasonCounts 只含非零项，按违例条数计） */
export function reasonCountRows(report) {
  const rc = (report && report.reasonCounts) || {}
  return Object.keys(rc)
    .sort()
    .map((k) => ({ reason: k, count: rc[k] }))
}

/**
 * 激活失败的用户话术。只认得 err.response.status + body.code 的稳定映射；
 * 认不出时原样透传 message，不编造原因。
 */
export function activationErrorMessage(err) {
  const status = err && err.response && err.response.status
  const body = (err && err.response && err.response.data) || {}
  const code = body.code || ''
  if (code === 'MAPPING_PROFILE_CHANGED' || status === 409) {
    return (
      '画像在预览后发生了变化（或报告不可激活，409 ' + (code || 'CONFLICT') +
      '）。请回到第 2 步重新粘贴画像并重新预览，再执行激活。'
    )
  }
  if (status === 404) {
    return 'dry-run 报告不存在或已过期（报告仅本进程内有效，平台重启后失效）。请重新预览生成新报告。'
  }
  if (status === 403) {
    return '当前账号没有运行管理权限（RUNTIME_MANAGE 仅 admin 等运维角色）。'
  }
  return (body && body.message) || (err && err.message) || '激活失败'
}

/** 映射激活结果行（MappingActivationOutcome 真实字段） */
export function summarizeActivation(outcome) {
  if (!outcome) return null
  return [
    { label: '是否变更指针', value: outcome.changed ? '是（写入新激活指针）' : '否（同源同画像幂等，未重复绑定）' },
    { label: '画像版本', value: outcome.profileVersion || '—' },
    { label: 'profileChecksum', value: shortChecksum(outcome.profileChecksum) },
    { label: '契约版本', value: outcome.contractVersion || '—' },
    { label: '授权报告', value: outcome.reportId || '—' },
    { label: '原指针画像', value: outcome.previousProfileChecksum ? shortChecksum(outcome.previousProfileChecksum) : '（首次激活，无原指针）' }
  ]
}
