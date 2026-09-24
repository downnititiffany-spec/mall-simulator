import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const login = readFileSync(
  fileURLToPath(new URL('../src/views/Login.vue', import.meta.url)),
  'utf8'
)

test('login submits once through a semantic form and supports keyboard/autofill', () => {
  assert.match(login, /<form\s+@submit\.prevent="onSubmit">/)
  assert.match(login, /id="username"[^>]*autocomplete="username"/)
  assert.match(login, /id="password"[^>]*autocomplete="current-password"/)
  assert.match(login, /<button[^>]*type="submit"[^>]*:disabled="loading"/)
  assert.doesNotMatch(login, /@keyup\.enter="onSubmit"/)
  assert.doesNotMatch(login, /@click="onSubmit"/)
})

test('login validation and error feedback remain accessible', () => {
  assert.match(login, /if \(!username\.value \|\| !password\.value\)/)
  assert.match(login, /class="login-error" role="alert"/)
  assert.match(login, /if \(loading\.value\) return/)
})
