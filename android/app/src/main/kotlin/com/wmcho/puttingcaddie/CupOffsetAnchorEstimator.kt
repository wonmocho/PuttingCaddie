package com.wmcho.puttingcaddie

import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 사용자는 계속 컵 중심을 조준하고, 엔진 내부에서만 중심 우하단 sector의 바닥 후보를 조사한다.
 * 선택된 anchor 월드 좌표는 **최종 컵 wp로 쓰지 않고**, anchor 주변 local plane에 중심 레이를 재투영한 교점만 사용한다.
 */
object CupOffsetAnchorEstimator {

    /** JSON/로그용 고정 문자열 — 리팩터 시 [wire]만 유지 */
    enum class QualityProbeStatus(val wire: String) {
        OK("ok"),
        DEPTH_MISSING("depth_missing"),
        TOO_FEW_POINTS("too_few_points"),
        FIT_REJECTED("fit_rejected"),
        /** reserved — 아직 Diagnostics에보내지 않음 */
        OUT_OF_BOUNDS("out_of_bounds")
    }

    enum class QualityInvalidateReason(val wire: String) {
        NONE("none"),
        PROBE_FAILED("probe_failed"),
        RESIDUAL("residual"),
        SLOPE("slope"),
        SAMPLE_DROP("sample_drop"),
        SPREAD("spread")
    }

    private data class PlaneProbeResult(
        val success: Boolean,
        val snapshot: OffsetAnchorQualitySnapshot?,
        val status: QualityProbeStatus
    )

    internal data class RecomputeDecision(
        val recompute: Boolean,
        val qualityProbeStatus: QualityProbeStatus,
        val qualityInvalidateReason: QualityInvalidateReason
    )

    /** 후보 1개·top2 완전 동률 → [HIGHEST_SCORE] 고정 */
    enum class SelectedOffsetRankReason(val wire: String) {
        HIGHEST_SCORE("highest_score"),
        TIE_BREAK_LEGACY_DELTA("tie_break_legacy_delta"),
        TIE_BREAK_RESIDUAL("tie_break_residual"),
        NONE("none")
    }

    const val USE_CENTER_AIM_OFFSET_ANCHOR: Boolean = true
    const val OFFSET_ANCHOR_DEBUG_LOG: Boolean = true

    private const val OFFSET_ANCHOR_THROTTLE_MS = 120L
    private const val OFFSET_ANCHOR_CENTER_MOVE_PX = 8f
    private const val OFFSET_ANCHOR_PROJECTED_PX_DELTA = 6f
    private const val OFFSET_ANCHOR_THROTTLE_LOG_MIN_MS = 250L
    private const val OFFSET_ANCHOR_CAMERA_TRANSLATION_DELTA_M = 0.03f
    private const val OFFSET_ANCHOR_CAMERA_ANGLE_DELTA_DEG = 2.5f

    private const val MAX_VARIANCE_M = 0.03f
    private const val MIN_NORMAL_Y = 0.90f
    private const val MAX_PLANE_RESIDUAL_M = 0.015f

    data class Ray3(
        val origin: FloatArray,
        val dir: FloatArray
    )

    data class PlaneFitResult(
        val point: FloatArray,
        val normal: FloatArray,
        val residualM: Float,
        val sampleCount: Int
    )

    data class CupAnchorCandidate(
        val screenPx: PointF,
        val world: FloatArray,
        val score: Float,
        val varianceM: Float,
        val normalY: Float,
        val residualM: Float,
        val distanceFromCupCenterM: Float
    )

    data class CupAnchorSelectionResult(
        val bestAnchor: CupAnchorCandidate?,
        val allCandidates: List<CupAnchorCandidate>,
        val localPlane: PlaneFitResult?,
        val reprojectedCupWorld: FloatArray?,
        /** 후보 정렬 tie-break 결과 요약 문자열 */
        val selectedOffsetRankReason: String? = null
    )

    data class OffsetRangeCm(val minCm: Float, val maxCm: Float)

    data class OffsetAnchorQualitySnapshot(
        val fitResidualCm: Float,
        val planeTiltDeg: Float,
        val sampleCount: Int,
        val hitDispersionCm: Float
    )

    data class FinalCupResolveOutcome(
        val world: FloatArray?,
        val diagnostics: Diagnostics,
        val qualitySnapshot: OffsetAnchorQualitySnapshot?,
        val anchorScreenPx: PointF?
    )

    /** JSON / UiModel / 로그 공통 */
    data class Diagnostics(
        val mode: String,
        val candidateCount: Int,
        val bestVarianceCm: Float?,
        val bestResidualCm: Float?,
        val bestNormalY: Float?,
        val bestOffsetDistCm: Float?,
        val planeResidualCm: Float?,
        val reprojectSuccess: Boolean,
        val failureReason: String?,
        /** recomputed_success | recomputed_fail | throttled_cache_success | throttled_cache_fail | feature_disabled */
        val throttleMode: String? = null,
        val throttleAgeMs: Long? = null,
        val cacheHit: Boolean = false,
        val cacheWasSuccess: Boolean? = null,
        val cameraTranslationDeltaM: Float? = null,
        val cameraAngleDeltaDeg: Float? = null,
        val reprojectedAffectsDistance: Boolean = true,
        val reprojectedAffectsEndAnchor: Boolean = true,
        val reprojectedAffectsExperimentalSurface: Boolean = false,
        val distanceFrameOfRef: String? = null,
        val endAnchorFrameOfRef: String? = null,
        val experimentalSurfaceFrameOfRef: String? = null,
        /** LIVE 거리/엔드는 재투영 ray 기준인데 실험 slope는 center ROI 기준일 때 true */
        val frameOfRefMismatch: Boolean? = null,
        /** [frameOfRefMismatch] 의미 고정 — downstream 파서용 */
        val frameOfRefMismatchReason: String? = null,
        val qualityProbeStatus: String = QualityProbeStatus.OK.wire,
        val qualityInvalidateReason: String = QualityInvalidateReason.NONE.wire,
        /** 선택된 offset 후보: legacy center 대비 앵커 평균 거리(cm) */
        val selectedOffsetCm: Float? = null,
        val selectedOffsetScore: Float? = null,
        /** [highest_score] | [tie_break_legacy_delta] | [tie_break_residual] | [none] */
        val selectedOffsetRankReason: String? = null,
        val cupAnchorEligibleMinCm: Float? = null,
        val cupAnchorEligibleMaxCm: Float? = null,
        /** 선택된 best anchor 평균 지점과 legacy center 간 거리(cm) */
        val cupAnchorLegacyDeltaCm: Float? = null
    )

    /**
     * offset-anchor throttle/cache — [V31StateMachine] 인스턴스당 1개.
     */
    class CacheState {
        var lastEvalRealtimeMs: Long = 0L
        var lastCenterPx: PointF? = null
        var lastProjectedCupPx: Float? = null
        var lastTrackingState: TrackingState? = null
        var lastCameraPoseTranslation: FloatArray? = null
        var lastCameraPoseForward: FloatArray? = null
        var lastSuccessWorld: FloatArray? = null
        var lastFailureFallbackWorld: FloatArray? = null
        var lastResultSuccess: Boolean = false
        var lastFailureReason: String? = null
        var lastFullDiagnostics: Diagnostics? = null
        var lastThrottleLogMs: Long = 0L
        var lastQualitySnapshot: OffsetAnchorQualitySnapshot? = null
        var lastCachedAnchorScreenPx: PointF? = null

        fun reset() {
            lastEvalRealtimeMs = 0L
            lastCenterPx = null
            lastProjectedCupPx = null
            lastTrackingState = null
            lastCameraPoseTranslation = null
            lastCameraPoseForward = null
            lastSuccessWorld = null
            lastFailureFallbackWorld = null
            lastResultSuccess = false
            lastFailureReason = null
            lastFullDiagnostics = null
            lastThrottleLogMs = 0L
            lastQualitySnapshot = null
            lastCachedAnchorScreenPx = null
        }
    }

    private fun cameraTranslationArray(frame: Frame): FloatArray {
        val p = frame.camera.pose
        return floatArrayOf(p.tx(), p.ty(), p.tz())
    }

    private fun cameraForwardArray(frame: Frame): FloatArray {
        val z = floatArrayOf(0f, 0f, -1f)
        val out = FloatArray(3)
        frame.camera.pose.rotateVector(z, 0, out, 0)
        val len = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]).takeIf { it > 1e-6f } ?: 1f
        return floatArrayOf(out[0] / len, out[1] / len, out[2] / len)
    }

    private fun distance3Arrays(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun angleDegArrays(a: FloatArray, b: FloatArray): Float {
        val dot = (a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(dot).toDouble()).toFloat()
    }

    fun computeAdaptiveOffsetRangeCm(distanceM: Float, projectedCupPx: Float, farMode: Boolean): OffsetRangeCm {
        var minCm = 8f
        var maxCm = 20f
        if (distanceM >= 6.0f || farMode) {
            minCm = 6f
            maxCm = 28f
        }
        if (projectedCupPx <= 20f) {
            minCm = min(minCm, 6f)
            maxCm = max(maxCm, 30f)
        }
        return OffsetRangeCm(minCm, maxCm)
    }

    private fun planeTiltDegFromNormalY(normalY: Float): Float {
        val c = abs(normalY).coerceIn(0f, 1f)
        return Math.toDegrees(acos(c.toDouble())).toFloat()
    }

    /** null = 무효화 없음 (프로브 성공 후에만 호출) */
    private fun qualityInvalidateIfAny(
        prev: OffsetAnchorQualitySnapshot,
        curr: OffsetAnchorQualitySnapshot
    ): QualityInvalidateReason? {
        if (abs(curr.fitResidualCm - prev.fitResidualCm) >= 1.5f) return QualityInvalidateReason.RESIDUAL
        if (abs(curr.planeTiltDeg - prev.planeTiltDeg) >= 2.0f) return QualityInvalidateReason.SLOPE
        if (curr.sampleCount <= 4 && prev.sampleCount - curr.sampleCount >= 3) {
            return QualityInvalidateReason.SAMPLE_DROP
        }
        if (abs(curr.hitDispersionCm - prev.hitDispersionCm) >= 2.0f) return QualityInvalidateReason.SPREAD
        return null
    }

    private fun probePlaneQualityAtAnchor(
        sampler: V31HitSampler,
        frame: Frame,
        anchorPx: PointF
    ): PlaneProbeResult {
        val samples = collectPlaneSamplesAroundAnchor(sampler, frame, anchorPx)
        if (samples.isEmpty()) {
            return PlaneProbeResult(success = false, snapshot = null, status = QualityProbeStatus.DEPTH_MISSING)
        }
        if (samples.size < 6) {
            return PlaneProbeResult(success = false, snapshot = null, status = QualityProbeStatus.TOO_FEW_POINTS)
        }
        val plane = fitLocalPlane(samples)
            ?: return PlaneProbeResult(success = false, snapshot = null, status = QualityProbeStatus.FIT_REJECTED)
        val dispCm = computeWorldVarianceM(samples) * 100f
        return PlaneProbeResult(
            success = true,
            snapshot =
                OffsetAnchorQualitySnapshot(
                    fitResidualCm = plane.residualM * 100f,
                    planeTiltDeg = planeTiltDegFromNormalY(plane.normal[1]),
                    sampleCount = plane.sampleCount,
                    hitDispersionCm = dispCm
                ),
            status = QualityProbeStatus.OK
        )
    }

    private fun fillFrameRefs(usedReprojectedWorld: Boolean, d: Diagnostics): Diagnostics {
        val distanceFor = if (usedReprojectedWorld) "reprojected_center_ray" else "legacy_center"
        val endFor = if (usedReprojectedWorld) "reprojected_live" else "legacy_live"
        val exp = "center_roi"
        val mismatchReason =
            if (usedReprojectedWorld) {
                "live_reprojected_vs_surface_center_roi"
            } else {
                "none"
            }
        return d.copy(
            distanceFrameOfRef = distanceFor,
            endAnchorFrameOfRef = endFor,
            experimentalSurfaceFrameOfRef = exp,
            frameOfRefMismatch = usedReprojectedWorld,
            frameOfRefMismatchReason = mismatchReason
        )
    }

    /**
     * throttle/cache 밖으로 나가기 직전: probe/invalidate 조합 불변식.
     * `(probeStatus != ok) == (invalidate == probe_failed)`
     */
    internal fun checkDiagnosticsQualityProbeInvariant(d: Diagnostics) {
        val ps = d.qualityProbeStatus
        val inv = d.qualityInvalidateReason
        val okW = QualityProbeStatus.OK.wire
        val pf = QualityInvalidateReason.PROBE_FAILED.wire
        check((ps != okW) == (inv == pf)) {
            "CupOffsetAnchor diagnostics invariant failed: probeStatus=$ps invalidate=$inv " +
                "(non-ok probe ⇒ probe_failed only; ok probe ⇒ invalidate must not be probe_failed)"
        }
    }

    internal fun finalizeCupOffsetDiagnostics(d: Diagnostics): Diagnostics {
        checkDiagnosticsQualityProbeInvariant(d)
        return d
    }

    /** [resolveFinalCupWorldPointWithThrottle] feature OFF 분기와 동일 */
    internal fun diagnosticsAfterFeatureOffFinalize(): Diagnostics =
        finalizeCupOffsetDiagnostics(fillFrameRefs(false, diagnosticsFeatureDisabled()))

    /** throttle cache hit 시 반환 Diagnostics 조립(프로덕션과 동일) */
    internal fun buildThrottledReturnDiagnostics(
        base: Diagnostics,
        lastResultSuccess: Boolean,
        recomputeDecision: RecomputeDecision,
        throttleAgeMs: Long,
        failureReasonForDiag: String?,
        camDistDelta: Float?,
        camAngDelta: Float?
    ): Diagnostics {
        val throttleLabel =
            if (lastResultSuccess) {
                "throttled_cache_success"
            } else {
                "throttled_cache_fail"
            }
        val diag =
            fillFrameRefs(
                lastResultSuccess,
                base.copy(
                    throttleMode = throttleLabel,
                    throttleAgeMs = throttleAgeMs,
                    cacheHit = true,
                    cacheWasSuccess = lastResultSuccess,
                    failureReason = failureReasonForDiag,
                    cameraTranslationDeltaM = camDistDelta,
                    cameraAngleDeltaDeg = camAngDelta,
                    reprojectedAffectsDistance = true,
                    reprojectedAffectsEndAnchor = true,
                    reprojectedAffectsExperimentalSurface = false,
                    qualityProbeStatus = recomputeDecision.qualityProbeStatus.wire,
                    qualityInvalidateReason = recomputeDecision.qualityInvalidateReason.wire
                )
            )
        return finalizeCupOffsetDiagnostics(diag)
    }

    /** 재계산 직후 [state.lastFullDiagnostics] 및 반환값과 동일한 Diagnostics 조립 */
    internal fun buildRecomputedStoreDiagnostics(
        diagFull: Diagnostics,
        success: Boolean,
        recomputeDecision: RecomputeDecision,
        camDistForDiag: Float?,
        camAngForDiag: Float?
    ): Diagnostics {
        val throttleMode = if (success) "recomputed_success" else "recomputed_fail"
        return finalizeCupOffsetDiagnostics(
            fillFrameRefs(
                success,
                diagFull.copy(
                    throttleMode = throttleMode,
                    throttleAgeMs = 0L,
                    cacheHit = false,
                    cacheWasSuccess = success,
                    cameraTranslationDeltaM = camDistForDiag,
                    cameraAngleDeltaDeg = camAngForDiag,
                    reprojectedAffectsDistance = true,
                    reprojectedAffectsEndAnchor = true,
                    reprojectedAffectsExperimentalSurface = false,
                    qualityProbeStatus = recomputeDecision.qualityProbeStatus.wire,
                    qualityInvalidateReason = recomputeDecision.qualityInvalidateReason.wire
                )
            )
        )
    }

    private fun evaluateRecomputeDecision(
        state: CacheState,
        frame: Frame,
        sampler: V31HitSampler,
        nowMs: Long,
        centerPx: PointF,
        projectedCupPx: Float,
        trackingState: TrackingState
    ): RecomputeDecision {
        val idle =
            RecomputeDecision(
                recompute = false,
                qualityProbeStatus = QualityProbeStatus.OK,
                qualityInvalidateReason = QualityInvalidateReason.NONE
            )
        val lastMs = state.lastEvalRealtimeMs
        val lastCenter = state.lastCenterPx
        val lastProjected = state.lastProjectedCupPx
        val lastTracking = state.lastTrackingState
        if (lastMs == 0L) {
            return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
        }
        if (nowMs - lastMs >= OFFSET_ANCHOR_THROTTLE_MS) {
            return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
        }
        if (lastTracking != trackingState) {
            return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
        }
        if (lastCenter != null) {
            val dx = centerPx.x - lastCenter.x
            val dy = centerPx.y - lastCenter.y
            val move = sqrt(dx * dx + dy * dy)
            if (move >= OFFSET_ANCHOR_CENTER_MOVE_PX) {
                return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
            }
        }
        if (lastProjected != null) {
            if (abs(projectedCupPx - lastProjected) >= OFFSET_ANCHOR_PROJECTED_PX_DELTA) {
                return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
            }
        }
        val curT = cameraTranslationArray(frame)
        val curF = cameraForwardArray(frame)
        val lastT = state.lastCameraPoseTranslation
        val lastF = state.lastCameraPoseForward
        if (lastT != null && lastF != null) {
            if (distance3Arrays(curT, lastT) >= OFFSET_ANCHOR_CAMERA_TRANSLATION_DELTA_M) {
                return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
            }
            if (angleDegArrays(curF, lastF) >= OFFSET_ANCHOR_CAMERA_ANGLE_DELTA_DEG) {
                return RecomputeDecision(true, QualityProbeStatus.OK, QualityInvalidateReason.NONE)
            }
        }
        val anchorPx = state.lastCachedAnchorScreenPx
        val prevQ = state.lastQualitySnapshot
        if (anchorPx != null && prevQ != null) {
            val probeResult = probePlaneQualityAtAnchor(sampler, frame, anchorPx)
            if (!probeResult.success) {
                return RecomputeDecision(
                    recompute = true,
                    qualityProbeStatus = probeResult.status,
                    qualityInvalidateReason = QualityInvalidateReason.PROBE_FAILED
                )
            }
            val snap = probeResult.snapshot!!
            val inv = qualityInvalidateIfAny(prevQ, snap)
            return if (inv != null) {
                RecomputeDecision(
                    recompute = true,
                    qualityProbeStatus = QualityProbeStatus.OK,
                    qualityInvalidateReason = inv
                )
            } else {
                idle
            }
        }
        return idle
    }

    /** [candidateSortOrder] 와 동일한 3단 비교를 분해해 둠 — tie 이유는 반드시 이 순서와 일치 */
    private val compareScoreDesc = compareByDescending<CupAnchorCandidate> { it.score }
    private val compareLegacyDeltaAsc =
        compareBy<CupAnchorCandidate> { abs(it.distanceFromCupCenterM * 100f) }
    private val compareResidualAsc = compareBy<CupAnchorCandidate> { it.residualM }
    private val candidateSortOrder =
        compareScoreDesc.then(compareLegacyDeltaAsc).then(compareResidualAsc)

    private fun selectedOffsetRankReason(sorted: List<CupAnchorCandidate>, best: CupAnchorCandidate?): SelectedOffsetRankReason {
        if (best == null || sorted.isEmpty()) return SelectedOffsetRankReason.NONE
        if (sorted.size <= 1) return SelectedOffsetRankReason.HIGHEST_SCORE
        val a = sorted[0]
        val b = sorted[1]
        return when {
            compareScoreDesc.compare(a, b) != 0 -> SelectedOffsetRankReason.HIGHEST_SCORE
            compareLegacyDeltaAsc.compare(a, b) != 0 -> SelectedOffsetRankReason.TIE_BREAK_LEGACY_DELTA
            compareResidualAsc.compare(a, b) != 0 -> SelectedOffsetRankReason.TIE_BREAK_RESIDUAL
            else -> SelectedOffsetRankReason.HIGHEST_SCORE
        }
    }

    private fun diagnosticsFeatureDisabled(): Diagnostics =
        Diagnostics(
            mode = "legacy_center",
            candidateCount = 0,
            bestVarianceCm = null,
            bestResidualCm = null,
            bestNormalY = null,
            bestOffsetDistCm = null,
            planeResidualCm = null,
            reprojectSuccess = false,
            failureReason = "flag_off",
            throttleMode = "feature_disabled",
            throttleAgeMs = null,
            cacheHit = false,
            reprojectedAffectsDistance = true,
            reprojectedAffectsEndAnchor = true,
            reprojectedAffectsExperimentalSurface = false
        )

    private fun diagnosticsThrottleNoPrior(ageMs: Long): Diagnostics =
        Diagnostics(
            mode = "legacy_center",
            candidateCount = 0,
            bestVarianceCm = null,
            bestResidualCm = null,
            bestNormalY = null,
            bestOffsetDistCm = null,
            planeResidualCm = null,
            reprojectSuccess = false,
            failureReason = "throttle_no_prior_diag",
            throttleMode = "throttled_cache_fail",
            throttleAgeMs = ageMs,
            cacheHit = true,
            cacheWasSuccess = false,
            reprojectedAffectsDistance = true,
            reprojectedAffectsEndAnchor = true,
            reprojectedAffectsExperimentalSurface = false
        )

    /**
     * LIVE마다 전체 재계산 대신 throttle/cache.
     * [state]는 [V31StateMachine] 인스턴스당 1개.
     * 반환 [world]는 항상 복사본(legacy 또는 재투영 성공 / 실패 시 캐시된 fallback).
     */
    fun resolveFinalCupWorldPointWithThrottle(
        state: CacheState,
        frame: Frame,
        sampler: V31HitSampler,
        cupCenterPx: PointF,
        projectedCupPx: Float,
        centerWorldLegacy: FloatArray,
        trackingState: TrackingState,
        rayDirYMin: Float,
        maxRayDistanceM: Float,
        distanceM: Float,
        farMode: Boolean
    ): Pair<FloatArray, Diagnostics> {
        val legacyCopy = centerWorldLegacy.copyOf()
        if (!USE_CENTER_AIM_OFFSET_ANCHOR) {
            return Pair(legacyCopy.copyOf(), diagnosticsAfterFeatureOffFinalize())
        }
        val nowMs = SystemClock.elapsedRealtime()
        val curT = cameraTranslationArray(frame)
        val curF = cameraForwardArray(frame)
        val camDistDelta = state.lastCameraPoseTranslation?.let { distance3Arrays(curT, it) }
        val camAngDelta = state.lastCameraPoseForward?.let { angleDegArrays(curF, it) }

        val recomputeDecision =
            evaluateRecomputeDecision(
                state = state,
                frame = frame,
                sampler = sampler,
                nowMs = nowMs,
                centerPx = cupCenterPx,
                projectedCupPx = projectedCupPx,
                trackingState = trackingState
            )
        val recompute = recomputeDecision.recompute
        if (!recompute) {
            val world =
                if (state.lastResultSuccess && state.lastSuccessWorld != null) {
                    state.lastSuccessWorld!!.copyOf()
                } else {
                    state.lastFailureFallbackWorld?.copyOf() ?: legacyCopy.copyOf()
                }
            val age = nowMs - state.lastEvalRealtimeMs
            val base = state.lastFullDiagnostics ?: diagnosticsThrottleNoPrior(age)
            val diag =
                buildThrottledReturnDiagnostics(
                    base = base,
                    lastResultSuccess = state.lastResultSuccess,
                    recomputeDecision = recomputeDecision,
                    throttleAgeMs = age,
                    failureReasonForDiag =
                        if (state.lastResultSuccess) {
                            base.failureReason
                        } else {
                            state.lastFailureReason ?: base.failureReason
                        },
                    camDistDelta = camDistDelta,
                    camAngDelta = camAngDelta
                )
            val throttleLabelForLog = diag.throttleMode ?: "throttled_cache"
            if (OFFSET_ANCHOR_DEBUG_LOG &&
                nowMs - state.lastThrottleLogMs >= OFFSET_ANCHOR_THROTTLE_LOG_MIN_MS
            ) {
                state.lastThrottleLogMs = nowMs
                Log.d(
                    "CupOffsetAnchor",
                    "offsetAnchorMode=$throttleLabelForLog cacheHit=true cacheWasSuccess=${state.lastResultSuccess} " +
                        "throttleAgeMs=$age camDeltaM=${camDistDelta?.let { "%.4f".format(it) } ?: "na"} " +
                        "camDeltaDeg=${camAngDelta?.let { "%.2f".format(it) } ?: "na"} " +
                        "lastFailureReason=${state.lastFailureReason ?: "none"}"
                )
            }
            return Pair(world.copyOf(), diag)
        }

        val camDistForDiag = state.lastCameraPoseTranslation?.let { distance3Arrays(curT, it) }
        val camAngForDiag = state.lastCameraPoseForward?.let { angleDegArrays(curF, it) }

        val outcome =
            resolveFinalCupWorldPoint(
                frame = frame,
                sampler = sampler,
                cupCenterPx = cupCenterPx,
                projectedCupPx = projectedCupPx,
                centerWorldLegacy = legacyCopy,
                rayDirYMin = rayDirYMin,
                maxRayDistanceM = maxRayDistanceM,
                distanceM = distanceM,
                farMode = farMode
            )
        val reproj = outcome.world
        val diagFull = outcome.diagnostics
        state.lastEvalRealtimeMs = nowMs
        state.lastCenterPx = PointF(cupCenterPx.x, cupCenterPx.y)
        state.lastProjectedCupPx = projectedCupPx
        state.lastTrackingState = trackingState
        state.lastCameraPoseTranslation = curT.copyOf()
        state.lastCameraPoseForward = curF.copyOf()

        val success = reproj != null
        if (success) {
            state.lastQualitySnapshot = outcome.qualitySnapshot
            state.lastCachedAnchorScreenPx =
                outcome.anchorScreenPx?.let { PointF(it.x, it.y) }
        } else {
            state.lastQualitySnapshot = null
            state.lastCachedAnchorScreenPx = null
        }
        val outWorld =
            if (success) {
                state.lastSuccessWorld = reproj!!.copyOf()
                state.lastFailureFallbackWorld = null
                state.lastResultSuccess = true
                state.lastFailureReason = null
                state.lastSuccessWorld!!.copyOf()
            } else {
                state.lastSuccessWorld = null
                state.lastFailureFallbackWorld = legacyCopy.copyOf()
                state.lastResultSuccess = false
                state.lastFailureReason = diagFull.failureReason
                state.lastFailureFallbackWorld!!.copyOf()
            }
        val outDiag =
            buildRecomputedStoreDiagnostics(
                diagFull = diagFull,
                success = success,
                recomputeDecision = recomputeDecision,
                camDistForDiag = camDistForDiag,
                camAngForDiag = camAngForDiag
            )
        state.lastFullDiagnostics = outDiag
        if (outDiag.frameOfRefMismatch == true) {
            Log.w(
                "CupOffsetAnchor",
                "FRAME_OF_REF_MISMATCH distance=${outDiag.distanceFrameOfRef} end=${outDiag.endAnchorFrameOfRef} exp=${outDiag.experimentalSurfaceFrameOfRef}"
            )
        }
        if (OFFSET_ANCHOR_DEBUG_LOG) {
            Log.d(
                "CupOffsetAnchor",
                "offsetAnchorMode=${outDiag.throttleMode} cacheHit=false cacheWasSuccess=$success " +
                    "camDeltaM=${camDistForDiag?.let { "%.4f".format(it) } ?: "na"} " +
                    "camDeltaDeg=${camAngForDiag?.let { "%.2f".format(it) } ?: "na"} " +
                    "lastFailureReason=${state.lastFailureReason ?: "none"} " +
                    "candidateCount=${diagFull.candidateCount}"
            )
        }
        return Pair(outWorld.copyOf(), outDiag)
    }

    /** 회귀 테스트용 — 이 문자열이 [QualityInvalidateReason.wire] 등에 다시 나오면 안 됨 */
    internal val removedQualityInvalidateWireSubstrings =
        listOf("residual_jump", "tilt_jump", "dispersion_jump")

    fun generateOffsetScreenCandidates(center: PointF, projectedCupPx: Float): List<PointF> {
        val base = (projectedCupPx * 0.55f).coerceIn(12f, 36f)
        val radii = listOf(base * 0.8f, base * 1.0f, base * 1.2f)
        val anglesDeg = listOf(25f, 40f, 55f)
        val out = ArrayList<PointF>(radii.size * anglesDeg.size)
        for (r in radii) {
            for (deg in anglesDeg) {
                val rad = Math.toRadians(deg.toDouble())
                val dx = (cos(rad) * r).toFloat()
                val dy = (sin(rad) * r).toFloat()
                out.add(PointF(center.x + dx, center.y + dy))
            }
        }
        return out
    }

    private fun hitGroundWorldPoint(
        sampler: V31HitSampler,
        frame: Frame,
        sx: Float,
        sy: Float
    ): FloatArray? {
        val hit =
            sampler.hitTestBestPlaneAtScreenPoint(
                frame = frame,
                screenX = sx,
                screenY = sy,
                maxDistanceMeters = 12f,
                preferUpwardFacing = true,
                yBelowCameraMeters = 0.1f,
                preferFarthestForDistance = false
            ) ?: return null
        return floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
    }

    fun sampleGroundWorldHits(
        sampler: V31HitSampler,
        frame: Frame,
        px: PointF,
        sampleRadiusPx: Float = 8f
    ): List<FloatArray> {
        val offsets =
            listOf(
                PointF(0f, 0f),
                PointF(-sampleRadiusPx, 0f),
                PointF(sampleRadiusPx, 0f),
                PointF(0f, -sampleRadiusPx),
                PointF(0f, sampleRadiusPx)
            )
        val hits = ArrayList<FloatArray>(offsets.size)
        for (o in offsets) {
            val hit = hitGroundWorldPoint(sampler, frame, px.x + o.x, px.y + o.y) ?: continue
            hits.add(hit)
        }
        return hits
    }

    private fun averageWorld(points: List<FloatArray>): FloatArray {
        val n = points.size.coerceAtLeast(1)
        var x = 0.0
        var y = 0.0
        var z = 0.0
        for (p in points) {
            x += p[0].toDouble()
            y += p[1].toDouble()
            z += p[2].toDouble()
        }
        val nf = n.toFloat()
        return floatArrayOf((x / nf).toFloat(), (y / nf).toFloat(), (z / nf).toFloat())
    }

    private fun computeWorldVarianceM(points: List<FloatArray>): Float {
        if (points.size < 2) return Float.MAX_VALUE
        val c = averageWorld(points)
        var sum = 0.0
        for (p in points) {
            val dx = p[0] - c[0]
            val dy = p[1] - c[1]
            val dz = p[2] - c[2]
            sum += (dx * dx + dy * dy + dz * dz).toDouble()
        }
        return sqrt((sum / points.size).toFloat())
    }

    private fun distance3(a: FloatArray, b: FloatArray): Float {
        val dx = a[0] - b[0]
        val dy = a[1] - b[1]
        val dz = a[2] - b[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * 앵커 주변 그리드 히트 — [anchorPx]는 현재 [frame]·[sampler] UV/뷰포트와 일치해야 함.
     * (throttle 품질 프로브는 캐시된 픽셀을 재사용하므로, 좌표계 꼬임 의심 시에만 별도 assert 검토.)
     */
    fun collectPlaneSamplesAroundAnchor(
        sampler: V31HitSampler,
        frame: Frame,
        anchorPx: PointF,
        radiusPx: Float = 24f
    ): List<FloatArray> {
        val out = ArrayList<FloatArray>(9)
        val grid = listOf(-1f, 0f, 1f)
        for (gx in grid) {
            for (gy in grid) {
                val px = anchorPx.x + gx * radiusPx * 0.6f
                val py = anchorPx.y + gy * radiusPx * 0.6f
                val hit = hitGroundWorldPoint(sampler, frame, px, py) ?: continue
                out.add(hit)
            }
        }
        return out
    }

    private fun fitLocalPlane(samples: List<FloatArray>): PlaneFitResult? {
        if (samples.size < 6) return null
        val fit = SharedPlaneFit.fitFromWorldPoints(samples) ?: return null
        val c = averageWorld(samples)
        if (fit.normalWorld[1] < MIN_NORMAL_Y) return null
        if (fit.residualMeanM > MAX_PLANE_RESIDUAL_M) return null
        return PlaneFitResult(
            point = c,
            normal = fit.normalWorld.copyOf(),
            residualM = fit.residualMeanM,
            sampleCount = fit.pointCount
        )
    }

    fun intersectRayWithPlane(ray: Ray3, plane: PlaneFitResult, maxRayDistanceM: Float): FloatArray? {
        val n = plane.normal
        val p0 = plane.point
        val ro = ray.origin
        val rd = ray.dir
        val denom = n[0] * rd[0] + n[1] * rd[1] + n[2] * rd[2]
        if (abs(denom) < 1e-5f) return null
        val t =
            (
                n[0] * (p0[0] - ro[0]) +
                    n[1] * (p0[1] - ro[1]) +
                    n[2] * (p0[2] - ro[2])
            ) / denom
        if (t <= 0f || !t.isFinite()) return null
        if (t > maxRayDistanceM) return null
        return floatArrayOf(
            ro[0] + rd[0] * t,
            ro[1] + rd[1] * t,
            ro[2] + rd[2] * t
        )
    }

    fun screenPointToWorldRay(
        frame: Frame,
        sampler: V31HitSampler,
        screenX: Float,
        screenY: Float,
        rayDirYMin: Float
    ): Ray3? {
        val uv = sampler.screenPointToAdjustedTextureUv(frame, screenX, screenY) ?: return null
        val intr = frame.camera.textureIntrinsics
        val dims = intr.imageDimensions
        val texW = dims[0].toFloat().takeIf { it > 1f } ?: 1f
        val texH = dims[1].toFloat().takeIf { it > 1f } ?: 1f
        val xPx = (uv.x.coerceIn(0f, 1f) * texW)
        val yPx = (uv.y.coerceIn(0f, 1f) * texH)
        val fx = intr.focalLength[0].takeIf { it > 1e-6f } ?: 1f
        val fy = intr.focalLength[1].takeIf { it > 1e-6f } ?: 1f
        val cx = intr.principalPoint[0]
        val cy = intr.principalPoint[1]
        var dx = (xPx - cx) / fx
        var dy = (yPx - cy) / fy
        var dz = -1f
        val norm = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 1e-6f } ?: 1f
        dx /= norm
        dy /= norm
        dz /= norm
        val dirCam = floatArrayOf(dx, dy, dz)
        val dirWorld = FloatArray(3)
        val camPose = frame.camera.pose
        camPose.rotateVector(dirCam, 0, dirWorld, 0)
        if (abs(dirWorld[1]) < rayDirYMin) return null
        return Ray3(
            origin = floatArrayOf(camPose.tx(), camPose.ty(), camPose.tz()),
            dir = dirWorld
        )
    }

    fun estimateCupWorldPointWithOffsetAnchor(
        frame: Frame,
        sampler: V31HitSampler,
        cupCenterPx: PointF,
        projectedCupPx: Float,
        centerWorldRough: FloatArray?,
        rayDirYMin: Float,
        maxRayDistanceM: Float,
        offsetRangeCm: OffsetRangeCm
    ): CupAnchorSelectionResult {
        val candidatePxList = generateOffsetScreenCandidates(cupCenterPx, projectedCupPx)
        val candidates = mutableListOf<CupAnchorCandidate>()
        for (candidatePx in candidatePxList) {
            val hits = sampleGroundWorldHits(sampler, frame, candidatePx)
            if (hits.size < 3) continue
            val variance = computeWorldVarianceM(hits)
            if (variance > MAX_VARIANCE_M) continue
            val avg = averageWorld(hits)
            val offsetDist =
                if (centerWorldRough != null) {
                    distance3(avg, centerWorldRough)
                } else {
                    -1f
                }
            if (centerWorldRough != null) {
                val deltaCm = offsetDist * 100f
                if (deltaCm !in offsetRangeCm.minCm..offsetRangeCm.maxCm) {
                    continue
                }
            }
            val planeSamples = collectPlaneSamplesAroundAnchor(sampler, frame, candidatePx)
            if (planeSamples.size < 6) continue
            val plane = fitLocalPlane(planeSamples) ?: continue
            val score =
                (1.0f / (variance + 1e-4f)) * 0.45f +
                    plane.normal[1] * 0.35f +
                    (1.0f / (plane.residualM + 1e-4f)) * 0.20f
            candidates.add(
                CupAnchorCandidate(
                    screenPx = candidatePx,
                    world = avg.copyOf(),
                    score = score,
                    varianceM = variance,
                    normalY = plane.normal[1],
                    residualM = plane.residualM,
                    distanceFromCupCenterM = offsetDist
                )
            )
        }
        val sortedCandidates = candidates.sortedWith(candidateSortOrder)
        val best = sortedCandidates.firstOrNull()
        val rankReason = selectedOffsetRankReason(sortedCandidates, best)
        if (best == null) {
            return CupAnchorSelectionResult(
                bestAnchor = null,
                allCandidates = candidates,
                localPlane = null,
                reprojectedCupWorld = null,
                selectedOffsetRankReason = rankReason.wire
            )
        }
        val bestPlaneSamples = collectPlaneSamplesAroundAnchor(sampler, frame, best.screenPx)
        val localPlane = fitLocalPlane(bestPlaneSamples)
        if (localPlane == null) {
            return CupAnchorSelectionResult(
                bestAnchor = best,
                allCandidates = candidates,
                localPlane = null,
                reprojectedCupWorld = null,
                selectedOffsetRankReason = rankReason.wire
            )
        }
        val centerRay = screenPointToWorldRay(frame, sampler, cupCenterPx.x, cupCenterPx.y, rayDirYMin)
            ?: return CupAnchorSelectionResult(
                bestAnchor = best,
                allCandidates = candidates,
                localPlane = localPlane,
                reprojectedCupWorld = null,
                selectedOffsetRankReason = rankReason.wire
            )
        val reprojected = intersectRayWithPlane(centerRay, localPlane, maxRayDistanceM)
        return CupAnchorSelectionResult(
            bestAnchor = best,
            allCandidates = candidates,
            localPlane = localPlane,
            reprojectedCupWorld = reprojected,
            selectedOffsetRankReason = rankReason.wire
        )
    }

    fun resolveFinalCupWorldPoint(
        frame: Frame,
        sampler: V31HitSampler,
        cupCenterPx: PointF,
        projectedCupPx: Float,
        centerWorldLegacy: FloatArray?,
        rayDirYMin: Float,
        maxRayDistanceM: Float,
        distanceM: Float,
        farMode: Boolean
    ): FinalCupResolveOutcome {
        if (!USE_CENTER_AIM_OFFSET_ANCHOR) {
            return FinalCupResolveOutcome(
                world = null,
                diagnostics =
                    Diagnostics(
                        mode = "legacy_center",
                        candidateCount = 0,
                        bestVarianceCm = null,
                        bestResidualCm = null,
                        bestNormalY = null,
                        bestOffsetDistCm = null,
                        planeResidualCm = null,
                        reprojectSuccess = false,
                        failureReason = "flag_off",
                        reprojectedAffectsDistance = true,
                        reprojectedAffectsEndAnchor = true,
                        reprojectedAffectsExperimentalSurface = false
                    ),
                qualitySnapshot = null,
                anchorScreenPx = null
            )
        }
        if (centerWorldLegacy == null) {
            return FinalCupResolveOutcome(
                world = null,
                diagnostics =
                    Diagnostics(
                        mode = "legacy_center",
                        candidateCount = 0,
                        bestVarianceCm = null,
                        bestResidualCm = null,
                        bestNormalY = null,
                        bestOffsetDistCm = null,
                        planeResidualCm = null,
                        reprojectSuccess = false,
                        failureReason = "no_legacy_world",
                        reprojectedAffectsDistance = true,
                        reprojectedAffectsEndAnchor = true,
                        reprojectedAffectsExperimentalSurface = false
                    ),
                qualitySnapshot = null,
                anchorScreenPx = null
            )
        }
        val offsetRangeCm = computeAdaptiveOffsetRangeCm(distanceM, projectedCupPx, farMode)
        val result =
            estimateCupWorldPointWithOffsetAnchor(
                frame = frame,
                sampler = sampler,
                cupCenterPx = cupCenterPx,
                projectedCupPx = projectedCupPx,
                centerWorldRough = centerWorldLegacy,
                rayDirYMin = rayDirYMin,
                maxRayDistanceM = maxRayDistanceM,
                offsetRangeCm = offsetRangeCm
            )
        val reproj = result.reprojectedCupWorld
        val best = result.bestAnchor
        val plane = result.localPlane
        val success = reproj != null
        val failureReason =
            when {
                success -> null
                result.allCandidates.isEmpty() -> "no_passing_candidates"
                best == null -> "no_best_anchor"
                plane == null -> "plane_fit_failed"
                reproj == null -> "ray_plane_miss_or_ray_invalid"
                else -> "unknown"
            }
        val legacyDeltaCm = best?.distanceFromCupCenterM?.takeIf { it >= 0f }?.times(100f)
        val diag =
            Diagnostics(
                mode = "center_aim_offset_anchor",
                candidateCount = result.allCandidates.size,
                bestVarianceCm = best?.varianceM?.times(100f),
                bestResidualCm = best?.residualM?.times(100f),
                bestNormalY = best?.normalY,
                bestOffsetDistCm = legacyDeltaCm,
                planeResidualCm = plane?.residualM?.times(100f),
                reprojectSuccess = success,
                failureReason = failureReason,
                reprojectedAffectsDistance = true,
                reprojectedAffectsEndAnchor = true,
                reprojectedAffectsExperimentalSurface = false,
                cupAnchorEligibleMinCm = offsetRangeCm.minCm,
                cupAnchorEligibleMaxCm = offsetRangeCm.maxCm,
                cupAnchorLegacyDeltaCm = legacyDeltaCm,
                selectedOffsetCm = legacyDeltaCm,
                selectedOffsetScore = best?.score,
                selectedOffsetRankReason = result.selectedOffsetRankReason
            )
        if (OFFSET_ANCHOR_DEBUG_LOG) {
            logCupOffsetAnchorResult(result, success, failureReason)
        }
        val quality =
            if (success && best != null && plane != null) {
                OffsetAnchorQualitySnapshot(
                    fitResidualCm = plane.residualM * 100f,
                    planeTiltDeg = planeTiltDegFromNormalY(plane.normal[1]),
                    sampleCount = plane.sampleCount,
                    hitDispersionCm = best.varianceM * 100f
                )
            } else {
                null
            }
        val anchorPx = if (success && best != null) PointF(best.screenPx.x, best.screenPx.y) else null
        return FinalCupResolveOutcome(
            world = if (success) reproj!!.copyOf() else null,
            diagnostics = diag,
            qualitySnapshot = quality,
            anchorScreenPx = anchorPx
        )
    }

    private fun logCupOffsetAnchorResult(result: CupAnchorSelectionResult, success: Boolean, failureReason: String?) {
        val best = result.bestAnchor
        val plane = result.localPlane
        Log.d(
            "CupOffsetAnchor",
            buildString {
                append("mode=center_aim_offset_anchor ")
                append("success=$success ")
                append("failureReason=${failureReason ?: "none"} ")
                append("candidateCount=${result.allCandidates.size} ")
                append("bestScore=${best?.score} ")
                append("bestVarianceM=${best?.varianceM} ")
                append("bestNormalY=${best?.normalY} ")
                append("bestResidualM=${best?.residualM} ")
                append("bestOffsetDistM=${best?.distanceFromCupCenterM} ")
                append("planeResidualM=${plane?.residualM} ")
                append("planeSamples=${plane?.sampleCount} ")
                append("rankReason=${result.selectedOffsetRankReason} ")
                append("reprojected=${result.reprojectedCupWorld?.contentToString()} ")
            }
        )
    }

}
