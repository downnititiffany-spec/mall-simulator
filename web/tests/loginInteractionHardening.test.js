import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const source = fs.readFileSync(path.join(here, '../src/views/Login.vue'), 'utf8')

test('登录请求在途时锁住用户名和密码输入，避免界面值与已发送凭据错位', () => {
  assert.match(source, /id="username"[\s\S]*?:disabled="loading"[\s\S]*?@keyup\.enter="onSubmit"/)
  assert.match(source, /id="password"[\s\S]*?:disabled="loading"[\s\S]*?@keyup\.enter="onSubmit"/)
})

test('登录按钮继续由 loading 状态禁用并显示在途文案', () => {
  assert.match(source, /<button class="login-btn" :disabled="loading" @click="onSubmit">/)
  assert.match(source, /loading \? '登录中…' : '登 录'/)
})

test('onSubmit 以 handler 级 loading 守卫开头，并在 finally 中释放状态', () => {
  const block = source.match(/const onSubmit = async \(\) => \{[\s\S]*?\n\}/)
  assert.ok(block, '未找到 onSubmit handler')
  const guardAt = block[0].indexOf('if (loading.value) return')
  const validationAt = block[0].indexOf('if (!username.value || !password.value)')
  const apiAt = block[0].indexOf('await api.login(username.value, password.value)')
  assert.ok(guardAt >= 0, '缺少 loading handler guard')
  assert.ok(validationAt > guardAt, 'loading guard 必须早于输入校验')
  assert.ok(apiAt > validationAt, 'API 调用必须发生在校验之后')
  assert.match(block[0], /finally \{\s*loading\.value = false\s*\}/)
})
