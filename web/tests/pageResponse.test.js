import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { requirePageResponse } from '../src/utils/pageResponse.js'

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..')

test('分页响应接受服务端一基页码及合法空结果', () => {
  const empty = { items: [], page: 1, size: 20, total: 0, totalPages: 1, sort: 'id,desc' }
  assert.equal(requirePageResponse(empty, '决策列表'), empty)
  const lastPage = { ...empty, items: [1], total: 41, totalPages: 3, page: 3 }
  assert.equal(requirePageResponse(lastPage), lastPage)
})

test('分页响应拒绝缺字段、错类型和不一致元数据', () => {
  const valid = { items: [], page: 1, size: 20, total: 0, totalPages: 1 }
  for (const value of [null, [], {}, { ...valid, items: {} }, { ...valid, page: '1' },
    { ...valid, page: 0 }, { ...valid, size: 0 }, { ...valid, total: -1 },
    { ...valid, totalPages: 0 }, { ...valid, total: 21, totalPages: 1 },
    { ...valid, total: 0, totalPages: 2 }, { ...valid, page: 2 },
    { ...valid, items: Array(21).fill(1) }]) {
    assert.throws(() => requirePageResponse(value, '决策列表'), /决策列表响应格式无效/)
  }
})

test('决策、流水线和运维页面在渲染空态前校验分页响应', () => {
  for (const file of ['src/views/Decisions.vue', 'src/views/Pipeline.vue', 'src/views/Ops.vue']) {
    const source = readFileSync(join(ROOT, file), 'utf8')
    assert.match(source, /import \{ requirePageResponse \} from '\.\.\/utils\/pageResponse\.js'/)
    assert.match(source, /requirePageResponse\(\s*await api\./)
    assert.doesNotMatch(source, /Array\.isArray\((result|raw)\.items\) \? (result|raw)\.items : \[\]/)
  }
  for (const label of ['快照列表', '质量结果列表', 'AI问答审计列表', 'AI调用审计列表', '流水线运行列表', '采集批次列表', '用户列表']) {
    assert.ok(fsReadOps().includes(`'${label}'`), `Ops should validate ${label}`)
  }
})

function fsReadOps() {
  return readFileSync(join(ROOT, 'src/views/Ops.vue'), 'utf8')
}
