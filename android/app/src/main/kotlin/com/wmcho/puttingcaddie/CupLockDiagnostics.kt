package com.wmcho.puttingcaddie

import java.util.Locale

/**
 * 컵 락(STABILIZING_END) 실패 원인 분리 관찰용 — 락 임계/연속/타임 게이트 로직은 변경하지 않는다.
 */
object CupLockDiagnostics {
    const val GATE_SNAPSHOT_MIN_INTERVAL_MS = 100L
    const val SIGMA_TIMELINE_MIN_INTERVAL_MS = 100L

    data class CupLockReasonSummary(
        val primaryReason: String,
        val secondaryReason: String?,
        val failDetailCode: String?,
        val sigmaUsedMeters: Float?,
        val sigmaMaxMeters: Float?,
        val projectedCupPx: Float?,
        val validSampleCount: Int
    )

    data class CupLockOutcomeSummary(
        val outcome: String,
        val primaryReason: String?,
        val secondaryReason: String?,
        val lastSigmaUsedMeters: Float?,
        val lastSigmaMaxMeters: Float?,
        val maxConsecutiveOkReached: Int,
        val elapsedTotalMs: Long,
        val projectedCupPxAtEnd: Float?,
        val validSampleCountAtEnd: Int,
        val softHoldTriggered: Boolean,
        val softLockTriggered: Boolean,
        val farModeHoldActive: Boolean,
        val qualityGuardPassed: Boolean,
        val liveSnapshotAvailable: Boolean,
        val eligibleLiveCupWorldAvailable: Boolean,
        val trackingStateEnd: String?
    )

    data class CascadeInput(
        val trackingOk: Boolean,
        val bufSize: Int,
        val fixedMinSamples: Int,
        val sigmaComputed: Boolean,
        val sigmaOk: Boolean,
        val consecutiveOkCount: Int,
        val consecutiveRequired: Int,
        /** [sigmaOkStartNs] 기준 경과(ns) — 엔진과 동일 */
        val okElapsedNs: Long,
        val lockTimeGateNs: Long,
        val firstMeasWarmupBlocking: Boolean,
        val firstSigmaGuardBlocking: Boolean,
        val cupQualityBlocked: Boolean,
        val liveSnapshotGuardWouldBlock: Boolean,
        val farModeHoldWouldBlock: Boolean,
        val eligibleLiveCupWorld: Boolean,
        val projectedCupPx: Float?,
        val aimMinProjectedPx: Float
    )

    /**
     * 현재 틱 기준 “락을 막는 가장 앞선” 원인을 primary로, 그 다음을 secondary로.
     * (실제 state machine return 순서와 동일하게 맞춤)
     */
    fun classifyCupLockBlockCascade(input: CascadeInput): Pair<String, String?> {
        val reasons = ArrayList<String>(8)
        fun add(code: String) {
            if (!reasons.contains(code)) reasons.add(code)
        }
        if (!input.trackingOk) add("tracking_not_ok")
        if (input.bufSize < input.fixedMinSamples) add("not_enough_samples")
        if (!input.sigmaComputed) add("sigma_not_computed")
        else if (!input.sigmaOk) add("sigma_not_ok")
        else if (input.consecutiveOkCount < input.consecutiveRequired) add("no_consecutive_ok")
        else if (input.okElapsedNs < input.lockTimeGateNs) add("time_gate_not_ok")
        else {
            when {
                input.firstMeasWarmupBlocking -> add("timeout_other")
                input.firstSigmaGuardBlocking -> add("timeout_other")
                input.cupQualityBlocked -> add("cup_quality_guard")
                input.liveSnapshotGuardWouldBlock -> add("live_snapshot_guard")
                input.farModeHoldWouldBlock -> add("far_mode_hold")
                !input.eligibleLiveCupWorld -> add("no_eligible_live_cup_world")
                else -> Unit
            }
        }
        val px = input.projectedCupPx
        if (px != null && px.isFinite() && px < input.aimMinProjectedPx) {
            add("low_projected_px")
        }
        val primary = reasons.firstOrNull() ?: "ok"
        val secondary = reasons.getOrNull(1)
        return primary to secondary
    }

    fun normalizeFromFailDetail(failDetailCode: String?): Pair<String, String?> {
        if (failDetailCode == null) return "timeout_other" to null
        return when (failDetailCode) {
            "TIMEOUT_SIGMA_NOT_OK" -> "sigma_not_ok" to null
            "TIMEOUT_NO_CONSECUTIVE_OK" -> "no_consecutive_ok" to null
            "TIMEOUT_TIME_GATE" -> "time_gate_not_ok" to null
            "TIMEOUT_NOT_ENOUGH_SAMPLES" -> "not_enough_samples" to null
            "TIMEOUT_NO_SIGMA_COMPUTED" -> "sigma_not_computed" to null
            "TIMEOUT_TRACKING_NOT_OK" -> "tracking_not_ok" to null
            "NO_VALID_HITS", "CUP_LOW_VALID_500MS" -> "not_enough_samples" to null
            "CUP_PENDING_TIMEOUT_3S" -> "timeout_other" to null
            "TIMEOUT_OTHER" -> "timeout_other" to null
            else -> "timeout_other" to failDetailCode.lowercase(Locale.US)
        }
    }

    fun formatGateSnapshotLine(
        sessionId: String,
        timestampMs: Long,
        state: String,
        fixedDEstM: Float,
        projectedCupPx: Float?,
        validSampleCount: Int,
        sigmaUsedM: Float?,
        sigmaMaxM: Float?,
        sigmaMode: String,
        sigmaComputed: Boolean,
        trackingOk: Boolean,
        timeGateOk: Boolean,
        consecutiveOk: Int,
        consecutiveRequired: Int,
        elapsedStabMs: Long,
        lockTimeGateMs: Long,
        primary: String,
        secondary: String?
    ): String {
        val margin =
            if (sigmaUsedM != null && sigmaMaxM != null && sigmaUsedM.isFinite() && sigmaMaxM.isFinite()) {
                sigmaMaxM - sigmaUsedM
            } else {
                null
            }
        return "CUP_LOCK_GATE_SNAPSHOT " +
            "sessionId=${sessionId.ifEmpty { "none" }} " +
            "tsMs=$timestampMs " +
            "state=$state " +
            "fixedDEstM=${fmt3(fixedDEstM)} " +
            "projectedCupPx=${fmt1(projectedCupPx)} " +
            "validSampleCount=$validSampleCount " +
            "sigmaUsedM=${fmt4(sigmaUsedM)} " +
            "sigmaMaxM=${fmt4(sigmaMaxM)} " +
            "sigmaMarginM=${fmt4(margin)} " +
            "sigmaMode=$sigmaMode " +
            "sigmaComputed=$sigmaComputed " +
            "trackingOk=$trackingOk " +
            "timeGateOk=$timeGateOk " +
            "consecutiveOk=$consecutiveOk/$consecutiveRequired " +
            "elapsedStabMs=$elapsedStabMs " +
            "lockTimeGateMs=$lockTimeGateMs " +
            "blockedPrimary=$primary " +
            "blockedSecondary=${secondary ?: "none"}"
    }

    fun formatTimelineLine(
        elapsedMs: Long,
        sigmaUsedM: Float?,
        sigmaMaxM: Float?,
        sigmaOk: Boolean,
        consecutiveOk: Int,
        projectedCupPx: Float?,
        validSampleCount: Int
    ): String =
        "CUP_SIGMA_TIMELINE elapsedMs=$elapsedMs " +
            "sigmaUsedM=${fmt4(sigmaUsedM)} sigmaMaxM=${fmt4(sigmaMaxM)} sigmaOk=$sigmaOk " +
            "consecutiveOk=$consecutiveOk " +
            "projectedCupPx=${fmt1(projectedCupPx)} validSampleCount=$validSampleCount"

    fun formatBlockReasonLine(primary: String, secondary: String?, failDetail: String?): String =
        "CUP_LOCK_BLOCK_REASON primary=$primary secondary=${secondary ?: "none"} failDetail=${failDetail ?: "none"}"

    fun formatOutcomeSummaryLine(o: CupLockOutcomeSummary): String =
        "CUP_LOCK_OUTCOME outcome=${o.outcome} primary=${o.primaryReason ?: "none"} secondary=${o.secondaryReason ?: "none"} " +
            "sigmaUsedM=${fmt4(o.lastSigmaUsedMeters)} sigmaMaxM=${fmt4(o.lastSigmaMaxMeters)} " +
            "maxConsec=${o.maxConsecutiveOkReached} elapsedMs=${o.elapsedTotalMs} " +
            "px=${fmt1(o.projectedCupPxAtEnd)} valid=${o.validSampleCountAtEnd} " +
            "softHold=${o.softHoldTriggered} softLock=${o.softLockTriggered} " +
            "farModeHold=${o.farModeHoldActive} qualityGuard=${o.qualityGuardPassed} " +
            "liveSnapshot=${o.liveSnapshotAvailable} eligibleLiveCupWorld=${o.eligibleLiveCupWorldAvailable} " +
            "tracking=${o.trackingStateEnd ?: "none"}"

    fun formatLockSummaryLine(
        outcome: String,
        primary: String,
        secondary: String?,
        sigmaUsedM: Float?,
        sigmaMaxM: Float?,
        maxConsec: Int,
        consecRequired: Int,
        px: Float?,
        validHits: Int,
        softHold: Boolean,
        softLock: Boolean,
        farModeHold: Boolean,
        qualityPassed: Boolean,
        liveSnap: Boolean,
        eligibleLive: Boolean
    ): String =
        "CUP_LOCK_SUMMARY outcome=$outcome primary=$primary secondary=${secondary ?: "none"} " +
            "sigmaUsedM=${fmt4(sigmaUsedM)} sigmaMaxM=${fmt4(sigmaMaxM)} " +
            "maxConsec=$maxConsec/$consecRequired px=${fmt1(px)} validHits=$validHits " +
            "softHold=$softHold softLock=$softLock farModeHold=$farModeHold " +
            "qualityGuard=$qualityPassed liveSnapshot=$liveSnap eligibleLiveCupWorld=$eligibleLive"

    private fun fmt1(f: Float?): String =
        if (f == null || !f.isFinite()) "null" else String.format(Locale.US, "%.1f", f)

    private fun fmt3(f: Float): String = String.format(Locale.US, "%.3f", f)

    private fun fmt4(f: Float?): String =
        if (f == null || !f.isFinite()) "null" else String.format(Locale.US, "%.4f", f)
}
