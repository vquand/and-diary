package io.github.vquand.anddiary

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

internal interface KeyValueStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun getFloat(key: String): Float?
    fun putFloat(key: String, value: Float)
}

internal class BrushBankSelection(
    private val store: KeyValueStore,
    private val key: String = KEY_BANK_BRUSH_ID,
) {
    val selectedId: String
        get() {
            val saved = store.getString(key)
            return if (saved != null && BrushBank.byId(saved) != null) saved else BrushBank.DEFAULT_ID
        }

    val selectedSpec: BrushSpec
        get() = BrushBank.byId(selectedId) ?: BrushBank.defaultSpec

    fun select(id: String): BrushSpec {
        val resolved = BrushBank.byId(id) ?: BrushBank.defaultSpec
        store.putString(key, resolved.id)
        return resolved
    }

    fun sizeFor(spec: BrushSpec): Float {
        val saved = store.getFloat(sizeKey(spec.id)) ?: spec.defaultSizeDp
        return saved.coerceIn(spec.minSizeDp, spec.maxSizeDp)
    }

    fun setSize(spec: BrushSpec, sizeDp: Float): Float {
        val clamped = sizeDp.coerceIn(spec.minSizeDp, spec.maxSizeDp)
        store.putFloat(sizeKey(spec.id), clamped)
        return clamped
    }

    companion object {
        const val KEY_BANK_BRUSH_ID = "bank_brush_id"
        const val KEY_BANK_SIZE_PREFIX = "bank_size_dp_"

        fun sizeKey(brushId: String): String = "$KEY_BANK_SIZE_PREFIX$brushId"
    }
}

internal data class BrushTipSpec(
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val cornerRounding: Float = 1f,
    val slantDegrees: Float = 0f,
    val pinch: Float = 0f,
    val rotationDegrees: Float = 0f,
    val particleGapDistanceScale: Float = 0f,
)

internal enum class BrushSourceKind {
    NORMALIZED_PRESSURE,
    DISTANCE_TRAVELED,
    DISTANCE_REMAINING,
    SPEED,
}

internal sealed class BrushBehaviorSpec {
    data class SizeResponse(
        val source: BrushSourceKind,
        val sourceStart: Float = 0f,
        val sourceEnd: Float,
        val minMultiplier: Float,
        val maxMultiplier: Float = 1f,
    ) : BrushBehaviorSpec()

    data class OpacityResponse(
        val source: BrushSourceKind,
        val sourceStart: Float = 0f,
        val sourceEnd: Float,
        val minMultiplier: Float,
        val maxMultiplier: Float = 1f,
    ) : BrushBehaviorSpec()

    data class LateralJitter(
        val seed: Int,
        val basePeriodCm: Float,
        val amplitude: Float,
    ) : BrushBehaviorSpec()
}

internal enum class TextureMappingSpec {
    WORLD_TILED,
    STROKE_TILED,
    STAMPING,
}

internal enum class ProceduralStampKind {
    FINE_GRAIN,
    COARSE_GRAIN,
    BRISTLE_STREAKS,
    RADIAL_FALLOFF,
}

internal data class BrushSpec(
    val id: String,
    val storageCode: Int,
    val nameRes: Int,
    val icon: ToolIconKind,
    val defaultColor: Int,
    val defaultSizeDp: Float,
    val minSizeDp: Float,
    val maxSizeDp: Float,
    val presetsDp: FloatArray,
    val tip: BrushTipSpec = BrushTipSpec(),
    val behaviors: List<BrushBehaviorSpec> = emptyList(),
    val opacity: Float = 1f,
    val textureId: String? = null,
    val textureMapping: TextureMappingSpec? = null,
    val proceduralStamp: ProceduralStampKind? = null,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is BrushSpec && id == other.id && storageCode == other.storageCode)

    override fun hashCode(): Int = 31 * id.hashCode() + storageCode
}

internal object BrushBank {
    const val DEFAULT_ID = "pencil"

    val all: List<BrushSpec> = listOf(
        BrushSpec(
            id = "pencil",
            storageCode = 16,
            nameRes = R.string.brush_pencil,
            icon = ToolIconKind.PENCIL,
            defaultColor = 0xff4a4743.toInt(),
            defaultSizeDp = 2.5f,
            minSizeDp = 1f,
            maxSizeDp = 8f,
            presetsDp = floatArrayOf(1.5f, 2.5f, 4f),
            tip = BrushTipSpec(cornerRounding = 1f),
            behaviors = listOf(
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.35f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.45f,
                    maxMultiplier = 0.95f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.SPEED,
                    sourceEnd = 45f,
                    minMultiplier = 1f,
                    maxMultiplier = 0.90f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_TRAVELED,
                    sourceEnd = 0.5f,
                    minMultiplier = 0.50f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_REMAINING,
                    sourceEnd = 1f,
                    minMultiplier = 0.55f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.LateralJitter(
                    seed = 16,
                    basePeriodCm = 0.20f,
                    amplitude = 0.06f,
                ),
            ),
            textureId = "pencil",
            textureMapping = TextureMappingSpec.WORLD_TILED,
            proceduralStamp = ProceduralStampKind.FINE_GRAIN,
        ),
        BrushSpec(
            id = "chinese-ink-brush",
            storageCode = 17,
            nameRes = R.string.brush_chinese_ink_brush,
            icon = ToolIconKind.CHINESE_INK_BRUSH,
            defaultColor = 0xff272320.toInt(),
            defaultSizeDp = 9f,
            minSizeDp = 3f,
            maxSizeDp = 24f,
            presetsDp = floatArrayOf(5f, 9f, 16f),
            tip = BrushTipSpec(cornerRounding = 1f),
            behaviors = listOf(
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.12f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.85f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.SPEED,
                    sourceEnd = 45f,
                    minMultiplier = 1f,
                    maxMultiplier = 0.60f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_TRAVELED,
                    sourceEnd = 1f,
                    minMultiplier = 0.55f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_REMAINING,
                    sourceEnd = 3f,
                    minMultiplier = 0.08f,
                    maxMultiplier = 1f,
                ),
            ),
            textureId = "chinese-ink-brush",
            textureMapping = TextureMappingSpec.STROKE_TILED,
            proceduralStamp = ProceduralStampKind.BRISTLE_STREAKS,
        ),
        BrushSpec(
            id = "calligraphy-quill",
            storageCode = 18,
            nameRes = R.string.brush_calligraphy_quill,
            icon = ToolIconKind.CALLIGRAPHY_QUILL,
            defaultColor = 0xff36496e.toInt(),
            defaultSizeDp = 11f,
            minSizeDp = 4f,
            maxSizeDp = 26f,
            presetsDp = floatArrayOf(6f, 11f, 18f),
            tip = BrushTipSpec(
                scaleX = 1f,
                scaleY = 0.14f,
                cornerRounding = 0.2f,
                rotationDegrees = 40f,
            ),
            behaviors = listOf(
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.88f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.90f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_TRAVELED,
                    sourceEnd = 1f,
                    minMultiplier = 0.55f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_REMAINING,
                    sourceEnd = 1f,
                    minMultiplier = 0.55f,
                    maxMultiplier = 1f,
                ),
            ),
        ),
        BrushSpec(
            id = "airbrush",
            storageCode = 19,
            nameRes = R.string.brush_airbrush,
            icon = ToolIconKind.AIRBRUSH,
            defaultColor = 0xff272320.toInt(),
            defaultSizeDp = 22f,
            minSizeDp = 8f,
            maxSizeDp = 48f,
            presetsDp = floatArrayOf(12f, 22f, 36f),
            tip = BrushTipSpec(
                cornerRounding = 1f,
                particleGapDistanceScale = 0.08f,
            ),
            behaviors = listOf(
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.55f,
                    maxMultiplier = 1.25f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.40f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.SPEED,
                    sourceEnd = 45f,
                    minMultiplier = 1f,
                    maxMultiplier = 0.55f,
                ),
                BrushBehaviorSpec.LateralJitter(
                    seed = 19,
                    basePeriodCm = 0.18f,
                    amplitude = 0.04f,
                ),
            ),
            opacity = 0.30f,
            textureId = "airbrush",
            textureMapping = TextureMappingSpec.STAMPING,
            proceduralStamp = ProceduralStampKind.RADIAL_FALLOFF,
        ),
        BrushSpec(
            id = "charcoal",
            storageCode = 20,
            nameRes = R.string.brush_charcoal,
            icon = ToolIconKind.CHARCOAL,
            defaultColor = 0xff272320.toInt(),
            defaultSizeDp = 13f,
            minSizeDp = 6f,
            maxSizeDp = 30f,
            presetsDp = floatArrayOf(8f, 13f, 22f),
            tip = BrushTipSpec(cornerRounding = 0.7f),
            behaviors = listOf(
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.70f,
                    maxMultiplier = 1.20f,
                ),
                BrushBehaviorSpec.OpacityResponse(
                    source = BrushSourceKind.NORMALIZED_PRESSURE,
                    sourceEnd = 1f,
                    minMultiplier = 0.40f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.SizeResponse(
                    source = BrushSourceKind.DISTANCE_TRAVELED,
                    sourceEnd = 0.8f,
                    minMultiplier = 0.50f,
                    maxMultiplier = 1f,
                ),
                BrushBehaviorSpec.LateralJitter(
                    seed = 20,
                    basePeriodCm = 0.22f,
                    amplitude = 0.08f,
                ),
            ),
            opacity = 0.75f,
            textureId = "charcoal",
            textureMapping = TextureMappingSpec.WORLD_TILED,
            proceduralStamp = ProceduralStampKind.COARSE_GRAIN,
        ),
    )

    private val specsById: Map<String, BrushSpec> = all.associateBy { it.id }
    private val specsByCode: Map<Int, BrushSpec> = all.associateBy { it.storageCode }

    val defaultSpec: BrushSpec = specsById.getValue(DEFAULT_ID)

    fun byId(id: String): BrushSpec? = specsById[id]

    fun byStorageCode(code: Int): BrushSpec? = specsByCode[code]
}

internal object BrushStampGenerator {
    fun generateAlphaMask(kind: ProceduralStampKind, size: Int = 64, seed: Long = 0x5EEDL): IntArray {
        require(size > 0) { "Stamp size must be positive" }
        val result = IntArray(size * size)
        return when (kind) {
            // ~56% mean coverage fine paper tooth
            ProceduralStampKind.FINE_GRAIN -> fillGrainMask(result, size, seed, contrast = 1.6f, bias = 0.56f, gridStep = 4)
            // ~49% mean coverage coarser 3-octave charcoal grain
            ProceduralStampKind.COARSE_GRAIN -> fillCoarseGrainMask(result, size, seed)
            // Directional bristle streaks along +x
            ProceduralStampKind.BRISTLE_STREAKS -> fillBristleStreaksMask(result, size, seed)
            // Soft Gaussian radial dot for airbrush fallback
            ProceduralStampKind.RADIAL_FALLOFF -> fillRadialMask(result, size, seed)
        }
    }

    /**
     * Converts decoded ARGB pixels into white-tinted alpha masks suitable for MODULATE textures.
     * Uses the PNG alpha channel directly when present; converts luminance -> alpha only when opaque.
     */
    fun grayscalePixelsToMaskArgb(pixels: IntArray): IntArray {
        var hasAlpha = false
        for (pixel in pixels) {
            if (((pixel ushr 24) and 0xff) < 255) {
                hasAlpha = true
                break
            }
        }
        return IntArray(pixels.size) { index ->
            val p = pixels[index]
            val alpha = if (hasAlpha) {
                (p ushr 24) and 0xff
            } else {
                val r = (p ushr 16) and 0xff
                val g = (p ushr 8) and 0xff
                val b = p and 0xff
                (0.299f * r + 0.587f * g + 0.114f * b).roundToInt().coerceIn(0, 255)
            }
            (alpha shl 24) or 0x00ffffff
        }
    }

    private fun fillGrainMask(
        out: IntArray,
        size: Int,
        seed: Long,
        contrast: Float,
        bias: Float,
        gridStep: Int,
    ): IntArray {
        val coarseW = (size / gridStep).coerceAtLeast(2)
        val coarse = FloatArray(coarseW * coarseW) { idx ->
            hashUnit(idx % coarseW, idx / coarseW, seed)
        }
        for (y in 0 until size) {
            val fy = (y.toFloat() / size) * coarseW
            val y0 = fy.toInt() % coarseW
            val y1 = (y0 + 1) % coarseW
            val ty = smoothstep(fy - fy.toInt())
            for (x in 0 until size) {
                val fx = (x.toFloat() / size) * coarseW
                val x0 = fx.toInt() % coarseW
                val x1 = (x0 + 1) % coarseW
                val tx = smoothstep(fx - fx.toInt())

                val lowFreq = bilerp(coarse, coarseW, x0, y0, x1, y1, tx, ty)
                val highFreq = hashUnit(x, y, seed xor HASH_MIX_1)
                val centered = (0.5f * lowFreq + 0.5f * highFreq - 0.5f) * contrast + bias
                out[y * size + x] = (centered.coerceIn(0f, 1f) * 255f).roundToInt()
            }
        }
        return out
    }

    private fun fillCoarseGrainMask(out: IntArray, size: Int, seed: Long): IntArray {
        val g1W = (size / 8).coerceAtLeast(2)
        val g2W = (size / 4).coerceAtLeast(2)
        val g1 = FloatArray(g1W * g1W) { idx -> hashUnit(idx % g1W, idx / g1W, seed) }
        val g2 = FloatArray(g2W * g2W) { idx -> hashUnit(idx % g2W, idx / g2W, seed xor HASH_MIX_2) }
        for (y in 0 until size) {
            val fy1 = (y.toFloat() / size) * g1W
            val fy2 = (y.toFloat() / size) * g2W
            val y10 = fy1.toInt() % g1W
            val y11 = (y10 + 1) % g1W
            val y20 = fy2.toInt() % g2W
            val y21 = (y20 + 1) % g2W
            val ty1 = smoothstep(fy1 - fy1.toInt())
            val ty2 = smoothstep(fy2 - fy2.toInt())
            for (x in 0 until size) {
                val fx1 = (x.toFloat() / size) * g1W
                val fx2 = (x.toFloat() / size) * g2W
                val x10 = fx1.toInt() % g1W
                val x11 = (x10 + 1) % g1W
                val x20 = fx2.toInt() % g2W
                val x21 = (x20 + 1) % g2W
                val tx1 = smoothstep(fx1 - fx1.toInt())
                val tx2 = smoothstep(fx2 - fx2.toInt())

                val oct1 = bilerp(g1, g1W, x10, y10, x11, y11, tx1, ty1)
                val oct2 = bilerp(g2, g2W, x20, y20, x21, y21, tx2, ty2)
                val oct3 = hashUnit(x, y, seed xor HASH_MIX_1)
                val v = (0.50f * oct1 + 0.30f * oct2 + 0.20f * oct3 - 0.5f) * 2.1f + 0.49f
                out[y * size + x] = (v.coerceIn(0f, 1f) * 255f).roundToInt()
            }
        }
        return out
    }

    private fun fillBristleStreaksMask(out: IntArray, size: Int, seed: Long): IntArray {
        val rows = FloatArray(size) { y -> hashUnit(0, y, seed) }
        val coarseX = (size / 8).coerceAtLeast(2)
        val band = FloatArray(coarseX * size) { idx -> hashUnit(idx % coarseX, idx / coarseX, seed xor HASH_MIX_2) }
        for (y in 0 until size) {
            val rowWeight = 0.55f + 0.45f * rows[y]
            for (x in 0 until size) {
                val fx = (x.toFloat() / size) * coarseX
                val x0 = fx.toInt() % coarseX
                val x1 = (x0 + 1) % coarseX
                val tx = smoothstep(fx - fx.toInt())
                val streak = lerp(band[y * coarseX + x0], band[y * coarseX + x1], tx)
                val v = (0.65f * rowWeight + 0.35f * streak).coerceIn(0.15f, 1f)
                out[y * size + x] = (v * 255f).roundToInt()
            }
        }
        return out
    }

    private fun fillRadialMask(out: IntArray, size: Int, seed: Long): IntArray {
        val half = (size - 1) / 2f
        val invHalf = if (half > 0f) 1f / half else 1f
        val sigma = 0.34f
        for (y in 0 until size) {
            val dy = (y - half) * invHalf
            for (x in 0 until size) {
                val dx = (x - half) * invHalf
                val r2 = dx * dx + dy * dy
                val gaussian = if (r2 >= 1f) {
                    0f
                } else {
                    val raw = exp((-0.5f * r2 / (sigma * sigma)).toDouble()).toFloat()
                    val edge = exp((-0.5f / (sigma * sigma)).toDouble()).toFloat()
                    ((raw - edge) / (1f - edge)).coerceIn(0f, 1f)
                }
                val mist = 0.82f + 0.18f * hashUnit(x, y, seed)
                val wave = 0.94f + 0.06f * sin((x * 2f * PI / size).toFloat()) * cos((y * 2f * PI / size).toFloat())
                out[y * size + x] = (gaussian * mist * wave * 255f).roundToInt().coerceIn(0, 255)
            }
        }
        return out
    }

    private fun bilerp(grid: FloatArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int, tx: Float, ty: Float): Float {
        val v00 = grid[y0 * w + x0]
        val v10 = grid[y0 * w + x1]
        val v01 = grid[y1 * w + x0]
        val v11 = grid[y1 * w + x1]
        return lerp(lerp(v00, v10, tx), lerp(v01, v11, tx), ty)
    }

    private fun hashUnit(x: Int, y: Int, seed: Long): Float {
        var h = seed xor (x.toLong() * HASH_MIX_1) xor (y.toLong() * HASH_MIX_2)
        h = (h xor (h ushr 30)) * HASH_MUL_1
        h = (h xor (h ushr 27)) * HASH_MUL_2
        h = h xor (h ushr 31)
        return ((h ushr 40).toInt() and 0xffffff) / 16777215f
    }

    private fun smoothstep(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private const val HASH_MIX_1: Long = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
    private const val HASH_MIX_2: Long = -0x3d4d51c2d82b14b1L // 0xC2B2AE3D27D4EB4F
    private const val HASH_MUL_1: Long = -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
    private const val HASH_MUL_2: Long = -0x6b2fb644ecceee15L // 0x94D049BB133111EB
}
