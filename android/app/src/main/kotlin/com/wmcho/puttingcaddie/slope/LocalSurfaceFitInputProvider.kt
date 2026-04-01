package com.wmcho.puttingcaddie.slope

import android.graphics.PointF
import android.graphics.RectF
import com.google.ar.core.Frame
import com.wmcho.puttingcaddie.SlopeComputer
import com.wmcho.puttingcaddie.V31HitSampler
import kotlin.math.max
import kotlin.math.sqrt

/**
 * BALL_FIX/CUP_FIX 시점에 수집한 slope raw 샘플.
 * debug에서 어느 프레임/시점에서 모았는지 추적용.
 */
data class SlopeRawSamplesData(
    val points: List<FloatArray>,
    val sourceTypes: Set<String>,
    val collectedAtNs: Long? = null,
    val trackingStateAtSample: String? = null,
    /** gridHalfSteps=2(5x5)일 때 각 point의 화면 그리드 (dx,dy); points 와 동일 길이 */
    val gridOffsets: List<Pair<Int, Int>>? = null
)

/**
 * Slope Input 2.0: Local Surface Fit 기반 경사 입력.
 * 볼/컵 주변 다점 샘플 → local plane fitting → normal 추정.
 * v1: BALL_FIX/CUP_FIX 시점에 각각 수집, RESULT에서 저장된 샘플로 계산.
 */
class LocalSurfaceFitInputProvider(private val sampler: V31HitSampler) : SlopeInputProvider {
    override val sourceId: String = "LOCAL_SURFACE_FIT"

    private val WORLD_UP = floatArrayOf(0f, 1f, 0f)
    private val MIN_SAMPLES = 3
    private val GRID_STEP_PX = 18f
    private val MAX_FIT_RESIDUAL_M = 0.05f
    private val DRIFT_THRESHOLD_DEG = 8f  // SlopeComputer.PLANE_DRIFT_THRESHOLD_DEG와 동일 (계산식 미수정)
    private val SANITY_MAX_ABS_FORWARD_PCT = 12f
    private val SANITY_MAX_ABS_LATERAL_PCT = 15f
    private val SANITY_MAX_DIFF_FROM_PHASE1_PCT = 10f
    /** 완화 구간 비율: 이 안이면 DEGRADED(값 유지·저신뢰), 밖이면 REJECT */
    private val SANITY_SOFT_BAND_RATIO = 0.72f
    private val SPREAD_NOISE_THRESHOLD_M = 0.12f
    private val NORMAL_STD_DEGRADED_DEG = 6f
    private val NORMAL_STD_UNSTABLE_DEG = 12f
    /** 샘플 공간 퍼짐이 이 값 이상이면 평면 fit 이전에 입력 붕괴로 분리 reject */
    private val SPREAD_HARD_REJECT_M = 2.0f
    /** projectedCupPx가 알려진 경우 이 미만이면 experimental 신뢰 한계(초기 휴리스틱) */
    private val CUP_PX_TOO_SMALL_EXPERIMENTAL = 28f
    private val P2_WEIGHTED_TRIGGER_STD_DEG = 4f
    private val P2_WEIGHTED_TRIGGER_DRIFT_CANON_DEG = 15f

    override fun collect(
        ballPos: FloatArray,
        cupPos: FloatArray,
        frame: Frame?,
        roiScreen: RectF?,
        ballNormalFromPlane: FloatArray?,
        cupNormalFromPlane: FloatArray?,
        ballScreenCenter: PointF?,
        cupScreenCenter: PointF?,
        isXyzMode: Boolean,
        trackingGood: Boolean
    ): SlopeInputResult {
        if (frame == null || roiScreen == null || !isXyzMode || !trackingGood) {
            return rejected("frame_or_roi_or_mode_null")
        }

        // v1: ball/cup 둘 다 다점 샘플링. hit 우선순위: DepthPoint > Point > Plane
        val cupCx = cupScreenCenter?.x ?: sampler.projectWorldToScreenPoint(frame, cupPos[0], cupPos[1], cupPos[2])?.x ?: roiScreen.centerX()
        val cupCy = cupScreenCenter?.y ?: sampler.projectWorldToScreenPoint(frame, cupPos[0], cupPos[1], cupPos[2])?.y ?: roiScreen.centerY()
        val ballCx = ballScreenCenter?.x ?: sampler.projectWorldToScreenPoint(frame, ballPos[0], ballPos[1], ballPos[2])?.x ?: roiScreen.centerX()
        val ballCy = ballScreenCenter?.y ?: sampler.projectWorldToScreenPoint(frame, ballPos[0], ballPos[1], ballPos[2])?.y ?: roiScreen.centerY()

        // Cup 3x3 샘플링 (v1: hitTestWithTypePriority 사용)
        val cupPoints = mutableListOf<FloatArray>()
        val cupSourceTypes = mutableSetOf<String>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = cupCx + dx * GRID_STEP_PX
                val sy = cupCy + dy * GRID_STEP_PX
                val hit = sampler.hitTestWithTypePriority(frame, sx, sy)
                if (hit != null) {
                    cupPoints.add(floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()))
                    cupSourceTypes.add(hit.trackable?.javaClass?.simpleName ?: "Unknown")
                }
            }
        }

        // Ball 3x3 샘플링 (v1)
        val ballPoints = mutableListOf<FloatArray>()
        val ballSourceTypes = mutableSetOf<String>()
        for (dy in -1..1) {
            for (dx in -1..1) {
                val sx = ballCx + dx * GRID_STEP_PX
                val sy = ballCy + dy * GRID_STEP_PX
                val hit = sampler.hitTestWithTypePriority(frame, sx, sy)
                if (hit != null) {
                    ballPoints.add(floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()))
                    ballSourceTypes.add(hit.trackable?.javaClass?.simpleName ?: "Unknown")
                }
            }
        }

        if (cupPoints.size < MIN_SAMPLES) {
            return rejected("cup_samples_insufficient(${cupPoints.size})")
        }

        val (cupNormal, residualCup) = fitPlaneToPoints(cupPoints)
        if (cupNormal == null) {
            return rejected("cup_plane_fit_failed")
        }
        if (residualCup != null && residualCup > MAX_FIT_RESIDUAL_M) {
            return rejected("cup_residual_too_large(${"%.4f".format(residualCup)})")
        }

        // v1: ball도 local fit. 부족하면 Plane fallback
        val ballNormal: FloatArray?
        val residualBall: Float?
        val ballInputSourceStr: String?
        if (ballPoints.size >= MIN_SAMPLES) {
            val (bn, rb) = fitPlaneToPoints(ballPoints)
            if (bn != null && (rb == null || rb <= MAX_FIT_RESIDUAL_M)) {
                ballNormal = bn
                residualBall = rb
                ballInputSourceStr = dominantSourceType(ballSourceTypes)
            } else {
                ballNormal = ballNormalFromPlane
                residualBall = null
                ballInputSourceStr = "Plane(fallback)"
            }
        } else {
            ballNormal = ballNormalFromPlane
            residualBall = null
            ballInputSourceStr = "Plane(fallback)"
        }
        if (ballNormal == null) {
            return rejected("ball_normal_missing_use_plane")
        }

        val cupInputSourceStr = dominantSourceType(cupSourceTypes)
        val allSourceTypes = (cupSourceTypes + ballSourceTypes).joinToString(",")

        val sd = SlopeComputer.compute(
            ballPos = ballPos,
            cupPos = cupPos,
            ballNormalRaw = ballNormal,
            cupNormalRaw = cupNormal,
            isXyzMode = true,
            trackingGood = true
        )

        val totalSamples = ballPoints.size + cupPoints.size
        val totalGrid = 18f
        return SlopeInputResult(
            ballNormal = sd.ballNormal,
            cupNormal = sd.cupNormal,
            refNormal = sd.refNormal,
            forwardPct = sd.forwardPct,
            lateralPct = sd.lateralPct,
            quality = sd.quality,
            rejectReason = sd.blockedReason,
            sourceId = sourceId,
            sampleCountBall = ballPoints.size,
            sampleCountCup = cupPoints.size,
            validSampleRatio = totalSamples / totalGrid,
            fitResidualBall = residualBall,
            fitResidualCup = residualCup,
            sampleSourceTypes = allSourceTypes,
            ballInputSource = ballInputSourceStr,
            cupInputSource = cupInputSourceStr
        )
    }

    private fun jackknifePlaneNormalStats(points: List<FloatArray>): Triple<FloatArray?, Float?, Int> {
        return ExperimentalSlopeMath.jackknifePlaneNormalMeanStdDeg(points) { sub ->
            fitPlaneToPoints(sub).first
        }
    }

    /** 5x5 수집 시 중앙 3x3에 해당하는 점만 (gridOffsets 기준 |dx|,|dy|≤1) */
    private fun cupCenterSubset(samples: SlopeRawSamplesData?): List<FloatArray> {
        val pts = samples?.points ?: return emptyList()
        val off = samples?.gridOffsets
        if (off == null || off.size != pts.size) return pts
        return pts.filterIndexed { i, _ ->
            val (dx, dy) = off[i]
            max(kotlin.math.abs(dx), kotlin.math.abs(dy)) <= 1
        }
    }

    /** WLS 실패 시 포인트 복제 근사 (P2 백업) */
    private fun fitPlaneWeightedApprox(points: List<FloatArray>, weights: List<Float>): Pair<FloatArray?, Float?> {
        if (points.size < 3 || points.size != weights.size) return Pair(null, null)
        val expanded = mutableListOf<FloatArray>()
        for (i in points.indices) {
            val reps = (weights[i] * 20f).toInt().coerceIn(1, 40)
            repeat(reps) { expanded.add(points[i]) }
        }
        return fitPlaneToPoints(expanded)
    }

    private fun fitCupWeightedPrimary(points: List<FloatArray>, weights: List<Float>): Pair<FloatArray?, Float?> {
        val wls = ExperimentalSlopeMath.fitPlaneWeightedWls(points, weights)
        if (wls.first != null) return wls
        return fitPlaneWeightedApprox(points, weights)
    }

    private fun fitPlaneToPoints(points: List<FloatArray>): Pair<FloatArray?, Float?> {
        if (points.size < 3) return Pair(null, null)
        val c = centroid(points)
        val centered = points.map { floatArrayOf(it[0] - c[0], it[1] - c[1], it[2] - c[2]) }
        val n = planeNormalFromPoints(centered)
        if (n == null) return Pair(null, null)
        val residual = meanDistanceToPlane(points, c, n)
        return Pair(n, residual)
    }

    private fun centroid(points: List<FloatArray>): FloatArray {
        val n = points.size.toFloat()
        var sx = 0f; var sy = 0f; var sz = 0f
        for (p in points) {
            sx += p[0]; sy += p[1]; sz += p[2]
        }
        return floatArrayOf(sx / n, sy / n, sz / n)
    }

    private fun planeNormalFromPoints(centered: List<FloatArray>): FloatArray? {
        if (centered.size < 3) return null
        val n = centered.size
        val p0 = centered[0]
        val p1 = centered[n / 2]
        val p2 = centered[n - 1]
        val v1 = floatArrayOf(p1[0] - p0[0], p1[1] - p0[1], p1[2] - p0[2])
        val v2 = floatArrayOf(p2[0] - p0[0], p2[1] - p0[1], p2[2] - p0[2])
        val cx = v1[1] * v2[2] - v1[2] * v2[1]
        val cy = v1[2] * v2[0] - v1[0] * v2[2]
        val cz = v1[0] * v2[1] - v1[1] * v2[0]
        val len = sqrt(cx * cx + cy * cy + cz * cz)
        if (len < 1e-6f) return null
        var nx = cx / len; var ny = cy / len; var nz = cz / len
        if (ny < 0) { nx = -nx; ny = -ny; nz = -nz }
        return floatArrayOf(nx, ny, nz)
    }

    private fun meanDistanceToPlane(points: List<FloatArray>, origin: FloatArray, normal: FloatArray): Float {
        var sum = 0f
        for (p in points) {
            val d = (p[0] - origin[0]) * normal[0] + (p[1] - origin[1]) * normal[1] + (p[2] - origin[2]) * normal[2]
            sum += kotlin.math.abs(d)
        }
        return sum / points.size
    }

    /** v1: DepthPoint > Point > Plane 우선순위로 dominant 타입 반환 */
    private fun dominantSourceType(types: Set<String>): String {
        val u = types.map { it.replace("com.google.ar.core.", "") }
        if (u.any { it.endsWith("DepthPoint") }) return "DepthPoint"
        if (u.any { it.endsWith("Point") && !it.contains("Depth") }) return "Point"
        if (u.any { it.endsWith("Plane") }) return "Plane"
        return types.firstOrNull() ?: "Unknown"
    }

    /**
     * BALL_FIX/CUP_FIX 시점 화면 그리드 hitTest.
     * @param gridHalfSteps 1 → 3x3, 2 → 5x5 (experimental cup 확장용)
     */
    fun collectSamplesAtCenter(
        frame: Frame,
        roiScreen: RectF,
        screenCenter: PointF,
        collectedAtNs: Long? = null,
        gridHalfSteps: Int = 1
    ): SlopeRawSamplesData {
        val points = mutableListOf<FloatArray>()
        val offsets = mutableListOf<Pair<Int, Int>>()
        val sourceTypes = mutableSetOf<String>()
        val span = gridHalfSteps.coerceIn(1, 2)
        for (dy in -span..span) {
            for (dx in -span..span) {
                val sx = screenCenter.x + dx * GRID_STEP_PX
                val sy = screenCenter.y + dy * GRID_STEP_PX
                val hit = sampler.hitTestWithTypePriority(frame, sx, sy)
                if (hit != null) {
                    points.add(floatArrayOf(hit.hitPose.tx(), hit.hitPose.ty(), hit.hitPose.tz()))
                    offsets.add(dx to dy)
                    sourceTypes.add(hit.trackable?.javaClass?.simpleName ?: "Unknown")
                }
            }
        }
        return SlopeRawSamplesData(
            points = points,
            sourceTypes = sourceTypes,
            collectedAtNs = collectedAtNs,
            trackingStateAtSample = frame.camera.trackingState.name,
            gridOffsets = if (offsets.isNotEmpty()) offsets else null
        )
    }

    /**
     * v1: BALL_FIX/CUP_FIX 시점에 저장된 샘플로 local fit 후 slope 계산.
     * frame 없이 저장된 world 포인트만 사용.
     */
    @Suppress("LongMethod")
    fun computeFromStoredSamples(
        ballSamples: SlopeRawSamplesData?,
        cupSamples: SlopeRawSamplesData?,
        ballPos: FloatArray,
        cupPos: FloatArray,
        ballNormalFromPlane: FloatArray?,
        cupNormalFromPlane: FloatArray?,
        phase1ForwardPct: Float? = null,
        phase1LateralPct: Float? = null,
        distanceFromCameraCupM: Float? = null,
        multiRayProjectedCupPx: Float? = null,
        testSessionId: String? = null,
        repeatIndex: Int? = null,
        targetScenario: String? = null
    ): SlopeInputResult {
        val trace = mutableListOf<String>()
        fun stage(s: String) {
            trace.add(s)
        }

        val cupPoints = cupSamples?.points ?: emptyList()
        val ballPoints = ballSamples?.points ?: emptyList()
        val ballTsMs = ballSamples?.collectedAtNs?.let { it / 1_000_000 }
        val cupTsMs = cupSamples?.collectedAtNs?.let { it / 1_000_000 }
        val cupSpreadM = ExperimentalSlopeMath.spreadExtentM(cupPoints)
        val ballSpreadM = ExperimentalSlopeMath.spreadExtentM(ballPoints)
        val (cupBmin, cupBmax) = ExperimentalSlopeMath.bbox(cupPoints)
        val (ballBmin, ballBmax) = ExperimentalSlopeMath.bbox(ballPoints)

        val (residualThreshold, residualMode) = residualThresholdInitialHeuristic(
            projectedCupPx = multiRayProjectedCupPx,
            sampleCountCup = cupPoints.size,
            spreadM = cupSpreadM
        )

        if (cupPoints.size < MIN_SAMPLES) {
            stage("cup_fit")
            return rejectedWithContext(
                reason = "cup_samples_insufficient(${cupPoints.size})",
                trace = trace,
                lastStage = "cup_fit",
                preReject = "cup_samples_insufficient",
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        if (cupSpreadM >= SPREAD_HARD_REJECT_M) {
            stage("spread_gate")
            return rejectedWithContext(
                reason = "cup_sample_spread_too_large(${"%.3f".format(cupSpreadM)})",
                trace = trace,
                lastStage = "spread_gate",
                preReject = "cup_sample_spread_too_large",
                residualType = "NOISE_SPREAD_HIGH",
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                cupBmin = cupBmin,
                cupBmax = cupBmax,
                ballBmin = ballBmin,
                ballBmax = ballBmax,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        if (multiRayProjectedCupPx != null && multiRayProjectedCupPx < CUP_PX_TOO_SMALL_EXPERIMENTAL) {
            stage("px_gate")
            return rejectedWithContext(
                reason = "cup_px_too_small_for_experimental(${"%.1f".format(multiRayProjectedCupPx)})",
                trace = trace,
                lastStage = "px_gate",
                preReject = "cup_px_too_small_for_experimental",
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                cupBmin = cupBmin,
                cupBmax = cupBmax,
                ballBmin = ballBmin,
                ballBmax = ballBmax,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        val cupCenterPts = cupCenterSubset(cupSamples)
        val cupFitFirst = when {
            cupCenterPts.size >= MIN_SAMPLES -> cupCenterPts
            cupPoints.size >= MIN_SAMPLES -> cupPoints
            else -> cupPoints
        }
        stage("cup_fit")
        var cupFitResult = fitPlaneToPoints(cupFitFirst)
        var cupNormalRaw = cupFitResult.first
        var residualCup = cupFitResult.second
        if (cupNormalRaw == null) {
            stage("cup_fit_failed")
            return rejectedWithContext(
                reason = "cup_plane_fit_failed",
                trace = trace,
                lastStage = "cup_fit",
                preReject = "cup_plane_fit_failed",
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        val residualTooLarge = residualCup != null && residualCup > residualThreshold
        if (residualTooLarge) {
            stage("cup_residual_gate")
            val resType = "FIT_UNSTABLE"
            return rejectedWithContext(
                reason = "cup_residual_too_large(${"%.4f".format(residualCup)})",
                trace = trace,
                lastStage = "cup_residual_gate",
                preReject = "cup_residual_too_large",
                residualType = resType,
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                fitResidualCup = residualCup,
                fitResidualBall = null,
                cupNormalRaw = cupNormalRaw,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                cupBmin = cupBmin,
                cupBmax = cupBmax,
                ballBmin = ballBmin,
                ballBmax = ballBmax,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        stage("ball_fit")
        val ballNormalRawFit: FloatArray?
        val residualBall: Float?
        val ballInputSourceStr: String
        if (ballPoints.size >= MIN_SAMPLES) {
            val (bn, rb) = fitPlaneToPoints(ballPoints)
            if (bn != null && (rb == null || rb <= residualThreshold)) {
                ballNormalRawFit = bn
                residualBall = rb
                ballInputSourceStr = ballSamples?.sourceTypes?.let { dominantSourceType(it) } ?: "Unknown"
            } else {
                ballNormalRawFit = ballNormalFromPlane
                residualBall = rb
                ballInputSourceStr = "Plane(fallback)"
            }
        } else {
            ballNormalRawFit = ballNormalFromPlane
            residualBall = null
            ballInputSourceStr = "Plane(fallback)"
        }
        if (ballNormalRawFit == null) {
            stage("ball_normal_missing")
            return rejectedWithContext(
                reason = "ball_normal_missing_use_plane",
                trace = trace,
                lastStage = "ball_fit",
                preReject = "ball_normal_missing",
                ballPoints = ballPoints,
                cupPoints = cupPoints,
                ballSamples = ballSamples,
                cupSamples = cupSamples,
                ballTsMs = ballTsMs,
                cupTsMs = cupTsMs,
                fitResidualCup = residualCup,
                cupNormalRaw = cupNormalRaw,
                cupSpreadM = cupSpreadM,
                residualThreshold = residualThreshold,
                residualMode = residualMode,
                distanceFromCameraCupM = distanceFromCameraCupM,
                multiRayProjectedCupPx = multiRayProjectedCupPx,
                testSessionId = testSessionId,
                repeatIndex = repeatIndex,
                targetScenario = targetScenario
            )
        }

        val cupInputSourceStr = cupSamples?.sourceTypes?.let { dominantSourceType(it) } ?: "Unknown"
        val allSourceTypes = ((cupSamples?.sourceTypes ?: emptySet()) + (ballSamples?.sourceTypes ?: emptySet())).joinToString(",")

        // P2: 트리거용 probe (center cup 기준) + 조건부 cup 전체 5x5 weighted 재피트
        val (_, preCupStdProbe, _) = jackknifePlaneNormalStats(cupFitFirst)
        val (_, ballStdProbe, _) = jackknifePlaneNormalStats(ballPoints)
        val ballRefProbe = ballNormalFromPlane ?: WORLD_UP
        val ballCanonProbe = ExperimentalSlopeMath.canonicalizeBallNormal(ballNormalRawFit, WORLD_UP, ballRefProbe)
        val cupRefProbe = cupNormalFromPlane ?: ballCanonProbe.canonical
        val cupCanonProbe = ExperimentalSlopeMath.canonicalizeCupNormal(
            cupNormalRaw, WORLD_UP, cupRefProbe, ballCanonProbe.canonical
        )
        val driftCanonicalProbe =
            ExperimentalSlopeMath.angleDeg(ballCanonProbe.canonical, cupCanonProbe.canonical)
        val pxOk = multiRayProjectedCupPx == null || multiRayProjectedCupPx >= CUP_PX_TOO_SMALL_EXPERIMENTAL
        val has5x5Grid = cupSamples?.gridOffsets != null && cupSamples.gridOffsets.size == cupPoints.size &&
            cupPoints.size >= MIN_SAMPLES
        val wantWeighted =
            pxOk && has5x5Grid &&
                ((ballStdProbe != null && ballStdProbe > P2_WEIGHTED_TRIGGER_STD_DEG) ||
                    (preCupStdProbe != null && preCupStdProbe > P2_WEIGHTED_TRIGGER_STD_DEG) ||
                    driftCanonicalProbe > P2_WEIGHTED_TRIGGER_DRIFT_CANON_DEG)
        var weightedFitAppliedToCup = false
        if (wantWeighted && cupSamples?.gridOffsets != null && cupSamples.gridOffsets.size == cupPoints.size) {
            val wts = cupPoints.indices.map { i ->
                val (dx, dy) = cupSamples.gridOffsets!![i]
                ExperimentalSlopeMath.weightFor5x5(dx, dy)
            }
            val (wn, wr) = fitCupWeightedPrimary(cupPoints, wts)
            if (wn != null) {
                cupNormalRaw = wn
                residualCup = wr
                weightedFitAppliedToCup = true
                if (wr != null && wr > residualThreshold) {
                    stage("cup_residual_gate_weighted")
                    return rejectedWithContext(
                        reason = "cup_residual_too_large(${"%.4f".format(residualCup)})",
                        trace = trace,
                        lastStage = "cup_residual_gate_weighted",
                        preReject = "cup_residual_too_large",
                        residualType = "FIT_UNSTABLE",
                        ballPoints = ballPoints,
                        cupPoints = cupPoints,
                        ballSamples = ballSamples,
                        cupSamples = cupSamples,
                        ballTsMs = ballTsMs,
                        cupTsMs = cupTsMs,
                        fitResidualCup = residualCup,
                        cupNormalRaw = cupNormalRaw,
                        ballNormalRaw = ballNormalRawFit,
                        cupSpreadM = cupSpreadM,
                        residualThreshold = residualThreshold,
                        residualMode = residualMode,
                        distanceFromCameraCupM = distanceFromCameraCupM,
                        multiRayProjectedCupPx = multiRayProjectedCupPx,
                        cupBmin = cupBmin,
                        cupBmax = cupBmax,
                        ballBmin = ballBmin,
                        ballBmax = ballBmax,
                        testSessionId = testSessionId,
                        repeatIndex = repeatIndex,
                        targetScenario = targetScenario,
                        experimentalSamplingPlanOverride = "5x5_weighted",
                        experimentalBallSamplingPlanOverride = "3x3",
                        experimentalWeightedFitOverride = true,
                        weightedFitAppliedToCupOverride = true,
                        experimentalSampleGridHalfSpanPxOverride = 2f * GRID_STEP_PX
                    )
                }
            }
        }
        val samplingPlanLabel = when {
            weightedFitAppliedToCup -> "5x5_weighted"
            cupSamples?.gridOffsets != null -> "5x5_center"
            else -> "3x3"
        }
        val gridHalfSpanPx = if (cupSamples?.gridOffsets != null) 2f * GRID_STEP_PX else GRID_STEP_PX

        val driftRawDeg = ExperimentalSlopeMath.angleDeg(ballNormalRawFit, cupNormalRaw)
        val ballRef = ballNormalFromPlane ?: WORLD_UP
        val ballCanonResult =
            ExperimentalSlopeMath.canonicalizeBallNormal(ballNormalRawFit, WORLD_UP, ballRef)
        val ballCan = ballCanonResult.canonical
        val cupRef = cupNormalFromPlane ?: ballCan
        val cupCanonResult =
            ExperimentalSlopeMath.canonicalizeCupNormal(cupNormalRaw, WORLD_UP, cupRef, ballCan)
        val cupCan = cupCanonResult.canonical
        val driftCanonicalDeg = ExperimentalSlopeMath.angleDeg(ballCan, cupCan)
        val driftType = ExperimentalSlopeMath.classifyDriftType(driftRawDeg, driftCanonicalDeg, DRIFT_THRESHOLD_DEG)
        val ballToCupDot = ExperimentalSlopeMath.dot(ExperimentalSlopeMath.normalize(ballNormalRawFit), ExperimentalSlopeMath.normalize(cupNormalRaw))
        val flipBall = ballCanonResult.flippedByWorldUp || ballCanonResult.flippedByRef
        val flipCup =
            cupCanonResult.flippedByWorldUp || cupCanonResult.flippedByRef || cupCanonResult.flippedByBallAlign

        stage("drift_gate")
        val sd = SlopeComputer.compute(
            ballPos = ballPos,
            cupPos = cupPos,
            ballNormalRaw = ballCan,
            cupNormalRaw = cupCan,
            isXyzMode = true,
            trackingGood = true
        )

        val (ballNormalMean, ballNormalStdDeg, ballNormalCandidateCount) = jackknifePlaneNormalStats(ballPoints)
        val cupJackknifePts = if (weightedFitAppliedToCup) cupPoints else cupFitFirst
        val (cupNormalMean, cupNormalStdDeg, cupNormalCandidateCount) = jackknifePlaneNormalStats(cupJackknifePts)
        val preWeightedCupStdDeg = if (cupSamples?.gridOffsets != null) preCupStdProbe else null
        val normalStabilityStatus: String? =
            when {
                ballNormalStdDeg == null || cupNormalStdDeg == null -> null
                ballNormalStdDeg > NORMAL_STD_UNSTABLE_DEG || cupNormalStdDeg > NORMAL_STD_UNSTABLE_DEG ->
                    "UNSTABLE"
                ballNormalStdDeg > NORMAL_STD_DEGRADED_DEG || cupNormalStdDeg > NORMAL_STD_DEGRADED_DEG ->
                    "DEGRADED"
                else -> "OK"
            }
        stage("normal_std_observed")

        var forwardOut = sd.forwardPct
        var lateralOut = sd.lateralPct
        var qualityOut = sd.quality
        var rejectOut = sd.blockedReason
        if (rejectOut.isNullOrBlank()) {
            when {
                ballNormalStdDeg != null && ballNormalStdDeg > NORMAL_STD_UNSTABLE_DEG -> {
                    rejectOut = "ball_normal_std_too_large"
                    stage("normal_std_gate_reject")
                }
                cupNormalStdDeg != null && cupNormalStdDeg > NORMAL_STD_UNSTABLE_DEG -> {
                    rejectOut = "cup_normal_std_too_large"
                    stage("normal_std_gate_reject")
                }
            }
        }
        if (rejectOut == "ball_normal_std_too_large" || rejectOut == "cup_normal_std_too_large") {
            forwardOut = null
            lateralOut = null
            qualityOut = "rejected"
        }
        var sanityStatus: String? = null
        var sanityReason: String? = null
        val expForwardRawPct = sd.forwardPct
        val expLateralRawPct = sd.lateralPct

        if (rejectOut == "plane_drift_too_large") {
            stage("drift_gate_reject")
        } else if (rejectOut.isNullOrBlank() && (forwardOut != null || lateralOut != null)) {
            stage("sanity_gate")
            val fw = forwardOut ?: 0f
            val lat = lateralOut ?: 0f
            val p1f = phase1ForwardPct
            val p1l = phase1LateralPct
            val hard = mutableListOf<String>()
            val soft = mutableListOf<String>()
            val af = kotlin.math.abs(fw)
            val al = kotlin.math.abs(lat)
            if (af > SANITY_MAX_ABS_FORWARD_PCT) hard.add("abs_forward>${SANITY_MAX_ABS_FORWARD_PCT}")
            else if (af > SANITY_MAX_ABS_FORWARD_PCT * SANITY_SOFT_BAND_RATIO) soft.add("abs_forward_soft")
            if (al > SANITY_MAX_ABS_LATERAL_PCT) hard.add("abs_lateral>${SANITY_MAX_ABS_LATERAL_PCT}")
            else if (al > SANITY_MAX_ABS_LATERAL_PCT * SANITY_SOFT_BAND_RATIO) soft.add("abs_lateral_soft")
            if (p1f != null) {
                val df = kotlin.math.abs(fw - p1f)
                when {
                    df > SANITY_MAX_DIFF_FROM_PHASE1_PCT -> hard.add("delta_forward_vs_phase1")
                    df > SANITY_MAX_DIFF_FROM_PHASE1_PCT * SANITY_SOFT_BAND_RATIO -> soft.add("delta_forward_vs_phase1_soft")
                }
            }
            if (p1l != null) {
                val dl = kotlin.math.abs(lat - p1l)
                when {
                    dl > SANITY_MAX_DIFF_FROM_PHASE1_PCT -> hard.add("delta_lateral_vs_phase1")
                    dl > SANITY_MAX_DIFF_FROM_PHASE1_PCT * SANITY_SOFT_BAND_RATIO -> soft.add("delta_lateral_vs_phase1_soft")
                }
            }
            when {
                hard.isNotEmpty() -> {
                    sanityStatus = "REJECTED"
                    sanityReason = hard.joinToString(",")
                    rejectOut = "rejected_sanity_outlier"
                    qualityOut = "rejected"
                    forwardOut = null
                    lateralOut = null
                    stage("sanity_gate_reject")
                }
                soft.isNotEmpty() -> {
                    sanityStatus = "DEGRADED"
                    sanityReason = soft.joinToString(",")
                    stage("sanity_gate_degraded")
                }
                else -> {
                    sanityStatus = "OK"
                }
            }
        } else {
            sanityStatus = if (rejectOut != null) null else "OK"
        }

        val totalSamples = ballPoints.size + cupPoints.size
        val diag = ExperimentalSlopeDiagnostics(
            rejectTrace = trace.toList(),
            lastRejectStage = trace.lastOrNull(),
            preRejectReason = rejectOut,
            residualType = null,
            driftRawDeg = driftRawDeg,
            driftCanonicalDeg = driftCanonicalDeg,
            driftType = driftType,
            ballLocalNormalRaw = ballNormalRawFit.copyOf(),
            cupLocalNormalRaw = cupNormalRaw.copyOf(),
            ballLocalNormalCanonical = ballCan.copyOf(),
            cupLocalNormalCanonical = cupCan.copyOf(),
            normalFlipAppliedBall = flipBall,
            normalFlipAppliedCup = flipCup,
            normalFlipByWorldUpBall = ballCanonResult.flippedByWorldUp,
            normalFlipByRefBall = ballCanonResult.flippedByRef,
            normalFlipByWorldUpCup = cupCanonResult.flippedByWorldUp,
            normalFlipByRefCup = cupCanonResult.flippedByRef,
            normalFlipByBallAlignCup = cupCanonResult.flippedByBallAlign,
            ballNormalMean = ballNormalMean?.copyOf(),
            cupNormalMean = cupNormalMean?.copyOf(),
            ballNormalCandidateCount = ballNormalCandidateCount,
            cupNormalCandidateCount = cupNormalCandidateCount,
            normalStabilityStatus = normalStabilityStatus,
            ballSampleBBoxMin = ballBmin,
            ballSampleBBoxMax = ballBmax,
            cupSampleBBoxMin = cupBmin,
            cupSampleBBoxMax = cupBmax,
            ballToCupNormalDot = ballToCupDot,
            ballNormalStdDeg = ballNormalStdDeg,
            cupNormalStdDeg = cupNormalStdDeg,
            cupResidualThresholdApplied = residualThreshold,
            cupResidualThresholdMode = residualMode,
            sampleSpreadCupM = cupSpreadM,
            distanceFromCameraCupM = distanceFromCameraCupM,
            multiRayProjectedCupPx = multiRayProjectedCupPx,
            sanityStatus = sanityStatus,
            sanityRejectReason = sanityReason,
            phase1ForwardPct = phase1ForwardPct,
            phase1LateralPct = phase1LateralPct,
            experimentalForwardPctRaw = expForwardRawPct,
            experimentalLateralPctRaw = expLateralRawPct,
            experimentalSamplingPlan = samplingPlanLabel,
            experimentalBallSamplingPlan = "3x3",
            experimentalSampleGridHalfSpanPx = gridHalfSpanPx,
            experimentalSampleStepPx = GRID_STEP_PX,
            experimentalWeightedFit = weightedFitAppliedToCup,
            weightedFitAppliedToCup = weightedFitAppliedToCup,
            weightedFitAppliedToBall = false,
            preWeightedCupStdDeg = preWeightedCupStdDeg,
            testSessionId = testSessionId,
            repeatIndex = repeatIndex,
            targetScenario = targetScenario
        )

        val result = SlopeInputResult(
            ballNormal = sd.ballNormal,
            cupNormal = sd.cupNormal,
            refNormal = sd.refNormal,
            forwardPct = forwardOut,
            lateralPct = lateralOut,
            quality = qualityOut,
            rejectReason = rejectOut,
            sourceId = sourceId,
            sampleCountBall = ballPoints.size,
            sampleCountCup = cupPoints.size,
            validSampleRatio = totalSamples / 18f,
            fitResidualBall = residualBall,
            fitResidualCup = residualCup,
            sampleSourceTypes = allSourceTypes,
            ballInputSource = ballInputSourceStr,
            cupInputSource = cupInputSourceStr,
            ballSampleTimestampMs = ballTsMs,
            cupSampleTimestampMs = cupTsMs,
            ballSampleFrameId = ballSamples?.collectedAtNs,
            cupSampleFrameId = cupSamples?.collectedAtNs,
            experimentalPlaneDriftDeg = sd.planeDriftDeg,
            driftThresholdDeg = DRIFT_THRESHOLD_DEG,
            trackingStateAtBallSample = ballSamples?.trackingStateAtSample,
            trackingStateAtCupSample = cupSamples?.trackingStateAtSample,
            experimentalDiagnostics = diag
        )
        ExperimentalSlopeKpi.recordResult(result.rejectReason, result.quality)
        return result
    }

    /**
     * projectedCupPx 기반 1차 tier (field 로그로 재조정 예정인 initial heuristic).
     * spread gate(2m) 통과 후에만 residual 과 비교한다.
     */
    private fun residualThresholdInitialHeuristic(
        projectedCupPx: Float?,
        sampleCountCup: Int,
        spreadM: Float
    ): Pair<Float, String> {
        val px = projectedCupPx ?: 48f
        val base = when {
            px >= 55f -> 0.050f
            px >= 40f -> 0.055f
            px >= 28f -> 0.060f
            else -> 0.060f
        }
        var t = base
        var mode = "px_tier_initial_heuristic_" + when {
            px >= 55f -> "a_ge55"
            px >= 40f -> "b_ge40_lt55"
            px >= 28f -> "c_ge28_lt40"
            else -> "d_lt28"
        }
        if (projectedCupPx == null) {
            mode += "_px_unknown_used_mid"
        }
        if (sampleCountCup < 6) {
            t *= 0.95f
            mode += "_strict_low_count"
        }
        if (spreadM > SPREAD_NOISE_THRESHOLD_M * 2f) {
            mode += "_high_spread_observed"
        }
        return Pair(t, mode)
    }

    @Suppress("LongParameterList")
    private fun rejectedWithContext(
        reason: String,
        trace: List<String>,
        lastStage: String,
        preReject: String? = null,
        residualType: String? = null,
        ballPoints: List<FloatArray> = emptyList(),
        cupPoints: List<FloatArray> = emptyList(),
        ballSamples: SlopeRawSamplesData? = null,
        cupSamples: SlopeRawSamplesData? = null,
        ballTsMs: Long? = null,
        cupTsMs: Long? = null,
        fitResidualBall: Float? = null,
        fitResidualCup: Float? = null,
        cupNormalRaw: FloatArray? = null,
        ballNormalRaw: FloatArray? = null,
        cupSpreadM: Float? = null,
        residualThreshold: Float? = null,
        residualMode: String? = null,
        distanceFromCameraCupM: Float? = null,
        multiRayProjectedCupPx: Float? = null,
        cupBmin: FloatArray? = null,
        cupBmax: FloatArray? = null,
        ballBmin: FloatArray? = null,
        ballBmax: FloatArray? = null,
        testSessionId: String? = null,
        repeatIndex: Int? = null,
        targetScenario: String? = null,
        experimentalSamplingPlanOverride: String? = null,
        experimentalBallSamplingPlanOverride: String? = null,
        experimentalWeightedFitOverride: Boolean? = null,
        weightedFitAppliedToCupOverride: Boolean? = null,
        experimentalSampleGridHalfSpanPxOverride: Float? = null
    ): SlopeInputResult {
        val ballSrc = ballSamples?.sourceTypes?.let { dominantSourceType(it) } ?: ""
        val cupSrc = cupSamples?.sourceTypes?.let { dominantSourceType(it) } ?: ""
        val driftRaw = if (ballNormalRaw != null && cupNormalRaw != null) {
            ExperimentalSlopeMath.angleDeg(ballNormalRaw, cupNormalRaw)
        } else null
        val planDefault =
            when {
                experimentalSamplingPlanOverride != null -> experimentalSamplingPlanOverride
                cupSamples?.gridOffsets != null -> "5x5_center"
                else -> "3x3"
            }
        val spanDefault = experimentalSampleGridHalfSpanPxOverride
            ?: if (cupSamples?.gridOffsets != null) 2f * GRID_STEP_PX else GRID_STEP_PX
        val diag = ExperimentalSlopeDiagnostics(
            rejectTrace = trace,
            lastRejectStage = lastStage,
            preRejectReason = preReject ?: reason,
            residualType = residualType,
            driftRawDeg = driftRaw,
            driftCanonicalDeg = null,
            driftType = null,
            ballLocalNormalRaw = ballNormalRaw?.copyOf(),
            cupLocalNormalRaw = cupNormalRaw?.copyOf(),
            ballSampleBBoxMin = ballBmin,
            ballSampleBBoxMax = ballBmax,
            cupSampleBBoxMin = cupBmin,
            cupSampleBBoxMax = cupBmax,
            cupResidualThresholdApplied = residualThreshold,
            cupResidualThresholdMode = residualMode,
            sampleSpreadCupM = cupSpreadM,
            distanceFromCameraCupM = distanceFromCameraCupM,
            multiRayProjectedCupPx = multiRayProjectedCupPx,
            experimentalSamplingPlan = planDefault,
            experimentalBallSamplingPlan = experimentalBallSamplingPlanOverride ?: "3x3",
            experimentalSampleGridHalfSpanPx = spanDefault,
            experimentalSampleStepPx = GRID_STEP_PX,
            experimentalWeightedFit = experimentalWeightedFitOverride ?: false,
            weightedFitAppliedToCup = weightedFitAppliedToCupOverride ?: false,
            weightedFitAppliedToBall = false,
            testSessionId = testSessionId,
            repeatIndex = repeatIndex,
            targetScenario = targetScenario
        )
        val bucketReason = preReject ?: reason
        val r = SlopeInputResult(
            ballNormal = null,
            cupNormal = null,
            refNormal = null,
            forwardPct = null,
            lateralPct = null,
            quality = "rejected",
            rejectReason = bucketReason,
            sourceId = sourceId,
            sampleCountBall = ballPoints.size,
            sampleCountCup = cupPoints.size,
            fitResidualBall = fitResidualBall,
            fitResidualCup = fitResidualCup,
            sampleSourceTypes = ((cupSamples?.sourceTypes ?: emptySet()) + (ballSamples?.sourceTypes ?: emptySet())).joinToString(",").ifBlank { "" },
            ballInputSource = ballSrc.ifBlank { null },
            cupInputSource = cupSrc.ifBlank { null },
            ballSampleTimestampMs = ballTsMs,
            cupSampleTimestampMs = cupTsMs,
            ballSampleFrameId = ballSamples?.collectedAtNs,
            cupSampleFrameId = cupSamples?.collectedAtNs,
            trackingStateAtBallSample = ballSamples?.trackingStateAtSample,
            trackingStateAtCupSample = cupSamples?.trackingStateAtSample,
            experimentalDiagnostics = diag
        )
        ExperimentalSlopeKpi.recordResult(r.rejectReason, r.quality)
        return r
    }

    private fun rejected(reason: String): SlopeInputResult {
        val r = SlopeInputResult(
            ballNormal = null,
            cupNormal = null,
            refNormal = null,
            forwardPct = null,
            lateralPct = null,
            quality = "rejected",
            rejectReason = reason,
            sourceId = sourceId,
            sampleCountBall = 0,
            sampleCountCup = 0,
            experimentalDiagnostics = ExperimentalSlopeDiagnostics(
                rejectTrace = listOf("early_reject"),
                lastRejectStage = "early_reject",
                preRejectReason = reason
            )
        )
        ExperimentalSlopeKpi.recordResult(r.rejectReason, r.quality)
        return r
    }
}
