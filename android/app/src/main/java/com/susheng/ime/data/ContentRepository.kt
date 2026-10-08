package com.susheng.ime.data

import android.content.Context
import com.susheng.ime.engine.Candidate
import com.susheng.ime.engine.ContentItem
import com.susheng.ime.engine.ImeRules
import com.susheng.ime.engine.LexEntry
import com.susheng.ime.engine.LexiconBin
import com.susheng.ime.engine.WubiTable
import com.susheng.ime.ime.LibrimeBridge
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.LinkedHashMap

class ContentRepository(private val context: Context) {
    private val packFile = File(context.filesDir, "content-pack.json")
    private val sessionFile = File(context.filesDir, "session.json")
    private val assetDir = File(context.filesDir, "assets").apply { mkdirs() }
    private val rimeUser = File(context.filesDir, "rime-user").apply { mkdirs() }
    private val rimeShared = File(context.filesDir, "rime-shared").apply { mkdirs() }
    private val pickFile = File(context.filesDir, "pinyin-picks.txt")
    private var picks: LinkedHashMap<String, String>? = null

    private var lexiconBin: LexiconBin? = null
    var items: List<ContentItem> = emptyList()
        private set
    var serverVersion: Int = 0
        private set
    var epoch: Int = 0
        private set

    fun loadLocal() {
        if (lexiconBin == null) loadLexicon()
        if (!packFile.exists()) {
            items = emptyList()
            return
        }
        val root = JSONObject(packFile.readText())
        serverVersion = root.optInt("serverVersion")
        epoch = root.optInt("epoch")
        items = parseItems(root.optJSONArray("items") ?: JSONArray())
    }

    fun session(): JSONObject? {
        if (!sessionFile.exists()) return null
        return JSONObject(sessionFile.readText())
    }

    fun saveSession(server: String, token: String, displayName: String) {
        sessionFile.writeText(JSONObject().put("server", server.trimEnd('/')).put("token", token).put("displayName", displayName).toString())
    }

    fun clearSession() {
        sessionFile.delete()
    }

    fun sync(): String {
        val session = session() ?: return "请先登录"
        val server = session.getString("server")
        val token = session.getString("token")
        val since = if (epoch == 0) 0 else serverVersion
        val response = request(server, "/api/sync?since=$since&epoch=$epoch", token)
        val root = JSONObject(response)
        if (root.has("error")) return root.getString("error")
        val incoming = parseItems(root.getJSONArray("items"))
        val full = root.optInt("since") == 0 || root.optInt("epoch") != epoch
        items = if (full) incoming.filter { !it.deleted } else merge(items, incoming)
        serverVersion = root.optInt("serverVersion")
        epoch = root.optInt("epoch")
        writePack()
        downloadAssets(server, token)
        writeUserDict()
        LibrimeBridge.setup(rimeShared.absolutePath, rimeUser.absolutePath)
        return "已同步到版本 $serverVersion"
    }

    fun reportUsage(itemId: String, surface: String) {
        val session = session() ?: return
        Thread {
            runCatching {
                request(session.getString("server"), "/api/usage", session.getString("token"), """{"itemId":"$itemId","surface":"$surface"}""")
            }
        }.start()
    }

    fun assetFile(assetId: String): File = File(assetDir, assetId)

    private fun merge(current: List<ContentItem>, incoming: List<ContentItem>): List<ContentItem> {
        val map = current.filter { !it.deleted }.associateBy { it.id }.toMutableMap()
        for (item in incoming) {
            if (item.deleted) map.remove(item.id) else map[item.id] = item
        }
        return map.values.toList()
    }

    private fun writePack() {
        val array = JSONArray()
        for (item in items) array.put(itemJson(item))
        packFile.writeText(JSONObject().put("serverVersion", serverVersion).put("epoch", epoch).put("items", array).toString())
    }

    private fun downloadAssets(server: String, token: String) {
        for (item in items) {
            val assetId = item.assetId ?: continue
            val file = assetFile(assetId)
            if (file.exists()) continue
            runCatching {
                val connection = open(server, "/api/assets/$assetId", token)
                connection.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun writeUserDict() {
        val lines = ArrayList<String>()
        lines.add("# 后台下发的官方词，提交正文")
        lines.add("---")
        lines.add("name: susheng")
        lines.add("version: \"1\"")
        lines.add("sort: by_weight")
        lines.add("use_preset_vocabulary: false")
        lines.add("...")
        for (item in items) {
            if (item.deleted || item.type == "image" || item.pinyin.isBlank()) continue
            val word = item.body.replace("\t", " ").replace("\n", "")
            if (word.isBlank()) continue
            lines.add("$word\t${item.pinyin}\t9000")
        }
        File(rimeUser, "susheng.user.dict.yaml").writeText(lines.joinToString("\n"))
        copySchemas()
    }

    private fun copySchemas() {
        val names = listOf("susheng_pinyin.schema.yaml", "susheng_t9.schema.yaml", "susheng_phrase.schema.yaml", "susheng.dict.yaml")
        for (name in names) {
            runCatching {
                context.assets.open(name).use { input -> File(rimeShared, name).outputStream().use { input.copyTo(it) } }
            }
        }
    }

    fun warmLexicon() {
        ensureWubi()
        lexiconBin?.warm()
    }

    fun ensureWubi() {
        if (WubiTable.ready()) return
        synchronized(this) {
            if (WubiTable.ready()) return
            runCatching {
                context.assets.open("wubi86.txt").bufferedReader().use { WubiTable.load(it.lineSequence()) }
            }
        }
    }

    fun lookup(raw: String, mode: String): List<LexEntry> = lexiconBin?.shortlist(raw, mode) ?: emptyList()

    fun sentences(raw: String): List<Candidate> = lexiconBin?.sentences(raw) ?: emptyList()

    fun lookupAssociate(word: String): List<LexEntry> = lexiconBin?.associate(word) ?: emptyList()

    fun rememberPick(raw: String, word: String) {
        val key = raw.lowercase().filter { it in 'a'..'z' }
        if (key.length < 2 || word.isBlank()) return
        val map = picks()
        map.remove(key)
        map[key] = word
        while (map.size > 400) {
            val eldest = map.keys.firstOrNull() ?: break
            map.remove(eldest)
        }
        pickFile.writeText(map.entries.joinToString("\n") { "${it.key}\t${it.value}" })
    }

    fun applyPick(raw: String, rows: List<Candidate>): List<Candidate> {
        val key = raw.lowercase().filter { it in 'a'..'z' }
        val picked = picks()[key] ?: return rows
        if (rows.none { it.commit == picked }) return rows
        return rows.map { if (it.commit == picked) it.copy(score = it.score + 800) else it }
            .sortedByDescending { it.score }
    }

    private fun picks(): LinkedHashMap<String, String> {
        picks?.let { return it }
        val map = LinkedHashMap<String, String>()
        if (pickFile.exists()) {
            for (line in pickFile.readLines()) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                map[line.substring(0, tab)] = line.substring(tab + 1)
            }
        }
        picks = map
        return map
    }

    private fun loadCharPrior(): Map<String, Int> {
        return runCatching {
            val text = context.assets.open("char-prior.json").bufferedReader().use { it.readText() }
            val root = JSONObject(text)
            val map = HashMap<String, Int>(root.length())
            val keys = root.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = root.getInt(key)
            }
            map
        }.getOrDefault(emptyMap())
    }

    private fun loadLexicon() {
        runCatching {
            ImeRules.load(context.assets.open("ime-rules.json").bufferedReader().use { it.readText() })
        }
        lexiconBin = runCatching {
            LexiconBin(context.assets.open("lexicon.bin").use { it.readBytes() }, loadCharPrior())
        }.getOrNull()
    }

    private fun request(server: String, path: String, token: String?, body: String? = null): String {
        val connection = open(server, path, token, body)
        val stream = if (connection.responseCode >= 400) connection.errorStream else connection.inputStream
        return stream.bufferedReader().readText()
    }

    private fun open(server: String, path: String, token: String?, body: String? = null): HttpURLConnection {
        val connection = URL(server + path).openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        return connection
    }

    fun login(server: String, username: String, password: String): String {
        val base = server.trimEnd('/')
        val payload = JSONObject().put("username", username).put("password", password).toString()
        val connection = open(base, "/api/auth/login", null, payload)
        val text = (if (connection.responseCode >= 400) connection.errorStream else connection.inputStream).bufferedReader().readText()
        val root = JSONObject(text)
        if (connection.responseCode >= 400) return root.optString("error", "登录失败")
        saveSession(base, root.getString("token"), root.getJSONObject("user").optString("displayName"))
        return "已登录"
    }
}

private fun parseItems(array: JSONArray): List<ContentItem> = List(array.length()) { index ->
    val row = array.getJSONObject(index)
    val aliases = row.optJSONArray("aliases")
    ContentItem(
        id = row.getString("id"),
        type = row.getString("type"),
        title = row.optString("title"),
        body = row.optString("body"),
        aliases = if (aliases == null) emptyList() else List(aliases.length()) { aliases.getString(it) },
        pinyin = row.optString("pinyin"),
        shortcut = row.optString("shortcut"),
        groupId = row.optString("groupId"),
        groupName = row.optString("groupName"),
        assetId = row.optString("assetId").ifBlank { null },
        mime = row.optString("mime").ifBlank { null },
        validFrom = if (row.isNull("validFrom")) null else row.optLong("validFrom"),
        validTo = if (row.isNull("validTo")) null else row.optLong("validTo"),
        status = row.optString("status"),
        version = row.optInt("version"),
        deleted = row.optBoolean("deleted")
    )
}

private fun itemJson(item: ContentItem): JSONObject {
    val aliases = JSONArray()
    item.aliases.forEach { aliases.put(it) }
    return JSONObject()
        .put("id", item.id)
        .put("type", item.type)
        .put("title", item.title)
        .put("body", item.body)
        .put("aliases", aliases)
        .put("pinyin", item.pinyin)
        .put("shortcut", item.shortcut)
        .put("groupId", item.groupId)
        .put("groupName", item.groupName)
        .put("assetId", item.assetId)
        .put("mime", item.mime)
        .put("validFrom", item.validFrom)
        .put("validTo", item.validTo)
        .put("status", item.status)
        .put("version", item.version)
        .put("deleted", item.deleted)
}
