import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/components/BaseChart.vue'), 'utf8')

test('BaseChart 继续只初始化一个 ECharts 实例并复用 setOption', () => {
  assert.match(source, /if \(!chart\) chart = echarts\.init\(el\.value\)/)
  assert.match(source, /chart\.setOption\(enrich\(props\.option\), true\)/)
})

test('option 变化继续走既有深度 render watch', () => {
  assert.match(source, /watch\(\(\) => props\.option, render, \{ deep: true \}\)/)
})

test('动态 height 变化在 DOM 更新后主动触发 chart.resize', () => {
  assert.match(source, /import \{[^}]*nextTick[^}]*\} from 'vue'/)
  assert.match(source, /watch\(\(\) => props\.height, \(\) => nextTick\(resize\)\)/)
})

test('窗口 resize 监听仍在挂载时注册并在卸载时清理', () => {
  assert.match(source, /window\.addEventListener\('resize', resize\)/)
  assert.match(source, /window\.removeEventListener\('resize', resize\)/)
  assert.match(source, /chart && chart\.dispose\(\)/)
})
