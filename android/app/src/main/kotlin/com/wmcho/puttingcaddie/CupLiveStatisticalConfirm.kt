package com.wmcho.puttingcaddie

import com.google.ar.core.TrackingState
import kotlin.math.sqrt

/**
 * 컵 END 커밋 직전: AIM_END~STABILIZING_END 동안 수집한 LIVE 월드 히스토리로 대표점을 뽑는다.
 * (버튼 순간 1틱 대신 직전 구간 통계 — 제품 기본 경로 1차)
 */
enum class DistanceQualityTier {
    GOOD,
    LOW_QUALITY_DISTANCE_ONLY,
    BLOCKED_LOW_CUP_QUALITY
}

enum class CupConfirmDecision {
    LIVE_STATISTICAL_CONFIRM,
    LIVE_LOW_PX_SALVAGE,
    LIVE_CAPTURE_BURST_CONFIRM,
    LIVE_DISTANCE_WORLD,
    CLUSTER_OVERRIDE
}

data class CupLiveObservation(
    val timestampNs: Long,
    val frameTimestampNs: Long?,
    val liveWorldPoint: FloatArray?,
    val projectedCupPx: Float?,
    val centerHitValid: Boolean,
    val liveSourcePlaneIntersection: Boolean,
    val trackingStateName: String,
    val validSampleCount: Int?
)

data class StatisticalLiveConfirm(
    val representativeWorld: FloatArray,
    val supportCount: Int,
    val temporalSpanMs: Float,
    val spreadXZM: Float,
    val stdXZM: Float,
    val rawSupportCount: Int,
    val selectedSupportCount: Int,
    val trimRatioUsed: Float,
    val spreadBeforeTrimXZM: Float,
    val stdBeforeTrimXZM: Float,
    /** eligible 표본 projectedCupPx 중앙값 — 스코어·tier 보조 */
    val projectedCupPxMedian: Float,
    /** 표준 임계 통과 */
    val liveStable: Boolean,
    /** 저픽셀 구간에서만, 더 엄격한 임계로 허용 */
    val lowPxSalvage: Boolean
)

object CupLiveStatisticalPolicy {
    const val MAX_HISTORY_NS: Long = 1_000_000_000L
    const val MAX_OBSERVATIONS: Int = 36
    const val MIN_SUPPORT: Int = 6
    const val PRIMARY_RECENT_WINDOW: Int = 12
    const val SECONDARY_RECENT_WINDOW: Int = 16
    const val MAX_SPREAD_XZ_M: Float = 0.25f
    const val MAX_STD_XZ_M: Float = 0.15f
    const val LOW_PX_THRESHOLD: Float = 28f
    const val GOOD_PX_THRESHOLD: Float = 45f
    const val SALVAGE_MAX_SPREAD_XZ_M: Float = 0.20f
    const val SALVAGE_MAX_STD_XZ_M: Float = 0.12f
    const val SALVAGE_MIN_VALID_HITS: Int = 3
}

private fun trimmedMeanAroundMedian(points: List<FloatArray>, trimRatio: Float): FloatArray {
    if (points.isEmpty()) return floatArrayOf(0f, 0f, 0f)
    if (points.size <= 2) return median3(points)
    val med = median3(points)
    val keepCount = (points.size * (1f - trimRatio)).toInt().coerceAtLeast(2).coerceAtMost(points.size)
    val kept =
        points
            .sortedBy {
                val dx = it[0] - med[0]
                val dz = it[2] - med[2]
                dx * dx + dz * dz
            }
            .take(keepCount)
    var sx = 0f
    var sy = 0f
    var sz = 0f
    for (p in kept) {
        sx += p[0]
        sy += p[1]
        sz += p[2]
    }
    val n = kept.size.toFloat().coerceAtLeast(1f)
    return floatArrayOf(sx / n, sy / n, sz / n)
}

private fun median3(points: List<FloatArray>): FloatArray {
    val xs = points.map { it[0] }.sorted()
    val ys = points.map { it[1] }.sorted()
    val zs = points.map { it[2] }.sorted()
    val n = points.size
    val mid = n / 2
    fun med(a: List<Float>): Float =
        if (n % 2 == 1) a[mid] else (a[mid - 1] + a[mid]) / 2f
    return floatArrayOf(med(xs), med(ys), med(zs))
}

private fun xzSpreadFromMedian(points: List<FloatArray>, median: FloatArray): Float {
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

private fun medianPxFromObservations(eligible: List<CupLiveObservation>): Float {
    val pxVals = eligible.mapNotNull { it.projectedCupPx?.takeIf { p -> p.isFinite() } }
    if (pxVals.isEmpty()) return Float.NaN
    val s = pxVals.sorted()
    val n = s.size
    val m = n / 2
    return if (n % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
}

private fun xzStdRms(points: List<FloatArray>): Float {
    val n = points.size
    if (n < 2) return Float.POSITIVE_INFINITY
    var sx = 0.0
    var sz = 0.0
    for (p in points) {
        sx += p[0].toDouble()
        sz += p[2].toDouble()
    }
    val mx = (sx / n).toFloat()
    val mz = (sz / n).toFloat()
    var sum = 0.0
    for (p in points) {
        val dx = (p[0] - mx).toDouble()
        val dz = (p[2] - mz).toDouble()
        sum += dx * dx + dz * dz
    }
    return sqrt((sum / (n - 1)).toFloat())
}

/**
 * @param commitValidHits STABILIZING_END 커밋 틱의 멀티레이 validHits (salvage 게이트)
 */
fun computeStatisticalLiveConfirm(
    observations: List<CupLiveObservation>,
    projectedCupPx: Float?,
    commitValidHits: Int
): StatisticalLiveConfirm? {
    val eligible = observations.filter {
        it.trackingStateName == TrackingState.TRACKING.name &&
            it.centerHitValid &&
            it.liveSourcePlaneIntersection &&
            it.liveWorldPoint != null &&
            it.liveWorldPoint.size >= 3
    }
    if (eligible.size < CupLiveStatisticalPolicy.MIN_SUPPORT) return null
    val rawEligibleCount = eligible.size

    val recentPrimary = eligible.takeLast(CupLiveStatisticalPolicy.PRIMARY_RECENT_WINDOW)
    val stablePrimary = recentPrimary.filter { (it.validSampleCount ?: 0) >= 3 }
    val selectedObs =
        if (stablePrimary.size >= CupLiveStatisticalPolicy.MIN_SUPPORT) {
            stablePrimary
        } else {
            val recentSecondary = eligible.takeLast(CupLiveStatisticalPolicy.SECONDARY_RECENT_WINDOW)
            val stableSecondary = recentSecondary.filter { (it.validSampleCount ?: 0) >= 2 }
            if (stableSecondary.size >= CupLiveStatisticalPolicy.MIN_SUPPORT) stableSecondary else eligible
        }
    if (selectedObs.size < CupLiveStatisticalPolicy.MIN_SUPPORT) return null

    val selectedPts = selectedObs.map { it.liveWorldPoint!! }
    val pxMedObs = medianPxFromObservations(selectedObs)
    val trimRatio =
        when {
            pxMedObs.isFinite() && pxMedObs < 30f -> 0.35f
            pxMedObs.isFinite() && pxMedObs < 40f -> 0.25f
            else -> 0.18f
        }
    val beforeMed = median3(selectedPts)
    val spreadBefore = xzSpreadFromMedian(selectedPts, beforeMed)
    val stdBefore = xzStdRms(selectedPts)
    val representative = trimmedMeanAroundMedian(selectedPts, trimRatio)
    val spread = xzSpreadFromMedian(selectedPts, representative)
    val std = xzStdRms(selectedPts)
    val t0 = selectedObs.first().timestampNs
    val t1 = selectedObs.last().timestampNs
    val spanMs = ((t1 - t0) / 1_000_000L).toFloat().coerceAtLeast(0f)

    val px = projectedCupPx
    val pxLow = px != null && px.isFinite() && px < CupLiveStatisticalPolicy.LOW_PX_THRESHOLD

    val standardOk =
        spread <= CupLiveStatisticalPolicy.MAX_SPREAD_XZ_M &&
            std <= CupLiveStatisticalPolicy.MAX_STD_XZ_M

    if (standardOk) {
        return StatisticalLiveConfirm(
            representativeWorld = representative.copyOf(),
            supportCount = selectedObs.size,
            temporalSpanMs = spanMs,
            spreadXZM = spread,
            stdXZM = std,
            rawSupportCount = rawEligibleCount,
            selectedSupportCount = selectedObs.size,
            trimRatioUsed = trimRatio,
            spreadBeforeTrimXZM = spreadBefore,
            stdBeforeTrimXZM = stdBefore,
            projectedCupPxMedian = pxMedObs,
            liveStable = true,
            lowPxSalvage = false
        )
    }

    if (pxLow &&
        commitValidHits >= CupLiveStatisticalPolicy.SALVAGE_MIN_VALID_HITS &&
        spread <= CupLiveStatisticalPolicy.SALVAGE_MAX_SPREAD_XZ_M &&
        std <= CupLiveStatisticalPolicy.SALVAGE_MAX_STD_XZ_M &&
        eligible.size >= CupLiveStatisticalPolicy.MIN_SUPPORT
    ) {
        return StatisticalLiveConfirm(
            representativeWorld = representative.copyOf(),
            supportCount = selectedObs.size,
            temporalSpanMs = spanMs,
            spreadXZM = spread,
            stdXZM = std,
            rawSupportCount = rawEligibleCount,
            selectedSupportCount = selectedObs.size,
            trimRatioUsed = trimRatio,
            spreadBeforeTrimXZM = spreadBefore,
            stdBeforeTrimXZM = stdBefore,
            projectedCupPxMedian = pxMedObs,
            liveStable = true,
            lowPxSalvage = true
        )
    }

    return null
}

data class CupCommitResolution(
    val world: FloatArray?,
    val decision: CupConfirmDecision,
    val source: String,
    val qualityTier: DistanceQualityTier,
    val statistical: StatisticalLiveConfirm?,
    val lowPxSalvage: Boolean,
    val captureBurstUsed: Boolean = false,
    val captureBurstComputed: Boolean = false,
    val captureBurstAcceptedFrames: Int? = null,
    val captureBurstRejectedFrames: Int? = null,
    val captureSpreadXZM: Float? = null,
    val captureStdXZM: Float? = null,
    val cupConfirmReason: String? = null,
    val finalCupConfirmSource: String? = null
)

/**
 * 컵 END 커밋 월드: 클러스터(실험) → (통계 vs 캡처 burst 스코어) → 단일 프레임 LIVE.
 * [captureBurstConfirm]: burst 수집 후 [computeCaptureBurstConfirm] 결과(없으면 null).
 */
fun finalizeCupCommitFromLiveHistory(
    clusterEnabled: Boolean,
    clusterWorld: FloatArray?,
    observations: List<CupLiveObservation>,
    projectedCupPx: Float?,
    commitValidHits: Int,
    fallbackLiveWorld: FloatArray?,
    captureBurstConfirm: CaptureBurstConfirm?,
    captureBurstUsed: Boolean
): CupCommitResolution {
    if (clusterEnabled && clusterWorld != null) {
        return CupCommitResolution(
            world = clusterWorld.copyOf(),
            decision = CupConfirmDecision.CLUSTER_OVERRIDE,
            source = "LIVE_CLUSTER_XZ_MEDIAN",
            qualityTier = distanceQualityTier(projectedCupPx, statisticalUsed = false, lowPxSalvage = false),
            statistical = null,
            lowPxSalvage = false,
            captureBurstUsed = false,
            captureBurstComputed = false,
            cupConfirmReason = "cluster_override",
            finalCupConfirmSource = "LIVE_CLUSTER_XZ_MEDIAN"
        )
    }
    val stat = computeStatisticalLiveConfirm(observations, projectedCupPx, commitValidHits)
    val selection = chooseBestCupConfirm(stat, captureBurstConfirm, projectedCupPx)
    val captureComputed = captureBurstConfirm != null

    if (selection != null) {
        if (selection.qualityTier == DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY) {
            return CupCommitResolution(
                world = null,
                decision = CupConfirmDecision.LIVE_DISTANCE_WORLD,
                source = "LIVE_DISTANCE_WORLD",
                qualityTier = DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY,
                statistical = stat,
                lowPxSalvage = false,
                captureBurstUsed = captureBurstUsed,
                captureBurstComputed = captureComputed,
                captureBurstAcceptedFrames = captureBurstConfirm?.acceptedFrameCount,
                captureBurstRejectedFrames = captureBurstConfirm?.rejectedFrameCount,
                captureSpreadXZM = captureBurstConfirm?.spreadXZM,
                captureStdXZM = captureBurstConfirm?.stdXZM,
                cupConfirmReason = selection.reason + "_blocked_tier",
                finalCupConfirmSource = selection.source.name
            )
        }
        val dec =
            when (selection.source) {
                CupFinalConfirmSource.LIVE_CAPTURE_BURST_CONFIRM -> CupConfirmDecision.LIVE_CAPTURE_BURST_CONFIRM
                CupFinalConfirmSource.LIVE_STATISTICAL_CONFIRM ->
                    if (stat?.lowPxSalvage == true) {
                        CupConfirmDecision.LIVE_LOW_PX_SALVAGE
                    } else {
                        CupConfirmDecision.LIVE_STATISTICAL_CONFIRM
                    }
            }
        val src =
            when (selection.source) {
                CupFinalConfirmSource.LIVE_CAPTURE_BURST_CONFIRM -> "LIVE_CAPTURE_BURST_CONFIRM"
                CupFinalConfirmSource.LIVE_STATISTICAL_CONFIRM ->
                    if (stat?.lowPxSalvage == true) "LIVE_LOW_PX_SALVAGE" else "LIVE_STATISTICAL_CONFIRM"
            }
        return CupCommitResolution(
            world = selection.representativeWorld.copyOf(),
            decision = dec,
            source = src,
            qualityTier = selection.qualityTier,
            statistical = stat,
            lowPxSalvage = stat?.lowPxSalvage == true && selection.source == CupFinalConfirmSource.LIVE_STATISTICAL_CONFIRM,
            captureBurstUsed = captureBurstUsed,
            captureBurstComputed = captureComputed,
            captureBurstAcceptedFrames = captureBurstConfirm?.acceptedFrameCount,
            captureBurstRejectedFrames = captureBurstConfirm?.rejectedFrameCount,
            captureSpreadXZM = captureBurstConfirm?.spreadXZM,
            captureStdXZM = captureBurstConfirm?.stdXZM,
            cupConfirmReason = selection.reason,
            finalCupConfirmSource = selection.source.name
        )
    }

    return CupCommitResolution(
        world = fallbackLiveWorld?.copyOf(),
        decision = CupConfirmDecision.LIVE_DISTANCE_WORLD,
        source = "LIVE_DISTANCE_WORLD",
        qualityTier = distanceQualityTier(projectedCupPx, statisticalUsed = false, lowPxSalvage = false),
        statistical = stat,
        lowPxSalvage = false,
        captureBurstUsed = captureBurstUsed,
        captureBurstComputed = captureComputed,
        captureBurstAcceptedFrames = captureBurstConfirm?.acceptedFrameCount,
        captureBurstRejectedFrames = captureBurstConfirm?.rejectedFrameCount,
        captureSpreadXZM = captureBurstConfirm?.spreadXZM,
        captureStdXZM = captureBurstConfirm?.stdXZM,
        cupConfirmReason = "fallback_live_distance_world",
        finalCupConfirmSource = null
    )
}

/** Burst 없이 확정할 때 [finalizeCupCommitFromLiveHistory]와 동일 경로. */
fun resolveCupCommitWorld(
    clusterEnabled: Boolean,
    clusterWorld: FloatArray?,
    observations: List<CupLiveObservation>,
    projectedCupPx: Float?,
    commitValidHits: Int,
    fallbackLiveWorld: FloatArray?
): CupCommitResolution =
    finalizeCupCommitFromLiveHistory(
        clusterEnabled = clusterEnabled,
        clusterWorld = clusterWorld,
        observations = observations,
        projectedCupPx = projectedCupPx,
        commitValidHits = commitValidHits,
        fallbackLiveWorld = fallbackLiveWorld,
        captureBurstConfirm = null,
        captureBurstUsed = false
    )

fun distanceQualityTier(
    projectedCupPx: Float?,
    statisticalUsed: Boolean,
    lowPxSalvage: Boolean
): DistanceQualityTier {
    val px = projectedCupPx
    if (px == null || !px.isFinite()) return DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
    if (px >= CupLiveStatisticalPolicy.GOOD_PX_THRESHOLD) return DistanceQualityTier.GOOD
    if (px >= CupLiveStatisticalPolicy.LOW_PX_THRESHOLD) return DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
    return if (statisticalUsed && lowPxSalvage) {
        DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
    } else {
        DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY
    }
}
