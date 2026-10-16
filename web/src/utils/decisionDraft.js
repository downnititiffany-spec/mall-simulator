// AI 建议 → 决策草稿的请求体口径（契约 `docs/contracts/r8-evidence-security-decision.md` §5 补遗 v1.1）。
//
// 唯一职责：把「页面已经展示、后端已经返回」的字段装成 `POST /api/v1/decisions` 的请求体。
// 三条不变量：
//   ① 不造字段：后端没给的一律留空（模板分支 `targetMetricCode` 恒为 null，不猜指标编码）；
//   ② 不猜方向：`targetDirection` 必须由员工显式选 UP/DOWN，本模块只做严格校验，不给默认值；
//   ③ 不造锚点：`evidenceId`/快照号必须是真实值，占位串与空白一律按「没有锚点」处理，
//      无锚点时**拒绝构造请求**（服务端 `missingForSubmit` 要求 `evidence_package_id` 或
//      `suggestion_snapshot_id` 之一，前端提前拦下，避免员工填完才被后端拒绝）。
//
// 边界：本模块**不**复刻服务端提交校验（`missingForSubmit` 的唯一属主在服务端），
//       **不**下发 `source`/`status`/`evalWindowDays`（服务端固定/给默认），**不**调接口。
import { isRealSnapshotId } from './context.js'

/** 决策目标方向（`DecisionService.missingForSubmit` 只接受这两个值） */
export const DRAFT_DIRECTIONS = Object.freeze({ UP: 'UP', DOWN: 'DOWN' })

/** 下拉框选项：空串代表「未选择」，页面不得替员工预选 */
export const DIRECTION_CHOICES = Object.freeze([
  { value: '', label: '未选择（提交审批前必选）' },
  { value: DRAFT_DIRECTIONS.UP, label: '提升（UP）' },
  { value: DRAFT_DIRECTIONS.DOWN, label: '降低（DOWN）' }
])

/** 锚点种类：证据包 ID 优先，其次真实快照号，都没有则 NONE（不可创建） */
export const ANCHOR_KIND = Object.freeze({
  EVIDENCE_PACKAGE: 'evidence_package',
  SUGGESTION_SNAPSHOT: 'suggestion_snapshot',
  NONE: 'none'
})

/** 构造被拒的原因码（页面据此给文案，不再各自判断） */
export const DRAFT_BLOCK = Object.freeze({
  SUGGESTION_INCOMPLETE: 'SUGGESTION_INCOMPLETE',
  NO_EVIDENCE_ANCHOR: 'NO_EVIDENCE_ANCHOR'
})

/** 请求体允许出现的键（超出即错误；创建草稿需要的其余字段由服务端补默认值） */
export const DRAFT_FIELDS = Object.freeze([
  'title', 'action', 'targetMetricCode', 'targetDirection', 'suggestionSnapshotId', 'evidencePackageId', 'owner'
])

/** 提交审批的齐全性判定归服务端：页面只原样转述，不自己维护第二份校验 */
export const SUBMIT_REQUIREMENT_TEXT =
  '提交审批前，服务端要求动作、负责人、目标指标、目标方向（提升/降低）与证据锚点齐全（评价窗口天数由服务端给默认值）；' +
  '缺项时服务端返回 PARAM_INVALID 原文，本页不复刻该校验。'

const isRecord = (v) => Boolean(v) && typeof v === 'object' && !Array.isArray(v)

/** 去空白取普通文本；仅用于标题/动作/负责人等展示或业务文本，不用于任何后端 ID。 */
export function draftText(value) {
  return typeof value === 'string' && value.trim() !== '' ? value.trim() : null
}

/**
 * 是否为真实证据包 ID。
 * S3-55/S3-56：ID 必须保持后端原始形状——不 trim、不把数字转字符串。这里只复用
 * `context.js` 的严格标识判据（空串、带前后空白、unknown 任意大小写均无效），避免第二份 ID 归一化。
 */
export function isRealEvidenceId(value) {
  return isRealSnapshotId(value)
}

/** 方向严格校验：只认 'UP'/'DOWN' 原值，不做 trim（带空格的枚举值不是有效值，不猜） */
export function normalizeDirection(value) {
  return value === DRAFT_DIRECTIONS.UP || value === DRAFT_DIRECTIONS.DOWN ? value : null
}

/** 单条建议归一化：只有 title+action 齐备才算可用条目（缺了就丢掉，不补文案） */
function normalizeSuggestion(value) {
  if (!isRecord(value)) return null
  const title = draftText(value.title)
  const action = draftText(value.action)
  if (!title || !action) return null
  return { index: 0, title, action, targetMetricCode: draftText(value.targetMetricCode) }
}

/** 后端 suggestions（可能为 null）→ 页面可用条目；index 保留原数组下标，便于回指证据 */
export function draftSuggestions(list) {
  const out = []
  const arr = Array.isArray(list) ? list : []
  arr.forEach((item, index) => {
    const s = normalizeSuggestion(item)
    if (s) out.push({ ...s, index })
  })
  return out
}

/**
 * 证据锚点：证据包 ID 优先，其次真实快照号；两者皆无 → NONE。
 * ID 不先经过 draftText：真实标识必须逐字来自后端，不能因为前端 trim 后“看起来合法”而被接受。
 */
export function draftAnchor({ evidenceId, snapshotId } = {}) {
  if (isRealEvidenceId(evidenceId)) {
    return { kind: ANCHOR_KIND.EVIDENCE_PACKAGE, value: evidenceId }
  }
  if (isRealSnapshotId(snapshotId)) {
    return { kind: ANCHOR_KIND.SUGGESTION_SNAPSHOT, value: snapshotId }
  }
  return { kind: ANCHOR_KIND.NONE, value: null }
}

/** 锚点的页面文案：无锚点必须写明「不可创建」，不允许静默留空 */
export function anchorText(anchor) {
  const a = isRecord(anchor) ? anchor : { kind: ANCHOR_KIND.NONE, value: null }
  if (a.kind === ANCHOR_KIND.EVIDENCE_PACKAGE) return `evidence_package:${a.value}`
  if (a.kind === ANCHOR_KIND.SUGGESTION_SNAPSHOT) return `suggestion_snapshot:${a.value}`
  return '无可用锚点（本次问答既没有证据包 ID 也没有真实快照号，不可创建草稿）'
}

/**
 * 装请求体。
 *
 * @returns {{ ok: boolean, body: object|null, blocked: string|null, anchor: object }}
 *   `ok=false` 时 `body=null`，`blocked` 取 {@link DRAFT_BLOCK} 之一。
 */
export function buildDraftBody({ suggestion, evidenceId, snapshotId, direction, owner } = {}) {
  const s = normalizeSuggestion(suggestion)
  const anchor = draftAnchor({ evidenceId, snapshotId })
  if (!s) return { ok: false, body: null, blocked: DRAFT_BLOCK.SUGGESTION_INCOMPLETE, anchor }
  if (anchor.kind === ANCHOR_KIND.NONE) return { ok: false, body: null, blocked: DRAFT_BLOCK.NO_EVIDENCE_ANCHOR, anchor }

  const body = { title: s.title, action: s.action }
  if (s.targetMetricCode) body.targetMetricCode = s.targetMetricCode
  const dir = normalizeDirection(direction)
  if (dir) body.targetDirection = dir
  // 锚点只能二选一：同一请求里不得同时提交证据包 ID 与快照锚点
  if (anchor.kind === ANCHOR_KIND.EVIDENCE_PACKAGE) body.evidencePackageId = anchor.value
  else body.suggestionSnapshotId = anchor.value
  const o = draftText(owner)
  if (o) body.owner = o

  return { ok: true, body, blocked: null, anchor }
}
