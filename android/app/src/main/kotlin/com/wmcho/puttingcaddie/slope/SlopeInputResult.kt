package com.wmcho.puttingcaddie.slope

/**
 * Slope Input 2.0: 공통 경사 입력 결과.
 * Baseline(PLANE_BASED)과 Experimental(LOCAL_SURFACE_FIT) 모두 동일 형식으로 반환.
 */
data class SlopeInputResult(
    val ballNormal: FloatArray?,
    val cupNormal: FloatArray?,
    val refNormal: FloatArray?,
    val forwardPct: Float?,
    val lateralPct: Float?,
    val quality: String,  // "valid" | "rejected"
    val rejectReason: String?,
    val sourceId: String,  // "PLANE_BASED" | "LOCAL_SURFACE_FIT"
    // LocalSurfaceFit 전용
    val sampleCountBall: Int = 0,
    val sampleCountCup: Int = 0,
    val validSampleRatio: Float = 0f,
    val fitResidualBall: Float? = null,
    val fitResidualCup: Float? = null,
    val sampleSourceTypes: String? = null,  // "PLANE,POINT,DEPTH" 등
    // v1: 입력 소스 분리 표시 (디버그용)
    val ballInputSource: String? = null,
    val cupInputSource: String? = null,
    // v1: 추적용 (디버그/로깅)
    val ballSampleTimestampMs: Long? = null,
    val cupSampleTimestampMs: Long? = null,
    val ballSampleFrameId: Long? = null,
    val cupSampleFrameId: Long? = null,
    // v1: plane_drift_too_large 등 reject 원인 정밀 진단
    val experimentalPlaneDriftDeg: Float? = null,
    val driftThresholdDeg: Float? = null,
    val trackingStateAtBallSample: String? = null,
    val trackingStateAtCupSample: String? = null,
    /** Experimental 전용: 분류·trace·sanity·KPI용 (로그 SSOT) */
    val experimentalDiagnostics: ExperimentalSlopeDiagnostics? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SlopeInputResult
        if (forwardPct != other.forwardPct) return false
        if (lateralPct != other.lateralPct) return false
        if (sourceId != other.sourceId) return false
        return true
    }

    override fun hashCode(): Int {
        var result = forwardPct?.hashCode() ?: 0
        result = 31 * result + (lateralPct?.hashCode() ?: 0)
        result = 31 * result + sourceId.hashCode()
        return result
    }
}
