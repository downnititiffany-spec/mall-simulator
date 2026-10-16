// QA-01 前端部分（2026-09-22 实测评价 §QA-01）：倒置日期区间必须在**前端**就被拦下。
//
// 实测事实：`2026-09-22 → 2026-09-16` 仍然发出请求并拿到 200，页面没有任何提示。
// 后端负责 400 与真正的趋势过滤；前端只负责：区间倒置时不发请求、给出明确中文提示。
//
// 属主：`web/src/utils/localDate.js` 是日期口径的唯一属主（局部日历日、非 UTC 瞬时）。
// 页面（Overview / Sales）只读该判据，不自建第二份比较。
import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

import { dateRangeErrorText, isIsoDay, isRangeInverted } from '../src/utils/localDate.js'

const here = dirname(fileURLToPath(import.meta.url))
const readView = (name) => readFileSync(join(here, `../src/views/${name}.vue`), 'utf8')

/** 取出 `const load = () => { … }` 的函数体，用于断言「请求之前先校验」 */
function loadBody(source, page) {
  const block = source.match(/const load = \(\) => \{[\s\S]*?\n\}/)
  assert.ok(block, `${page} 未找到 load handler`)
  return block[0]
}

/** 断言 `needle` 在 `block` 中出现在 `anchor` 之后的第 offset 个匹配处 */
function assertAfter(block, needle, anchor, offset, page, message) {
  const needleAt = block.indexOf(needle)
  const anchorAt = block.indexOf(anchor, offset)
  assert.ok(needleAt >= 0, `${page} 缺少 ${needle}`)
  assert.ok(anchorAt >= 0, `${page} 缺少前序锚点 ${anchor}`)
  assert.ok(needleAt < anchorAt, message)
}

// ── ① 纯函数：日历日形状与倒置判据 ──────────────────────────────────────────

test('isIsoDay：只认 yyyy-MM-dd 的局部日历日，日历上不存在的日期也拒绝', () => {
  for (const ok of ['2026-09-21', '2026-01-01', '2024-02-29']) {
    assert.equal(isIsoDay(ok), true, `${ok} 应是合法日历日`)
  }
  for (const bad of ['2026-9-21', '20260921', '', '   ', null, undefined, 20260921, '2026-13-01', '2026-09-31', '2026-02-30', new Date()]) {
    assert.equal(isIsoDay(bad), false, `${JSON.stringify(bad)} 不是合法日历日`)
  }
})

test('isRangeInverted：from > to 为 true；相等/正序为 false', () => {
  assert.equal(isRangeInverted('2026-09-22', '2026-09-16'), true)
  assert.equal(isRangeInverted('2026-09-21', '2026-09-21'), false, '单日区间不是倒置')
  assert.equal(isRangeInverted('2026-09-15', '2026-09-21'), false)
})

test('isRangeInverted：任一端缺值/形状不合法时不报倒置（校验只拦确定可判定的错误）', () => {
  for (const [from, to] of [['', '2026-09-21'], ['2026-09-22', ''], [null, null], ['2026-9-22', '2026-09-16'], [undefined, '2026-09-16'], ['2026-09-22', 20260916]]) {
    assert.equal(isRangeInverted(from, to), false, `${JSON.stringify([from, to])} 不应报倒置`)
  }
})

test('dateRangeErrorText：给出明确中文提示，含实际与期望关系，且不是空串', () => {
  const text = dateRangeErrorText('2026-09-22', '2026-09-16')
  assert.ok(typeof text === 'string' && text.trim() !== '')
  assert.match(text, /起始日期/)
  assert.match(text, /结束日期/)
  assert.match(text, /2026-09-22/)
  assert.match(text, /2026-09-16/)
  assert.match(text, /趋势|日期/)
})

// ── ② 源码守卫：页面在发请求之前拦下倒置区间并展示提示 ───────────────────────

for (const page of ['Overview', 'Sales']) {
  test(`${page}：load 先把倒置区间的提示写进页面，再 fail-closed 返回，绝不发请求`, () => {
    const src = readView(page)
    assert.match(src, /isRangeInverted/, `${page} 必须引用日期区间属主的判据`)
    assert.match(src, /dateRangeErrorText/, `${page} 必须引用共享的中文提示文案`)
    assert.match(src, /const rangeError = ref\(''\)/, `${page} 必须有可见的区间错误状态`)

    const body = loadBody(src, page)
    assert.match(body, /rangeError\.value = dateRangeErrorText\(from\.value, to\.value\)/)
    // 拦截必须发生在清空错误与调用 analysis.load 之前
    assertAfter(body, 'isRangeInverted(from.value, to.value)', 'rangeError.value =', 0, page, '必须先判定倒置再写提示')
    assert.ok(
      body.indexOf('if (isRangeInverted(from.value, to.value)) return') >= 0 ||
        /isRangeInverted\(from\.value, to\.value\)\)\s*\{\s*\n\s*rangeError\.value[\s\S]*?\n\s*return\s*\n\s*\}/.test(body),
      `${page} 倒置分支必须直接 return`
    )
    assertAfter(body, 'rangeError.value = dateRangeErrorText', 'analysis.load', 0, page, '提示必须早于请求')
  })

  test(`${page}：正常区间先清掉旧提示，模板上有可见的错误横幅`, () => {
    const src = readView(page)
    const body = loadBody(src, page)
    assert.match(body, /rangeError\.value = ''/, `${page} 正常区间必须清掉上一次的提示`)
    assert.ok(
      body.indexOf("rangeError.value = ''") < body.indexOf('analysis.load'),
      `${page} 清空提示必须早于请求`
    )
    assert.match(src, /v-if="rangeError"/, `${page} 模板必须有错误提示的可见出口`)
    assert.match(src, /\{\{\s*rangeError\s*\}\}/, `${page} 提示必须渲染出来`)
  })
}
