import SwiftUI
import UIKit

@main
struct SushengApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

private final class RepositoryHolder: ObservableObject {
    let repo = ContentRepository()
}

private enum Screen { case guide, privacy, home, settings, legal }

struct RootView: View {
    @StateObject private var holder = RepositoryHolder()
    @State private var screen: Screen = KeyboardPrefs.accepted ? .home : .guide
    @State private var tab = 0
    @State private var legalTitle = "隐私政策"
    @State private var legalBack: Screen = .privacy
    @State private var server = "http://127.0.0.1:8787"
    @State private var username = ""
    @State private var password = ""
    @State private var status = "尚未登录"
    @State private var skinId = KeyboardPrefs.skinId
    @State private var owned = KeyboardPrefs.owned

    var body: some View {
        Group {
            switch screen {
            case .guide: guide
            case .privacy: privacy
            case .legal: legal
            case .settings: settings
            case .home: home
            }
        }
        .background(Color(red: 0.96, green: 0.96, blue: 0.97))
        .onAppear {
            holder.repo.loadLocal()
            if let session = holder.repo.session() {
                server = session.server
                status = "\(session.displayName) · 本地版本 \(holder.repo.serverVersion) · \(holder.repo.items.count) 条"
            }
        }
    }

    private var guide: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("将「苏晟输入法」设为默认输入法").font(.title2.bold())
            Text("打开 设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 苏晟输入法。需要剪贴板或图片时，再打开完全访问。按键内容不会上传。")
                .foregroundStyle(.secondary)
            Spacer()
            Button("我已添加，继续") { screen = .privacy }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 1, green: 0.42, blue: 0))
        }
        .padding(24)
    }

    private var privacy: some View {
        VStack(spacing: 12) {
            if let image = UIImage(named: "susheng_logo") {
                Image(uiImage: image).resizable().frame(width: 72, height: 72)
            }
            Text("欢迎您体验").foregroundStyle(.secondary)
            Text("苏晟输入法").font(.title.bold())
            Text("请阅读协议后选择。同意后进入首页。苏晟输入法不上传你敲下的文字。")
            ForEach(["用户服务协议", "隐私政策", "儿童隐私保护指引", "已收集个人信息清单", "第三方信息共享清单"], id: \.self) { title in
                Button("《\(title)》") {
                    legalTitle = title
                    legalBack = .privacy
                    screen = .legal
                }
            }
            Spacer()
            HStack {
                Button("拒绝") { exit(0) }.buttonStyle(.bordered)
                Button("同意") {
                    KeyboardPrefs.accepted = true
                    screen = .home
                }
                .buttonStyle(.borderedProminent)
                .tint(Color(red: 1, green: 0.42, blue: 0))
            }
        }
        .padding(24)
    }

    private var legal: some View {
        VStack(alignment: .leading) {
            HStack {
                Button("返回") { screen = legalBack }
                Text(legalTitle).font(.headline)
            }
            ScrollView { Text(legalText(legalTitle)).frame(maxWidth: .infinity, alignment: .leading) }
        }
        .padding(16)
    }

    private var home: some View {
        VStack(spacing: 0) {
            Group {
                if tab == 0 { store }
                else if tab == 1 { phrases }
                else { mine }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            HStack {
                tabButton("商城", 0)
                tabButton("语录", 1)
                tabButton("我的", 2)
            }
            .padding(.vertical, 10)
            .background(.white)
        }
    }

    private func tabButton(_ title: String, _ index: Int) -> some View {
        Button(title) { tab = index }
            .frame(maxWidth: .infinity)
            .foregroundStyle(tab == index ? Color(red: 1, green: 0.42, blue: 0) : .secondary)
            .fontWeight(tab == index ? .bold : .regular)
    }

    private var store: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("装扮商城").font(.title2.bold())
                ForEach(KeyboardSkins.all, id: \.id) { skin in skinCard(skin) }
                Text("套装").font(.headline)
                ForEach(KeyboardSkins.sets, id: \.0) { item in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(item.0).bold()
                        Text(item.1).font(.footnote).foregroundStyle(.secondary)
                        Button("免费使用") { use(item.2) }
                            .buttonStyle(.borderedProminent)
                            .tint(Color(red: 1, green: 0.42, blue: 0))
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding(16)
        }
    }

    private func skinCard(_ skin: KeyboardSkin) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                RoundedRectangle(cornerRadius: 6).fill(Color(skin.bg)).frame(width: 36, height: 28)
                VStack(alignment: .leading) {
                    Text(skin.name).bold()
                    Text(skin.blurb).font(.footnote).foregroundStyle(.secondary)
                }
            }
            Button(skinId == skin.id ? "使用中" : "免费使用") { use(skin.id) }
                .buttonStyle(.borderedProminent)
                .tint(skinId == skin.id ? Color(red: 1, green: 0.94, blue: 0.91) : Color(red: 1, green: 0.42, blue: 0))
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(.white)
        .clipShape(RoundedRectangle(cornerRadius: 14))
    }

    private var phrases: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                Text("语录").font(.title2.bold())
                if holder.repo.items.isEmpty {
                    Text("还没有同步内容。到「我的」→「设置」登录并同步。").foregroundStyle(.secondary)
                }
                ForEach(holder.repo.items.filter { !$0.deleted }, id: \.id) { item in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(item.title.isEmpty ? item.body : item.title).bold()
                        if !item.body.isEmpty && item.body != item.title {
                            Text(item.body).font(.footnote).foregroundStyle(.secondary)
                        }
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(.white)
                    .clipShape(RoundedRectangle(cornerRadius: 14))
                }
            }
            .padding(16)
        }
    }

    private var mine: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    if let image = UIImage(named: "susheng_logo") {
                        Image(uiImage: image).resizable().frame(width: 44, height: 44)
                    }
                    VStack(alignment: .leading) {
                        Text(holder.repo.session()?.displayName ?? "苏晟用户").font(.title3.bold())
                        Text("当前皮肤 · \(KeyboardSkins.byId(skinId).name)").font(.footnote).foregroundStyle(.secondary)
                    }
                }
                .padding(16)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(.white)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                Text("已拥有的皮肤").font(.headline)
                ForEach(KeyboardSkins.all.filter { owned.contains($0.id) }, id: \.id) { skinCard($0) }
                Button("设置") { screen = .settings }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.white).clipShape(RoundedRectangle(cornerRadius: 14))
                Button("用户服务协议") { openLegal("用户服务协议") }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.white).clipShape(RoundedRectangle(cornerRadius: 14))
                Button("隐私政策") { openLegal("隐私政策") }.frame(maxWidth: .infinity, alignment: .leading).padding().background(.white).clipShape(RoundedRectangle(cornerRadius: 14))
            }
            .padding(16)
        }
    }

    private var settings: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Button("返回") { screen = .home }
                    Text("设置").font(.headline)
                }
                TextField("内容台地址", text: $server).textFieldStyle(.roundedBorder)
                TextField("账号", text: $username).textFieldStyle(.roundedBorder)
                SecureField("密码", text: $password).textFieldStyle(.roundedBorder)
                Button("登录") { Task { await run { try await holder.repo.login(server: server, username: username, password: password) } } }
                    .buttonStyle(.borderedProminent).tint(Color(red: 1, green: 0.42, blue: 0))
                Button("同步内容包") { Task { await run { try await holder.repo.sync() } } }
                Text("启用方式：设置 → 通用 → 键盘 → 键盘 → 添加新键盘 → 苏晟输入法。")
                    .font(.footnote).foregroundStyle(.secondary)
                Text(status)
            }
            .padding(16)
        }
    }

    private func use(_ id: Int) {
        KeyboardPrefs.apply(id)
        skinId = id
        owned = KeyboardPrefs.owned
    }

    private func openLegal(_ title: String) {
        legalTitle = title
        legalBack = .home
        screen = .legal
    }

    private func run(_ work: @escaping () async throws -> String) async {
        do {
            status = try await work()
            holder.repo.loadLocal()
            status += " · \(holder.repo.items.count) 条"
        } catch {
            status = error.localizedDescription
        }
    }
}

private func legalText(_ title: String) -> String {
    let date = "生效日期：2026年10月6日\n\n"
    switch title {
    case "用户服务协议":
        return date + "欢迎使用苏晟输入法。本协议由苏晟集团与使用本应用的员工、合作达人订立。\n\n你敲下的拼音和文字只在本机完成组词，不作为聊天记录上传。应用内皮肤点按即可启用，选择保存在本机。"
    case "隐私政策":
        return date + "苏晟输入法不上传你正在输入的拼音、候选词和已经上屏的句子。\n\n登录会使用内容台账号和显示名。同步保存公司发布的话术、推文、宣发、词条和图片。插入公司内容时只上报条目编号和使用场景。\n\n拒绝协议将退出应用。已在系统里启用的键盘仍可在本机打字。"
    case "儿童隐私保护指引":
        return date + "苏晟输入法面向苏晟集团员工和合作达人，不向儿童提供账号、内容同步或个性化皮肤服务。"
    case "已收集个人信息清单":
        return date + "账号、显示名、内容台地址、话术使用记录（条目编号和场景）、本机皮肤偏好、同步下来的公司内容。\n\n不会收集通讯录、定位、短信，也不会收集你敲下的原文。"
    default:
        return date + "信息只交给公司内容台，用于登录、同步和话术使用统计。不会发送你敲下的拼音和上屏文字。iOS 系统负责键盘启用和剪贴板，语音仅在你调用系统语音时由系统处理。"
    }
}
