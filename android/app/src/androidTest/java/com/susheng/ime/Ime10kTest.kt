package com.susheng.ime

import android.content.res.AssetManager
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.susheng.ime.engine.HandModel
import com.susheng.ime.engine.ImeRules
import com.susheng.ime.engine.LexiconBin
import com.susheng.ime.engine.PinyinEngine
import com.susheng.ime.engine.WubiTable
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** 十万条日常用例：拼音首选、五笔首码、手写认字。在模拟器上跑安装包里的同一套引擎。 */
@RunWith(AndroidJUnit4::class)
class Ime10kTest {
    @Test(timeout = 1_800_000)
    fun pinyin() = check("py", 83967) { raw, word ->
        val rows = PinyinEngine.candidates(
            raw,
            "qwerty",
            bin.shortlist(raw, "qwerty"),
            emptyList(),
            sentences = bin.sentences(raw)
        )
        rows.firstOrNull()?.commit == word
    }

    @Test(timeout = 180_000)
    fun wubi() = check("wb", 15000) { code, word ->
        WubiTable.first(code) == word
    }

    @Test(timeout = 300_000)
    fun handwriting() {
        assertTrue(HandModel.ensure(app))
        val bad = ArrayList<String>()
        var count = 0
        // 首选命中计数只在本次运行有效，用局部变量避免依赖 JUnit 实例生命周期。
        var handTop1 = 0
        for (index in 0 until cases.length()) {
            val row = cases.getJSONArray(index)
            if (row.getString(0) != "hw") continue
            count++
            val file = row.getString(1)
            val word = row.getString(2)
            val bitmap = testAssets.open("hand/$file").use { BitmapFactory.decodeStream(it) }
                ?: throw AssertionError("无法解码手写资源 hand/$file")
            try {
                // 只调用一次：重跑相同入参只能掩盖识别的不确定性，并拖慢每个未命中用例。
                val got = HandModel.recognize(bitmap, 5)
                if (got.firstOrNull() == word) handTop1++
                if (word !in got) bad.add("$file $word → ${got.joinToString("")}")
            } finally {
                // 无论识别是否抛异常/断言失败，native bitmap 都要释放，否则跨 1000 次循环泄露。
                bitmap.recycle()
            }
        }
        assertEquals(count, 1000)
        assertEquals(failures(bad), 0, bad.size)
        // 识别模型与资源位图都是固定的，结果是确定性的；970 是质量闸门而非设备敏感阈值。
        assertTrue("手写首选命中 $handTop1，低于 970", handTop1 >= 970)
    }

    private fun check(mode: String, expected: Int, hit: (String, String) -> Boolean) {
        val bad = ArrayList<String>()
        var count = 0
        for (index in 0 until cases.length()) {
            val row = cases.getJSONArray(index)
            if (row.getString(0) != mode) continue
            count++
            val input = row.getString(1)
            val word = row.getString(2)
            if (!hit(input, word)) bad.add("$input → 期望 $word")
        }
        // 断言确切条数，避免用例集被截断/缩水时静默通过。
        assertEquals("$mode 用例数不符", expected, count)
        assertEquals(failures(bad), 0, bad.size)
    }

    /** 失败诊断：展示前 12 条，剩余用“... 还有 N 条”显式标记，不静默截断。 */
    private fun failures(bad: List<String>): String {
        val shown = bad.take(12).joinToString("\n")
        val more = bad.size - 12
        return if (more > 0) "$shown\n... 还有 $more 条" else shown
    }

    companion object {
        private lateinit var app: android.content.Context
        private lateinit var testAssets: AssetManager
        private lateinit var bin: LexiconBin
        private lateinit var cases: JSONArray

        @JvmStatic
        @BeforeClass
        fun load() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            app = instrumentation.targetContext
            testAssets = instrumentation.context.assets
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
            app.assets.open("wubi86.txt").bufferedReader().use { WubiTable.load(it.lineSequence()) }
            val text = instrumentation.context.assets.open("ime-10k-cases.json").bufferedReader().use { it.readText() }
            cases = JSONArray(text)
            assertTrue("用例只有 ${cases.length()} 条", cases.length() >= 99900)
        }
    }
}
