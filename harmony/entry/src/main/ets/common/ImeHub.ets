import inputMethodEngine from '@ohos.inputMethodEngine'
import InputMethodExtensionContext from '@ohos.InputMethodExtensionContext'

export class ImeHub {
  static client: inputMethodEngine.InputClient | undefined = undefined
  static context: InputMethodExtensionContext | undefined = undefined

  static insert(text: string): void {
    // 跳过空/缺失文本，避免向编辑会话推送无效插入。
    // （不做长度截断：上屏内容可能是较长的话术/推文，截断会损坏合法输出。）
    if (!text) {
      return
    }
    if (ImeHub.client !== undefined) {
      try {
        ImeHub.client.insertTextSync(text)
      } catch (e) {
        // 编辑器可能已失焦或 client 已失效，静默忽略，避免未处理异常打断输入。
      }
    }
  }

  static backspace(): void {
    if (ImeHub.client !== undefined) {
      try {
        ImeHub.client.deleteBackwardSync(1)
      } catch (e) {
        // 同上：stale client 时静默失败。
      }
    }
  }
}
