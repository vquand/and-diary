package io.github.vquand.anddiary

import android.content.Context
import android.content.SharedPreferences
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode

internal enum class DiaryTool {
    PEN,
    BRUSH,
    BANK,
    ERASER,
    SELECT,
}

private class SharedPreferencesKeyValueStore(
    private val prefs: SharedPreferences,
) : KeyValueStore {
    override fun getString(key: String): String? = prefs.getString(key, null)

    override fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun getFloat(key: String): Float? =
        if (prefs.contains(key)) prefs.getFloat(key, 0f) else null

    override fun putFloat(key: String, value: Float) {
        prefs.edit().putFloat(key, value).apply()
    }

    override fun getInt(key: String): Int? =
        if (prefs.contains(key)) prefs.getInt(key, 0) else null

    override fun putInt(key: String, value: Int) {
        prefs.edit().putInt(key, value).apply()
    }
}

internal class DiaryBrushes(context: Context, private val density: Float) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val bankSelection = BrushBankSelection(SharedPreferencesKeyValueStore(prefs))

    val textureStore = AssetAndProceduralTextureStore.shared(context)

    val penFamily: BrushFamily = StockBrushes.pressurePen()

    val softBrushFamily: BrushFamily = BrushFamily(
        tip = BrushTip(
            cornerRounding = 1f,
            behaviors = listOf(
                sizeResponse(SourceNode.Source.NORMALIZED_PRESSURE, 1f, 0.28f),
                sizeResponse(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 1.2f, 0.22f),
            ),
        ),
        paint = BrushPaint(),
    )

    private val bankFamilyById: Map<String, BrushFamily> =
        BrushBank.all.associate { it.id to BrushBankBuilder.buildFamily(it, density) }

    private val bankStorageCodeByFamily: Map<BrushFamily, Int> =
        BrushBank.all.associate { bankFamilyById.getValue(it.id) to it.storageCode }

    var bankBrush: BrushSpec = bankSelection.selectedSpec
        private set

    var penColor: Int = prefs.getInt(KEY_PEN_COLOR, DEFAULT_PEN_COLOR)
        set(value) {
            field = value or ALPHA_MASK
            prefs.edit().putInt(KEY_PEN_COLOR, field).apply()
        }

    var brushColor: Int = prefs.getInt(KEY_BRUSH_COLOR, DEFAULT_BRUSH_COLOR)
        set(value) {
            field = value or ALPHA_MASK
            prefs.edit().putInt(KEY_BRUSH_COLOR, field).apply()
        }

    private var _bankColor: Int = bankSelection.colorFor(bankBrush)
    var bankColor: Int
        get() = _bankColor
        set(value) {
            _bankColor = bankSelection.setColor(bankBrush, value)
        }

    var penSizeDp: Float = prefs.getFloat(KEY_PEN_SIZE, DEFAULT_PEN_SIZE_DP).coerceIn(PEN_MIN_DP, PEN_MAX_DP)
        set(value) {
            field = value.coerceIn(PEN_MIN_DP, PEN_MAX_DP)
            prefs.edit().putFloat(KEY_PEN_SIZE, field).apply()
        }

    var brushSizeDp: Float = prefs.getFloat(KEY_BRUSH_SIZE, DEFAULT_BRUSH_SIZE_DP).coerceIn(BRUSH_MIN_DP, BRUSH_MAX_DP)
        set(value) {
            field = value.coerceIn(BRUSH_MIN_DP, BRUSH_MAX_DP)
            prefs.edit().putFloat(KEY_BRUSH_SIZE, field).apply()
        }

    private var _bankSizeDp: Float = bankSelection.sizeFor(bankBrush)
    var bankSizeDp: Float
        get() = _bankSizeDp
        set(value) {
            _bankSizeDp = bankSelection.setSize(bankBrush, value)
        }

    var eraserSizeDp: Float = prefs.getFloat(KEY_ERASER_SIZE, DEFAULT_ERASER_SIZE_DP).coerceIn(ERASER_MIN_DP, ERASER_MAX_DP)
        set(value) {
            field = value.coerceIn(ERASER_MIN_DP, ERASER_MAX_DP)
            prefs.edit().putFloat(KEY_ERASER_SIZE, field).apply()
        }

    val pen: Brush
        get() = Brush.createWithColorIntArgb(penFamily, penColor, penSizeDp * density, EPSILON)

    val softBrush: Brush
        get() = Brush.createWithColorIntArgb(softBrushFamily, brushColor, brushSizeDp * density, EPSILON)

    val bankBrushActive: Brush
        get() = Brush.createWithColorIntArgb(familyFor(bankBrush), bankColor, bankSizeDp * density, EPSILON)

    val eraserRadiusPx: Float
        get() = eraserSizeDp * density

    fun selectBankBrush(id: String) {
        val next = bankSelection.select(id)
        if (next.id != bankBrush.id) {
            bankBrush = next
            _bankSizeDp = bankSelection.sizeFor(next)
            _bankColor = bankSelection.colorFor(next)
        }
    }

    fun bankColorFor(spec: BrushSpec): Int = bankSelection.colorFor(spec)

    fun bankSizeDpFor(spec: BrushSpec): Float = bankSelection.sizeFor(spec)

    fun familyFor(spec: BrushSpec): BrushFamily =
        bankFamilyById[spec.id] ?: bankFamilyById.getValue(BrushBank.DEFAULT_ID)

    fun previewBrushFor(
        spec: BrushSpec,
        sizeDp: Float = bankSizeDpFor(spec),
        color: Int = bankColorFor(spec),
    ): Brush = Brush.createWithColorIntArgb(familyFor(spec), color, sizeDp * density, EPSILON)

    fun storageCodeFor(family: BrushFamily): Int = when (family) {
        penFamily -> InkEntry.PEN
        softBrushFamily -> InkEntry.SOFT_BRUSH
        else -> bankStorageCodeByFamily[family] ?: error("Unknown diary brush family")
    }

    fun brushFor(tool: DiaryTool): Brush = when (tool) {
        DiaryTool.PEN -> pen
        DiaryTool.BRUSH -> softBrush
        DiaryTool.BANK -> bankBrushActive
        DiaryTool.ERASER, DiaryTool.SELECT -> pen
    }

    fun colorFor(tool: DiaryTool): Int = when (tool) {
        DiaryTool.PEN -> penColor
        DiaryTool.BRUSH -> brushColor
        DiaryTool.BANK -> bankColor
        DiaryTool.ERASER, DiaryTool.SELECT -> 0xff736c60.toInt()
    }

    fun setColorFor(tool: DiaryTool, color: Int) {
        when (tool) {
            DiaryTool.PEN -> penColor = color
            DiaryTool.BRUSH -> brushColor = color
            DiaryTool.BANK -> bankColor = color
            DiaryTool.ERASER, DiaryTool.SELECT -> Unit
        }
    }

    fun sizeDpFor(tool: DiaryTool): Float = when (tool) {
        DiaryTool.PEN -> penSizeDp
        DiaryTool.BRUSH -> brushSizeDp
        DiaryTool.BANK -> bankSizeDp
        DiaryTool.ERASER, DiaryTool.SELECT -> eraserSizeDp
    }

    fun setSizeDpFor(tool: DiaryTool, sizeDp: Float) {
        when (tool) {
            DiaryTool.PEN -> penSizeDp = sizeDp
            DiaryTool.BRUSH -> brushSizeDp = sizeDp
            DiaryTool.BANK -> bankSizeDp = sizeDp
            DiaryTool.ERASER -> eraserSizeDp = sizeDp
            DiaryTool.SELECT -> Unit
        }
    }

    fun sizeRangeFor(tool: DiaryTool): ClosedFloatingPointRange<Float> = when (tool) {
        DiaryTool.PEN -> PEN_MIN_DP..PEN_MAX_DP
        DiaryTool.BRUSH -> BRUSH_MIN_DP..BRUSH_MAX_DP
        DiaryTool.BANK -> bankBrush.minSizeDp..bankBrush.maxSizeDp
        DiaryTool.ERASER, DiaryTool.SELECT -> ERASER_MIN_DP..ERASER_MAX_DP
    }

    fun sizePresetsFor(tool: DiaryTool): FloatArray = when (tool) {
        DiaryTool.PEN -> PEN_PRESETS_DP
        DiaryTool.BRUSH -> BRUSH_PRESETS_DP
        DiaryTool.BANK -> bankBrush.presetsDp
        DiaryTool.ERASER, DiaryTool.SELECT -> ERASER_PRESETS_DP
    }

    private fun sizeResponse(source: SourceNode.Source, end: Float, minimum: Float): BrushBehavior =
        BrushBehavior(
            TargetNode(
                target = TargetNode.Target.SIZE_MULTIPLIER,
                targetModifierRangeStart = minimum,
                targetModifierRangeEnd = 1f,
                input = SourceNode(source, 0f, end, OutOfRange.CLAMP),
            ),
        )

    companion object {
        private const val PREFS_NAME = "diary_toolbox"
        private const val KEY_PEN_COLOR = "pen_color"
        private const val KEY_BRUSH_COLOR = "brush_color"
        private const val KEY_PEN_SIZE = "pen_size_dp"
        private const val KEY_BRUSH_SIZE = "brush_size_dp"
        private const val KEY_ERASER_SIZE = "eraser_size_dp"

        private const val ALPHA_MASK = 0xff000000.toInt()
        private const val EPSILON = 0.2f

        const val DEFAULT_PEN_COLOR = 0xff283948.toInt()
        const val DEFAULT_BRUSH_COLOR = 0xff272320.toInt()

        const val DEFAULT_PEN_SIZE_DP = 2.5f
        const val DEFAULT_BRUSH_SIZE_DP = 12f
        const val DEFAULT_ERASER_SIZE_DP = 14f

        const val PEN_MIN_DP = 1f
        const val PEN_MAX_DP = 10f
        const val BRUSH_MIN_DP = 4f
        const val BRUSH_MAX_DP = 28f
        const val ERASER_MIN_DP = 6f
        const val ERASER_MAX_DP = 32f

        val PEN_PRESETS_DP = floatArrayOf(1.5f, 2.5f, 5f)
        val BRUSH_PRESETS_DP = floatArrayOf(7f, 12f, 20f)
        val ERASER_PRESETS_DP = floatArrayOf(8f, 14f, 24f)

        val INK_SWATCHES: IntArray = BrushBank.INK_SWATCHES
    }
}
