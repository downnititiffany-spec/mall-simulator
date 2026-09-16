// CSV 导出上下文测试（指导书 §18.4：导出必须携带 filters、snapshotId、生成时间）
import test from 'node:test'
import assert from 'node:assert/strict'
import {
  buildCsvText,
  buildExportFilename,
  buildContextRows,
  formatFilters,
  compactTimestamp,
  escapeCsvCell
} from '../src/utils/csv.js'

const CONTEXT = {
  snapshotId: 'S20260901_24',
  businessTime: '2026-09-01T00:00:00',
  dataUpdatedAt: '2026-09-10T20:12:33',
  source: 'spark-ads',
  definitionVersion: 'v2',
  qualityStatus: 'PASS',
  filters: { from: '2026-09-01', to: '2026-09-01', snapshotId: 'S20260901_24' },
  warnings: ['UNKNOWN_DIMENSION_TABLE']
}
const GENERATED_AT = '2026-09-11T09:30:00'

test('CSV 文本包含 snapshotId、filters 与生成时间', () => {
  const text = buildCsvText({
    context: CONTEXT,
    generatedAt: GENERATED_AT,
    headers: ['日期', '订单数'],
    rows: [['2026-09-01', 5]]
  })
  assert.match(text, /# 快照ID,S20260901_24/)
  assert.match(text, /# 生成时间,2026-09-11 09:30:00/)
  assert.match(text, /# 生效筛选,from=2026-09-01;snapshotId=S20260901_24;to=2026-09-01/)
  assert.match(text, /# 业务时间,2026-09-01 00:00:00/)
  assert.match(text, /# 数据更新时间,2026-09-10 20:12:33/)
  assert.match(text, /# 来源（发布方）,spark-ads/)
  assert.match(text, /# 口径版本,v2/)
  assert.match(text, /# 质量状态,PASS/)
  assert.match(text, /# 告警,UNKNOWN_DIMENSION_TABLE/)
  // 元信息之后是表头与数据行
  assert.match(text, /\n\n日期,订单数\n2026-09-01,5$/)
})

test('文件名带快照与生成时间（文件名安全字符集）', () => {
  const name = buildExportFilename({ baseName: 'sales-analysis', context: CONTEXT, generatedAt: GENERATED_AT })
  assert.equal(name, 'sales-analysis-S20260901_24-20260911-093000.csv')
  assert.equal(/[\\/:*?"<>|\s]/.test(name), false)
})

test('无快照时文件名与元信息给出显式缺失标记，不留空', () => {
  const name = buildExportFilename({ baseName: 'rfm-segments', context: {}, generatedAt: GENERATED_AT })
  assert.equal(name, 'rfm-segments-no-snapshot-20260911-093000.csv')
  const rows = buildContextRows({}, GENERATED_AT)
  assert.equal(rows[0][1], '（缺失）')
  // S3-26 起「来源（发布方）」插在「数据更新时间」之后：既有行标签不变，仅行号后移一位
  assert.equal(rows[3][0], '# 来源（发布方）')
  assert.equal(rows[3][1], '（缺失）')
  assert.equal(rows[6][0], '# 生效筛选')
  assert.equal(rows[6][1], '（无筛选）')
})

test('导出元信息携带来源（发布方），与快照/口径版本同源可比对', () => {
  const rows = buildContextRows(CONTEXT, GENERATED_AT)
  const labels = rows.map((r) => r[0])
  assert.equal(labels.includes('# 来源（发布方）'), true)
  const row = rows.find((r) => r[0] === '# 来源（发布方）')
  assert.equal(row[1], 'spark-ads')
  // 发布方在快照、业务时间、数据更新时间之后，便于导出件自证「数据来自哪次快照、谁发布的」
  assert.equal(labels.indexOf('# 来源（发布方）'), 3)
  assert.equal(labels[0], '# 快照ID')
})

test('filters 键排序稳定，输出可重复', () => {
  assert.equal(formatFilters({ to: 'b', from: 'a' }), 'from=a;to=b')
  assert.equal(formatFilters(null), '（无筛选）')
  assert.equal(formatFilters({}), '（无筛选）')
  assert.equal(formatFilters({ topN: 10 }), 'topN=10')
})

test('CSV 转义：逗号/引号/换行按 RFC4180 加引号', () => {
  assert.equal(escapeCsvCell('a,b'), '"a,b"')
  assert.equal(escapeCsvCell('说"明"'), '"说""明"""')
  assert.equal(escapeCsvCell('第一行\n第二行'), '"第一行\n第二行"')
  assert.equal(escapeCsvCell('引号"无逗号'), '"引号""无逗号"')
  assert.equal(escapeCsvCell(null), '')
  const text = buildCsvText({ context: {}, generatedAt: GENERATED_AT, headers: ['商品'], rows: [['A,B']] })
  assert.match(text, /"A,B"/)
})

test('compactTimestamp 对非法时间不抛异常', () => {
  assert.equal(compactTimestamp('2026-09-11T09:30:00'), '20260911-093000')
  assert.equal(compactTimestamp(null), 'unknown')
})

test('空行集合也能生成合法 CSV（仅元信息与表头）', () => {
  const text = buildCsvText({ context: CONTEXT, generatedAt: GENERATED_AT, headers: ['阶段'], rows: [] })
  assert.equal(text.endsWith('\n阶段'), true)
})
