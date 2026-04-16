package com.wmcho.puttingcaddie

import kotlin.math.abs

enum class RawQuality { GOOD, SOFT, WEAK, BLOCK }
enum class UiSlopeState { FULL, DEGRADED, BLOCK }
enum class Source { SHARED, LOCAL, HV, NONE }

data class AxisCandidate(
    val value: Float?,
    val quality: RawQuality,
    val source: Source,
    val residual: Float?,
    val spread: Float?,
    val driftDeg: Float?,
    val sampleCount: Int?,
    val samePlane: Boolean?,
    val rejectReason: String?,
    val hMeters: Float?
)

data class SlopeCandidateSet(
    val verticalCandidate: AxisCandidate,
    val lateralCandidate: AxisCandidate
)

private fun rank(q: RawQuality): Int =
    when (q) {
        RawQuality.GOOD -> 4
        RawQuality.SOFT -> 3
        RawQuality.WEAK -> 2
        RawQuality.BLOCK -> 1
    }

private fun qualityFromMetrics(
    blockedReason: String?,
    residual: Float?,
    spread: Float?,
    driftDeg: Float?,
    projectedCupPx: Float?
): RawQuality {
    if (!blockedReason.isNullOrBlank()) return RawQuality.BLOCK
    val absDrift = driftDeg?.let { abs(it) }
    if (residual != null && residual > 0.05f) return RawQuality.BLOCK
    if (spread != null && spread > 1.20f) return RawQuality.BLOCK
    if (absDrift != null && absDrift > 14f) return RawQuality.BLOCK
    if (projectedCupPx != null && projectedCupPx < 30f) return RawQuality.WEAK
    if ((residual != null && residual > 0.02f) ||
        (spread != null && spread > 0.70f) ||
        (absDrift != null && absDrift > 8f)
    ) {
        return RawQuality.SOFT
    }
    return RawQuality.GOOD
}

fun buildSharedCandidate(ui: V31StateMachine.UiModel): SlopeCandidateSet {
    val shared = ui.experimentalSharedSlope
    val log = ui.sharedP3Log
    val spread = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM
    val projected = ui.multiRayProjectedCupPx
    val blocked = log?.finalBlockedReason ?: shared?.blockedReason
    val drift = shared?.planeDriftDeg
    val residual = log?.finalResidualM
    val sampleCount = log?.finalSampleCount
    val samePlane = ui.ballCupSamePlane

    val verticalQ = qualityFromMetrics(blocked, residual, spread, drift, projected)
    val lateralQ =
        if (verticalQ == RawQuality.BLOCK) {
            RawQuality.BLOCK
        } else {
            // lateral은 더 엄격: spread/drift/샘플 수가 약하면 즉시 WEAK/BLOCK
            when {
                sampleCount != null && sampleCount < 5 -> RawQuality.BLOCK
                spread != null && spread > 0.50f -> RawQuality.BLOCK
                drift != null && abs(drift) > 6f -> RawQuality.SOFT
                else -> verticalQ
            }
        }

    return SlopeCandidateSet(
        verticalCandidate =
            AxisCandidate(
                value = shared?.forwardPct,
                quality = verticalQ,
                source = Source.SHARED,
                residual = residual,
                spread = spread,
                driftDeg = drift,
                sampleCount = sampleCount,
                samePlane = samePlane,
                rejectReason = if (verticalQ == RawQuality.BLOCK) blocked ?: "shared_vertical_blocked" else null,
                hMeters = shared?.hMeters
            ),
        lateralCandidate =
            AxisCandidate(
                value = shared?.lateralPct,
                quality = lateralQ,
                source = Source.SHARED,
                residual = residual,
                spread = spread,
                driftDeg = drift,
                sampleCount = sampleCount,
                samePlane = samePlane,
                rejectReason = if (lateralQ == RawQuality.BLOCK) "shared_lateral_blocked" else null,
                hMeters = shared?.hMeters
            )
    )
}

fun buildLocalCandidate(ui: V31StateMachine.UiModel): SlopeCandidateSet {
    val local = ui.slopeDebugInfo
    val spread = ui.slopeExperimentalResult?.experimentalDiagnostics?.sampleSpreadCupM
    val projected = ui.multiRayProjectedCupPx
    val drift = local?.planeDriftDeg
    val blocked = local?.blockedReason
    val samePlane = ui.ballCupSamePlane

    val verticalQ = qualityFromMetrics(blocked, null, spread, drift, projected)
    val lateralQ =
        if (verticalQ == RawQuality.BLOCK) {
            RawQuality.BLOCK
        } else {
            when {
                spread != null && spread > 0.45f -> RawQuality.BLOCK
                drift != null && abs(drift) > 5.5f -> RawQuality.SOFT
                else -> verticalQ
            }
        }

    return SlopeCandidateSet(
        verticalCandidate =
            AxisCandidate(
                value = local?.forwardPct,
                quality = verticalQ,
                source = Source.LOCAL,
                residual = null,
                spread = spread,
                driftDeg = drift,
                sampleCount = null,
                samePlane = samePlane,
                rejectReason = if (verticalQ == RawQuality.BLOCK) blocked ?: "local_vertical_blocked" else null,
                hMeters = local?.hMeters
            ),
        lateralCandidate =
            AxisCandidate(
                value = local?.lateralPct,
                quality = lateralQ,
                source = Source.LOCAL,
                residual = null,
                spread = spread,
                driftDeg = drift,
                sampleCount = null,
                samePlane = samePlane,
                rejectReason = if (lateralQ == RawQuality.BLOCK) "local_lateral_blocked" else null,
                hMeters = local?.hMeters
            )
    )
}

fun buildHvVerticalCandidate(ui: V31StateMachine.UiModel): AxisCandidate {
    val hv = ui.horizontalVerticalMeters
    val h = hv?.first
    val v = hv?.second
    val trackingOk = ui.trackingState == "TRACKING"
    val distanceConfirmed = ui.distanceMeters.isFinite() && ui.distanceMeters > 0f
    val spreadOk = (ui.confirmGateWorldSpreadM ?: 0f) <= 0.18f
    val deltaY = ui.deltaYRaw
    val deltaYNoiseFloorOk = deltaY != null && abs(deltaY) >= 0.003f
    val jumpStable = ui.confirmRejectedReason != "transform_version_changed"

    val baseReject =
        when {
            !trackingOk -> "tracking_not_ready"
            !distanceConfirmed -> "distance_not_confirmed"
            hv == null || h == null || v == null || !h.isFinite() || !v.isFinite() || h <= 1e-4f -> "hv_missing"
            !spreadOk -> "world_spread_large"
            !deltaYNoiseFloorOk -> "deltaY_below_noise_floor"
            !jumpStable -> "transform_jump_detected"
            else -> null
        }
    val quality = if (baseReject == null) RawQuality.SOFT else RawQuality.BLOCK
    val pct = if (baseReject == null) (v!! / h!!) * 100f else null

    return AxisCandidate(
        value = pct,
        quality = quality,
        source = Source.HV,
        residual = null,
        spread = ui.confirmGateWorldSpreadM,
        driftDeg = null,
        sampleCount = ui.confirmGateStableFrameCount,
        samePlane = ui.ballCupSamePlane,
        rejectReason = baseReject,
        hMeters = h
    )
}

fun decideBestVertical(shared: AxisCandidate, local: AxisCandidate, hv: AxisCandidate): AxisCandidate {
    val candidates = listOf(shared, local, hv)
    return candidates.maxWithOrNull(
        compareBy<AxisCandidate> { rank(it.quality) }
            .thenBy {
                when (it.source) {
                    Source.SHARED -> 3
                    Source.LOCAL -> 2
                    Source.HV -> 1
                    Source.NONE -> 0
                }
            }
    ) ?: hv
}

fun decideBestLateral(shared: AxisCandidate, local: AxisCandidate): AxisCandidate {
    val candidates = listOf(shared, local)
    return candidates.maxWithOrNull(
        compareBy<AxisCandidate> { rank(it.quality) }
            .thenBy {
                when (it.source) {
                    Source.SHARED -> 2
                    Source.LOCAL -> 1
                    else -> 0
                }
            }
    ) ?: AxisCandidate(null, RawQuality.BLOCK, Source.NONE, null, null, null, null, null, "no_lateral_candidate", null)
}

fun decideSlopeDisplayState(verticalBest: AxisCandidate, lateralBest: AxisCandidate): UiSlopeState {
    val verticalUsable = verticalBest.quality != RawQuality.BLOCK && verticalBest.value != null
    if (!verticalUsable) return UiSlopeState.BLOCK
    return if (verticalBest.quality == RawQuality.GOOD && lateralBest.quality == RawQuality.GOOD && lateralBest.value != null) {
        UiSlopeState.FULL
    } else {
        UiSlopeState.DEGRADED
    }
}

