package com.wmcho.puttingcaddie

/**
 * Slope Debug UI용 데이터. LATERAL_SLOPE_DESIGN.md 정의에 따른 forward/lateral 분해 결과.
 * ballCupPlaneAngleDeg는 품질지표(평면차이)로만 사용. 측면경사는 별도 계산.
 */
data class SlopeDebugInfo(
    val forwardPct: Float?,
    val lateralPct: Float?,
    val hMeters: Float?,
    val vMeters: Float?,
    val planeDriftDeg: Float?,
    val blockedReason: String?,
    val quality: String,
    val isXyzMode: Boolean,
    val ballNormal: FloatArray?,
    val cupNormal: FloatArray?,
    val refNormal: FloatArray?,
    val ballPos: FloatArray?,
    val cupPos: FloatArray?,
    val forward: FloatArray?,
    val left: FloatArray?,
    val worldUp: FloatArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SlopeDebugInfo
        if (forwardPct != other.forwardPct) return false
        if (lateralPct != other.lateralPct) return false
        if (ballNormal != null) {
            if (other.ballNormal == null || !ballNormal.contentEquals(other.ballNormal)) return false
        } else if (other.ballNormal != null) return false
        return true
    }

    override fun hashCode(): Int {
        var result = forwardPct?.hashCode() ?: 0
        result = 31 * result + (lateralPct?.hashCode() ?: 0)
        result = 31 * result + (ballNormal?.contentHashCode() ?: 0)
        return result
    }
}
