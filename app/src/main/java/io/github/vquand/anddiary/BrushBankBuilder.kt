package io.github.vquand.anddiary

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.TextureBitmapStore
import androidx.ink.brush.behavior.NoiseNode
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

internal object BrushBankBuilder {
    fun buildFamily(spec: BrushSpec, density: Float): BrushFamily {
        val tip = BrushTip(
            scaleX = spec.tip.scaleX,
            scaleY = spec.tip.scaleY,
            cornerRounding = spec.tip.cornerRounding,
            slantDegrees = spec.tip.slantDegrees,
            pinch = spec.tip.pinch,
            rotationDegrees = spec.tip.rotationDegrees,
            particleGapDistanceScale = spec.tip.particleGapDistanceScale,
            behaviors = spec.behaviors.map(::buildBehavior),
        )

        val colorFunctions = if (spec.opacity < 1f) {
            listOf(BrushPaint.ColorFunction.OpacityMultiplier(spec.opacity))
        } else {
            emptyList()
        }

        val textureId = spec.textureId
        val mapping = spec.textureMapping
        val paintPreferences: List<BrushPaint> = if (textureId != null && mapping != null) {
            when (mapping) {
                TextureMappingSpec.WORLD_TILED -> {
                    // 256px grain stamp at ~0.5dp/texel = 128dp world-anchored tile.
                    val tilePx = 128f * density.coerceAtLeast(1f)
                    listOf(
                        BrushPaint(
                            textureLayers = listOf(
                                BrushPaint.TilingTexture(
                                    clientTextureId = textureId,
                                    sizeX = tilePx,
                                    sizeY = tilePx,
                                    sizeUnit = BrushPaint.TextureLayer.SizeUnit.STROKE_COORDINATES,
                                    origin = BrushPaint.TilingTexture.Origin.STROKE_SPACE_ORIGIN,
                                    wrapX = BrushPaint.TextureLayer.Wrap.REPEAT,
                                    wrapY = BrushPaint.TextureLayer.Wrap.REPEAT,
                                    blendMode = BrushPaint.TextureLayer.BlendMode.MODULATE,
                                ),
                            ),
                            colorFunctions = colorFunctions,
                            selfOverlap = SelfOverlap.ANY,
                        ),
                    )
                }

                TextureMappingSpec.STROKE_TILED -> {
                    // androidx.ink 1.1.0-alpha12 TilingTexture does not expose arclength stroke-aligned UV
                    // mapping; approximate with BRUSH_SIZE tiling anchored at FIRST_STROKE_INPUT.
                    listOf(
                        BrushPaint(
                            textureLayers = listOf(
                                BrushPaint.TilingTexture(
                                    clientTextureId = textureId,
                                    sizeX = 4f,
                                    sizeY = 1f,
                                    sizeUnit = BrushPaint.TextureLayer.SizeUnit.BRUSH_SIZE,
                                    origin = BrushPaint.TilingTexture.Origin.FIRST_STROKE_INPUT,
                                    wrapX = BrushPaint.TextureLayer.Wrap.REPEAT,
                                    wrapY = BrushPaint.TextureLayer.Wrap.REPEAT,
                                    blendMode = BrushPaint.TextureLayer.BlendMode.MODULATE,
                                ),
                            ),
                            colorFunctions = colorFunctions,
                            selfOverlap = SelfOverlap.ANY,
                        ),
                    )
                }

                TextureMappingSpec.STAMPING -> {
                    // StampingTexture requires CanvasMeshRenderer (API 34+ hardware canvas); provide a
                    // flat-alpha fallback BrushPaint for API <34 or software canvases.
                    val primary = BrushPaint(
                        textureLayers = listOf(
                            BrushPaint.StampingTexture(
                                clientTextureId = textureId,
                                blendMode = BrushPaint.TextureLayer.BlendMode.MODULATE,
                            ),
                        ),
                        colorFunctions = colorFunctions,
                        selfOverlap = SelfOverlap.ANY,
                    )
                    val fallback = BrushPaint(
                        colorFunctions = colorFunctions,
                        selfOverlap = SelfOverlap.ANY,
                    )
                    listOf(primary, fallback)
                }
            }
        } else {
            listOf(
                BrushPaint(
                    colorFunctions = colorFunctions,
                    selfOverlap = SelfOverlap.ANY,
                ),
            )
        }

        return BrushFamily(
            coats = listOf(BrushCoat(tip = tip, paintPreferences = paintPreferences)),
        )
    }

    private fun buildBehavior(spec: BrushBehaviorSpec): BrushBehavior = when (spec) {
        is BrushBehaviorSpec.SizeResponse -> BrushBehavior(
            TargetNode(
                target = TargetNode.Target.SIZE_MULTIPLIER,
                targetModifierRangeStart = spec.minMultiplier,
                targetModifierRangeEnd = spec.maxMultiplier,
                input = SourceNode(mapSource(spec.source), spec.sourceStart, spec.sourceEnd, OutOfRange.CLAMP),
            ),
        )

        is BrushBehaviorSpec.OpacityResponse -> BrushBehavior(
            TargetNode(
                target = TargetNode.Target.OPACITY_MULTIPLIER,
                targetModifierRangeStart = spec.minMultiplier,
                targetModifierRangeEnd = spec.maxMultiplier,
                input = SourceNode(mapSource(spec.source), spec.sourceStart, spec.sourceEnd, OutOfRange.CLAMP),
            ),
        )

        // NoiseNode outputs [0, 1] over the progress domain; map symmetrically to [-amplitude, +amplitude].
        is BrushBehaviorSpec.LateralJitter -> BrushBehavior(
            TargetNode(
                target = TargetNode.Target.POSITION_OFFSET_LATERAL_IN_MULTIPLES_OF_BRUSH_SIZE,
                targetModifierRangeStart = -spec.amplitude,
                targetModifierRangeEnd = spec.amplitude,
                input = NoiseNode(
                    seed = spec.seed,
                    varyOver = ProgressDomain.DISTANCE_IN_CENTIMETERS,
                    basePeriod = spec.basePeriodCm,
                ),
            ),
        )
    }

    private fun mapSource(kind: BrushSourceKind): SourceNode.Source = when (kind) {
        BrushSourceKind.NORMALIZED_PRESSURE -> SourceNode.Source.NORMALIZED_PRESSURE
        BrushSourceKind.DISTANCE_TRAVELED -> SourceNode.Source.DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE
        BrushSourceKind.DISTANCE_REMAINING -> SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE
        BrushSourceKind.SPEED -> SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND
    }
}

internal class AssetAndProceduralTextureStore(context: Context) : TextureBitmapStore {
    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, Bitmap>()

    override operator fun get(clientTextureId: String): Bitmap? {
        cache[clientTextureId]?.let { return it }
        val loaded = loadFromAsset(clientTextureId) ?: generateProcedural(clientTextureId) ?: return null
        return cache.putIfAbsent(clientTextureId, loaded) ?: loaded
    }

    private fun loadFromAsset(textureId: String): Bitmap? {
        val assetPath = "brushes/$textureId/stamp.png"
        val decoded = try {
            appContext.assets.open(assetPath).use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (_: IOException) {
            return null
        } ?: return null

        val w = decoded.width
        val h = decoded.height
        if (w <= 0 || h <= 0) return null
        val pixels = IntArray(w * h)
        decoded.getPixels(pixels, 0, w, 0, 0, w, h)
        decoded.recycle()
        val maskArgb = BrushStampGenerator.grayscalePixelsToMaskArgb(pixels)
        return Bitmap.createBitmap(maskArgb, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun generateProcedural(textureId: String): Bitmap? {
        val spec = BrushBank.byId(textureId) ?: return null
        val kind = spec.proceduralStamp ?: return null
        val alphas = BrushStampGenerator.generateAlphaMask(
            kind = kind,
            size = PROCEDURAL_STAMP_SIZE,
            seed = spec.storageCode.toLong(),
        )
        val argb = IntArray(alphas.size) { index ->
            (alphas[index] shl 24) or 0x00ffffff
        }
        return Bitmap.createBitmap(argb, PROCEDURAL_STAMP_SIZE, PROCEDURAL_STAMP_SIZE, Bitmap.Config.ARGB_8888)
    }

    private companion object {
        const val PROCEDURAL_STAMP_SIZE = 64
    }
}

internal object PreviewStrokeRenderer {
    private val identity = Matrix()

    fun buildSampleInputs(width: Float, height: Float, density: Float): StrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val sample = StrokeInput()
        val padX = (12f * density).coerceAtMost(width * 0.2f)
        val startX = padX
        val endX = (width - padX).coerceAtLeast(startX + 1f)
        val cy = height / 2f
        val amp = (height * 0.24f).coerceAtMost(8f * density)
        val unitCm = 0.025f / density.coerceAtLeast(1f)
        val steps = 36

        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = startX + (endX - startX) * t
            val y = cy - sin((t * 2f * PI).toFloat()) * amp
            // Smooth entry -> peak -> exit pressure ramp to showcase taper and pressure response.
            val pressure = (0.14f + 0.82f * sin((t * PI).toFloat())).coerceIn(0.05f, 1f)
            sample.update(
                x = x,
                y = y,
                elapsedTimeMillis = i * 12L,
                toolType = InputToolType.STYLUS,
                strokeUnitLengthCm = unitCm,
                pressure = pressure,
            )
            batch.add(sample)
        }
        return batch.toImmutable()
    }

    fun drawPreview(
        canvas: Canvas,
        renderer: CanvasStrokeRenderer,
        brush: Brush,
        inputs: StrokeInputBatch,
    ) {
        val stroke = Stroke(brush, inputs)
        renderer.draw(canvas, stroke, identity)
    }
}
