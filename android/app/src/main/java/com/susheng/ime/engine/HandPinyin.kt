package com.susheng.ime.engine

import android.content.Context

/** 单字的带声调拼音，用来在手写候选上面显示 wǒ 这样的读音。 */
internal object HandPinyin {
    private val readings = HashMap<String, String>()

    fun ensure(context: Context) {
        if (readings.isNotEmpty()) return
        synchronized(this) {
            if (readings.isNotEmpty()) return
            context.assets.open("char-pinyin.txt").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val split = line.indexOf('\t')
                    if (split <= 0) continue
                    readings[line.substring(0, split)] = line.substring(split + 1)
                }
            }
        }
    }

    fun of(text: String): String {
        if (text.isEmpty() || readings.isEmpty()) return ""
        return text.mapNotNull { readings[it.toString()] }.joinToString(" ")
    }
}
