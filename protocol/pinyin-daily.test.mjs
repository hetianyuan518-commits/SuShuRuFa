import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { candidates } from './pinyin-engine.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const lexicon = JSON.parse(readFileSync(join(here, 'base-lexicon.json'), 'utf8')).entries
const cases = JSON.parse(readFileSync(join(here, 'daily-pinyin-cases.json'), 'utf8'))

test('至少 1000 条日常拼音的首选就是这句话', () => {
  assert.ok(cases.length >= 1000, `用例只有 ${cases.length} 条`)
  const bad = []
  for (const [raw, word] of cases) {
    const top = candidates({ raw, lexicon, items: [] })[0]
    if (!top || top.commit !== word) bad.push(`${raw} → ${top ? top.commit : '无'}，期望 ${word}`)
  }
  assert.equal(bad.length, 0, bad.slice(0, 12).join('\n'))
})
