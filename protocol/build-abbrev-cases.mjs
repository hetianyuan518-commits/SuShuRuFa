// 从口语短语层和日常短句派生「没打全」的用例：全简拼、末字只打首字母、第二个字只打首字母。
// 期望答案就是这句话本身。同一串字母常常对应好几句都说得通的话，所以测试按通过率卡，不要求全对。
//   node protocol/build-abbrev-cases.mjs
import { writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { SYLLABLES } from './pinyin-engine.mjs'
import { chatPhrases } from './lexicon-chat.mjs'
import { dailyPhrases } from './lexicon-daily.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

const seen = new Map()
for (const [word, pinyin, freq] of [...chatPhrases, ...dailyPhrases]) {
  const syls = pinyin.split(' ')
  if (syls.length < 2 || syls.length > 8) continue
  if (Array.from(word).length !== syls.length) continue
  const prev = seen.get(word)
  if (!prev || freq > prev.freq) seen.set(word, { word, syls, freq })
}
const phrases = [...seen.values()].filter((row) => row.freq >= 470).sort((a, b) => b.freq - a.freq || (a.word < b.word ? -1 : 1))

const cases = []
const used = new Set()
function push(kind, input, word) {
  const key = `${kind}\n${input}`
  if (used.has(key)) return
  used.add(key)
  cases.push([kind, input, word])
}

for (const { word, syls } of phrases) {
  const ini = syls.map((syl) => syl[0]).join('')
  // 全简拼：wxy → 我想要。本身就是一个音节的（wa、wan）用户多半是在打那个字，跳过。
  if (!SYLLABLES.has(ini)) push('ini', ini, word)
  // 末字只打首字母：woxiangy → 我想要。ta + n 会粘成 tan（谈），没有分隔符分不开，这种跳过。
  const tail = syls[syls.length - 1][0]
  if (!SYLLABLES.has(syls[syls.length - 2] + tail)) push('tail', syls.slice(0, -1).join('') + tail, word)
  // 第二个字只打首字母：woxyao → 我想要。只对三字以上的说法做；m + ai 会粘成 mai（买）的跳过。
  if (syls.length >= 3 && !SYLLABLES.has(syls[1][0] + syls[2]) && !SYLLABLES.has(syls[0] + syls[1][0])) {
    push('mid', syls[0] + syls[1][0] + syls.slice(2).join(''), word)
  }
}

const json = '[\n' + cases.map((row) => JSON.stringify(row)).join(',\n') + '\n]\n'
for (const target of [join(root, 'protocol/pinyin-abbrev-cases.json'), join(root, 'android/app/src/androidTest/assets/pinyin-abbrev-cases.json')]) {
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, json)
}
const count = (kind) => cases.filter((row) => row[0] === kind).length
console.log(`phrases ${phrases.length} cases ${cases.length} ini ${count('ini')} tail ${count('tail')} mid ${count('mid')}`)
