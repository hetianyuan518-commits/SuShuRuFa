import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { candidates } from './pinyin-engine.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const lexicon = JSON.parse(readFileSync(join(here, 'base-lexicon.json'), 'utf8')).entries
const cases = JSON.parse(readFileSync(join(here, 'ime-10k-cases.json'), 'utf8'))

test('十万条里的拼音用例首选正确', () => {
  const rows = cases.filter((row) => row[0] === 'py')
  assert.ok(rows.length >= 80000, `拼音只有 ${rows.length} 条`)
  const bad = []
  for (const [, raw, word] of rows) {
    const top = candidates({ raw, lexicon, items: [] })[0]
    if (!top || top.commit !== word) bad.push(`${raw} → ${top ? top.commit : '无'}，期望 ${word}`)
  }
  assert.equal(bad.length, 0, bad.slice(0, 12).join('\n'))
})

test('十万条覆盖拼音、五笔、手写', () => {
  const count = { py: 0, wb: 0, hw: 0 }
  for (const [mode] of cases) count[mode] += 1
  assert.ok(cases.length >= 99900, `用例只有 ${cases.length} 条`)
  assert.ok(count.py >= 80000)
  assert.ok(count.wb >= 10000)
  assert.ok(count.hw >= 1000)
})
