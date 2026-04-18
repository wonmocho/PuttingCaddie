package com.wmcho.puttingcaddie

import com.wmcho.puttingcaddie.slope.ExperimentalSlopeDiagnostics
import com.wmcho.puttingcaddie.slope.SlopeInputResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MeasurementFinalizationPolicyTest {

    @After
    fun tearDown() {
        MeasurementFinalizationPolicy.endSession()
    }

    @Test
    fun distanceHardGuardsFail_whenProjectedPxBelow45() {
        val (ok, reason) =
            MeasurementFinalizationPolicy.distanceHardGuardsPass(
                trackingState = "TRACKING",
                targetMode = MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD,
                centerYOffsetApplied = false,
                projectedCupPx = 40f,
                candidateVsLiveHitXzDeltaM = 0.1f,
                sampleSpreadCupM = 0.1f
            )
        assertFalse(ok)
        assertEquals("projected_cup_px_below_guard", reason)
    }

    @Test
    fun distanceHardGuardsPass_whenThresholdsMet() {
        val (ok, reason) =
            MeasurementFinalizationPolicy.distanceHardGuardsPass(
                trackingState = "TRACKING",
                targetMode = MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD,
                centerYOffsetApplied = false,
                projectedCupPx = 50f,
                candidateVsLiveHitXzDeltaM = 0.1f,
                sampleSpreadCupM = 0.5f
            )
        assertTrue(ok)
        assertEquals(null, reason)
    }

    @Test
    fun beginSession_throwsWhenTargetModeChangesWhileLocked() {
        MeasurementFinalizationPolicy.beginSession(MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD)
        assertThrows(IllegalStateException::class.java) {
            MeasurementFinalizationPolicy.beginSession(MeasurementFinalizationPolicy.TargetMode.BALL_ON_FLOOR)
        }
    }

    @Test
    fun distanceHardGuardsPass_farDistanceTier_allowsLowerProjectedPx() {
        val (ok, reason) =
            MeasurementFinalizationPolicy.distanceHardGuardsPass(
                trackingState = "TRACKING",
                targetMode = MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD,
                centerYOffsetApplied = false,
                projectedCupPx = 35f,
                candidateVsLiveHitXzDeltaM = 0.2f,
                sampleSpreadCupM = 0.35f,
                distanceMeters = 7f
            )
        assertTrue(ok)
        assertEquals(null, reason)
    }

    @Test
    fun distanceHardGuardsFail_ballOnFloor_whenCenterYOffsetApplied() {
        val (ok, _) =
            MeasurementFinalizationPolicy.distanceHardGuardsPass(
                trackingState = "TRACKING",
                targetMode = MeasurementFinalizationPolicy.TargetMode.BALL_ON_FLOOR,
                centerYOffsetApplied = true,
                projectedCupPx = 50f,
                candidateVsLiveHitXzDeltaM = 0.1f,
                sampleSpreadCupM = 0.1f
            )
        assertFalse(ok)
    }

    @Test
    fun chooseFinalForwardSlope_sharedP3Primary_beatsPhase1() {
        val ui =
            baseUiModel(
                sharedP3Log = sharedLog(finalForward = 1.2f),
                slopeDebugInfo = emptySlopeDebug(forwardPct = 99f),
                ballCupSamePlane = true,
                ballCupPlaneAngleDeg = 10f
            )
        assertTrue(MeasurementFinalizationPolicy.sharedP3PrimaryUsable(ui))
        val d = MeasurementFinalizationPolicy.chooseFinalForwardSlope(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, d.status)
        assertEquals(MeasurementFinalizationPolicy.SlopeSource.SHARED_P3, d.source)
        assertEquals(1.2f, d.forwardPct!!, 1e-4f)
    }

    @Test
    fun chooseFinalForwardSlope_samePhysicalFloorFlat_disablesPhase1() {
        val ui =
            baseUiModel(
                sharedP3Log = null,
                slopeDebugInfo = emptySlopeDebug(forwardPct = 5f),
                ballCupSamePlane = false,
                ballCupPlaneAngleDeg = 2f
            )
        assertTrue(MeasurementFinalizationPolicy.samePhysicalFloorHeuristicDisablesPhase1(ui))
        val d = MeasurementFinalizationPolicy.chooseFinalForwardSlope(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.UNAVAILABLE, d.status)
        assertEquals(null, d.source)
        assertEquals("NO_USABLE_FORWARD_SOURCE", d.reason)
    }

    @Test
    fun chooseFinalLateralSlope_sharedP3Primary_beatsPhase1() {
        val ui =
            baseUiModel(
                sharedP3Log = sharedLog(finalForward = 1.2f, finalLateral = 0.6f),
                slopeDebugInfo = emptySlopeDebug(forwardPct = 99f, lateralPct = 77f),
                ballCupSamePlane = true,
                ballCupPlaneAngleDeg = 10f
            )
        assertTrue(MeasurementFinalizationPolicy.sharedP3PrimaryUsable(ui))
        val d = MeasurementFinalizationPolicy.chooseFinalLateralSlope(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, d.status)
        assertEquals(MeasurementFinalizationPolicy.SlopeSource.SHARED_P3, d.source)
        assertEquals(0.6f, d.forwardPct!!, 1e-4f)
    }

    @Test
    fun finalMeasurementFromUi_includesDistanceForwardAndLateral() {
        MeasurementFinalizationPolicy.beginSession(MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD)
        val ui =
            baseUiModel(
                distanceMeters = 3f,
                sharedP3Log = sharedLog(finalForward = 1.0f, finalLateral = 0.4f),
                slopeDebugInfo = emptySlopeDebug(forwardPct = 50f, lateralPct = 50f),
                ballCupSamePlane = true,
                ballCupPlaneAngleDeg = 10f,
                trackingState = "TRACKING",
                multiRayProjectedCupPx = 50f,
                centerYOffsetApplied = false,
                cupCandidateVsLiveHitXZDeltaMAtCommit = 0.1f
            )
        val fm = MeasurementFinalizationPolicy.finalMeasurementFromUi(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, fm.distance.status)
        assertEquals(3f, fm.distance.valueMeters!!, 1e-4f)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, fm.forwardSlope.status)
        assertEquals(1.0f, fm.forwardSlope.forwardPct!!, 1e-4f)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, fm.lateralSlope.status)
        assertEquals(0.4f, fm.lateralSlope.forwardPct!!, 1e-4f)
    }

    @Test
    fun distanceDecisionFromUi_rejected_whenProjectedCupPxTooSmall() {
        MeasurementFinalizationPolicy.beginSession(MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD)
        val ui =
            baseUiModel(
                distanceMeters = 3f,
                trackingState = "TRACKING",
                multiRayProjectedCupPx = 35f,
                centerYOffsetApplied = false,
                cupCandidateVsLiveHitXZDeltaMAtCommit = 0.1f
            )
        val d = MeasurementFinalizationPolicy.distanceDecisionFromUi(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.REJECTED, d.status)
        assertEquals(null, d.valueMeters)
    }

    @Test
    fun distanceDecisionFromUi_valid_whenGuardsPass() {
        MeasurementFinalizationPolicy.beginSession(MeasurementFinalizationPolicy.TargetMode.CUP_STANDARD)
        val ui =
            baseUiModel(
                distanceMeters = 3f,
                trackingState = "TRACKING",
                multiRayProjectedCupPx = 50f,
                centerYOffsetApplied = false,
                cupCandidateVsLiveHitXZDeltaMAtCommit = 0.1f,
                slopeExperimentalResult =
                    SlopeInputResult(
                        ballNormal = null,
                        cupNormal = null,
                        refNormal = null,
                        forwardPct = null,
                        lateralPct = null,
                        quality = "rejected",
                        rejectReason = "cup_sample_spread_too_large",
                        sourceId = "LOCAL_SURFACE_FIT",
                        experimentalDiagnostics =
                            ExperimentalSlopeDiagnostics(sampleSpreadCupM = 0.5f)
                    )
            )
        val d = MeasurementFinalizationPolicy.distanceDecisionFromUi(ui)
        assertEquals(MeasurementFinalizationPolicy.MetricStatus.VALID, d.status)
        assertEquals(3f, d.valueMeters!!, 1e-4f)
    }

    private fun sharedLog(
        finalForward: Float = 1.0f,
        finalLateral: Float = 0.5f,
        quality: String = "GOOD",
        normalY: Float = 0.95f,
        residualM: Float = 0.02f,
        finalBlocked: String? = null
    ): SharedP3LogPayload =
        SharedP3LogPayload(
            trim = null,
            corridor = null,
            candidateAll = null,
            candidateTrimmed = null,
            candidateCorridor = null,
            selectedType = null,
            selectionReason = null,
            rejectedSummary = null,
            normalYFinal = normalY,
            selectionBlockedReason = null,
            quality = quality,
            selectedPrevAngleDeg = null,
            selectedFlipApplied = null,
            selectedPrevDot = null,
            finalForwardPct = finalForward,
            finalLateralPct = finalLateral,
            finalForwardPctRaw = null,
            finalLateralPctRaw = null,
            stabilizationReasons = null,
            lateralDampingFactor = null,
            lateralForwardRatio = null,
            ratioSmallForwardGuard = null,
            finalResidualM = residualM,
            finalSampleCount = 20,
            finalBlockedReason = finalBlocked
        )

    private fun emptySlopeDebug(
        forwardPct: Float?,
        lateralPct: Float? = null,
        quality: String = "valid"
    ): SlopeDebugInfo =
        SlopeDebugInfo(
            forwardPct = forwardPct,
            lateralPct = lateralPct,
            hMeters = null,
            vMeters = null,
            planeDriftDeg = null,
            blockedReason = null,
            quality = quality,
            isXyzMode = false,
            ballNormal = null,
            cupNormal = null,
            refNormal = null,
            ballPos = null,
            cupPos = null,
            forward = null,
            left = null,
            worldUp = null
        )

    @Suppress("LongMethod")
    private fun baseUiModel(
        distanceMeters: Float = 3f,
        ballCupPlaneAngleDeg: Float? = 10f,
        ballCupSamePlane: Boolean? = true,
        sharedP3Log: SharedP3LogPayload? = null,
        slopeDebugInfo: SlopeDebugInfo? = null,
        experimentalSharedSlope: SlopeDebugInfo? = null,
        slopeExperimentalResult: SlopeInputResult? = null,
        trackingState: String? = "TRACKING",
        multiRayProjectedCupPx: Float? = 50f,
        centerYOffsetApplied: Boolean? = false,
        cupCandidateVsLiveHitXZDeltaMAtCommit: Float? = 0.1f,
        isResultFinal: Boolean = true
    ): V31StateMachine.UiModel =
        V31StateMachine.UiModel(
            engineState = V31StateMachine.State.RESULT,
            distanceMeters = distanceMeters,
            distanceTextColor = 0,
            viewFinderState = ViewFinderView.State.DEFAULT,
            viewFinderQuality = ViewFinderView.QualityState.OK,
            flashLock = false,
            flashFail = false,
            sampleValidHits = 10,
            sampleTotalPoints = 10,
            sigmaUsedCm = 1f,
            sigmaMaxCm = 2f,
            fixDEstMeters = distanceMeters,
            bestHitDistanceFromCameraMeters = null,
            cupHoldSigmaCm = null,
            cupHoldMaxCm = null,
            cupHoldDurationMs = null,
            failReasonCode = null,
            failDetailCode = null,
            fixedMinSamples = null,
            bufSize = null,
            sigmaOkConsecutive = null,
            sigmaOkElapsedMs = null,
            cupSigmaNearHoldCount = null,
            sigmaCurrentCmEnd = null,
            sigmaThresholdCmEnd = null,
            liveSource = null,
            ballGroundPlaneNormalY = null,
            ballGroundPlaneNormalLen = null,
            ballGroundPlaneAbsNormalY = null,
            ballGroundPlaneType = null,
            ballGroundPlaneTrackingState = null,
            ballGroundPlaneHitDistanceFromCameraMeters = null,
            ballGroundPlaneExtentX = null,
            ballGroundPlaneExtentZ = null,
            ballCupPlaneAngleDeg = ballCupPlaneAngleDeg,
            ballCupSamePlane = ballCupSamePlane,
            cupPlaneType = null,
            liveRawMeters = null,
            centerHitValid = null,
            multiRayGridHalfSpanPx = null,
            multiRayStepPx = null,
            validSampleCount = null,
            hitDistanceAvgMeters = null,
            hitDistanceMaxMeters = null,
            cameraY = null,
            medianY = null,
            centerYOffsetApplied = centerYOffsetApplied,
            multiRayPlan = null,
            multiRayEstimatedDistanceMeters = null,
            multiRayProjectedCupPx = multiRayProjectedCupPx,
            multiRayCenterFallbackUsed = null,
            ballGridMode = null,
            ballGridStepPx = null,
            ballSampleTotalPoints = null,
            ballSampleValidHits = null,
            ballHitSourceUsed = null,
            ballFreezeUsed = null,
            ballFreezeAgeMs = null,
            ballJumpRejected = null,
            ballFixRuleWindow = null,
            ballFixRuleNeedHits = null,
            ballFixHitsInWindow = null,
            ballFixState = null,
            horizontalVerticalMeters = null,
            startEnabled = false,
            finishEnabled = false,
            statusWantsMoveDeviceText = false,
            isResultFinal = isResultFinal,
            isMeasuringFlow = false,
            slopeDebugInfo = slopeDebugInfo,
            trackingState = trackingState,
            slopeExperimentalResult = slopeExperimentalResult,
            experimentalSharedSlope = experimentalSharedSlope,
            sharedP3Log = sharedP3Log,
            cupCandidateVsLiveHitXZDeltaMAtCommit = cupCandidateVsLiveHitXZDeltaMAtCommit
        )
}
