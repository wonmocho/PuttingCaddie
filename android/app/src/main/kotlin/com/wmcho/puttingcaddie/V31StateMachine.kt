package com.wmcho.puttingcaddie

import android.graphics.Color
import android.graphics.PointF
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
        val cupSigmaNearHoldCount: Int?, // END-only: near-threshold extra-hold trigger count
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
    private val BALL_FIX_MIN_HOLD_NS = 220_000_000L
    private val BALL_FREEZE_TIMEOUT_NS = 2_000_000_000L
    private val BALL_JUMP_GATE_INITIAL_M = 0.35f
    private val BALL_JUMP_GATE_M = 0.50f
    private val BALL_FIX_MAX_CAMDIST_RANGE_M = 0.20f
    private val START_MIN_DISTANCE_M = 0.80f
    private val START_MIN_DISTANCE_HYSTERESIS_M = 0.75f
    private val START_MIN_DISTANCE_STABLE_FRAMES = 4

    // GREENIQ LIVE relaxed hit policy (v1 fix)
    private val LIVE_MAX_HIT_DISTANCE_M = 25f
    private val LIVE_JUMP_GUARD_M = 3.0f
    // Relaxed from 0.05 so ray-plane works when camera is behind ball (more horizontal view)
    private val LIVE_RAYDIR_Y_EPS = 0.02f
    // Frame clamp + smoothing (tuned): slightly faster than 0.4 / 85:15 while keeping stability
    private val LIVE_MAX_FRAME_DELTA_M = 0.55f
    // First measurement guards: reduce first-run cup lock jitter with minimal scope.
    private val FIRST_MEAS_WARMUP_NS = 800_000_000L
    private val FIRST_MEAS_SIGMA_GUARD_RATIO = 0.9f
    private val FIRST_MEAS_SIGMA_EXTRA_HOLD_NS = 250_000_000L
    // Cup lock quality guards (always-on): block low-quality lock and large live snapshot jump.
    private val CUP_LOCK_MIN_VALID_SAMPLES = 3
    private val CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES = 5
    private val LIVE_SNAPSHOT_GUARD_BASE_DIFF_M = 0.60f
    private val LIVE_SNAPSHOT_GUARD_RELATIVE_RATIO = 0.10f
    private val LIVE_SNAPSHOT_GUARD_HOLD_NS = 250_000_000L
    private val LIVE_SNAPSHOT_GUARD_MAX_RETRIES = 2
    private val FAR_MODE_CUP_DISTANCE_M = 6.0f
    private val FAR_MODE_MIN_PROJECTED_CUP_PX = 35f
    private val FAR_MODE_PLAN_ULTRA_LINE_5 = "ULTRA_LINE_5"
    private val FAR_MODE_MAX_LIVE_CUP_DIFF_M = 0.40f
    private val FAR_MODE_EXTRA_HOLD_NS = 300_000_000L
    private val CUP_AIM_READY_MIN_PROJECTED_PX = 18f
    private val CUP_CAPTURE_PENDING_HOLD_NS = 400_000_000L
    private val CUP_CAPTURE_PENDING_HOLD_ZOOMED_NS = 550_000_000L
    private val CUP_CAPTURE_PENDING_HOLD_ZOOMED_SMALL_PX_NS = 700_000_000L
    private val CUP_CAPTURE_PENDING_HOLD_FAR_NS = 650_000_000L
    private val CUP_CAPTURE_PENDING_SMALL_PROJECTED_PX = 22f
    private val CUP_CAPTURE_PENDING_EARLY_VALID_HITS = 3
    private val CUP_LOW_VALID_EARLY_FAIL_NS = 500_000_000L
    private val CUP_CAPTURE_PENDING_MAX_NS = 3_000_000_000L
    private val CUP_PROJECTED_PX_FORCE_FAR5 = 22f
    private val CUP_PROJECTED_PX_CONDITIONAL_FAR5 = 24f
    private val FAR_PRECISION_MODE_DISTANCE_M = 8.0f
    private val FAR_PRECISION_MODE_ENTER_PROJECTED_PX = 24f
    private val FAR_PRECISION_MODE_EXIT_PROJECTED_PX = 26f
    private val CUP_SIGMA_NEAR_RATIO = 1.10f
    private val CUP_SIGMA_NEAR_EXTRA_HOLD_NS = 250_000_000L
    private val CUP_SIGMA_SOFTPASS_RATIO = 1.12f
    private val CUP_SIGMA_SOFTPASS_MIN_VALID_HITS = 9
    private val CUP_SIGMA_SOFTPASS_MIN_PROJECTED_PX = 24f

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
    private var lastCupSigmaNearHoldCountAtFail: Int? = null

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
    private var startDistanceStableFrames: Int = 0
    private var startDistanceReady: Boolean = false
    private var startDistanceLastLogNs: Long = 0L
    private var farModeHoldStartNs: Long = 0L
    private var cupCapturePendingStartNs: Long = 0L
    private var cupSigmaNearHoldStartNs: Long = 0L
    private var cupSigmaNearHoldCount: Int = 0
    private var cupSigmaExtraHoldUsed: Boolean = false
    private var cupSigmaSoftPassLastLogNs: Long = 0L
    private var farPrecisionMode: Boolean = false
    private val cupCenterHistory = ArrayDeque<PointF>(5)
    private var cupFrozenCenter: PointF? = null
    private var useFarModeLiveMedianAtEndLock: Boolean = false
    private var farModeLiveMedianAtEndLock: Float? = null

    private var finalDistanceMeters: Float = 0f
    // Distance SSOT: LIVE snapshot at CUP lock (END_LOCKED).
    // Must never use WP/anchor-based distance for final distance.
    private var endLiveSnapshotMeters: Float = 0f

    // GREENIQ LIVE (laser) smoothing state (display-only)
    private var liveSmoothedMeters: Float = 0f
    private var liveHasValue: Boolean = false
    private val liveMedianWindow = FloatArray(5)
    private var liveMedianWindowSize = 0
    private var liveMedianWindowIndex = 0
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
    private var isFirstMeasurementPending: Boolean = true
    private var isFirstMeasurementActive: Boolean = false
    private var firstMeasurementStartNs: Long = 0L
    private var firstSigmaGuardStartNs: Long = 0L
    private var liveSnapshotGuardHoldStartNs: Long = 0L
    private var liveSnapshotGuardRetryCount: Int = 0
    private val ballRecentCamDistMeters = ArrayDeque<Float?>(3)

    private fun resetLiveMedianWindow() {
        liveMedianWindowSize = 0
        liveMedianWindowIndex = 0
    }

    private fun pushLiveMedianWindow(d: Float) {
        if (!d.isFinite() || d <= 0f) return
        liveMedianWindow[liveMedianWindowIndex] = d
        liveMedianWindowIndex = (liveMedianWindowIndex + 1) % liveMedianWindow.size
        if (liveMedianWindowSize < liveMedianWindow.size) liveMedianWindowSize++
    }

    private fun liveMedian5OrNaN(): Float {
        if (liveMedianWindowSize <= 0) return Float.NaN
        val tmp = FloatArray(liveMedianWindowSize)
        var idx = liveMedianWindowIndex - liveMedianWindowSize
        if (idx < 0) idx += liveMedianWindow.size
        for (i in 0 until liveMedianWindowSize) {
            tmp[i] = liveMedianWindow[idx]
            idx++
            if (idx == liveMedianWindow.size) idx = 0
        }
        tmp.sort()
        val n = tmp.size
        return if (n % 2 == 1) tmp[n / 2] else (tmp[(n / 2) - 1] + tmp[n / 2]) * 0.5f
    }

    private fun resetBallRecentCamDist() {
        ballRecentCamDistMeters.clear()
    }

    private fun pushBallRecentCamDist(d: Float?) {
        ballRecentCamDistMeters.addLast(d)
        while (ballRecentCamDistMeters.size > 3) ballRecentCamDistMeters.removeFirst()
    }

    private fun ballRecentCamDistRangeOrNull(): Float? {
        if (ballRecentCamDistMeters.size < 3) return null
        val vals = ballRecentCamDistMeters.toList()
        if (vals.any { it == null || !it.isFinite() }) return null
        val nn = vals.map { it!! }
        return (nn.maxOrNull() ?: return null) - (nn.minOrNull() ?: return null)
    }

    private fun isFarMode(cupDistanceFromCameraMeters: Float, projectedCupPx: Float?, plan: String?): Boolean {
        val byDistance = cupDistanceFromCameraMeters.isFinite() && cupDistanceFromCameraMeters >= FAR_MODE_CUP_DISTANCE_M
        val byProjectedPx = projectedCupPx != null && projectedCupPx.isFinite() && projectedCupPx < FAR_MODE_MIN_PROJECTED_CUP_PX
        val byPlan = (plan == FAR_MODE_PLAN_ULTRA_LINE_5)
        return byDistance || byProjectedPx || byPlan
    }

    private fun updateFarPrecisionMode(cupDistanceFromCameraMeters: Float, projectedCupPx: Float?) {
        if (farPrecisionMode) {
            val keepByDistance = cupDistanceFromCameraMeters.isFinite() && cupDistanceFromCameraMeters >= FAR_PRECISION_MODE_DISTANCE_M
            val keepByProjectedPx =
                projectedCupPx != null &&
                    projectedCupPx.isFinite() &&
                    projectedCupPx < FAR_PRECISION_MODE_EXIT_PROJECTED_PX
            if (!(keepByDistance || keepByProjectedPx)) {
                farPrecisionMode = false
                Log.d(
                    "V31StateMachine",
                    "FAR_PRECISION_MODE exit=true dist=${"%.3f".format(cupDistanceFromCameraMeters)} projectedPx=${if (projectedCupPx != null) "%.1f".format(projectedCupPx) else "NA"}"
                )
            }
            return
        }

        val enterByDistance = cupDistanceFromCameraMeters.isFinite() && cupDistanceFromCameraMeters >= FAR_PRECISION_MODE_DISTANCE_M
        val enterByProjectedPx =
            projectedCupPx != null &&
                projectedCupPx.isFinite() &&
                projectedCupPx < FAR_PRECISION_MODE_ENTER_PROJECTED_PX
        if (enterByDistance || enterByProjectedPx) {
            farPrecisionMode = true
            val reason =
                when {
                    enterByDistance && enterByProjectedPx -> "dist+projectedPx"
                    enterByDistance -> "dist"
                    else -> "projectedPx"
                }
            Log.d(
                "V31StateMachine",
                "FAR_PRECISION_MODE enter=true reason=$reason dist=${"%.3f".format(cupDistanceFromCameraMeters)} projectedPx=${if (projectedCupPx != null) "%.1f".format(projectedCupPx) else "NA"}"
            )
        }
    }

    private fun canRetryCupFromFail(): Boolean {
        if (startAnchor == null) return false
        return when (lastFailDetailCode) {
            "CUP_LOW_VALID_500MS",
            "NO_VALID_HITS",
            "CUP_PENDING_TIMEOUT_3S",
            "TIMEOUT_SIGMA_NOT_OK",
            "TIMEOUT_NOT_ENOUGH_SAMPLES",
            "TIMEOUT_NO_CONSECUTIVE_OK",
            "TIMEOUT_TIME_GATE" -> true
            else -> false
        }
    }

    private fun pushCupCenterHistory(center: PointF) {
        cupCenterHistory.addLast(center)
        while (cupCenterHistory.size > 5) cupCenterHistory.removeFirst()
    }

    private fun resetCupCenterHistory() {
        cupCenterHistory.clear()
    }

    private fun median(values: FloatArray): Float {
        if (values.isEmpty()) return Float.NaN
        values.sort()
        val n = values.size
        return if (n % 2 == 1) values[n / 2] else (values[(n / 2) - 1] + values[n / 2]) * 0.5f
    }

    private fun medianCupCenterOrNull(): PointF? {
        if (cupCenterHistory.isEmpty()) return null
        val xs = FloatArray(cupCenterHistory.size)
        val ys = FloatArray(cupCenterHistory.size)
        var i = 0
        for (p in cupCenterHistory) {
            xs[i] = p.x
            ys[i] = p.y
            i++
        }
        val mx = median(xs)
        val my = median(ys)
        if (!mx.isFinite() || !my.isFinite()) return null
        return PointF(mx, my)
    }

    private fun roiCenteredAt(original: RectF, center: PointF): RectF {
        val halfW = original.width() * 0.5f
        val halfH = original.height() * 0.5f
        return RectF(center.x - halfW, center.y - halfH, center.x + halfW, center.y + halfH)
    }

    private fun updateStartDistanceGuard(nowNs: Long, distanceFromCameraMeters: Float?) {
        val d = distanceFromCameraMeters
        if (d == null || !d.isFinite() || d <= 0f) {
            startDistanceStableFrames = 0
            startDistanceReady = false
            return
        }
        when {
            d >= START_MIN_DISTANCE_M -> {
                startDistanceStableFrames++
                if (startDistanceStableFrames >= START_MIN_DISTANCE_STABLE_FRAMES) {
                    startDistanceReady = true
                }
            }
            d < START_MIN_DISTANCE_HYSTERESIS_M -> {
                startDistanceStableFrames = 0
                startDistanceReady = false
            }
            else -> {
                // Hysteresis band: keep current state to reduce near-threshold flicker.
            }
        }
        if (startRequestPending && !startDistanceReady && (nowNs - startDistanceLastLogNs >= 300_000_000L)) {
            startDistanceLastLogNs = nowNs
            Log.d(
                "V31StateMachine",
                "START_DISTANCE_GUARD startDistanceCurrent_m=${"%.3f".format(d)} " +
                    "startDistanceThreshold_m=${"%.3f".format(START_MIN_DISTANCE_M)} " +
                    "startDistanceRejected=true startDistanceStableFrames=$startDistanceStableFrames"
            )
        }
    }

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
                        if (isFirstMeasurementPending) {
                            isFirstMeasurementActive = true
                            firstMeasurementStartNs = nowNs
                            firstSigmaGuardStartNs = 0L
                        } else {
                            isFirstMeasurementActive = false
                        }
                        ballHitWindow.clear()
                        ballFixHitsInWindow = 0
                        resetBallRecentCamDist()
                        startDistanceStableFrames = 0
                        startDistanceReady = false
                        startDistanceLastLogNs = 0L
                        ballLastGoodHit = null
                        ballLastGoodPose = null
                        ballLastGoodNs = 0L
                        startAnchor?.detach(); startAnchor = null
                        endAnchor?.detach(); endAnchor = null
                        lastAimSample = null
                        farModeHoldStartNs = 0L
                        useFarModeLiveMedianAtEndLock = false
                        farModeLiveMedianAtEndLock = null
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        resetCupCenterHistory()
                        cupSigmaNearHoldStartNs = 0L
                        cupSigmaNearHoldCount = 0
                        cupSigmaExtraHoldUsed = false
                        cupSigmaSoftPassLastLogNs = 0L
                        farPrecisionMode = false
                        resetLiveMedianWindow()
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
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        cupSigmaNearHoldStartNs = 0L
                        cupSigmaExtraHoldUsed = false
                        cupSigmaSoftPassLastLogNs = 0L
                    }
                    State.FAIL -> {
                        if (canRetryCupFromFail()) {
                            state = State.AIM_END
                            failReason = null
                            finishRequestPending = true
                            cupCapturePendingStartNs = 0L
                            cupFrozenCenter = null
                            resetCupCenterHistory()
                            cupSigmaNearHoldStartNs = 0L
                            cupSigmaExtraHoldUsed = false
                            cupSigmaSoftPassLastLogNs = 0L
                        }
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
            (
                state == State.AIM_START ||
                    state == State.STABILIZING_START ||
                    state == State.AIM_END ||
                    state == State.STABILIZING_END ||
                    (state == State.FAIL && canRetryCupFromFail())
                )
        if (!sampling) return buildUi(nowNs, tracking)
        if (state == State.AIM_END || state == State.FAIL) {
            pushCupCenterHistory(PointF(roiScreen.centerX(), roiScreen.centerY()))
        }

        val grid =
            when (state) {
                State.AIM_END, State.STABILIZING_END -> CUP_GRID_SIZE_POINTS
                State.AIM_START -> 9
                State.STABILIZING_START -> fixedGrid
                State.FAIL -> CUP_GRID_SIZE_POINTS
                else -> 9
            }

        val sample =
            if (state == State.AIM_END || state == State.STABILIZING_END || state == State.FAIL) {
                val cupSamplingRoi =
                    if (state == State.AIM_END && finishRequestPending && cupFrozenCenter != null) {
                        roiCenteredAt(roiScreen, cupFrozenCenter!!)
                    } else {
                        roiScreen
                    }
                // Cup FIX sampling: multi-ray 5x5 centered near screen center with Y offset and distance/Y guards.
                var s =
                    sampler.sampleCupPlaneMultiRay(
                        frame = frame,
                        baseRoiScreen = cupSamplingRoi,
                        offsetPercent = CUP_OFFSET_PERCENT_PRIMARY,
                        centerYOffsetRatio = CUP_CENTER_Y_OFFSET_RATIO,
                        gridSize = 5,
                        maxHitDistanceMeters = 12f,
                        yBelowCameraMeters = 0.1f,
                        preferUpwardFacing = true,
                        requireUpwardFacing = false
                    )
                updateFarPrecisionMode(s.bestHit?.distance ?: Float.NaN, s.gridProjectedCupPx)
                val projectedPx = s.gridProjectedCupPx
                val forceFar5x5 = projectedPx != null && projectedPx < CUP_PROJECTED_PX_FORCE_FAR5
                val conditionalFar5x5 =
                    projectedPx != null &&
                        projectedPx >= CUP_PROJECTED_PX_FORCE_FAR5 &&
                        projectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5 &&
                        s.validHits <= 2
                val needsFarExpand =
                    forceFar5x5 ||
                        (farPrecisionMode && projectedPx != null && projectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5) ||
                        conditionalFar5x5 ||
                        (projectedPx == null && s.validHits <= 2)
                if (needsFarExpand) {
                    val expanded =
                        sampler.sampleCupPlaneMultiRay(
                            frame = frame,
                            baseRoiScreen = cupSamplingRoi,
                            offsetPercent = CUP_OFFSET_PERCENT_PRIMARY,
                            centerYOffsetRatio = CUP_CENTER_Y_OFFSET_RATIO,
                            gridSize = 5,
                            maxHitDistanceMeters = 18f,
                            yBelowCameraMeters = 0.05f,
                            preferUpwardFacing = true,
                            requireUpwardFacing = false,
                            forceFar5x5 = true
                        )
                    val modeSwitchReason =
                        when {
                            forceFar5x5 -> "PROJECTED_PX_LT_22"
                            farPrecisionMode && projectedPx != null && projectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5 -> "FAR_PRECISION_LT_24"
                            conditionalFar5x5 -> "PROJECTED_PX_22_24_LOW_VALID"
                            else -> "LOW_VALID_OR_UNKNOWN_PX"
                        }
                    val decision =
                        when {
                            forceFar5x5 -> "FORCE_FAR_5x5"
                            farPrecisionMode && projectedPx != null && projectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5 -> "FAR_PRECISION_FORCE_FAR_5x5"
                            expanded.validHits >= s.validHits -> "EXPAND_TO_FAR_5x5"
                            else -> "KEEP_BASE"
                        }
                    val z = sampler.currentZoomLevel()
                    Log.d(
                        "V31StateMachine",
                        "CUP_EXPAND zoom=${"%.2f".format(z)} mode=${s.gridPlan ?: "UNKNOWN"} projectedPx=${if (projectedPx != null) "%.1f".format(projectedPx) else "NA"} " +
                            "gridHalfPx=${if (s.gridHalfSpanPx != null) "%.1f".format(s.gridHalfSpanPx) else "NA"} valid=${s.validHits}/${s.totalPoints} " +
                            "modeSwitchReason=$modeSwitchReason decision=$decision"
                    )
                    if (forceFar5x5 || expanded.validHits >= s.validHits) s = expanded
                }
                if (s.validHits < 5) {
                    val retryProjectedPx = s.gridProjectedCupPx
                    val retryForceFar5 = retryProjectedPx != null && retryProjectedPx < CUP_PROJECTED_PX_FORCE_FAR5
                    val retryConditionalFar5 =
                        retryProjectedPx != null &&
                            retryProjectedPx >= CUP_PROJECTED_PX_FORCE_FAR5 &&
                            retryProjectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5 &&
                            s.validHits <= 2
                    val retry =
                        sampler.sampleCupPlaneMultiRay(
                            frame = frame,
                            baseRoiScreen = cupSamplingRoi,
                            offsetPercent = CUP_OFFSET_PERCENT_RETRY,
                            centerYOffsetRatio = CUP_CENTER_Y_OFFSET_RATIO,
                            gridSize = 5,
                            // 2nd pass only: widen distance cap + slightly relax Y filter for hit availability.
                            maxHitDistanceMeters = 18f,
                            yBelowCameraMeters = 0.05f,
                            preferUpwardFacing = true,
                            requireUpwardFacing = false,
                            forceFar5x5 =
                                retryForceFar5 ||
                                    (farPrecisionMode && retryProjectedPx != null && retryProjectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5) ||
                                    retryConditionalFar5 ||
                                    (retryProjectedPx == null && s.validHits <= 2)
                        )
                    val modeSwitchReason =
                        when {
                            retryForceFar5 -> "PROJECTED_PX_LT_22"
                            farPrecisionMode && retryProjectedPx != null && retryProjectedPx < CUP_PROJECTED_PX_CONDITIONAL_FAR5 -> "FAR_PRECISION_LT_24"
                            retryConditionalFar5 -> "PROJECTED_PX_22_24_LOW_VALID"
                            else -> "LOW_VALID_OR_UNKNOWN_PX"
                        }
                    val decision = if (retry.validHits > s.validHits) "RETRY_UPGRADE" else "RETRY_KEEP"
                    val z = sampler.currentZoomLevel()
                    Log.d(
                        "V31StateMachine",
                        "CUP_EXPAND zoom=${"%.2f".format(z)} mode=${s.gridPlan ?: "UNKNOWN"} projectedPx=${if (s.gridProjectedCupPx != null) "%.1f".format(s.gridProjectedCupPx) else "NA"} " +
                            "gridHalfPx=${if (s.gridHalfSpanPx != null) "%.1f".format(s.gridHalfSpanPx) else "NA"} valid=${s.validHits}/${s.totalPoints} " +
                            "modeSwitchReason=$modeSwitchReason decision=$decision"
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
            val jumpGateForTick = BALL_JUMP_GATE_INITIAL_M

            if (candidate != null) {
                val p = candidate.hitPose
                val curPose = PoseStatsMad.Vec3(p.tx(), p.ty(), p.tz())
                val prevPose = ballLastGoodPose
                if (prevPose != null) {
                    val jump = poseDistance(prevPose, curPose)
                    if (jump > jumpGateForTick) {
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
            val freshCamDist = if (candidate != null && ballFreezeUsedTick != true) candidate.distance else null
            pushBallRecentCamDist(freshCamDist)
            if (state == State.AIM_START) {
                val startDistForGuard = ballEffectiveHitForTick?.distance ?: sample.bestHit?.distance
                updateStartDistanceGuard(nowNs, startDistForGuard)
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
                pushLiveMedianWindow(liveSmoothedMeters)
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
            resetLiveMedianWindow()
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
                val dist = hit?.distance
                val allowStartDistance = startDistanceReady
                if (!allowStartDistance && dist != null && dist.isFinite() && nowNs - startDistanceLastLogNs >= 300_000_000L) {
                    startDistanceLastLogNs = nowNs
                    Log.d(
                        "V31StateMachine",
                        "START_DISTANCE_GUARD startDistanceCurrent_m=${"%.3f".format(dist)} " +
                            "startDistanceThreshold_m=${"%.3f".format(START_MIN_DISTANCE_M)} " +
                            "startDistanceRejected=true startDistanceStableFrames=$startDistanceStableFrames"
                    )
                }
                if (hit != null && ballFixHitsInWindow >= currentBallFixNeedHits() && allowStartDistance) {
                    startRequestPending = false
                    enterStabilizingStart(nowNs, hit)
                    return buildUi(nowNs, tracking, sample)
                }
            }
            if (state == State.AIM_END && finishRequestPending) {
                val hit = sample.bestHit
                val aimReady = hit != null && startAnchor != null && isCupAimReady(sample)
                if (aimReady) {
                    if (cupCapturePendingStartNs == 0L) {
                        cupCapturePendingStartNs = nowNs
                        cupFrozenCenter = medianCupCenterOrNull() ?: PointF(roiScreen.centerX(), roiScreen.centerY())
                        val z = sampler.currentZoomLevel()
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=START holdMs=0 zoom=${"%.2f".format(z)} projectedPx=${if (sample.gridProjectedCupPx != null) "%.1f".format(sample.gridProjectedCupPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} valid=${sample.validHits}/${sample.totalPoints} " +
                                "decision=PENDING_START frozenCenter=(${if (cupFrozenCenter != null) "%.1f".format(cupFrozenCenter!!.x) else "NA"},${if (cupFrozenCenter != null) "%.1f".format(cupFrozenCenter!!.y) else "NA"})"
                        )
                    }
                    val z = sampler.currentZoomLevel()
                    val projectedPx = sample.gridProjectedCupPx
                    val holdTargetNs =
                        if (farPrecisionMode) {
                            CUP_CAPTURE_PENDING_HOLD_FAR_NS
                        } else if (z >= 2.9f) {
                            if (projectedPx != null && projectedPx.isFinite() && projectedPx < CUP_CAPTURE_PENDING_SMALL_PROJECTED_PX) {
                                CUP_CAPTURE_PENDING_HOLD_ZOOMED_SMALL_PX_NS
                            } else {
                                CUP_CAPTURE_PENDING_HOLD_ZOOMED_NS
                            }
                        } else {
                            CUP_CAPTURE_PENDING_HOLD_NS
                        }
                    val holdElapsedNs = nowNs - cupCapturePendingStartNs
                    if (sample.validHits <= 1 && holdElapsedNs >= CUP_LOW_VALID_EARLY_FAIL_NS) {
                        lastFailDetailCode = "CUP_LOW_VALID_500MS"
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=EARLY_FAIL holdMs=${holdElapsedNs / 1_000_000L} zoom=${"%.2f".format(z)} " +
                                "projectedPx=${if (projectedPx != null) "%.1f".format(projectedPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} " +
                                "valid=${sample.validHits}/${sample.totalPoints} decision=EARLY_FAIL_LOW_VALID"
                        )
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        enterFail(FailReason.FAIL_NO_VALID_HITS)
                        return buildUi(nowNs, tracking, sample, flashFail = true)
                    }
                    if (holdElapsedNs >= CUP_CAPTURE_PENDING_MAX_NS) {
                        lastFixedMinSamplesAtFail = null
                        lastBufSizeAtFail = null
                        lastSigmaOkConsecutiveAtFail = 0
                        lastSigmaOkElapsedMsAtFail = 0L
                        lastCupSigmaNearHoldCountAtFail = cupSigmaNearHoldCount
                        lastFailDetailCode = "CUP_PENDING_TIMEOUT_3S"
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=TIMEOUT holdMs=${holdElapsedNs / 1_000_000L} zoom=${"%.2f".format(z)} " +
                                "projectedPx=${if (projectedPx != null) "%.1f".format(projectedPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} " +
                                "valid=${sample.validHits}/${sample.totalPoints} decision=TIMEOUT_3S"
                        )
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        enterFail(FailReason.FAIL_TIMEOUT)
                        return buildUi(nowNs, tracking, sample, flashFail = true)
                    }
                    val earlyFixReady = isCupFixReady(sample) && sample.validHits >= CUP_CAPTURE_PENDING_EARLY_VALID_HITS
                    if (holdElapsedNs < holdTargetNs && !earlyFixReady) {
                        return buildUi(nowNs, tracking, sample)
                    }
                    if (isCupFixReady(sample)) {
                        finishRequestPending = false
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        enterStabilizingEnd(nowNs, hit!!)
                        return buildUi(nowNs, tracking, sample)
                    }
                    if (nowNs - startDistanceLastLogNs >= 300_000_000L) {
                        startDistanceLastLogNs = nowNs
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=WAIT_FIX holdMs=${holdElapsedNs / 1_000_000L} zoom=${"%.2f".format(z)} " +
                                "projectedPx=${if (sample.gridProjectedCupPx != null) "%.1f".format(sample.gridProjectedCupPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} " +
                                "valid=${sample.validHits}/${sample.totalPoints} fallback=${sample.centerFallbackUsed == true} decision=WAIT_FIX"
                        )
                    }
                } else {
                    cupCapturePendingStartNs = 0L
                    cupFrozenCenter = null
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
                lastCupSigmaNearHoldCountAtFail = cupSigmaNearHoldCount
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
                    lastCupSigmaNearHoldCountAtFail = cupSigmaNearHoldCount
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
                    val distRange = ballRecentCamDistRangeOrNull()
                    val allowBallFix = (distRange != null && distRange <= BALL_FIX_MAX_CAMDIST_RANGE_M)
                    Log.d(
                        "V31StateMachine",
                        "BALL_FIX_GUARD holdMs=${holdElapsed / 1_000_000L} jumpGate=${"%.2f".format(BALL_JUMP_GATE_INITIAL_M)} " +
                            "distRange=${if (distRange != null) "%.3f".format(distRange) else "NA"} allow=$allowBallFix"
                    )
                    if (!allowBallFix) {
                        return buildUi(nowNs, tracking, sample)
                    }
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
                val projectedPx = sample.gridProjectedCupPx
                val sigmaSoftPassCandidate =
                    state == State.STABILIZING_END &&
                        !sigmaOk &&
                        sample.validHits >= CUP_SIGMA_SOFTPASS_MIN_VALID_HITS &&
                        projectedPx != null &&
                        projectedPx.isFinite() &&
                        projectedPx >= CUP_SIGMA_SOFTPASS_MIN_PROJECTED_PX &&
                        sigmaUsed.isFinite() &&
                        sigmaMax.isFinite() &&
                        sigmaMax > 1e-6f &&
                        sigmaUsed <= (sigmaMax * CUP_SIGMA_SOFTPASS_RATIO)
                val nearSigmaForEnd =
                    state == State.STABILIZING_END &&
                        !sigmaOk &&
                        sample.validHits >= minValidHitsForCupEnd(sample.totalPoints) &&
                        sigmaUsed.isFinite() &&
                        sigmaMax.isFinite() &&
                        sigmaMax > 1e-6f &&
                        sigmaUsed <= (sigmaMax * CUP_SIGMA_NEAR_RATIO)
                if (nearSigmaForEnd) {
                    if (!cupSigmaExtraHoldUsed && cupSigmaNearHoldStartNs == 0L) {
                        cupSigmaNearHoldStartNs = nowNs
                        cupSigmaNearHoldCount++
                    }
                    val nearHoldElapsedNs = if (cupSigmaNearHoldStartNs > 0L) nowNs - cupSigmaNearHoldStartNs else 0L
                    if (!cupSigmaExtraHoldUsed && nearHoldElapsedNs < CUP_SIGMA_NEAR_EXTRA_HOLD_NS) {
                        Log.d(
                            "V31StateMachine",
                            "CUP_SIGMA_NEAR_HOLD holdMs=${nearHoldElapsedNs / 1_000_000L} sigma=${"%.3f".format(sigmaUsed)} " +
                                "thr=${"%.3f".format(sigmaMax)} ratio=${"%.3f".format(sigmaUsed / sigmaMax)}"
                        )
                        if (sigmaSoftPassCandidate && nowNs - cupSigmaSoftPassLastLogNs >= 300_000_000L) {
                            cupSigmaSoftPassLastLogNs = nowNs
                            Log.d(
                                "V31StateMachine",
                                "CUP_SIGMA_SOFTPASS active=true sigma=${"%.3f".format(sigmaUsed)} thr=${"%.3f".format(sigmaMax)} " +
                                    "ratio=${"%.3f".format(sigmaUsed / sigmaMax)} projectedPx=${"%.1f".format(projectedPx)} " +
                                    "valid=${sample.validHits}/${sample.totalPoints}"
                            )
                        }
                        return buildUi(nowNs, tracking, sample)
                    }
                    if (!cupSigmaExtraHoldUsed && cupSigmaNearHoldStartNs > 0L) {
                        cupSigmaExtraHoldUsed = true
                    }
                    cupSigmaNearHoldStartNs = 0L
                } else {
                    cupSigmaNearHoldStartNs = 0L
                }
                if (sigmaOk) {
                    if (sigmaOkConsecutive == 0) sigmaOkStartNs = nowNs
                    sigmaOkConsecutive++
                } else {
                    sigmaOkConsecutive = 0
                    sigmaOkStartNs = 0L
                }

                val okElapsed = if (sigmaOkStartNs > 0L) (nowNs - sigmaOkStartNs) else 0L
                if (sigmaOkConsecutive >= LOCK_CONSEC_TICKS && okElapsed >= LOCK_TIME_GATE_NS) {
                    if (state == State.STABILIZING_END && isFirstMeasurementActive) {
                        val warmupElapsedNs = nowNs - firstMeasurementStartNs
                        if (warmupElapsedNs < FIRST_MEAS_WARMUP_NS) {
                            Log.d(
                                "V31StateMachine",
                                "FIRST_WARMUP active=true elapsedMs=${warmupElapsedNs / 1_000_000L}"
                            )
                            return buildUi(nowNs, tracking, sample)
                        }

                        val sigmaRatio =
                            if (sigmaUsed.isFinite() && sigmaMax.isFinite() && sigmaMax > 1e-6f) {
                                sigmaUsed / sigmaMax
                            } else {
                                0f
                            }
                        if (sigmaRatio >= FIRST_MEAS_SIGMA_GUARD_RATIO) {
                            if (firstSigmaGuardStartNs == 0L) {
                                firstSigmaGuardStartNs = nowNs
                            }
                            val sigmaGuardElapsedNs = nowNs - firstSigmaGuardStartNs
                            Log.d(
                                "V31StateMachine",
                                "FIRST_SIGMA_GUARD active=true sigma=${"%.3f".format(sigmaUsed)} thr=${"%.3f".format(sigmaMax)} ratio=${"%.3f".format(sigmaRatio)}"
                            )
                            if (sigmaGuardElapsedNs < FIRST_MEAS_SIGMA_EXTRA_HOLD_NS) {
                                return buildUi(nowNs, tracking, sample)
                            }
                        } else {
                            firstSigmaGuardStartNs = 0L
                        }
                    }
                    if (state == State.STABILIZING_END) {
                        val cupValidSampleCount = sample.validHits
                        val centerFallbackUsed = (sample.centerFallbackUsed == true)
                        val qualityBlocked =
                            cupValidSampleCount < CUP_LOCK_MIN_VALID_SAMPLES ||
                                (centerFallbackUsed && cupValidSampleCount < CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES)
                        if (qualityBlocked) {
                            Log.d(
                                "V31StateMachine",
                                "CUP_QUALITY_GUARD block=true validSampleCount=$cupValidSampleCount validHits=${sample.validHits} " +
                                    "centerFallback=$centerFallbackUsed plan=${sample.gridPlan ?: "UNKNOWN"}"
                            )
                            return buildUi(nowNs, tracking, sample)
                        }

                        val liveRaw = liveRawMeters
                        val liveEma = liveSmoothedMeters
                        val liveReady =
                            liveSource == LiveSource.PLANE_INTERSECTION &&
                                centerHitValid == true &&
                                liveRaw != null &&
                                liveRaw.isFinite() &&
                                liveEma.isFinite() &&
                                liveEma > 0f
                        if (liveReady) {
                            val diff = abs(liveRaw!! - liveEma)
                            val diffThreshold = max(LIVE_SNAPSHOT_GUARD_BASE_DIFF_M, liveEma * LIVE_SNAPSHOT_GUARD_RELATIVE_RATIO)
                            if (diff > diffThreshold) {
                                if (liveSnapshotGuardRetryCount >= LIVE_SNAPSHOT_GUARD_MAX_RETRIES) {
                                    return buildUi(nowNs, tracking, sample)
                                }
                                if (liveSnapshotGuardHoldStartNs == 0L) {
                                    liveSnapshotGuardHoldStartNs = nowNs
                                }
                                Log.d(
                                    "V31StateMachine",
                                    "LIVE_SNAPSHOT_GUARD block=true liveRaw=${"%.3f".format(liveRaw)} liveEma=${"%.3f".format(liveEma)} diff=${"%.3f".format(diff)}"
                                )
                                val guardElapsedNs = nowNs - liveSnapshotGuardHoldStartNs
                                if (guardElapsedNs < LIVE_SNAPSHOT_GUARD_HOLD_NS) {
                                    return buildUi(nowNs, tracking, sample)
                                }
                                liveSnapshotGuardRetryCount++
                                liveSnapshotGuardHoldStartNs = 0L
                                return buildUi(nowNs, tracking, sample)
                            } else {
                                liveSnapshotGuardHoldStartNs = 0L
                                liveSnapshotGuardRetryCount = 0
                            }
                        } else {
                            liveSnapshotGuardHoldStartNs = 0L
                            liveSnapshotGuardRetryCount = 0
                        }

                        val cupFixDist =
                            startAnchor?.pose?.let {
                                distanceMeters(it, stabilizingHit.hitPose)
                            }
                        val liveMedian5 = liveMedian5OrNaN()
                        val farMode =
                            isFarMode(
                                cupDistanceFromCameraMeters = stabilizingHit.distance,
                                projectedCupPx = sample.gridProjectedCupPx,
                                plan = sample.gridPlan
                            )
                        useFarModeLiveMedianAtEndLock = false
                        farModeLiveMedianAtEndLock = null

                        if (farMode) {
                            val liveMedianValid = liveMedian5.isFinite() && liveMedian5 > 0f
                            val cupFixValid = cupFixDist != null && cupFixDist.isFinite() && cupFixDist > 0f
                            val diff =
                                if (liveMedianValid && cupFixValid) {
                                    abs(liveMedian5 - cupFixDist!!)
                                } else {
                                    Float.NaN
                                }

                            val decision: String
                            if (!liveMedianValid || !cupFixValid) {
                                if (farModeHoldStartNs == 0L) farModeHoldStartNs = nowNs
                                val holdElapsedNs = nowNs - farModeHoldStartNs
                                decision = if (holdElapsedNs < FAR_MODE_EXTRA_HOLD_NS) "HOLD" else "LIVE_KEEP"
                                Log.d(
                                    "V31StateMachine",
                                    "FAR_MODE_DECISION mode=FAR liveMedian5=${if (liveMedianValid) "%.3f".format(liveMedian5) else "NA"} " +
                                        "cupFixDist=${if (cupFixValid) "%.3f".format(cupFixDist) else "NA"} diff=NA decision=$decision"
                                )
                                if (decision == "HOLD") {
                                    return buildUi(nowNs, tracking, sample)
                                }
                            } else if (diff > FAR_MODE_MAX_LIVE_CUP_DIFF_M) {
                                if (farModeHoldStartNs == 0L) farModeHoldStartNs = nowNs
                                val holdElapsedNs = nowNs - farModeHoldStartNs
                                decision = if (holdElapsedNs < FAR_MODE_EXTRA_HOLD_NS) "HOLD" else "LIVE_KEEP"
                                Log.d(
                                    "V31StateMachine",
                                    "FAR_MODE_DECISION mode=FAR liveMedian5=${"%.3f".format(liveMedian5)} " +
                                        "cupFixDist=${"%.3f".format(cupFixDist)} diff=${"%.3f".format(diff)} decision=$decision"
                                )
                                if (decision == "HOLD") {
                                    return buildUi(nowNs, tracking, sample)
                                }
                            } else {
                                decision = "CUP_ACCEPT"
                                Log.d(
                                    "V31StateMachine",
                                    "FAR_MODE_DECISION mode=FAR liveMedian5=${"%.3f".format(liveMedian5)} " +
                                        "cupFixDist=${"%.3f".format(cupFixDist)} diff=${"%.3f".format(diff)} decision=$decision"
                                )
                            }
                            farModeHoldStartNs = 0L
                            if (liveMedianValid) {
                                useFarModeLiveMedianAtEndLock = true
                                farModeLiveMedianAtEndLock = liveMedian5
                            }
                        } else {
                            farModeHoldStartNs = 0L
                        }
                    }
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
        lastCupSigmaNearHoldCountAtFail = null
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
        cupCapturePendingStartNs = 0L
        cupSigmaNearHoldStartNs = 0L
        cupSigmaNearHoldCount = 0
        cupSigmaExtraHoldUsed = false
        cupSigmaSoftPassLastLogNs = 0L
        farPrecisionMode = false
        cupFrozenCenter = null
        resetCupCenterHistory()
        liveSnapshotGuardHoldStartNs = 0L
        liveSnapshotGuardRetryCount = 0
        farModeHoldStartNs = 0L
        useFarModeLiveMedianAtEndLock = false
        farModeLiveMedianAtEndLock = null
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
        lastCupSigmaNearHoldCountAtFail = null
        stabilizingEnterNs = nowNs
        pausedEnterNs = 0L
        consecutiveNoValidHits = 0
        sigmaOkConsecutive = 0
        sigmaOkStartNs = 0L
        cupSigmaExtraHoldUsed = false
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
                completeFirstMeasurementIfNeeded()
                // Capture LIVE snapshot to prevent END_LOCKED jump to anchor-based distance.
                endLiveSnapshotMeters =
                    (if (useFarModeLiveMedianAtEndLock && (farModeLiveMedianAtEndLock?.isFinite() == true) && (farModeLiveMedianAtEndLock ?: 0f) > 0f) {
                        farModeLiveMedianAtEndLock
                    } else if (liveHasValue && liveSmoothedMeters.isFinite() && liveSmoothedMeters > 0f) {
                        liveSmoothedMeters
                    } else {
                        null
                    })
                        ?: lastDisplayDistanceMeters.takeIf { it.isFinite() && it > 0f }
                        ?: 0f
                if (endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f) {
                    lastDisplayDistanceMeters = endLiveSnapshotMeters
                }
                useFarModeLiveMedianAtEndLock = false
                farModeLiveMedianAtEndLock = null
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
        completeFirstMeasurementIfNeeded()
    }

    private fun resetAll() {
        completeFirstMeasurementIfNeeded()
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
        cupCapturePendingStartNs = 0L
        cupSigmaNearHoldStartNs = 0L
        cupSigmaNearHoldCount = 0
        cupSigmaExtraHoldUsed = false
        cupSigmaSoftPassLastLogNs = 0L
        farPrecisionMode = false
        cupFrozenCenter = null
        resetCupCenterHistory()
        liveSnapshotGuardHoldStartNs = 0L
        liveSnapshotGuardRetryCount = 0
        farModeHoldStartNs = 0L
        useFarModeLiveMedianAtEndLock = false
        farModeLiveMedianAtEndLock = null
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
        lastCupSigmaNearHoldCountAtFail = null
        ballHitWindow.clear()
        resetBallRecentCamDist()
        startDistanceStableFrames = 0
        startDistanceReady = false
        startDistanceLastLogNs = 0L
        ballFixHitsInWindow = 0
        ballLastGoodHit = null
        ballLastGoodPose = null
        ballLastGoodNs = 0L
        failReason = null
        state = State.IDLE
        resetEndDisplayBuf()
    }

    private fun completeFirstMeasurementIfNeeded() {
        if (!isFirstMeasurementActive) return
        isFirstMeasurementPending = false
        isFirstMeasurementActive = false
        firstMeasurementStartNs = 0L
        firstSigmaGuardStartNs = 0L
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
            // 5-point cup sampling: require at least 3 valid hits.
            5 -> 3
            4 -> 1
            1 -> 1
            else -> max(1, (0.32f * totalPoints.toFloat()).toInt())
        }
    }

    private fun isCupAimReady(sample: V31HitSampler.Sample?): Boolean {
        if (sample == null || sample.bestHit == null) return false
        val projectedPx = sample.gridProjectedCupPx
        val projectedReady = projectedPx != null && projectedPx.isFinite() && projectedPx >= CUP_AIM_READY_MIN_PROJECTED_PX
        return projectedReady && sample.validHits >= 1
    }

    private fun isCupFixReady(sample: V31HitSampler.Sample?): Boolean {
        if (sample == null || sample.bestHit == null) return false
        val enoughHits = sample.validHits >= minValidHitsForCupEnd(sample.totalPoints)
        val noCenterOnlyFallback = sample.centerFallbackUsed != true
        return enoughHits && noCenterOnlyFallback
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
                State.AIM_START -> trackingOk && ballFixHitsInWindow >= currentBallFixNeedHits() && startDistanceReady
                else -> false
            }

        val finishEnabled =
            when (state) {
                State.AIM_END ->
                    trackingOk &&
                        isCupAimReady(sample) &&
                        startAnchor != null
                State.FAIL ->
                    trackingOk &&
                        canRetryCupFromFail() &&
                        isCupAimReady(sample)
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
        val diagCupSigmaNearHoldCount =
            if (state == State.STABILIZING_END || state == State.END_LOCKED || state == State.RESULT) {
                cupSigmaNearHoldCount
            } else if (state == State.FAIL) {
                lastCupSigmaNearHoldCountAtFail
            } else {
                null
            }

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
            cupSigmaNearHoldCount = diagCupSigmaNearHoldCount,
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

