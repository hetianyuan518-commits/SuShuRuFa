package com.susheng.ime.ui

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.susheng.ime.data.ContentRepository
import com.susheng.ime.skin.KeyboardPrefs
import com.susheng.ime.skin.KeyboardSkin
import com.susheng.ime.skin.KeyboardSkins
import com.susheng.ime.skin.SkinPreview
import com.susheng.ime.skin.SkinSet

internal enum class HomeTab { STORE, PHRASES, MINE }

internal fun homePage(
    activity: AppCompatActivity,
    repo: ContentRepository,
    tab: HomeTab,
    onTab: (HomeTab) -> Unit,
    onApply: (Int) -> Unit,
    onSettings: () -> Unit,
    onDoc: (String) -> Unit
): View {
    val root = vcol(activity, PAGE)
    val page = when (tab) {
        HomeTab.STORE -> storePage(activity, onApply)
        HomeTab.PHRASES -> phrasePage(activity, repo, onSettings)
        HomeTab.MINE -> minePage(activity, repo, onApply, onSettings, onDoc)
    }
    root.addView(page, weight())
    root.addView(bottomNav(activity, tab, onTab))
    return root
}

private fun storePage(activity: AppCompatActivity, onApply: (Int) -> Unit): View {
    val root = vcol(activity, PAGE)
    var showSets = false
    var query = ""
    val grid = vcol(activity).apply { setPadding(dpi(activity, 16), 0, dpi(activity, 16), dpi(activity, 20)) }
    fun refill() {
        grid.removeAllViews()
        if (showSets) fillSets(activity, grid, query, onApply) else fillSkins(activity, grid, query, onApply)
    }
    val header = vcol(activity).apply { setPadding(dpi(activity, 16), dpi(activity, 16), dpi(activity, 16), dpi(activity, 8)) }
    val top = vrow(activity)
    top.addView(tv(activity, "装扮商城", 22f, INK, true), LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    val search = EditText(activity).apply {
        hint = "搜索皮肤"
        textSize = 14f
        setSingleLine(true)
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 18f))
        setPadding(dpi(activity, 14), dpi(activity, 8), dpi(activity, 14), dpi(activity, 8))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dpi(activity, 12) }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                // 修剪后的查询未变化时跳过 refill，避免每次敲键都重建全部卡片。
                val next = s?.toString()?.trim().orEmpty()
                if (next == query) return
                query = next
                refill()
            }
        })
    }
    top.addView(search)
    header.addView(top)
    header.addView(gap(activity, 14))
    val tabs = vrow(activity)
    val skinTab = tabLabel(activity, "皮肤", true)
    val setTab = tabLabel(activity, "套装", false)
    skinTab.setOnClickListener {
        showSets = false
        markTab(skinTab, true)
        markTab(setTab, false)
        refill()
    }
    setTab.setOnClickListener {
        showSets = true
        markTab(skinTab, false)
        markTab(setTab, true)
        refill()
    }
    tabs.addView(skinTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    tabs.addView(setTab, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    header.addView(tabs)
    root.addView(header)
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    scroll.addView(grid)
    root.addView(scroll, weight())
    refill()
    return root
}

private fun tabLabel(activity: AppCompatActivity, text: String, selected: Boolean) =
    tv(activity, text, 16f, if (selected) ORANGE else MUTED, selected).apply { gravity = Gravity.CENTER; setPadding(0, dpi(activity, 8), 0, dpi(activity, 8)) }

private fun markTab(view: TextView, selected: Boolean) {
    view.setTextColor(if (selected) ORANGE else MUTED)
    view.setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
}

private fun fillSkins(activity: AppCompatActivity, grid: LinearLayout, query: String, onApply: (Int) -> Unit) {
    val items = KeyboardSkins.skins.filter { query.isEmpty() || it.name.contains(query) || it.blurb.contains(query) }
    fillCards(activity, grid, items, "没有找到这套皮肤") { skinCard(activity, it, onApply) }
}

private fun fillSets(activity: AppCompatActivity, grid: LinearLayout, query: String, onApply: (Int) -> Unit) {
    val items = KeyboardSkins.sets.filter { query.isEmpty() || it.name.contains(query) || it.blurb.contains(query) }
    fillCards(activity, grid, items, "没有找到这套装扮") { setCard(activity, it, onApply) }
}

// 皮肤/套装共用同一套两列网格构建逻辑，仅数据源、空态文案和卡片工厂不同。
private fun <T> fillCards(
    activity: AppCompatActivity,
    grid: LinearLayout,
    items: List<T>,
    emptyText: String,
    cardFactory: (T) -> View
) {
    if (items.isEmpty()) {
        grid.addView(tv(activity, emptyText, 14f, MUTED).apply { setPadding(0, dpi(activity, 24), 0, 0) })
        return
    }
    items.chunked(2).forEach { pair ->
        val line = vrow(activity)
        pair.forEachIndexed { index, item ->
            line.addView(cardFactory(item), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index == 0) marginEnd = dpi(activity, 6) else marginStart = dpi(activity, 6)
                bottomMargin = dpi(activity, 12)
            })
        }
        if (pair.size == 1) line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        grid.addView(line)
    }
}

private fun skinCard(activity: AppCompatActivity, skin: KeyboardSkin, onApply: (Int) -> Unit): View {
    val current = KeyboardPrefs.skinId(activity) == skin.id
    val card = vcol(activity).apply {
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 16f))
        setPadding(dpi(activity, 10), dpi(activity, 10), dpi(activity, 10), dpi(activity, 12))
        elevation = dpf(activity, 1f)
        setOnClickListener { onApply(skin.id) }
    }
    card.addView(SkinPreview(activity, skin))
    card.addView(tv(activity, skin.name, 15f, INK, true).apply { setPadding(dpi(activity, 4), dpi(activity, 10), dpi(activity, 4), 0) })
    card.addView(tv(activity, skin.blurb, 12f, MUTED).apply { setPadding(dpi(activity, 4), dpi(activity, 4), dpi(activity, 4), dpi(activity, 8)) })
    card.addView(useButton(activity, current) { onApply(skin.id) })
    return card
}

private fun setCard(activity: AppCompatActivity, set: SkinSet, onApply: (Int) -> Unit): View {
    val skin = KeyboardSkins.byId(set.skinId)
    val current = KeyboardPrefs.skinId(activity) == set.skinId
    val card = vcol(activity).apply {
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 16f))
        setPadding(dpi(activity, 10), dpi(activity, 10), dpi(activity, 10), dpi(activity, 12))
        elevation = dpf(activity, 1f)
        setOnClickListener { onApply(set.skinId) }
    }
    card.addView(SkinPreview(activity, skin))
    card.addView(tv(activity, set.name, 15f, INK, true).apply { setPadding(dpi(activity, 4), dpi(activity, 10), dpi(activity, 4), 0) })
    card.addView(tv(activity, set.blurb, 12f, MUTED).apply { setPadding(dpi(activity, 4), dpi(activity, 4), dpi(activity, 4), dpi(activity, 8)) })
    card.addView(useButton(activity, current) { onApply(set.skinId) })
    return card
}

private fun useButton(activity: AppCompatActivity, current: Boolean, click: () -> Unit): TextView {
    val label = if (current) "使用中" else "免费使用"
    val bg = if (current) 0xFFFFF1E8.toInt() else ORANGE
    val fg = if (current) ORANGE else 0xFFFFFFFF.toInt()
    return tv(activity, label, 13f, fg, true).apply {
        gravity = Gravity.CENTER
        background = rounded(bg, dpf(activity, 14f))
        setPadding(0, dpi(activity, 8), 0, dpi(activity, 8))
        setOnClickListener { click() }
    }
}

private val PHRASE_TYPES = setOf("script", "tweet", "promo")

// 判定是否为本机/模拟器回环地址（开发调试用），这些地址允许明文 http。
private fun isLoopbackHost(host: String): Boolean {
    val authority = host.removePrefix("http://").removePrefix("https://").substringBefore('/')
    return when (authority.substringBefore(':')) {
        "10.0.2.2", "localhost", "127.0.0.1" -> true
        else -> false
    }
}

private fun phrasePage(activity: AppCompatActivity, repo: ContentRepository, onSettings: () -> Unit): View {
    val root = vcol(activity, PAGE)
    root.addView(tv(activity, "语录", 22f, INK, true).apply { setPadding(dpi(activity, 20), dpi(activity, 18), dpi(activity, 20), dpi(activity, 8)) })
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    val box = vcol(activity).apply { setPadding(dpi(activity, 16), 0, dpi(activity, 16), dpi(activity, 20)) }
    val items = repo.items.filter { it.type in PHRASE_TYPES && !it.deleted && it.status == "published" }
    if (items.isEmpty()) {
        box.addView(tv(activity, "登录并同步后，这里会出现公司话术、推文和宣发。", 14f, MUTED).apply {
            setPadding(dpi(activity, 4), dpi(activity, 12), dpi(activity, 4), dpi(activity, 16))
            setLineSpacing(dpf(activity, 3f), 1f)
        })
        box.addView(pill(activity, "去设置登录", ORANGE, 0xFFFFFFFF.toInt(), onSettings))
    } else {
        items.forEach { item ->
            val card = vcol(activity).apply {
                background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 14f))
                setPadding(dpi(activity, 14), dpi(activity, 12), dpi(activity, 14), dpi(activity, 12))
                layoutParams = fill().apply { bottomMargin = dpi(activity, 10) }
            }
            card.addView(tv(activity, item.title.ifBlank { item.body }.take(40), 15f, INK, true))
            if (item.body.isNotBlank() && item.body != item.title) {
                card.addView(tv(activity, item.body.take(80), 13f, MUTED).apply { setPadding(0, dpi(activity, 6), 0, 0) })
            }
            box.addView(card)
        }
    }
    scroll.addView(box)
    root.addView(scroll, weight())
    return root
}

private fun minePage(
    activity: AppCompatActivity,
    repo: ContentRepository,
    onApply: (Int) -> Unit,
    onSettings: () -> Unit,
    onDoc: (String) -> Unit
): View {
    val root = vcol(activity, PAGE)
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    val box = vcol(activity).apply { setPadding(dpi(activity, 16), dpi(activity, 16), dpi(activity, 16), dpi(activity, 20)) }
    val name = repo.session()?.optString("displayName").orEmpty().ifBlank { "苏晟用户" }
    val current = KeyboardSkins.byId(KeyboardPrefs.skinId(activity))
    val head = vrow(activity).apply {
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 16f))
        setPadding(dpi(activity, 16), dpi(activity, 16), dpi(activity, 16), dpi(activity, 16))
    }
    head.addView(logoView(activity, 44))
    val titles = vcol(activity).apply { setPadding(dpi(activity, 12), 0, 0, 0) }
    titles.addView(tv(activity, name, 18f, INK, true))
    titles.addView(tv(activity, "当前皮肤 · ${current.name}", 13f, MUTED).apply { setPadding(0, dpi(activity, 4), 0, 0) })
    head.addView(titles)
    box.addView(head)
    box.addView(tv(activity, "已拥有的皮肤", 16f, INK, true).apply { setPadding(dpi(activity, 4), dpi(activity, 18), 0, dpi(activity, 10)) })
    val owned = KeyboardSkins.skins.filter { it.id in KeyboardPrefs.owned(activity) }
    owned.chunked(2).forEach { pair ->
        val line = vrow(activity)
        pair.forEachIndexed { index, skin ->
            line.addView(skinCard(activity, skin, onApply), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (index == 0) marginEnd = dpi(activity, 6) else marginStart = dpi(activity, 6)
                bottomMargin = dpi(activity, 12)
            })
        }
        if (pair.size == 1) line.addView(View(activity), LinearLayout.LayoutParams(0, 1, 1f))
        box.addView(line)
    }
    box.addView(gap(activity, 6))
    box.addView(settingRow(activity, "设置", "账号、同步、试打和振动", onSettings))
    box.addView(gap(activity, 8))
    box.addView(settingRow(activity, "用户服务协议", "", { onDoc("用户服务协议") }))
    box.addView(gap(activity, 8))
    box.addView(settingRow(activity, "隐私政策", "", { onDoc("隐私政策") }))
    scroll.addView(box)
    root.addView(scroll, weight())
    return root
}

private fun settingRow(activity: AppCompatActivity, title: String, subtitle: String, click: () -> Unit): View {
    val row = vrow(activity).apply {
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 14f))
        setPadding(dpi(activity, 16), dpi(activity, 14), dpi(activity, 16), dpi(activity, 14))
        setOnClickListener { click() }
    }
    val text = vcol(activity)
    text.addView(tv(activity, title, 16f, INK))
    if (subtitle.isNotEmpty()) text.addView(tv(activity, subtitle, 12f, MUTED).apply { setPadding(0, dpi(activity, 4), 0, 0) })
    row.addView(text, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    row.addView(tv(activity, "›", 20f, MUTED))
    return row
}

private fun bottomNav(activity: AppCompatActivity, tab: HomeTab, onTab: (HomeTab) -> Unit): View {
    val bar = vrow(activity, 0xFFFFFFFF.toInt()).apply {
        setPadding(0, dpi(activity, 8), 0, dpi(activity, 10))
        elevation = dpf(activity, 6f)
    }
    listOf(HomeTab.STORE to "商城", HomeTab.PHRASES to "语录", HomeTab.MINE to "我的").forEach { (item, label) ->
        val selected = item == tab
        val cell = tv(activity, label, 14f, if (selected) ORANGE else MUTED, selected).apply {
            gravity = Gravity.CENTER
            setPadding(0, dpi(activity, 8), 0, dpi(activity, 8))
            setOnClickListener { onTab(item) }
        }
        bar.addView(cell, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }
    return bar
}

internal fun settingsPage(
    activity: AppCompatActivity,
    repo: ContentRepository,
    onBack: () -> Unit,
    onEnableIme: () -> Unit,
    onSwitchIme: () -> Unit,
    onDoc: (String) -> Unit
): View {
    val root = vcol(activity, PAGE)
    val bar = vrow(activity).apply { setPadding(dpi(activity, 8), dpi(activity, 12), dpi(activity, 16), dpi(activity, 4)) }
    bar.addView(tv(activity, "‹", 28f, INK).apply {
        setPadding(dpi(activity, 12), 0, dpi(activity, 12), 0)
        setOnClickListener { onBack() }
    })
    bar.addView(tv(activity, "设置", 18f, INK, true))
    root.addView(bar)
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    val box = vcol(activity).apply { setPadding(dpi(activity, 16), dpi(activity, 8), dpi(activity, 16), dpi(activity, 24)) }
    val session = repo.session()
    val status = tv(activity, session?.let { "${it.optString("displayName")} · 本地版本 ${repo.serverVersion} · ${repo.items.size} 条" } ?: "尚未登录", 13f, MUTED)
    val server = field(activity, "内容台地址", session?.optString("server") ?: "http://10.0.2.2:8787", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
    val username = field(activity, "账号", "", InputType.TYPE_CLASS_TEXT)
    val password = field(activity, "密码", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
    box.addView(status)
    box.addView(gap(activity, 8))
    box.addView(server)
    box.addView(username)
    box.addView(password)
    box.addView(gap(activity, 8))
    box.addView(pill(activity, "登录", ORANGE, 0xFFFFFFFF.toInt()) {
        val host = server.text.toString().trim()
        // 非 TLS 主机拒绝提交凭据，避免账号/密码明文出网；仅放行本机/模拟器回环地址用于开发调试。
        if (!host.startsWith("https://") && !isLoopbackHost(host)) {
            status.text = "为保护账号安全，请填写 https:// 地址（本机调试地址除外）"
            return@pill
        }
        status.text = "正在登录"
        val user = username.text.toString()
        val pass = password.text.toString()
        Thread {
            val message = runCatching { repo.login(host, user, pass) }.getOrElse { it.message ?: "登录失败" }
            if (activity.isFinishing || activity.isDestroyed) return@Thread
            activity.runOnUiThread { status.text = message }
        }.start()
    })
    box.addView(gap(activity, 10))
    box.addView(pill(activity, "同步内容包", 0xFFFFF1E8.toInt(), ORANGE) {
        status.text = "正在同步"
        Thread {
            val message = runCatching {
                val result = repo.sync()
                repo.loadLocal()
                result
            }.getOrElse { it.message ?: "同步失败" }
            if (activity.isFinishing || activity.isDestroyed) return@Thread
            activity.runOnUiThread { status.text = "$message · ${repo.items.size} 条" }
        }.start()
    })
    box.addView(gap(activity, 16))
    box.addView(pill(activity, "去系统里启用输入法", 0xFFFFFFFF.toInt(), INK, onEnableIme))
    box.addView(gap(activity, 10))
    box.addView(pill(activity, "切换到苏晟输入法", 0xFFFFFFFF.toInt(), INK, onSwitchIme))
    box.addView(gap(activity, 8))
    box.addView(tv(activity, ImeSystem.manualHint(), 12f, MUTED).apply {
        setLineSpacing(dpf(activity, 3f), 1f)
    })
    box.addView(gap(activity, 16))
    box.addView(tv(activity, "在这里试打，确认皮肤和联想", 13f, MUTED))
    box.addView(field(activity, "试试 nihao、tishici", "", InputType.TYPE_CLASS_TEXT))
    val haptic = Switch(activity).apply {
        text = "按键振动"
        textSize = 16f
        setTextColor(INK)
        isChecked = KeyboardPrefs.prefs(activity).getBoolean("haptic", true)
        setPadding(dpi(activity, 4), dpi(activity, 12), dpi(activity, 4), dpi(activity, 12))
        setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            KeyboardPrefs.prefs(activity).edit().putBoolean("haptic", checked).apply()
        }
    }
    box.addView(haptic)
    box.addView(tv(activity, "输入法不上传你敲下的字。", 13f, MUTED).apply { setPadding(0, dpi(activity, 8), 0, dpi(activity, 12)) })
    box.addView(settingRow(activity, "查看隐私政策", "", { onDoc("隐私政策") }))
    scroll.addView(box)
    root.addView(scroll, weight())
    return root
}

private fun field(activity: AppCompatActivity, hint: String, value: String, input: Int): EditText {
    return EditText(activity).apply {
        this.hint = hint
        setText(value)
        inputType = input
        setTextColor(INK)
        setHintTextColor(MUTED)
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 12f))
        setPadding(dpi(activity, 14), dpi(activity, 12), dpi(activity, 14), dpi(activity, 12))
        layoutParams = fill().apply { topMargin = dpi(activity, 8) }
    }
}
