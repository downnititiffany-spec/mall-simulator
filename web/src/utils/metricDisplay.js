// 概览「清单外指标」的量纲/展示名登记表 —— **唯一属主**（契约文首 v1.10「展示义务」）。
//
// 依据：契约 §1 总原则 4「响应是图表语义数据…比值同时给 decimal 与单位/百分号语义」；
//      指导书 V3.0 §8 阶段5 L201「员工能完成主要操作并理解结果；页面不混源/混快照；无数据不造数」。
//
// 边界（不得越界）：
//   1. 本表**只**回答「怎么显示」（量纲 + 展示名），**不**回答「怎么算」——口径公式的唯一 owner
//      仍是 `analytics_meta.metric_definition`（页面「查看指标口径」面板直接展示字典行）。
//   2. 未登记的码**照实展示** `formatNumber(v, 2)`：不猜量纲、不擅自把 decimal 当百分数放大。
//   3. 展示名**字典优先**：本表只在字典缺该码时兜底（已发布 `V2__platform_pipeline_quality.sql:63-78`
//      的 `metric_definition` 种子里 `full_refund_rate`/`fav_cnt`/`cart_add_cnt` **无行**，故标题此前
//      回退成裸码）。补字典行属**加性迁移**，需另行裁决（已发布 V2 迁移不得改）。
//   4. 固定卡片（`Overview.vue` 的 `CARD_META`，9 个码）的顺序与量纲**仍由 CARD_META 决定**，
//      两者的码集合**不得重叠**（`web/tests/metricDisplay.test.js` 与
//      `web/tests/overviewOutsideMetric.test.js` 分别钉住「全集＝发布侧 14 码」与「交集为空」）。
//   5. 格式化函数**复用** `utils/number.js`（比例/整数/小数三个 owner 已在那里），本文件不重复实现。
import { formatInteger, formatNumber, formatPercent } from './number.js'

export const METRIC_KIND_PERCENT = 'percent'
export const METRIC_KIND_INTEGER = 'integer'

/** 清单外指标的量纲登记表（比例型 3 个 ＋ 计数型 2 个） */
export const OUTSIDE_METRIC_KINDS = Object.freeze({
  cart_rate: METRIC_KIND_PERCENT, // 加购率＝加购用户数 ÷ 浏览用户数（metric-dictionary.md:23）
  buy_rate: METRIC_KIND_PERCENT, // 购买转化率＝支付用户数 ÷ 浏览用户数（metric-dictionary.md:24）
  full_refund_rate: METRIC_KIND_PERCENT, // 全额退款率（字典种子无行 ⇒ 口径未登记，但量纲仍是 decimal 比例）
  fav_cnt: METRIC_KIND_INTEGER, // 收藏次数（metric-dictionary.md:21）
  cart_add_cnt: METRIC_KIND_INTEGER // 加购次数（metric-dictionary.md:22）
})

/** 展示名兜底表：**仅当**后端字典缺该码时使用 */
export const OUTSIDE_METRIC_NAMES = Object.freeze({
  full_refund_rate: '全额退款率',
  fav_cnt: '收藏次数',
  cart_add_cnt: '加购次数'
})

/** 字典缺该码时的限制说明（视图不得另写字面量） */
export const DICTIONARY_MISSING_NOTE = '字典未登记口径'

/**
 * 量纲查询：登记表命中返回 `'percent'` / `'integer'`，未登记返回 `null`（**不猜**）。
 * @param {unknown} metricCode 指标码（来自后端 `metrics[*].metricCode`）
 */
export function outsideMetricKind(metricCode) {
  if (typeof metricCode !== 'string' || metricCode === '') return null
  return OUTSIDE_METRIC_KINDS[metricCode] || null
}

/**
 * 展示名三级回退：字典名 → 登记表兜底 → 指标码。
 * @param {unknown} metricCode 指标码
 * @param {unknown} dictionaryName 后端字典给的 `metricName`（可为空串/null）
 */
export function displayMetricName(metricCode, dictionaryName) {
  if (typeof dictionaryName === 'string' && dictionaryName !== '') return dictionaryName
  const code = typeof metricCode === 'string' ? metricCode : ''
  return OUTSIDE_METRIC_NAMES[code] || code
}

/**
 * 清单外指标的数值文本：登记表命中按量纲格式化，未登记照实按两位小数展示（不猜、不放大）。
 * 空值一律占位符 `—`（`number.js` 约定：空值不得显示成 0）。
 * @param {unknown} metricCode 指标码
 * @param {unknown} value 后端 `metric_value` 原值（**前端不重算**）
 */
export function formatOutsideMetricValue(metricCode, value) {
  const kind = outsideMetricKind(metricCode)
  if (kind === METRIC_KIND_PERCENT) return formatPercent(value)
  if (kind === METRIC_KIND_INTEGER) return formatInteger(value)
  return formatNumber(value, 2)
}
