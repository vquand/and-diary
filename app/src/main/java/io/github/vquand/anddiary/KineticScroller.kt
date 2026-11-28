package io.github.vquand.anddiary

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln

/**
 * Continuous floating-point scroll fling physics matching AOSP's SplineOverScroller
 * (as transcribed in gpui-pre's `ScrollPhysics::FrictionSpline`), evaluated in closed form
 * against Choreographer frame timestamps so high-refresh scrolling avoids integer-pixel stepping.
 */
object KineticScroller {
    private const val NB_SAMPLES = 100
    private const val INFLEXION = 0.35f
    private const val START_TENSION = 0.5f
    private const val END_TENSION = 1.0f
    private const val P1 = START_TENSION * INFLEXION
    private const val P2 = 1.0f - END_TENSION * (1.0f - INFLEXION)
    const val DEFAULT_FRICTION = 0.015f
    const val RELEASE_STOPPED_GAP_MS = 40L

    private val DECELERATION_RATE = ln(0.78) / ln(0.9)

    private val SPLINE_POSITION = FloatArray(NB_SAMPLES + 1).apply {
        var xMin = 0f
        for (i in 0 until NB_SAMPLES) {
            val alpha = i.toFloat() / NB_SAMPLES.toFloat()
            var xMax = 1f
            var x: Float
            var coefficient: Float
            while (true) {
                x = xMin + (xMax - xMin) / 2f
                coefficient = 3f * x * (1f - x)
                val time = coefficient * ((1f - x) * P1 + x * P2) + x * x * x
                if (abs(time - alpha) < 1e-5f) break
                if (time > alpha) xMax = x else xMin = x
            }
            this[i] = coefficient * ((1f - x) * START_TENSION + x) + x * x * x
        }
        this[NB_SAMPLES] = 1f
    }

    data class FlingSpec(
        val startOffset: Float,
        val direction: Float,
        val totalDistance: Float,
        val durationNanos: Long,
        val startTimeNanos: Long,
        val minOffset: Float,
        val maxOffset: Float,
    ) {
        fun offsetAt(frameTimeNanos: Long): Pair<Float, Boolean> {
            if (durationNanos <= 0L || totalDistance <= 0f) {
                return startOffset.coerceIn(minOffset, maxOffset) to true
            }
            val elapsedNanos = (frameTimeNanos - startTimeNanos).coerceAtLeast(0L)
            val finishedTime = elapsedNanos >= durationNanos
            val progress = if (finishedTime) {
                1f
            } else {
                (elapsedNanos.toDouble() / durationNanos.toDouble()).toFloat()
            }
            val rawOffset = startOffset + direction * totalDistance * distanceCoefficient(progress)
            val clamped = rawOffset.coerceIn(minOffset, maxOffset)
            val hitEdge = (direction < 0f && clamped <= minOffset) ||
                (direction > 0f && clamped >= maxOffset)
            return clamped to (finishedTime || hitEdge)
        }
    }

    fun createFling(
        startOffset: Float,
        velocityPxPerSec: Float,
        minOffset: Float,
        maxOffset: Float,
        pixelsPerInch: Float,
        startTimeNanos: Long,
        friction: Float = DEFAULT_FRICTION,
    ): FlingSpec? {
        val speed = abs(velocityPxPerSec)
        if (speed <= 0f || maxOffset <= minOffset || pixelsPerInch <= 0f || friction <= 0f) {
            return null
        }
        val direction = if (velocityPxPerSec > 0f) 1f else -1f
        val clampedStart = startOffset.coerceIn(minOffset, maxOffset)
        if ((clampedStart <= minOffset && direction < 0f) ||
            (clampedStart >= maxOffset && direction > 0f)
        ) {
            return null
        }
        val physicalCoeff = 9.80665 * 39.37 * pixelsPerInch.toDouble() * 0.84
        val decel = ln(INFLEXION.toDouble() * speed.toDouble() / (friction.toDouble() * physicalCoeff))
        val decelMinusOne = DECELERATION_RATE - 1.0
        val durationSec = exp(decel / decelMinusOne)
        val totalDistance = (friction.toDouble() * physicalCoeff * exp(DECELERATION_RATE / decelMinusOne * decel)).toFloat()
        val durationNanos = (durationSec * 1_000_000_000.0).toLong()
        if (durationNanos <= 0L || totalDistance <= 0.5f) return null
        return FlingSpec(
            startOffset = clampedStart,
            direction = direction,
            totalDistance = totalDistance,
            durationNanos = durationNanos,
            startTimeNanos = startTimeNanos,
            minOffset = minOffset,
            maxOffset = maxOffset,
        )
    }

    internal fun distanceCoefficient(progress: Float): Float {
        if (progress <= 0f) return 0f
        if (progress >= 1f) return 1f
        val index = ((NB_SAMPLES * progress).toInt()).coerceAtMost(NB_SAMPLES - 1)
        val tLower = index.toFloat() / NB_SAMPLES.toFloat()
        val tUpper = (index + 1).toFloat() / NB_SAMPLES.toFloat()
        val dLower = SPLINE_POSITION[index]
        val dUpper = SPLINE_POSITION[index + 1]
        val slope = (dUpper - dLower) / (tUpper - tLower)
        return dLower + (progress - tLower) * slope
    }
}
