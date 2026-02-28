package com.wmcho.puttingcaddie

import android.graphics.Color
import android.graphics.RectF
import com.google.ar.core.Frame

/**
 * Safety net only.
 *
 * - Default OFF in release.
 * - Shadow is allowed only in DEBUG builds.
 * - Never creates anchors, never touches UI, never changes Session/Config.
 */
class LegacyPoseEngine : MeasurementEngine {
    private var axisMode: V31StateMachine.AxisMode = V31StateMachine.AxisMode.XZ

    override fun setAxisMode(axisMode: V31StateMachine.AxisMode) {
        this.axisMode = axisMode
    }

    override fun onUiEvent(e: V31StateMachine.UiEvent, nowNs: Long) {
        // Intentionally minimal placeholder.
        // If a full legacy migration is desired, move old pose-based logic here.
        if (e is V31StateMachine.UiEvent.ResetPressed) reset()
    }

    override fun onFrame(frame: Frame, roiScreen: RectF, nowNs: Long): V31StateMachine.UiModel {
        // No UI impact intended. Provide a safe default model.
        return V31StateMachine.UiModel(
            engineState = V31StateMachine.State.IDLE,
            distanceMeters = 0f,
            distanceTextColor = Color.parseColor("#4CAF50"),
            viewFinderState = ViewFinderView.State.DEFAULT,
            viewFinderQuality = ViewFinderView.QualityState.NONE,
            flashLock = false,
            flashFail = false,
            sampleValidHits = 0,
            sampleTotalPoints = 0,
            sigmaUsedCm = null,
            sigmaMaxCm = null,
            fixDEstMeters = 0f,
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
            ballCupPlaneAngleDeg = null,
            liveRawMeters = null,
            centerHitValid = null,
            multiRayGridHalfSpanPx = null,
            multiRayStepPx = null,
            validSampleCount = null,
            hitDistanceAvgMeters = null,
            hitDistanceMaxMeters = null,
            cameraY = null,
            medianY = null,
            centerYOffsetApplied = null,
            multiRayPlan = null,
            multiRayEstimatedDistanceMeters = null,
            multiRayProjectedCupPx = null,
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
            startEnabled = true,
            finishEnabled = false,
            statusWantsMoveDeviceText = false,
            isResultFinal = false,
            isMeasuringFlow = false
        )
    }

    override fun reset() {
        // no-op
    }
}

