package com.wmcho.puttingcaddie

import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * LATERAL_SLOPE_DESIGN.md 정의에 따른 forward/lateral 경사 계산.
 * ballCupPlaneAngleDeg는 품질지표(평면차이)로만 사용. 측면경사는 normal 분해로 계산.
 */
object SlopeComputer {
    private const val PLANE_DRIFT_THRESHOLD_DEG = 8f  // 내부 테스트 기준
    private const val MIN_HORIZONTAL_DISTANCE_M = 0.3f

    private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

    fun compute(
        ballPos: FloatArray,
        cupPos: FloatArray,
        ballNormalRaw: FloatArray?,
        cupNormalRaw: FloatArray?,
        isXyzMode: Boolean,
        trackingGood: Boolean
    ): SlopeDebugInfo {
        if (!isXyzMode) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "xyz_mode_required",
                quality = "rejected",
                isXyzMode = false,
                ballNormal = ballNormalRaw,
                cupNormal = cupNormalRaw,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }
        if (!trackingGood) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "tracking_not_good",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = ballNormalRaw,
                cupNormal = cupNormalRaw,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }
        if (ballNormalRaw == null || cupNormalRaw == null) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "plane_normal_missing",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = ballNormalRaw,
                cupNormal = cupNormalRaw,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }

        val u = normalize(WORLD_UP)
        val ballNormal = normalize(ballNormalRaw)
        val cupNormal = normalize(cupNormalRaw)
        val planeDriftDeg = angleDeg(ballNormal, cupNormal)

        if (planeDriftDeg > PLANE_DRIFT_THRESHOLD_DEG) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = planeDriftDeg,
                blockedReason = "plane_drift_too_large",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = ballNormal,
                cupNormal = cupNormal,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = u
            )
        }

        val refNormal = normalize(add(ballNormal, cupNormal))
        return computeSlopeFromRefNormal(
            refNormal = refNormal,
            ballPos = ballPos,
            cupPos = cupPos,
            planeDriftDeg = planeDriftDeg,
            ballNormal = ballNormal,
            cupNormal = cupNormal
        )
    }

    /**
     * P3 Step 1: [SharedPlaneFit] 등으로 얻은 **shared plane 법선(월드)** 만으로 경사 산출.
     * forward/lateral 기준 축은 **ball→cup 월드 벡터** (§8.2). 카메라·ROI 축 사용 안 함.
     *
     * local correction·mid 샘플·튜닝은 이 함수 밖에서 하지 않는다(§8.10).
     */
    fun computeSharedOnly(
        sharedNormalWorld: FloatArray,
        ballPos: FloatArray,
        cupPos: FloatArray,
        isXyzMode: Boolean,
        trackingGood: Boolean
    ): SlopeDebugInfo {
        if (!isXyzMode) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "xyz_mode_required",
                quality = "rejected",
                isXyzMode = false,
                ballNormal = null,
                cupNormal = null,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }
        if (!trackingGood) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "tracking_not_good",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = null,
                cupNormal = null,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }
        val len = norm(sharedNormalWorld)
        if (len < 1e-6f) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = null,
                vMeters = null,
                planeDriftDeg = null,
                blockedReason = "shared_normal_invalid",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = null,
                cupNormal = null,
                refNormal = null,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = WORLD_UP
            )
        }
        val refNormal = normalize(sharedNormalWorld)
        return computeSlopeFromRefNormal(
            refNormal = refNormal,
            ballPos = ballPos,
            cupPos = cupPos,
            planeDriftDeg = null,
            ballNormal = null,
            cupNormal = null
        )
    }

    /**
     * [refNormal]은 이미 단위 벡터로 가정. 기준 축은 ball→cup 수평 투영.
     */
    private fun computeSlopeFromRefNormal(
        refNormal: FloatArray,
        ballPos: FloatArray,
        cupPos: FloatArray,
        planeDriftDeg: Float?,
        ballNormal: FloatArray?,
        cupNormal: FloatArray?
    ): SlopeDebugInfo {
        val u = normalize(WORLD_UP)
        val d = sub(cupPos, ballPos)
        val dHorizontal = reject(d, u)
        val h = norm(dHorizontal)

        if (h < MIN_HORIZONTAL_DISTANCE_M) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = h,
                vMeters = d[1],
                planeDriftDeg = planeDriftDeg,
                blockedReason = "horizontal_distance_too_small",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = ballNormal,
                cupNormal = cupNormal,
                refNormal = refNormal,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = null,
                left = null,
                worldUp = u
            )
        }

        val forward = normalize(dHorizontal)
        val left = normalize(cross(u, forward))

        val denom = dot(refNormal, u)
        if (abs(denom) < 1e-3f) {
            return SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = h,
                vMeters = d[1],
                planeDriftDeg = planeDriftDeg,
                blockedReason = "invalid_plane_orientation",
                quality = "rejected",
                isXyzMode = true,
                ballNormal = ballNormal,
                cupNormal = cupNormal,
                refNormal = refNormal,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = forward,
                left = left,
                worldUp = u
            )
        }

        val forwardPct = -(dot(refNormal, forward) / denom) * 100f
        val lateralPct = -(dot(refNormal, left) / denom) * 100f

        return SlopeDebugInfo(
            forwardPct = forwardPct,
            lateralPct = lateralPct,
            hMeters = h,
            vMeters = d[1],
            planeDriftDeg = planeDriftDeg,
            blockedReason = null,
            quality = "valid",
            isXyzMode = true,
            ballNormal = ballNormal,
            cupNormal = cupNormal,
            refNormal = refNormal,
            ballPos = ballPos,
            cupPos = cupPos,
            forward = forward,
            left = left,
            worldUp = u
        )
    }

    fun formatForwardSlope(v: Float): String {
        return "${if (v >= 0f) "+" else ""}${"%.1f".format(v)}%"
    }

    fun formatLateralSlope(v: Float): String {
        val absV = abs(v)
        if (absV < 0.1f) return "0.0%"
        val dir = if (v < 0f) "좌고우저" else "우고좌저"
        return "$dir ${"%.1f".format(absV)}%"
    }

    private fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        if (n < 1e-6f) return v.copyOf()
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun norm(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun add(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])

    private fun sub(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun reject(v: FloatArray, on: FloatArray): FloatArray {
        val d = dot(v, on)
        return floatArrayOf(v[0] - d * on[0], v[1] - d * on[1], v[2] - d * on[2])
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray =
        floatArrayOf(
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0]
        )

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val d = dot(a, b).coerceIn(-1f, 1f)
        return (acos(d) * 57.29578f)
    }
}
