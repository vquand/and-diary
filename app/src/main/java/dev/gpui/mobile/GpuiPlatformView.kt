package dev.gpui.mobile

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.PopupWindow
import io.github.vquand.anddiary.DayScrubberPill
import io.github.vquand.anddiary.DiarySettings
import io.github.vquand.anddiary.InkSheet
import java.io.File
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow
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
            sheets.put(id, MountedSheet(activity, date))?.dismissAll()
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
            if (visible) mounted.show() else mounted.dismissAll()
        }
    }

    @JvmStatic
    fun disposeView(id: Long) {
        ui.post { sheets.remove(id)?.dismissAll() }
    }

    @JvmStatic
    fun disposeAll() {
        ui.post {
            sheets.values.forEach { it.dismissAll() }
            sheets.clear()
        }
    }

    @JvmStatic
    fun pauseAll() {
        ui.post {
            sheets.values.forEach {
                it.endHeaderScrub(canceled = true)
                it.sheet.cancelGesture()
            }
        }
    }

    @JvmStatic
    fun resumeAll() {
        ui.post { sheets.values.forEach { it.sheet.invalidate() } }
    }

    @JvmStatic
    fun refreshAppearance() {
        ui.post { sheets.values.forEach { it.refreshAppearance() } }
    }

    @JvmStatic
    fun switchDate(date: String) {
        val parsed = runCatching { LocalDate.parse(date) }.getOrNull() ?: return
        ui.post {
            sheets.values.lastOrNull { it.window.isShowing }?.let { mounted ->
                mounted.date = parsed
                mounted.sheet.switchDate(parsed, animate = true, notifyHost = false)
            }
        }
    }

    @JvmStatic
    fun beginHeaderScrub() {
        ui.post {
            sheets.values.lastOrNull { it.window.isShowing }?.beginHeaderScrub()
        }
    }

    @JvmStatic
    fun updateHeaderScrub(dxDp: Float) {
        ui.post {
            sheets.values.lastOrNull { it.window.isShowing }?.updateHeaderScrub(dxDp)
        }
    }

    @JvmStatic
    fun endHeaderScrub(canceled: Boolean) {
        ui.post {
            sheets.values.lastOrNull { it.window.isShowing }?.endHeaderScrub(canceled)
        }
    }

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int)

    private class MountedSheet(private val activity: Activity, var date: LocalDate) {
        val sheet = InkSheet(activity, date)

        // NativeActivity gives its main surface to GPUI. The sheet needs its own Android window.
        val window = PopupWindow(sheet, 0, 0, false)
        private val scrubber = DayScrubberPill(activity, File(activity.filesDir, "entries"))
        private val scrubberWindow = PopupWindow(scrubber, 0, 0, false).apply {
            isTouchable = false
            isClippingEnabled = false
        }
        private var bounds = Bounds(0, 0, 0, 0)
        private var scrubbing = false
        private var scrubTarget: LocalDate = date

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

        fun refreshAppearance() {
            sheet.refreshAppearance()
            scrubber.applyPalette(DiarySettings.palette(activity))
        }

        fun dismissAll() {
            scrubbing = false
            scrubberWindow.dismiss()
            window.dismiss()
        }

        @SuppressLint("RtlHardcoded") // Place Dynamic Island directly over the centered Date header area.
        fun beginHeaderScrub() {
            val decor = activity.window.decorView
            if (!decor.isAttachedToWindow || activity.isFinishing) return
            val density = sheet.resources.displayMetrics.density
            val pillW = minOf((360f * density).roundToInt(), (bounds.width - (24f * density).roundToInt()).coerceAtLeast((240f * density).roundToInt()))
            val pillH = (68f * density).roundToInt()
            val headerHeightPx = (56f * density).roundToInt()
            val headerTopY = (bounds.y - headerHeightPx).coerceAtLeast(0)
            val pillX = bounds.x + (bounds.width - pillW) / 2
            val pillY = (headerTopY + (headerHeightPx - pillH) / 2).coerceAtLeast(headerTopY)

            sheet.cancelGesture()
            scrubbing = true
            scrubTarget = sheet.selectedDate
            scrubber.applyPalette(DiarySettings.palette(activity))

            scrubberWindow.width = pillW
            scrubberWindow.height = pillH
            if (!scrubberWindow.isShowing) {
                scrubberWindow.showAtLocation(decor, Gravity.TOP or Gravity.LEFT, pillX, pillY)
            } else {
                scrubberWindow.update(pillX, pillY, pillW, pillH)
            }
            scrubber.showScrubber(scrubTarget)
            sheet.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }

        fun updateHeaderScrub(dxDp: Float) {
            if (!scrubbing) return
            val raw = -dxDp / 40f
            val sign = if (raw < 0f) -1f else 1f
            val kinetic = sign * abs(raw).toDouble().pow(1.14).toFloat()
            val nextTarget = scrubber.updateOffset(kinetic)
            if (nextTarget != scrubTarget) {
                scrubTarget = nextTarget
                sheet.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }

        fun endHeaderScrub(canceled: Boolean) {
            if (!scrubbing) return
            scrubbing = false
            val chosen = scrubTarget
            scrubber.dismissScrubber()
            ui.postDelayed({ if (!scrubbing) scrubberWindow.dismiss() }, 170L)
            if (!canceled && chosen != sheet.selectedDate) {
                date = chosen
                sheet.switchDate(chosen, animate = true, notifyHost = true)
            }
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
