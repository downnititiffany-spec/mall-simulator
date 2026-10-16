import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const pkg = JSON.parse(readFileSync(join(here, '..', 'package.json'), 'utf8'))

test('package.json exposes one frontend verification entry', () => {
  assert.equal(pkg.scripts.verify, 'npm test && npm run build')
})

test('verification entry keeps unit tests before production build', () => {
  const command = pkg.scripts.verify
  assert.ok(command.indexOf('npm test') < command.indexOf('npm run build'))
  assert.equal((command.match(/npm test/g) || []).length, 1)
  assert.equal((command.match(/npm run build/g) || []).length, 1)
})
