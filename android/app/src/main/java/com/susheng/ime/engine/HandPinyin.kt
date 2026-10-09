package com.susheng.ime.engine

import android.content.Context

/** 单字的带声调拼音，用来在手写候选上面显示 wǒ 这样的读音。 */
internal object HandPinyin {
    // 通过 @Volatile 发布不可变 Map：ensure() 在锁内构建完整局部表后一次性赋值，
    // 避免非 volatile HashMap 双重检查锁定导致其他线程读到过期/半填充状态
    @Volatile
    private var readings: Map<String, String> = emptyMap()

    fun ensure(context: Context) {
        if (readings.isNotEmpty()) return
        // 资产读取放到锁外，避免整个文件读期间持有监视器阻塞其他线程（反正 of() 的读也不靠这把锁）；
        // 读完后仅在锁内做双重检查与 volatile 发布。
        val loaded = HashMap<String, String>()
        context.assets.open("char-pinyin.txt").bufferedReader().useLines { lines ->
            for (line in lines) {
                val split = line.indexOf('\t')
                if (split <= 0) continue
                loaded[line.substring(0, split)] = line.substring(split + 1)
            }
        }
        synchronized(this) {
            if (readings.isEmpty()) readings = loaded
        }
    }

    fun of(text: String): String {
        val table = readings
        if (text.isEmpty() || table.isEmpty()) return ""
        // 直接拼进 StringBuilder，避免 mapNotNull 的中间 List 与 joinToString 的二次构建。
        // 键仍用 String（而非 Char）以兼容非 BMP 字符。
        val sb = StringBuilder()
        for (ch in text) {
            val reading = table[ch.toString()] ?: continue
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(reading)
        }
        return sb.toString()
    }
}
