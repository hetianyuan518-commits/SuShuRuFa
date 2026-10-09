import { LexEntry, charPrior, nextWords } from './ContentRead'
import { SYLLABLES, hasSyllable, isSyllablePrefix } from './Syllables'

interface MixHit {
  text: string
  commit: string
  source: string
  kind: string
  itemId: string
  assetId: string
  hint: string
  score: number
}

class MixRow {
  w: string = ''
  p: string = ''
  f: number = 0
  syls: string[] = []
  ini: string = ''
}

class MixState {
  score: number = 0
  freq: number = 0
  text: string = ''
  words: number = 0
  loose: number = 0
  last: string = ''
}

class MixEdge {
  to: number = 0
  word: string = ''
  freq: number = 0
  loose: number = 0
  gain: number = 0
}

let cachedRows: MixRow[] = []
let cachedExact: Map<string, MixRow[]> = new Map()
let cachedFreq: Map<string, number> = new Map()
let cachedCount: number = -1
let cachedHead: string = ''
let cachedTail: string = ''

export function mixedSentences(input: string, lexicon: LexEntry[]): MixHit[] {
  if (input.length > 24) {
    return []
  }
  let tokens = mixTokens(input)
  let tailInitial = tokens.length >= 2 && longerThan(tokens[tokens.length - 1]).length > 0
  if (!tailInitial && pinyinCovers(input)) {
    return []
  }
  let out: MixHit[] = []
  let seen: Set<string> = new Set()
  let groups = mixSegmentations(input)
  for (let g = 0; g < groups.length; g++) {
    let rows = mixedFor(groups[g], lexicon)
    for (let i = 0; i < rows.length; i++) {
      pushRow(out, seen, rows[i])
    }
    let list = groups[g]
    if (list.length < 2) {
      continue
    }
    let last = list[list.length - 1]
    let head = last.substring(0, last.length - 1)
    let tail = last.substring(last.length - 1)
    if (last.length >= 2 && hasSyllable(head) && isSyllablePrefix(tail)) {
      let split: string[] = []
      for (let i = 0; i < list.length - 1; i++) {
        split.push(list[i])
      }
      split.push(head)
      split.push(tail)
      let extra = mixedFor(split, lexicon)
      for (let i = 0; i < extra.length; i++) {
        pushRow(out, seen, extra[i])
      }
    }
  }
  out.sort((a: MixHit, b: MixHit) => b.score - a.score)
  return out
}

function pushRow(out: MixHit[], seen: Set<string>, row: MixHit): void {
  if (!row.text || seen.has(row.text)) {
    return
  }
  seen.add(row.text)
  out.push(row)
}

function mixTokens(input: string): string[] {
  if (input.length > 24) {
    return []
  }
  let tokens: string[] = []
  let i = 0
  while (i < input.length) {
    let max = Math.min(6, input.length - i)
    let best = 0
    for (let len = 1; len <= max; len++) {
      let piece = input.substring(i, i + len)
      if (!isSyllablePrefix(piece)) {
        break
      }
      best = len
    }
    if (best === 0) {
      return []
    }
    tokens.push(input.substring(i, i + best))
    i += best
  }
  return tokens
}

function mixSegmentations(input: string): string[][] {
  let base = mixTokens(input)
  if (base.length === 0) {
    return []
  }
  let all = true
  for (let i = 0; i < base.length; i++) {
    if (!hasSyllable(base[i])) {
      all = false
    }
  }
  if (all) {
    return [base]
  }
  let found: string[][] = [base]
  let seen: Set<string> = new Set()
  seen.add(base.join('\u0000'))
  for (let i = 0; i < base.length && found.length < 4; i++) {
    let tok = base[i]
    if (tok.length < 3 || !hasSyllable(tok)) {
      continue
    }
    let head = tok.substring(0, tok.length - 1)
    if (!hasSyllable(head)) {
      continue
    }
    let restInput = tok.substring(tok.length - 1)
    for (let k = i + 1; k < base.length; k++) {
      restInput += base[k]
    }
    let rest = mixTokens(restInput)
    if (rest.length === 0 || rest[0].length < 2) {
      continue
    }
    let alt: string[] = []
    for (let k = 0; k < i; k++) {
      alt.push(base[k])
    }
    alt.push(head)
    for (let k = 0; k < rest.length; k++) {
      alt.push(rest[k])
    }
    if (alt.length > 16) {
      continue
    }
    let key = alt.join('\u0000')
    if (seen.has(key)) {
      continue
    }
    seen.add(key)
    found.push(alt)
  }
  return found
}

function longerThan(tail: string): string[] {
  let out: string[] = []
  if (!tail || tail.length > 5) {
    return out
  }
  for (let i = 0; i < SYLLABLES.length; i++) {
    if (SYLLABLES[i].length > tail.length && SYLLABLES[i].startsWith(tail)) {
      out.push(SYLLABLES[i])
    }
  }
  return out
}

function pinyinCovers(input: string): boolean {
  let i = 0
  while (i < input.length) {
    let max = Math.min(6, input.length - i)
    let best = 0
    for (let len = max; len >= 1; len--) {
      if (hasSyllable(input.substring(i, i + len))) {
        best = len
        break
      }
    }
    if (best === 0) {
      return false
    }
    i += best
  }
  return input.length > 0
}

function followsOf(word: string): string[] | null {
  if (!word) {
    return null
  }
  let direct = nextWords.get(word)
  if (direct && direct.length > 0) {
    return direct
  }
  let chars = Array.from(word)
  if (chars.length > 1) {
    return nextWords.get(chars[chars.length - 1]) || null
  }
  return null
}

function mixedFor(tokens: string[], lexicon: LexEntry[]): MixHit[] {
  if (tokens.length < 2 || tokens.length > 16) {
    return []
  }
  let allSingle = true
  let gluedBare = true
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i].length > 1) {
      allSingle = false
      if (hasSyllable(tokens[i])) {
        gluedBare = false
      }
    }
  }
  if (allSingle || gluedBare) {
    return []
  }
  ensureIndex(lexicon)
  let n = tokens.length
  let bestInitial: Map<string, number> = new Map()
  let edges: MixEdge[][] = []
  for (let i = 0; i < n; i++) {
    edges.push([])
  }
  let budget = 12000
  for (let i = 0; i < n; i++) {
    let acc: string[] = []
    for (let j = i; j < n && acc.length < 8; j++) {
      acc.push(tokens[j])
      let hits = matchMixed(acc, budget)
      budget = hits.budget
      let key = ''
      for (let s = 0; s < acc.length; s++) {
        key += acc[s].substring(0, 1)
      }
      let extra = cachedExact.get(key) || []
      for (let e = 0; e < extra.length; e++) {
        if (extra[e].syls.length !== acc.length) {
          continue
        }
        let ok = true
        for (let s = 0; s < acc.length; s++) {
          if (!extra[e].syls[s].startsWith(acc[s])) {
            ok = false
          }
        }
        if (ok) {
          hits.rows.push(extra[e])
        }
      }
      let seenRow: Set<string> = new Set()
      for (let h = 0; h < hits.rows.length; h++) {
        let row = hits.rows[h]
        let id = row.w + '\n' + row.p
        if (seenRow.has(id)) {
          continue
        }
        seenRow.add(id)
        let loose = 0
        let abbrev = 0
        for (let s = 0; s < row.syls.length; s++) {
          if (acc[s].length >= 2) {
            loose += Math.max(0, row.syls[s].length - acc[s].length)
          } else if (acc[s].length === 1) {
            let got = cachedFreq.get(row.syls[s]) || 0
            if (got + 40 < initialBest(acc[s], bestInitial)) {
              abbrev += 40
            }
          }
        }
        let gain = edgeGain(row.w, row.f, row.syls.length, loose) - abbrev
        if (row.syls.length === 1 && acc[0].length === 1) {
          gain -= 200
        }
        let edge = new MixEdge()
        edge.to = j + 1
        edge.word = row.w
        edge.freq = row.f
        edge.loose = loose
        edge.gain = gain
        edges[i].push(edge)
      }
    }
  }
  let beam: MixState[][] = []
  for (let i = 0; i <= n; i++) {
    beam.push([])
  }
  beam[0].push(new MixState())
  for (let i = 0; i < n; i++) {
    if (beam[i].length > 8) {
      beam[i].sort((a: MixState, b: MixState) => b.score - a.score)
      beam[i] = beam[i].slice(0, 8)
    }
    for (let s = 0; s < beam[i].length; s++) {
      let state = beam[i][s]
      let follow = followsOf(state.last)
      for (let e = 0; e < edges[i].length; e++) {
        let edge = edges[i][e]
        let bonus = 0
        if (follow && follow.indexOf(edge.word) >= 0) {
          bonus = 300
        }
        let next = new MixState()
        next.score = state.score + edge.gain + bonus
        next.freq = state.freq + edge.freq
        next.text = state.text + edge.word
        next.words = state.words + 1
        next.loose = state.loose + edge.loose
        next.last = edge.word
        beam[edge.to].push(next)
      }
    }
  }
  let full = beam[n].slice().sort((a: MixState, b: MixState) => b.score - a.score)
  let scored: Map<string, number> = new Map()
  let order: string[] = []
  let multiRank = 0
  for (let i = 0; i < full.length; i++) {
    let state = full[i]
    if (!state.text) {
      continue
    }
    let score = 0
    if (state.words === 1) {
      score = 4600 + Math.min(state.freq, 489) - state.loose * 12
    } else if (scored.has(state.text)) {
      continue
    } else {
      score = 3700 - multiRank * 5
      multiRank++
    }
    let prev = scored.get(state.text)
    if (prev !== undefined && prev >= score) {
      continue
    }
    if (!scored.has(state.text)) {
      order.push(state.text)
    }
    scored.set(state.text, score)
  }
  let out: MixHit[] = []
  for (let i = 0; i < order.length; i++) {
    out.push(sentence(order[i], scored.get(order[i]) || 0))
  }
  let seenText: Set<string> = new Set()
  for (let i = 0; i < out.length; i++) {
    seenText.add(out[i].text)
  }
  let prefixRank = 0
  for (let i = n - 1; i >= 1 && prefixRank < 3; i--) {
    if (beam[i].length === 0) {
      continue
    }
    let best = beam[i][0]
    for (let s = 1; s < beam[i].length; s++) {
      if (beam[i][s].score > best.score) {
        best = beam[i][s]
      }
    }
    if (Array.from(best.text).length < 2 || seenText.has(best.text)) {
      continue
    }
    seenText.add(best.text)
    out.push(sentence(best.text, 3000 - prefixRank * 5))
    prefixRank++
  }
  // 主句与补充的前缀候选统一在末尾排序并截断到 8：保证返回顺序单调、总数不超上限，
  // 不再出现「先截 8、又追加 3」导致的 11 条且乱序结果。
  out.sort((a: MixHit, b: MixHit) => b.score - a.score)
  if (out.length > 8) {
    out = out.slice(0, 8)
  }
  return out
}

function sentence(text: string, score: number): MixHit {
  return {
    text: text,
    commit: text,
    source: 'lexicon',
    kind: 'lexicon',
    itemId: '',
    assetId: '',
    hint: '',
    score: score
  }
}

function initialBest(letter: string, memo: Map<string, number>): number {
  if (memo.has(letter)) {
    return memo.get(letter) as number
  }
  let best = 0
  cachedFreq.forEach((weight: number, syl: string) => {
    if (syl.length > 0 && syl.substring(0, 1) === letter && weight > best) {
      best = weight
    }
  })
  memo.set(letter, best)
  return best
}

function edgeGain(word: string, freq: number, count: number, loose: number): number {
  let base = freq
  if (count >= 2) {
    base = freq + 560 * (count - 1)
  } else if (charPrior.has(word)) {
    // 已用 has 判存在；值为 0 时直接采用 0，不再用 || 回退到 freq。
    base = charPrior.get(word) as number
  }
  return base - loose * 40
}

function rowWeight(row: MixRow): number {
  if (row.syls.length === 1 && Array.from(row.w).length === 1) {
    // charPrior 中值为 0 是合法先验，不应用 || 回退到 row.f。
    return charPrior.has(row.w) ? (charPrior.get(row.w) as number) : row.f
  }
  return row.f
}

class HitBag {
  rows: MixRow[] = []
  budget: number = 0
}

function matchMixed(spells: string[], budget: number): HitBag {
  let options: string[][] = []
  for (let i = 0; i < spells.length; i++) {
    options.push(mixSpellOptions(spells[i]))
  }
  let path: string[] = []
  let bag = new HitBag()
  bag.budget = budget
  let seen: Set<string> = new Set()
  let rec = (pos: number): void => {
    if (bag.budget <= 0) {
      return
    }
    if (pos === spells.length) {
      let exact = topExact(path, 12)
      for (let i = 0; i < exact.length; i++) {
        let id = exact[i].w + '\n' + exact[i].p
        if (!seen.has(id)) {
          seen.add(id)
          bag.rows.push(exact[i])
        }
      }
      return
    }
    for (let i = 0; i < options[pos].length; i++) {
      path.push(options[pos][i])
      bag.budget--
      if (hasSylPrefix(path)) {
        rec(pos + 1)
      }
      path.pop()
      if (bag.budget <= 0) {
        return
      }
    }
  }
  rec(0)
  bag.rows.sort((a: MixRow, b: MixRow) => rowWeight(b) - rowWeight(a))
  if (bag.rows.length > 12) {
    bag.rows = bag.rows.slice(0, 12)
  }
  return bag
}

function mixSpellOptions(spell: string): string[] {
  let ranked: string[] = []
  for (let i = 0; i < SYLLABLES.length; i++) {
    if (SYLLABLES[i].startsWith(spell)) {
      ranked.push(SYLLABLES[i])
    }
  }
  ranked.sort((a: string, b: string) => {
    let diff = (cachedFreq.get(b) || 0) - (cachedFreq.get(a) || 0)
    if (diff !== 0) {
      return diff
    }
    if (a.length !== b.length) {
      return a.length - b.length
    }
    return a < b ? -1 : 1
  })
  let picked: string[] = []
  let seen: Set<string> = new Set()
  if (hasSyllable(spell)) {
    picked.push(spell)
    seen.add(spell)
  }
  for (let i = 0; i < ranked.length && picked.length < 16; i++) {
    if (!seen.has(ranked[i])) {
      seen.add(ranked[i])
      picked.push(ranked[i])
    }
  }
  return picked
}

function hasSylPrefix(prefix: string[]): boolean {
  let index = lowerRows(prefix)
  return index < cachedRows.length && startsSyl(cachedRows[index].syls, prefix)
}

function topExact(prefix: string[], k: number): MixRow[] {
  let index = lowerRows(prefix)
  let bag: MixRow[] = []
  while (index < cachedRows.length && bag.length < 48) {
    let row = cachedRows[index]
    index++
    if (row.syls.length !== prefix.length || !startsSyl(row.syls, prefix)) {
      break
    }
    bag.push(row)
  }
  bag.sort((a: MixRow, b: MixRow) => rowWeight(b) - rowWeight(a))
  if (bag.length > k) {
    return bag.slice(0, k)
  }
  return bag
}

function startsSyl(syls: string[], prefix: string[]): boolean {
  if (syls.length < prefix.length) {
    return false
  }
  for (let i = 0; i < prefix.length; i++) {
    if (syls[i] !== prefix[i]) {
      return false
    }
  }
  return true
}

function lowerRows(prefix: string[]): number {
  let lo = 0
  let hi = cachedRows.length
  while (lo < hi) {
    let mid = Math.floor((lo + hi) / 2)
    if (sylsBefore(cachedRows[mid].syls, prefix)) {
      lo = mid + 1
    } else {
      hi = mid
    }
  }
  return lo
}

function sylsBefore(syls: string[], prefix: string[]): boolean {
  let n = Math.min(syls.length, prefix.length)
  for (let i = 0; i < n; i++) {
    if (syls[i] < prefix[i]) {
      return true
    }
    if (syls[i] > prefix[i]) {
      return false
    }
  }
  return syls.length < prefix.length
}

function ensureIndex(lexicon: LexEntry[]): void {
  let head = lexicon.length > 0 ? lexicon[0].w : ''
  // 仅用 length + 首词做缓存签名不够：词表被换成同长度、同首词但内容不同的版本时会命中陈旧缓存。
  // 追加末条的 词/拼音/词频 作为签名，同时校验首尾，尽量代表整体内容。
  let last = lexicon.length > 0 ? lexicon[lexicon.length - 1] : undefined
  let tail = last !== undefined ? (last.w + '|' + last.p + '|' + last.f) : ''
  if (cachedCount === lexicon.length && cachedHead === head && cachedTail === tail && cachedRows.length > 0) {
    return
  }
  let prepared: MixRow[] = []
  for (let i = 0; i < lexicon.length; i++) {
    let syls = syllablesOf(lexicon[i].p || '')
    if (syls.length === 0 || syls.length > 16) {
      continue
    }
    let row = new MixRow()
    row.w = lexicon[i].w
    row.p = lexicon[i].p
    row.f = lexicon[i].f || 0
    row.syls = syls
    row.ini = ''
    for (let s = 0; s < syls.length; s++) {
      row.ini += syls[s].substring(0, 1)
    }
    prepared.push(row)
  }
  let ranked = prepared.slice().sort((a: MixRow, b: MixRow) => b.f - a.f)
  prepared.sort((a: MixRow, b: MixRow) => {
    let n = Math.min(a.syls.length, b.syls.length)
    for (let i = 0; i < n; i++) {
      if (a.syls[i] < b.syls[i]) {
        return -1
      }
      if (a.syls[i] > b.syls[i]) {
        return 1
      }
    }
    return a.syls.length - b.syls.length
  })
  // 先把 freq/exact 构建到局部变量，最后再一次性替换所有 cached* 字段，
  // 避免重入/交错调用读到半重建的索引（module 级可变单例的原子切换）。
  let freq: Map<string, number> = new Map()
  for (let i = 0; i < prepared.length; i++) {
    let row = prepared[i]
    if (row.syls.length === 1 && Array.from(row.w).length === 1) {
      // charPrior 命中且值为 0 时应采用 0，而非回退到 row.f；用 has 显式判存在。
      let weight = charPrior.has(row.w) ? (charPrior.get(row.w) as number) : row.f
      if (weight > (freq.get(row.syls[0]) || 0)) {
        freq.set(row.syls[0], weight)
      }
    }
  }
  let exact: Map<string, MixRow[]> = new Map()
  for (let i = 0; i < ranked.length; i++) {
    let row = ranked[i]
    if (row.ini.length < 1 || row.ini.length > 16) {
      continue
    }
    let list = exact.get(row.ini) || []
    if (list.length < 12) {
      list.push(row)
      exact.set(row.ini, list)
    }
  }
  cachedRows = prepared
  cachedFreq = freq
  cachedExact = exact
  cachedCount = lexicon.length
  cachedHead = head
  cachedTail = tail
}

function syllablesOf(pinyin: string): string[] {
  let text = pinyin.toLowerCase().trim()
  if (!text) {
    return []
  }
  if (text.indexOf(' ') >= 0) {
    let parts = text.split(' ')
    let syls: string[] = []
    for (let i = 0; i < parts.length; i++) {
      let piece = parts[i].replace(/[^a-z]/g, '')
      if (!piece) {
        continue
      }
      if (!hasSyllable(piece)) {
        return []
      }
      syls.push(piece)
    }
    return syls
  }
  let folded = text.replace(/[^a-z]/g, '')
  let syls: string[] = []
  let i = 0
  while (i < folded.length) {
    let max = Math.min(6, folded.length - i)
    let best = 0
    for (let len = max; len >= 1; len--) {
      if (hasSyllable(folded.substring(i, i + len))) {
        best = len
        break
      }
    }
    if (best === 0) {
      return []
    }
    syls.push(folded.substring(i, i + best))
    i += best
  }
  return syls
}
