package com.susheng.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.susheng.ime.engine.ImeRules
import com.susheng.ime.engine.LexiconBin
import com.susheng.ime.engine.PinyinEngine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 没打全的拼音：全简拼、末字只打首字母、第二个字只打首字母。
 * 和 protocol/pinyin-abbrev.test.mjs 同一份用例、同一条卡线。同一串字母常对应好几句都通的话，所以按通过率卡。
 */
@RunWith(AndroidJUnit4::class)
class AbbrevPinyinTest {
    @Test(timeout = 900_000)
    fun fullInitials() = check("ini", "全简拼", 86, 94)

    @Test(timeout = 900_000)
    fun tailInitial() = check("tail", "末字只打首字母", 92, 97)

    @Test(timeout = 900_000)
    fun midInitial() = check("mid", "第二个字只打首字母", 92, 94)

    private fun check(kind: String, name: String, wantTop1: Int, wantTop5: Int) {
        var total = 0
        var top1 = 0
        var top5 = 0
        val misses = ArrayList<String>()
        for (index in 0 until cases.length()) {
            val row = cases.getJSONArray(index)
            if (row.getString(0) != kind) continue
            total++
            val raw = row.getString(1)
            val word = row.getString(2)
            val texts = PinyinEngine.candidates(
                raw,
                "qwerty",
                bin.shortlist(raw, "qwerty"),
                emptyList(),
                sentences = bin.sentences(raw)
            ).take(5).map { it.commit }
            if (texts.firstOrNull() == word) top1++ else if (misses.size < 20) misses.add("$raw → ${texts.take(3).joinToString(" ")}，期望 $word")
            if (word in texts) top5++
        }
        assertTrue("$name 用例只有 $total 条", total >= 50)
        val rate1 = top1 * 100 / total
        val rate5 = top5 * 100 / total
        val detail = "$name $total 条：首选 $top1（$rate1%），前五 $top5（$rate5%）\n" + misses.joinToString("\n")
        assertTrue("$detail\n首选 $rate1% 低于 $wantTop1%", rate1 >= wantTop1)
        assertTrue("$detail\n前五 $rate5% 低于 $wantTop5%", rate5 >= wantTop5)
    }

    companion object {
        private lateinit var bin: LexiconBin
        private lateinit var cases: JSONArray

        @JvmStatic
        @BeforeClass
        fun load() {
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
            bin = LexiconBin(bytes, prior)
            val text = instrumentation.context.assets.open("pinyin-abbrev-cases.json").bufferedReader().use { it.readText() }
            cases = JSONArray(text)
            assertTrue("用例只有 ${cases.length()} 条", cases.length() >= 20000)
        }
    }
}
