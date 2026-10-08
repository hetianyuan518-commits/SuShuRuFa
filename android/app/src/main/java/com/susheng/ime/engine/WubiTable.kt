package com.susheng.ime.engine

/**
 * 五笔 86。单字码来自公开码表，词组用「前两码 / 前两码」这类规则从拼音词表拼出来。
 * 没有对应编码时才把字母本身留在候选末尾，避免正常打字直接上屏字母。
 */
internal object WubiTable {
    private val lock = Any()

    /** 只在整表建完后替换。按键线程读这张快照，不会撞上正在 put 的表。 */
    @Volatile private var table: Map<String, List<String>> = emptyMap()

    fun ready() = table.isNotEmpty()

    fun load(lines: Sequence<String>) {
        if (table.isNotEmpty()) return
        synchronized(lock) {
            if (table.isNotEmpty()) return
            val grouped = HashMap<String, ArrayList<String>>()
            for (line in lines) {
                val split = line.indexOf('\t')
                if (split <= 0) continue
                val code = line.substring(0, split)
                val word = line.substring(split + 1).trim()
                if (code.isEmpty() || word.isEmpty()) continue
                val list = grouped.getOrPut(code) { ArrayList(1) }
                if (word !in list) list.add(word)
            }
            table = grouped
        }
    }

    fun candidates(code: String): List<String> {
        val exact = table
        val key = code.lowercase()
        if (key.isEmpty()) return emptyList()
        val words = exact[key]
        if (!words.isNullOrEmpty()) return words
        if (key.length >= 4) return listOf(key)
        return emptyList()
    }

    fun first(code: String): String? = table[code.lowercase()]?.firstOrNull()
}
