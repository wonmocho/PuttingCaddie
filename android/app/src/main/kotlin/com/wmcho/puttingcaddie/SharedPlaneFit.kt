package com.wmcho.puttingcaddie

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * P3 Step 1: ball+cup에서 수집한 **월드 좌표 점군**만으로 단일 shared plane 추정.
 * [LocalSurfaceFitInputProvider]의 단순 피트와 동일한 형태(최소 의존·동일 부호 규칙).
 *
 * mid 패치 미포함(§8.8). 호출부에서 ball·cup 샘플만 합쳐 전달한다.
 */
object SharedPlaneFit {

    data class Result(
        val normalWorld: FloatArray,
        val residualMeanM: Float,
        val pointCount: Int
    )

    fun fitFromWorldPoints(worldPoints: List<FloatArray>): Result? {
        if (worldPoints.size < 3) return null
        val c = centroid(worldPoints)
        val centered = worldPoints.map { p ->
            floatArrayOf(p[0] - c[0], p[1] - c[1], p[2] - c[2])
        }
        val n = planeNormalFromCentered(centered) ?: return null
        val residual = meanAbsDistanceToPlane(worldPoints, c, n)
        return Result(n, residual, worldPoints.size)
    }

    data class TrimmedFitOutput(
        val result: Result?,
        val originalCount: Int,
        val removedCount: Int,
        val remainingCount: Int,
        val originalResidualMeanM: Float?,
        val trimmedResidualMeanM: Float?,
        val removedResidualMin: Float?,
        val removedResidualMax: Float?,
        val removedResidualMedian: Float?
    )

    data class CorridorStats(
        val ballCupDistM: Float,
        val corridorHalfWidthM: Float,
        val inputCount: Int,
        val keptCount: Int,
        val rejectedByDistanceCount: Int,
        val maxDistanceToLineM: Float?,
        val medianDistanceToLineM: Float?,
        val keptPoints: List<FloatArray>
    )

    /**
     * 전체 fit 후 점별 residual 상위 ~22% 제거 → 재-fit. 새 샘플 없음.
     */
    fun fitTrimmed(worldPoints: List<FloatArray>): Result? =
        fitTrimmedDetailed(worldPoints).result

    /** trim 전후 개수·잔차 통계 (로그용). */
    fun fitTrimmedDetailed(worldPoints: List<FloatArray>): TrimmedFitOutput {
        if (worldPoints.size < 4) {
            return TrimmedFitOutput(
                result = null,
                originalCount = worldPoints.size,
                removedCount = 0,
                remainingCount = worldPoints.size,
                originalResidualMeanM = null,
                trimmedResidualMeanM = null,
                removedResidualMin = null,
                removedResidualMax = null,
                removedResidualMedian = null
            )
        }
        val base = fitFromWorldPoints(worldPoints)
        if (base == null) {
            return TrimmedFitOutput(
                result = null,
                originalCount = worldPoints.size,
                removedCount = 0,
                remainingCount = worldPoints.size,
                originalResidualMeanM = null,
                trimmedResidualMeanM = null,
                removedResidualMin = null,
                removedResidualMax = null,
                removedResidualMedian = null
            )
        }
        val c = centroid(worldPoints)
        val n = base.normalWorld
        val withDist = worldPoints.mapIndexed { i, p ->
            val d = kotlin.math.abs(
                (p[0] - c[0]) * n[0] + (p[1] - c[1]) * n[1] + (p[2] - c[2]) * n[2]
            )
            i to d
        }.sortedByDescending { it.second }
        val removeCount = max(1, kotlin.math.ceil(worldPoints.size * 0.22).toInt())
        if (worldPoints.size - removeCount < 3) {
            val removedD = withDist.take(removeCount).map { it.second }
            return TrimmedFitOutput(
                result = null,
                originalCount = worldPoints.size,
                removedCount = removeCount,
                remainingCount = worldPoints.size - removeCount,
                originalResidualMeanM = base.residualMeanM,
                trimmedResidualMeanM = null,
                removedResidualMin = removedD.minOrNull(),
                removedResidualMax = removedD.maxOrNull(),
                removedResidualMedian = medianFloat(removedD)
            )
        }
        val drop = withDist.take(removeCount).map { it.first }.toSet()
        val removedD = withDist.take(removeCount).map { it.second }
        val trimmed = worldPoints.filterIndexed { i, _ -> i !in drop }
        val trimmedResult = fitFromWorldPoints(trimmed)
        return TrimmedFitOutput(
            result = trimmedResult,
            originalCount = worldPoints.size,
            removedCount = removeCount,
            remainingCount = trimmed.size,
            originalResidualMeanM = base.residualMeanM,
            trimmedResidualMeanM = trimmedResult?.residualMeanM,
            removedResidualMin = removedD.minOrNull(),
            removedResidualMax = removedD.maxOrNull(),
            removedResidualMedian = medianFloat(removedD)
        )
    }

    private fun medianFloat(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    /**
     * 기존 ball∪cup 점 중 ball→cup 선분에 가까운 점만 (corridor). 새 샘플링 아님.
     */
    fun corridorSubset(
        worldPoints: List<FloatArray>,
        ballPos: FloatArray,
        cupPos: FloatArray
    ): List<FloatArray> = corridorSubsetWithStats(worldPoints, ballPos, cupPos).keptPoints

    fun corridorSubsetWithStats(
        worldPoints: List<FloatArray>,
        ballPos: FloatArray,
        cupPos: FloatArray
    ): CorridorStats {
        val dx = cupPos[0] - ballPos[0]
        val dy = cupPos[1] - ballPos[1]
        val dz = cupPos[2] - ballPos[2]
        val dist = sqrt(dx * dx + dy * dy + dz * dz)
        if (dist < 1e-4f) {
            return CorridorStats(
                ballCupDistM = 0f,
                corridorHalfWidthM = 0f,
                inputCount = worldPoints.size,
                keptCount = 0,
                rejectedByDistanceCount = worldPoints.size,
                maxDistanceToLineM = null,
                medianDistanceToLineM = null,
                keptPoints = emptyList()
            )
        }
        val halfW = max(0.10f, min(0.25f, 0.08f * dist))
        val distances = worldPoints.map { distancePointToSegment3D(it, ballPos, cupPos) }
        val kept = worldPoints.filterIndexed { i, _ -> distances[i] <= halfW }
        val allD = distances.sorted()
        val med = medianFloat(distances)
        return CorridorStats(
            ballCupDistM = dist,
            corridorHalfWidthM = halfW,
            inputCount = worldPoints.size,
            keptCount = kept.size,
            rejectedByDistanceCount = worldPoints.size - kept.size,
            maxDistanceToLineM = distances.maxOrNull(),
            medianDistanceToLineM = med,
            keptPoints = kept
        )
    }

    private fun distancePointToSegment3D(p: FloatArray, a: FloatArray, b: FloatArray): Float {
        val ab = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val ap = floatArrayOf(p[0] - a[0], p[1] - a[1], p[2] - a[2])
        val abLen2 = ab[0] * ab[0] + ab[1] * ab[1] + ab[2] * ab[2]
        if (abLen2 < 1e-12f) {
            return sqrt(ap[0] * ap[0] + ap[1] * ap[1] + ap[2] * ap[2])
        }
        var t = (ap[0] * ab[0] + ap[1] * ab[1] + ap[2] * ab[2]) / abLen2
        t = t.coerceIn(0f, 1f)
        val cx = a[0] + t * ab[0]
        val cy = a[1] + t * ab[1]
        val cz = a[2] + t * ab[2]
        val ex = p[0] - cx
        val ey = p[1] - cy
        val ez = p[2] - cz
        return sqrt(ex * ex + ey * ey + ez * ez)
    }

    private fun centroid(points: List<FloatArray>): FloatArray {
        val n = points.size.toFloat()
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (p in points) {
            sx += p[0]
            sy += p[1]
            sz += p[2]
        }
        return floatArrayOf(sx / n, sy / n, sz / n)
    }

    private fun planeNormalFromCentered(centered: List<FloatArray>): FloatArray? {
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
        var nx = cx / len
        var ny = cy / len
        var nz = cz / len
        if (ny < 0f) {
            nx = -nx
            ny = -ny
            nz = -nz
        }
        return floatArrayOf(nx, ny, nz)
    }

    private fun meanAbsDistanceToPlane(
        points: List<FloatArray>,
        origin: FloatArray,
        normal: FloatArray
    ): Float {
        var sum = 0f
        for (p in points) {
            val d = (p[0] - origin[0]) * normal[0] +
                (p[1] - origin[1]) * normal[1] +
                (p[2] - origin[2]) * normal[2]
            sum += kotlin.math.abs(d)
        }
        return sum / points.size
    }
}
