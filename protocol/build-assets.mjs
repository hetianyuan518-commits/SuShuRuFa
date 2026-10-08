import { writeFileSync, mkdirSync, rmSync, readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { LEXICON_LICENSE, collectEntries, writeLexiconBin } from './build-lexicon.mjs'
import { chatNext } from './lexicon-chat.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

// 三端共用的排序规则：前词 → 后词搭配，以及各档分数。代码只负责加载。
const rules = {
  version: 1,
  weights: {
    exactWord: 5000,
    wordCap: 489,
    prefixWord: 4000,
    spokenBase: 5000,
    spokenRare: 200,
    spokenPriorCap: 450,
    spokenCommonChar: 400,
    wholeWordFloor: 100,
    phraseBonus: 6,
    followBonus: 12,
    followGain: 900,
    sentenceFollowGain: 300,
    mixedFollowGain: 300,
    initialCharCost: 200,
    keepCost: 600,
    syllableGain: 560,
    looseCost: 40,
    initialWordGain: 2200,
    initialGrammarGain: 280,
    initialCharGain: -700,
    associateLengthCost: 90,
    associatePairBonus: 40,
    associateNextStep: 8
  },
  next: chatNext
}
const rulesJson = JSON.stringify(rules)
for (const target of [
  join(root, 'protocol/ime-rules.json'),
  join(root, 'android/app/src/main/assets/ime-rules.json'),
  join(root, 'windows/internal/pinyin/ime-rules.json'),
  join(root, 'ios/Resources/ime-rules.json'),
  join(root, 'harmony/entry/src/main/resources/rawfile/ime-rules.json')
]) {
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, rulesJson)
}
console.log(`rules ${Object.keys(chatNext).length} next keys`)
for (const target of [
  join(root, 'ios/Resources/char-prior.json'),
  join(root, 'harmony/entry/src/main/resources/rawfile/char-prior.json')
]) {
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, readFileSync(join(root, 'protocol/char-prior.json')))
}
const entries = collectEntries()
const lexicon = { version: 2, license: LEXICON_LICENSE, entries }
const json = JSON.stringify(lexicon)
const targets = [
  join(root, 'protocol/base-lexicon.json'),
  join(root, 'ios/Resources/base-lexicon.json'),
  join(root, 'harmony/entry/src/main/resources/rawfile/base-lexicon.json'),
  join(root, 'macos/Resources/base-lexicon.json'),
  join(root, 'windows/internal/pinyin/base-lexicon.json')
]
for (const target of targets) {
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, json)
}
const bin = writeLexiconBin(entries)
const binTargets = [
  join(root, 'protocol/lexicon.bin'),
  join(root, 'android/app/src/main/assets/lexicon.bin')
]
for (const target of binTargets) {
  mkdirSync(dirname(target), { recursive: true })
  writeFileSync(target, bin.bytes)
}
rmSync(join(root, 'android/app/src/main/assets/base-lexicon.json'), { force: true })
console.log(`bin ${bin.entries} entries, ${bin.lists} lists, ${(bin.bytes.length / 1048576).toFixed(1)} MB`)

const dict = [
  '# 苏晟自建词表，供 librime script_translator 使用',
  '---',
  'name: susheng',
  'version: "1.0"',
  'sort: by_weight',
  'use_preset_vocabulary: false',
  '...',
  ...entries.map((entry) => `${entry.w}\t${entry.p}\t${entry.f}`)
].join('\n') + '\n'
writeFileSync(join(root, 'rime/susheng.dict.yaml'), dict)
console.log(`lexicon ${entries.length} -> ${targets.length} copies`)
