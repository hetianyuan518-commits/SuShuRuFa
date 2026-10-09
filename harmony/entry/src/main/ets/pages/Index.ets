import { login, sync } from '../common/ContentSync'
import { ContentItem, readPack } from '../common/ContentRead'
import common from '@ohos.app.ability.common'
import dataPreferences from '@ohos.data.preferences'
import { Want } from '@kit.AbilityKit'
import { Skin, SkinSet, skins, sets, skinById, privacyAccepted, acceptPrivacy, currentSkin, ownedSkins, applySkin } from '../common/Skin'
import { legalTitles, legalBody } from '../common/Legal'

@Entry
@Component
struct Index {
  @State screen: string = 'guide'
  @State tab: string = 'store'
  @State legalTitle: string = '隐私政策'
  @State legalBack: string = 'privacy'
  @State @Watch('onSkinIdChange') skinId: number = 0
  @State skinName: string = skinById(0).name
  @State owned: number[] = [0]
  @State server: string = 'http://127.0.0.1:8787'
  @State username: string = ''
  @State password: string = ''
  @State status: string = '尚未登录'
  @State displayName: string = '苏晟用户'
  @State phrases: ContentItem[] = []

  async aboutToAppear() {
    try {
      let context = getContext(this) as common.UIAbilityContext
      if (await privacyAccepted(context)) {
        this.screen = 'home'
      }
      let skin = await currentSkin(context)
      this.skinId = skin.id
      this.owned = await ownedSkins(context)
      let pref = await dataPreferences.getPreferences(context, 'susheng')
      this.server = await pref.get('server', this.server) as string
      this.displayName = await pref.get('displayName', '苏晟用户') as string
      this.phrases = await readPack(context)
    } catch (e) {
      // 首屏加载失败时保留默认状态，避免未处理 rejection 导致白屏/崩溃。
    }
  }

  build() {
    Column() {
      if (this.screen === 'guide') {
        this.guidePage()
      } else if (this.screen === 'privacy') {
        this.privacyPage()
      } else if (this.screen === 'legal') {
        this.legalPage()
      } else if (this.screen === 'settings') {
        this.settingsPage()
      } else {
        this.homePage()
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#F4F5F7')
  }

  @Builder
  guidePage() {
    Column() {
      Text('将「苏晟输入法」设为默认输入法')
        .fontSize(22)
        .fontWeight(FontWeight.Bold)
        .fontColor('#222222')
        .margin({ top: 36, bottom: 16 })
      Text('鸿蒙 5、6：设置 → 系统 → 输入法。鸿蒙 7 若菜单有调整，在「系统」里进入「输入法」。启用苏晟输入法，同意风险提示，再设为默认。')
        .fontSize(14)
        .fontColor('#8A9099')
        .margin({ bottom: 24 })
      Button('第一步  打开系统输入法页面')
        .width('100%')
        .height(48)
        .backgroundColor('#E4E6EA')
        .fontColor('#222222')
        .onClick(() => this.openInputSettings())
      Button('第二步  我已切换，继续')
        .width('100%')
        .height(48)
        .margin({ top: 12 })
        .backgroundColor('#FF6A00')
        .fontColor('#FFFFFF')
        .onClick(() => { this.screen = 'privacy' })
    }
    .padding(24)
    .width('100%')
    .height('100%')
    .justifyContent(FlexAlign.Start)
  }

  @Builder
  privacyPage() {
    Scroll() {
      Column() {
        Image($r('app.media.app_icon')).width(72).height(72).margin({ top: 28 })
        Text('欢迎您体验').fontSize(15).fontColor('#8A9099').margin({ top: 16 })
        Text('苏晟输入法').fontSize(26).fontWeight(FontWeight.Bold).margin({ top: 8 })
        Text('请阅读协议后选择。同意后进入首页。苏晟输入法不上传你敲下的文字。')
          .fontSize(14)
          .fontColor('#222222')
          .margin({ top: 16 })
        ForEach(legalTitles, (title: string) => {
          Text(`《${title}》`)
            .fontSize(15)
            .fontColor('#2F7FE0')
            .margin({ top: 8 })
            .onClick(() => {
              this.legalTitle = title
              this.legalBack = 'privacy'
              this.screen = 'legal'
            })
        }, (title: string) => title)
      }
      .width('100%')
      .padding(24)
      .alignItems(HorizontalAlign.Center)
    }
    .layoutWeight(1)
    Row() {
      Button('拒绝')
        .layoutWeight(1)
        .height(48)
        .backgroundColor('#F0F1F4')
        .fontColor('#8A9099')
        .onClick(() => this.reject())
      Button('同意')
        .layoutWeight(1)
        .height(48)
        .margin({ left: 12 })
        .backgroundColor('#FF6A00')
        .fontColor('#FFFFFF')
        .onClick(() => this.agree())
    }
    .width('100%')
    .padding(20)
  }

  @Builder
  legalPage() {
    Column() {
      Row() {
        Text('‹').fontSize(28).onClick(() => { this.screen = this.legalBack })
        Text(this.legalTitle).fontSize(18).fontWeight(FontWeight.Bold).margin({ left: 8 })
      }.width('100%').padding(16)
      Scroll() {
        Text(legalBody(this.legalTitle))
          .fontSize(14)
          .fontColor('#222222')
          .padding(16)
      }
      .layoutWeight(1)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#FFFFFF')
  }

  @Builder
  homePage() {
    Column() {
      if (this.tab === 'store') {
        this.storePage()
      } else if (this.tab === 'phrases') {
        this.phrasePage()
      } else {
        this.minePage()
      }
      Row() {
        this.tabButton('商城', 'store')
        this.tabButton('语录', 'phrases')
        this.tabButton('我的', 'mine')
      }
      .width('100%')
      .height(56)
      .backgroundColor('#FFFFFF')
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  tabButton(label: string, name: string) {
    Text(label)
      .layoutWeight(1)
      .height(56)
      .textAlign(TextAlign.Center)
      .fontSize(14)
      .fontColor(this.tab === name ? '#FF6A00' : '#8A9099')
      .fontWeight(this.tab === name ? FontWeight.Bold : FontWeight.Normal)
      .onClick(() => { this.tab = name })
  }

  @Builder
  storePage() {
    Scroll() {
      Column() {
        Text('装扮商城').fontSize(22).fontWeight(FontWeight.Bold).margin({ bottom: 12 })
        ForEach(skins, (skin: Skin) => {
          this.skinCard(skin)
        }, (skin: Skin) => 'skin-' + skin.id.toString())
        Text('套装').fontSize(18).fontWeight(FontWeight.Bold).margin({ top: 8, bottom: 8 })
        ForEach(sets, (item: SkinSet) => {
          Column() {
            Text(item.name).fontSize(16).fontWeight(FontWeight.Bold)
            Text(item.blurb).fontSize(13).fontColor('#8A9099').margin({ top: 4 })
            Button('免费使用')
              .width('100%')
              .height(40)
              .margin({ top: 8 })
              .backgroundColor('#FF6A00')
              .onClick(() => this.useSkin(item.skinId))
          }
          .width('100%')
          .padding(14)
          .margin({ bottom: 10 })
          .backgroundColor('#FFFFFF')
          .borderRadius(14)
        }, (item: SkinSet) => 'set-' + item.name)
      }
      .width('100%')
      .padding(16)
    }
    .layoutWeight(1)
  }

  @Builder
  skinCard(skin: Skin) {
    Column() {
      Row() {
        Column().width(36).height(28).backgroundColor(skin.bg).borderRadius(6).margin({ right: 8 })
        Column() {
          Text(skin.name).fontSize(16).fontWeight(FontWeight.Bold)
          Text(skin.blurb).fontSize(12).fontColor('#8A9099')
        }
        .alignItems(HorizontalAlign.Start)
        .layoutWeight(1)
      }.width('100%')
      Button(this.skinId === skin.id ? '使用中' : '免费使用')
        .width('100%')
        .height(40)
        .margin({ top: 10 })
        .backgroundColor(this.skinId === skin.id ? '#FFF1E8' : '#FF6A00')
        .fontColor(this.skinId === skin.id ? '#FF6A00' : '#FFFFFF')
        .onClick(() => this.useSkin(skin.id))
    }
    .width('100%')
    .padding(14)
    .margin({ bottom: 10 })
    .backgroundColor('#FFFFFF')
    .borderRadius(14)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  phrasePage() {
    Scroll() {
      Column() {
        Text('语录').fontSize(22).fontWeight(FontWeight.Bold).margin({ bottom: 12 })
        if (this.phrases.length === 0) {
          Text('还没有同步内容。到「我的」→「设置」登录并同步。')
            .fontSize(14)
            .fontColor('#8A9099')
        }
        ForEach(this.phrases, (item: ContentItem) => {
          Column() {
            Text(item.title || item.body).fontSize(15).fontWeight(FontWeight.Bold)
            if (item.body && item.body !== item.title) {
              Text(item.body).fontSize(13).fontColor('#8A9099').margin({ top: 4 })
            }
          }
          .width('100%')
          .padding(14)
          .margin({ bottom: 10 })
          .backgroundColor('#FFFFFF')
          .borderRadius(14)
          .alignItems(HorizontalAlign.Start)
        }, (item: ContentItem) => item.id)
      }
      .width('100%')
      .padding(16)
    }
    .layoutWeight(1)
  }

  @Builder
  minePage() {
    Scroll() {
      Column() {
        Row() {
          Image($r('app.media.app_icon')).width(44).height(44)
          Column() {
            Text(this.displayName).fontSize(18).fontWeight(FontWeight.Bold)
            Text(`当前皮肤 · ${this.skinName}`).fontSize(13).fontColor('#8A9099').margin({ top: 4 })
          }
          .alignItems(HorizontalAlign.Start)
          .margin({ left: 12 })
        }
        .width('100%')
        .padding(16)
        .backgroundColor('#FFFFFF')
        .borderRadius(16)
        Text('已拥有的皮肤').fontSize(16).fontWeight(FontWeight.Bold).margin({ top: 16, bottom: 8 })
        ForEach(skins, (skin: Skin) => {
          if (this.owned.indexOf(skin.id) >= 0) {
            this.skinCard(skin)
          }
        }, (skin: Skin) => 'owned-' + skin.id.toString())
        this.rowLink('设置', '账号、同步和试打', () => { this.screen = 'settings' })
        this.rowLink('用户服务协议', '', () => {
          this.legalTitle = '用户服务协议'
          this.legalBack = 'home'
          this.screen = 'legal'
        })
        this.rowLink('隐私政策', '', () => {
          this.legalTitle = '隐私政策'
          this.legalBack = 'home'
          this.screen = 'legal'
        })
      }
      .width('100%')
      .padding(16)
    }
    .layoutWeight(1)
  }

  @Builder
  rowLink(title: string, subtitle: string, action: () => void) {
    Column() {
      Text(title).fontSize(16)
      if (subtitle) {
        Text(subtitle).fontSize(12).fontColor('#8A9099').margin({ top: 4 })
      }
    }
    .width('100%')
    .padding(16)
    .margin({ top: 8 })
    .backgroundColor('#FFFFFF')
    .borderRadius(14)
    .alignItems(HorizontalAlign.Start)
    .onClick(action)
  }

  @Builder
  settingsPage() {
    Scroll() {
      Column() {
        Row() {
          Text('‹').fontSize(28).onClick(() => { this.screen = 'home'; this.tab = 'mine' })
          Text('设置').fontSize(18).fontWeight(FontWeight.Bold).margin({ left: 8 })
        }.width('100%')
        TextInput({ text: this.server, placeholder: '内容台地址' })
          .margin({ top: 16 })
          .onChange((value: string) => { this.server = value })
        TextInput({ text: this.username, placeholder: '账号' })
          .margin({ top: 8 })
          .onChange((value: string) => { this.username = value })
        TextInput({ text: this.password, placeholder: '密码' })
          .type(InputType.Password)
          .margin({ top: 8 })
          .onChange((value: string) => { this.password = value })
        Button('登录').width('100%').height(48).margin({ top: 12 }).backgroundColor('#FF6A00').onClick(() => this.doLogin())
        Button('同步内容包').width('100%').height(48).margin({ top: 8 }).backgroundColor('#FFF1E8').fontColor('#FF6A00').onClick(() => this.doSync())
        Button('打开系统输入法页面').width('100%').height(48).margin({ top: 8 }).backgroundColor('#FFFFFF').fontColor('#222222').onClick(() => this.openInputSettings())
        Text('如果没有进入输入法页面：设置 → 系统 → 输入法。启用苏晟输入法并设为默认。')
          .fontSize(12)
          .fontColor('#8A9099')
          .margin({ top: 8 })
        Text(this.status).fontSize(14).margin({ top: 12 })
      }
      .width('100%')
      .padding(16)
      .alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .height('100%')
  }

  async agree() {
    let context = getContext(this) as common.UIAbilityContext
    await acceptPrivacy(context)
    this.screen = 'home'
    this.tab = 'store'
  }

  reject() {
    let context = getContext(this) as common.UIAbilityContext
    context.terminateSelf()
  }

  async useSkin(id: number) {
    let context = getContext(this) as common.UIAbilityContext
    await applySkin(context, id)
    this.skinId = id
    this.owned = await ownedSkins(context)
  }

  openInputSettings() {
    let context = getContext(this) as common.UIAbilityContext
    let want: Want = {
      bundleName: 'com.huawei.hmos.settings',
      abilityName: 'com.huawei.hmos.settings.MainAbility',
      uri: 'set_input'
    }
    context.startAbility(want)
  }

  async doLogin() {
    // 默认回环地址（本地内容台）可用 http；但把凭据发往非本机的明文 http 主机存在泄露风险，
    // 登录前校验：非 https 且主机不是本机/回环时拒绝并提示，避免明文传输账号密码。
    if (this.cleartextCredentialRisk(this.server)) {
      this.status = '为保护账号密码，非本机地址请使用 https://'
      return
    }
    let context = getContext(this) as common.UIAbilityContext
    this.status = await login(context, this.server, this.username, this.password)
    let pref = await dataPreferences.getPreferences(context, 'susheng')
    this.displayName = await pref.get('displayName', this.displayName) as string
  }

  cleartextCredentialRisk(server: string): boolean {
    let lower = server.trim().toLowerCase()
    if (lower.startsWith('https://')) {
      return false
    }
    let noScheme = lower.startsWith('http://') ? lower.substring(7) : lower
    let host: string
    if (noScheme.startsWith('[')) {
      let end = noScheme.indexOf(']')
      host = end >= 0 ? noScheme.substring(0, end + 1) : noScheme
    } else {
      host = noScheme
      for (let i = 0; i < noScheme.length; i++) {
        let c = noScheme.charAt(i)
        if (c === '/' || c === ':' || c === '?' || c === '#') {
          host = noScheme.substring(0, i)
          break
        }
      }
    }
    let local = ['127.0.0.1', 'localhost', '::1', '[::1]', '0.0.0.0']
    return local.indexOf(host) < 0
  }

  onSkinIdChange() {
    this.skinName = skinById(this.skinId).name
  }

  async doSync() {
    let context = getContext(this) as common.UIAbilityContext
    this.status = await sync(context)
    this.phrases = await readPack(context)
  }
}
