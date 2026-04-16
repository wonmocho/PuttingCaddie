package com.wmcho.puttingcaddie

import android.util.Log
import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * 1일차: 컵 track 관측 링 버퍼 + XZ 기준 클러스터(대표점) 후보.
 * [CupTrackClusterConfig]로 로그만 / 실제 커밋 적용을 분리한다.
 */
object CupTrackClusterConfig {
    /** true면 버퍼·클러스터 계산 및 [logCupTrackConfirm] 호출. */
    @JvmField var logOnly: Boolean = true

    /** true면 [clusterOverrideWorldForEnd]로 END 커밋 world를 덮어쓴다( [logOnly]와 함께 켜면 로그+적용). */
    @JvmField var enabled: Boolean = false

    /** 버퍼 최대 보관 시간(ns) — 약 1.0s */
    const val MAX_HISTORY_NS: Long = 1_000_000_000L

    /** 최대 프레임 수(20Hz 가정 시 ~20) */
    const val MAX_OBSERVATIONS: Int = 32

    /** 클러스터 채택 최소 support(라이브 월드 유효 샘플 수) */
    const val MIN_SUPPORT_FOR_CLUSTER: Int = 5

    /** XZ spread(m) 상한 — 초과 시 FALLBACK */
    const val MAX_SPREAD_XZ_M: Float = 0.45f
}

/**
 * AIM_END 틱당 1건. 저품질도 남기되 [qualityEligible]로 표시.
 */
data class CupTrackObservation(
    val timestampNs: Long,
    val liveWorldPoint: FloatArray?,
    val bestHitWorldPoint: FloatArray?,
    val projectedCupPx: Float?,
    val validHits: Int,
    val trackingStateName: String,
    val gridPlan: String?,
    val trackingOk: Boolean,
    /** 클러스터 후보로 쓸 만한지(트래킹·라이브 월드 존재 등) */
    val qualityEligible: Boolean
)

enum class CupTrackConfirmDecision {
    /** 라이브 월드 샘플 median(XZ), Y는 median */
    CLUSTER,
    /** 기존 [HitResult] 경로 유지 */
    FALLBACK_BESTHIT,
    /**
     * 클러스터 품질 부족 등 — 1일차는 UX 블록 없이 FALLBACK과 동일 처리,
     * 로그에만 구분.
     */
    RELOCK
}

data class CupTrackClusterResult(
    val decision: CupTrackConfirmDecision,
    /** CLUSTER일 때만 유효 */
    val world: FloatArray?,
    val historySize: Int,
    val selectedSupport: Int,
    val temporalSpanMs: Float,
    val spreadXZMeters: Float,
    val selectedSource: String,
    val fallbackReason: String?
)

private fun xzSpreadMeters(points: List<FloatArray>, median: FloatArray): Float {
    if (points.isEmpty()) return Float.POSITIVE_INFINITY
    var maxD = 0f
    val mx = median[0]
    val mz = median[2]
    for (p in points) {
        val dx = p[0] - mx
        val dz = p[2] - mz
        val d = sqrt(dx * dx + dz * dz)
        if (d.isFinite() && d > maxD) maxD = d
    }
    return maxD
}

private fun median3(points: List<FloatArray>): FloatArray {
    val xs = points.map { it[0] }.sorted()
    val ys = points.map { it[1] }.sorted()
    val zs = points.map { it[2] }.sorted()
    val n = points.size
    val mid = n / 2
    fun med(a: List<Float>): Float = if (n % 2 == 1) a[mid] else (a[mid - 1] + a[mid]) / 2f
    return floatArrayOf(med(xs), med(ys), med(zs))
}

/**
 * 라이브 월드가 있는 관측만 모아 XZ median 클러스터를 고른다.
 */
fun selectCupTrackClusterXZ(observations: List<CupTrackObservation>): CupTrackClusterResult {
    val withLive = observations.filter { it.liveWorldPoint != null && it.liveWorldPoint.size >= 3 }
    val eligible = withLive.filter { it.qualityEligible }
    val use = if (eligible.size >= 3) eligible else withLive
    val pts = use.map { it.liveWorldPoint!! }
    val historySize = observations.size
    if (pts.size < CupTrackClusterConfig.MIN_SUPPORT_FOR_CLUSTER) {
        return CupTrackClusterResult(
            decision = CupTrackConfirmDecision.FALLBACK_BESTHIT,
            world = null,
            historySize = historySize,
            selectedSupport = pts.size,
            temporalSpanMs = temporalSpanMs(use),
            spreadXZMeters = Float.NaN,
            selectedSource = "NONE",
            fallbackReason = "insufficient_live_support"
        )
    }
    val med = median3(pts)
    val spread = xzSpreadMeters(pts, med)
    val span = temporalSpanMs(use)
    if (!spread.isFinite() || spread > CupTrackClusterConfig.MAX_SPREAD_XZ_M) {
        return CupTrackClusterResult(
            decision = CupTrackConfirmDecision.RELOCK,
            world = null,
            historySize = historySize,
            selectedSupport = pts.size,
            temporalSpanMs = span,
            spreadXZMeters = spread,
            selectedSource = "LIVE_CLUSTER_XZ_MEDIAN",
            fallbackReason = "spread_xz_too_large"
        )
    }
    return CupTrackClusterResult(
        decision = CupTrackConfirmDecision.CLUSTER,
        world = med.copyOf(),
        historySize = historySize,
        selectedSupport = pts.size,
        temporalSpanMs = span,
        spreadXZMeters = spread,
        selectedSource = "LIVE_CLUSTER_XZ_MEDIAN",
        fallbackReason = null
    )
}

private fun temporalSpanMs(obs: List<CupTrackObservation>): Float {
    if (obs.size < 2) return 0f
    var minT = obs[0].timestampNs
    var maxT = obs[0].timestampNs
    for (o in obs) {
        if (o.timestampNs < minT) minT = o.timestampNs
        if (o.timestampNs > maxT) maxT = o.timestampNs
    }
    return (maxT - minT) / 1_000_000f
}

fun logCupTrackConfirm(tag: String, r: CupTrackClusterResult, clusterAppliedToCommit: Boolean) {
    val decisionName =
        when (r.decision) {
            CupTrackConfirmDecision.CLUSTER -> "CLUSTER"
            CupTrackConfirmDecision.FALLBACK_BESTHIT -> "FALLBACK_BESTHIT"
            CupTrackConfirmDecision.RELOCK -> "RELOCK"
        }
    val w = r.world
    val wStr =
        if (w != null && w.size >= 3) "(${ "%.4f".format(w[0]) },${ "%.4f".format(w[1]) },${ "%.4f".format(w[2]) })"
        else "null"
    val line =
        "historySize=${r.historySize} selectedSupport=${r.selectedSupport} " +
            "temporalSpanMs=${"%.1f".format(r.temporalSpanMs)} spreadXZM=${if (r.spreadXZMeters.isFinite()) "%.4f".format(r.spreadXZMeters) else "na"} " +
            "decision=$decisionName selectedSource=${r.selectedSource} clusterAppliedToCommit=$clusterAppliedToCommit " +
            "fallbackReason=${r.fallbackReason ?: "null"} world=$wStr"
    Log.i(tag, line)
}

fun buildCupTrackObservation(
    nowNs: Long,
    tracking: TrackingState,
    lastLiveCupWorld: FloatArray?,
    bestHitWorld: FloatArray?,
    sample: V31HitSampler.Sample?
): CupTrackObservation {
    val trackingOk = tracking == TrackingState.TRACKING
    val live = lastLiveCupWorld?.takeIf { it.size >= 3 }?.copyOf()
    val bh = bestHitWorld?.takeIf { it.size >= 3 }?.copyOf()
    val qualityEligible =
        trackingOk &&
            live != null &&
            ((sample?.validHits ?: 0) >= 1)
    return CupTrackObservation(
        timestampNs = nowNs,
        liveWorldPoint = live,
        bestHitWorldPoint = bh,
        projectedCupPx = sample?.gridProjectedCupPx,
        validHits = sample?.validHits ?: 0,
        trackingStateName = tracking.name,
        gridPlan = sample?.gridPlan,
        trackingOk = trackingOk,
        qualityEligible = qualityEligible
    )
}
