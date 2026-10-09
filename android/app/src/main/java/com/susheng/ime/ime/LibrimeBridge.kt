package com.susheng.ime.ime

object LibrimeBridge {
    @Volatile private var loaded = false
    @Volatile private var attempted = false

    val available: Boolean
        get() {
            ensureLoaded()
            return loaded && nativeAvailable()
        }

    fun setup(sharedDir: String, userDir: String): Boolean {
        if (!available) return false
        return nativeSetup(sharedDir, userDir)
    }

    fun context(raw: String): String {
        if (!available || raw.isEmpty()) return ""
        val session = nativeCreateSession()
        if (session == 0L) return ""
        try {
            // 按码点迭代，避免 BMP 外字符（代理对）被拆成两个错误 keycode。
            var i = 0
            while (i < raw.length) {
                val cp = raw.codePointAt(i)
                if (!nativeProcessKey(session, cp)) return ""
                i += Character.charCount(cp)
            }
            return nativeContext(session) ?: ""
        } finally {
            nativeDestroySession(session)
        }
    }

    @Synchronized
    private fun ensureLoaded() {
        if (attempted) return
        attempted = true
        try {
            System.loadLibrary("susheng_rime")
            loaded = true
        } catch (_: Throwable) {
            // 除 UnsatisfiedLinkError 外，SecurityException/JNI_OnLoad 抛出的错误也应回退为不可用，避免崩溃调用方。
            loaded = false
        }
    }

    private external fun nativeAvailable(): Boolean
    private external fun nativeSetup(sharedDir: String, userDir: String): Boolean
    private external fun nativeCreateSession(): Long
    private external fun nativeProcessKey(session: Long, keycode: Int): Boolean
    private external fun nativeContext(session: Long): String?
    private external fun nativeDestroySession(session: Long)
}
