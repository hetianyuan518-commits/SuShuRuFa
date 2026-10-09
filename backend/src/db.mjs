import { randomBytes, scryptSync, timingSafeEqual, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync, existsSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { DatabaseSync } from 'node:sqlite'

const PNG_1X1 = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==',
  'base64'
)

export function hashPassword(password) {
  const salt = randomBytes(16).toString('hex')
  const hash = scryptSync(password, salt, 32).toString('hex')
  return `${salt}:${hash}`
}

export function verifyPassword(password, stored) {
  const [salt, hex] = String(stored || '').split(':')
  if (!salt || !hex) return false
  const got = scryptSync(password, salt, 32)
  const expected = Buffer.from(hex, 'hex')
  if (got.length !== expected.length) return false
  return timingSafeEqual(got, expected)
}

export function openDatabase(dbPath) {
  mkdirSync(dirname(dbPath), { recursive: true })
  const db = new DatabaseSync(dbPath)
  db.exec('PRAGMA journal_mode = WAL')
  db.exec(`
    CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
    CREATE TABLE IF NOT EXISTS users (
      id TEXT PRIMARY KEY,
      username TEXT UNIQUE NOT NULL,
      password_hash TEXT NOT NULL,
      display_name TEXT NOT NULL,
      role TEXT NOT NULL,
      group_ids TEXT NOT NULL,
      disabled INTEGER NOT NULL DEFAULT 0,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS groups (
      id TEXT PRIMARY KEY,
      name TEXT NOT NULL,
      kind TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS assets (
      id TEXT PRIMARY KEY,
      filename TEXT NOT NULL,
      mime TEXT NOT NULL,
      path TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS items (
      id TEXT PRIMARY KEY,
      type TEXT NOT NULL,
      title TEXT NOT NULL,
      body TEXT NOT NULL,
      aliases TEXT NOT NULL,
      pinyin TEXT NOT NULL,
      shortcut TEXT NOT NULL,
      group_id TEXT NOT NULL,
      asset_id TEXT,
      valid_from INTEGER,
      valid_to INTEGER,
      status TEXT NOT NULL,
      version INTEGER NOT NULL,
      updated_at INTEGER NOT NULL,
      deleted INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE IF NOT EXISTS sessions (
      token TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
    CREATE TABLE IF NOT EXISTS usage_events (
      id TEXT PRIMARY KEY,
      user_id TEXT NOT NULL,
      item_id TEXT NOT NULL,
      surface TEXT NOT NULL,
      created_at INTEGER NOT NULL
    );
  `)
  const seeded = db.prepare('SELECT value FROM meta WHERE key = ?').get('seeded')
  if (!seeded) seed(db, dbPath)
  return db
}

function metaGet(db, key, fallback) {
  const row = db.prepare('SELECT value FROM meta WHERE key = ?').get(key)
  return row ? row.value : fallback
}

export function metaNumber(db, key) {
  return Number(metaGet(db, key, '0'))
}

// 用单条 UPSERT 语句完成自增，避免“读取-计算-写回”分三步（即便当前为单进程同步执行，也保持原子语义）。
function incrementMeta(db, key) {
  db.prepare(`INSERT INTO meta(key, value) VALUES(?, '1')
    ON CONFLICT(key) DO UPDATE SET value = CAST(CAST(meta.value AS INTEGER) + 1 AS TEXT)`).run(key)
  return metaNumber(db, key)
}

function bump(db) {
  return incrementMeta(db, 'version')
}

export function bumpEpoch(db) {
  return incrementMeta(db, 'epoch')
}

// 种子账号密码：优先读取环境变量；未配置时生成随机一次性密码并告警，避免源码中硬编码默认凭据。
function seedPassword(envKey, username) {
  const fromEnv = process.env[envKey]
  if (fromEnv) return fromEnv
  const generated = randomBytes(12).toString('base64url')
  console.warn(`[susheng] 种子账号 ${username} 未配置 ${envKey}，已生成随机密码：${generated}（请登录后立即修改）`)
  return generated
}

function seed(db, dbPath) {
  const now = Date.now()
  const uploadDir = join(dirname(dbPath), 'uploads')
  mkdirSync(uploadDir, { recursive: true })
  // 整个种子过程包在单个事务里：任一步失败则整体回滚，避免留下半成品数据库。
  db.exec('BEGIN')
  try {
  const groups = [
    ['g-all', '全员', 'company'],
    ['g-variety', '综艺组', 'department'],
    ['g-spark', '达人-星火', 'creator']
  ]
  const insertGroup = db.prepare('INSERT INTO groups(id, name, kind, created_at) VALUES(?, ?, ?, ?)')
  // created_at 递增，保证 ORDER BY created_at 的分组顺序确定。
  groups.forEach((group, index) => insertGroup.run(...group, now + index))

  const insertUser = db.prepare(`INSERT INTO users(id, username, password_hash, display_name, role, group_ids, disabled, created_at)
    VALUES(?, ?, ?, ?, ?, ?, 0, ?)`)
  insertUser.run('u-admin', 'admin', hashPassword(seedPassword('SEED_ADMIN_PASSWORD', 'admin')), '管理员', 'admin', JSON.stringify(['g-all', 'g-variety', 'g-spark']), now)
  insertUser.run('u-staff', 'staff', hashPassword(seedPassword('SEED_STAFF_PASSWORD', 'staff')), '内部员工', 'staff', JSON.stringify(['g-all', 'g-variety']), now)
  insertUser.run('u-creator', 'creator', hashPassword(seedPassword('SEED_CREATOR_PASSWORD', 'creator')), '合作达人', 'creator', JSON.stringify(['g-spark']), now)

  const assetId = 'a-seal'
  const assetPath = join(uploadDir, assetId)
  writeFileSync(assetPath, PNG_1X1)
  db.prepare('INSERT INTO assets(id, filename, mime, path, created_at) VALUES(?, ?, ?, ?, ?)').run(assetId, 'seal.png', 'image/png', assetPath, now)

  const insertItem = db.prepare(`INSERT INTO items(id, type, title, body, aliases, pinyin, shortcut, group_id, asset_id, valid_from, valid_to, status, version, updated_at, deleted)
    VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, 'published', ?, ?, 0)`)
  const samples = [
    ['i-hello', 'script', '开场问候', '大家好，我是苏晟的合作达人，今晚直播间见。', ['问候', '开场'], 'kai chang wen hou', 'vhy', 'g-all', null],
    ['i-tweet', 'tweet', '直播预告', '今晚八点，综艺组直播间不见不散。点进主页就能预约。', ['预告'], 'zhi bo yu gao', 'vyg', 'g-variety', null],
    ['i-promo', 'promo', '星火宣发', '星火计划本周开播，评论区留下你想看的主题。', ['星火'], 'xing huo xuan fa', 'vxh', 'g-spark', null],
    ['i-name', 'lexicon', '苏晟', '苏晟', ['苏晟集团'], 'su sheng', '', 'g-all', null],
    ['i-seal', 'image', '苏晟印记', '宣发用小图', ['印记'], 'yin ji', 'vyj', 'g-all', assetId]
  ]
  samples.forEach((row, index) => {
    const [id, type, title, body, aliases, pinyin, shortcut, groupId, assetId] = row
    insertItem.run(id, type, title, body, JSON.stringify(aliases), pinyin, shortcut, groupId, assetId, index + 1, now)
  })
  db.prepare('INSERT INTO meta(key, value) VALUES(?, ?)').run('version', String(samples.length))
  db.prepare('INSERT INTO meta(key, value) VALUES(?, ?)').run('epoch', '1')
  db.prepare('INSERT INTO meta(key, value) VALUES(?, ?)').run('seeded', '1')
  db.exec('COMMIT')
  } catch (error) {
    db.exec('ROLLBACK')
    throw error
  }
}

export function publicUser(row) {
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    role: row.role,
    groupIds: parseGroupIds(row.group_ids),
    disabled: Boolean(row.disabled)
  }
}

// group_ids 存储为 JSON 文本；若为 NULL / 空 / 非法 JSON 则回退为空数组，避免解析抛异常。
export function parseGroupIds(raw) {
  try {
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function createSession(db, userId) {
  const token = randomBytes(24).toString('hex')
  db.prepare('INSERT INTO sessions(token, user_id, created_at) VALUES(?, ?, ?)').run(token, userId, Date.now())
  return token
}

// 会话令牌最长有效期（30 天）；超过则视为失效并顺手清理。
const SESSION_TTL_MS = 30 * 24 * 60 * 60 * 1000

export function userFromToken(db, token) {
  if (!token) return null
  const session = db.prepare('SELECT user_id, created_at FROM sessions WHERE token = ?').get(token)
  if (!session) return null
  if (Date.now() - session.created_at > SESSION_TTL_MS) {
    db.prepare('DELETE FROM sessions WHERE token = ?').run(token)
    return null
  }
  const user = db.prepare('SELECT * FROM users WHERE id = ?').get(session.user_id)
  if (!user || user.disabled) return null
  return user
}

export function nextVersion(db) {
  return bump(db)
}

export function readGroups(db) {
  return db.prepare('SELECT * FROM groups ORDER BY created_at').all().map((row) => ({
    id: row.id,
    name: row.name,
    kind: row.kind,
    createdAt: row.created_at
  }))
}

export function groupMap(db) {
  const map = new Map()
  for (const group of readGroups(db)) map.set(group.id, group)
  return map
}

export function readAsset(db, id) {
  return db.prepare('SELECT * FROM assets WHERE id = ?').get(id)
}

export function saveAsset(db, dbPath, { filename, mime, bytes }) {
  const id = `a-${randomUUID()}`
  const uploadDir = join(dirname(dbPath), 'uploads')
  mkdirSync(uploadDir, { recursive: true })
  const path = join(uploadDir, id)
  writeFileSync(path, bytes)
  db.prepare('INSERT INTO assets(id, filename, mime, path, created_at) VALUES(?, ?, ?, ?, ?)').run(id, filename, mime, path, Date.now())
  return { id, filename, mime }
}

export function loadAssetBytes(row) {
  if (!row || !existsSync(row.path)) return null
  return readFileSync(row.path)
}

export { randomUUID }
