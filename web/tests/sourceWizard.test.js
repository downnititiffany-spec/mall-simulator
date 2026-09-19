// 接入向导（02.5）单测：纯逻辑模块 + 关键源码文本断言（node --test 范式，见 exportFreshnessGuard.test.js）。
// 验收锚点（指导书 V3.1 02.5）：选源→受控样本→预览错误/覆盖率→确认激活；不要求编辑服务器文件。
import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  PRESET_SAMPLE_REFS, canPreview, validateProfileText, formatCoverage,
  shortChecksum, summarizeReport, reasonCountRows, activationErrorMessage, summarizeActivation
} from '../src/utils/sourceWizard.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const wizardVue = fs.readFileSync(path.resolve(here, '../src/views/SourceWizard.vue'), 'utf8')
const apiSource = fs.readFileSync(path.resolve(here, '../src/api.js'), 'utf8')
const routerSource = fs.readFileSync(path.resolve(here, '../src/router.js'), 'utf8')
const appSource = fs.readFileSync(path.resolve(here, '../src/App.vue'), 'utf8')

// ---------- 预览门（canPreview） ----------

test('未选源/未给样本/未粘贴画像/limit 越界时禁止预览并说明原因', () => {
  assert.equal(canPreview({}).ok, false)
  assert.match(canPreview({}).reason, /选择/)
  assert.equal(canPreview({ sourceId: 2 }).ok, false)
  assert.match(canPreview({ sourceId: 2 }).reason, /样本/)
  assert.equal(canPreview({ sourceId: 2, sampleRef: 'fixture-shop-b/normal.jsonl' }).ok, false)
  assert.match(canPreview({ sourceId: 2, sampleRef: 'fixture-shop-b/normal.jsonl' }).reason, /画像/)
  assert.equal(
    canPreview({ sourceId: 2, sampleRef: 'fixture-shop-b/normal.jsonl', profileText: '{}', limit: 0 }).ok,
    false
  )
  assert.equal(
    canPreview({ sourceId: 2, sampleRef: 'fixture-shop-b/normal.jsonl', profileText: '{}', limit: 101 }).ok,
    false
  )
  assert.equal(
    canPreview({ sourceId: 2, sampleRef: 'fixture-shop-b/normal.jsonl', profileText: '{}', limit: 20 }).ok,
    true
  )
})

test('手动样本引用拒绝绝对路径/.. /协议前缀（任意服务器路径不放行，边界在服务端但客户端先拦）', () => {
  for (const bad of ['/etc/passwd', 'a/../b.jsonl', 'file:///x', 'http://x/y']) {
    const r = canPreview({ sourceId: 2, sampleRef: bad, profileText: '{}', limit: 10 })
    assert.equal(r.ok, false, bad)
    assert.match(r.reason, /相对路径/, bad)
  }
})

// ---------- 画像原文 advisory 校验 ----------

test('validateProfileText 只做形状初检：JSON/对象/fieldMappings，不复制 Loader 语义', () => {
  assert.equal(validateProfileText('').ok, false)
  assert.equal(validateProfileText('not json').ok, false)
  assert.equal(validateProfileText('[]').ok, false)
  assert.equal(validateProfileText('{"a":1}').ok, false)
  assert.match(validateProfileText('{"a":1}').error, /fieldMappings/)
  assert.equal(validateProfileText('{"fieldMappings":{"event_type":"$.type"}}').ok, true)
})

// ---------- 报告摘要（只引用 MappingDryRunReport 真实字段） ----------

const REPORT = {
  reportId: 'dr-1', profileVersion: '2.0', profileSyntax: 'V2_STRICT',
  profileChecksum: 'a'.repeat(64), profileAccepted: true,
  sampleRef: 'fixture-shop-b/normal.jsonl',
  processedCount: 38, acceptedCount: 38, quarantinedCount: 0, systemErrors: [],
  requiredCoverage: 1.0, enumCoverage: null,
  reasonCounts: { EMPTY_FIELD: 3 }, activationBlocks: [], capabilityGaps: [],
  activationIneligibleReasons: [], activationEligible: true
}

test('summarizeReport 折叠报告为固定顺序展示行，覆盖率为 null 时显示「未观察到」不写 0%', () => {
  const s = summarizeReport(REPORT)
  assert.equal(s.eligible, true)
  assert.deepEqual(s.rows.map((r) => r.label), [
    '画像版本', '画像语法', 'profileChecksum', '画像装载', '处理行数',
    '接受 / 隔离', '系统异常', '必填覆盖率', '枚举覆盖率', '样本引用'
  ])
  const byLabel = Object.fromEntries(s.rows.map((r) => [r.label, r.value]))
  assert.equal(byLabel['处理行数'], '38')
  assert.equal(byLabel['接受 / 隔离'], '38 / 0')
  assert.equal(byLabel['必填覆盖率'], '100.0%')
  assert.equal(byLabel['枚举覆盖率'], '未观察到')
  assert.equal(byLabel['profileChecksum'], 'a'.repeat(12))
})

test('summarizeReport 对不可激活报告携带原因/阻断/缺口清单', () => {
  const s = summarizeReport({ ...REPORT, activationEligible: false,
    activationIneligibleReasons: ['存在违例行'], capabilityGaps: ['identityPolicy 未执行'] })
  assert.equal(s.eligible, false)
  assert.deepEqual(s.ineligibleReasons, ['存在违例行'])
  assert.deepEqual(s.gaps, ['identityPolicy 未执行'])
})

test('reasonCountRows 输出排序稳定的非零计数行', () => {
  assert.deepEqual(reasonCountRows(REPORT), [{ reason: 'EMPTY_FIELD', count: 3 }])
  assert.deepEqual(reasonCountRows(null), [])
})

// ---------- 激活结果与错误话术 ----------

test('activationErrorMessage: 409/MAPPING_PROFILE_CHANGED 指回重新预览；404 指报告过期；403 指权限；未知透传', () => {
  const mk = (status, code, message) => ({ response: { status, data: { code, message } } })
  assert.match(activationErrorMessage(mk(409, 'MAPPING_PROFILE_CHANGED', 'x')), /重新预览/)
  assert.match(activationErrorMessage(mk(409, 'MAPPING_REPORT_NOT_ACTIVATABLE', 'x')), /409/)
  assert.match(activationErrorMessage(mk(404, 'MAPPING_REPORT_NOT_FOUND', 'x')), /过期|不存在/)
  assert.match(activationErrorMessage(mk(403, 'FORBIDDEN', 'x')), /RUNTIME_MANAGE/)
  assert.equal(activationErrorMessage(mk(400, 'X', '原始消息')), '原始消息')
  assert.match(activationErrorMessage(new Error('boom')), /boom/)
})

test('summarizeActivation 展示幂等（changed=false）与替换原指针', () => {
  const rows = Object.fromEntries(
    summarizeActivation({
      changed: false, profileVersion: '2.0', profileChecksum: 'b'.repeat(64),
      contractVersion: '1.0', reportId: 'dr-1', previousProfileChecksum: 'c'.repeat(64)
    }).map((r) => [r.label, r.value])
  )
  assert.match(rows['是否变更指针'], /幂等/)
  assert.equal(rows['原指针画像'], 'c'.repeat(12))
  const first = Object.fromEntries(
    summarizeActivation({ changed: true, profileVersion: '1.0', profileChecksum: 'b'.repeat(64) })
      .map((r) => [r.label, r.value])
  )
  assert.match(first['原指针画像'], /首次激活/)
})

test('formatCoverage/shortChecksum 边界', () => {
  assert.equal(formatCoverage(null), '未观察到')
  assert.equal(formatCoverage(undefined), '未观察到')
  assert.equal(formatCoverage(0), '0.0%')
  // JS 浮点：0.8765*100 = 87.64999…，toFixed(1) 得 87.6%；展示层按浮点结果呈现，报告保留原值
  assert.equal(formatCoverage(0.8765), '87.6%')
  assert.equal(shortChecksum(null), '—')
})

// ---------- 源码文本断言：页面/路由/API/导航接线 ----------

test('SourceWizard.vue 具备四步结构与零服务器文件编辑语义', () => {
  for (const anchor of [
    '第 1 步', '第 2 步', '第 3 步', '第 4 步',
    '不要求编辑服务器文件', '预置受控样本', 'advisory preflight',
    'MAPPING_PROFILE_CHANGED'
  ]) {
    assert.ok(wizardVue.includes(anchor), `缺少锚点: ${anchor}`)
  }
})

test('向导激活顺序先映射后源；②在①成功前禁用', () => {
  const idxMapping = wizardVue.indexOf('activateMapping')
  const idxSource = wizardVue.indexOf('activateSource')
  assert.ok(idxMapping > 0 && idxSource > idxMapping)
  assert.match(wizardVue, /:disabled="!mappingDone/)
})

test('api.js 暴露向导所需 4 个既有端点封装；router/App 接线为 admin 专属', () => {
  for (const anchor of ['sources:', 'sourceActivate:', 'mappingDryRun:', 'mappingActivate:']) {
    assert.ok(apiSource.includes(anchor), `api.js 缺少 ${anchor}`)
  }
  assert.match(routerSource, /\/sources\/wizard/)
  assert.match(appSource, /'\/sources\/wizard'/)
})

test('预置样本引用清单非空且全部为仓库相对路径', () => {
  assert.ok(PRESET_SAMPLE_REFS.length >= 3)
  for (const p of PRESET_SAMPLE_REFS) {
    assert.ok(!p.ref.startsWith('/'))
    assert.ok(!p.ref.includes('..'))
    assert.ok(!/^[a-zA-Z]+:/.test(p.ref))
  }
})

test('预置选择回填输入框（覆盖旧手输引用）；②按调用前 current 区分幂等与真切换', () => {
  // 预置回填：effectiveSampleRef 是「手输 || 预置」，若无回填，先手输再选预置会静默沿用旧引用
  assert.match(wizardVue, /watch\(presetRef, \(v\) => \{ if \(v\) sampleRefInput\.value = v \}\)/)
  // 激活响应里的 current 恒为 true（切换后视图），不能用来源区分幂等——必须取调用前状态
  assert.match(wizardVue, /wasCurrentAtActivate\.value = !!\(sources\.value \|\| \[\]\)\.find/)
  assert.match(wizardVue, /sourceResult\.current && wasCurrentAtActivate/)
  assert.match(wizardVue, /已切换为当前运行环境绑定的源/)
})
