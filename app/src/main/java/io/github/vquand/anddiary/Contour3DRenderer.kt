package io.github.vquand.anddiary

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Renders a [ContourDocument] into a 3D isometric/axonometric terrain bitmap
 * supporting both [ContourRenderStyle.SMOOTH] (topographic elevation surface with
 * draped 3D contour lines and elevation reference frame) and [ContourRenderStyle.BLOCK]
 * (Minecraft-style stacked isometric voxels).
 */
internal object Contour3DRenderer {
    private class RenderScratch {
        val p00 = FloatArray(2)
        val p10 = FloatArray(2)
        val p11 = FloatArray(2)
        val p01 = FloatArray(2)
        val b00 = FloatArray(2)
        val b10 = FloatArray(2)
        val b11 = FloatArray(2)
        val b01 = FloatArray(2)
        val cA = FloatArray(2)
        val cB = FloatArray(2)
        val path = Path()

        val baseFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xffcbc5b9.toInt()
        }
        val baseEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xff3b3732.toInt()
            strokeJoin = Paint.Join.ROUND
        }
        val dashedFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x7a575148.toInt()
        }
        var lastDashUnitStroke = -1f
        val quadFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val quadSeam = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        val contourPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xff2b2723.toInt()
            strokeCap = Paint.Cap.ROUND
        }
        val wallVPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val wallUPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val facePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0x55272320
            strokeJoin = Paint.Join.ROUND
        }
    }

    private val scratchLocal = ThreadLocal.withInitial { RenderScratch() }

    fun render(
        document: ContourDocument,
        widthPx: Int = 800,
        heightPx: Int = 600,
        heightmap: ContourHeightmap = ContourTerrainEngine.buildHeightmap(document.strokes),
        voxels: VoxelHeightmap? = null,
    ): Bitmap {
        val w = widthPx.coerceAtLeast(64)
        val h = heightPx.coerceAtLeast(64)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        draw(canvas, document, w.toFloat(), h.toFloat(), heightmap, voxels)
        return bitmap
    }

    fun draw(
        canvas: Canvas,
        document: ContourDocument,
        width: Float,
        height: Float,
        heightmap: ContourHeightmap = ContourTerrainEngine.buildHeightmap(document.strokes),
        voxels: VoxelHeightmap? = null,
        gridStep: Int = 1,
    ) {
        val yawRad = ContourDocument.normalizeYaw(document.yawRad)
        val pitchRad = ContourDocument.clampPitch(document.pitchRad)
        val scratch = scratchLocal.get()!!
        when (document.style) {
            ContourRenderStyle.SMOOTH -> drawSmoothTerrain(
                canvas,
                heightmap,
                width,
                height,
                yawRad,
                pitchRad,
                gridStep.coerceAtLeast(1),
                scratch,
            )
            ContourRenderStyle.BLOCK -> drawBlockTerrain(
                canvas,
                voxels ?: ContourTerrainEngine.quantizeForBlocks(heightmap),
                width,
                height,
                yawRad,
                pitchRad,
                scratch,
            )
        }
    }

    internal fun painterOrder(count: Int, eyePos01: Float): IntArray {
        val out = IntArray(count)
        var idx = 0
        for (i in 0 until count) {
            if ((i + 0.5f) / count < eyePos01) {
                out[idx++] = i
            }
        }
        for (i in (count - 1) downTo 0) {
            if ((i + 0.5f) / count >= eyePos01) {
                out[idx++] = i
            }
        }
        return out
    }

    private class IsoProjector(
        private val width: Float,
        private val height: Float,
        private val zHeightRatio: Float,
        private val maxZUnits: Float,
        yawRad: Float,
        pitchRad: Float,
    ) {
        val cosYaw = cos(yawRad.toDouble()).toFloat()
        val sinYaw = sin(yawRad.toDouble()).toFloat()
        private val cosPitch = cos(pitchRad.toDouble()).toFloat()
        private val sinPitch = sin(pitchRad.toDouble()).toFloat()
        private val cameraDist = 3.8f
        private val zCenter = (maxZUnits * zHeightRatio) * 0.35f
        val eyeU01 = cameraDist * cosPitch * sinYaw + 0.5f
        val eyeV01 = cameraDist * cosPitch * cosYaw + 0.5f

        private val scale: Float
        private val originX: Float
        private val originY: Float

        init {
            // Measure projected bounds of the 3D bounding box [-0.5..0.5] x [-0.5..0.5] x [0..zHeightRatio]
            var minX = Float.POSITIVE_INFINITY
            var maxX = Float.NEGATIVE_INFINITY
            var minY = Float.POSITIVE_INFINITY
            var maxY = Float.NEGATIVE_INFINITY
            val zTop = maxZUnits * zHeightRatio
            for (u in floatArrayOf(-0.5f, 0.5f)) {
                for (v in floatArrayOf(-0.5f, 0.5f)) {
                    for (z in floatArrayOf(-0.04f, zTop)) {
                        val px = rawX(u, v, z)
                        val py = rawY(u, v, z)
                        if (px < minX) minX = px
                        if (px > maxX) maxX = px
                        if (py < minY) minY = py
                        if (py > maxY) maxY = py
                    }
                }
            }
            val spanX = (maxX - minX).coerceAtLeast(0.1f)
            val spanY = (maxY - minY).coerceAtLeast(0.1f)
            val padX = width * 0.06f
            val padY = height * 0.07f
            scale = min((width - padX * 2f) / spanX, (height - padY * 2f) / spanY)
            originX = width * 0.5f - (minX + maxX) * 0.5f * scale
            originY = height * 0.5f - (minY + maxY) * 0.5f * scale
        }

        private fun persp(u: Float, v: Float, zScaled: Float): Float {
            val cy = u * sinYaw + v * cosYaw
            val dz = zScaled - zCenter
            val camZ = cy * cosPitch + dz * sinPitch
            return cameraDist / (cameraDist - camZ).coerceAtLeast(0.6f)
        }

        private fun rawX(u: Float, v: Float, zScaled: Float): Float =
            (u * cosYaw - v * sinYaw) * persp(u, v, zScaled)

        private fun rawY(u: Float, v: Float, zScaled: Float): Float {
            val cy = u * sinYaw + v * cosYaw
            val dz = zScaled - zCenter
            return (cy * sinPitch - dz * cosPitch) * persp(u, v, zScaled)
        }

        fun project(u01: Float, v01: Float, zUnits: Float, out: FloatArray) {
            val u = u01 - 0.5f
            val v = v01 - 0.5f
            val zScaled = zUnits * zHeightRatio
            out[0] = originX + rawX(u, v, zScaled) * scale
            out[1] = originY + rawY(u, v, zScaled) * scale
        }
    }

    internal fun sampleCoords(size: Int, step: Int): IntArray {
        if (step <= 1) return IntArray(size) { it }
        val list = ArrayList<Int>((size + step - 1) / step + 1)
        var i = 0
        while (i < size - 1) {
            list.add(i)
            i += step
        }
        list.add(size - 1)
        return list.toIntArray()
    }

    private fun drawSmoothTerrain(
        canvas: Canvas,
        hm: ContourHeightmap,
        width: Float,
        height: Float,
        yawRad: Float,
        pitchRad: Float,
        gridStep: Int,
        scratch: RenderScratch,
    ) {
        val displayMaxZ = max(hm.maxHeight, max(hm.maxLevel.toFloat(), 1f))
        val zHeightRatio = 0.44f / displayMaxZ
        val proj = IsoProjector(width, height, zHeightRatio, displayMaxZ, yawRad, pitchRad)
        val unitStroke = (min(width, height) / 420f).coerceAtLeast(1f)
        val uDir = if (proj.sinYaw >= 0f) 1 else -1
        val vDir = if (proj.cosYaw >= 0f) 1 else -1
        val backU = if (uDir > 0) 0f else 1f
        val backV = if (vDir > 0) 0f else 1f
        val frontU = 1f - backU
        val frontV = 1f - backV

        val p00 = scratch.p00
        val p10 = scratch.p10
        val p11 = scratch.p11
        val p01 = scratch.p01
        val path = scratch.path

        // 1. Draw tilted 3D base plane (matching classic topographic 3D block diagram)
        val baseFill = scratch.baseFill
        val baseEdge = scratch.baseEdge.apply { strokeWidth = 1.4f * unitStroke }
        proj.project(0f, 0f, 0f, p00)
        proj.project(1f, 0f, 0f, p10)
        proj.project(1f, 1f, 0f, p11)
        proj.project(0f, 1f, 0f, p01)

        path.reset()
        path.moveTo(p00[0], p00[1])
        path.lineTo(p10[0], p10[1])
        path.lineTo(p11[0], p11[1])
        path.lineTo(p01[0], p01[1])
        path.close()
        canvas.drawPath(path, baseFill)
        canvas.drawPath(path, baseEdge)

        // 2. Subtle dashed elevation reference frame on whichever two walls face away from the camera
        val frameLevels = hm.maxLevel.coerceIn(1, 8)
        val dashedFrame = scratch.dashedFrame
        dashedFrame.strokeWidth = 1.0f * unitStroke
        if (abs(scratch.lastDashUnitStroke - unitStroke) > 0.05f) {
            scratch.lastDashUnitStroke = unitStroke
            dashedFrame.pathEffect = DashPathEffect(floatArrayOf(5f * unitStroke, 4f * unitStroke), 0f)
        }
        val topZ = max(hm.maxHeight, frameLevels.toFloat())
        val corners = arrayOf(backU to backV, frontU to backV, backU to frontV)
        for ((cu, cv) in corners) {
            proj.project(cu, cv, 0f, p00)
            proj.project(cu, cv, topZ, p01)
            canvas.drawLine(p00[0], p00[1], p01[0], p01[1], dashedFrame)
        }
        for (lvl in 1..frameLevels) {
            val z = lvl.toFloat()
            proj.project(backU, frontV, z, p01)
            proj.project(backU, backV, z, p00)
            proj.project(frontU, backV, z, p10)
            canvas.drawLine(p01[0], p01[1], p00[0], p00[1], dashedFrame)
            canvas.drawLine(p00[0], p00[1], p10[0], p10[1], dashedFrame)
        }

        if (hm.maxHeight <= 0.001f) return

        // 3. Painter's algorithm back-to-front over the heightmap grid
        val quadFill = scratch.quadFill
        val quadSeam = scratch.quadSeam.apply { strokeWidth = 0.8f * unitStroke }
        val contourPaint = scratch.contourPaint.apply { strokeWidth = 1.55f * unitStroke }

        // Light vector (normalized) from upper-left-front
        val lx = -0.52f
        val ly = -0.38f
        val lz = 0.76f

        val cols = hm.cols
        val rows = hm.rows
        val invCols = 1f / (cols - 1).toFloat()
        val invRows = 1f / (rows - 1).toFloat()
        val cA = scratch.cA
        val cB = scratch.cB

        val xCoords = sampleCoords(cols, gridStep)
        val yCoords = sampleCoords(rows, gridStep)
        val gyOrder = painterOrder(yCoords.size - 1, proj.eyeV01)
        val gxOrder = painterOrder(xCoords.size - 1, proj.eyeU01)

        for (yi in gyOrder) {
            val gy0 = yCoords[yi]
            val gy1 = yCoords[yi + 1]
            val v0 = gy0 * invRows
            val v1 = gy1 * invRows
            val cellRows = (gy1 - gy0).coerceAtLeast(1)
            for (xi in gxOrder) {
                val gx0 = xCoords[xi]
                val gx1 = xCoords[xi + 1]
                val h00 = hm.heightAt(gx0, gy0)
                val h10 = hm.heightAt(gx1, gy0)
                val h11 = hm.heightAt(gx1, gy1)
                val h01 = hm.heightAt(gx0, gy1)

                // Skip completely flat base-plane quads so the grey base plane shows cleanly.
                if (h00 < 0.02f && h10 < 0.02f && h11 < 0.02f && h01 < 0.02f) continue

                val u0 = gx0 * invCols
                val u1 = gx1 * invCols
                proj.project(u0, v0, h00, p00)
                proj.project(u1, v0, h10, p10)
                proj.project(u1, v1, h11, p11)
                proj.project(u0, v1, h01, p01)

                // Surface normal from finite differences
                val cellCols = (gx1 - gx0).coerceAtLeast(1)
                val dzdx = ((h10 - h00) + (h11 - h01)) * 0.5f * ((cols - 1).toFloat() / cellCols) * zHeightRatio
                val dzdy = ((h01 - h00) + (h11 - h10)) * 0.5f * ((rows - 1).toFloat() / cellRows) * zHeightRatio
                val nx = -dzdx
                val ny = -dzdy
                val nz = 1f
                val invLen = 1f / sqrt(nx * nx + ny * ny + nz * nz)
                val ndotl = ((nx * lx + ny * ly + nz * lz) * invLen).coerceIn(0f, 1f)

                val avgH = (h00 + h10 + h11 + h01) * 0.25f
                val elevFrac = (avgH / displayMaxZ).coerceIn(0f, 1f)
                // Clean paper-topographic shading (bright ivory/white peaks, warm shaded slopes)
                val shade = (0.74f + 0.26f * ndotl + 0.06f * elevFrac).coerceIn(0.68f, 1.0f)
                val r = (252f * shade).toInt().coerceIn(0, 255)
                val g = (249f * shade).toInt().coerceIn(0, 255)
                val b = (242f * shade).toInt().coerceIn(0, 255)
                val color = (0xff shl 24) or (r shl 16) or (g shl 8) or b

                path.reset()
                path.moveTo(p00[0], p00[1])
                path.lineTo(p10[0], p10[1])
                path.lineTo(p11[0], p11[1])
                path.lineTo(p01[0], p01[1])
                path.close()
                quadFill.color = color
                quadSeam.color = color
                canvas.drawPath(path, quadFill)
                canvas.drawPath(path, quadSeam)

                // Drape 3D contour lines across this quad using marching squares at integer levels
                val minQ = min(min(h00, h10), min(h11, h01))
                val maxQ = max(max(h00, h10), max(h11, h01))
                val firstLevel = max(1, minQ.toInt() + 1)
                val lastLevel = min(hm.maxLevel, maxQ.toInt())
                for (lvl in firstLevel..lastLevel) {
                    val z = lvl.toFloat()
                    drawQuadContourSegment(
                        canvas, proj, contourPaint,
                        u0, v0, u1, v1,
                        h00, h10, h11, h01,
                        z, cA, cB,
                    )
                }

                // Draw crisp base shoreline contour at z = 0.08f
                if (minQ < 0.08f && maxQ >= 0.08f) {
                    drawQuadContourSegment(
                        canvas, proj, contourPaint,
                        u0, v0, u1, v1,
                        h00, h10, h11, h01,
                        0.08f, cA, cB,
                    )
                }
            }
        }

        // 4. Front cutaway walls along the two front-facing edges when terrain reaches the border
        fun cutawayColor(nx: Float, ny: Float): Int {
            val shade = (0.5f + 0.5f * (nx * lx + ny * ly)).coerceIn(0f, 1f)
            val r = (158f + 42f * shade).toInt().coerceIn(0, 255)
            val g = (150f + 42f * shade).toInt().coerceIn(0, 255)
            val b = (136f + 40f * shade).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
        val frontRow = if (vDir > 0) rows - 1 else 0
        val frontCol = if (uDir > 0) cols - 1 else 0
        val wallVPaint = scratch.wallVPaint.apply { color = cutawayColor(0f, vDir.toFloat()) }
        val wallUPaint = scratch.wallUPaint.apply { color = cutawayColor(uDir.toFloat(), 0f) }

        if (proj.eyeV01 > 1f || proj.eyeV01 < 0f) {
            for (xi in gxOrder) {
                val gx0 = xCoords[xi]
                val gx1 = xCoords[xi + 1]
                val h0 = hm.heightAt(gx0, frontRow)
                val h1 = hm.heightAt(gx1, frontRow)
                if (h0 > 0.02f || h1 > 0.02f) {
                    val u0 = gx0 * invCols
                    val u1 = gx1 * invCols
                    proj.project(u0, frontV, h0, p00)
                    proj.project(u1, frontV, h1, p10)
                    proj.project(u1, frontV, 0f, p11)
                    proj.project(u0, frontV, 0f, p01)
                    path.reset()
                    path.moveTo(p00[0], p00[1])
                    path.lineTo(p10[0], p10[1])
                    path.lineTo(p11[0], p11[1])
                    path.lineTo(p01[0], p01[1])
                    path.close()
                    canvas.drawPath(path, wallVPaint)
                    canvas.drawLine(p00[0], p00[1], p10[0], p10[1], contourPaint)
                }
            }
        }
        if (proj.eyeU01 > 1f || proj.eyeU01 < 0f) {
            for (yi in gyOrder) {
                val gy0 = yCoords[yi]
                val gy1 = yCoords[yi + 1]
                val h0 = hm.heightAt(frontCol, gy0)
                val h1 = hm.heightAt(frontCol, gy1)
                if (h0 > 0.02f || h1 > 0.02f) {
                    val v0 = gy0 * invRows
                    val v1 = gy1 * invRows
                    proj.project(frontU, v0, h0, p00)
                    proj.project(frontU, v1, h1, p10)
                    proj.project(frontU, v1, 0f, p11)
                    proj.project(frontU, v0, 0f, p01)
                    path.reset()
                    path.moveTo(p00[0], p00[1])
                    path.lineTo(p10[0], p10[1])
                    path.lineTo(p11[0], p11[1])
                    path.lineTo(p01[0], p01[1])
                    path.close()
                    canvas.drawPath(path, wallUPaint)
                    canvas.drawLine(p00[0], p00[1], p10[0], p10[1], contourPaint)
                }
            }
        }
    }

    private fun drawQuadContourSegment(
        canvas: Canvas,
        proj: IsoProjector,
        paint: Paint,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        h00: Float,
        h10: Float,
        h11: Float,
        h01: Float,
        level: Float,
        ptA: FloatArray,
        ptB: FloatArray,
    ) {
        var count = 0
        fun addCrossing(u: Float, v: Float) {
            if (count == 0) {
                proj.project(u, v, level, ptA)
                count = 1
            } else if (count == 1) {
                proj.project(u, v, level, ptB)
                count = 2
            }
        }
        // Top edge (00 -> 10)
        if ((h00 < level) != (h10 < level)) {
            val t = (level - h00) / (h10 - h00)
            addCrossing(u0 + (u1 - u0) * t, v0)
        }
        // Right edge (10 -> 11)
        if ((h10 < level) != (h11 < level)) {
            val t = (level - h10) / (h11 - h10)
            addCrossing(u1, v0 + (v1 - v0) * t)
        }
        // Bottom edge (11 -> 01)
        if ((h11 < level) != (h01 < level)) {
            val t = (level - h11) / (h01 - h11)
            addCrossing(u1 + (u0 - u1) * t, v1)
        }
        // Left edge (01 -> 00)
        if ((h01 < level) != (h00 < level)) {
            val t = (level - h01) / (h00 - h01)
            addCrossing(u0, v1 + (v0 - v1) * t)
        }
        if (count == 2) {
            canvas.drawLine(ptA[0], ptA[1], ptB[0], ptB[1], paint)
        }
    }

    private fun drawBlockTerrain(
        canvas: Canvas,
        voxels: VoxelHeightmap,
        width: Float,
        height: Float,
        yawRad: Float,
        pitchRad: Float,
        scratch: RenderScratch,
    ) {
        val cols = voxels.cols
        val rows = voxels.rows
        val maxZ = max(voxels.maxBlocks, 1).toFloat()
        // Keep voxels cube-proportioned relative to cell width
        val cellStep = 1f / max(cols, rows).toFloat()
        val zHeightRatio = min(cellStep * 0.92f, 0.46f / maxZ)
        val proj = IsoProjector(width, height, zHeightRatio, maxZ + 0.5f, yawRad, pitchRad)
        val uDir = if (proj.sinYaw >= 0f) 1 else -1
        val vDir = if (proj.cosYaw >= 0f) 1 else -1

        val unitStroke = (min(width, height) / 460f).coerceAtLeast(0.8f)
        val facePaint = scratch.facePaint
        val edgePaint = scratch.edgePaint.apply { strokeWidth = 0.9f * unitStroke }

        val p00 = scratch.p00
        val p10 = scratch.p10
        val p11 = scratch.p11
        val p01 = scratch.p01
        val b00 = scratch.b00
        val b10 = scratch.b10
        val b11 = scratch.b11
        val b01 = scratch.b01
        val path = scratch.path

        val invCols = 1f / cols.toFloat()
        val invRows = 1f / rows.toFloat()
        val snowLevel = if (voxels.maxBlocks >= 6) (voxels.maxBlocks * 0.80f).toInt() else Int.MAX_VALUE
        val stoneLevel = if (voxels.maxBlocks >= 4) (voxels.maxBlocks * 0.56f).toInt() else Int.MAX_VALUE

        val vyOrder = painterOrder(rows, proj.eyeV01)
        val vxOrder = painterOrder(cols, proj.eyeU01)

        // Draw Minecraft-style voxel columns strictly back-to-front toward the camera eye
        for (vy in vyOrder) {
            val v0 = vy * invRows
            val v1 = (vy + 1) * invRows
            val vCellDir = when {
                proj.eyeV01 > v1 -> 1
                proj.eyeV01 < v0 -> -1
                else -> 0
            }
            for (vx in vxOrder) {
                val u0 = vx * invCols
                val u1 = (vx + 1) * invCols
                val uCellDir = when {
                    proj.eyeU01 > u1 -> 1
                    proj.eyeU01 < u0 -> -1
                    else -> 0
                }
                val h = voxels.heightAt(vx, vy)
                val activeUDir = if (uCellDir != 0) uCellDir else uDir
                val activeVDir = if (vCellDir != 0) vCellDir else vDir
                val cellVBrighter = (activeVDir * -0.38f) >= (activeUDir * -0.52f)

                // Always draw a crisp base ground tile at z = 0
                if (h == 0) {
                    val checker = ((vx + vy) and 1) == 0
                    val topColor = if (checker) 0xff86aa66.toInt() else 0xff7da15e.toInt()
                    val lightSide = 0xff6d4e35.toInt()
                    val darkSide = 0xff573d28.toInt()
                    drawIsoBlock(
                        canvas, proj, path, facePaint, edgePaint,
                        u0, v0, u1, v1,
                        zBottom = -0.35f, zTop = 0f,
                        topColor = topColor,
                        vFaceColor = if (cellVBrighter) lightSide else darkSide,
                        uFaceColor = if (cellVBrighter) darkSide else lightSide,
                        drawVFace = (vCellDir > 0 && vy == rows - 1) || (vCellDir < 0 && vy == 0),
                        drawUFace = (uCellDir > 0 && vx == cols - 1) || (uCellDir < 0 && vx == 0),
                        uDir = activeUDir,
                        vDir = activeVDir,
                        vFaceBrighter = cellVBrighter,
                        p00, p10, p11, p01, b00, b10, b11, b01,
                    )
                    continue
                }

                val vNeighborIdx = vy + vCellDir
                val uNeighborIdx = vx + uCellDir
                val vNeighbor = if (vCellDir != 0 && vNeighborIdx in 0 until rows) voxels.heightAt(vx, vNeighborIdx) else 0
                val uNeighbor = if (uCellDir != 0 && uNeighborIdx in 0 until cols) voxels.heightAt(uNeighborIdx, vy) else 0
                val minVisibleZ = min(
                    if (vCellDir != 0) vNeighbor else h,
                    if (uCellDir != 0) uNeighbor else h,
                ).coerceAtLeast(0)

                // Base stone skirt below z=0 on visible front edges
                val drawSkirtV = (vCellDir > 0 && vy == rows - 1) || (vCellDir < 0 && vy == 0)
                val drawSkirtU = (uCellDir > 0 && vx == cols - 1) || (uCellDir < 0 && vx == 0)
                if (drawSkirtV || drawSkirtU) {
                    val lightSkirt = 0xff6e7072.toInt()
                    val darkSkirt = 0xff595b5d.toInt()
                    drawIsoBlock(
                        canvas, proj, path, facePaint, edgePaint,
                        u0, v0, u1, v1,
                        zBottom = -0.35f, zTop = 0f,
                        topColor = 0xff7c7f80.toInt(),
                        vFaceColor = if (cellVBrighter) lightSkirt else darkSkirt,
                        uFaceColor = if (cellVBrighter) darkSkirt else lightSkirt,
                        drawVFace = drawSkirtV,
                        drawUFace = drawSkirtU,
                        uDir = activeUDir,
                        vDir = activeVDir,
                        vFaceBrighter = cellVBrighter,
                        p00, p10, p11, p01, b00, b10, b11, b01,
                    )
                }

                // Stack exposed cliff blocks and always include the top cap (z == h) so valleys/plateaus never leave holes.
                val startZ = min(h, max(1, minVisibleZ + 1))
                for (z in startZ..h) {
                    val isTop = z == h
                    val (topCol, lightCol, darkCol) = blockColors(z, h, isTop, stoneLevel, snowLevel, vx, vy)
                    drawIsoBlock(
                        canvas, proj, path, facePaint, edgePaint,
                        u0, v0, u1, v1,
                        zBottom = (z - 1).toFloat(),
                        zTop = z.toFloat(),
                        topColor = topCol,
                        vFaceColor = if (cellVBrighter) lightCol else darkCol,
                        uFaceColor = if (cellVBrighter) darkCol else lightCol,
                        drawVFace = vCellDir != 0 && z > vNeighbor,
                        drawUFace = uCellDir != 0 && z > uNeighbor,
                        uDir = activeUDir,
                        vDir = activeVDir,
                        vFaceBrighter = cellVBrighter,
                        p00, p10, p11, p01, b00, b10, b11, b01,
                        grassLip = isTop && z < stoneLevel,
                    )
                }
            }
        }
    }

    private fun blockColors(
        z: Int,
        columnTop: Int,
        isTop: Boolean,
        stoneLevel: Int,
        snowLevel: Int,
        vx: Int,
        vy: Int,
    ): Triple<Int, Int, Int> {
        val jitter = if (((vx * 7 + vy * 13 + z * 3) and 1) == 0) 0 else -7
        return when {
            isTop && z >= snowLevel -> Triple(
                tint(0xfff4f7fa.toInt(), jitter / 2),
                tint(0xffdce3ea.toInt(), jitter / 2),
                tint(0xffc3cdd8.toInt(), jitter / 2),
            )

            z >= stoneLevel || (!isTop && columnTop - z >= 2) -> Triple(
                tint(0xff9b9fa1.toInt(), jitter),
                tint(0xff7e8285.toInt(), jitter),
                tint(0xff65696c.toInt(), jitter),
            )

            isTop -> Triple(
                tint(0xff6ea84b.toInt(), jitter),
                tint(0xff8c6240.toInt(), jitter),
                tint(0xff704d31.toInt(), jitter),
            )

            else -> Triple(
                tint(0xff946a46.toInt(), jitter),
                tint(0xff845b3a.toInt(), jitter),
                tint(0xff69472c.toInt(), jitter),
            )
        }
    }

    private fun tint(argb: Int, delta: Int): Int {
        val r = (((argb shr 16) and 0xff) + delta).coerceIn(0, 255)
        val g = (((argb shr 8) and 0xff) + delta).coerceIn(0, 255)
        val b = ((argb and 0xff) + delta).coerceIn(0, 255)
        return (0xff shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun drawIsoBlock(
        canvas: Canvas,
        proj: IsoProjector,
        path: Path,
        facePaint: Paint,
        edgePaint: Paint,
        u0: Float,
        v0: Float,
        u1: Float,
        v1: Float,
        zBottom: Float,
        zTop: Float,
        topColor: Int,
        vFaceColor: Int,
        uFaceColor: Int,
        drawVFace: Boolean,
        drawUFace: Boolean,
        uDir: Int,
        vDir: Int,
        vFaceBrighter: Boolean,
        p00: FloatArray,
        p10: FloatArray,
        p11: FloatArray,
        p01: FloatArray,
        b00: FloatArray,
        b10: FloatArray,
        b11: FloatArray,
        b01: FloatArray,
        grassLip: Boolean = false,
    ) {
        proj.project(u0, v0, zTop, p00)
        proj.project(u1, v0, zTop, p10)
        proj.project(u1, v1, zTop, p11)
        proj.project(u0, v1, zTop, p01)

        if (drawVFace || drawUFace) {
            proj.project(u0, v0, zBottom, b00)
            proj.project(u1, v0, zBottom, b10)
            proj.project(u1, v1, zBottom, b11)
            proj.project(u0, v1, zBottom, b01)
        }

        val lightLip = 0xff5d913e.toInt()
        val darkLip = 0xff4b7831.toInt()

        fun drawVerticalFace(
            tA: FloatArray,
            tB: FloatArray,
            bB: FloatArray,
            bA: FloatArray,
            fillColor: Int,
            lipColor: Int,
        ) {
            path.reset()
            path.moveTo(tA[0], tA[1])
            path.lineTo(tB[0], tB[1])
            path.lineTo(bB[0], bB[1])
            path.lineTo(bA[0], bA[1])
            path.close()
            facePaint.color = fillColor
            canvas.drawPath(path, facePaint)
            if (grassLip) {
                val lip = 0.28f
                path.reset()
                path.moveTo(tA[0], tA[1])
                path.lineTo(tB[0], tB[1])
                path.lineTo(tB[0] + (bB[0] - tB[0]) * lip, tB[1] + (bB[1] - tB[1]) * lip)
                path.lineTo(tA[0] + (bA[0] - tA[0]) * lip, tA[1] + (bA[1] - tA[1]) * lip)
                path.close()
                facePaint.color = lipColor
                canvas.drawPath(path, facePaint)
            }
            path.reset()
            path.moveTo(tA[0], tA[1])
            path.lineTo(tB[0], tB[1])
            path.lineTo(bB[0], bB[1])
            path.lineTo(bA[0], bA[1])
            path.close()
            canvas.drawPath(path, edgePaint)
        }

        // Front-facing v face (v = v1 when vDir > 0, v = v0 when vDir < 0)
        if (drawVFace) {
            val lip = if (vFaceBrighter) lightLip else darkLip
            if (vDir > 0) {
                drawVerticalFace(p01, p11, b11, b01, vFaceColor, lip)
            } else {
                drawVerticalFace(p00, p10, b10, b00, vFaceColor, lip)
            }
        }

        // Front-facing u face (u = u1 when uDir > 0, u = u0 when uDir < 0)
        if (drawUFace) {
            val lip = if (vFaceBrighter) darkLip else lightLip
            if (uDir > 0) {
                drawVerticalFace(p10, p11, b11, b10, uFaceColor, lip)
            } else {
                drawVerticalFace(p00, p01, b01, b00, uFaceColor, lip)
            }
        }

        // Top face: p00 -> p10 -> p11 -> p01
        path.reset()
        path.moveTo(p00[0], p00[1])
        path.lineTo(p10[0], p10[1])
        path.lineTo(p11[0], p11[1])
        path.lineTo(p01[0], p01[1])
        path.close()
        facePaint.color = topColor
        canvas.drawPath(path, facePaint)
        canvas.drawPath(path, edgePaint)
    }
}
