package com.susheng.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.os.Handler
import android.os.Looper
import android.annotation.SuppressLint
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.susheng.ime.MainActivity
import com.susheng.ime.data.ContentRepository
import com.susheng.ime.skin.KeyboardPrefs
import com.susheng.ime.engine.Candidate
import com.susheng.ime.engine.ContentItem
import com.susheng.ime.engine.HandModel
import com.susheng.ime.engine.HandPinyin
import com.susheng.ime.engine.PinyinEngine
import com.susheng.ime.engine.StrokeTable
import com.susheng.ime.engine.WubiTable
import org.json.JSONArray
import java.io.File

class SushengInputMethodService : InputMethodService() {
    private lateinit var repo: ContentRepository
    private lateinit var clipboard: ClipboardManager
    private var chrome: ImeChrome? = null
    private var board = Board.PINYIN9
    private var shift = false
    private var raw = ""
    private var handCandidates = emptyList<Candidate>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCommit = ""
    private var associating = false
    internal var keyScale = 1f
    internal var handBias = 0
    internal var hapticOn = true
    internal var skinId = 0
    private val clips = mutableListOf<String>()
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip(silent = true) }
    private var warmThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        repo = ContentRepository(this)
        repo.loadLocal()
        clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.addPrimaryClipChangedListener(clipListener)
        readPrefs()
        loadClips()
        Thread {
            runCatching {
                repo.warmLexicon()
                HandPinyin.ensure(this)
                HandModel.ensure(this)
            }
        }.apply { name = "susheng-lexicon" }.also { warmThread = it }.start()
    }

    override fun onDestroy() {
        if (::clipboard.isInitialized) clipboard.removePrimaryClipChangedListener(clipListener)
        // 预热线程不应比 Service 活得更久；中断以尽快释放（I/O 中断由 runCatching 兜底）。
        warmThread?.interrupt()
        warmThread = null
        super.onDestroy()
    }

    @SuppressLint("InflateParams")
    override fun onCreateInputView(): View {
        val ui = chrome ?: ImeChrome(this).also { chrome = it }
        return ui.create()
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // 上屏后很多输入框会 restartInput。这时不能把刚提交的字的联想清掉，
        // 也不能在主线程上把整本词库再解析一遍，否则字上了屏、联想是空的，接着内存耗尽崩溃。
        if (!restarting) repo.loadLocal()
        if (isPassword(info)) {
            switchToNextInputMethod(false)
            return
        }
        if (!restarting) {
            raw = ""
            handCandidates = emptyList()
            associating = false
        }
        val ui = chrome ?: return
        ui.searching = false
        ui.query = ""
        val wasKeys = ui.face == Face.KEYS
        ui.face = Face.KEYS
        ui.symbolPage = false
        ui.numberPage = false
        val previousSkin = skinId
        readPrefs()
        if (wasKeys && previousSkin == skinId) ui.updateCandidates() else relayout()
    }

    private fun readPrefs() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        keyScale = prefs.getFloat("scale", keyScale)
        handBias = prefs.getInt("hand", handBias)
        hapticOn = prefs.getBoolean("haptic", hapticOn)
        skinId = prefs.getInt("skin", skinId)
        board = Board.from(prefs.getString("board", board.id).orEmpty())
        if (board == Board.STROKE) board = Board.PINYIN26
    }

    internal fun relayout() {
        val ui = chrome ?: return
        setInputView(ui.create())
    }

    internal fun rawInput() = raw

    internal fun shownCandidates(): List<Candidate> = currentCandidates()

    internal fun chooseCandidate(candidate: Candidate) = choose(candidate)

    internal fun onLetter(value: String) {
        if (chrome?.takeQuery(value) == true) return
        type(value)
        if (board == Board.WUBI && raw.length >= 4) {
            repo.ensureWubi()
            val word = WubiTable.first(raw)
            if (word != null) {
                choose(Candidate(word, word, "lexicon", "lexicon", null, null, "", 1000))
                chrome?.showComposing()
                chrome?.scheduleCandidates()
                return
            }
        }
        chrome?.showComposing()
        chrome?.scheduleCandidates()
    }

    internal fun onBackspace(editingQuery: Boolean = false) {
        if (editingQuery && chrome?.takeBackspace() == true) return
        backspace()
        chrome?.showComposing()
        chrome?.scheduleCandidates()
    }

    internal fun onSpace() {
        space()
        chrome?.showComposing()
        chrome?.scheduleCandidates()
    }

    internal fun onEnter() {
        enter()
        chrome?.updateCandidates()
    }

    internal fun onPunct(text: String) {
        val first = currentCandidates().firstOrNull()
        if (raw.isNotEmpty() && first != null) choose(first)
        else if (raw.isNotEmpty()) {
            commit(raw)
            raw = ""
            associating = false
        }
        commit(text)
        chrome?.showComposing()
        chrome?.scheduleCandidates()
    }

    internal fun toggleChinese() {
        setBoard(if (board == Board.ENGLISH) Board.PINYIN26 else Board.ENGLISH)
    }

    internal fun setBoard(next: Board) {
        board = next
        shift = false
        raw = ""
        associating = false
        chrome?.symbolPage = false
        chrome?.numberPage = false
        chrome?.face = Face.KEYS
        chrome?.clearHand()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("board", next.id).apply()
        relayout()
    }

    internal fun setNine(enabled: Boolean) = setBoard(if (enabled) Board.PINYIN9 else Board.PINYIN26)

    internal fun board() = board

    internal fun shuangpinName(): String = getSharedPreferences(PREFS, MODE_PRIVATE).getString("shuangpin", "关闭").orEmpty()

    internal fun setShuangpin(name: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("shuangpin", name).apply()
        chrome?.face = Face.BOARDS
        relayout()
    }

    internal fun isChinese() = board != Board.ENGLISH

    internal fun isNine() = board == Board.PINYIN9

    internal fun capsKeys() = board == Board.PINYIN26 || board == Board.WUBI

    internal fun composeLabel(): String {
        if (board == Board.HAND) return handCandidates.firstOrNull()?.hint.orEmpty()
        if (board == Board.STROKE) {
            return raw.map { StrokeTable.symbol(it) }.joinToString("")
        }
        if (board == Board.PINYIN9) {
            val hint = currentCandidates().firstOrNull()?.hint.orEmpty()
            return PinyinEngine.t9Preview(raw, hint)
        }
        return raw
    }

    internal fun onHandNext() {
        val first = handCandidates.firstOrNull() ?: return
        // 新的一笔已经落在画布上。这里只提交上一个字，不能把正在写的笔迹清掉。
        choose(first, keepInk = true)
    }

    internal fun onHandSample(token: Int, strokes: List<List<PointF>>) {
        Thread {
            var bitmap: android.graphics.Bitmap? = null
            // 用 try/catch/finally 包住识别：inkBitmap/recognize 抛异常时线程不会静默死亡，
            // bitmap 总能回收，并向主线程 post 一个终态（空候选）。
            val chars = try {
                HandPinyin.ensure(this)
                bitmap = inkBitmap(strokes)
                val bmp = bitmap
                if (bmp != null && HandModel.ensure(this)) HandModel.recognize(bmp, 8) else emptyList()
            } catch (t: Throwable) {
                emptyList()
            } finally {
                bitmap?.recycle()
            }
            mainHandler.post {
                if (chrome?.handToken() != token) return@post
                if (chars.isEmpty()) return@post
                handCandidates = handRows(chars)
                associating = false
                chrome?.showComposing()
                chrome?.updateCandidates()
            }
        }.apply { name = "susheng-hand" }.start()
    }

    /** 按手指轨迹重画一张细笔迹，再交给手写模型。屏幕上的粗线不拿去认。 */
    private fun inkBitmap(strokes: List<List<PointF>>): Bitmap? {
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for (stroke in strokes) {
            for (point in stroke) {
                minX = kotlin.math.min(minX, point.x)
                minY = kotlin.math.min(minY, point.y)
                maxX = kotlin.math.max(maxX, point.x)
                maxY = kotlin.math.max(maxY, point.y)
            }
        }
        if (minX == Float.POSITIVE_INFINITY) return null
        val spanX = kotlin.math.max(maxX - minX, 1f)
        val spanY = kotlin.math.max(maxY - minY, 1f)
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val scale = size * 0.84f / kotlin.math.max(spanX, spanY)
        val ox = (size - spanX * scale) / 2f - minX * scale
        val oy = (size - spanY * scale) / 2f - minY * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            strokeWidth = size * 0.045f
        }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.FILL
        }
        for (stroke in strokes) {
            if (stroke.size < 2) {
                val point = stroke.firstOrNull() ?: continue
                canvas.drawCircle(point.x * scale + ox, point.y * scale + oy, paint.strokeWidth / 2f, dot)
                continue
            }
            val path = Path()
            path.moveTo(stroke[0].x * scale + ox, stroke[0].y * scale + oy)
            for (index in 1 until stroke.size) {
                path.lineTo(stroke[index].x * scale + ox, stroke[index].y * scale + oy)
            }
            canvas.drawPath(path, paint)
        }
        return bitmap
    }

    private fun handRows(chars: List<String>): List<Candidate> {
        val rows = ArrayList<Candidate>()
        val seen = HashSet<String>()
        chars.take(6).forEachIndexed { index, text ->
            if (text.isEmpty() || !seen.add(text)) return@forEachIndexed
            rows.add(Candidate(text, text, "hand", "lexicon", null, null, HandPinyin.of(text), 3000 - index))
        }
        val top = chars.firstOrNull().orEmpty()
        if (top.isNotEmpty()) {
            val associated = PinyinEngine.associate(
                top,
                repo.lookupAssociate(top),
                repo.items,
                commitSuffix = false,
                limit = 6
            )
            for (row in associated) {
                if (!seen.add(row.text)) continue
                val hint = row.hint.ifEmpty { HandPinyin.of(row.text) }
                rows.add(row.copy(hint = hint))
                if (rows.size >= 12) break
            }
        }
        chars.drop(6).forEachIndexed { index, text ->
            if (!seen.add(text)) return@forEachIndexed
            rows.add(Candidate(text, text, "hand", "lexicon", null, null, HandPinyin.of(text), 800 - index))
        }
        return rows
    }

    internal fun onStroke(code: Char) {
        when (code) {
            '7' -> {
                val first = currentCandidates().firstOrNull()
                if (first != null) choose(first) else if (raw.isNotEmpty()) {
                    commit(composeLabel())
                    raw = ""
                }
            }
            '8' -> onPunct("：")
            '9' -> onPunct("；")
            else -> {
                raw += code
                associating = false
                chrome?.showComposing()
                chrome?.scheduleCandidates()
            }
        }
    }
    internal fun isShift() = shift

    internal fun toggleShift() {
        shift = !shift
        relayout()
    }

    internal fun applyScale(value: Float) {
        keyScale = value
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat("scale", value).apply()
        relayout()
    }

    internal fun setHand(value: Int) {
        handBias = value
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("hand", value).apply()
        relayout()
    }

    internal fun toggleHaptic() {
        hapticOn = !hapticOn
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean("haptic", hapticOn).apply()
        relayout()
    }

    internal fun setSkin(value: Int) {
        skinId = value
        KeyboardPrefs.applySkin(this, value)
        relayout()
    }

    internal fun displayName(): String = repo.session()?.optString("displayName").orEmpty().ifBlank { "苏晟用户" }

    internal fun itemsOf(types: Set<String>): List<ContentItem> {
        return repo.items.filter { it.type in types && !it.deleted && it.status == "published" }
    }

    internal fun insertContent(item: ContentItem) = insertItem(item)

    internal fun assetFile(item: ContentItem): File? {
        val id = item.assetId ?: return null
        val file = repo.assetFile(id)
        return file.takeIf { it.exists() }
    }

    internal fun clipHistory(): List<String> = clips.toList()

    internal fun clearClips() {
        clips.clear()
        saveClips()
        relayout()
    }

    internal fun pullClip(): String? = captureClip(silent = false)

    internal fun commitPlain(text: String) {
        commit(text)
        lastCommit = text.take(12)
        associating = false
        raw = ""
        chrome?.updateCandidates()
    }

    internal fun rememberEmoji(symbol: String) {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val current = prefs.getString("recent", "").orEmpty().split('\n').filter { it.isNotEmpty() }
        val next = listOf(symbol) + current.filter { it != symbol }
        prefs.edit().putString("recent", next.take(24).joinToString("\n")).apply()
    }

    internal fun recentEmoji(): List<String> {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getString("recent", "").orEmpty().split('\n').filter { it.isNotEmpty() }
    }

    internal fun hideKeyboard() = requestHideSelf(0)

    internal fun pickIme() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    internal fun openHome() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    internal fun voice() {
        val intent = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { startActivity(intent) }.onFailure { toast("当前设备没有语音输入") }
    }

    internal fun actionLabel(): String {
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        return when (action) {
            EditorInfo.IME_ACTION_SEARCH -> "搜索"
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_NEXT -> "下一项"
            EditorInfo.IME_ACTION_SEND -> "发送"
            EditorInfo.IME_ACTION_DONE -> "完成"
            else -> "发送"
        }
    }

    internal fun buzz(view: View) {
        if (!hapticOn) return
        // 保存并恢复原值，避免永久改变复用 view 的声音反馈开关。
        val prev = view.isSoundEffectsEnabled
        view.isSoundEffectsEnabled = false
        view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
        view.isSoundEffectsEnabled = prev
    }

    private fun isPassword(info: EditorInfo): Boolean {
        val variation = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        return variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD
    }

    private fun type(value: String) {
        associating = false
        if (!isChinese()) {
            val text = if (shift && value.length == 1 && value[0].isLetter()) value.uppercase() else value
            commit(text)
            return
        }
        if (value == "'") {
            raw += "'"
            return
        }
        if (value.length == 1 && (value[0].isLetter() || value[0].isDigit())) raw += value.lowercase()
        else commit(value)
    }

    private fun backspace() {
        associating = false
        if (board == Board.HAND && handCandidates.isNotEmpty() && chrome?.handHasInk() != true) {
            handCandidates = emptyList()
            chrome?.updateCandidates()
            return
        }
        if (raw.isNotEmpty()) raw = raw.dropLast(1) else currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun space() {
        val first = currentCandidates().firstOrNull()
        if (first != null && (raw.isNotEmpty() || associating || handCandidates.isNotEmpty())) choose(first)
        else if (board == Board.WUBI && raw.isNotEmpty()) return
        else commit(" ")
    }

    private fun enter() {
        val first = currentCandidates().firstOrNull()
        if ((raw.isNotEmpty() || handCandidates.isNotEmpty()) && first != null) {
            choose(first)
            return
        }
        val action = currentInputEditorInfo?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            currentInputConnection?.performEditorAction(action)
        } else {
            commit("\n")
        }
    }

    private fun currentCandidates(): List<Candidate> {
        if (board == Board.HAND && handCandidates.isNotEmpty()) return handCandidates
        if (board == Board.ENGLISH) {
            if (associating && lastCommit.isNotEmpty()) return PinyinEngine.associate(lastCommit, repo.lookupAssociate(lastCommit), repo.items)
            return emptyList()
        }
        if (raw.isNotEmpty()) {
            if (board == Board.STROKE) {
                return StrokeTable.candidates(raw).map {
                    Candidate(it, it, "lexicon", "lexicon", null, null, "", 1000)
                }
            }
            if (board == Board.WUBI) {
                repo.ensureWubi()
                return WubiTable.candidates(raw).mapIndexed { index, word ->
                    Candidate(word, word, "lexicon", "lexicon", null, null, "", 1000 - index)
                }
            }
            val mode = if (board == Board.PINYIN9) "t9" else "qwerty"
            val sentences = if (board == Board.PINYIN9) emptyList() else repo.sentences(raw)
            val builtin = PinyinEngine.candidates(raw, mode, repo.lookup(raw, mode), repo.items, sentences = sentences)
            val rime = LibrimeBridge.context(raw).lineSequence().drop(1).filter { it.isNotBlank() }.map {
                Candidate(it, it, "rime", "lexicon", null, null, "", 0)
            }
            val rows = if (rime.none()) builtin else (builtin.filter { it.source == "official" } + rime).distinctBy { it.commit }
            return repo.applyPick(raw, rows)
        }
        if (associating && lastCommit.isNotEmpty()) return PinyinEngine.associate(lastCommit, repo.lookupAssociate(lastCommit), repo.items)
        return emptyList()
    }

    private fun choose(candidate: Candidate, keepInk: Boolean = false) {
        if (candidate.kind == "image" && candidate.assetId != null) {
            commitImage(repo.assetFile(candidate.assetId), candidate.itemId)
        } else {
            val typed = raw
            commit(candidate.commit)
            lastCommit = candidate.text
            val remain = candidate.keep
            val consumed = if (remain > 0 && remain < typed.length) typed.dropLast(remain) else typed
            if (candidate.commit.isNotEmpty()) repo.rememberPick(consumed, candidate.commit)
            raw = if (remain > 0 && remain < typed.length) typed.takeLast(remain) else ""
            associating = raw.isEmpty() && candidate.commit.isNotEmpty()
            handCandidates = emptyList()
            if (!keepInk && raw.isEmpty()) chrome?.clearHand()
        }
        if (candidate.itemId != null) repo.reportUsage(candidate.itemId, candidate.kind)
        if (candidate.kind == "image") raw = ""
        chrome?.updateCandidates()
    }

    private fun commit(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    private fun insertItem(item: ContentItem) {
        if (item.type == "image") {
            val id = item.assetId
            if (id != null) commitImage(repo.assetFile(id), item.id)
        } else {
            commit(item.body)
            lastCommit = item.title
            associating = true
            repo.reportUsage(item.id, item.type)
        }
        raw = ""
        chrome?.face = Face.KEYS
        chrome?.updateCandidates()
        relayout()
    }

    private fun commitImage(file: File, itemId: String?) {
        if (!file.exists()) {
            toast("图片还没同步下来")
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        // MIME 由实际文件推导，避免把 JPG/WebP 当作 PNG 广播导致 commitContent 被拒。
        val mime = contentResolver.getType(uri)?.takeIf { it.startsWith("image/") } ?: mimeFromName(file.name)
        val info = InputContentInfoCompat(uri, ClipDescription("苏晟图片", arrayOf(mime)), null)
        val editor = currentInputEditorInfo
        val connection = currentInputConnection
        var inserted = false
        if (editor != null && connection != null) {
            grantUriPermission(editor.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            inserted = InputConnectionCompat.commitContent(
                connection, editor, info, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null
            )
        }
        if (!inserted) {
            val clip = android.content.ClipData.newUri(contentResolver, "苏晟图片", uri)
            clipboard.setPrimaryClip(clip)
            toast("已复制图片，请在输入框长按粘贴")
        }
        if (itemId != null) repo.reportUsage(itemId, "image")
    }

    private fun captureClip(silent: Boolean): String? {
        val clip = runCatching { clipboard.primaryClip }.getOrNull()
        if (clip == null || clip.itemCount == 0) {
            if (!silent) toast("剪贴板是空的")
            return null
        }
        if (clip.description?.label == "苏晟图片") return null
        val text = clip.getItemAt(0).coerceToText(this)?.toString()?.trim().orEmpty()
        if (text.isEmpty() || text.startsWith("content://")) {
            if (!silent) toast("剪贴板里没有文字")
            return null
        }
        clips.remove(text)
        clips.add(0, text)
        while (clips.size > 500) clips.removeAt(clips.lastIndex)
        saveClips()
        return text
    }

    private fun loadClips() {
        val file = File(filesDir, "clipboard-history.json")
        if (!file.exists()) return
        runCatching {
            val array = JSONArray(file.readText())
            clips.clear()
            for (index in 0 until array.length()) clips.add(array.getString(index))
        }
    }

    private fun saveClips() {
        val array = JSONArray()
        clips.forEach { array.put(it) }
        // 写入失败（磁盘满/IO 错）不应崩溃 IME。
        runCatching { File(filesDir, "clipboard-history.json").writeText(array.toString()) }
    }

    private fun mimeFromName(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }

    private fun toast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val PREFS = KeyboardPrefs.NAME
    }
}
