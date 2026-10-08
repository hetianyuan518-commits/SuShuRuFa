import UIKit

struct KeyboardSkin {
    let id: Int
    let name: String
    let blurb: String
    let bg: UIColor
    let key: UIColor
    let text: UIColor
    let accent: UIColor
}

enum KeyboardSkins {
    static let all: [KeyboardSkin] = [
        KeyboardSkin(id: 0, name: "浅灰日常", blurb: "白键橙字，适合每天打字", bg: rgb(0xF3F4F6), key: .white, text: rgb(0x222222), accent: rgb(0xFF6A00)),
        KeyboardSkin(id: 1, name: "宣纸朱红", blurb: "纸色底，朱红点缀", bg: rgb(0xF3EADB), key: rgb(0xFFFCF7), text: rgb(0x1B140F), accent: rgb(0xC4372C)),
        KeyboardSkin(id: 2, name: "新叶", blurb: "清浅绿色按键", bg: rgb(0xE7F6E4), key: .white, text: rgb(0x1C3A24), accent: rgb(0x2E9B4A)),
        KeyboardSkin(id: 3, name: "夜航", blurb: "深色键盘，适合夜间口播", bg: rgb(0x1C1E27), key: rgb(0x2C3140), text: rgb(0xF2F4F8), accent: rgb(0xFF8A3D)),
        KeyboardSkin(id: 4, name: "海盐", blurb: "浅蓝底，适合白天发布", bg: rgb(0xE7F2FA), key: .white, text: rgb(0x16324A), accent: rgb(0x2F7FE0)),
        KeyboardSkin(id: 5, name: "暖杏", blurb: "暖色按键，适合欢迎和感谢", bg: rgb(0xFDEFE3), key: rgb(0xFFFAF6), text: rgb(0x4A2E22), accent: rgb(0xE36A2E))
    ]
    static let sets: [(String, String, Int)] = [
        ("直播暖场", "宣纸朱红，适合开场和欢迎语", 1),
        ("夜场口播", "夜航深色，适合夜间直播", 3),
        ("清新日常", "新叶配色，适合白天发内容", 2),
        ("海盐发布", "海盐配色，适合评论和标题", 4)
    ]
    static func byId(_ id: Int) -> KeyboardSkin { all.first { $0.id == id } ?? all[0] }
    private static func rgb(_ hex: Int) -> UIColor {
        UIColor(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

enum KeyboardPrefs {
    private static let suite = UserDefaults(suiteName: "group.com.susheng.ime") ?? .standard
    static var accepted: Bool {
        get { suite.bool(forKey: "privacy") }
        set { suite.set(newValue, forKey: "privacy") }
    }
    static var skinId: Int {
        get { suite.integer(forKey: "skin") }
        set { suite.set(newValue, forKey: "skin") }
    }
    static var owned: Set<Int> {
        get {
            let raw = suite.string(forKey: "owned") ?? "0"
            let ids = Set(raw.split(separator: ",").compactMap { Int($0) })
            return ids.isEmpty ? [0] : ids
        }
        set { suite.set(newValue.map(String.init).joined(separator: ","), forKey: "owned") }
    }
    static func apply(_ id: Int) {
        var next = owned
        next.insert(id)
        owned = next
        skinId = id
    }
}
