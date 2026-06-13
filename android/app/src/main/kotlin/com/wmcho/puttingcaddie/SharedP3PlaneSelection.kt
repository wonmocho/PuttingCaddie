package com.wmcho.puttingcaddie

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * SharedP3: all / trimmed / corridor 3후보 중 안정적인 단일 plane 1개 선택.
 * 최종 출력은 1개 plane이며, 병렬·제품 미교체 경로(SHARED_ONLY) 전용.
 */
object SharedP3PlaneSelection {

    private const val MIN_NORMAL_ABS_Y = 0.12f
    private const val MAX_RESIDUAL_M = 0.14f
    private const val MIN_POINTS = 3
    private const val LATERAL_SOFT_CAP = 45f
    private const val LATERAL_HARD_BLOCK = 88f // 후보 스코어링용(raw). 최종 출력은 [SharedSlopeStabilization]이 담당.

    private fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    private fun norm(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        if (n < 1e-6f) return v.copyOf()
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val d = dot(a, b).coerceIn(-1f, 1f)
        return acos(d) * 57.29578f
    }

    data class Outcome(
        val slope: SlopeDebugInfo?,
        val residualM: Float?,
        val sampleCount: Int?,
        val logPayload: SharedP3LogPayload
    )

    private data class ScoreParts(
        val total: Float,
        val normalY: Float,
        val residualPen: Float,
        val lateralPen: Float,
        val prevAnglePen: Float,
        val blockedPen: Float
    )

    private fun evaluateCandidate(
        type: String,
        fit: SharedPlaneFit.Result?,
        ballPos: FloatArray,
        cupPos: FloatArray,
        previousNormal: FloatArray?
    ): SharedP3CandidateLog {
        val reject = mutableListOf<String>()
        if (fit == null) {
            return SharedP3CandidateLog(
                type = type,
                valid = false,
                rejectReasons = listOf("fit_null"),
                sampleCount = null,
                residualM = null,
                normal = null,
                normalY = null,
                forwardPct = null,
                lateralPct = null,
                prevDot = null,
                prevAngleDeg = null,
                flipApplied = false,
                scoreTotal = null,
                scoreNormalY = null,
                scoreResidualPenalty = null,
                scoreLateralPenalty = null,
                scorePrevAnglePenalty = null,
                scoreBlockedPenalty = null
            )
        }
        val ny = abs(fit.normalWorld[1])
        if (fit.pointCount < MIN_POINTS) reject.add("too_few_points")
        if (ny < MIN_NORMAL_ABS_Y) reject.add("normal_y_too_small")
        if (fit.residualMeanM > MAX_RESIDUAL_M) reject.add("residual_too_large")

        var n = normalize(fit.normalWorld.copyOf())
        var flipApplied = false
        if (previousNormal != null && dot(n, previousNormal) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flipApplied = true
        }
        val prevDot = previousNormal?.let { dot(n, it).coerceIn(-1f, 1f) }
        val prevAngleDeg = previousNormal?.let { angleDeg(n, it) }

        val sd = SlopeComputer.computeSharedOnly(
            sharedNormalWorld = n,
            ballPos = ballPos,
            cupPos = cupPos,
            isXyzMode = true,
            trackingGood = true
        )
        if (sd.lateralPct != null && abs(sd.lateralPct) > LATERAL_HARD_BLOCK) {
            reject.add("lateral_too_large")
        }
        if (sd.blockedReason != null) reject.add("compute_blocked:${sd.blockedReason}")

        val sny = ny * 120f
        val sRes = fit.residualMeanM * 500f
        var sLat = 0f
        if (sd.lateralPct != null && sd.quality == "valid") {
            val lat = abs(sd.lateralPct)
            if (lat > LATERAL_SOFT_CAP) sLat = (lat - LATERAL_SOFT_CAP) * 3.5f
        }
        var sAng = 0f
        if (previousNormal != null) {
            sAng = angleDeg(n, previousNormal) * 2.2f
        }
        var sBlk = 0f
        if (sd.blockedReason != null) sBlk = 40f
        val total = sny - sRes - sLat - sAng - sBlk

        return SharedP3CandidateLog(
            type = type,
            valid = reject.isEmpty(),
            rejectReasons = reject,
            sampleCount = fit.pointCount,
            residualM = fit.residualMeanM,
            normal = fit.normalWorld.copyOf(),
            normalY = ny,
            forwardPct = sd.forwardPct,
            lateralPct = sd.lateralPct,
            prevDot = prevDot,
            prevAngleDeg = prevAngleDeg,
            flipApplied = flipApplied,
            scoreTotal = total,
            scoreNormalY = sny,
            scoreResidualPenalty = sRes,
            scoreLateralPenalty = sLat,
            scorePrevAnglePenalty = sAng,
            scoreBlockedPenalty = sBlk
        )
    }

    fun selectBest(
        ballPoints: List<FloatArray>,
        cupPoints: List<FloatArray>,
        ballPos: FloatArray,
        cupPos: FloatArray,
        previousNormal: FloatArray?
    ): Outcome {
        val allPts = ballPoints + cupPoints
        val rejectedGlobal = mutableListOf<String>()

        fun emptyPayload(
            blocked: String,
            reason: String
        ): SharedP3LogPayload = SharedP3LogPayload(
            trim = null,
            corridor = null,
            candidateAll = null,
            candidateTrimmed = null,
            candidateCorridor = null,
            selectedType = null,
            selectionReason = reason,
            rejectedSummary = rejectedGlobal.joinToString(";").ifBlank { null },
            normalYFinal = null,
            selectionBlockedReason = blocked,
            quality = "BLOCKED",
            selectedPrevAngleDeg = null,
            selectedFlipApplied = null,
            selectedPrevDot = null,
            finalForwardPct = null,
            finalLateralPct = null,
            finalForwardPctRaw = null,
            finalLateralPctRaw = null,
            stabilizationReasons = null,
            lateralDampingFactor = null,
            lateralForwardRatio = null,
            ratioSmallForwardGuard = null,
            finalResidualM = null,
            finalSampleCount = null,
            finalBlockedReason = blocked
        )

        if (allPts.size < MIN_POINTS) {
            rejectedGlobal.add("too_few_world_points:${allPts.size}")
            return Outcome(
                slope = SlopeDebugInfo(
                    forwardPct = null,
                    lateralPct = null,
                    hMeters = null,
                    vMeters = null,
                    planeDriftDeg = null,
                    blockedReason = "shared_too_few_points",
                    quality = "rejected",
                    isXyzMode = true,
                    ballNormal = null,
                    cupNormal = null,
                    refNormal = null,
                    ballPos = ballPos,
                    cupPos = cupPos,
                    forward = null,
                    left = null,
                    worldUp = floatArrayOf(0f, 1f, 0f)
                ),
                residualM = null,
                sampleCount = null,
                logPayload = emptyPayload("shared_too_few_points", "too_few_points")
            )
        }

        val trimOut = SharedPlaneFit.fitTrimmedDetailed(allPts)
        val corridorStats = SharedPlaneFit.corridorSubsetWithStats(allPts, ballPos, cupPos)

        val candAllFit = SharedPlaneFit.fitFromWorldPoints(allPts)
        val candTrimFit = trimOut.result
        val candCorFit = if (corridorStats.keptPoints.size >= MIN_POINTS) {
            SharedPlaneFit.fitFromWorldPoints(corridorStats.keptPoints)
        } else {
            null
        }

        if (corridorStats.keptCount < MIN_POINTS) {
            rejectedGlobal.add("corridor_too_few_points(${corridorStats.keptCount})")
        }

        val logAll = evaluateCandidate("all", candAllFit, ballPos, cupPos, previousNormal)
        val logTrim = evaluateCandidate("trimmed", candTrimFit, ballPos, cupPos, previousNormal)
        val logCorr = evaluateCandidate("corridor", candCorFit, ballPos, cupPos, previousNormal)

        data class Scored(val type: String, val fit: SharedPlaneFit.Result, val log: SharedP3CandidateLog, val score: Float)

        val scored = mutableListOf<Scored>()
        if (logAll.valid && candAllFit != null) {
            scored.add(Scored("all", candAllFit, logAll, logAll.scoreTotal ?: -1e9f))
        }
        if (logTrim.valid && candTrimFit != null) {
            scored.add(Scored("trimmed", candTrimFit, logTrim, logTrim.scoreTotal ?: -1e9f))
        }
        if (logCorr.valid && candCorFit != null) {
            scored.add(Scored("corridor", candCorFit, logCorr, logCorr.scoreTotal ?: -1e9f))
        }

        if (scored.isEmpty()) {
            rejectedGlobal.add("shared_no_stable_candidate")
            val payload = SharedP3LogPayload(
                trim = trimOut,
                corridor = corridorStats,
                candidateAll = logAll,
                candidateTrimmed = logTrim,
                candidateCorridor = logCorr,
                selectedType = null,
                selectionReason = "no_candidates_passed_gates",
                rejectedSummary = (
                    rejectedGlobal + logAll.rejectReasons + logTrim.rejectReasons + logCorr.rejectReasons
                    ).distinct().joinToString(";").ifBlank { null },
                normalYFinal = null,
                selectionBlockedReason = "shared_no_stable_candidate",
                quality = "BLOCKED",
                selectedPrevAngleDeg = null,
                selectedFlipApplied = null,
                selectedPrevDot = null,
                finalForwardPct = null,
                finalLateralPct = null,
                finalForwardPctRaw = null,
                finalLateralPctRaw = null,
                stabilizationReasons = null,
                lateralDampingFactor = null,
                lateralForwardRatio = null,
                ratioSmallForwardGuard = null,
                finalResidualM = null,
                finalSampleCount = null,
                finalBlockedReason = "shared_no_stable_candidate"
            )
            return Outcome(
                slope = SlopeDebugInfo(
                    forwardPct = null,
                    lateralPct = null,
                    hMeters = null,
                    vMeters = null,
                    planeDriftDeg = null,
                    blockedReason = "shared_no_stable_candidate",
                    quality = "rejected",
                    isXyzMode = true,
                    ballNormal = null,
                    cupNormal = null,
                    refNormal = null,
                    ballPos = ballPos,
                    cupPos = cupPos,
                    forward = null,
                    left = null,
                    worldUp = floatArrayOf(0f, 1f, 0f)
                ),
                residualM = null,
                sampleCount = null,
                logPayload = payload
            )
        }

        val best = scored.maxBy { it.score }
        val finalN = normalize(best.fit.normalWorld.copyOf())
        var selectedFlip = false
        val finalNormal = if (previousNormal != null && dot(finalN, previousNormal) < 0f) {
            selectedFlip = true
            floatArrayOf(-finalN[0], -finalN[1], -finalN[2])
        } else {
            finalN
        }
        val selPrevDot = previousNormal?.let { dot(finalNormal, it).coerceIn(-1f, 1f) }
        val selPrevAngle = previousNormal?.let { angleDeg(finalNormal, it) }

        val finalSd = SlopeComputer.computeSharedOnly(
            sharedNormalWorld = finalNormal,
            ballPos = ballPos,
            cupPos = cupPos,
            isXyzMode = true,
            trackingGood = true
        )

        val stab = if (finalSd.quality == "valid" && finalSd.blockedReason == null &&
            finalSd.forwardPct != null && finalSd.lateralPct != null
        ) {
            SharedSlopeStabilization.stabilize(
                forwardRaw = finalSd.forwardPct,
                lateralRaw = finalSd.lateralPct,
                normalYAbs = abs(finalNormal[1]),
                prevAngleDeg = selPrevAngle
            )
        } else {
            null
        }

        val blocked = when {
            finalSd.blockedReason != null -> finalSd.blockedReason
            stab?.quality == "BLOCKED" -> stab.blockedReason
            else -> null
        }

        val residualDegraded = best.fit.residualMeanM > MAX_RESIDUAL_M * 0.85f
        val quality = when {
            blocked != null -> "BLOCKED"
            residualDegraded || stab?.quality == "DEGRADED" -> "DEGRADED"
            else -> "GOOD"
        }

        val outSlope = if (blocked != null) {
            SlopeDebugInfo(
                forwardPct = null,
                lateralPct = null,
                hMeters = finalSd.hMeters,
                vMeters = finalSd.vMeters,
                planeDriftDeg = null,
                blockedReason = blocked,
                quality = "rejected",
                isXyzMode = true,
                ballNormal = null,
                cupNormal = null,
                refNormal = finalNormal,
                ballPos = ballPos,
                cupPos = cupPos,
                forward = finalSd.forward,
                left = finalSd.left,
                worldUp = finalSd.worldUp
            )
        } else if (stab != null && stab.forwardPct != null && stab.lateralPct != null) {
            finalSd.copy(forwardPct = stab.forwardPct, lateralPct = stab.lateralPct)
        } else {
            finalSd
        }

        val reason = "selected=${best.type} score=${"%.2f".format(best.score)} " +
            "ny=${"%.3f".format(abs(finalNormal[1]))} res=${"%.4f".format(best.fit.residualMeanM)}"

        val stabReasonsJoined = stab?.reasons?.joinToString(";")?.ifBlank { null }
        val payload = SharedP3LogPayload(
            trim = trimOut,
            corridor = corridorStats,
            candidateAll = logAll,
            candidateTrimmed = logTrim,
            candidateCorridor = logCorr,
            selectedType = best.type,
            selectionReason = reason,
            rejectedSummary = rejectedGlobal.joinToString(";").ifBlank { null },
            normalYFinal = abs(finalNormal[1]),
            selectionBlockedReason = blocked,
            quality = quality,
            selectedPrevAngleDeg = selPrevAngle,
            selectedFlipApplied = selectedFlip,
            selectedPrevDot = selPrevDot,
            finalForwardPct = outSlope.forwardPct,
            finalLateralPct = outSlope.lateralPct,
            finalForwardPctRaw = stab?.forwardPctRaw ?: finalSd.forwardPct,
            finalLateralPctRaw = stab?.lateralPctRaw ?: finalSd.lateralPct,
            stabilizationReasons = stabReasonsJoined,
            lateralDampingFactor = stab?.lateralDampingFactor,
            lateralForwardRatio = stab?.lateralForwardRatio,
            ratioSmallForwardGuard = stab?.ratioSmallForwardApplied,
            finalResidualM = best.fit.residualMeanM,
            finalSampleCount = best.fit.pointCount,
            finalBlockedReason = blocked ?: outSlope.blockedReason
        )

        return Outcome(
            slope = outSlope,
            residualM = best.fit.residualMeanM,
            sampleCount = best.fit.pointCount,
            logPayload = payload
        )
    }
}
