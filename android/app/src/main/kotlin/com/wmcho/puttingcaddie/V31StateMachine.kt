package com.wmcho.puttingcaddie

import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v3.1 fixed engine (UI output only; no debug UI).
 *
 * Notes:
 * - hitTest is executed only via V31HitSampler (SSOT)
 * - LOCK requires:
 *   (a) sigma ok consecutive >= 6
 *   (b) AND elapsed ok time >= 300ms (time gate for low-FPS devices)
 */
class V31StateMachine(
    private val sampler: V31HitSampler,
    private val poseStats: PoseStatsMad,
    private val debugLoggingEnabled: Boolean = false
) {
    // Debug-only diagnostics for XYZ H/V issues (no UI exposure).
    private var dbgLastHvLogNs: Long = 0L
    private var dbgLastZoomHitLogNs: Long = 0L
    private var dbgPrevH: Float = Float.NaN
    private var dbgPrevV: Float = Float.NaN

    // Display-only stabilization for END phase (keeps LOCK logic unchanged).
    // We smooth ONLY what is shown during STABILIZING_END to reduce perceived jitter.
    private val endDisplayAvgN = 6
    private val endDisplayBuf = FloatArray(endDisplayAvgN)
    private var endDisplayBufSize = 0
    private var endDisplayBufIndex = 0

    private fun resetEndDisplayBuf() {
        endDisplayBufSize = 0
        endDisplayBufIndex = 0
    }

    private fun pushEndDisplayDistance(d: Float) {
        endDisplayBuf[endDisplayBufIndex] = d
        endDisplayBufIndex = (endDisplayBufIndex + 1) % endDisplayAvgN
        if (endDisplayBufSize < endDisplayAvgN) endDisplayBufSize++
    }

    private fun meanEndDisplayDistanceOrNaN(): Float {
        val n = endDisplayBufSize
        if (n <= 0) return Float.NaN
        var sum = 0f
        // Oldest element index in the ring buffer.
        var idx = endDisplayBufIndex - n
        if (idx < 0) idx += endDisplayAvgN
        for (i in 0 until n) {
            sum += endDisplayBuf[idx]
            idx++
            if (idx == endDisplayAvgN) idx = 0
        }
        return sum / n.toFloat()
    }

    enum class AxisMode { XZ, XYZ }

    enum class State {
        IDLE,
        AIM_START,
        STABILIZING_START,
        START_LOCKED,
        AIM_END,
        STABILIZING_END,
        END_LOCKED,
        RESULT,
        FAIL
    }

    sealed class UiEvent {
        data object StartPressed : UiEvent()
        data object FinishPressed : UiEvent()
        data object ResetPressed : UiEvent()
    }

    enum class FailReason { FAIL_NO_VALID_HITS, FAIL_TIMEOUT, FAIL_TRACKING_STOPPED }

    data class UiModel(
        val engineState: State,
        val distanceMeters: Float,
        val distanceTextColor: Int,
        val viewFinderState: ViewFinderView.State,
        val viewFinderQuality: ViewFinderView.QualityState,
        val flashLock: Boolean,
        val flashFail: Boolean,
        // Debug/feedback log metrics (GREENIQ)
        val sampleValidHits: Int,
        val sampleTotalPoints: Int,
        val sigmaUsedCm: Float?, // null if not computed yet
        val sigmaMaxCm: Float?, // null if not computed yet
        val fixDEstMeters: Float, // distance estimate used for sigma gating (meters)
        val bestHitDistanceFromCameraMeters: Float?, // HitResult.distance (meters)
        // Cup hold metrics (post-fix stability, ~1s)
        val cupHoldSigmaCm: Float?, // null until computed
        val cupHoldMaxCm: Float?, // null until computed
        val cupHoldDurationMs: Long?, // null until computed
        val failReasonCode: String?, // null unless FAIL
        val failDetailCode: String?, // null unless FAIL (or not classified)
        val fixedMinSamples: Int?, // stabilizing-only (for diagnostics)
        val bufSize: Int?, // stabilizing-only (for diagnostics)
        val sigmaOkConsecutive: Int?, // stabilizing-only (for diagnostics)
        val sigmaOkElapsedMs: Long?, // stabilizing-only (for diagnostics)
        val sigmaCurrentCmEnd: Float?, // END(Cup) only: current sigma (cm)
        val sigmaThresholdCmEnd: Float?, // END(Cup) only: sigma threshold used (cm)
        // GREENIQ LIVE diagnostics (logged on END_LOCKED/FAIL)
        val liveSource: String?,
        // GREENIQ BALL ground plane diagnostics (captured at BALL fix)
        val ballGroundPlaneNormalY: Float?,
        val ballGroundPlaneNormalLen: Float?,
        val ballGroundPlaneAbsNormalY: Float?,
        val ballGroundPlaneType: String?,
        val ballGroundPlaneTrackingState: String?,
        val ballGroundPlaneHitDistanceFromCameraMeters: Float?,
        val ballGroundPlaneExtentX: Float?,
        val ballGroundPlaneExtentZ: Float?,
        // GREENIQ Plane consistency (logged at CUP fix)
        val ballCupPlaneAngleDeg: Float?,
        // GREENIQ LIVE minimal diagnostics (for 6m rotation issue analysis)
        val liveRawMeters: Float?,        // raw distance BEFORE smoothing/clamp
        val centerHitValid: Boolean?,     // true iff LIVE raw was available (intersection or fallback)
        // GREENIQ Cup multi-ray diagnostics (logged on END_LOCKED)
        val multiRayGridHalfSpanPx: Float?,
        val multiRayStepPx: Float?,
        val validSampleCount: Int?,
        val hitDistanceAvgMeters: Float?,
        val hitDistanceMaxMeters: Float?,
        val cameraY: Float?,
        val medianY: Float?,
        val centerYOffsetApplied: Boolean?,
        val multiRayPlan: String?,
        val multiRayEstimatedDistanceMeters: Float?,
        val multiRayProjectedCupPx: Float?,
        val multiRayCenterFallbackUsed: Boolean?,
        // Ball robustness diagnostics (START-only tuning)
        val ballGridMode: String?,
        val ballGridStepPx: Float?,
        val ballSampleTotalPoints: Int?,
        val ballSampleValidHits: Int?,
        val ballHitSourceUsed: String?,
        val ballFreezeUsed: Boolean?,
        val ballFreezeAgeMs: Long?,
        val ballJumpRejected: Boolean?,
        val ballFixRuleWindow: Int?,
        val ballFixRuleNeedHits: Int?,
        val ballFixHitsInWindow: Int?,
        val ballFixState: String?,
        // XYZ mode support (UI-only): horizontal = sqrt(dx^2+dz^2), vertical = dy (signed)
        val horizontalVerticalMeters: Pair<Float, Float>?,
        val startEnabled: Boolean,
        val finishEnabled: Boolean,
        val statusWantsMoveDeviceText: Boolean,
        val isResultFinal: Boolean,
        val isMeasuringFlow: Boolean
    )

    // v3.1 constants
    private val FAIL_NO_VALID_HITS_M = 10
    private val STABILIZING_TIMEOUT_NS = 2_000_000_000L
    private val END_STABILIZING_TIMEOUT_NS = 4_000_000_000L
    private val PAUSED_GRACE_NS = 1_000_000_000L

    // END tuning (Cup): cap samples to reduce far-distance timeouts.
    private val END_MAX_MIN_SAMPLES = 18
    // END tuning (Cup): relax sigma gate (distance app mode).
    // Policy: allow ~2x of observed sigmaCurrent in each distance band.
    // NOTE: This is END-only; START/BALL remain unchanged.
    private fun endSigmaCapMeters(dMeters: Float): Float {
        return when {
            dMeters < 5f -> 0.06f  // 6cm
            dMeters < 8f -> 0.08f  // 8cm
            else -> 0.12f          // 12cm (8~10m+)
        }
    }

    private fun sigmaMaxEnd(dMeters: Float): Float {
        // Keep base distance-aware model, but guarantee a distance-based floor.
        // This avoids pathological low thresholds (e.g. ~1.7cm at 5m) that never become OK in practice.
        val raw = a + (b * dMeters)
        val cap = endSigmaCapMeters(dMeters)
        val floor = cap * 0.7f
        return max(raw, floor).coerceIn(sigmaMin, cap)
    }

    // sigma model params
    private val a = 0.003f
    private val b = 0.0015f
    private val sigmaMin = 0.005f
    private val sigmaCap = 0.03f

    // LOCK: 0.3s target @ 20Hz => 6 ticks + time gate >= 300ms
    private val LOCK_CONSEC_TICKS = 6
    private val LOCK_TIME_GATE_NS = 300_000_000L

    // GREENIQ Cup multi-ray settings (v1 directive)
    private val CUP_GRID_SIZE_POINTS = 25 // 5x5
    private val CUP_OFFSET_PERCENT_PRIMARY = 0.03f
    private val CUP_OFFSET_PERCENT_RETRY = 0.05f
    private val CUP_CENTER_Y_OFFSET_RATIO = 0.05f
    private val CUP_MIN_VALID_HITS = 8
    // Ball robustness (START-only): prioritize FIX success over precision.
    private val BALL_GRID_STEP_PX = 6f
    private val BALL_FIX_WINDOW_FRAMES = 10
    private val BALL_FIX_NEED_HITS = 3
    private val BALL_FIX_MIN_HOLD_NS = 150_000_000L
    private val BALL_FREEZE_TIMEOUT_NS = 2_000_000_000L
    private val BALL_JUMP_GATE_M = 0.50f

    // GREENIQ LIVE relaxed hit policy (v1 fix)
    private val LIVE_MAX_HIT_DISTANCE_M = 25f
    private val LIVE_JUMP_GUARD_M = 3.0f
    // Relaxed from 0.05 so ray-plane works when camera is behind ball (more horizontal view)
    private val LIVE_RAYDIR_Y_EPS = 0.02f
    // Frame clamp + smoothing (tuned): slightly faster than 0.4 / 85:15 while keeping stability
    private val LIVE_MAX_FRAME_DELTA_M = 0.55f

    var axisMode: AxisMode = AxisMode.XZ
    var state: State = State.IDLE
        private set

    private var failReason: FailReason? = null

    private var stabilizingEnterNs: Long = 0L
    private var pausedEnterNs: Long = 0L
    private var consecutiveNoValidHits: Int = 0

    private var fixedDEstMeters: Float = 0f
    private var fixedGrid: Int = 9
    private var fixedMinSamples: Int = 10

    private var sigmaOkConsecutive: Int = 0
    private var sigmaOkStartNs: Long = 0L
    private var lastSigmaUsedMeters: Float? = null
    private var lastSigmaMaxMeters: Float? = null
    private enum class SigmaPhase { START, END }
    private var lastSigmaPhase: SigmaPhase? = null

    private var lastFailDetailCode: String? = null
    private var lastFixedMinSamplesAtFail: Int? = null
    private var lastBufSizeAtFail: Int? = null
    private var lastSigmaOkConsecutiveAtFail: Int? = null
    private var lastSigmaOkElapsedMsAtFail: Long? = null

    // Cup hold (post-fix) stability measurement
    private var cupHoldStartNs: Long = 0L
    private val cupHoldBuf = ArrayList<PoseStatsMad.Vec3>(64)
    private var cupHoldMaxDevMeters: Float = 0f
    private var cupHoldSigmaMeters: Float? = null
    private var cupHoldDurationMs: Long? = null

    private var startAnchor: Anchor? = null
    private var endAnchor: Anchor? = null

    private val buf = ArrayList<PoseStatsMad.Vec3>(96)
    private var lastAimSample: V31HitSampler.Sample? = null
    private var startRequestPending: Boolean = false
    private var finishRequestPending: Boolean = false
    private var startLockedAtNs: Long = 0L
    private var endLockedAtNs: Long = 0L
    private var lastDisplayDistanceMeters: Float = 0f

    private var finalDistanceMeters: Float = 0f
    // Distance SSOT: LIVE snapshot at CUP lock (END_LOCKED).
    // Must never use WP/anchor-based distance for final distance.
    private var endLiveSnapshotMeters: Float = 0f

    // GREENIQ LIVE (laser) smoothing state (display-only)
    private var liveSmoothedMeters: Float = 0f
    private var liveHasValue: Boolean = false
    private enum class LiveSource { PLANE_INTERSECTION, HITTEST_FALLBACK, NONE }
    private var liveSource: LiveSource = LiveSource.NONE

    private data class GroundPlaneModel(
        val pointOnPlane: PoseStatsMad.Vec3,
        val normal: PoseStatsMad.Vec3,
        val source: String
    )

    private var groundPlaneModel: GroundPlaneModel? = null
    private var ballGroundPlaneNormalY: Float? = null
    private var ballGroundPlaneNormalLen: Float? = null
    private var ballGroundPlaneAbsNormalY: Float? = null
    private var ballGroundPlaneType: String? = null
    private var ballGroundPlaneTrackingState: String? = null
    private var ballGroundPlaneHitDistanceFromCameraMeters: Float? = null
    private var ballGroundPlaneExtentX: Float? = null
    private var ballGroundPlaneExtentZ: Float? = null
    private var ballCupPlaneAngleDeg: Float? = null

    // LIVE minimal diagnostics (must not affect computation)
    private var liveRawMeters: Float? = null
    private var centerHitValid: Boolean? = null

    // GREENIQ Cup multi-ray diagnostics (copied from sampler output for logging)
    private var lastMultiRayGridHalfSpanPx: Float? = null
    private var lastMultiRayStepPx: Float? = null
    private var lastValidSampleCount: Int? = null
    private var lastHitDistanceAvgMeters: Float? = null
    private var lastHitDistanceMaxMeters: Float? = null
    private var lastCameraY: Float? = null
    private var lastMedianY: Float? = null
    private var lastCenterYOffsetApplied: Boolean? = null
    private var lastMultiRayPlan: String? = null
    private var lastMultiRayEstimatedDistanceMeters: Float? = null
    private var lastMultiRayProjectedCupPx: Float? = null
    private var lastMultiRayCenterFallbackUsed: Boolean? = null
    // Ball robustness state
    private val ballHitWindow = ArrayDeque<Boolean>(BALL_FIX_WINDOW_FRAMES)
    private var ballFixHitsInWindow: Int = 0
    private var ballLastGoodHit: HitResult? = null
    private var ballLastGoodPose: PoseStatsMad.Vec3? = null
    private var ballLastGoodNs: Long = 0L
    private var ballDiagGridMode: String? = null
    private var ballDiagGridStepPx: Float? = null
    private var ballDiagSampleTotalPoints: Int? = null
    private var ballDiagSampleValidHits: Int? = null
    private var ballDiagHitSourceUsed: String? = null
    private var ballDiagFreezeUsed: Boolean? = null
    private var ballDiagFreezeAgeMs: Long? = null
    private var ballDiagJumpRejected: Boolean? = null
    private var ballDiagFixState: String? = null

    private fun currentBallFixNeedHits(): Int {
        // Minimal guard for 3.0x zoom: require one extra hit to reduce false fixes.
        val z = sampler.currentZoomLevel()
        return if (z >= 2.9f) 4 else BALL_FIX_NEED_HITS
    }

    fun onUiEvent(e: UiEvent, nowNs: Long) {
        when (e) {
            UiEvent.ResetPressed -> resetAll()
            UiEvent.StartPressed -> {
                when (state) {
                    State.IDLE -> {
                        state = State.AIM_START
                        failReason = null
                        finalDistanceMeters = 0f
                        endLiveSnapshotMeters = 0f
                        ballHitWindow.clear()
                        ballFixHitsInWindow = 0
                        ballLastGoodHit = null
                        ballLastGoodPose = null
                        ballLastGoodNs = 0L
                        startAnchor?.detach(); startAnchor = null
                        endAnchor?.detach(); endAnchor = null
                        lastAimSample = null
                        startRequestPending = true
                        resetEndDisplayBuf()
                    }
                    State.AIM_START -> {
                        startRequestPending = true
                    }
                    // Hard ignore to prevent duplicate arming/anchors
                    State.STABILIZING_START,
                    State.START_LOCKED,
                    State.AIM_END,
                    State.STABILIZING_END,
                    State.END_LOCKED,
                    State.RESULT,
                    State.FAIL -> Unit
                    else -> Unit
                }
            }
            UiEvent.FinishPressed -> {
                when (state) {
                    State.AIM_END -> {
                        finishRequestPending = true
                    }
                    State.END_LOCKED -> {
                        // Final distance SSOT: LIVE snapshot (never WP/anchor-based distance).
                        finalDistanceMeters =
                            endLiveSnapshotMeters.takeIf { it.isFinite() && it > 0f }
                                ?: lastDisplayDistanceMeters.takeIf { it.isFinite() && it > 0f }
                                ?: 0f
                        state = State.RESULT
                    }
                    // Hard ignore
                    State.STABILIZING_START,
                    State.START_LOCKED,
                    State.AIM_START,
                    State.STABILIZING_END,
                    State.RESULT,
                    State.FAIL,
                    State.IDLE -> Unit
                    else -> Unit
                }
            }
        }
    }

    fun tick(frame: Frame, roiScreen: RectF, nowNs: Long): UiModel {
        // START_LOCKED is a transient state (kept for spec alignment).
        if (state == State.START_LOCKED && startLockedAtNs > 0L) {
            // Keep at least one tick; then proceed.
            if (nowNs - startLockedAtNs >= 1L) {
                state = State.AIM_END
                startLockedAtNs = 0L
                resetEndDisplayBuf()
            }
        }
        // END_LOCKED is also transient: auto-finalize RESULT without second tap.
        if (state == State.END_LOCKED && endLockedAtNs > 0L) {
            if (nowNs - endLockedAtNs >= 1L) {
                finalDistanceMeters =
                    endLiveSnapshotMeters.takeIf { it.isFinite() && it > 0f }
                        ?: lastDisplayDistanceMeters.takeIf { it.isFinite() && it > 0f }
                        ?: 0f
                state = State.RESULT
                endLockedAtNs = 0L
            }
        }

        val tracking = frame.camera.trackingState

        // STOPPED => immediate FAIL
        if (tracking == TrackingState.STOPPED) {
            enterFail(FailReason.FAIL_TRACKING_STOPPED)
            return buildUi(nowNs, tracking, flashFail = true)
        }

        val inStabilizing = (state == State.STABILIZING_START || state == State.STABILIZING_END)

        // PAUSED => stop accumulating, but if stabilizing and persists >= 1s => timeout
        if (tracking == TrackingState.PAUSED) {
            if (inStabilizing) {
                if (pausedEnterNs == 0L) pausedEnterNs = nowNs
                if (nowNs - pausedEnterNs >= PAUSED_GRACE_NS) {
                    enterFail(FailReason.FAIL_TIMEOUT)
                    return buildUi(nowNs, tracking, flashFail = true)
                }
            }
            return buildUi(nowNs, tracking)
        } else {
            pausedEnterNs = 0L
        }

        val sampling =
            (state == State.AIM_START || state == State.STABILIZING_START || state == State.AIM_END || state == State.STABILIZING_END)
        if (!sampling) return buildUi(nowNs, tracking)

        val grid =
            when (state) {
                State.AIM_END, State.STABILIZING_END -> CUP_GRID_SIZE_POINTS
                State.AIM_START -> 9
                State.STABILIZING_START -> fixedGrid
                else -> 9
            }

        val sample =
            if (state == State.AIM_END || state == State.STABILIZING_END) {
                // Cup FIX sampling: multi-ray 5x5 centered near screen center with Y offset and distance/Y guards.
                var s =
                    sampler.sampleCupPlaneMultiRay(
                        frame = frame,
                        baseRoiScreen = roiScreen,
                        offsetPercent = CUP_OFFSET_PERCENT_PRIMARY,
                        centerYOffsetRatio = CUP_CENTER_Y_OFFSET_RATIO,
                        gridSize = 5,
                        maxHitDistanceMeters = 12f,
                        yBelowCameraMeters = 0.1f,
                        preferUpwardFacing = true,
                        requireUpwardFacing = false
                    )
                if (s.validHits < 5) {
                    val retry =
                        sampler.sampleCupPlaneMultiRay(
                            frame = frame,
                            baseRoiScreen = roiScreen,
                            offsetPercent = CUP_OFFSET_PERCENT_RETRY,
                            centerYOffsetRatio = CUP_CENTER_Y_OFFSET_RATIO,
                            gridSize = 5,
                            // 2nd pass only: widen distance cap + slightly relax Y filter for hit availability.
                            maxHitDistanceMeters = 18f,
                            yBelowCameraMeters = 0.05f,
                            preferUpwardFacing = true,
                            requireUpwardFacing = false
                        )
                    if (retry.validHits > s.validHits) s = retry
                }

                // Persist diagnostics for activity logging (END_LOCKED).
                lastMultiRayGridHalfSpanPx = s.gridHalfSpanPx
                lastMultiRayStepPx = s.gridStepPx
                lastValidSampleCount = s.validHits
                lastHitDistanceAvgMeters = s.hitDistanceAvgMeters
                lastHitDistanceMaxMeters = s.hitDistanceMaxMeters
                lastCameraY = s.cameraY
                lastMedianY = s.medianY
                lastCenterYOffsetApplied = s.centerYOffsetApplied
                lastMultiRayPlan = s.gridPlan
                lastMultiRayEstimatedDistanceMeters = s.gridEstimatedDistanceMeters
                lastMultiRayProjectedCupPx = s.gridProjectedCupPx
                lastMultiRayCenterFallbackUsed = s.centerFallbackUsed
                s
            } else {
                // Default sampling (BALL fix + generic UI quality)
                val s = sampler.sampleBestHit(frame, roiScreen, grid)
                // Clear cup diagnostics outside cup sampling window (avoid leaking old values).
                lastMultiRayGridHalfSpanPx = null
                lastMultiRayStepPx = null
                lastValidSampleCount = null
                lastHitDistanceAvgMeters = null
                lastHitDistanceMaxMeters = null
                lastCameraY = null
                lastMedianY = null
                lastCenterYOffsetApplied = null
                lastMultiRayPlan = null
                lastMultiRayEstimatedDistanceMeters = null
                lastMultiRayProjectedCupPx = null
                lastMultiRayCenterFallbackUsed = null
                s
            }

        // START/BALL robustness path (does NOT affect CUP/END):
        // - 3x3 sampling with source priority happens in sampler
        // - keep last good hit as FREEZE fallback
        // - reject large jumps before updating last good pose
        val inBallStates = (state == State.AIM_START || state == State.STABILIZING_START)
        var ballGridModeTick: String? = null
        var ballGridStepPxTick: Float? = null
        var ballSampleTotalPointsTick: Int? = null
        var ballSampleValidHitsTick: Int? = null
        var ballHitSourceUsedTick: String? = null
        var ballFreezeUsedTick: Boolean? = null
        var ballFreezeAgeMsTick: Long? = null
        var ballJumpRejectedTick: Boolean? = null
        var ballFixStateTick: String? = null
        var ballEffectiveHitForTick: HitResult? = null

        if (inBallStates) {
            ballGridModeTick = if (sample.totalPoints == 9) "GRID_3x3" else "CENTER_1"
            ballGridStepPxTick = if (sample.totalPoints == 9) BALL_GRID_STEP_PX else null
            ballSampleTotalPointsTick = sample.totalPoints
            ballSampleValidHitsTick = sample.validHits

            var candidate: HitResult? = if (sample.bestHit != null && sample.validHits > 0) sample.bestHit else null
            ballHitSourceUsedTick = sample.hitType.name
            ballJumpRejectedTick = false
            ballFreezeUsedTick = false

            if (candidate != null) {
                val p = candidate.hitPose
                val curPose = PoseStatsMad.Vec3(p.tx(), p.ty(), p.tz())
                val prevPose = ballLastGoodPose
                if (prevPose != null) {
                    val jump = poseDistance(prevPose, curPose)
                    if (jump > BALL_JUMP_GATE_M) {
                        // Outlier update rejected; keep FREEZE candidate instead.
                        candidate = null
                        ballJumpRejectedTick = true
                    } else {
                        ballLastGoodHit = candidate
                        ballLastGoodPose = curPose
                        ballLastGoodNs = nowNs
                    }
                } else {
                    ballLastGoodHit = candidate
                    ballLastGoodPose = curPose
                    ballLastGoodNs = nowNs
                }
            }

            if (candidate == null) {
                val canFreeze = (ballLastGoodHit != null && nowNs - ballLastGoodNs <= BALL_FREEZE_TIMEOUT_NS)
                if (canFreeze) {
                    ballEffectiveHitForTick = ballLastGoodHit
                    ballFreezeUsedTick = true
                    ballHitSourceUsedTick = "FREEZE"
                    ballFreezeAgeMsTick = ((nowNs - ballLastGoodNs) / 1_000_000L).coerceAtLeast(0L)
                } else {
                    ballEffectiveHitForTick = null
                    ballFreezeAgeMsTick = null
                }
            } else {
                ballEffectiveHitForTick = candidate
                ballFreezeAgeMsTick = 0L
            }

            if (state == State.AIM_START) {
                pushBallWindowHit(ballEffectiveHitForTick != null)
            }
            ballFixStateTick = when {
                state == State.AIM_START && ballEffectiveHitForTick == null -> "UNLOCKED"
                state == State.AIM_START && ballEffectiveHitForTick != null -> "COLLECTING"
                state == State.STABILIZING_START && ballFreezeUsedTick == true -> "FROZEN"
                state == State.STABILIZING_START -> "COLLECTING"
                else -> null
            }
            ballDiagGridMode = ballGridModeTick
            ballDiagGridStepPx = ballGridStepPxTick
            ballDiagSampleTotalPoints = ballSampleTotalPointsTick
            ballDiagSampleValidHits = ballSampleValidHitsTick
            ballDiagHitSourceUsed = ballHitSourceUsedTick
            ballDiagFreezeUsed = ballFreezeUsedTick
            ballDiagFreezeAgeMs = ballFreezeAgeMsTick
            ballDiagJumpRejected = ballJumpRejectedTick
            ballDiagFixState = ballFixStateTick
        } else {
            ballDiagGridMode = null
            ballDiagGridStepPx = null
            ballDiagSampleTotalPoints = null
            ballDiagSampleValidHits = null
            ballDiagHitSourceUsed = null
            ballDiagFreezeUsed = null
            ballDiagFreezeAgeMs = null
            ballDiagJumpRejected = null
            ballDiagFixState = null
        }

        // GREENIQ LIVE (laser): prefer ray-plane intersection against saved ground plane.
        // Fallback to relaxed plane hitTest only if intersection is unavailable.
        // Display-only; does not affect locking logic.
        val inLiveStates = (state == State.AIM_END || state == State.STABILIZING_END)
        if (inLiveStates && startAnchor != null) {
            var raw: Float? = null
            liveSource = LiveSource.NONE
            // reset per-tick LIVE diagnostics
            liveRawMeters = null
            centerHitValid = false

            // 1) Ray-plane intersection (primary)
            val gp = groundPlaneModel
            if (gp != null) {
                val camPose = frame.camera.pose
                val uv = sampler.screenPointToAdjustedTextureUv(frame, roiScreen.centerX(), roiScreen.centerY())
                val intr = frame.camera.textureIntrinsics
                if (uv != null) {
                    val dims = intr.imageDimensions
                    val texW = dims[0].toFloat().takeIf { it > 1f } ?: 1f
                    val texH = dims[1].toFloat().takeIf { it > 1f } ?: 1f
                    val xPx = (uv.x.coerceIn(0f, 1f) * texW)
                    val yPx = (uv.y.coerceIn(0f, 1f) * texH)

                    val fx = intr.focalLength[0].takeIf { it > 1e-6f } ?: 1f
                    val fy = intr.focalLength[1].takeIf { it > 1e-6f } ?: 1f
                    val cx = intr.principalPoint[0]
                    val cy = intr.principalPoint[1]

                    // Camera coordinates: +X right, +Y up, -Z forward.
                    var dx = (xPx - cx) / fx
                    var dy = (yPx - cy) / fy
                    var dz = -1f
                    val norm = sqrt(dx * dx + dy * dy + dz * dz).takeIf { it > 1e-6f } ?: 1f
                    dx /= norm; dy /= norm; dz /= norm

                    val dirCam = floatArrayOf(dx, dy, dz)
                    val dirWorld = FloatArray(3)
                    camPose.rotateVector(dirCam, 0, dirWorld, 0)

                    val ox = camPose.tx()
                    val oy = camPose.ty()
                    val oz = camPose.tz()

                    val nx = gp.normal.x
                    val ny = gp.normal.y
                    val nz = gp.normal.z
                    val denom = (nx * dirWorld[0] + ny * dirWorld[1] + nz * dirWorld[2])
                    // Additional sanity: if the ray is near-horizontal, intersection becomes numerically unstable.
                    if (abs(dirWorld[1]) >= LIVE_RAYDIR_Y_EPS && abs(denom) > 1e-5f) {
                        val px = gp.pointOnPlane.x
                        val py = gp.pointOnPlane.y
                        val pz = gp.pointOnPlane.z
                        val t = (nx * (px - ox) + ny * (py - oy) + nz * (pz - oz)) / denom
                        if (t.isFinite() && t > 0f && t <= LIVE_MAX_HIT_DISTANCE_M) {
                            val ix = ox + (dirWorld[0] * t)
                            val iy = oy + (dirWorld[1] * t)
                            val iz = oz + (dirWorld[2] * t)
                            val iPose = Pose.makeTranslation(ix, iy, iz)
                            raw = distanceFromStartToPoseMeters(startAnchor!!.pose, iPose)
                            liveSource = LiveSource.PLANE_INTERSECTION
                        }
                    }
                }
            }

            // 2) Fallback: hitTest with FARTHEST hit (ball-to-cup; camera may be behind ball)
            if (raw == null) {
                val centerHit =
                    sampler.hitTestBestPlaneAtScreenPoint(
                        frame = frame,
                        screenX = roiScreen.centerX(),
                        screenY = roiScreen.centerY(),
                        maxDistanceMeters = LIVE_MAX_HIT_DISTANCE_M,
                        preferUpwardFacing = false,
                        yBelowCameraMeters = null,
                        preferFarthestForDistance = true
                    )
                if (centerHit != null) {
                    raw = distanceFromStartToPoseMeters(startAnchor!!.pose, centerHit.hitPose)
                    liveSource = LiveSource.HITTEST_FALLBACK
                }
            }

            if (raw != null && raw!!.isFinite() && raw!! > 0f) {
                val cur = raw!!
                // Record raw distance BEFORE smoothing/clamp (diagnostic only)
                liveRawMeters = cur
                centerHitValid = true
                if (!liveHasValue) {
                    liveSmoothedMeters = cur
                    liveHasValue = true
                } else {
                    val prev = liveSmoothedMeters
                    // Frame clamp: limit change per frame for smooth display on rapid camera move
                    val delta = (cur - prev).coerceIn(-LIVE_MAX_FRAME_DELTA_M, LIVE_MAX_FRAME_DELTA_M)
                    val curClamped = prev + delta
                    val jumpLimit = max(LIVE_JUMP_GUARD_M, prev * 0.35f)
                    if (abs(curClamped - prev) <= jumpLimit) {
                        // Balanced inertia (75/25): smoother than baseline, faster than 85/15
                        liveSmoothedMeters = (prev * 0.75f) + (curClamped * 0.25f)
                    }
                }
                if (debugLoggingEnabled && nowNs - dbgLastZoomHitLogNs >= 300_000_000L) {
                    dbgLastZoomHitLogNs = nowNs
                    Log.d(
                        "ZOOM_HIT",
                        "state=$state src=${liveSource.name} raw=${"%.3f".format(cur)} smooth=${"%.3f".format(liveSmoothedMeters)} " +
                            "bestHitDist=${"%.3f".format(sample.bestHit?.distance ?: 0f)} hitType=${sample.hitType} valid=${sample.validHits}/${sample.totalPoints}"
                    )
                }
            }
            // If no valid raw: keep previous live (per directive).
        } else {
            // Not in LIVE states (or BALL not fixed): reset display live to avoid stale values.
            liveHasValue = false
            liveSmoothedMeters = 0f
            liveSource = LiveSource.NONE
            liveRawMeters = null
            centerHitValid = null
        }

        // --- Debug diagnostics (XYZ mode) ---
        // Helps detect:
        // - V (dy) stuck near 0 (likely hitting same horizontal plane)
        // - Sudden H (sqrt(dx^2+dz^2)) jumps (hit switched / snapping)
        // - hitType (PLANE/DEPTH/POINT) causing quantization
        if (debugLoggingEnabled && axisMode == AxisMode.XYZ) {
            val shouldLogState =
                state == State.AIM_START ||
                    state == State.STABILIZING_START ||
                    state == State.AIM_END ||
                    state == State.STABILIZING_END

            if (shouldLogState &&
                sample.bestHit != null &&
                (nowNs - dbgLastHvLogNs >= 300_000_000L)
            ) {
                dbgLastHvLogNs = nowNs

                val a = startAnchor?.pose
                val b = sample.bestHit!!.hitPose
                if (a != null) {
                    val dx = b.tx() - a.tx()
                    val dy = b.ty() - a.ty()
                    val dz = b.tz() - a.tz()
                    val h = sqrt(dx * dx + dz * dz)
                    val v = dy

                    val dH = if (dbgPrevH.isFinite()) kotlin.math.abs(h - dbgPrevH) else 0f
                    val dV = if (dbgPrevV.isFinite()) kotlin.math.abs(v - dbgPrevV) else 0f
                    dbgPrevH = h
                    dbgPrevV = v

                    val msg =
                        "XYZ_HV state=$state hitType=${sample.hitType} valid=${sample.validHits}/${sample.totalPoints} " +
                            "hitDist=${"%.2f".format(sample.bestHit!!.distance)} " +
                            "dx=${"%.3f".format(dx)} dy=${"%.3f".format(dy)} dz=${"%.3f".format(dz)} " +
                            "H=${"%.3f".format(h)} V=${"%.3f".format(v)} dH=${"%.3f".format(dH)} dV=${"%.3f".format(dV)}"

                    if (dH >= 0.50f) Log.w("V31StateMachine", "HV_JUMP $msg") else Log.d("V31StateMachine", msg)
                } else {
                    Log.d(
                        "V31StateMachine",
                        "XYZ_HV state=$state (no startAnchor) hitType=${sample.hitType} valid=${sample.validHits}/${sample.totalPoints}"
                    )
                }
            }
        }

        if (state == State.AIM_START || state == State.AIM_END) {
            lastAimSample = sample
            if (state == State.AIM_START && startRequestPending) {
                val hit = ballEffectiveHitForTick ?: sample.bestHit
                if (hit != null && ballFixHitsInWindow >= currentBallFixNeedHits()) {
                    startRequestPending = false
                    enterStabilizingStart(nowNs, hit)
                    return buildUi(nowNs, tracking, sample)
                }
            }
            if (state == State.AIM_END && finishRequestPending) {
                val hit = sample.bestHit
                if (hit != null && sample.validHits >= minValidHitsForCupEnd(sample.totalPoints) && startAnchor != null) {
                    finishRequestPending = false
                    enterStabilizingEnd(nowNs, hit)
                    return buildUi(nowNs, tracking, sample)
                }
            }
            return buildUi(nowNs, tracking, sample)
        }

        // stabilizing logic
        if (state == State.STABILIZING_START || state == State.STABILIZING_END) {
            val timeoutNs = if (state == State.STABILIZING_END) END_STABILIZING_TIMEOUT_NS else STABILIZING_TIMEOUT_NS
            if (nowNs - stabilizingEnterNs >= timeoutNs) {
                // Classify timeout cause (best-effort; diagnostics only)
                lastFixedMinSamplesAtFail = fixedMinSamples
                lastBufSizeAtFail = buf.size
                lastSigmaOkConsecutiveAtFail = sigmaOkConsecutive
                lastSigmaOkElapsedMsAtFail = if (sigmaOkStartNs > 0L) ((nowNs - sigmaOkStartNs) / 1_000_000L) else 0L
                lastFailDetailCode =
                    when {
                        tracking != TrackingState.TRACKING -> "TIMEOUT_TRACKING_NOT_OK"
                        buf.size < fixedMinSamples -> "TIMEOUT_NOT_ENOUGH_SAMPLES"
                        else -> {
                            val sigmaUsed = lastSigmaUsedMeters
                            val sigmaMax = lastSigmaMaxMeters
                            if (sigmaUsed == null || sigmaMax == null || !sigmaUsed.isFinite() || !sigmaMax.isFinite()) {
                                "TIMEOUT_NO_SIGMA_COMPUTED"
                            } else {
                                val sigmaOk = sigmaUsed <= sigmaMax
                                if (!sigmaOk) {
                                    "TIMEOUT_SIGMA_NOT_OK"
                                } else {
                                    val okElapsed = if (sigmaOkStartNs > 0L) (nowNs - sigmaOkStartNs) else 0L
                                    when {
                                        sigmaOkConsecutive < LOCK_CONSEC_TICKS -> "TIMEOUT_NO_CONSECUTIVE_OK"
                                        okElapsed < LOCK_TIME_GATE_NS -> "TIMEOUT_TIME_GATE"
                                        else -> "TIMEOUT_OTHER"
                                    }
                                }
                            }
                        }
                    }
                enterFail(FailReason.FAIL_TIMEOUT)
                return buildUi(nowNs, tracking, sample, flashFail = true)
            }

            val stabilizingHit = if (state == State.STABILIZING_START) (ballEffectiveHitForTick ?: sample.bestHit) else sample.bestHit
            val stabilizingValidHits = if (state == State.STABILIZING_START) (if (stabilizingHit != null) max(1, sample.validHits) else 0) else sample.validHits
            val ok =
                (stabilizingHit != null) &&
                    (
                        if (state == State.STABILIZING_END) {
                            stabilizingValidHits >= minValidHitsForCupEnd(sample.totalPoints)
                        } else {
                            // START/BALL: availability-first policy (do not block on high hit count).
                            stabilizingValidHits >= 1
                        }
                        )
            if (!ok) {
                consecutiveNoValidHits++
                if (consecutiveNoValidHits >= FAIL_NO_VALID_HITS_M) {
                    lastFixedMinSamplesAtFail = fixedMinSamples
                    lastBufSizeAtFail = buf.size
                    lastSigmaOkConsecutiveAtFail = sigmaOkConsecutive
                    lastSigmaOkElapsedMsAtFail = if (sigmaOkStartNs > 0L) ((nowNs - sigmaOkStartNs) / 1_000_000L) else 0L
                    lastFailDetailCode = "NO_VALID_HITS"
                    enterFail(FailReason.FAIL_NO_VALID_HITS)
                    return buildUi(nowNs, tracking, sample, flashFail = true)
                }
                return buildUi(nowNs, tracking, sample)
            }
            consecutiveNoValidHits = 0

            val p = stabilizingHit!!.hitPose
            buf.add(PoseStatsMad.Vec3(p.tx(), p.ty(), p.tz()))

            if (state == State.STABILIZING_START) {
                val holdElapsed = nowNs - stabilizingEnterNs
                if (buf.size >= currentBallFixNeedHits() && holdElapsed >= BALL_FIX_MIN_HOLD_NS) {
                    confirmLock(nowNs, stabilizingHit)
                    buf.clear()
                    lastAimSample = null
                    return buildUi(nowNs, tracking, sample, flashLock = true)
                }
                return buildUi(nowNs, tracking, sample)
            }

            if (buf.size >= fixedMinSamples) {
                val sig = poseStats.computeSigma(buf)
                val sigmaUsed =
                    when (axisMode) {
                        AxisMode.XYZ -> sig.sigmaXYZ
                        AxisMode.XZ -> sig.sigmaXZ
                    }
                val sigmaMax = if (state == State.STABILIZING_END) sigmaMaxEnd(fixedDEstMeters) else sigmaMax(fixedDEstMeters)
                lastSigmaUsedMeters = if (sigmaUsed.isFinite()) sigmaUsed else null
                lastSigmaMaxMeters = if (sigmaMax.isFinite()) sigmaMax else null
                lastSigmaPhase = if (state == State.STABILIZING_END) SigmaPhase.END else SigmaPhase.START

                val sigmaOk = sigmaUsed.isFinite() && sigmaUsed <= sigmaMax
                if (sigmaOk) {
                    if (sigmaOkConsecutive == 0) sigmaOkStartNs = nowNs
                    sigmaOkConsecutive++
                } else {
                    sigmaOkConsecutive = 0
                    sigmaOkStartNs = 0L
                }

                val okElapsed = if (sigmaOkStartNs > 0L) (nowNs - sigmaOkStartNs) else 0L
                if (sigmaOkConsecutive >= LOCK_CONSEC_TICKS && okElapsed >= LOCK_TIME_GATE_NS) {
                    confirmLock(nowNs, stabilizingHit)
                    sigmaOkConsecutive = 0
                    sigmaOkStartNs = 0L
                    buf.clear()
                    lastAimSample = null
                    return buildUi(nowNs, tracking, sample, flashLock = true)
                }
            }

            return buildUi(nowNs, tracking, sample)
        }

        return buildUi(nowNs, tracking, sample)
    }

    private fun enterStabilizingStart(nowNs: Long, hit: HitResult) {
        state = State.STABILIZING_START
        failReason = null
        lastFailDetailCode = null
        lastFixedMinSamplesAtFail = null
        lastBufSizeAtFail = null
        lastSigmaOkConsecutiveAtFail = null
        lastSigmaOkElapsedMsAtFail = null
        stabilizingEnterNs = nowNs
        pausedEnterNs = 0L
        consecutiveNoValidHits = 0
        sigmaOkConsecutive = 0
        sigmaOkStartNs = 0L
        lastSigmaUsedMeters = null
        lastSigmaMaxMeters = null
        // reset cup-hold metrics when starting a new flow
        cupHoldStartNs = 0L
        cupHoldBuf.clear()
        cupHoldMaxDevMeters = 0f
        cupHoldSigmaMeters = null
        cupHoldDurationMs = null
        buf.clear()

        fixedDEstMeters = hit.distance
        // START/BALL: prioritize FIX availability over precision.
        fixedGrid = 9
        fixedMinSamples = currentBallFixNeedHits()
    }

    private fun enterStabilizingEnd(nowNs: Long, hit: HitResult) {
        state = State.STABILIZING_END
        failReason = null
        lastFailDetailCode = null
        lastFixedMinSamplesAtFail = null
        lastBufSizeAtFail = null
        lastSigmaOkConsecutiveAtFail = null
        lastSigmaOkElapsedMsAtFail = null
        stabilizingEnterNs = nowNs
        pausedEnterNs = 0L
        consecutiveNoValidHits = 0
        sigmaOkConsecutive = 0
        sigmaOkStartNs = 0L
        lastSigmaUsedMeters = null
        lastSigmaMaxMeters = null
        buf.clear()
        resetEndDisplayBuf()

        val start = startAnchor?.pose
        fixedDEstMeters =
            if (start != null) distanceMeters(start, hit.hitPose) else hit.distance
        // Cup directive: force 5x5 multi-ray for end stabilization.
        fixedGrid = CUP_GRID_SIZE_POINTS
        fixedMinSamples = chooseMinSamplesForDEst(fixedDEstMeters).coerceAtMost(END_MAX_MIN_SAMPLES)
    }

    private fun confirmLock(nowNs: Long, hit: HitResult) {
        val anchor = hit.createAnchor()
        when (state) {
            State.STABILIZING_START -> {
                startAnchor?.detach()
                startAnchor = anchor
                state = State.START_LOCKED
                startLockedAtNs = System.nanoTime()

                // Capture ground plane model at BALL fix time for LIVE ray-plane intersection.
                val t = hit.trackable
                val plane = t as? Plane
                if (plane != null) {
                    val center = plane.centerPose
                    val axis = FloatArray(3)
                    center.getTransformedAxis(1, 1.0f, axis, 0) // Y axis as plane normal in world
                    val nLenRaw = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2])
                    val nNorm = nLenRaw.takeIf { it > 1e-6f } ?: 1f
                    val nx = axis[0] / nNorm
                    val ny = axis[1] / nNorm
                    val nz = axis[2] / nNorm
                    groundPlaneModel =
                        GroundPlaneModel(
                            pointOnPlane = PoseStatsMad.Vec3(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()),
                            normal = PoseStatsMad.Vec3(nx, ny, nz),
                            source = "BALL_FIX_PLANE"
                        )
                    // Diagnostics (quality proxy)
                    ballGroundPlaneNormalY = ny
                    ballGroundPlaneAbsNormalY = kotlin.math.abs(ny)
                    ballGroundPlaneNormalLen = nLenRaw
                    ballGroundPlaneType = plane.type.name
                    ballGroundPlaneTrackingState = plane.trackingState.name
                    ballGroundPlaneHitDistanceFromCameraMeters = hit.distance
                    ballGroundPlaneExtentX = plane.extentX
                    ballGroundPlaneExtentZ = plane.extentZ
                    Log.d(
                        "V31StateMachine",
                        "BALL_FIX_OK src=${ballDiagHitSourceUsed ?: "UNKNOWN"} freeze=${ballDiagFreezeUsed ?: false} freezeAgeMs=${ballDiagFreezeAgeMs ?: -1} " +
                            "jumpRejected=${ballDiagJumpRejected ?: false} hitsWindow=$ballFixHitsInWindow/${BALL_FIX_WINDOW_FRAMES} ruleNeed=${currentBallFixNeedHits()} " +
                            "valid=${ballDiagSampleValidHits ?: 0}/${ballDiagSampleTotalPoints ?: 0} grid=${ballDiagGridMode ?: "UNKNOWN"} stepPx=${ballDiagGridStepPx ?: 0f} " +
                            "planeType=${plane.type.name} absNy=${"%.3f".format(kotlin.math.abs(ny))}"
                    )
                } else {
                    groundPlaneModel = null
                    ballGroundPlaneNormalY = null
                    ballGroundPlaneAbsNormalY = null
                    ballGroundPlaneNormalLen = null
                    ballGroundPlaneType = null
                    ballGroundPlaneTrackingState = null
                    ballGroundPlaneHitDistanceFromCameraMeters = null
                    ballGroundPlaneExtentX = null
                    ballGroundPlaneExtentZ = null
                    Log.d(
                        "V31StateMachine",
                        "BALL_FIX_OK src=${ballDiagHitSourceUsed ?: "UNKNOWN"} freeze=${ballDiagFreezeUsed ?: false} freezeAgeMs=${ballDiagFreezeAgeMs ?: -1} " +
                            "jumpRejected=${ballDiagJumpRejected ?: false} hitsWindow=$ballFixHitsInWindow/${BALL_FIX_WINDOW_FRAMES} ruleNeed=${currentBallFixNeedHits()} " +
                            "valid=${ballDiagSampleValidHits ?: 0}/${ballDiagSampleTotalPoints ?: 0} grid=${ballDiagGridMode ?: "UNKNOWN"} stepPx=${ballDiagGridStepPx ?: 0f} planeType=NONE"
                    )
                }
            }
            State.STABILIZING_END -> {
                endAnchor?.detach()
                endAnchor = anchor
                state = State.END_LOCKED
                endLockedAtNs = nowNs
                // Capture LIVE snapshot to prevent END_LOCKED jump to anchor-based distance.
                endLiveSnapshotMeters =
                    (if (liveHasValue && liveSmoothedMeters.isFinite() && liveSmoothedMeters > 0f) liveSmoothedMeters else null)
                        ?: lastDisplayDistanceMeters.takeIf { it.isFinite() && it > 0f }
                        ?: 0f
                if (endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f) {
                    lastDisplayDistanceMeters = endLiveSnapshotMeters
                }
                // Plane consistency diagnostics: compare CUP plane normal vs saved BALL ground plane normal.
                ballCupPlaneAngleDeg = null
                val gp = groundPlaneModel
                val cupPlane = hit.trackable as? Plane
                if (gp != null && cupPlane != null) {
                    val center = cupPlane.centerPose
                    val axis = FloatArray(3)
                    center.getTransformedAxis(1, 1.0f, axis, 0)
                    val nLen = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]).takeIf { it > 1e-6f } ?: 1f
                    val cnx = axis[0] / nLen
                    val cny = axis[1] / nLen
                    val cnz = axis[2] / nLen
                    val dot = (gp.normal.x * cnx + gp.normal.y * cny + gp.normal.z * cnz).coerceIn(-1f, 1f)
                    val angleRad = kotlin.math.acos(dot)
                    val angleDeg = (angleRad * 57.29578f)
                    ballCupPlaneAngleDeg = angleDeg
                    if (angleDeg >= 10f) {
                        Log.w("V31StateMachine", "PLANE_DRIFT_WARN angleDeg=${"%.1f".format(angleDeg)} ballNy=${"%.3f".format(gp.normal.y)} cupNy=${"%.3f".format(cny)}")
                    }
                }
                // Start post-fix hold stability measurement for ~1s.
                cupHoldStartNs = nowNs
                cupHoldBuf.clear()
                cupHoldMaxDevMeters = 0f
                cupHoldSigmaMeters = null
                cupHoldDurationMs = null
            }
            else -> anchor.detach()
        }
    }

    private fun enterFail(r: FailReason) {
        state = State.FAIL
        failReason = r
    }

    private fun resetAll() {
        startAnchor?.detach(); startAnchor = null
        endAnchor?.detach(); endAnchor = null
        buf.clear()
        lastAimSample = null
        startRequestPending = false
        finishRequestPending = false
        startLockedAtNs = 0L
        endLockedAtNs = 0L
        consecutiveNoValidHits = 0
        sigmaOkConsecutive = 0
        sigmaOkStartNs = 0L
        lastSigmaUsedMeters = null
        lastSigmaMaxMeters = null
        cupHoldStartNs = 0L
        cupHoldBuf.clear()
        cupHoldMaxDevMeters = 0f
        cupHoldSigmaMeters = null
        cupHoldDurationMs = null
        stabilizingEnterNs = 0L
        pausedEnterNs = 0L
        fixedDEstMeters = 0f
        fixedGrid = 9
        fixedMinSamples = 10
        finalDistanceMeters = 0f
        endLiveSnapshotMeters = 0f
        lastDisplayDistanceMeters = 0f
        liveSmoothedMeters = 0f
        liveHasValue = false
        liveSource = LiveSource.NONE
        groundPlaneModel = null
        ballGroundPlaneNormalY = null
        ballGroundPlaneNormalLen = null
        ballGroundPlaneAbsNormalY = null
        ballGroundPlaneType = null
        ballGroundPlaneTrackingState = null
        ballGroundPlaneHitDistanceFromCameraMeters = null
        ballGroundPlaneExtentX = null
        ballGroundPlaneExtentZ = null
        ballCupPlaneAngleDeg = null
        lastMultiRayGridHalfSpanPx = null
        lastMultiRayStepPx = null
        lastValidSampleCount = null
        lastHitDistanceAvgMeters = null
        lastHitDistanceMaxMeters = null
        lastCameraY = null
        lastMedianY = null
        lastCenterYOffsetApplied = null
        lastMultiRayPlan = null
        lastMultiRayEstimatedDistanceMeters = null
        lastMultiRayProjectedCupPx = null
        lastMultiRayCenterFallbackUsed = null
        ballDiagGridMode = null
        ballDiagGridStepPx = null
        ballDiagSampleTotalPoints = null
        ballDiagSampleValidHits = null
        ballDiagHitSourceUsed = null
        ballDiagFreezeUsed = null
        ballDiagFreezeAgeMs = null
        ballDiagJumpRejected = null
        ballDiagFixState = null
        lastFailDetailCode = null
        lastFixedMinSamplesAtFail = null
        lastBufSizeAtFail = null
        lastSigmaOkConsecutiveAtFail = null
        lastSigmaOkElapsedMsAtFail = null
        ballHitWindow.clear()
        ballFixHitsInWindow = 0
        ballLastGoodHit = null
        ballLastGoodPose = null
        ballLastGoodNs = 0L
        failReason = null
        state = State.IDLE
        resetEndDisplayBuf()
    }

    private fun pushBallWindowHit(ok: Boolean) {
        ballHitWindow.addLast(ok)
        while (ballHitWindow.size > BALL_FIX_WINDOW_FRAMES) ballHitWindow.removeFirst()
        ballFixHitsInWindow = ballHitWindow.count { it }
    }

    private fun poseDistance(a: PoseStatsMad.Vec3, b: PoseStatsMad.Vec3): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val dz = b.z - a.z
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun chooseGridForDEst(d: Float, allow49: Boolean): Int {
        return when {
            d < 1.0f -> 9
            d < 3.0f -> 25
            else -> if (allow49) 49 else 25
        }
    }

    private fun chooseMinSamplesForDEst(d: Float): Int {
        val n = (10f + 3f * d).toInt()
        return n.coerceIn(10, 30)
    }

    private fun minValidHitsForGrid(grid: Int): Int {
        return when (grid) {
            9 -> 3
            25 -> 7
            49 -> 12
            else -> 3
        }
    }

    private fun minValidHitsForCupEnd(totalPoints: Int): Int {
        // Cup end sampling is distance-adaptive (may use 49/25/9/5 points).
        // Keep the "availability" goal for far distances while preserving near precision.
        return when (totalPoints) {
            49 -> 12
            25 -> CUP_MIN_VALID_HITS // 8
            // FAR: allow representative hit with fewer samples.
            9 -> 2
            // ULTRA: allow center fallback (1) to unlock the flow.
            5 -> 1
            4 -> 1
            1 -> 1
            else -> max(1, (0.32f * totalPoints.toFloat()).toInt())
        }
    }

    private fun sigmaMax(dMeters: Float): Float {
        val raw = a + (b * dMeters)
        return raw.coerceIn(sigmaMin, sigmaCap)
    }

    private fun distanceMeters(a: com.google.ar.core.Pose, b: com.google.ar.core.Pose): Float {
        val dx = b.tx() - a.tx()
        val dy = b.ty() - a.ty()
        val dz = b.tz() - a.tz()
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    private fun distanceBetweenAnchorsMeters(): Float {
        val a = startAnchor?.pose ?: return 0f
        val b = endAnchor?.pose ?: return 0f
        val dx = b.tx() - a.tx()
        val dy = b.ty() - a.ty()
        val dz = b.tz() - a.tz()
        return when (axisMode) {
            AxisMode.XYZ -> sqrt(dx * dx + dy * dy + dz * dz)
            AxisMode.XZ -> sqrt(dx * dx + dz * dz)
        }
    }

    private fun buildUi(
        nowNs: Long,
        tracking: TrackingState,
        sample: V31HitSampler.Sample? = null,
        flashLock: Boolean = false,
        flashFail: Boolean = false
    ): UiModel {
        val stabilizing = (state == State.STABILIZING_START || state == State.STABILIZING_END)
        val locked = (state == State.START_LOCKED || state == State.AIM_END || state == State.END_LOCKED || state == State.RESULT)

        val distanceMeters = when (state) {
            State.AIM_END -> {
                if (liveHasValue && liveSmoothedMeters.isFinite() && liveSmoothedMeters > 0f) {
                    liveSmoothedMeters.also { lastDisplayDistanceMeters = it }
                } else {
                    // Prevent flicker: keep last valid display distance.
                    lastDisplayDistanceMeters
                }
            }
            State.STABILIZING_END -> {
                if (liveHasValue && liveSmoothedMeters.isFinite() && liveSmoothedMeters > 0f) {
                    liveSmoothedMeters.also { lastDisplayDistanceMeters = it }
                } else {
                    // Prevent flicker: keep last valid display distance (already smoothed).
                    lastDisplayDistanceMeters
                }
            }
            State.END_LOCKED -> {
                endLiveSnapshotMeters
                    .takeIf { it.isFinite() && it > 0f }
                    ?: lastDisplayDistanceMeters
            }
            State.RESULT -> finalDistanceMeters
            else -> 0f
        }

        val hv: Pair<Float, Float>? =
            if (axisMode == AxisMode.XYZ) {
                when (state) {
                    State.AIM_END, State.STABILIZING_END -> {
                        val a = startAnchor?.pose
                        val b = sample?.bestHit?.hitPose
                        val canUse =
                            a != null &&
                                b != null &&
                                (sample?.validHits ?: 0) >= minValidHitsForCupEnd(sample?.totalPoints ?: CUP_GRID_SIZE_POINTS)
                        if (canUse) {
                            val dx = b!!.tx() - a!!.tx()
                            val dy = b.ty() - a.ty()
                            val dz = b.tz() - a.tz()
                            Pair(sqrt(dx * dx + dz * dz), dy)
                        } else {
                            null
                        }
                    }
                    State.END_LOCKED, State.RESULT -> {
                        val a = startAnchor?.pose
                        val b = endAnchor?.pose
                        if (a != null && b != null) {
                            val dx = b.tx() - a.tx()
                            val dy = b.ty() - a.ty()
                            val dz = b.tz() - a.tz()
                            Pair(sqrt(dx * dx + dz * dz), dy)
                        } else {
                            null
                        }
                    }
                    else -> null
                }
            } else {
                null
            }

        // OK/NG quality mapping (UI-only)
        val quality: ViewFinderView.QualityState =
            when {
                state == State.IDLE -> ViewFinderView.QualityState.NONE
                tracking != TrackingState.TRACKING -> ViewFinderView.QualityState.NG
                state == State.FAIL -> ViewFinderView.QualityState.NG
                // UI-only quality: surface/trackable readiness (independent of sigma lock).
                state == State.AIM_START -> {
                    val ok = sample?.bestHit != null && (sample.validHits >= minValidHitsForGrid(9))
                    if (ok) ViewFinderView.QualityState.OK else ViewFinderView.QualityState.NG
                }
                state == State.STABILIZING_START -> {
                    val grid = fixedGrid
                    val okHits = sample?.bestHit != null && (sample.validHits >= minValidHitsForGrid(grid))
                    // NOTE: keep sigma lock logic unchanged; only relax UI quality so "OK" can appear
                    // while accumulating samples.
                    if (okHits) ViewFinderView.QualityState.OK else ViewFinderView.QualityState.NG
                }
                state == State.AIM_END || state == State.STABILIZING_END -> {
                    val ok =
                        sample?.bestHit != null &&
                            (sample.validHits >= minValidHitsForCupEnd(sample.totalPoints)) &&
                            startAnchor != null
                    if (ok) ViewFinderView.QualityState.OK else ViewFinderView.QualityState.NG
                }
                else -> ViewFinderView.QualityState.NONE
            }

        val distanceColor =
            when {
                stabilizing -> Color.parseColor("#9E9E9E")
                locked -> Color.parseColor("#4CAF50")
                else -> Color.parseColor("#4CAF50")
            }

        val vfState =
            if (stabilizing) ViewFinderView.State.STABILIZING else ViewFinderView.State.DEFAULT

        val trackingOk = tracking == TrackingState.TRACKING

        val startEnabled =
            when (state) {
                State.IDLE -> trackingOk
                State.AIM_START -> trackingOk && ballFixHitsInWindow >= currentBallFixNeedHits()
                else -> false
            }

        val finishEnabled =
            when (state) {
                State.AIM_END ->
                    trackingOk &&
                        (sample?.validHits ?: 0) >= minValidHitsForCupEnd(sample?.totalPoints ?: CUP_GRID_SIZE_POINTS) &&
                        startAnchor != null
                State.END_LOCKED -> true
                else -> false
            }

        val wantsMoveDevice = tracking != TrackingState.TRACKING
        val isFinal = state == State.RESULT
        val isMeasuringFlow = state != State.IDLE && state != State.RESULT && state != State.FAIL

        val vh = sample?.validHits ?: 0
        val tp = sample?.totalPoints ?: 0
        val sigCm = lastSigmaUsedMeters?.let { it * 100f }
        val sigMaxCm = lastSigmaMaxMeters?.let { it * 100f }
        val failCode = if (state == State.FAIL) failReason?.name else null
        val failDetail = if (state == State.FAIL) lastFailDetailCode else null
        val bestHitDist = sample?.bestHit?.distance
        val endSigmaCurrentCm = if (lastSigmaPhase == SigmaPhase.END) sigCm else null
        val endSigmaThresholdCm = if (lastSigmaPhase == SigmaPhase.END) sigMaxCm else null
        val diagFixedMinSamples = if (stabilizing) fixedMinSamples else if (state == State.FAIL) lastFixedMinSamplesAtFail else null
        val diagBufSize = if (stabilizing) buf.size else if (state == State.FAIL) lastBufSizeAtFail else null
        val diagSigmaOkConsecutive = if (stabilizing) sigmaOkConsecutive else if (state == State.FAIL) lastSigmaOkConsecutiveAtFail else null
        val diagSigmaOkElapsedMs =
            if (stabilizing && sigmaOkStartNs > 0L) ((nowNs - sigmaOkStartNs) / 1_000_000L) else if (state == State.FAIL) lastSigmaOkElapsedMsAtFail else null

        // Cup hold stability update (runs even if state has already moved to RESULT)
        if (cupHoldStartNs > 0L && endAnchor != null) {
            val elapsedNs = nowNs - cupHoldStartNs
            val inWindow = elapsedNs in 0L..1_000_000_000L
            if (inWindow) {
                val hitPose = sample?.bestHit?.hitPose
                val okSample = hitPose != null && vh >= minValidHitsForGrid(9)
                if (okSample) {
                    cupHoldBuf.add(PoseStatsMad.Vec3(hitPose!!.tx(), hitPose.ty(), hitPose.tz()))
                    val a = endAnchor!!.pose
                    val dev = distanceMeters(a, hitPose)
                    if (dev.isFinite()) cupHoldMaxDevMeters = kotlin.math.max(cupHoldMaxDevMeters, dev)
                }
            } else if (cupHoldSigmaMeters == null) {
                cupHoldDurationMs = (elapsedNs / 1_000_000L).coerceAtLeast(0L)
                if (cupHoldBuf.size >= 4) {
                    val sig = poseStats.computeSigma(cupHoldBuf)
                    val sigmaUsed =
                        when (axisMode) {
                            AxisMode.XYZ -> sig.sigmaXYZ
                            AxisMode.XZ -> sig.sigmaXZ
                        }
                    cupHoldSigmaMeters = if (sigmaUsed.isFinite()) sigmaUsed else null
                } else {
                    cupHoldSigmaMeters = null
                }
            }
        }

        val holdSigmaCm = cupHoldSigmaMeters?.let { it * 100f }
        val holdMaxCm = if (cupHoldStartNs > 0L) (cupHoldMaxDevMeters * 100f) else null

        return UiModel(
            engineState = state,
            distanceMeters = distanceMeters,
            distanceTextColor = distanceColor,
            viewFinderState = vfState,
            viewFinderQuality = quality,
            flashLock = flashLock,
            flashFail = flashFail || (state == State.FAIL && failReason != null),
            sampleValidHits = vh,
            sampleTotalPoints = tp,
            sigmaUsedCm = sigCm,
            sigmaMaxCm = sigMaxCm,
            fixDEstMeters = fixedDEstMeters,
            bestHitDistanceFromCameraMeters = bestHitDist,
            cupHoldSigmaCm = holdSigmaCm,
            cupHoldMaxCm = holdMaxCm,
            cupHoldDurationMs = cupHoldDurationMs,
            failReasonCode = failCode,
            failDetailCode = failDetail,
            fixedMinSamples = diagFixedMinSamples,
            bufSize = diagBufSize,
            sigmaOkConsecutive = diagSigmaOkConsecutive,
            sigmaOkElapsedMs = diagSigmaOkElapsedMs,
            sigmaCurrentCmEnd = endSigmaCurrentCm,
            sigmaThresholdCmEnd = endSigmaThresholdCm,
            liveSource = liveSource.name,
            ballGroundPlaneNormalY = ballGroundPlaneNormalY,
            ballGroundPlaneNormalLen = ballGroundPlaneNormalLen,
            ballGroundPlaneAbsNormalY = ballGroundPlaneAbsNormalY,
            ballGroundPlaneType = ballGroundPlaneType,
            ballGroundPlaneTrackingState = ballGroundPlaneTrackingState,
            ballGroundPlaneHitDistanceFromCameraMeters = ballGroundPlaneHitDistanceFromCameraMeters,
            ballGroundPlaneExtentX = ballGroundPlaneExtentX,
            ballGroundPlaneExtentZ = ballGroundPlaneExtentZ,
            ballCupPlaneAngleDeg = ballCupPlaneAngleDeg,
            liveRawMeters = liveRawMeters,
            centerHitValid = centerHitValid,
            multiRayGridHalfSpanPx = lastMultiRayGridHalfSpanPx,
            multiRayStepPx = lastMultiRayStepPx,
            validSampleCount = lastValidSampleCount,
            hitDistanceAvgMeters = lastHitDistanceAvgMeters,
            hitDistanceMaxMeters = lastHitDistanceMaxMeters,
            cameraY = lastCameraY,
            medianY = lastMedianY,
            centerYOffsetApplied = lastCenterYOffsetApplied,
            multiRayPlan = lastMultiRayPlan,
            multiRayEstimatedDistanceMeters = lastMultiRayEstimatedDistanceMeters,
            multiRayProjectedCupPx = lastMultiRayProjectedCupPx,
            multiRayCenterFallbackUsed = lastMultiRayCenterFallbackUsed,
            ballGridMode = ballDiagGridMode,
            ballGridStepPx = ballDiagGridStepPx,
            ballSampleTotalPoints = ballDiagSampleTotalPoints,
            ballSampleValidHits = ballDiagSampleValidHits,
            ballHitSourceUsed = ballDiagHitSourceUsed,
            ballFreezeUsed = ballDiagFreezeUsed,
            ballFreezeAgeMs = ballDiagFreezeAgeMs,
            ballJumpRejected = ballDiagJumpRejected,
            ballFixRuleWindow = BALL_FIX_WINDOW_FRAMES,
            ballFixRuleNeedHits = currentBallFixNeedHits(),
            ballFixHitsInWindow = ballFixHitsInWindow,
            ballFixState = ballDiagFixState,
            horizontalVerticalMeters = hv,
            startEnabled = startEnabled,
            finishEnabled = finishEnabled,
            statusWantsMoveDeviceText = wantsMoveDevice,
            isResultFinal = isFinal,
            isMeasuringFlow = isMeasuringFlow
        )
    }

    private fun distanceFromStartToPoseMeters(start: com.google.ar.core.Pose, p: com.google.ar.core.Pose): Float {
        val dx = p.tx() - start.tx()
        val dy = p.ty() - start.ty()
        val dz = p.tz() - start.tz()
        return when (axisMode) {
            AxisMode.XYZ -> sqrt(dx * dx + dy * dy + dz * dz)
            AxisMode.XZ -> sqrt(dx * dx + dz * dz)
        }
    }
}

