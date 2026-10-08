import dataPreferences from '@ohos.data.preferences'
import common from '@ohos.app.ability.common'

export interface Skin {
  id: number
  name: string
  blurb: string
  bg: string
  key: string
  fn: string
  text: string
  accent: string
}

export interface SkinSet {
  name: string
  blurb: string
  skinId: number
}

export const skins: Skin[] = [
  { id: 0, name: '浅灰日常', blurb: '白键橙字，适合每天打字', bg: '#F3F4F6', key: '#FFFFFF', fn: '#E3E5EA', text: '#222222', accent: '#FF6A00' },
  { id: 1, name: '宣纸朱红', blurb: '纸色底，朱红点缀', bg: '#F3EADB', key: '#FFFCF7', fn: '#E7D9C8', text: '#1B140F', accent: '#C4372C' },
  { id: 2, name: '新叶', blurb: '清浅绿色按键', bg: '#E7F6E4', key: '#FFFFFF', fn: '#D5EBD0', text: '#1C3A24', accent: '#2E9B4A' },
  { id: 3, name: '夜航', blurb: '深色键盘，适合夜间口播', bg: '#1C1E27', key: '#2C3140', fn: '#232733', text: '#F2F4F8', accent: '#FF8A3D' },
  { id: 4, name: '海盐', blurb: '浅蓝底，适合白天发布', bg: '#E7F2FA', key: '#FFFFFF', fn: '#D5E6F4', text: '#16324A', accent: '#2F7FE0' },
  { id: 5, name: '暖杏', blurb: '暖色按键，适合欢迎和感谢', bg: '#FDEFE3', key: '#FFFAF6', fn: '#F6E0D0', text: '#4A2E22', accent: '#E36A2E' }
]

export const sets: SkinSet[] = [
  { name: '直播暖场', blurb: '宣纸朱红，适合开场和欢迎语', skinId: 1 },
  { name: '夜场口播', blurb: '夜航深色，适合夜间直播', skinId: 3 },
  { name: '清新日常', blurb: '新叶配色，适合白天发内容', skinId: 2 },
  { name: '海盐发布', blurb: '海盐配色，适合评论和标题', skinId: 4 }
]

export function skinById(id: number): Skin {
  for (let i = 0; i < skins.length; i++) {
    if (skins[i].id === id) {
      return skins[i]
    }
  }
  return skins[0]
}

async function prefs(context: common.Context): Promise<dataPreferences.Preferences> {
  return await dataPreferences.getPreferences(context, 'susheng')
}

export async function privacyAccepted(context: common.Context): Promise<boolean> {
  let pref = await prefs(context)
  return await pref.get('privacy', false) as boolean
}

export async function acceptPrivacy(context: common.Context): Promise<void> {
  let pref = await prefs(context)
  await pref.put('privacy', true)
  await pref.flush()
}

export async function currentSkin(context: common.Context): Promise<Skin> {
  let pref = await prefs(context)
  let id = await pref.get('skin', 0) as number
  return skinById(id)
}

export async function ownedSkins(context: common.Context): Promise<number[]> {
  let pref = await prefs(context)
  let raw = await pref.get('owned', '0') as string
  let ids: number[] = []
  let parts = raw.split(',')
  for (let i = 0; i < parts.length; i++) {
    let n = Number(parts[i])
    if (!Number.isNaN(n)) {
      ids.push(n)
    }
  }
  if (ids.length === 0) {
    ids.push(0)
  }
  return ids
}

export async function applySkin(context: common.Context, id: number): Promise<void> {
  let owned = await ownedSkins(context)
  if (owned.indexOf(id) < 0) {
    owned.push(id)
  }
  let pref = await prefs(context)
  await pref.put('skin', id)
  await pref.put('owned', owned.join(','))
  await pref.flush()
}
