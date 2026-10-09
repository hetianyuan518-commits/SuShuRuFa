import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { listen } from '../src/server.mjs'

// 测试环境注入固定种子密码（生产环境不设置这些变量时会生成随机密码）
process.env.SEED_ADMIN_PASSWORD ??= 'susheng-admin'
process.env.SEED_STAFF_PASSWORD ??= 'susheng-staff'
process.env.SEED_CREATOR_PASSWORD ??= 'susheng-creator'

async function boot() {
  const dir = mkdtempSync(join(tmpdir(), 'susheng-'))
  const app = await listen({ dbPath: join(dir, 'susheng.sqlite'), port: 0, host: '127.0.0.1' })
  const base = `http://127.0.0.1:${app.port}`
  // 关闭服务后顺手删除临时目录，避免测试遗留垃圾。
  const close = async () => {
    await app.close()
    rmSync(dir, { recursive: true, force: true })
  }
  return { base, close }
}

// 统一断言响应为 200 再解析 JSON，避免默默接受错误状态码的响应体。
async function json(response) {
  assert.equal(response.status, 200)
  return response.json()
}

async function login(base, username, password) {
  const response = await fetch(`${base}/api/auth/login`, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify({ username, password })
  })
  const body = await response.json()
  assert.equal(response.status, 200)
  return body.token
}

function auth(token) {
  return { authorization: `Bearer ${token}`, 'content-type': 'application/json' }
}

test('员工、达人看到的内容包不同，增量同步会下架', async () => {
  const { base, close } = await boot()
  try {
    const admin = await login(base, 'admin', 'susheng-admin')
    const staff = await login(base, 'staff', 'susheng-staff')
    const creator = await login(base, 'creator', 'susheng-creator')

    const staffPack = await fetch(`${base}/api/sync?since=0&epoch=0`, { headers: auth(staff) }).then(json)
    const creatorPack = await fetch(`${base}/api/sync?since=0&epoch=0`, { headers: auth(creator) }).then(json)
    assert.equal(staffPack.items.some((item) => item.id === 'i-hello'), true)
    assert.equal(staffPack.items.some((item) => item.id === 'i-promo'), false)
    assert.deepEqual(creatorPack.items.map((item) => item.id), ['i-promo'])

    const again = await fetch(`${base}/api/sync?since=${staffPack.serverVersion}&epoch=${staffPack.epoch}`, { headers: auth(staff) }).then(json)
    assert.equal(again.items.length, 0)

    const unpublished = await fetch(`${base}/api/admin/items/i-hello/unpublish`, { method: 'POST', headers: auth(admin) })
    assert.equal(unpublished.status, 200)
    const delta = await fetch(`${base}/api/sync?since=${staffPack.serverVersion}&epoch=${staffPack.epoch}`, { headers: auth(staff) }).then(json)
    const tombstone = delta.items.find((item) => item.id === 'i-hello')
    assert.equal(tombstone.deleted, true)
    assert.equal(tombstone.body, '')

    const usage = await fetch(`${base}/api/usage`, {
      method: 'POST',
      headers: auth(creator),
      body: JSON.stringify({ itemId: 'i-promo', surface: 'promo', typed: '不应被保存的正文' })
    })
    assert.equal(usage.status, 201)
    const report = await fetch(`${base}/api/admin/usage`, { headers: auth(admin) }).then(json)
    assert.equal(report.events.some((event) => event.itemId === 'i-promo'), true)
    assert.equal(JSON.stringify(report).includes('不应被保存'), false)

    const denied = await fetch(`${base}/api/admin/items`, { headers: auth(creator) })
    assert.equal(denied.status, 403)
  } finally {
    await close()
  }
})

test('分组发布、定时生效和图片权限', async () => {
  const { base, close } = await boot()
  try {
    const admin = await login(base, 'admin', 'susheng-admin')
    const creator = await login(base, 'creator', 'susheng-creator')
    const created = await fetch(`${base}/api/admin/items`, {
      method: 'POST',
      headers: auth(admin),
      body: JSON.stringify({
        type: 'script',
        title: '星火口播',
        body: '星火计划今晚加播一场。',
        aliases: ['加播'],
        pinyin: 'xing huo kou bo',
        shortcut: 'vjb',
        groupId: 'g-spark'
      })
    }).then(async (r) => {
      assert.equal(r.status, 201)
      return r.json()
    })

    const before = await fetch(`${base}/api/sync?since=0&epoch=0`, { headers: auth(creator) }).then(json)
    assert.equal(before.items.some((item) => item.id === created.item.id), false)

    const published = await fetch(`${base}/api/admin/groups/g-spark/publish`, { method: 'POST', headers: auth(admin) })
    assert.equal(published.status, 200)
    const after = await fetch(`${base}/api/sync?since=0&epoch=0`, { headers: auth(creator) }).then(json)
    assert.equal(after.items.some((item) => item.shortcut === 'vjb'), true)

    const future = Date.now() + 86400000
    await fetch(`${base}/api/admin/items/${created.item.id}`, {
      method: 'PATCH',
      headers: auth(admin),
      body: JSON.stringify({ validFrom: future })
    })
    const hidden = await fetch(`${base}/api/sync?since=0&epoch=0`, { headers: auth(creator) }).then(json)
    assert.equal(hidden.items.some((item) => item.id === created.item.id), false)

    const image = await fetch(`${base}/api/assets/a-seal`, { headers: auth(creator) })
    assert.equal(image.status, 403)
    const staff = await login(base, 'staff', 'susheng-staff')
    const ok = await fetch(`${base}/api/assets/a-seal`, { headers: auth(staff) })
    assert.equal(ok.status, 200)
    assert.equal(ok.headers.get('content-type'), 'image/png')
  } finally {
    await close()
  }
})
