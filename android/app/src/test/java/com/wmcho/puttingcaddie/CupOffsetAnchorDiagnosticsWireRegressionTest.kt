package com.wmcho.puttingcaddie

import org.junit.Assert.assertFalse
import org.junit.Test

class CupOffsetAnchorDiagnosticsWireRegressionTest {

    private fun diag(qualityProbeStatus: String, qualityInvalidateReason: String) =
        CupOffsetAnchorEstimator.Diagnostics(
            mode = "t",
            candidateCount = 0,
            bestVarianceCm = null,
            bestResidualCm = null,
            bestNormalY = null,
            bestOffsetDistCm = null,
            planeResidualCm = null,
            reprojectSuccess = false,
            failureReason = null,
            qualityProbeStatus = qualityProbeStatus,
            qualityInvalidateReason = qualityInvalidateReason
        )

    @Test
    fun qualityInvalidateReason_wires_excludeRemovedJumpVariants() {
        val joined =
            CupOffsetAnchorEstimator.QualityInvalidateReason.entries.joinToString("|") { it.wire }
        for (s in CupOffsetAnchorEstimator.removedQualityInvalidateWireSubstrings) {
            assertFalse("wire bundle must not contain '$s'", joined.contains(s))
        }
    }

    @Test
    fun diagnosticsInvariant_okAndNone() {
        val ok = CupOffsetAnchorEstimator.QualityProbeStatus.OK.wire
        val none = CupOffsetAnchorEstimator.QualityInvalidateReason.NONE.wire
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(diag(ok, none))
        CupOffsetAnchorEstimator.finalizeCupOffsetDiagnostics(diag(ok, none))
    }

    @Test
    fun diagnosticsInvariant_probeFailDepthAndProbeFailed() {
        val dm = CupOffsetAnchorEstimator.QualityProbeStatus.DEPTH_MISSING.wire
        val pf = CupOffsetAnchorEstimator.QualityInvalidateReason.PROBE_FAILED.wire
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(diag(dm, pf))
    }

    @Test
    fun diagnosticsInvariant_okAndResidual() {
        val ok = CupOffsetAnchorEstimator.QualityProbeStatus.OK.wire
        val res = CupOffsetAnchorEstimator.QualityInvalidateReason.RESIDUAL.wire
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(diag(ok, res))
    }

    @Test(expected = IllegalStateException::class)
    fun diagnosticsInvariant_rejectsNonOkWithNonProbeFailed() {
        val dm = CupOffsetAnchorEstimator.QualityProbeStatus.DEPTH_MISSING.wire
        val res = CupOffsetAnchorEstimator.QualityInvalidateReason.RESIDUAL.wire
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(diag(dm, res))
    }

    @Test(expected = IllegalStateException::class)
    fun diagnosticsInvariant_rejectsOkWithProbeFailed() {
        val ok = CupOffsetAnchorEstimator.QualityProbeStatus.OK.wire
        val pf = CupOffsetAnchorEstimator.QualityInvalidateReason.PROBE_FAILED.wire
        CupOffsetAnchorEstimator.checkDiagnosticsQualityProbeInvariant(diag(ok, pf))
    }
}
