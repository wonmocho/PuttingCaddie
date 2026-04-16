package com.wmcho.puttingcaddie

import android.util.Log
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 거리 우선 필드 테스트 로그 — 계산 로직 변경 없이 원인 분류·가시성.
 * [SlopeFieldTestLog]보다 먼저 해석할 수 있도록 이메일 JSON·Logcat에 동일 스키마 반영.
 */
object DistanceFieldTestLog {

    private const val TAG = "DISTANCE_FIELD"

    /** Activity에서만 알 수 있는 LIVE@finish 등 */
    data class ActivityExtras(
        val liveAtFinishM: Float?,
        val liveSourceAtFinish: String?,
        val liveRawAtFinish: Float?,
        val centerHitValidAtFinish: Boolean?,
        /** 이전 완료 측정들의 final 거리(m) — 이상치 비교용 */
        val priorFinalDistancesM: List<Float>
    )

    data class DistanceFeedbackSnapshot(
        val distanceOk: Boolean,
        val finalDistanceM: Float,
        val liveRawM: Float?,
        val liveAtFinishM: Float?,
        val anchorDistanceM: Float?,
        val liveSource: String?,
        val distanceSourcePrimary: String?,
        val distanceSourceFinal: String?,
        val distanceFallbackUsed: Boolean,
        val distanceFallbackReason: String?,
        val finalDistanceClass: String,
        val finalDistanceReason: String,
        val centerHitValid: Boolean?,
        /** 앵커 존재 기준 — raw JSON fixState와 동일 의미 */
        val ballFixSummary: String,
        val cupFixSummary: String,
        /** 이전: sampleValidHits / validSampleCount 기반(프레임별, RESULT에서 흔히 OPEN) */
        val ballFixRaw: String,
        val cupFixRaw: String,
        val ballAnchorUsedState: String,
        val cupAnchorUsedState: String,
        val ballAnchorWorld: FloatArray?,
        val cupAnchorWorld: FloatArray?,
        val anchorHorizontalM: Float?,
        val anchor3dM: Float?,
        val startAnchorCreatedAtMs: Long?,
        val endAnchorCreatedAtMs: Long?,
        val anchorCreatedAtMs: Long?,
        val anchorReused: Boolean?,
        val startAnchorPose: FloatArray?,
        val endAnchorPose: FloatArray?,
        val trackingState: String?,
        val projectedCupPx: Float?,
        val validSampleCount: Int?,
        val planeIntersectionDistanceM: Float?,
        val planeIntersectionVsAnchorDeltaM: Float?,
        val planeIntersectionAbnormal: Boolean,
        val planeIntersectionAbnormalReason: String?,
        val liveRangeM: Float?,
        val liveStdM: Float?,
        val liveStable: Boolean?,
        val liveOutlierCount: Int,
        val liveRejectedReason: String?,
        val distanceBlockedByCupFix: Boolean,
        val cupFailReason: String?,
        val distanceFailureStage: String?,
        val sampleSpreadCupM: Float?,
        val multiRayPlan: String?,
        val centerFallbackUsed: Boolean?,
        val sigmaCurrentCm: Float?,
        val sigmaThresholdCm: Float?,
        val liveFinalGapM: Float?,
        val liveFinalGapLarge: Boolean,
        val distanceSuspicious: Boolean,
        val distanceSuspiciousReason: String?,
        /** [FinalDistanceGuard] — RESULT 시 엔진 캡처 */
        val finalDistanceLivePlaneM: Float?,
        val finalDistanceSourceBeforeGuard: String?,
        val finalDistanceSourceAfterGuard: String?,
        val finalDistanceGuardTriggered: Boolean,
        val finalDistanceGuardReasons: String?,
        val finalDistanceAnchorInvalidReason: String?,
        val planeIntersectionVsAnchorDeltaRatio: Float?,
        /** END_LOCK 직전 LIVE 경로의 볼/컵 월드 참조(엔진 [V31StateMachine]) */
        val ballLiveHitWorldAtFinish: FloatArray?,
        val cupLiveHitWorldAtFinish: FloatArray?,
        val cupAnchorHitWorldBeforeSnap: FloatArray?,
        val cupAnchorPoseWorldAfterSnap: FloatArray?,
        val cupAnchorCommitTrackableType: String?,
        val cupAnchorCommitTrackableId: String?,
        /** anchor 월드 vs LIVE가 참조한 월드 — XZ(m) */
        val ballAnchorVsLiveHitXZDeltaM: Float?,
        val cupAnchorVsLiveHitXZDeltaM: Float?,
        /** LIVE가 같은 프레임에서 쓴 볼·컵 월드 간 XZ 거리(m) */
        val liveHitPairXZM: Float?,
        /** 컵 히트 스냅 전·후 앵커 포즈 XZ 차이(m) */
        val cupSnapXZDeltaM: Float?,
        /** FAR_3x3 + 낮은 px / 적은 샘플 / 큰 spread 등 */
        val endAnchorLowQualityFar: Boolean,
        /**
         * END_LOCK 커밋 직전: 멀티레이 후보(hit pose) vs [cupLiveHitWorldAtFinish] XZ(m).
         * [CupEndAnchorCommitPolicy] 게이트와 동일 정의.
         */
        val cupCandidateVsLiveHitXZDeltaMAtCommit: Float?,
        val cupEndAnchorCommitStrictFar: Boolean?,
        val cupEndAnchorCommitGateThresholdM: Float?,
        /** 재시도 상한 초과 시 true — 게이트 우회 후 커밋 */
        val cupEndAnchorGateBypassedMaxRetries: Boolean?,
        /** [cupEndAnchorGateBypassedMaxRetries] 와 동일 — 통계/버킷용 */
        val cupEndAnchorCommitBypassSession: Boolean?,
        /** 컵 END: LIVE 갱신에 사용한 ARCore 프레임 타임스탬프(ns) */
        val cupLiveWorldFrameTimestampNs: Long?,
        /** 멀티레이 정렬·게이트가 동일 틱 LIVE 스냅샷을 썼는지(타임스탬프 있으면 true) */
        val cupLiveAlignAndGateSameFrame: Boolean,
        /** 컵 END translation 소스 — [LIVE_DISTANCE_WORLD] 등 [UiModel]과 동일 */
        val cupEndAnchorPositionSource: String?,
        /** 앵커 포즈 vs freeze 직전 live world XZ(m) */
        val cupEndAnchorVsLiveWorldXZM: Float?,
        val distanceQualityTier: String?,
        val cupConfirmDecision: String?,
        val cupConfirmSource: String?,
        val statisticalConfirmSupportCount: Int?,
        val statisticalConfirmSpreadXZM: Float?,
        val statisticalConfirmStdXZM: Float?,
        val statisticalConfirmLowPxSalvage: Boolean?,
        val captureBurstUsed: Boolean,
        val captureBurstComputed: Boolean,
        val captureBurstAcceptedFrames: Int?,
        val captureBurstRejectedFrames: Int?,
        val captureConfirmSpreadXZM: Float?,
        val captureConfirmStdXZM: Float?,
        val cupConfirmReason: String?,
        val finalCupConfirmSource: String?,
        val measureState: String?,
        val measurementTransformVersion: Long?,
        val detectorArFrameDeltaMs: Float?,
        val confirmRejectedReason: String?,
        val confirmActiveTransformVersion: Long?,
        val autoZoomRequested: Boolean,
        val autoZoomTargetRatio: Float?,
        val autoZoomReason: String?,
        val debugBannerShort: String
    )

    fun feedbackSnapshot(ui: V31StateMachine.UiModel, extras: ActivityExtras): DistanceFeedbackSnapshot {
        val isFail = ui.engineState == V31StateMachine.State.FAIL
        val isResult = ui.engineState == V31StateMachine.State.RESULT
        val finalD = ui.distanceMeters
        val distanceOk = finalD.isFinite() && finalD > 0f

        val ballFixRaw =
            if (ui.ballSampleValidHits != null && (ui.ballSampleValidHits ?: 0) > 0) "FIXED" else "OPEN"
        val cupFixRaw =
            if (ui.validSampleCount != null && (ui.validSampleCount ?: 0) >= 1) "FIXED" else "OPEN"
        val ballFixSummary = if (ui.ballAnchorWorld != null) "FIXED" else "OPEN"
        val cupFixSummary = if (ui.cupAnchorWorld != null) "FIXED" else "OPEN"
        val ballAnchorUsedState = ui.ballAnchorSourceState ?: "NONE"
        val cupAnchorUsedState = ui.cupAnchorSourceState ?: "NONE"

        val primary = ui.distanceLockLiveSource ?: extras.liveSourceAtFinish
        val fallbackUsed = ui.distanceFinalFallbackUsed
        val snapReason = ui.distanceFinalSnapshotReason
        val sourceFinal =
            when {
                !distanceOk -> "NONE"
                fallbackUsed -> "LAST_DISPLAY_FALLBACK"
                snapReason == "live_snapshot" -> primary ?: "LIVE_SMOOTHED"
                else -> primary ?: "UNKNOWN"
            }

        val anchor = ui.anchorDistanceMeters
        val planeRaw = ui.distanceLockLiveRawM ?: extras.liveRawAtFinish
        val vsAnchorLegacy =
            if (anchor != null && anchor.isFinite() && finalD.isFinite()) abs(finalD - anchor) else null
        val vsAnchor =
            if (isResult && ui.planeIntersectionVsAnchorDeltaM != null) ui.planeIntersectionVsAnchorDeltaM else vsAnchorLegacy
        val abnormalPair = classifyPlaneAbnormal(finalD, anchor, ui.multiRayProjectedCupPx, vsAnchor)
        val gapM =
            if (extras.liveAtFinishM != null && extras.liveAtFinishM.isFinite() && distanceOk) {
                abs(finalD - extras.liveAtFinishM)
            } else {
                null
            }
        val gapLarge = gapM != null && gapM > GAP_LARGE_M

        val spread = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM

        val ballAnchorVsLiveHitXZDeltaM = xzDeltaM(ui.ballAnchorWorld, ui.ballLiveHitWorldAtFinish)
        val cupAnchorVsLiveHitXZDeltaM = xzDeltaM(ui.cupAnchorWorld, ui.cupLiveHitWorldAtFinish)
        val liveHitPairXZM = xzDeltaM(ui.ballLiveHitWorldAtFinish, ui.cupLiveHitWorldAtFinish)
        val cupSnapXZDeltaM = xzDeltaM(ui.cupAnchorHitWorldBeforeSnap, ui.cupAnchorPoseWorldAfterSnap)
        val endAnchorLowQualityFar =
            (
                ui.multiRayPlan == "FAR_3x3" ||
                    ui.multiRayPlan == "FAR_5x5"
                ) &&
                (
                    (ui.multiRayProjectedCupPx ?: 999f) < 50f ||
                        (ui.validSampleCount ?: 0) <= 9 ||
                        (spread ?: 0f) > 1.3f
                    )

        val (cls, reason) =
            if (isFail && !distanceOk) {
                classifyFail(ui)
            } else if (isFail && distanceOk) {
                "degraded" to "DISTANCE_ONLY_SALVAGED_ON_FAIL"
            } else if (isResult) {
                classifyResult(
                    ui = ui,
                    distanceOk = distanceOk,
                    gapLarge = gapLarge,
                    abnormalPair = abnormalPair,
                    spread = spread
                )
            } else {
                "ok" to "NONE"
            }

        val suspiciousPair =
            classifySuspicious(
                ui = ui,
                extras = extras,
                distanceOk = distanceOk,
                primary = primary,
                gapLarge = gapLarge,
                spread = spread,
                cls = cls
            )

        val stage = distanceFailureStage(ui, isFail, cupFixSummary)

        var banner =
            when {
                !distanceOk -> "DIST FAIL"
                cls == "distance_failure" -> "DIST FAIL: ${reason.take(24)}"
                suspiciousPair.first -> "DIST SUSPECT: ${(suspiciousPair.second ?: "").take(20)}"
                cls == "degraded" -> "DIST DEGRADED"
                else -> "DIST OK"
            }
        if (isResult) {
            when {
                ui.finalDistanceGuardTriggered -> banner += " →ANCHOR"
                ui.finalDistanceAnchorInvalidReason != null ->
                    banner += " |keepLive:${ui.finalDistanceAnchorInvalidReason}"
                else -> Unit
            }
            if (ui.cupEndAnchorCommitBypassSession == true) {
                banner += " |CUP_END_BYPASS"
            }
            ui.cupConfirmSource?.let { banner += " |cupConfirm=$it" }
            ui.distanceQualityTier?.let { banner += " |tier=$it" }
            if (ui.captureBurstUsed) {
                banner += " |burst=${ui.captureBurstAcceptedFrames ?: 0}/${ui.captureBurstRejectedFrames ?: 0}"
            }
            ui.measureState?.let { banner += " |mState=$it" }
            ui.confirmRejectedReason?.let { banner += " |confirmReject=${it.take(24)}" }
            if (ui.autoZoomRequested) {
                banner += " |autoZoom=${ui.autoZoomTargetRatio?.let { String.format(Locale.US, "%.1f", it) } ?: "?"}"
            }
        }

        return DistanceFeedbackSnapshot(
            distanceOk = distanceOk,
            finalDistanceM = finalD,
            liveRawM = planeRaw ?: extras.liveRawAtFinish,
            liveAtFinishM = extras.liveAtFinishM,
            anchorDistanceM = anchor,
            liveSource = extras.liveSourceAtFinish ?: primary,
            distanceSourcePrimary = primary,
            distanceSourceFinal = sourceFinal,
            distanceFallbackUsed = fallbackUsed,
            distanceFallbackReason = snapReason,
            finalDistanceClass = if (isFail) "distance_failure" else cls,
            finalDistanceReason = if (isFail) reason else reason,
            centerHitValid = ui.centerHitValid ?: extras.centerHitValidAtFinish,
            ballFixSummary = ballFixSummary,
            cupFixSummary = cupFixSummary,
            ballFixRaw = ballFixRaw,
            cupFixRaw = cupFixRaw,
            ballAnchorUsedState = ballAnchorUsedState,
            cupAnchorUsedState = cupAnchorUsedState,
            ballAnchorWorld = ui.ballAnchorWorld,
            cupAnchorWorld = ui.cupAnchorWorld,
            anchorHorizontalM = ui.anchorHorizontalM,
            anchor3dM = ui.anchor3dM,
            startAnchorCreatedAtMs = ui.startAnchorCreatedAtMs,
            endAnchorCreatedAtMs = ui.endAnchorCreatedAtMs,
            anchorCreatedAtMs = ui.anchorCreatedAtMs,
            anchorReused = ui.anchorReused,
            startAnchorPose = ui.startAnchorPose,
            endAnchorPose = ui.endAnchorPose,
            trackingState = ui.trackingState,
            projectedCupPx = ui.multiRayProjectedCupPx,
            validSampleCount = ui.validSampleCount,
            planeIntersectionDistanceM = planeRaw,
            planeIntersectionVsAnchorDeltaM = vsAnchor,
            planeIntersectionAbnormal = abnormalPair.abnormal,
            planeIntersectionAbnormalReason = abnormalPair.reason,
            liveRangeM = ui.distanceLockLiveRangeM,
            liveStdM = ui.distanceLockLiveSigmaM,
            liveStable = ui.distanceLockLiveStable,
            liveOutlierCount = 0,
            liveRejectedReason = if (ui.distanceLockLiveStable == false) "sigma_or_range_gate" else null,
            distanceBlockedByCupFix =
                cupFixSummary != "FIXED" &&
                    isFail &&
                    !distanceOk &&
                    ((extras.liveAtFinishM == null) || !(extras.liveAtFinishM.isFinite() && extras.liveAtFinishM > 0f)),
            cupFailReason = ui.cupBlockedReason,
            distanceFailureStage = stage,
            sampleSpreadCupM = spread,
            multiRayPlan = ui.multiRayPlan,
            centerFallbackUsed = ui.multiRayCenterFallbackUsed,
            sigmaCurrentCm = ui.sigmaCurrentCmEnd,
            sigmaThresholdCm = ui.sigmaThresholdCmEnd,
            liveFinalGapM = gapM,
            liveFinalGapLarge = gapLarge,
            distanceSuspicious = suspiciousPair.first,
            distanceSuspiciousReason = suspiciousPair.second,
            finalDistanceLivePlaneM = if (isResult) ui.finalDistanceLivePlaneMeters else null,
            finalDistanceSourceBeforeGuard = if (isResult) ui.finalDistanceSourceBeforeGuard else null,
            finalDistanceSourceAfterGuard = if (isResult) ui.finalDistanceSourceAfterGuard else null,
            finalDistanceGuardTriggered = isResult && ui.finalDistanceGuardTriggered,
            finalDistanceGuardReasons = if (isResult) ui.finalDistanceGuardReasons else null,
            finalDistanceAnchorInvalidReason = if (isResult) ui.finalDistanceAnchorInvalidReason else null,
            planeIntersectionVsAnchorDeltaRatio = if (isResult) ui.planeIntersectionVsAnchorDeltaRatio else null,
            ballLiveHitWorldAtFinish = ui.ballLiveHitWorldAtFinish,
            cupLiveHitWorldAtFinish = ui.cupLiveHitWorldAtFinish,
            cupAnchorHitWorldBeforeSnap = ui.cupAnchorHitWorldBeforeSnap,
            cupAnchorPoseWorldAfterSnap = ui.cupAnchorPoseWorldAfterSnap,
            cupAnchorCommitTrackableType = ui.cupAnchorCommitTrackableType,
            cupAnchorCommitTrackableId = ui.cupAnchorCommitTrackableId,
            ballAnchorVsLiveHitXZDeltaM = ballAnchorVsLiveHitXZDeltaM,
            cupAnchorVsLiveHitXZDeltaM = cupAnchorVsLiveHitXZDeltaM,
            liveHitPairXZM = liveHitPairXZM,
            cupSnapXZDeltaM = cupSnapXZDeltaM,
            endAnchorLowQualityFar = endAnchorLowQualityFar,
            cupCandidateVsLiveHitXZDeltaMAtCommit = ui.cupCandidateVsLiveHitXZDeltaMAtCommit,
            cupEndAnchorCommitStrictFar = ui.cupEndAnchorCommitStrictFar,
            cupEndAnchorCommitGateThresholdM = ui.cupEndAnchorCommitGateThresholdM,
            cupEndAnchorGateBypassedMaxRetries = ui.cupEndAnchorGateBypassedMaxRetries,
            cupEndAnchorCommitBypassSession = ui.cupEndAnchorCommitBypassSession,
            cupLiveWorldFrameTimestampNs = ui.cupLiveWorldFrameTimestampNs,
            cupLiveAlignAndGateSameFrame = ui.cupLiveWorldFrameTimestampNs != null,
            cupEndAnchorPositionSource = ui.cupEndAnchorPositionSource,
            cupEndAnchorVsLiveWorldXZM = ui.cupEndAnchorVsLiveWorldXZM,
            distanceQualityTier = ui.distanceQualityTier,
            cupConfirmDecision = ui.cupConfirmDecision,
            cupConfirmSource = ui.cupConfirmSource,
            statisticalConfirmSupportCount = ui.statisticalConfirmSupportCount,
            statisticalConfirmSpreadXZM = ui.statisticalConfirmSpreadXZM,
            statisticalConfirmStdXZM = ui.statisticalConfirmStdXZM,
            statisticalConfirmLowPxSalvage = ui.statisticalConfirmLowPxSalvage,
            captureBurstUsed = ui.captureBurstUsed,
            captureBurstComputed = ui.captureBurstComputed,
            captureBurstAcceptedFrames = ui.captureBurstAcceptedFrames,
            captureBurstRejectedFrames = ui.captureBurstRejectedFrames,
            captureConfirmSpreadXZM = ui.captureConfirmSpreadXZM,
            captureConfirmStdXZM = ui.captureConfirmStdXZM,
            cupConfirmReason = ui.cupConfirmReason,
            finalCupConfirmSource = ui.finalCupConfirmSource,
            measureState = ui.measureState,
            measurementTransformVersion = ui.measurementTransformVersion,
            detectorArFrameDeltaMs = ui.confirmGateMaxFrameDeltaMs,
            confirmRejectedReason = ui.confirmRejectedReason,
            confirmActiveTransformVersion = ui.confirmActiveTransformVersion,
            autoZoomRequested = ui.autoZoomRequested,
            autoZoomTargetRatio = ui.autoZoomTargetRatio,
            autoZoomReason = ui.autoZoomReason,
            debugBannerShort = banner
        )
    }

    private fun xzDeltaM(a: FloatArray?, b: FloatArray?): Float? {
        if (a == null || b == null || a.size < 3 || b.size < 3) return null
        val dx = a[0] - b[0]
        val dz = a[2] - b[2]
        val d = sqrt(dx * dx + dz * dz)
        return if (d.isFinite()) d else null
    }

    private data class AbnormalPlane(val abnormal: Boolean, val reason: String?)

    private fun classifyPlaneAbnormal(
        finalD: Float,
        anchor: Float?,
        projectedPx: Float?,
        vsAnchorDelta: Float?
    ): AbnormalPlane {
        if (!finalD.isFinite() || finalD <= 0f) return AbnormalPlane(false, null)
        if (anchor != null && anchor.isFinite() && anchor > 0.1f) {
            val ratio = finalD / anchor
            if (ratio > ANCHOR_RATIO_TOO_HIGH || finalD - anchor > ANCHOR_ABS_TOO_HIGH_M) {
                return AbnormalPlane(true, "too_far_from_anchor")
            }
        }
        if (projectedPx != null && projectedPx.isFinite() && projectedPx < TINY_PROJECTED_PX &&
            finalD > 6f
        ) {
            return AbnormalPlane(true, "small_projected_px_high_distance")
        }
        if (vsAnchorDelta != null && vsAnchorDelta > ANCHOR_DELTA_SUSPICIOUS_M) {
            return AbnormalPlane(true, "large_delta_vs_anchor")
        }
        return AbnormalPlane(false, null)
    }

    private fun classifyFail(ui: V31StateMachine.UiModel): Pair<String, String> {
        val code = ui.failReasonCode ?: "UNKNOWN"
        val detail = ui.failDetailCode
        val r =
            when (code) {
                "FAIL_NO_VALID_HITS" -> "DISTANCE_NO_VALID_HITS"
                "FAIL_TIMEOUT" -> "DISTANCE_TIMEOUT_SIGMA_NOT_OK"
                "FAIL_TRACKING_STOPPED" -> "DISTANCE_TRACKING_STOPPED"
                else -> "DISTANCE_UNKNOWN"
            }
        val cls = "distance_failure"
        if (detail != null && detail.contains("cup", ignoreCase = true)) {
            return cls to "DISTANCE_CUP_FIX_FAIL"
        }
        return cls to r
    }

    private fun classifyResult(
        ui: V31StateMachine.UiModel,
        distanceOk: Boolean,
        gapLarge: Boolean,
        abnormalPair: AbnormalPlane,
        spread: Float?
    ): Pair<String, String> {
        if (!distanceOk) return "distance_failure" to "DISTANCE_UNKNOWN"
        if (ui.centerHitValid == false && ui.distanceLockLiveSource == "NONE") {
            return "degraded" to "DISTANCE_CENTER_HIT_INVALID"
        }
        if (abnormalPair.abnormal) {
            val rr =
                when (abnormalPair.reason) {
                    "too_far_from_anchor" -> "DISTANCE_PLANE_INTERSECTION_TOO_FAR"
                    "small_projected_px_high_distance" -> "DISTANCE_PLANE_INTERSECTION_UNSTABLE"
                    "large_delta_vs_anchor" -> "DISTANCE_PLANE_INTERSECTION_TOO_FAR"
                    else -> "DISTANCE_PLANE_MISMATCH"
                }
            return "degraded" to rr
        }
        if (gapLarge) return "degraded" to "DISTANCE_LIVE_FINAL_GAP_LARGE"
        if (ui.distanceFinalFallbackUsed) return "degraded" to "DISTANCE_SOURCE_FALLBACK_USED"
        if (spread != null && spread > SPREAD_HIGH_M) return "degraded" to "DISTANCE_PLANE_SAMPLE_SPREAD_HIGH"
        if (ui.ballCupPlaneAngleDeg != null && ui.ballCupPlaneAngleDeg!! > 12f) {
            return "degraded" to "DISTANCE_PLANE_MISMATCH"
        }
        return "ok" to "NONE"
    }

    private fun classifySuspicious(
        ui: V31StateMachine.UiModel,
        extras: ActivityExtras,
        distanceOk: Boolean,
        primary: String?,
        gapLarge: Boolean,
        spread: Float?,
        cls: String
    ): Pair<Boolean, String?> {
        if (!distanceOk) return false to null
        val meanPrior =
            if (extras.priorFinalDistancesM.isNotEmpty()) {
                extras.priorFinalDistancesM.filter { it.isFinite() && it > 0f }.average().toFloat()
            } else {
                null
            }
        if (meanPrior != null && meanPrior > 0.5f && ui.distanceMeters > meanPrior * 1.45f) {
            return true to "vs_prior_session_mean"
        }
        if (primary == "PLANE_INTERSECTION" &&
            ui.multiRayProjectedCupPx != null &&
            ui.multiRayProjectedCupPx!! < TINY_PROJECTED_PX
        ) {
            return true to "plane_intersection_far_with_small_projected_px"
        }
        if (spread != null && spread > SPREAD_HIGH_M) return true to "high_sample_spread_cup"
        if (ui.multiRayCenterFallbackUsed == true) return true to "center_fallback_used"
        if ((ui.validSampleCount ?: 0) in 1..2) return true to "low_valid_sample_count"
        if (gapLarge) return true to "live_final_gap_large"
        if (cls == "ok" && ui.slopeExperimentalResult?.quality == "rejected" && ui.distanceMeters > 5f) {
            return true to "slope_rejected_but_long_distance"
        }
        return false to null
    }

    private fun distanceFailureStage(
        ui: V31StateMachine.UiModel,
        isFail: Boolean,
        cupFixSummary: String
    ): String? {
        if (!isFail) return null
        val c = ui.cupBlockedReason
        if (c != null) return "CUP_FIX"
        if (ui.failReasonCode == "FAIL_NO_VALID_HITS") return "SAMPLING"
        return "UNKNOWN"
    }

    fun emitLogcat(ui: V31StateMachine.UiModel, extras: ActivityExtras) {
        val s = feedbackSnapshot(ui, extras)
        Log.d(
            TAG,
            "DISTANCE_FINAL_SUMMARY distanceOk=${s.distanceOk} finalDistanceM=${fmt(s.finalDistanceM)} " +
                "liveRawM=${s.liveRawM?.let { fmt(it) } ?: "null"} liveAtFinishM=${s.liveAtFinishM?.let { fmt(it) } ?: "null"} " +
                "anchorDistanceM=${s.anchorDistanceM?.let { fmt(it) } ?: "null"} liveSource=${s.liveSource ?: "null"} " +
                "distanceClass=${s.finalDistanceClass} distanceReason=${s.finalDistanceReason} " +
                "centerHitValid=${s.centerHitValid} ballFixSummary=${s.ballFixSummary} cupFixSummary=${s.cupFixSummary} " +
                "ballFixRaw=${s.ballFixRaw} cupFixRaw=${s.cupFixRaw} " +
                "trackingState=${s.trackingState ?: "null"} projectedCupPx=${s.projectedCupPx?.let { "%.1f".format(Locale.US, it) } ?: "null"} " +
                "validSampleCount=${s.validSampleCount ?: "null"}"
        )
        Log.d(
            TAG,
            "DISTANCE_SOURCE primary=${s.distanceSourcePrimary ?: "null"} final=${s.distanceSourceFinal} " +
                "fallbackUsed=${s.distanceFallbackUsed} fallbackReason=${s.distanceFallbackReason ?: "NONE"}"
        )
        Log.d(
            TAG,
            "DISTANCE_PLANE_CHECK planeIntersection=${s.planeIntersectionDistanceM?.let { fmt(it) } ?: "null"} " +
                "anchor=${s.anchorDistanceM?.let { fmt(it) } ?: "null"} " +
                "delta=${s.planeIntersectionVsAnchorDeltaM?.let { fmt(it) } ?: "null"} " +
                "abnormal=${s.planeIntersectionAbnormal} reason=${s.planeIntersectionAbnormalReason ?: "none"}"
        )
        Log.d(
            TAG,
            "DISTANCE_LIVE_STABILITY range=${s.liveRangeM?.let { fmt(it) } ?: "null"} " +
                "std=${s.liveStdM?.let { fmt(it) } ?: "null"} stable=${s.liveStable} " +
                "outlierCount=${s.liveOutlierCount} reject=${s.liveRejectedReason ?: "NONE"}"
        )
        Log.d(
            TAG,
            "DISTANCE_FAIL_STAGE stage=${s.distanceFailureStage ?: "none"} cupFailReason=${s.cupFailReason ?: "none"} " +
                "blockedByCupFix=${s.distanceBlockedByCupFix} distanceReason=${s.finalDistanceReason}"
        )
        val spreadStr = s.sampleSpreadCupM?.let { fmt(it) } ?: "null"
        Log.d(
            TAG,
            "DISTANCE_INPUT_QUALITY projectedCupPx=${s.projectedCupPx?.let { "%.1f".format(Locale.US, it) } ?: "null"} " +
                "validSampleCount=${s.validSampleCount ?: "null"} spreadCupM=$spreadStr " +
                "multiRayPlan=${s.multiRayPlan ?: "null"} centerFallbackUsed=${s.centerFallbackUsed} " +
                "sigmaCurrentCm=${s.sigmaCurrentCm?.let { "%.2f".format(Locale.US, it) } ?: "null"} " +
                "sigmaThresholdCm=${s.sigmaThresholdCm?.let { "%.2f".format(Locale.US, it) } ?: "null"}"
        )
        Log.d(
            TAG,
            "DISTANCE_SUSPICIOUS suspicious=${s.distanceSuspicious} reason=${s.distanceSuspiciousReason ?: "none"} " +
                "liveFinalGapM=${s.liveFinalGapM?.let { fmt(it) } ?: "null"} gapLarge=${s.liveFinalGapLarge}"
        )
        Log.d(
            TAG,
            "DISTANCE_BIAS_DEBUG " +
                "liveAtFinishM=${s.liveAtFinishM?.let { fmt(it) } ?: "null"} " +
                "anchorDistanceM=${s.anchorDistanceM?.let { fmt(it) } ?: "null"} " +
                "cupMultiRayEstimatedDistanceM=${ui.multiRayEstimatedDistanceMeters?.let { fmt(it) } ?: "null"} " +
                "cupHitDistanceAvgM=${ui.hitDistanceAvgMeters?.let { fmt(it) } ?: "null"} " +
                "cameraToCupLiveM=${ui.bestHitDistanceFromCameraMeters?.let { fmt(it) } ?: "null"} " +
                "ballToCupXZLiveM=${s.liveHitPairXZM?.let { fmt(it) } ?: "null"} " +
                "ballToCupXZAnchorM=${s.anchorHorizontalM?.let { fmt(it) } ?: "null"} " +
                "cupEndAnchorPositionSource=${s.cupEndAnchorPositionSource ?: "null"} " +
                "projectedCupPx=${s.projectedCupPx?.let { String.format(Locale.US, "%.1f", it) } ?: "null"} " +
                "validSampleCount=${s.validSampleCount ?: "null"} centerFallbackUsed=${s.centerFallbackUsed}"
        )
        Log.d(
            TAG,
            "DISTANCE_GUARD_SUMMARY livePlaneM=${s.finalDistanceLivePlaneM?.let { fmt(it) } ?: "null"} " +
                "before=${s.finalDistanceSourceBeforeGuard ?: "null"} after=${s.finalDistanceSourceAfterGuard ?: "null"} " +
                "triggered=${s.finalDistanceGuardTriggered} reasons=${s.finalDistanceGuardReasons ?: "none"} " +
                "anchorInvalid=${s.finalDistanceAnchorInvalidReason ?: "none"} " +
                "deltaRatio=${s.planeIntersectionVsAnchorDeltaRatio?.let { String.format(Locale.US, "%.4f", it) } ?: "null"}"
        )
        Log.d(
            TAG,
            "ANCHOR_LIVE_ENDPOINT_COMPARE " +
                "ballDeltaXZ=${s.ballAnchorVsLiveHitXZDeltaM?.let { fmt(it) } ?: "null"} " +
                "cupDeltaXZ=${s.cupAnchorVsLiveHitXZDeltaM?.let { fmt(it) } ?: "null"} " +
                "liveHitPairXZ=${s.liveHitPairXZM?.let { fmt(it) } ?: "null"} " +
                "cupSnapXZ=${s.cupSnapXZDeltaM?.let { fmt(it) } ?: "null"} " +
                "endAnchorLowQualityFar=${s.endAnchorLowQualityFar}"
        )
        Log.d(
            TAG,
            "CUP_END_ANCHOR_COMMIT_JSON " +
                "anchorPosSrc=${s.cupEndAnchorPositionSource ?: "null"} " +
                "anchorVsLiveWorldXZ_m=${s.cupEndAnchorVsLiveWorldXZM?.let { fmt(it) } ?: "null"} " +
                "gateDeltaAtCommit_m=${s.cupCandidateVsLiveHitXZDeltaMAtCommit?.let { fmt(it) } ?: "null"} " +
                "thr_m=${s.cupEndAnchorCommitGateThresholdM?.let { fmt(it) } ?: "null"} " +
                "strictFar=${s.cupEndAnchorCommitStrictFar} " +
                "bypassMaxRetries=${s.cupEndAnchorGateBypassedMaxRetries} " +
                "bypassSession=${s.cupEndAnchorCommitBypassSession} " +
                "liveFrameNs=${s.cupLiveWorldFrameTimestampNs?.toString() ?: "null"} " +
                "alignGateSameFrame=${s.cupLiveAlignAndGateSameFrame}"
        )
        Log.d(
            TAG,
            "MEASURE_ZOOM_GATE " +
                "measureState=${s.measureState ?: "null"} " +
                "transformVersion=${s.measurementTransformVersion?.toString() ?: "null"} " +
                "confirmActiveTransformVersion=${s.confirmActiveTransformVersion?.toString() ?: "null"} " +
                "detectorArDeltaMs=${s.detectorArFrameDeltaMs?.let { String.format(Locale.US, "%.2f", it) } ?: "null"} " +
                "confirmRejectedReason=${s.confirmRejectedReason ?: "none"} " +
                "projectedCupPx=${s.projectedCupPx?.let { String.format(Locale.US, "%.1f", it) } ?: "null"}"
        )
        Log.d(
            TAG,
            "MEASURE_AUTO_ZOOM " +
                "requested=${s.autoZoomRequested} " +
                "targetRatio=${s.autoZoomTargetRatio?.let { String.format(Locale.US, "%.1f", it) } ?: "null"} " +
                "reason=${s.autoZoomReason ?: "none"}"
        )
    }

    fun appendDistanceFeedbackJson(
        sb: StringBuilder,
        snap: DistanceFeedbackSnapshot?,
        esc: (String) -> String
    ) {
        if (snap == null) {
            sb.append("null")
            return
        }
        fun n(f: Float?): String =
            when {
                f == null -> "null"
                !f.isFinite() -> "null"
                else -> String.format(Locale.US, "%.6f", f)
            }
        fun nb(b: Boolean?) = when (b) {
            null -> "null"
            true -> "true"
            else -> "false"
        }
        fun fa(a: FloatArray?): String =
            if (a == null || a.isEmpty()) {
                "null"
            } else {
                buildString {
                    append('[')
                    for (i in a.indices) {
                        if (i > 0) append(',')
                        append(String.format(Locale.US, "%.6f", a[i]))
                    }
                    append(']')
                }
            }
        sb.append('{')
        sb.append("\"distanceOk\":").append(snap.distanceOk).append(',')
        sb.append("\"finalDistanceM\":").append(n(snap.finalDistanceM)).append(',')
        sb.append("\"liveRawM\":").append(n(snap.liveRawM)).append(',')
        sb.append("\"liveAtFinishM\":").append(n(snap.liveAtFinishM)).append(',')
        sb.append("\"anchorDistanceM\":").append(n(snap.anchorDistanceM)).append(',')
        sb.append("\"liveSource\":").append(jsonStr(snap.liveSource, esc)).append(',')
        sb.append("\"distanceSourcePrimary\":").append(jsonStr(snap.distanceSourcePrimary, esc)).append(',')
        sb.append("\"distanceSourceFinal\":").append(jsonStr(snap.distanceSourceFinal, esc)).append(',')
        sb.append("\"distanceFallbackUsed\":").append(snap.distanceFallbackUsed).append(',')
        sb.append("\"distanceFallbackReason\":").append(jsonStr(snap.distanceFallbackReason, esc)).append(',')
        sb.append("\"finalDistanceClass\":\"").append(esc(snap.finalDistanceClass)).append("\",")
        sb.append("\"finalDistanceReason\":\"").append(esc(snap.finalDistanceReason)).append("\",")
        sb.append("\"centerHitValid\":").append(nb(snap.centerHitValid)).append(',')
        sb.append("\"ballFixSummary\":\"").append(esc(snap.ballFixSummary)).append("\",")
        sb.append("\"cupFixSummary\":\"").append(esc(snap.cupFixSummary)).append("\",")
        sb.append("\"ballFixRaw\":\"").append(esc(snap.ballFixRaw)).append("\",")
        sb.append("\"cupFixRaw\":\"").append(esc(snap.cupFixRaw)).append("\",")
        sb.append("\"ballAnchorUsedState\":\"").append(esc(snap.ballAnchorUsedState)).append("\",")
        sb.append("\"cupAnchorUsedState\":\"").append(esc(snap.cupAnchorUsedState)).append("\",")
        sb.append("\"ballAnchorWorld\":").append(fa(snap.ballAnchorWorld)).append(',')
        sb.append("\"cupAnchorWorld\":").append(fa(snap.cupAnchorWorld)).append(',')
        sb.append("\"anchorHorizontalM\":").append(n(snap.anchorHorizontalM)).append(',')
        sb.append("\"anchor3dM\":").append(n(snap.anchor3dM)).append(',')
        sb.append("\"startAnchorCreatedAtMs\":").append(snap.startAnchorCreatedAtMs?.toString() ?: "null").append(',')
        sb.append("\"endAnchorCreatedAtMs\":").append(snap.endAnchorCreatedAtMs?.toString() ?: "null").append(',')
        sb.append("\"anchorCreatedAtMs\":").append(snap.anchorCreatedAtMs?.toString() ?: "null").append(',')
        sb.append("\"anchorReused\":").append(nb(snap.anchorReused)).append(',')
        sb.append("\"startAnchorPose\":").append(fa(snap.startAnchorPose)).append(',')
        sb.append("\"endAnchorPose\":").append(fa(snap.endAnchorPose)).append(',')
        sb.append("\"trackingState\":").append(jsonStr(snap.trackingState, esc)).append(',')
        sb.append("\"projectedCupPx\":").append(n(snap.projectedCupPx)).append(',')
        sb.append("\"validSampleCount\":").append(snap.validSampleCount ?: "null").append(',')
        sb.append("\"planeIntersectionDistanceM\":").append(n(snap.planeIntersectionDistanceM)).append(',')
        sb.append("\"planeIntersectionVsAnchorDeltaM\":").append(n(snap.planeIntersectionVsAnchorDeltaM)).append(',')
        sb.append("\"planeIntersectionAbnormal\":").append(snap.planeIntersectionAbnormal).append(',')
        sb.append("\"planeIntersectionAbnormalReason\":").append(jsonStr(snap.planeIntersectionAbnormalReason, esc)).append(',')
        sb.append("\"liveRangeM\":").append(n(snap.liveRangeM)).append(',')
        sb.append("\"liveStdM\":").append(n(snap.liveStdM)).append(',')
        sb.append("\"liveStable\":").append(nb(snap.liveStable)).append(',')
        sb.append("\"liveOutlierCount\":").append(snap.liveOutlierCount).append(',')
        sb.append("\"liveRejectedReason\":").append(jsonStr(snap.liveRejectedReason, esc)).append(',')
        sb.append("\"distanceBlockedByCupFix\":").append(snap.distanceBlockedByCupFix).append(',')
        sb.append("\"cupFailReason\":").append(jsonStr(snap.cupFailReason, esc)).append(',')
        sb.append("\"distanceFailureStage\":").append(jsonStr(snap.distanceFailureStage, esc)).append(',')
        sb.append("\"sampleSpreadCupM\":").append(n(snap.sampleSpreadCupM)).append(',')
        sb.append("\"multiRayPlan\":").append(jsonStr(snap.multiRayPlan, esc)).append(',')
        sb.append("\"centerFallbackUsed\":").append(nb(snap.centerFallbackUsed)).append(',')
        sb.append("\"sigmaCurrentCm\":").append(n(snap.sigmaCurrentCm)).append(',')
        sb.append("\"sigmaThresholdCm\":").append(n(snap.sigmaThresholdCm)).append(',')
        sb.append("\"liveFinalGapM\":").append(n(snap.liveFinalGapM)).append(',')
        sb.append("\"liveFinalGapLarge\":").append(snap.liveFinalGapLarge).append(',')
        sb.append("\"distanceSuspicious\":").append(snap.distanceSuspicious).append(',')
        sb.append("\"distanceSuspiciousReason\":").append(jsonStr(snap.distanceSuspiciousReason, esc)).append(',')
        sb.append("\"finalDistanceLivePlaneM\":").append(n(snap.finalDistanceLivePlaneM)).append(',')
        sb.append("\"finalDistanceSourceBeforeGuard\":").append(jsonStr(snap.finalDistanceSourceBeforeGuard, esc)).append(',')
        sb.append("\"finalDistanceSourceAfterGuard\":").append(jsonStr(snap.finalDistanceSourceAfterGuard, esc)).append(',')
        sb.append("\"finalDistanceGuardTriggered\":").append(snap.finalDistanceGuardTriggered).append(',')
        sb.append("\"finalDistanceGuardReasons\":").append(jsonStr(snap.finalDistanceGuardReasons, esc)).append(',')
        sb.append("\"finalDistanceAnchorInvalidReason\":").append(jsonStr(snap.finalDistanceAnchorInvalidReason, esc)).append(',')
        sb.append("\"planeIntersectionVsAnchorDeltaRatio\":").append(n(snap.planeIntersectionVsAnchorDeltaRatio)).append(',')
        sb.append("\"ballLiveHitWorldAtFinish\":").append(fa(snap.ballLiveHitWorldAtFinish)).append(',')
        sb.append("\"cupLiveHitWorldAtFinish\":").append(fa(snap.cupLiveHitWorldAtFinish)).append(',')
        sb.append("\"cupAnchorHitWorldBeforeSnap\":").append(fa(snap.cupAnchorHitWorldBeforeSnap)).append(',')
        sb.append("\"cupAnchorPoseWorldAfterSnap\":").append(fa(snap.cupAnchorPoseWorldAfterSnap)).append(',')
        sb.append("\"cupAnchorCommitTrackableType\":").append(jsonStr(snap.cupAnchorCommitTrackableType, esc)).append(',')
        sb.append("\"cupAnchorCommitTrackableId\":").append(jsonStr(snap.cupAnchorCommitTrackableId, esc)).append(',')
        sb.append("\"ballAnchorVsLiveHitXZDeltaM\":").append(n(snap.ballAnchorVsLiveHitXZDeltaM)).append(',')
        sb.append("\"cupAnchorVsLiveHitXZDeltaM\":").append(n(snap.cupAnchorVsLiveHitXZDeltaM)).append(',')
        sb.append("\"liveHitPairXZM\":").append(n(snap.liveHitPairXZM)).append(',')
        sb.append("\"cupSnapXZDeltaM\":").append(n(snap.cupSnapXZDeltaM)).append(',')
        sb.append("\"endAnchorLowQualityFar\":").append(snap.endAnchorLowQualityFar).append(',')
        sb.append("\"cupCandidateVsLiveHitXZDeltaMAtCommit\":").append(n(snap.cupCandidateVsLiveHitXZDeltaMAtCommit)).append(',')
        sb.append("\"cupEndAnchorCommitStrictFar\":").append(nb(snap.cupEndAnchorCommitStrictFar)).append(',')
        sb.append("\"cupEndAnchorCommitGateThresholdM\":").append(n(snap.cupEndAnchorCommitGateThresholdM)).append(',')
        sb.append("\"cupEndAnchorGateBypassedMaxRetries\":").append(nb(snap.cupEndAnchorGateBypassedMaxRetries)).append(',')
        sb.append("\"cupEndAnchorCommitBypassSession\":").append(nb(snap.cupEndAnchorCommitBypassSession)).append(',')
        sb.append("\"cupLiveWorldFrameTimestampNs\":").append(snap.cupLiveWorldFrameTimestampNs?.toString() ?: "null").append(',')
        sb.append("\"cupLiveAlignAndGateSameFrame\":").append(snap.cupLiveAlignAndGateSameFrame).append(',')
        sb.append("\"cupEndAnchorPositionSource\":").append(jsonStr(snap.cupEndAnchorPositionSource, esc)).append(',')
        sb.append("\"cupEndAnchorVsLiveWorldXZM\":").append(n(snap.cupEndAnchorVsLiveWorldXZM)).append(',')
        sb.append("\"distanceQualityTier\":").append(jsonStr(snap.distanceQualityTier, esc)).append(',')
        sb.append("\"cupConfirmDecision\":").append(jsonStr(snap.cupConfirmDecision, esc)).append(',')
        sb.append("\"cupConfirmSource\":").append(jsonStr(snap.cupConfirmSource, esc)).append(',')
        sb.append("\"statisticalConfirmSupportCount\":").append(snap.statisticalConfirmSupportCount ?: "null").append(',')
        sb.append("\"statisticalConfirmSpreadXZM\":").append(n(snap.statisticalConfirmSpreadXZM)).append(',')
        sb.append("\"statisticalConfirmStdXZM\":").append(n(snap.statisticalConfirmStdXZM)).append(',')
        sb.append("\"statisticalConfirmLowPxSalvage\":").append(nb(snap.statisticalConfirmLowPxSalvage)).append(',')
        sb.append("\"captureBurstUsed\":").append(snap.captureBurstUsed).append(',')
        sb.append("\"captureBurstComputed\":").append(snap.captureBurstComputed).append(',')
        sb.append("\"captureBurstAcceptedFrames\":").append(snap.captureBurstAcceptedFrames ?: "null").append(',')
        sb.append("\"captureBurstRejectedFrames\":").append(snap.captureBurstRejectedFrames ?: "null").append(',')
        sb.append("\"captureConfirmSpreadXZM\":").append(n(snap.captureConfirmSpreadXZM)).append(',')
        sb.append("\"captureConfirmStdXZM\":").append(n(snap.captureConfirmStdXZM)).append(',')
        sb.append("\"cupConfirmReason\":").append(jsonStr(snap.cupConfirmReason, esc)).append(',')
        sb.append("\"finalCupConfirmSource\":").append(jsonStr(snap.finalCupConfirmSource, esc)).append(',')
        sb.append("\"measureState\":").append(jsonStr(snap.measureState, esc)).append(',')
        sb.append("\"measurementTransformVersion\":").append(snap.measurementTransformVersion ?: "null").append(',')
        sb.append("\"confirmActiveTransformVersion\":").append(snap.confirmActiveTransformVersion ?: "null").append(',')
        sb.append("\"detectorArFrameDeltaMs\":").append(n(snap.detectorArFrameDeltaMs)).append(',')
        sb.append("\"confirmRejectedReason\":").append(jsonStr(snap.confirmRejectedReason, esc)).append(',')
        sb.append("\"autoZoomRequested\":").append(snap.autoZoomRequested).append(',')
        sb.append("\"autoZoomTargetRatio\":").append(n(snap.autoZoomTargetRatio)).append(',')
        sb.append("\"autoZoomReason\":").append(jsonStr(snap.autoZoomReason, esc)).append(',')
        sb.append("\"debugBannerShort\":\"").append(esc(snap.debugBannerShort)).append("\"")
        sb.append('}')
    }

    /**
     * [buildMeasurementLogJson]의 `finalDistanceGuard`와 동일 스키마 — 피드백 JSON·파서 정합용.
     * RESULT가 아니거나 가드 스냅샷이 없으면 `null`.
     */
    fun appendFinalDistanceGuardJson(
        sb: StringBuilder,
        snap: DistanceFeedbackSnapshot?,
        esc: (String) -> String
    ) {
        if (snap == null || snap.finalDistanceSourceBeforeGuard == null) {
            sb.append("null")
            return
        }
        fun n(f: Float?): String =
            when {
                f == null -> "null"
                !f.isFinite() -> "null"
                else -> String.format(Locale.US, "%.6f", f)
            }
        sb.append('{')
        sb.append("\"anchorDistanceM\":").append(n(snap.anchorDistanceM)).append(',')
        sb.append("\"livePlaneDistanceM\":").append(n(snap.finalDistanceLivePlaneM)).append(',')
        sb.append("\"planeIntersectionVsAnchorDeltaM\":").append(n(snap.planeIntersectionVsAnchorDeltaM)).append(',')
        sb.append("\"planeIntersectionVsAnchorDeltaRatio\":").append(n(snap.planeIntersectionVsAnchorDeltaRatio)).append(',')
        sb.append("\"projectedCupPx\":").append(n(snap.projectedCupPx)).append(',')
        sb.append("\"finalDistanceSourceBeforeGuard\":\"").append(esc(snap.finalDistanceSourceBeforeGuard ?: "")).append("\",")
        sb.append("\"finalDistanceSourceAfterGuard\":\"").append(esc(snap.finalDistanceSourceAfterGuard ?: "")).append("\",")
        sb.append("\"finalDistanceGuardTriggered\":").append(snap.finalDistanceGuardTriggered).append(',')
        sb.append("\"finalDistanceGuardReasons\":\"").append(esc(snap.finalDistanceGuardReasons ?: "")).append("\",")
        sb.append("\"finalDistanceAnchorInvalidReason\":")
        if (snap.finalDistanceAnchorInvalidReason == null) sb.append("null") else sb.append("\"").append(esc(snap.finalDistanceAnchorInvalidReason)).append("\"")
        sb.append(',')
        sb.append("\"guardConfigMaxDeltaM\":").append(FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_M).append(',')
        sb.append("\"guardConfigMaxRatio\":").append(FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_RATIO).append(',')
        sb.append("\"guardConfigMinProjectedCupPx\":").append(FinalDistanceGuardConfig.MIN_PROJECTED_CUP_PX)
        sb.append('}')
    }

    private fun jsonStr(s: String?, esc: (String) -> String): String =
        if (s == null) "null" else "\"${esc(s)}\""

    private fun fmt(f: Float): String = String.format(Locale.US, "%.3f", f)

    private const val GAP_LARGE_M = 0.30f
    private const val ANCHOR_RATIO_TOO_HIGH = 1.35f
    private const val ANCHOR_ABS_TOO_HIGH_M = 1.2f
    private const val ANCHOR_DELTA_SUSPICIOUS_M = 1.0f
    private const val TINY_PROJECTED_PX = 22f
    private const val SPREAD_HIGH_M = 2.5f
}
