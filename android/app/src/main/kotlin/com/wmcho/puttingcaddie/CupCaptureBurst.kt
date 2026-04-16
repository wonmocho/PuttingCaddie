package com.wmcho.puttingcaddie

import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.sqrt

/** Capture burst: 6m+ / 저픽셀 경계에서 statistical 보강용 (거리 SSOT 정책 유지). */
object CaptureBurstPolicy {
    const val FRAME_TARGET: Int = 7
    const val MAX_SYNC_DELTA_NS: Long = 33_000_000L
    const val CENTER_OUTLIER_PX: Float = 18f
    const val WORLD_OUTLIER_XZ_M: Float = 0.35f
}

/**
 * 단일 틱에서 수집(거절은 [computeCaptureBurstConfirm]에서 처리).
 */
data class CaptureFrameObservation(
    val timestampNs: Long,
    val frameTimestampNs: Long?,
    val liveWorldPoint: FloatArray?,
    val projectedCupPx: Float?,
    val trackingStateName: String,
    val roiCenterX: Float,
    val roiCenterY: Float,
    val centerHitValid: Boolean,
    val liveSourcePlaneIntersection: Boolean
)

data class CaptureBurstConfirm(
    val representativeWorld: FloatArray,
    val acceptedFrameCount: Int,
    val rejectedFrameCount: Int,
    val spreadXZM: Float,
    val stdXZM: Float,
    val projectedCupPxMedian: Float,
    val poseSyncOk: Boolean
)

enum class CupFinalConfirmSource {
    LIVE_STATISTICAL_CONFIRM,
    LIVE_CAPTURE_BURST_CONFIRM
}

data class CupConfirmSelection(
    val source: CupFinalConfirmSource,
    val representativeWorld: FloatArray,
    val qualityTier: DistanceQualityTier,
    val spreadXZM: Float,
    val stdXZM: Float,
    val projectedCupPx: Float,
    val reason: String
)

/** 지시문: px·spread 기준 단순 tier (burst 게이트용). */
fun classifyDistanceTierSimple(projectedCupPx: Float?, spreadXZM: Float, stdXZM: Float): DistanceQualityTier {
    val px = projectedCupPx
    if (px == null || !px.isFinite()) return DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
    if (px >= 32f && spreadXZM <= 0.06f && stdXZM <= 0.03f) return DistanceQualityTier.GOOD
    if (px >= 24f && spreadXZM <= 0.12f && stdXZM <= 0.08f) return DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
    if (px < 24f) return DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY
    return DistanceQualityTier.GOOD
}

fun shouldRunCaptureBurst(
    distanceM: Float?,
    projectedCupPx: Float?,
    stat: StatisticalLiveConfirm?,
    currentTier: DistanceQualityTier?
): Boolean {
    val d = distanceM ?: 0f
    val px = projectedCupPx ?: 0f
    val spread = stat?.spreadXZM ?: Float.MAX_VALUE
    val std = stat?.stdXZM ?: Float.MAX_VALUE
    // 3m GOOD 등 근거리 고품질: burst 금지(회귀 방지)
    if (currentTier == DistanceQualityTier.GOOD && d < 6f) return false
    if (currentTier == DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY) return true
    if (px.isFinite() && px >= 28f && px < 45f) return true
    if (d >= 6f) return true
    if (spread > 0.25f) return true
    if (std > 0.15f) return true
    return false
}

fun allowLowPxDistanceSalvage(
    projectedCupPx: Float,
    spreadXZM: Float,
    stdXZM: Float,
    acceptedCount: Int
): Boolean {
    return projectedCupPx < 28f &&
        acceptedCount >= 5 &&
        spreadXZM <= 0.20f &&
        stdXZM <= 0.12f
}

fun scoreCupConfirm(
    projectedCupPx: Float,
    spreadXZM: Float,
    stdXZM: Float,
    acceptedFrameCount: Int
): Float {
    val px = projectedCupPx.takeIf { it.isFinite() } ?: 0f
    return px * 1.5f -
        spreadXZM * 100f -
        stdXZM * 80f +
        acceptedFrameCount * 2f
}

fun chooseBestCupConfirm(
    stat: StatisticalLiveConfirm?,
    capture: CaptureBurstConfirm?,
    fallbackProjectedCupPx: Float?
): CupConfirmSelection? {
    val pxFallback = fallbackProjectedCupPx?.takeIf { it.isFinite() } ?: 0f
    if (stat == null && capture == null) return null
    if (stat != null && capture == null) {
        val pxMed = stat.projectedCupPxMedian.takeIf { it.isFinite() } ?: pxFallback
        var tier = classifyDistanceTierSimple(pxMed, stat.spreadXZM, stat.stdXZM)
        if (tier == DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY &&
            allowLowPxDistanceSalvage(pxMed, stat.spreadXZM, stat.stdXZM, stat.supportCount)
        ) {
            tier = DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
        }
        return CupConfirmSelection(
            source = CupFinalConfirmSource.LIVE_STATISTICAL_CONFIRM,
            representativeWorld = stat.representativeWorld.copyOf(),
            qualityTier = tier,
            spreadXZM = stat.spreadXZM,
            stdXZM = stat.stdXZM,
            projectedCupPx = pxMed,
            reason = "capture_not_run"
        )
    }
    if (stat == null && capture != null) {
        val pxMed = capture.projectedCupPxMedian.takeIf { it.isFinite() } ?: pxFallback
        var tier = classifyDistanceTierSimple(pxMed, capture.spreadXZM, capture.stdXZM)
        if (tier == DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY &&
            allowLowPxDistanceSalvage(pxMed, capture.spreadXZM, capture.stdXZM, capture.acceptedFrameCount)
        ) {
            tier = DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
        }
        return CupConfirmSelection(
            source = CupFinalConfirmSource.LIVE_CAPTURE_BURST_CONFIRM,
            representativeWorld = capture.representativeWorld.copyOf(),
            qualityTier = tier,
            spreadXZM = capture.spreadXZM,
            stdXZM = capture.stdXZM,
            projectedCupPx = pxMed,
            reason = "statistical_unavailable"
        )
    }
    val s = stat!!
    val c = capture!!
    val sPx = s.projectedCupPxMedian.takeIf { it.isFinite() } ?: pxFallback
    val cPx = c.projectedCupPxMedian.takeIf { it.isFinite() } ?: pxFallback
    val statScore = scoreCupConfirm(sPx, s.spreadXZM, s.stdXZM, s.supportCount)
    val captureScore = scoreCupConfirm(cPx, c.spreadXZM, c.stdXZM, c.acceptedFrameCount)
    val pickCapture = captureScore > statScore
    return if (pickCapture) {
        var tier = classifyDistanceTierSimple(cPx, c.spreadXZM, c.stdXZM)
        if (tier == DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY &&
            allowLowPxDistanceSalvage(cPx, c.spreadXZM, c.stdXZM, c.acceptedFrameCount)
        ) {
            tier = DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
        }
        CupConfirmSelection(
            source = CupFinalConfirmSource.LIVE_CAPTURE_BURST_CONFIRM,
            representativeWorld = c.representativeWorld.copyOf(),
            qualityTier = tier,
            spreadXZM = c.spreadXZM,
            stdXZM = c.stdXZM,
            projectedCupPx = cPx,
            reason = "capture_better_than_statistical"
        )
    } else {
        var tier = classifyDistanceTierSimple(sPx, s.spreadXZM, s.stdXZM)
        if (tier == DistanceQualityTier.BLOCKED_LOW_CUP_QUALITY &&
            allowLowPxDistanceSalvage(sPx, s.spreadXZM, s.stdXZM, s.supportCount)
        ) {
            tier = DistanceQualityTier.LOW_QUALITY_DISTANCE_ONLY
        }
        CupConfirmSelection(
            source = CupFinalConfirmSource.LIVE_STATISTICAL_CONFIRM,
            representativeWorld = s.representativeWorld.copyOf(),
            qualityTier = tier,
            spreadXZM = s.spreadXZM,
            stdXZM = s.stdXZM,
            projectedCupPx = sPx,
            reason = "statistical_better_or_equal"
        )
    }
}

private fun medianFloat(values: List<Float>): Float {
    if (values.isEmpty()) return Float.NaN
    val s = values.sorted()
    val n = s.size
    val m = n / 2
    return if (n % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2f
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

fun computeCaptureBurstConfirm(frames: List<CaptureFrameObservation>): CaptureBurstConfirm? {
    var rejected = 0
    var syncOk = true
    data class Row(val w: FloatArray, val px: Float?, val cx: Float, val cy: Float)
    val rows = ArrayList<Row>()
    for (f in frames) {
        if (f.trackingStateName != TrackingState.TRACKING.name) {
            rejected++
            continue
        }
        val w = f.liveWorldPoint
        if (w == null || w.size < 3 || !f.centerHitValid || !f.liveSourcePlaneIntersection) {
            rejected++
            continue
        }
        val fts = f.frameTimestampNs
        if (fts != null && abs(fts - f.timestampNs) > CaptureBurstPolicy.MAX_SYNC_DELTA_NS) {
            syncOk = false
            rejected++
            continue
        }
        rows.add(Row(w.copyOf(), f.projectedCupPx?.takeIf { it.isFinite() }, f.roiCenterX, f.roiCenterY))
    }
    if (rows.size < CupLiveStatisticalPolicy.MIN_SUPPORT) return null

    val medCx = medianFloat(rows.map { it.cx })
    val medCy = medianFloat(rows.map { it.cy })
    val afterCenter = ArrayList<Row>()
    for (r in rows) {
        val dx = r.cx - medCx
        val dy = r.cy - medCy
        if (sqrt(dx * dx + dy * dy) > CaptureBurstPolicy.CENTER_OUTLIER_PX) {
            rejected++
            continue
        }
        afterCenter.add(r)
    }
    if (afterCenter.size < CupLiveStatisticalPolicy.MIN_SUPPORT) return null

    val med = median3(afterCenter.map { it.w })
    val afterWorld = ArrayList<Row>()
    for (r in afterCenter) {
        val p = r.w
        val ddx = p[0] - med[0]
        val ddz = p[2] - med[2]
        val d = sqrt(ddx * ddx + ddz * ddz)
        if (d > CaptureBurstPolicy.WORLD_OUTLIER_XZ_M) {
            rejected++
            continue
        }
        afterWorld.add(r)
    }
    if (afterWorld.size < CupLiveStatisticalPolicy.MIN_SUPPORT) return null

    val pts = afterWorld.map { it.w }
    val finalMed = median3(pts)
    val spread = xzSpreadFromMedian(pts, finalMed)
    val std = xzStdRms(pts)
    val pxVals = afterWorld.mapNotNull { it.px }
    val pxMed = medianFloat(if (pxVals.isNotEmpty()) pxVals else listOf(0f))
    return CaptureBurstConfirm(
        representativeWorld = finalMed.copyOf(),
        acceptedFrameCount = afterWorld.size,
        rejectedFrameCount = rejected,
        spreadXZM = spread,
        stdXZM = std,
        projectedCupPxMedian = if (pxMed.isFinite()) pxMed else 0f,
        poseSyncOk = syncOk
    )
}

fun buildCaptureFrameObservation(
    nowNs: Long,
    frame: Frame,
    tracking: TrackingState,
    sample: V31HitSampler.Sample,
    roiScreen: android.graphics.RectF,
    lastLiveCupWorld: FloatArray?,
    centerHitValid: Boolean,
    liveSourcePlaneIntersection: Boolean
): CaptureFrameObservation {
    return CaptureFrameObservation(
        timestampNs = nowNs,
        frameTimestampNs = frame.timestamp,
        liveWorldPoint = lastLiveCupWorld?.takeIf { it.size >= 3 }?.copyOf(),
        projectedCupPx = sample.gridProjectedCupPx,
        trackingStateName = tracking.name,
        roiCenterX = roiScreen.centerX(),
        roiCenterY = roiScreen.centerY(),
        centerHitValid = centerHitValid,
        liveSourcePlaneIntersection = liveSourcePlaneIntersection
    )
}
