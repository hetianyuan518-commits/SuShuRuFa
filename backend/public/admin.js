const state = {
  token: localStorage.getItem('susheng-token') || '',
  user: null,
  tab: 'items',
  groups: [],
  users: [],
  items: [],
  usage: [],
  error: '',
  editing: null
}

const typeName = { script: '话术', tweet: '推文', promo: '宣发', lexicon: '词条', image: '图片' }
const roleName = { admin: '管理员', staff: '员工', creator: '达人' }
const kindName = { company: '全员', department: '部门', creator: '达人组' }
const statusName = { draft: '稿', published: '发', unpublished: '撤' }

const app = document.querySelector('#app')

function esc(value) {
  return String(value ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch]))
}

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) }
  if (state.token) headers.authorization = `Bearer ${state.token}`
  if (options.body && !(options.body instanceof FormData)) headers['content-type'] = 'application/json'
  const response = await fetch(path, { ...options, headers, body: options.body && typeof options.body !== 'string' ? JSON.stringify(options.body) : options.body })
  if (response.status === 401 && path !== '/api/auth/login') {
    state.token = ''
    state.user = null
    localStorage.removeItem('susheng-token')
    render()
    throw new Error('请先登录')
  }
  const data = response.headers.get('content-type')?.includes('json') ? await response.json() : null
  if (!response.ok) throw new Error(data?.error || '请求失败')
  return data
}

function render() {
  app.innerHTML = state.user ? shell() : gate()
  bind()
}

function gate() {
  return `
    <section class="gate">
      <div class="mast">
        <div class="brand-kicker">Susheng MCN</div>
        <h1>内容<br>先于按键</h1>
        <p>话术、推文、宣发和图片从这里发出。输入法只负责在任意 App 里插入已经定稿的内容，不回收大家敲下的字。</p>
      </div>
      <form class="ticket" id="login">
        <h2>入台</h2>
        <div class="hint">管理员账号 admin，初始密码写在内部说明里。</div>
        <label>账号</label>
        <input name="username" autocomplete="username" required />
        <label>密码</label>
        <input name="password" type="password" autocomplete="current-password" required />
        <div class="error">${esc(state.error)}</div>
        <button class="primary" type="submit">进入内容台</button>
      </form>
    </section>`
}

function shell() {
  const panels = { items: itemsPanel(), groups: groupsPanel(), users: usersPanel(), usage: usagePanel() }
  return `
    <section class="shell">
      <aside class="spine">苏晟输入法 · 内容台</aside>
      <div class="main">
        <header class="top">
          <h1>${esc({ items: '稿件', groups: '分组', users: '账号', usage: '使用' }[state.tab] || '')}</h1>
          <div class="who">${esc(state.user.displayName)} · ${esc(roleName[state.user.role] || '')}<br><button class="ghost" id="logout" type="button">离开</button></div>
        </header>
        <nav class="tabs">
          ${['items', 'groups', 'users', 'usage'].map((tab) => `<button type="button" data-tab="${tab}" class="${state.tab === tab ? 'on' : ''}">${{ items: '稿件', groups: '分组', users: '账号', usage: '使用' }[tab]}</button>`).join('')}
        </nav>
        <div class="error">${esc(state.error)}</div>
        ${panels[state.tab]}
      </div>
    </section>`
}

function itemsPanel() {
  const editing = state.editing || blankItem()
  return `
    <form class="sheet" id="item-form">
      <h3>${editing.id ? '改稿' : '新稿'}</h3>
      <div class="row">
        <div><label>类型</label><select name="type">${Object.entries(typeName).map(([key, name]) => `<option value="${key}" ${editing.type === key ? 'selected' : ''}>${name}</option>`).join('')}</select></div>
        <div><label>分组</label><select name="groupId">${state.groups.map((group) => `<option value="${esc(group.id)}" ${editing.groupId === group.id ? 'selected' : ''}>${esc(group.name)}</option>`).join('')}</select></div>
      </div>
      <label>标题</label><input name="title" value="${esc(editing.title)}" required />
      <label>正文</label><textarea name="body">${esc(editing.body)}</textarea>
      <div class="row">
        <div><label>别名，逗号分开</label><input name="aliases" value="${esc((editing.aliases || []).join('，'))}" /></div>
        <div><label>拼音</label><input name="pinyin" value="${esc(editing.pinyin)}" placeholder="kai chang wen hou" /></div>
        <div><label>短码</label><input name="shortcut" value="${esc(editing.shortcut)}" placeholder="vhy" /></div>
      </div>
      <div class="row">
        <div><label>生效</label><input name="validFrom" type="datetime-local" value="${esc(toLocal(editing.validFrom))}" /></div>
        <div><label>截止</label><input name="validTo" type="datetime-local" value="${esc(toLocal(editing.validTo))}" /></div>
      </div>
      <div id="image-field"><label>图片</label><input name="file" type="file" accept="image/png,image/jpeg,image/webp,image/gif" /></div>
      <div class="actions">
        <button class="primary" type="submit">${editing.id ? '保存' : '存为草稿'}</button>
        <button class="ghost" id="reset-item" type="button">清空</button>
      </div>
    </form>
    <div class="list">
      ${state.items.map((item, index) => `
        <article class="entry" style="animation-delay:${index * 30}ms">
          <div class="stamp ${esc(item.status)}">${statusName[item.status] || ''}</div>
          <div>
            <strong>${esc(item.title)}</strong>
            <div class="meta">${typeName[item.type] || ''} · ${esc(item.groupName)} · ${esc(item.shortcut || item.pinyin || '无短码')}</div>
          </div>
          <div class="actions">
            <button class="ghost" data-edit="${esc(item.id)}" type="button">编辑</button>
            <button class="ghost" data-publish="${esc(item.id)}" type="button">发布</button>
            <button class="ghost" data-unpublish="${esc(item.id)}" type="button">下架</button>
          </div>
        </article>`).join('') || '<p>还没有稿件。</p>'}
    </div>`
}

function groupsPanel() {
  return `
    <form class="sheet" id="group-form">
      <h3>新分组</h3>
      <div class="row">
        <div><label>名称</label><input name="name" required /></div>
        <div><label>类型</label><select name="kind"><option value="department">部门</option><option value="creator">达人组</option><option value="company">全员</option></select></div>
      </div>
      <button class="primary" type="submit">建立</button>
    </form>
    <div class="list">
      ${state.groups.map((group) => `
        <article class="entry">
          <div class="stamp">${esc((kindName[group.kind] || group.kind || '?').slice(0, 1))}</div>
          <div><strong>${esc(group.name)}</strong><div class="meta">${esc(kindName[group.kind] || '')} · ${esc(group.id)}</div></div>
          <button class="ghost" data-publish-group="${esc(group.id)}" type="button">发布组内草稿</button>
        </article>`).join('')}
    </div>`
}

function usersPanel() {
  return `
    <form class="sheet" id="user-form">
      <h3>新账号</h3>
      <div class="row">
        <div><label>账号</label><input name="username" required /></div>
        <div><label>密码</label><input name="password" type="password" required /></div>
        <div><label>显示名</label><input name="displayName" required /></div>
      </div>
      <div class="row">
        <div><label>角色</label><select name="role"><option value="staff">员工</option><option value="creator">达人</option><option value="admin">管理员</option></select></div>
        <div><label>分组</label><select name="groupIds" multiple size="3">${state.groups.map((group) => `<option value="${esc(group.id)}">${esc(group.name)}</option>`).join('')}</select></div>
      </div>
      <button class="primary" type="submit">建立</button>
    </form>
    <div class="list">
      ${state.users.map((user) => `
        <article class="entry">
          <div class="stamp ${user.disabled ? 'off' : ''}">${esc((roleName[user.role] || user.role || '?').slice(0, 1))}</div>
          <div><strong>${esc(user.displayName)}</strong><div class="meta">${esc(user.username)} · ${esc(roleName[user.role] || '')} · ${(user.groupIds || []).map((id) => esc(groupName(id))).join(' / ') || '未分组'}</div></div>
          <button class="ghost" data-disable="${esc(user.id)}" type="button">${user.disabled ? '恢复' : '停用'}</button>
        </article>`).join('')}
    </div>`
}

function usagePanel() {
  const max = Math.max(1, ...state.usage.map((row) => row.count))
  return `
    <div class="sheet">
      <h3>插过哪些素材</h3>
      <p class="meta">这里只有素材编号和次数，没有任何人在输入框里打过的句子。</p>
      <div class="usage">
        ${state.usage.map((row) => `
          <div>
            <span>${esc(row.title || row.itemId)}</span>
            <i class="bar" style="transform: scaleX(${row.count / max})"></i>
            <b>${row.count}</b>
          </div>`).join('') || '<p>还没有使用记录。</p>'}
      </div>
    </div>`
}

function blankItem() {
  return { type: 'script', title: '', body: '', aliases: [], pinyin: '', shortcut: '', groupId: state.groups[0]?.id || '', validFrom: null, validTo: null }
}

function groupName(id) {
  return state.groups.find((group) => group.id === id)?.name || id
}

function toLocal(value) {
  if (!value) return ''
  const date = new Date(value)
  const pad = (n) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function bind() {
  document.querySelector('#login')?.addEventListener('submit', async (event) => {
    event.preventDefault()
    const form = new FormData(event.target)
    try {
      const data = await api('/api/auth/login', { method: 'POST', body: { username: form.get('username'), password: form.get('password') } })
      if (data.user.role !== 'admin') throw new Error('内容台只对管理员开放')
      state.token = data.token
      state.user = data.user
      state.error = ''
      localStorage.setItem('susheng-token', data.token)
      await refresh()
    } catch (error) {
      state.error = error.message
      render()
    }
  })
  document.querySelector('#logout')?.addEventListener('click', async () => {
    await api('/api/auth/logout', { method: 'POST', body: {} }).catch(() => {})
    state.token = ''
    state.user = null
    localStorage.removeItem('susheng-token')
    render()
  })
  document.querySelectorAll('[data-tab]').forEach((button) => button.addEventListener('click', async () => {
    state.tab = button.dataset.tab
    state.error = ''
    await refresh()
  }))
  document.querySelector('#item-form')?.addEventListener('submit', saveItem)
  document.querySelector('#reset-item')?.addEventListener('click', () => { state.editing = null; render() })
  document.querySelector('#group-form')?.addEventListener('submit', saveGroup)
  document.querySelector('#user-form')?.addEventListener('submit', saveUser)
  document.querySelectorAll('[data-edit]').forEach((button) => button.addEventListener('click', () => {
    state.editing = state.items.find((item) => item.id === button.dataset.edit)
    render()
  }))
  document.querySelectorAll('[data-publish]').forEach((button) => button.addEventListener('click', () => act(`/api/admin/items/${button.dataset.publish}/publish`)))
  document.querySelectorAll('[data-unpublish]').forEach((button) => button.addEventListener('click', () => act(`/api/admin/items/${button.dataset.unpublish}/unpublish`)))
  document.querySelectorAll('[data-publish-group]').forEach((button) => button.addEventListener('click', () => act(`/api/admin/groups/${button.dataset.publishGroup}/publish`)))
  document.querySelectorAll('[data-disable]').forEach((button) => button.addEventListener('click', async () => {
    const user = state.users.find((row) => row.id === button.dataset.disable)
    if (!user) return
    await act(`/api/admin/users/${user.id}`, 'PATCH', { disabled: !user.disabled })
  }))
}

async function act(path, method = 'POST', body) {
  try {
    await api(path, { method, body: body ?? {} })
    state.error = ''
    await refresh()
  } catch (error) {
    state.error = error.message
    render()
  }
}

async function saveItem(event) {
  event.preventDefault()
  const form = new FormData(event.target)
  try {
    let assetId = state.editing?.assetId || null
    const file = form.get('file')
    if (file && file.size) {
      const dataBase64 = await fileToBase64(file)
      const uploaded = await api('/api/admin/assets', { method: 'POST', body: { filename: file.name, mime: file.type, dataBase64 } })
      assetId = uploaded.asset.id
    }
    const payload = {
      type: form.get('type'),
      groupId: form.get('groupId'),
      title: form.get('title'),
      body: form.get('body'),
      aliases: String(form.get('aliases') || '').split(/[,，]/).map((part) => part.trim()).filter(Boolean),
      pinyin: form.get('pinyin'),
      shortcut: form.get('shortcut'),
      validFrom: form.get('validFrom') || null,
      validTo: form.get('validTo') || null,
      assetId
    }
    if (state.editing?.id) await api(`/api/admin/items/${state.editing.id}`, { method: 'PATCH', body: payload })
    else await api('/api/admin/items', { method: 'POST', body: payload })
    state.editing = null
    state.error = ''
    await refresh()
  } catch (error) {
    state.error = error.message
    render()
  }
}

function fileToBase64(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result).split(',')[1])
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}

async function saveGroup(event) {
  event.preventDefault()
  const form = new FormData(event.target)
  await act('/api/admin/groups', 'POST', { name: form.get('name'), kind: form.get('kind') })
}

async function saveUser(event) {
  event.preventDefault()
  const form = new FormData(event.target)
  await act('/api/admin/users', 'POST', {
    username: form.get('username'),
    password: form.get('password'),
    displayName: form.get('displayName'),
    role: form.get('role'),
    groupIds: form.getAll('groupIds')
  })
}

async function refresh() {
  if (!state.token) return render()
  try {
    if (!state.user) state.user = (await api('/api/me')).user
    if (state.user.role !== 'admin') throw new Error('内容台只对管理员开放')
    state.groups = (await api('/api/admin/groups')).groups
    if (state.tab === 'items') state.items = (await api('/api/admin/items')).items
    if (state.tab === 'users') state.users = (await api('/api/admin/users')).users
    if (state.tab === 'usage') state.usage = (await api('/api/admin/usage')).events
    state.error = ''
  } catch (error) {
    state.error = error.message
  }
  render()
}

refresh()
