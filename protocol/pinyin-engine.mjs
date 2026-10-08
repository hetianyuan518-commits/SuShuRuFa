// 全拼 / 九键 / 简拼 / 短码候选。各端引擎与本文件保持同一规则。
import priorTable from './char-prior.json' with { type: 'json' }
import rulesTable from './ime-rules.json' with { type: 'json' }

const CHAR_PRIOR = priorTable
// 排序权重和「前词 → 后词」搭配表来自 ime-rules.json，三端共用同一份数据。
export const RULES = rulesTable
const WEIGHT = RULES.weights
const NEXT = new Map(Object.entries(RULES.next || {}).map(([word, list]) => [word, new Set(list)]))

export function nextWords(word) {
  const text = word || ''
  const direct = RULES.next ? RULES.next[text] : null
  if (direct && direct.length) return direct
  const chars = Array.from(text)
  if (chars.length > 1) {
    const tail = RULES.next ? RULES.next[chars[chars.length - 1]] : null
    if (tail && tail.length) return tail
  }
  return []
}

export const SYLLABLE_LIST = `
a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
e ei en eng er
fa fan fang fei fen feng fo fou fu
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo
o ou
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
wa wai wan wang wei wen weng wo wu
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
`.trim().split(/\s+/)

export const SYLLABLES = new Set(SYLLABLE_LIST)

const SPELL_PREFIXES = new Set()
for (const syl of SYLLABLES) {
  for (let len = 1; len <= syl.length; len++) SPELL_PREFIXES.add(syl.slice(0, len))
}

const T9 = {
  a: '2', b: '2', c: '2', d: '3', e: '3', f: '3', g: '4', h: '4', i: '4',
  j: '5', k: '5', l: '5', m: '6', n: '6', o: '6', p: '7', q: '7', r: '7', s: '7',
  t: '8', u: '8', v: '8', w: '9', x: '9', y: '9', z: '9'
}

export function compactPinyin(pinyin) {
  return (pinyin || '').toLowerCase().replace(/[^a-z]/g, '')
}

export function toT9(compact) {
  let out = ''
  for (const ch of compact) out += T9[ch] || ''
  return out
}

function isSyllablePrefix(text) {
  return Boolean(text) && SPELL_PREFIXES.has(text)
}

export function segmentAllowPartial(input) {
  const memo = new Map()
  function rec(i) {
    if (memo.has(i)) return memo.get(i)
    if (i === input.length) {
      const done = { syls: [], partial: '' }
      memo.set(i, done)
      return done
    }
    const max = Math.min(6, input.length - i)
    for (let len = max; len >= 1; len--) {
      const piece = input.slice(i, i + len)
      if (!SYLLABLES.has(piece)) continue
      const rest = rec(i + len)
      if (rest) {
        const hit = { syls: [piece, ...rest.syls], partial: rest.partial }
        memo.set(i, hit)
        return hit
      }
    }
    const tail = input.slice(i)
    if (tail.length <= 6 && isSyllablePrefix(tail) && !SYLLABLES.has(tail)) {
      const partial = { syls: [], partial: tail }
      memo.set(i, partial)
      return partial
    }
    memo.set(i, null)
    return null
  }
  return rec(0)
}

export function segmentFull(compact) {
  const got = segmentAllowPartial(compact)
  if (!got || got.partial) return null
  return got.syls
}

function syllablesOf(pinyin) {
  const raw = (pinyin || '').toLowerCase().trim()
  if (!raw) return null
  const parts = raw.split(/\s+/).map((part) => part.replace(/[^a-z]/g, '')).filter(Boolean)
  if (parts.length === 0) return null
  if (parts.length > 1) return parts.every((part) => SYLLABLES.has(part)) ? parts : null
  return segmentFull(parts[0])
}

function alignedTo(input, syls) {
  const got = segmentAllowPartial(input)
  if (!syls || !got || got.syls.length > syls.length) return false
  for (let i = 0; i < got.syls.length; i++) {
    if (syls[i] !== got.syls[i]) return false
  }
  if (!got.partial) return true
  const next = syls[got.syls.length]
  return Boolean(next && next.startsWith(got.partial))
}

function alignedParts(parts, syls) {
  if (!syls || parts.length > syls.length || parts.length === 0) return false
  let any = false
  for (let i = 0; i < parts.length; i++) {
    const part = parts[i]
    if (!part) continue
    any = true
    if (!syls[i].startsWith(part)) return false
  }
  return any
}

function extendsByOneSyllable(input, pinyin) {
  const compact = compactPinyin(pinyin)
  const syls = syllablesOf(pinyin)
  if (!syls || syls.length < 2 || !input) return false
  const tail = syls[syls.length - 1]
  return compact.length === input.length + tail.length && compact.startsWith(input)
}

function qwertyScore(raw, pinyin) {
  const compact = compactPinyin(pinyin)
  const syls = syllablesOf(pinyin)
  if (!compact || !syls) return 0
  if (raw.includes("'")) {
    const parts = raw.split("'").map((part) => part.replace(/[^a-z]/g, ''))
    if (!alignedParts(parts, syls)) return 0
    return compact === parts.join('') ? 5000 : 4000 - (compact.length - parts.join('').length)
  }
  const input = raw.replace(/[^a-z]/g, '')
  if (!input) return 0
  if (compact === input) return 5000
  if (alignedTo(input, syls)) return 4000 - (compact.length - input.length)
  const ini = syls.map((part) => part[0]).join('')
  if (ini === input && input !== compact) return 2500
  if (input.length >= 2 && ini.startsWith(input) && input !== compact) return 1500 - (ini.length - input.length)
  return 0
}

function t9Score(raw, pinyin) {
  const input = raw.replace(/[^2-9]/g, '')
  if (!input || input !== raw.replace(/'/g, '')) return 0
  const digits = toT9(compactPinyin(pinyin))
  if (!digits.startsWith(input)) return 0
  return 3000 - (digits.length - input.length) + (digits === input ? 500 : 0)
}

function scorePinyin(raw, mode, pinyin) {
  if (mode === 't9') return t9Score(raw, pinyin)
  return qwertyScore(raw.toLowerCase(), pinyin)
}

function activeItems(items, now) {
  return (items || []).filter((item) => {
    if (!item || item.deleted || item.status !== 'published') return false
    if (item.validFrom && now < item.validFrom) return false
    if (item.validTo && now > item.validTo) return false
    return true
  })
}

function officialCandidate(item, score) {
  const image = item.type === 'image'
  return {
    text: image ? item.title : (item.type === 'lexicon' ? item.body : item.title),
    commit: image ? '' : item.body,
    source: 'official',
    kind: item.type,
    itemId: item.id,
    assetId: item.assetId || null,
    hint: item.shortcut || item.pinyin || item.groupName || '',
    score
  }
}

function dedupe(list) {
  const seen = new Set()
  const out = []
  for (const row of list) {
    const key = row.itemId ? `id:${row.itemId}` : `t:${row.commit}`
    if (seen.has(key)) continue
    seen.add(key)
    out.push(row)
  }
  return out
}

export function candidates({ raw, mode = 'qwerty', lexicon = [], items = [], now = Date.now(), limit = 20 }) {
  const input = (raw || '').trim().toLowerCase()
  if (!input) return []
  const pool = activeItems(items, now)
  const rows = []

  if (input.startsWith('v')) {
    for (const item of pool) {
      const shortcut = (item.shortcut || '').toLowerCase()
      if (shortcut && (shortcut === input || shortcut.startsWith(input))) {
        rows.push(officialCandidate(item, 2000000 - shortcut.length))
      }
    }
    rows.sort((a, b) => b.score - a.score)
    return rows.slice(0, limit)
  }

  for (const item of pool) {
    const typed = scorePinyin(input, mode, item.pinyin || '')
    if (typed > 0) rows.push(officialCandidate(item, typed + 1000000))
    const names = [item.title, ...(item.aliases || [])]
    if (names.some((name) => name && (name === input || name.startsWith(input)))) {
      rows.push(officialCandidate(item, 1500000))
    }
  }

  for (const entry of shortlist(lexicon, input, mode)) {
    const typed = scorePinyin(input, mode, entry.p || '')
    if (typed <= 0) continue
    let score = typed + (entry.f || 0)
    const letters = input.replace(/[^a-z]/g, '')
    if (mode !== 't9' && letters.length >= 12 && (entry.f || 0) >= 300 && extendsByOneSyllable(letters, entry.p || '')) {
      score += 800
    }
    rows.push({
      text: entry.w,
      commit: entry.w,
      source: 'lexicon',
      kind: 'lexicon',
      itemId: null,
      assetId: null,
      hint: entry.p,
      score
    })
  }
  if (mode !== 't9') {
    const letters = input.replace(/[^a-z]/g, '')
    const parts = input.includes("'") ? input.split("'").map((part) => part.replace(/[^a-z]/g, '')) : null
    const singleInitials = parts && letters.length >= 2 && parts.every((part) => part.length <= 1)
    if (!parts) {
      for (const row of sentenceCandidates(lexicon, letters)) rows.push(row)
    }
    // xiexien 能被 xie xi en 盖满，但用户多半是「谢谢 + n」；niha 也多半是「你好」没打完。
    // 末尾音节还能往下长时也走混拼，让整词有机会压过「你哈」这种凑出来的说法。
    const tokens = letters.length >= 2 ? mixTokens(letters) : null
    const tailInitial = Boolean(tokens && tokens.length >= 2 && longerThan(tokens[tokens.length - 1]).length > 0)
    if (letters.length >= 2 && (!parts || singleInitials) && (tailInitial || !pinyinCovers(letters))) {
      const mixed = mixedSentences(lexicon, letters)
      const rowsToAdd = mixed.length ? mixed : initialSentences(lexicon, letters)
      for (const row of rowsToAdd) rows.push(row)
    }
  }

  rows.sort((a, b) => b.score - a.score || a.text.length - b.text.length)
  return dedupe(rows).slice(0, limit)
}

export function associate({ last, lexicon = [], items = [], now = Date.now(), limit = 8 }) {
  const word = (last || '').trim()
  if (!word) return []
  const rows = []
  for (const item of activeItems(items, now)) {
    if (item.type === 'image') continue
    const names = [item.title, ...(item.aliases || [])]
    if (!names.includes(word)) continue
    rows.push(officialCandidate(item, 1000000))
  }
  for (const entry of associateList(lexicon, word)) {
    if (!entry.w || !entry.w.startsWith(word) || entry.w === word) continue
    const suffix = entry.w.slice(word.length)
    rows.push({
      text: entry.w,
      commit: suffix,
      source: 'lexicon',
      kind: 'lexicon',
      itemId: null,
      assetId: null,
      hint: entry.p,
      score: associationScore(entry.w, entry.f || 0)
    })
  }
  // 「下一个词」联想：上屏 我想 以后给出 要 / 你 / 去，而不只是以「我想」开头的更长词。
  const wordLength = Array.from(word).length
  const pairs = rows.filter((row) => !row.itemId && Array.from(row.text).length === wordLength + 1)
  const anchor = pairs.reduce((best, row) => Math.max(best, row.score), 0) || 480
  nextWords(word).forEach((follow, index) => {
    const text = word + follow
    if (rows.some((row) => row.text === text)) return
    rows.push({
      text,
      commit: follow,
      source: 'lexicon',
      kind: 'lexicon',
      itemId: null,
      assetId: null,
      hint: '',
      score: anchor - WEIGHT.associateNextStep * (index + 1)
    })
  })
  rows.sort((a, b) => b.score - a.score)
  return dedupe(rows).slice(0, limit)
}

const indexCache = new WeakMap()

function pushBucket(map, key, row, cap) {
  let list = map.get(key)
  if (!list) {
    list = []
    map.set(key, list)
  }
  if (list.length < cap) list.push(row)
}

function pushPrefixes(map, key, row, minLen, maxLen, cap) {
  const end = Math.min(key.length, maxLen)
  for (let len = minLen; len <= end; len++) pushBucket(map, key.slice(0, len), row, cap)
}

function buildIndex(lexicon) {
  const cached = indexCache.get(lexicon)
  if (cached) return cached
  const prepared = []
  for (const entry of lexicon || []) {
    const syls = syllablesOf(entry.p || '')
    if (!syls || syls.length > 16) continue
    prepared.push({
      w: entry.w,
      p: entry.p,
      f: entry.f || 0,
      syls,
      ini: syls.map((part) => part[0]).join(''),
      t9: toT9(syls.join(''))
    })
  }
  const ranked = prepared.slice().sort((a, b) => b.f - a.f)
  prepared.sort((a, b) => {
    const n = Math.min(a.syls.length, b.syls.length)
    for (let i = 0; i < n; i++) {
      if (a.syls[i] < b.syls[i]) return -1
      if (a.syls[i] > b.syls[i]) return 1
    }
    return a.syls.length - b.syls.length
  })
  const ini = new Map()
  const exactIni = new Map()
  const t9 = new Map()
  const head = new Map()
  for (const row of ranked) {
    pushPrefixes(ini, row.ini, row, 2, 4, 80)
    if (row.ini.length >= 1 && row.ini.length <= 16) pushBucket(exactIni, row.ini, row, 12)
    pushPrefixes(t9, row.t9, row, 2, 5, 60)
    const ch = Array.from(row.w || '')[0]
    if (ch) pushBucket(head, ch, row, 100)
  }
  const sylFreq = new Map()
  for (const row of prepared) {
    if (row.syls.length !== 1 || Array.from(row.w || '').length !== 1) continue
    const weight = rowWeight(row)
    const prev = sylFreq.get(row.syls[0]) || 0
    if (weight > prev) sylFreq.set(row.syls[0], weight)
  }
  const index = { prepared, ini, exactIni, t9, head, sylFreq }
  indexCache.set(lexicon, index)
  return index
}

function compareSyllables(syls, prefix) {
  const n = Math.min(syls.length, prefix.length)
  for (let i = 0; i < n; i++) {
    if (syls[i] < prefix[i]) return -1
    if (syls[i] > prefix[i]) return 1
  }
  return syls.length < prefix.length ? -1 : 0
}

function startsWithSyllables(syls, prefix) {
  if (syls.length < prefix.length) return false
  for (let i = 0; i < prefix.length; i++) if (syls[i] !== prefix[i]) return false
  return true
}

function lowerBound(rows, prefix) {
  let lo = 0
  let hi = rows.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    if (compareSyllables(rows[mid].syls, prefix) < 0) lo = mid + 1
    else hi = mid
  }
  return lo
}

function upperBound(rows, prefix) {
  let lo = 0
  let hi = rows.length
  while (lo < hi) {
    const mid = (lo + hi) >> 1
    const syls = rows[mid].syls
    if (compareSyllables(syls, prefix) < 0 || startsWithSyllables(syls, prefix)) lo = mid + 1
    else hi = mid
  }
  return lo
}

function takeSpan(rows, lo, hi, exactLen, into, seen) {
  const exact = []
  const rest = []
  for (let i = lo; i < hi; i++) {
    const row = rows[i]
    if (seen.has(row)) continue
    if (row.syls.length === exactLen) exact.push(row)
    else rest.push(row)
  }
  for (const row of exact.slice(0, 500)) {
    seen.add(row)
    into.push(row)
  }
  if (rest.length > 200) rest.sort((a, b) => b.f - a.f)
  for (const row of rest.slice(0, 200)) {
    seen.add(row)
    into.push(row)
  }
}

function syllablesStarting(prefix) {
  if (!prefix) return []
  const list = []
  for (const syl of SYLLABLES) if (syl.startsWith(prefix)) list.push(syl)
  return list
}

export function segmentAll(input, limit = 8) {
  const found = []
  const acc = []
  function rec(i) {
    if (found.length >= limit) return
    if (i === input.length) {
      found.push({ syls: acc.slice(), partial: '' })
      return
    }
    const max = Math.min(6, input.length - i)
    let any = false
    for (let len = max; len >= 1; len--) {
      const piece = input.slice(i, i + len)
      if (!SYLLABLES.has(piece)) continue
      any = true
      acc.push(piece)
      rec(i + len)
      acc.pop()
      if (found.length >= limit) return
    }
    if (!any) {
      const tail = input.slice(i)
      if (tail && tail.length <= 6 && isSyllablePrefix(tail)) found.push({ syls: acc.slice(), partial: tail })
    }
  }
  rec(0)
  return found
}

function collectSegment(rows, seg, into, seen) {
  if (seg.partial) {
    for (const syl of syllablesStarting(seg.partial)) {
      const prefix = seg.syls.concat([syl])
      takeSpan(rows, lowerBound(rows, prefix), upperBound(rows, prefix), prefix.length, into, seen)
    }
    return
  }
  if (seg.syls.length === 0) return
  takeSpan(rows, lowerBound(rows, seg.syls), upperBound(rows, seg.syls), seg.syls.length, into, seen)
}

function collectApostrophe(rows, parts, into, seen) {
  const needed = parts.reduce((count, part) => count + (part ? 1 : 0), 0)
  for (const first of syllablesStarting(parts[0] || '')) {
    const lo = lowerBound(rows, [first])
    const hi = upperBound(rows, [first])
    const matched = []
    for (let i = lo; i < hi; i++) {
      const row = rows[i]
      if (parts.length > row.syls.length) continue
      let ok = true
      for (let p = 0; p < parts.length; p++) {
        if (!parts[p]) continue
        if (!row.syls[p] || !row.syls[p].startsWith(parts[p])) {
          ok = false
          break
        }
      }
      if (ok) matched.push(row)
    }
    const exact = []
    const rest = []
    for (const row of matched) {
      if (seen.has(row)) continue
      if (row.syls.length === needed) exact.push(row)
      else rest.push(row)
    }
    for (const row of exact.slice(0, 500)) {
      seen.add(row)
      into.push(row)
    }
    if (rest.length > 200) rest.sort((a, b) => b.f - a.f)
    for (const row of rest.slice(0, 200)) {
      seen.add(row)
      into.push(row)
    }
  }
}

const longerCache = new Map()

function longerThan(tail) {
  if (!tail || tail.length > 5) return []
  const cached = longerCache.get(tail)
  if (cached) return cached
  const hit = []
  for (const syl of SYLLABLES) {
    if (syl.length > tail.length && syl.startsWith(tail)) hit.push(syl)
  }
  longerCache.set(tail, hit)
  return hit
}

function topExact(rows, prefix, k) {
  let index = lowerBound(rows, prefix)
  const bag = []
  while (index < rows.length && bag.length < 48) {
    const row = rows[index++]
    if (row.syls.length !== prefix.length || !startsWithSyllables(row.syls, prefix)) break
    bag.push(row)
  }
  if (bag.length > k) bag.sort((a, b) => rowWeight(b) - rowWeight(a) || (b.f || 0) - (a.f || 0))
  return bag.slice(0, k)
}

function rowWeight(row) {
  if (row.syls.length === 1 && CHAR_PRIOR[row.w]) return CHAR_PRIOR[row.w]
  return row.f || 0
}

function hasPrefix(rows, prefix) {
  const next = rows[lowerBound(rows, prefix)]
  return Boolean(next && startsWithSyllables(next.syls, prefix))
}

const GRAMMAR = new Set('的了是在有要就都也还把被给让我你他她不没很会能到去来和与或而这那个着过吗呢吧啊'.split(''))

// 供测试和排查用：分别看三条组句路径各自给出什么。
export const internals = {
  sentence: (lexicon, input) => sentenceCandidates(lexicon, input),
  mixed: (lexicon, input) => mixedSentences(lexicon, input),
  initial: (lexicon, input) => initialSentences(lexicon, input),
  tokens: (input) => mixTokens(input),
  segments: (input) => mixSegmentations(input)
}

function pinyinCovers(input) {
  for (const seg of segmentAll(input, 1)) {
    if (!seg.partial && seg.syls.join('') === input) return true
  }
  return false
}

function edgeGain(word, freq, syllables, loose = 0) {
  const base = syllables >= 2 ? freq + WEIGHT.syllableGain * (syllables - 1) : (CHAR_PRIOR[word] || freq)
  return base - loose * WEIGHT.looseCost
}

// 简拼里的单字：常用字按字频给分，再减去一个底数，让生僻字组合沉下去。
function initialGain(word, freq, syllables) {
  if (syllables >= 2) return freq + WEIGHT.initialWordGain
  if (GRAMMAR.has(word)) return WEIGHT.initialGrammarGain
  const prior = CHAR_PRIOR[word] || 0
  return prior >= 300 ? prior - 300 : WEIGHT.initialCharGain
}

function mixSpellOptions(spell, sylFreq) {
  const ranked = []
  for (const syl of SYLLABLES) if (syl.startsWith(spell)) ranked.push(syl)
  ranked.sort((a, b) => (sylFreq.get(b) || 0) - (sylFreq.get(a) || 0) || a.length - b.length || (a < b ? -1 : 1))
  const picked = []
  const seen = new Set()
  if (SYLLABLES.has(spell)) {
    picked.push(spell)
    seen.add(spell)
  }
  for (const syl of ranked) {
    if (seen.has(syl)) continue
    picked.push(syl)
    if (picked.length >= 16) break
  }
  return picked
}

function mixTokens(input) {
  if (!input || input.length > 24) return null
  const tokens = []
  let i = 0
  while (i < input.length) {
    const max = Math.min(6, input.length - i)
    let best = 0
    for (let len = 1; len <= max; len++) {
      if (!isSyllablePrefix(input.slice(i, i + len))) break
      best = len
    }
    if (!best) return null
    tokens.push(input.slice(i, i + best))
    i += best
  }
  return tokens
}

// 贪心会把 bangongs 切成 bang + o + n + g + s。完整音节把最后一个字母让给后面，
// 就能凑出 ban + gong + s（办公室）。最多再试 3 种切法。
function mixSegmentations(input) {
  const base = mixTokens(input)
  if (!base) return []
  const found = [base]
  // yan+e 已经是完整音节，不要再拆成 ya+ne，否则「要呢」会压过「严恶」。
  // bang+o+n+g+s 里有切不开的单字母，才把 bang 的 g 让给后面，凑出 ban+gong+s。
  if (base.every((tok) => SYLLABLES.has(tok))) return found
  const seen = new Set([base.join('\0')])
  for (let i = 0; i < base.length && found.length < 4; i++) {
    const tok = base[i]
    if (tok.length < 3 || !SYLLABLES.has(tok)) continue
    const head = tok.slice(0, -1)
    if (!SYLLABLES.has(head)) continue
    const rest = mixTokens(tok.slice(-1) + base.slice(i + 1).join(''))
    if (!rest || rest[0].length < 2) continue
    const alt = base.slice(0, i).concat(head, rest)
    if (alt.length > 16) continue
    const key = alt.join('\0')
    if (seen.has(key)) continue
    seen.add(key)
    found.push(alt)
  }
  return found
}

function followsOf(word) {
  if (!word) return null
  const direct = NEXT.get(word)
  if (direct && direct.size) return direct
  const chars = Array.from(word)
  if (chars.length > 1) return NEXT.get(chars[chars.length - 1]) || null
  return null
}

function matchMixed(rows, spells, budget, sylFreq, k = 12) {
  const options = spells.map((spell) => mixSpellOptions(spell, sylFreq))
  const path = []
  const bag = []
  const seen = new Set()
  function rec(pos) {
    if (budget.left <= 0) return
    if (pos === spells.length) {
      for (const row of topExact(rows, path, k)) {
        if (seen.has(row)) continue
        seen.add(row)
        bag.push(row)
      }
      return
    }
    for (const syl of options[pos]) {
      path.push(syl)
      budget.left -= 1
      if (hasPrefix(rows, path)) rec(pos + 1)
      path.pop()
      if (budget.left <= 0) return
    }
  }
  rec(0)
  bag.sort((a, b) => rowWeight(b) - rowWeight(a) || (b.f || 0) - (a.f || 0))
  return bag.slice(0, k)
}

// niyaoqun 贪心切出来是 ni yao qun，但用户多半是「你要去 + n」：末尾音节能拆成「音节 + 首字母」时，两种切法都算。
function mixedSentences(lexicon, input) {
  const segmentations = mixSegmentations(input)
  const out = []
  const seen = new Set()
  for (const tokens of segmentations) {
    for (const row of mixedSentencesFor(lexicon, tokens)) {
      if (seen.has(row.text)) continue
      seen.add(row.text)
      out.push(row)
    }
    if (!tokens || tokens.length < 2) continue
    const last = tokens[tokens.length - 1]
    if (last.length >= 2 && SYLLABLES.has(last.slice(0, -1)) && isSyllablePrefix(last.slice(-1))) {
      const split = tokens.slice(0, -1).concat(last.slice(0, -1), last.slice(-1))
      for (const row of mixedSentencesFor(lexicon, split)) {
        if (seen.has(row.text)) continue
        seen.add(row.text)
        out.push(row)
      }
    }
  }
  out.sort((a, b) => b.score - a.score)
  return out
}

function mixedSentencesFor(lexicon, tokens) {
  if (!tokens || tokens.length < 2 || tokens.length > 16 || tokens.every((token) => token.length === 1)) return []
  const glued = tokens.filter((token) => token.length > 1)
  if (glued.every((token) => !SYLLABLES.has(token))) return []
  const index = buildIndex(lexicon)
  const rows = index.prepared
  const sylFreq = index.sylFreq
  // 单个字母最常见的读音（w→wo，d→de）。偏门读音（晚、点）在这里扣一档，避免「晚点」压过「我的」。
  const bestInitial = new Map()
  const initialBest = (letter) => {
    let got = bestInitial.get(letter)
    if (got != null) return got
    let best = 0
    for (const [syl, weight] of sylFreq) {
      if (syl[0] === letter && weight > best) best = weight
    }
    bestInitial.set(letter, best)
    return best
  }
  const n = tokens.length
  const edges = Array.from({ length: n }, () => [])
  const budget = { left: 12000 }
  for (let i = 0; i < n; i++) {
    const acc = []
    for (let j = i; j < n && acc.length < 8; j++) {
      acc.push(tokens[j])
      const seenRow = new Set()
      const hits = matchMixed(rows, acc, budget, sylFreq)
      // 音节枚举有预算，zen 这种排不进前 16 的音节会漏掉；再按首字母桶把整词补上（zmb → 怎么办）。
      for (const row of index.exactIni.get(acc.map((spell) => spell[0]).join('')) || []) {
        if (row.syls.length !== acc.length) continue
        if (!row.syls.every((syl, s) => syl.startsWith(acc[s]))) continue
        hits.push(row)
      }
      for (const row of hits) {
        if (seenRow.has(row)) continue
        seenRow.add(row)
        const syllables = row.syls.length
        const freq = row.f || 0
        // 只打了首字母的位置（wozd 的 z、d）任何音节都一样可能，不算松；打了半个音节（xia → xiang）才算。
        let loose = 0
        let abbrev = 0
        for (let s = 0; s < syllables; s++) {
          if (acc[s].length >= 2) loose += Math.max(0, row.syls[s].length - acc[s].length)
          if (acc[s].length === 1) {
            const got = sylFreq.get(row.syls[s]) || 0
            if (got + WEIGHT.looseCost < initialBest(acc[s])) abbrev += WEIGHT.looseCost
          }
        }
        let gain = edgeGain(row.w, freq, syllables, loose) - abbrev
        // 只打了一个首字母的单字（wozd 里的 d）最不确定，压一档，让「我知道」整词压过「我在的」。
        if (syllables === 1 && acc[0].length === 1) gain -= WEIGHT.initialCharCost
        edges[i].push({
          to: j + 1,
          w: row.w,
          f: freq,
          loose,
          gain,
          singleTail: tokens[j].length === 1 && syllables === 1
        })
      }
    }
  }
  const beam = Array.from({ length: n + 1 }, () => [])
  beam[0].push({ score: 0, freq: 0, text: '', words: 0, loose: 0, last: '', follow: 0 })
  for (let i = 0; i < n; i++) {
    if (beam[i].length > 8) {
      beam[i].sort((a, b) => b.score - a.score)
      beam[i].length = 8
    }
    for (const state of beam[i]) {
      const follow = followsOf(state.last)
      for (const edge of edges[i]) {
        const followed = follow && follow.has(edge.w)
        beam[edge.to].push({
          score: state.score + edge.gain + (followed ? WEIGHT.mixedFollowGain : 0),
          freq: state.freq + edge.f,
          text: state.text + edge.w,
          words: state.words + 1,
          loose: state.loose + edge.loose,
          last: edge.w,
          follow: 0
        })
      }
    }
  }
  const full = beam[n].slice().sort((a, b) => b.score - a.score)
  const out = rankStates(full, (state, rank) => {
    if (state.words === 1) return 4600 + Math.min(state.freq, 489) - state.loose * 12
    return 3700 - rank * 5
  }, 8)
  const seenText = new Set(out.map((row) => row.text))
  let prefixRank = 0
  for (let i = n - 1; i >= 1 && prefixRank < 3; i--) {
    const state = beam[i].slice().sort((a, b) => b.score - a.score)[0]
    if (!state || Array.from(state.text).length < 2 || seenText.has(state.text)) continue
    seenText.add(state.text)
    out.push(sentenceRow(state.text, 3000 - prefixRank++ * 5, 0))
  }
  return out
}

function sentenceRow(text, score, keep) {
  return { text, commit: text, source: 'lexicon', kind: 'lexicon', itemId: null, assetId: null, hint: '', score, keep }
}

// 同一段文字可能既是整词（你好）又是两个单字拼出来的（你 + 好）。
// 搭配加分只用来在束搜索里挑路径，最后按整词 / 多词的档位取高的那条，整词不会被自己的拆法压下去。
function rankStates(states, tier, limit) {
  const scored = new Map()
  let multiRank = 0
  for (const state of states) {
    if (!state || !state.text) continue
    let score
    if (state.words === 1) {
      score = tier(state, 0)
    } else {
      if (scored.has(state.text)) continue
      score = tier(state, multiRank++)
    }
    const prev = scored.get(state.text)
    if (prev && prev.score >= score) continue
    scored.set(state.text, sentenceRow(state.text, score, 0))
  }
  return [...scored.values()].sort((a, b) => b.score - a.score).slice(0, limit)
}

function initialSentences(lexicon, input) {
  if (!input || input.length < 2 || input.length > 24) return []
  const exact = buildIndex(lexicon).exactIni
  const n = input.length
  const edges = Array.from({ length: n }, () => [])
  for (let i = 0; i < n; i++) {
    const end = Math.min(n, i + 16)
    for (let j = i + 1; j <= end; j++) {
      const hits = exact.get(input.slice(i, j))
      if (!hits) continue
      for (const row of hits) {
        const syllables = row.syls.length
        const freq = row.f || 0
        edges[i].push({ to: j, w: row.w, f: freq, gain: initialGain(row.w, freq, syllables) })
      }
    }
  }
  const beam = Array.from({ length: n + 1 }, () => [])
  beam[0].push({ score: 0, freq: 0, text: '', words: 0, last: '' })
  for (let i = 0; i < n; i++) {
    if (beam[i].length > 8) {
      beam[i].sort((a, b) => b.score - a.score)
      beam[i].length = 8
    }
    for (const state of beam[i]) {
      const follow = NEXT.get(state.last)
      for (const edge of edges[i]) {
        beam[edge.to].push({
          score: state.score + edge.gain + (follow && follow.has(edge.w) ? WEIGHT.followGain : 0),
          freq: state.freq + edge.f,
          text: state.text + edge.w,
          words: state.words + 1,
          last: edge.w
        })
      }
    }
  }
  const full = beam[n].slice().sort((a, b) => b.score - a.score)
  const out = rankStates(full, (state, rank) => (state.words === 1 ? 2500 + Math.min(state.freq, 489) : 2400 - rank * 5), 5)
  const seen = new Set(out.map((row) => row.text))
  let prefixRank = 0
  for (let i = n - 1; i >= 2 && prefixRank < 4; i--) {
    const state = beam[i].slice().sort((a, b) => b.score - a.score)[0]
    if (!state || Array.from(state.text).length < 2 || seen.has(state.text)) continue
    seen.add(state.text)
    out.push(sentenceRow(state.text, 1800 - prefixRank++ * 5, 0))
  }
  return out
}

function sentenceCandidates(lexicon, input) {
  if (!input || input.length < 2 || input.length > 96) return []
  const rows = buildIndex(lexicon).prepared
  const best = new Map()
  for (const seg of segmentAll(input, 4)) {
    for (const row of decodeSegmentation(rows, seg.syls, seg.partial, input.length)) {
      if (!row.text) continue
      const prev = best.get(row.text)
      if (!prev || row.score > prev.score) best.set(row.text, row)
    }
  }
  const merged = [...best.values()]
  merged.sort((a, b) => b.score - a.score || a.text.length - b.text.length)
  return merged.slice(0, 8)
}

function decodeSegmentation(rows, syls, partial, inputLength) {
  const n = syls.length
  const letters = [0]
  for (const syl of syls) letters.push(letters[letters.length - 1] + syl.length)
  if (n === 0) {
    const out = []
    for (const syl of longerThan(partial)) {
      for (const row of topExact(rows, [syl], 4)) out.push(sentenceHit(row, inputLength, true))
    }
    return out
  }
  const edges = Array.from({ length: n }, () => [])
  const pushSlice = (start, slice, to, isPartial) => {
    const consumed = isPartial ? inputLength - letters[start] : letters[to] - letters[start]
    for (const row of topExact(rows, slice, 4)) {
      const syllables = slice.length
      const freq = row.f || 0
      edges[start].push({
        to,
        w: row.w,
        f: freq,
        full: row.syls.reduce((sum, syl) => sum + syl.length, 0),
        consumed,
        partial: isPartial,
        gain: edgeGain(row.w, freq, syllables)
      })
    }
  }
  for (let i = 0; i < n; i++) {
    const acc = []
    for (let j = i; j < n && acc.length < 16; j++) {
      acc.push(syls[j])
      const matched = hasPrefix(rows, acc)
      if (matched) pushSlice(i, acc.slice(), j + 1, false)
      if (j === n - 1 && !partial) {
        const head = acc.slice(0, -1)
        for (const syl of longerThan(syls[j])) {
          const grown = head.concat(syl)
          if (hasPrefix(rows, grown)) pushSlice(i, grown, n, true)
        }
      }
      if (!matched) break
    }
  }
  if (partial) {
    for (let i = 0; i < n; i++) {
      const head = syls.slice(i)
      if (head.length && !hasPrefix(rows, head)) continue
      for (const syl of longerThan(partial)) {
        const grown = head.concat(syl)
        if (hasPrefix(rows, grown)) pushSlice(i, grown, n, true)
      }
    }
    for (const syl of longerThan(partial)) {
      if (hasPrefix(rows, [syl])) pushSlice(n - 1, [syl], n, true)
    }
  }
  const beam = Array.from({ length: n + 1 }, () => [])
  beam[0].push({ score: 0, freq: 0, text: '', words: 0, partial: false, full: 0, covered: 0, last: '', bestMulti: 0, looseMin: 999, follow: 0 })
  for (let i = 0; i < n; i++) {
    if (beam[i].length > 6) {
      beam[i].sort((a, b) => b.score - a.score)
      beam[i].length = 6
    }
    for (const state of beam[i]) {
      const follow = NEXT.get(state.last)
      for (const edge of edges[i]) {
        const piece = Array.from(edge.w)
        const multi = !edge.partial && piece.length >= 2 ? edge.f : 0
        let looseMin = state.looseMin == null ? 999 : state.looseMin
        if (!(multi >= 320)) {
          for (const ch of piece) looseMin = Math.min(looseMin, CHAR_PRIOR[ch] || 0)
        }
        beam[edge.to].push({
          score: state.score + edge.gain + (follow && follow.has(edge.w) ? WEIGHT.sentenceFollowGain : 0),
          last: edge.w,
          freq: state.freq + edge.f,
          text: state.text + edge.w,
          words: state.words + 1,
          partial: state.partial || edge.partial,
          full: state.full + edge.full,
          covered: state.covered + edge.consumed,
          bestMulti: Math.max(state.bestMulti || 0, multi),
          looseMin,
          follow: state.follow || (follow && follow.has(edge.w) ? 1 : 0)
        })
      }
    }
  }
  let pool = beam[n]
  if (pool.length === 0) {
    for (let i = n - 1; i > 0; i--) {
      if (beam[i].length > 0) {
        pool = beam[i]
        break
      }
    }
  }
  pool.sort((a, b) => b.score - a.score)
  let bestExact = 0
  for (const state of pool) {
    if (state.words === 1 && !state.partial && state.full === state.covered) {
      bestExact = Math.max(bestExact, state.freq || 0)
    }
  }
  // 同一段文字可能既是整词（你好）又是两个单字拼出来的（你 + 好），按最终档位取高的那条。
  const scored = new Map()
  let multiRank = 0
  for (const state of pool) {
    if (!state.text) continue
    const chars = Array.from(state.text)
    const spoken = !state.partial && state.full === state.covered && state.words >= 2 && chars.length === state.words
    const followBonus = state.follow ? 12 : 0
    let score
    if (state.words === 1 && !state.partial && state.full === state.covered) {
      score = 5000 + Math.min(state.freq, 489)
    } else if (state.words === 1 && state.partial) {
      score = 4000 - Math.max(0, state.full - state.covered) + Math.min(state.freq, 489)
    } else if (spoken) {
      let min = 999
      for (const ch of chars) min = Math.min(min, CHAR_PRIOR[ch] || 0)
      // 两个字、而且都是最常用的字（我也 / 把住）时，才压过生僻整词；
      // 三字以上整个打完的（地下道）以及字本身不常用的（双台人身），都排到整词后面，也不能压过别的分词里的常用词。
      score = (bestExact < WEIGHT.spokenRare && min >= WEIGHT.spokenCommonChar && state.words <= 2
        ? 5000 + bestExact + 1 + Math.floor(Math.min(min, WEIGHT.spokenPriorCap) / 10)
        : 4500 + min) + followBonus
    } else if (!state.partial && state.full === state.covered && (state.bestMulti || 0) >= 200) {
      // 常用词盖住的字不再拉低整句，所以「我知道」能压过「我之到」，生僻词仍压不过「你是谁」。
      const min = state.looseMin == null || state.looseMin === 999 ? 450 : state.looseMin
      score = 5000 + 1 + Math.floor(Math.min(min, 450) / 10) + 6 + followBonus
      // 整个输入本来就是一个词（月天、问天）时，拆成「余额 + 天」这种多词说法排在它后面；词频低到不可信的除外。
      if (bestExact >= WEIGHT.wholeWordFloor) score = Math.min(score, 5000 + bestExact - 1)
    } else {
      if (scored.has(state.text)) continue
      score = 3600 - multiRank++ * 5
    }
    const keep = Math.max(0, inputLength - state.covered)
    // 末尾还有没用到的字母（nihaom 里的 m）时，「你好」只是半截，要排在吃掉 m 的「你好吗」后面。
    if (keep > 0) score -= WEIGHT.keepCost
    const prev = scored.get(state.text)
    if (prev && prev.score >= score) continue
    scored.set(state.text, sentenceRow(state.text, score, keep))
  }
  return [...scored.values()].sort((a, b) => b.score - a.score).slice(0, 6)
}

function sentenceHit(row, inputLength, partial) {
  const full = row.syls.reduce((sum, syl) => sum + syl.length, 0)
  const freq = row.f || 0
  const score = partial ? 4000 - Math.max(0, full - inputLength) + Math.min(freq, 489) : 5000 + Math.min(freq, 489)
  return {
    text: row.w,
    commit: row.w,
    source: 'lexicon',
    kind: 'lexicon',
    itemId: null,
    assetId: null,
    hint: '',
    score,
    keep: 0
  }
}


function shortlist(lexicon, raw, mode) {
  const index = buildIndex(lexicon)
  const out = []
  const seen = new Set()
  const pushRows = (rows) => {
    if (!rows) return
    for (const row of rows) {
      if (seen.has(row)) continue
      seen.add(row)
      out.push(row)
    }
  }
  if (mode === 't9') {
    const digits = raw.replace(/[^2-9]/g, '')
    if (!digits) return []
    pushRows(index.t9.get(digits.length <= 5 ? digits : digits.slice(0, 5)))
    return out
  }
  if (raw.includes("'")) {
    const parts = raw.split("'").map((part) => part.replace(/[^a-z]/g, ''))
    const letters = parts.join('')
    if (letters.length >= 2 && parts.every((part) => part.length <= 1)) {
      pushRows(index.ini.get(letters.length <= 4 ? letters : letters.slice(0, 4)))
      return out
    }
    collectApostrophe(index.prepared, parts, out, seen)
    return out
  }
  const input = raw.replace(/[^a-z]/g, '')
  if (input.length >= 2) pushRows(index.ini.get(input.length <= 4 ? input : input.slice(0, 4)))
  for (const seg of segmentAll(input)) collectSegment(index.prepared, seg, out, seen)
  return out
}

function associationScore(word, freq) {
  const length = Array.from(word).length
  const extra = Math.max(0, length - 2)
  return freq - extra * WEIGHT.associateLengthCost + (length === 2 ? WEIGHT.associatePairBonus : 0)
}

function associateList(lexicon, word) {
  const index = buildIndex(lexicon)
  const ch = Array.from(word)[0]
  return (index.head.get(ch) || []).filter((row) => row.w.startsWith(word) && row.w !== word)
}
