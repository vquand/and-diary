package io.github.vquand.anddiary

import android.app.Activity
import android.app.NativeActivity
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController

class DiaryActivity : NativeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(lightBars, lightBars)
        }
    }

    companion object {
        @JvmStatic
        fun edgeToEdgeInsets(activity: Activity): IntArray {
            if (Build.VERSION.SDK_INT < 35) return IntArray(4)
            val insets = activity.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return intArrayOf(insets.left, insets.top, insets.right, insets.bottom)
        }
    }
}
