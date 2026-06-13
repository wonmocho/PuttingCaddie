package com.wmcho.puttingcaddie

import kotlin.math.abs

class LiveDistanceStabilizer(
    private val maxSize: Int = 7,
    private val outlierThresholdM: Float = 0.5f
) {
    private val buffer = ArrayDeque<Float>()

    fun update(value: Float): Float {
        if (value <= 0f) return lastStableOr(value)

        buffer.addLast(value)
        if (buffer.size > maxSize) buffer.removeFirst()

        if (buffer.size < 3) return value

        val sorted = buffer.sorted()
        val median = sorted[sorted.size / 2]

        val filtered =
            sorted.filter { abs(it - median) <= outlierThresholdM }

        val result =
            if (filtered.isNotEmpty()) {
                filtered.average().toFloat()
            } else {
                median
            }

        return result
    }

    fun lastStableOr(fallback: Float): Float {
        return if (buffer.isNotEmpty()) buffer.last() else fallback
    }

    fun reset() {
        buffer.clear()
    }
}
