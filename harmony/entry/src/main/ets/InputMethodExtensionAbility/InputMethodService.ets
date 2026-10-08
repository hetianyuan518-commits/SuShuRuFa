import { Want } from '@kit.AbilityKit'
import InputMethodExtensionAbility from '@ohos.InputMethodExtensionAbility'
import inputMethodEngine from '@ohos.inputMethodEngine'
import { ImeHub } from '../common/ImeHub'

export default class InputMethodService extends InputMethodExtensionAbility {
  onCreate(want: Want): void {
    ImeHub.context = this.context
    let panelInfo: inputMethodEngine.PanelInfo = {
      type: inputMethodEngine.PanelType.SOFT_KEYBOARD,
      flag: inputMethodEngine.PanelFlag.FLG_FIXED
    }
    let ability = inputMethodEngine.getInputMethodAbility()
    ability.createPanel(this.context, panelInfo).then((panel: inputMethodEngine.Panel) => {
      panel.setUiContent('pages/Keyboard')
      panel.resize(1260, 900)
    })
    ability.on('inputStart', (_keyboard: inputMethodEngine.KeyboardController, client: inputMethodEngine.InputClient) => {
      ImeHub.client = client
    })
  }

  onDestroy(): void {
    ImeHub.client = undefined
    ImeHub.context = undefined
  }
}
