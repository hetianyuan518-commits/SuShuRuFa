import UIAbility from '@ohos.app.ability.UIAbility'
import window from '@ohos.window'
import { Want } from '@kit.AbilityKit'

export default class EntryAbility extends UIAbility {
  onCreate(_want: Want): void {}

  onWindowStageCreate(windowStage: window.WindowStage): void {
    windowStage.loadContent('pages/Index')
  }
}
