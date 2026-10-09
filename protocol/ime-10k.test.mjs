import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { candidates } from './pinyin-engine.mjs'

const here = dirname(fileURLToPath(import.meta.url))

// 覆盖率阈值（用例条数下限）集中命名，便于调整与复用。
const MIN_TOTAL = 99900
const MIN_PY = 80000
const MIN_WB = 10000
const MIN_HW = 1000
// 失败信息只保留前若干条，避免 8 万条用例全错时把巨量字符串堆进内存。
const MAX_REPORTED = 12

// 加载失败时给出明确文件名，避免 import 期抛出难以定位的底层错误。
function loadJson(name) {
  try {
    return JSON.parse(readFileSync(join(here, name), 'utf8'))
  } catch (error) {
    throw new Error(`无法读取或解析 ${name}：${error.message}`)
  }
}

const lexicon = loadJson('base-lexicon.json').entries
const cases = loadJson('ime-10k-cases.json')

test('十万条里的拼音用例首选正确', () => {
  const rows = cases.filter((row) => row[0] === 'py')
  assert.ok(rows.length >= MIN_PY, `拼音只有 ${rows.length} 条`)
  const bad = []
  let badCount = 0
  for (const [, raw, word] of rows) {
    const top = candidates({ raw, lexicon, items: [] })[0]
    if (!top || top.commit !== word) {
      badCount += 1
      if (bad.length < MAX_REPORTED) bad.push(`${raw} → ${top ? top.commit : '无'}，期望 ${word}`)
    }
  }
  assert.equal(badCount, 0, `共 ${badCount} 条不符，前 ${bad.length} 条：\n${bad.join('\n')}`)
})

test('十万条覆盖拼音、五笔、手写', () => {
  const count = { py: 0, wb: 0, hw: 0 }
  for (const [mode] of cases) {
    // 守卫未知 mode，避免 count[mode] 变成 NaN 让后续断言以难以理解的方式失败。
    if (Object.prototype.hasOwnProperty.call(count, mode)) count[mode] += 1
  }
  assert.ok(cases.length >= MIN_TOTAL, `用例只有 ${cases.length} 条`)
  assert.ok(count.py >= MIN_PY, `拼音 ${count.py} 条`)
  assert.ok(count.wb >= MIN_WB, `五笔 ${count.wb} 条`)
  assert.ok(count.hw >= MIN_HW, `手写 ${count.hw} 条`)
})
