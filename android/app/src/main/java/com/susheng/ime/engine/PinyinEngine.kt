package com.susheng.ime.engine

data class LexEntry(val word: String, val pinyin: String, val freq: Int)

data class ContentItem(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val aliases: List<String>,
    val pinyin: String,
    val shortcut: String,
    val groupId: String,
    val groupName: String,
    val assetId: String?,
    val mime: String?,
    val validFrom: Long?,
    val validTo: Long?,
    val status: String,
    val version: Int,
    val deleted: Boolean
)

data class Candidate(
    val text: String,
    val commit: String,
    val source: String,
    val kind: String,
    val itemId: String?,
    val assetId: String?,
    val hint: String,
    val score: Int,
    val keep: Int = 0
)

internal class Seg(val syls: List<String>, val partial: String)

/**
 * 与 protocol/pinyin-engine.mjs 同一套规则：全拼、九键、简拼、v 短码、联想。
 */
object PinyinEngine {
    private val syllables = syllablesText.trim().split(Regex("\\s+")).toSet()
    private val syllablePrefixes: Set<String> = buildSet {
        for (syl in syllables) {
            for (len in 1..syl.length) add(syl.substring(0, len))
        }
    }
    private val startingCache = HashMap<String, List<String>>()
    private val t9 = mapOf(
        'a' to '2', 'b' to '2', 'c' to '2', 'd' to '3', 'e' to '3', 'f' to '3',
        'g' to '4', 'h' to '4', 'i' to '4', 'j' to '5', 'k' to '5', 'l' to '5',
        'm' to '6', 'n' to '6', 'o' to '6', 'p' to '7', 'q' to '7', 'r' to '7', 's' to '7',
        't' to '8', 'u' to '8', 'v' to '8', 'w' to '9', 'x' to '9', 'y' to '9', 'z' to '9'
    )

    fun candidates(
        raw: String,
        mode: String = "qwerty",
        lexicon: List<LexEntry>,
        items: List<ContentItem>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 20,
        sentences: List<Candidate> = emptyList()
    ): List<Candidate> {
        val input = raw.trim().lowercase()
        if (input.isEmpty()) return emptyList()
        val pool = active(items, now)
        val rows = mutableListOf<Candidate>()
        if (input.startsWith("v")) {
            for (item in pool) {
                val shortcut = item.shortcut.lowercase()
                if (shortcut.isNotEmpty() && (shortcut == input || shortcut.startsWith(input))) {
                    rows.add(official(item, 2_000_000 - shortcut.length))
                }
            }
            return rows.sortedByDescending { it.score }.take(limit)
        }
        for (item in pool) {
            val typed = scorePinyin(input, mode, item.pinyin)
            if (typed > 0) rows.add(official(item, typed + 1_000_000))
            val names = listOf(item.title) + item.aliases
            if (names.any { it.isNotEmpty() && (it == input || it.startsWith(input)) }) {
                rows.add(official(item, 1_500_000))
            }
        }
        for (entry in lexicon) {
            val typed = scorePinyin(input, mode, entry.pinyin)
            if (typed <= 0) continue
            var score = typed + entry.freq
            val letters = input.replace(Regex("[^a-z]"), "")
            if (mode != "t9" && letters.length >= 12 && entry.freq >= 300 && extendsByOneSyllable(letters, entry.pinyin)) {
                score += 800
            }
            rows.add(
                Candidate(entry.word, entry.word, "lexicon", "lexicon", null, null, entry.pinyin, score)
            )
        }
        rows.addAll(sentences)
        return dedupe(rows.sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.text.length })).take(limit)
    }

    fun associate(
        last: String,
        lexicon: List<LexEntry>,
        items: List<ContentItem>,
        now: Long = System.currentTimeMillis(),
        limit: Int = 8,
        commitSuffix: Boolean = true
    ): List<Candidate> {
        val word = last.trim()
        if (word.isEmpty()) return emptyList()
        val rows = mutableListOf<Candidate>()
        for (item in active(items, now)) {
            if (item.type == "image") continue
            val names = listOf(item.title) + item.aliases
            if (word in names) rows.add(official(item, 1_000_000))
        }
        for (entry in lexicon) {
            if (!entry.word.startsWith(word) || entry.word == word) continue
            val commit = if (commitSuffix) entry.word.substring(word.length) else entry.word
            rows.add(
                Candidate(entry.word, commit, "lexicon", "lexicon", null, null, entry.pinyin, associationScore(entry.word, entry.freq))
            )
        }
        // 「下一个词」联想：上屏 我想 以后给出 要 / 你 / 去，而不只是以「我想」开头的更长词。
        val anchor = rows.filter { it.itemId == null && it.text.length == word.length + 1 }.maxOfOrNull { it.score } ?: 480
        val step = ImeRules.weight("associateNextStep")
        ImeRules.nextWords(word).forEachIndexed { index, follow ->
            val text = word + follow
            if (rows.any { it.text == text }) return@forEachIndexed
            val commit = if (commitSuffix) follow else text
            rows.add(Candidate(text, commit, "lexicon", "lexicon", null, null, "", anchor - step * (index + 1)))
        }
        return dedupe(rows.sortedByDescending { it.score }).take(limit)
    }

    /** 长句词频往往高于二字词。联想时把超长词压下去，二字日常词略抬高。 */
    private fun associationScore(word: String, freq: Int): Int {
        val extra = maxOf(0, word.length - 2)
        return freq - extra * ImeRules.weight("associateLengthCost") + if (word.length == 2) ImeRules.weight("associatePairBonus") else 0
    }

    private fun active(items: List<ContentItem>, now: Long) = items.filter { item ->
        !item.deleted && item.status == "published" &&
            (item.validFrom == null || now >= item.validFrom) &&
            (item.validTo == null || now <= item.validTo)
    }

    private fun official(item: ContentItem, score: Int): Candidate {
        val image = item.type == "image"
        val text = if (image) item.title else if (item.type == "lexicon") item.body else item.title
        return Candidate(
            text, if (image) "" else item.body, "official", item.type, item.id, item.assetId,
            item.shortcut.ifEmpty { item.pinyin.ifEmpty { item.groupName } }, score
        )
    }

    private fun dedupe(list: List<Candidate>): List<Candidate> {
        val seen = HashSet<String>()
        val out = mutableListOf<Candidate>()
        for (row in list) {
            val key = if (row.itemId != null) "id:${row.itemId}" else "t:${row.commit}"
            if (!seen.add(key)) continue
            out.add(row)
        }
        return out
    }

    private fun compact(pinyin: String) = pinyin.lowercase().replace(Regex("[^a-z]"), "")

    private fun toT9(compact: String) = buildString {
        for (ch in compact) append(t9[ch] ?: "")
    }

    /** 九键的编码是数字。这里按首选词的拼音，把已经敲下的数字还原成字母。 */
    fun t9Preview(raw: String, pinyin: String): String {
        val need = raw.count { it in '2'..'9' }
        if (need == 0 || pinyin.isEmpty()) return ""
        var digits = 0
        val out = StringBuilder()
        for (ch in pinyin.lowercase()) {
            if (t9[ch] == null) {
                if ((ch == ' ' || ch == '\'') && digits in 1 until need && (out.isEmpty() || out.last() != '\'')) {
                    out.append('\'')
                }
                continue
            }
            if (digits == need) break
            out.append(ch)
            digits++
        }
        val letters = buildString { for (ch in out) if (ch in 'a'..'z') append(ch) }
        if (letters.isEmpty() || toT9(letters) != raw.filter { it in '2'..'9' }.take(letters.length)) return ""
        return out.toString().trim('\'', ' ')
    }

    private fun isSyllablePrefix(text: String): Boolean = text.isNotEmpty() && text in syllablePrefixes

    internal fun isSyllable(text: String) = text in syllables

    internal fun mixTokens(input: String): List<String>? {
        if (input.length > 24) return null
        val tokens = ArrayList<String>()
        var i = 0
        while (i < input.length) {
            val max = minOf(6, input.length - i)
            var best = 0
            for (len in 1..max) {
                if (!isSyllablePrefix(input.substring(i, i + len))) break
                best = len
            }
            if (best == 0) return null
            tokens.add(input.substring(i, i + best))
            i += best
        }
        return tokens
    }

    /** bangongs 贪心是 bang + o + n + g + s。完整音节让出一个字母，就能切成 ban + gong + s。 */
    internal fun mixSegmentations(input: String): List<List<String>> {
        val base = mixTokens(input) ?: return emptyList()
        val found = ArrayList<List<String>>()
        found.add(base)
        if (base.all { isSyllable(it) }) return found
        val seen = HashSet<String>()
        seen.add(base.joinToString("\u0000"))
        for (i in base.indices) {
            if (found.size >= 4) break
            val tok = base[i]
            if (tok.length < 3 || !isSyllable(tok)) continue
            val head = tok.dropLast(1)
            if (!isSyllable(head)) continue
            val rest = mixTokens(tok.takeLast(1) + base.subList(i + 1, base.size).joinToString("")) ?: continue
            if (rest.isEmpty() || rest[0].length < 2) continue
            val alt = ArrayList<String>(base.subList(0, i))
            alt.add(head)
            alt.addAll(rest)
            if (alt.size > 16) continue
            if (!seen.add(alt.joinToString("\u0000"))) continue
            found.add(alt)
        }
        return found
    }

    private fun segmentAllowPartial(input: String): Seg? {
        val memo = HashMap<Int, Seg?>()
        fun rec(i: Int): Seg? {
            if (memo.containsKey(i)) return memo[i]
            if (i == input.length) {
                val done = Seg(emptyList(), "")
                memo[i] = done
                return done
            }
            val max = minOf(6, input.length - i)
            for (len in max downTo 1) {
                val piece = input.substring(i, i + len)
                if (piece !in syllables) continue
                val rest = rec(i + len) ?: continue
                val hit = Seg(listOf(piece) + rest.syls, rest.partial)
                memo[i] = hit
                return hit
            }
            val tail = input.substring(i)
            if (tail.length <= 6 && isSyllablePrefix(tail) && tail !in syllables) {
                val partial = Seg(emptyList(), tail)
                memo[i] = partial
                return partial
            }
            memo[i] = null
            return null
        }
        return rec(0)
    }

    private fun segmentFull(compact: String): List<String>? {
        val got = segmentAllowPartial(compact) ?: return null
        if (got.partial.isNotEmpty()) return null
        return got.syls
    }

    private fun syllablesOf(pinyin: String): List<String>? {
        val parts = pinyin.lowercase().trim().split(Regex("\\s+")).map { it.replace(Regex("[^a-z]"), "") }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null
        if (parts.size > 1) return if (parts.all { it in syllables }) parts else null
        return segmentFull(parts[0])
    }

    private fun alignedTo(input: String, syls: List<String>?): Boolean {
        val got = segmentAllowPartial(input) ?: return false
        if (syls == null || got.syls.size > syls.size) return false
        for (i in got.syls.indices) if (syls[i] != got.syls[i]) return false
        if (got.partial.isEmpty()) return true
        val next = syls.getOrNull(got.syls.size) ?: return false
        return next.startsWith(got.partial)
    }

    private fun alignedParts(parts: List<String>, syls: List<String>?): Boolean {
        if (syls == null || parts.size > syls.size || parts.isEmpty()) return false
        var any = false
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty()) continue
            any = true
            if (!syls[i].startsWith(part)) return false
        }
        return any
    }

    private fun extendsByOneSyllable(input: String, pinyin: String): Boolean {
        val compact = compact(pinyin)
        val syls = syllablesOf(pinyin) ?: return false
        if (syls.size < 2 || input.isEmpty()) return false
        val tail = syls.last()
        return compact.length == input.length + tail.length && compact.startsWith(input)
    }

    private fun qwertyScore(raw: String, pinyin: String): Int {
        val compact = compact(pinyin)
        val syls = syllablesOf(pinyin) ?: return 0
        if (compact.isEmpty()) return 0
        if ('\'' in raw) {
            val parts = raw.split("'").map { it.replace(Regex("[^a-z]"), "") }
            if (!alignedParts(parts, syls)) return 0
            val joined = parts.joinToString("")
            return if (compact == joined) 5000 else 4000 - (compact.length - joined.length)
        }
        val input = raw.replace(Regex("[^a-z]"), "")
        if (input.isEmpty()) return 0
        if (compact == input) return 5000
        if (alignedTo(input, syls)) return 4000 - (compact.length - input.length)
        val ini = syls.joinToString("") { it.take(1) }
        if (ini == input && input != compact) return 2500
        if (input.length >= 2 && ini.startsWith(input) && input != compact) return 1500 - (ini.length - input.length)
        return 0
    }

    private fun t9Score(raw: String, pinyin: String): Int {
        val input = raw.replace(Regex("[^2-9]"), "")
        if (input.isEmpty() || input != raw.replace("'", "")) return 0
        val digits = toT9(compact(pinyin))
        if (!digits.startsWith(input)) return 0
        return 3000 - (digits.length - input.length) + if (digits == input) 500 else 0
    }

    private fun scorePinyin(raw: String, mode: String, pinyin: String): Int {
        return if (mode == "t9") t9Score(raw, pinyin) else qwertyScore(raw.lowercase(), pinyin)
    }

    internal fun longerSyllables(tail: String): List<String> {
        if (tail.isEmpty() || tail.length > 5) return emptyList()
        return syllables.filter { it.length > tail.length && it.startsWith(tail) }
    }

    internal fun syllablesStarting(prefix: String): List<String> {
        if (prefix.isEmpty()) return emptyList()
        return startingCache.getOrPut(prefix) { syllables.filter { it.startsWith(prefix) } }
    }

    internal fun segmentAll(input: String, limit: Int = 8): List<Seg> {
        val found = ArrayList<Seg>()
        val acc = ArrayList<String>()
        fun rec(i: Int) {
            if (found.size >= limit) return
            if (i == input.length) {
                found.add(Seg(acc.toList(), ""))
                return
            }
            val max = minOf(6, input.length - i)
            var any = false
            for (len in max downTo 1) {
                val piece = input.substring(i, i + len)
                if (piece !in syllables) continue
                any = true
                acc.add(piece)
                rec(i + len)
                acc.removeAt(acc.lastIndex)
                if (found.size >= limit) return
            }
            if (!any) {
                val tail = input.substring(i)
                if (tail.isNotEmpty() && tail.length <= 6 && isSyllablePrefix(tail)) found.add(Seg(acc.toList(), tail))
            }
        }
        rec(0)
        return found
    }
}

private const val syllablesText = """
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
