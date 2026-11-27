package io.github.vquand.anddiary

import android.content.Context
import android.content.res.Configuration

internal enum class ThemeMode(val code: Int) {
    AUTO(0),
    LIGHT(1),
    DARK(2);

    companion object {
        fun fromCode(code: Int): ThemeMode = entries.firstOrNull { it.code == code } ?: AUTO
    }
}

internal enum class ToolbarMode(val code: Int) {
    ICON_ONLY(0),
    ICON_AND_LABEL(1),
    LABEL_ONLY(2);

    companion object {
        fun fromCode(code: Int): ToolbarMode = entries.firstOrNull { it.code == code } ?: ICON_ONLY
    }
}

internal enum class DiaryPlugin(val bit: Int) {
    HANZI(1 shl 0),
    CONTOUR_3D(1 shl 1),
}

internal data class DiaryPalette(
    val isDark: Boolean,
    val paper: Int,
    val paperSoft: Int,
    val wash: Int,
    val rule: Int,
    val text: Int,
    val muted: Int,
    val disabled: Int,
    val accent: Int,
    val danger: Int,
    val eraserFill: Int,
) {
    companion object {
        @Volatile
        var LIGHT = fromCoreColors(
            isDark = false,
            paper = 0xfff8f3e8.toInt(),
            wash = 0xffeee5d5.toInt(),
            rule = 0xffe0d7c6.toInt(),
            text = 0xff302e29.toInt(),
            muted = 0xff736c60.toInt(),
            accent = 0xff984631.toInt(),
            danger = 0xff9b3a32.toInt(),
        )
            internal set

        @Volatile
        var DARK = fromCoreColors(
            isDark = true,
            paper = 0xff1b1917.toInt(),
            wash = 0xff2a2622.toInt(),
            rule = 0xff3d3731.toInt(),
            text = 0xffebe4d8.toInt(),
            muted = 0xffa59c8e.toInt(),
            accent = 0xffd97757.toInt(),
            danger = 0xffe06c60.toInt(),
        )
            internal set

        fun fromCoreColors(
            isDark: Boolean,
            paper: Int,
            wash: Int,
            rule: Int,
            text: Int,
            muted: Int,
            accent: Int,
            danger: Int,
        ): DiaryPalette {
            val paperSoft = blendArgb(paper, wash, 0.5f)
            val disabled = blendArgb(muted, paper, 0.35f)
            val eraserFill = ((if (isDark) 0x33 else 0x26) shl 24) or (muted and 0x00ffffff)
            return DiaryPalette(
                isDark = isDark,
                paper = paper,
                paperSoft = paperSoft,
                wash = wash,
                rule = rule,
                text = text,
                muted = muted,
                disabled = disabled,
                accent = accent,
                danger = danger,
                eraserFill = eraserFill,
            )
        }

        private fun blendArgb(a: Int, b: Int, t: Float): Int {
            val inv = 1f - t
            val r = (((a shr 16) and 0xff) * inv + ((b shr 16) and 0xff) * t).toInt().coerceIn(0, 255)
            val g = (((a shr 8) and 0xff) * inv + ((b shr 8) and 0xff) * t).toInt().coerceIn(0, 255)
            val bl = ((a and 0xff) * inv + (b and 0xff) * t).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
}

internal object DiarySettings {
    private const val PREFS_NAME = "diary_app_settings"
    private const val KEY_THEME = "theme_mode"
    private const val KEY_TOOLBAR = "toolbar_mode"
    private const val KEY_PLUGINS = "plugins_mask"

    fun updatePalettes(lightCore: IntArray, darkCore: IntArray) {
        if (lightCore.size >= 7) {
            DiaryPalette.LIGHT = DiaryPalette.fromCoreColors(
                isDark = false,
                paper = lightCore[0],
                wash = lightCore[1],
                rule = lightCore[2],
                text = lightCore[3],
                muted = lightCore[4],
                accent = lightCore[5],
                danger = lightCore[6],
            )
        }
        if (darkCore.size >= 7) {
            DiaryPalette.DARK = DiaryPalette.fromCoreColors(
                isDark = true,
                paper = darkCore[0],
                wash = darkCore[1],
                rule = darkCore[2],
                text = darkCore[3],
                muted = darkCore[4],
                accent = darkCore[5],
                danger = darkCore[6],
            )
        }
    }

    fun themeMode(context: Context): ThemeMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ThemeMode.fromCode(prefs.getInt(KEY_THEME, ThemeMode.AUTO.code))
    }

    fun toolbarMode(context: Context): ToolbarMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return ToolbarMode.fromCode(prefs.getInt(KEY_TOOLBAR, ToolbarMode.ICON_ONLY.code))
    }

    fun pluginsMask(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_PLUGINS, 0)
    }

    fun isPluginEnabled(context: Context, plugin: DiaryPlugin): Boolean =
        isPluginEnabled(pluginsMask(context), plugin)

    fun isPluginEnabled(mask: Int, plugin: DiaryPlugin): Boolean =
        (mask and plugin.bit) != 0

    fun save(context: Context, themeCode: Int, toolbarCode: Int, pluginsMask: Int) {
        val theme = ThemeMode.fromCode(themeCode)
        val toolbar = ToolbarMode.fromCode(toolbarCode)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_THEME, theme.code)
            .putInt(KEY_TOOLBAR, toolbar.code)
            .putInt(KEY_PLUGINS, pluginsMask)
            .apply()
    }

    fun isSystemDark(context: Context): Boolean {
        val nightFlags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return nightFlags == Configuration.UI_MODE_NIGHT_YES
    }

    fun resolveDark(context: Context): Boolean = when (themeMode(context)) {
        ThemeMode.AUTO -> isSystemDark(context)
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    fun palette(context: Context): DiaryPalette =
        if (resolveDark(context)) DiaryPalette.DARK else DiaryPalette.LIGHT
}
