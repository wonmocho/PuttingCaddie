package com.wmcho.puttingcaddie

import com.wmcho.puttingcaddie.slope.SlopeInputResult
import java.util.Locale

/**
 * 거리·경사 **최종화 정책 SSOT** (재발 방지용 구조).
 * - SharedP3가 제품 임계를 통과하면 Phase1으로 덮어쓰지 않음
 * - 거리 하드 가드 실패 시 측정값은 로그에 남지만 `distanceOk`는 false (재촬영)
 * - experimental은 shadow-only 플래그로 표시 경로에서 제외 가능
 */
object MeasurementFinalizationPolicy {

    /** true: experimental은 로그·진단만, 상하 표시 후보에서 제외 */
    const val EXPERIMENTAL_SLOPE_SHADOW_ONLY: Boolean = true

    enum class MetricStatus {
        VALID,
        UNAVAILABLE,
        REJECTED
    }

    enum class SlopeSource {
        SHARED_P3,
        PHASE1,
        EXPERIMENTAL
    }

    enum class TargetMode {
        /** 기본: 컵 타깃, cup 멀티레이 Y 오프셋 허용 */
        CUP_STANDARD,

        /** 바닥 볼: cup Y 오프셋 금지·가드 정책 분기 */
        BALL_ON_FLOOR
    }

    /** 로그·JSON에서 상하(forward) vs 좌우(lateral) 경사 정책 결과를 분리 집계할 때 사용. */
    enum class SlopeAxis {
        FORWARD,
        LATERAL
    }

    private data class DistanceGuardThresholds(
        val minProjectedCupPx: Float,
        val maxXzDeltaMeters: Float,
        val maxCupSpreadMeters: Float
    )

    private fun thresholdsForDistance(distanceMeters: Double): DistanceGuardThresholds =
        when {
            distanceMeters >= 6.0 ->
                DistanceGuardThresholds(
                    minProjectedCupPx = 30f,
                    maxXzDeltaMeters = 0.35f,
                    maxCupSpreadMeters = 0.40f
                )
            distanceMeters >= 3.0 ->
                DistanceGuardThresholds(
                    minProjectedCupPx = 40f,
                    maxXzDeltaMeters = 0.45f,
                    maxCupSpreadMeters = 0.50f
                )
            else ->
                DistanceGuardThresholds(
                    minProjectedCupPx = 45f,
                    maxXzDeltaMeters = 0.50f,
                    maxCupSpreadMeters = 0.60f
                )
        }

    /**
     * 한 축(상하 또는 좌우)의 경사 최종값.
     * [forwardSlope]에는 상하 forward%, [lateralSlope]에는 좌우 lateral%를
     * 동일 필드 [forwardPct]에 담는다(타입 재사용).
     */
    data class SlopeDecision(
        val status: MetricStatus,
        val source: SlopeSource?,
        val forwardPct: Float?,
        val qualityLabel: String?,
        val reason: String?
    )

    data class DistanceDecision(
        val status: MetricStatus,
        val valueMeters: Float?,
        val reason: String?
    )

    data class FinalMeasurementResult(
        val distance: DistanceDecision,
        val forwardSlope: SlopeDecision,
        val lateralSlope: SlopeDecision
    )

    @Volatile
    var sessionTargetMode: TargetMode = TargetMode.CUP_STANDARD
        private set

    @Volatile
    private var sessionLocked: Boolean = false

    fun beginSession(requested: TargetMode) {
        if (!sessionLocked) {
            sessionTargetMode = requested
            sessionLocked = true
            return
        }
        check(sessionTargetMode == requested) { "TARGET_MODE_CHANGED_DURING_SESSION" }
    }

    fun endSession() {
        sessionLocked = false
    }

    /** SharedP3 “제품 1순위” — 로그 품질·수치 + (가능하면) slope 객체 valid. */
    fun sharedP3PrimaryUsable(ui: V31StateMachine.UiModel): Boolean {
        val log = ui.sharedP3Log ?: return false
        if (!log.finalBlockedReason.isNullOrBlank()) return false
        if (log.quality !in setOf("GOOD", "DEGRADED")) return false
        val ny = log.normalYFinal ?: return false
        val res = log.finalResidualM ?: return false
        if (ny < 0.94f || res > 0.03f) return false
        val fwd = log.finalForwardPct ?: log.finalForwardPctRaw ?: ui.experimentalSharedSlope?.forwardPct
        if (fwd == null || !fwd.isFinite()) return false
        val sh = ui.experimentalSharedSlope
        if (sh != null) {
            if (!sh.blockedReason.isNullOrBlank()) return false
            if (sh.quality != "valid" || sh.forwardPct == null) return false
        }
        return true
    }

    /** experimental drift/spread reject 시 좁은 SharedP3 로그 폴백 ([SlopeFieldTestLog]와 동일 임계). */
    fun sharedP3LogUsableForExperimentalFallback(log: SharedP3LogPayload?, distanceM: Float): Boolean {
        if (log == null) return false
        if (!log.finalBlockedReason.isNullOrBlank()) return false
        if (log.quality !in setOf("GOOD", "DEGRADED")) return false
        val n = log.finalSampleCount ?: return false
        val r = log.finalResidualM ?: return false
        val ny = log.normalYFinal ?: return false
        val far = distanceM >= 9f
        val minN = if (far) 16 else 18
        val maxR = if (far) 0.022f else 0.015f
        val minNy = if (far) 0.98f else 0.995f
        return n >= minN && r <= maxR && ny >= minNy
    }

    fun experimentalRejectEligibleForSharedP3(rr: String?): Boolean {
        if (rr.isNullOrBlank()) return false
        if (rr == "plane_drift_too_large" || rr == "cup_sample_spread_too_large") return true
        if (rr.contains("drift", ignoreCase = true)) return true
        if (rr.contains("spread", ignoreCase = true)) return true
        return false
    }

    fun forwardPctFromSharedP3Primary(ui: V31StateMachine.UiModel): Float? {
        val sh = ui.experimentalSharedSlope
        if (sh?.forwardPct != null && sh.forwardPct.isFinite() && sh.blockedReason.isNullOrBlank()) {
            return sh.forwardPct
        }
        val log = ui.sharedP3Log
        val f = log?.finalForwardPct ?: log?.finalForwardPctRaw
        return f?.takeIf { it.isFinite() }
    }

    fun lateralPctFromSharedP3Primary(ui: V31StateMachine.UiModel): Float? {
        val sh = ui.experimentalSharedSlope
        if (sh?.lateralPct != null && sh.lateralPct.isFinite() && sh.blockedReason.isNullOrBlank()) {
            return sh.lateralPct
        }
        val log = ui.sharedP3Log
        val f = log?.finalLateralPct ?: log?.finalLateralPctRaw
        return f?.takeIf { it.isFinite() }
    }

    /**
     * 동일 물리 바닥 추정(트랙 병합 금지 — 우선순위만 조정): samePlane==false 이고 평면 각이 매우 작으면 Phase1 폴백을 끈다.
     */
    fun samePhysicalFloorHeuristicDisablesPhase1(ui: V31StateMachine.UiModel): Boolean {
        if (ui.ballCupSamePlane == true) return false
        val ang = ui.ballCupPlaneAngleDeg ?: return false
        return ang.isFinite() && ang < 4f
    }

    /**
     * 하드 가드: 실패 시 거리는 **REJECTED**(재촬영). 경사 로직과 독립.
     * [distanceMeters]가 유효하면 [thresholdsForDistance]로 px/XZ/spread 임계를 구간별 적용.
     */
    fun distanceHardGuardsPass(
        trackingState: String?,
        targetMode: TargetMode,
        centerYOffsetApplied: Boolean?,
        projectedCupPx: Float?,
        candidateVsLiveHitXzDeltaM: Float?,
        sampleSpreadCupM: Float?,
        distanceMeters: Float? = null
    ): Pair<Boolean, String?> {
        if (trackingState != "TRACKING") {
            return false to "tracking_not_ready"
        }
        if (targetMode == TargetMode.BALL_ON_FLOOR && centerYOffsetApplied == true) {
            return false to "ball_mode_center_y_offset_forbidden"
        }
        val dM =
            distanceMeters
                ?.takeIf { it.isFinite() && it > 0f }
                ?.toDouble()
                ?: 0.0
        val t = thresholdsForDistance(dM)
        val px = projectedCupPx
        if (px == null || !px.isFinite() || px < t.minProjectedCupPx) {
            return false to "projected_cup_px_below_guard"
        }
        val xz = candidateVsLiveHitXzDeltaM
        if (xz != null && xz.isFinite() && xz > t.maxXzDeltaMeters) {
            return false to "cup_candidate_vs_live_xz_exceeds"
        }
        val sp = sampleSpreadCupM
        if (sp != null && sp.isFinite() && sp > t.maxCupSpreadMeters) {
            return false to "sample_spread_cup_exceeds_guard"
        }
        return true to null
    }

    fun distanceHardGuardsPass(ui: V31StateMachine.UiModel): Pair<Boolean, String?> =
        distanceHardGuardsPass(
            trackingState = ui.trackingState,
            targetMode = sessionTargetMode,
            centerYOffsetApplied = ui.centerYOffsetApplied,
            projectedCupPx = ui.multiRayProjectedCupPx,
            candidateVsLiveHitXzDeltaM = ui.cupCandidateVsLiveHitXZDeltaMAtCommit,
            sampleSpreadCupM = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM,
            distanceMeters = ui.distanceMeters.takeIf { it.isFinite() && it > 0f }
        )

    fun chooseFinalForwardSlope(ui: V31StateMachine.UiModel): SlopeDecision {
        val experimental: SlopeInputResult? = ui.slopeExperimentalResult
        val phase1 = ui.slopeDebugInfo
        val distanceM = ui.distanceMeters.takeIf { it.isFinite() && it > 0f } ?: 0f
        val expUsable =
            !EXPERIMENTAL_SLOPE_SHADOW_ONLY &&
                experimental != null &&
                experimental.quality == "valid" &&
                experimental.forwardPct != null &&
                experimental.forwardPct.isFinite()

        if (sharedP3PrimaryUsable(ui)) {
            val v = forwardPctFromSharedP3Primary(ui)
            return if (v != null) {
                SlopeDecision(
                    status = MetricStatus.VALID,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = v,
                    qualityLabel = "HIGH",
                    reason = null
                )
            } else {
                SlopeDecision(
                    status = MetricStatus.UNAVAILABLE,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = null,
                    qualityLabel = null,
                    reason = "SHARED_P3_MARKED_USABLE_BUT_NO_FORWARD"
                )
            }
        }

        if (expUsable) {
            return SlopeDecision(
                status = MetricStatus.VALID,
                source = SlopeSource.EXPERIMENTAL,
                forwardPct = experimental!!.forwardPct,
                qualityLabel = "EXPERIMENTAL",
                reason = null
            )
        }

        val log = ui.sharedP3Log
        val expRejected = experimental?.quality == "rejected"
        val useSharedStrictFallback =
            sharedP3LogUsableForExperimentalFallback(log, distanceM) &&
                expRejected &&
                experimentalRejectEligibleForSharedP3(experimental?.rejectReason)
        if (useSharedStrictFallback) {
            val v =
                log!!.finalForwardPct
                    ?: log.finalForwardPctRaw
                    ?: ui.experimentalSharedSlope?.forwardPct?.takeIf {
                        ui.experimentalSharedSlope?.blockedReason.isNullOrBlank()
                    }
            if (v != null && v.isFinite()) {
                return SlopeDecision(
                    status = MetricStatus.VALID,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = v,
                    qualityLabel = "MEDIUM",
                    reason = null
                )
            }
        }

        val p1f = phase1?.forwardPct?.takeIf { it.isFinite() }
        if (p1f != null &&
            phase1?.quality == "valid" &&
            !samePhysicalFloorHeuristicDisablesPhase1(ui) &&
            ui.ballCupSamePlane != true
        ) {
            return SlopeDecision(
                status = MetricStatus.VALID,
                source = SlopeSource.PHASE1,
                forwardPct = p1f,
                qualityLabel = "LOW",
                reason = null
            )
        }

        return SlopeDecision(
            status = MetricStatus.UNAVAILABLE,
            source = null,
            forwardPct = null,
            qualityLabel = null,
            reason = "NO_USABLE_FORWARD_SOURCE"
        )
    }

    /** [chooseFinalForwardSlope]와 동일 우선순위·가드, 축만 lateral로 분리. */
    fun chooseFinalLateralSlope(ui: V31StateMachine.UiModel): SlopeDecision {
        val experimental: SlopeInputResult? = ui.slopeExperimentalResult
        val phase1 = ui.slopeDebugInfo
        val distanceM = ui.distanceMeters.takeIf { it.isFinite() && it > 0f } ?: 0f
        val expUsable =
            !EXPERIMENTAL_SLOPE_SHADOW_ONLY &&
                experimental != null &&
                experimental.quality == "valid" &&
                experimental.lateralPct != null &&
                experimental.lateralPct.isFinite()

        if (sharedP3PrimaryUsable(ui)) {
            val v = lateralPctFromSharedP3Primary(ui)
            return if (v != null) {
                SlopeDecision(
                    status = MetricStatus.VALID,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = v,
                    qualityLabel = "HIGH",
                    reason = null
                )
            } else {
                SlopeDecision(
                    status = MetricStatus.UNAVAILABLE,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = null,
                    qualityLabel = null,
                    reason = "SHARED_P3_MARKED_USABLE_BUT_NO_LATERAL"
                )
            }
        }

        if (expUsable) {
            return SlopeDecision(
                status = MetricStatus.VALID,
                source = SlopeSource.EXPERIMENTAL,
                forwardPct = experimental!!.lateralPct,
                qualityLabel = "EXPERIMENTAL",
                reason = null
            )
        }

        val log = ui.sharedP3Log
        val expRejected = experimental?.quality == "rejected"
        val useSharedStrictFallback =
            sharedP3LogUsableForExperimentalFallback(log, distanceM) &&
                expRejected &&
                experimentalRejectEligibleForSharedP3(experimental?.rejectReason)
        if (useSharedStrictFallback) {
            val v =
                log!!.finalLateralPct
                    ?: log.finalLateralPctRaw
                    ?: ui.experimentalSharedSlope?.lateralPct?.takeIf {
                        ui.experimentalSharedSlope?.blockedReason.isNullOrBlank()
                    }
            if (v != null && v.isFinite()) {
                return SlopeDecision(
                    status = MetricStatus.VALID,
                    source = SlopeSource.SHARED_P3,
                    forwardPct = v,
                    qualityLabel = "MEDIUM",
                    reason = null
                )
            }
        }

        val p1l = phase1?.lateralPct?.takeIf { it.isFinite() }
        if (p1l != null &&
            phase1?.quality == "valid" &&
            !samePhysicalFloorHeuristicDisablesPhase1(ui) &&
            ui.ballCupSamePlane != true
        ) {
            return SlopeDecision(
                status = MetricStatus.VALID,
                source = SlopeSource.PHASE1,
                forwardPct = p1l,
                qualityLabel = "LOW",
                reason = null
            )
        }

        return SlopeDecision(
            status = MetricStatus.UNAVAILABLE,
            source = null,
            forwardPct = null,
            qualityLabel = null,
            reason = "NO_USABLE_LATERAL_SOURCE"
        )
    }

    fun finalMeasurementFromUi(ui: V31StateMachine.UiModel): FinalMeasurementResult =
        FinalMeasurementResult(
            distance = distanceDecisionFromUi(ui),
            forwardSlope = chooseFinalForwardSlope(ui),
            lateralSlope = chooseFinalLateralSlope(ui)
        )

    /** Logcat 한 줄/축 — QA에서 forward vs lateral UNAVAILABLE·REJECT 분리 집계용. */
    fun logFinalSlopeAxes(final: FinalMeasurementResult) {
        logSlopeAxisDecision(SlopeAxis.FORWARD, final.forwardSlope)
        logSlopeAxisDecision(SlopeAxis.LATERAL, final.lateralSlope)
    }

    private fun logSlopeAxisDecision(axis: SlopeAxis, d: SlopeDecision) {
        android.util.Log.d(
            "MeasurementPolicy",
            "SLOPE_AXIS_DECISION axis=${axis.name} status=${d.status.name} source=${d.source?.name ?: "null"} " +
                "reason=${d.reason ?: "null"} qualityLabel=${d.qualityLabel ?: "null"}"
        )
    }

    /**
     * [finalMeasurementFromUi] 결과를 JSON 객체 한 덩어리로 직렬화 (키 이름은 필드 테스트·파서 정합용).
     * 호출부에서 `"finalMeasurementSsot":` 접두 후 본 함수만 호출하면 된다.
     */
    fun appendFinalMeasurementSsotJson(
        sb: StringBuilder,
        final: FinalMeasurementResult,
        escJson: (String) -> String
    ) {
        fun q(s: String?): String =
            if (s == null) {
                "null"
            } else {
                "\"" + escJson(s) + "\""
            }
        fun n(f: Float?): String =
            when {
                f == null -> "null"
                !f.isFinite() -> "null"
                else -> String.format(Locale.US, "%.6f", f)
            }
        fun src(s: SlopeSource?): String =
            if (s == null) {
                "null"
            } else {
                "\"" + s.name + "\""
            }
        sb.append('{')
        sb.append("\"distanceStatus\":\"").append(final.distance.status.name).append("\",")
        sb.append("\"distanceMeters\":").append(n(final.distance.valueMeters)).append(',')
        sb.append("\"distanceReason\":").append(q(final.distance.reason)).append(',')
        sb.append("\"forwardStatus\":\"").append(final.forwardSlope.status.name).append("\",")
        sb.append("\"forwardPct\":").append(n(final.forwardSlope.forwardPct)).append(',')
        sb.append("\"forwardReason\":").append(q(final.forwardSlope.reason)).append(',')
        sb.append("\"forwardSource\":").append(src(final.forwardSlope.source)).append(',')
        sb.append("\"lateralStatus\":\"").append(final.lateralSlope.status.name).append("\",")
        sb.append("\"lateralPct\":").append(n(final.lateralSlope.forwardPct)).append(',')
        sb.append("\"lateralReason\":").append(q(final.lateralSlope.reason)).append(',')
        sb.append("\"lateralSource\":").append(src(final.lateralSlope.source))
        sb.append('}')
    }

    fun distanceDecisionFromUi(ui: V31StateMachine.UiModel): DistanceDecision {
        val d = ui.distanceMeters
        val rawOk = d.isFinite() && d > 0f
        if (!rawOk) {
            return DistanceDecision(
                status = MetricStatus.UNAVAILABLE,
                valueMeters = null,
                reason = "NO_DISTANCE_VALUE"
            )
        }
        val (pass, reason) = distanceHardGuardsPass(ui)
        if (!pass) {
            return DistanceDecision(
                status = MetricStatus.REJECTED,
                valueMeters = null,
                reason = reason ?: "RETAKE_REQUIRED"
            )
        }
        return DistanceDecision(
            status = MetricStatus.VALID,
            valueMeters = d,
            reason = null
        )
    }
}
