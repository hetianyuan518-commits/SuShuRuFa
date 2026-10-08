import http from '@ohos.net.http'
import fs from '@ohos.file.fs'
import dataPreferences from '@ohos.data.preferences'
import common from '@ohos.app.ability.common'
import { ContentItem } from './ContentRead'

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
  let user = payload['user'] as Record<string, string>
  if (!token) {
    return payload['error'] as string || '登录失败'
  }
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  await pref.put('server', base)
  await pref.put('token', token)
  await pref.put('displayName', user['displayName'] || username)
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
  let responseSince = payload['since'] as number
  let responseEpoch = payload['epoch'] as number
  let current = JSON.parse(await pref.get('pack', '[]') as string) as ContentItem[]
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
  await pref.put('serverVersion', payload['serverVersion'])
  await pref.put('epoch', responseEpoch)
  await pref.flush()
  await downloadAssets(context, server, token, next)
  await flushUsage(context, server, token)
  return '已同步到版本 ' + payload['serverVersion']
}

async function flushUsage(context: common.Context, server: string, token: string): Promise<void> {
  let pref = await dataPreferences.getPreferences(context, 'susheng')
  let raw = await pref.get('usageQueue', '[]') as string
  let queue = JSON.parse(raw) as Record<string, string>[]
  for (let i = 0; i < queue.length; i++) {
    let usageBody: UsageBody = { itemId: queue[i]['itemId'], surface: queue[i]['surface'] }
    await postJson(server + '/api/usage', token, usageBody)
  }
  await pref.put('usageQueue', '[]')
  await pref.flush()
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
    let path = dir + '/' + assetId
    if (fs.accessSync(path)) {
      continue
    }
    let client = http.createHttp()
    let response = await client.request(server + '/api/assets/' + assetId, {
      method: http.RequestMethod.GET,
      header: { Authorization: 'Bearer ' + token },
      expectDataType: http.HttpDataType.ARRAY_BUFFER
    })
    let bytes = response.result as ArrayBuffer
    let file = fs.openSync(path, fs.OpenMode.CREATE | fs.OpenMode.WRITE_ONLY)
    fs.writeSync(file.fd, bytes)
    fs.closeSync(file)
    client.destroy()
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
  let header: Record<string, string> = { 'Content-Type': 'application/json' }
  if (token) {
    header['Authorization'] = 'Bearer ' + token
  }
  let response = await client.request(url, {
    method: http.RequestMethod.POST,
    header: header,
    extraData: JSON.stringify(body)
  })
  client.destroy()
  return JSON.parse(response.result as string) as Record<string, Object>
}

async function getJson(url: string, token: string): Promise<Record<string, Object>> {
  let client = http.createHttp()
  let response = await client.request(url, {
    method: http.RequestMethod.GET,
    header: { Authorization: 'Bearer ' + token }
  })
  client.destroy()
  return JSON.parse(response.result as string) as Record<string, Object>
}

function trimSlash(value: string): string {
  let text = value
  while (text.endsWith('/')) {
    text = text.substring(0, text.length - 1)
  }
  return text
}
