// 把可再分发的词表收成一份。不包含搜狗词库，不收录仍在版权期内的歌词。
import { readFileSync, readdirSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { syllableLines, phrases } from './lexicon-source.mjs'
import { dailyPhrases } from './lexicon-daily.mjs'
import { chatPhrases } from './lexicon-chat.mjs'
import { SYLLABLES, SYLLABLE_LIST, toT9 } from './pinyin-engine.mjs'

const cache = join(dirname(fileURLToPath(import.meta.url)), 'lexicon-cache')

const TONE = {
  ā: 'a', á: 'a', ǎ: 'a', à: 'a', ē: 'e', é: 'e', ě: 'e', è: 'e',
  ī: 'i', í: 'i', ǐ: 'i', ì: 'i', ō: 'o', ó: 'o', ǒ: 'o', ò: 'o',
  ū: 'u', ú: 'u', ǔ: 'u', ù: 'u', ǖ: 'v', ǘ: 'v', ǚ: 'v', ǜ: 'v', ü: 'v',
  ń: 'n', ň: 'n', ǹ: 'n'
}

const PIN = new Map([
  ['你好', 490],
  ['你好吗', 475],
  ['黑胡椒', 488],
  ['哈哈镜', 462],
  ['还喝酒', 450],
  ['还回家', 455],
  ['二维火', 486],
  ['二位好', 468],
  ['二维化', 458],
  ['二维码', 492],
  ['西安', 486],
  ['先', 470]
])

export const LEXICON_LICENSE = 'MIT/Apache-2.0 词表：jieba、phrase-pinyin-data、pinyin-data、THUOCL、OpenCC；唐诗原文为公有领域。不含搜狗词库，不含现代歌词。'

function normSyl(text) {
  let out = ''
  for (const ch of text.toLowerCase()) out += TONE[ch] || ch
  out = out.replace(/[^a-z]/g, '')
  if (out === 'lue') out = 'lve'
  if (out === 'nue') out = 'nve'
  if (!SYLLABLES.has(out)) return null
  return out
}

function normPinyin(text) {
  const parts = String(text || '').trim().split(/[\s,]+/).filter(Boolean)
  if (parts.length === 0 || parts.length > 16) return null
  const syls = []
  for (const part of parts) {
    const syl = normSyl(part)
    if (!syl) return null
    syls.push(syl)
  }
  return syls.join(' ')
}

function scaleFreq(raw) {
  const n = Number(raw) || 0
  if (n <= 0) return 8
  return Math.max(1, Math.min(420, Math.round(Math.log10(n + 1) * 80)))
}

function loadCountMap(file, t2s) {
  const map = new Map()
  if (!existsSync(file)) return map
  const text = readFileSync(file, 'utf8').replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  for (const line of text.split('\n')) {
    if (!line || line.startsWith('#')) continue
    const [word, freq] = line.trim().split(/\s+/)
    if (!word || !/^\d+$/.test(freq || '')) continue
    const simple = toSimplified(word, t2s)
    const n = Number(freq)
    const prev = map.get(simple)
    if (prev === undefined || n > prev) map.set(simple, n)
  }
  return map
}

function loadCharPrior() {
  const file = join(dirname(fileURLToPath(import.meta.url)), 'char-prior.json')
  if (!existsSync(file)) {
    console.warn(`[build-lexicon] 缺少 char-prior.json（${file}），同音字定序将被跳过`)
    return {}
  }
  try {
    const parsed = JSON.parse(readFileSync(file, 'utf8'))
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed) || Object.keys(parsed).length === 0) {
      console.warn('[build-lexicon] char-prior.json 为空或结构异常，同音字定序可能不生效')
    }
    return parsed
  } catch (error) {
    throw new Error(`char-prior.json 解析失败：${error.message}`)
  }
}

function settleHomophones(best, t2s) {
  const prior = loadCharPrior()
  const minPrior = (word) => {
    let min = 999
    for (const ch of word) min = Math.min(min, prior[ch] || 0)
    return min
  }
  const nameSet = new Set()
  for (const file of ['THUOCL_diming.txt', 'THUOCL_lishimingren.txt']) {
    for (const word of loadCountMap(join(cache, file), t2s).keys()) nameSet.add(word)
  }
  const jieba = loadCountMap(join(cache, 'dict.txt.big'), t2s)
  const groups = new Map()
  for (const [key, freq] of best) {
    const splitAt = key.indexOf('\n')
    const word = key.slice(0, splitAt)
    if (Array.from(word).length !== 2) continue
    const pinyin = key.slice(splitAt + 1)
    let group = groups.get(pinyin)
    if (!group) {
      group = []
      groups.set(pinyin, group)
    }
    group.push({ key, word, freq })
  }
  for (const group of groups.values()) {
    for (const name of group) {
      if (!nameSet.has(name.word) || name.freq >= 220) continue
      if (scaleFreq(jieba.get(name.word) || 0) >= name.freq) continue
      const rivals = group.filter((other) => {
        return other.key !== name.key && other.freq < 320 && other.freq >= name.freq - 40 &&
          minPrior(other.word) >= 300 && minPrior(other.word) >= minPrior(name.word) + 50
      })
      rivals.sort((a, b) => b.freq - a.freq)
      const rival = rivals[0]
      if (!rival || name.freq < rival.freq) continue
      const next = Math.max(1, rival.freq - 1)
      best.set(name.key, next)
      name.freq = next
    }
    const pool = group.filter((row) => row.freq < 180 && jieba.has(row.word))
    if (pool.length < 2) continue
    pool.sort((a, b) => b.freq - a.freq)
    const winner = pool[0]
    if (minPrior(winner.word) > 310) continue
    const other = pool.slice(1).find((row) => {
      const known = jieba.get(winner.word) || 0
      const count = jieba.get(row.word) || 0
      return known > 0 && count >= known * 0.55 && minPrior(row.word) >= 340 && minPrior(row.word) >= minPrior(winner.word) + 50
    })
    if (!other || other.freq >= winner.freq) continue
    best.set(other.key, winner.freq + 1)
    other.freq = winner.freq + 1
  }
}

function hasCjk(text) {
  return /[\u3400-\u9fff]/.test(text)
}

function loadT2S() {
  const file = join(cache, 'STCharacters.txt')
  const map = new Map()
  if (!existsSync(file)) return map
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    if (!line || line.startsWith('#')) continue
    const [simplified, traditional] = line.split('\t')
    if (!simplified || !traditional) continue
    for (const variant of traditional.split(/\s+/)) {
      if (!variant || variant === simplified || map.has(variant)) continue
      map.set(variant, simplified)
    }
  }
  return map
}

function toSimplified(text, t2s) {
  let out = ''
  for (const ch of text) out += t2s.get(ch) || ch
  return out
}

function loadCharPinyin() {
  const file = join(cache, 'pinyin.txt')
  const map = new Map()
  if (!existsSync(file)) return map
  for (const line of readFileSync(file, 'utf8').split(/\n/)) {
    const matched = line.match(/^U\+([0-9A-F]+):\s+([^#]+)#\s+(\S+)/i)
    if (!matched) continue
    const ch = matched[3]
    const first = matched[2].split(',')[0].trim()
    const syl = normSyl(first)
    if (syl) map.set(ch, syl)
  }
  return map
}

function pinyinOf(word, chars) {
  const syls = []
  for (const ch of word) {
    // 刻意跳过非汉字：add() 会把标点从词中剔除，这里同步忽略标点才能音节数与去标点后的文本对齐。
    if (!/[\u3400-\u9fff]/.test(ch)) continue
    const syl = chars.get(ch)
    if (!syl) return null
    syls.push(syl)
  }
  if (syls.length === 0 || syls.length > 16) return null
  return syls.join(' ')
}

export function collectEntries() {
  const t2s = loadT2S()
  const chars = loadCharPinyin()
  const best = new Map()

  function add(word, pinyin, freq) {
    const text = toSimplified(String(word || '').trim(), t2s).replace(/[，。！？、；：,.!?;:“”"'‘’（）()\s]/g, '')
    if (!text || text.length > 16 || !hasCjk(text) || /[A-Za-z0-9]/.test(text)) return
    const p = normPinyin(pinyin)
    if (!p) return
    const key = `${text}\n${p}`
    const prev = best.get(key)
    if (prev === undefined || freq > prev) best.set(key, freq)
  }

  for (const line of syllableLines.split('\n')) {
    const parts = line.trim().split(/\s+/)
    if (parts.length < 2) continue
    const syllable = parts[0]
    parts.slice(1).forEach((word, index) => add(word, syllable, 560 - index * 12))
  }

  for (const [word, pinyin, freq] of phrases) {
    add(word, pinyin, Math.max(320, Math.min(470, Math.round(freq / 6))))
  }

  for (const [word, pinyin, freq] of dailyPhrases) {
    add(word, pinyin, Math.min(489, Number(freq) || 430))
  }

  for (const [word, pinyin, freq] of chatPhrases) {
    add(word, pinyin, Math.min(489, Number(freq) || 470))
  }

  const extra = join(dirname(fileURLToPath(import.meta.url)), 'lexicon-extra.txt')
  if (existsSync(extra)) {
    for (const line of readFileSync(extra, 'utf8').split(/\r?\n/)) {
      if (!line || line.startsWith('#')) continue
      const [word, pinyin, freq] = line.split('\t')
      add(word, pinyin, Math.min(489, Number(freq) || 400))
    }
  }

  const phraseFile = join(cache, 'large_pinyin.txt')
  const phrasePinyin = new Map()
  if (existsSync(phraseFile)) {
    for (const line of readFileSync(phraseFile, 'utf8').split(/\n/)) {
      if (!line || line.startsWith('#')) continue
      const splitAt = line.indexOf(':')
      if (splitAt < 1) continue
      const word = line.slice(0, splitAt).trim()
      const pinyin = normPinyin(line.slice(splitAt + 1))
      if (word && pinyin) phrasePinyin.set(word, pinyin)
    }
  }

  // 词典源（dict.txt.big / THUOCL_*）共用的“繁转简 + 拼音回填”解析：优先用 large_pinyin 映射，回退到逐字拼。
  function resolveWord(word) {
    const simple = toSimplified(word, t2s)
    return { simple, pinyin: phrasePinyin.get(word) || phrasePinyin.get(simple) || pinyinOf(simple, chars) }
  }

  const dictFile = join(cache, 'dict.txt.big')
  if (existsSync(dictFile)) {
    for (const line of readFileSync(dictFile, 'utf8').split(/\n/)) {
      if (!line) continue
      const [word, freq] = line.split(/\s+/)
      if (!word) continue
      const { simple, pinyin } = resolveWord(word)
      add(simple, pinyin, scaleFreq(freq))
    }
  }
  for (const [word, pinyin] of phrasePinyin) add(toSimplified(word, t2s), pinyin, 40)

  if (existsSync(cache)) {
    for (const name of readdirSync(cache)) {
      if (!name.startsWith('THUOCL_') || !name.endsWith('.txt')) continue
      const idiom = name.includes('chengyu')
      const text = readFileSync(join(cache, name), 'utf8')
      for (const line of text.split(/\r?\n|\r/)) {
        if (!line || line.startsWith('#')) continue
        const [word, freq] = line.trim().split(/\s+/)
        if (!word) continue
        const { simple, pinyin } = resolveWord(word)
        const weight = scaleFreq(freq) + (idiom ? 40 : 0)
        add(simple, pinyin, Math.min(450, weight))
      }
    }
  }

  const poetryDir = join(cache, 'poetry')
  if (existsSync(poetryDir)) {
    for (const name of readdirSync(poetryDir)) {
      if (!name.endsWith('.json') || name.startsWith('probe') || name.startsWith('ci.probe')) continue
      let data
      try {
        data = JSON.parse(readFileSync(join(poetryDir, name), 'utf8'))
      } catch {
        continue
      }
      const poems = Array.isArray(data) ? data : []
      for (const poem of poems) {
        const paragraphs = poem.paragraphs || poem.content || []
        const title = poem.title || ''
        if (title) add(toSimplified(title, t2s), pinyinOf(toSimplified(title, t2s), chars), 80)
        for (const paragraph of paragraphs) {
          const pieces = String(paragraph).split(/[，。！？、；：\s]/)
          for (const piece of pieces) {
            const simple = toSimplified(piece, t2s)
            if (simple.length < 4) continue
            add(simple, pinyinOf(simple, chars), 70)
          }
        }
      }
    }
  }

  // 人名、地名表和分词词典不是一个尺度：生僻名（萧蔷、桓仁）只比常用词高一两档时，让给常用词。
  // 已经被抬到 220 以上的（姚明、胡适）保持不动。两个都在分词词典里、词频又很接近时（新手 / 信守），常用字的那个靠前。
  settleHomophones(best, t2s)

  for (const [key, freq] of best) {
    const word = key.slice(0, key.indexOf('\n'))
    const pin = PIN.get(word)
    if (pin !== undefined && pin > freq) best.set(key, pin)
  }

  const entries = []
  for (const [key, freq] of best) {
    const splitAt = key.indexOf('\n')
    entries.push({ w: key.slice(0, splitAt), p: key.slice(splitAt + 1), f: freq })
  }
  entries.sort((a, b) => b.f - a.f || a.w.length - b.w.length)
  return entries
}

export function writeLexiconBin(entries, file) {
  const sylId = new Map(SYLLABLE_LIST.map((syl, index) => [syl, index]))
  const rows = []
  for (const entry of entries) {
    const parts = entry.p.split(' ')
    if (parts.some((part) => !sylId.has(part))) continue
    rows.push({
      w: entry.w,
      p: entry.p,
      f: entry.f,
      ids: parts.map((part) => sylId.get(part)),
      ini: parts.map((part) => part[0]).join(''),
      t9: toT9(parts.join(''))
    })
  }
  const ranked = rows.slice().sort((a, b) => b.f - a.f)
  rows.sort((a, b) => {
    const n = Math.min(a.ids.length, b.ids.length)
    for (let i = 0; i < n; i++) if (a.ids[i] !== b.ids[i]) return a.ids[i] - b.ids[i]
    return a.ids.length - b.ids.length
  })
  rows.forEach((row, index) => { row.i = index })

  const lists = []
  const buckets = new Map()
  function push(kind, key, index, cap) {
    const id = `${kind}:${key}`
    let bucket = buckets.get(id)
    if (!bucket) {
      bucket = { kind, key, items: [] }
      buckets.set(id, bucket)
      lists.push(bucket)
    }
    if (bucket.items.length < cap) bucket.items.push(index)
  }
  for (const row of ranked) {
    const iniEnd = Math.min(row.ini.length, 4)
    for (let len = 2; len <= iniEnd; len++) push(1, row.ini.slice(0, len), row.i, 80)
    const t9End = Math.min(row.t9.length, 5)
    for (let len = 2; len <= t9End; len++) push(2, row.t9.slice(0, len), row.i, 60)
    const head = Array.from(row.w)[0]
    if (head) push(3, head, row.i, 100)
  }

  const sylBytes = []
  for (const syl of SYLLABLE_LIST) {
    const raw = Buffer.from(syl, 'utf8')
    sylBytes.push(Buffer.from([raw.length]), raw)
  }
  const syllableBlob = Buffer.concat(sylBytes)

  const entryBlobs = rows.map((row) => {
    const word = Buffer.from(row.w, 'utf8')
    const body = Buffer.alloc(1 + row.ids.length * 2 + 2 + 1 + word.length)
    let cursor = 0
    body[cursor++] = row.ids.length
    for (const id of row.ids) {
      body.writeUInt16LE(id, cursor)
      cursor += 2
    }
    body.writeUInt16LE(Math.max(0, Math.min(65535, row.f)), cursor)
    cursor += 2
    body[cursor++] = word.length
    word.copy(body, cursor)
    return body
  })

  const header = 16
  const entryOffsetsAt = header + syllableBlob.length + (4 - ((header + syllableBlob.length) % 4)) % 4
  const blobAt = entryOffsetsAt + rows.length * 4
  let blobSize = 0
  const offsets = []
  for (const blob of entryBlobs) {
    offsets.push(blobAt + blobSize)
    blobSize += blob.length
  }
  const listsAt = blobAt + blobSize
  const listChunks = []
  for (const bucket of lists) {
    const key = Buffer.from(bucket.key, 'utf8')
    if (key.length > 255 || bucket.items.length > 65535) continue
    const chunk = Buffer.alloc(1 + 1 + key.length + 2 + bucket.items.length * 4)
    let cursor = 0
    chunk[cursor++] = bucket.kind
    chunk[cursor++] = key.length
    key.copy(chunk, cursor)
    cursor += key.length
    chunk.writeUInt16LE(bucket.items.length, cursor)
    cursor += 2
    for (const index of bucket.items) {
      chunk.writeUInt32LE(index, cursor)
      cursor += 4
    }
    listChunks.push(chunk)
  }
  const listBlob = Buffer.concat(listChunks)
  const out = Buffer.alloc(listsAt + 4 + listBlob.length)
  out.write('SXL1', 0, 'ascii')
  out.writeUInt32LE(rows.length, 4)
  out.writeUInt32LE(SYLLABLE_LIST.length, 8)
  out.writeUInt32LE(listsAt, 12)
  syllableBlob.copy(out, header)
  for (let i = 0; i < offsets.length; i++) out.writeUInt32LE(offsets[i], entryOffsetsAt + i * 4)
  let cursor = blobAt
  for (const blob of entryBlobs) {
    blob.copy(out, cursor)
    cursor += blob.length
  }
  out.writeUInt32LE(listChunks.length, listsAt)
  listBlob.copy(out, listsAt + 4)
  return { file, bytes: out, entries: rows.length, lists: listChunks.length }
}
