import { Candidate, associate, candidates } from '../common/PinyinEngine'
import { ContentItem, LexEntry, queueUsage, readLexicon, readPack } from '../common/ContentRead'
import { ImeHub } from '../common/ImeHub'
import { Skin, currentSkin, skinById } from '../common/Skin'
import pasteboard from '@ohos.pasteboard'
import image from '@ohos.multimedia.image'
import fs from '@ohos.file.fs'

@Component
struct KeyButton {
  @Prop label: string = ''
  @Prop keyColor: string = '#FFFFFF'
  @Prop textColor: string = '#222222'
  onTap: () => void = () => {}
  build() {
    Button(this.label)
      .fontSize(16)
      .layoutWeight(1)
      .height(42)
      .margin(2)
      .backgroundColor(this.keyColor)
      .fontColor(this.textColor)
      .onClick(() => this.onTap())
  }
}

@Entry
@Component
struct KeyboardPage {
  @State raw: string = ''
  @State mode: string = 'qwerty'
  @State hint: string = ''
  @State rows: Candidate[] = []
  @State panel: string = ''
  @State panelItems: ContentItem[] = []
  @State skin: Skin = skinById(0)
  private lexicon: LexEntry[] = []
  private items: ContentItem[] = []
  private lastCommit: string = ''
  private associating: boolean = false

  async aboutToAppear() {
    if (ImeHub.context !== undefined) {
      this.skin = await currentSkin(ImeHub.context)
      this.lexicon = readLexicon(ImeHub.context)
      this.items = await readPack(ImeHub.context)
    }
    this.refresh()
  }

  build() {
    Column() {
      Text(this.raw || ' ')
        .width('100%')
        .fontSize(14)
        .padding(8)
      Scroll() {
        Row() {
          ForEach(this.rows, (row: Candidate) => {
            Button(row.text)
              .fontSize(16)
              .margin(4)
              .backgroundColor('#E7DCC8')
              .fontColor('#1B140F')
              .onClick(() => this.choose(row))
          }, (row: Candidate, index: number) => index + ':' + (row.itemId ? row.itemId : row.source + ':' + row.commit))
        }
      }.scrollable(ScrollDirection.Horizontal).height(48)
      Row() {
        Image($r('app.media.app_icon')).width(32).height(32).margin({ left: 8, right: 8 })
        this.tool('话术', 'script')
        this.tool('推文', 'tweet')
        this.tool('宣发', 'promo')
        this.tool('图片', 'image')
      }.width('100%')
      if (this.panel) {
        Column() {
          ForEach(this.panelItems, (item: ContentItem) => {
            Button(item.title || item.body)
              .width('100%')
              .onClick(() => this.insertItem(item))
          }, (item: ContentItem, index: number) => index + ':' + item.id)
        }.height(120)
      }
      if (this.mode === 't9') {
        this.keyRow(['分词', 'ABC', 'DEF', '删除'], ["'", '2', '3', '\b'])
        this.keyRow(['GHI', 'JKL', 'MNO'], ['4', '5', '6'])
        this.keyRow(['PQRS', 'TUV', 'WXYZ'], ['7', '8', '9'])
        this.keyRow(['全拼', '空格', '上屏'], ['mode', ' ', '\n'])
      } else {
        this.keyRow(['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'], ['q', 'w', 'e', 'r', 't', 'y', 'u', 'i', 'o', 'p'])
        this.keyRow(['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'], ['a', 's', 'd', 'f', 'g', 'h', 'j', 'k', 'l'])
        this.keyRow(['z', 'x', 'c', 'v', 'b', 'n', 'm'], ['z', 'x', 'c', 'v', 'b', 'n', 'm'])
        this.keyRow(['九键', '空格', '删除', '换行'], ['mode', ' ', '\b', '\n'])
      }
      Text(this.hint).fontSize(12).fontColor('#C4372C')
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.skin.bg)
  }

  @Builder
  tool(label: string, kind: string) {
    Button(label)
      .layoutWeight(1)
      .fontSize(14)
      .onClick(() => {
        this.panel = this.panel === kind ? '' : kind
        this.panelItems = []
        for (let i = 0; i < this.items.length; i++) {
          if (this.items[i].type === this.panel && !this.items[i].deleted) {
            this.panelItems.push(this.items[i])
          }
        }
      })
  }

  @Builder
  keyRow(labels: string[], values: string[]) {
    Row() {
      ForEach(labels, (label: string, index: number) => {
        KeyButton({ label: label, keyColor: this.skin.key, textColor: this.skin.text, onTap: (): void => this.onKey(values[index]) })
      }, (label: string, index: number) => index + ':' + label)
    }.width('100%')
  }

  onKey(value: string) {
    if (value === 'mode') {
      this.mode = this.mode === 't9' ? 'qwerty' : 't9'
      this.raw = ''
    } else if (value === '\b') {
      this.associating = false
      if (this.raw.length > 0) {
        this.raw = this.raw.substring(0, this.raw.length - 1)
      } else {
        ImeHub.backspace()
      }
    } else if (value === '\n') {
      if (this.raw && this.rows.length > 0) {
        this.choose(this.rows[0])
      } else {
        ImeHub.insert('\n')
      }
    } else if (value === ' ') {
      if ((this.raw || this.associating) && this.rows.length > 0) {
        this.choose(this.rows[0])
      } else {
        ImeHub.insert(' ')
      }
    } else {
      this.associating = false
      this.raw += value
    }
    this.refresh()
  }

  refresh() {
    if (this.raw) {
      this.rows = candidates(this.raw, this.mode === 't9' ? 't9' : 'qwerty', this.lexicon, this.items, Date.now())
    } else if (this.associating) {
      this.rows = associate(this.lastCommit, this.lexicon, this.items, Date.now())
    } else {
      this.rows = []
    }
  }

  choose(row: Candidate) {
    if (row.kind === 'image' && row.assetId) {
      // 图片分支也要复位联想状态，否则上一次提交遗留的 associating=true
      // 会让随后的 refresh() 继续用旧 lastCommit 拉出不相关的联想候选。
      this.associating = false
      this.lastCommit = ''
      this.pasteImage(row.assetId, row.itemId)
    } else {
      ImeHub.insert(row.commit)
      this.lastCommit = row.text
      this.associating = row.commit.length > 0
      this.note(row.itemId, row.kind)
    }
    this.raw = ''
    this.refresh()
  }

  insertItem(item: ContentItem) {
    if (item.type === 'image' && item.assetId) {
      this.pasteImage(item.assetId, item.id)
    } else {
      ImeHub.insert(item.body)
      this.lastCommit = item.title
      this.associating = true
      this.note(item.id, item.type)
    }
    this.panel = ''
    this.refresh()
  }

  note(itemId: string, surface: string) {
    if (!itemId || ImeHub.context === undefined) {
      return
    }
    queueUsage(ImeHub.context, itemId, surface)
  }

  async pasteImage(assetId: string, itemId: string) {
    if (ImeHub.context === undefined) {
      return
    }
    // assetId 来自服务端，拒绝包含路径分隔符或 .. 的值，防止读取 assets 目录以外的文件。
    if (assetId.includes('/') || assetId.includes('\\') || assetId.includes('..')) {
      this.hint = '图片还没同步下来'
      return
    }
    let path = ImeHub.context.filesDir + '/assets/' + assetId
    if (!fs.accessSync(path)) {
      this.hint = '图片还没同步下来'
      return
    }
    let source: image.ImageSource | undefined = undefined
    try {
      let file = fs.openSync(path, fs.OpenMode.READ_ONLY)
      let buffer: ArrayBuffer
      try {
        let stat = fs.statSync(path)
        buffer = new ArrayBuffer(stat.size)
        fs.readSync(file.fd, buffer)
      } finally {
        fs.closeSync(file)
      }
      source = image.createImageSource(buffer)
      let pixel = await source.createPixelMap()
      let data = pasteboard.createData(pasteboard.MIMETYPE_PIXELMAP, pixel)
      await pasteboard.getSystemPasteboard().setData(data)
      this.hint = '已复制图片，请在输入框粘贴'
      this.note(itemId, 'image')
    } catch (e) {
      this.hint = '图片复制失败'
    } finally {
      // 无论成败失败都释放 image source，避免资源泄露。
      if (source !== undefined) {
        source.release()
      }
    }
  }
}
