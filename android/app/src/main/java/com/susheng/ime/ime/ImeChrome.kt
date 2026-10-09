package com.susheng.ime.ime

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.susheng.ime.R
import com.susheng.ime.engine.ContentItem
import com.susheng.ime.engine.StrokeTable
import com.susheng.ime.skin.KeyboardSkins

internal enum class Face { KEYS, HUB, EMOJI, CLIP, PHRASES, OPTIONS, BOARDS }

internal enum class Board(val title: String, val id: String) {
    PINYIN9("拼音9键", "p9"),
    PINYIN26("拼音26键", "p26"),
    HAND("手写", "hand"),
    STROKE("笔画", "stroke"),
    ENGLISH("英文26键", "en"),
    WUBI("五笔", "wubi");

    companion object {
        fun from(id: String) = entries.firstOrNull { it.id == id } ?: PINYIN9
    }
}

internal class ImeChrome(private val ime: SushengInputMethodService) {
    var face = Face.KEYS
    var symbolPage = false
    var numberPage = false
    var searching = false
    var query = ""
    var emojiTab = 0
    var emojiCat = 1
    var clipTab = 0
    var showClipBanner = true
    var phraseTitle = "常用语"
    var phraseTypes = setOf("script", "tweet", "promo")
    var optionTitle = ""
    var optionLabels = listOf<String>()
    var optionSelected = 0
    var optionPick: (Int) -> Unit = {}
    private var handPad: HandPad? = null

    fun clearHand() {
        handPad?.clear()
    }

    fun handHasInk() = handPad?.hasInk() == true

    fun handToken() = handPad?.token ?: -1

    private var candidateBar: LinearLayout? = null
    private var composingLine: TextView? = null
    private var candidateRow: LinearLayout? = null
    private val chipPool = ArrayList<TextView>()
    private var shownRaw = ""
    private var candidateGeneration = 0
    private val density get() = ime.resources.displayMetrics.density
    private fun px(value: Int) = (value * density).toInt()
    private fun px(value: Float) = (value * density).toInt()

    private fun decodeSampled(path: String, reqSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= reqSize && bounds.outHeight / (sample * 2) >= reqSize) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, options)
    }

    private data class Palette(
        val bg: Int,
        val key: Int,
        val fn: Int,
        val text: Int,
        val sub: Int,
        val accent: Int,
        val card: Int,
        val muted: Int
    )

    private fun palette(): Palette {
        val skin = KeyboardSkins.byId(ime.skinId)
        return Palette(skin.bg, skin.key, skin.fn, skin.text, skin.sub, skin.accent, skin.card, skin.muted)
    }

    fun create(): View {
        val colors = palette()
        val root = column(colors.bg)
        if (face == Face.KEYS) root.addView(candidateStrip(colors))
        root.addView(stage(colors), LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(footer(colors))
        if (face == Face.KEYS) updateCandidates()
        return root
    }

    fun showComposing() {
        val composing = composingLine ?: return
        val raw = ime.rawInput()
        composing.text = ime.composeLabel()
        composing.visibility = if (composing.text.isNullOrEmpty() && raw.isEmpty()) View.GONE else View.VISIBLE
        if (raw.isEmpty() && ime.shownCandidates().isEmpty()) {
            candidateBar?.visibility = View.GONE
        } else if (raw.isNotEmpty()) {
            candidateBar?.visibility = View.VISIBLE
        }
    }

    fun scheduleCandidates() {
        val token = ++candidateGeneration
        val host = candidateBar ?: return
        host.post {
            if (token != candidateGeneration) return@post
            updateCandidates()
        }
    }

    fun updateCandidates() {
        val row = candidateRow ?: return
        val colors = palette()
        val raw = ime.rawInput()
        if (raw != shownRaw) {
            shownRaw = raw
        }
        val list = ime.shownCandidates()
        val bar = candidateBar
        if (raw.isEmpty() && list.isEmpty()) {
            showComposing()
            bar?.visibility = View.GONE
            return
        }
        bar?.visibility = View.VISIBLE
        showComposing()
        val shown = list.take(6)
        while (chipPool.size < shown.size) {
            val chip = label("", 18f, colors.text).apply {
                setPadding(px(10), px(4), px(10), px(4))
            }
            chipPool.add(chip)
            row.addView(chip)
        }
        chipPool.forEachIndexed { index, chip ->
            if (index >= shown.size) {
                chip.visibility = View.GONE
                chip.setOnClickListener(null)
                return@forEachIndexed
            }
            val candidate = shown[index]
            chip.text = candidate.text
            chip.visibility = View.VISIBLE
            chip.textSize = if (index == 0) 22f else 18f
            chip.setTextColor(if (index == 0) colors.accent else colors.text)
            chip.typeface = if (index == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            chip.setOnClickListener {
                ime.buzz(chip)
                ime.chooseCandidate(candidate)
            }
        }
    }

    fun takeQuery(value: String): Boolean {
        if (face != Face.EMOJI || !searching || value.length != 1 || !value[0].isLetter()) return false
        query += value.lowercase()
        ime.relayout()
        return true
    }

    fun takeBackspace(): Boolean {
        if (face != Face.EMOJI || !searching || query.isEmpty()) return false
        query = query.dropLast(1)
        ime.relayout()
        return true
    }

    private fun stage(colors: Palette): View {
        val body = when (face) {
            Face.KEYS -> keys(colors)
            Face.HUB -> hub(colors)
            Face.EMOJI -> emoji(colors)
            Face.CLIP -> clipboard(colors)
            Face.PHRASES -> phrases(colors)
            Face.OPTIONS -> options(colors)
            Face.BOARDS -> boards(colors)
        }
        if (ime.handBias == 0 || face != Face.KEYS) return body
        val screen = ime.resources.displayMetrics.widthPixels
        val gap = (screen * 0.28f).toInt()
        val wrap = FrameLayout(ime)
        val params = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        if (ime.handBias < 0) params.rightMargin = gap else params.leftMargin = gap
        wrap.addView(body, params)
        return wrap
    }

    private fun candidateStrip(colors: Palette): View {
        val bar = column(colors.bg).apply {
            setPadding(px(12), px(2), px(8), 0)
            visibility = View.GONE
        }
        val composing = label("", 15f, colors.text).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            setPadding(px(4), px(2), px(4), 0)
        }
        val scroller = HorizontalScrollView(ime).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val line = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        scroller.addView(line)
        bar.addView(composing)
        bar.addView(scroller)
        candidateBar = bar
        composingLine = composing
        candidateRow = line
        chipPool.clear()
        return bar
    }

    private fun keys(colors: Palette): View {
        val box = column(colors.bg).apply { setPadding(px(3), px(4), px(3), 0) }
        box.addView(toolbar(colors))
        when {
            symbolPage -> symbolRows(colors).forEach { box.addView(it) }
            numberPage -> numberRows(colors).forEach { box.addView(it) }
            ime.board() == Board.PINYIN9 -> nineRows(colors).forEach { box.addView(it) }
            ime.board() == Board.STROKE -> box.addView(strokeBoard(colors))
            ime.board() == Board.HAND -> box.addView(handBoard(colors))
            else -> letterRows(colors).forEach { box.addView(it) }
        }
        box.addView(bottomRow(colors))
        return box
    }

    private fun toolbar(colors: Palette): View {
        val bar = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(2), px(8), px(6))
        }
        val tools = listOf(
            "苏晟" to { open(Face.HUB) },
            "表情" to { open(Face.EMOJI) },
            "键盘" to { open(Face.BOARDS) },
            "语音" to { ime.voice() },
            "剪贴板" to { clipTab = 0; open(Face.CLIP) },
            "收起" to { ime.hideKeyboard() }
        )
        tools.forEach { (name, action) ->
            bar.addView(toolButton(name, colors, action), LinearLayout.LayoutParams(0, px(46), 1f))
        }
        return bar
    }

    private fun toolButton(name: String, colors: Palette, action: () -> Unit): View {
        val wrap = FrameLayout(ime)
        val circle = if (name == "苏晟") {
            ImageView(ime).apply {
                setImageResource(R.drawable.susheng_logo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = "苏晟"
                clipCircle()
            }
        } else {
            FrameLayout(ime).apply {
                addView(LineIcon(ime, name, colors.text), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
            }
        }
        circle.background = plate(if (name == "苏晟") 0xFFFFFFFF.toInt() else colors.key, 23f)
        circle.elevation = px(2).toFloat()
        val size = if (name == "苏晟") px(44) else px(38)
        wrap.addView(circle, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        wrap.contentDescription = name
        wrap.setOnClickListener { ime.buzz(it); action() }
        return wrap
    }

    private fun letterRows(colors: Palette): List<View> {
        val top = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0")
        val mid = listOf("~", "!", "@", "#", "%", "“", "”", "*", "?")
        val low = listOf("(", ")", "-", "'", ":", ";", "/")
        val rows = listOf("qwertyuiop" to top, "asdfghjkl" to mid, "zxcvbnm" to low)
        return rows.mapIndexed { index, (letters, hints) ->
            val line = row(Color.TRANSPARENT)
            if (index == 2) {
                if (ime.board() == Board.WUBI) line.addView(fnKey("拼", colors, 1.35f) { ime.setBoard(Board.PINYIN26) })
                else line.addView(fnKey("⇧", colors, 1.35f, onLong = {
                    if (ime.isChinese()) ime.onLetter("'")
                }) { ime.toggleShift() })
            }
            letters.forEachIndexed { i, ch ->
                val shown = if (ime.capsKeys() || ime.isShift()) ch.uppercaseChar().toString() else ch.toString()
                line.addView(letterKey(shown, hints[i], colors) {
                    ime.onLetter(if (ime.board() == Board.ENGLISH && ime.isShift()) shown else ch.toString())
                }.apply {
                    setOnLongClickListener {
                        ime.onPunct(hints[i])
                        true
                    }
                })
            }
            if (index == 2) line.addView(fnKey("⌫", colors, 1.35f, repeat = true) { ime.onBackspace(searching) })
            line
        }
    }

    private fun nineRows(colors: Palette): List<View> {
        val spec = listOf(
            listOf("分词" to "'", "ABC" to "2", "DEF" to "3", "⌫" to "\b"),
            listOf("GHI" to "4", "JKL" to "5", "MNO" to "6"),
            listOf("PQRS" to "7", "TUV" to "8", "WXYZ" to "9")
        )
        return spec.map { entries ->
            val line = row(Color.TRANSPARENT)
            entries.forEach { (title, value) ->
                val key = if (value == "\b") fnKey(title, colors, 1f, repeat = true) { ime.onBackspace() }
                else letterKey(title, "", colors) { ime.onLetter(value) }
                line.addView(key)
            }
            line
        }
    }

    private fun strokeBoard(colors: Palette): View {
        val board = row(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyHeight() * 3)
        }
        val punct = column(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.7f)
        }
        listOf("，", "。", "？", "！").forEach { mark ->
            punct.addView(fnKey(mark, colors, 1f) { ime.onPunct(mark) }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(px(3), px(3), px(3), px(3))
                }
            })
        }
        val grid = column(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 3f)
        }
        val cells = listOf(
            listOf("一" to '1', "丨" to '2', "丿" to '3'),
            listOf("丶" to '4', "乛" to '5', "通配" to '6'),
            listOf("分词" to '7', "：" to '8', "；" to '9')
        )
        cells.forEach { entries ->
            val line = row(Color.TRANSPARENT).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            }
            entries.forEach { (title, code) ->
                val hint = if (code in '1'..'6') code.toString() else ""
                line.addView(letterKey(title, hint, colors) { ime.onStroke(code) }.apply {
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply {
                        setMargins(px(3), px(3), px(3), px(3))
                    }
                })
            }
            grid.addView(line)
        }
        val side = column(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.05f)
        }
        listOf(
            "⌫" to { ime.onBackspace() },
            "换行" to { ime.onEnter() },
            "发送" to { ime.onEnter() }
        ).forEach { (title, action) ->
            side.addView(fnKey(title, colors, 1f, repeat = title == "⌫", action = action).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(px(3), px(3), px(3), px(3))
                }
            })
        }
        board.addView(punct)
        board.addView(grid)
        board.addView(side)
        return board
    }

    private fun handBoard(colors: Palette): View {
        val board = row(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, keyHeight() * 3)
        }
        val pad = HandPad(ime, colors.text, { token, ink -> ime.onHandSample(token, ink) }) {
            ime.onHandNext()
        }
        // 覆盖前取消旧 pad 的挂起任务，避免旧 recognize/idleClear 在替换后触发
        handPad?.clear()
        handPad = pad
        val frame = FrameLayout(ime).apply {
            background = keyFace(colors.key)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f).apply {
                setMargins(px(3), px(3), px(3), px(3))
            }
        }
        frame.addView(pad, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        frame.addView(label("设置 ▶", 13f, colors.sub).apply {
            setPadding(px(12), px(8), px(12), px(8))
            setOnClickListener { open(Face.BOARDS) }
        }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        val side = column(Color.TRANSPARENT).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f)
        }
        side.addView(fnKey("⌫", colors, 1f, repeat = true) {
            if (pad.hasInk()) pad.undo() else ime.onBackspace()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.1f).apply {
                setMargins(px(3), px(3), px(3), px(3))
            }
        })
        listOf("，", "。", "？", "！").forEach { mark ->
            side.addView(fnKey(mark, colors, 1f) { ime.onPunct(mark) }.apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                    setMargins(px(3), px(3), px(3), px(3))
                }
            })
        }
        board.addView(frame)
        board.addView(side)
        return board
    }

    private fun boards(colors: Palette): View {
        val wrap = column(colors.bg)
        val head = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(8), px(4))
        }
        head.addView(label("‹", 26f, colors.text).apply {
            setPadding(px(8), 0, px(12), 0)
            setOnClickListener { open(Face.KEYS) }
        })
        head.addView(label("键盘选择", 18f, colors.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        head.addView(label("⚙", 18f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            setOnClickListener {
                showOptions("键盘调节", listOf("紧凑", "标准", "疏松"), scaleIndex()) {
                    ime.applyScale(listOf(0.86f, 1f, 1.14f)[it])
                }
            }
        })
        wrap.addView(head)
        val card = column(Color.TRANSPARENT).apply {
            background = plate(colors.card, 22f)
            setPadding(px(8), px(12), px(8), px(8))
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.setMargins(px(12), px(8), px(12), px(8))
            layoutParams = params
        }
        val items: List<Pair<Board?, String>> = listOf(
            Board.PINYIN9 to "拼9",
            Board.PINYIN26 to "拼",
            Board.HAND to "手",
            Board.ENGLISH to "EN",
            Board.WUBI to "五"
        )
        items.chunked(4).forEach { group ->
            val line = row(Color.TRANSPARENT)
            group.forEach { (board, mark) ->
                line.addView(boardCell(board, mark, colors))
            }
            repeat(4 - group.size) { line.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f)) }
            card.addView(line)
        }
        wrap.addView(card)
        wrap.minimumHeight = keysBlock()
        return wrap
    }

    private fun boardCell(board: Board?, mark: String, colors: Palette): View {
        val selected = board != null && board == ime.board()
        val tint = if (selected) colors.accent else colors.text
        val cell = column(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(4), px(12), px(4), px(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                ime.buzz(this)
                if (board == null) {
                    val names = listOf("关闭", "自然码", "小鹤双拼", "搜狗双拼", "微软双拼")
                    val selectedIndex = names.indexOf(ime.shuangpinName()).coerceAtLeast(0)
                    showOptions("双拼设置", names, selectedIndex) { ime.setShuangpin(names[it]) }
                } else {
                    ime.setBoard(board)
                }
            }
        }
        val badge = TextView(ime).apply {
            text = mark
            textSize = 16f
            setTextColor(tint)
            gravity = Gravity.CENTER
            background = plate(if (selected) (colors.accent and 0x00FFFFFF) or 0x1A000000 else Color.TRANSPARENT, 10f)
            layoutParams = LinearLayout.LayoutParams(px(40), px(40)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        }
        cell.addView(badge)
        val title = board?.title ?: "双拼设置"
        cell.addView(label(title, 12f, tint).apply { setPadding(0, px(8), 0, 0) })
        return cell
    }

    private fun numberRows(colors: Palette): List<View> {
        return listOf("1234567890", "-/:;()$&@\"", ".,?!'").map { letters ->
            val line = row(Color.TRANSPARENT)
            letters.forEach { ch -> line.addView(letterKey(ch.toString(), "", colors) { ime.commitPlain(ch.toString()) }) }
            line
        }
    }

    private fun symbolRows(colors: Palette): List<View> {
        return listOf("【】{}#%^*+=", "_\\|~<>€£¥•", "，。！？、…—～").map { letters ->
            val line = row(Color.TRANSPARENT)
            letters.forEach { ch -> line.addView(letterKey(ch.toString(), "", colors) { ime.onPunct(ch.toString()) }) }
            line
        }
    }

    private fun bottomRow(colors: Palette): View {
        val line = row(Color.TRANSPARENT)
        val comma = if (ime.isChinese()) "，" else ","
        val period = if (ime.isChinese()) "。" else "."
        val symbolLabel = when {
            symbolPage -> "返回"
            ime.board() == Board.ENGLISH -> "!?#"
            else -> "符"
        }
        line.addView(fnKey(symbolLabel, colors, 1.15f) {
            symbolPage = !symbolPage
            if (symbolPage) numberPage = false
            ime.relayout()
        })
        line.addView(fnKey(if (numberPage) "返回" else "123", colors, 1.15f) {
            numberPage = !numberPage
            if (numberPage) symbolPage = false
            ime.relayout()
        })
        line.addView(letterKey(comma, "", colors, 1f) { ime.onPunct(comma) })
        line.addView(spaceKey(colors))
        line.addView(letterKey(period, "", colors, 1f) { ime.onPunct(period) })
        line.addView(langKey(colors))
        line.addView(fnKey(ime.actionLabel(), colors, 1.35f) { ime.onEnter() })
        return line
    }

    private fun spaceKey(colors: Palette): View {
        val key = FrameLayout(ime).apply {
            background = keyFace(colors.key)
            contentDescription = "空格"
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, keyHeight(), 3.4f).apply { setMargins(px(3), px(3), px(3), px(3)) }
            bindKey({ ime.onSpace() }, { ime.voice() })
        }
        val modeName = when (ime.board()) {
            Board.WUBI -> "五笔"
            Board.STROKE -> "笔画"
            Board.HAND -> "手写"
            else -> ""
        }
        if (modeName.isNotEmpty()) {
            key.addView(label(modeName, 11f, colors.sub), FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply { topMargin = px(4) })
        }
        key.addView(LineIcon(ime, "语音", colors.sub), FrameLayout.LayoutParams(px(18), px(18), Gravity.CENTER))
        return key
    }

    private fun langKey(colors: Palette): View {
        val key = column(Color.TRANSPARENT).apply {
            background = plate(colors.fn, 8f)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, keyHeight(), 1.2f).apply { setMargins(px(3), px(3), px(3), px(3)) }
            setOnClickListener { ime.buzz(this); ime.toggleChinese() }
        }
        key.addView(label(if (ime.isChinese()) "中" else "英", 13f, colors.text))
        key.addView(label(if (ime.isChinese()) "英" else "中", 11f, colors.sub))
        return key
    }

    private fun letterKey(text: String, hint: String, colors: Palette, weight: Float = 1f, action: () -> Unit): View {
        val key = column(Color.TRANSPARENT).apply {
            background = keyFace(colors.key)
            gravity = Gravity.CENTER
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, keyHeight(), weight).apply { setMargins(px(3), px(3), px(3), px(3)) }
            val hintAction = if (hint.isEmpty() || hint.all { it.isDigit() }) null else ({ ime.onPunct(hint) })
            bindKey(action, hintAction)
        }
        if (hint.isNotEmpty()) key.addView(label(hint, 10f, colors.sub))
        val size = when {
            text.length > 2 -> 15f
            text.length > 1 -> 18f
            hint.isNotEmpty() -> 20f
            else -> 22f
        }
        key.addView(label(text, size, colors.text))
        return key
    }

    private fun fnKey(text: String, colors: Palette, weight: Float, repeat: Boolean = false, onLong: (() -> Unit)? = null, action: () -> Unit): View {
        val key = label(text, if (text.length > 1) 15f else 18f, colors.text).apply {
            background = keyFace(colors.fn)
            gravity = Gravity.CENTER
            isSoundEffectsEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, keyHeight(), weight).apply { setMargins(px(3), px(3), px(3), px(3)) }
        }
        if (ime.isShift() && text == "⇧") key.setTextColor(colors.accent)
        if (repeat) bindRepeat(key, action) else key.bindKey(action, onLong)
        return key
    }

    private fun View.bindKey(onTap: () -> Unit, onLong: (() -> Unit)?) {
        var longFired = false
        val longTask = Runnable {
            longFired = true
            isPressed = false
            onLong?.invoke()
        }
        setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longFired = false
                    v.isPressed = true
                    ime.buzz(v)
                    if (onLong != null) v.postDelayed(longTask, 280)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.removeCallbacks(longTask)
                    val inside = event.x >= 0 && event.x <= v.width && event.y >= 0 && event.y <= v.height
                    v.isPressed = false
                    if (event.actionMasked == MotionEvent.ACTION_UP && !longFired && inside) onTap()
                    true
                }
                else -> false
            }
        }
    }

    private fun keyFace(color: Int): android.graphics.drawable.StateListDrawable {
        val radius = px(8).toFloat()
        fun fill(value: Int) = GradientDrawable().apply {
            setColor(value)
            cornerRadius = radius
        }
        return android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), fill(pressColor(color)))
            addState(intArrayOf(), fill(color))
        }
    }

    private fun pressColor(color: Int): Int {
        val red = ((color shr 16) and 0xff) * 86 / 100
        val green = ((color shr 8) and 0xff) * 86 / 100
        val blue = (color and 0xff) * 86 / 100
        return (0xff shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun keyHeight() = px(52 * ime.keyScale)

    private fun footer(colors: Palette): View {
        val bar = row(colors.bg).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(18), px(4), px(18), px(8))
        }
        bar.addView(iconButton("地球", colors) { ime.pickIme() })
        bar.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton("闪光", colors) {
            if (face == Face.KEYS) openPhrases("闪光语录", setOf("promo", "script", "tweet")) else open(Face.KEYS)
        })
        return bar
    }

    private fun iconButton(name: String, colors: Palette, action: () -> Unit): View {
        return FrameLayout(ime).apply {
            contentDescription = name
            addView(LineIcon(ime, name, colors.muted), FrameLayout.LayoutParams(px(22), px(22), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(px(36), px(32))
            setOnClickListener { ime.buzz(it); action() }
        }
    }

    private fun hub(colors: Palette): View {
        val pageWidth = ime.resources.displayMetrics.widthPixels
        val scroller = HorizontalScrollView(ime).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    val page = if ((view as HorizontalScrollView).scrollX > pageWidth / 2) 1 else 0
                    view.post { view.smoothScrollTo(page * pageWidth, 0) }
                }
                false
            }
        }
        val pages = row(Color.TRANSPARENT)
        pages.addView(hubPage(colors, hubTools(0), pageWidth))
        pages.addView(hubPage(colors, hubTools(1), pageWidth))
        scroller.addView(pages)
        val wrap = column(colors.bg)
        wrap.addView(hubHeader(colors))
        wrap.addView(scroller, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        wrap.addView(dots(colors))
        wrap.minimumHeight = keysBlock()
        return wrap
    }

    private fun hubHeader(colors: Palette): View {
        val bar = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(8), px(12), px(4))
        }
        bar.addView(label("‹", 28f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            setOnClickListener { open(Face.KEYS) }
        })
        val avatar = ImageView(ime).apply {
            setImageResource(R.drawable.susheng_logo)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = plate(0xFFFFFFFF.toInt(), 18f)
            contentDescription = "苏晟"
            layoutParams = LinearLayout.LayoutParams(px(36), px(36))
            clipCircle()
        }
        bar.addView(avatar)
        bar.addView(label(ime.displayName(), 16f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        bar.addView(label("意见反馈", 13f, colors.text).apply {
            setPadding(px(12), px(8), px(12), px(8))
            background = plate(colors.fn, 16f)
            setOnClickListener { ime.openHome() }
        })
        return bar
    }

    private fun hubPage(colors: Palette, tools: List<Tool>, width: Int): View {
        val page = column(Color.TRANSPARENT).apply {
            setPadding(px(16), px(8), px(16), px(4))
            layoutParams = LinearLayout.LayoutParams(width, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val card = column(Color.TRANSPARENT).apply {
            background = plate(colors.card, 22f)
            setPadding(px(8), px(16), px(8), px(12))
            elevation = px(1).toFloat()
        }
        tools.chunked(4).forEach { group ->
            val line = row(Color.TRANSPARENT)
            group.forEach { tool -> line.addView(toolCell(tool, colors)) }
            repeat(4 - group.size) { line.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f)) }
            card.addView(line)
        }
        page.addView(card)
        return page
    }

    private fun toolCell(tool: Tool, colors: Palette): View {
        val cell = column(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(4), px(10), px(4), px(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { ime.buzz(this); tool.run() }
        }
        val tint = if (tool.mark) colors.accent else colors.text
        cell.addView(LineIcon(ime, tool.icon, tint).apply {
            layoutParams = LinearLayout.LayoutParams(px(28), px(28)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        cell.addView(label(tool.name, 12f, colors.text).apply { setPadding(0, px(8), 0, 0) })
        return cell
    }

    private data class Tool(val name: String, val icon: String, val mark: Boolean = false, val run: () -> Unit)

    private fun hubTools(page: Int): List<Tool> {
        if (page == 1) {
            return listOf(
                Tool("话术", "常用语") { openPhrases("话术", setOf("script")) },
                Tool("推文", "闪光") { openPhrases("推文", setOf("tweet")) },
                Tool("宣发", "皮肤") { openPhrases("宣发", setOf("promo")) },
                Tool("图片", "斗图") { emojiTab = 2; open(Face.EMOJI) }
            )
        }
        return listOf(
            Tool("常用语", "常用语") { openPhrases("常用语", setOf("script", "tweet", "promo")) },
            Tool("剪贴板", "剪贴板") { clipTab = 0; open(Face.CLIP) },
            Tool("个性皮肤", "皮肤") {
                val skins = KeyboardSkins.skins
                val selected = skins.indexOfFirst { it.id == ime.skinId }.coerceAtLeast(0)
                showOptions("个性皮肤", skins.map { it.name }, selected) { ime.setSkin(skins[it].id) }
            },
            Tool("键盘选择", "键盘") { open(Face.BOARDS) },
            Tool("音效振动", "语音", ime.hapticOn) { ime.toggleHaptic() },
            Tool("键盘调节", "调节") { showOptions("键盘调节", listOf("紧凑", "标准", "疏松"), scaleIndex()) { ime.applyScale(listOf(0.86f, 1f, 1.14f)[it]) } },
            Tool("单手键盘", "单手") { showOptions("单手键盘", listOf("左手", "标准", "右手"), ime.handBias + 1) { ime.setHand(it - 1) } },
            Tool("闪光语录", "闪光") { openPhrases("闪光语录", setOf("promo", "script")) }
        )
    }

    private fun scaleIndex() = when {
        ime.keyScale < 0.95f -> 0
        ime.keyScale > 1.05f -> 2
        else -> 1
    }

    private fun dots(colors: Palette): View {
        val bar = row(Color.TRANSPARENT).apply { gravity = Gravity.CENTER }
        bar.addView(dot(colors.accent))
        bar.addView(dot(0xFFD5D8DE.toInt()).apply {
            (layoutParams as LinearLayout.LayoutParams).leftMargin = px(6)
        })
        return bar
    }

    private fun dot(color: Int) = View(ime).apply {
        background = plate(color, 4f)
        layoutParams = LinearLayout.LayoutParams(px(6), px(6))
    }

    private fun emoji(colors: Palette): View {
        val wrap = column(colors.bg).apply { minimumHeight = keysBlock() }
        wrap.addView(emojiHeader(colors))
        if (searching) {
            wrap.addView(label(if (query.isEmpty()) "输入拼音筛选" else query, 15f, colors.sub).apply {
                setPadding(px(16), px(4), px(16), px(4))
            })
        }
        val scroll = ScrollView(ime).apply { isVerticalScrollBarEnabled = false }
        scroll.addView(emojiBody(colors))
        val gridHeight = if (searching) px(96) else px(210)
        wrap.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, gridHeight))
        if (!searching) wrap.addView(emojiCategories(colors))
        else letterRows(colors).forEach { wrap.addView(it) }
        return wrap
    }

    private fun emojiHeader(colors: Palette): View {
        val bar = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(6), px(8), px(4))
        }
        bar.addView(label("‹", 26f, colors.text).apply {
            setPadding(px(8), 0, px(10), 0)
            setOnClickListener { searching = false; query = ""; open(Face.KEYS) }
        })
        listOf("Emoji", "颜文字", "斗图").forEachIndexed { index, title ->
            bar.addView(label(title, 16f, if (emojiTab == index) colors.accent else colors.muted).apply {
                typeface = if (emojiTab == index) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                setPadding(px(10), px(8), px(10), px(8))
                setOnClickListener { emojiTab = index; ime.relayout() }
            })
        }
        bar.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton("搜索", colors) {
            searching = !searching
            if (!searching) query = ""
            ime.relayout()
        })
        return bar
    }

    private fun emojiBody(colors: Palette): View {
        val filtered = emojiItems().filter { query.isEmpty() || it.label.contains(query, true) || it.words.contains(query, true) }
        if (emojiTab == 2) return stickerGrid(colors)
        val grid = column(Color.TRANSPARENT).apply { setPadding(px(8), px(4), px(8), px(8)) }
        if (filtered.isEmpty()) {
            grid.addView(label("没有匹配的内容", 14f, colors.muted).apply { setPadding(px(12), px(24), px(12), px(12)) })
            return grid
        }
        filtered.chunked(8).forEach { lineItems ->
            val line = row(Color.TRANSPARENT)
            lineItems.forEach { item ->
                line.addView(label(item.label, if (emojiTab == 1) 14f else 24f, colors.text).apply {
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(0, px(46), 1f)
                    setOnClickListener {
                        ime.buzz(this)
                        ime.commitPlain(item.commit)
                        ime.rememberEmoji(item.commit)
                    }
                })
            }
            repeat(8 - lineItems.size) { line.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line)
        }
        return grid
    }

    private fun stickerGrid(colors: Palette): View {
        val grid = column(Color.TRANSPARENT).apply { setPadding(px(12), px(8), px(12), px(8)) }
        val stickers = ime.itemsOf(setOf("image")).filter { query.isEmpty() || it.title.contains(query, true) }
        if (stickers.isEmpty()) {
            grid.addView(label("同步内容包后，图片会出现在斗图里", 14f, colors.muted).apply { setPadding(px(8), px(28), px(8), px(8)) })
            return grid
        }
        stickers.chunked(4).forEach { lineItems ->
            val line = row(Color.TRANSPARENT)
            lineItems.forEach { item -> line.addView(stickerCell(item, colors)) }
            repeat(4 - lineItems.size) { line.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f)) }
            grid.addView(line)
        }
        return grid
    }

    private fun stickerCell(item: ContentItem, colors: Palette): View {
        val cell = column(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(4), px(4), px(4), px(4))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { ime.buzz(this); ime.insertContent(item) }
        }
        val image = ImageView(ime).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = plate(colors.fn, 12f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(72))
        }
        ime.assetFile(item)?.let { file ->
            decodeSampled(file.absolutePath, px(72))?.let { bmp -> image.setImageBitmap(bmp) }
        }
        cell.addView(image)
        cell.addView(label(item.title, 11f, colors.muted).apply { setPadding(0, px(4), 0, 0) })
        return cell
    }

    private fun emojiCategories(colors: Palette): View {
        val names = listOf("最近", "黄脸", "组合", "动物", "植物", "食物", "手势", "心形")
        val bar = row(colors.bg).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(8), px(2), px(8), px(4))
        }
        val scroll = HorizontalScrollView(ime).apply { isHorizontalScrollBarEnabled = false }
        val line = row(Color.TRANSPARENT).apply { gravity = Gravity.CENTER_VERTICAL }
        names.forEachIndexed { index, name ->
            val chip = label(if (index == 0) "★" else name, 14f, if (emojiCat == index) colors.text else colors.muted).apply {
                setPadding(px(12), px(6), px(12), px(6))
                if (emojiCat == index) background = plate(colors.key, 14f)
                setOnClickListener { emojiCat = index; ime.relayout() }
            }
            line.addView(chip)
        }
        scroll.addView(line)
        bar.addView(scroll, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(fnKey("⌫", colors, 0f) { ime.onBackspace() }.apply {
            layoutParams = LinearLayout.LayoutParams(px(48), px(36)).apply { leftMargin = px(6) }
        })
        return bar
    }

    private data class EmojiItem(val label: String, val commit: String, val words: String)

    private fun emojiItems(): List<EmojiItem> {
        if (emojiTab == 1) {
            return listOf(
                "(◕‿◕)", "(｡♥‿♥｡)", "¯\\_(ツ)_/¯", "(╯°□°）╯", "(づ｡◕‿‿◕｡)づ", "(ノ°益°)ノ",
                "(•̀ᴗ•́)و", "(=^･ω･^=)", "(｡•́︿•̀｡)", "(ง •̀_•́)ง", "（￣▽￣）", "(｡･ω･｡)",
                "╮(╯▽╰)╭", "(๑•̀ㅂ•́)و✧", "o(≧口≦)o", "(´･ω･`)", "(T_T)", "(^_^)"
            ).map { EmojiItem(it, it, it) }
        }
        if (emojiTab == 2) return emptyList()
        val sets = listOf(
            ime.recentEmoji(),
            listOf("😀", "😃", "😄", "😁", "😆", "😅", "🤣", "😂", "🙂", "😉", "😊", "😇", "🥰", "😍", "🤩", "😘", "😗", "😚", "😋", "😛", "😜", "🤪", "😝", "🤑", "🤗", "🤭", "🤫", "🤔", "😐", "😑", "😶", "😏", "😒", "🙄", "😬", "😌", "😔", "😪", "🤤", "😴", "😷", "🤒", "🤕", "🤢", "🥵", "🥶", "😵", "🤯", "🤠", "🥳", "😎", "🤓", "😕", "😮", "😲", "😳", "🥺", "😦", "😨", "😰", "😢", "😭", "😱", "😖", "😞", "😓", "😩", "😫", "🥱", "😤", "😡", "😠", "🤬", "😈", "💀", "💩", "🤡", "👻", "👽", "🤖"),
            listOf("👨‍👩‍👧", "👨‍💻", "👩‍🚀", "🤝", "👏", "🙌", "💪", "👀", "💋", "💯", "🔥", "✨"),
            listOf("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🐔", "🐧", "🐦", "🦄", "🐝", "🦋", "🐢"),
            listOf("🌸", "💐", "🌹", "🌺", "🌻", "🌼", "🌷", "🌱", "🌲", "🌳", "🌴", "🌵", "🍀", "🍁", "🍂", "🍃"),
            listOf("🍎", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🍍", "🥝", "🍅", "🥑", "🍔", "🍟", "🍕", "🍜", "🍣", "🍩", "🍪", "🎂", "☕"),
            listOf("👍", "👎", "👌", "✌️", "🤞", "🤟", "🤘", "👊", "✊", "👋", "🤚", "✋", "🖐️", "👏", "🙌", "🙏", "💪", "✍️"),
            listOf("❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍", "💔", "❣️", "💕", "💞", "💓", "💗", "💖", "💘", "💝")
        )
        return sets.getOrElse(emojiCat) { emptyList() }.map { EmojiItem(it, it, it) }
    }

    private fun clipboard(colors: Palette): View {
        val wrap = column(colors.bg).apply { minimumHeight = keysBlock() }
        wrap.addView(clipHeader(colors))
        if (clipTab == 0 && showClipBanner && ime.clipHistory().isEmpty()) wrap.addView(clipBanner(colors))
        val scroll = ScrollView(ime)
        scroll.addView(if (clipTab == 0) clipList(colors) else phraseList(colors, ime.itemsOf(setOf("script", "tweet", "promo")), "还没有常用语，登录并同步内容包后会出现在这里"))
        wrap.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        return wrap
    }

    private fun clipHeader(colors: Palette): View {
        val bar = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(8), px(8), px(6))
        }
        bar.addView(label("‹", 26f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            setOnClickListener { open(Face.KEYS) }
        })
        bar.addView(chip("剪贴板", clipTab == 0, colors) { clipTab = 0; ime.relayout() })
        bar.addView(chip("常用语", clipTab == 1, colors) { clipTab = 1; ime.relayout() })
        bar.addView(View(ime), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(iconButton("调节", colors) { if (clipTab == 0) ime.clearClips() })
        return bar
    }

    private fun chip(text: String, selected: Boolean, colors: Palette, action: () -> Unit): View {
        return label(text, 14f, if (selected) Color.WHITE else colors.text).apply {
            setPadding(px(14), px(7), px(14), px(7))
            background = plate(if (selected) colors.accent else colors.key, 16f)
            val params = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            params.leftMargin = px(6)
            layoutParams = params
            setOnClickListener { ime.buzz(this); action() }
        }
    }

    private fun clipBanner(colors: Palette): View {
        val banner = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            background = plate(colors.card, 16f)
            setPadding(px(14), px(10), px(10), px(10))
            elevation = px(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(px(12), px(4), px(12), px(4))
            }
        }
        banner.addView(label("使用剪贴板需要获取系统权限", 13f, colors.text).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        banner.addView(label("去授权", 13f, Color.WHITE).apply {
            setPadding(px(12), px(6), px(12), px(6))
            background = plate(colors.accent, 14f)
            setOnClickListener {
                ime.pullClip()
                ime.relayout()
            }
        })
        banner.addView(label("×", 18f, colors.muted).apply {
            setPadding(px(10), 0, px(4), 0)
            setOnClickListener { showClipBanner = false; ime.relayout() }
        })
        return banner
    }

    private fun clipList(colors: Palette): View {
        val list = ime.clipHistory()
        if (list.isEmpty()) return emptyClip(colors)
        return phraseList(colors, list.map { text ->
            ContentItem(text, "clip", text, text, emptyList(), "", "", "", "", null, null, null, null, "published", 0, false)
        }, "")
    }

    private fun emptyClip(colors: Palette): View {
        val box = column(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(px(24), px(28), px(24), px(12))
        }
        box.addView(LineIcon(ime, "空剪贴板", colors.sub).apply {
            layoutParams = LinearLayout.LayoutParams(px(72), px(72)).apply { gravity = Gravity.CENTER_HORIZONTAL }
        })
        box.addView(label("剪贴板为空", 16f, colors.text).apply { setPadding(0, px(12), 0, px(6)) })
        box.addView(label("您复制/剪切的文本会在这里\n最多记录500条哦~", 13f, colors.muted).apply { gravity = Gravity.CENTER })
        return box
    }

    private fun phrases(colors: Palette): View {
        val wrap = column(colors.bg).apply { minimumHeight = keysBlock() }
        val head = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(8), px(12), px(6))
        }
        head.addView(label("‹", 26f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            setOnClickListener { open(Face.HUB) }
        })
        head.addView(label(phraseTitle, 16f, colors.text))
        wrap.addView(head)
        val scroll = ScrollView(ime)
        scroll.addView(phraseList(colors, ime.itemsOf(phraseTypes), "同步内容包后，这里会出现${phraseTitle}"))
        wrap.addView(scroll)
        return wrap
    }

    private fun phraseList(colors: Palette, items: List<ContentItem>, empty: String): View {
        val box = column(Color.TRANSPARENT).apply { setPadding(px(12), px(4), px(12), px(8)) }
        if (items.isEmpty() && empty.isNotEmpty()) {
            box.addView(label(empty, 14f, colors.muted).apply { setPadding(px(8), px(24), px(8), px(8)) })
            return box
        }
        items.forEach { item ->
            val card = column(Color.TRANSPARENT).apply {
                background = plate(colors.card, 14f)
                setPadding(px(14), px(12), px(14), px(12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = px(8) }
                setOnClickListener {
                    ime.buzz(this)
                    if (item.type == "clip") ime.commitPlain(item.body) else ime.insertContent(item)
                    if (item.type == "clip") open(Face.KEYS)
                }
            }
            card.addView(label(item.title.ifBlank { item.body }.take(40), 15f, colors.text))
            if (item.type != "clip" && item.body.isNotBlank() && item.body != item.title) {
                card.addView(label(item.body.take(80), 13f, colors.muted).apply { setPadding(0, px(4), 0, 0) })
            }
            box.addView(card)
        }
        return box
    }

    private fun options(colors: Palette): View {
        val wrap = column(colors.bg).apply { minimumHeight = keysBlock() }
        val head = row(Color.TRANSPARENT).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(6), px(8), px(12), px(6))
        }
        head.addView(label("‹", 26f, colors.text).apply {
            setPadding(px(8), 0, px(8), 0)
            setOnClickListener { open(Face.HUB) }
        })
        head.addView(label(optionTitle, 16f, colors.text))
        wrap.addView(head)
        val scroll = ScrollView(ime).apply { isVerticalScrollBarEnabled = false }
        val box = column(Color.TRANSPARENT)
        optionLabels.forEachIndexed { index, text ->
            val card = row(Color.TRANSPARENT).apply {
                background = plate(colors.card, 14f)
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), px(14), px(16), px(14))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(px(12), px(6), px(12), 0)
                }
                setOnClickListener {
                    optionSelected = index
                    optionPick(index)
                }
            }
            card.addView(label(text, 16f, colors.text), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            if (index == optionSelected) card.addView(label("✓", 16f, colors.accent))
            box.addView(card)
        }
        scroll.addView(box)
        wrap.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(280)))
        return wrap
    }

    private fun showOptions(title: String, labels: List<String>, selected: Int, pick: (Int) -> Unit) {
        optionTitle = title
        optionLabels = labels
        optionSelected = selected
        optionPick = pick
        open(Face.OPTIONS)
    }

    private fun openPhrases(title: String, types: Set<String>) {
        phraseTitle = title
        phraseTypes = types
        open(Face.PHRASES)
    }

    private fun open(next: Face) {
        face = next
        ime.relayout()
    }

    private fun keysBlock() = px(52) + keyHeight() * 4 + px(8)

    private fun bindRepeat(view: View, action: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val task = object : Runnable {
            override fun run() {
                action()
                handler.postDelayed(this, 45)
            }
        }
        view.setOnTouchListener { touched, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touched.isPressed = true
                    ime.buzz(touched)
                    action()
                    handler.postDelayed(task, 320)
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    touched.isPressed = false
                    handler.removeCallbacks(task)
                    true
                }
                else -> false
            }
        }
        // view 被 detach（键盘重建/隐藏）时取消挂起的重复任务，避免 Handler 泄漏与 action 持续执行
        view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = Unit
            override fun onViewDetachedFromWindow(v: View) {
                handler.removeCallbacks(task)
            }
        })
    }

    private fun View.clipCircle() {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        clipToOutline = true
    }

    private fun plate(color: Int, radiusDp: Float): RippleDrawable {
        val fill = GradientDrawable().apply {
            setColor(color)
            cornerRadius = px(radiusDp).toFloat()
        }
        return RippleDrawable(ColorStateList.valueOf(0x22000000), fill, fill)
    }

    private fun column(color: Int) = LinearLayout(ime).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (color != Color.TRANSPARENT) setBackgroundColor(color)
    }

    private fun row(color: Int) = LinearLayout(ime).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        if (color != Color.TRANSPARENT) setBackgroundColor(color)
    }

    private fun label(text: String, size: Float, color: Int) = TextView(ime).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
    }
}

private class LineIcon(context: android.content.Context, private val kind: String, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = color
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        this.color = color
    }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = width * 0.07f
        val w = width.toFloat()
        val h = height.toFloat()
        when (kind) {
            "表情" -> {
                canvas.drawCircle(w / 2f, h / 2f, w * 0.36f, paint)
                canvas.drawCircle(w * 0.38f, h * 0.42f, w * 0.045f, fill)
                canvas.drawCircle(w * 0.62f, h * 0.42f, w * 0.045f, fill)
                canvas.drawArc(w * 0.3f, h * 0.38f, w * 0.7f, h * 0.78f, 20f, 140f, false, paint)
            }
            "键盘" -> {
                canvas.drawRoundRect(w * 0.16f, h * 0.28f, w * 0.84f, h * 0.74f, 6f, 6f, paint)
                repeat(3) { canvas.drawCircle(w * (0.34f + it * 0.16f), h * 0.5f, w * 0.035f, fill) }
            }
            "语音" -> {
                canvas.drawRoundRect(w * 0.38f, h * 0.18f, w * 0.62f, h * 0.52f, 20f, 20f, paint)
                canvas.drawArc(w * 0.26f, h * 0.28f, w * 0.74f, h * 0.7f, 0f, 180f, false, paint)
                canvas.drawLine(w / 2f, h * 0.7f, w / 2f, h * 0.84f, paint)
            }
            "剪贴板" -> {
                canvas.drawCircle(w * 0.36f, h * 0.68f, w * 0.12f, paint)
                canvas.drawCircle(w * 0.64f, h * 0.68f, w * 0.12f, paint)
                canvas.drawLine(w * 0.32f, h * 0.58f, w * 0.7f, h * 0.24f, paint)
                canvas.drawLine(w * 0.68f, h * 0.58f, w * 0.3f, h * 0.24f, paint)
            }
            "收起" -> {
                canvas.drawCircle(w / 2f, h / 2f, w * 0.36f, paint)
                val path = Path().apply {
                    moveTo(w * 0.32f, h * 0.42f)
                    lineTo(w * 0.5f, h * 0.62f)
                    lineTo(w * 0.68f, h * 0.42f)
                }
                canvas.drawPath(path, paint)
            }
            "常用语" -> {
                canvas.drawRoundRect(w * 0.28f, h * 0.16f, w * 0.72f, h * 0.84f, 4f, 4f, paint)
                canvas.drawLine(w * 0.38f, h * 0.38f, w * 0.62f, h * 0.38f, paint)
                canvas.drawLine(w * 0.38f, h * 0.52f, w * 0.62f, h * 0.52f, paint)
                canvas.drawLine(w * 0.38f, h * 0.66f, w * 0.54f, h * 0.66f, paint)
            }
            "皮肤" -> {
                val path = Path().apply {
                    moveTo(w * 0.38f, h * 0.22f)
                    lineTo(w * 0.18f, h * 0.4f)
                    lineTo(w * 0.32f, h * 0.5f)
                    lineTo(w * 0.32f, h * 0.82f)
                    lineTo(w * 0.68f, h * 0.82f)
                    lineTo(w * 0.68f, h * 0.5f)
                    lineTo(w * 0.82f, h * 0.4f)
                    lineTo(w * 0.62f, h * 0.22f)
                    quadTo(w * 0.5f, h * 0.36f, w * 0.38f, h * 0.22f)
                }
                canvas.drawPath(path, paint)
            }
            "调节" -> {
                canvas.drawCircle(w / 2f, h / 2f, w * 0.16f, paint)
                repeat(6) { index ->
                    val angle = Math.toRadians((index * 60).toDouble())
                    val cx = w / 2f + (Math.cos(angle) * w * 0.34f).toFloat()
                    val cy = h / 2f + (Math.sin(angle) * h * 0.34f).toFloat()
                    canvas.drawCircle(cx, cy, w * 0.045f, fill)
                }
            }
            "单手" -> {
                canvas.drawRoundRect(w * 0.3f, h * 0.12f, w * 0.7f, h * 0.88f, 8f, 8f, paint)
                canvas.drawCircle(w * 0.62f, h * 0.62f, w * 0.06f, fill)
            }
            "闪光" -> {
                canvas.drawCircle(w * 0.42f, h * 0.48f, w * 0.08f, fill)
                canvas.drawLine(w * 0.42f, h * 0.18f, w * 0.42f, h * 0.32f, paint)
                canvas.drawLine(w * 0.68f, h * 0.28f, w * 0.56f, h * 0.4f, paint)
                canvas.drawLine(w * 0.72f, h * 0.55f, w * 0.56f, h * 0.52f, paint)
            }
            "地球" -> canvas.drawCircle(w / 2f, h / 2f, w * 0.34f, paint).also {
                canvas.drawLine(w * 0.18f, h / 2f, w * 0.82f, h / 2f, paint)
                canvas.drawOval(w * 0.36f, h * 0.16f, w * 0.64f, h * 0.84f, paint)
            }
            "搜索" -> {
                canvas.drawCircle(w * 0.42f, h * 0.42f, w * 0.2f, paint)
                canvas.drawLine(w * 0.56f, h * 0.56f, w * 0.76f, h * 0.76f, paint)
            }
            "斗图" -> canvas.drawRoundRect(w * 0.2f, h * 0.22f, w * 0.8f, h * 0.78f, 6f, 6f, paint)
            "空剪贴板" -> {
                canvas.drawRoundRect(w * 0.28f, h * 0.22f, w * 0.72f, h * 0.84f, 10f, 10f, paint)
                canvas.drawCircle(w * 0.42f, h * 0.46f, w * 0.035f, fill)
                canvas.drawCircle(w * 0.58f, h * 0.46f, w * 0.035f, fill)
                canvas.drawArc(w * 0.36f, h * 0.48f, w * 0.64f, h * 0.7f, 20f, 140f, false, paint)
            }
            else -> canvas.drawCircle(w / 2f, h / 2f, w * 0.28f, paint)
        }
    }
}

private class HandPad(
    context: android.content.Context,
    ink: Int,
    private val onSample: (Int, List<List<PointF>>) -> Unit,
    private val onNextCharacter: () -> Unit
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 8f * resources.displayMetrics.density
    }
    private val strokes = mutableListOf<Path>()
    private val traces = mutableListOf<List<PointF>>()
    private val live = ArrayList<PointF>()
    private val current = Path()
    private var penX = 0f
    private var penY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    /** 手指还在落点附近时只画点，拖出这个范围才开始拉线。 */
    private var tracing = false
    private var showDot = false
    /** 这一笔开始时画布是空的，抬笔后再提交上一个字，避免按下时堵住主线程把笔迹收成一条直线。 */
    private var commitOnFinish = false
    private val pressSlop = 8f * resources.displayMetrics.density
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ink
        style = Paint.Style.FILL
    }
    private val handler = Handler(Looper.getMainLooper())
    /** 停笔这么久才清屏。识别不等这一下，每笔抬起就把画布上的全部笔迹认一次。 */
    private val idleClearMs = 1500L
    private val recognize = Runnable {
        if (traces.isEmpty()) return@Runnable
        val copy = traces.map { stroke -> stroke.map { PointF(it.x, it.y) } }
        onSample(generation, copy)
    }
    private val idleClear = Runnable { clearInk() }
    var generation = 0
        private set
    val token get() = generation

    init {
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun hasInk() = strokes.isNotEmpty() || !current.isEmpty

    fun clear() {
        handler.removeCallbacks(recognize)
        handler.removeCallbacks(idleClear)
        clearInk()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        // 键盘重建/隐藏时取消挂起的识别与清屏任务，避免 detach 后回调仍触发
        handler.removeCallbacks(recognize)
        handler.removeCallbacks(idleClear)
    }

    private fun clearInk() {
        strokes.clear()
        traces.clear()
        live.clear()
        current.reset()
        showDot = false
        tracing = false
        commitOnFinish = false
        invalidate()
    }

    /** 抬笔后立刻用当前全部笔迹识别，并重新计算清屏倒计时。 */
    private fun afterStroke() {
        handler.removeCallbacks(recognize)
        handler.removeCallbacks(idleClear)
        handler.post(recognize)
        handler.postDelayed(idleClear, idleClearMs)
    }

    fun undo() {
        handler.removeCallbacks(recognize)
        handler.removeCallbacks(idleClear)
        if (strokes.isEmpty()) return
        strokes.removeAt(strokes.lastIndex)
        if (traces.isNotEmpty()) traces.removeAt(traces.lastIndex)
        invalidate()
        if (strokes.isNotEmpty()) afterStroke()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(recognize)
                handler.removeCallbacks(idleClear)
                parent?.requestDisallowInterceptTouchEvent(true)
                commitOnFinish = strokes.isEmpty()
                generation++
                current.reset()
                tracing = false
                anchorX = event.x
                anchorY = event.y
                penX = anchorX
                penY = anchorY
                live.clear()
                live.add(PointF(anchorX, anchorY))
                showDot = true
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                trace(event)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                trace(event)
                if (!tracing) finishDot()
                if (live.isNotEmpty()) traces.add(ArrayList(live))
                live.clear()
                if (!current.isEmpty) strokes.add(Path(current))
                current.reset()
                showDot = false
                tracing = false
                invalidate()
                if (commitOnFinish) {
                    commitOnFinish = false
                    handler.post { onNextCharacter() }
                }
                if (strokes.isNotEmpty()) afterStroke()
            }
            MotionEvent.ACTION_CANCEL -> {
                showDot = false
                tracing = false
                commitOnFinish = false
                live.clear()
                current.reset()
                invalidate()
            }
        }
        return true
    }

    /** 落点附近的抖动只保留圆点；拖出范围后才从落点把线拉出去。 */
    private fun trace(event: MotionEvent) {
        val history = event.historySize
        for (i in 0 until history) {
            extend(event.getHistoricalX(i), event.getHistoricalY(i))
        }
        extend(event.x, event.y)
    }

    private fun extend(x: Float, y: Float) {
        if (!tracing) {
            val dx = x - anchorX
            val dy = y - anchorY
            if (dx * dx + dy * dy < pressSlop * pressSlop) return
            tracing = true
            showDot = false
            current.moveTo(anchorX, anchorY)
            penX = anchorX
            penY = anchorY
        }
        penTo(x, y)
        remember(x, y)
    }

    private fun remember(x: Float, y: Float) {
        val last = live.lastOrNull()
        if (last != null && kotlin.math.abs(x - last.x) + kotlin.math.abs(y - last.y) < 1.5f) return
        live.add(PointF(x, y))
    }

    private fun finishDot() {
        current.moveTo(anchorX, anchorY)
        current.lineTo(anchorX + 0.8f, anchorY)
    }

    /** 用中点做二次曲线，稀疏的采样也不会被连成一条直棍。 */
    private fun penTo(x: Float, y: Float) {
        val midX = (penX + x) / 2f
        val midY = (penY + y) / 2f
        current.quadTo(penX, penY, midX, midY)
        penX = x
        penY = y
    }

    override fun onDraw(canvas: Canvas) {
        strokes.forEach { canvas.drawPath(it, paint) }
        if (showDot) canvas.drawCircle(anchorX, anchorY, paint.strokeWidth / 2f, dotPaint)
        canvas.drawPath(current, paint)
    }
}
