package com.susheng.ime.engine

import android.graphics.PointF
import kotlin.math.abs

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
            val parts = line.trim().split(" ")
            parts[0] to parts[1]
        }.sortedBy { it.first.length }.toList()
    }

    fun symbol(ch: Char): String = when (ch) {
        '1' -> "一"
        '2' -> "丨"
        '3' -> "丿"
        '4' -> "丶"
        '5' -> "乛"
        '6', '*' -> "*"
        else -> ch.toString()
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

    fun classify(points: List<PointF>): Char {
        if (points.size < 2) return '4'
        val start = points.first()
        val end = points.last()
        val dx = end.x - start.x
        val dy = end.y - start.y
        var turn = false
        var prevX = 0f
        var prevY = 0f
        for (index in 1 until points.size) {
            val stepX = points[index].x - points[index - 1].x
            val stepY = points[index].y - points[index - 1].y
            if (index > 1 && (stepX * prevX + stepY * prevY) < 0f) turn = true
            prevX = stepX
            prevY = stepY
        }
        if (turn && abs(dx) + abs(dy) > 0f) return '5'
        if (abs(dx) >= abs(dy) * 1.15f && dx > 0f) return '1'
        if (abs(dy) >= abs(dx) * 1.15f && dy > 0f) return '2'
        if (dx < 0f && dy > 0f) return '3'
        if (dx > 0f && dy > 0f) return '4'
        return '5'
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
