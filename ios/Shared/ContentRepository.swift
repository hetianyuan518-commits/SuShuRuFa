import Foundation
import Security

struct SessionFile: Codable {
    var server: String
    var token: String
    var displayName: String
}

/// 落盘到共享容器 session.json 的结构：只保留非敏感字段。
/// token 属于凭据，改存 Keychain（见 KeychainStore）。`token` 仅为兼容旧版本明文文件保留，用于一次性迁移。
private struct StoredSession: Codable {
    var server: String
    var displayName: String
    var token: String?
}

struct ContentPack: Codable {
    var serverVersion: Int
    var epoch: Int
    var items: [ContentItem]
}

struct LexiconFile: Codable {
    let entries: [LexEntry]
}

struct UsageEvent: Codable {
    let itemId: String
    let surface: String
}

enum AppGroup {
    static let identifier = "group.com.susheng.ime"
    static var root: URL {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: identifier)
            ?? FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }
}

/// 内容仓库：词库、内容包、会话与用量上报的本地存储 + 服务端同步。
///
/// 线程模型：除用量上报（reportUsage/flushUsage，见 usageQueue）外，本类型的
/// `items`/`lexicon`/`serverVersion`/`epoch` 等状态约定只在主 actor 上读写——宿主
/// App 的 SwiftUI body 与键盘扩展的 viewDidLoad 均在主线程访问。整类 actor 化会
/// 牵动所有调用点（含无法本机编译验证的键盘扩展），故此处以文档化不变量 +
/// 专用串行队列隔离唯一的跨上下文写入路径（用量文件）。
final class ContentRepository {
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private(set) var lexicon: [LexEntry] = []
    private(set) var items: [ContentItem] = []
    private(set) var serverVersion = 0
    private(set) var epoch = 0
    /// 本地内容包存在但解码失败时置真，供 UI 区分「无内容」与「读取失败」。
    private(set) var loadFailed = false
    /// 用量文件的读-改-写全部串行化到此队列，消除主线程同步磁盘 I/O 与并发丢失更新。
    private let usageQueue = DispatchQueue(label: "com.susheng.ime.usage")

    private var packURL: URL { AppGroup.root.appendingPathComponent("content-pack.json") }
    private var sessionURL: URL { AppGroup.root.appendingPathComponent("session.json") }
    private var usageURL: URL { AppGroup.root.appendingPathComponent("usage-queue.json") }
    private var assetDirectory: URL { AppGroup.root.appendingPathComponent("assets", isDirectory: true) }

    func loadLocal() {
        lexicon = loadLexicon()
        loadFailed = false
        guard FileManager.default.fileExists(atPath: packURL.path) else {
            items = []
            return
        }
        guard let data = try? Data(contentsOf: packURL),
              let pack = try? decoder.decode(ContentPack.self, from: data) else {
            // 文件存在却读不出/解不开：标记失败，让 UI 能提示重新同步而非当成空包。
            items = []
            loadFailed = true
            return
        }
        serverVersion = pack.serverVersion
        epoch = pack.epoch
        items = pack.items.filter { !$0.deleted }
    }

    func session() -> SessionFile? {
        guard let data = try? Data(contentsOf: sessionURL),
              let stored = try? decoder.decode(StoredSession.self, from: data) else { return nil }
        let keychainToken = KeychainStore.token
        let legacyToken = stored.token ?? ""
        // 旧版本把 token 明文写在 session.json；首次读取时迁移到 Keychain，并把文件里的明文抹掉。
        if keychainToken == nil && !legacyToken.isEmpty {
            KeychainStore.save(legacyToken)
            persistSession(server: stored.server, displayName: stored.displayName)
        }
        return SessionFile(server: stored.server, token: keychainToken ?? legacyToken, displayName: stored.displayName)
    }

    private func persistSession(server: String, displayName: String) {
        let stored = StoredSession(server: server, displayName: displayName, token: nil)
        try? encoder.encode(stored).write(to: sessionURL, options: .atomic)
    }

    func login(server: String, username: String, password: String) async throws -> String {
        let base = server.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        let body = try JSONSerialization.data(withJSONObject: ["username": username, "password": password])
        let payload = try await request(base: base, path: "/api/auth/login", token: nil, method: "POST", body: body)
        guard let token = payload["token"] as? String,
              let user = payload["user"] as? [String: Any] else {
            throw RepositoryError.message(payload["error"] as? String ?? "登录失败")
        }
        let session = SessionFile(server: base, token: token, displayName: user["displayName"] as? String ?? username)
        // token 存入 Keychain；共享容器里的 session.json 不再包含明文凭据。
        KeychainStore.save(token)
        persistSession(server: session.server, displayName: session.displayName)
        return "已登录"
    }

    func sync() async throws -> String {
        guard let session = session() else { throw RepositoryError.message("请先登录") }
        let since = epoch == 0 ? 0 : serverVersion
        let payload = try await request(base: session.server, path: "/api/sync?since=\(since)&epoch=\(epoch)", token: session.token, method: "GET", body: nil)
        if let error = payload["error"] as? String { throw RepositoryError.message(error) }
        let data = try JSONSerialization.data(withJSONObject: payload["items"] ?? [])
        let incoming = try decoder.decode([ContentItem].self, from: data)
        let responseSince = payload["since"] as? Int ?? 0
        let responseEpoch = payload["epoch"] as? Int ?? epoch
        if responseSince == 0 || responseEpoch != epoch {
            items = incoming.filter { !$0.deleted }
        } else {
            // 服务端下发的 id 可能重复，用 uniquingKeysWith 避免 uniqueKeysWithValues 运行时 trap。
            var map = Dictionary(items.map { ($0.id, $0) }, uniquingKeysWith: { _, new in new })
            for item in incoming {
                if item.deleted { map.removeValue(forKey: item.id) } else { map[item.id] = item }
            }
            items = Array(map.values)
        }
        serverVersion = payload["serverVersion"] as? Int ?? serverVersion
        epoch = responseEpoch
        // 先完成依赖新 items 的下载/写词表，全部成功后再持久化内容包，
        // 避免下载失败时磁盘上的 pack 已声称拥有未下载的资源、且 epoch 已提前推进。
        try await downloadAssets(session)
        try writeUserDict()
        let pack = ContentPack(serverVersion: serverVersion, epoch: epoch, items: items)
        try encoder.encode(pack).write(to: packURL, options: .atomic)
        await flushUsage(session)
        _ = SushengRimeAvailable()
        return "已同步到版本 \(serverVersion)"
    }

    func reportUsage(itemId: String, surface: String) {
        // 读-改-写整体派发到专用串行队列：既不阻塞主线程打字，也消除多来源
        // （键盘扩展 + 宿主 App）并发上报时的丢失更新。用局部 coder，避免与主线程
        // 共享的 decoder/encoder 并发使用。
        let event = UsageEvent(itemId: itemId, surface: surface)
        usageQueue.async { [weak self] in
            guard let self else { return }
            let url = self.usageURL
            let decoder = JSONDecoder()
            let encoder = JSONEncoder()
            var queue: [UsageEvent] = []
            if let data = try? Data(contentsOf: url),
               let loaded = try? decoder.decode([UsageEvent].self, from: data) {
                queue = loaded
            }
            queue.append(event)
            if queue.count > 200 { queue.removeFirst(queue.count - 200) }
            if let data = try? encoder.encode(queue) {
                try? data.write(to: url, options: .atomic)
            }
        }
    }

    private func readUsageSerial() -> [UsageEvent] {
        let decoder = JSONDecoder()
        return usageQueue.sync {
            guard let data = try? Data(contentsOf: usageURL),
                  let queue = try? decoder.decode([UsageEvent].self, from: data) else { return [] }
            return queue
        }
    }

    private func writeUsageSerial(_ events: [UsageEvent]) {
        let encoder = JSONEncoder()
        usageQueue.sync {
            if let data = try? encoder.encode(events) {
                try? data.write(to: usageURL, options: .atomic)
            }
        }
    }

    private func flushUsage(_ session: SessionFile) async {
        let queue = readUsageSerial()
        guard !queue.isEmpty else { return }
        var sent = 0
        for event in queue {
            let body = try? JSONSerialization.data(withJSONObject: ["itemId": event.itemId, "surface": event.surface])
            do {
                _ = try await request(base: session.server, path: "/api/usage", token: session.token, method: "POST", body: body)
                sent += 1
            } catch {
                break
            }
        }
        guard sent > 0 else { return }
        // 重新读取队列，避免覆盖 flush 期间新追加的事件；仅移除已成功上传的部分。
        var remaining = readUsageSerial()
        for event in queue.prefix(sent) {
            if let index = remaining.firstIndex(where: { $0.itemId == event.itemId && $0.surface == event.surface }) {
                remaining.remove(at: index)
            }
        }
        writeUsageSerial(remaining)
    }

    func assetURL(_ id: String) -> URL {
        // 只取最后一段路径，剔除服务端下发 assetId 中可能夹带的 ../ 或目录分隔符，防止路径穿越。
        let safe = (id as NSString).lastPathComponent
        return assetDirectory.appendingPathComponent(safe)
    }

    private func downloadAssets(_ session: SessionFile) async throws {
        try FileManager.default.createDirectory(at: assetDirectory, withIntermediateDirectories: true)
        for item in items {
            guard let assetId = item.assetId, !assetId.isEmpty else { continue }
            if assetId.contains("/") || assetId.contains("\\") || assetId.contains("..") { continue }
            let file = assetURL(assetId)
            if FileManager.default.fileExists(atPath: file.path) { continue }
            let encoded = assetId.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) ?? assetId
            guard let url = URL(string: session.server + "/api/assets/" + encoded) else { continue }
            var request = URLRequest(url: url)
            request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization")
            let (data, response) = try await URLSession.shared.data(for: request)
            // 4xx/5xx 的响应体（如 JSON 错误）不能当成资源落盘，否则下次同步的存在性检查会把损坏文件当作有效。
            if let http = response as? HTTPURLResponse, http.statusCode >= 400 { continue }
            try data.write(to: file, options: .atomic)
        }
    }

    private func writeUserDict() throws {
        let directory = AppGroup.root.appendingPathComponent("rime-user", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var lines = ["# 后台下发的官方词", "---", "name: susheng", "version: \"1\"", "sort: by_weight", "use_preset_vocabulary: false", "..."]
        for item in items where !item.deleted && item.type != "image" && !item.pinyin.isEmpty {
            let word = item.body.replacingOccurrences(of: "\n", with: "").replacingOccurrences(of: "\t", with: " ")
            if !word.isEmpty { lines.append("\(word)\t\(item.pinyin)\t9000") }
        }
        try lines.joined(separator: "\n").write(to: directory.appendingPathComponent("susheng.user.dict.yaml"), atomically: true, encoding: .utf8)
    }

    private func loadLexicon() -> [LexEntry] {
        guard let url = Bundle.main.url(forResource: "base-lexicon", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let file = try? decoder.decode(LexiconFile.self, from: data) else { return [] }
        return file.entries
    }

    private func request(base: String, path: String, token: String?, method: String, body: Data?) async throws -> [String: Any] {
        guard let url = URL(string: base + path) else { throw RepositoryError.message("无效的服务器地址") }
        var request = URLRequest(url: url)
        request.httpMethod = method
        if let token { request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
        if let body {
            request.httpBody = body
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        if let http = response as? HTTPURLResponse, http.statusCode >= 400 {
            throw RepositoryError.message(json["error"] as? String ?? "请求失败")
        }
        return json
    }
}

enum RepositoryError: LocalizedError {
    case message(String)
    var errorDescription: String? {
        switch self {
        case .message(let text): return text
        }
    }
}

/// 仅主 App 使用登录 token（键盘扩展不做 sync/鉴权），因此存入应用默认 Keychain，
/// 无需 keychain-access-groups entitlement，也不与扩展共享，避免明文凭据落在 App Group 容器里。
enum KeychainStore {
    private static let service = "com.susheng.ime.session"
    private static let account = "token"

    private static func baseQuery() -> [String: Any] {
        return [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
    }

    static var token: String? {
        var query = baseQuery()
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
              let data = item as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    static func save(_ value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query = baseQuery()
        let status = SecItemUpdate(query as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if status == errSecItemNotFound {
            var add = query
            add[kSecValueData as String] = data
            add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(add as CFDictionary, nil)
        }
    }

    static func delete() {
        SecItemDelete(baseQuery() as CFDictionary)
    }
}
