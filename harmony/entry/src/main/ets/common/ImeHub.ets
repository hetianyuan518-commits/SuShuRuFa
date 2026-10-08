import inputMethodEngine from '@ohos.inputMethodEngine'
import InputMethodExtensionContext from '@ohos.InputMethodExtensionContext'

export class ImeHub {
  static client: inputMethodEngine.InputClient | undefined = undefined
  static context: InputMethodExtensionContext | undefined = undefined

  static insert(text: string): void {
    if (ImeHub.client !== undefined) {
      ImeHub.client.insertTextSync(text)
    }
  }

  static backspace(): void {
    if (ImeHub.client !== undefined) {
      ImeHub.client.deleteBackwardSync(1)
    }
  }
}
