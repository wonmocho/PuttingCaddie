package com.wmcho.puttingcaddie

/**
 * 상하경사 **제품 출력** 전용 게이트 (거리 파이프라인과 분리).
 * [SlopeFieldTestLog.resolveGraphic] 에서만 적용한다.
 *
 * 정책: 실질 소스는 SharedP3이므로, blocked·저품질이면 SHARED_RAW salvage 포함 출력하지 않는다.
 */
object UpDownSlopeProductGate {
    /** SharedP3 quality가 GOOD일 때만 제품 상하경사 허용 */
    const val REQUIRED_SHARED_QUALITY = "GOOD"

    /** [SharedP3LogPayload.normalYFinal] 하한 — 이보다 작으면 저품질 */
    const val MIN_SHARED_PLANE_NORMAL_Y = 0.95f

    /** [SharedP3LogPayload.finalResidualM] 상한(m) — 초과 시 저품질 */
    const val MAX_FINAL_RESIDUAL_M = 0.01f

    /** [UiModel.multiRayProjectedCupPx] 하한 — 미만이면 저품질(원거리·작은 컵) */
    const val MIN_PROJECTED_CUP_PX = 55f

    /** 샘플 spread 상한(m) — 초과 시 저품질 */
    const val MAX_SAMPLE_SPREAD_CUP_M = 0.60f

    /**
     * [SharedP3LogPayload.finalBlockedReason] 이 비어 있지 않으면 제품 경로 전면 차단.
     * @return `shared_blocked` 또는 null
     */
    fun sharedP3BlockedReason(log: SharedP3LogPayload?): String? {
        if (log == null) return null
        if (!log.finalBlockedReason.isNullOrBlank()) return "shared_blocked"
        return null
    }

    /**
     * 품질·거리 진단 임계 미달 시 제품 상하경사 차단.
     * blocked가 우선이므로, 호출부에서 [sharedP3BlockedReason] 이 null일 때만 호출할 것.
     * @return `shared_low_quality` 또는 null
     */
    fun sharedP3LowQualityReason(
        log: SharedP3LogPayload?,
        projectedCupPx: Float?,
        sampleSpreadCupM: Float?
    ): String? {
        if (log == null) return "shared_low_quality"
        if (log.quality != REQUIRED_SHARED_QUALITY) return "shared_low_quality"
        val ny = log.normalYFinal
        if (ny == null || !ny.isFinite() || ny < MIN_SHARED_PLANE_NORMAL_Y) return "shared_low_quality"
        val res = log.finalResidualM
        if (res == null || !res.isFinite() || res > MAX_FINAL_RESIDUAL_M) return "shared_low_quality"
        if (projectedCupPx == null || !projectedCupPx.isFinite() || projectedCupPx < MIN_PROJECTED_CUP_PX) {
            return "shared_low_quality"
        }
        if (sampleSpreadCupM != null && sampleSpreadCupM.isFinite() && sampleSpreadCupM > MAX_SAMPLE_SPREAD_CUP_M) {
            return "shared_low_quality"
        }
        return null
    }

    fun productGateReason(
        log: SharedP3LogPayload?,
        projectedCupPx: Float?,
        sampleSpreadCupM: Float?
    ): String? {
        sharedP3BlockedReason(log)?.let { return it }
        return sharedP3LowQualityReason(log, projectedCupPx, sampleSpreadCupM)
    }
}
