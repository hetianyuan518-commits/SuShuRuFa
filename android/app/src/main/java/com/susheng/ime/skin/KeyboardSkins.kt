package com.susheng.ime.skin

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

data class KeyboardSkin(
    val id: Int,
    val name: String,
    val blurb: String,
    val bg: Int,
    val key: Int,
    val fn: Int,
    val text: Int,
    val sub: Int,
    val accent: Int,
    val card: Int,
    val muted: Int
)

data class SkinSet(val name: String, val blurb: String, val skinId: Int)

object KeyboardSkins {
    val skins = listOf(
        KeyboardSkin(0, "浅灰日常", "白键橙字，适合每天打字", 0xFFF3F4F6.toInt(), 0xFFFFFFFF.toInt(), 0xFFE3E5EA.toInt(), 0xFF222222.toInt(), 0xFF9AA0A8.toInt(), 0xFFFF6A00.toInt(), 0xFFFFFFFF.toInt(), 0xFF8B9098.toInt()),
        KeyboardSkin(1, "宣纸朱红", "纸色底，朱红点缀", 0xFFF3EADB.toInt(), 0xFFFFFCF7.toInt(), 0xFFE7D9C8.toInt(), 0xFF1B140F.toInt(), 0xFF8A7368.toInt(), 0xFFC4372C.toInt(), 0xFFFFFCF7.toInt(), 0xFF8A7368.toInt()),
        KeyboardSkin(2, "新叶", "清浅绿色按键", 0xFFE7F6E4.toInt(), 0xFFFFFFFF.toInt(), 0xFFD5EBD0.toInt(), 0xFF1C3A24.toInt(), 0xFF6E8F78.toInt(), 0xFF2E9B4A.toInt(), 0xFFFFFFFF.toInt(), 0xFF6E8F78.toInt()),
        KeyboardSkin(3, "夜航", "深色键盘，适合夜间口播", 0xFF1C1E27.toInt(), 0xFF2C3140.toInt(), 0xFF232733.toInt(), 0xFFF2F4F8.toInt(), 0xFF9AA3B5.toInt(), 0xFFFF8A3D.toInt(), 0xFF2C3140.toInt(), 0xFF8B93A7.toInt()),
        KeyboardSkin(4, "海盐", "浅蓝底，适合白天发布", 0xFFE7F2FA.toInt(), 0xFFFFFFFF.toInt(), 0xFFD5E6F4.toInt(), 0xFF16324A.toInt(), 0xFF6D88A0.toInt(), 0xFF2F7FE0.toInt(), 0xFFFFFFFF.toInt(), 0xFF6D88A0.toInt()),
        KeyboardSkin(5, "暖杏", "暖色按键，适合欢迎和感谢", 0xFFFDEFE3.toInt(), 0xFFFFFAF6.toInt(), 0xFFF6E0D0.toInt(), 0xFF4A2E22.toInt(), 0xFFA07A68.toInt(), 0xFFE36A2E.toInt(), 0xFFFFFAF6.toInt(), 0xFFA07A68.toInt())
    )

    val sets = listOf(
        SkinSet("直播暖场", "宣纸朱红，适合开场和欢迎语", 1),
        SkinSet("夜场口播", "夜航深色，适合夜间直播", 3),
        SkinSet("清新日常", "新叶配色，适合白天发内容", 2),
        SkinSet("海盐发布", "海盐配色，适合评论和标题", 4)
    )

    fun byId(id: Int): KeyboardSkin = skins.firstOrNull { it.id == id } ?: skins[0]
}

object KeyboardPrefs {
    const val NAME = "susheng-keyboard"

    fun prefs(context: Context) = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun accepted(context: Context) = prefs(context).getBoolean("privacy", false)

    fun accept(context: Context) {
        prefs(context).edit().putBoolean("privacy", true).putBoolean("await_privacy", false).apply()
    }

    fun awaitPrivacy(context: Context) = prefs(context).getBoolean("await_privacy", false)

    fun markAwaitPrivacy(context: Context) {
        prefs(context).edit().putBoolean("await_privacy", true).apply()
    }

    fun clearAwaitPrivacy(context: Context) {
        prefs(context).edit().putBoolean("await_privacy", false).apply()
    }

    fun skinId(context: Context) = prefs(context).getInt("skin", 0)

    fun owned(context: Context): Set<Int> {
        val raw = prefs(context).getStringSet("owned_skins", setOf("0")).orEmpty()
        val ids = raw.mapNotNull { it.toIntOrNull() }.toMutableSet()
        if (ids.isEmpty()) ids.add(0)
        return ids
    }

    fun applySkin(context: Context, id: Int) {
        val next = owned(context).map { it.toString() }.toMutableSet()
        next.add(id.toString())
        prefs(context).edit().putInt("skin", id).putStringSet("owned_skins", next).apply()
    }
}

class SkinPreview(context: Context, private val skin: KeyboardSkin) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, (width * 0.62f).toInt().coerceAtLeast(1))
    }

    override fun onDraw(canvas: Canvas) {
        val radius = width * 0.06f
        paint.color = skin.bg
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, paint)
        val pad = width * 0.05f
        val gap = width * 0.012f
        val counts = intArrayOf(10, 9, 7)
        val rowH = (height - pad * 2 - gap * 2) / 3f
        counts.forEachIndexed { row, count ->
            val keyW = (width - pad * 2 - gap * (count - 1)) / count
            val y = pad + row * (rowH + gap)
            for (index in 0 until count) {
                val x = pad + index * (keyW + gap)
                paint.color = if (row == 2 && (index == 0 || index == count - 1)) skin.fn else skin.key
                canvas.drawRoundRect(x, y, x + keyW, y + rowH, keyW * 0.18f, keyW * 0.18f, paint)
            }
        }
    }
}
