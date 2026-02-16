package com.wmcho.puttingcaddie

import kotlin.math.cos
import kotlin.math.sin

/**
 * Shared math for lie-caddy-v1 texture-level user adjustment.
 * Returns a column-major mat3 that transforms (u,v,1) around center.
 */
object UvAdjust {
    fun buildTexMatrix3(rotationDeg: Float, mirrorH: Boolean, mirrorV: Boolean): FloatArray {
        val theta = Math.toRadians((-rotationDeg).toDouble())
        val c = cos(theta).toFloat()
        val s = sin(theta).toFloat()

        val sx = if (mirrorH) -1f else 1f
        val sy = if (mirrorV) -1f else 1f

        val a = c * sx
        val b = s * sx
        val cc = -s * sy
        val d = c * sy

        val tx = 0.5f - (a * 0.5f + cc * 0.5f)
        val ty = 0.5f - (b * 0.5f + d * 0.5f)

        // Column-major mat3
        return floatArrayOf(
            a, b, 0f,
            cc, d, 0f,
            tx, ty, 1f
        )
    }

    fun applyUv(rotationDeg: Float, mirrorH: Boolean, mirrorV: Boolean, u: Float, v: Float): Pair<Float, Float> {
        val m = buildTexMatrix3(rotationDeg, mirrorH, mirrorV)
        val uu = (m[0] * u) + (m[3] * v) + m[6]
        val vv = (m[1] * u) + (m[4] * v) + m[7]
        return Pair(uu, vv)
    }
}

