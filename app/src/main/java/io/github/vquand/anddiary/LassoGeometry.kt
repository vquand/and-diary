package io.github.vquand.anddiary

import kotlin.math.cos
import kotlin.math.sin

internal object LassoGeometry {
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
}
