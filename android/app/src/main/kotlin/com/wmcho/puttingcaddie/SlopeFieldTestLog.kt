package com.wmcho.puttingcaddie

import android.util.Log
import com.wmcho.puttingcaddie.slope.SlopeInputResult
import java.util.Locale
import kotlin.math.sqrt

/**
 * Field test 전용 **판정 로그** — 알고리즘/게이트 로직은 변경하지 않고,
 * RESULT 진입 1회마다 원인 분류·요약 1줄을 남긴다.
 */
object SlopeFieldTestLog {

    private const val TAG = "SLOPE_FIELD_TEST"

    /**
     * 메일 첨부 `PC_feedback_*.json` · 일별 `PC_measurements_*.jsonl`에 넣는
     * [SLOPE_FINAL_SUMMARY] / [SLOPE_DISPLAY_DECISION] 정합 스냅샷.
     */
    data class SlopeFeedbackSnapshot(
        val upDownSlopeAvailable: Boolean,
        val leftRightSlopeAvailable: Boolean,
        val finalSlopeDisplayMode: String,
        val finalForwardSource: String,
        val forwardPct: Float?,
        val lateralPctInternal: Float?,
        val lateralSuppressedReason: String?,
        val finalNoSlopeReason: String,
        val finalNoSlopeClass: String,
        val classify: String,
        val distanceOk: Boolean,
        val ballAnchored: Boolean,
        val cupAnchored: Boolean,
        val vMeters: Float?,
        val hMeters: Float,
        val phase1Line: String,
        val experimentalLine: String,
        val sharedP3Line: String,
        val trackingState: String?,
        /** 제품 UI·판정에서 측면경사 미사용 (상하만 제품 목표). */
        val lateralUsedForProduct: Boolean,
        val upDownSamePlaneCollapse: Boolean,
        val upDownSamePlaneCollapseReason: String?,
        /** 상하 전용: OK | NO_SLOPE_* … (거짓 0 vs FLAT vs 붕괴 분리). */
        val finalUpDownNoSlopeReason: String,
        val finalUpDownNoSlopeClass: String,
        val upDownSlopeSourceCandidate: String,
        val upDownSlopeSourceFinal: String,
        /** HAS_SLOPE일 때만: FLAT_MEASURED | REAL_SLOPE; 실패 시 null. */
        val upDownFlatLabel: String?,
        /** SharedP3 — 다음 필드에서 상하 1순위 후보 검증용 한 줄. */
        val sharedP3UpDownVerificationSummary: String,
        /** [UpDownSlopeProductGate] — null 이면 통과 또는 SharedP3 미사용 */
        val upDownProductGateReason: String?,
        val sharedP3Quality: String?,
        val sharedBlockedReason: String?,
        val sharedPlaneNormalY: Float?,
        val sharedFinalResidualM: Float?,
        val sharedProjectedCupPx: Float?,
        val sharedSampleSpreadCupM: Float?,
        val distanceState: String,
        val verticalBestSource: String,
        val lateralBestSource: String,
        val verticalQuality: String,
        val lateralQuality: String,
        val verticalRejectReason: String?,
        val lateralRejectReason: String?,
        /** 정책상 Phase1·Experimental은 제품 상하 후보에서 제외 */
        val phase1UsableForProduct: Boolean,
        val experimentalUsableForProduct: Boolean
    )

    /**
     * @param ballAnchored Activity의 볼 고정 성공 여부(예: `ballValidHits != null`).
     * @param cupAnchored 컵 고정 성공 여부(예: `cupValidHits != null`).
     */
    fun feedbackSnapshot(
        ui: V31StateMachine.UiModel,
        ballAnchored: Boolean,
        cupAnchored: Boolean
    ): SlopeFeedbackSnapshot {
        val distanceOk = ui.distanceMeters.isFinite() && ui.distanceMeters > 0f
        val gr = resolveGraphic(ui)
        val (noSlopeReason, noSlopeClass) = resolveNoSlopeReasonAndClass(
            ui, ballAnchored, cupAnchored, distanceOk, gr
        )

        val p1 = ui.slopeDebugInfo
        val exp = ui.slopeExperimentalResult
        val shared = ui.experimentalSharedSlope
        val log = ui.sharedP3Log

        val phase1Line =
            when {
                p1 == null -> "null"
                p1.blockedReason != null -> "rejected(${p1.blockedReason})"
                p1.quality == "valid" -> "valid"
                else -> "rejected(quality=${p1.quality})"
            }
        val experimentalLine =
            when {
                exp == null -> "null"
                exp.quality == "valid" -> "valid"
                else -> "rejected(${exp.rejectReason ?: "unknown"})"
            }
        val sharedP3Line =
            when {
                shared == null -> "null"
                shared.blockedReason != null -> "rejected(${shared.blockedReason})"
                shared.quality == "valid" -> "valid"
                else -> "rejected(${shared.blockedReason ?: shared.quality})"
            }

        val classify =
            when {
                log?.selectionBlockedReason != null -> log.selectionBlockedReason!!.substringBefore(';').take(40)
                !gr.finalSlopeAvailable && noSlopeReason.contains("SHARED") -> "shared_issue"
                !gr.finalSlopeAvailable && phase1Line.startsWith("rejected") -> "phase1_issue"
                !gr.finalSlopeAvailable && experimentalLine.startsWith("rejected") -> "experimental_issue"
                else -> "none"
            }

        val displayMode = gr.uiSlopeState.name
        val lateralSuppressed =
            when {
                gr.uiSlopeState == UiSlopeState.DEGRADED -> "policy_ui_hidden"
                gr.lateralPct == null -> "not_available"
                else -> "unstable_or_outlier"
            }

        val collapse = analyzeUpDownCollapse(ui)
        val (upDownReason, upDownClass) =
            resolveUpDownNoSlope(gr, noSlopeReason, noSlopeClass, collapse)
        val flatLabel =
            if (gr.finalSlopeAvailable) {
                classifyUpDownFlatLabel(gr.forwardPct)
            } else {
                null
            }
        val candidateTrace = buildUpDownCandidateTrace(ui, gr)
        val sharedP3UpDown = sharedP3UpDownVerificationSummary(log)
        val spreadM = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM
        val gateReason = gr.productGateReason

        return SlopeFeedbackSnapshot(
            upDownSlopeAvailable = gr.finalSlopeAvailable,
            leftRightSlopeAvailable = gr.lateralPct != null,
            finalSlopeDisplayMode = displayMode,
            finalForwardSource = gr.source,
            forwardPct = gr.forwardPct,
            lateralPctInternal = gr.lateralPct,
            lateralSuppressedReason = lateralSuppressed,
            finalNoSlopeReason = noSlopeReason,
            finalNoSlopeClass = noSlopeClass,
            classify = classify,
            distanceOk = distanceOk,
            ballAnchored = ballAnchored,
            cupAnchored = cupAnchored,
            vMeters = gr.vMeters,
            hMeters = gr.hMeters,
            phase1Line = phase1Line,
            experimentalLine = experimentalLine,
            sharedP3Line = sharedP3Line,
            trackingState = ui.trackingState,
            lateralUsedForProduct = false,
            upDownSamePlaneCollapse = collapse.samePlaneCollapse,
            upDownSamePlaneCollapseReason = collapse.samePlaneCollapseReason,
            finalUpDownNoSlopeReason = upDownReason,
            finalUpDownNoSlopeClass = upDownClass,
            upDownSlopeSourceCandidate = candidateTrace,
            upDownSlopeSourceFinal = gr.source,
            upDownFlatLabel = flatLabel,
            sharedP3UpDownVerificationSummary = sharedP3UpDown,
            upDownProductGateReason = gateReason,
            sharedP3Quality = log?.quality,
            sharedBlockedReason = log?.finalBlockedReason,
            sharedPlaneNormalY = log?.normalYFinal,
            sharedFinalResidualM = log?.finalResidualM,
            sharedProjectedCupPx = ui.multiRayProjectedCupPx,
            sharedSampleSpreadCupM = spreadM,
            distanceState = distanceState(ui),
            verticalBestSource = gr.verticalBestSource.name,
            lateralBestSource = gr.lateralBestSource.name,
            verticalQuality = gr.verticalQuality.name,
            lateralQuality = gr.lateralQuality.name,
            verticalRejectReason = gr.verticalRejectReason,
            lateralRejectReason = gr.lateralRejectReason,
            phase1UsableForProduct = false,
            experimentalUsableForProduct = false
        )
    }

    fun appendSlopeFeedbackSnapshotJson(
        sb: StringBuilder,
        snap: SlopeFeedbackSnapshot?,
        esc: (String) -> String
    ) {
        if (snap == null) {
            sb.append("null")
            return
        }
        fun num(f: Float?): String =
            when {
                f == null -> "null"
                !f.isFinite() -> "null"
                else -> String.format(Locale.US, "%.6f", f)
            }
        sb.append('{')
        sb.append("\"upDownSlopeAvailable\":").append(snap.upDownSlopeAvailable).append(',')
        sb.append("\"leftRightSlopeAvailable\":").append(snap.leftRightSlopeAvailable).append(',')
        sb.append("\"finalSlopeDisplayMode\":\"").append(esc(snap.finalSlopeDisplayMode)).append("\",")
        sb.append("\"finalForwardSource\":\"").append(esc(snap.finalForwardSource)).append("\",")
        sb.append("\"forwardPct\":").append(num(snap.forwardPct)).append(',')
        sb.append("\"lateralPctInternal\":").append(num(snap.lateralPctInternal)).append(',')
        sb.append("\"lateralSuppressedReason\":")
        if (snap.lateralSuppressedReason == null) sb.append("null") else sb.append('"').append(esc(snap.lateralSuppressedReason)).append('"')
        sb.append(',')
        sb.append("\"finalNoSlopeReason\":\"").append(esc(snap.finalNoSlopeReason)).append("\",")
        sb.append("\"finalNoSlopeClass\":\"").append(esc(snap.finalNoSlopeClass)).append("\",")
        sb.append("\"classify\":\"").append(esc(snap.classify)).append("\",")
        sb.append("\"distanceOk\":").append(snap.distanceOk).append(',')
        sb.append("\"ballAnchored\":").append(snap.ballAnchored).append(',')
        sb.append("\"cupAnchored\":").append(snap.cupAnchored).append(',')
        sb.append("\"vMeters\":").append(num(snap.vMeters)).append(',')
        sb.append("\"hMeters\":").append(num(snap.hMeters)).append(',')
        sb.append("\"phase1\":\"").append(esc(snap.phase1Line)).append("\",")
        sb.append("\"experimental\":\"").append(esc(snap.experimentalLine)).append("\",")
        sb.append("\"sharedP3\":\"").append(esc(snap.sharedP3Line)).append("\",")
        sb.append("\"trackingState\":")
        if (snap.trackingState == null) sb.append("null") else sb.append('"').append(esc(snap.trackingState)).append('"')
        sb.append(',')
        sb.append("\"lateralUsedForProduct\":").append(snap.lateralUsedForProduct).append(',')
        sb.append("\"upDownSamePlaneCollapse\":").append(snap.upDownSamePlaneCollapse).append(',')
        sb.append("\"upDownSamePlaneCollapseReason\":")
        if (snap.upDownSamePlaneCollapseReason == null) sb.append("null") else sb.append('"').append(esc(snap.upDownSamePlaneCollapseReason)).append('"')
        sb.append(',')
        sb.append("\"finalUpDownNoSlopeReason\":\"").append(esc(snap.finalUpDownNoSlopeReason)).append("\",")
        sb.append("\"finalUpDownNoSlopeClass\":\"").append(esc(snap.finalUpDownNoSlopeClass)).append("\",")
        sb.append("\"upDownSlopeSourceCandidate\":\"").append(esc(snap.upDownSlopeSourceCandidate)).append("\",")
        sb.append("\"upDownSlopeSourceFinal\":\"").append(esc(snap.upDownSlopeSourceFinal)).append("\",")
        sb.append("\"upDownFlatLabel\":")
        if (snap.upDownFlatLabel == null) sb.append("null") else sb.append('"').append(esc(snap.upDownFlatLabel)).append('"')
        sb.append(',')
        sb.append("\"sharedP3UpDownVerificationSummary\":\"").append(esc(snap.sharedP3UpDownVerificationSummary)).append("\",")
        sb.append("\"upDownProductGateReason\":")
        if (snap.upDownProductGateReason == null) sb.append("null") else sb.append('"').append(esc(snap.upDownProductGateReason)).append('"')
        sb.append(',')
        sb.append("\"sharedP3Quality\":")
        if (snap.sharedP3Quality == null) sb.append("null") else sb.append('"').append(esc(snap.sharedP3Quality)).append('"')
        sb.append(',')
        sb.append("\"sharedBlockedReason\":")
        if (snap.sharedBlockedReason == null) sb.append("null") else sb.append('"').append(esc(snap.sharedBlockedReason)).append('"')
        sb.append(',')
        sb.append("\"sharedPlaneNormalY\":").append(num(snap.sharedPlaneNormalY)).append(',')
        sb.append("\"sharedFinalResidualM\":").append(num(snap.sharedFinalResidualM)).append(',')
        sb.append("\"sharedProjectedCupPx\":").append(num(snap.sharedProjectedCupPx)).append(',')
        sb.append("\"sharedSampleSpreadCupM\":").append(num(snap.sharedSampleSpreadCupM)).append(',')
        sb.append("\"distanceState\":\"").append(esc(snap.distanceState)).append("\",")
        sb.append("\"verticalBestSource\":\"").append(esc(snap.verticalBestSource)).append("\",")
        sb.append("\"lateralBestSource\":\"").append(esc(snap.lateralBestSource)).append("\",")
        sb.append("\"verticalQuality\":\"").append(esc(snap.verticalQuality)).append("\",")
        sb.append("\"lateralQuality\":\"").append(esc(snap.lateralQuality)).append("\",")
        sb.append("\"verticalRejectReason\":")
        if (snap.verticalRejectReason == null) sb.append("null") else sb.append('"').append(esc(snap.verticalRejectReason)).append('"')
        sb.append(',')
        sb.append("\"lateralRejectReason\":")
        if (snap.lateralRejectReason == null) sb.append("null") else sb.append('"').append(esc(snap.lateralRejectReason)).append('"')
        sb.append(',')
        sb.append("\"phase1UsableForProduct\":").append(snap.phase1UsableForProduct).append(',')
        sb.append("\"experimentalUsableForProduct\":").append(snap.experimentalUsableForProduct)
        sb.append('}')
    }

    data class GraphicResolution(
        /** SHARED | SHARED_RAW | PHASE1 | NONE — 상하(forward) 표시용 최종 소스 */
        val source: String,
        val finalSlopeAvailable: Boolean,
        val forwardPct: Float?,
        /** 내부/로그용. 제품 UI에서는 당분간 미표시. */
        val lateralPct: Float?,
        val vMeters: Float?,
        val hMeters: Float,
        /** [UpDownSlopeProductGate] — 차단 시 상하 미표시 (거리와 무관) */
        val productGateReason: String? = null,
        val uiSlopeState: UiSlopeState = UiSlopeState.BLOCK,
        val verticalQuality: RawQuality = RawQuality.BLOCK,
        val lateralQuality: RawQuality = RawQuality.BLOCK,
        val verticalRejectReason: String? = null,
        val lateralRejectReason: String? = null,
        val verticalBestSource: Source = Source.NONE,
        val lateralBestSource: Source = Source.NONE
    )

    /**
     * 상하경사 우선 정책: forward만 있으면 표시 가능. lateral 부재·불안정은 전체 실패로 보지 않음.
     * 제품 소스: SharedP3만 ([UpDownSlopeProductGate]). Phase1·Experimental은 최종 후보에서 제외.
     * 우선순위: Shared 안정 출력 → Shared raw (게이트 통과 시에만).
     */
    fun resolveGraphic(ui: V31StateMachine.UiModel): GraphicResolution {
        val log = ui.sharedP3Log
        val projectedCupPx = ui.multiRayProjectedCupPx
        val sampleSpreadCupM = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM
        val productGateReason = UpDownSlopeProductGate.productGateReason(log, projectedCupPx, sampleSpreadCupM)

        val sharedSet = buildSharedCandidate(ui)
        val localSet = buildLocalCandidate(ui)
        val hvVertical = buildHvVerticalCandidate(ui)
        val verticalBest = decideBestVertical(sharedSet.verticalCandidate, localSet.verticalCandidate, hvVertical)
        val lateralBest = decideBestLateral(sharedSet.lateralCandidate, localSet.lateralCandidate)
        val uiState = decideSlopeDisplayState(verticalBest, lateralBest)

        val rawM = ui.distanceMeters.takeIf { it.isFinite() && it > 0f } ?: 0f
        val h = verticalBest.hMeters?.takeIf { it.isFinite() && it > 0f } ?: rawM.coerceAtLeast(1e-4f)
        val forwardPct = if (uiState == UiSlopeState.BLOCK) null else verticalBest.value
        val vMeters = forwardPct?.let { (it / 100f) * h }
        val lateralPct = if (uiState == UiSlopeState.FULL) lateralBest.value else null
        val sourceTag =
            when (verticalBest.source) {
                Source.SHARED -> "SHARED"
                Source.LOCAL -> "LOCAL"
                Source.HV -> "HV"
                Source.NONE -> "NONE"
            }

        return GraphicResolution(
            source = sourceTag,
            finalSlopeAvailable = uiState != UiSlopeState.BLOCK && forwardPct != null && vMeters != null,
            forwardPct = forwardPct,
            lateralPct = lateralPct,
            vMeters = vMeters,
            hMeters = h,
            productGateReason = productGateReason,
            uiSlopeState = uiState,
            verticalQuality = verticalBest.quality,
            lateralQuality = lateralBest.quality,
            verticalRejectReason = verticalBest.rejectReason,
            lateralRejectReason = lateralBest.rejectReason,
            verticalBestSource = verticalBest.source,
            lateralBestSource = lateralBest.source
        )
    }

    private fun phase1Mode(ui: V31StateMachine.UiModel): String {
        val p = ui.slopeDebugInfo ?: return "none"
        return when {
            p.ballNormal != null && p.cupNormal != null -> "plane_only"
            p.quality == "valid" && p.forwardPct != null -> "plane_only"
            ui.horizontalVerticalMeters != null && p.forwardPct == null -> "hv_fallback_eligible"
            else -> "none"
        }
    }

    private fun compressCandidate(c: SharedP3CandidateLog?): String {
        if (c == null) return "null"
        val rej = c.rejectReasonsJoined().ifBlank { "none" }
        val v = if (c.valid) "valid" else "invalid"
        val ny = c.normalY?.let { String.format(Locale.US, "%.3f", it) } ?: "na"
        val res = c.residualM?.let { String.format(Locale.US, "%.4f", it) } ?: "na"
        val fwd = c.forwardPct?.let { String.format(Locale.US, "%.2f", it) } ?: "na"
        val lat = c.lateralPct?.let { String.format(Locale.US, "%.2f", it) } ?: "na"
        return "$v(n=${c.sampleCount},ny=$ny,res=$res,fwd=$fwd,lat=$lat,rej=$rej)"
    }

    private fun experimentalSummary(exp: SlopeInputResult?): String {
        if (exp == null) return "null"
        val q = exp.quality
        val rr = exp.rejectReason ?: "none"
        val drift = exp.experimentalPlaneDriftDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "na"
        val dTh = exp.driftThresholdDeg?.let { String.format(Locale.US, "%.1f", it) } ?: "na"
        val trB = exp.trackingStateAtBallSample ?: "na"
        val trC = exp.trackingStateAtCupSample ?: "na"
        val diag = exp.experimentalDiagnostics
        val spread = diag?.sampleSpreadCupM?.let { String.format(Locale.US, "%.3f", it) } ?: "na"
        val cls = when {
            exp.quality == "valid" -> "valid"
            rr.contains("drift", ignoreCase = true) -> "drift_reject"
            rr.contains("spread", ignoreCase = true) || (diag?.residualType != null) -> "spread_or_residual"
            else -> "other"
        }
        return "quality=$q reject=$rr driftDeg=$drift driftTh=$dTh spreadCupM=$spread trackBall=$trB trackCup=$trC classify=$cls"
    }

    private fun resolveNoSlopeReasonAndClass(
        ui: V31StateMachine.UiModel,
        ballOk: Boolean,
        cupOk: Boolean,
        distanceOk: Boolean,
        gr: GraphicResolution
    ): Pair<String, String> {
        if (gr.finalSlopeAvailable) return "NONE" to "none"
        if (!cupOk) return "NO_SLOPE_CUP_FIX_FAIL" to "distance_failure"
        if (!ballOk) return "NO_SLOPE_BALL_ANCHOR_MISSING" to "phase1_input"
        if (!distanceOk) return "NO_SLOPE_DISTANCE_FAIL" to "distance_failure"
        if (ui.trackingState != "TRACKING") return "NO_SLOPE_TRACKING_NOT_READY" to "tracking_issue"

        val log = ui.sharedP3Log
        val sel = log?.selectionBlockedReason
        val fin = log?.finalBlockedReason
        val blocked = sel ?: fin
        if (!blocked.isNullOrBlank()) {
            when {
                blocked.contains("shared_no_stable_candidate") ->
                    return "NO_SLOPE_SHARED_NO_STABLE_CANDIDATE" to "shared_sampling_issue"
                blocked.contains("lateral_forward_ratio") || blocked.contains("shared_lateral") ->
                    return "NO_SLOPE_SHARED_LATERAL_OUTLIER" to "shared_sanity_issue"
                blocked.contains("corridor") || blocked.contains("too_few") ->
                    return "NO_SLOPE_SHARED_CORRIDOR_EMPTY" to "shared_sampling_issue"
            }
        }

        val p1 = ui.slopeDebugInfo
        if (p1?.blockedReason != null) {
            val br = p1.blockedReason!!
            when {
                br.contains("drift") -> return "NO_SLOPE_PHASE1_DRIFT" to "surface_mismatch"
                br.contains("horizontal") -> return "NO_SLOPE_PHASE1_INPUT_MISSING" to "phase1_input"
                br.contains("plane") -> return "NO_SLOPE_PHASE1_POLICY_BLOCKED" to "policy_block"
            }
            return "NO_SLOPE_PHASE1_POLICY_BLOCKED" to "policy_block"
        }

        val exp = ui.slopeExperimentalResult
        if (exp?.quality == "rejected") {
            val rr = exp.rejectReason ?: ""
            when {
                rr.contains("drift") -> return "NO_SLOPE_EXPERIMENTAL_DRIFT_REJECT" to "surface_mismatch"
                rr.contains("spread") -> return "NO_SLOPE_EXPERIMENTAL_SPREAD_REJECT" to "shared_sampling_issue"
            }
        }

        if (log?.candidateCorridor?.valid == false && (log.corridor?.keptCount ?: 0) < 3) {
            return "NO_SLOPE_SHARED_CORRIDOR_EMPTY" to "shared_sampling_issue"
        }

        if (p1 != null && p1.forwardPct == null && gr.source == "NONE") {
            return "NO_SLOPE_PHASE1_INPUT_MISSING" to "phase1_input"
        }

        if (ui.ballCupPlaneAngleDeg != null && ui.ballCupPlaneAngleDeg!! > 8f && gr.source == "NONE") {
            return "NO_SLOPE_SURFACE_MISMATCH" to "surface_mismatch"
        }

        return "NO_SLOPE_UNKNOWN" to "unknown"
    }

    private fun distanceState(ui: V31StateMachine.UiModel): String {
        val distanceOk = ui.distanceMeters.isFinite() && ui.distanceMeters > 0f
        return when {
            !distanceOk -> "DISTANCE_BLOCK"
            ui.confirmGateAccepted == true -> "DISTANCE_FIXED"
            else -> "DISTANCE_DEGRADED"
        }
    }

    /**
     * RESULT 상태로 **처음 진입한 프레임**에서만 호출한다 (호출부에서 전이 감지).
     */
    fun emitOnResult(
        ui: V31StateMachine.UiModel,
        ballAnchored: Boolean,
        cupAnchored: Boolean
    ) {
        val distanceOk = ui.distanceMeters.isFinite() && ui.distanceMeters > 0f
        val gr = resolveGraphic(ui)
        val p3Log = ui.sharedP3Log
        val spreadMForCheck = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM
        ui.slopeProjectionSnapshot?.let { p ->
            val dxb = p.slopeBall[0] - p.rawBall[0]
            val dzb = p.slopeBall[2] - p.rawBall[2]
            val ballXZ = sqrt(dxb * dxb + dzb * dzb)
            val dxc = p.slopeCup[0] - p.rawCup[0]
            val dzc = p.slopeCup[2] - p.rawCup[2]
            val cupXZ = sqrt(dxc * dxc + dzc * dzc)
            fun fmt4(x: Float) = String.format(Locale.US, "%.4f", x)
            fun fmt3v(v: FloatArray) =
                "${fmt4(v[0])},${fmt4(v[1])},${fmt4(v[2])}"
            Log.i(
                "SLOPE_PROJECTION_CHECK",
                "ballSrc=${p.ballProjectionSource} cupSrc=${p.cupProjectionSource} " +
                    "ballRaw_m=(${fmt3v(p.rawBall)}) ballProj_m=(${fmt3v(p.slopeBall)}) " +
                    "cupRaw_m=(${fmt3v(p.rawCup)}) cupProj_m=(${fmt3v(p.slopeCup)}) " +
                    "dY_ball_m=${fmt4(p.slopeBall[1] - p.rawBall[1])} dY_cup_m=${fmt4(p.slopeCup[1] - p.rawCup[1])} " +
                    "dXZ_ball_m=${fmt4(ballXZ)} dXZ_cup_m=${fmt4(cupXZ)} " +
                    "deltaYRaw_m=${ui.deltaYRaw?.let { fmt4(it) } ?: "na"} deltaYProjected_m=${ui.deltaYProjected?.let { fmt4(it) } ?: "na"} " +
                    "cupDepression=${p.cupDepressionSuspected} " +
                    "sharedP3Quality=${p3Log?.quality ?: "null"} " +
                    "upDownSlopeSourceFinal=${gr.source} finalSource=${gr.source} " +
                    "projectedCupPx=${ui.multiRayProjectedCupPx?.let { String.format(Locale.US, "%.1f", it) } ?: "na"} " +
                    "sampleSpreadCupM=${spreadMForCheck?.let { fmt4(it) } ?: "na"}"
            )
        } ?: Log.w(
            "SLOPE_PROJECTION_CHECK",
            "no_slopeProjectionSnapshot (RESULT) — anchors or projection unavailable"
        )
        Log.d(
            TAG,
            "SLOPE_DISPLAY_DECISION mode=${gr.uiSlopeState} upDownAvailable=${gr.finalSlopeAvailable} " +
                "leftRightAvailable=${gr.lateralPct != null} finalForwardSource=${gr.source} " +
                "distanceState=${distanceState(ui)} verticalBestSource=${gr.verticalBestSource} " +
                "lateralBestSource=${gr.lateralBestSource} verticalQuality=${gr.verticalQuality} " +
                "lateralQuality=${gr.lateralQuality} verticalRejectReason=${gr.verticalRejectReason ?: "none"} " +
                "lateralRejectReason=${gr.lateralRejectReason ?: "none"} " +
                "lateralSuppressedReason=${if (gr.uiSlopeState == UiSlopeState.DEGRADED) "policy_ui_hidden" else "none"}"
        )
        val (noSlopeReason, noSlopeClass) = resolveNoSlopeReasonAndClass(
            ui, ballAnchored, cupAnchored, distanceOk, gr
        )

        val p1 = ui.slopeDebugInfo
        val exp = ui.slopeExperimentalResult
        val shared = ui.experimentalSharedSlope
        val log = ui.sharedP3Log

        val phase1Str =
            when {
                p1 == null -> "null"
                p1.blockedReason != null -> "rejected(${p1.blockedReason})"
                p1.quality == "valid" -> "valid"
                else -> "rejected(quality=${p1.quality})"
            }
        val expStr =
            when {
                exp == null -> "null"
                exp.quality == "valid" -> "valid"
                else -> "rejected(${exp.rejectReason ?: "unknown"})"
            }
        val sharedStr =
            when {
                shared == null -> "null"
                shared.blockedReason != null -> "rejected(${shared.blockedReason})"
                shared.quality == "valid" -> "valid"
                else -> "rejected(${shared.blockedReason ?: shared.quality})"
            }

        val classify =
            when {
                log?.selectionBlockedReason != null -> log.selectionBlockedReason!!.substringBefore(';').take(40)
                !gr.finalSlopeAvailable && noSlopeReason.contains("SHARED") -> "shared_issue"
                !gr.finalSlopeAvailable && phase1Str.startsWith("rejected") -> "phase1_issue"
                !gr.finalSlopeAvailable && expStr.startsWith("rejected") -> "experimental_issue"
                else -> "none"
            }

        Log.d(
            TAG,
            "SLOPE_FINAL_SUMMARY distanceOk=$distanceOk ballAnchored=$ballAnchored cupAnchored=$cupAnchored " +
                "phase1=$phase1Str experimental=$expStr sharedP3=$sharedStr " +
                "graphicSource=${gr.source} finalSlopeAvailable=${gr.finalSlopeAvailable} " +
                "finalNoSlopeReason=$noSlopeReason finalNoSlopeClass=$noSlopeClass classify=$classify"
        )

        val collapseAn = analyzeUpDownCollapse(ui)
        val snapForUp = feedbackSnapshot(ui, ballAnchored, cupAnchored)
        Log.d(
            TAG,
            "UPDOWN_SLOPE_SUMMARY collapse=${snapForUp.upDownSamePlaneCollapse} " +
                "collapseReason=${snapForUp.upDownSamePlaneCollapseReason ?: "na"} " +
                "deltaYRaw_m=${collapseAn.deltaYRawM?.let { String.format(Locale.US, "%.4f", it) } ?: "na"} " +
                "ballNy=${collapseAn.ballNy?.let { String.format(Locale.US, "%.3f", it) } ?: "na"} " +
                "cupNy=${collapseAn.cupNy?.let { String.format(Locale.US, "%.3f", it) } ?: "na"} " +
                "flatLabel=${snapForUp.upDownFlatLabel ?: "na"} " +
                "${snapForUp.upDownSlopeSourceCandidate} " +
                "finalUpDownReason=${snapForUp.finalUpDownNoSlopeReason} finalUpDownClass=${snapForUp.finalUpDownNoSlopeClass} " +
                "lateralUsedForProduct=${snapForUp.lateralUsedForProduct}"
        )
        Log.d(TAG, "UP_DOWN_SHAREDP3_VERIFICATION ${snapForUp.sharedP3UpDownVerificationSummary}")

        Log.d(
            TAG,
            "SLOPE_FINAL_CODES finalNoSlopeReason=$noSlopeReason finalNoSlopeClass=$noSlopeClass " +
                "graphicSource=${gr.source} finalSlopeAvailable=${gr.finalSlopeAvailable}"
        )

        val mode = phase1Mode(ui)
        Log.d(
            TAG,
            "PHASE1_DECISION mode=$mode hasForward=${p1?.forwardPct != null} hasLateral=${p1?.lateralPct != null} " +
                "hasVMeters=${p1?.vMeters != null} policyBlocked=${p1?.blockedReason ?: "NONE"} quality=${p1?.quality ?: "null"}"
        )

        val samePl = ui.ballCupSamePlane
        val ang = ui.ballCupPlaneAngleDeg?.let { String.format(Locale.US, "%.2f", it) } ?: "na"
        val ad = ui.anchorDistanceMeters?.let { String.format(Locale.US, "%.3f", it) } ?: "na"
        Log.d(
            TAG,
            "PLANE_RELATION samePlaneById=$samePl ballCupPlaneNormalAngleDeg=$ang anchorDistanceM=$ad"
        )

        Log.d(TAG, "EXPERIMENTAL_DECISION ${experimentalSummary(exp)}")

        val allC = compressCandidate(log?.candidateAll)
        val trimC = compressCandidate(log?.candidateTrimmed)
        val corC = compressCandidate(log?.candidateCorridor)
        Log.d(
            TAG,
            "SHAREDP3_CANDIDATES all=$allC trimmed=$trimC corridor=$corC " +
                "selected=${log?.selectedType ?: "none"} finalBlock=${log?.finalBlockedReason ?: log?.selectionBlockedReason ?: "none"} " +
                "sharedP3Quality=${log?.quality ?: "null"}"
        )

        val cor = log?.corridor
        if (cor != null) {
            val cov =
                if (cor.ballCupDistM > 1e-6f) {
                    (cor.keptCount.toFloat() / cor.inputCount.coerceAtLeast(1)).coerceIn(0f, 1f)
                } else {
                    0f
                }
            Log.d(
                TAG,
                "CORRIDOR_QUALITY halfWidth_m=${String.format(Locale.US, "%.3f", cor.corridorHalfWidthM)} " +
                    "input=${cor.inputCount} kept=${cor.keptCount} rejected=${cor.rejectedByDistanceCount} " +
                    "coverageRatio=${String.format(Locale.US, "%.2f", cov)} " +
                    "maxDist=${cor.maxDistanceToLineM?.let { String.format(Locale.US, "%.4f", it) } ?: "na"} " +
                    "medianDist=${cor.medianDistanceToLineM?.let { String.format(Locale.US, "%.4f", it) } ?: "na"} " +
                    "(minAlong/maxAlong/maxGap require extended pipeline — not computed here)"
            )
        } else {
            Log.d(TAG, "CORRIDOR_QUALITY null")
        }

        val ed = exp?.experimentalDiagnostics
        if (ed != null) {
            Log.d(
                TAG,
                "SHARED_SAMPLE_DISTRIBUTION spreadCupM=${ed.sampleSpreadCupM?.let { String.format(Locale.US, "%.3f", it) } ?: "na"} " +
                    "bboxBall=${ed.ballSampleBBoxMin != null && ed.ballSampleBBoxMax != null} " +
                    "bboxCup=${ed.cupSampleBBoxMin != null && ed.cupSampleBBoxMax != null}"
            )
        }

        Log.d(
            TAG,
            "SLOPE_TRACKING trackingAtCompute=${ui.trackingState} anchorDistanceM=${ui.anchorDistanceMeters?.let { String.format(Locale.US, "%.3f", it) } ?: "na"} " +
                "projectedCupPx=${ui.multiRayProjectedCupPx?.let { String.format(Locale.US, "%.1f", it) } ?: "na"}"
        )

        Log.d(
            TAG,
            "NOTE: scenarioId/greenType/operatorNote are manual — add to feedback JSON or QA sheet for field runs."
        )
    }
}
