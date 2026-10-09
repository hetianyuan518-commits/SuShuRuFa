package com.susheng.ime.engine

import android.util.Log
import org.json.JSONObject

/**
 * 排序权重和「前词 → 后词」搭配表，来自 assets/ime-rules.json，和 protocol/ime-rules.json 是同一份。
 * 代码只负责加载；没加载到文件时用内置默认值，默认值和文件保持一致。
 */
object ImeRules {
    private val DEFAULT_WEIGHTS = mapOf(
        "exactWord" to 5000,
        "wordCap" to 489,
        "prefixWord" to 4000,
        "spokenBase" to 5000,
        "spokenRare" to 200,
        "spokenPriorCap" to 450,
        "spokenCommonChar" to 400,
        "wholeWordFloor" to 100,
        "phraseBonus" to 6,
        "followBonus" to 12,
        "followGain" to 900,
        "sentenceFollowGain" to 300,
        "mixedFollowGain" to 300,
        "initialCharCost" to 200,
        "keepCost" to 600,
        "syllableGain" to 560,
        "looseCost" to 40,
        "initialWordGain" to 2200,
        "initialGrammarGain" to 280,
        "initialCharGain" to -700,
        "associateLengthCost" to 90,
        "associatePairBonus" to 40,
        "associateNextStep" to 8
    )

    // 三张表通过单一 @Volatile 引用原子发布，避免读者观察到新 weights 配旧 next/follows 的撕裂状态
    private class Rules(
        val weights: Map<String, Int>,
        val next: Map<String, List<String>>,
        val follows: Map<String, Set<String>>
    )

    @Volatile
    private var rules = Rules(DEFAULT_WEIGHTS, emptyMap(), emptyMap())

    fun load(json: String) {
        // 解析失败时保留上一份有效规则表，并记录日志便于诊断；坏条目用 opt* 容忍
        try {
            val root = JSONObject(json)
            val weightMap = HashMap(DEFAULT_WEIGHTS)
            root.optJSONObject("weights")?.let { table ->
                val keys = table.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    weightMap[key] = table.optInt(key, weightMap[key] ?: 0)
                }
            }
            val nextMap = HashMap<String, List<String>>()
            // follows 不是独立数据源：它在同一循环里从与 nextMap 相同的 words 派生（仅多了 Set 形态），
            // 供束搜索做 O(1) 成员判定，与 nextWords 的有序 List 共用同一份解析结果，不会漂移。
            val followMap = HashMap<String, Set<String>>()
            root.optJSONObject("next")?.let { table ->
                val keys = table.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val list = table.optJSONArray(key) ?: continue
                    val words = ArrayList<String>(list.length())
                    for (i in 0 until list.length()) {
                        val word = list.optString(i)
                        if (word.isNotEmpty()) words.add(word)
                    }
                    nextMap[key] = words
                    followMap[key] = words.toHashSet()
                }
            }
            rules = Rules(weightMap, nextMap, followMap)
        } catch (e: Exception) {
            Log.w("ImeRules", "ime-rules.json 解析失败，保留上一份规则", e)
        }
    }

    // weights 始终以 DEFAULT_WEIGHTS 的完整副本为底再叠加 json，每个默认键必存在；
    // 未知键两边都取不到，回退到 0 即可（无需再查 DEFAULT_WEIGHTS）。
    fun weight(name: String): Int = rules.weights[name] ?: 0

    /** 束搜索里用：上一词是 prev 时，哪些后续词算搭配。 */
    fun follows(prev: String): Set<String>? = rules.follows[prev]

    /** 联想用：上屏「我想」后给 要 / 你 / 去；整词没有搭配时退到末字。 */
    fun nextWords(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        val table = rules.next
        table[word]?.let { if (it.isNotEmpty()) return it }
        if (word.length > 1) {
            table[word.takeLast(1)]?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }
}
