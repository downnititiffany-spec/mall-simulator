// 分析响应 `data.quality` 的展示文案统一属主（S3-28）。
// 口径来源：`docs/contracts/analysis-viewmodel-r7-4.md` v1.6（字段与键集语义）＋ v1.8（读侧消费）。
// 键集语义（不得越界）：`ruleVersions` 的键集是 `ruleCount` 的**子集** ——
// 不出现的规则码 ＝ 该行 `rule_version` 为 NULL 或不可解析，**不补 0、不冒充 v1**。
import { formatInteger } from './number.js'

/** 版本键集限制说明（单一常量，两个视图共用，禁止各写一份） */
export const RULE_VERSION_NOTE =
  '规则版本取快照质量行的 rule_version 原样值；未列出的规则码＝该行未记录版本（NULL 或不可解析），' +
  '页面不补 0、不冒充 v1（键集是规则总数的子集）。'

/** 「规则 3/4 通过，失败规则：A_B、C_D」——原 Sales.vue 内联逻辑的唯一属主 */
export function qualitySummaryText(quality = {}) {
  const q = quality || {}
  const ruleCount = formatInteger(q.ruleCount, '—')
  const passedCount = formatInteger(q.passedCount, '—')
  const failed = Array.isArray(q.failedRules) && q.failedRules.length ? q.failedRules.join('、') : '无'
  return `规则 ${passedCount}/${ruleCount} 通过，失败规则：${failed}`
}

/**
 * 规则版本列表：`规则码=v版本`，按规则码升序稳定输出（与后端 `TreeMap` 同序）。
 * 无任何版本记录 ⇒ 「无版本记录」（不显示空白、不显示 v1）；
 * 键存在但值不可解析 ⇒ 该码标「未记录版本」（不臆造版本号）。
 */
export function ruleVersionText(ruleVersions) {
  const map = ruleVersions && typeof ruleVersions === 'object' ? ruleVersions : {}
  const codes = Object.keys(map).sort()
  if (!codes.length) return '无版本记录'
  return codes
    .map((code) => {
      const raw = map[code]
      const n = raw === null || raw === undefined || raw === '' ? null : Number(raw)
      return Number.isFinite(n) ? `${code}=v${n}` : `${code}=未记录版本`
    })
    .join('、')
}
