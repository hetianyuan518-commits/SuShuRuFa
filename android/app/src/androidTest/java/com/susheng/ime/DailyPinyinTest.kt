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

/**
 * 在模拟器上跑和协议测试同一份日常拼音，确认安装包里的词库排序一致。
 *
 * 被测引擎的 lexicon.bin/char-prior.json/ime-rules.json 都从 `targetContext`（即安装包本体）读取，
 * 所以验证的是实际随应用发行的引擎；而期望表 daily-pinyin-cases.json 是测试专用资产（不随应用打包），
 * 由 protocol/build-*.mjs 与 lexicon 同源生成。两者同源于 lexicon 生成链，因此排序应一致；
 * 若期望表相对打包词库变旧，下面的首选断言会失败。彻底消除漂移需在资产里埋版本戳（当前无此基建）。
 */
@RunWith(AndroidJUnit4::class)
class DailyPinyinTest {
    @Test
    fun everydayPinyinRanksFirst() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext
        val bytes = app.assets.open("lexicon.bin").use { it.readBytes() }
        val priorText = app.assets.open("char-prior.json").bufferedReader().use { it.readText() }
        val root = JSONObject(priorText)
        val prior = root.keys().asSequence().associateWith { root.getInt(it) }
        ImeRules.load(app.assets.open("ime-rules.json").bufferedReader().use { it.readText() })
        val bin = LexiconBin(bytes, prior)
        val casesText = instrumentation.context.assets.open("daily-pinyin-cases.json").bufferedReader().use { it.readText() }
        val cases = JSONArray(casesText)
        assertTrue("用例只有 ${cases.length()} 条", cases.length() >= 1000)
        val bad = ArrayList<String>()
        for (index in 0 until cases.length()) {
            // 包一层 try/catch，使格式/模式漂移的行报错时带上 index，而不是抛裸 JSONException 中断整个测试。
            val raw: String
            val word: String
            try {
                val row = cases.getJSONArray(index)
                raw = row.getString(0)
                word = row.getString(1)
            } catch (e: Exception) {
                throw AssertionError("daily-pinyin-cases.json 第 $index 行解析失败", e)
            }
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
