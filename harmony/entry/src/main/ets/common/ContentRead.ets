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
  try {
    let pref = await dataPreferences.getPreferences(context, 'susheng')
    let raw = await pref.get('pack', '[]') as string
    let parsed = JSON.parse(raw) as ContentItem[]
    if (!parsed || !Array.isArray(parsed)) {
      return []
    }
    return parsed
  } catch (e) {
    // 持久化内容损坏时返回空列表，避免 JSON.parse 抛出未处理异常。
    return []
  }
}

let usageChain: Promise<void> = Promise.resolve()

// 串行化所有对 usageQueue 的读改写，避免并发调用互相覆盖导致事件丢失。
export function withUsageQueue(task: () => Promise<void>): Promise<void> {
  usageChain = usageChain.then(task).catch(() => {})
  return usageChain
}

export function queueUsage(context: common.Context, itemId: string, surface: string): Promise<void> {
  return withUsageQueue(async () => {
    let pref = await dataPreferences.getPreferences(context, 'susheng')
    let raw = await pref.get('usageQueue', '[]') as string
    let queue = JSON.parse(raw) as UsageEvent[]
    if (!Array.isArray(queue)) {
      queue = []
    }
    queue.push({ itemId: itemId, surface: surface })
    await pref.put('usageQueue', JSON.stringify(queue))
    await pref.flush()
  })
}

interface UsageEvent {
  itemId: string
  surface: string
}

export function readLexicon(context: common.Context): LexEntry[] {
  try {
    loadSideTables(context)
    let bytes = context.resourceManager.getRawFileContentSync('base-lexicon.json')
    let decoder = util.TextDecoder.create('utf-8')
    let text = decoder.decodeToString(bytes)
    let parsed = JSON.parse(text) as LexiconFile
    return parsed && parsed.entries ? parsed.entries : []
  } catch (e) {
    // 资源缺失或解析失败时返回空词表，避免在 UI 线程抛出未处理异常。
    return []
  }
}

// 两张副表以 const 绑定导出（绑定不可重赋值），且只由 loadSideTables 在本模块内原地填充。
// 导入方拿到的是 ES module 的只读活绑定，无法重新赋值；如需完全只读可进一步改为访问器。
export const charPrior: Map<string, number> = new Map()
export const nextWords: Map<string, string[]> = new Map()

// 专用初始化标志：不再依赖 nextWords.size 推断是否已加载。旧写法下，若 nextWords
// 被某条路径填非空而 charPrior 未填，charPrior 会永远为空；且部分加载失败时无法重试。
let sideTablesLoaded = false

function loadSideTables(context: common.Context): void {
  if (sideTablesLoaded) {
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
  // 两张表都成功填充后才置位；若上面抛异常则保持 false，允许下次调用重试。
  sideTablesLoaded = true
}
