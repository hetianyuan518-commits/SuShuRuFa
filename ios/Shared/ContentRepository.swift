import Foundation

struct SessionFile: Codable {
    var server: String
    var token: String
    var displayName: String
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

final class ContentRepository {
    private let decoder = JSONDecoder()
    private let encoder = JSONEncoder()
    private(set) var lexicon: [LexEntry] = []
    private(set) var items: [ContentItem] = []
    private(set) var serverVersion = 0
    private(set) var epoch = 0

    private var packURL: URL { AppGroup.root.appendingPathComponent("content-pack.json") }
    private var sessionURL: URL { AppGroup.root.appendingPathComponent("session.json") }
    private var usageURL: URL { AppGroup.root.appendingPathComponent("usage-queue.json") }
    private var assetDirectory: URL { AppGroup.root.appendingPathComponent("assets", isDirectory: true) }

    func loadLocal() {
        lexicon = loadLexicon()
        guard let data = try? Data(contentsOf: packURL), let pack = try? decoder.decode(ContentPack.self, from: data) else {
            items = []
            return
        }
        serverVersion = pack.serverVersion
        epoch = pack.epoch
        items = pack.items.filter { !$0.deleted }
    }

    func session() -> SessionFile? {
        guard let data = try? Data(contentsOf: sessionURL) else { return nil }
        return try? decoder.decode(SessionFile.self, from: data)
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
        try encoder.encode(session).write(to: sessionURL, options: .atomic)
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
            var map = Dictionary(uniqueKeysWithValues: items.map { ($0.id, $0) })
            for item in incoming {
                if item.deleted { map.removeValue(forKey: item.id) } else { map[item.id] = item }
            }
            items = Array(map.values)
        }
        serverVersion = payload["serverVersion"] as? Int ?? serverVersion
        epoch = responseEpoch
        let pack = ContentPack(serverVersion: serverVersion, epoch: epoch, items: items)
        try encoder.encode(pack).write(to: packURL, options: .atomic)
        try await downloadAssets(session)
        try writeUserDict()
        await flushUsage(session)
        _ = SushengRimeAvailable()
        return "已同步到版本 \(serverVersion)"
    }

    func reportUsage(itemId: String, surface: String) {
        var queue = loadUsage()
        queue.append(UsageEvent(itemId: itemId, surface: surface))
        if queue.count > 200 { queue.removeFirst(queue.count - 200) }
        guard let data = try? encoder.encode(queue) else { return }
        try? data.write(to: usageURL, options: .atomic)
    }

    private func loadUsage() -> [UsageEvent] {
        guard let data = try? Data(contentsOf: usageURL),
              let queue = try? decoder.decode([UsageEvent].self, from: data) else { return [] }
        return queue
    }

    private func flushUsage(_ session: SessionFile) async {
        let queue = loadUsage()
        guard !queue.isEmpty else { return }
        for event in queue {
            let body = try? JSONSerialization.data(withJSONObject: ["itemId": event.itemId, "surface": event.surface])
            _ = try? await request(base: session.server, path: "/api/usage", token: session.token, method: "POST", body: body)
        }
        try? encoder.encode([UsageEvent]()).write(to: usageURL, options: .atomic)
    }

    func assetURL(_ id: String) -> URL {
        assetDirectory.appendingPathComponent(id)
    }

    private func downloadAssets(_ session: SessionFile) async throws {
        try FileManager.default.createDirectory(at: assetDirectory, withIntermediateDirectories: true)
        for item in items {
            guard let assetId = item.assetId else { continue }
            let file = assetURL(assetId)
            if FileManager.default.fileExists(atPath: file.path) { continue }
            var request = URLRequest(url: URL(string: session.server + "/api/assets/\(assetId)")!)
            request.setValue("Bearer \(session.token)", forHTTPHeaderField: "Authorization")
            let (data, _) = try await URLSession.shared.data(for: request)
            try data.write(to: file)
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
        var request = URLRequest(url: URL(string: base + path)!)
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
