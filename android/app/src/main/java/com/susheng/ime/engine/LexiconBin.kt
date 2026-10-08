package com.susheng.ime.engine

import java.nio.charset.Charset

/**
 * 压缩词表。条目按音节编号排序，简拼、九键和首字联想走预建桶，避免每次敲键扫完整库。
 */
class LexiconBin(private val data: ByteArray, private val charPrior: Map<String, Int> = emptyMap()) {
    private val syllables: Array<String>
    private val sylOf: Map<String, Int>
    private val offsets: IntArray
    private val initials: Map<String, IntArray>
    private val digits: Map<String, IntArray>
    private val heads: Map<String, IntArray>
    private var exactInitials: Map<String, IntArray>? = null

    init {
        require(data.size >= 16 && String(data, 0, 4, ASCII) == "SXL1") { "词表格式不对" }
        val count = u32(4)
        val sylCount = u32(8)
        val listsAt = u32(12)
        var cursor = 16
        syllables = Array(sylCount) {
            val len = data[cursor].toInt() and 0xff
            cursor += 1
            val text = String(data, cursor, len, ASCII)
            cursor += len
            text
        }
        sylOf = syllables.withIndex().associate { it.value to it.index }
        while (cursor % 4 != 0) cursor++
        offsets = IntArray(count) {
            val value = u32(cursor)
            cursor += 4
            value
        }
        cursor = listsAt
        val listCount = u32(cursor)
        cursor += 4
        val ini = HashMap<String, IntArray>()
        val t9 = HashMap<String, IntArray>()
        val head = HashMap<String, IntArray>()
        repeat(listCount) {
            val kind = data[cursor].toInt() and 0xff
            val keyLen = data[cursor + 1].toInt() and 0xff
            cursor += 2
            val key = String(data, cursor, keyLen, UTF8)
            cursor += keyLen
            val n = u16(cursor)
            cursor += 2
            val items = IntArray(n) {
                val value = u32(cursor)
                cursor += 4
                value
            }
            when (kind) {
                1 -> ini[key] = items
                2 -> t9[key] = items
                else -> head[key] = items
            }
        }
        initials = ini
        digits = t9
        heads = head
    }

    fun shortlist(raw: String, mode: String): List<LexEntry> {
        val input = raw.trim().lowercase()
        if (input.isEmpty()) return emptyList()
        val ids = ArrayList<Int>()
        val seen = HashSet<Int>()
        if (mode == "t9") {
            val numbers = input.filter { it in '2'..'9' }
            if (numbers.isEmpty()) return emptyList()
            if (numbers.length == 1) {
                val digit = numbers[0]
                for ((key, bucket) in digits) {
                    if (key.length == 2 && key[0] == digit) addBucket(bucket, ids, seen)
                }
                return materialize(ids)
            }
            val key = if (numbers.length <= 5) numbers else numbers.substring(0, 5)
            addBucket(digits[key], ids, seen)
            return materialize(ids)
        }
        if ('\'' in input) {
            val parts = input.split("'").map { it.filter { ch -> ch in 'a'..'z' } }
            val letters = parts.joinToString("")
            if (letters.length >= 2 && parts.all { it.length <= 1 }) {
                val key = if (letters.length <= 4) letters else letters.substring(0, 4)
                addBucket(initials[key], ids, seen)
                return materialize(ids)
            }
            collectApostrophe(parts, ids, seen)
            return materialize(ids)
        }
        val letters = input.filter { it in 'a'..'z' }
        if (letters.length >= 2) {
            val key = if (letters.length <= 4) letters else letters.substring(0, 4)
            addBucket(initials[key], ids, seen)
        }
        for (seg in PinyinEngine.segmentAll(letters)) collectSegment(seg, ids, seen)
        return materialize(ids)
    }

    fun associate(word: String): List<LexEntry> {
        if (word.isEmpty()) return emptyList()
        val ids = heads[word.substring(0, 1)] ?: return emptyList()
        return ids.map { entryAt(it) }.filter { it.word.startsWith(word) && it.word != word }
    }

    private fun collectSegment(seg: Seg, into: MutableList<Int>, seen: HashSet<Int>) {
        if (seg.partial.isNotEmpty()) {
            for (syl in PinyinEngine.syllablesStarting(seg.partial)) {
                val prefix = (seg.syls + syl).map { sylOf[it] ?: return@map -1 }
                if (prefix.any { it < 0 }) continue
                takeSpan(lower(prefix), upper(prefix), prefix.size, into, seen)
            }
            return
        }
        if (seg.syls.isEmpty()) return
        val prefix = seg.syls.map { sylOf[it] ?: return }
        takeSpan(lower(prefix), upper(prefix), prefix.size, into, seen)
    }

    private fun collectApostrophe(parts: List<String>, into: MutableList<Int>, seen: HashSet<Int>) {
        val needed = parts.count { it.isNotEmpty() }
        val first = parts.firstOrNull().orEmpty()
        for (syl in PinyinEngine.syllablesStarting(first)) {
            val id = sylOf[syl] ?: continue
            val lo = lower(listOf(id))
            val hi = upper(listOf(id))
            val matched = ArrayList<Int>()
            for (index in lo until hi) {
                if (parts.size > sylCount(index)) continue
                var ok = true
                for (i in parts.indices) {
                    val part = parts[i]
                    if (part.isEmpty()) continue
                    val got = syllableAt(index, i)
                    if (got == null || !got.startsWith(part)) {
                        ok = false
                        break
                    }
                }
                if (ok) matched.add(index)
            }
            val exact = ArrayList<Int>()
            val rest = ArrayList<Int>()
            for (index in matched) {
                if (!seen.add(index)) continue
                seen.remove(index)
                if (sylCount(index) == needed) exact.add(index) else rest.add(index)
            }
            for (index in exact.take(500)) if (seen.add(index)) into.add(index)
            val ranked = if (rest.size > 200) rest.sortedByDescending { freq(it) } else rest
            for (index in ranked.take(200)) if (seen.add(index)) into.add(index)
        }
    }

    private fun takeSpan(lo: Int, hi: Int, exactLen: Int, into: MutableList<Int>, seen: HashSet<Int>) {
        var index = lo
        var exactCount = 0
        while (index < hi && exactCount < 80) {
            if (sylCount(index) != exactLen) break
            if (seen.add(index)) into.add(index)
            exactCount++
            index++
        }
        val best = IntArray(36)
        val scores = IntArray(36)
        var kept = 0
        var walked = 0
        while (index < hi && walked < 700) {
            val weight = freq(index)
            if (kept < best.size) {
                best[kept] = index
                scores[kept] = weight
                kept++
            } else {
                var worst = 0
                for (slot in 1 until kept) if (scores[slot] < scores[worst]) worst = slot
                if (weight > scores[worst]) {
                    best[worst] = index
                    scores[worst] = weight
                }
            }
            index++
            walked++
        }
        for (slot in 0 until kept) if (seen.add(best[slot])) into.add(best[slot])
    }

    fun warm() {
        exactInitials()
        charFreq()
    }

    private fun addBucket(bucket: IntArray?, into: MutableList<Int>, seen: HashSet<Int>) {
        if (bucket == null) return
        for (index in bucket) if (seen.add(index)) into.add(index)
    }

    private fun lower(prefix: List<Int>): Int {
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, prefix) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun upper(prefix: List<Int>): Int {
        var lo = 0
        var hi = offsets.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (compare(mid, prefix) < 0 || starts(mid, prefix)) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun compare(index: Int, prefix: List<Int>): Int {
        val nSyl = sylCount(index)
        val n = minOf(nSyl, prefix.size)
        for (i in 0 until n) {
            val id = sylId(index, i)
            if (id < prefix[i]) return -1
            if (id > prefix[i]) return 1
        }
        return if (nSyl < prefix.size) -1 else 0
    }

    private fun starts(index: Int, prefix: List<Int>): Boolean {
        if (sylCount(index) < prefix.size) return false
        for (i in prefix.indices) if (sylId(index, i) != prefix[i]) return false
        return true
    }

    private fun materialize(ids: List<Int>) = ids.map { entryAt(it) }

    private fun entryAt(index: Int): LexEntry {
        val n = sylCount(index)
        val syls = Array(n) { syllableAt(index, it).orEmpty() }
        return LexEntry(wordAt(index), syls.joinToString(" "), freq(index))
    }

    private fun syllableAt(index: Int, pos: Int): String? {
        if (pos >= sylCount(index)) return null
        val id = sylId(index, pos)
        return syllables.getOrNull(id)
    }

    private fun sylCount(index: Int) = data[offsets[index]].toInt() and 0xff

    private fun sylId(index: Int, pos: Int): Int {
        val base = offsets[index] + 1 + pos * 2
        return u16(base)
    }

    private fun freq(index: Int): Int {
        val base = offsets[index] + 1 + sylCount(index) * 2
        return u16(base)
    }

    private fun wordAt(index: Int): String {
        val n = sylCount(index)
        var cursor = offsets[index] + 1 + n * 2 + 2
        val len = data[cursor].toInt() and 0xff
        cursor += 1
        return String(data, cursor, len, UTF8)
    }

    private fun u16(offset: Int) = (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(offset: Int): Int {
        return (data[offset].toInt() and 0xff) or
            ((data[offset + 1].toInt() and 0xff) shl 8) or
            ((data[offset + 2].toInt() and 0xff) shl 16) or
            ((data[offset + 3].toInt() and 0xff) shl 24)
    }

    fun sentences(raw: String): List<Candidate> {
        val lowered = raw.trim().lowercase()
        val parts = if ('\'' in lowered) lowered.split("'").map { it.filter { ch -> ch in 'a'..'z' } } else null
        val letters = if (parts != null) parts.joinToString("") else lowered.filter { it in 'a'..'z' }
        if (letters.length < 2) return emptyList()
        if (parts != null && parts.any { it.length > 1 }) return emptyList()
        val out = ArrayList<Candidate>()
        if (parts == null && letters.length <= 96) out.addAll(pinyinSentences(letters))
        // xiexien 能被 xie xi en 盖满，但用户多半是「谢谢 + n」；niha 也多半是「你好」没打完。
        // 末尾音节还能往下长时也走混拼，让整词有机会压过「你哈」这种凑出来的说法。
        val tokens = PinyinEngine.mixTokens(letters)
        val tailInitial = tokens != null && tokens.size >= 2 && PinyinEngine.longerSyllables(tokens.last()).isNotEmpty()
        if ((tailInitial || !pinyinCovers(letters)) && letters.length <= 24) {
            val mixed = mixedSentences(letters)
            out.addAll(if (mixed.isNotEmpty()) mixed else initialSentences(letters))
        }
        return out
    }

    private fun pinyinCovers(letters: String) =
        PinyinEngine.segmentAll(letters, 1).any { seg -> seg.partial.isEmpty() && seg.syls.joinToString("") == letters }

    private fun pinyinSentences(letters: String): List<Candidate> {
        val best = LinkedHashMap<String, Candidate>()
        for (seg in PinyinEngine.segmentAll(letters, 4)) {
            val ids = ArrayList<Int>(seg.syls.size)
            var known = true
            for (syl in seg.syls) {
                val id = sylOf[syl]
                if (id == null) {
                    known = false
                    break
                }
                ids.add(id)
            }
            if (!known) continue
            for (hit in decode(ids, seg.syls, seg.partial, letters.length)) {
                val prev = best[hit.commit]
                if (prev == null || hit.score > prev.score) best[hit.commit] = hit
            }
        }
        return best.values.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.text.length }).take(8)
    }

    /** niyaoqun 贪心切出来是 ni yao qun，但用户多半是「你要去 + n」：末尾音节能拆成「音节 + 首字母」时，两种切法都算。 */
    private fun mixedSentences(input: String): List<Candidate> {
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        for (tokens in PinyinEngine.mixSegmentations(input)) {
            for (row in mixedSentencesFor(tokens)) if (seen.add(row.text)) out.add(row)
            if (tokens.size < 2) continue
            val last = tokens.last()
            val head = last.dropLast(1)
            val tail = last.takeLast(1)
            if (last.length >= 2 && PinyinEngine.isSyllable(head) && PinyinEngine.longerSyllables(tail).isNotEmpty()) {
                val split = tokens.dropLast(1) + head + tail
                for (row in mixedSentencesFor(split)) if (seen.add(row.text)) out.add(row)
            }
        }
        out.sortByDescending { it.score }
        return out
    }

    private fun mixedSentencesFor(tokens: List<String>): List<Candidate> {
        if (tokens.size < 2 || tokens.size > 16 || tokens.all { it.length == 1 }) return emptyList()
        if (tokens.filter { it.length > 1 }.all { !PinyinEngine.isSyllable(it) }) return emptyList()
        val n = tokens.size
        val edges = Array(n) { ArrayList<WordEdge>() }
        val budget = intArrayOf(12000)
        val exact = exactInitials()
        val initialCharCost = ImeRules.weight("initialCharCost")
        val looseCost = ImeRules.weight("looseCost")
        val weights = charFreq()
        val bestInitial = IntArray(26) { -1 }
        fun initialBest(letter: Char): Int {
            val slot = letter - 'a'
            if (slot !in 0..25) return 0
            if (bestInitial[slot] >= 0) return bestInitial[slot]
            var best = 0
            for (id in syllables.indices) {
                val syl = syllables[id]
                if (syl.isNotEmpty() && syl[0] == letter && id < weights.size && weights[id] > best) best = weights[id]
            }
            bestInitial[slot] = best
            return best
        }
        for (i in 0 until n) {
            val acc = ArrayList<String>()
            for (j in i until n) {
                if (acc.size >= 8) break
                acc.add(tokens[j])
                val hits = ArrayList(matchMixed(acc, budget))
                // 音节枚举有预算，zen 这种排不进前 16 的音节会漏掉；再按首字母桶把整词补上（zmb → 怎么办）。
                val key = buildString(acc.size) { for (spell in acc) append(spell[0]) }
                for (index in exact[key] ?: EMPTY) {
                    if (sylCount(index) != acc.size) continue
                    var ok = true
                    for (s in acc.indices) {
                        if (!syllableAt(index, s).orEmpty().startsWith(acc[s])) {
                            ok = false
                            break
                        }
                    }
                    if (ok) hits.add(index)
                }
                val seenRow = HashSet<Int>()
                for (index in hits) {
                    if (!seenRow.add(index)) continue
                    val syllables = sylCount(index)
                    val weight = freq(index)
                    // 只打了首字母的位置（wozd 的 z、d）任何音节都一样可能，不算松；打了半个音节（xia → xiang）才算。
                    // 单个字母的偏门读音（w→晚、d→点）再扣一档，让「我的」压过「晚点」。
                    var loose = 0
                    var abbrev = 0
                    for (s in 0 until syllables) {
                        val spell = acc[s]
                        if (spell.length >= 2) loose += maxOf(0, syllableAt(index, s).orEmpty().length - spell.length)
                        if (spell.length == 1) {
                            val id = sylId(index, s)
                            val got = if (id in weights.indices) weights[id] else 0
                            if (got + looseCost < initialBest(spell[0])) abbrev += looseCost
                        }
                    }
                    var gain = edgeGain(wordAt(index), weight, syllables, loose) - abbrev
                    // 只打了一个首字母的单字（wozd 里的 d）最不确定，压一档，让「我知道」整词压过「我在的」。
                    if (syllables == 1 && acc[0].length == 1) gain -= initialCharCost
                    val singleTail = tokens[j].length == 1 && syllables == 1
                    edges[i].add(WordEdge(j + 1, wordAt(index), weight, loose, j + 1, false, gain, singleTail))
                }
            }
        }
        val beam = Array(n + 1) { ArrayList<PathState>() }
        beam[0].add(PathState(0, 0, "", 0, false, 0, 0, ""))
        val followGain = ImeRules.weight("mixedFollowGain")
        for (i in 0 until n) {
            if (beam[i].size > 8) {
                beam[i].sortByDescending { it.score }
                while (beam[i].size > 8) beam[i].removeAt(beam[i].lastIndex)
            }
            for (state in beam[i].toList()) {
                val follow = followsOf(state.last)
                for (edge in edges[i]) {
                    val followed = follow != null && edge.word in follow
                    val bonus = if (followed) followGain else 0
                    beam[edge.to].add(
                        PathState(
                            state.score + edge.gain + bonus,
                            state.freq + edge.freq,
                            state.text + edge.word,
                            state.words + 1,
                            false,
                            state.full + edge.full,
                            edge.to,
                            edge.word
                        )
                    )
                }
            }
        }
        val full = beam[n].sortedByDescending { it.score }
        val out = rankStates(full, 8) { state, rank ->
            if (state.words == 1) 4600 + minOf(state.freq, 489) - state.full * 12
            else 3700 - rank * 5
        }
        val seen = out.mapTo(HashSet()) { it.text }
        var prefixRank = 0
        for (i in n - 1 downTo 1) {
            if (prefixRank >= 3) break
            val state = beam[i].maxByOrNull { it.score } ?: continue
            if (state.text.length < 2 || !seen.add(state.text)) continue
            out.add(sentenceRow(state.text, 3000 - prefixRank++ * 5, 0))
        }
        return out
    }

    private fun sentenceRow(text: String, score: Int, keep: Int) =
        Candidate(text, text, "lexicon", "lexicon", null, null, "", score, keep)

    /**
     * 同一段文字可能既是整词（你好）又是两个单字拼出来的（你 + 好）。
     * 搭配加分只用来在束搜索里挑路径，最后按整词 / 多词的档位取高的那条，整词不会被自己的拆法压下去。
     */
    private fun rankStates(states: List<PathState>, limit: Int, tier: (PathState, Int) -> Int): ArrayList<Candidate> {
        val scored = LinkedHashMap<String, Candidate>()
        var multiRank = 0
        for (state in states) {
            if (state.text.isEmpty()) continue
            val score = if (state.words == 1) {
                tier(state, 0)
            } else {
                if (scored.containsKey(state.text)) continue
                tier(state, multiRank++)
            }
            val prev = scored[state.text]
            if (prev != null && prev.score >= score) continue
            scored[state.text] = sentenceRow(state.text, score, 0)
        }
        return scored.values.sortedByDescending { it.score }.take(limit).toCollection(ArrayList())
    }

    private fun followsOf(word: String): Set<String>? {
        if (word.isEmpty()) return null
        ImeRules.follows(word)?.let { if (it.isNotEmpty()) return it }
        if (word.length > 1) return ImeRules.follows(word.takeLast(1))
        return null
    }

    private fun edgeGain(word: String, freq: Int, syllables: Int, loose: Int = 0): Int {
        val base = if (syllables >= 2) freq + ImeRules.weight("syllableGain") * (syllables - 1) else charPrior[word] ?: freq
        return base - loose * ImeRules.weight("looseCost")
    }

    /** 简拼里的单字：常用字按字频给分，再减去一个底数，让生僻字组合沉下去。 */
    private fun initialGain(word: String, freq: Int, syllables: Int): Int {
        if (syllables >= 2) return freq + ImeRules.weight("initialWordGain")
        if (word.length == 1 && word[0] in GRAMMAR) return ImeRules.weight("initialGrammarGain")
        val prior = charPrior[word] ?: 0
        return if (prior >= 300) prior - 300 else ImeRules.weight("initialCharGain")
    }

    private var charFreqCache: IntArray? = null

    private fun charFreq(): IntArray {
        charFreqCache?.let { return it }
        val weights = IntArray(syllables.size)
        for (index in offsets.indices) {
            if (sylCount(index) != 1 || wordAt(index).length != 1) continue
            val id = sylId(index, 0)
            val word = wordAt(index)
            val weight = charPrior[word] ?: freq(index)
            if (id in weights.indices && weight > weights[id]) weights[id] = weight
        }
        charFreqCache = weights
        return weights
    }

    private fun mixOptions(spell: String): List<String> {
        val weights = charFreq()
        val ranked = PinyinEngine.syllablesStarting(spell).sortedWith(
            compareByDescending<String> {
                val id = sylOf[it]
                if (id != null && id in weights.indices) weights[id] else 0
            }.thenBy { it.length }.thenBy { it }
        )
        val picked = ArrayList<String>(8)
        val seen = HashSet<String>()
        if (PinyinEngine.isSyllable(spell) && seen.add(spell)) picked.add(spell)
        for (syl in ranked) {
            if (!seen.add(syl)) continue
            picked.add(syl)
            if (picked.size >= 16) break
        }
        return picked
    }

    private fun matchMixed(spells: List<String>, budget: IntArray): List<Int> {
        val options = spells.map { mixOptions(it) }
        val path = ArrayList<Int>()
        val bag = ArrayList<Int>()
        val seen = HashSet<Int>()
        fun rec(pos: Int) {
            if (budget[0] <= 0) return
            if (pos == spells.size) {
                for (index in topExact(path.toList(), 12)) if (seen.add(index)) bag.add(index)
                return
            }
            for (syl in options[pos]) {
                val id = sylOf[syl] ?: continue
                path.add(id)
                budget[0]--
                if (hasPrefix(path)) rec(pos + 1)
                path.removeAt(path.lastIndex)
                if (budget[0] <= 0) return
            }
        }
        rec(0)
        return bag.sortedByDescending { rankWeight(it) }.take(12)
    }

    private fun initialSentences(input: String): List<Candidate> {
        val exact = exactInitials()
        val n = input.length
        val edges = Array(n) { ArrayList<WordEdge>() }
        for (i in 0 until n) {
            val end = minOf(n, i + 16)
            for (j in i + 1..end) {
                val hits = exact[input.substring(i, j)] ?: continue
                for (index in hits) {
                    val syllables = sylCount(index)
                    val weight = freq(index)
                    val word = wordAt(index)
                    edges[i].add(WordEdge(j, word, weight, 0, j - i, false, initialGain(word, weight, syllables)))
                }
            }
        }
        val beam = Array(n + 1) { ArrayList<PathState>() }
        beam[0].add(PathState(0, 0, "", 0, false, 0, 0, ""))
        val followGain = ImeRules.weight("followGain")
        for (i in 0 until n) {
            if (beam[i].size > 8) {
                beam[i].sortByDescending { it.score }
                while (beam[i].size > 8) beam[i].removeAt(beam[i].lastIndex)
            }
            for (state in beam[i].toList()) {
                val follow = ImeRules.follows(state.last)
                for (edge in edges[i]) {
                    val bonus = if (follow != null && edge.word in follow) followGain else 0
                    beam[edge.to].add(
                        PathState(
                            state.score + edge.gain + bonus,
                            state.freq + edge.freq,
                            state.text + edge.word,
                            state.words + 1,
                            false,
                            0,
                            edge.to,
                            edge.word
                        )
                    )
                }
            }
        }
        val full = beam[n].sortedByDescending { it.score }
        val out = rankStates(full, 5) { state, rank ->
            if (state.words == 1) 2500 + minOf(state.freq, 489) else 2400 - rank * 5
        }
        val seen = out.mapTo(HashSet()) { it.text }
        var prefixRank = 0
        for (i in n - 1 downTo 2) {
            if (prefixRank >= 4) break
            val state = beam[i].maxByOrNull { it.score } ?: continue
            if (state.text.length < 2 || !seen.add(state.text)) continue
            out.add(sentenceRow(state.text, 1800 - prefixRank++ * 5, 0))
        }
        return out
    }

    private fun exactInitials(): Map<String, IntArray> {
        exactInitials?.let { return it }
        synchronized(this) {
            exactInitials?.let { return it }
            return buildExactInitials()
        }
    }

    private fun buildExactInitials(): Map<String, IntArray> {
        val buckets = HashMap<String, ArrayList<Int>>()
        for (index in offsets.indices) {
            val count = sylCount(index)
            if (count == 0 || count > 16) continue
            val key = buildString(count) {
                for (pos in 0 until count) {
                    val syl = syllableAt(index, pos).orEmpty()
                    if (syl.isEmpty()) {
                        append('?')
                    } else {
                        append(syl[0])
                    }
                }
            }
            if ('?' in key) continue
            val bag = buckets.getOrPut(key) { ArrayList(4) }
            if (bag.size < 12) {
                bag.add(index)
            } else {
                var worst = 0
                for (slot in 1 until bag.size) if (freq(bag[slot]) < freq(bag[worst])) worst = slot
                if (freq(index) > freq(bag[worst])) bag[worst] = index
            }
        }
        val built = HashMap<String, IntArray>(buckets.size)
        for ((key, bag) in buckets) built[key] = bag.sortedByDescending { freq(it) }.toIntArray()
        exactInitials = built
        return built
    }

    private fun decode(ids: List<Int>, syls: List<String>, partial: String, inputLength: Int): List<Candidate> {
        val n = ids.size
        val letters = IntArray(n + 1)
        for (i in 0 until n) letters[i + 1] = letters[i] + syls[i].length
        if (n == 0) {
            val out = ArrayList<Candidate>()
            for (syl in PinyinEngine.longerSyllables(partial)) {
                val id = sylOf[syl] ?: continue
                for (index in topExact(listOf(id), 4)) out.add(sentenceHit(index, inputLength, true))
            }
            return out
        }
        val edges = Array(n) { ArrayList<WordEdge>() }
        fun pushSlice(start: Int, slice: List<Int>, to: Int, isPartial: Boolean) {
            val consumed = if (isPartial) inputLength - letters[start] else letters[to] - letters[start]
            for (index in topExact(slice, 4)) {
                val syllables = slice.size
                val weight = freq(index)
                edges[start].add(
                    WordEdge(
                        to, wordAt(index), weight, fullLen(index), consumed, isPartial,
                        edgeGain(wordAt(index), weight, syllables)
                    )
                )
            }
        }
        for (i in 0 until n) {
            val acc = ArrayList<Int>()
            for (j in i until n) {
                if (acc.size >= 16) break
                acc.add(ids[j])
                val matched = hasPrefix(acc)
                if (matched) pushSlice(i, acc.toList(), j + 1, false)
                if (j == n - 1 && partial.isEmpty()) {
                    val head = acc.dropLast(1)
                    for (syl in PinyinEngine.longerSyllables(syls[j])) {
                        val id = sylOf[syl] ?: continue
                        val grown = head + id
                        if (hasPrefix(grown)) pushSlice(i, grown, n, true)
                    }
                }
                if (!matched) break
            }
        }
        if (partial.isNotEmpty()) {
            for (i in 0 until n) {
                val headIds = ids.subList(i, n)
                if (headIds.isNotEmpty() && !hasPrefix(headIds)) continue
                for (syl in PinyinEngine.longerSyllables(partial)) {
                    val id = sylOf[syl] ?: continue
                    val grown = headIds + id
                    if (hasPrefix(grown)) pushSlice(i, grown, n, true)
                }
            }
            for (syl in PinyinEngine.longerSyllables(partial)) {
                val id = sylOf[syl] ?: continue
                if (hasPrefix(listOf(id))) pushSlice(n - 1, listOf(id), n, true)
            }
        }
        val beam = Array(n + 1) { ArrayList<PathState>() }
        beam[0].add(PathState(0, 0, "", 0, false, 0, 0))
        val followGain = ImeRules.weight("sentenceFollowGain")
        for (i in 0 until n) {
            if (beam[i].size > 6) {
                beam[i].sortByDescending { it.score }
                while (beam[i].size > 6) beam[i].removeAt(beam[i].lastIndex)
            }
            for (state in beam[i]) {
                val follow = ImeRules.follows(state.last)
                for (edge in edges[i]) {
                    val bonus = if (follow != null && edge.word in follow) followGain else 0
                    val multi = if (!edge.partial && edge.word.length >= 2) edge.freq else 0
                    var loose = state.looseMin
                    if (multi < 320) {
                        for (ch in edge.word) {
                            val weight = charPrior[ch.toString()] ?: 0
                            if (weight < loose) loose = weight
                        }
                    }
                    val followed = if (state.follow == 1 || (follow != null && edge.word in follow)) 1 else 0
                    beam[edge.to].add(
                        PathState(
                            state.score + edge.gain + bonus,
                            state.freq + edge.freq,
                            state.text + edge.word,
                            state.words + 1,
                            state.partial || edge.partial,
                            state.full + edge.full,
                            state.covered + edge.consumed,
                            edge.word,
                            maxOf(state.bestMulti, multi),
                            loose,
                            followed
                        )
                    )
                }
            }
        }
        var pool = beam[n]
        if (pool.isEmpty()) {
            for (i in n - 1 downTo 1) {
                if (beam[i].isNotEmpty()) {
                    pool = beam[i]
                    break
                }
            }
        }
        if (pool.isEmpty()) return emptyList()
        val ranked = pool.sortedByDescending { it.score }
        var bestExact = 0
        for (state in pool) {
            if (state.words == 1 && !state.partial && state.full == state.covered && state.freq > bestExact) {
                bestExact = state.freq
            }
        }
        // 同一段文字可能既是整词（你好）又是两个单字拼出来的（你 + 好），按最终档位取高的那条。
        val scored = LinkedHashMap<String, Candidate>()
        var multiRank = 0
        val spokenRare = ImeRules.weight("spokenRare")
        val spokenCommonChar = ImeRules.weight("spokenCommonChar")
        val spokenPriorCap = ImeRules.weight("spokenPriorCap")
        val wholeWordFloor = ImeRules.weight("wholeWordFloor")
        val keepCost = ImeRules.weight("keepCost")
        for (state in ranked) {
            if (state.text.isEmpty()) continue
            val spoken = !state.partial && state.full == state.covered && state.words >= 2 && state.text.length == state.words
            val followBonus = if (state.follow == 1) 12 else 0
            var score = when {
                state.words == 1 && !state.partial && state.full == state.covered -> 5000 + minOf(state.freq, 489)
                state.words == 1 && state.partial -> 4000 - maxOf(0, state.full - state.covered) + minOf(state.freq, 489)
                spoken -> {
                    // 两个字、而且都是最常用的字（我也 / 把住）时，才压过生僻整词；
                    // 三字以上整个打完的（地下道）以及字本身不常用的（双台人身），都排到整词后面。
                    val min = spokenMin(state.text)
                    (if (bestExact < spokenRare && min >= spokenCommonChar && state.words <= 2) {
                        5000 + bestExact + 1 + minOf(min, spokenPriorCap) / 10
                    } else {
                        4500 + min
                    }) + followBonus
                }
                !state.partial && state.full == state.covered && state.bestMulti >= 200 -> {
                    val min = if (state.looseMin == 999) 450 else state.looseMin
                    val value = 5000 + 1 + minOf(min, 450) / 10 + 6 + followBonus
                    // 整个输入本来就是一个词（月天、问天）时，拆成「余额 + 天」这种多词说法排在它后面；词频低到不可信的除外。
                    if (bestExact >= wholeWordFloor) minOf(value, 5000 + bestExact - 1) else value
                }
                else -> {
                    if (scored.containsKey(state.text)) continue
                    3600 - multiRank++ * 5
                }
            }
            val keep = maxOf(0, inputLength - state.covered)
            // 末尾还有没用到的字母（nihaom 里的 m）时，「你好」只是半截，要排在吃掉 m 的「你好吗」后面。
            if (keep > 0) score -= keepCost
            val prev = scored[state.text]
            if (prev != null && prev.score >= score) continue
            scored[state.text] = sentenceRow(state.text, score, keep)
        }
        return scored.values.sortedByDescending { it.score }.take(6)
    }

    /** 整词很生僻时，用单字常用度把「我也」这类说法抬到生僻词前面。 */
    private fun spokenMin(text: String): Int {
        var min = 999
        for (ch in text) {
            val prior = charPrior[ch.toString()] ?: 0
            if (prior < min) min = prior
        }
        return min
    }

    private fun sentenceHit(index: Int, inputLength: Int, partial: Boolean): Candidate {
        val full = fullLen(index)
        val weight = freq(index)
        val score = if (partial) 4000 - maxOf(0, full - inputLength) + minOf(weight, 489) else 5000 + minOf(weight, 489)
        val word = wordAt(index)
        return Candidate(word, word, "lexicon", "lexicon", null, null, "", score, 0)
    }

    private fun hasPrefix(prefix: List<Int>): Boolean {
        val index = lower(prefix)
        return index < offsets.size && starts(index, prefix)
    }

    private fun topExact(prefix: List<Int>, k: Int): List<Int> {
        var index = lower(prefix)
        val bag = ArrayList<Int>()
        while (index < offsets.size && bag.size < 48) {
            val count = sylCount(index)
            if (count != prefix.size || !starts(index, prefix)) break
            bag.add(index)
            index++
        }
        return bag.sortedByDescending { rankWeight(it) }.take(k)
    }

    private fun rankWeight(index: Int): Int {
        if (sylCount(index) == 1) {
            val word = wordAt(index)
            if (word.length == 1) return charPrior[word] ?: freq(index)
        }
        return freq(index)
    }

    private fun fullLen(index: Int): Int {
        var total = 0
        for (pos in 0 until sylCount(index)) total += syllableAt(index, pos).orEmpty().length
        return total
    }

    private class WordEdge(
        val to: Int,
        val word: String,
        val freq: Int,
        val full: Int,
        val consumed: Int,
        val partial: Boolean,
        val gain: Int,
        val singleTail: Boolean = false
    )

    private class PathState(
        val score: Int,
        val freq: Int,
        val text: String,
        val words: Int,
        val partial: Boolean,
        val full: Int,
        val covered: Int,
        val last: String = "",
        val bestMulti: Int = 0,
        val looseMin: Int = 999,
        val follow: Int = 0
    )

    companion object {
        private val ASCII = Charset.forName("US-ASCII")
        private val UTF8 = Charsets.UTF_8
        private val GRAMMAR = "的了是在有要就都也还把被给让我你他她不没很会能到去来和与或而这那个着过吗呢吧啊".toSet()
        private val EMPTY = IntArray(0)
    }
}
