package io.github.vquand.anddiary

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.OutOfRange
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode

internal class DiaryBrushes(density: Float) {
    val pen = Brush.createWithColorIntArgb(StockBrushes.pressurePen(), 0xff283948.toInt(), 2.5f * density, 0.2f)

    val softBrush = Brush.createWithColorIntArgb(
        BrushFamily(
            tip = BrushTip(
                cornerRounding = 1f,
                behaviors = listOf(
                    sizeResponse(SourceNode.Source.NORMALIZED_PRESSURE, 1f, 0.28f),
                    sizeResponse(SourceNode.Source.DISTANCE_REMAINING_IN_MULTIPLES_OF_BRUSH_SIZE, 1.2f, 0.22f),
                ),
            ),
            paint = BrushPaint(),
        ),
        0xff272320.toInt(),
        12f * density,
        0.2f,
    )

    private fun sizeResponse(source: SourceNode.Source, end: Float, minimum: Float): BrushBehavior =
        BrushBehavior(
            TargetNode(
                target = TargetNode.Target.SIZE_MULTIPLIER,
                targetModifierRangeStart = minimum,
                targetModifierRangeEnd = 1f,
                input = SourceNode(source, 0f, end, OutOfRange.CLAMP),
            ),
        )
}
