package com.wmcho.puttingcaddie

/**
 * 컵 END 앵커 커밋: 게이트(순간) vs 사후 스냅샷 분리.
 * [V31StateMachine] STABILIZING_END confirmLock 직전에만 사용.
 */
object CupEndAnchorCommitPolicy {
    const val CUP_DELTA_NEAR_THRESHOLD_M = 0.15f
    const val CUP_DELTA_FAR_THRESHOLD_M = 0.30f

    fun xzDistanceMeters(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dz = a[2] - b[2]
        return kotlin.math.sqrt(dx * dx + dz * dz)
    }

    /** FAR 저품질에 가까우면 완화 임계(0.30m) 사용 */
    fun strictFarMode(projectedCupPx: Float?, sampleSpreadCupM: Float?, validSamples: Int): Boolean {
        val px = projectedCupPx ?: 999f
        val spread = sampleSpreadCupM ?: 0f
        return px < 50f || spread > 1.0f || validSamples <= 9
    }

    fun thresholdM(strictFar: Boolean): Float =
        if (strictFar) CUP_DELTA_FAR_THRESHOLD_M else CUP_DELTA_NEAR_THRESHOLD_M

    fun vetoShouldBlock(gateDeltaM: Float, strictFar: Boolean): Boolean =
        gateDeltaM > thresholdM(strictFar)
}
