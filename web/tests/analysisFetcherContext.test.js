// 分析页业务来源 ID 透传与 fetcher 上下文字段完整性守护（U02-S01）
// 依据：《现有功能完善实施指导书 V1.0》§5 U02、问题 UP-20261018-09（P1）、UP-20261018-10（P1）、UP-20261018-13（P2/测试空白）
// 规约：mall-simulator-notes/U02-S01.md
//
// 说明：本测试为源码结构守卫（web 未装 SFC 编译器，fetcher 为 SFC 内未导出函数）。
// 断言作用域严格限定在对应 fetcher 函数文本片段内（先截取函数片段再断言），杜绝无锚点宽泛匹配。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const read = (rel) => readFileSync(fileURLToPath(new URL(rel, import.meta.url)), 'utf8')

const behaviorSource = read('../src/views/Behavior.vue')
const rfmSource = read('../src/views/Rfm.vue')
const overviewSource = read('../src/views/Overview.vue')
const salesSource = read('../src/views/Sales.vue')
const productsSource = read('../src/views/Products.vue')

function extractFetcherSnippet(source, functionName) {
  const start = source.indexOf(`async function ${functionName}`)
  assert.ok(start !== -1, `${functionName} 必须在源码中以 async function 形式声明`)
  const end = source.indexOf('const analysis = useAnalysis', start)
  assert.ok(end !== -1, `${functionName} 之后必须紧跟 useAnalysis 初始化`)
  return source.slice(start, end)
}

function extractReturnObjectSnippet(fetcherSnippet) {
  const returnIdx = fetcherSnippet.indexOf('return {')
  assert.ok(returnIdx !== -1, 'fetcher 函数内必须包含 return {')
  return fetcherSnippet.slice(returnIdx)
}

function extractUseAnalysisFetcher(source, viewName) {
  const useAnalysisStart = source.indexOf('useAnalysis({')
  assert.ok(useAnalysisStart !== -1, `${viewName} 必须声明 useAnalysis({`)
  const fetcherStart = source.indexOf('fetcher:', useAnalysisStart)
  assert.ok(fetcherStart !== -1, `${viewName} useAnalysis 必须包含 fetcher 配置项`)
  const rowKeysStart = source.indexOf('rowKeys:', fetcherStart)
  assert.ok(rowKeysStart !== -1, `${viewName} useAnalysis fetcher 后必须跟随 rowKeys 配置项`)
  return source.slice(fetcherStart, rowKeysStart)
}

// -----------------------------------------------------------------------------
// 1. Behavior.vue (UP-20261018-09)
// -----------------------------------------------------------------------------
test('Behavior.vue fetchBehavior 透传 sourceId（主备回退）且字段位置紧随 snapshotId 之后', () => {
  const fetcher = extractFetcherSnippet(behaviorSource, 'fetchBehavior')
  const returnObj = extractReturnObjectSnippet(fetcher)

  // 必须透传 sourceId: funnel.sourceId || overview.sourceId
  assert.match(returnObj, /sourceId:\s*funnel\.sourceId\s*\|\|\s*overview\.sourceId,/)

  // 必须紧跟 snapshotId 之后、businessTime 之前
  assert.match(
    returnObj,
    /snapshotId:\s*funnel\.snapshotId,\s*\n\s*sourceId:\s*funnel\.sourceId\s*\|\|\s*overview\.sourceId,\s*\n\s*businessTime:/
  )

  // 同对象其余既有上下文字段结构逐字完好
  assert.match(returnObj, /snapshotId:\s*funnel\.snapshotId,/)
  assert.match(returnObj, /businessTime:\s*funnel\.businessTime\s*\|\|\s*overview\.businessTime,/)
  assert.match(returnObj, /dataUpdatedAt:\s*funnel\.dataUpdatedAt\s*\|\|\s*overview\.dataUpdatedAt,/)
  assert.match(returnObj, /source:\s*funnel\.source\s*\|\|\s*overview\.source,/)
  assert.match(returnObj, /definitionVersion:\s*funnel\.definitionVersion\s*\|\|\s*overview\.definitionVersion,/)
})

test('Behavior.vue fetchBehavior 不存在写死字面量或从 params 补造 sourceId', () => {
  const fetcher = extractFetcherSnippet(behaviorSource, 'fetchBehavior')
  assert.doesNotMatch(fetcher, /sourceId:\s*\d+/, '禁止在 fetchBehavior 返回中写死数字字面量 sourceId')
  assert.doesNotMatch(fetcher, /sourceId:\s*params\./, '禁止从请求参数 params 中补造 sourceId')
})

// -----------------------------------------------------------------------------
// 2. Rfm.vue (UP-20261018-10)
// -----------------------------------------------------------------------------
test('Rfm.vue fetchRfm 透传 sourceId 且字段位置紧随 snapshotId 之后', () => {
  const fetcher = extractFetcherSnippet(rfmSource, 'fetchRfm')
  const returnObj = extractReturnObjectSnippet(fetcher)

  // 必须透传 sourceId: rfm.sourceId
  assert.match(returnObj, /sourceId:\s*rfm\.sourceId,/)

  // 必须紧跟 snapshotId 之后、businessTime 之前
  assert.match(
    returnObj,
    /snapshotId:\s*rfm\.snapshotId,\s*\n\s*sourceId:\s*rfm\.sourceId,\s*\n\s*businessTime:/
  )

  // 同对象其余既有上下文字段结构逐字完好
  assert.match(returnObj, /snapshotId:\s*rfm\.snapshotId,/)
  assert.match(returnObj, /businessTime:\s*rfm\.businessTime,/)
  assert.match(returnObj, /dataUpdatedAt:\s*rfm\.dataUpdatedAt,/)
  assert.match(returnObj, /source:\s*rfm\.source,/)
  assert.match(returnObj, /definitionVersion:\s*rfm\.definitionVersion,/)
})

test('Rfm.vue fetchRfm 不存在写死字面量、users 信封覆盖或从 params 补造 sourceId', () => {
  const fetcher = extractFetcherSnippet(rfmSource, 'fetchRfm')
  assert.doesNotMatch(fetcher, /sourceId:\s*\d+/, '禁止在 fetchRfm 返回中写死数字字面量 sourceId')
  assert.doesNotMatch(fetcher, /sourceId:\s*users\./, '禁止用 users 信封覆盖主响应 rfm 的 sourceId')
  assert.doesNotMatch(fetcher, /sourceId:\s*params\./, '禁止从请求参数 params 中补造 sourceId')
})

// -----------------------------------------------------------------------------
// 3. 五页完整性守护：Overview.vue / Sales.vue / Products.vue (UP-20261018-13)
// -----------------------------------------------------------------------------
test('Overview.vue useAnalysis fetcher 为直通信封形态，不存在手工拼装 businessTime 字段', () => {
  const fetcherSnippet = extractUseAnalysisFetcher(overviewSource, 'Overview.vue')
  assert.match(
    fetcherSnippet,
    /fetcher:\s*async\s*\(params,\s*signal\)\s*=>\s*requireAnalysisDataResponse\(\s*await\s*api\.overview\(params,\s*\{\s*signal\s*\}\)/
  )
  assert.doesNotMatch(fetcherSnippet, /businessTime:/, 'Overview fetcher 不得手工拼装 businessTime')
  assert.doesNotMatch(fetcherSnippet, /sourceId:/, 'Overview fetcher 不得手工拼装 sourceId')
  assert.doesNotMatch(overviewSource, /businessTime:/, 'Overview.vue 全文不得包含手工拼装 businessTime 字段')
})

test('Sales.vue useAnalysis fetcher 为直通信封形态，不存在手工拼装 businessTime 字段', () => {
  const fetcherSnippet = extractUseAnalysisFetcher(salesSource, 'Sales.vue')
  assert.match(
    fetcherSnippet,
    /fetcher:\s*async\s*\(params,\s*signal\)\s*=>\s*requireAnalysisDataResponse\(\s*await\s*api\.sales\(params,\s*\{\s*signal\s*\}\)/
  )
  assert.doesNotMatch(fetcherSnippet, /businessTime:/, 'Sales fetcher 不得手工拼装 businessTime')
  assert.doesNotMatch(fetcherSnippet, /sourceId:/, 'Sales fetcher 不得手工拼装 sourceId')
  assert.doesNotMatch(salesSource, /businessTime:/, 'Sales.vue 全文不得包含手工拼装 businessTime 字段')
})

test('Products.vue useAnalysis fetcher 为直通信封形态，不存在手工拼装 businessTime 字段', () => {
  const fetcherSnippet = extractUseAnalysisFetcher(productsSource, 'Products.vue')
  assert.match(
    fetcherSnippet,
    /fetcher:\s*async\s*\(params,\s*signal\)\s*=>\s*requireProductsPageResponse\(\s*await\s*api\.products\(params,\s*\{\s*signal\s*\}\)\)/
  )
  assert.doesNotMatch(fetcherSnippet, /businessTime:/, 'Products fetcher 不得手工拼装 businessTime')
  assert.doesNotMatch(fetcherSnippet, /sourceId:/, 'Products fetcher 不得手工拼装 sourceId')
  assert.doesNotMatch(productsSource, /businessTime:/, 'Products.vue 全文不得包含手工拼装 businessTime 字段')
})

test('五页分析体系完整性：直通三页无手工 businessTime，拼装两页严格透传 sourceId', () => {
  const passThroughViews = [
    { name: 'Overview.vue', src: overviewSource },
    { name: 'Sales.vue', src: salesSource },
    { name: 'Products.vue', src: productsSource }
  ]
  for (const { name, src } of passThroughViews) {
    const fetcherSnippet = extractUseAnalysisFetcher(src, name)
    assert.doesNotMatch(fetcherSnippet, /businessTime:/, `${name} fetcher 不应有手工拼装 businessTime`)
    assert.doesNotMatch(src, /businessTime:/, `${name} 全文不应有手工拼装 businessTime`)
  }

  const assembledViews = [
    { name: 'Behavior.vue', src: behaviorSource, fn: 'fetchBehavior', expected: /sourceId:\s*funnel\.sourceId\s*\|\|\s*overview\.sourceId,/ },
    { name: 'Rfm.vue', src: rfmSource, fn: 'fetchRfm', expected: /sourceId:\s*rfm\.sourceId,/ }
  ]
  for (const { name, src, fn, expected } of assembledViews) {
    const fetcherSnippet = extractFetcherSnippet(src, fn)
    const returnObj = extractReturnObjectSnippet(fetcherSnippet)
    assert.match(returnObj, expected, `${name} fetcher 必须包含正确的 sourceId 透传`)
  }
})
