import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Pipeline.vue'), 'utf8')

test('异步流水线创建和重试后按 RunId 轮询并显示阶段/失败反馈', () => {
  assert.match(source, /if \(runResult\.value && runResult\.value\.runId\) startRunPolling\(runResult\.value\.runId\)/g)
  assert.match(source, /api\.pipelineRun\(runId\)/)
  assert.match(source, /当前阶段：\{\{ runResult\.currentStage \|\| 'PENDING' \}\}/)
  assert.match(source, /失败码：\{\{ runResult\.errorCode \}\}/)
  assert.match(source, /v-for="stage in runResult\.stages"/)
  assert.match(source, /stage\.stageCode[\s\S]*stage\.status[\s\S]*stage\.errorCode/)
})

test('只在 PENDING/RUNNING 时继续轮询，卸载或切换新任务时清理定时器', () => {
  assert.match(source, /const runIsActive = \(status\) => status === 'PENDING' \|\| status === 'RUNNING'/)
  assert.match(source, /runPollTimer\.value = setTimeout\(poll, 1500\)/)
  assert.match(source, /function stopRunPolling\(\)[\s\S]*clearTimeout\(runPollTimer\.value\)/)
  assert.match(source, /onBeforeUnmount\(\(\) => \{[\s\S]*stopRunPolling\(\)[\s\S]*analysis\.cancel\(\)/)
})

test('轮询网络失败保持任务状态并显示恢复提示，不把读取错误冒充任务失败', () => {
  assert.match(source, /pollingError: true/)
  assert.match(source, /暂时无法刷新任务进度，将继续重试/)
  assert.doesNotMatch(source, /pollingError: true[\s\S]*status: 'FAILED'/)
})
