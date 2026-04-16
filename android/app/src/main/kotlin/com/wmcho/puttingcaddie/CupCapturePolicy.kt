package com.wmcho.puttingcaddie

import kotlin.math.max

/**
 * 컵 멀티레이 / CUP_CAPTURE_PENDING / 원거리 far 모드 관련 상수 SSOT.
 *
 * 실내 로그 기준: 3m대는 안정, 6m대부터 projectedCupPx ≈ 35 전후로 품질 경고,
 * 저픽셀에서 샘플 spread 증가 → 캡처 대기·홀드 튜닝은 여기서 조정.
 */
object CupCapturePolicy {
    const val FAR_MODE_CUP_DISTANCE_M = 6.0f
    /** Far 모드 진입: 컵 화면 크기 이하일 때(실내 6m대 ≈ 35px 근처) */
    const val FAR_MODE_MIN_PROJECTED_CUP_PX = 35f
    const val FAR_MODE_PLAN_ULTRA_LINE_5 = "ULTRA_LINE_5"
    const val FAR_MODE_MAX_LIVE_CUP_DIFF_M = 0.40f
    const val FAR_MODE_EXTRA_HOLD_NS = 300_000_000L

    const val CUP_AIM_READY_MIN_PROJECTED_PX = 18f

    /** 기본 캡처 대기 (비줌) */
    const val CUP_CAPTURE_PENDING_HOLD_NS = 400_000_000L
    const val CUP_CAPTURE_PENDING_HOLD_ZOOMED_NS = 550_000_000L
    const val CUP_CAPTURE_PENDING_HOLD_ZOOMED_SMALL_PX_NS = 700_000_000L
    const val CUP_CAPTURE_PENDING_HOLD_FAR_NS = 650_000_000L
    /** 이 px 미만이면 줌 경로에서 더 긴 홀드 */
    const val CUP_CAPTURE_PENDING_SMALL_PROJECTED_PX = 22f

    /**
     * 실내 6m대 로그: 35~42px에서 품질 경고 구간 — 비줌일 때 최소 홀드를 [HOLD_NS_QUALITY_WARNING_MIN]까지 끌어올림.
     */
    const val QUALITY_WARNING_MAX_PROJECTED_PX = 42f
    const val HOLD_NS_QUALITY_WARNING_MIN = 600_000_000L

    const val CUP_CAPTURE_PENDING_EARLY_VALID_HITS = 3
    /**
     * CUP_CAPTURE_PENDING: 짧은 구간에 유효 히트가 적을 때의 처리.
     * distance/slope 분리: [CUP_LOW_VALID_EARLY_FAIL_NS] 경과 시 **validHits==0** 만 즉시 FAIL.
     * 1히트 등은 경고 후 hold/거리-only 경로로 계속(slope 품질로 distance FAIL 금지).
     */
    const val CUP_LOW_VALID_EARLY_FAIL_NS = 500_000_000L
    const val CUP_CAPTURE_PENDING_MAX_NS = 3_000_000_000L

    /**
     * ball–cup 추정 거리가 이 값 이상이면 STABILIZING_END 잠금·캡처 대기에서 **distance-only** 최소 표본 완화.
     * (원거리는 샘플링 붕괴에 취약 — slope 가능 여부와 무관하게 거리 fix 우선.)
     */
    const val DISTANCE_ONLY_RELAX_MIN_BALL_TO_CUP_M = 6.0f

    const val CUP_PROJECTED_PX_FORCE_FAR5 = 22f
    const val CUP_PROJECTED_PX_CONDITIONAL_FAR5 = 24f

    const val FAR_PRECISION_MODE_DISTANCE_M = 8.0f
    const val FAR_PRECISION_MODE_ENTER_PROJECTED_PX = 24f
    const val FAR_PRECISION_MODE_EXIT_PROJECTED_PX = 26f

    const val CUP_SIGMA_NEAR_RATIO = 1.10f
    const val CUP_SIGMA_NEAR_EXTRA_HOLD_NS = 250_000_000L
    const val CUP_SIGMA_SOFTPASS_RATIO = 1.12f
    const val CUP_SIGMA_SOFTPASS_MIN_VALID_HITS = 9
    const val CUP_SIGMA_SOFTPASS_MIN_PROJECTED_PX = 24f

    const val CUP_SOFT_LOCK_ENABLED = true
    const val CUP_SOFT_LOCK_SIGMA_MARGIN_M = 0.015f
    const val CUP_SOFT_LOCK_MIN_VALID_HITS = 9
    const val CUP_SOFT_LOCK_MIN_PROJECTED_PX = 18f

    const val CUP_LIVE_WORLD_MAX_STALE_NS = 200_000_000L

    /**
     * STABILIZING_END 잠금 전 품질: **거리 FAIL이 아니라** “표본 부족 시 한 틱 홀드”의 기준(과거에는 slope형 품질이 겹침).
     * 원거리는 [cupLockMinValidSamplesForDistance]로 1까지 완화 가능.
     */
    const val CUP_LOCK_MIN_VALID_SAMPLES = 3
    /** center fallback 시 slope/품질 쪽 참고치 — distance-only 진행 시에는 잠금 차단에 쓰지 않음(로그·tier용). */
    const val CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES = 5

    private const val ZOOM_HIGH_THRESHOLD = 2.9f

    /**
     * CUP 버튼 후 [AIM_END] 캡처 대기 최소 시간.
     * 저픽셀(22~42px)이고 고배율이 아닐 때는 [HOLD_NS_QUALITY_WARNING_MIN]으로 상향해 중심 안정화 시간을 확보.
     */
    fun holdTargetNsForCapturePending(
        farPrecisionMode: Boolean,
        zoomLevel: Float,
        projectedCupPx: Float?
    ): Long {
        val px = projectedCupPx
        val base =
            when {
                farPrecisionMode -> CUP_CAPTURE_PENDING_HOLD_FAR_NS
                zoomLevel >= ZOOM_HIGH_THRESHOLD -> {
                    if (px != null && px.isFinite() && px < CUP_CAPTURE_PENDING_SMALL_PROJECTED_PX) {
                        CUP_CAPTURE_PENDING_HOLD_ZOOMED_SMALL_PX_NS
                    } else {
                        CUP_CAPTURE_PENDING_HOLD_ZOOMED_NS
                    }
                }
                else -> CUP_CAPTURE_PENDING_HOLD_NS
            }
        if (!farPrecisionMode &&
            zoomLevel < ZOOM_HIGH_THRESHOLD &&
            px != null &&
            px.isFinite() &&
            px >= CUP_CAPTURE_PENDING_SMALL_PROJECTED_PX &&
            px < QUALITY_WARNING_MAX_PROJECTED_PX
        ) {
            return max(base, HOLD_NS_QUALITY_WARNING_MIN)
        }
        return base
    }

    /** STABILIZING_END: 원거리는 최소 유효 히트 1까지 허용(거리-only), 근거리는 [CUP_LOCK_MIN_VALID_SAMPLES] 유지. */
    fun cupLockMinValidSamplesForDistance(estimatedBallToCupM: Float): Int =
        if (estimatedBallToCupM >= DISTANCE_ONLY_RELAX_MIN_BALL_TO_CUP_M) 1 else CUP_LOCK_MIN_VALID_SAMPLES
}
