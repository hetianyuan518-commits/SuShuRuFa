package com.susheng.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.susheng.ime.engine.ImeRules
import com.susheng.ime.engine.LexiconBin
import com.susheng.ime.engine.PinyinEngine
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 不是纯首字母：末尾少打几字母，或者句子中间夹着首字母。 */
@RunWith(AndroidJUnit4::class)
class MixedPinyinTest {
    @Test
    fun tailPartialListsSeveralPhrases() {
        val texts = top("woshis", 8)
        for (word in listOf("我是说", "我是谁", "我试试")) {
            assertTrue("$word 不在 ${texts.joinToString(" ")}", word in texts)
        }
        assertEquals("我想说", top("woxiangs", 1).first())
        assertEquals("办公室", top("bangongs", 1).first())
    }

    @Test
    fun initialsInsideASentence() {
        assertEquals("你来下我的办公室", top("nilaixiawdbangongs", 1).first())
    }

    private fun top(raw: String, limit: Int) = PinyinEngine.candidates(
        raw,
        "qwerty",
        bin.shortlist(raw, "qwerty"),
        emptyList(),
        sentences = bin.sentences(raw)
    ).take(limit).map { it.commit }

    companion object {
        private lateinit var bin: LexiconBin

        @JvmStatic
        @BeforeClass
        fun load() {
            val app = InstrumentationRegistry.getInstrumentation().targetContext
            val bytes = app.assets.open("lexicon.bin").use { it.readBytes() }
            val root = JSONObject(app.assets.open("char-prior.json").bufferedReader().use { it.readText() })
            val prior = HashMap<String, Int>(root.length())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                prior[key] = root.getInt(key)
            }
            ImeRules.load(app.assets.open("ime-rules.json").bufferedReader().use { it.readText() })
            bin = LexiconBin(bytes, prior)
        }
    }
}
