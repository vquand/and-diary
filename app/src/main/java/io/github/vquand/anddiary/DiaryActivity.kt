package io.github.vquand.anddiary

import android.Manifest
import android.app.Activity
import android.app.NativeActivity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import dev.gpui.mobile.GpuiPlatformView
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

class DiaryActivity : NativeActivity() {
    private var pendingMediaCallback: ((Uri?) -> Unit)? = null
    private var pendingStoragePermissionCallback: ((Boolean) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySystemBars()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemBars()
        GpuiPlatformView.refreshAppearance()
    }

    private fun applySystemBars() {
        val palette = DiarySettings.palette(this)
        @Suppress("DEPRECATION")
        window.statusBarColor = palette.paper
        @Suppress("DEPRECATION")
        window.navigationBarColor = palette.paper
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (palette.isDark) 0 else lightBars,
                lightBars,
            )
        } else {
            @Suppress("DEPRECATION")
            val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
    }

    fun launchMediaPicker(mimeType: String, onResult: (Uri?) -> Unit) {
        pendingMediaCallback = onResult
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
        }
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQUEST_PICK_MEDIA)
    }

    @Deprecated("NativeActivity uses Activity.onActivityResult for document picker results.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_PICK_MEDIA) {
            val callback = pendingMediaCallback
            pendingMediaCallback = null
            callback?.invoke(if (resultCode == RESULT_OK) data?.data else null)
        }
    }

    fun requestLegacyStoragePermission(onResult: (Boolean) -> Unit) {
        if (Build.VERSION.SDK_INT >= 29) {
            onResult(true)
            return
        }
        val permission = Manifest.permission.WRITE_EXTERNAL_STORAGE
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) {
            onResult(true)
            return
        }
        pendingStoragePermissionCallback = onResult
        requestPermissions(arrayOf(permission), REQUEST_LEGACY_STORAGE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            val callback = pendingStoragePermissionCallback
            pendingStoragePermissionCallback = null
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            callback?.invoke(granted)
        }
    }

    companion object {
        private const val REQUEST_PICK_MEDIA = 4102
        private const val REQUEST_LEGACY_STORAGE = 4103

        @JvmStatic
        fun edgeToEdgeInsets(activity: Activity): IntArray {
            if (Build.VERSION.SDK_INT < 35) return IntArray(4)
            val insets = activity.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return intArrayOf(insets.left, insets.top, insets.right, insets.bottom)
        }

        @JvmStatic
        fun isSystemDarkMode(activity: Activity): Boolean = DiarySettings.isSystemDark(activity)

        @JvmStatic
        fun formatSystemDate(year: Int, month: Int, day: Int): String =
            runCatching {
                LocalDate.of(year, month, day)
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
            }.getOrElse { "$year-$month-$day" }

        @JvmStatic
        fun notifyDateSelected(date: LocalDate) {
            runCatching { nativeOnDateSelected(date.year, date.monthValue, date.dayOfMonth) }
        }

        @JvmStatic
        private external fun nativeOnDateSelected(year: Int, month: Int, day: Int)

        @JvmStatic
        fun applySettings(
            activity: Activity,
            themeCode: Int,
            toolbarCode: Int,
            lightPalette: IntArray,
            darkPalette: IntArray,
        ) {
            DiarySettings.updatePalettes(lightPalette, darkPalette)
            DiarySettings.save(activity, themeCode, toolbarCode)
            activity.runOnUiThread {
                (activity as? DiaryActivity)?.applySystemBars()
                GpuiPlatformView.refreshAppearance()
            }
        }
    }
}
