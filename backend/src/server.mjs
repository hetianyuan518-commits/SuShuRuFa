import { createServer } from 'node:http'
import { readFileSync, existsSync } from 'node:fs'
import { dirname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  openDatabase, hashPassword, verifyPassword, publicUser, createSession, userFromToken,
  nextVersion, bumpEpoch, readGroups, groupMap, readAsset, saveAsset, loadAssetBytes,
  metaNumber, randomUUID
} from './db.mjs'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const publicDir = join(root, 'public')
const TYPES = new Set(['script', 'tweet', 'promo', 'lexicon', 'image'])
const ROLES = new Set(['admin', 'staff', 'creator'])
const KINDS = new Set(['company', 'department', 'creator'])
const SURFACES = new Set(['candidate', 'script', 'tweet', 'promo', 'lexicon', 'image', 'shortcut'])

function send(res, status, body, headers = {}) {
  const payload = Buffer.from(JSON.stringify(body))
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'cache-control': 'no-store',
    'content-length': payload.length,
    ...headers
  })
  res.end(payload)
}

function sendBytes(res, status, bytes, mime) {
  res.writeHead(status, {
    'content-type': mime,
    'cache-control': 'private, max-age=3600',
    'content-length': bytes.length
  })
  res.end(bytes)
}

function readBody(req, limit = 8 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = []
    let size = 0
    req.on('data', (chunk) => {
      size += chunk.length
      if (size > limit) {
        reject(Object.assign(new Error('请求体过大'), { status: 413 }))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => {
      if (!chunks.length) return resolve({})
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString('utf8')))
      } catch {
        reject(Object.assign(new Error('JSON 无法解析'), { status: 400 }))
      }
    })
    req.on('error', reject)
  })
}

function bearer(req) {
  const header = req.headers.authorization || ''
  const match = header.match(/^Bearer\s+(.+)$/i)
  return match ? match[1] : ''
}

function parseTime(value) {
  if (value === null || value === undefined || value === '') return null
  if (typeof value === 'number' && Number.isFinite(value)) return value
  const time = Date.parse(value)
  if (Number.isNaN(time)) throw Object.assign(new Error('时间格式无效'), { status: 400 })
  return time
}

function cleanText(value, max) {
  return String(value ?? '').trim().slice(0, max)
}

function validateItem(input, { partial } = {}) {
  const item = {}
  if (!partial || input.type !== undefined) {
    if (!TYPES.has(input.type)) throw Object.assign(new Error('内容类型无效'), { status: 400 })
    item.type = input.type
  }
  if (!partial || input.title !== undefined) item.title = cleanText(input.title, 80)
  if (!partial || input.body !== undefined) item.body = cleanText(input.body, 4000)
  if (!partial || input.aliases !== undefined) {
    const aliases = Array.isArray(input.aliases) ? input.aliases : String(input.aliases || '').split(/[,，]/)
    item.aliases = aliases.map((alias) => cleanText(alias, 40)).filter(Boolean).slice(0, 12)
  }
  if (!partial || input.pinyin !== undefined) {
    const pinyin = cleanText(input.pinyin, 120).toLowerCase()
    if (pinyin && !/^[a-z]+(?: [a-z]+)*$/.test(pinyin)) {
      throw Object.assign(new Error('拼音需为小写音节，用空格分开'), { status: 400 })
    }
    item.pinyin = pinyin
  }
  if (!partial || input.shortcut !== undefined) {
    const shortcut = cleanText(input.shortcut, 24).toLowerCase()
    if (shortcut && !/^v[a-z0-9]*$/.test(shortcut)) {
      throw Object.assign(new Error('短码需以 v 开头'), { status: 400 })
    }
    item.shortcut = shortcut
  }
  if (!partial || input.groupId !== undefined) item.groupId = cleanText(input.groupId, 40)
  if (!partial || input.assetId !== undefined) item.assetId = input.assetId ? cleanText(input.assetId, 80) : null
  if (!partial || input.validFrom !== undefined) item.validFrom = parseTime(input.validFrom)
  if (!partial || input.validTo !== undefined) item.validTo = parseTime(input.validTo)
  if (!partial) {
    if (!item.title) throw Object.assign(new Error('标题不能为空'), { status: 400 })
    if (!item.groupId) throw Object.assign(new Error('请选择分组'), { status: 400 })
    if (item.type !== 'image' && !item.body) throw Object.assign(new Error('正文不能为空'), { status: 400 })
  }
  return item
}

function canSeeGroup(user, groupId) {
  if (user.role === 'admin') return true
  return JSON.parse(user.group_ids).includes(groupId)
}

function inWindow(row, now) {
  if (row.valid_from && now < row.valid_from) return false
  if (row.valid_to && now > row.valid_to) return false
  return true
}

function toSyncItem(row, groups, deleted = false) {
  const group = groups.get(row.group_id)
  return {
    id: row.id,
    type: row.type,
    title: deleted ? '' : row.title,
    body: deleted ? '' : row.body,
    aliases: deleted ? [] : JSON.parse(row.aliases),
    pinyin: deleted ? '' : row.pinyin,
    shortcut: deleted ? '' : row.shortcut,
    groupId: row.group_id,
    groupName: group ? group.name : '',
    groupKind: group ? group.kind : 'company',
    assetId: deleted ? null : row.asset_id,
    mime: null,
    validFrom: row.valid_from,
    validTo: row.valid_to,
    status: deleted ? 'unpublished' : row.status,
    version: row.version,
    updatedAt: row.updated_at,
    deleted
  }
}

function visibleRow(user, row, now) {
  return row.deleted === 0 && row.status === 'published' && canSeeGroup(user, row.group_id) && inWindow(row, now)
}

export function createApp({ dbPath }) {
  const db = openDatabase(dbPath)
  const groupsOf = () => groupMap(db)

  function requireUser(req) {
    const user = userFromToken(db, bearer(req))
    if (!user) throw Object.assign(new Error('请先登录'), { status: 401 })
    return user
  }

  function requireAdmin(req) {
    const user = requireUser(req)
    if (user.role !== 'admin') throw Object.assign(new Error('需要管理员权限'), { status: 403 })
    return user
  }

  async function handle(req, res) {
    const url = new URL(req.url, 'http://127.0.0.1')
    const path = url.pathname
    try {
      if (req.method === 'GET' && !path.startsWith('/api/')) return serveStatic(path, res)
      if (req.method === 'POST' && path === '/api/auth/login') return login(await readBody(req), res)
      if (req.method === 'POST' && path === '/api/auth/logout') return logout(req, res)
      if (req.method === 'GET' && path === '/api/me') return send(res, 200, { user: publicUser(requireUser(req)) })
      if (req.method === 'GET' && path === '/api/health') {
        return send(res, 200, { ok: true, version: metaNumber(db, 'version'), epoch: metaNumber(db, 'epoch') })
      }
      if (req.method === 'GET' && path === '/api/sync') return sync(req, url, res)
      if (req.method === 'POST' && path === '/api/usage') return usage(req, await readBody(req), res)
      if (req.method === 'GET' && path.startsWith('/api/assets/')) return asset(req, path.slice('/api/assets/'.length), res)
      if (path.startsWith('/api/admin/')) return await admin(req, path, url, res)
      send(res, 404, { error: '没有这个接口' })
    } catch (error) {
      send(res, error.status || 500, { error: error.status ? error.message : '服务器错误' })
    }
  }

  function login(body, res) {
    const username = cleanText(body.username, 32)
    const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username)
    if (!user || user.disabled || !verifyPassword(body.password || '', user.password_hash)) {
      return send(res, 401, { error: '账号或密码不正确' })
    }
    const token = createSession(db, user.id)
    send(res, 200, { token, user: publicUser(user), epoch: metaNumber(db, 'epoch') })
  }

  function logout(req, res) {
    const token = bearer(req)
    if (token) db.prepare('DELETE FROM sessions WHERE token = ?').run(token)
    send(res, 200, { ok: true })
  }

  function sync(req, url, res) {
    const user = requireUser(req)
    const since = Number(url.searchParams.get('since') || '0')
    const clientEpoch = Number(url.searchParams.get('epoch') || '0')
    const epoch = metaNumber(db, 'epoch')
    const now = Date.now()
    const groups = groupsOf()
    const full = !since || clientEpoch !== epoch
    const rows = full
      ? db.prepare('SELECT * FROM items').all().filter((row) => visibleRow(user, row, now))
      : db.prepare('SELECT * FROM items WHERE version > ?').all(since)
    const items = rows.map((row) => {
      const keep = visibleRow(user, row, now)
      const packed = toSyncItem(row, groups, !keep)
      if (packed.assetId) {
        const assetRow = readAsset(db, packed.assetId)
        packed.mime = assetRow ? assetRow.mime : null
      }
      return packed
    }).filter((item) => full ? !item.deleted : true)
    send(res, 200, { serverVersion: metaNumber(db, 'version'), epoch, since: full ? 0 : since, items })
  }

  function usage(req, body, res) {
    const user = requireUser(req)
    const itemId = cleanText(body.itemId, 80)
    const surface = SURFACES.has(body.surface) ? body.surface : 'candidate'
    if (!itemId) throw Object.assign(new Error('缺少素材编号'), { status: 400 })
    const row = db.prepare('SELECT * FROM items WHERE id = ?').get(itemId)
    if (!row || !visibleRow(user, row, Date.now())) throw Object.assign(new Error('看不到这条素材'), { status: 404 })
    db.prepare('INSERT INTO usage_events(id, user_id, item_id, surface, created_at) VALUES(?, ?, ?, ?, ?)').run(randomUUID(), user.id, itemId, surface, Date.now())
    send(res, 201, { ok: true })
  }

  function asset(req, id, res) {
    const user = requireUser(req)
    const row = readAsset(db, id)
    if (!row) return send(res, 404, { error: '没有这张图片' })
    const linked = db.prepare('SELECT * FROM items WHERE asset_id = ? AND deleted = 0').all(id)
    const allowed = user.role === 'admin' || linked.some((item) => visibleRow(user, item, Date.now()))
    if (!allowed) return send(res, 403, { error: '无权读取这张图片' })
    const bytes = loadAssetBytes(row)
    if (!bytes) return send(res, 404, { error: '图片文件不存在' })
    sendBytes(res, 200, bytes, row.mime)
  }

  async function admin(req, path, url, res) {
    requireAdmin(req)
    if (req.method === 'GET' && path === '/api/admin/groups') return send(res, 200, { groups: readGroups(db) })
    if (req.method === 'POST' && path === '/api/admin/groups') return createGroup(await readBody(req), res)
    if (req.method === 'PATCH' && /^\/api\/admin\/groups\/[^/]+$/.test(path)) {
      return patchGroup(decodeURIComponent(path.split('/').pop()), await readBody(req), res)
    }
    if (req.method === 'POST' && /^\/api\/admin\/groups\/[^/]+\/publish$/.test(path)) {
      return publishGroup(decodeURIComponent(path.split('/')[4]), res)
    }
    if (req.method === 'GET' && path === '/api/admin/users') {
      const users = db.prepare('SELECT * FROM users ORDER BY created_at').all().map(publicUser)
      return send(res, 200, { users })
    }
    if (req.method === 'POST' && path === '/api/admin/users') return createUser(await readBody(req), res)
    if (req.method === 'PATCH' && /^\/api\/admin\/users\/[^/]+$/.test(path)) {
      return patchUser(decodeURIComponent(path.split('/').pop()), await readBody(req), res)
    }
    if (req.method === 'GET' && path === '/api/admin/items') return listItems(url, res)
    if (req.method === 'POST' && path === '/api/admin/items') return createItem(await readBody(req), res)
    if (req.method === 'PATCH' && /^\/api\/admin\/items\/[^/]+$/.test(path)) {
      return patchItem(decodeURIComponent(path.split('/').pop()), await readBody(req), res)
    }
    if (req.method === 'POST' && /^\/api\/admin\/items\/[^/]+\/publish$/.test(path)) {
      return setStatus(decodeURIComponent(path.split('/')[4]), 'published', res)
    }
    if (req.method === 'POST' && /^\/api\/admin\/items\/[^/]+\/unpublish$/.test(path)) {
      return setStatus(decodeURIComponent(path.split('/')[4]), 'unpublished', res)
    }
    if (req.method === 'DELETE' && /^\/api\/admin\/items\/[^/]+$/.test(path)) {
      return removeItem(decodeURIComponent(path.split('/').pop()), res)
    }
    if (req.method === 'POST' && path === '/api/admin/assets') return upload(await readBody(req), res)
    if (req.method === 'GET' && path === '/api/admin/usage') return usageReport(res)
    send(res, 404, { error: '没有这个接口' })
  }

  function createGroup(body, res) {
    const name = cleanText(body.name, 40)
    const kind = body.kind
    if (!name || !KINDS.has(kind)) throw Object.assign(new Error('分组名称或类型无效'), { status: 400 })
    const id = `g-${randomUUID()}`
    db.prepare('INSERT INTO groups(id, name, kind, created_at) VALUES(?, ?, ?, ?)').run(id, name, kind, Date.now())
    bumpEpoch(db)
    send(res, 201, { group: { id, name, kind } })
  }

  function patchGroup(id, body, res) {
    const current = db.prepare('SELECT * FROM groups WHERE id = ?').get(id)
    if (!current) throw Object.assign(new Error('分组不存在'), { status: 404 })
    const name = body.name !== undefined ? cleanText(body.name, 40) : current.name
    const kind = body.kind !== undefined ? body.kind : current.kind
    if (!name || !KINDS.has(kind)) throw Object.assign(new Error('分组名称或类型无效'), { status: 400 })
    db.prepare('UPDATE groups SET name = ?, kind = ? WHERE id = ?').run(name, kind, id)
    bumpEpoch(db)
    send(res, 200, { group: { id, name, kind } })
  }

  function publishGroup(id, res) {
    const group = db.prepare('SELECT id FROM groups WHERE id = ?').get(id)
    if (!group) throw Object.assign(new Error('分组不存在'), { status: 404 })
    const drafts = db.prepare(`SELECT id FROM items WHERE group_id = ? AND deleted = 0 AND status = 'draft'`).all(id)
    const now = Date.now()
    for (const draft of drafts) {
      const version = nextVersion(db)
      db.prepare(`UPDATE items SET status = 'published', version = ?, updated_at = ? WHERE id = ?`).run(version, now, draft.id)
    }
    send(res, 200, { published: drafts.length })
  }

  function createUser(body, res) {
    const username = cleanText(body.username, 32)
    const password = String(body.password || '')
    const displayName = cleanText(body.displayName, 40)
    const role = body.role
    const groupIds = Array.isArray(body.groupIds) ? body.groupIds.map(String) : []
    if (!/^[a-zA-Z0-9_]{3,32}$/.test(username)) throw Object.assign(new Error('账号需为 3-32 位字母数字'), { status: 400 })
    if (password.length < 6) throw Object.assign(new Error('密码至少 6 位'), { status: 400 })
    if (!displayName || !ROLES.has(role)) throw Object.assign(new Error('姓名或角色无效'), { status: 400 })
    ensureGroups(groupIds)
    try {
      db.prepare(`INSERT INTO users(id, username, password_hash, display_name, role, group_ids, disabled, created_at)
        VALUES(?, ?, ?, ?, ?, ?, 0, ?)`).run(`u-${randomUUID()}`, username, hashPassword(password), displayName, role, JSON.stringify(groupIds), Date.now())
    } catch {
      throw Object.assign(new Error('账号已存在'), { status: 409 })
    }
    bumpEpoch(db)
    const user = db.prepare('SELECT * FROM users WHERE username = ?').get(username)
    send(res, 201, { user: publicUser(user) })
  }

  function patchUser(id, body, res) {
    const current = db.prepare('SELECT * FROM users WHERE id = ?').get(id)
    if (!current) throw Object.assign(new Error('账号不存在'), { status: 404 })
    const displayName = body.displayName !== undefined ? cleanText(body.displayName, 40) : current.display_name
    const role = body.role !== undefined ? body.role : current.role
    const groupIds = body.groupIds !== undefined ? body.groupIds.map(String) : JSON.parse(current.group_ids)
    const disabled = body.disabled !== undefined ? (body.disabled ? 1 : 0) : current.disabled
    if (!displayName || !ROLES.has(role)) throw Object.assign(new Error('姓名或角色无效'), { status: 400 })
    ensureGroups(groupIds)
    let passwordHash = current.password_hash
    if (body.password) {
      if (String(body.password).length < 6) throw Object.assign(new Error('密码至少 6 位'), { status: 400 })
      passwordHash = hashPassword(body.password)
    }
    db.prepare('UPDATE users SET display_name = ?, role = ?, group_ids = ?, disabled = ?, password_hash = ? WHERE id = ?')
      .run(displayName, role, JSON.stringify(groupIds), disabled, passwordHash, id)
    bumpEpoch(db)
    send(res, 200, { user: publicUser(db.prepare('SELECT * FROM users WHERE id = ?').get(id)) })
  }

  function ensureGroups(groupIds) {
    for (const id of groupIds) {
      if (!db.prepare('SELECT id FROM groups WHERE id = ?').get(id)) {
        throw Object.assign(new Error('分组不存在'), { status: 400 })
      }
    }
  }

  function listItems(url, res) {
    const groups = groupsOf()
    let rows = db.prepare('SELECT * FROM items WHERE deleted = 0 ORDER BY updated_at DESC').all()
    const status = url.searchParams.get('status')
    const type = url.searchParams.get('type')
    const groupId = url.searchParams.get('groupId')
    if (status) rows = rows.filter((row) => row.status === status)
    if (type) rows = rows.filter((row) => row.type === type)
    if (groupId) rows = rows.filter((row) => row.group_id === groupId)
    send(res, 200, { items: rows.map((row) => presentItem(row, groups)) })
  }

  function presentItem(row, groups) {
    const packed = toSyncItem(row, groups, false)
    packed.status = row.status
    packed.deleted = Boolean(row.deleted)
    if (packed.assetId) {
      const assetRow = readAsset(db, packed.assetId)
      packed.mime = assetRow ? assetRow.mime : null
      packed.filename = assetRow ? assetRow.filename : ''
    }
    return packed
  }

  function createItem(body, res) {
    const item = validateItem(body)
    ensureGroups([item.groupId])
    if (item.type === 'image' && item.assetId && !readAsset(db, item.assetId)) {
      throw Object.assign(new Error('图片不存在'), { status: 400 })
    }
    const id = `i-${randomUUID()}`
    const version = nextVersion(db)
    const now = Date.now()
    db.prepare(`INSERT INTO items(id, type, title, body, aliases, pinyin, shortcut, group_id, asset_id, valid_from, valid_to, status, version, updated_at, deleted)
      VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'draft', ?, ?, 0)`).run(
      id, item.type, item.title, item.body || '', JSON.stringify(item.aliases), item.pinyin || '', item.shortcut || '',
      item.groupId, item.assetId, item.validFrom, item.validTo, version, now
    )
    const row = db.prepare('SELECT * FROM items WHERE id = ?').get(id)
    send(res, 201, { item: presentItem(row, groupsOf()) })
  }

  function patchItem(id, body, res) {
    const current = db.prepare('SELECT * FROM items WHERE id = ? AND deleted = 0').get(id)
    if (!current) throw Object.assign(new Error('内容不存在'), { status: 404 })
    const item = validateItem({
      type: body.type ?? current.type,
      title: body.title ?? current.title,
      body: body.body ?? current.body,
      aliases: body.aliases ?? JSON.parse(current.aliases),
      pinyin: body.pinyin ?? current.pinyin,
      shortcut: body.shortcut ?? current.shortcut,
      groupId: body.groupId ?? current.group_id,
      assetId: body.assetId !== undefined ? body.assetId : current.asset_id,
      validFrom: body.validFrom !== undefined ? body.validFrom : current.valid_from,
      validTo: body.validTo !== undefined ? body.validTo : current.valid_to
    })
    ensureGroups([item.groupId])
    const version = nextVersion(db)
    db.prepare(`UPDATE items SET type=?, title=?, body=?, aliases=?, pinyin=?, shortcut=?, group_id=?, asset_id=?, valid_from=?, valid_to=?, version=?, updated_at=? WHERE id=?`).run(
      item.type, item.title, item.body || '', JSON.stringify(item.aliases), item.pinyin || '', item.shortcut || '',
      item.groupId, item.assetId, item.validFrom, item.validTo, version, Date.now(), id
    )
    send(res, 200, { item: presentItem(db.prepare('SELECT * FROM items WHERE id = ?').get(id), groupsOf()) })
  }

  function setStatus(id, status, res) {
    const current = db.prepare('SELECT * FROM items WHERE id = ? AND deleted = 0').get(id)
    if (!current) throw Object.assign(new Error('内容不存在'), { status: 404 })
    if (status === 'published' && current.type === 'image' && !current.asset_id) {
      throw Object.assign(new Error('图片内容发布前需要先上传图片'), { status: 400 })
    }
    const version = nextVersion(db)
    db.prepare('UPDATE items SET status = ?, version = ?, updated_at = ? WHERE id = ?').run(status, version, Date.now(), id)
    send(res, 200, { item: presentItem(db.prepare('SELECT * FROM items WHERE id = ?').get(id), groupsOf()) })
  }

  function removeItem(id, res) {
    const current = db.prepare('SELECT * FROM items WHERE id = ? AND deleted = 0').get(id)
    if (!current) throw Object.assign(new Error('内容不存在'), { status: 404 })
    const version = nextVersion(db)
    db.prepare(`UPDATE items SET deleted = 1, status = 'unpublished', version = ?, updated_at = ? WHERE id = ?`).run(version, Date.now(), id)
    send(res, 200, { ok: true })
  }

  function upload(body, res) {
    const filename = cleanText(body.filename, 80) || 'image.png'
    const mime = cleanText(body.mime, 40)
    if (!/^image\/(png|jpeg|webp|gif)$/.test(mime)) throw Object.assign(new Error('只接收 png、jpeg、webp、gif'), { status: 400 })
    let bytes
    try {
      bytes = Buffer.from(String(body.dataBase64 || ''), 'base64')
    } catch {
      throw Object.assign(new Error('图片数据无效'), { status: 400 })
    }
    if (!bytes.length || bytes.length > 2 * 1024 * 1024) throw Object.assign(new Error('图片需小于 2MB'), { status: 400 })
    const asset = saveAsset(db, dbPath, { filename, mime, bytes })
    send(res, 201, { asset })
  }

  function usageReport(res) {
    const rows = db.prepare(`SELECT item_id, surface, COUNT(*) AS count FROM usage_events GROUP BY item_id, surface ORDER BY count DESC`).all()
    const items = db.prepare('SELECT id, title, type FROM items').all()
    const titles = new Map(items.map((item) => [item.id, item]))
    send(res, 200, {
      events: rows.map((row) => ({
        itemId: row.item_id,
        title: titles.get(row.item_id)?.title || '',
        type: titles.get(row.item_id)?.type || '',
        surface: row.surface,
        count: row.count
      }))
    })
  }

  return { handle, db }
}

function serveStatic(path, res) {
  const requested = path === '/' ? '/index.html' : path
  const file = normalize(join(publicDir, requested))
  if (!file.startsWith(publicDir) || !existsSync(file)) {
    res.writeHead(404)
    res.end('not found')
    return
  }
  const ext = file.split('.').pop()
  const types = { html: 'text/html; charset=utf-8', css: 'text/css; charset=utf-8', js: 'text/javascript; charset=utf-8' }
  const body = readFileSync(file)
  res.writeHead(200, { 'content-type': types[ext] || 'application/octet-stream', 'content-length': body.length })
  res.end(body)
}

export function listen({ dbPath, port = 8787, host = '0.0.0.0' } = {}) {
  const app = createApp({ dbPath })
  const server = createServer((req, res) => {
    app.handle(req, res)
  })
  return new Promise((resolve) => {
    server.listen(port, host, () => {
      const address = server.address()
      resolve({
        port: address.port,
        db: app.db,
        close: () => new Promise((done) => {
          server.close(() => {
            try { app.db.close() } catch { /* already closed */ }
            done()
          })
          if (typeof server.closeAllConnections === 'function') server.closeAllConnections()
        })
      })
    })
  })
}

const invoked = process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]
if (invoked) {
  const dbPath = join(root, 'data', 'susheng.sqlite')
  listen({ dbPath, port: Number(process.env.PORT || 8787) }).then(({ port }) => {
    console.log(`苏晟内容台 http://127.0.0.1:${port}`)
  })
}
