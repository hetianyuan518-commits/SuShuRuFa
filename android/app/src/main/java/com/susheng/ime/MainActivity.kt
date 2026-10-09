package com.susheng.ime

import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.susheng.ime.data.ContentRepository
import com.susheng.ime.skin.KeyboardPrefs
import com.susheng.ime.skin.KeyboardSkins
import com.susheng.ime.ui.HomeTab
import com.susheng.ime.ui.ImeSystem
import com.susheng.ime.ui.guidePage
import com.susheng.ime.ui.homePage
import com.susheng.ime.ui.legalPage
import com.susheng.ime.ui.privacyPage
import com.susheng.ime.ui.settingsPage

class MainActivity : AppCompatActivity() {
    private lateinit var repo: ContentRepository
    private var screen = Screen.GUIDE
    private var tab = HomeTab.STORE
    private var legalTitle = "隐私政策"
    private var legalBack = Screen.PRIVACY
    private var leftForSystem = false
    private var obscured = false
    private var guideDialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFFF4F5F7.toInt()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        repo = ContentRepository(this)
        if (savedInstanceState != null) {
            screen = runCatching { Screen.valueOf(savedInstanceState.getString("screen", Screen.GUIDE.name)) }.getOrDefault(Screen.GUIDE)
            tab = runCatching { HomeTab.valueOf(savedInstanceState.getString("tab", HomeTab.STORE.name)) }.getOrDefault(HomeTab.STORE)
            legalTitle = savedInstanceState.getString("legal", legalTitle)
            leftForSystem = savedInstanceState.getBoolean("leftForSystem")
            obscured = savedInstanceState.getBoolean("obscured")
        } else {
            screen = openingScreen()
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (screen) {
                    Screen.LEGAL -> {
                        screen = legalBack
                        show()
                    }
                    Screen.SETTINGS -> {
                        screen = Screen.HOME
                        tab = HomeTab.MINE
                        show()
                    }
                    Screen.PRIVACY -> {
                        screen = Screen.GUIDE
                        show()
                    }
                    else -> {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        // 默认返回未被消费（Activity 未 finish）时重新启用，避免后续返回键绕过状态机。
                        isEnabled = true
                    }
                }
            }
        })
        show()
        // 词表/内容包的磁盘解析放到后台线程，避免主线程阻塞导致掉帧/ANR；
        // 加载完成后仅在依赖 repo 的界面（HOME/SETTINGS）重绘。
        loadRepoAsync()
    }

    private fun loadRepoAsync() {
        Thread {
            repo.loadLocal()
            runOnUiThread {
                if (!isFinishing && (screen == Screen.HOME || screen == Screen.SETTINGS)) show()
            }
        }.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("screen", screen.name)
        outState.putString("tab", tab.name)
        outState.putString("legal", legalTitle)
        outState.putBoolean("leftForSystem", leftForSystem)
        outState.putBoolean("obscured", obscured)
        super.onSaveInstanceState(outState)
    }

    override fun onResume() {
        super.onResume()
        revealPrivacyIfReturned()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus && leftForSystem) obscured = true
        if (hasFocus) revealPrivacyIfReturned()
    }

    private fun revealPrivacyIfReturned() {
        if (screen != Screen.GUIDE || !obscured) return
        if (KeyboardPrefs.accepted(this) || !KeyboardPrefs.awaitPrivacy(this)) return
        obscured = false
        leftForSystem = false
        KeyboardPrefs.clearAwaitPrivacy(this)
        screen = Screen.PRIVACY
        show()
    }

    private fun openingScreen(): Screen {
        if (KeyboardPrefs.accepted(this)) return Screen.HOME
        if (KeyboardPrefs.awaitPrivacy(this)) {
            KeyboardPrefs.clearAwaitPrivacy(this)
            return Screen.PRIVACY
        }
        return Screen.GUIDE
    }

    private fun show() {
        val view = when (screen) {
            Screen.GUIDE -> guidePage(this, { openSystem(enable = true) }, { openSystem(enable = false) })
            Screen.PRIVACY -> privacyPage(this, { acceptPrivacy() }, { finishAffinity() }, { openDoc(it, Screen.PRIVACY) })
            Screen.LEGAL -> legalPage(this, legalTitle) {
                screen = legalBack
                show()
            }
            Screen.HOME -> homePage(this, repo, tab, { tab = it; show() }, { applySkin(it) }, { openSettings() }, { openDoc(it, Screen.HOME) })
            Screen.SETTINGS -> settingsPage(this, repo, {
                screen = Screen.HOME
                tab = HomeTab.MINE
                show()
            }, { openSystem(enable = true) }, { openSystem(enable = false) }, { openDoc(it, Screen.SETTINGS) })
        }
        setContentView(view)
    }

    override fun onDestroy() {
        guideDialog?.dismiss()
        super.onDestroy()
    }

    private fun openSystem(enable: Boolean) {
        if (!KeyboardPrefs.accepted(this)) {
            KeyboardPrefs.markAwaitPrivacy(this)
            leftForSystem = true
        }
        val jump = if (enable) ImeSystem.openEnable(this) else ImeSystem.openSwitch(this)
        if (jump == ImeSystem.Jump.NONE) {
            // 未能真正跳转系统设置，回滚待确认隐私的挂起状态，避免后续无关的失焦误触发 PRIVACY。
            leftForSystem = false
            KeyboardPrefs.clearAwaitPrivacy(this)
        } else if (jump != ImeSystem.Jump.INPUT) {
            showImeGuide()
        }
    }

    private fun showImeGuide() {
        if (guideDialog?.isShowing == true) return
        guideDialog = AlertDialog.Builder(this)
            .setTitle("打开输入法页面")
            .setMessage(ImeSystem.guideMessage())
            .setPositiveButton("打开系统设置") { _, _ ->
                if (!ImeSystem.openAnySettings(this)) {
                    Toast.makeText(this, "请手动打开手机「设置」", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("知道了", null)
            .show()
    }

    private fun acceptPrivacy() {
        KeyboardPrefs.accept(this)
        screen = Screen.HOME
        tab = HomeTab.STORE
        show()
    }

    private fun openSettings() {
        screen = Screen.SETTINGS
        show()
        loadRepoAsync()
    }

    private fun openDoc(title: String, back: Screen) {
        legalTitle = title
        legalBack = back
        screen = Screen.LEGAL
        show()
    }

    private fun applySkin(id: Int) {
        KeyboardPrefs.applySkin(this, id)
        Toast.makeText(this, "已切换为${KeyboardSkins.byId(id).name}", Toast.LENGTH_SHORT).show()
        show()
    }

    private enum class Screen { GUIDE, PRIVACY, HOME, SETTINGS, LEGAL }
}
