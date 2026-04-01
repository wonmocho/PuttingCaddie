package com.wmcho.puttingcaddie

import kotlin.math.abs
import kotlin.math.max

/**
 * Field-test용 초기값 — 로그 비교 후 조정. 정책 확정값이 아님.
 */
object FinalDistanceGuardConfig {
    const val MAX_PLANE_ANCHOR_DELTA_M = 0.5f
    const val MAX_PLANE_ANCHOR_DELTA_RATIO = 0.10f
    const val MIN_PROJECTED_CUP_PX = 45f
    const val MIN_ANCHOR_M = 0.01f
    const val MAX_PLANE_ANGLE_DEG_FOR_DEGRADED = 12f
}

data class FinalDistanceGuardResult(
    val finalMeters: Float,
    val livePlaneMeters: Float,
    val livePlaneBeforeSource: String,
    val sourceAfterGuard: String,
    val guardTriggered: Boolean,
    val reasonsJoined: String,
    val anchorInvalidReason: String?,
    val deltaM: Float,
    val deltaRatio: Float
)

/**
 * 기본은 live/plane 후보 유지.
 * 위험 신호 + anchor 유효 시에만 [finalMeters] = anchor.
 * 위험인데 anchor 무효면 live/plane 유지 + [anchorInvalidReason].
 */
object FinalDistanceGuard {
    fun apply(
        endLiveSnapshotMeters: Float,
        lastDisplayDistanceMeters: Float,
        anchorMeters: Float,
        anchorsBothPresent: Boolean,
        projectedCupPx: Float?,
        liveStable: Boolean?,
        finalFallbackUsed: Boolean,
        ballCupPlaneAngleDeg: Float?
    ): FinalDistanceGuardResult {
        val livePlane =
            when {
                endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f -> endLiveSnapshotMeters
                lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> lastDisplayDistanceMeters
                else -> 0f
            }
        val beforeSource =
            when {
                !livePlane.isFinite() || livePlane <= 0f -> "NONE"
                endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f -> "LIVE_PLANE_SNAPSHOT"
                else -> "LAST_DISPLAY_FALLBACK"
            }

        val anchorInvalid: String? =
            when {
                !anchorsBothPresent -> "anchors_missing"
                !anchorMeters.isFinite() || anchorMeters <= FinalDistanceGuardConfig.MIN_ANCHOR_M -> "anchor_distance_invalid"
                else -> null
            }

        val deltaM =
            if (livePlane > 0f && anchorMeters.isFinite() && anchorMeters > FinalDistanceGuardConfig.MIN_ANCHOR_M) {
                abs(livePlane - anchorMeters)
            } else {
                0f
            }
        val denom = max(anchorMeters, FinalDistanceGuardConfig.MIN_ANCHOR_M)
        val deltaRatio =
            if (livePlane > 0f && anchorMeters.isFinite() && anchorMeters > FinalDistanceGuardConfig.MIN_ANCHOR_M) {
                deltaM / denom
            } else {
                0f
            }

        val primaryDelta = deltaM > FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_M
        val primaryRel = deltaRatio > FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_RATIO
        val primaryPx =
            projectedCupPx != null && projectedCupPx.isFinite() &&
                projectedCupPx < FinalDistanceGuardConfig.MIN_PROJECTED_CUP_PX

        val distDegraded =
            liveStable == false ||
                finalFallbackUsed ||
                (ballCupPlaneAngleDeg != null && ballCupPlaneAngleDeg > FinalDistanceGuardConfig.MAX_PLANE_ANGLE_DEG_FOR_DEGRADED)

        val reasons = mutableListOf<String>()
        if (primaryDelta) reasons.add("delta_too_large")
        if (primaryRel) reasons.add("relative_delta_too_large")
        if (primaryPx) reasons.add("projected_px_too_small")
        if (distDegraded) {
            val hasPrimary = primaryDelta || primaryRel || primaryPx
            if (hasPrimary) reasons.add("degraded_plus_triggers") else reasons.add("degraded_only")
        }

        val planeUnsafe = primaryDelta || primaryRel || primaryPx || distDegraded

        val finalMeters: Float
        val sourceAfter: String
        val triggered: Boolean
        val anchorInvReason: String?

        when {
            planeUnsafe && anchorInvalid == null -> {
                finalMeters = anchorMeters
                sourceAfter = "ANCHOR_FALLBACK"
                triggered = true
                anchorInvReason = null
            }
            planeUnsafe && anchorInvalid != null -> {
                finalMeters = if (livePlane.isFinite() && livePlane > 0f) livePlane else 0f
                sourceAfter = beforeSource
                triggered = false
                anchorInvReason = anchorInvalid
            }
            else -> {
                finalMeters = if (livePlane.isFinite() && livePlane > 0f) livePlane else 0f
                sourceAfter = beforeSource
                triggered = false
                anchorInvReason = null
            }
        }

        return FinalDistanceGuardResult(
            finalMeters = finalMeters,
            livePlaneMeters = livePlane,
            livePlaneBeforeSource = beforeSource,
            sourceAfterGuard = sourceAfter,
            guardTriggered = triggered,
            reasonsJoined = if (reasons.isEmpty()) "none" else reasons.joinToString("|"),
            anchorInvalidReason = anchorInvReason,
            deltaM = deltaM,
            deltaRatio = deltaRatio
        )
    }
}
