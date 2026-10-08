package com.susheng.ime.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.susheng.ime.R

internal const val PAGE = 0xFFF4F5F7.toInt()
internal const val INK = 0xFF222222.toInt()
internal const val MUTED = 0xFF8A9099.toInt()
internal const val ORANGE = 0xFFFF6A00.toInt()
internal const val LINE = 0xFFE6E8EC.toInt()

internal fun dpf(context: Context, value: Float) = value * context.resources.displayMetrics.density

internal fun dpi(context: Context, value: Int) = dpf(context, value.toFloat()).toInt()

internal fun rounded(color: Int, radius: Float): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

internal fun vcol(context: Context, color: Int = 0): LinearLayout = MatchColumn(context).apply {
    if (color != 0) setBackgroundColor(color)
}

internal fun vrow(context: Context, color: Int = 0): LinearLayout = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    if (color != 0) setBackgroundColor(color)
}

internal fun tv(context: Context, text: String, size: Float, color: Int, bold: Boolean = false): TextView {
    return TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }
}

internal fun fill(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

internal fun weight(): LinearLayout.LayoutParams =
    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)

internal fun logoView(context: Context, sizeDp: Int): ImageView {
    return ImageView(context).apply {
        setImageResource(R.drawable.susheng_logo)
        scaleType = ImageView.ScaleType.FIT_CENTER
        contentDescription = "苏晟"
        layoutParams = LinearLayout.LayoutParams(dpi(context, sizeDp), dpi(context, sizeDp))
    }
}

internal fun gap(context: Context, height: Int) = View(context).apply {
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpi(context, height))
}

internal fun pill(context: Context, text: String, bg: Int, fg: Int, click: () -> Unit): TextView {
    return tv(context, text, 16f, fg, true).apply {
        gravity = Gravity.CENTER
        background = rounded(bg, dpf(context, 24f))
        setPadding(dpi(context, 16), dpi(context, 14), dpi(context, 16), dpi(context, 14))
        setOnClickListener { click() }
    }
}

internal fun linkText(full: String, labels: List<Pair<String, () -> Unit>>, color: Int): SpannableString {
    val span = SpannableString(full)
    labels.forEach { (label, action) ->
        val start = full.indexOf(label)
        if (start < 0) return@forEach
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) = action()
            override fun updateDrawState(ds: TextPaint) {
                ds.color = color
                ds.isUnderlineText = false
            }
        }, start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
    }
    return span
}

private class MatchColumn(context: Context) : LinearLayout(context) {
    init { orientation = VERTICAL }
    override fun generateDefaultLayoutParams() = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
}

internal fun linkedParagraph(context: Context, full: String, labels: List<Pair<String, () -> Unit>>): TextView {
    return tv(context, full, 14f, 0xFF5C6370.toInt()).apply {
        text = linkText(full, labels, ORANGE)
        movementMethod = LinkMovementMethod.getInstance()
        highlightColor = 0x00000000
        setLineSpacing(dpf(context, 4f), 1f)
    }
}
