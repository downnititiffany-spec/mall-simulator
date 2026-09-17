import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { localIsoDay, localIsoDayOffset } from '../src/utils/localDate.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const readView = (name) => fs.readFileSync(path.join(here, `../src/views/${name}.vue`), 'utf8')
const pipelineSource = readView('Pipeline')
const behaviorSource = readView('Behavior')
const salesSource = readView('Sales')
const overviewSource = readView('Overview')

test('localIsoDay 按本地日历字段组装 YYYY-MM-DD，不经 UTC toISOString', () => {
  const fakeLocalDate = {
    getFullYear: () => 2026,
    getMonth: () => 8,
    getDate: () => 7
  }
  assert.equal(localIsoDay(fakeLocalDate), '2026-09-07')
})

test('localIsoDayOffset 按本地日历加减天数并处理跨月', () => {
  const localNoon = new Date(2026, 8, 2, 12, 0, 0)
  assert.equal(localIsoDayOffset(0, localNoon), '2026-09-02')
  assert.equal(localIsoDayOffset(-6, localNoon), '2026-08-27')
  assert.equal(localIsoDayOffset(30, localNoon), '2026-10-02')
})

test('Pipeline 默认业务日使用本地日历 helper，不能退回 UTC 日期截断', () => {
  assert.match(pipelineSource, /import \{ localIsoDay \} from '\.\.\/utils\/localDate\.js'/)
  assert.match(pipelineSource, /const businessDate = ref\(localIsoDay\(\)\)/)
  assert.doesNotMatch(pipelineSource, /toISOString\(\)\.slice\(0,\s*10\)/)
})

test('分析页默认近 7 天范围共用本地日历 helper，不再各自从 UTC instant 截日期', () => {
  for (const source of [behaviorSource, salesSource, overviewSource]) {
    assert.match(source, /import \{ localIsoDayOffset \} from '\.\.\/utils\/localDate\.js'/)
    assert.match(source, /const from = ref\(localIsoDayOffset\(-6\)\)/)
    assert.match(source, /const to = ref\(localIsoDayOffset\(0\)\)/)
    assert.doesNotMatch(source, /toISOString\(\)\.slice\(0,\s*10\)/)
  }
})

test('Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime', () => {
  assert.match(pipelineSource, /businessTime: businessDate\.value \+ 'T00:00:00'/)
})
