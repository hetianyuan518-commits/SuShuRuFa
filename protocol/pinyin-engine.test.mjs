import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { candidates, associate } from './pinyin-engine.mjs'

const lexicon = Object.freeze(JSON.parse(readFileSync(join(dirname(fileURLToPath(import.meta.url)), 'base-lexicon.json'), 'utf8')).entries)
const items = [
  {
    id: 'i-hello',
    type: 'script',
    title: '开场问候',
    body: '大家好',
    aliases: ['问候'],
    pinyin: 'kai chang wen hou',
    shortcut: 'vhy',
    status: 'published',
    deleted: false
  },
  {
    id: 'i-xian',
    type: 'lexicon',
    title: '西安',
    body: '西安',
    aliases: [],
    pinyin: 'xi an',
    shortcut: '',
    status: 'published',
    deleted: false
  }
]

// fixture 为所有用例共享，冻结以防某个测试意外改写而波及其余用例（浅冻结即可：用例只会读取或展开副本）。
items.forEach(Object.freeze)
Object.freeze(items)

test('全拼 nihao 首选你好', () => {
  const rows = candidates({ raw: 'nihao', lexicon, items })
  assert.equal(rows[0].commit, '你好')
  assert.equal(rows.some((row) => row.commit === '你'), false)
})

test('简拼 nh 能出你好', () => {
  const rows = candidates({ raw: 'nh', lexicon, items })
  assert.equal(rows.some((row) => row.commit === '你好'), true)
})

test('分词简拼 h\'h\'j 按音节前缀出词', () => {
  const rows = candidates({ raw: "h'h'j", lexicon, items })
  assert.equal(rows[0].commit, '黑胡椒')
  assert.equal(rows.some((row) => row.commit === '哈哈镜'), true)
  assert.equal(rows.some((row) => row.commit === '还喝酒'), true)
})

test('er\'wei\'h 出以 h 开头的第三音节', () => {
  const rows = candidates({ raw: "er'wei'h", lexicon, items })
  assert.equal(rows[0].commit, '二维火')
  assert.equal(rows.some((row) => row.commit === '二维化'), true)
  assert.equal(rows.some((row) => row.commit === '二维码'), false)
})

test('分隔音节 xi an 对应西安而不是先', () => {
  const split = candidates({ raw: "xi'an", lexicon, items })
  assert.equal(split.some((row) => row.commit === '西安'), true)
  assert.equal(split.some((row) => row.commit === '先'), false)
  const merged = candidates({ raw: 'xian', lexicon, items })
  assert.equal(merged.some((row) => row.commit === '先'), true)
  assert.equal(merged.some((row) => row.commit === '西安'), true)
})

test('九键 64426 对应你好', () => {
  const rows = candidates({ raw: '64426', mode: 't9', lexicon, items })
  assert.equal(rows[0].commit, '你好')
})

test('短码 vhy 只展开官方话术', () => {
  const rows = candidates({ raw: 'vhy', lexicon, items })
  assert.equal(rows.length, 1)
  assert.equal(rows[0].itemId, 'i-hello')
  assert.equal(rows[0].commit, '大家好')
})

test('联想在提交问候后给出话术', () => {
  const rows = associate({ last: '问候', lexicon, items })
  assert.equal(rows[0].itemId, 'i-hello')
  const more = associate({ last: '你好', lexicon, items })
  assert.equal(more.some((row) => row.commit === '吗'), true)
})

test('woye 和 niye 首选我也、你也', () => {
  assert.equal(candidates({ raw: 'woye', lexicon, items })[0].commit, '我也')
  assert.equal(candidates({ raw: 'niye', lexicon, items })[0].commit, '你也')
  assert.equal(candidates({ raw: 'nihao', lexicon, items })[0].commit, '你好')
  assert.equal(candidates({ raw: 'haishi', lexicon, items })[0].commit, '还是')
})

test('单字联想先出日常二字，再出口语后续', () => {
  const rows = associate({ last: '我', lexicon, items })
  const texts = rows.map((row) => row.text)
  assert.equal(texts[0], '我们')
  assert.equal(rows[0].commit, '们')
  // “我的”必须出现但不能排首位（首位固定为“我们”）；若“我国”也入候选，则“我的”必须排在它之前（口语优先于书面语）。
  assert.ok(texts.indexOf('我的') > 0)
  const country = texts.indexOf('我国')
  assert.ok(country === -1 || texts.indexOf('我的') < country)
  assert.ok(texts.indexOf('我想') > 0)
  assert.equal(texts.some((text) => Array.from(text).length > 6), false)
})

test('简拼 jtwsyzb 能组出整句', () => {
  const rows = candidates({ raw: 'jtwsyzb', lexicon, items })
  assert.equal(rows[0].commit, '今天晚上要准备')
  assert.equal(rows.some((row) => row.commit === '今天晚上有直播'), true)
  assert.equal(rows.some((row) => row.commit === '今天晚上'), true)
  const split = candidates({ raw: "j't'w's'y'z'b", lexicon, items })
  assert.equal(split[0].commit, '今天晚上要准备')
})

test('日常短句全拼和简拼都能出', () => {
  const ask = candidates({ raw: 'nichifanlema', lexicon, items })
  assert.equal(ask[0].commit, '你吃饭了吗')
  const askIni = candidates({ raw: 'ncflm', lexicon, items })
  assert.equal(askIni[0].commit, '你吃饭了吗')
  const home = candidates({ raw: 'daojialegenwoshuoyisheng', lexicon, items })
  assert.equal(home[0].commit, '到家了跟我说一声')
  const homeIni = candidates({ raw: 'djlgwsys', lexicon, items })
  assert.equal(homeIni[0].commit, '到家了跟我说一声')
  const ready = candidates({ raw: 'jintianwanshangyaozhunbei', lexicon, items })
  assert.equal(ready[0].commit, '今天晚上要准备')
  const live = candidates({ raw: 'jiarenmenjinwanqidianzhibojianbujianbusan', lexicon, items })
  assert.equal(live[0].commit, '家人们今晚七点直播间不见不散')
  const liveIni = candidates({ raw: 'jrmjwqdzbjbjbs', lexicon, items })
  assert.equal(liveIni[0].commit, '家人们今晚七点直播间不见不散')
})

test('混拼 jitwans 按音节前缀组出今天晚上', () => {
  const rows = candidates({ raw: 'jitwans', lexicon, items })
  assert.equal(rows[0].commit, '今天晚上')
  const china = candidates({ raw: 'zhguo', lexicon, items })
  assert.equal(china[0].commit, '中国')
  const initials = candidates({ raw: 'zhrmghg', lexicon, items })
  assert.equal(initials[0].commit, '中华人民共和国')
})

test('日常说法优先于生僻词', () => {
  const who = candidates({ raw: 'nishishui', lexicon, items })
  assert.equal(who[0].commit, '你是谁')
  const me = candidates({ raw: 'woshishui', lexicon, items })
  assert.equal(me[0].commit, '我是谁')
  const below = candidates({ raw: 'wozxiam', lexicon, items })
  assert.equal(below[0].commit, '我在下面')
  const where = candidates({ raw: 'nimzaina', lexicon, items })
  assert.equal(where[0].commit, '你们在哪')
})

test('长拼音仍能组出整句', () => {
  const night = candidates({ raw: 'jintianwanshangyaozhibo', lexicon, items })
  assert.equal(night[0].commit, '今天晚上要直播')
  const crew = candidates({ raw: 'womenshihezuodaren', lexicon, items })
  assert.equal(crew[0].commit, '我们是合作达人')
  const poem = candidates({ raw: 'chuangqianmingyueguangwoaini', lexicon, items })
  assert.equal(poem[0].commit, '床前明月光我爱你')
  const half = candidates({ raw: 'zhonghuarenmingonghe', lexicon, items })
  assert.equal(half[0].commit.includes('中华人民共和国'), true)
  const early = candidates({ raw: 'niha', lexicon, items })
  assert.equal(early[0].commit, '你好')
})

test('我想要、我知道、我想吃饭 是首选', () => {
  assert.equal(candidates({ raw: 'woxiangyao', lexicon, items })[0].commit, '我想要')
  assert.equal(candidates({ raw: 'wozhidao', lexicon, items })[0].commit, '我知道')
  assert.equal(candidates({ raw: 'woxiangchifan', lexicon, items })[0].commit, '我想吃饭')
  assert.equal(candidates({ raw: 'xianzaizaina', lexicon, items })[0].commit, '现在在哪')
  assert.equal(candidates({ raw: 'woaini', lexicon, items })[0].commit, '我爱你')
})

test('没打完和中间夹首字母也能联想', () => {
  const half = candidates({ raw: 'woshis', lexicon, items }).slice(0, 8).map((row) => row.commit)
  for (const word of ['我是说', '我是谁', '我试试']) {
    assert.ok(half.includes(word), `${word} 不在 ${half.join(' ')}`)
  }
  assert.equal(candidates({ raw: 'woxiangs', lexicon, items })[0].commit, '我想说')
  assert.equal(candidates({ raw: 'bangongs', lexicon, items })[0].commit, '办公室')
  assert.equal(candidates({ raw: 'nilaixiawdbangongs', lexicon, items })[0].commit, '你来下我的办公室')
})

test('过期内容不进入候选', () => {
  const now = Date.now()
  const rows = candidates({
    raw: 'vhy',
    lexicon,
    items: [{ ...items[0], validTo: now - 1000 }],
    now
  })
  assert.equal(rows.length, 0)
})

test('空输入和纯分隔符不产生候选', () => {
  assert.deepEqual(candidates({ raw: '', lexicon, items }), [])
  assert.deepEqual(candidates({ raw: "'''", lexicon, items }), [])
})
