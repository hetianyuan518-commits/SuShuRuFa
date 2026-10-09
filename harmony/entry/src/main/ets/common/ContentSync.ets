import http from '@ohos.net.http'
import fs from '@ohos.file.fs'
import dataPreferences from '@ohos.data.preferences'
import common from '@ohos.app.ability.common'
import { ContentItem, withUsageQueue } from './ContentRead'

interface LoginBody {
  username: string
  password: string
}

interface UsageBody {
  itemId: string
  surface: string
}

export async function login(context: common.Context, server: string, username: string, password: string): Promise<string> {
  let base = trimSlash(server)
  let loginBody: LoginBody = { username: username, password: password }
  let payload = await postJson(base + '/api/auth/login', '', loginBody)
  let token = payload['token'] as string
  if (!token) {
    return payload['error'] as string || '登录失败'
  }
  // user 可能缺失，先校验再取值，避免对 undefined 解引用崩溃。
  let user = payload['user'] as Record<string, string> | undefined
  let displayName = (user && user['displayName']) ? user['displayName'] : username
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  await pref.put('server', base)
  await pref.put('token', token)
  await pref.put('displayName', displayName)
  await pref.flush()
  return '已登录'
}

export async function sync(context: common.Context): Promise<string> {
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  let server = await pref.get('server', '') as string
  let token = await pref.get('token', '') as string
  if (!server || !token) {
    return '请先登录'
  }
  let since = await pref.get('serverVersion', 0) as number
  let epoch = await pref.get('epoch', 0) as number
  let url = server + '/api/sync?since=' + since + '&epoch=' + epoch
  let payload = await getJson(url, token)
  let incoming = payload['items'] as ContentItem[]
  if (!Array.isArray(incoming)) {
    incoming = []
  }
  let responseSince = (typeof payload['since'] === 'number') ? payload['since'] as number : 0
  let responseEpoch = (typeof payload['epoch'] === 'number') ? payload['epoch'] as number : epoch
  let nextServerVersion = (typeof payload['serverVersion'] === 'number') ? payload['serverVersion'] as number : since
  let current = JSON.parse(await pref.get('pack', '[]') as string) as ContentItem[]
  if (!Array.isArray(current)) {
    current = []
  }
  let next: ContentItem[] = []
  if (!responseSince || responseEpoch !== epoch) {
    for (let i = 0; i < incoming.length; i++) {
      if (!incoming[i].deleted) {
        next.push(incoming[i])
      }
    }
  } else {
    next = merge(current, incoming)
  }
  await pref.put('pack', JSON.stringify(next))
  await pref.put('serverVersion', nextServerVersion)
  await pref.put('epoch', responseEpoch)
  await pref.flush()
  await downloadAssets(context, server, token, next)
  await flushUsage(context, server, token)
  return '已同步到版本 ' + nextServerVersion
}

async function flushUsage(context: common.Context, server: string, token: string): Promise<void> {
  await withUsageQueue(async () => {
    let pref = await dataPreferences.getPreferences(context, 'susheng')
    let raw = await pref.get('usageQueue', '[]') as string
    let queue = JSON.parse(raw) as Record<string, string>[]
    if (!Array.isArray(queue)) {
      return
    }
    let sent = 0
    for (let i = 0; i < queue.length; i++) {
      let usageBody: UsageBody = { itemId: queue[i]['itemId'], surface: queue[i]['surface'] }
      try {
        await postJson(server + '/api/usage', token, usageBody)
        sent++
      } catch (e) {
        // 一旦上报失败就停止，保留剩余事件下次重试，避免丢失。
        break
      }
    }
    if (sent <= 0) {
      return
    }
    // 重新读取，仅移除已成功上传的前 sent 条，保留 flush 期间新追加的事件。
    let latestRaw = await pref.get('usageQueue', '[]') as string
    let latest = JSON.parse(latestRaw) as Record<string, string>[]
    if (!Array.isArray(latest)) {
      return
    }
    for (let i = 0; i < sent && i < queue.length; i++) {
      let target = queue[i]
      for (let j = 0; j < latest.length; j++) {
        if (latest[j]['itemId'] === target['itemId'] && latest[j]['surface'] === target['surface']) {
          latest.splice(j, 1)
          break
        }
      }
    }
    await pref.put('usageQueue', JSON.stringify(latest))
    await pref.flush()
  })
}

async function downloadAssets(context: common.Context, server: string, token: string, items: ContentItem[]): Promise<void> {
  let dir = context.filesDir + '/assets'
  if (!fs.accessSync(dir)) {
    fs.mkdirSync(dir)
  }
  for (let i = 0; i < items.length; i++) {
    let assetId = items[i].assetId
    if (!assetId) {
      continue
    }
    // assetId 来自服务端，拒绝包含路径分隔符或 .. 的值，防止路径穿越覆盖任意文件。
    if (assetId.includes('/') || assetId.includes('\\') || assetId.includes('..')) {
      continue
    }
    let path = dir + '/' + assetId
    if (fs.accessSync(path)) {
      continue
    }
    let client = http.createHttp()
    try {
      let response = await client.request(server + '/api/assets/' + assetId, {
        method: http.RequestMethod.GET,
        header: { Authorization: 'Bearer ' + token },
        expectDataType: http.HttpDataType.ARRAY_BUFFER
      })
      // 非 200 时不能把错误体当成资源写入，否则下次同步会误以为已下载。
      if (response.responseCode !== 200) {
        continue
      }
      let bytes = response.result as ArrayBuffer
      let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY)
      try {
        fs.writeSync(file.fd, bytes)
      } finally {
        fs.closeSync(file)
      }
    } finally {
      client.destroy()
    }
  }
}

function merge(current: ContentItem[], incoming: ContentItem[]): ContentItem[] {
  let map: Record<string, ContentItem> = {}
  let removed: Record<string, boolean> = {}
  for (let i = 0; i < current.length; i++) {
    if (!current[i].deleted) {
      map[current[i].id] = current[i]
    }
  }
  for (let i = 0; i < incoming.length; i++) {
    if (incoming[i].deleted) {
      removed[incoming[i].id] = true
    } else {
      map[incoming[i].id] = incoming[i]
    }
  }
  let out: ContentItem[] = []
  let keys = Object.keys(map)
  for (let i = 0; i < keys.length; i++) {
    if (!removed[keys[i]]) {
      out.push(map[keys[i]])
    }
  }
  return out
}

async function postJson(url: string, token: string, body: LoginBody | UsageBody): Promise<Record<string, Object>> {
  let client = http.createHttp()
  try {
    let header: Record<string, string> = { 'Content-Type': 'application/json' }
    if (token) {
      header['Authorization'] = 'Bearer ' + token
    }
    let response = await client.request(url, {
      method: http.RequestMethod.POST,
      header: header,
      extraData: JSON.stringify(body)
    })
    return JSON.parse(response.result as string) as Record<string, Object>
  } finally {
    client.destroy()
  }
}

async function getJson(url: string, token: string): Promise<Record<string, Object>> {
  let client = http.createHttp()
  try {
    let response = await client.request(url, {
      method: http.RequestMethod.GET,
      header: { Authorization: 'Bearer ' + token }
    })
    return JSON.parse(response.result as string) as Record<string, Object>
  } finally {
    client.destroy()
  }
}

function trimSlash(value: string): string {
  let text = value
  while (text.endsWith('/')) {
    text = text.substring(0, text.length - 1)
  }
  return text
}
