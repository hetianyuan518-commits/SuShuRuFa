# 苏晟输入法

内容台统一下发话术、推文、宣发、词条和图片。Android、iOS、鸿蒙、macOS、Windows 读取同一份内容包。客户端只上报「哪条素材被插入」，不上传键入正文。

## 内容台

```bash
cd backend
npm start
```

打开 http://127.0.0.1:8787

| 账号 | 密码 | 角色 |
| --- | --- | --- |
| admin | susheng-admin | 管理员 |
| staff | susheng-staff | 综艺组员工 |
| creator | susheng-creator | 星火达人 |

达人只能同步「达人-星火」里已发布、且在生效时间内的内容。员工还能看到「全员」和「综艺组」。

增量同步：`GET /api/sync?since={serverVersion}&epoch={epoch}`。权限变化会抬高 epoch，客户端应丢弃本地包并重新全量拉取。

## 拼音

`protocol/pinyin-engine.mjs` 是候选规则的对照实现，覆盖全拼、九键、简拼和 `v` 短码。基础词表来自 jieba、phrase-pinyin-data、THUOCL 和公有领域诗文，不包含 GPL 词库，也不包含搜狗词库。

```bash
node protocol/build-assets.mjs
node --test protocol/pinyin-engine.test.mjs backend/test/api.test.mjs
```

`rime/` 里是 librime 方案。Android / iOS 默认走内置引擎；编好 librime 后再链上原生库，见 `android/scripts/build-librime.sh` 和 `ios/scripts/build-librime.sh`。桌面端直接使用同一套内置规则。

## 客户端

- Android：用 Android Studio 打开 `android/`。模拟器访问内容台用 `http://10.0.2.2:8787`。密码框会切回下一个输入法。图片优先 `commitContent`，对方不接收就复制到剪贴板。
- iOS：用 Xcode 打开 `ios/SushengIME.xcodeproj`，签名里勾上 App Group `group.com.susheng.ime`。完全访问只为了读写剪贴板。键盘把素材使用记进 App Group，主 App 同步时再上报。密码框读取 `isSecureTextEntry` 后调用 `advanceToNextInputMode()`。键盘高度固定，内存告警时清掉面板。
- 鸿蒙：用 DevEco Studio 打开 `harmony/`。输入法页面不发起网络请求，使用记录先记在本地，主应用下次同步时再上报。图片通过系统剪贴板粘贴。
- macOS：在 `macos/` 执行 `sh build.sh`，得到 `苏晟输入法.app`。把它拷到 `~/Library/Input Methods/`，在系统设置的键盘输入法里启用「苏晟拼音」。这是系统输入法：全拼、九键、联想、`v` 短码，以及话术、推文、宣发、图片。Shift+Space 切换中英文。密码框检测到安全输入后把按键交回系统。图片复制到剪贴板再粘贴。输入法菜单里可以登录并同步。
- Windows：在 `windows/` 执行 `sh build.sh`（或在 Windows 上 `go build -o susheng.exe ./cmd/susheng`）。运行后出现托盘图标，Ctrl+Space 开关中文。提交的文字送进当前窗口，候选显示在悬浮条上。F2 到 F5 打开话术、推文、宣发和图片。密码输入框会放行。托盘菜单里登录并同步。使用记录先写在本地，同步时再上报。
