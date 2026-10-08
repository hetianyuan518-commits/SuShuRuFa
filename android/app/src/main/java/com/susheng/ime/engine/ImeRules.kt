package com.susheng.ime.engine

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

    @Volatile
    private var weights: Map<String, Int> = DEFAULT_WEIGHTS

    @Volatile
    private var next: Map<String, List<String>> = emptyMap()

    @Volatile
    private var follows: Map<String, Set<String>> = emptyMap()

    fun load(json: String) {
        val root = JSONObject(json)
        val weightMap = HashMap<String, Int>(DEFAULT_WEIGHTS)
        root.optJSONObject("weights")?.let { table ->
            val keys = table.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                weightMap[key] = table.getInt(key)
            }
        }
        val nextMap = HashMap<String, List<String>>()
        val followMap = HashMap<String, Set<String>>()
        root.optJSONObject("next")?.let { table ->
            val keys = table.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val list = table.getJSONArray(key)
                val words = ArrayList<String>(list.length())
                for (i in 0 until list.length()) words.add(list.getString(i))
                nextMap[key] = words
                followMap[key] = words.toHashSet()
            }
        }
        weights = weightMap
        next = nextMap
        follows = followMap
    }

    fun weight(name: String): Int = weights[name] ?: DEFAULT_WEIGHTS[name] ?: 0

    /** 束搜索里用：上一词是 prev 时，哪些后续词算搭配。 */
    fun follows(prev: String): Set<String>? = follows[prev]

    /** 联想用：上屏「我想」后给 要 / 你 / 去；整词没有搭配时退到末字。 */
    fun nextWords(word: String): List<String> {
        if (word.isEmpty()) return emptyList()
        next[word]?.let { if (it.isNotEmpty()) return it }
        if (word.length > 1) {
            next[word.takeLast(1)]?.let { if (it.isNotEmpty()) return it }
        }
        return emptyList()
    }
}
