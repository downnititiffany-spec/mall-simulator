// 商城前端边界守卫（指导书 V2.0 §18.4、§31 第 6 条）
// 商城前端（mall-frontend/）只允许访问模拟商城（8090）自身接口与存储键：
//   · 不读写分析平台登录态键名（analytics_token / analytics_user）
//   · 不出现分析平台接口路径（/api/v1/dashboards|analysis|metrics|pipeline-runs|ai|decisions|admin）
//   · 不硬编码分析平台端口 8091
//   · 不注册分析平台页面路由
// 与 web/tests/boundary.test.js 互为镜像；构建期还有 scripts/build-web-and-package.ps1 的
// Assert-FrontendBoundary / Assert-JarBoundary 双层拦截。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative } from 'node:path'
import { fileURLToPath } from 'node:url'

const SRC = fileURLToPath(new URL('../src', import.meta.url))

function walk(dir) {
  const out = []
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) out.push(...walk(full))
    else out.push(full)
  }
  return out
}

const files = walk(SRC).map((f) => ({ path: relative(SRC, f).replace(/\\/g, '/'), text: readFileSync(f, 'utf8') }))

// 允许出现分析平台字样的唯一语境：说明「该页面/接口不属本前端」的注释或提示文案
const ALLOW_MENTION = /(不属于|不在这里|不再提供|已迁出|相互独立|分析平台|§18\.4|指导书|商城侧)/

test('商城前端不使用分析平台登录态键名', () => {
  const bad = files.filter((f) => /analytics_token|analytics_user/.test(f.text))
  assert.deepEqual(bad.map((f) => f.path), [])
})

test('商城前端登录态键名为 mall_token / mall_user', () => {
  const api = files.find((f) => f.path === 'api.js')
  assert.ok(api, '缺少 src/api.js')
  assert.match(api.text, /TOKEN_KEY\s*=\s*'mall_token'/)
  assert.match(api.text, /USER_KEY\s*=\s*'mall_user'/)
})

test('商城前端不出现分析平台接口路径与 8091 端口', () => {
  const problems = []
  for (const f of files) {
    if (/['"`]\/api\/v1\/(dashboards|analysis|metrics|pipeline-runs|ai|decisions|admin)/.test(f.text)) {
      problems.push(`${f.path}: 分析平台接口路径`)
    }
    // 模板里给使用者看的一句「分析平台看板在 http://127.0.0.1:8091」属边界说明文案，
    // 只有代码里（脚本段）出现 8091 才算越界依赖。
    const scriptPart = f.text.replace(/<template>[\s\S]*?<\/template>/g, '')
    if (/127\.0\.0\.1:8091|localhost:8091/.test(scriptPart)) problems.push(`${f.path}: 脚本段引用分析平台端口 8091`)
  }
  assert.deepEqual(problems, [])
})

test('商城前端不注册分析平台页面路由', () => {
  const router = files.find((f) => f.path === 'router.js')
  for (const p of ['/overview', '/sales', '/rfm', '/behavior', '/products', '/pipeline', '/ops', '/ai', '/decisions']) {
    assert.ok(!new RegExp(`path:\\s*'${p}'`).test(router.text), `商城路由不得包含 ${p}`)
  }
})

test('分析平台字样只出现在边界说明语境', () => {
  const suspicious = []
  for (const f of files) {
    f.text.split('\n').forEach((line, i) => {
      if (!/analytics|分析平台/.test(line)) return
      if (ALLOW_MENTION.test(line)) return
      if (/^\s*(\/\/|\*|\/\*|<!--)/.test(line)) return
      suspicious.push(`${f.path}:${i + 1}: ${line.trim().slice(0, 100)}`)
    })
  }
  assert.deepEqual(suspicious, [])
})

// 离线可运行性守卫：答辩环境无外网，页面不得依赖任何外部 CDN/字体/脚本
test('商城前端不引用外网资源（离线可运行）', () => {
  const indexHtml = readFileSync(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8')
  const hits = []
  for (const f of [...files, { path: '../index.html', text: indexHtml }]) {
    f.text.split('\n').forEach((line, i) => {
      const m = line.match(/https?:\/\/[^\s'"()]+/)
      if (!m) return
      if (/127\.0\.0\.1|localhost/.test(m[0])) return
      if (/^\s*(\/\/|\*|\/\*|<!--)/.test(line)) return
      hits.push(`${f.path}:${i + 1}: ${m[0]}`)
    })
  }
  assert.deepEqual(hits, [])
})
