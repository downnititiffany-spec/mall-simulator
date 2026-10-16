import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { requireArrayResponse } from '../src/utils/arrayResponse.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

test('裸数组响应接受空数组与数据数组，拒绝其他成功 JSON 形状', () => {
  assert.deepEqual(requireArrayResponse([], 'AI历史'), [])
  assert.deepEqual(requireArrayResponse([{ id: 1 }], '来源'), [{ id: 1 }])
  for (const value of [null, {}, { items: [] }, '[]', 0]) {
    assert.throws(() => requireArrayResponse(value, 'AI历史'), {
      name: 'TypeError',
      message: 'AI历史响应格式无效，无法显示列表'
    })
  }
})

test('AI历史、来源向导和 Ops 裸数组入口校验响应后再渲染', () => {
  const assistant = readFileSync(join(ROOT, 'src/views/AiAssistant.vue'), 'utf8')
  const wizard = readFileSync(join(ROOT, 'src/views/SourceWizard.vue'), 'utf8')
  const ops = readFileSync(join(ROOT, 'src/views/Ops.vue'), 'utf8')
  for (const source of [assistant, wizard, ops]) {
    assert.match(source, /import \{ requireArrayResponse \} from '\.\.\/utils\/arrayResponse\.js'/)
  }
  assert.match(assistant, /requireArrayResponse\(rows, 'AI历史'\)/)
  assert.match(wizard, /requireArrayResponse\(await api\.sources\(\), '数据来源'\)/)
  assert.match(ops, /requireArrayResponse\(snapshotContextRows, '快照上下文'\)/)
  assert.match(ops, /requireArrayResponse\(quality, '质量列表'\)/)
  assert.doesNotMatch(assistant, /history\.value = Array\.isArray\(rows\) \? rows : \[\]/)
})
