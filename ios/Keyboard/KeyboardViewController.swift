import UIKit

final class KeyboardViewController: UIInputViewController {
    private let repo = ContentRepository()
    private let stack = UIStackView()
    private let composing = UILabel()
    private let candidateRow = UIStackView()
    private let panel = UIStackView()
    private let keys = UIStackView()
    private let hint = UILabel()
    private var mode = "qwerty"
    private var chinese = true
    private var raw = ""
    private var lastCommit = ""
    private var associating = false
    private var panelKind: String?
    private var skin = KeyboardSkins.byId(KeyboardPrefs.skinId)
    private var candidateCacheKey: String?
    private var candidateCache: [Candidate] = []

    override func viewDidLoad() {
        super.viewDidLoad()
        repo.loadLocal()
        skin = KeyboardSkins.byId(KeyboardPrefs.skinId)
        view.backgroundColor = skin.bg
        stack.axis = .vertical
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        let height = view.heightAnchor.constraint(equalToConstant: 320)
        height.priority = .defaultHigh
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            stack.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            stack.topAnchor.constraint(equalTo: view.topAnchor),
            stack.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            height
        ])
        composing.font = .systemFont(ofSize: 14)
        composing.textColor = skin.text
        candidateRow.axis = .horizontal
        panel.axis = .vertical
        keys.axis = .vertical
        hint.font = .systemFont(ofSize: 12)
        hint.textColor = UIColor(red: 0.77, green: 0.22, blue: 0.17, alpha: 1)
        hint.numberOfLines = 2
        [composing, candidateScroll(), toolbar(), panel, keys, hint].forEach { stack.addArrangedSubview($0) }
        panel.isHidden = true
        renderKeys()
        refresh()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        repo.loadLocal()
        skin = KeyboardSkins.byId(KeyboardPrefs.skinId)
        view.backgroundColor = skin.bg
        composing.textColor = skin.text
        renderKeys()
        fallbackIfSecure(nil)
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        fallbackIfSecure(textInput)
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        panelKind = nil
        renderPanel()
        candidateRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
    }

    private func fallbackIfSecure(_ textInput: UITextInput?) {
        if textDocumentProxy.isSecureTextEntry == true {
            advanceToNextInputMode()
            return
        }
        if let field = textInput as? UITextField, field.isSecureTextEntry {
            advanceToNextInputMode()
            return
        }
        if let textView = textInput as? UITextView, textView.isSecureTextEntry {
            advanceToNextInputMode()
        }
    }

    private func candidateScroll() -> UIScrollView {
        let scroll = UIScrollView()
        scroll.translatesAutoresizingMaskIntoConstraints = false
        scroll.heightAnchor.constraint(equalToConstant: 44).isActive = true
        candidateRow.translatesAutoresizingMaskIntoConstraints = false
        scroll.addSubview(candidateRow)
        NSLayoutConstraint.activate([
            candidateRow.leadingAnchor.constraint(equalTo: scroll.contentLayoutGuide.leadingAnchor),
            candidateRow.trailingAnchor.constraint(equalTo: scroll.contentLayoutGuide.trailingAnchor),
            candidateRow.topAnchor.constraint(equalTo: scroll.contentLayoutGuide.topAnchor),
            candidateRow.bottomAnchor.constraint(equalTo: scroll.contentLayoutGuide.bottomAnchor),
            candidateRow.heightAnchor.constraint(equalTo: scroll.frameLayoutGuide.heightAnchor)
        ])
        return scroll
    }

    private func toolbar() -> UIStackView {
        let bar = UIStackView()
        bar.axis = .horizontal
        bar.distribution = .fillEqually
        if let logo = UIImage(named: "susheng_logo") {
            let mark = UIImageView(image: logo)
            mark.contentMode = .scaleAspectFit
            mark.widthAnchor.constraint(equalToConstant: 32).isActive = true
            bar.addArrangedSubview(mark)
        }
        [("话术", "script"), ("推文", "tweet"), ("宣发", "promo"), ("图片", "image"), ("剪贴板", "clip")].forEach { label, kind in
            bar.addArrangedSubview(button(label) { [weak self] in
                guard let self else { return }
                self.panelKind = self.panelKind == kind ? nil : kind
                self.renderPanel()
            })
        }
        return bar
    }

    private func renderKeys() {
        keys.arrangedSubviews.forEach { $0.removeFromSuperview() }
        if chinese && mode == "t9" {
            let rows: [[(String, String)]] = [
                [("分词", "'"), ("ABC", "2"), ("DEF", "3"), ("删除", "\u{8}")],
                [("GHI", "4"), ("JKL", "5"), ("MNO", "6")],
                [("PQRS", "7"), ("TUV", "8"), ("WXYZ", "9")],
                [("空格", " "), ("上屏", "\n")]
            ]
            rows.forEach { addRow($0) }
            addRow([("全拼", "mode"), ("英", "lang")])
            return
        }
        ["qwertyuiop", "asdfghjkl", "zxcvbnm"].forEach { letters in
            addRow(letters.map { (String($0), String($0)) })
        }
        addRow([
            (mode == "t9" ? "全拼" : "九键", "mode"),
            (chinese ? "英" : "中", "lang"),
            ("空格", " "),
            ("删除", "\u{8}"),
            ("换行", "\n")
        ])
    }

    private func addRow(_ pairs: [(String, String)]) {
        let row = UIStackView()
        row.axis = .horizontal
        row.distribution = .fillEqually
        pairs.forEach { label, value in
            row.addArrangedSubview(button(label) { [weak self] in self?.onKey(value) })
        }
        keys.addArrangedSubview(row)
    }

    private func onKey(_ value: String) {
        switch value {
        case "\u{8}":
            associating = false
            if !raw.isEmpty { raw.removeLast() } else { textDocumentProxy.deleteBackward() }
        case "\n":
            if let first = currentCandidates().first, !raw.isEmpty { choose(first) } else { textDocumentProxy.insertText("\n") }
        case " ":
            if let first = currentCandidates().first, !raw.isEmpty || associating { choose(first) } else { textDocumentProxy.insertText(" ") }
        case "mode":
            mode = mode == "t9" ? "qwerty" : "t9"
            raw = ""
            renderKeys()
        case "lang":
            chinese.toggle()
            raw = ""
            renderKeys()
        default:
            associating = false
            if !chinese {
                textDocumentProxy.insertText(value)
            } else if value == "'" || value.range(of: "^[a-zA-Z0-9]$", options: .regularExpression) != nil {
                raw += value.lowercased()
            } else {
                textDocumentProxy.insertText(value)
            }
        }
        refresh()
    }

    private func currentCandidates() -> [Candidate] {
        // 一次按键会在 onKey/choose/refresh 中多次调用，这里按输入状态缓存，
        // 避免重复创建 rime 会话 / 重扫词表，降低主线程每次按键的延迟。
        let key = "\(chinese)|\(mode)|\(raw)|\(associating)|\(lastCommit)|\(repo.serverVersion)"
        if let cached = candidateCacheKey, cached == key { return candidateCache }
        let result = computeCandidates()
        candidateCacheKey = key
        candidateCache = result
        return result
    }

    private func computeCandidates() -> [Candidate] {
        guard chinese else { return [] }
        if !raw.isEmpty {
            let builtin = PinyinEngine.candidates(raw: raw, mode: mode == "t9" ? "t9" : "qwerty", lexicon: repo.lexicon, items: repo.items)
            let rime = SushengRimeContext(raw).split(separator: "\n").dropFirst().map { String($0) }.filter { !$0.isEmpty }
            if rime.isEmpty { return builtin }
            let official = builtin.filter { $0.source == "official" }
            let extra = rime.map { Candidate(text: $0, commit: $0, source: "rime", kind: "lexicon", itemId: nil, assetId: nil, hint: "", score: 0) }
            return official + extra
        }
        if associating { return PinyinEngine.associate(last: lastCommit, lexicon: repo.lexicon, items: repo.items) }
        return []
    }

    private func refresh() {
        composing.text = raw.isEmpty ? " " : raw
        candidateRow.arrangedSubviews.forEach { $0.removeFromSuperview() }
        currentCandidates().prefix(12).forEach { candidate in
            candidateRow.addArrangedSubview(button(candidate.text) { [weak self] in self?.choose(candidate) })
        }
    }

    private func choose(_ candidate: Candidate) {
        if candidate.kind == "image", let assetId = candidate.assetId {
            pasteImage(repo.assetURL(assetId), itemId: candidate.itemId)
        } else {
            textDocumentProxy.insertText(candidate.commit)
            lastCommit = candidate.text
            associating = !candidate.commit.isEmpty
            if let itemId = candidate.itemId { repo.reportUsage(itemId: itemId, surface: candidate.kind) }
        }
        raw = ""
        refresh()
    }

    private func renderPanel() {
        panel.arrangedSubviews.forEach { $0.removeFromSuperview() }
        guard let kind = panelKind else {
            panel.isHidden = true
            return
        }
        panel.isHidden = false
        if kind == "clip" {
            guard hasFullAccess else {
                hint.text = "读取剪贴板需要完全访问。完全访问只用于剪贴板，按键内容不会上传。"
                return
            }
            let text = UIPasteboard.general.string ?? ""
            panel.addArrangedSubview(button(text.isEmpty ? "剪贴板是空的" : String(text.prefix(40))) { [weak self] in
                if !text.isEmpty { self?.textDocumentProxy.insertText(text) }
            })
            return
        }
        repo.items.filter { $0.type == kind && !$0.deleted }.prefix(40).forEach { item in
            panel.addArrangedSubview(button(String((item.title.isEmpty ? item.body : item.title).prefix(18))) { [weak self] in
                self?.insert(item)
            })
        }
    }

    private func insert(_ item: ContentItem) {
        if item.type == "image", let assetId = item.assetId {
            pasteImage(repo.assetURL(assetId), itemId: item.id)
        } else {
            textDocumentProxy.insertText(item.body)
            lastCommit = item.title
            associating = true
            repo.reportUsage(itemId: item.id, surface: item.type)
        }
        panelKind = nil
        renderPanel()
        refresh()
    }

    private func pasteImage(_ url: URL, itemId: String?) {
        guard hasFullAccess else {
            hint.text = "图片需要在系统设置里允许完全访问。完全访问只用于写入剪贴板，按键内容不会上传。"
            return
        }
        hint.text = "正在准备图片…"
        // 图片读取放到后台，避免在主线程同步读大文件阻塞键盘 UI 甚至触发看门狗。
        Task.detached { [weak self] in
            let data: Data? = autoreleasepool { try? Data(contentsOf: url) }
            await MainActor.run {
                guard let self else { return }
                guard let data else {
                    self.hint.text = "图片还没同步下来"
                    return
                }
                UIPasteboard.general.setData(data, forPasteboardType: "public.png")
                self.hint.text = "已复制图片，请在输入框粘贴"
                if let itemId { self.repo.reportUsage(itemId: itemId, surface: "image") }
            }
        }
    }

    private func button(_ title: String, action: @escaping () -> Void) -> UIButton {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.setTitleColor(skin.text, for: .normal)
        button.backgroundColor = skin.key
        button.titleLabel?.font = .systemFont(ofSize: 16)
        button.titleLabel?.lineBreakMode = .byTruncatingTail
        button.addAction(UIAction { _ in action() }, for: .touchUpInside)
        return button
    }
}
