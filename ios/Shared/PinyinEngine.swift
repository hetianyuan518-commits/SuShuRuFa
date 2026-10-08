import Foundation

struct LexEntry: Codable {
    let w: String
    let p: String
    let f: Int
}

struct ContentItem: Codable {
    let id: String
    let type: String
    let title: String
    let body: String
    let aliases: [String]
    let pinyin: String
    let shortcut: String
    let groupId: String
    let groupName: String
    let assetId: String?
    let mime: String?
    let validFrom: Int64?
    let validTo: Int64?
    let status: String
    let version: Int
    let deleted: Bool
}

struct Candidate {
    let text: String
    let commit: String
    let source: String
    let kind: String
    let itemId: String?
    let assetId: String?
    let hint: String
    let score: Int
}

private struct Seg {
    let syls: [String]
    let partial: String
}

enum PinyinEngine {
    static let syllables = Set(syllableText.split { $0.isWhitespace }.map(String.init))
    private static let t9Map: [Character: Character] = [
        "a": "2", "b": "2", "c": "2", "d": "3", "e": "3", "f": "3",
        "g": "4", "h": "4", "i": "4", "j": "5", "k": "5", "l": "5",
        "m": "6", "n": "6", "o": "6", "p": "7", "q": "7", "r": "7", "s": "7",
        "t": "8", "u": "8", "v": "8", "w": "9", "x": "9", "y": "9", "z": "9"
    ]

    static func candidates(raw: String, mode: String = "qwerty", lexicon: [LexEntry], items: [ContentItem], now: Int64 = Int64(Date().timeIntervalSince1970 * 1000), limit: Int = 20) -> [Candidate] {
        let input = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        if input.isEmpty { return [] }
        let pool = active(items, now)
        var rows: [Candidate] = []
        if input.hasPrefix("v") {
            for item in pool {
                let shortcut = item.shortcut.lowercased()
                if !shortcut.isEmpty && (shortcut == input || shortcut.hasPrefix(input)) {
                    rows.append(official(item, 2_000_000 - shortcut.count))
                }
            }
            return Array(rows.sorted { $0.score > $1.score }.prefix(limit))
        }
        for item in pool {
            let typed = scorePinyin(input, mode, item.pinyin)
            if typed > 0 { rows.append(official(item, typed + 1_000_000)) }
            let names = [item.title] + item.aliases
            if names.contains(where: { !$0.isEmpty && ($0 == input || $0.hasPrefix(input)) }) {
                rows.append(official(item, 1_500_000))
            }
        }
        for entry in lexicon {
            let typed = scorePinyin(input, mode, entry.p)
            if typed <= 0 { continue }
            rows.append(Candidate(text: entry.w, commit: entry.w, source: "lexicon", kind: "lexicon", itemId: nil, assetId: nil, hint: entry.p, score: typed + entry.f))
        }
        if mode != "t9" {
            let letters = String(input.filter { $0.isLetter && $0.isASCII })
            let marked = input.contains("'")
            var singleInitials = marked && letters.count >= 2
            if marked {
                for part in input.split(separator: "'", omittingEmptySubsequences: false) {
                    let cleaned = part.filter { $0.isLetter && $0.isASCII }
                    if cleaned.count > 1 { singleInitials = false }
                }
            }
            if letters.count >= 2 && (!marked || singleInitials) {
                rows.append(contentsOf: mixedSentences(letters, lexicon))
            }
        }
        let sorted = rows.sorted { lhs, rhs in
            if lhs.score != rhs.score { return lhs.score > rhs.score }
            return lhs.text.count < rhs.text.count
        }
        return Array(dedupe(sorted).prefix(limit))
    }

    static func associate(last: String, lexicon: [LexEntry], items: [ContentItem], now: Int64 = Int64(Date().timeIntervalSince1970 * 1000), limit: Int = 8) -> [Candidate] {
        let word = last.trimmingCharacters(in: .whitespacesAndNewlines)
        if word.isEmpty { return [] }
        var rows: [Candidate] = []
        for item in active(items, now) {
            if item.type == "image" { continue }
            if ([item.title] + item.aliases).contains(word) { rows.append(official(item, 1_000_000)) }
        }
        for entry in lexicon where entry.w.hasPrefix(word) && entry.w != word {
            let suffix = String(entry.w.dropFirst(word.count))
            rows.append(Candidate(text: entry.w, commit: suffix, source: "lexicon", kind: "lexicon", itemId: nil, assetId: nil, hint: entry.p, score: entry.f))
        }
        return Array(dedupe(rows.sorted { $0.score > $1.score }).prefix(limit))
    }

    private static func active(_ items: [ContentItem], _ now: Int64) -> [ContentItem] {
        items.filter { item in
            !item.deleted && item.status == "published" &&
            (item.validFrom == nil || now >= item.validFrom!) &&
            (item.validTo == nil || now <= item.validTo!)
        }
    }

    private static func official(_ item: ContentItem, _ score: Int) -> Candidate {
        let image = item.type == "image"
        let text = image ? item.title : (item.type == "lexicon" ? item.body : item.title)
        let hint = item.shortcut.isEmpty ? (item.pinyin.isEmpty ? item.groupName : item.pinyin) : item.shortcut
        return Candidate(text: text, commit: image ? "" : item.body, source: "official", kind: item.type, itemId: item.id, assetId: item.assetId, hint: hint, score: score)
    }

    private static func dedupe(_ list: [Candidate]) -> [Candidate] {
        var seen = Set<String>()
        var out: [Candidate] = []
        for row in list {
            let key = row.itemId.map { "id:\($0)" } ?? "t:\(row.commit)"
            if seen.insert(key).inserted { out.append(row) }
        }
        return out
    }

    private static func compact(_ pinyin: String) -> String {
        pinyin.lowercased().filter { $0.isLetter && $0.isASCII }
    }

    private static func toT9(_ compact: String) -> String {
        String(compact.map { t9Map[$0] ?? "?" }.filter { $0 != "?" })
    }

    static func isSyllablePrefix(_ text: String) -> Bool {
        !text.isEmpty && syllables.contains { $0.hasPrefix(text) }
    }

    private static func segmentAllowPartial(_ input: String) -> Seg? {
        var memo: [Int: Seg] = [:]
        var missing = Set<Int>()
        func rec(_ i: Int) -> Seg? {
            if missing.contains(i) { return nil }
            if let hit = memo[i] { return hit }
            if i == input.count {
                let done = Seg(syls: [], partial: "")
                memo[i] = done
                return done
            }
            let max = min(6, input.count - i)
            if max >= 1 {
                for len in stride(from: max, through: 1, by: -1) {
                    let piece = input.slice(i, i + len)
                    if !syllables.contains(piece) { continue }
                    guard let rest = rec(i + len) else { continue }
                    let hit = Seg(syls: [piece] + rest.syls, partial: rest.partial)
                    memo[i] = hit
                    return hit
                }
            }
            let tail = input.slice(i, input.count)
            if tail.count <= 6 && isSyllablePrefix(tail) && !syllables.contains(tail) {
                let partial = Seg(syls: [], partial: tail)
                memo[i] = partial
                return partial
            }
            missing.insert(i)
            return nil
        }
        return rec(0)
    }

    static func pinyinCovers(_ input: String) -> Bool {
        guard let got = segmentAllowPartial(input), got.partial.isEmpty else { return false }
        return got.syls.joined() == input
    }

    private static func segmentFull(_ compact: String) -> [String]? {
        guard let got = segmentAllowPartial(compact), got.partial.isEmpty else { return nil }
        return got.syls
    }

    static func syllablesOf(_ pinyin: String) -> [String]? {
        let parts = pinyin.lowercased().split { $0.isWhitespace }.map { String($0).filter { $0.isLetter && $0.isASCII } }.filter { !$0.isEmpty }
        if parts.isEmpty { return nil }
        if parts.count > 1 { return parts.allSatisfy { syllables.contains($0) } ? parts : nil }
        return segmentFull(parts[0])
    }

    private static func alignedTo(_ input: String, _ syls: [String]?) -> Bool {
        guard let syls = syls, let got = segmentAllowPartial(input), got.syls.count <= syls.count else { return false }
        for index in got.syls.indices where syls[index] != got.syls[index] { return false }
        if got.partial.isEmpty { return true }
        guard got.syls.count < syls.count else { return false }
        return syls[got.syls.count].hasPrefix(got.partial)
    }

    private static func alignedParts(_ parts: [String], _ syls: [String]?) -> Bool {
        guard let syls = syls, parts.count <= syls.count, !parts.isEmpty else { return false }
        var any = false
        for index in parts.indices {
            let part = parts[index]
            if part.isEmpty { continue }
            any = true
            if !syls[index].hasPrefix(part) { return false }
        }
        return any
    }

    private static func qwertyScore(_ raw: String, _ pinyin: String) -> Int {
        let folded = compact(pinyin)
        guard !folded.isEmpty, let syls = syllablesOf(pinyin) else { return 0 }
        if raw.contains("'") {
            let parts = raw.split(separator: "'", omittingEmptySubsequences: false).map { String($0).filter { $0.isLetter && $0.isASCII } }
            if !alignedParts(parts, syls) { return 0 }
            let joined = parts.joined()
            return folded == joined ? 5000 : 4000 - (folded.count - joined.count)
        }
        let input = String(raw.filter { $0.isLetter && $0.isASCII })
        if input.isEmpty { return 0 }
        if folded == input { return 5000 }
        if alignedTo(input, syls) { return 4000 - (folded.count - input.count) }
        let initials = syls.map { String($0.prefix(1)) }.joined()
        if initials == input && input != folded { return 2500 }
        if input.count >= 2 && initials.hasPrefix(input) && input != folded { return 1500 - (initials.count - input.count) }
        return 0
    }

    private static func t9Score(_ raw: String, _ pinyin: String) -> Int {
        let input = String(raw.filter { ("2"..."9").contains(String($0)) })
        let comparable = raw.replacingOccurrences(of: "'", with: "")
        if input.isEmpty || input != comparable { return 0 }
        let digits = toT9(compact(pinyin))
        if !digits.hasPrefix(input) { return 0 }
        return 3000 - (digits.count - input.count) + (digits == input ? 500 : 0)
    }

    private static func scorePinyin(_ raw: String, _ mode: String, _ pinyin: String) -> Int {
        mode == "t9" ? t9Score(raw, pinyin) : qwertyScore(raw.lowercased(), pinyin)
    }
}

private extension String {
    func slice(_ start: Int, _ end: Int) -> String {
        let from = index(startIndex, offsetBy: start)
        let to = index(startIndex, offsetBy: end)
        return String(self[from..<to])
    }
}

private let syllableText = """
a ai an ang ao ba bai ban bang bao bei ben beng bi bian biao bie bin bing bo bu
ca cai can cang cao ce cen ceng cha chai chan chang chao che chen cheng chi chong chou chu chua chuai chuan chuang chui chun chuo ci cong cou cu cuan cui cun cuo
da dai dan dang dao de dei den deng di dia dian diao die ding diu dong dou du duan dui dun duo
e ei en eng er
fa fan fang fei fen feng fo fou fu
ga gai gan gang gao ge gei gen geng gong gou gu gua guai guan guang gui gun guo
ha hai han hang hao he hei hen heng hong hou hu hua huai huan huang hui hun huo
ji jia jian jiang jiao jie jin jing jiong jiu ju juan jue jun
ka kai kan kang kao ke ken keng kong kou ku kua kuai kuan kuang kui kun kuo
la lai lan lang lao le lei leng li lia lian liang liao lie lin ling liu lo long lou lu lv lve luan lun luo
ma mai man mang mao me mei men meng mi mian miao mie min ming miu mo mou mu
na nai nan nang nao ne nei nen neng ni nian niang niao nie nin ning niu nong nou nu nv nve nuan nuo
o ou
pa pai pan pang pao pei pen peng pi pian piao pie pin ping po pou pu
qi qia qian qiang qiao qie qin qing qiong qiu qu quan que qun
ran rang rao re ren reng ri rong rou ru rua ruan rui run ruo
sa sai san sang sao se sen seng sha shai shan shang shao she shei shen sheng shi shou shu shua shuai shuan shuang shui shun shuo si song sou su suan sui sun suo
ta tai tan tang tao te teng ti tian tiao tie ting tong tou tu tuan tui tun tuo
wa wai wan wang wei wen weng wo wu
xi xia xian xiang xiao xie xin xing xiong xiu xu xuan xue xun
ya yan yang yao ye yi yin ying yo yong you yu yuan yue yun
za zai zan zang zao ze zei zen zeng zha zhai zhan zhang zhao zhe zhei zhen zheng zhi zhong zhou zhu zhua zhuai zhuan zhuang zhui zhun zhuo zi zong zou zu zuan zui zun zuo
"""
