package com.wmcho.puttingcaddie

import android.graphics.Color
import android.graphics.PointF
import android.graphics.RectF
import android.util.Log
import com.google.ar.core.Anchor
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.TrackingState
import java.util.ArrayDeque
import com.wmcho.puttingcaddie.slope.LocalSurfaceFitInputProvider
import com.wmcho.puttingcaddie.slope.PlaneBaselineInputProvider
import com.wmcho.puttingcaddie.slope.SlopeInputResult
import com.wmcho.puttingcaddie.slope.SlopeRawSamplesData
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
    private val localSurfaceFitProvider = LocalSurfaceFitInputProvider(sampler)

    /** [DistanceMeasurementActivity] 필드 테스트 prefs — LocalSurfaceFit experimentalDiagnostics에 기록 */
    @Volatile var slopeTestSessionId: String? = null
    @Volatile var slopeRepeatIndex: Int? = null
    @Volatile var slopeTargetScenario: String? = null

    // Slope Input 2.0: tick 시 frame/roi를 buildUi에서 사용
    private var tickFrame: Frame? = null
    private var tickRoiScreen: RectF? = null
    // Debug-only diagnostics for XYZ H/V issues (no UI exposure).
    private var dbgLastHvLogNs: Long = 0L
    private var dbgLastZoomHitLogNs: Long = 0L
    private var lastBallEnableGateLogNs: Long = 0L
    private var lastBallSampleRejectJumpNs: Long = 0L
    // CUP debug: CUP_* logs use debugLoggingEnabled (isDebuggableBuild). Release: no CUP logs.
    private var lastCupEnableGateLogNs: Long = 0L
    private var lastCupDetectStateLogNs: Long = 0L
    private var lastCupFixAttemptLogNs: Long = 0L
    /** RESULT 진입 시 1회만 [SlopeFieldTestLog] 남기기 위한 전이 감지 */
    private var lastEngineStateForSlopeFieldLog: State? = null
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
        val         ballGroundPlaneExtentX: Float?,
        val ballGroundPlaneExtentZ: Float?,
        // GREENIQ Plane consistency (logged at CUP fix)
        val ballCupPlaneAngleDeg: Float?,
        val ballCupSamePlane: Boolean?,
        val cupPlaneType: String?,
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
        /** FAR 샘플링 플랜 (로그/QA; [V31HitSampler.sampleCupPlaneMultiRay] 정책과 정합) */
        val samplingPlanGrid: Int? = null,
        val samplingPlanHalfSpanPx: Float? = null,
        val samplingPlanStepPx: Float? = null,
        val samplingPlanTemporalFrames: Int? = null,
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
        val isMeasuringFlow: Boolean,
            // Debug: BALL 버튼 막힌 이유 (개발 빌드에서만 표시)
        val ballBlockedReason: BallBlockedReason? = null,
        val ballArWarmupSuccessCount: Int? = null,
        val ballArWarmupRequired: Int? = null,
        // Debug: CUP 버튼 막힌 이유 (개발 빌드에서만 표시)
        val cupBlockedReason: String? = null,
        val slopeDebugInfo: SlopeDebugInfo? = null,
        val trackingState: String? = null,  // Slope Debug: GOOD/LIMITED/BAD
        val anchorDistanceMeters: Float? = null,  // Slope Debug: anchor-to-anchor (거리 vs h 비교용)
        // Slope 2nd gen Phase 1: 입력 소스 진단
        val slopeInputSource: String? = null,
        val ballTrackableType: String? = null,
        val cupTrackableType: String? = null,
        val deltaYRaw: Float? = null,
        val ballNormalSource: String? = null,
        val cupNormalSource: String? = null,
        val slopeExperimentalResult: SlopeInputResult? = null,
        /** P3 Step 1: ball∪cup shared plane + [SlopeComputer.computeSharedOnly]. 제품 경사 미교체. */
        val experimentalSharedSlope: SlopeDebugInfo? = null,
        val sharedPlaneFitResidualM: Float? = null,
        val sharedPlaneSampleCount: Int? = null,
        /** P3 후보 선택·trim/corridor·JSON·Logcat용 */
        val sharedP3Log: SharedP3LogPayload? = null,
        /** CUP lock 직후 캡처한 LIVE/거리 진단(RESULT에서만 의미 있음). */
        val distanceLockLiveSource: String? = null,
        val distanceLockLiveRawM: Float? = null,
        val distanceLockLiveSigmaM: Float? = null,
        val distanceLockLiveRangeM: Float? = null,
        val distanceLockLiveStable: Boolean? = null,
        val distanceFinalFallbackUsed: Boolean = false,
        val distanceFinalSnapshotReason: String? = null,
        /** [FinalDistanceGuard] — RESULT 전용 */
        val finalDistanceLivePlaneMeters: Float? = null,
        val finalDistanceSourceBeforeGuard: String? = null,
        val finalDistanceSourceAfterGuard: String? = null,
        val finalDistanceGuardTriggered: Boolean = false,
        val finalDistanceGuardReasons: String? = null,
        val finalDistanceAnchorInvalidReason: String? = null,
        val planeIntersectionVsAnchorDeltaM: Float? = null,
        val planeIntersectionVsAnchorDeltaRatio: Float? = null,
        /** 앵커 포즈 월드 위치 [x,y,z] — 로그/JSON 전용 */
        val ballAnchorWorld: FloatArray? = null,
        val cupAnchorWorld: FloatArray? = null,
        /** 앵커가 생성된 UI 상태 (confirmLock 시점) */
        val ballAnchorSourceState: String? = null,
        val cupAnchorSourceState: String? = null,
        val anchorHorizontalM: Float? = null,
        val anchor3dM: Float? = null,
        /** 컵 앵커 생성 시각(ms). [anchorCreatedAtMs]와 동일(마지막 앵커 커밋). */
        val startAnchorCreatedAtMs: Long? = null,
        val endAnchorCreatedAtMs: Long? = null,
        val anchorCreatedAtMs: Long? = null,
        /** 이전 세션 앵커를 detach 후 교체했는지(동일 측정 흐름 내 재락) */
        val anchorReused: Boolean? = null,
        /** [tx,ty,tz,qw,qx,qy,qz] */
        val startAnchorPose: FloatArray? = null,
        val endAnchorPose: FloatArray? = null,
        /** END_LOCK 직전 LIVE가 쓴 컵 쪽 월드 좌표(레이–면 교차 또는 hitTest) */
        val ballLiveHitWorldAtFinish: FloatArray? = null,
        val cupLiveHitWorldAtFinish: FloatArray? = null,
        val cupAnchorHitWorldBeforeSnap: FloatArray? = null,
        val cupAnchorPoseWorldAfterSnap: FloatArray? = null,
        val cupAnchorCommitTrackableType: String? = null,
        val cupAnchorCommitTrackableId: String? = null,
        /** END_LOCK 직전: 커밋 후보(hit) vs [cupLiveHitWorldAtFinish] XZ 거리(m) — 게이트와 동일 정의 */
        val cupCandidateVsLiveHitXZDeltaMAtCommit: Float? = null,
        val cupEndAnchorCommitStrictFar: Boolean? = null,
        val cupEndAnchorCommitGateThresholdM: Float? = null,
        val cupEndAnchorGateBypassedMaxRetries: Boolean? = null,
        /** [cupEndAnchorGateBypassedMaxRetries] 와 동일 — 통계/파서용 별칭 */
        val cupEndAnchorCommitBypassSession: Boolean? = null,
        /** 컵 END 경로: 이번 틱 LIVE 갱신에 사용한 프레임 타임스탬프(ns). 멀티레이 정렬·게이트 동일 */
        val cupLiveWorldFrameTimestampNs: Long? = null,
        /** 컵 END translation 소스 — [LIVE_DISTANCE_WORLD] = [lastLiveCupWorldForDistance] freeze */
        val cupEndAnchorPositionSource: String? = null,
        /** [endAnchor.pose] vs freeze 직전 live world XZ(m) — 동일성 검증 */
        val cupEndAnchorVsLiveWorldXZM: Float? = null,
        /** END_LOCKED/RESULT: [SlopeInputProjection] 결과 (필드 검증 로그용). */
        val slopeProjectionSnapshot: SlopeInputProjection.Result? = null,
        /** 투영 좌표 기준 컵−볼 Y 차이(m): slopeCup.y − slopeBall.y */
        val deltaYProjected: Float? = null,
        /** 컵 락 분리 관찰용(로그/JSON) — 락 로직 변경 없음 */
        val cupLockPrimaryReason: String? = null,
        val cupLockSecondaryReason: String? = null,
        val cupLockOutcome: String? = null,
        val cupSigmaMarginCm: Float? = null,
        val cupMaxConsecutiveOkReached: Int? = null,
        val cupConsecutiveRequired: Int? = null,
        val cupElapsedStabilizingMs: Long? = null,
        val cupProjectedPxEnd: Float? = null,
        val cupValidSampleCountEnd: Int? = null,
        val cupSoftHoldTriggered: Boolean? = null,
        val cupSoftLockTriggered: Boolean? = null,
        val cupTrackingStateEnd: String? = null,
        val cupFarModeHoldActive: Boolean? = null,
        val cupQualityGuardPassed: Boolean? = null,
        val cupLiveSnapshotAvailable: Boolean? = null,
        val cupEligibleLiveCupWorldAvailable: Boolean? = null,
        /** 중심 조준 유지 + 내부 offset-anchor 재투영 컵 월드 */
        val cupOffsetMode: String? = null,
        val cupAnchorCandidateCount: Int? = null,
        val cupAnchorBestVarianceCm: Float? = null,
        val cupAnchorBestResidualCm: Float? = null,
        val cupAnchorBestNormalY: Float? = null,
        val cupAnchorBestOffsetDistCm: Float? = null,
        val cupAnchorPlaneResidualCm: Float? = null,
        val cupAnchorReprojectSuccess: Boolean? = null,
        val cupAnchorFailureReason: String? = null,
        val cupAnchorThrottleMode: String? = null,
        val cupAnchorThrottleAgeMs: Long? = null,
        val cupAnchorCacheHit: Boolean? = null,
        val cupAnchorLastFailureReason: String? = null,
        val cupAnchorCacheWasSuccess: Boolean? = null,
        val cupAnchorCameraMovedM: Float? = null,
        val cupAnchorCameraAngleMovedDeg: Float? = null,
        val cupAnchorReprojectedAffectsDistance: Boolean? = null,
        val cupAnchorReprojectedAffectsEndAnchor: Boolean? = null,
        val cupAnchorReprojectedAffectsExperimentalSurface: Boolean? = null,
        val cupAnchorDistanceFrameOfRef: String? = null,
        val cupAnchorEndAnchorFrameOfRef: String? = null,
        val cupAnchorExperimentalSurfaceFrameOfRef: String? = null,
        val cupAnchorFrameOfRefMismatch: Boolean? = null,
        val cupAnchorFrameOfRefMismatchReason: String? = null,
        val cupAnchorQualityProbeStatus: String? = null,
        val cupAnchorQualityInvalidateReason: String? = null,
        val cupAnchorSelectedOffsetCm: Float? = null,
        val cupAnchorSelectedOffsetScore: Float? = null,
        val cupAnchorSelectedOffsetRankReason: String? = null,
        val cupAnchorEligibleMinCm: Float? = null,
        val cupAnchorEligibleMaxCm: Float? = null,
        val cupAnchorLegacyDeltaCm: Float? = null
    )

    // v3.1 constants
    private val FAIL_NO_VALID_HITS_M = 10
    private val STABILIZING_TIMEOUT_NS = 2_000_000_000L
    private val END_STABILIZING_TIMEOUT_NS = 4_000_000_000L
    private val PAUSED_GRACE_NS = 1_000_000_000L

    // END tuning (Cup): cap samples to reduce far-distance timeouts.
    private val END_MAX_MIN_SAMPLES = 18
    // CUP 1차: 원거리 sigma 완화 (ball-to-cup 기준 6m 이상) — 장거리 lock 성공률용
    private val CUP_SIGMA_RELAX_FROM_DISTANCE_M = 6.0f
    private val CUP_SIGMA_FAR_RELAX_CM = 0.015f  // 6m+ 구간 threshold +1.5cm (short bias 원인 아님)
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
        // CUP 1차: 원거리(ball-to-cup >= 6m)에서 소폭 완화
        val raw = a + (b * dMeters)
        val cap = endSigmaCapMeters(dMeters)
        val floor = cap * 0.7f
        var sigmaMax = max(raw, floor).coerceIn(sigmaMin, cap)
        if (dMeters >= CUP_SIGMA_RELAX_FROM_DISTANCE_M) {
            sigmaMax = (sigmaMax + CUP_SIGMA_FAR_RELAX_CM).coerceIn(sigmaMin, cap)
        }
        return sigmaMax
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
    private val START_ANCHOR_AVERAGE_FRAMES = 10
    private val START_ANCHOR_CLOSE_TO_AVG_M = 0.05f  // 현재 hit가 평균에서 5cm 이내일 때만 confirm
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
    private val CUP_PROJECTED_PX_CONDITIONAL_FAR5 = 45f
    private val FAR_PRECISION_MODE_DISTANCE_M = 6.0f
    private val FAR_PRECISION_MODE_ENTER_PROJECTED_PX = 45f
    private val FAR_PRECISION_MODE_EXIT_PROJECTED_PX = 48f
    private val CUP_SIGMA_NEAR_RATIO = 1.10f
    private val CUP_SIGMA_NEAR_EXTRA_HOLD_NS = 250_000_000L
    private val CUP_SIGMA_SOFTPASS_RATIO = 1.12f
    private val CUP_SIGMA_SOFTPASS_MIN_VALID_HITS = 9
    private val CUP_SIGMA_SOFTPASS_MIN_PROJECTED_PX = 24f
    // CUP 1차: timeout 직전 near-stable 구제 (아주 작게만)
    private val CUP_SOFT_LOCK_ENABLED = true  // 장거리 lock 성공률용 (short bias 원인 아님)
    private val CUP_SOFT_LOCK_SIGMA_MARGIN_M = 0.015f  // threshold +1.5cm
    private val CUP_SOFT_LOCK_MIN_VALID_HITS = 9
    private val CUP_SOFT_LOCK_MIN_PROJECTED_PX = 18f
    /** 컵 END anchor freeze 시 허용하는 live world 최대 노후화 (ns) — [cupLiveWorldEligibleForEndCommit] */
    private val CUP_LIVE_WORLD_MAX_STALE_NS = 200_000_000L

    // AR warm-up: 최근 N프레임 윈도우 내 중앙 hit 성공 횟수로 판단
    // BALL 1차: 상수화 + 초기 2.5초 완화 (35→28)
    private val BALL_WARMUP_WINDOW_FRAMES = 45
    private val BALL_WARMUP_REQUIRED_HITS = 35
    private val BALL_WARMUP_REQUIRED_HITS_INITIAL = 28
    private val BALL_WARMUP_INITIAL_RELAX_MS = 2500L
    private val arWarmupWindow = ArrayDeque<Boolean>(BALL_WARMUP_WINDOW_FRAMES + 4)
    private var arWarmupReady: Boolean = false
    private var warmupSessionStartNs: Long = 0L

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
    private var startAnchorCreatedAtMs: Long? = null
    private var endAnchorCreatedAtMs: Long? = null
    private var ballAnchorReplacedPrevious: Boolean = false
    private var cupAnchorReplacedPrevious: Boolean = false
    private var lastTickTrackingStateName: String = "UNKNOWN"

    /** 마지막 유효 LIVE(컵 ROI) 거리에 쓰인 컵 쪽 월드 좌표 — END_LOCK 스냅샷용 */
    private var lastLiveCupWorldForDistance: FloatArray? = null
    /** [lastLiveCupWorldForDistance] 갱신 시점의 ARCore [Frame.getTimestamp] (ns) — 멀티레이·게이트 동일 tick 정렬용 */
    private var lastLiveCupWorldFrameTimestampNs: Long? = null
    /** [lastLiveCupWorldForDistance] 갱신 시점의 [tick] nowNs — 컵 END commit 시 freshness 검사 */
    private var lastLiveCupWorldUpdateNs: Long = 0L
    private var capturedBallLiveHitWorldAtFinish: FloatArray? = null
    private var capturedCupLiveHitWorldAtFinish: FloatArray? = null
    private var capturedCupAnchorHitWorldBeforeSnap: FloatArray? = null
    private var capturedCupAnchorPoseWorldAfterSnap: FloatArray? = null
    private var capturedCupAnchorCommitTrackableType: String? = null
    private var capturedCupAnchorCommitTrackableId: String? = null
    /** 커밋 직전 후보 vs LIVE XZ(m) — 피드백 JSON */
    private var capturedCupEndAnchorCommitGateDeltaM: Float? = null
    private var capturedCupEndAnchorGateStrictFar: Boolean? = null
    private var capturedCupEndAnchorGateThresholdM: Float? = null
    private var capturedCupEndAnchorGateBypassedMaxRetries: Boolean? = null
    private var capturedCupLiveWorldFrameTimestampNs: Long? = null
    /** 컵 END translation source — [LIVE_DISTANCE_WORLD] = [lastLiveCupWorldForDistance] freeze */
    private var capturedCupEndAnchorPositionSource: String? = null
    /** [endAnchor.pose] vs commit 직전 live world XZ(m) — 동일 freeze면 ~0 */
    private var capturedCupEndAnchorVsLiveWorldXZM: Float? = null

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

    // --- Cup lock field diagnostics (no threshold / lock logic changes) ---
    private var cupLockDiagGateLogMs: Long = 0L
    private var cupLockDiagTimelineLogMs: Long = 0L
    private var cupLockDiagLastSigmaOk: Boolean? = null
    private var cupLockDiagMaxConsecutiveOk: Int = 0
    private var cupLockDiagSoftHoldTriggered: Boolean = false
    private var cupLockDiagSoftLockTriggered: Boolean = false
    private var cupLockDiagLastPrimary: String? = null
    private var cupLockDiagLastSecondary: String? = null
    private var lastCupLockOutcome: CupLockDiagnostics.CupLockOutcomeSummary? = null
    /** 컵 LIVE 월드: center-aim + 우하단 offset anchor 재투영 경로 진단(JSON/로그) */
    private var lastCupOffsetAnchorDiag: CupOffsetAnchorEstimator.Diagnostics? = null
    private val cupOffsetAnchorCacheState = CupOffsetAnchorEstimator.CacheState()
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

    // LIVE stability gate: END_LOCKED 전 sigma/max-min 검사
    private val liveStabilityBuf = FloatArray(12)
    private var liveStabilityBufSize = 0
    private var liveStabilityBufIndex = 0
    private val LIVE_STABILITY_MIN_FRAMES = 5
    private val LIVE_STABILITY_MAX_SIGMA_M = 0.12f
    private val LIVE_STABILITY_MAX_RANGE_M = 0.18f

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
    private var cupPlaneNormal: PoseStatsMad.Vec3? = null
    /** CUP_FIX 시 컵 Plane 히트 지점(주변 그린 면 기준점용), world */
    private var cupPlanePointFix: FloatArray? = null
    // Slope diagnostics: BALL fix 시 plane 저장, CUP fix 시 samePlane/cupPlaneType 확정
    private var ballPlaneAtFix: Plane? = null
    private var ballCupSamePlane: Boolean? = null
    private var cupPlaneType: String? = null
    // Slope 2nd gen Phase 1: 입력 소스 진단
    private var ballTrackableType: String? = null
    private var cupTrackableType: String? = null
    private var slopePhase1ResultLogged: Boolean = false
    private var slopeSharedP3Logged: Boolean = false
    private var slopeInputProjectionLogged: Boolean = false
    /** SharedP3 normal 방향 최소 temporal 일관성 (측정 리셋 시 초기화) */
    private var lastSharedP3NormalWorld: FloatArray? = null

    // Slope v1: BALL_FIX/CUP_FIX 시점에 수집한 experimental slope 샘플
    private var experimentalBallSlopeSamples: SlopeRawSamplesData? = null
    private var experimentalCupSlopeSamples: SlopeRawSamplesData? = null

    // LIVE minimal diagnostics (must not affect computation)
    private var liveRawMeters: Float? = null
    private var centerHitValid: Boolean? = null

    /** END_LOCKED에서 최종 거리 스냅샷 직전 캡처 ([DistanceFieldTestLog] / 피드백 JSON용). */
    private var capturedDistanceLockLiveSource: String? = null
    private var capturedDistanceLockLiveRawM: Float? = null
    private var capturedDistanceLockLiveSigmaM: Float? = null
    private var capturedDistanceLockLiveRangeM: Float? = null
    private var capturedDistanceLockLiveStable: Boolean? = null
    private var capturedDistanceFinalFallbackUsed: Boolean = false
    private var capturedDistanceFinalSnapshotReason: String? = null

    private var capturedFinalDistanceLivePlaneMeters: Float? = null
    private var capturedFinalDistanceSourceBeforeGuard: String? = null
    private var capturedFinalDistanceSourceAfterGuard: String? = null
    private var capturedFinalDistanceGuardTriggered: Boolean = false
    private var capturedFinalDistanceGuardReasons: String? = null
    private var capturedFinalDistanceAnchorInvalidReason: String? = null
    private var capturedPlaneVsAnchorDeltaM: Float? = null
    private var capturedPlaneVsAnchorDeltaRatio: Float? = null

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
    private var lastSamplingPlanGrid: Int? = null
    private var lastSamplingPlanHalfSpanPx: Float? = null
    private var lastSamplingPlanStepPx: Float? = null
    private var lastSamplingPlanTemporalFrames: Int? = null
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
    /** [buildUi] 직전 틱의 BALL 유효 히트(게이트 스냅샷용). */
    private var ballEffectiveHitLastTick: HitResult? = null
    /** [tick] 마지막 IDLE/AIM_START [buildUi]에서 갱신 — [UiEvent.StartPressed]는 그 다음이므로 탭 직전 프레임 값. */
    private var lastBallGateSnapshot: BallGateSnapshot? = null
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

    private fun pushLiveStability(d: Float) {
        if (!d.isFinite() || d <= 0f) return
        liveStabilityBuf[liveStabilityBufIndex] = d
        liveStabilityBufIndex = (liveStabilityBufIndex + 1) % liveStabilityBuf.size
        if (liveStabilityBufSize < liveStabilityBuf.size) liveStabilityBufSize++
    }

    private fun resetLiveStabilityBuf() {
        liveStabilityBufSize = 0
        liveStabilityBufIndex = 0
    }

    private fun liveStabilitySigmaAndRangeOrNull(): Pair<Float, Float>? {
        if (liveStabilityBufSize < LIVE_STABILITY_MIN_FRAMES) return null
        val vals = (0 until liveStabilityBufSize).map {
            liveStabilityBuf[(liveStabilityBufIndex - 1 - it + liveStabilityBuf.size) % liveStabilityBuf.size]
        }.filter { it.isFinite() }
        if (vals.size < LIVE_STABILITY_MIN_FRAMES) return null
        val mean = vals.average().toFloat()
        val variance = vals.map { (it - mean) * (it - mean) }.average().toFloat()
        val sigma = kotlin.math.sqrt(variance)
        val range = (vals.maxOrNull() ?: mean) - (vals.minOrNull() ?: mean)
        return sigma to range
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
                        logBallStartGateLine(didResetState = true)
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
                        startAnchorCreatedAtMs = null
                        endAnchorCreatedAtMs = null
                        ballAnchorReplacedPrevious = false
                        cupAnchorReplacedPrevious = false
                        lastLiveCupWorldForDistance = null
                        lastLiveCupWorldFrameTimestampNs = null
                        lastLiveCupWorldUpdateNs = 0L
                        lastCupOffsetAnchorDiag = null
                        cupOffsetAnchorCacheState.reset()
                        capturedBallLiveHitWorldAtFinish = null
                        capturedCupLiveHitWorldAtFinish = null
                        capturedCupAnchorHitWorldBeforeSnap = null
                        capturedCupAnchorPoseWorldAfterSnap = null
                        capturedCupAnchorCommitTrackableType = null
                        capturedCupAnchorCommitTrackableId = null
                        capturedCupEndAnchorCommitGateDeltaM = null
                        capturedCupEndAnchorGateStrictFar = null
                        capturedCupEndAnchorGateThresholdM = null
                        capturedCupEndAnchorGateBypassedMaxRetries = null
                        capturedCupLiveWorldFrameTimestampNs = null
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
                        experimentalBallSlopeSamples = null
                        experimentalCupSlopeSamples = null
                        lastSharedP3NormalWorld = null
                    }
                    State.AIM_START -> {
                        logBallStartGateLine(didResetState = false)
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
                        commitFinalDistanceWithGuard()
                        if (debugLoggingEnabled) {
                            val usedFallback = !(endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f) &&
                                (lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f)
                            val resultReason = when {
                                endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f -> "live_snapshot"
                                lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> "last_display_fallback"
                                else -> "zero_default"
                            }
                            val anchorDist = distanceBetweenAnchorsMeters()
                            Log.d("CUP_FINAL_RESULT", "anchorDistance_m=${"%.3f".format(anchorDist)} finalDistance_m=${finalDistanceMeters.let { "%.3f".format(it) }} " +
                                "endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }} " +
                                "usedFallback=$usedFallback resultReason=$resultReason")
                            if (finalDistanceMeters <= 0f || !finalDistanceMeters.isFinite()) {
                                Log.d("CUP_ZERO_DISTANCE_GUARD", "state=RESULT whyZero=snapshot_null_and_last_display_invalid " +
                                    "endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} " +
                                    "lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }}")
                            }
                        }
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

    fun tick(frame: Frame, roiScreen: RectF, nowNs: Long, session: Session): UiModel {
        tickFrame = frame
        tickRoiScreen = roiScreen
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
                commitFinalDistanceWithGuard()
                if (debugLoggingEnabled) {
                    val usedFallback = !(endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f) &&
                        (lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f)
                    val resultReason = when {
                        endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f -> "live_snapshot"
                        lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> "last_display_fallback"
                        else -> "zero_default"
                    }
                    val anchorDist = distanceBetweenAnchorsMeters()
                    Log.d("CUP_FINAL_RESULT", "anchorDistance_m=${"%.3f".format(anchorDist)} finalDistance_m=${finalDistanceMeters.let { "%.3f".format(it) }} " +
                        "endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }} " +
                        "usedFallback=$usedFallback resultReason=$resultReason")
                    if (finalDistanceMeters <= 0f || !finalDistanceMeters.isFinite()) {
                        Log.d("CUP_ZERO_DISTANCE_GUARD", "state=RESULT whyZero=snapshot_null_and_last_display_invalid " +
                            "endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} " +
                            "lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }}")
                    }
                }
                state = State.RESULT
                endLockedAtNs = 0L
            }
        }

        val tracking = frame.camera.trackingState
        lastTickTrackingStateName = tracking.name

        // STOPPED => immediate FAIL
        if (tracking == TrackingState.STOPPED) {
            logCupLockSessionEnd(
                outcome = "TRACKING_STOPPED",
                primary = "tracking_not_ok",
                secondary = null,
                nowNs = nowNs,
                sample = null
            )
            enterFail(FailReason.FAIL_TRACKING_STOPPED)
            return buildUi(nowNs, tracking, flashFail = true)
        }

        val inStabilizing = (state == State.STABILIZING_START || state == State.STABILIZING_END)

        // PAUSED => stop accumulating, but if stabilizing and persists >= 1s => timeout
        if (tracking == TrackingState.PAUSED) {
            if (inStabilizing) {
                if (pausedEnterNs == 0L) pausedEnterNs = nowNs
                if (nowNs - pausedEnterNs >= PAUSED_GRACE_NS) {
                    logCupLockSessionEnd(
                        outcome = "TIMEOUT",
                        primary = "tracking_not_ok",
                        secondary = "paused_grace",
                        nowNs = nowNs,
                        sample = null
                    )
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
        if (!sampling) {
            // AR warm-up: IDLE 시 중앙 hit 성공을 슬라이딩 윈도우로 판단
            if (state == State.IDLE && tracking == TrackingState.TRACKING) {
                if (warmupSessionStartNs == 0L) warmupSessionStartNs = System.nanoTime()
                val centerHit = sampler.hitTestBestPlaneAtScreenPoint(
                    frame = frame,
                    screenX = roiScreen.centerX(),
                    screenY = roiScreen.centerY(),
                    maxDistanceMeters = 10f,
                    preferUpwardFacing = true,
                    yBelowCameraMeters = null
                )
                val success = centerHit != null
                arWarmupWindow.addLast(success)
                while (arWarmupWindow.size > BALL_WARMUP_WINDOW_FRAMES) arWarmupWindow.removeFirst()
                val requiredHits = if (warmupSessionStartNs > 0L) {
                    val elapsedMs = (System.nanoTime() - warmupSessionStartNs) / 1_000_000L
                    if (elapsedMs < BALL_WARMUP_INITIAL_RELAX_MS) BALL_WARMUP_REQUIRED_HITS_INITIAL else BALL_WARMUP_REQUIRED_HITS
                } else BALL_WARMUP_REQUIRED_HITS
                arWarmupReady = arWarmupWindow.count { it } >= requiredHits
            }
            return buildUi(nowNs, tracking)
        }
        if (state == State.AIM_END || state == State.FAIL) {
            pushCupCenterHistory(PointF(roiScreen.centerX(), roiScreen.centerY()))
        }

        // GREENIQ LIVE (laser): 컵 AIM/END에서 멀티레이·게이트가 동일 tick의 LIVE를 쓰도록 샘플보다 먼저 갱신.
        val inLiveStates = (state == State.AIM_END || state == State.STABILIZING_END)
        if (inLiveStates && startAnchor != null) {
            var raw: Float? = null
            liveSource = LiveSource.NONE
            liveRawMeters = null
            centerHitValid = false

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
                    if (abs(dirWorld[1]) >= LIVE_RAYDIR_Y_EPS && abs(denom) > 1e-5f) {
                        val px = gp.pointOnPlane.x
                        val py = gp.pointOnPlane.y
                        val pz = gp.pointOnPlane.z
                        val t = (nx * (px - ox) + ny * (py - oy) + nz * (pz - oz)) / denom
                        if (t.isFinite() && t > 0f && t <= LIVE_MAX_HIT_DISTANCE_M) {
                            val ix = ox + (dirWorld[0] * t)
                            val iy = oy + (dirWorld[1] * t)
                            val iz = oz + (dirWorld[2] * t)
                            val legacyW = floatArrayOf(ix, iy, iz)
                            val usedW = applyOffsetAnchorIfEnabled(frame, roiScreen, legacyW)
                            val iPose = Pose.makeTranslation(usedW[0], usedW[1], usedW[2])
                            raw = distanceFromStartToPoseMeters(startAnchor!!.pose, iPose)
                            liveSource = LiveSource.PLANE_INTERSECTION
                            lastLiveCupWorldForDistance = usedW.copyOf()
                            lastLiveCupWorldFrameTimestampNs = frame.timestamp
                            lastLiveCupWorldUpdateNs = nowNs
                        }
                    }
                }
            }

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
                    liveSource = LiveSource.HITTEST_FALLBACK
                    val p = centerHit.hitPose
                    val legacyW = floatArrayOf(p.tx(), p.ty(), p.tz())
                    val usedW = applyOffsetAnchorIfEnabled(frame, roiScreen, legacyW)
                    val iPose = Pose.makeTranslation(usedW[0], usedW[1], usedW[2])
                    raw = distanceFromStartToPoseMeters(startAnchor!!.pose, iPose)
                    lastLiveCupWorldForDistance = usedW.copyOf()
                    lastLiveCupWorldFrameTimestampNs = frame.timestamp
                    lastLiveCupWorldUpdateNs = nowNs
                }
            }

            if (raw != null && raw!!.isFinite() && raw!! > 0f) {
                val cur = raw!!
                liveRawMeters = cur
                centerHitValid = true
                if (!liveHasValue) {
                    liveSmoothedMeters = cur
                    liveHasValue = true
                } else {
                    val prev = liveSmoothedMeters
                    val delta = (cur - prev).coerceIn(-LIVE_MAX_FRAME_DELTA_M, LIVE_MAX_FRAME_DELTA_M)
                    val curClamped = prev + delta
                    val jumpLimit = max(LIVE_JUMP_GUARD_M, prev * 0.35f)
                    if (abs(curClamped - prev) <= jumpLimit) {
                        liveSmoothedMeters = (prev * 0.75f) + (curClamped * 0.25f)
                    }
                }
                pushLiveMedianWindow(liveSmoothedMeters)
                pushLiveStability(liveSmoothedMeters)
                if (debugLoggingEnabled && nowNs - dbgLastZoomHitLogNs >= 300_000_000L) {
                    dbgLastZoomHitLogNs = nowNs
                    val prevS = lastAimSample
                    Log.d(
                        "ZOOM_HIT",
                        "state=$state src=${liveSource.name} raw=${"%.3f".format(cur)} smooth=${"%.3f".format(liveSmoothedMeters)} " +
                            "bestHitDist=${"%.3f".format(prevS?.bestHit?.distance ?: 0f)} hitType=${prevS?.hitType} valid=${prevS?.validHits ?: 0}/${prevS?.totalPoints ?: 0}"
                    )
                }
            }
        } else {
            liveHasValue = false
            liveSmoothedMeters = 0f
            liveSource = LiveSource.NONE
            liveRawMeters = null
            centerHitValid = null
            lastLiveCupWorldForDistance = null
            lastLiveCupWorldFrameTimestampNs = null
            lastLiveCupWorldUpdateNs = 0L
            lastCupOffsetAnchorDiag = null
            cupOffsetAnchorCacheState.reset()
            resetLiveMedianWindow()
            resetLiveStabilityBuf()
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
                val cupLiveAlignForHitPick = cupLiveAlignForMultiRaySample()
                // Cup FIX sampling: multi-ray 5x5 centered near screen center with Y offset and distance/Y guards.
                val cupYOffsetRatio =
                    if (MeasurementFinalizationPolicy.sessionTargetMode ==
                        MeasurementFinalizationPolicy.TargetMode.BALL_ON_FLOOR
                    ) {
                        0f
                    } else {
                        CUP_CENTER_Y_OFFSET_RATIO
                    }
                var s =
                    sampler.sampleCupPlaneMultiRay(
                        frame = frame,
                        baseRoiScreen = cupSamplingRoi,
                        offsetPercent = CUP_OFFSET_PERCENT_PRIMARY,
                        centerYOffsetRatio = cupYOffsetRatio,
                        gridSize = 5,
                        maxHitDistanceMeters = 12f,
                        yBelowCameraMeters = 0.1f,
                        preferUpwardFacing = true,
                        requireUpwardFacing = false,
                        liveWorldAlignForHitPick = cupLiveAlignForHitPick
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
                            centerYOffsetRatio = cupYOffsetRatio,
                            gridSize = 5,
                            maxHitDistanceMeters = 18f,
                            yBelowCameraMeters = 0.05f,
                            preferUpwardFacing = true,
                            requireUpwardFacing = false,
                            forceFar5x5 = true,
                            liveWorldAlignForHitPick = cupLiveAlignForHitPick
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
                            centerYOffsetRatio = cupYOffsetRatio,
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
                                    (retryProjectedPx == null && s.validHits <= 2),
                            liveWorldAlignForHitPick = cupLiveAlignForHitPick
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
                lastSamplingPlanGrid = s.samplingPlanGrid
                lastSamplingPlanHalfSpanPx = s.gridHalfSpanPx
                lastSamplingPlanStepPx = s.gridStepPx
                lastSamplingPlanTemporalFrames = s.samplingPlanTemporalFrames
                if (debugLoggingEnabled && (state == State.AIM_END || state == State.STABILIZING_END) &&
                    nowNs - lastCupDetectStateLogNs >= 500_000_000L) {
                    lastCupDetectStateLogNs = nowNs
                    Log.d("CUP_DETECT_STATE", "state=$state gridPlan=${s.gridPlan ?: "null"} projectedCupPx=${s.gridProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} " +
                        "validHits=${s.validHits} totalPoints=${s.totalPoints} centerFallbackUsed=${s.centerFallbackUsed == true} " +
                        "roiCenterX=${cupSamplingRoi.centerX()} roiCenterY=${cupSamplingRoi.centerY()} " +
                        "cupFrozenCenterX=${cupFrozenCenter?.x?.let { "%.1f".format(it) } ?: "null"} cupFrozenCenterY=${cupFrozenCenter?.y?.let { "%.1f".format(it) } ?: "null"} " +
                        "startAnchorOk=${startAnchor != null} dMeters=${s.gridEstimatedDistanceMeters?.let { "%.2f".format(it) } ?: "null"}")
                }
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
                lastSamplingPlanGrid = null
                lastSamplingPlanHalfSpanPx = null
                lastSamplingPlanStepPx = null
                lastSamplingPlanTemporalFrames = null
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
            // B. jumpRejected 구분 로그 (throttled)
            if (ballJumpRejectedTick == true && state == State.AIM_START) {
                if (nowNs - lastBallSampleRejectJumpNs >= 500_000_000L) {
                    lastBallSampleRejectJumpNs = nowNs
                    Log.d("BALL_SAMPLE_REJECT", "reason=jumpRejected")
                }
            }
            ballEffectiveHitLastTick = ballEffectiveHitForTick
        } else {
            ballEffectiveHitLastTick = null
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
                        val estM = startAnchor?.pose?.let { s -> sample.bestHit?.let { h -> distanceMeters(s, h.hitPose) } } ?: sample.bestHit?.distance ?: 0f
                        if (debugLoggingEnabled) {
                            val validHitRatio = if (sample.totalPoints > 0) sample.validHits.toFloat() / sample.totalPoints else 0f
                            Log.d("CUP_VALID_HIT_BREAKDOWN", "failureMode=CUP_LOW_VALID_500MS phase=CUP_CAPTURE_PENDING " +
                                "projectedCupPx=${projectedPx?.let { "%.1f".format(it) } ?: "null"} validHits=${sample.validHits} totalPoints=${sample.totalPoints} " +
                                "validSampleCount=${sample.validHits} validHitRatio=${"%.2f".format(validHitRatio)} " +
                                "centerFallbackUsed=${sample.centerFallbackUsed == true} multiRayPlan=${sample.gridPlan ?: "null"} " +
                                "gridHalfPx=${sample.gridHalfSpanPx?.let { "%.1f".format(it) } ?: "null"} stepPx=${sample.gridStepPx?.let { "%.1f".format(it) } ?: "null"} " +
                                "estimatedDistanceM=${"%.3f".format(estM)}")
                            if (estM >= CUP_SIGMA_RELAX_FROM_DISTANCE_M) {
                                val sigmaRelaxApplied = estM >= CUP_SIGMA_RELAX_FROM_DISTANCE_M
                                val sigmaRelaxTier = when {
                                    estM < 5f -> "near"
                                    estM < CUP_SIGMA_RELAX_FROM_DISTANCE_M -> "mid"
                                    estM < 8f -> "far"
                                    else -> "max"
                                }
                                Log.d("CUP_LONG_RANGE_GATE", "failureMode=low_valid_hits estimatedDistanceM=${"%.3f".format(estM)} " +
                                    "projectedCupPx=${projectedPx?.let { "%.1f".format(it) } ?: "null"} validHits=${sample.validHits} validSampleCount=${sample.validHits} " +
                                    "centerFallbackUsed=${sample.centerFallbackUsed == true} fallbackOnly=${(sample.validHits <= 1 && sample.centerFallbackUsed == true)} " +
                                    "sigmaRelaxApplied=$sigmaRelaxApplied sigmaRelaxTier=$sigmaRelaxTier phase=CUP_CAPTURE_PENDING")
                            }
                        }
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=EARLY_FAIL holdMs=${holdElapsedNs / 1_000_000L} zoom=${"%.2f".format(z)} " +
                                "projectedPx=${if (projectedPx != null) "%.1f".format(projectedPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} " +
                                "valid=${sample.validHits}/${sample.totalPoints} decision=EARLY_FAIL_LOW_VALID"
                        )
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        logCupLockSessionEnd(
                            outcome = "NO_VALID_HITS",
                            primary = "not_enough_samples",
                            secondary = "cup_low_valid_500ms",
                            nowNs = nowNs,
                            sample = sample
                        )
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
                        val estM = startAnchor?.pose?.let { s -> sample.bestHit?.let { h -> distanceMeters(s, h.hitPose) } } ?: sample.bestHit?.distance ?: 0f
                        if (debugLoggingEnabled && estM >= CUP_SIGMA_RELAX_FROM_DISTANCE_M) {
                            val sigmaRelaxTier = when {
                                estM < 5f -> "near"
                                estM < CUP_SIGMA_RELAX_FROM_DISTANCE_M -> "mid"
                                estM < 8f -> "far"
                                else -> "max"
                            }
                            Log.d("CUP_LONG_RANGE_GATE", "failureMode=mixed estimatedDistanceM=${"%.3f".format(estM)} " +
                                "projectedCupPx=${projectedPx?.let { "%.1f".format(it) } ?: "null"} validHits=${sample.validHits} validSampleCount=${sample.validHits} " +
                                "centerFallbackUsed=${sample.centerFallbackUsed == true} fallbackOnly=${(sample.validHits <= 1 && sample.centerFallbackUsed == true)} " +
                                "sigmaRelaxApplied=true sigmaRelaxTier=$sigmaRelaxTier phase=CUP_CAPTURE_PENDING")
                        }
                        Log.d(
                            "V31StateMachine",
                            "CUP_CAPTURE_PENDING state=TIMEOUT holdMs=${holdElapsedNs / 1_000_000L} zoom=${"%.2f".format(z)} " +
                                "projectedPx=${if (projectedPx != null) "%.1f".format(projectedPx) else "NA"} " +
                                "gridHalfPx=${if (sample.gridHalfSpanPx != null) "%.1f".format(sample.gridHalfSpanPx) else "NA"} " +
                                "valid=${sample.validHits}/${sample.totalPoints} decision=TIMEOUT_3S"
                        )
                        cupCapturePendingStartNs = 0L
                        cupFrozenCenter = null
                        logCupLockSessionEnd(
                            outcome = "TIMEOUT",
                            primary = "timeout_other",
                            secondary = "cup_pending_timeout_3s",
                            nowNs = nowNs,
                            sample = sample
                        )
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
                // CUP 1차: soft-lock — 검증용 비활성화 (런칭버전과 동일)
                if (CUP_SOFT_LOCK_ENABLED && state == State.STABILIZING_END &&
                    lastFailDetailCode == "TIMEOUT_SIGMA_NOT_OK" &&
                    tracking == TrackingState.TRACKING) {
                    val sigmaUsed = lastSigmaUsedMeters ?: 0f
                    val sigmaMax = lastSigmaMaxMeters ?: 0f
                    val hit = sample.bestHit
                    val proj = sample.gridProjectedCupPx
                    val validOk = sample.validHits >= CUP_SOFT_LOCK_MIN_VALID_HITS
                    val projOk = proj != null && proj.isFinite() && proj >= CUP_SOFT_LOCK_MIN_PROJECTED_PX
                    val sigmaNearOk = sigmaMax > 1e-6f && sigmaUsed <= sigmaMax + CUP_SOFT_LOCK_SIGMA_MARGIN_M
                    if (hit != null && validOk && projOk && sigmaNearOk) {
                        val cw = cupLiveWorldEligibleForEndCommit(nowNs)
                        if (cw != null) {
                            cupLockDiagSoftLockTriggered = true
                            confirmLock(nowNs, hit, sample, cw, session)
                            buf.clear()
                            lastAimSample = null
                            if (debugLoggingEnabled) {
                                Log.d("CUP_LOCK_GATE", "softLock=true sigmaUsed=${"%.3f".format(sigmaUsed)} sigmaMax=${"%.3f".format(sigmaMax)} " +
                                    "projectedPx=${"%.1f".format(proj)} validHits=${sample.validHits}")
                            }
                            return buildUi(nowNs, tracking, sample, flashLock = true)
                        }
                    }
                }
                if (debugLoggingEnabled && lastFailDetailCode == "TIMEOUT_SIGMA_NOT_OK") {
                    Log.d("CUP_ZERO_DISTANCE_GUARD", "state=FAIL whyZero=timeout_sigma_not_ok " +
                        "sigmaCurrent_cm=${lastSigmaUsedMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                        "sigmaThreshold_cm=${lastSigmaMaxMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                        "liveAtFinish_m=${liveRawMeters?.let { "%.3f".format(it) } ?: "null"} " +
                        "fixedDEstMeters=${"%.3f".format(fixedDEstMeters)}")
                }
                if (debugLoggingEnabled && fixedDEstMeters >= CUP_SIGMA_RELAX_FROM_DISTANCE_M) {
                    val proj = sample.gridProjectedCupPx
                    val sigmaRelaxApplied = fixedDEstMeters >= CUP_SIGMA_RELAX_FROM_DISTANCE_M
                    val sigmaRelaxTier = when {
                        fixedDEstMeters < 5f -> "near"
                        fixedDEstMeters < CUP_SIGMA_RELAX_FROM_DISTANCE_M -> "mid"
                        fixedDEstMeters < 8f -> "far"
                        else -> "max"
                    }
                    val failureMode = when (lastFailDetailCode) {
                        "TIMEOUT_SIGMA_NOT_OK" -> "sigma_timeout"
                        "TIMEOUT_NOT_ENOUGH_SAMPLES" -> "low_valid_hits"
                        "TIMEOUT_TRACKING_NOT_OK" -> "tracking_not_ok"
                        else -> lastFailDetailCode?.lowercase() ?: "unknown"
                    }
                    Log.d("CUP_LONG_RANGE_GATE", "failureMode=$failureMode estimatedDistanceM=${"%.3f".format(fixedDEstMeters)} " +
                        "fixedDEstMeters=${"%.3f".format(fixedDEstMeters)} projectedCupPx=${proj?.let { "%.1f".format(it) } ?: "null"} " +
                        "validHits=${sample.validHits} validSampleCount=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                        "fallbackOnly=${(sample.validHits <= 1 && sample.centerFallbackUsed == true)} " +
                        "sigmaCurrent_cm=${lastSigmaUsedMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                        "sigmaThreshold_cm=${lastSigmaMaxMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                        "sigmaRelaxApplied=$sigmaRelaxApplied sigmaRelaxTier=$sigmaRelaxTier phase=STABILIZING_END")
                }
                if (state == State.STABILIZING_END) {
                    logCupLockSessionEndStabilizingTimeout(nowNs, sample)
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
                    if (debugLoggingEnabled && state == State.STABILIZING_END) {
                        if (nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                            lastCupFixAttemptLogNs = nowNs
                            Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                "rejectedReason=insufficient_valid_hits")
                        }
                        val validHitRatio = if (sample.totalPoints > 0) sample.validHits.toFloat() / sample.totalPoints else 0f
                        Log.d("CUP_VALID_HIT_BREAKDOWN", "failureMode=NO_VALID_HITS phase=STABILIZING_END " +
                            "projectedCupPx=${sample.gridProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} validHits=${sample.validHits} totalPoints=${sample.totalPoints} " +
                            "validSampleCount=${sample.validHits} validHitRatio=${"%.2f".format(validHitRatio)} " +
                            "centerFallbackUsed=${sample.centerFallbackUsed == true} multiRayPlan=${sample.gridPlan ?: "null"} " +
                            "gridHalfPx=${sample.gridHalfSpanPx?.let { "%.1f".format(it) } ?: "null"} stepPx=${sample.gridStepPx?.let { "%.1f".format(it) } ?: "null"} " +
                            "estimatedDistanceM=${"%.3f".format(fixedDEstMeters)}")
                        if (fixedDEstMeters >= CUP_SIGMA_RELAX_FROM_DISTANCE_M) {
                            val sigmaRelaxTier = when {
                                fixedDEstMeters < 5f -> "near"
                                fixedDEstMeters < CUP_SIGMA_RELAX_FROM_DISTANCE_M -> "mid"
                                fixedDEstMeters < 8f -> "far"
                                else -> "max"
                            }
                            Log.d("CUP_LONG_RANGE_GATE", "failureMode=low_valid_hits estimatedDistanceM=${"%.3f".format(fixedDEstMeters)} " +
                                "fixedDEstMeters=${"%.3f".format(fixedDEstMeters)} projectedCupPx=${sample.gridProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} " +
                                "validHits=${sample.validHits} validSampleCount=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                "fallbackOnly=${(sample.validHits <= 1 && sample.centerFallbackUsed == true)} " +
                                "sigmaCurrent_cm=${lastSigmaUsedMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                                "sigmaThreshold_cm=${lastSigmaMaxMeters?.let { "%.2f".format(it * 100) } ?: "null"} " +
                                "sigmaRelaxApplied=true sigmaRelaxTier=$sigmaRelaxTier phase=STABILIZING_END")
                        }
                    }
                    lastFixedMinSamplesAtFail = fixedMinSamples
                    lastBufSizeAtFail = buf.size
                    lastSigmaOkConsecutiveAtFail = sigmaOkConsecutive
                    lastSigmaOkElapsedMsAtFail = if (sigmaOkStartNs > 0L) ((nowNs - sigmaOkStartNs) / 1_000_000L) else 0L
                    lastCupSigmaNearHoldCountAtFail = cupSigmaNearHoldCount
                    lastFailDetailCode = "NO_VALID_HITS"
                    if (state == State.STABILIZING_END) {
                        logCupLockSessionEnd(
                            outcome = "NO_VALID_HITS",
                            primary = "not_enough_samples",
                            secondary = "stabilizing_insufficient_valid_hits",
                            nowNs = nowNs,
                            sample = sample
                        )
                    }
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
                if (buf.size >= START_ANCHOR_AVERAGE_FRAMES && holdElapsed >= BALL_FIX_MIN_HOLD_NS) {
                    val distRange = ballRecentCamDistRangeOrNull()
                    val allowBallFix = (distRange != null && distRange <= BALL_FIX_MAX_CAMDIST_RANGE_M)
                    val recentBuf = buf.takeLast(START_ANCHOR_AVERAGE_FRAMES)
                    val avgTx = recentBuf.map { it.x }.average().toFloat()
                    val avgTy = recentBuf.map { it.y }.average().toFloat()
                    val avgTz = recentBuf.map { it.z }.average().toFloat()
                    val curP = stabilizingHit!!.hitPose
                    val distToAvg = sqrt(
                        (curP.tx() - avgTx) * (curP.tx() - avgTx) +
                            (curP.ty() - avgTy) * (curP.ty() - avgTy) +
                            (curP.tz() - avgTz) * (curP.tz() - avgTz)
                    )
                    val closeToAvg = distToAvg <= START_ANCHOR_CLOSE_TO_AVG_M
                    Log.d(
                        "V31StateMachine",
                        "BALL_FIX_GUARD holdMs=${holdElapsed / 1_000_000L} buf=${buf.size} distToAvg=${"%.3f".format(distToAvg)} " +
                            "closeToAvg=$closeToAvg distRange=${if (distRange != null) "%.3f".format(distRange) else "NA"} allow=$allowBallFix"
                    )
                    if (!allowBallFix || !closeToAvg) {
                        return buildUi(nowNs, tracking, sample)
                    }
                    confirmLock(nowNs, stabilizingHit, session = session)
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
                        cupLockDiagSoftHoldTriggered = true
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

                // CUP_LOCK_GATE: 원거리 sigma block 시 로그 (500ms throttle)
                if (debugLoggingEnabled && state == State.STABILIZING_END && !sigmaOk &&
                    nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                    lastCupFixAttemptLogNs = nowNs
                    val timeoutRemainingMs = max(0L, (END_STABILIZING_TIMEOUT_NS - (nowNs - stabilizingEnterNs)) / 1_000_000L)
                    val blockedReason = if (sigmaUsed.isFinite() && sigmaMax.isFinite()) "sigma_not_ok" else "sigma_not_computed"
                    Log.d("CUP_LOCK_GATE", "engineState=END_STABILIZING estimatedDistanceM=${"%.3f".format(fixedDEstMeters)} " +
                        "projectedCupPx=${projectedPx?.let { "%.1f".format(it) } ?: "null"} multiRayPlan=${sample.gridPlan ?: "null"} " +
                        "validSampleCount=${sample.validHits} sigmaCurrent_cm=${sigmaUsed.let { "%.2f".format(it * 100) }} " +
                        "sigmaThreshold_cm=${sigmaMax.let { "%.2f".format(it * 100) }} sigmaOkConsecutive=$sigmaOkConsecutive " +
                        "sigmaOkElapsedMs=${if (sigmaOkStartNs > 0L) (nowNs - sigmaOkStartNs) / 1_000_000L else 0} " +
                        "timeoutRemainingMs=$timeoutRemainingMs blockedReason=$blockedReason")
                }

                val okElapsed = if (sigmaOkStartNs > 0L) (nowNs - sigmaOkStartNs) else 0L
                if (state == State.STABILIZING_END) {
                    emitCupLockStabilizingEndDiagnostics(
                        nowNs = nowNs,
                        tracking = tracking,
                        sample = sample,
                        stabilizingHit = stabilizingHit,
                        sigmaUsed = sigmaUsed,
                        sigmaMax = sigmaMax,
                        sigmaOk = sigmaOk,
                        sigmaOkConsecutive = sigmaOkConsecutive,
                        okElapsedNs = okElapsed
                    )
                }
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
                            if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                                lastCupFixAttemptLogNs = nowNs
                                Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=$centerFallbackUsed " +
                                    "sigma=${lastSigmaUsedMeters?.let { "%.3f".format(it) } ?: "null"} sigmaMax=${lastSigmaMaxMeters?.let { "%.3f".format(it) } ?: "null"} " +
                                    "rejectedReason=cup_quality_guard")
                            }
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
                                    if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                                        lastCupFixAttemptLogNs = nowNs
                                        Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                            "sigma=${lastSigmaUsedMeters?.let { "%.3f".format(it) } ?: "null"} sigmaMax=${lastSigmaMaxMeters?.let { "%.3f".format(it) } ?: "null"} " +
                                            "liveRaw=${liveRaw?.let { "%.3f".format(it) } ?: "null"} liveEma=${liveEma.let { "%.3f".format(it) }} " +
                                            "rejectedReason=live_snapshot_guard")
                                    }
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
                            startAnchor?.pose?.let { s ->
                                val lw = lastLiveCupWorldForDistance
                                if (lw != null) {
                                    val p = Pose.makeTranslation(lw[0], lw[1], lw[2])
                                    distanceMeters(s, p)
                                } else {
                                    distanceMeters(s, stabilizingHit.hitPose)
                                }
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
                                    if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                                        lastCupFixAttemptLogNs = nowNs
                                        Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                            "sigma=${lastSigmaUsedMeters?.let { "%.3f".format(it) } ?: "null"} sigmaMax=${lastSigmaMaxMeters?.let { "%.3f".format(it) } ?: "null"} " +
                                            "rejectedReason=far_mode_hold")
                                    }
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
                                    if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                                        lastCupFixAttemptLogNs = nowNs
                                        Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                            "sigma=${lastSigmaUsedMeters?.let { "%.3f".format(it) } ?: "null"} sigmaMax=${lastSigmaMaxMeters?.let { "%.3f".format(it) } ?: "null"} " +
                                            "rejectedReason=far_mode_hold")
                                    }
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
                    val cupWorldForCommit = cupLiveWorldEligibleForEndCommit(nowNs)
                    if (state == State.STABILIZING_END && cupWorldForCommit == null) {
                        if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                            lastCupFixAttemptLogNs = nowNs
                            Log.d(
                                "CUP_FIX_ATTEMPT",
                                "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                                    "rejectedReason=no_eligible_live_cup_world staleAgeMs=${if (lastLiveCupWorldUpdateNs > 0L) (nowNs - lastLiveCupWorldUpdateNs) / 1_000_000L else -1}"
                            )
                        }
                        return buildUi(nowNs, tracking, sample)
                    }
                    if (debugLoggingEnabled && nowNs - lastCupFixAttemptLogNs >= 500_000_000L) {
                        lastCupFixAttemptLogNs = nowNs
                        val cupFixDistVal =
                            startAnchor?.pose?.let { s ->
                                val lw = lastLiveCupWorldForDistance
                                if (lw != null) {
                                    val p = Pose.makeTranslation(lw[0], lw[1], lw[2])
                                    distanceMeters(s, p)
                                } else {
                                    distanceMeters(s, stabilizingHit.hitPose)
                                }
                            }
                        Log.d("CUP_FIX_ATTEMPT", "state=$state validHits=${sample.validHits} centerFallbackUsed=${sample.centerFallbackUsed == true} " +
                            "sigma=${lastSigmaUsedMeters?.let { "%.3f".format(it) } ?: "null"} sigmaMax=${lastSigmaMaxMeters?.let { "%.3f".format(it) } ?: "null"} " +
                            "liveHasValue=$liveHasValue liveSmoothedMeters=${liveSmoothedMeters.let { "%.3f".format(it) }} " +
                            "lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }} cupFixDist=${cupFixDistVal?.let { "%.3f".format(it) } ?: "null"} " +
                            "rejectedReason=none")
                    }
                    confirmLock(nowNs, stabilizingHit, sample, cupWorldForCommit, session)
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
        resetCupLockFieldDiagnosticsForNewEndAttempt()
        cupOffsetAnchorCacheState.reset()

        val start = startAnchor?.pose
        fixedDEstMeters =
            if (start != null) distanceMeters(start, hit.hitPose) else hit.distance
        // Cup directive: force 5x5 multi-ray for end stabilization.
        fixedGrid = CUP_GRID_SIZE_POINTS
        fixedMinSamples = chooseMinSamplesForDEst(fixedDEstMeters).coerceAtMost(END_MAX_MIN_SAMPLES)
    }

    private fun confirmLock(
        nowNs: Long,
        hit: HitResult,
        endLockSample: V31HitSampler.Sample? = null,
        /** 컵 END: 거리용 [lastLiveCupWorldForDistance]와 동일한 점을 freeze — 필수 */
        cupAnchorLiveWorld: FloatArray? = null,
        session: Session
    ) {
        when (state) {
            State.STABILIZING_START -> {
                val anchor = hit.createAnchor()
                ballAnchorReplacedPrevious = startAnchor != null
                startAnchor?.detach()
                startAnchor = anchor
                startAnchorCreatedAtMs = System.currentTimeMillis()
                state = State.START_LOCKED
                startLockedAtNs = System.nanoTime()

                // Slope v1: BALL_FIX 시점에 Ball slope 샘플 수집·저장 (카메라가 Ball 향함)
                val roi = tickRoiScreen
                val frame = tickFrame
                if (roi != null && frame != null && axisMode == AxisMode.XYZ) {
                    val ballCenter = PointF(roi.centerX(), roi.centerY())
                    experimentalBallSlopeSamples = localSurfaceFitProvider.collectSamplesAtCenter(
                        frame = frame,
                        roiScreen = roi,
                        screenCenter = ballCenter,
                        collectedAtNs = nowNs
                    )
                }

                // Capture ground plane model at BALL fix time for LIVE ray-plane intersection.
                val t = hit.trackable
                ballTrackableType = t?.javaClass?.simpleName ?: "Unknown"
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
                    ballPlaneAtFix = plane
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
                    Log.d("SLOPE_PHASE1", "BALL_FIX slopeInput=PLANE trackableType=$ballTrackableType planeType=${plane.type.name} normalY=${"%.3f".format(ny)}")
                    if (debugLoggingEnabled) {
                        Log.d("BALL_FIX_DIAGNOSTICS", "fixHitPose_x=${"%.4f".format(hit.hitPose.tx())} fixHitPose_y=${"%.4f".format(hit.hitPose.ty())} fixHitPose_z=${"%.4f".format(hit.hitPose.tz())} " +
                            "distanceFromCamera_m=${"%.3f".format(hit.distance)} fixSource=${ballDiagHitSourceUsed ?: "UNKNOWN"} validHits=${ballDiagSampleValidHits ?: 0} " +
                            "useFarthest=true gridCount=9")
                    }
                } else {
                    ballTrackableType = null
                    groundPlaneModel = null
                    ballPlaneAtFix = null
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
                    Log.d("SLOPE_PHASE1", "BALL_FIX slopeInput=NONE trackableType=$ballTrackableType planeType=NONE (plane cast failed)")
                    if (debugLoggingEnabled) {
                        Log.d("BALL_FIX_DIAGNOSTICS", "fixHitPose_x=${"%.4f".format(hit.hitPose.tx())} fixHitPose_y=${"%.4f".format(hit.hitPose.ty())} fixHitPose_z=${"%.4f".format(hit.hitPose.tz())} " +
                            "distanceFromCamera_m=${"%.3f".format(hit.distance)} fixSource=${ballDiagHitSourceUsed ?: "UNKNOWN"} validHits=${ballDiagSampleValidHits ?: 0} " +
                            "useFarthest=true gridCount=9")
                    }
                }
            }
            State.STABILIZING_END -> {
                val liveW = cupAnchorLiveWorld ?: return
                val frame = tickFrame ?: return
                capturedCupEndAnchorCommitGateDeltaM =
                    CupEndAnchorCommitPolicy.xzDistanceMeters(world3FromPose(hit.hitPose), liveW)
                val strictFarCommit =
                    endLockSample?.let {
                        CupEndAnchorCommitPolicy.strictFarMode(it.gridProjectedCupPx, null, it.validHits)
                    }
                        ?: CupEndAnchorCommitPolicy.strictFarMode(
                            lastMultiRayProjectedCupPx,
                            null,
                            lastValidSampleCount ?: 0
                        )
                capturedCupEndAnchorGateStrictFar = strictFarCommit
                capturedCupEndAnchorGateThresholdM =
                    CupEndAnchorCommitPolicy.thresholdM(strictFarCommit)
                capturedCupEndAnchorGateBypassedMaxRetries = false
                capturedCupLiveWorldFrameTimestampNs = lastLiveCupWorldFrameTimestampNs

                val cupHitWorldBeforeSnap = liveW.copyOf()
                cupAnchorReplacedPrevious = endAnchor != null
                endAnchor?.detach()
                val pose = Pose.makeTranslation(liveW[0], liveW[1], liveW[2])
                val anchor =
                    try {
                        session.createAnchor(pose)
                    } catch (e: Exception) {
                        Log.e("V31StateMachine", "CUP_END_SESSION_ANCHOR_FAILED", e)
                        return
                    }
                val cupPoseWorldAfterSnap = world3FromPose(anchor.pose)
                capturedCupAnchorHitWorldBeforeSnap = cupHitWorldBeforeSnap.copyOf()
                capturedCupAnchorPoseWorldAfterSnap = cupPoseWorldAfterSnap.copyOf()
                capturedBallLiveHitWorldAtFinish = startAnchor?.pose?.let { world3FromPose(it) }?.copyOf()
                capturedCupLiveHitWorldAtFinish = liveW.copyOf()
                capturedCupAnchorCommitTrackableType = hit.trackable?.javaClass?.simpleName ?: "SESSION_LIVE_WORLD"
                capturedCupAnchorCommitTrackableId = hit.trackable?.let { System.identityHashCode(it).toString() }
                capturedCupEndAnchorPositionSource = "LIVE_DISTANCE_WORLD"
                capturedCupEndAnchorVsLiveWorldXZM =
                    CupEndAnchorCommitPolicy.xzDistanceMeters(
                        floatArrayOf(anchor.pose.tx(), anchor.pose.ty(), anchor.pose.tz()),
                        liveW
                    )
                endAnchor = anchor
                endAnchorCreatedAtMs = System.currentTimeMillis()
                state = State.END_LOCKED
                endLockedAtNs = nowNs
                logCupLockSessionEnd(
                    outcome = "LOCKED",
                    primary = "ok",
                    secondary = null,
                    nowNs = nowNs,
                    sample = endLockSample
                )
                Log.i(
                    "MEASUREMENT_CUP_END",
                    "anchorSrc=LIVE_DISTANCE_WORLD liveWorld_m=(${liveW[0]},${liveW[1]},${liveW[2]}) " +
                        "bestHitVsLiveXZ_m=${capturedCupEndAnchorCommitGateDeltaM?.let { "%.4f".format(it) } ?: "na"} " +
                        "anchorVsLiveXZ_m=${capturedCupEndAnchorVsLiveWorldXZM?.let { "%.4f".format(it) } ?: "na"}"
                )
                Log.d(
                    "END_ANCHOR_COMMIT",
                    "anchorSrc=LIVE_DISTANCE_WORLD candidateBeforeSnap=${capturedCupAnchorHitWorldBeforeSnap?.contentToString()} " +
                        "candidateAfterSnap=${capturedCupAnchorPoseWorldAfterSnap?.contentToString()} " +
                        "trackableType=${capturedCupAnchorCommitTrackableType ?: "null"} " +
                        "trackableId=${capturedCupAnchorCommitTrackableId ?: "null"} " +
                        "ballLiveAtFinish=${capturedBallLiveHitWorldAtFinish?.contentToString()} " +
                        "cupLiveAtFinish=${capturedCupLiveHitWorldAtFinish?.contentToString()}"
                )
                if (debugLoggingEnabled) {
                    val anchorDist = distanceBetweenAnchorsMeters()
                    Log.d("CUP_FIX_DIAGNOSTICS", "fixHitPose_x=${"%.4f".format(hit.hitPose.tx())} fixHitPose_y=${"%.4f".format(hit.hitPose.ty())} fixHitPose_z=${"%.4f".format(hit.hitPose.tz())} " +
                        "distanceFromCamera_m=${"%.3f".format(hit.distance)} fixSource=${lastMultiRayPlan ?: "UNKNOWN"} centerFallbackUsed=${lastMultiRayCenterFallbackUsed == true} " +
                        "validHits=${lastValidSampleCount ?: 0} useFarthest=false gridCount=25 anchorDistance_m=${"%.3f".format(anchorDist)}")
                }
                // Slope v1: CUP_FIX 시점에 Cup slope 샘플 수집·저장 (카메라가 Cup 향함)
                val cupRoi = tickRoiScreen
                val cupFrame = tickFrame
                if (cupRoi != null && cupFrame != null && axisMode == AxisMode.XYZ) {
                    val cupCenter = PointF(cupRoi.centerX(), cupRoi.centerY())
                    experimentalCupSlopeSamples = localSurfaceFitProvider.collectSamplesAtCenter(
                        frame = cupFrame,
                        roiScreen = cupRoi,
                        screenCenter = cupCenter,
                        collectedAtNs = nowNs,
                        gridHalfSteps = 2
                    )
                }

                completeFirstMeasurementIfNeeded()
                // LIVE stability gate: 통과 시 farMode → liveSmoothed, 실패 시 fallback
                val stab = liveStabilitySigmaAndRangeOrNull()
                val liveStable = stab != null &&
                    ((stab.first <= LIVE_STABILITY_MAX_SIGMA_M) || (stab.second <= LIVE_STABILITY_MAX_RANGE_M))
                capturedDistanceLockLiveSource = liveSource.name
                capturedDistanceLockLiveRawM = liveRawMeters
                capturedDistanceLockLiveSigmaM = stab?.first
                capturedDistanceLockLiveRangeM = stab?.second
                capturedDistanceLockLiveStable = liveStable
                val snapshotValue: Float? =
                    if (liveStable) {
                        if (useFarModeLiveMedianAtEndLock && (farModeLiveMedianAtEndLock?.isFinite() == true) && (farModeLiveMedianAtEndLock ?: 0f) > 0f) {
                            farModeLiveMedianAtEndLock
                        } else if (liveHasValue && liveSmoothedMeters.isFinite() && liveSmoothedMeters > 0f) {
                            liveSmoothedMeters
                        } else null
                    } else {
                        null
                    }
                capturedDistanceFinalFallbackUsed =
                    (snapshotValue == null) && (lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f)
                capturedDistanceFinalSnapshotReason =
                    when {
                        snapshotValue != null && snapshotValue.isFinite() && snapshotValue > 0f -> "live_snapshot"
                        lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> "last_display_fallback"
                        else -> "zero_default"
                    }
                endLiveSnapshotMeters =
                    snapshotValue
                        ?: lastDisplayDistanceMeters.takeIf { it.isFinite() && it > 0f }
                        ?: 0f
                if (debugLoggingEnabled) {
                    val usedFallback = (snapshotValue == null) && (lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f)
                    val resultReason = when {
                        snapshotValue != null && snapshotValue.isFinite() && snapshotValue > 0f -> "live_snapshot"
                        lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> "last_display_fallback"
                        else -> "zero_default"
                    }
                    val anchorDist = distanceBetweenAnchorsMeters()
                    Log.d("CUP_FINAL_RESULT", "anchorDistance_m=${"%.3f".format(anchorDist)} finalDistance_m=${endLiveSnapshotMeters.let { "%.3f".format(it) }} " +
                        "endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }} " +
                        "liveStable=$liveStable liveHasValue=$liveHasValue usedFallback=$usedFallback resultReason=$resultReason")
                    if (endLiveSnapshotMeters <= 0f || !endLiveSnapshotMeters.isFinite()) {
                        val whyZero = when {
                            !liveStable -> "live_stability_failed"
                            !liveHasValue || (liveSmoothedMeters <= 0f || !liveSmoothedMeters.isFinite()) -> "no_live_value"
                            !(lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f) -> "snapshot_null_and_last_display_invalid"
                            else -> "unknown"
                        }
                        Log.d("CUP_ZERO_DISTANCE_GUARD", "state=END_LOCKED whyZero=$whyZero endLiveSnapshotMeters=${endLiveSnapshotMeters.let { "%.3f".format(it) }} " +
                            "lastDisplayDistanceMeters=${lastDisplayDistanceMeters.let { "%.3f".format(it) }} liveStable=$liveStable liveHasValue=$liveHasValue " +
                            "projectedPx=${lastMultiRayProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} validHits=${lastValidSampleCount ?: 0} " +
                            "centerFallbackUsed=${lastMultiRayCenterFallbackUsed == true}")
                    }
                }
                if (liveStable) {
                    Log.d("V31StateMachine", "LIVE_STABILITY_GATE pass sigma=${stab?.first?.let { "%.3f".format(it) } ?: "N/A"} range=${stab?.second?.let { "%.3f".format(it) } ?: "N/A"}")
                } else {
                    Log.d("V31StateMachine", "LIVE_STABILITY_GATE fail sigma=${stab?.first?.let { "%.3f".format(it) } ?: "N/A"} range=${stab?.second?.let { "%.3f".format(it) } ?: "N/A"} useFallback=true")
                }
                if (endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f) {
                    lastDisplayDistanceMeters = endLiveSnapshotMeters
                }
                useFarModeLiveMedianAtEndLock = false
                farModeLiveMedianAtEndLock = null
                resetLiveStabilityBuf()
                // Plane consistency diagnostics: compare CUP plane normal vs saved BALL ground plane normal.
                ballCupPlaneAngleDeg = null
                cupPlaneNormal = null
                cupPlanePointFix = null
                ballCupSamePlane = null
                cupPlaneType = null
                cupTrackableType = hit.trackable?.javaClass?.simpleName ?: "Unknown"
                val gp = groundPlaneModel
                val cupPlane = hit.trackable as? Plane
                if (cupPlane != null) {
                    cupPlaneType = cupPlane.type.name
                    ballCupSamePlane = (ballPlaneAtFix != null && cupPlane === ballPlaneAtFix)
                    cupPlanePointFix = floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz())
                    val center = cupPlane.centerPose
                    val axis = FloatArray(3)
                    center.getTransformedAxis(1, 1.0f, axis, 0)
                    val nLen = sqrt(axis[0] * axis[0] + axis[1] * axis[1] + axis[2] * axis[2]).takeIf { it > 1e-6f } ?: 1f
                    val cnx = axis[0] / nLen
                    val cny = axis[1] / nLen
                    val cnz = axis[2] / nLen
                    cupPlaneNormal = PoseStatsMad.Vec3(cnx, cny, cnz)
                    if (gp != null) {
                        val dot = (gp.normal.x * cnx + gp.normal.y * cny + gp.normal.z * cnz).coerceIn(-1f, 1f)
                        val angleRad = kotlin.math.acos(dot)
                        val angleDeg = (angleRad * 57.29578f)
                        ballCupPlaneAngleDeg = angleDeg
                        if (angleDeg >= 10f) {
                            Log.w("V31StateMachine", "PLANE_DRIFT_WARN angleDeg=${"%.1f".format(angleDeg)} ballNy=${"%.3f".format(gp.normal.y)} cupNy=${"%.3f".format(cny)}")
                        }
                    }
                } else {
                    ballCupSamePlane = null
                    cupPlaneType = null
                    cupTrackableType = null
                }
                val deltaYLog = if (startAnchor != null && endAnchor != null) endAnchor!!.pose.ty() - startAnchor!!.pose.ty() else Float.NaN
                Log.d("SLOPE_PHASE1", "CUP_FIX slopeInput=PLANE trackableType=$cupTrackableType planeType=${cupPlaneType ?: "null"} samePlane=$ballCupSamePlane deltaYRaw_m=${if (deltaYLog.isFinite()) "%.4f".format(deltaYLog) else "null"} planeDriftDeg=${ballCupPlaneAngleDeg?.let { "%.1f".format(it) } ?: "null"}")
                // Start post-fix hold stability measurement for ~1s.
                cupHoldStartNs = nowNs
                cupHoldBuf.clear()
                cupHoldMaxDevMeters = 0f
                cupHoldSigmaMeters = null
                cupHoldDurationMs = null
            }
            else -> {
                val orphan = hit.createAnchor()
                orphan.detach()
            }
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
        startAnchorCreatedAtMs = null
        endAnchorCreatedAtMs = null
        ballAnchorReplacedPrevious = false
        cupAnchorReplacedPrevious = false
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
        resetCupLockFieldDiagnosticsForNewEndAttempt()
        lastCupLockOutcome = null
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
        resetLiveStabilityBuf()
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
        cupPlaneNormal = null
        cupPlanePointFix = null
        ballPlaneAtFix = null
        ballCupSamePlane = null
        cupPlaneType = null
        ballTrackableType = null
        cupTrackableType = null
        slopePhase1ResultLogged = false
        slopeSharedP3Logged = false
        slopeInputProjectionLogged = false
        lastSharedP3NormalWorld = null
        experimentalBallSlopeSamples = null
        experimentalCupSlopeSamples = null
        capturedDistanceLockLiveSource = null
        capturedDistanceLockLiveRawM = null
        capturedDistanceLockLiveSigmaM = null
        capturedDistanceLockLiveRangeM = null
        capturedDistanceLockLiveStable = null
        capturedDistanceFinalFallbackUsed = false
        capturedDistanceFinalSnapshotReason = null
        capturedFinalDistanceLivePlaneMeters = null
        capturedFinalDistanceSourceBeforeGuard = null
        capturedFinalDistanceSourceAfterGuard = null
        capturedFinalDistanceGuardTriggered = false
        capturedFinalDistanceGuardReasons = null
        capturedFinalDistanceAnchorInvalidReason = null
        capturedPlaneVsAnchorDeltaM = null
        capturedPlaneVsAnchorDeltaRatio = null
        capturedCupEndAnchorCommitGateDeltaM = null
        capturedCupEndAnchorGateStrictFar = null
        capturedCupEndAnchorGateThresholdM = null
        capturedCupEndAnchorGateBypassedMaxRetries = null
        capturedCupLiveWorldFrameTimestampNs = null
        capturedCupEndAnchorPositionSource = null
        capturedCupEndAnchorVsLiveWorldXZM = null
        lastLiveCupWorldForDistance = null
        lastLiveCupWorldFrameTimestampNs = null
        lastLiveCupWorldUpdateNs = 0L
        lastCupOffsetAnchorDiag = null
        cupOffsetAnchorCacheState.reset()
        capturedBallLiveHitWorldAtFinish = null
        capturedCupLiveHitWorldAtFinish = null
        capturedCupAnchorHitWorldBeforeSnap = null
        capturedCupAnchorPoseWorldAfterSnap = null
        capturedCupAnchorCommitTrackableType = null
        capturedCupAnchorCommitTrackableId = null
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
        MeasurementFinalizationPolicy.endSession()
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
        arWarmupWindow.clear()
        arWarmupReady = false
        warmupSessionStartNs = 0L
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

    private fun resetCupLockFieldDiagnosticsForNewEndAttempt() {
        cupLockDiagGateLogMs = 0L
        cupLockDiagTimelineLogMs = 0L
        cupLockDiagLastSigmaOk = null
        cupLockDiagMaxConsecutiveOk = 0
        cupLockDiagSoftHoldTriggered = false
        cupLockDiagSoftLockTriggered = false
        cupLockDiagLastPrimary = null
        cupLockDiagLastSecondary = null
    }

    private fun logCupLockSessionEndStabilizingTimeout(nowNs: Long, sample: V31HitSampler.Sample) {
        val (p, s) = CupLockDiagnostics.normalizeFromFailDetail(lastFailDetailCode)
        logCupLockSessionEnd(
            outcome = "TIMEOUT",
            primary = p,
            secondary = s,
            nowNs = nowNs,
            sample = sample
        )
    }

    private fun logCupLockSessionEnd(
        outcome: String,
        primary: String,
        secondary: String?,
        nowNs: Long,
        sample: V31HitSampler.Sample?
    ) {
        val totalMs =
            if (stabilizingEnterNs > 0L) {
                ((nowNs - stabilizingEnterNs) / 1_000_000L).coerceAtLeast(0L)
            } else {
                0L
            }
        val farHoldActive =
            farModeHoldStartNs != 0L && (nowNs - farModeHoldStartNs) < FAR_MODE_EXTRA_HOLD_NS
        val liveSnapAvail =
            liveSource == LiveSource.PLANE_INTERSECTION &&
                centerHitValid == true &&
                liveRawMeters != null &&
                liveRawMeters!!.isFinite() &&
                liveSmoothedMeters.isFinite() &&
                liveSmoothedMeters > 0f
        val vh = sample?.validHits ?: (lastValidSampleCount ?: 0)
        val cf = sample?.centerFallbackUsed == true
        val qualityPassed =
            vh >= CUP_LOCK_MIN_VALID_SAMPLES &&
                !(cf && vh < CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES)
        val eligibleLive = cupLiveWorldEligibleForEndCommit(nowNs) != null
        val summ =
            CupLockDiagnostics.CupLockOutcomeSummary(
                outcome = outcome,
                primaryReason = primary,
                secondaryReason = secondary,
                lastSigmaUsedMeters = lastSigmaUsedMeters,
                lastSigmaMaxMeters = lastSigmaMaxMeters,
                maxConsecutiveOkReached = cupLockDiagMaxConsecutiveOk,
                elapsedTotalMs = totalMs,
                projectedCupPxAtEnd = sample?.gridProjectedCupPx ?: lastMultiRayProjectedCupPx,
                validSampleCountAtEnd = vh,
                softHoldTriggered = cupLockDiagSoftHoldTriggered,
                softLockTriggered = cupLockDiagSoftLockTriggered,
                farModeHoldActive = farHoldActive,
                qualityGuardPassed = qualityPassed,
                liveSnapshotAvailable = liveSnapAvail,
                eligibleLiveCupWorldAvailable = eligibleLive,
                trackingStateEnd = lastTickTrackingStateName
            )
        lastCupLockOutcome = summ
        Log.i("CUP_LOCK_DIAG", CupLockDiagnostics.formatOutcomeSummaryLine(summ))
        Log.i(
            "CUP_LOCK_DIAG",
            CupLockDiagnostics.formatLockSummaryLine(
                outcome = outcome,
                primary = primary,
                secondary = secondary,
                sigmaUsedM = lastSigmaUsedMeters,
                sigmaMaxM = lastSigmaMaxMeters,
                maxConsec = cupLockDiagMaxConsecutiveOk,
                consecRequired = LOCK_CONSEC_TICKS,
                px = sample?.gridProjectedCupPx ?: lastMultiRayProjectedCupPx,
                validHits = vh,
                softHold = cupLockDiagSoftHoldTriggered,
                softLock = cupLockDiagSoftLockTriggered,
                farModeHold = farHoldActive,
                qualityPassed = qualityPassed,
                liveSnap = liveSnapAvail,
                eligibleLive = eligibleLive
            )
        )
    }

    private fun emitCupLockStabilizingEndDiagnostics(
        nowNs: Long,
        tracking: TrackingState,
        sample: V31HitSampler.Sample,
        stabilizingHit: HitResult?,
        sigmaUsed: Float,
        sigmaMax: Float,
        sigmaOk: Boolean,
        sigmaOkConsecutive: Int,
        okElapsedNs: Long
    ) {
        if (stabilizingHit == null) return
        cupLockDiagMaxConsecutiveOk = max(cupLockDiagMaxConsecutiveOk, sigmaOkConsecutive)
        val trackingOk = tracking == TrackingState.TRACKING
        val sigmaComputed = sigmaUsed.isFinite() && sigmaMax.isFinite()
        val sigmaMode = if (axisMode == AxisMode.XZ) "sigmaXZ" else "sigmaXYZ"
        val timeGateOkFlag = sigmaOkConsecutive >= LOCK_CONSEC_TICKS && okElapsedNs >= LOCK_TIME_GATE_NS
        val elapsedStabMs = (nowNs - stabilizingEnterNs) / 1_000_000L

        val cupValidSampleCount = sample.validHits
        val centerFallbackUsed = sample.centerFallbackUsed == true
        val qualityBlocked =
            cupValidSampleCount < CUP_LOCK_MIN_VALID_SAMPLES ||
                (centerFallbackUsed && cupValidSampleCount < CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES)

        val liveRaw = liveRawMeters
        val liveEma = liveSmoothedMeters
        val liveReady =
            liveSource == LiveSource.PLANE_INTERSECTION &&
                centerHitValid == true &&
                liveRaw != null &&
                liveRaw.isFinite() &&
                liveEma.isFinite() &&
                liveEma > 0f
        val liveSnapFriction =
            liveReady && run {
                val diff = abs(liveRaw!! - liveEma)
                val diffThreshold = max(LIVE_SNAPSHOT_GUARD_BASE_DIFF_M, liveEma * LIVE_SNAPSHOT_GUARD_RELATIVE_RATIO)
                diff > diffThreshold
            }

        val cupFixDist =
            startAnchor?.pose?.let { s ->
                val lw = lastLiveCupWorldForDistance
                if (lw != null) {
                    val p = Pose.makeTranslation(lw[0], lw[1], lw[2])
                    distanceMeters(s, p)
                } else {
                    distanceMeters(s, stabilizingHit.hitPose)
                }
            }
        val liveMedian5 = liveMedian5OrNaN()
        val farMode =
            isFarMode(
                cupDistanceFromCameraMeters = stabilizingHit.distance,
                projectedCupPx = sample.gridProjectedCupPx,
                plan = sample.gridPlan
            )
        val farHoldWouldBlock =
            farMode && run {
                val liveMedianValid = liveMedian5.isFinite() && liveMedian5 > 0f
                val cupFixValid = cupFixDist != null && cupFixDist.isFinite() && cupFixDist > 0f
                if (!liveMedianValid || !cupFixValid) {
                    true
                } else {
                    abs(liveMedian5 - cupFixDist!!) > FAR_MODE_MAX_LIVE_CUP_DIFF_M
                }
            }

        val gatedReady = sigmaOk && sigmaOkConsecutive >= LOCK_CONSEC_TICKS && okElapsedNs >= LOCK_TIME_GATE_NS
        val warmupBlock =
            gatedReady && isFirstMeasurementActive && (nowNs - firstMeasurementStartNs) < FIRST_MEAS_WARMUP_NS
        val sigmaRatio =
            if (sigmaUsed.isFinite() && sigmaMax.isFinite() && sigmaMax > 1e-6f) sigmaUsed / sigmaMax else 0f
        val firstSigmaBlock =
            gatedReady &&
                isFirstMeasurementActive &&
                !warmupBlock &&
                sigmaRatio >= FIRST_MEAS_SIGMA_GUARD_RATIO

        val eligibleLive = cupLiveWorldEligibleForEndCommit(nowNs) != null

        val prevPrimary = cupLockDiagLastPrimary
        val prevSecondary = cupLockDiagLastSecondary
        val (primary, secondary) =
            CupLockDiagnostics.classifyCupLockBlockCascade(
                CupLockDiagnostics.CascadeInput(
                    trackingOk = trackingOk,
                    bufSize = buf.size,
                    fixedMinSamples = fixedMinSamples,
                    sigmaComputed = sigmaComputed,
                    sigmaOk = sigmaOk,
                    consecutiveOkCount = sigmaOkConsecutive,
                    consecutiveRequired = LOCK_CONSEC_TICKS,
                    okElapsedNs = okElapsedNs,
                    lockTimeGateNs = LOCK_TIME_GATE_NS,
                    firstMeasWarmupBlocking = warmupBlock,
                    firstSigmaGuardBlocking = firstSigmaBlock,
                    cupQualityBlocked = gatedReady && qualityBlocked,
                    liveSnapshotGuardWouldBlock = gatedReady && !qualityBlocked && liveSnapFriction,
                    farModeHoldWouldBlock = gatedReady && !qualityBlocked && !liveSnapFriction && farHoldWouldBlock,
                    eligibleLiveCupWorld = eligibleLive,
                    projectedCupPx = sample.gridProjectedCupPx,
                    aimMinProjectedPx = CUP_AIM_READY_MIN_PROJECTED_PX
                )
            )
        cupLockDiagLastPrimary = primary
        cupLockDiagLastSecondary = secondary

        val tsMs = System.currentTimeMillis()
        val sessionId = slopeTestSessionId?.trim().orEmpty()
        if (tsMs - cupLockDiagGateLogMs >= CupLockDiagnostics.GATE_SNAPSHOT_MIN_INTERVAL_MS ||
            primary != prevPrimary ||
            secondary != prevSecondary
        ) {
            cupLockDiagGateLogMs = tsMs
            if (primary != prevPrimary || secondary != prevSecondary) {
                Log.i("CUP_LOCK_DIAG", CupLockDiagnostics.formatBlockReasonLine(primary, secondary, lastFailDetailCode))
            }
            Log.i(
                "CUP_LOCK_DIAG",
                CupLockDiagnostics.formatGateSnapshotLine(
                    sessionId = sessionId,
                    timestampMs = tsMs,
                    state = state.name,
                    fixedDEstM = fixedDEstMeters,
                    projectedCupPx = sample.gridProjectedCupPx,
                    validSampleCount = sample.validHits,
                    sigmaUsedM = if (sigmaComputed) sigmaUsed else null,
                    sigmaMaxM = if (sigmaComputed) sigmaMax else null,
                    sigmaMode = sigmaMode,
                    sigmaComputed = sigmaComputed,
                    trackingOk = trackingOk,
                    timeGateOk = timeGateOkFlag,
                    consecutiveOk = sigmaOkConsecutive,
                    consecutiveRequired = LOCK_CONSEC_TICKS,
                    elapsedStabMs = elapsedStabMs,
                    lockTimeGateMs = LOCK_TIME_GATE_NS / 1_000_000L,
                    primary = primary,
                    secondary = secondary
                )
            )
        }

        val sigmaOkChanged = cupLockDiagLastSigmaOk != sigmaOk
        cupLockDiagLastSigmaOk = sigmaOk
        if (sigmaOkChanged || tsMs - cupLockDiagTimelineLogMs >= CupLockDiagnostics.SIGMA_TIMELINE_MIN_INTERVAL_MS) {
            cupLockDiagTimelineLogMs = tsMs
            Log.i(
                "CUP_LOCK_DIAG",
                CupLockDiagnostics.formatTimelineLine(
                    elapsedMs = elapsedStabMs,
                    sigmaUsedM = if (sigmaComputed) sigmaUsed else null,
                    sigmaMaxM = if (sigmaComputed) sigmaMax else null,
                    sigmaOk = sigmaOk,
                    consecutiveOk = sigmaOkConsecutive,
                    projectedCupPx = sample.gridProjectedCupPx,
                    validSampleCount = sample.validHits
                )
            )
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

    /** JSON/로그: [tx,ty,tz,qw,qx,qy,qz] */
    private fun poseToFloatArray7(p: Pose): FloatArray {
        val q = FloatArray(4)
        p.getRotationQuaternion(q, 0)
        return floatArrayOf(p.tx(), p.ty(), p.tz(), q[0], q[1], q[2], q[3])
    }

    private fun world3FromPose(p: Pose): FloatArray = floatArrayOf(p.tx(), p.ty(), p.tz())

    /**
     * 컵 중심 레이를 유지한 채 우하단 sector ground 후보로 local plane을 잡고, 재투영한 월드 점을 반환.
     * 실패 시 [legacyWorld] 그대로. (offset anchor 월드는 최종 wp로 쓰이지 않음)
     */
    private fun applyOffsetAnchorIfEnabled(frame: Frame, roi: RectF, legacyWorld: FloatArray): FloatArray {
        val (world, diag) =
            CupOffsetAnchorEstimator.resolveFinalCupWorldPointWithThrottle(
                state = cupOffsetAnchorCacheState,
                frame = frame,
                sampler = sampler,
                cupCenterPx = PointF(roi.centerX(), roi.centerY()),
                projectedCupPx = lastMultiRayProjectedCupPx ?: 40f,
                centerWorldLegacy = legacyWorld,
                trackingState = frame.camera.trackingState,
                rayDirYMin = LIVE_RAYDIR_Y_EPS,
                maxRayDistanceM = LIVE_MAX_HIT_DISTANCE_M,
                distanceM = fixedDEstMeters,
                farMode = isFarMode(fixedDEstMeters, lastMultiRayProjectedCupPx, lastMultiRayPlan)
            )
        lastCupOffsetAnchorDiag = diag
        return world
    }

    /**
     * 컵 END anchor freeze용 live world.
     * [lastLiveCupWorldForDistance]가 있고, 갱신이 [CUP_LIVE_WORLD_MAX_STALE_NS] 이내일 때만 허용.
     */
    private fun cupLiveWorldEligibleForEndCommit(nowNs: Long): FloatArray? {
        val w = lastLiveCupWorldForDistance ?: return null
        if (lastLiveCupWorldUpdateNs <= 0L) return null
        if (nowNs - lastLiveCupWorldUpdateNs > CUP_LIVE_WORLD_MAX_STALE_NS) return null
        return w.copyOf()
    }

    /** 멀티레이 대표 히트: LIVE 컵 월드가 있으면 [V31HitSampler]에서 XZ 정렬 선택 */
    private fun cupLiveAlignForMultiRaySample(): FloatArray? =
        lastLiveCupWorldForDistance?.copyOf()

    private fun anchorHorizontalDistanceMeters(): Float? {
        val a = startAnchor?.pose ?: return null
        val b = endAnchor?.pose ?: return null
        val dx = b.tx() - a.tx()
        val dz = b.tz() - a.tz()
        return sqrt(dx * dx + dz * dz)
    }

    private fun anchor3dDistanceMeters(): Float? {
        val a = startAnchor?.pose ?: return null
        val b = endAnchor?.pose ?: return null
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

    /** slope 파이프라인 공통: raw anchor pose → support surface / surrounding green 투영 */
    private fun computeSlopeInputProjectionForAnchors(): SlopeInputProjection.Result {
        val a = startAnchor!!.pose
        val b = endAnchor!!.pose
        val rawBall = floatArrayOf(a.tx(), a.ty(), a.tz())
        val rawCup = floatArrayOf(b.tx(), b.ty(), b.tz())
        val gpLike = groundPlaneModel?.let {
            SlopeInputProjection.GroundPlaneModelLike(
                floatArrayOf(it.pointOnPlane.x, it.pointOnPlane.y, it.pointOnPlane.z),
                floatArrayOf(it.normal.x, it.normal.y, it.normal.z)
            )
        }
        val cupN = cupPlaneNormal?.let { floatArrayOf(it.x, it.y, it.z) }
        return SlopeInputProjection.compute(rawBall, rawCup, gpLike, cupPlanePointFix, cupN)
    }

    private fun refreshBallGateSnapshot(nowNs: Long, tracking: TrackingState, sample: V31HitSampler.Sample?) {
        if (state != State.IDLE && state != State.AIM_START) return
        val trackingOk = tracking == TrackingState.TRACKING
        val gateState =
            when (state) {
                State.IDLE -> BallGateState.IDLE
                State.AIM_START -> BallGateState.AIM_START
                else -> return
            }
        val hit =
            when (state) {
                State.AIM_START -> ballEffectiveHitLastTick ?: sample?.bestHit
                State.IDLE -> sample?.bestHit
                else -> null
            }
        val dist = hit?.distance
        val requiredHits = currentBallFixNeedHits()
        val arSuccess = arWarmupWindow.count { it }
        val requiredWarmup =
            if (warmupSessionStartNs > 0L) {
                val elapsedMs = (System.nanoTime() - warmupSessionStartNs) / 1_000_000L
                if (elapsedMs < BALL_WARMUP_INITIAL_RELAX_MS) BALL_WARMUP_REQUIRED_HITS_INITIAL else BALL_WARMUP_REQUIRED_HITS
            } else {
                BALL_WARMUP_REQUIRED_HITS
            }
        val unstableDistance = sample?.ballSampleRejectionReason == "planeOutsidePolygon"
        lastBallGateSnapshot =
            BallGateSnapshot(
                gateState = gateState,
                trackingStateName = tracking.name,
                trackingOk = trackingOk,
                hit = hit,
                distanceFromCameraM = dist,
                minStartDistanceM = START_MIN_DISTANCE_M,
                startDistanceReady = startDistanceReady,
                arWarmupReady = arWarmupReady,
                arWarmupSuccessCount = arSuccess,
                arWarmupRequired = requiredWarmup,
                stableHits = ballFixHitsInWindow,
                requiredStableHits = requiredHits,
                jumpRejected = ballDiagJumpRejected == true,
                unstableDistance = unstableDistance,
                measurementMode =
                    if (isFirstMeasurementPending) BallMeasurementMode.NEW_MEASUREMENT else BallMeasurementMode.EDIT,
                previousBallPoseExists = startAnchor != null,
                previousCupPoseExists = endAnchor != null
            )
    }

    private fun logBallStartGateLine(didResetState: Boolean) {
        val s = lastBallGateSnapshot
        val finalReason = s?.let { decideBallBlockedReason(it) } ?: BallBlockedReason.NONE
        val line =
            buildString {
                append("mode=${s?.measurementMode?.name ?: "null"} ")
                append("didResetState=$didResetState ")
                append("trackingState=${s?.trackingStateName ?: "null"} ")
                append("trackingOk=${s?.trackingOk ?: false} ")
                append("hitFound=${s?.hit != null} ")
                append("distanceFromCameraM=${s?.distanceFromCameraM?.let { "%.4f".format(it) } ?: "null"} ")
                append("minStartDistanceM=${START_MIN_DISTANCE_M} ")
                append("startDistanceReady=${s?.startDistanceReady ?: false} ")
                append("arWarmupReady=${s?.arWarmupReady ?: false} ")
                append("arWarmupSuccessCount=${s?.arWarmupSuccessCount ?: 0} ")
                append("stableHits=${s?.stableHits ?: 0} ")
                append("requiredHits=${s?.requiredStableHits ?: 0} ")
                append("jumpRejected=${s?.jumpRejected ?: false} ")
                append("unstableDistance=${s?.unstableDistance ?: false} ")
                append("previousBallPoseExists=${s?.previousBallPoseExists ?: false} ")
                append("previousCupPoseExists=${s?.previousCupPoseExists ?: false} ")
                append("ballBlockedReasonFinal=${finalReason.name}")
            }
        Log.i("BALL_START_GATE", line)
    }

    private fun buildUi(
        nowNs: Long,
        tracking: TrackingState,
        sample: V31HitSampler.Sample? = null,
        flashLock: Boolean = false,
        flashFail: Boolean = false
    ): UiModel {
        refreshBallGateSnapshot(nowNs, tracking, sample)
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

        val slopeProjected: SlopeInputProjection.Result? =
            if ((state == State.END_LOCKED || state == State.RESULT) &&
                startAnchor != null && endAnchor != null) {
                computeSlopeInputProjectionForAnchors()
            } else null

        if (slopeProjected != null && debugLoggingEnabled && !slopeInputProjectionLogged) {
            slopeInputProjectionLogged = true
            val p = slopeProjected
            Log.d(
                "SLOPE_INPUT",
                "rawBall_m=(${p.rawBall[0]},${p.rawBall[1]},${p.rawBall[2]}) slopeBall_m=(${p.slopeBall[0]},${p.slopeBall[1]},${p.slopeBall[2]}) " +
                    "rawCup_m=(${p.rawCup[0]},${p.rawCup[1]},${p.rawCup[2]}) slopeCup_m=(${p.slopeCup[0]},${p.slopeCup[1]},${p.slopeCup[2]}) " +
                    "ballSrc=${p.ballProjectionSource} cupSrc=${p.cupProjectionSource} cupDepressionSuspected=${p.cupDepressionSuspected}"
            )
        }

        val slopeDebugInfo: SlopeDebugInfo? =
            if ((state == State.END_LOCKED || state == State.RESULT) &&
                startAnchor != null && endAnchor != null) {
                val a = startAnchor!!.pose
                val b = endAnchor!!.pose
                val ballPos = slopeProjected?.slopeBall ?: floatArrayOf(a.tx(), a.ty(), a.tz())
                val cupPos = slopeProjected?.slopeCup ?: floatArrayOf(b.tx(), b.ty(), b.tz())
                val ballNorm = groundPlaneModel?.normal?.let { floatArrayOf(it.x, it.y, it.z) }
                val cupNorm = cupPlaneNormal?.let { floatArrayOf(it.x, it.y, it.z) }
                val sd = SlopeComputer.compute(
                    ballPos = ballPos,
                    cupPos = cupPos,
                    ballNormalRaw = ballNorm,
                    cupNormalRaw = cupNorm,
                    isXyzMode = axisMode == AxisMode.XYZ,
                    trackingGood = tracking == TrackingState.TRACKING
                )
                if (!slopePhase1ResultLogged) {
                    slopePhase1ResultLogged = true
                    val deltaYVal = b.ty() - a.ty()
                    val deltaYProj = slopeProjected?.let { it.slopeCup[1] - it.slopeBall[1] }
                    Log.d(
                        "SLOPE_PHASE1",
                        "SLOPE_RESULT slopeInput=PLANE forwardPct=${sd.forwardPct?.let { "%.2f".format(it) } ?: "null"} lateralPct=${sd.lateralPct?.let { "%.2f".format(it) } ?: "null"} blocked=${sd.blockedReason ?: "none"} " +
                            "deltaYRaw_m=${"%.4f".format(deltaYVal)} deltaYProjected_m=${if (deltaYProj != null) "%.4f".format(deltaYProj) else "null"} " +
                            "ballNy=${ballNorm?.get(1)?.let { "%.3f".format(it) } ?: "null"} cupNy=${cupNorm?.get(1)?.let { "%.3f".format(it) } ?: "null"}"
                    )
                }
                sd
            } else {
                null
            }

        // Slope Input 2.0: Experimental (LocalSurfaceFit) 경로
        // v1: BALL_FIX/CUP_FIX 시점에 저장된 샘플로 계산. RESULT 시점 재샘플링 금지.
        val slopeExperimentalResult: SlopeInputResult? =
            if ((state == State.END_LOCKED || state == State.RESULT) &&
                startAnchor != null && endAnchor != null &&
                axisMode == AxisMode.XYZ && tracking == TrackingState.TRACKING) {
                val a = startAnchor!!.pose
                val b = endAnchor!!.pose
                val ballPos = slopeProjected?.slopeBall ?: floatArrayOf(a.tx(), a.ty(), a.tz())
                val cupPos = slopeProjected?.slopeCup ?: floatArrayOf(b.tx(), b.ty(), b.tz())
                val ballNorm = groundPlaneModel?.normal?.let { floatArrayOf(it.x, it.y, it.z) }
                val cupNorm = cupPlaneNormal?.let { floatArrayOf(it.x, it.y, it.z) }
                runCatching {
                    localSurfaceFitProvider.computeFromStoredSamples(
                        ballSamples = experimentalBallSlopeSamples,
                        cupSamples = experimentalCupSlopeSamples,
                        ballPos = ballPos,
                        cupPos = cupPos,
                        ballNormalFromPlane = ballNorm,
                        cupNormalFromPlane = cupNorm,
                        phase1ForwardPct = slopeDebugInfo?.forwardPct,
                        phase1LateralPct = slopeDebugInfo?.lateralPct,
                        distanceFromCameraCupM = sample?.bestHit?.distance,
                        multiRayProjectedCupPx = lastMultiRayProjectedCupPx,
                        testSessionId = slopeTestSessionId,
                        repeatIndex = slopeRepeatIndex,
                        targetScenario = slopeTargetScenario
                    )
                }.getOrElse { null }
            } else {
                null
            }

        // P3: all/trimmed/corridor 후보 중 1개 선택 + computeSharedOnly — experimental·phase1과 병렬, 덮어쓰기 없음.
        val sharedP3Outcome: SharedP3PlaneSelection.Outcome? =
            if ((state == State.END_LOCKED || state == State.RESULT) &&
                startAnchor != null && endAnchor != null &&
                axisMode == AxisMode.XYZ && tracking == TrackingState.TRACKING) {
                val ballPts = experimentalBallSlopeSamples?.points ?: emptyList()
                val cupPts = experimentalCupSlopeSamples?.points ?: emptyList()
                val a = startAnchor!!.pose
                val b = endAnchor!!.pose
                val ballPos = slopeProjected?.slopeBall ?: floatArrayOf(a.tx(), a.ty(), a.tz())
                val cupPos = slopeProjected?.slopeCup ?: floatArrayOf(b.tx(), b.ty(), b.tz())
                val out = SharedP3PlaneSelection.selectBest(
                    ballPoints = ballPts,
                    cupPoints = cupPts,
                    ballPos = ballPos,
                    cupPos = cupPos,
                    previousNormal = lastSharedP3NormalWorld
                )
                val sd = out.slope
                if (sd != null && sd.quality == "valid" && sd.forwardPct != null && sd.refNormal != null) {
                    val rn = sd.refNormal
                    val len = sqrt(rn[0] * rn[0] + rn[1] * rn[1] + rn[2] * rn[2])
                    if (len > 1e-5f) {
                        lastSharedP3NormalWorld = floatArrayOf(rn[0] / len, rn[1] / len, rn[2] / len)
                    }
                }
                if (!slopeSharedP3Logged) {
                    slopeSharedP3Logged = true
                    out.logPayload.emitLogcatLines()
                }
                out
            } else {
                null
            }
        val experimentalSharedSlope = sharedP3Outcome?.slope
        val sharedPlaneFitResidualM = sharedP3Outcome?.residualM
        val sharedPlaneSampleCount = sharedP3Outcome?.sampleCount
        val sharedP3Log = sharedP3Outcome?.logPayload

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
                State.IDLE -> trackingOk && arWarmupReady
                State.AIM_START -> trackingOk && ballFixHitsInWindow >= currentBallFixNeedHits() && startDistanceReady
                else -> false
            }

        // A. BALL enable 막힌 이유 — [decideBallBlockedReason] 단일 값 ([BallBlockedReason])
        val ballBlockedReason: BallBlockedReason? =
            when {
                startEnabled -> null
                state == State.IDLE || state == State.AIM_START -> {
                    val snap = lastBallGateSnapshot
                    if (snap == null) BallBlockedReason.INSUFFICIENT_STABLE_HITS
                    else {
                        val r = decideBallBlockedReason(snap)
                        if (r == BallBlockedReason.NONE) BallBlockedReason.INSUFFICIENT_STABLE_HITS else r
                    }
                }
                else -> null
            }
        if (!startEnabled && (state == State.IDLE || state == State.AIM_START)) {
            val throttleNs = 500_000_000L
            if (nowNs - lastBallEnableGateLogNs >= throttleNs) {
                lastBallEnableGateLogNs = nowNs
                val arSuccess = arWarmupWindow.count { it }
                val requiredHits = if (warmupSessionStartNs > 0L) {
                    val elapsedMs = (System.nanoTime() - warmupSessionStartNs) / 1_000_000L
                    if (elapsedMs < BALL_WARMUP_INITIAL_RELAX_MS) BALL_WARMUP_REQUIRED_HITS_INITIAL else BALL_WARMUP_REQUIRED_HITS
                } else BALL_WARMUP_REQUIRED_HITS
                val needHits = currentBallFixNeedHits()
                val planeRejected = sample?.ballSampleRejectionReason == "planeOutsidePolygon"
                val blockedReason = lastBallGateSnapshot?.let { decideBallBlockedReason(it).name } ?: "null"
                Log.d("BALL_ENABLE_GATE", "state=$state trackingOk=$trackingOk arWarmupReady=$arWarmupReady " +
                    "arWarmupSuccessCount=$arSuccess/$requiredHits warmupWindowSize=$BALL_WARMUP_WINDOW_FRAMES " +
                    "startDistanceReady=$startDistanceReady blockedReason=$blockedReason " +
                    "ballFixHitsInWindow=$ballFixHitsInWindow/needHits=$needHits " +
                    "startDistanceReady=$startDistanceReady currentStartDistanceM=${sample?.bestHit?.distance ?: "null"} " +
                    "jumpRejected=${ballDiagJumpRejected ?: false} planeRejectedByPolygon=$planeRejected")
                // B. unstableDistance / insufficientHitsWindow 구분 로그
                if (state == State.AIM_START) {
                    if (!startDistanceReady) Log.d("BALL_SAMPLE_REJECT", "reason=unstableDistance")
                    else if (ballFixHitsInWindow < needHits) Log.d("BALL_SAMPLE_REJECT", "reason=insufficientHitsWindow")
                }
            }
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

        // CUP blocked reason (debug only)
        val cupBlockedReason: String? = when {
            finishEnabled -> null
            state == State.AIM_END || state == State.FAIL -> {
                when {
                    !trackingOk -> "tracking_not_ready"
                    startAnchor == null -> "start_anchor_missing"
                    state == State.FAIL && !canRetryCupFromFail() -> "cannot_retry"
                    else -> {
                        val proj = sample?.gridProjectedCupPx
                        val projOk = proj != null && proj.isFinite() && proj >= CUP_AIM_READY_MIN_PROJECTED_PX
                        val hitsOk = (sample?.validHits ?: 0) >= 1
                        when {
                            !projOk -> "projected_px_small"
                            !hitsOk -> "valid_hits_insufficient"
                            else -> "unknown"
                        }
                    }
                }
            }
            else -> null
        }
        if (debugLoggingEnabled && !finishEnabled && (state == State.AIM_END || state == State.FAIL)) {
            val throttleNs = 500_000_000L
            if (nowNs - lastCupEnableGateLogNs >= throttleNs) {
                lastCupEnableGateLogNs = nowNs
                val proj = sample?.gridProjectedCupPx
                val aimReady = isCupAimReady(sample)
                Log.d("CUP_ENABLE_GATE", "state=$state trackingOk=$trackingOk startAnchorOk=${startAnchor != null} " +
                    "isCupAimReady=$aimReady projectedPx=${if (proj != null && proj.isFinite()) "%.1f".format(proj) else "null"} " +
                    "validHits=${sample?.validHits ?: 0} blockedReason=${cupBlockedReason ?: "none"}")
            }
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

        val liveSnapAvailUi =
            liveSource == LiveSource.PLANE_INTERSECTION &&
                centerHitValid == true &&
                liveRawMeters != null &&
                liveRawMeters!!.isFinite() &&
                liveSmoothedMeters.isFinite() &&
                liveSmoothedMeters > 0f
        val qualityPassedUi =
            vh >= CUP_LOCK_MIN_VALID_SAMPLES &&
                !((sample?.centerFallbackUsed == true) && vh < CUP_LOCK_FALLBACK_SAFE_MIN_SAMPLES)
        val farHoldUiStab =
            farModeHoldStartNs != 0L && (nowNs - farModeHoldStartNs) < FAR_MODE_EXTRA_HOLD_NS
        val eligibleLiveUiStab = cupLiveWorldEligibleForEndCommit(nowNs) != null

        val cupSigmaMarginCmVal =
            if (lastSigmaUsedMeters != null && lastSigmaMaxMeters != null &&
                lastSigmaUsedMeters!!.isFinite() && lastSigmaMaxMeters!!.isFinite()
            ) {
                (lastSigmaMaxMeters!! - lastSigmaUsedMeters!!) * 100f
            } else {
                null
            }
        val o = lastCupLockOutcome
        val terminalCupDiag = state == State.FAIL || state == State.RESULT || state == State.END_LOCKED
        val cupLockPrimaryUi =
            when {
                state == State.STABILIZING_END -> cupLockDiagLastPrimary
                terminalCupDiag -> o?.primaryReason
                else -> null
            }
        val cupLockSecondaryUi =
            when {
                state == State.STABILIZING_END -> cupLockDiagLastSecondary
                terminalCupDiag -> o?.secondaryReason
                else -> null
            }
        val cupLockOutcomeUi = if (terminalCupDiag) o?.outcome else null
        val cupMaxConsecUi =
            when {
                state == State.STABILIZING_END -> cupLockDiagMaxConsecutiveOk
                terminalCupDiag -> o?.maxConsecutiveOkReached ?: cupLockDiagMaxConsecutiveOk
                else -> null
            }
        val cupConsecReqUi =
            if (state == State.STABILIZING_END || terminalCupDiag) LOCK_CONSEC_TICKS else null
        val cupElapsedStabUi: Long? =
            when {
                state == State.STABILIZING_END && stabilizingEnterNs > 0L ->
                    ((nowNs - stabilizingEnterNs) / 1_000_000L).coerceAtLeast(0L)
                terminalCupDiag -> o?.elapsedTotalMs
                else -> null
            }
        val cupProjPxEndUi: Float? =
            when {
                state == State.STABILIZING_END -> sample?.gridProjectedCupPx ?: lastMultiRayProjectedCupPx
                terminalCupDiag -> o?.projectedCupPxAtEnd ?: sample?.gridProjectedCupPx ?: lastMultiRayProjectedCupPx
                else -> null
            }
        val cupValidEndUi: Int? =
            when {
                state == State.STABILIZING_END -> vh
                terminalCupDiag -> o?.validSampleCountAtEnd ?: vh
                else -> null
            }
        val cupSoftHoldUi: Boolean? =
            when {
                state == State.STABILIZING_END -> cupLockDiagSoftHoldTriggered
                terminalCupDiag -> o?.softHoldTriggered
                else -> null
            }
        val cupSoftLockUi: Boolean? =
            when {
                state == State.STABILIZING_END -> cupLockDiagSoftLockTriggered
                terminalCupDiag -> o?.softLockTriggered
                else -> null
            }
        val cupTrackingEndUi: String? =
            when {
                state == State.STABILIZING_END -> tracking.name
                terminalCupDiag -> o?.trackingStateEnd
                else -> null
            }
        val cupFarHoldUi: Boolean? =
            when {
                state == State.STABILIZING_END -> farHoldUiStab
                terminalCupDiag -> o?.farModeHoldActive
                else -> null
            }
        val cupQualityPassedUi: Boolean? =
            when {
                state == State.STABILIZING_END -> qualityPassedUi
                terminalCupDiag -> o?.qualityGuardPassed
                else -> null
            }
        val cupLiveSnapUi: Boolean? =
            when {
                state == State.STABILIZING_END -> liveSnapAvailUi
                terminalCupDiag -> o?.liveSnapshotAvailable
                else -> null
            }
        val cupEligibleUi: Boolean? =
            when {
                state == State.STABILIZING_END -> eligibleLiveUiStab
                terminalCupDiag -> o?.eligibleLiveCupWorldAvailable
                else -> null
            }

        val dOff = lastCupOffsetAnchorDiag

        val uiModel = UiModel(
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
            ballCupSamePlane = ballCupSamePlane,
            cupPlaneType = cupPlaneType,
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
            samplingPlanGrid = lastSamplingPlanGrid,
            samplingPlanHalfSpanPx = lastSamplingPlanHalfSpanPx,
            samplingPlanStepPx = lastSamplingPlanStepPx,
            samplingPlanTemporalFrames = lastSamplingPlanTemporalFrames,
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
            isMeasuringFlow = isMeasuringFlow,
            ballBlockedReason = ballBlockedReason,
            ballArWarmupSuccessCount = if (!startEnabled && (state == State.IDLE || state == State.AIM_START)) arWarmupWindow.count { it } else null,
            ballArWarmupRequired = if (!startEnabled && (state == State.IDLE || state == State.AIM_START)) {
                if (warmupSessionStartNs > 0L) {
                    val elapsedMs = (System.nanoTime() - warmupSessionStartNs) / 1_000_000L
                    if (elapsedMs < BALL_WARMUP_INITIAL_RELAX_MS) BALL_WARMUP_REQUIRED_HITS_INITIAL else BALL_WARMUP_REQUIRED_HITS
                } else BALL_WARMUP_REQUIRED_HITS
            } else null,
            cupBlockedReason = cupBlockedReason,
            slopeDebugInfo = slopeDebugInfo,
            trackingState = tracking.name,
            anchorDistanceMeters = if (startAnchor != null && endAnchor != null) distanceBetweenAnchorsMeters() else null,
            slopeInputSource = if (state == State.END_LOCKED || state == State.RESULT) "PLANE_BASED" else null,
            ballTrackableType = ballTrackableType,
            cupTrackableType = cupTrackableType,
            deltaYRaw = if (startAnchor != null && endAnchor != null) {
                val a = startAnchor!!.pose
                val b = endAnchor!!.pose
                b.ty() - a.ty()
            } else null,
            ballNormalSource = if (groundPlaneModel != null) "PLANE_CENTER_POSE" else null,
            cupNormalSource = if (cupPlaneNormal != null) "PLANE_CENTER_POSE" else null,
            slopeExperimentalResult = slopeExperimentalResult,
            experimentalSharedSlope = experimentalSharedSlope,
            sharedPlaneFitResidualM = sharedPlaneFitResidualM,
            sharedPlaneSampleCount = sharedPlaneSampleCount,
            sharedP3Log = sharedP3Log,
            distanceLockLiveSource = if (state == State.RESULT) capturedDistanceLockLiveSource else null,
            distanceLockLiveRawM = if (state == State.RESULT) capturedDistanceLockLiveRawM else null,
            distanceLockLiveSigmaM = if (state == State.RESULT) capturedDistanceLockLiveSigmaM else null,
            distanceLockLiveRangeM = if (state == State.RESULT) capturedDistanceLockLiveRangeM else null,
            distanceLockLiveStable = if (state == State.RESULT) capturedDistanceLockLiveStable else null,
            distanceFinalFallbackUsed = if (state == State.RESULT) capturedDistanceFinalFallbackUsed else false,
            distanceFinalSnapshotReason = if (state == State.RESULT) capturedDistanceFinalSnapshotReason else null,
            finalDistanceLivePlaneMeters = if (state == State.RESULT) capturedFinalDistanceLivePlaneMeters else null,
            finalDistanceSourceBeforeGuard = if (state == State.RESULT) capturedFinalDistanceSourceBeforeGuard else null,
            finalDistanceSourceAfterGuard = if (state == State.RESULT) capturedFinalDistanceSourceAfterGuard else null,
            finalDistanceGuardTriggered = if (state == State.RESULT) capturedFinalDistanceGuardTriggered else false,
            finalDistanceGuardReasons = if (state == State.RESULT) capturedFinalDistanceGuardReasons else null,
            finalDistanceAnchorInvalidReason = if (state == State.RESULT) capturedFinalDistanceAnchorInvalidReason else null,
            planeIntersectionVsAnchorDeltaM = if (state == State.RESULT) capturedPlaneVsAnchorDeltaM else null,
            planeIntersectionVsAnchorDeltaRatio = if (state == State.RESULT) capturedPlaneVsAnchorDeltaRatio else null,
            ballAnchorWorld = startAnchor?.pose?.let { world3FromPose(it) },
            cupAnchorWorld = endAnchor?.pose?.let { world3FromPose(it) },
            ballAnchorSourceState = if (startAnchor != null) "STABILIZING_START_LOCK" else null,
            cupAnchorSourceState = if (endAnchor != null) "STABILIZING_END_LOCK" else null,
            anchorHorizontalM = anchorHorizontalDistanceMeters(),
            anchor3dM = anchor3dDistanceMeters(),
            startAnchorCreatedAtMs = startAnchorCreatedAtMs,
            endAnchorCreatedAtMs = endAnchorCreatedAtMs,
            anchorCreatedAtMs = endAnchorCreatedAtMs,
            anchorReused = ballAnchorReplacedPrevious || cupAnchorReplacedPrevious,
            startAnchorPose = startAnchor?.pose?.let { poseToFloatArray7(it) },
            endAnchorPose = endAnchor?.pose?.let { poseToFloatArray7(it) },
            ballLiveHitWorldAtFinish =
                if (state == State.END_LOCKED || state == State.RESULT) capturedBallLiveHitWorldAtFinish else null,
            cupLiveHitWorldAtFinish =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupLiveHitWorldAtFinish else null,
            cupAnchorHitWorldBeforeSnap =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupAnchorHitWorldBeforeSnap else null,
            cupAnchorPoseWorldAfterSnap =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupAnchorPoseWorldAfterSnap else null,
            cupAnchorCommitTrackableType =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupAnchorCommitTrackableType else null,
            cupAnchorCommitTrackableId =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupAnchorCommitTrackableId else null,
            cupCandidateVsLiveHitXZDeltaMAtCommit =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorCommitGateDeltaM else null,
            cupEndAnchorCommitStrictFar =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorGateStrictFar else null,
            cupEndAnchorCommitGateThresholdM =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorGateThresholdM else null,
            cupEndAnchorGateBypassedMaxRetries =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorGateBypassedMaxRetries else null,
            cupEndAnchorCommitBypassSession =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorGateBypassedMaxRetries else null,
            cupLiveWorldFrameTimestampNs =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupLiveWorldFrameTimestampNs else null,
            cupEndAnchorPositionSource =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorPositionSource else null,
            cupEndAnchorVsLiveWorldXZM =
                if (state == State.END_LOCKED || state == State.RESULT) capturedCupEndAnchorVsLiveWorldXZM else null,
            slopeProjectionSnapshot = slopeProjected,
            deltaYProjected = slopeProjected?.let { it.slopeCup[1] - it.slopeBall[1] },
            cupLockPrimaryReason = cupLockPrimaryUi,
            cupLockSecondaryReason = cupLockSecondaryUi,
            cupLockOutcome = cupLockOutcomeUi,
            cupSigmaMarginCm = cupSigmaMarginCmVal,
            cupMaxConsecutiveOkReached = cupMaxConsecUi,
            cupConsecutiveRequired = cupConsecReqUi,
            cupElapsedStabilizingMs = cupElapsedStabUi,
            cupProjectedPxEnd = cupProjPxEndUi,
            cupValidSampleCountEnd = cupValidEndUi,
            cupSoftHoldTriggered = cupSoftHoldUi,
            cupSoftLockTriggered = cupSoftLockUi,
            cupTrackingStateEnd = cupTrackingEndUi,
            cupFarModeHoldActive = cupFarHoldUi,
            cupQualityGuardPassed = cupQualityPassedUi,
            cupLiveSnapshotAvailable = cupLiveSnapUi,
            cupEligibleLiveCupWorldAvailable = cupEligibleUi,
            cupOffsetMode = dOff?.mode,
            cupAnchorCandidateCount = dOff?.candidateCount,
            cupAnchorBestVarianceCm = dOff?.bestVarianceCm,
            cupAnchorBestResidualCm = dOff?.bestResidualCm,
            cupAnchorBestNormalY = dOff?.bestNormalY,
            cupAnchorBestOffsetDistCm = dOff?.bestOffsetDistCm,
            cupAnchorPlaneResidualCm = dOff?.planeResidualCm,
            cupAnchorReprojectSuccess = dOff?.reprojectSuccess,
            cupAnchorFailureReason = dOff?.failureReason,
            cupAnchorThrottleMode = dOff?.throttleMode,
            cupAnchorThrottleAgeMs = dOff?.throttleAgeMs,
            cupAnchorCacheHit = dOff?.cacheHit,
            cupAnchorLastFailureReason = dOff?.failureReason,
            cupAnchorCacheWasSuccess = dOff?.cacheWasSuccess,
            cupAnchorCameraMovedM = dOff?.cameraTranslationDeltaM,
            cupAnchorCameraAngleMovedDeg = dOff?.cameraAngleDeltaDeg,
            cupAnchorReprojectedAffectsDistance = dOff?.reprojectedAffectsDistance,
            cupAnchorReprojectedAffectsEndAnchor = dOff?.reprojectedAffectsEndAnchor,
            cupAnchorReprojectedAffectsExperimentalSurface = dOff?.reprojectedAffectsExperimentalSurface,
            cupAnchorDistanceFrameOfRef = dOff?.distanceFrameOfRef,
            cupAnchorEndAnchorFrameOfRef = dOff?.endAnchorFrameOfRef,
            cupAnchorExperimentalSurfaceFrameOfRef = dOff?.experimentalSurfaceFrameOfRef,
            cupAnchorFrameOfRefMismatch = dOff?.frameOfRefMismatch,
            cupAnchorFrameOfRefMismatchReason = dOff?.frameOfRefMismatchReason,
            cupAnchorQualityProbeStatus = dOff?.qualityProbeStatus,
            cupAnchorQualityInvalidateReason = dOff?.qualityInvalidateReason,
            cupAnchorSelectedOffsetCm = dOff?.selectedOffsetCm,
            cupAnchorSelectedOffsetScore = dOff?.selectedOffsetScore,
            cupAnchorSelectedOffsetRankReason = dOff?.selectedOffsetRankReason,
            cupAnchorEligibleMinCm = dOff?.cupAnchorEligibleMinCm,
            cupAnchorEligibleMaxCm = dOff?.cupAnchorEligibleMaxCm,
            cupAnchorLegacyDeltaCm = dOff?.cupAnchorLegacyDeltaCm
        )
        if (state == State.RESULT && lastEngineStateForSlopeFieldLog != State.RESULT) {
            SlopeFieldTestLog.emitOnResult(
                ui = uiModel,
                ballAnchored = startAnchor != null,
                cupAnchored = endAnchor != null
            )
        }
        lastEngineStateForSlopeFieldLog = state
        return uiModel
    }

    /**
     * LIVE/plane 후보 후 [FinalDistanceGuard] 적용. anchor 유효 + 위험 시 anchor fallback.
     */
    private fun commitFinalDistanceWithGuard() {
        val anchorM = distanceBetweenAnchorsMeters()
        val livePlaneCandidate =
            when {
                endLiveSnapshotMeters.isFinite() && endLiveSnapshotMeters > 0f -> endLiveSnapshotMeters
                lastDisplayDistanceMeters.isFinite() && lastDisplayDistanceMeters > 0f -> lastDisplayDistanceMeters
                else -> 0f
            }
        fun guardContextLine(phase: String, selFinal: Float?, selSource: String?, reasons: String?): String =
            "DISTANCE_GUARD_COMMIT phase=$phase liveDistanceM=${"%.6f".format(livePlaneCandidate)} " +
                "anchorDistanceM=${"%.6f".format(anchorM)} " +
                "selectedFinalDistanceM=${if (selFinal != null && selFinal.isFinite()) "%.6f".format(selFinal) else "pending"} " +
                "selectedSource=${selSource ?: "pending"} " +
                "guardReasons=${reasons ?: "pending"} " +
                "projectedCupPx=${lastMultiRayProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} " +
                "validSampleCount=${lastValidSampleCount ?: "null"} " +
                "multiRayPlan=${lastMultiRayPlan ?: "null"} " +
                "trackingState=$lastTickTrackingStateName"
        Log.d("DISTANCE_GUARD_COMMIT", guardContextLine("BEFORE", null, null, null))
        val res =
            FinalDistanceGuard.apply(
                endLiveSnapshotMeters = endLiveSnapshotMeters,
                lastDisplayDistanceMeters = lastDisplayDistanceMeters,
                anchorMeters = anchorM,
                anchorsBothPresent = startAnchor != null && endAnchor != null,
                projectedCupPx = lastMultiRayProjectedCupPx,
                liveStable = capturedDistanceLockLiveStable,
                finalFallbackUsed = capturedDistanceFinalFallbackUsed,
                ballCupPlaneAngleDeg = ballCupPlaneAngleDeg,
                cupEndAnchorFromLiveWorld = capturedCupEndAnchorPositionSource == "LIVE_DISTANCE_WORLD"
            )
        finalDistanceMeters = res.finalMeters
        capturedFinalDistanceLivePlaneMeters = res.livePlaneMeters
        capturedFinalDistanceSourceBeforeGuard = res.livePlaneBeforeSource
        capturedFinalDistanceSourceAfterGuard = res.sourceAfterGuard
        capturedFinalDistanceGuardTriggered = res.guardTriggered
        capturedFinalDistanceGuardReasons = res.reasonsJoined
        capturedFinalDistanceAnchorInvalidReason = res.anchorInvalidReason
        capturedPlaneVsAnchorDeltaM = res.deltaM
        capturedPlaneVsAnchorDeltaRatio = res.deltaRatio

        Log.d(
            "DISTANCE_GUARD_COMMIT",
            guardContextLine("AFTER", res.finalMeters, res.sourceAfterGuard, res.reasonsJoined)
        )
        Log.d(
            "DISTANCE_FINAL_GUARD",
            "anchorDistanceM=${"%.3f".format(anchorM)} livePlaneDistanceM=${"%.3f".format(res.livePlaneMeters)} " +
                "planeIntersectionVsAnchorDeltaM=${"%.3f".format(res.deltaM)} " +
                "planeIntersectionVsAnchorDeltaRatio=${"%.4f".format(res.deltaRatio)} " +
                "projectedCupPx=${lastMultiRayProjectedCupPx?.let { "%.1f".format(it) } ?: "null"} " +
                "finalDistanceSourceBeforeGuard=${res.livePlaneBeforeSource} finalDistanceSourceAfterGuard=${res.sourceAfterGuard} " +
                "finalDistanceGuardTriggered=${res.guardTriggered} finalDistanceGuardReasons=${res.reasonsJoined} " +
                "finalDistanceAnchorInvalidReason=${res.anchorInvalidReason ?: "none"} " +
                "cfg: maxDeltaM=${FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_M} " +
                "maxRatio=${FinalDistanceGuardConfig.MAX_PLANE_ANCHOR_DELTA_RATIO} minPx=${FinalDistanceGuardConfig.MIN_PROJECTED_CUP_PX}"
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

