package com.susheng.ime.engine

/**
 * 五笔 86。单字码来自公开码表，词组用「前两码 / 前两码」这类规则从拼音词表拼出来。
 * 没有对应编码时才把字母本身留在候选末尾，避免正常打字直接上屏字母。
 */
internal object WubiTable {
    private val lock = Any()

    /** 只在整表建完后替换。按键线程读这张快照，不会撞上正在 put 的表。 */
    @Volatile private var table: Map<String, Set<String>> = emptyMap()

    fun ready() = table.isNotEmpty()

    fun load(lines: Sequence<String>) {
        if (table.isNotEmpty()) return
        synchronized(lock) {
            if (table.isNotEmpty()) return
            val grouped = HashMap<String, LinkedHashSet<String>>()
            for (line in lines) {
                val split = line.indexOf('\t')
                if (split <= 0) continue
                // 载入时统一小写，和 candidates()/first() 的 code.lowercase() 查找保持一致，
                // 否则源表里的大写/混合大小写编码将永远查不到。
                val code = line.substring(0, split).lowercase()
                val word = line.substring(split + 1).trim()
                if (code.isEmpty() || word.isEmpty()) continue
                // LinkedHashSet：O(1) 去重且保留插入顺序，避免 ArrayList 的 O(n²) 线性扫描。
                grouped.getOrPut(code) { LinkedHashSet(1) }.add(word)
            }
            table = grouped
        }
    }

    fun candidates(code: String): List<String> {
        val exact = table
        val key = code.lowercase()
        if (key.isEmpty()) return emptyList()
        val words = exact[key]
        // 防御性拷贝，避免调用方（或 Java 互操作）转型为 MutableList 后修改共享快照。
        if (!words.isNullOrEmpty()) return words.toList()
        // 无匹配且编码 >= 4 位时，有意把编码字母本身作为候选（见类 KDoc），
        // 避免正常打字直接丢字；这是设计约定，非疏漏。
        if (key.length >= 4) return listOf(key)
        return emptyList()
    }

    fun first(code: String): String? = table[code.lowercase()]?.firstOrNull()
}
