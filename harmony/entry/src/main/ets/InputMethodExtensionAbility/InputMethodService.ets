import { Want } from '@kit.AbilityKit'
import InputMethodExtensionAbility from '@ohos.InputMethodExtensionAbility'
import inputMethodEngine from '@ohos.inputMethodEngine'
import { ImeHub } from '../common/ImeHub'

// 软键盘面板的初始尺寸（单位：vp）。抽为具名常量，便于统一调整与后续多设备适配。
const PANEL_WIDTH = 1260
const PANEL_HEIGHT = 900

export default class InputMethodService extends InputMethodExtensionAbility {
  private ability: inputMethodEngine.InputMethodAbility | undefined = undefined
  private panel: inputMethodEngine.Panel | undefined = undefined
  private inputStartCallback = (_keyboard: inputMethodEngine.KeyboardController, client: inputMethodEngine.InputClient): void => {
    ImeHub.client = client
  }

  onCreate(want: Want): void {
    ImeHub.context = this.context
    let panelInfo: inputMethodEngine.PanelInfo = {
      type: inputMethodEngine.PanelType.SOFT_KEYBOARD,
      flag: inputMethodEngine.PanelFlag.FLG_FIXED
    }
    let ability = inputMethodEngine.getInputMethodAbility()
    this.ability = ability
    ability.createPanel(this.context, panelInfo).then((panel: inputMethodEngine.Panel) => {
      this.panel = panel
      panel.setUiContent('pages/Keyboard')
      panel.resize(PANEL_WIDTH, PANEL_HEIGHT)
    }).catch((_e: Object) => {
      // 面板创建失败时吸掉 rejection，避免未处理异常。
    })
    ability.on('inputStart', this.inputStartCallback)
  }

  onDestroy(): void {
    if (this.ability !== undefined) {
      try {
        this.ability.off('inputStart', this.inputStartCallback)
      } catch (e) {
        // 监听器可能已自动解除，忽略。
      }
      if (this.panel !== undefined) {
        this.ability.destroyPanel(this.panel).catch((_e: Object) => {
        })
      }
    }
    this.panel = undefined
    this.ability = undefined
    ImeHub.client = undefined
    ImeHub.context = undefined
  }
}
