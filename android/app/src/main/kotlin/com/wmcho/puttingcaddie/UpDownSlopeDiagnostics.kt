package com.wmcho.puttingcaddie

import java.util.Locale
import kotlin.math.abs

/**
 * 상하경사 전용 진단·로그용 (제품 계산식 변경 없음).
 * 목표: 입력 붕괴·소스 후보·품질 gate·거짓 0 vs FLAT vs NONE 분리를 로그로 분해.
 *
 * SharedP3는 이번 단계에서 제품 메인으로 즉시 승격하지는 않지만,
 * 다음 필드 테스트에서 상하경사 최종 소스의 1순위 후보로 검증할 수 있도록
 * [sharedP3UpDownVerificationSummary] 로그를 반드시 남긴다.
 */
object UpDownCollapseConfig {
    /** 동일 평면에서 상하 분리가 붕괴했다고 판단할 |ΔY| 상한 (m). 튜닝 가능. */
    const val COLLAPSE_DELTA_Y_M = 0.015f

    /** 볼/컵 법선 Y가 “거의 수직”으로 간주할 하한 (|n| 정규화 가정). */
    const val COLLAPSE_NORMAL_Y_MIN = 0.995f

    /** 표시용 forward%가 이 값 미만이면 FLAT(측정)으로 분류. */
    const val FLAT_FORWARD_PCT_EPS = 0.08f
}

data class UpDownCollapseAnalysis(
    val samePlaneCollapse: Boolean,
    val samePlaneCollapseReason: String?,
    val deltaYRawM: Float?,
    val ballNy: Float?,
    val cupNy: Float?
)

fun analyzeUpDownCollapse(ui: V31StateMachine.UiModel): UpDownCollapseAnalysis {
    val dy = ui.deltaYRaw
    val samePl = ui.ballCupSamePlane == true
    val ballNy = ui.ballGroundPlaneNormalY ?: ui.slopeDebugInfo?.ballNormal?.getOrNull(1)
    val cupNy = ui.slopeDebugInfo?.cupNormal?.getOrNull(1)
    if (!samePl) {
        return UpDownCollapseAnalysis(false, null, dy, ballNy, cupNy)
    }
    if (dy == null || !dy.isFinite()) {
        return UpDownCollapseAnalysis(false, "same_plane_missing_delta_y", dy, ballNy, cupNy)
    }
    val dySmall = abs(dy) < UpDownCollapseConfig.COLLAPSE_DELTA_Y_M
    val nyUp =
        ballNy != null && cupNy != null &&
            ballNy >= UpDownCollapseConfig.COLLAPSE_NORMAL_Y_MIN &&
            cupNy >= UpDownCollapseConfig.COLLAPSE_NORMAL_Y_MIN
    return when {
        dySmall && nyUp ->
            UpDownCollapseAnalysis(true, "same_plane_delta_y|normal_y", dy, ballNy, cupNy)
        dySmall && !nyUp ->
            UpDownCollapseAnalysis(true, "same_plane_delta_y_small_normals_not_upright", dy, ballNy, cupNy)
        else -> UpDownCollapseAnalysis(false, null, dy, ballNy, cupNy)
    }
}

/**
 * 거짓 0 감소·진짜 flat·측정 불가 구분용 라벨 (상하 forward% 기준).
 */
fun classifyUpDownFlatLabel(forwardPct: Float?): String {
    if (forwardPct == null || !forwardPct.isFinite()) return "NONE"
    return if (abs(forwardPct) < UpDownCollapseConfig.FLAT_FORWARD_PCT_EPS) {
        "FLAT_MEASURED"
    } else {
        "REAL_SLOPE"
    }
}

fun resolveUpDownNoSlope(
    gr: SlopeFieldTestLog.GraphicResolution,
    baseReason: String,
    baseClass: String,
    collapse: UpDownCollapseAnalysis
): Pair<String, String> {
    if (gr.finalSlopeAvailable) {
        return "OK" to "has_slope"
    }
    if (collapse.samePlaneCollapse) {
        return "NO_SLOPE_SAME_PLANE_COLLAPSE" to "input_collapse"
    }
    return baseReason to baseClass
}

/** 상하 최종 소스 후보 순서 (로그용). 실제 채택은 [SlopeFieldTestLog.resolveGraphic]. */
fun buildUpDownCandidateTrace(ui: V31StateMachine.UiModel, gr: SlopeFieldTestLog.GraphicResolution): String {
    val log = ui.sharedP3Log
    val shared = ui.experimentalSharedSlope
    val p1 = ui.slopeDebugInfo
    val candidates = buildList {
        if (shared != null && shared.blockedReason == null && shared.quality == "valid") add("SHARED")
        if (log != null && (log.quality == "GOOD" || log.quality == "DEGRADED" || log.finalForwardPct != null)) {
            add("SHARED_P3")
        }
        if (p1 != null && p1.forwardPct != null && p1.blockedReason == null) add("PHASE1")
    }.distinct()
    return "candidates=${candidates.joinToString(">")} final=${gr.source}"
}

/** SharedP3 — 다음 필드 상하 1순위 후보 검증용 요약 (품질·수치 한 줄). */
fun sharedP3UpDownVerificationSummary(log: SharedP3LogPayload?): String {
    if (log == null) return "null"
    fun f3(x: Float?) = x?.let { String.format(Locale.US, "%.3f", it) } ?: "null"
    fun f2(x: Float?) = x?.let { String.format(Locale.US, "%.2f", it) } ?: "null"
    fun f4(x: Float?) = x?.let { String.format(Locale.US, "%.4f", it) } ?: "null"
    return "quality=${log.quality} selectedType=${log.selectedType} normalYFinal=${f3(log.normalYFinal)} " +
        "forwardPct=${f2(log.finalForwardPct)} forwardRaw=${f2(log.finalForwardPctRaw)} " +
        "residual_m=${f4(log.finalResidualM)} samples=${log.finalSampleCount ?: "null"} " +
        "block=${log.finalBlockedReason ?: log.selectionBlockedReason ?: "none"} stabilize=${log.stabilizationReasons ?: "none"}"
}
