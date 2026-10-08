import util from '@ohos.util'
import dataPreferences from '@ohos.data.preferences'
import common from '@ohos.app.ability.common'

export interface ContentItem {
  id: string
  type: string
  title: string
  body: string
  aliases: string[]
  pinyin: string
  shortcut: string
  groupName: string
  assetId: string
  status: string
  deleted: boolean
  validFrom: number
  validTo: number
}

export interface LexEntry {
  w: string
  p: string
  f: number
}

interface LexiconFile {
  entries: LexEntry[]
}

export async function readPack(context: common.Context): Promise<ContentItem[]> {
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  let raw = await pref.get('pack', '[]') as string
  let parsed = JSON.parse(raw) as ContentItem[]
  if (!parsed) {
    return []
  }
  return parsed
}

export async function queueUsage(context: common.Context, itemId: string, surface: string): Promise<void> {
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  let raw = await pref.get('usageQueue', '[]') as string
  let queue = JSON.parse(raw) as UsageEvent[]
  queue.push({ itemId: itemId, surface: surface })
  await pref.put('usageQueue', JSON.stringify(queue))
  await pref.flush()
}

interface UsageEvent {
  itemId: string
  surface: string
}

export function readLexicon(context: common.Context): LexEntry[] {
  loadSideTables(context)
  let bytes = context.resourceManager.getRawFileContentSync('base-lexicon.json')
  let decoder = util.TextDecoder.create('utf-8')
  let text = decoder.decodeToString(bytes)
  let parsed = JSON.parse(text) as LexiconFile
  return parsed.entries
}

export let charPrior: Map<string, number> = new Map()
export let nextWords: Map<string, string[]> = new Map()

function loadSideTables(context: common.Context): void {
  if (nextWords.size > 0) {
    return
  }
  let decoder = util.TextDecoder.create('utf-8')
  let rulesText = decoder.decodeToString(context.resourceManager.getRawFileContentSync('ime-rules.json'))
  let rules = JSON.parse(rulesText) as Record<string, Object>
  let next = rules['next'] as Record<string, string[]>
  let keys = Object.keys(next)
  for (let i = 0; i < keys.length; i++) {
    nextWords.set(keys[i], next[keys[i]])
  }
  let priorText = decoder.decodeToString(context.resourceManager.getRawFileContentSync('char-prior.json'))
  let prior = JSON.parse(priorText) as Record<string, number>
  let chars = Object.keys(prior)
  for (let i = 0; i < chars.length; i++) {
    charPrior.set(chars[i], prior[chars[i]])
  }
}
