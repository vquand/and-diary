package io.github.vquand.anddiary

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class ContourRenderStyle(val wire: String) {
    SMOOTH("smooth"),
    BLOCK("block"),
    ;

    companion object {
        fun fromWire(value: String): ContourRenderStyle =
            entries.firstOrNull { it.wire == value } ?: SMOOTH
    }
}

/**
 * A single contour polyline in normalized rectangle coordinates [0f..1f] x [0f..1f].
 * When [explicitLevel] is null, the engine infers its elevation from topological nesting depth.
 */
internal data class ContourStroke(
    val points: List<Pair<Float, Float>>,
    val explicitLevel: Int? = null,
)

internal data class ContourDocument(
    val style: ContourRenderStyle = ContourRenderStyle.SMOOTH,
    val strokes: List<ContourStroke> = emptyList(),
    val yawRad: Float = DEFAULT_YAW_RAD,
    val pitchRad: Float = DEFAULT_PITCH_RAD,
) {
    fun toJson(): String {
        val strokesArr = JSONArray()
        for (stroke in strokes) {
            val ptsArr = JSONArray()
            for ((x, y) in stroke.points) {
                ptsArr.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
            }
            val obj = JSONObject().put("points", ptsArr)
            if (stroke.explicitLevel != null) {
                obj.put("level", stroke.explicitLevel)
            }
            strokesArr.put(obj)
        }
        return JSONObject()
            .put("version", 1)
            .put("style", style.wire)
            .put("yaw", normalizeYaw(yawRad).toDouble())
            .put("pitch", clampPitch(pitchRad).toDouble())
            .put("strokes", strokesArr)
            .toString()
    }

    companion object {
        const val DEFAULT_YAW_RAD = 0.62f // ~35.5 deg
        const val DEFAULT_PITCH_RAD = 0.56f // ~32 deg
        const val MIN_PITCH_RAD = 0.08f // ~4.6 deg above ground plane; never below surface
        const val MAX_PITCH_RAD = 1.42f // ~81.4 deg

        fun normalizeYaw(yawRad: Float): Float {
            if (!yawRad.isFinite()) return DEFAULT_YAW_RAD
            val twoPi = (PI * 2.0).toFloat()
            var wrapped = (yawRad + PI.toFloat()) % twoPi
            if (wrapped < 0f) wrapped += twoPi
            return wrapped - PI.toFloat()
        }

        fun clampPitch(pitchRad: Float): Float {
            if (!pitchRad.isFinite()) return DEFAULT_PITCH_RAD
            return pitchRad.coerceIn(MIN_PITCH_RAD, MAX_PITCH_RAD)
        }

        fun fromJson(json: String): ContourDocument {
            val root = JSONObject(json)
            val style = ContourRenderStyle.fromWire(root.optString("style", ContourRenderStyle.SMOOTH.wire))
            val yaw = if (root.has("yaw") && !root.isNull("yaw")) {
                normalizeYaw(root.optDouble("yaw", DEFAULT_YAW_RAD.toDouble()).toFloat())
            } else {
                DEFAULT_YAW_RAD
            }
            val pitch = if (root.has("pitch") && !root.isNull("pitch")) {
                clampPitch(root.optDouble("pitch", DEFAULT_PITCH_RAD.toDouble()).toFloat())
            } else {
                DEFAULT_PITCH_RAD
            }
            val strokesArr = root.optJSONArray("strokes") ?: JSONArray()
            val strokes = buildList(strokesArr.length()) {
                for (i in 0 until strokesArr.length()) {
                    val obj = strokesArr.getJSONObject(i)
                    val level = if (obj.has("level") && !obj.isNull("level")) obj.getInt("level") else null
                    val ptsArr = obj.optJSONArray("points") ?: continue
                    val pts = buildList(ptsArr.length()) {
                        for (p in 0 until ptsArr.length()) {
                            val pt = ptsArr.getJSONArray(p)
                            if (pt.length() >= 2) {
                                add(
                                    pt.getDouble(0).toFloat().coerceIn(0f, 1f) to
                                        pt.getDouble(1).toFloat().coerceIn(0f, 1f),
                                )
                            }
                        }
                    }
                    if (pts.size >= 2) {
                        add(ContourStroke(pts, level))
                    }
                }
            }
            return ContourDocument(style = style, strokes = strokes, yawRad = yaw, pitchRad = pitch)
        }
    }
}

internal data class ContourHeightmap(
    val cols: Int,
    val rows: Int,
    val heights: FloatArray,
    val maxHeight: Float,
    val maxLevel: Int,
    val resolvedLevels: List<Int>,
) {
    fun heightAt(x: Int, y: Int): Float =
        heights[y.coerceIn(0, rows - 1) * cols + x.coerceIn(0, cols - 1)]
}

internal data class VoxelHeightmap(
    val cols: Int,
    val rows: Int,
    val heights: IntArray,
    val maxBlocks: Int,
) {
    fun heightAt(x: Int, y: Int): Int =
        heights[y.coerceIn(0, rows - 1) * cols + x.coerceIn(0, cols - 1)]
}

/**
 * Converts 2D hand-drawn contour strokes into a continuous 2.5D heightmap using:
 * 1. Topological nesting-depth inference for unlabeled closed contour loops.
 * 2. Dirichlet Laplace PDE interpolation (∇²h = 0) via Red-Black Gauss-Seidel SOR.
 * 3. Smooth peak-dome shaping inside innermost summit loops so hills crest naturally.
 */
internal object ContourTerrainEngine {
    const val DEFAULT_GRID_COLS = 64
    const val DEFAULT_GRID_ROWS = 48
    const val DEFAULT_VOXEL_COLS = 24
    const val DEFAULT_VOXEL_ROWS = 18
    private const val BORDER_MARGIN = 0.045f

    fun isClosedLoop(points: List<Pair<Float, Float>>): Boolean {
        if (points.size < 4) return false
        val first = points.first()
        val last = points.last()
        val gap = hypot((last.first - first.first).toDouble(), (last.second - first.second).toDouble()).toFloat()
        var perimeter = 0f
        for (i in 1 until points.size) {
            perimeter += hypot(
                (points[i].first - points[i - 1].first).toDouble(),
                (points[i].second - points[i - 1].second).toDouble(),
            ).toFloat()
        }
        if (perimeter < 0.08f) return false
        return gap <= 0.16f || gap <= perimeter * 0.22f
    }

    fun polygonArea(points: List<Pair<Float, Float>>): Float {
        if (points.size < 3) return 0f
        var sum = 0.0
        var j = points.lastIndex
        for (i in points.indices) {
            sum += (points[j].first + points[i].first) * (points[j].second - points[i].second)
            j = i
        }
        return abs(sum * 0.5).toFloat()
    }

    fun pointInPolygon(x: Float, y: Float, polygon: List<Pair<Float, Float>>): Boolean {
        if (polygon.size < 3) return false
        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val xi = polygon[i].first
            val yi = polygon[i].second
            val xj = polygon[j].first
            val yj = polygon[j].second
            val intersects = ((yi > y) != (yj > y)) &&
                (x < (xj - xi) * (y - yi) / ((yj - yi).takeIf { it != 0f } ?: 1e-6f) + xi)
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    /**
     * Resolves integer elevation levels for every stroke.
     * Explicit levels are preserved; auto strokes receive `1 + count(enclosing closed loops)`.
     */
    fun resolveLevels(strokes: List<ContourStroke>): List<Int> {
        if (strokes.isEmpty()) return emptyList()
        val closed = BooleanArray(strokes.size) { i -> isClosedLoop(strokes[i].points) }
        val areas = FloatArray(strokes.size) { i -> if (closed[i]) polygonArea(strokes[i].points) else 0f }

        return List(strokes.size) { i ->
            val explicit = strokes[i].explicitLevel
            if (explicit != null) {
                explicit.coerceIn(0, 12)
            } else {
                val sample = representativePoint(strokes[i].points)
                var depth = 0
                for (j in strokes.indices) {
                    if (i == j || !closed[j]) continue
                    if (closed[i] && areas[j] <= areas[i] * 1.02f) continue
                    if (pointInPolygon(sample.first, sample.second, strokes[j].points)) {
                        depth++
                    }
                }
                (1 + depth).coerceIn(1, 12)
            }
        }
    }

    fun buildHeightmap(
        strokes: List<ContourStroke>,
        cols: Int = DEFAULT_GRID_COLS,
        rows: Int = DEFAULT_GRID_ROWS,
        sorIterations: Int = 140,
    ): ContourHeightmap {
        val size = cols * rows
        val heights = FloatArray(size)
        if (strokes.isEmpty()) {
            return ContourHeightmap(cols, rows, heights, 0f, 0, emptyList())
        }

        val resolvedLevels = resolveLevels(strokes)
        val fixed = BooleanArray(size)
        val closed = BooleanArray(strokes.size) { i -> isClosedLoop(strokes[i].points) }
        val areas = FloatArray(strokes.size) { i -> if (closed[i]) polygonArea(strokes[i].points) else 0f }

        var touchesBorder = false
        for (i in strokes.indices) {
            val pts = strokes[i].points
            if (pts.size < 2) continue
            val level = resolvedLevels[i].toFloat()
            if (!closed[i] && (isNearBorder(pts.first()) || isNearBorder(pts.last()))) {
                touchesBorder = true
            }
            for (p in 1 until pts.size) {
                rasterizeSegment(
                    pts[p - 1].first, pts[p - 1].second,
                    pts[p].first, pts[p].second,
                    level, cols, rows, heights, fixed,
                )
            }
            if (closed[i] && pts.size >= 3) {
                rasterizeSegment(
                    pts.last().first, pts.last().second,
                    pts.first().first, pts.first().second,
                    level, cols, rows, heights, fixed,
                )
            }
        }

        // For closed-hill maps (no open contours exiting the border), anchor the outer border to base plane 0.
        if (!touchesBorder) {
            for (x in 0 until cols) {
                if (!fixed[x]) {
                    fixed[x] = true
                    heights[x] = 0f
                }
                val bottomIdx = (rows - 1) * cols + x
                if (!fixed[bottomIdx]) {
                    fixed[bottomIdx] = true
                    heights[bottomIdx] = 0f
                }
            }
            for (y in 0 until rows) {
                val leftIdx = y * cols
                if (!fixed[leftIdx]) {
                    fixed[leftIdx] = true
                    heights[leftIdx] = 0f
                }
                val rightIdx = y * cols + (cols - 1)
                if (!fixed[rightIdx]) {
                    fixed[rightIdx] = true
                    heights[rightIdx] = 0f
                }
            }
        }

        // Seed unfixed cells with average fixed height for faster convergence.
        var fixedSum = 0.0
        var fixedCount = 0
        for (idx in 0 until size) {
            if (fixed[idx]) {
                fixedSum += heights[idx]
                fixedCount++
            }
        }
        val seed = if (fixedCount > 0) (fixedSum / fixedCount).toFloat() else 0f
        for (idx in 0 until size) {
            if (!fixed[idx]) heights[idx] = seed
        }

        // Red-Black Gauss-Seidel SOR solving ∇²h = 0 (with Neumann zero-slope on any unfixed border cells).
        val omega = 1.72f
        val oneMinusOmega = 1f - omega
        for (iter in 0 until sorIterations) {
            for (parity in 0..1) {
                for (y in 0 until rows) {
                    val rowOffset = y * cols
                    var x = (y + parity) and 1
                    while (x < cols) {
                        val idx = rowOffset + x
                        if (!fixed[idx]) {
                            var neighborSum = 0f
                            var neighborCount = 0
                            if (x > 0) {
                                neighborSum += heights[idx - 1]
                                neighborCount++
                            }
                            if (x + 1 < cols) {
                                neighborSum += heights[idx + 1]
                                neighborCount++
                            }
                            if (y > 0) {
                                neighborSum += heights[idx - cols]
                                neighborCount++
                            }
                            if (y + 1 < rows) {
                                neighborSum += heights[idx + cols]
                                neighborCount++
                            }
                            if (neighborCount > 0) {
                                heights[idx] = oneMinusOmega * heights[idx] + omega * (neighborSum / neighborCount)
                            }
                        }
                        x += 2
                    }
                }
            }
        }

        // Add subtle rounded summit domes inside innermost closed loops so peaks rise gently above their top contour.
        applySummitDomes(strokes, resolvedLevels, closed, areas, cols, rows, heights, fixed)

        var maxH = 0f
        for (idx in 0 until size) {
            if (heights[idx] < 0f) heights[idx] = 0f
            if (heights[idx] > maxH) maxH = heights[idx]
        }
        val maxLevel = resolvedLevels.maxOrNull() ?: 0
        return ContourHeightmap(
            cols = cols,
            rows = rows,
            heights = heights,
            maxHeight = maxH,
            maxLevel = maxLevel,
            resolvedLevels = resolvedLevels,
        )
    }

    /**
     * Downsamples a continuous [ContourHeightmap] into an integer block grid for Minecraft-style rendering.
     */
    fun quantizeForBlocks(
        heightmap: ContourHeightmap,
        blockCols: Int = DEFAULT_VOXEL_COLS,
        blockRows: Int = DEFAULT_VOXEL_ROWS,
        maxBlocks: Int = 10,
    ): VoxelHeightmap {
        val out = IntArray(blockCols * blockRows)
        if (heightmap.maxHeight <= 0.001f) {
            return VoxelHeightmap(blockCols, blockRows, out, 0)
        }
        val targetMax = max(2, min(maxBlocks, (heightmap.maxLevel * 2).coerceAtLeast(3)))
        val scale = targetMax / heightmap.maxHeight
        var highest = 0

        for (by in 0 until blockRows) {
            val y0 = (by * heightmap.rows) / blockRows
            val y1 = max(y0 + 1, ((by + 1) * heightmap.rows) / blockRows)
            for (bx in 0 until blockCols) {
                val x0 = (bx * heightmap.cols) / blockCols
                val x1 = max(x0 + 1, ((bx + 1) * heightmap.cols) / blockCols)
                var sum = 0f
                var count = 0
                for (gy in y0 until y1) {
                    val rowOff = gy * heightmap.cols
                    for (gx in x0 until x1) {
                        sum += heightmap.heights[rowOff + gx]
                        count++
                    }
                }
                val avg = if (count > 0) sum / count else 0f
                val blocks = if (avg < 0.14f) 0 else (avg * scale).roundToInt().coerceIn(1, targetMax)
                out[by * blockCols + bx] = blocks
                if (blocks > highest) highest = blocks
            }
        }
        return VoxelHeightmap(blockCols, blockRows, out, highest)
    }

    private fun applySummitDomes(
        strokes: List<ContourStroke>,
        levels: List<Int>,
        closed: BooleanArray,
        areas: FloatArray,
        cols: Int,
        rows: Int,
        heights: FloatArray,
        fixed: BooleanArray,
    ) {
        for (i in strokes.indices) {
            if (!closed[i] || levels[i] <= 0) continue
            // Skip loops that enclose another closed loop.
            var hasInnerLoop = false
            for (j in strokes.indices) {
                if (i == j || !closed[j]) continue
                if (areas[j] >= areas[i]) continue
                val pt = representativePoint(strokes[j].points)
                if (pointInPolygon(pt.first, pt.second, strokes[i].points)) {
                    hasInnerLoop = true
                    break
                }
            }
            if (hasInnerLoop) continue

            val poly = strokes[i].points
            val insideMask = BooleanArray(cols * rows)
            var minGx = cols
            var maxGx = -1
            var minGy = rows
            var maxGy = -1

            for (gy in 1 until rows - 1) {
                val ny = gy.toFloat() / (rows - 1).toFloat()
                for (gx in 1 until cols - 1) {
                    val idx = gy * cols + gx
                    if (fixed[idx]) continue
                    val nx = gx.toFloat() / (cols - 1).toFloat()
                    if (pointInPolygon(nx, ny, poly)) {
                        insideMask[idx] = true
                        if (gx < minGx) minGx = gx
                        if (gx > maxGx) maxGx = gx
                        if (gy < minGy) minGy = gy
                        if (gy > maxGy) maxGy = gy
                    }
                }
            }
            if (maxGx < minGx || maxGy < minGy) continue

            // Fast 2-pass Manhattan/Chamfer distance transform inside the summit polygon.
            val dist = FloatArray(cols * rows) { idx -> if (insideMask[idx]) 1e4f else 0f }
            for (gy in minGy..maxGy) {
                for (gx in minGx..maxGx) {
                    val idx = gy * cols + gx
                    if (!insideMask[idx]) continue
                    var d = dist[idx]
                    d = min(d, dist[idx - 1] + 1f)
                    d = min(d, dist[idx - cols] + 1f)
                    d = min(d, dist[idx - cols - 1] + 1.414f)
                    if (gx + 1 < cols) d = min(d, dist[idx - cols + 1] + 1.414f)
                    dist[idx] = d
                }
            }
            var maxDist = 0f
            for (gy in maxGy downTo minGy) {
                for (gx in maxGx downTo minGx) {
                    val idx = gy * cols + gx
                    if (!insideMask[idx]) continue
                    var d = dist[idx]
                    d = min(d, dist[idx + 1] + 1f)
                    d = min(d, dist[idx + cols] + 1f)
                    d = min(d, dist[idx + cols + 1] + 1.414f)
                    if (gx > 0) d = min(d, dist[idx + cols - 1] + 1.414f)
                    dist[idx] = d
                    if (d > maxDist) maxDist = d
                }
            }
            if (maxDist <= 0.5f) continue

            val domeHeight = 0.52f
            for (gy in minGy..maxGy) {
                for (gx in minGx..maxGx) {
                    val idx = gy * cols + gx
                    if (!insideMask[idx]) continue
                    val norm = (dist[idx] / maxDist).coerceIn(0f, 1f)
                    heights[idx] += domeHeight * sin(norm * (PI / 2.0)).toFloat()
                }
            }

            // Light smoothing passes inside the summit dome for a natural crest.
            val scratch = heights.copyOf()
            repeat(5) {
                for (gy in minGy..maxGy) {
                    for (gx in minGx..maxGx) {
                        val idx = gy * cols + gx
                        if (!insideMask[idx]) continue
                        scratch[idx] = (
                            heights[idx] * 2f +
                                heights[idx - 1] + heights[idx + 1] +
                                heights[idx - cols] + heights[idx + cols]
                            ) / 6f
                    }
                }
                for (gy in minGy..maxGy) {
                    for (gx in minGx..maxGx) {
                        val idx = gy * cols + gx
                        if (insideMask[idx]) heights[idx] = scratch[idx]
                    }
                }
            }
        }
    }

    private fun representativePoint(points: List<Pair<Float, Float>>): Pair<Float, Float> {
        var sx = 0f
        var sy = 0f
        for ((x, y) in points) {
            sx += x
            sy += y
        }
        val n = points.size.coerceAtLeast(1)
        return (sx / n) to (sy / n)
    }

    private fun isNearBorder(pt: Pair<Float, Float>): Boolean =
        pt.first <= BORDER_MARGIN || pt.first >= 1f - BORDER_MARGIN ||
            pt.second <= BORDER_MARGIN || pt.second >= 1f - BORDER_MARGIN

    private fun rasterizeSegment(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        level: Float,
        cols: Int,
        rows: Int,
        heights: FloatArray,
        fixed: BooleanArray,
    ) {
        val gx0 = x0 * (cols - 1)
        val gy0 = y0 * (rows - 1)
        val gx1 = x1 * (cols - 1)
        val gy1 = y1 * (rows - 1)
        val dist = hypot((gx1 - gx0).toDouble(), (gy1 - gy0).toDouble())
        val steps = max(2, ceil(dist * 2.5).toInt())
        for (s in 0..steps) {
            val t = s.toFloat() / steps.toFloat()
            val gx = (gx0 + (gx1 - gx0) * t).roundToInt().coerceIn(0, cols - 1)
            val gy = (gy0 + (gy1 - gy0) * t).roundToInt().coerceIn(0, rows - 1)
            val idx = gy * cols + gx
            heights[idx] = level
            fixed[idx] = true
        }
    }
}
