package com.wmcho.puttingcaddie

import android.util.Log

/**
 * SharedP3 필드 테스트·이메일 피드백 JSON용 구조 + Logcat 1줄 로그.
 */
data class SharedP3CandidateLog(
    val type: String,
    val valid: Boolean,
    val rejectReasons: List<String>,
    val sampleCount: Int?,
    val residualM: Float?,
    val normal: FloatArray?,
    val normalY: Float?,
    val forwardPct: Float?,
    val lateralPct: Float?,
    val prevDot: Float?,
    val prevAngleDeg: Float?,
    val flipApplied: Boolean,
    val scoreTotal: Float?,
    val scoreNormalY: Float?,
    val scoreResidualPenalty: Float?,
    val scoreLateralPenalty: Float?,
    val scorePrevAnglePenalty: Float?,
    val scoreBlockedPenalty: Float?
) {
    fun rejectReasonsJoined(): String = rejectReasons.joinToString(",")
}

data class SharedP3LogPayload(
    val trim: SharedPlaneFit.TrimmedFitOutput?,
    val corridor: SharedPlaneFit.CorridorStats?,
    val candidateAll: SharedP3CandidateLog?,
    val candidateTrimmed: SharedP3CandidateLog?,
    val candidateCorridor: SharedP3CandidateLog?,
    val selectedType: String?,
    val selectionReason: String?,
    val rejectedSummary: String?,
    val normalYFinal: Float?,
    val selectionBlockedReason: String?,
    /** GOOD | DEGRADED | BLOCKED */
    val quality: String,
    val selectedPrevAngleDeg: Float?,
    val selectedFlipApplied: Boolean?,
    val selectedPrevDot: Float?,
    val finalForwardPct: Float?,
    val finalLateralPct: Float?,
    /** stabilize 전 computeSharedOnly 값 */
    val finalForwardPctRaw: Float?,
    val finalLateralPctRaw: Float?,
    val stabilizationReasons: String?,
    val lateralDampingFactor: Float?,
    val lateralForwardRatio: Float?,
    /** 작은 forward 구간에서만 lateral/forward 비율 가드 적용 여부 */
    val ratioSmallForwardGuard: Boolean?,
    val finalResidualM: Float?,
    val finalSampleCount: Int?,
    val finalBlockedReason: String?
)

/** [slopeSharedP3] 안에서 forwardPct·lateralPct·refNormal·quality 뒤에 붙인다 (앞에 콤마 없음 → 호출부에서 콤마 후 호출). */
fun SharedP3LogPayload.appendJson(
    sb: StringBuilder,
    escJson: (String) -> String,
    vec3Json: (FloatArray?) -> String,
    fmt4: (Float?) -> String,
    fmt3: (Float?) -> String
) {
    fun candJson(c: SharedP3CandidateLog?) {
        if (c == null) {
            sb.append("null")
            return
        }
        sb.append("{")
        sb.append("\"type\":\"").append(escJson(c.type)).append("\",")
        sb.append("\"valid\":").append(if (c.valid) "true" else "false").append(",")
        sb.append("\"rejectReasons\":[")
        c.rejectReasons.forEachIndexed { i, r ->
            if (i > 0) sb.append(',')
            sb.append("\"").append(escJson(r)).append("\"")
        }
        sb.append("],")
        sb.append("\"sampleCount\":").append(c.sampleCount ?: "null").append(",")
        sb.append("\"residual_m\":").append(c.residualM?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"normal\":").append(vec3Json(c.normal)).append(",")
        sb.append("\"normalY\":").append(c.normalY?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"forwardPct\":").append(c.forwardPct?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"lateralPct\":").append(c.lateralPct?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"prevDot\":").append(c.prevDot?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"prevAngleDeg\":").append(c.prevAngleDeg?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"flipApplied\":").append(if (c.flipApplied) "true" else "false").append(",")
        sb.append("\"scoreTotal\":").append(c.scoreTotal?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"scoreNormalY\":").append(c.scoreNormalY?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"scoreResidualPenalty\":").append(c.scoreResidualPenalty?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"scoreLateralPenalty\":").append(c.scoreLateralPenalty?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"scorePrevAnglePenalty\":").append(c.scorePrevAnglePenalty?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"scoreBlockedPenalty\":").append(c.scoreBlockedPenalty?.let { fmt4(it) } ?: "null")
        sb.append("}")
    }

    fun trimJson(t: SharedPlaneFit.TrimmedFitOutput?) {
        if (t == null) {
            sb.append("null")
            return
        }
        sb.append("{")
        sb.append("\"originalCount\":").append(t.originalCount).append(",")
        sb.append("\"removedCount\":").append(t.removedCount).append(",")
        sb.append("\"remainingCount\":").append(t.remainingCount).append(",")
        sb.append("\"originalResidual_m\":").append(t.originalResidualMeanM?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"trimmedResidual_m\":").append(t.trimmedResidualMeanM?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"removedResidualMin_m\":").append(t.removedResidualMin?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"removedResidualMax_m\":").append(t.removedResidualMax?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"removedResidualMedian_m\":").append(t.removedResidualMedian?.let { fmt4(it) } ?: "null")
        sb.append("}")
    }

    fun corridorJson(c: SharedPlaneFit.CorridorStats?) {
        if (c == null) {
            sb.append("null")
            return
        }
        sb.append("{")
        sb.append("\"ballCupDist_m\":").append(fmt4(c.ballCupDistM)).append(",")
        sb.append("\"corridorHalfWidth_m\":").append(fmt4(c.corridorHalfWidthM)).append(",")
        sb.append("\"inputCount\":").append(c.inputCount).append(",")
        sb.append("\"keptCount\":").append(c.keptCount).append(",")
        sb.append("\"rejectedByDistanceCount\":").append(c.rejectedByDistanceCount).append(",")
        sb.append("\"maxDistanceToLine_m\":").append(c.maxDistanceToLineM?.let { fmt4(it) } ?: "null").append(",")
        sb.append("\"medianDistanceToLine_m\":").append(c.medianDistanceToLineM?.let { fmt4(it) } ?: "null")
        sb.append("}")
    }

    sb.append("\"trim\":")
    trimJson(trim)
    sb.append(",\"corridor\":")
    corridorJson(corridor)
    sb.append(",\"candidateAll\":")
    candJson(candidateAll)
    sb.append(",\"candidateTrimmed\":")
    candJson(candidateTrimmed)
    sb.append(",\"candidateCorridor\":")
    candJson(candidateCorridor)
    sb.append(",\"sharedPlaneSelectedType\":")
    if (selectedType == null) sb.append("null") else sb.append("\"").append(escJson(selectedType)).append("\"")
    sb.append(",\"sharedPlaneSelectionReason\":")
    if (selectionReason == null) sb.append("null") else sb.append("\"").append(escJson(selectionReason)).append("\"")
    sb.append(",\"sharedPlaneRejectedCandidates\":")
    if (rejectedSummary == null) sb.append("null") else sb.append("\"").append(escJson(rejectedSummary)).append("\"")
    sb.append(",\"sharedPlaneNormalY\":").append(normalYFinal?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"sharedPlaneSelectionBlockedReason\":")
    if (selectionBlockedReason == null) sb.append("null") else sb.append("\"").append(escJson(selectionBlockedReason)).append("\"")
    sb.append(",\"sharedP3Quality\":\"").append(escJson(quality)).append("\",")
    sb.append("\"prevAngleDeg\":").append(selectedPrevAngleDeg?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"flipApplied\":").append(if (selectedFlipApplied == true) "true" else if (selectedFlipApplied == false) "false" else "null").append(",")
    sb.append("\"prevNormalDot\":").append(selectedPrevDot?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"finalForwardPct\":").append(finalForwardPct?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"finalLateralPct\":").append(finalLateralPct?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"finalForwardPctRaw\":").append(finalForwardPctRaw?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"finalLateralPctRaw\":").append(finalLateralPctRaw?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"stabilizationReasons\":")
    if (stabilizationReasons == null) sb.append("null") else sb.append("\"").append(escJson(stabilizationReasons)).append("\"")
    sb.append(",\"lateralDampingFactor\":").append(lateralDampingFactor?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"lateralForwardRatio\":").append(lateralForwardRatio?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"ratioSmallForwardGuard\":")
        .append(
            when (ratioSmallForwardGuard) {
                true -> "true"
                false -> "false"
                null -> "null"
            }
        )
        .append(",")
    sb.append("\"finalResidual_m\":").append(finalResidualM?.let { fmt4(it) } ?: "null").append(",")
    sb.append("\"finalSampleCount\":").append(finalSampleCount ?: "null").append(",")
    sb.append("\"finalBlockedReason\":")
    if (finalBlockedReason == null) sb.append("null") else sb.append("\"").append(escJson(finalBlockedReason)).append("\"")
}

fun appendSharedP3DetailsNull(sb: StringBuilder) {
    sb.append(",\"trim\":null,\"corridor\":null")
    sb.append(",\"candidateAll\":null,\"candidateTrimmed\":null,\"candidateCorridor\":null")
    sb.append(",\"sharedPlaneSelectedType\":null,\"sharedPlaneSelectionReason\":null")
    sb.append(",\"sharedPlaneRejectedCandidates\":null,\"sharedPlaneNormalY\":null")
    sb.append(",\"sharedPlaneSelectionBlockedReason\":null,\"sharedP3Quality\":null")
    sb.append(",\"prevAngleDeg\":null,\"flipApplied\":null,\"prevNormalDot\":null")
    sb.append(",\"finalForwardPct\":null,\"finalLateralPct\":null")
    sb.append(",\"finalForwardPctRaw\":null,\"finalLateralPctRaw\":null,\"stabilizationReasons\":null")
    sb.append(",\"lateralDampingFactor\":null,\"lateralForwardRatio\":null,\"ratioSmallForwardGuard\":null")
    sb.append(",\"finalResidual_m\":null")
    sb.append(",\"finalSampleCount\":null,\"finalBlockedReason\":null")
}

fun SharedP3LogPayload.emitLogcatLines() {
    val tag = "SLOPE_SHARED_P3"
    trim?.let { t ->
        Log.d(
            tag,
            "SHARED_P3_TRIM originalCount=${t.originalCount} removedCount=${t.removedCount} remainingCount=${t.remainingCount} " +
                "originalResidual=${t.originalResidualMeanM?.let { "%.4f".format(it) } ?: "null"} " +
                "trimmedResidual=${t.trimmedResidualMeanM?.let { "%.4f".format(it) } ?: "null"} " +
                "removedMin=${t.removedResidualMin?.let { "%.4f".format(it) } ?: "null"} " +
                "removedMax=${t.removedResidualMax?.let { "%.4f".format(it) } ?: "null"} " +
                "removedMedian=${t.removedResidualMedian?.let { "%.4f".format(it) } ?: "null"}"
        )
    }
    corridor?.let { c ->
        Log.d(
            tag,
            "SHARED_P3_CORRIDOR ballCupDist=${"%.3f".format(c.ballCupDistM)} halfWidth=${"%.3f".format(c.corridorHalfWidthM)} " +
                "input=${c.inputCount} kept=${c.keptCount} rejected=${c.rejectedByDistanceCount} " +
                "maxDist=${c.maxDistanceToLineM?.let { "%.4f".format(it) } ?: "null"} " +
                "medianDist=${c.medianDistanceToLineM?.let { "%.4f".format(it) } ?: "null"}"
        )
    }
    listOfNotNull(candidateAll, candidateTrimmed, candidateCorridor).forEach { c ->
        Log.d(
            tag,
            "SHARED_P3_CANDIDATE type=${c.type} valid=${c.valid} n=${c.sampleCount} " +
                "residual=${c.residualM?.let { "%.4f".format(it) } ?: "null"} " +
                "normalY=${c.normalY?.let { "%.4f".format(it) } ?: "null"} " +
                "fwd=${c.forwardPct?.let { "%.2f".format(it) } ?: "null"} lat=${c.lateralPct?.let { "%.2f".format(it) } ?: "null"} " +
                "prevDot=${c.prevDot?.let { "%.4f".format(it) } ?: "null"} prevAngleDeg=${c.prevAngleDeg?.let { "%.2f".format(it) } ?: "null"} " +
                "flip=${c.flipApplied} score=${c.scoreTotal?.let { "%.2f".format(it) } ?: "null"} " +
                "rej=${c.rejectReasonsJoined()}"
        )
    }
    if (quality == "BLOCKED" || selectionBlockedReason != null) {
        Log.d(
            tag,
            "SHARED_P3_BLOCKED blockedReason=${selectionBlockedReason ?: finalBlockedReason ?: "none"} " +
                "summary=${rejectedSummary ?: "none"}"
        )
    }
    Log.d(
        tag,
        "SHARED_P3_FINAL selectedType=${selectedType ?: "none"} forwardPct=${finalForwardPct?.let { "%.2f".format(it) } ?: "null"} " +
            "lateralPct=${finalLateralPct?.let { "%.2f".format(it) } ?: "null"} " +
            "fwdRaw=${finalForwardPctRaw?.let { "%.2f".format(it) } ?: "null"} latRaw=${finalLateralPctRaw?.let { "%.2f".format(it) } ?: "null"} " +
            "damp=${lateralDampingFactor?.let { "%.3f".format(it) } ?: "null"} latFwdRatio=${lateralForwardRatio?.let { "%.2f".format(it) } ?: "null"} " +
            "ratioGuard=${ratioSmallForwardGuard} stab=${stabilizationReasons ?: "none"} " +
            "residual=${finalResidualM?.let { "%.4f".format(it) } ?: "null"} sampleCount=${finalSampleCount ?: "null"} " +
            "normalY=${normalYFinal?.let { "%.4f".format(it) } ?: "null"} " +
            "blockedReason=${finalBlockedReason ?: selectionBlockedReason ?: "none"} " +
            "quality=$quality " +
            "selectionReason=${selectionReason ?: ""} " +
            "prevAngleDeg=${selectedPrevAngleDeg?.let { "%.2f".format(it) } ?: "null"} " +
            "flipApplied=${selectedFlipApplied} prevDot=${selectedPrevDot?.let { "%.4f".format(it) } ?: "null"}"
    )
}
