package com.justdistance.measurepro

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v3.1: MAD-based outlier removal + sigma on remaining points.
 */
class PoseStatsMad(
    private val madK: Float = 3.5f,
    private val madScaleToSigma: Float = 1.4826f,
    private val minPoints: Int = 3
) {
    data class Vec3(val x: Float, val y: Float, val z: Float)

    data class SigmaResult(
        val sigmaXYZ: Float,
        val sigmaXZ: Float,
        val usedCount: Int,
        val totalCount: Int
    )

    fun computeSigma(points: List<Vec3>): SigmaResult {
        val total = points.size
        if (total < minPoints) return SigmaResult(Float.NaN, Float.NaN, 0, total)

        val filtered = filterOutliersMad(points)
        if (filtered.size < minPoints) return SigmaResult(Float.NaN, Float.NaN, filtered.size, total)

        val (varX, varY, varZ) = sampleVarianceXYZ(filtered)
        val sigmaXYZ = sqrt(varX + varY + varZ)
        val sigmaXZ = sqrt(varX + varZ)
        return SigmaResult(sigmaXYZ, sigmaXZ, filtered.size, total)
    }

    private fun filterOutliersMad(points: List<Vec3>): List<Vec3> {
        val n = points.size
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val zs = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            xs[i] = p.x
            ys[i] = p.y
            zs[i] = p.z
        }

        val mx = median(xs)
        val my = median(ys)
        val mz = median(zs)

        val rs = FloatArray(n)
        for (i in 0 until n) {
            val p = points[i]
            val dx = p.x - mx
            val dy = p.y - my
            val dz = p.z - mz
            rs[i] = sqrt(dx * dx + dy * dy + dz * dz)
        }
        val rMed = median(rs)

        val dev = FloatArray(n)
        for (i in 0 until n) dev[i] = abs(rs[i] - rMed)
        val mad = median(dev)

        if (mad == 0f) {
            val out = ArrayList<Vec3>(n)
            for (i in 0 until n) {
                if (rs[i] == rMed) out.add(points[i])
            }
            return out
        }

        val threshold = rMed + (madK * madScaleToSigma * mad)
        val out = ArrayList<Vec3>(n)
        for (i in 0 until n) {
            if (rs[i] <= threshold) out.add(points[i])
        }
        return out
    }

    private data class Vars(val varX: Float, val varY: Float, val varZ: Float)

    private fun sampleVarianceXYZ(points: List<Vec3>): Vars {
        val n = points.size
        var meanX = 0f
        var meanY = 0f
        var meanZ = 0f
        for (p in points) {
            meanX += p.x
            meanY += p.y
            meanZ += p.z
        }
        val invN = 1f / n.toFloat()
        meanX *= invN
        meanY *= invN
        meanZ *= invN

        var accX = 0f
        var accY = 0f
        var accZ = 0f
        for (p in points) {
            val dx = p.x - meanX
            val dy = p.y - meanY
            val dz = p.z - meanZ
            accX += dx * dx
            accY += dy * dy
            accZ += dz * dz
        }
        val denom = (n - 1).toFloat().coerceAtLeast(1f)
        return Vars(accX / denom, accY / denom, accZ / denom)
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        values.sort()
        val n = values.size
        val mid = n / 2
        return if (n % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) * 0.5f
    }
}

