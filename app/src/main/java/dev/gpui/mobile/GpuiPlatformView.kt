package dev.gpui.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.PopupWindow
import io.github.vquand.anddiary.InkSheet
import java.time.LocalDate
import kotlin.math.roundToInt

// gpui-mobile calls this class by name from its native render thread.
object GpuiPlatformView {
    private val ui = Handler(Looper.getMainLooper())
    private val sheets = mutableMapOf<Long, MountedSheet>()

    @JvmStatic
    fun createView(
        activity: Activity, type: String, id: Long,
        x: Float, y: Float, width: Float, height: Float, creationParams: String,
    ): Boolean {
        if (type != "diary_ink" || !creationParams.startsWith("date=")) return false
        val date = runCatching { LocalDate.parse(creationParams.removePrefix("date=")) }
            .getOrNull() ?: return false
        return ui.post {
            if (activity.isDestroyed) return@post
            sheets.put(id, MountedSheet(activity, date))?.window?.dismiss()
            sheets[id]?.setBounds(x, y, width, height)
        }
    }

    @JvmStatic
    fun setBounds(id: Long, x: Float, y: Float, width: Float, height: Float) {
        ui.post { sheets[id]?.setBounds(x, y, width, height) }
    }

    @JvmStatic
    fun setVisible(id: Long, visible: Boolean) {
        ui.post {
            val mounted = sheets[id] ?: return@post
            if (visible) mounted.show() else mounted.window.dismiss()
        }
    }

    @JvmStatic
    fun disposeView(id: Long) {
        ui.post { sheets.remove(id)?.window?.dismiss() }
    }

    @JvmStatic
    fun disposeAll() {
        ui.post {
            sheets.values.forEach { it.window.dismiss() }
            sheets.clear()
        }
    }

    @JvmStatic
    fun pauseAll() {
        ui.post { sheets.values.forEach { it.sheet.cancelGesture() } }
    }

    @JvmStatic
    fun resumeAll() {
        ui.post { sheets.values.forEach { it.sheet.invalidate() } }
    }

    @JvmStatic
    fun confirmClearDay(date: String) {
        ui.post {
            sheets.values.firstOrNull { it.window.isShowing && it.date.toString() == date }
                ?.sheet?.confirmClearDay()
        }
    }

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private class MountedSheet(private val activity: Activity, val date: LocalDate) {
        val sheet = InkSheet(activity, date)

        // NativeActivity gives its main surface to GPUI. The sheet needs its own Android window.
        val window = PopupWindow(sheet, 0, 0, false)
        private var bounds = Bounds(0, 0, 0, 0)

        fun setBounds(x: Float, y: Float, width: Float, height: Float) {
            val density = sheet.resources.displayMetrics.density
            val next = Bounds(
                (x * density).roundToInt(), (y * density).roundToInt(),
                (width * density).roundToInt(), (height * density).roundToInt(),
            )
            if (next == bounds) return
            bounds = next
            if (window.isShowing) window.update(next.x, next.y, next.width, next.height)
        }

        @SuppressLint("RtlHardcoded") // GPUI bounds use an absolute left origin.
        fun show() {
            val decor = activity.window.decorView
            if (window.isShowing || !decor.isAttachedToWindow || activity.isFinishing ||
                bounds.width <= 0 || bounds.height <= 0
            ) return
            window.width = bounds.width
            window.height = bounds.height
            window.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, bounds.x, bounds.y)
        }
    }
}
