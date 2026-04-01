package com.wmcho.puttingcaddie

import kotlin.math.abs
import kotlin.math.max

/**
 * 최종 선택된 shared plane 법선으로부터 계산된 forwardPct/lateralPct에만 보수적 후처리.
 * 후보 선택·피트 로직은 변경하지 않는다.
 *
 * - normalY·prev각·절대 lateral·(작은 forward일 때만) lateral/forward 비율 가드
 * - forward–lateral 비율 규칙은 [SMALL_FORWARD_THRESHOLD] 미만일 때만 적용 (측면 경사 보존)
 */
object SharedSlopeStabilization {

    private const val SMALL_FORWARD_THRESHOLD = 5f
    private const val RATIO_FLOOR = 5f
    /** ratio 초과 시 BLOCKED (작은 forward 구간에서만 평가) */
    private const val RATIO_BLOCK = 12f
    /** ratio 초과 시 DEGRADED (차단은 아님) */
    private const val RATIO_DEGRADED = 8f

    private const val LATERAL_HARD_BLOCK = 88f
    private const val LATERAL_SOFT_CAP = 45f
    private const val LATERAL_DEGRADED_ABS = 25f

    private const val PREV_ANGLE_DEG_DAMP_START = 22f
    private const val PREV_ANGLE_DAMP_FACTOR = 0.88f

    private const val NY_DAMP_LOW = 0.12f
    private const val NY_DAMP_HIGH = 0.38f

    data class Result(
        val forwardPctRaw: Float,
        val lateralPctRaw: Float,
        /** null 이면 출력·표시도 막음(BLOCKED) */
        val forwardPct: Float?,
        val lateralPct: Float?,
        val lateralDampingFactor: Float,
        val ratioSmallForwardApplied: Boolean,
        val lateralForwardRatio: Float?,
        /** GOOD | DEGRADED | BLOCKED — 잔차 등 외부 요인은 호출부에서 합성 */
        val quality: String,
        val blockedReason: String?,
        val reasons: List<String>
    )

    /**
     * [forwardRaw]/[lateralRaw]는 computeSharedOnly 유효 출력만 전달한다.
     */
    fun stabilize(
        forwardRaw: Float,
        lateralRaw: Float,
        normalYAbs: Float,
        prevAngleDeg: Float?
    ): Result {
        val reasons = mutableListOf<String>()

        val nyDamp = lateralDampingFromNormalY(normalYAbs)
        var prevDamp = 1f
        if (prevAngleDeg != null && prevAngleDeg > PREV_ANGLE_DEG_DAMP_START) {
            prevDamp = PREV_ANGLE_DAMP_FACTOR
            reasons.add("prev_angle_damp")
        }

        val combinedDamp = nyDamp * prevDamp
        var lateral = lateralRaw * combinedDamp
        val forward = forwardRaw

        if (nyDamp < 0.999f) reasons.add("normalY_damp")

        var ratioApplied = false
        var ratio: Float? = null
        if (abs(forward) < SMALL_FORWARD_THRESHOLD) {
            ratioApplied = true
            val denom = max(abs(forward), RATIO_FLOOR)
            ratio = abs(lateral) / denom
            when {
                ratio > RATIO_BLOCK -> {
                    reasons.add("lateral_forward_ratio_block")
                    return Result(
                        forwardPctRaw = forwardRaw,
                        lateralPctRaw = lateralRaw,
                        forwardPct = null,
                        lateralPct = null,
                        lateralDampingFactor = combinedDamp,
                        ratioSmallForwardApplied = true,
                        lateralForwardRatio = ratio,
                        quality = "BLOCKED",
                        blockedReason = "shared_lateral_forward_ratio",
                        reasons = reasons
                    )
                }
                ratio > RATIO_DEGRADED -> reasons.add("lateral_forward_ratio_degraded")
            }
        }

        if (abs(lateral) > LATERAL_HARD_BLOCK) {
            reasons.add("lateral_abs_hard")
            return Result(
                forwardPctRaw = forwardRaw,
                lateralPctRaw = lateralRaw,
                forwardPct = null,
                lateralPct = null,
                lateralDampingFactor = combinedDamp,
                ratioSmallForwardApplied = ratioApplied,
                lateralForwardRatio = ratio,
                quality = "BLOCKED",
                blockedReason = "shared_lateral_unstable",
                reasons = reasons
            )
        }

        var lateralOut = lateral
        var degraded = reasons.any { it.endsWith("_degraded") } || ratio?.let { it > RATIO_DEGRADED } == true

        if (abs(lateralOut) > LATERAL_SOFT_CAP) {
            lateralOut = LATERAL_SOFT_CAP * if (lateralOut >= 0f) 1f else -1f
            reasons.add("lateral_soft_clamped")
            degraded = true
        } else if (abs(lateralOut) > LATERAL_DEGRADED_ABS) {
            reasons.add("lateral_abs_elevated")
            degraded = true
        }

        val q = if (degraded) "DEGRADED" else "GOOD"
        return Result(
            forwardPctRaw = forwardRaw,
            lateralPctRaw = lateralRaw,
            forwardPct = forward,
            lateralPct = lateralOut,
            lateralDampingFactor = combinedDamp,
            ratioSmallForwardApplied = ratioApplied,
            lateralForwardRatio = ratio,
            quality = q,
            blockedReason = null,
            reasons = reasons
        )
    }

    /** 낮은 normalY에서 lateral 과민 완화: [NY_DAMP_LOW]→약하게, [NY_DAMP_HIGH]→1.0 근접 */
    private fun lateralDampingFromNormalY(normalYAbs: Float): Float {
        val t = ((normalYAbs - NY_DAMP_LOW) / (NY_DAMP_HIGH - NY_DAMP_LOW)).coerceIn(0f, 1f)
        return 0.52f + 0.48f * t
    }
}
