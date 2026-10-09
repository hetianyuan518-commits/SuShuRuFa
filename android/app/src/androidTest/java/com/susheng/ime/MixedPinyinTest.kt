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

/**
 * 不是纯首字母：末尾少打几字母，或者句子中间夹着首字母。
 *
 * 这里的首选断言（`我想说`/`办公室`/`你来下我的办公室`）是有意的“排序回归闸门”——和
 * DailyPinyinTest 一样，目的就是确认安装包里词库/规则的排序没有意外漂移，所以刻意锁定精确首选，
 * 而不是只断言“候选里包含某词”。词库或规则重建若改变了排序，这里应当失败并提示人工复核。
 */
@RunWith(AndroidJUnit4::class)
class MixedPinyinTest {
    @Test
    fun tailPartialListsSeveralPhrases() {
        val texts = top("woshis", 8)
        for (word in listOf("我是说", "我是谁", "我试试")) {
            assertTrue("$word 不在 ${texts.joinToString(" ")}", word in texts)
        }
        assertEquals("我想说", top1("woxiangs"))
        assertEquals("办公室", top1("bangongs"))
    }

    @Test
    fun initialsInsideASentence() {
        assertEquals("你来下我的办公室", top1("nilaixiawdbangongs"))
    }

    /** 首选：候选为空时给出带输入的可诊断信息，而不是抛 `List is empty`。 */
    private fun top1(raw: String): String {
        val list = top(raw, 1)
        assertTrue("$raw 无候选", list.isNotEmpty())
        return list.first()
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
            val bytes = readAsset(app, "lexicon.bin")
            val prior = JSONObject(readText(app, "char-prior.json"))
                .let { root -> root.keys().asSequence().associateWith { root.getInt(it) } }
            ImeRules.load(readText(app, "ime-rules.json"))
            bin = LexiconBin(bytes, prior)
        }

        /** 读取二进制资产，失败时点名是哪个资产，而不是抛裸 `FileNotFoundException`。 */
        private fun readAsset(app: android.content.Context, name: String): ByteArray =
            try {
                app.assets.open(name).use { it.readBytes() }
            } catch (e: Exception) {
                throw AssertionError("无法读取测试资产 $name", e)
            }

        /** 读取文本资产，失败时点名是哪个资产。 */
        private fun readText(app: android.content.Context, name: String): String =
            try {
                app.assets.open(name).bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                throw AssertionError("无法读取测试资产 $name", e)
            }
    }
}
