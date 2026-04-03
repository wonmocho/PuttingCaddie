package com.wmcho.puttingcaddie

import com.google.ar.core.HitResult

/** IDLE / AIM_START BALL 게이트에서만 사용. */
enum class BallGateState {
    IDLE,
    AIM_START
}

enum class BallMeasurementMode {
    NEW_MEASUREMENT,
    EDIT
}

enum class BallBlockedReason {
    NONE,
    TRUE_TOO_CLOSE,
    START_DISTANCE_NOT_READY,
    AR_WARMUP_NOT_READY,
    TRACKING_NOT_READY,
    HIT_NOT_FOUND,
    INSUFFICIENT_STABLE_HITS,
    UNSTABLE_DISTANCE,
    JUMP_REJECTED,
    PREVIOUS_STATE_INTERFERENCE
}

/**
 * 마지막 렌더 틱 기준 스냅샷. [onUiEvent](StartPressed)는 [tick]보다 먼저 실행되므로
 * 탭 직전 프레임의 값이 여기에 들어 있다.
 */
data class BallGateSnapshot(
    val gateState: BallGateState,
    val trackingStateName: String,
    val trackingOk: Boolean,
    val hit: HitResult?,
    val distanceFromCameraM: Float?,
    val minStartDistanceM: Float,
    val startDistanceReady: Boolean,
    val arWarmupReady: Boolean,
    val arWarmupSuccessCount: Int,
    val arWarmupRequired: Int,
    val stableHits: Int,
    val requiredStableHits: Int,
    val jumpRejected: Boolean,
    val unstableDistance: Boolean,
    val measurementMode: BallMeasurementMode,
    val previousBallPoseExists: Boolean,
    val previousCupPoseExists: Boolean
)

fun decideBallBlockedReason(s: BallGateSnapshot): BallBlockedReason {
    if (s.gateState == BallGateState.IDLE) {
        if (!s.trackingOk) return BallBlockedReason.TRACKING_NOT_READY
        if (!s.arWarmupReady) return BallBlockedReason.AR_WARMUP_NOT_READY
        if (s.measurementMode == BallMeasurementMode.NEW_MEASUREMENT &&
            (s.previousBallPoseExists || s.previousCupPoseExists)
        ) {
            return BallBlockedReason.PREVIOUS_STATE_INTERFERENCE
        }
        return BallBlockedReason.NONE
    }

    // AIM_START
    if (!s.trackingOk) return BallBlockedReason.TRACKING_NOT_READY
    if (s.hit == null) return BallBlockedReason.HIT_NOT_FOUND
    if (!s.arWarmupReady) return BallBlockedReason.AR_WARMUP_NOT_READY
    if (!s.startDistanceReady) return BallBlockedReason.START_DISTANCE_NOT_READY
    if (s.jumpRejected) return BallBlockedReason.JUMP_REJECTED
    if (s.unstableDistance) return BallBlockedReason.UNSTABLE_DISTANCE
    if (s.stableHits < s.requiredStableHits) return BallBlockedReason.INSUFFICIENT_STABLE_HITS

    val d = s.distanceFromCameraM
    if (d != null && d.isFinite() && d < s.minStartDistanceM) return BallBlockedReason.TRUE_TOO_CLOSE

    if (s.measurementMode == BallMeasurementMode.NEW_MEASUREMENT &&
        (s.previousBallPoseExists || s.previousCupPoseExists)
    ) {
        return BallBlockedReason.PREVIOUS_STATE_INTERFERENCE
    }
    return BallBlockedReason.NONE
}
