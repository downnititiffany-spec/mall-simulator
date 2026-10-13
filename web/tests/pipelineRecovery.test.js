import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const API = readFileSync(fileURLToPath(new URL('../src/api.js', import.meta.url)), 'utf8')
const PAGE = readFileSync(fileURLToPath(new URL('../src/views/Pipeline.vue', import.meta.url)), 'utf8')

test('流水线恢复 API 接线带原因，不再发送请求方自报 operator', () => {
  assert.match(API, /pipelineRecoveryReport:\s*\(options\)\s*=>\s*client\.get\('\/admin\/pipeline-runs\/recovery-report'/)
  assert.match(API, /resumePipelineRun:\s*\(id, reason\)\s*=>\s*client\.post\(`\/admin\/pipeline-runs\/\$\{id\}\/resume`,\s*null,\s*\{\s*params:\s*\{\s*reason\s*\}\s*\}\)/)
  assert.match(API, /markPipelineRunFailed:\s*\(id, reason\)\s*=>\s*client\.post\(`\/admin\/pipeline-runs\/\$\{id\}\/mark-failed`,\s*null,\s*\{\s*params:\s*\{\s*reason\s*\}\s*\}\)/)
  assert.match(API, /retryPipelineRunFromStage:\s*\(id, stage, reason\)\s*=>\s*client\.post\(`\/admin\/pipeline-runs\/\$\{id\}\/retry-from-stage`,\s*null,\s*\{\s*params:\s*\{\s*stage,\s*reason\s*\}\s*\}\)/)
  assert.doesNotMatch(API.match(/pipelineRecoveryReport:[\s\S]*?\/\/ 指标\/快照/)?.[0] || '', /operator/)
})

test('流水线页展示恢复对账，并将人工恢复入口限制为可运行流水线角色', () => {
  assert.match(PAGE, /api\.pipelineRecoveryReport\(\)/)
  assert.match(PAGE, /recoveryReport\.interruptedRunning/)
  assert.match(PAGE, /recoveryReport\.orphanJobsMarkedUnknown/)
  assert.match(PAGE, /const canOperatePipeline = computed\(\(\) => \{[\s\S]*admin', 'data_dev', 'operator/)
  assert.match(PAGE, /v-if="canOperatePipeline"/)
})

test('恢复操作有理由、会话权限以外的显式风险确认和执行前状态复核', () => {
  assert.match(PAGE, /恢复原因（必填）/)
  assert.match(PAGE, /!recoveryForm\.reason\.trim\(\)/)
  assert.match(PAGE, /!recoveryForm\.confirmed/)
  assert.match(PAGE, /此操作只改写平台 run 状态，不会停止进程或从执行队列/)
  assert.match(PAGE, /按阶段重试会删除所选阶段及之后的阶段记录并重跑/)
  assert.match(PAGE, /const fresh = await api\.pipelineRun\(form\.runId\)/)
  assert.match(PAGE, /只有 FAILED 实例可续跑或按阶段重跑/)
  assert.match(PAGE, /只有 PENDING\/RUNNING 实例可由管理员判定失败/)
})
