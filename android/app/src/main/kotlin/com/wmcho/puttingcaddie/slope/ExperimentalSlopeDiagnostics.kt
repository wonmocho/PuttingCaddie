package com.wmcho.puttingcaddie.slope

import java.util.Locale
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Experimental(LocalSurfaceFit) 전용 디버그·분류 메타데이터.
 * 제품 경사(phase1)와 분리되어 로그/연구용으로만 사용.
 */
data class ExperimentalSlopeDiagnostics(
    val rejectTrace: List<String> = emptyList(),
    val lastRejectStage: String? = null,
    val preRejectReason: String? = null,
    /** cup residual reject 세분화: NOISE_SPREAD_HIGH | FIT_UNSTABLE | null */
    val residualType: String? = null,
    val driftRawDeg: Float? = null,
    val driftCanonicalDeg: Float? = null,
    /** SIGN_MISMATCH | SURFACE_MISMATCH | NONE | null */
    val driftType: String? = null,
    val ballLocalNormalRaw: FloatArray? = null,
    val cupLocalNormalRaw: FloatArray? = null,
    val ballLocalNormalCanonical: FloatArray? = null,
    val cupLocalNormalCanonical: FloatArray? = null,
    val normalFlipAppliedBall: Boolean = false,
    val normalFlipAppliedCup: Boolean = false,
    val normalFlipByWorldUpBall: Boolean = false,
    val normalFlipByRefBall: Boolean = false,
    val normalFlipByWorldUpCup: Boolean = false,
    val normalFlipByRefCup: Boolean = false,
    /** v2: cup을 ball canonical 과 같은 반구로 맞춤 */
    val normalFlipByBallAlignCup: Boolean = false,
    val ballNormalMean: FloatArray? = null,
    val cupNormalMean: FloatArray? = null,
    val ballNormalCandidateCount: Int? = null,
    val cupNormalCandidateCount: Int? = null,
    /** OK | DEGRADED | UNSTABLE — 초기에는 태깅만(P2부터 reject 연결 가능) */
    val normalStabilityStatus: String? = null,
    val ballSampleBBoxMin: FloatArray? = null,
    val ballSampleBBoxMax: FloatArray? = null,
    val cupSampleBBoxMin: FloatArray? = null,
    val cupSampleBBoxMax: FloatArray? = null,
    val ballToCupNormalDot: Float? = null,
    val ballNormalStdDeg: Float? = null,
    val cupNormalStdDeg: Float? = null,
    val cupResidualThresholdApplied: Float? = null,
    val cupResidualThresholdMode: String? = null,
    /** world-space spread proxy (m), not px */
    val sampleSpreadCupM: Float? = null,
    val distanceFromCameraCupM: Float? = null,
    val multiRayProjectedCupPx: Float? = null,
    /** OK | DEGRADED | REJECTED | null */
    val sanityStatus: String? = null,
    val sanityRejectReason: String? = null,
    val phase1ForwardPct: Float? = null,
    val phase1LateralPct: Float? = null,
    val experimentalForwardPctRaw: Float? = null,
    val experimentalLateralPctRaw: Float? = null,
    val experimentalSamplingPlan: String? = null,
    /** ball 쪽은 항상 3x3 수집 — cup plan 과 분리해 로그 일관성 */
    val experimentalBallSamplingPlan: String = "3x3",
    val experimentalSampleGridHalfSpanPx: Float? = null,
    val experimentalSampleStepPx: Float? = null,
    val experimentalWeightedFit: Boolean = false,
    /** P2: weighted 5x5 cup 재피트 적용 여부 */
    val weightedFitAppliedToCup: Boolean = false,
    val weightedFitAppliedToBall: Boolean = false,
    /** center 3x3 jackknife cup std (5x5 수집 시 비교용) */
    val preWeightedCupStdDeg: Float? = null,
    val testSessionId: String? = null,
    val repeatIndex: Int? = null,
    val targetScenario: String? = null
)

/** 세션 단위 experimental KPI (프로세스 전역, 로그·디버그용) */
object ExperimentalSlopeKpi {
    @Volatile var attemptCount: Int = 0
        private set
    @Volatile var validCount: Int = 0
        private set
    @Volatile var rejectCount: Int = 0
        private set
    private val reasonHistogram = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun reset() {
        attemptCount = 0
        validCount = 0
        rejectCount = 0
        reasonHistogram.clear()
    }

    fun recordResult(rejectReason: String?, quality: String) {
        attemptCount++
        when {
            quality == "valid" && rejectReason.isNullOrBlank() -> validCount++
            else -> {
                rejectCount++
                val key = rejectReason ?: "unknown"
                reasonHistogram[key] = (reasonHistogram[key] ?: 0) + 1
            }
        }
    }

    fun reasonHistogramSnapshot(): Map<String, Int> = reasonHistogram.toMap()

    /** JSONL `experimentalSlopeKpi` 필드용 (세션 누적 스냅샷) */
    fun toJsonSnapshot(): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"attemptCount\":").append(attemptCount)
        sb.append(",\"validCount\":").append(validCount)
        sb.append(",\"rejectCount\":").append(rejectCount)
        sb.append(",\"reasonHistogram\":{")
        val hist = reasonHistogramSnapshot()
        var first = true
        for ((k, v) in hist.entries.sortedBy { it.key }) {
            if (!first) sb.append(',')
            first = false
            sb.append('"').append(escapeJsonKey(k)).append("\":").append(v)
        }
        sb.append('}')
        sb.append('}')
        return sb.toString()
    }

    private fun escapeJsonKey(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
}

/**
 * [MEASUREMENT] JSON의 `slopeExperimental.experimentalDiagnostics`용.
 * @param escJson 문자열 값 이스케이프 (Activity의 escJson과 동일 규약)
 */
fun experimentalSlopeDiagnosticsToJson(
    d: ExperimentalSlopeDiagnostics?,
    escJson: (String) -> String,
    vec3Json: (FloatArray?) -> String
): String {
    if (d == null) return "null"
    val sb = StringBuilder()
    fun f(key: String, v: Float?) {
        sb.append('"').append(key).append("\":")
        if (v == null) sb.append("null") else sb.append(String.format(Locale.US, "%.6f", v))
    }
    fun i(key: String, v: Int?) {
        sb.append('"').append(key).append("\":")
        if (v == null) sb.append("null") else sb.append(v)
    }
    fun b(key: String, v: Boolean) {
        sb.append('"').append(key).append("\":").append(if (v) "true" else "false")
    }
    fun s(key: String, v: String?) {
        sb.append('"').append(key).append("\":")
        if (v == null) sb.append("null") else sb.append('"').append(escJson(v)).append('"')
    }
    sb.append('{')
    sb.append("\"rejectTrace\":[")
    d.rejectTrace.forEachIndexed { idx, t ->
        if (idx > 0) sb.append(',')
        sb.append('"').append(escJson(t)).append('"')
    }
    sb.append("],")
    s("lastRejectStage", d.lastRejectStage)
    sb.append(',')
    s("preRejectReason", d.preRejectReason)
    sb.append(',')
    s("residualType", d.residualType)
    sb.append(',')
    f("driftRawDeg", d.driftRawDeg)
    sb.append(',')
    f("driftCanonicalDeg", d.driftCanonicalDeg)
    sb.append(',')
    s("driftType", d.driftType)
    sb.append(',')
    sb.append("\"ballLocalNormalRaw\":").append(vec3Json(d.ballLocalNormalRaw)).append(',')
    sb.append("\"cupLocalNormalRaw\":").append(vec3Json(d.cupLocalNormalRaw)).append(',')
    sb.append("\"ballLocalNormalCanonical\":").append(vec3Json(d.ballLocalNormalCanonical)).append(',')
    sb.append("\"cupLocalNormalCanonical\":").append(vec3Json(d.cupLocalNormalCanonical)).append(',')
    b("normalFlipAppliedBall", d.normalFlipAppliedBall)
    sb.append(',')
    b("normalFlipAppliedCup", d.normalFlipAppliedCup)
    sb.append(',')
    b("normalFlipByWorldUpBall", d.normalFlipByWorldUpBall)
    sb.append(',')
    b("normalFlipByRefBall", d.normalFlipByRefBall)
    sb.append(',')
    b("normalFlipByWorldUpCup", d.normalFlipByWorldUpCup)
    sb.append(',')
    b("normalFlipByRefCup", d.normalFlipByRefCup)
    sb.append(',')
    b("normalFlipByBallAlignCup", d.normalFlipByBallAlignCup)
    sb.append(',')
    sb.append("\"ballNormalMean\":").append(vec3Json(d.ballNormalMean)).append(',')
    sb.append("\"cupNormalMean\":").append(vec3Json(d.cupNormalMean)).append(',')
    i("ballNormalCandidateCount", d.ballNormalCandidateCount)
    sb.append(',')
    i("cupNormalCandidateCount", d.cupNormalCandidateCount)
    sb.append(',')
    s("normalStabilityStatus", d.normalStabilityStatus)
    sb.append(',')
    sb.append("\"ballSampleBBoxMin\":").append(vec3Json(d.ballSampleBBoxMin)).append(',')
    sb.append("\"ballSampleBBoxMax\":").append(vec3Json(d.ballSampleBBoxMax)).append(',')
    sb.append("\"cupSampleBBoxMin\":").append(vec3Json(d.cupSampleBBoxMin)).append(',')
    sb.append("\"cupSampleBBoxMax\":").append(vec3Json(d.cupSampleBBoxMax)).append(',')
    f("ballToCupNormalDot", d.ballToCupNormalDot)
    sb.append(',')
    f("ballNormalStdDeg", d.ballNormalStdDeg)
    sb.append(',')
    f("cupNormalStdDeg", d.cupNormalStdDeg)
    sb.append(',')
    f("cupResidualThresholdApplied", d.cupResidualThresholdApplied)
    sb.append(',')
    s("cupResidualThresholdMode", d.cupResidualThresholdMode)
    sb.append(',')
    f("sampleSpreadCupM", d.sampleSpreadCupM)
    sb.append(',')
    f("distanceFromCameraCupM", d.distanceFromCameraCupM)
    sb.append(',')
    f("multiRayProjectedCupPx", d.multiRayProjectedCupPx)
    sb.append(',')
    s("sanityStatus", d.sanityStatus)
    sb.append(',')
    s("sanityRejectReason", d.sanityRejectReason)
    sb.append(',')
    f("phase1ForwardPct", d.phase1ForwardPct)
    sb.append(',')
    f("phase1LateralPct", d.phase1LateralPct)
    sb.append(',')
    f("experimentalForwardPctRaw", d.experimentalForwardPctRaw)
    sb.append(',')
    f("experimentalLateralPctRaw", d.experimentalLateralPctRaw)
    sb.append(',')
    s("experimentalSamplingPlan", d.experimentalSamplingPlan)
    sb.append(',')
    s("experimentalBallSamplingPlan", d.experimentalBallSamplingPlan)
    sb.append(',')
    f("experimentalSampleGridHalfSpanPx", d.experimentalSampleGridHalfSpanPx)
    sb.append(',')
    f("experimentalSampleStepPx", d.experimentalSampleStepPx)
    sb.append(',')
    b("experimentalWeightedFit", d.experimentalWeightedFit)
    sb.append(',')
    b("weightedFitAppliedToCup", d.weightedFitAppliedToCup)
    sb.append(',')
    b("weightedFitAppliedToBall", d.weightedFitAppliedToBall)
    sb.append(',')
    f("preWeightedCupStdDeg", d.preWeightedCupStdDeg)
    sb.append(',')
    s("testSessionId", d.testSessionId)
    sb.append(',')
    i("repeatIndex", d.repeatIndex)
    sb.append(',')
    s("targetScenario", d.targetScenario)
    sb.append('}')
    return sb.toString()
}

internal object ExperimentalSlopeMath {
    private val WORLD_UP = floatArrayOf(0f, 1f, 0f)

    internal fun normalize(v: FloatArray): FloatArray {
        val n = norm(v)
        if (n < 1e-6f) return v.copyOf()
        return floatArrayOf(v[0] / n, v[1] / n, v[2] / n)
    }

    private fun norm(v: FloatArray): Float =
        sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    fun dot(a: FloatArray, b: FloatArray): Float =
        a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    /**
     * worldUp 기준 y 성분이 음이면 부호 반전 (한쪽 hemisphere로 통일).
     * @return Pair(canonical normal, flipApplied)
     */
    fun canonicalizeAgainstUp(n: FloatArray, worldUp: FloatArray = WORLD_UP): Pair<FloatArray, Boolean> {
        val u = normalize(worldUp)
        var v = normalize(n)
        var flipped = false
        if (dot(v, u) < 0f) {
            v = floatArrayOf(-v[0], -v[1], -v[2])
            flipped = true
        }
        return Pair(v, flipped)
    }

    fun angleDeg(a: FloatArray, b: FloatArray): Float {
        val na = normalize(a)
        val nb = normalize(b)
        val d = dot(na, nb).coerceIn(-1f, 1f)
        return (acos(d) * 57.29578f)
    }

    fun bbox(points: List<FloatArray>): Pair<FloatArray?, FloatArray?> {
        if (points.isEmpty()) return Pair(null, null)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        var maxZ = Float.NEGATIVE_INFINITY
        for (p in points) {
            if (p.size < 3) continue
            minX = minOf(minX, p[0]); maxX = maxOf(maxX, p[0])
            minY = minOf(minY, p[1]); maxY = maxOf(maxY, p[1])
            minZ = minOf(minZ, p[2]); maxZ = maxOf(maxZ, p[2])
        }
        return Pair(
            floatArrayOf(minX, minY, minZ),
            floatArrayOf(maxX, maxY, maxZ)
        )
    }

    /** 대각선 길이(m)로 샘플 퍼짐 proxy */
    fun spreadExtentM(points: List<FloatArray>): Float {
        val (mn, mx) = bbox(points) ?: return 0f
        if (mn == null || mx == null) return 0f
        val dx = mx[0] - mn[0]
        val dy = mx[1] - mn[1]
        val dz = mx[2] - mn[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    /**
     * raw만 크고 canonical은 임계 이하 → 부호/기준 정렬 문제(SIGN_MISMATCH).
     * canonical이 임계 초과 → 표면 불일치(SURFACE_MISMATCH).
     * 그 외 → NONE.
     */
    fun classifyDriftType(
        driftRawDeg: Float,
        driftCanonicalDeg: Float,
        driftThresholdDeg: Float
    ): String {
        return when {
            driftRawDeg > driftThresholdDeg && driftCanonicalDeg <= driftThresholdDeg -> "SIGN_MISMATCH"
            driftCanonicalDeg > driftThresholdDeg -> "SURFACE_MISMATCH"
            else -> "NONE"
        }
    }

    /**
     * v2: ball — worldUp → phase1(ref).
     * ref는 null 금지 호출부에서 WORLD_UP 등으로 채움.
     */
    data class CanonicalNormalResult(
        val canonical: FloatArray,
        val flippedByWorldUp: Boolean,
        val flippedByRef: Boolean,
        val flippedByBallAlign: Boolean = false
    )

    /** 5x5 grid offset (dx,dy) from center, Manhattan ring 기반 가중치 */
    fun weightFor5x5(dx: Int, dy: Int): Float {
        val ax = abs(dx)
        val ay = abs(dy)
        val ring = maxOf(ax, ay)
        return when {
            ring == 0 -> 1.0f
            ring == 1 && (ax + ay <= 1) -> 0.7f
            ring == 1 -> 0.6f
            ring == 2 && ax == 2 && ay == 2 -> 0.25f
            ring == 2 -> 0.4f
            else -> 0.25f
        }
    }

    fun canonicalizeBallNormal(
        raw: FloatArray,
        worldUp: FloatArray,
        ref: FloatArray
    ): CanonicalNormalResult {
        var n = normalize(raw)
        var flippedByWorldUp = false
        var flippedByRef = false
        val u = normalize(worldUp)
        if (dot(n, u) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flippedByWorldUp = true
        }
        val r = normalize(ref)
        if (dot(n, r) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flippedByRef = true
        }
        return CanonicalNormalResult(n, flippedByWorldUp, flippedByRef, false)
    }

    /**
     * v2: cup — worldUp → ref(phase1 cup ?: ballCanonical) → ballCanonical 과 같은 반구 강제.
     */
    fun canonicalizeCupNormal(
        raw: FloatArray,
        worldUp: FloatArray,
        ref: FloatArray,
        ballCanonical: FloatArray
    ): CanonicalNormalResult {
        var n = normalize(raw)
        var flippedByWorldUp = false
        var flippedByRef = false
        var flippedByBallAlign = false
        val u = normalize(worldUp)
        if (dot(n, u) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flippedByWorldUp = true
        }
        val r = normalize(ref)
        if (dot(n, r) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flippedByRef = true
        }
        val bc = normalize(ballCanonical)
        if (dot(n, bc) < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
            flippedByBallAlign = true
        }
        return CanonicalNormalResult(n, flippedByWorldUp, flippedByRef, flippedByBallAlign)
    }

    /**
     * Jackknife 평면 노멀들의 mean 대비 각도 편차 표준편차(°).
     * 포인트 4개 이상일 때만 의미(leave-one-out마다 최소 3점).
     */
    fun jackknifePlaneNormalMeanStdDeg(
        points: List<FloatArray>,
        fitPlane: (List<FloatArray>) -> FloatArray?
    ): Triple<FloatArray?, Float?, Int> {
        if (points.size < 4) return Triple(null, null, points.size)
        val normals = ArrayList<FloatArray>(points.size)
        for (i in points.indices) {
            val subset = points.filterIndexed { idx, _ -> idx != i }
            val n = fitPlane(subset) ?: continue
            normals.add(n)
        }
        if (normals.size < 3) return Triple(null, null, normals.size)
        val n0 = normalize(normals[0])
        val aligned = ArrayList<FloatArray>(normals.size)
        aligned.add(n0)
        for (j in 1 until normals.size) {
            var nj = normalize(normals[j])
            if (dot(nj, n0) < 0f) {
                nj = floatArrayOf(-nj[0], -nj[1], -nj[2])
            }
            aligned.add(nj)
        }
        var mx = 0f
        var my = 0f
        var mz = 0f
        for (p in aligned) {
            mx += p[0]; my += p[1]; mz += p[2]
        }
        val mean = normalize(floatArrayOf(mx, my, mz))
        val angles = FloatArray(aligned.size) { i -> angleDeg(aligned[i], mean) }
        var sum = 0f
        for (a in angles) sum += a
        val m = sum / angles.size
        var varSum = 0f
        for (a in angles) {
            val d = a - m
            varSum += d * d
        }
        val std = kotlin.math.sqrt(varSum / angles.size)
        return Triple(mean, std, normals.size)
    }

    /**
     * 가중 최소제곱 평면: 3×3 공분산의 **최소** 고유값에 대응하는 단위 법선.
     * (C^{-1}의 최대 고유벡터 = C의 최소 고유벡터, power iteration)
     */
    fun fitPlaneWeightedWls(
        points: List<FloatArray>,
        weights: List<Float>
    ): Pair<FloatArray?, Float?> {
        if (points.size < 3 || points.size != weights.size) return Pair(null, null)
        var sw = 0f
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (i in points.indices) {
            val w = weights[i].coerceAtLeast(1e-8f)
            sw += w
            cx += w * points[i][0]
            cy += w * points[i][1]
            cz += w * points[i][2]
        }
        if (sw < 1e-6f) return Pair(null, null)
        cx /= sw
        cy /= sw
        cz /= sw
        var cxx = 0f
        var cyy = 0f
        var czz = 0f
        var cxy = 0f
        var cxz = 0f
        var cyz = 0f
        for (i in points.indices) {
            val w = weights[i]
            val x = points[i][0] - cx
            val y = points[i][1] - cy
            val z = points[i][2] - cz
            cxx += w * x * x
            cyy += w * y * y
            czz += w * z * z
            cxy += w * x * y
            cxz += w * x * z
            cyz += w * y * z
        }
        cxx /= sw
        cyy /= sw
        czz /= sw
        cxy /= sw
        cxz /= sw
        cyz /= sw
        val ridge = 1e-5f
        cxx += ridge
        cyy += ridge
        czz += ridge
        val invC = invert3x3(
            arrayOf(
                floatArrayOf(cxx, cxy, cxz),
                floatArrayOf(cxy, cyy, cyz),
                floatArrayOf(cxz, cyz, czz)
            )
        ) ?: return Pair(null, null)
        var vx = 1f
        var vy = 0f
        var vz = 0f
        repeat(24) {
            val nx = invC[0][0] * vx + invC[0][1] * vy + invC[0][2] * vz
            val ny = invC[1][0] * vx + invC[1][1] * vy + invC[1][2] * vz
            val nz = invC[2][0] * vx + invC[2][1] * vy + invC[2][2] * vz
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len < 1e-10f) return Pair(null, null)
            vx = nx / len
            vy = ny / len
            vz = nz / len
        }
        var n = normalize(floatArrayOf(vx, vy, vz))
        if (n[1] < 0f) {
            n = floatArrayOf(-n[0], -n[1], -n[2])
        }
        var resSum = 0f
        for (i in points.indices) {
            val w = weights[i]
            val x = points[i][0] - cx
            val y = points[i][1] - cy
            val z = points[i][2] - cz
            val d = abs(x * n[0] + y * n[1] + z * n[2])
            resSum += w * d
        }
        val resMean = resSum / sw
        return Pair(n, resMean)
    }

    private fun invert3x3(a: Array<FloatArray>): Array<FloatArray>? {
        val a00 = a[0][0]
        val a01 = a[0][1]
        val a02 = a[0][2]
        val a10 = a[1][0]
        val a11 = a[1][1]
        val a12 = a[1][2]
        val a20 = a[2][0]
        val a21 = a[2][1]
        val a22 = a[2][2]
        val det =
            a00 * (a11 * a22 - a12 * a21) -
                a01 * (a10 * a22 - a12 * a20) +
                a02 * (a10 * a21 - a11 * a20)
        if (abs(det) < 1e-14f) return null
        val invDet = 1f / det
        return arrayOf(
            floatArrayOf(
                (a11 * a22 - a12 * a21) * invDet,
                (a02 * a21 - a01 * a22) * invDet,
                (a01 * a12 - a02 * a11) * invDet
            ),
            floatArrayOf(
                (a12 * a20 - a10 * a22) * invDet,
                (a00 * a22 - a02 * a20) * invDet,
                (a02 * a10 - a00 * a12) * invDet
            ),
            floatArrayOf(
                (a10 * a21 - a11 * a20) * invDet,
                (a01 * a20 - a00 * a21) * invDet,
                (a00 * a11 - a01 * a10) * invDet
            )
        )
    }
}
