package com.wmcho.puttingcaddie

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CupOffsetAnchorEstimator] 밖으로 나가는 Diagnostics는 항상 [finalizeCupOffsetDiagnostics]를 통과한다는
 * 계약을 고정한다 (feature OFF / throttle hit / recompute 저장).
 */
class CupOffsetAnchorFinalizeGuaranteeTest {

    private val ok = CupOffsetAnchorEstimator.QualityProbeStatus.OK.wire
    private val none = CupOffsetAnchorEstimator.QualityInvalidateReason.NONE.wire

    private fun minimalRecomputeDiagFull(): CupOffsetAnchorEstimator.Diagnostics =
        CupOffsetAnchorEstimator.Diagnostics(
            mode = "center_aim_offset_anchor",
            candidateCount = 1,
            bestVarianceCm = 1f,
            bestResidualCm = 1f,
            bestNormalY = 0.99f,
            bestOffsetDistCm = 10f,
            planeResidualCm = 1f,
            reprojectSuccess = true,
            failureReason = null
        )

    @Test
    fun featureOff_diagnostics_are_finalized_with_default_quality_fields() {
        val d = CupOffsetAnchorEstimator.diagnosticsAfterFeatureOffFinalize()
        assertEquals(ok, d.qualityProbeStatus)
        assertEquals(none, d.qualityInvalidateReason)
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(d)
    }

    @Test
    fun throttleHit_lastFullDiagnostics_contains_only_finalized_payload() {
        val prior =
            CupOffsetAnchorEstimator.buildRecomputedStoreDiagnostics(
                diagFull = minimalRecomputeDiagFull(),
                success = true,
                recomputeDecision =
                    CupOffsetAnchorEstimator.RecomputeDecision(
                        recompute = true,
                        qualityProbeStatus = CupOffsetAnchorEstimator.QualityProbeStatus.OK,
                        qualityInvalidateReason = CupOffsetAnchorEstimator.QualityInvalidateReason.NONE
                    ),
                camDistForDiag = null,
                camAngForDiag = null
            )
        val cache = CupOffsetAnchorEstimator.CacheState()
        cache.lastFullDiagnostics = prior
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(cache.lastFullDiagnostics!!)

        val idle =
            CupOffsetAnchorEstimator.RecomputeDecision(
                recompute = false,
                qualityProbeStatus = CupOffsetAnchorEstimator.QualityProbeStatus.OK,
                qualityInvalidateReason = CupOffsetAnchorEstimator.QualityInvalidateReason.NONE
            )
        val throttled =
            CupOffsetAnchorEstimator.buildThrottledReturnDiagnostics(
                base = cache.lastFullDiagnostics!!,
                lastResultSuccess = true,
                recomputeDecision = idle,
                throttleAgeMs = 50L,
                failureReasonForDiag = cache.lastFullDiagnostics!!.failureReason,
                camDistDelta = 0.01f,
                camAngDelta = 0.5f
            )
        assertEquals(ok, throttled.qualityProbeStatus)
        assertEquals(none, throttled.qualityInvalidateReason)
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(throttled)
    }

    @Test
    fun recompute_outDiag_is_finalized_before_return_and_before_cache_store() {
        val recon =
            CupOffsetAnchorEstimator.RecomputeDecision(
                recompute = true,
                qualityProbeStatus = CupOffsetAnchorEstimator.QualityProbeStatus.OK,
                qualityInvalidateReason = CupOffsetAnchorEstimator.QualityInvalidateReason.NONE
            )
        val outDiag =
            CupOffsetAnchorEstimator.buildRecomputedStoreDiagnostics(
                diagFull = minimalRecomputeDiagFull(),
                success = true,
                recomputeDecision = recon,
                camDistForDiag = 0.02f,
                camAngForDiag = 1f
            )
        assertEquals(
            outDiag,
            CupOffsetAnchorEstimator.finalizeCupOffsetDiagnostics(outDiag)
        )
        val cache = CupOffsetAnchorEstimator.CacheState()
        cache.lastFullDiagnostics = outDiag
        assertEquals(outDiag, cache.lastFullDiagnostics)
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(cache.lastFullDiagnostics!!)
    }
}
