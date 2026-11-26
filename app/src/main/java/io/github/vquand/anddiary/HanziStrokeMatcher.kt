package io.github.vquand.anddiary

import kotlin.math.hypot
import kotlin.math.roundToInt

internal object HanziStrokeMatcher {
    fun matchScore(user: List<Pair<Float, Float>>, expected: List<Pair<Float, Float>>): Int? {
        if (user.size < 2 || expected.size < 2) return null
        val u = resample(user, 20)
        val e = resample(expected, 20)

        val expectedLen = polylineLength(e)
        val userLen = polylineLength(u)
        val isShortDot = expectedLen < 16f

        val startDist = dist(u.first(), e.first())
        val endDist = dist(u.last(), e.last())
        val meanDist = u.indices.sumOf { i -> dist(u[i], e[i]).toDouble() }.toFloat() / u.size

        if (isShortDot) {
            if (startDist > 26f || endDist > 28f || meanDist > 24f || userLen > 45f) return null
        } else {
            val udx = u.last().first - u.first().first
            val udy = u.last().second - u.first().second
            val edx = e.last().first - e.first().first
            val edy = e.last().second - e.first().second
            val uMag = hypot(udx.toDouble(), udy.toDouble()).toFloat()
            val eMag = hypot(edx.toDouble(), edy.toDouble()).toFloat()
            if (uMag > 4f && eMag > 4f) {
                val cos = (udx * edx + udy * edy) / (uMag * eMag)
                if (cos < 0.25f) return null
            }
            if (startDist > 26f || endDist > 30f || meanDist > 22f) return null
        }

        return (100f - meanDist * 2.4f).roundToInt().coerceIn(60, 100)
    }

    fun resample(points: List<Pair<Float, Float>>, count: Int): List<Pair<Float, Float>> {
        val total = polylineLength(points)
        if (total <= 0.001f) return List(count) { points.first() }
        val step = total / (count - 1)
        val out = mutableListOf(points.first())
        var acc = 0f
        var i = 1
        var prev = points.first()
        while (i < points.size && out.size < count) {
            val curr = points[i]
            val d = dist(prev, curr)
            if (acc + d >= step) {
                val t = (step - acc) / d
                val nx = prev.first + (curr.first - prev.first) * t
                val ny = prev.second + (curr.second - prev.second) * t
                val pt = nx to ny
                out.add(pt)
                prev = pt
                acc = 0f
            } else {
                acc += d
                prev = curr
                i++
            }
        }
        while (out.size < count) out.add(points.last())
        return out
    }

    fun polylineLength(points: List<Pair<Float, Float>>): Float {
        var sum = 0f
        for (i in 1 until points.size) sum += dist(points[i - 1], points[i])
        return sum
    }

    fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>): Float =
        hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat()
}
