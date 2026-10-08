package com.susheng.ime.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.InputMethodManager

internal object ImeSystem {
    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val HARMONY_SETTINGS = "com.huawei.hmos.settings"
    private const val HARMONY_ABILITY = "com.huawei.hmos.settings.MainAbility"

    enum class Jump { INPUT, LANGUAGE, NONE }

    fun isHuaweiFamily(): Boolean {
        val blob = listOf(Build.BRAND, Build.MANUFACTURER, Build.DISPLAY, Build.FINGERPRINT)
            .joinToString(" ")
            .lowercase()
        if (listOf("huawei", "honor", "harmony", "emui").any { blob.contains(it) }) return true
        return runCatching {
            Class.forName("com.huawei.system.BuildEx")
                .getMethod("getOsBrand")
                .invoke(null)
                ?.toString()
                .orEmpty()
                .equals("harmony", true)
        }.getOrDefault(false)
    }

    fun harmonyMajor(): Int {
        val platform = systemProperty("hw_sc.build.platform.version")
        val blob = listOf(
            platform,
            systemProperty("ro.build.version.harmony"),
            systemProperty("ro.huawei.build.version.harmony"),
            systemProperty("const.product.os.dist.version"),
            systemProperty("persist.sys.harmony_version"),
            systemProperty("ro.build.display.id"),
            Build.DISPLAY
        ).joinToString(" ")
        val labeled = Regex("""(?i)harmony(?:os)?[^0-9]{0,12}(\d{1,2})""").find(blob)
        if (labeled != null) return labeled.groupValues[1].toInt()
        return Regex("""^(\d{1,2})\.""").find(platform)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun manualPath(): String {
        return when (harmonyMajor()) {
            in 1..4 -> "设置 → 系统和更新 → 语言和输入法 → 输入法（有的机型这一项叫安全输入）"
            5, 6 -> "设置 → 系统 → 输入法"
            in 7..99 -> "设置 → 系统 → 输入法。若这一版把菜单收进了「语言和输入法」，就再点一次「输入法」"
            else -> "鸿蒙 4：设置 → 系统和更新 → 语言和输入法 → 输入法（有的机型叫安全输入）。鸿蒙 5 和 6：设置 → 系统 → 输入法。鸿蒙 7：设置 → 系统 → 输入法，菜单若有调整就在「系统」里进入「输入法」"
        }
    }

    fun manualHint(): String {
        if (!isHuaweiFamily()) return "如果点击后没有跳转：到系统设置里启用苏晟输入法，并把它设为默认输入法。"
        return "如果没有进入输入法页面：${manualPath()}。启用苏晟输入法并设为默认。"
    }

    fun guideMessage(): String {
        if (!isHuaweiFamily()) return "请到系统设置里启用「苏晟输入法」，并把它设为默认输入法。"
        return "请打开系统的「输入法」页面，启用「苏晟输入法」，在风险提示里点同意，再把它设为默认。\n\n${manualPath()}"
    }

    fun openEnable(activity: Activity): Jump {
        if (isHuaweiFamily()) return openHarmonyInput(activity)
        return if (openAndroidInputSettings(activity)) Jump.INPUT else Jump.NONE
    }

    fun openSwitch(activity: Activity): Jump {
        if (isHuaweiFamily()) return openHarmonyInput(activity)
        if (showPicker(activity)) return Jump.INPUT
        return if (openAndroidInputSettings(activity)) Jump.INPUT else Jump.NONE
    }

    fun openAnySettings(activity: Activity): Boolean {
        if (isHuaweiFamily() && openHarmonyInput(activity) != Jump.NONE) return true
        if (!isHuaweiFamily() && openAndroidInputSettings(activity)) return true
        return tryStart(activity, Intent(Settings.ACTION_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        })
    }

    private fun openHarmonyInput(activity: Activity): Jump {
        if (openSetInput(activity)) return Jump.INPUT
        if (openLanguageAndInput(activity)) return Jump.LANGUAGE
        return Jump.NONE
    }

    private fun openSetInput(activity: Activity): Boolean {
        val component = ComponentName(HARMONY_SETTINGS, HARMONY_ABILITY)
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).apply {
                this.component = component
                data = Uri.parse("set_input")
                putExtra("uri", "set_input")
                addCategory(Intent.CATEGORY_DEFAULT)
            },
            Intent(Intent.ACTION_VIEW).apply {
                this.component = component
                data = Uri.parse("settings://set_input")
                putExtra("uri", "set_input")
                addCategory(Intent.CATEGORY_DEFAULT)
            }
        )
        return intents.any { tryStart(activity, it, requireResolve = false) }
    }

    private fun openLanguageAndInput(activity: Activity): Boolean {
        val pages = listOf(
            "com.android.settings.Settings\$InputMethodAndLanguageSettingsActivity",
            "com.android.settings.Settings\$LanguageAndInputSettingsActivity",
            "com.android.settings.LanguageSettings"
        )
        return pages.any { className ->
            tryStart(activity, Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(SETTINGS_PACKAGE, className)
                addCategory(Intent.CATEGORY_DEFAULT)
            })
        }
    }

    private fun openAndroidInputSettings(activity: Activity): Boolean {
        val implicit = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        if (tryStart(activity, implicit)) return true
        val pages = listOf(
            "com.android.settings.Settings\$InputMethodAndLanguageSettingsActivity",
            "com.android.settings.Settings\$LanguageAndInputSettingsActivity",
            "com.android.settings.Settings\$InputMethodSettingsActivity",
            "com.android.settings.Settings\$AvailableVirtualKeyboardActivity"
        )
        return pages.any { className ->
            tryStart(activity, Intent(Intent.ACTION_MAIN).apply {
                component = ComponentName(SETTINGS_PACKAGE, className)
                addCategory(Intent.CATEGORY_DEFAULT)
            })
        }
    }

    private fun showPicker(activity: Activity): Boolean {
        return runCatching {
            activity.getSystemService(InputMethodManager::class.java).showInputMethodPicker()
            true
        }.getOrDefault(false)
    }

    private fun tryStart(activity: Activity, intent: Intent, requireResolve: Boolean = true): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (requireResolve && activity.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) {
            return false
        }
        return runCatching {
            activity.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun systemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            clazz.getMethod("get", String::class.java, String::class.java).invoke(null, key, "") as String
        }.getOrDefault("")
    }
}
