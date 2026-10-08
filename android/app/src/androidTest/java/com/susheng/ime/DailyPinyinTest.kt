package com.susheng.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.susheng.ime.engine.ImeRules
import com.susheng.ime.engine.LexiconBin
import com.susheng.ime.engine.PinyinEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** 在模拟器上跑和协议测试同一份日常拼音，确认安装包里的词库排序一致。 */
@RunWith(AndroidJUnit4::class)
class DailyPinyinTest {
    @Test
    fun everydayPinyinRanksFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val bytes = app.assets.open("lexicon.bin").use { it.readBytes() }
        val priorText = app.assets.open("char-prior.json").bufferedReader().use { it.readText() }
        val root = JSONObject(priorText)
        val prior = HashMap<String, Int>(root.length())
        val keys = root.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            prior[key] = root.getInt(key)
        }
        ImeRules.load(app.assets.open("ime-rules.json").bufferedReader().use { it.readText() })
        val bin = LexiconBin(bytes, prior)
        val casesText = instrumentation.context.assets.open("daily-pinyin-cases.json").bufferedReader().use { it.readText() }
        val cases = JSONArray(casesText)
        assertTrue("用例只有 ${cases.length()} 条", cases.length() >= 1000)
        val bad = ArrayList<String>()
        for (index in 0 until cases.length()) {
            val row = cases.getJSONArray(index)
            val raw = row.getString(0)
            val word = row.getString(1)
            val rows = PinyinEngine.candidates(
                raw,
                "qwerty",
                bin.shortlist(raw, "qwerty"),
                emptyList(),
                sentences = bin.sentences(raw)
            )
            val top = rows.firstOrNull()?.commit
            if (top != word) bad.add("$raw → ${top ?: "无"}，期望 $word")
        }
        assertEquals(bad.take(12).joinToString("\n"), 0, bad.size)
    }
}
