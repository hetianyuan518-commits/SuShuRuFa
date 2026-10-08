package com.susheng.ime.ui

import android.content.Context
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

internal fun guidePage(activity: AppCompatActivity, onEnable: () -> Unit, onSwitch: () -> Unit): View {
    val root = vcol(activity, PAGE)
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    val body = vcol(activity).apply { setPadding(dpi(activity, 20), dpi(activity, 28), dpi(activity, 20), dpi(activity, 12)) }
    body.addView(tv(activity, "将「苏晟输入法」设为「默认输入法」", 22f, INK, true).apply {
        setLineSpacing(dpf(activity, 4f), 1f)
    })
    body.addView(gap(activity, 18))
    body.addView(guideCard(activity, onSwitch))
    scroll.addView(body)
    root.addView(scroll, weight())
    val actions = vcol(activity).apply { setPadding(dpi(activity, 28), dpi(activity, 8), dpi(activity, 28), dpi(activity, 28)) }
    actions.addView(pill(activity, "第一步  启用苏晟输入法", 0xFFE4E6EA.toInt(), 0xFF9AA0A6.toInt(), onEnable))
    actions.addView(gap(activity, 12))
    actions.addView(pill(activity, "第二步  切换到苏晟输入法", ORANGE, 0xFFFFFFFF.toInt(), onSwitch))
    actions.addView(gap(activity, 10))
    actions.addView(tv(activity, ImeSystem.manualHint(), 12f, MUTED).apply {
        setLineSpacing(dpf(activity, 3f), 1f)
    })
    root.addView(actions)
    return root
}

private fun guideCard(activity: AppCompatActivity, onSwitch: () -> Unit): View {
    val card = vcol(activity, 0xFFFFFFFF.toInt()).apply {
        background = rounded(0xFFFFFFFF.toInt(), dpf(activity, 18f))
        setPadding(dpi(activity, 18), dpi(activity, 16), dpi(activity, 18), dpi(activity, 8))
    }
    card.addView(tv(activity, "输入法管理", 13f, MUTED))
    val head = vrow(activity).apply {
        setPadding(0, dpi(activity, 16), 0, dpi(activity, 16))
        setOnClickListener { onSwitch() }
    }
    head.addView(tv(activity, "默认输入法", 16f, INK), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    head.addView(tv(activity, currentImeName(activity), 15f, MUTED))
    head.addView(tv(activity, "  ▾", 14f, MUTED))
    card.addView(head)
    card.addView(View(activity).apply {
        setBackgroundColor(LINE)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(activity, 1))
    })
    enabledImes(activity).forEach { name ->
        val row = vrow(activity).apply { setPadding(0, dpi(activity, 14), 0, dpi(activity, 14)) }
        row.addView(tv(activity, "✓", 16f, 0xFF2F7FE0.toInt()).apply { setPadding(0, 0, dpi(activity, 10), 0) })
        row.addView(tv(activity, name, 16f, INK))
        card.addView(row)
    }
    return card
}

private fun currentImeName(activity: AppCompatActivity): String {
    val current = Settings.Secure.getString(activity.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    val imm = activity.getSystemService(InputMethodManager::class.java)
    val match = imm.enabledInputMethodList.firstOrNull { it.id == current }
    return match?.loadLabel(activity.packageManager)?.toString() ?: "未选择"
}

private fun enabledImes(activity: AppCompatActivity): List<String> {
    val imm = activity.getSystemService(InputMethodManager::class.java)
    val names = imm.enabledInputMethodList.map { it.loadLabel(activity.packageManager).toString() }
    return if (names.isEmpty()) listOf("苏晟输入法") else names
}

internal fun privacyPage(activity: AppCompatActivity, onAgree: () -> Unit, onReject: () -> Unit, onDoc: (String) -> Unit): View {
    val root = vcol(activity, 0xFFFFFFFF.toInt())
    val scroll = ScrollView(activity).apply { isVerticalScrollBarEnabled = false }
    val body = vcol(activity).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dpi(activity, 24), dpi(activity, 36), dpi(activity, 24), dpi(activity, 12))
    }
    body.addView(logoView(activity, 72).apply {
        (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.CENTER_HORIZONTAL
    })
    body.addView(gap(activity, 18))
    body.addView(tv(activity, "欢迎您体验", 15f, MUTED).apply { gravity = Gravity.CENTER })
    body.addView(gap(activity, 8))
    body.addView(tv(activity, "苏晟输入法", 26f, INK, true).apply { gravity = Gravity.CENTER })
    body.addView(gap(activity, 16))
    val links = LegalCopy.titles.map { title -> "《$title》" to { onDoc(title) } }
    val intro = "请阅读${LegalCopy.titles.joinToString("") { "《$it》" }}。同意后进入首页。苏晟输入法不上传你敲下的文字。"
    body.addView(linkedParagraph(activity, intro, links))
    body.addView(gap(activity, 16))
    body.addView(noticeCard(activity, "同意上述条款，进入苏晟输入法", "登录后同步公司发布的话术、词条和图片。使用话术时只记录条目编号和场景。你敲下的字留在本机。"))
    body.addView(gap(activity, 12))
    body.addView(noticeCard(activity, "拒绝上述条款，退出应用", "未同意时不进入首页。已经在系统里启用的键盘仍可在本机打字，同样不会上传你敲下的字。"))
    scroll.addView(body)
    root.addView(scroll, weight())
    val actions = vrow(activity).apply { setPadding(dpi(activity, 20), dpi(activity, 8), dpi(activity, 20), dpi(activity, 24)) }
    actions.addView(pill(activity, "拒绝", 0xFFF0F1F4.toInt(), MUTED, onReject), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    actions.addView(View(activity), LinearLayout.LayoutParams(dpi(activity, 12), 1))
    actions.addView(pill(activity, "同意", ORANGE, 0xFFFFFFFF.toInt(), onAgree), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    root.addView(actions)
    return root
}

private fun noticeCard(context: Context, title: String, body: String): View {
    val card = vcol(context).apply {
        background = rounded(0xFFF7F8FA.toInt(), dpf(context, 16f))
        setPadding(dpi(context, 16), dpi(context, 14), dpi(context, 16), dpi(context, 14))
    }
    card.addView(tv(context, title, 16f, INK, true))
    card.addView(tv(context, body, 13f, MUTED).apply {
        setPadding(0, dpi(context, 8), 0, 0)
        setLineSpacing(dpf(context, 3f), 1f)
    })
    return card
}

internal fun legalPage(activity: AppCompatActivity, title: String, onBack: () -> Unit): View {
    val root = vcol(activity, 0xFFFFFFFF.toInt())
    val bar = vrow(activity).apply { setPadding(dpi(activity, 8), dpi(activity, 12), dpi(activity, 16), dpi(activity, 12)) }
    bar.addView(tv(activity, "‹", 28f, INK).apply {
        setPadding(dpi(activity, 12), 0, dpi(activity, 12), 0)
        setOnClickListener { onBack() }
    })
    bar.addView(tv(activity, title, 18f, INK, true))
    root.addView(bar)
    val scroll = ScrollView(activity)
    scroll.addView(tv(activity, LegalCopy.body(title), 15f, INK).apply {
        setPadding(dpi(activity, 20), dpi(activity, 8), dpi(activity, 20), dpi(activity, 28))
        setLineSpacing(dpf(activity, 5f), 1f)
    })
    root.addView(scroll, weight())
    return root
}
