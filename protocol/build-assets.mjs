import { writeFileSync, mkdirSync, rmSync, readFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { LEXICON_LICENSE, collectEntries, writeLexiconBin } from './build-lexicon.mjs'
import { chatNext } from './lexicon-chat.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')

// 统一的资源写入：失败时带上目标路径，便于定位是哪一端没写成功（避免部分写入后静默中断）。
function writeTarget(target, data) {
  try {
    mkdirSync(dirname(target), { recursive: true })
    writeFileSync(target, data)
  } catch (error) {
    throw new Error(`写入资源失败 ${target}：${error.message}`)
  }
}

// 各平台资源目录约定（相对仓库根）。集中在此，避免同一目录字符串在多处重复且容易漏改。
const PLATFORM_DIR = {
  protocol: 'protocol',
  android: 'android/app/src/main/assets',
  windows: 'windows/internal/pinyin',
  ios: 'ios/Resources',
  harmony: 'harmony/entry/src/main/resources/rawfile',
  macos: 'macos/Resources'
}
// 每种资源只写到消费它的平台子集（例如 android 用 lexicon.bin、不用 base-lexicon.json），
// 用文件名 + 平台键派生目标路径，使各资源的分发范围一目了然。
const assetTargets = (file, platforms) => platforms.map((name) => join(root, PLATFORM_DIR[name], file))

// 全客户端共用的排序规则：前词 → 后词搭配，以及各档分数。代码只负责加载。
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
for (const target of assetTargets('ime-rules.json', ['protocol', 'android', 'windows', 'ios', 'harmony'])) {
  writeTarget(target, rulesJson)
}
console.log(`rules ${Object.keys(chatNext).length} next keys`)
const charPrior = readFileSync(join(root, 'protocol/char-prior.json'))
for (const target of assetTargets('char-prior.json', ['ios', 'harmony'])) {
  writeTarget(target, charPrior)
}
const entries = collectEntries()
const lexicon = { version: 2, license: LEXICON_LICENSE, entries }
const json = JSON.stringify(lexicon)
const targets = assetTargets('base-lexicon.json', ['protocol', 'ios', 'harmony', 'macos', 'windows'])
for (const target of targets) {
  writeTarget(target, json)
}
const bin = writeLexiconBin(entries)
for (const target of assetTargets('lexicon.bin', ['protocol', 'android'])) {
  writeTarget(target, bin.bytes)
}
// android 端消费二进制 lexicon.bin，不需要 base-lexicon.json；显式清理旧文件并记录。
const androidJson = join(root, 'android/app/src/main/assets/base-lexicon.json')
if (existsSync(androidJson)) {
  rmSync(androidJson)
  console.log(`removed stale ${androidJson}`)
}
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
writeTarget(join(root, 'rime/susheng.dict.yaml'), dict)
console.log(`lexicon ${entries.length} -> ${targets.length} copies`)
