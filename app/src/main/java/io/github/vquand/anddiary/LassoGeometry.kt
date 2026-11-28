package io.github.vquand.anddiary

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal object LassoGeometry {

    // MotionEvent.TOOL_TYPE_FINGER == 1 (compile-time constant, safe for plain JVM tests).
    private const val TOOL_TYPE_FINGER = 1

    /**
     * Returns true when a two-finger rotate/scale gesture may start.
     * Both pointers must be [TOOL_TYPE_FINGER], there must be an active selection,
     * the stylus eraser must not be active, and no pen-driven gesture (drawing,
     * erasing, lassoing, single-pointer drag) may be in progress.
     */
    fun canStartTwoFingerTransform(
        toolTypes: IntArray,
        hasSelection: Boolean,
        stylusEraser: Boolean,
        penGestureActive: Boolean,
    ): Boolean {
        if (!hasSelection) return false
        if (stylusEraser) return false
        if (penGestureActive) return false
        if (toolTypes.size != 2) return false
        return toolTypes[0] == TOOL_TYPE_FINGER && toolTypes[1] == TOOL_TYPE_FINGER
    }

    /**
     * Returns true when a two-finger transform has moved enough to be meaningful.
     * Once this returns true for a gesture, stay moved, but [isIdentityTransform]
     * should be checked again at commit time to avoid no-op undo steps.
     *
     * @param translatePx  midpoint translation in pixels
     * @param scale        pinch scale factor (1 = no change)
     * @param rotationDeg  rotation in degrees
     * @param thresholdPx  minimum translation distance (default 4dp worth of px)
     * @param scaleEpsilon minimum |scale - 1| to count (default 0.02)
     * @param rotationDegEpsilon minimum |rotation| in degrees (default 1.5)
     */
    fun isTransformMeaningful(
        translatePx: Float,
        scale: Float,
        rotationDeg: Float,
        thresholdPx: Float = 4f,
        scaleEpsilon: Float = 0.02f,
        rotationDegEpsilon: Float = 1.5f,
    ): Boolean {
        if (translatePx > thresholdPx) return true
        if (abs(scale - 1f) > scaleEpsilon) return true
        if (abs(rotationDeg) > rotationDegEpsilon) return true
        return false
    }

    /**
     * Returns true when the final transform is effectively identity
     * (no visible change), so the commit can be skipped entirely.
     */
    fun isIdentityTransform(
        translateX: Float,
        translateY: Float,
        scale: Float,
        rotationDeg: Float,
        thresholdPx: Float = 0.5f,
        scaleEpsilon: Float = 0.005f,
        rotationDegEpsilon: Float = 0.3f,
    ): Boolean {
        if (abs(translateX) > thresholdPx || abs(translateY) > thresholdPx) return false
        if (abs(scale - 1f) > scaleEpsilon) return false
        if (abs(rotationDeg) > rotationDegEpsilon) return false
        return true
    }

    /**
     * Computes the scale for an area-drag resize, using whichever axis (width or height)
     * is larger so a drag in either direction is effective.
     *
     * @param baseW   the starting width of the selection (should already be coerced to >= minBase)
     * @param baseH   the starting height of the selection (should already be coerced to >= minBase)
     * @param dx      horizontal drag delta in pixels
     * @param dy      vertical drag delta in pixels
     * @param minScale  lower clamp for the returned scale
     * @param maxScale  upper clamp for the returned scale
     */
    fun areaDragScale(
        baseW: Float,
        baseH: Float,
        dx: Float,
        dy: Float,
        minScale: Float = 0.25f,
        maxScale: Float = 4f,
    ): Float {
        return if (baseW >= baseH) {
            ((baseW + dx) / baseW).coerceIn(minScale, maxScale)
        } else {
            ((baseH + dy) / baseH).coerceIn(minScale, maxScale)
        }
    }

    /**
     * Returns true if the line segment (ax,ay)→(bx,by) intersects (cx,cy)→(dx,dy).
     */
    fun segmentsIntersect(
        ax: Float, ay: Float, bx: Float, by: Float,
        cx: Float, cy: Float, dx: Float, dy: Float,
    ): Boolean {
        val d1x = bx - ax; val d1y = by - ay
        val d2x = dx - cx; val d2y = dy - cy
        val denom = d1x * d2y - d1y * d2x
        if (denom == 0f) return false // parallel
        val t = ((cx - ax) * d2y - (cy - ay) * d2x) / denom
        val u = ((cx - ax) * d1y - (cy - ay) * d1x) / denom
        return t in 0f..1f && u in 0f..1f
    }

    /**
     * Returns true if the polyline formed by [samples] intersects the closed [polygon]
     * boundary (any consecutive pair of sample points crosses any polygon edge).
     */
    fun polylineIntersectsPolygon(
        samples: List<Pair<Float, Float>>,
        polygon: List<Pair<Float, Float>>,
    ): Boolean {
        if (samples.size < 2 || polygon.size < 3) return false
        for (s in 0 until samples.size - 1) {
            val (ax, ay) = samples[s]
            val (bx, by) = samples[s + 1]
            var pj = polygon.size - 1
            for (pi in polygon.indices) {
                val (cx, cy) = polygon[pi]
                val (dx, dy) = polygon[pj]
                if (segmentsIntersect(ax, ay, bx, by, cx, cy, dx, dy)) return true
                pj = pi
            }
        }
        return false
    }

    /**
     * Standard ray-casting point-in-polygon test.
     * Returns true if (px, py) is inside the closed polygon defined by [polygon].
     */
    fun pointInPolygon(px: Float, py: Float, polygon: List<Pair<Float, Float>>): Boolean {
        val n = polygon.size
        if (n < 3) return false
        var inside = false
        var j = n - 1
        for (i in 0 until n) {
            val (xi, yi) = polygon[i]
            val (xj, yj) = polygon[j]
            val intersects = ((yi > py) != (yj > py)) &&
                (px < (xj - xi) * (py - yi) / (yj - yi) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Rotates point (px, py) around (cx, cy) by [degrees] clockwise (Android Canvas convention).
     */
    fun rotatePoint(px: Float, py: Float, cx: Float, cy: Float, degrees: Float): Pair<Float, Float> {
        if (degrees == 0f) return px to py
        val rad = Math.toRadians(degrees.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        val dx = px - cx
        val dy = py - cy
        return (cx + dx * cosA - dy * sinA) to (cy + dx * sinA + dy * cosA)
    }

    /**
     * Returns the 4 corners of a rectangle rotated around its center by [rotationDeg].
     */
    fun rotatedRectCorners(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
    ): List<Pair<Float, Float>> {
        val cx = x + width / 2f
        val cy = y + height / 2f
        return listOf(
            rotatePoint(x, y, cx, cy, rotationDeg),
            rotatePoint(x + width, y, cx, cy, rotationDeg),
            rotatePoint(x + width, y + height, cx, cy, rotationDeg),
            rotatePoint(x, y + height, cx, cy, rotationDeg),
        )
    }

    /**
     * Returns the axis-aligned bounding box [left, top, right, bottom] of a rotated rectangle.
     */
    fun rotatedRectBounds(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
    ): FloatArray {
        val corners = rotatedRectCorners(x, y, width, height, rotationDeg)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        for ((cx, cy) in corners) {
            if (cx < minX) minX = cx
            if (cy < minY) minY = cy
            if (cx > maxX) maxX = cx
            if (cy > maxY) maxY = cy
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /**
     * Returns true if a rotated item's center or any corner falls inside [polygon],
     * or if any polygon vertex falls inside the rotated item.
     */
    fun itemIntersectsLasso(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
        polygon: List<Pair<Float, Float>>,
    ): Boolean {
        if (polygon.size < 3) return false
        val cx = x + width / 2f
        val cy = y + height / 2f
        if (pointInPolygon(cx, cy, polygon)) return true
        val corners = rotatedRectCorners(x, y, width, height, rotationDeg)
        if (corners.any { (px, py) -> pointInPolygon(px, py, polygon) }) return true
        for ((vx, vy) in polygon) {
            val (lx, ly) = rotatePoint(vx, vy, cx, cy, -rotationDeg)
            if (lx >= x && lx <= x + width && ly >= y && ly <= y + height) return true
        }
        return false
    }

    /**
     * Normalizes an angle in degrees to (-180, 180].
     */
    fun normalizeAngle(degrees: Float): Float {
        var a = degrees % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        return if (a == -0f) 0f else a
    }

    /**
     * Resizes a rotated rectangle by a screen-space drag delta (dx, dy) on its bottom-right handle,
     * preserving its aspect ratio and keeping its rotated top-left corner anchored in place.
     * Returns floatArrayOf(newX, newY, newWidth, newHeight).
     */
    fun resizeRotatedRect(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
        dx: Float,
        dy: Float,
        minWidth: Float,
        maxWidth: Float,
    ): FloatArray {
        val aspect = if (width > 0f) height / width else 1f
        val (localDx, _) = rotatePoint(dx, dy, 0f, 0f, -rotationDeg)
        val clampedMax = maxWidth.coerceAtLeast(minWidth)
        val newW = (width + localDx).coerceIn(minWidth, clampedMax)
        val newH = (newW * aspect).coerceAtLeast(minWidth * aspect)

        val halfDw = (newW - width) / 2f
        val halfDh = (newH - height) / 2f
        val (shiftX, shiftY) = rotatePoint(halfDw, halfDh, 0f, 0f, rotationDeg)
        val oldCx = x + width / 2f
        val oldCy = y + height / 2f
        val newCx = oldCx + shiftX
        val newCy = oldCy + shiftY
        return floatArrayOf(newCx - newW / 2f, newCy - newH / 2f, newW, newH)
    }

    /**
     * Returns the (x, y) that keeps the rotated item's visual bounds inside the sheet,
     * so its handles stay reachable.
     */
    fun clampRotatedItemToSheet(
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        rotationDeg: Float,
        sheetWidth: Float,
        sheetHeight: Float,
    ): Pair<Float, Float> {
        val b = rotatedRectBounds(x, y, width, height, rotationDeg)
        val (shiftX, shiftY) = computeGroupClampShift(b[0], b[1], b[2], b[3], sheetWidth, sheetHeight)
        return (x + shiftX) to (y + shiftY)
    }

    /**
     * Clamps a group [scale] so every non-empty resizable item width in [itemWidths]
     * stays within [[minWidth], [maxWidth]], keeping strokes and items on one shared scale.
     */
    fun clampGroupScale(
        scale: Float,
        itemWidths: List<Float>,
        minWidth: Float,
        maxWidth: Float,
    ): Float {
        var clamped = scale
        val hiMax = maxWidth.coerceAtLeast(minWidth)
        for (w in itemWidths) {
            if (w <= 0f) continue
            val lo = (minWidth / w).coerceAtMost(1f)
            val hi = (hiMax / w).coerceAtLeast(1f)
            clamped = clamped.coerceIn(lo, hi)
        }
        return clamped
    }

    /**
     * Computes a shared (shiftX, shiftY) to keep a group bounding box [minX..maxX, minY..maxY]
     * inside [0..sheetWidth, 0..sheetHeight] without splitting strokes and items apart.
     */
    fun computeGroupClampShift(
        minX: Float,
        minY: Float,
        maxX: Float,
        maxY: Float,
        sheetWidth: Float,
        sheetHeight: Float,
    ): Pair<Float, Float> {
        val shiftX = when {
            maxX - minX >= sheetWidth -> -minX
            minX < 0f -> -minX
            maxX > sheetWidth -> sheetWidth - maxX
            else -> 0f
        }
        val shiftY = when {
            maxY - minY >= sheetHeight -> -minY
            minY < 0f -> -minY
            maxY > sheetHeight -> sheetHeight - maxY
            else -> 0f
        }
        return shiftX to shiftY
    }

    /**
     * Wraps an orientation value in radians so it stays in [0, 2π).
     * Handles the edge case where float rounding leaves the value
     * at exactly 2π after adding the correction for negative values.
     */
    fun wrapOrientation(orientationRadians: Float): Float {
        val twoPi = (2.0 * Math.PI).toFloat()
        var o = orientationRadians % twoPi
        if (o < 0f) o += twoPi
        if (o >= twoPi) o -= twoPi
        return o
    }

    /**
     * Sanitizes a user-edited export filename and ensures it ends with `.$extension`.
     */
    fun sanitizeExportFileName(rawName: String, fallbackBase: String, extension: String): String {
        val stripped = rawName
            .trim()
            .replace(Regex("[/\\\\:*?\"<>|]"), "_")
            .replace(Regex("\\.(png|jpe?g|webp)$", RegexOption.IGNORE_CASE), "")
            .trim('.', ' ')
        val base = stripped.ifBlank { fallbackBase }
        return "$base.$extension"
    }

    enum class DeleteConfirmHit { OK, NOT_OK }

    data class DeleteConfirmPillBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val splitX: Float,
    )

    fun deleteIconCenterX(boxRight: Float, density: Float): Float = boxRight - 30f * density

    fun deleteConfirmPillBounds(
        boxRight: Float,
        boxTop: Float,
        density: Float,
        minLeft: Float = Float.NEGATIVE_INFINITY,
        minInlineLeft: Float = Float.NEGATIVE_INFINITY,
    ): DeleteConfirmPillBounds {
        val pillW = 80f * density
        val pillH = 30f * density
        val okW = 40f * density
        val gapFromClose = 18f * density
        var right = boxRight - gapFromClose
        var left = right - pillW
        val centerY = if (left < minInlineLeft) {
            // Narrow contour box: move the pill off the corner row so it never covers the gear button.
            if (left < minLeft) {
                left = minLeft
                right = left + pillW
            }
            val liftY = boxTop - 28f * density
            if (liftY - pillH / 2f >= 4f * density) liftY else boxTop + 28f * density
        } else {
            if (left < minLeft) {
                left = boxRight + gapFromClose
                right = left + pillW
            }
            boxTop
        }
        return DeleteConfirmPillBounds(
            left = left,
            top = centerY - pillH / 2f,
            right = right,
            bottom = centerY + pillH / 2f,
            splitX = left + okW,
        )
    }

    fun hitTestDeleteConfirmPill(
        x: Float,
        y: Float,
        pill: DeleteConfirmPillBounds,
        slopPx: Float,
    ): DeleteConfirmHit? {
        if (x < pill.left - slopPx || x > pill.right + slopPx) return null
        if (y < pill.top - slopPx || y > pill.bottom + slopPx) return null
        return if (x < pill.splitX) DeleteConfirmHit.OK else DeleteConfirmHit.NOT_OK
    }

    /**
     * Returns true when a transformed stroke sample shares the exact same position and
     * elapsed timestamp as the previous sample, which AndroidX Ink rejects in
     * [androidx.ink.strokes.MutableStrokeInputBatch.add].
     */
    fun isDuplicateStrokeSample(
        prevX: Float,
        prevY: Float,
        prevElapsedMillis: Long,
        x: Float,
        y: Float,
        elapsedMillis: Long,
    ): Boolean = x == prevX && y == prevY && elapsedMillis == prevElapsedMillis
}
