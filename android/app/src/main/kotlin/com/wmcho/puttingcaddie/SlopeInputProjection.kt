package com.wmcho.puttingcaddie

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 제품 경사(slope) 계산용 시작/끝 3D 점.
 * UI·앵커 표시는 raw anchor pose를 유지하고, slope 입력만 볼/컵 의미 기준으로 보정한다.
 */
object SlopeInputProjectionConfig {
    /** 컵 raw Y가 평면 투영 대비 이 값(m) 이상 더 낮으면 depression 의심 */
    const val CUP_DEPRESSION_DROP_Y_M = 0.025f
}

/**
 * @param groundPlane BALL_FIX [GroundPlaneModel] — 볼 지지면(support surface) 근사
 * @param cupPlanePoint CUP_FIX 시 컵 트랙 평면 위 한 점(히트 pose)
 * @param cupPlaneNormal CUP_FIX 시 컵 Plane 법선(단위)
 */
object SlopeInputProjection {

    data class Result(
        val slopeBall: FloatArray,
        val slopeCup: FloatArray,
        val rawBall: FloatArray,
        val rawCup: FloatArray,
        /** support_plane | raw_fallback */
        val ballProjectionSource: String,
        /** cup_plane | ball_ground_plane | raw_fallback */
        val cupProjectionSource: String,
        val cupDepressionSuspected: Boolean
    )

    private fun vec3(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

    private fun normalize(n: FloatArray): FloatArray {
        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).takeIf { it > 1e-6f } ?: return n.copyOf()
        return floatArrayOf(n[0] / len, n[1] / len, n[2] / len)
    }

    /** p를 (p0, 단위법선 n) 평면에 정사영 */
    fun projectOntoPlane(p: FloatArray, planePoint: FloatArray, planeNormalUnit: FloatArray): FloatArray {
        val n = normalize(planeNormalUnit)
        val dx = p[0] - planePoint[0]
        val dy = p[1] - planePoint[1]
        val dz = p[2] - planePoint[2]
        val dist = dx * n[0] + dy * n[1] + dz * n[2]
        return floatArrayOf(
            p[0] - n[0] * dist,
            p[1] - n[1] * dist,
            p[2] - n[2] * dist
        )
    }

    fun compute(
        rawBall: FloatArray,
        rawCup: FloatArray,
        groundPlane: GroundPlaneModelLike?,
        cupPlanePoint: FloatArray?,
        cupPlaneNormal: FloatArray?
    ): Result {
        val gp = groundPlane

        var ballSrc = "raw_fallback"
        var slopeBall = rawBall.copyOf()
        if (gp != null) {
            val p0 = gp.point
            val n = gp.normal
            slopeBall = projectOntoPlane(rawBall, p0, n)
            ballSrc = "support_plane"
        }

        var cupSrc = "raw_fallback"
        var slopeCup = rawCup.copyOf()
        when {
            cupPlanePoint != null && cupPlaneNormal != null -> {
                val nCup = cupPlaneNormal
                slopeCup = projectOntoPlane(rawCup, cupPlanePoint, nCup)
                cupSrc = "cup_plane"
            }
            gp != null -> {
                slopeCup = projectOntoPlane(rawCup, gp.point, gp.normal)
                cupSrc = "ball_ground_plane"
            }
        }

        val cupDepressionSuspected =
            (slopeCup[1] - rawCup[1]) > SlopeInputProjectionConfig.CUP_DEPRESSION_DROP_Y_M

        return Result(
            slopeBall = slopeBall,
            slopeCup = slopeCup,
            rawBall = rawBall,
            rawCup = rawCup,
            ballProjectionSource = ballSrc,
            cupProjectionSource = cupSrc,
            cupDepressionSuspected = cupDepressionSuspected
        )
    }

    /** V31StateMachine [GroundPlaneModel]과 동일 필드 — 패키지 private 타입 회피용 */
    data class GroundPlaneModelLike(
        val point: FloatArray,
        val normal: FloatArray
    )
}
