// 没打全的拼音：全简拼、末字只打首字母、第二个字只打首字母。
// 同一串字母往往对应好几句都通的话（wsd 可以是 我说的 / 我是的 / 我收到），所以按通过率卡线，
// 卡线取当前水平往下一点，防止回退；首选没中的会打印出来，方便查词库和权重。
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { candidates } from './pinyin-engine.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const lexicon = JSON.parse(readFileSync(join(here, 'base-lexicon.json'), 'utf8')).entries
const cases = JSON.parse(readFileSync(join(here, 'pinyin-abbrev-cases.json'), 'utf8'))

// 首选 / 前五 的最低通过率（百分比）。
// 现状：全简拼 90 / 97，末字首字母 95 / 99，第二个字首字母 96 / 97（2026-10，21787 条）。
const ABBREV_THRESHOLDS = {
  ini: { top1: 86, top5: 94 },
  tail: { top1: 92, top5: 97 },
  mid: { top1: 92, top5: 94 }
}

const NAMES = { ini: '全简拼', tail: '末字只打首字母', mid: '第二个字只打首字母' }

function measure(kind) {
  const rows = cases.filter((row) => row[0] === kind)
  let top1 = 0
  let top5 = 0
  const misses = []
  for (const [, raw, word] of rows) {
    const texts = candidates({ raw, lexicon, items: [] }).slice(0, 5).map((row) => row.commit)
    if (texts[0] === word) top1 += 1
    else misses.push(`${raw} → ${texts.slice(0, 3).join(' ')}，期望 ${word}`)
    if (texts.includes(word)) top5 += 1
  }
  return { total: rows.length, top1, top5, misses }
}

// 种类从用例文件推导，并与卡线表交叉校验：任一侧新增/漏配种类都会让测试立即失败，
// 避免用例与阈值不同步时“静默跳过、构建仍绿”。
const KINDS = [...new Set(cases.map((row) => row[0]))]
assert.deepEqual(
  KINDS.slice().sort(),
  Object.keys(ABBREV_THRESHOLDS).sort(),
  '用例种类与卡线种类不一致'
)

for (const kind of KINDS) {
  test(`${NAMES[kind]}：首选和前五的通过率不低于卡线`, () => {
    const { total, top1, top5, misses } = measure(kind)
    assert.ok(total >= 50, `${NAMES[kind]} 用例只有 ${total} 条`)
    const rate1 = Math.floor((top1 * 100) / total)
    const rate5 = Math.floor((top5 * 100) / total)
    console.log(`${NAMES[kind]} ${total} 条：首选 ${top1}（${rate1}%），前五 ${top5}（${rate5}%）`)
    for (const line of misses.slice(0, 20)) console.log('  ' + line)
    // 把未命中样例附进断言消息，即便 CI 吞掉 console 输出，失败信息也能自解释。
    const detail = misses.slice(0, 20).join(' | ')
    const want = ABBREV_THRESHOLDS[kind]
    assert.ok(rate1 >= want.top1, `${NAMES[kind]} 首选 ${rate1}% 低于 ${want.top1}%：${detail}`)
    assert.ok(rate5 >= want.top5, `${NAMES[kind]} 前五 ${rate5}% 低于 ${want.top5}%：${detail}`)
  })
}
