// 前后端/前后台边界守卫（指导书 V2.0 §18.4、§31 第 6 条）
// 分析前端（web/）只允许访问分析平台（8091）自身的接口与存储键：
//   · 不得读写商城侧登录态键名（mall_token / mall_user）
//   · 不得出现商城侧页面、接口路径（/api/v1/mall/**）、生成器入口
//   · 不得硬编码商城端口 8090
// 说明：本文件是「永久守卫」，与 Java 侧 AnalysisSourcePolicyTest、构建脚本
// Assert-FrontendBoundary / Assert-JarBoundary 一起构成三层边界拦截。
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

// 允许出现商城字样的唯一位置：说明边界「不做商城页面」的注释/文案
const ALLOW_MENTION = /(不再提供|已迁出|相互独立|商城前端|mall-simulator|模拟商城)/

test('分析前端不读写商城侧登录态键名', () => {
  const bad = files.filter((f) => /localStorage[\s\S]{0,40}(mall_token|mall_user)|(mall_token|mall_user)[\s\S]{0,40}localStorage/.test(f.text))
  assert.deepEqual(bad.map((f) => f.path), [], '分析前端不得使用 mall_token / mall_user')
})

test('分析前端登录态键名为 analytics_token / analytics_user', () => {
  const api = files.find((f) => f.path === 'api.js')
  assert.ok(api, '缺少 src/api.js')
  assert.match(api.text, /TOKEN_KEY\s*=\s*'analytics_token'/)
  assert.match(api.text, /USER_KEY\s*=\s*'analytics_user'/)
  const router = files.find((f) => f.path === 'router.js')
  assert.match(router.text, /localStorage\.getItem\('analytics_token'\)/)
})

test('分析前端不出现商城接口路径与商城端口', () => {
  const problems = []
  for (const f of files) {
    if (/['"`]\/api\/v1\/mall/.test(f.text)) problems.push(`${f.path}: 商城接口路径`)
    if (/127\.0\.0\.1:8090|localhost:8090/.test(f.text)) problems.push(`${f.path}: 商城端口 8090`)
  }
  assert.deepEqual(problems, [])
})

test('分析前端不注册商城侧页面路由（/mall、/admin-products、/generator）', () => {
  const router = files.find((f) => f.path === 'router.js')
  for (const p of ['/mall', '/admin-products', '/generator']) {
    assert.ok(!new RegExp(`path:\\s*'${p}'`).test(router.text), `路由不得包含 ${p}`)
  }
})

test('商城字样只出现在「边界说明」语境，不做为接口/路由使用', () => {
  const suspicious = []
  for (const f of files) {
    const lines = f.text.split('\n')
    lines.forEach((line, i) => {
      if (!/mall/i.test(line)) return
      if (ALLOW_MENTION.test(line)) return
      if (/^\s*(\/\/|\*|\/\*)/.test(line)) return // 注释行由人工复核
      suspicious.push(`${f.path}:${i + 1}: ${line.trim().slice(0, 100)}`)
    })
  }
  assert.deepEqual(suspicious, [])
})

// 离线可运行性守卫：答辩环境无外网，页面不得依赖任何外部 CDN/字体/脚本
test('前端不引用外网资源（离线可运行）', () => {
  const indexHtml = readFileSync(fileURLToPath(new URL('../index.html', import.meta.url)), 'utf8')
  const hits = []
  for (const f of [...files, { path: '../index.html', text: indexHtml }]) {
    f.text.split('\n').forEach((line, i) => {
      const m = line.match(/https?:\/\/[^\s'"()]+/)
      if (!m) return
      if (/127\.0\.0\.1|localhost/.test(m[0])) return
      if (/^\s*(\/\/|\*|\/\*|<!--)/.test(line)) return // 注释里提到外网地址不算依赖
      hits.push(`${f.path}:${i + 1}: ${m[0]}`)
    })
  }
  assert.deepEqual(hits, [])
})
