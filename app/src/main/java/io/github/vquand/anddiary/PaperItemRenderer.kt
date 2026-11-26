package io.github.vquand.anddiary

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

internal class PaperItemRenderer(
    private val density: Float,
    private val paperPalette: DiaryPalette,
) {
    private val tianzigeDash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.rule
        style = Paint.Style.STROKE
        strokeWidth = 0.9f * density
        pathEffect = DashPathEffect(floatArrayOf(3f * density, 3f * density), 0f)
    }
    private val hanziSilhouettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.muted
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 4.2f * density
    }
    private val hanziPinyinPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.accent
        textSize = 14f * density
        isFakeBoldText = true
    }
    private val cardFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val cardStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.rule
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * density
    }
    private val selectStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.accent
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.paper
        textSize = 12f * density
        isFakeBoldText = true
        textAlign = Paint.Align.CENTER
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.text
        textSize = 13f * density
        isFakeBoldText = true
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = paperPalette.muted
        textSize = 11f * density
    }
    private val rect = RectF()
    private val boxRect = RectF()
    private val pillRect = RectF()
    private val sealRect = RectF()
    private val clipPath = Path()
    private val trianglePath = Path()

    fun drawItem(
        canvas: Canvas,
        item: PaperItem,
        itemsStore: PaperItemsStore,
        playingAudioId: String?,
        requestBitmap: (PaperItem, PaperItemsStore) -> Unit,
    ) {
        rect.set(item.x, item.y, item.x + item.width, item.y + item.height)
        val corner = 8f * density
        when (item.kind) {
            PaperItemKind.STICKER -> {
                val bmp = itemsStore.cachedBitmap(item)
                if (bmp != null) canvas.drawBitmap(bmp, null, rect, null)
            }

            PaperItemKind.PHOTO -> {
                val bmp = itemsStore.cachedBitmap(item)
                if (bmp != null) {
                    canvas.drawBitmap(bmp, null, rect, null)
                } else {
                    if (!itemsStore.hasBitmapEntry(item)) requestBitmap(item, itemsStore)
                    cardFill.color = paperPalette.wash
                    canvas.drawRoundRect(rect, corner, corner, cardFill)
                }
                canvas.drawRect(rect, cardStroke)
            }

            PaperItemKind.VIDEO -> {
                cardFill.color = 0xff22201d.toInt()
                canvas.drawRoundRect(rect, corner, corner, cardFill)
                val bmp = itemsStore.cachedBitmap(item)
                if (bmp != null) {
                    canvas.save()
                    clipPath.reset()
                    clipPath.addRoundRect(rect, corner, corner, Path.Direction.CW)
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(bmp, null, rect, null)
                    canvas.restore()
                } else if (!itemsStore.hasBitmapEntry(item)) {
                    requestBitmap(item, itemsStore)
                }
                canvas.drawRoundRect(rect, corner, corner, cardStroke)

                val cx = rect.centerX()
                val cy = rect.centerY()
                val playR = 20f * density
                badgeFill.color = 0xb8272320.toInt()
                canvas.drawCircle(cx, cy, playR, badgeFill)
                drawPlayTriangle(canvas, cx + 1.5f * density, cy, 8f * density, paperPalette.paper)

                val dur = PaperItemsStore.formatDuration(item.durationMs)
                if (dur.isNotEmpty()) {
                    val tw = badgeText.measureText(dur)
                    val padH = 6f * density
                    val pillH = 18f * density
                    pillRect.set(
                        rect.right - tw - padH * 2f - 6f * density,
                        rect.bottom - pillH - 6f * density,
                        rect.right - 6f * density,
                        rect.bottom - 6f * density,
                    )
                    badgeFill.color = 0xc8272320.toInt()
                    canvas.drawRoundRect(pillRect, 4f * density, 4f * density, badgeFill)
                    val baseline = pillRect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2f
                    canvas.drawText(dur, pillRect.centerX(), baseline, badgeText)
                }
            }

            PaperItemKind.AUDIO -> {
                cardFill.color = paperPalette.wash
                canvas.drawRoundRect(rect, corner, corner, cardFill)
                canvas.drawRoundRect(rect, corner, corner, cardStroke)

                val playCx = rect.left + 26f * density
                val playCy = rect.centerY()
                val playR = 16f * density
                badgeFill.color = paperPalette.accent
                canvas.drawCircle(playCx, playCy, playR, badgeFill)
                if (playingAudioId == item.id) {
                    val barW = 3.2f * density
                    val barH = 11f * density
                    cardFill.color = paperPalette.paper
                    canvas.drawRect(playCx - 4.5f * density, playCy - barH / 2f, playCx - 4.5f * density + barW, playCy + barH / 2f, cardFill)
                    canvas.drawRect(playCx + 1.3f * density, playCy - barH / 2f, playCx + 1.3f * density + barW, playCy + barH / 2f, cardFill)
                } else {
                    drawPlayTriangle(canvas, playCx + 1.2f * density, playCy, 6.5f * density, paperPalette.paper)
                }

                val textX = rect.left + 50f * density
                val maxTextW = (rect.right - textX - 10f * density).coerceAtLeast(24f * density)
                val title = ellipsize(item.label.ifBlank { "Audio" }, labelPaint, maxTextW)
                canvas.drawText(title, textX, rect.top + 25f * density, labelPaint)
                val dur = PaperItemsStore.formatDuration(item.durationMs).ifEmpty { "Audio" }
                canvas.drawText(dur, textX, rect.top + 43f * density, subLabelPaint)
            }

            PaperItemKind.HANZI -> drawHanziCard(canvas, item, corner, itemsStore)
        }
    }

    private fun drawHanziCard(canvas: Canvas, item: PaperItem, corner: Float, itemsStore: PaperItemsStore) {
        val completed = item.score > 0
        cardFill.color = if (completed) paperPalette.paper else paperPalette.paperSoft
        canvas.drawRoundRect(rect, corner, corner, cardFill)
        cardStroke.color = if (completed) paperPalette.accent else paperPalette.rule
        canvas.drawRoundRect(rect, corner, corner, cardStroke)
        cardStroke.color = paperPalette.rule

        val pad = 10f * density
        val boxSize = (rect.height() - pad * 2f).coerceAtLeast(48f * density)
        val boxLeft = rect.left + pad
        val boxTop = rect.top + pad
        boxRect.set(boxLeft, boxTop, boxLeft + boxSize, boxTop + boxSize)

        cardFill.color = if (completed) paperPalette.paperSoft else paperPalette.paper
        canvas.drawRoundRect(boxRect, 6f * density, 6f * density, cardFill)
        tianzigeDash.color = paperPalette.rule
        canvas.drawLine(boxRect.centerX(), boxRect.top, boxRect.centerX(), boxRect.bottom, tianzigeDash)
        canvas.drawLine(boxRect.left, boxRect.centerY(), boxRect.right, boxRect.centerY(), tianzigeDash)
        canvas.drawRoundRect(boxRect, 6f * density, 6f * density, cardStroke)

        val word = itemsStore.hanziCatalog.find(item.ref)
        if (word != null) {
            hanziSilhouettePaint.color = if (completed) paperPalette.text else paperPalette.muted
            hanziSilhouettePaint.strokeWidth = (boxSize * if (completed) 0.068f else 0.052f)
                .coerceIn(2.5f * density, 6.8f * density)
            for (stroke in word.strokes) {
                if (stroke.size < 2) continue
                trianglePath.reset()
                trianglePath.moveTo(
                    boxLeft + stroke[0].first / 100f * boxSize,
                    boxTop + stroke[0].second / 100f * boxSize,
                )
                for (i in 1 until stroke.size) {
                    trianglePath.lineTo(
                        boxLeft + stroke[i].first / 100f * boxSize,
                        boxTop + stroke[i].second / 100f * boxSize,
                    )
                }
                canvas.drawPath(trianglePath, hanziSilhouettePaint)
            }
        }

        val playR = 9.5f * density
        val playCx = boxRect.right - playR - 3f * density
        val playCy = boxRect.bottom - playR - 3f * density
        badgeFill.color = paperPalette.accent
        canvas.drawCircle(playCx, playCy, playR, badgeFill)
        if (completed) {
            val baseline = playCy - (badgeText.descent() + badgeText.ascent()) / 2f
            canvas.drawText("✓", playCx, baseline, badgeText)
        } else {
            drawPlayTriangle(canvas, playCx + 0.8f * density, playCy, 4f * density, paperPalette.paper)
        }

        val sealReserve = if (completed) 32f * density else 0f
        if (completed) {
            val sealSize = 24f * density
            sealRect.set(
                rect.right - sealSize - 8f * density,
                rect.top + 8f * density,
                rect.right - 8f * density,
                rect.top + 8f * density + sealSize,
            )
            cardStroke.color = paperPalette.accent
            canvas.drawRoundRect(sealRect, 3.5f * density, 3.5f * density, cardStroke)
            cardStroke.color = paperPalette.rule
            val prevColor = badgeText.color
            badgeText.color = paperPalette.accent
            val glyph = if (item.score >= 85) "優" else "好"
            val sealBase = sealRect.centerY() - (badgeText.descent() + badgeText.ascent()) / 2f
            canvas.drawText(glyph, sealRect.centerX(), sealBase, badgeText)
            badgeText.color = prevColor
        }

        val textX = boxRect.right + 10f * density
        val maxTextW = (rect.right - textX - 10f * density).coerceAtLeast(40f * density)
        hanziPinyinPaint.color = paperPalette.accent
        if (word != null) {
            canvas.drawText(
                ellipsize(word.pinyin, hanziPinyinPaint, (maxTextW - sealReserve).coerceAtLeast(32f * density)),
                textX,
                rect.top + 26f * density,
                hanziPinyinPaint,
            )
            canvas.drawText(ellipsize("Hán-Việt: ${word.hanViet}", labelPaint, maxTextW), textX, rect.top + 48f * density, labelPaint)
            canvas.drawText(ellipsize(word.meaning, subLabelPaint, maxTextW), textX, rect.top + 68f * density, subLabelPaint)
            if (completed) {
                val prevColor = subLabelPaint.color
                subLabelPaint.color = paperPalette.accent
                canvas.drawText(
                    ellipsize("✓ Practiced · ${item.score}%", subLabelPaint, maxTextW),
                    textX,
                    rect.bottom - 14f * density,
                    subLabelPaint,
                )
                subLabelPaint.color = prevColor
            } else {
                canvas.drawText(ellipsize("Tap to animate & write", subLabelPaint, maxTextW), textX, rect.bottom - 14f * density, subLabelPaint)
            }
        } else {
            canvas.drawText(item.ref, textX, rect.centerY(), labelPaint)
        }
    }

    fun drawSelectionHandles(canvas: Canvas, item: PaperItem) {
        val pad = 4f * density
        rect.set(item.x - pad, item.y - pad, item.x + item.width + pad, item.y + item.height + pad)
        canvas.drawRoundRect(rect, 6f * density, 6f * density, selectStroke)

        val handleR = 12f * density
        badgeFill.color = paperPalette.danger
        canvas.drawCircle(rect.right, rect.top, handleR, badgeFill)
        val baseline = rect.top - (badgeText.descent() + badgeText.ascent()) / 2f
        canvas.drawText("×", rect.right, baseline, badgeText)

        if (item.kind != PaperItemKind.AUDIO) {
            badgeFill.color = paperPalette.accent
            canvas.drawCircle(rect.right, rect.bottom, handleR, badgeFill)
            cardStroke.color = paperPalette.paper
            canvas.drawCircle(rect.right, rect.bottom, handleR - 2.5f * density, cardStroke)
            cardStroke.color = paperPalette.rule
        }
    }

    private fun drawPlayTriangle(canvas: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        trianglePath.reset()
        trianglePath.moveTo(cx - r * 0.65f, cy - r)
        trianglePath.lineTo(cx + r, cy)
        trianglePath.lineTo(cx - r * 0.65f, cy + r)
        trianglePath.close()
        cardFill.color = color
        canvas.drawPath(trianglePath, cardFill)
    }

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(text) <= maxWidth) return text
        var end = text.length
        while (end > 1 && paint.measureText(text.substring(0, end) + "…") > maxWidth) {
            end--
        }
        return text.substring(0, end) + "…"
    }
}
