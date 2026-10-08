package com.susheng.ime.ime

object LibrimeBridge {
    private var loaded = false
    private var attempted = false

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
            for (ch in raw) nativeProcessKey(session, ch.code)
            return nativeContext(session)
        } finally {
            nativeDestroySession(session)
        }
    }

    private fun ensureLoaded() {
        if (attempted) return
        attempted = true
        try {
            System.loadLibrary("susheng_rime")
            loaded = true
        } catch (_: UnsatisfiedLinkError) {
            loaded = false
        }
    }

    private external fun nativeAvailable(): Boolean
    private external fun nativeSetup(sharedDir: String, userDir: String): Boolean
    private external fun nativeCreateSession(): Long
    private external fun nativeProcessKey(session: Long, keycode: Int): Boolean
    private external fun nativeContext(session: Long): String
    private external fun nativeDestroySession(session: Long)
}
