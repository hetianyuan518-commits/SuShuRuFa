package com.susheng.ime.engine

/**
 * 五笔画：1 横、2 竖、3 撇、4 点捺、5 折。6 或 * 是通配。
 * 只收录笔顺稳定的常用字，避免把不确定的笔顺写进词表。
 */
internal object StrokeTable {
    private val entries: List<Pair<String, String>>

    init {
        val rows = """
            1 一
            11 二
            111 三
            12 十
            112 士干于
            121 土工
            13 厂
            15 七丁
            124 下
            1324 不
            134 大
            1134 天夫
            1344 太
            34 人入八
            342 个
            3434 从
            24 卜
            211 上
            251 口
            2512 中
            25112 申
            25111 日
            2511111 目
            251121 田
            252 山
            1234 木
            11234 未末
            12341234 林
            1112 王
            41121 主
            31121 生
            1132 开井
            2534 水
            4334 火
            534 小
            4544 心
            35111 月
            32511 白
            132511 百
            312 千
            54 又
            544 叉
            521 子
            531 女
            531521 好
            52 了
            525 也
            35 九
            344 今
            32 亻
        """.trimIndent()
        entries = rows.lineSequence().map { line ->
            // 用 \s+ 而非单个字面空格切分，避免连续空格产生空字段；
            // 校验后再索引，坏行以清晰消息失败，而不是抛 IndexOutOfBounds。
            val parts = line.trim().split(Regex("\\s+"))
            require(parts.size >= 2 && parts[0].isNotEmpty() && parts[1].isNotEmpty()) { "bad stroke row: $line" }
            parts[0] to parts[1]
        }.toList()
    }

    fun symbol(ch: Char): String = when (ch) {
        '1' -> "一"
        '2' -> "丨"
        '3' -> "丿"
        '4' -> "丶"
        '5' -> "乛"
        '6', '*' -> "*"
        // 非笔画符号的意外输入返回空串，由调用方过滤，避免杂字符泄漏到显示标签。
        else -> ""
    }

    fun candidates(pattern: String): List<String> {
        if (pattern.isEmpty()) return emptyList()
        val ranked = entries.filter { (code, _) -> matches(code, pattern) }
            .sortedWith(compareBy<Pair<String, String>> { if (it.first.length == pattern.length) 0 else 1 }.thenBy { it.first.length })
        val out = ArrayList<String>()
        for ((_, words) in ranked) {
            for (ch in words) {
                val text = ch.toString()
                if (text in out) continue
                out.add(text)
                if (out.size >= 12) return out
            }
        }
        return out
    }

    private fun matches(code: String, pattern: String): Boolean {
        if (pattern.length > code.length) return false
        for (index in pattern.indices) {
            val mark = pattern[index]
            if (mark != '6' && mark != '*' && mark != code[index]) return false
        }
        return true
    }
}
