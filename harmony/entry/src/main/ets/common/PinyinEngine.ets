import { ContentItem, LexEntry } from './ContentRead'
import { hasSyllable, isSyllablePrefix } from './Syllables'
import { mixedSentences } from './MixedPinyin'

export interface Candidate {
  text: string
  commit: string
  source: string
  kind: string
  itemId: string
  assetId: string
  hint: string
  score: number
}

class Seg {
  syls: string[] = []
  partial: string = ''
}

function compact(pinyin: string): string {
  return pinyin.toLowerCase().replace(/[^a-z]/g, '')
}

function toT9(text: string): string {
  let map = '22233344455566677778889999'
  let out = ''
  for (let i = 0; i < text.length; i++) {
    let code = text.charCodeAt(i) - 97
    if (code >= 0 && code < 26) {
      out += map[code]
    }
  }
  return out
}

function segmentAllowPartial(input: string): Seg | null {
  let memo: Array<Seg | null | undefined> = []
  let rec = (index: number): Seg | null => {
    if (memo[index] !== undefined) {
      return memo[index] as Seg | null
    }
    if (index === input.length) {
      memo[index] = new Seg()
      return memo[index] as Seg
    }
    let max = Math.min(6, input.length - index)
    for (let len = max; len >= 1; len--) {
      let piece = input.substring(index, index + len)
      if (!hasSyllable(piece)) {
        continue
      }
      let rest = rec(index + len)
      if (rest === null) {
        continue
      }
      let hit = new Seg()
      hit.syls = [piece].concat(rest.syls)
      hit.partial = rest.partial
      memo[index] = hit
      return hit
    }
    let tail = input.substring(index)
    if (tail.length <= 6 && isSyllablePrefix(tail) && !hasSyllable(tail)) {
      let partial = new Seg()
      partial.partial = tail
      memo[index] = partial
      return partial
    }
    memo[index] = null
    return null
  }
  return rec(0)
}

function segmentFull(text: string): string[] | null {
  let got = segmentAllowPartial(text)
  if (got === null || got.partial) {
    return null
  }
  return got.syls
}

function syllablesOf(pinyin: string): string[] | null {
  let parts = pinyin.toLowerCase().trim().split(/\s+/).filter((part: string) => compact(part).length > 0).map((part: string) => compact(part))
  if (parts.length === 0) {
    return null
  }
  if (parts.length > 1) {
    for (let i = 0; i < parts.length; i++) {
      if (!hasSyllable(parts[i])) {
        return null
      }
    }
    return parts
  }
  return segmentFull(parts[0])
}

function alignedTo(input: string, syls: string[] | null): boolean {
  let got = segmentAllowPartial(input)
  if (syls === null || got === null || got.syls.length > syls.length) {
    return false
  }
  for (let i = 0; i < got.syls.length; i++) {
    if (syls[i] !== got.syls[i]) {
      return false
    }
  }
  if (!got.partial) {
    return true
  }
  let next = syls[got.syls.length]
  return !!next && next.startsWith(got.partial)
}

function qwertyScore(raw: string, pinyin: string): number {
  let folded = compact(pinyin)
  let syls = syllablesOf(pinyin)
  if (!folded || syls === null) {
    return 0
  }
  if (raw.indexOf("'") >= 0) {
    let parts = raw.split("'").map((part: string) => part.replace(/[^a-z]/g, ''))
    if (parts.length > syls.length) {
      return 0
    }
    for (let i = 0; i < parts.length; i++) {
      if (!parts[i]) {
        continue
      }
      if (!syls[i].startsWith(parts[i])) {
        return 0
      }
    }
    let joined = parts.join('')
    return folded === joined ? 5000 : 4000 - (folded.length - joined.length)
  }
  let input = raw.replace(/[^a-z]/g, '')
  if (!input) {
    return 0
  }
  if (folded === input) {
    return 5000
  }
  if (alignedTo(input, syls)) {
    return 4000 - (folded.length - input.length)
  }
  let initials = ''
  for (let i = 0; i < syls.length; i++) {
    initials += syls[i][0]
  }
  if (initials === input && input !== folded) {
    return 2500
  }
  if (input.length >= 2 && initials.startsWith(input) && input !== folded) {
    return 1500 - (initials.length - input.length)
  }
  return 0
}

function t9Score(raw: string, pinyin: string): number {
  let input = raw.replace(/[^2-9]/g, '')
  if (!input || input !== raw.replace(/'/g, '')) {
    return 0
  }
  let digits = toT9(compact(pinyin))
  if (!digits.startsWith(input)) {
    return 0
  }
  return 3000 - (digits.length - input.length) + (digits === input ? 500 : 0)
}

function scorePinyin(raw: string, mode: string, pinyin: string): number {
  if (mode === 't9') {
    return t9Score(raw, pinyin)
  }
  return qwertyScore(raw.toLowerCase(), pinyin)
}

function active(items: ContentItem[], now: number): ContentItem[] {
  let out: ContentItem[] = []
  for (let i = 0; i < items.length; i++) {
    let item = items[i]
    if (item.deleted || item.status !== 'published') {
      continue
    }
    if (item.validFrom && now < item.validFrom) {
      continue
    }
    if (item.validTo && now > item.validTo) {
      continue
    }
    out.push(item)
  }
  return out
}

function official(item: ContentItem, score: number): Candidate {
  let image = item.type === 'image'
  let row: Candidate = {
    text: image ? item.title : (item.type === 'lexicon' ? item.body : item.title),
    commit: image ? '' : item.body,
    source: 'official',
    kind: item.type,
    itemId: item.id,
    assetId: item.assetId || '',
    hint: item.shortcut || item.pinyin || item.groupName || '',
    score: score
  }
  return row
}

function dedupe(list: Candidate[]): Candidate[] {
  let seen: Record<string, boolean> = {}
  let out: Candidate[] = []
  for (let i = 0; i < list.length; i++) {
    let key = list[i].itemId ? 'id:' + list[i].itemId : 't:' + list[i].commit
    if (seen[key]) {
      continue
    }
    seen[key] = true
    out.push(list[i])
  }
  return out
}

export function candidates(raw: string, mode: string, lexicon: LexEntry[], items: ContentItem[], now: number): Candidate[] {
  let input = raw.trim().toLowerCase()
  if (!input) {
    return []
  }
  let pool = active(items, now)
  let rows: Candidate[] = []
  if (input.startsWith('v')) {
    for (let i = 0; i < pool.length; i++) {
      let shortcut = (pool[i].shortcut || '').toLowerCase()
      if (shortcut && (shortcut === input || shortcut.startsWith(input))) {
        rows.push(official(pool[i], 2000000 - shortcut.length))
      }
    }
    rows.sort((a: Candidate, b: Candidate) => b.score - a.score)
    return rows.slice(0, 20)
  }
  for (let i = 0; i < pool.length; i++) {
    let typed = scorePinyin(input, mode, pool[i].pinyin || '')
    if (typed > 0) {
      rows.push(official(pool[i], typed + 1000000))
    }
    let names = [pool[i].title].concat(pool[i].aliases || [])
    for (let n = 0; n < names.length; n++) {
      if (names[n] && (names[n] === input || names[n].startsWith(input))) {
        rows.push(official(pool[i], 1500000))
        break
      }
    }
  }
  for (let i = 0; i < lexicon.length; i++) {
    let typed = scorePinyin(input, mode, lexicon[i].p || '')
    if (typed <= 0) {
      continue
    }
    rows.push({
      text: lexicon[i].w,
      commit: lexicon[i].w,
      source: 'lexicon',
      kind: 'lexicon',
      itemId: '',
      assetId: '',
      hint: lexicon[i].p,
      score: typed + (lexicon[i].f || 0)
    })
  }
  if (mode !== 't9') {
    let letters = input.replace(/[^a-z]/g, '')
    let marked = input.indexOf("'") >= 0
    let single = marked && letters.length >= 2
    if (marked) {
      let parts = input.split("'")
      for (let i = 0; i < parts.length; i++) {
        let cleaned = parts[i].replace(/[^a-z]/g, '')
        if (cleaned.length > 1) {
          single = false
        }
      }
    }
    if (letters.length >= 2 && (!marked || single)) {
      let mixed = mixedSentences(letters, lexicon)
      for (let i = 0; i < mixed.length; i++) {
        rows.push({
          text: mixed[i].text,
          commit: mixed[i].commit,
          source: mixed[i].source,
          kind: mixed[i].kind,
          itemId: mixed[i].itemId,
          assetId: mixed[i].assetId,
          hint: mixed[i].hint,
          score: mixed[i].score
        })
      }
    }
  }
  rows.sort((a: Candidate, b: Candidate) => b.score - a.score || a.text.length - b.text.length)
  return dedupe(rows).slice(0, 20)
}

export function associate(last: string, lexicon: LexEntry[], items: ContentItem[], now: number): Candidate[] {
  let word = last.trim()
  if (!word) {
    return []
  }
  let rows: Candidate[] = []
  let pool = active(items, now)
  for (let i = 0; i < pool.length; i++) {
    if (pool[i].type === 'image') {
      continue
    }
    let names = [pool[i].title].concat(pool[i].aliases || [])
    if (names.indexOf(word) >= 0) {
      rows.push(official(pool[i], 1000000))
    }
  }
  for (let i = 0; i < lexicon.length; i++) {
    if (lexicon[i].w.startsWith(word) && lexicon[i].w !== word) {
      rows.push({
        text: lexicon[i].w,
        commit: lexicon[i].w.substring(word.length),
        source: 'lexicon',
        kind: 'lexicon',
        itemId: '',
        assetId: '',
        hint: lexicon[i].p,
        score: lexicon[i].f || 0
      })
    }
  }
  rows.sort((a: Candidate, b: Candidate) => b.score - a.score)
  return dedupe(rows).slice(0, 8)
}
