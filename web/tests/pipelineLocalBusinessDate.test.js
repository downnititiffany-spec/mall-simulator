import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

import { localIsoDay } from '../src/utils/localDate.js'

const here = path.dirname(fileURLToPath(import.meta.url))
const pipelineSource = fs.readFileSync(path.join(here, '../src/views/Pipeline.vue'), 'utf8')

test('localIsoDay 按本地日历字段组装 YYYY-MM-DD，不经 UTC toISOString', () => {
  const fakeLocalDate = {
    getFullYear: () => 2026,
    getMonth: () => 8,
    getDate: () => 7
  }
  assert.equal(localIsoDay(fakeLocalDate), '2026-09-07')
})

test('Pipeline 默认业务日使用本地日历 helper，不能退回 UTC 日期截断', () => {
  assert.match(pipelineSource, /import \{ localIsoDay \} from '\.\.\/utils\/localDate\.js'/)
  assert.match(pipelineSource, /const businessDate = ref\(localIsoDay\(\)\)/)
  assert.doesNotMatch(pipelineSource, /toISOString\(\)\.slice\(0,\s*10\)/)
})

test('Pipeline 仍把用户确认的业务日原样组成本地午夜 businessTime', () => {
  assert.match(pipelineSource, /businessTime: businessDate\.value \+ 'T00:00:00'/)
})
