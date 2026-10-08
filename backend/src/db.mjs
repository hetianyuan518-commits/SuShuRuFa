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

function bump(db) {
  const version = metaNumber(db, 'version') + 1
  db.prepare('INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run('version', String(version))
  return version
}

export function bumpEpoch(db) {
  const epoch = metaNumber(db, 'epoch') + 1
  db.prepare('INSERT INTO meta(key, value) VALUES(?, ?) ON CONFLICT(key) DO UPDATE SET value = excluded.value').run('epoch', String(epoch))
  return epoch
}

function seed(db, dbPath) {
  const now = Date.now()
  const uploadDir = join(dirname(dbPath), 'uploads')
  mkdirSync(uploadDir, { recursive: true })
  const groups = [
    ['g-all', '全员', 'company'],
    ['g-variety', '综艺组', 'department'],
    ['g-spark', '达人-星火', 'creator']
  ]
  const insertGroup = db.prepare('INSERT INTO groups(id, name, kind, created_at) VALUES(?, ?, ?, ?)')
  for (const group of groups) insertGroup.run(...group, now)

  const insertUser = db.prepare(`INSERT INTO users(id, username, password_hash, display_name, role, group_ids, disabled, created_at)
    VALUES(?, ?, ?, ?, ?, ?, 0, ?)`)
  insertUser.run('u-admin', 'admin', hashPassword('susheng-admin'), '管理员', 'admin', JSON.stringify(['g-all', 'g-variety', 'g-spark']), now)
  insertUser.run('u-staff', 'staff', hashPassword('susheng-staff'), '内部员工', 'staff', JSON.stringify(['g-all', 'g-variety']), now)
  insertUser.run('u-creator', 'creator', hashPassword('susheng-creator'), '合作达人', 'creator', JSON.stringify(['g-spark']), now)

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
}

export function publicUser(row) {
  return {
    id: row.id,
    username: row.username,
    displayName: row.display_name,
    role: row.role,
    groupIds: JSON.parse(row.group_ids),
    disabled: Boolean(row.disabled)
  }
}

export function createSession(db, userId) {
  const token = randomBytes(24).toString('hex')
  db.prepare('INSERT INTO sessions(token, user_id, created_at) VALUES(?, ?, ?)').run(token, userId, Date.now())
  return token
}

export function userFromToken(db, token) {
  if (!token) return null
  const session = db.prepare('SELECT user_id FROM sessions WHERE token = ?').get(token)
  if (!session) return null
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
