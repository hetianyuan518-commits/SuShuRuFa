import Foundation

private struct MixRow {
    let w: String
    let p: String
    let f: Int
    let syls: [String]
    let ini: String
}

private struct MixIndex {
    let rows: [MixRow]
    let exactIni: [String: [MixRow]]
    let sylFreq: [String: Int]
}

private struct MixState {
    var score: Int
    var freq: Int
    var text: String
    var words: Int
    var loose: Int
    var last: String
}

private struct MixEdge {
    let to: Int
    let word: String
    let freq: Int
    let loose: Int
    let gain: Int
}

extension PinyinEngine {
    // 这些进程级静态缓存（索引、next 表、字先验、spell 选项）此前无同步。
    // 当前调用者都在主线程，属潜在竞争；统一用非递归 NSLock 守护读写。
    // 注意：indexFor 构建阶段会调用 loadPrior（内部同样加锁），故必须在释放
    // cacheLock 之后再构建，避免自死锁。
    private static let cacheLock = NSLock()
    private static var cachedIndex: MixIndex?
    private static var cachedCount = -1
    private static var cachedHead = ""
    private static var cachedTail = ""
    private static var nextWords: [String: Set<String>]?
    private static var charPrior: [String: Int]?
    private static var spellOptionsCache: [String: [String]] = [:]

    static func mixedSentences(_ input: String, _ lexicon: [LexEntry]) -> [Candidate] {
        guard input.count <= 24 else { return [] }
        let tokens = mixTokens(input)
        let tailInitial = tokens.count >= 2 && !longerThan(tokens[tokens.count - 1]).isEmpty
        if !tailInitial && pinyinCovers(input) { return [] }
        var out: [Candidate] = []
        var seen = Set<String>()
        func push(_ row: Candidate) {
            if row.text.isEmpty || seen.contains(row.text) { return }
            seen.insert(row.text)
            out.append(row)
        }
        for tokenList in mixSegmentations(input) {
            for row in mixedSentencesFor(tokenList, lexicon) { push(row) }
            guard tokenList.count >= 2 else { continue }
            let last = tokenList[tokenList.count - 1]
            let head = String(last.dropLast())
            let tail = String(last.suffix(1))
            if last.count >= 2 && syllables.contains(head) && isSyllablePrefix(tail) {
                var split = Array(tokenList.dropLast())
                split.append(head)
                split.append(tail)
                for row in mixedSentencesFor(split, lexicon) { push(row) }
            }
        }
        return out.sorted { $0.score > $1.score }
    }

    private static func mixTokens(_ input: String) -> [String] {
        if input.count > 24 { return [] }
        var tokens: [String] = []
        var i = 0
        let chars = Array(input)
        while i < chars.count {
            let max = min(6, chars.count - i)
            var best = 0
            var len = 1
            while len <= max {
                let piece = String(chars[i..<(i + len)])
                if !isSyllablePrefix(piece) { break }
                best = len
                len += 1
            }
            if best == 0 { return [] }
            tokens.append(String(chars[i..<(i + best)]))
            i += best
        }
        return tokens
    }

    private static func mixSegmentations(_ input: String) -> [[String]] {
        let base = mixTokens(input)
        if base.isEmpty { return [] }
        if base.allSatisfy({ syllables.contains($0) }) { return [base] }
        var found = [base]
        var seen = Set([base.joined(separator: "\u{0}")])
        var i = 0
        while i < base.count && found.count < 4 {
            let tok = base[i]
            let head = String(tok.dropLast())
            if tok.count >= 3 && syllables.contains(tok) && syllables.contains(head) {
                let restInput = String(tok.suffix(1)) + base[(i + 1)...].joined()
                let rest = mixTokens(restInput)
                if let first = rest.first, first.count >= 2 {
                    var alt = Array(base[..<i])
                    alt.append(head)
                    alt.append(contentsOf: rest)
                    if alt.count <= 16 {
                        let key = alt.joined(separator: "\u{0}")
                        if seen.insert(key).inserted { found.append(alt) }
                    }
                }
            }
            i += 1
        }
        return found
    }

    private static func longerThan(_ tail: String) -> [String] {
        if tail.isEmpty || tail.count > 5 { return [] }
        return syllables.filter { $0.count > tail.count && $0.hasPrefix(tail) }
    }

    private static func followsOf(_ word: String) -> Set<String>? {
        let table = loadNext()
        if let direct = table[word], !direct.isEmpty { return direct }
        let chars = Array(word)
        if chars.count > 1 { return table[String(chars[chars.count - 1])] }
        return nil
    }

    private static func mixedSentencesFor(_ tokens: [String], _ lexicon: [LexEntry]) -> [Candidate] {
        if tokens.count < 2 || tokens.count > 16 || tokens.allSatisfy({ $0.count == 1 }) { return [] }
        let glued = tokens.filter { $0.count > 1 }
        if glued.allSatisfy({ !syllables.contains($0) }) { return [] }
        let index = indexFor(lexicon)
        let n = tokens.count
        var bestInitial: [Character: Int] = [:]
        func initialBest(_ letter: Character) -> Int {
            if let got = bestInitial[letter] { return got }
            var best = 0
            for (syl, weight) in index.sylFreq where syl.first == letter && weight > best { best = weight }
            bestInitial[letter] = best
            return best
        }
        var edges = Array(repeating: [MixEdge](), count: n)
        var budget = 12000
        for i in 0..<n {
            var acc: [String] = []
            var j = i
            while j < n && acc.count < 8 {
                acc.append(tokens[j])
                var hits = matchMixed(index.rows, acc, &budget, index.sylFreq)
                let key = acc.map { String($0.prefix(1)) }.joined()
                for row in index.exactIni[key] ?? [] {
                    if row.syls.count == acc.count && zip(row.syls, acc).allSatisfy({ $0.hasPrefix($1) }) {
                        hits.append(row)
                    }
                }
                var seenRow = Set<String>()
                for row in hits {
                    let id = row.w + "\n" + row.p
                    if !seenRow.insert(id).inserted { continue }
                    var loose = 0
                    var abbrev = 0
                    for s in 0..<row.syls.count {
                        if acc[s].count >= 2 {
                            loose += max(0, row.syls[s].count - acc[s].count)
                        } else if acc[s].count == 1, let letter = acc[s].first {
                            if (index.sylFreq[row.syls[s]] ?? 0) + 40 < initialBest(letter) { abbrev += 40 }
                        }
                    }
                    var gain = edgeGain(row.w, row.f, row.syls.count, loose) - abbrev
                    if row.syls.count == 1 && acc[0].count == 1 { gain -= 200 }
                    edges[i].append(MixEdge(to: j + 1, word: row.w, freq: row.f, loose: loose, gain: gain))
                }
                j += 1
            }
        }
        var beam = Array(repeating: [MixState](), count: n + 1)
        beam[0] = [MixState(score: 0, freq: 0, text: "", words: 0, loose: 0, last: "")]
        for i in 0..<n {
            if beam[i].count > 8 {
                beam[i].sort { $0.score > $1.score }
                beam[i] = Array(beam[i].prefix(8))
            }
            for state in beam[i] {
                let follow = followsOf(state.last)
                for edge in edges[i] {
                    let bonus = follow?.contains(edge.word) == true ? 300 : 0
                    beam[edge.to].append(MixState(
                        score: state.score + edge.gain + bonus,
                        freq: state.freq + edge.freq,
                        text: state.text + edge.word,
                        words: state.words + 1,
                        loose: state.loose + edge.loose,
                        last: edge.word
                    ))
                }
            }
        }
        let full = beam[n].sorted { $0.score > $1.score }
        var scored: [String: Int] = [:]
        var order: [String] = []
        var multiRank = 0
        for state in full where !state.text.isEmpty {
            let score: Int
            if state.words == 1 {
                score = 4600 + min(state.freq, 489) - state.loose * 12
            } else if scored[state.text] != nil {
                continue
            } else {
                score = 3700 - multiRank * 5
                multiRank += 1
            }
            if let prev = scored[state.text], prev >= score { continue }
            if scored[state.text] == nil { order.append(state.text) }
            scored[state.text] = score
        }
        var out = order.map { text in
            Candidate(text: text, commit: text, source: "lexicon", kind: "lexicon", itemId: nil, assetId: nil, hint: "", score: scored[text] ?? 0)
        }
        out.sort { $0.score > $1.score }
        if out.count > 8 { out = Array(out.prefix(8)) }
        var seenText = Set(out.map(\.text))
        var prefixRank = 0
        var i = n - 1
        while i >= 1 && prefixRank < 3 {
            if let best = beam[i].max(by: { $0.score < $1.score }), best.text.count >= 2, !seenText.contains(best.text) {
                seenText.insert(best.text)
                out.append(Candidate(text: best.text, commit: best.text, source: "lexicon", kind: "lexicon", itemId: nil, assetId: nil, hint: "", score: 3000 - prefixRank * 5))
                prefixRank += 1
            }
            i -= 1
        }
        return out
    }

    private static func edgeGain(_ word: String, _ freq: Int, _ syllables: Int, _ loose: Int) -> Int {
        let base: Int
        if syllables >= 2 {
            base = freq + 560 * (syllables - 1)
        } else {
            base = loadPrior()[word] ?? freq
        }
        return base - loose * 40
    }

    private static func rowWeight(_ row: MixRow) -> Int {
        if row.syls.count == 1 && row.w.count == 1 { return loadPrior()[row.w] ?? row.f }
        return row.f
    }

    private static func matchMixed(_ rows: [MixRow], _ spells: [String], _ budget: inout Int, _ sylFreq: [String: Int]) -> [MixRow] {
        let options = spells.map { mixSpellOptions($0, sylFreq) }
        var path: [String] = []
        var bag: [MixRow] = []
        var seen = Set<String>()
        func rec(_ pos: Int) {
            if budget <= 0 { return }
            if pos == spells.count {
                for row in topExact(rows, path, 12) {
                    let id = row.w + "\n" + row.p
                    if seen.insert(id).inserted { bag.append(row) }
                }
                return
            }
            for syl in options[pos] {
                path.append(syl)
                budget -= 1
                if hasSylPrefix(rows, path) { rec(pos + 1) }
                path.removeLast()
                if budget <= 0 { return }
            }
        }
        rec(0)
        bag.sort { rowWeight($0) > rowWeight($1) }
        return Array(bag.prefix(12))
    }

    private static func mixSpellOptions(_ spell: String, _ weight: [String: Int]) -> [String] {
        // 结果只取决于 spell 与当前索引的 sylFreq；索引重建时在 indexFor 里整体清空缓存。
        cacheLock.lock()
        if let hit = spellOptionsCache[spell] {
            cacheLock.unlock()
            return hit
        }
        cacheLock.unlock()
        let result = computeSpellOptions(spell, weight)
        cacheLock.lock()
        spellOptionsCache[spell] = result
        cacheLock.unlock()
        return result
    }

    private static func computeSpellOptions(_ spell: String, _ weight: [String: Int]) -> [String] {
        let ranked = syllables.filter { $0.hasPrefix(spell) }.sorted { lhs, rhs in
            let lw = weight[lhs] ?? 0
            let rw = weight[rhs] ?? 0
            if lw != rw { return lw > rw }
            if lhs.count != rhs.count { return lhs.count < rhs.count }
            return lhs < rhs
        }
        var picked: [String] = []
        var seen = Set<String>()
        if syllables.contains(spell) {
            picked.append(spell)
            seen.insert(spell)
        }
        for syl in ranked where seen.insert(syl).inserted {
            picked.append(syl)
            if picked.count >= 16 { break }
        }
        return picked
    }

    private static func hasSylPrefix(_ rows: [MixRow], _ prefix: [String]) -> Bool {
        let index = lowerRows(rows, prefix)
        return index < rows.count && startsSyl(rows[index].syls, prefix)
    }

    private static func topExact(_ rows: [MixRow], _ prefix: [String], _ k: Int) -> [MixRow] {
        var index = lowerRows(rows, prefix)
        var bag: [MixRow] = []
        while index < rows.count && bag.count < 48 {
            let row = rows[index]
            index += 1
            if row.syls.count != prefix.count || !startsSyl(row.syls, prefix) { break }
            bag.append(row)
        }
        bag.sort { rowWeight($0) > rowWeight($1) }
        return Array(bag.prefix(k))
    }

    private static func startsSyl(_ syls: [String], _ prefix: [String]) -> Bool {
        if syls.count < prefix.count { return false }
        for i in prefix.indices where syls[i] != prefix[i] { return false }
        return true
    }

    private static func lowerRows(_ rows: [MixRow], _ prefix: [String]) -> Int {
        var lo = 0
        var hi = rows.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if sylsBefore(rows[mid].syls, prefix) { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    private static func sylsBefore(_ syls: [String], _ prefix: [String]) -> Bool {
        let n = min(syls.count, prefix.count)
        for i in 0..<n {
            if syls[i] < prefix[i] { return true }
            if syls[i] > prefix[i] { return false }
        }
        return syls.count < prefix.count
    }

    private static func indexFor(_ lexicon: [LexEntry]) -> MixIndex {
        let head = lexicon.first?.w ?? ""
        let tail = lexicon.last?.w ?? ""
        cacheLock.lock()
        if let cachedIndex, cachedCount == lexicon.count, cachedHead == head, cachedTail == tail {
            cacheLock.unlock()
            return cachedIndex
        }
        cacheLock.unlock()
        var prepared: [MixRow] = []
        prepared.reserveCapacity(lexicon.count)
        for entry in lexicon {
            guard let syls = syllablesOf(entry.p), !syls.isEmpty, syls.count <= 16 else { continue }
            prepared.append(MixRow(w: entry.w, p: entry.p, f: entry.f, syls: syls, ini: syls.map { String($0.prefix(1)) }.joined()))
        }
        let ranked = prepared.sorted { $0.f > $1.f }
        prepared.sort { lhs, rhs in
            let n = min(lhs.syls.count, rhs.syls.count)
            for i in 0..<n {
                if lhs.syls[i] != rhs.syls[i] { return lhs.syls[i] < rhs.syls[i] }
            }
            return lhs.syls.count < rhs.syls.count
        }
        var sylFreq: [String: Int] = [:]
        let prior = loadPrior()
        for row in prepared where row.syls.count == 1 && row.w.count == 1 {
            let weight = prior[row.w] ?? row.f
            if weight > (sylFreq[row.syls[0]] ?? 0) { sylFreq[row.syls[0]] = weight }
        }
        var exact: [String: [MixRow]] = [:]
        for row in ranked where row.ini.count >= 1 && row.ini.count <= 16 {
            var list = exact[row.ini] ?? []
            if list.count < 12 {
                list.append(row)
                exact[row.ini] = list
            }
        }
        let built = MixIndex(rows: prepared, exactIni: exact, sylFreq: sylFreq)
        cacheLock.lock()
        cachedIndex = built
        cachedCount = lexicon.count
        cachedHead = head
        cachedTail = tail
        // spell 选项 memo 绑定当前索引的 sylFreq，换索引即失效。
        spellOptionsCache.removeAll()
        cacheLock.unlock()
        return built
    }

    private static func loadNext() -> [String: Set<String>] {
        cacheLock.lock()
        if let cached = nextWords {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()
        var table: [String: Set<String>] = [:]
        var loaded = false
        if let url = Bundle.main.url(forResource: "ime-rules", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let next = root["next"] as? [String: [String]] {
            for (word, list) in next { table[word] = Set(list) }
            loaded = true
        }
        // 仅在资源确实加载成功时写缓存；失败返回空表但不缓存，允许后续调用重试。
        guard loaded else { return table }
        cacheLock.lock()
        nextWords = table
        cacheLock.unlock()
        return table
    }

    private static func loadPrior() -> [String: Int] {
        cacheLock.lock()
        if let cached = charPrior {
            cacheLock.unlock()
            return cached
        }
        cacheLock.unlock()
        var table: [String: Int] = [:]
        var loaded = false
        if let url = Bundle.main.url(forResource: "char-prior", withExtension: "json"),
           let data = try? Data(contentsOf: url),
           let root = try? JSONSerialization.jsonObject(with: data) as? [String: Int] {
            table = root
            loaded = true
        }
        guard loaded else { return table }
        cacheLock.lock()
        charPrior = table
        cacheLock.unlock()
        return table
    }
}
