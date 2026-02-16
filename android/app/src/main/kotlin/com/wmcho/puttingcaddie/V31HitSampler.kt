package com.wmcho.puttingcaddie

import android.graphics.PointF
import android.graphics.RectF
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import kotlin.math.abs
import kotlin.math.max

class V31HitSampler(private val mapper: ScreenToViewMapper) {
    enum class HitType { PLANE, DEPTH, POINT, NONE }

    data class Sample(
        val bestHit: HitResult?,
        val hitType: HitType,
        val validHits: Int,
        val totalPoints: Int,
        // Optional diagnostics (used by GREENIQ Cup multi-ray)
        val gridHalfSpanPx: Float? = null,
        val gridStepPx: Float? = null,
        val hitDistanceAvgMeters: Float? = null,
        val hitDistanceMaxMeters: Float? = null,
        val cameraY: Float? = null,
        val medianY: Float? = null,
        val centerYOffsetApplied: Boolean? = null
    )

    private data class Candidate(
        val hit: HitResult,
        val type: HitType,
        val distToRoiCenter: Float
    )

    private data class PlanePolicy(
        val maxDistanceMeters: Float,
        val preferUpwardFacing: Boolean,
        val requireUpwardFacing: Boolean,
        // If set: reject hits with hitY > (cameraY - yBelowCameraMeters)
        val yBelowCameraMeters: Float? = null
    )

    private data class SelectedHit(
        val hit: HitResult,
        val pose: Pose,
        val hitDistanceMeters: Float
    )

    /**
     * Precise screen(px) -> hitTest local(px) mapping (UV-adjusted).
     * Returns null if view size is not ready.
     */
    private fun adjustedHitTestLocalPoint(frame: Frame, screenX: Float, screenY: Float): PointF? {
        val glW = mapper.viewWidthPx().toFloat()
        val glH = mapper.viewHeightPx().toFloat()
        if (glW <= 1f || glH <= 1f) return null

        val pLocal = mapper.screenToLocal(PointF(screenX, screenY))
        val screenPts = floatArrayOf(
            (pLocal.x / glW).coerceIn(0f, 1f),
            (pLocal.y / glH).coerceIn(0f, 1f)
        )

        // VIEW_NORMALIZED -> TEXTURE_NORMALIZED
        val uv = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, screenPts, Coordinates2d.TEXTURE_NORMALIZED, uv)

        // Apply user texture adjustment (same as BackgroundRenderer)
        val (uu, vv) = UvAdjust.applyUv(mapper.rotationDeg, mapper.mirrorH, mapper.mirrorV, uv[0], uv[1])
        uv[0] = uu
        uv[1] = vv

        // TEXTURE_NORMALIZED -> VIEW_NORMALIZED
        val viewAdj = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, uv, Coordinates2d.VIEW_NORMALIZED, viewAdj)

        val xAdj = (viewAdj[0] * glW).coerceIn(0f, glW)
        val yAdj = (viewAdj[1] * glH).coerceIn(0f, glH)
        return PointF(xAdj, yAdj)
    }

    /**
     * Screen(px) -> TEXTURE_NORMALIZED uv (with user UV adjust applied).
     * Useful for building a camera ray via Camera.textureIntrinsics.
     */
    fun screenPointToAdjustedTextureUv(frame: Frame, screenX: Float, screenY: Float): PointF? {
        val glW = mapper.viewWidthPx().toFloat()
        val glH = mapper.viewHeightPx().toFloat()
        if (glW <= 1f || glH <= 1f) return null

        val pLocal = mapper.screenToLocal(PointF(screenX, screenY))
        val screenPts = floatArrayOf(
            (pLocal.x / glW).coerceIn(0f, 1f),
            (pLocal.y / glH).coerceIn(0f, 1f)
        )

        // VIEW_NORMALIZED -> TEXTURE_NORMALIZED
        val uv = FloatArray(2)
        frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, screenPts, Coordinates2d.TEXTURE_NORMALIZED, uv)

        // Apply user texture adjustment (same as BackgroundRenderer)
        val (uu, vv) = UvAdjust.applyUv(mapper.rotationDeg, mapper.mirrorH, mapper.mirrorV, uv[0], uv[1])
        return PointF(uu, vv)
    }

    private fun selectBestPlaneHit(
        results: List<HitResult>,
        policy: PlanePolicy,
        cameraY: Float
    ): HitResult? {
        var bestUpward: HitResult? = null
        var bestOther: HitResult? = null

        for (r in results) {
            val t = r.trackable
            if (t is InstantPlacementPoint) continue
            if (t !is Plane) continue
            if (!t.isPoseInPolygon(r.hitPose)) continue
            if (r.distance > policy.maxDistanceMeters) continue

            val hitY = r.hitPose.ty()
            val yBelow = policy.yBelowCameraMeters
            if (yBelow != null) {
                if (hitY > (cameraY - yBelow)) continue
            }

            val isUpward = t.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            if (policy.requireUpwardFacing && !isUpward) continue

            if (isUpward) {
                if (bestUpward == null || r.distance < bestUpward!!.distance - 0.0001f) bestUpward = r
            } else {
                if (bestOther == null || r.distance < bestOther!!.distance - 0.0001f) bestOther = r
            }
        }

        return if (policy.preferUpwardFacing) (bestUpward ?: bestOther) else (bestOther ?: bestUpward)
    }

    /**
     * Single-ray plane hit at a screen coordinate (GREENIQ LIVE "laser" mode).
     *
     * Representative hit selection rules:
     * - Plane + isPoseInPolygon
     * - Prefer HORIZONTAL_UPWARD_FACING
     * - Distance cap
     * - Optional Y filter (must be below camera)
     * - Choose closest hit.distance
     */
    fun hitTestBestPlaneAtScreenPoint(
        frame: Frame,
        screenX: Float,
        screenY: Float,
        maxDistanceMeters: Float,
        preferUpwardFacing: Boolean,
        yBelowCameraMeters: Float? = null
    ): HitResult? {
        val pAdj = adjustedHitTestLocalPoint(frame, screenX, screenY) ?: return null
        val results = frame.hitTest(pAdj.x, pAdj.y)
        val camY = frame.camera.pose.ty()
        val policy =
            PlanePolicy(
                maxDistanceMeters = maxDistanceMeters,
                preferUpwardFacing = preferUpwardFacing,
                requireUpwardFacing = false,
                yBelowCameraMeters = yBelowCameraMeters
            )
        return selectBestPlaneHit(results, policy, camY)
    }

    /**
     * GREENIQ Cup multi-ray sampling (5x5 grid) with median-based representative hit selection.
     *
     * - Screen center is based on base ROI center, with an extra Y offset downwards (Cup only).
     * - Sampling rect is centered at adjusted center with half-span = offsetPercent * viewSizePx.
     * - Each ray contributes at most 1 representative Plane hit (policy-driven).
     * - Representative hit returned is the valid hit whose pose is closest to the median (XYZ).
     */
    fun sampleCupPlaneMultiRay(
        frame: Frame,
        baseRoiScreen: RectF,
        offsetPercent: Float,
        centerYOffsetRatio: Float,
        gridSize: Int = 5,
        maxHitDistanceMeters: Float = 12f,
        yBelowCameraMeters: Float = 0.1f,
        preferUpwardFacing: Boolean = true,
        requireUpwardFacing: Boolean = false
    ): Sample {
        val glW = mapper.viewWidthPx().toFloat().takeIf { it > 1f } ?: return Sample(null, HitType.NONE, 0, gridSize * gridSize)
        val glH = mapper.viewHeightPx().toFloat().takeIf { it > 1f } ?: return Sample(null, HitType.NONE, 0, gridSize * gridSize)

        val cx = baseRoiScreen.centerX()
        val cy = baseRoiScreen.centerY() + (glH * centerYOffsetRatio)

        val halfSpanX = (glW * offsetPercent).coerceAtLeast(1f)
        val halfSpanY = (glH * offsetPercent).coerceAtLeast(1f)
        val roi =
            RectF(
                cx - halfSpanX,
                cy - halfSpanY,
                cx + halfSpanX,
                cy + halfSpanY
            )

        val camY = frame.camera.pose.ty()
        val policy =
            PlanePolicy(
                maxDistanceMeters = maxHitDistanceMeters,
                preferUpwardFacing = preferUpwardFacing,
                requireUpwardFacing = requireUpwardFacing,
                yBelowCameraMeters = yBelowCameraMeters
            )

        val selected = ArrayList<SelectedHit>(gridSize * gridSize)
        val distances = ArrayList<Float>(gridSize * gridSize)

        // Build samples directly in screen coords, but execute hitTest at UV-adjusted local coords.
        for (iy in 0 until gridSize) {
            val ty = if (gridSize == 1) 0.5f else iy.toFloat() / (gridSize - 1).toFloat()
            val yScreen = roi.top + (ty * roi.height())
            for (ix in 0 until gridSize) {
                val tx = if (gridSize == 1) 0.5f else ix.toFloat() / (gridSize - 1).toFloat()
                val xScreen = roi.left + (tx * roi.width())

                val pAdj = adjustedHitTestLocalPoint(frame, xScreen, yScreen) ?: continue
                val results = frame.hitTest(pAdj.x, pAdj.y)
                val hit = selectBestPlaneHit(results, policy, camY) ?: continue
                val pose = hit.hitPose
                selected.add(SelectedHit(hit = hit, pose = pose, hitDistanceMeters = hit.distance))
                distances.add(hit.distance)
            }
        }

        val valid = selected.size
        val total = gridSize * gridSize
        if (valid <= 0) {
            val gridHalf = max(halfSpanX, halfSpanY)
            val stepPx = if (gridSize <= 1) 0f else (2f * gridHalf) / (gridSize - 1).toFloat()
            return Sample(
                bestHit = null,
                hitType = HitType.NONE,
                validHits = 0,
                totalPoints = total,
                gridHalfSpanPx = gridHalf,
                gridStepPx = stepPx,
                hitDistanceAvgMeters = null,
                hitDistanceMaxMeters = null,
                cameraY = camY,
                medianY = null,
                centerYOffsetApplied = true
            )
        }

        fun median(values: List<Float>): Float {
            val s = values.sorted()
            val n = s.size
            val mid = n / 2
            return if (n % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
        }

        val xs = selected.map { it.pose.tx() }
        val ys = selected.map { it.pose.ty() }
        val zs = selected.map { it.pose.tz() }
        val mx = median(xs)
        val my = median(ys)
        val mz = median(zs)

        // Choose the real hit whose pose is closest to the median point (for anchor creation).
        var best: SelectedHit? = null
        var bestD2 = Float.POSITIVE_INFINITY
        for (s in selected) {
            val dx = s.pose.tx() - mx
            val dy = s.pose.ty() - my
            val dz = s.pose.tz() - mz
            val d2 = dx * dx + dy * dy + dz * dz
            if (d2 < bestD2) {
                bestD2 = d2
                best = s
            }
        }

        val avgDist = distances.sum() / distances.size.toFloat()
        val maxDist = distances.maxOrNull()

        val gridHalf = max(halfSpanX, halfSpanY)
        val stepPx = if (gridSize <= 1) 0f else (2f * gridHalf) / (gridSize - 1).toFloat()

        return Sample(
            bestHit = best!!.hit,
            hitType = HitType.PLANE,
            validHits = valid,
            totalPoints = total,
            gridHalfSpanPx = gridHalf,
            gridStepPx = stepPx,
            hitDistanceAvgMeters = avgDist,
            hitDistanceMaxMeters = maxDist,
            cameraY = camY,
            medianY = my,
            centerYOffsetApplied = true
        )
    }

    fun sampleBestHit(frame: Frame, roiScreen: RectF, gridCount: Int): Sample {
        val gridSize = when (gridCount) {
            9 -> 3
            25 -> 5
            49 -> 7
            else -> 3
        }
        val cx = roiScreen.centerX()
        val cy = roiScreen.centerY()

        val plane = ArrayList<Candidate>(gridCount)
        val depth = ArrayList<Candidate>(gridCount)
        val point = ArrayList<Candidate>(gridCount)
        var planeValid = 0
        var depthValid = 0
        var pointValid = 0

        // Collect points in view-normalized coords first for a single transform batch.
        val screenPts = FloatArray(gridSize * gridSize * 2)
        var idxPt = 0

        // Normalize by GLSurfaceView size (hitTest expects view pixel coords).
        val glW = mapper.viewWidthPx().toFloat().takeIf { it > 1f } ?: 1f
        val glH = mapper.viewHeightPx().toFloat().takeIf { it > 1f } ?: 1f

        for (iy in 0 until gridSize) {
            val ty = if (gridSize == 1) 0.5f else iy.toFloat() / (gridSize - 1).toFloat()
            val yScreen = roiScreen.top + (ty * roiScreen.height())
            for (ix in 0 until gridSize) {
                val tx = if (gridSize == 1) 0.5f else ix.toFloat() / (gridSize - 1).toFloat()
                val xScreen = roiScreen.left + (tx * roiScreen.width())

                val pLocal = mapper.screenToLocal(PointF(xScreen, yScreen))
                screenPts[idxPt++] = (pLocal.x / glW).coerceIn(0f, 1f)
                screenPts[idxPt++] = (pLocal.y / glH).coerceIn(0f, 1f)
            }
        }

        // 1) VIEW_NORMALIZED -> TEXTURE_NORMALIZED
        val uv = FloatArray(screenPts.size)
        frame.transformCoordinates2d(Coordinates2d.VIEW_NORMALIZED, screenPts, Coordinates2d.TEXTURE_NORMALIZED, uv)

        // 2) Apply user texture adjustment (same as BackgroundRenderer)
        val rot = mapper.rotationDeg
        val mh = mapper.mirrorH
        val mv = mapper.mirrorV
        for (i in uv.indices step 2) {
            val (uu, vv) = UvAdjust.applyUv(rot, mh, mv, uv[i], uv[i + 1])
            uv[i] = uu
            uv[i + 1] = vv
        }

        // 3) TEXTURE_NORMALIZED -> VIEW_NORMALIZED
        val viewAdj = FloatArray(screenPts.size)
        frame.transformCoordinates2d(Coordinates2d.TEXTURE_NORMALIZED, uv, Coordinates2d.VIEW_NORMALIZED, viewAdj)

        // Now iterate points and do hitTest at adjusted view coords
        var pointIndex = 0
        for (iy in 0 until gridSize) {
            val ty = if (gridSize == 1) 0.5f else iy.toFloat() / (gridSize - 1).toFloat()
            val yScreen = roiScreen.top + (ty * roiScreen.height())
            for (ix in 0 until gridSize) {
                val tx = if (gridSize == 1) 0.5f else ix.toFloat() / (gridSize - 1).toFloat()
                val xScreen = roiScreen.left + (tx * roiScreen.width())

                val xAdj = (viewAdj[pointIndex++] * glW).coerceIn(0f, glW)
                val yAdj = (viewAdj[pointIndex++] * glH).coerceIn(0f, glH)

                val results = frame.hitTest(xAdj, yAdj)
                val distToCenter = abs(xScreen - cx) + abs(yScreen - cy)

                // same point: pick best match by type priority
                var chosen: HitResult? = null
                var chosenType: HitType = HitType.NONE

                for (r in results) {
                    val t = r.trackable
                    if (t is InstantPlacementPoint) continue
                    if (t is Plane && t.isPoseInPolygon(r.hitPose)) {
                        chosen = r
                        chosenType = HitType.PLANE
                        break
                    }
                }

                if (chosen == null) {
                    for (r in results) {
                        val t = r.trackable
                        if (t is InstantPlacementPoint) continue
                        if (t is Plane) continue
                        if (isDepthTrackable(t)) {
                            chosen = r
                            chosenType = HitType.DEPTH
                            break
                        }
                    }
                }

                if (chosen == null) {
                    for (r in results) {
                        val t = r.trackable
                        if (t is InstantPlacementPoint) continue
                        if (t is Plane) continue
                        if (t is Point) {
                            chosen = r
                            chosenType = HitType.POINT
                            break
                        }
                    }
                }

                if (chosen != null) {
                    when (chosenType) {
                        HitType.PLANE -> {
                            planeValid++
                            plane.add(Candidate(chosen, chosenType, distToCenter))
                        }
                        HitType.DEPTH -> {
                            depthValid++
                            depth.add(Candidate(chosen, chosenType, distToCenter))
                        }
                        HitType.POINT -> {
                            pointValid++
                            point.add(Candidate(chosen, chosenType, distToCenter))
                        }
                        else -> {}
                    }
                }
            }
        }

        val bestPlane = pickBest(plane)
        val bestDepth = pickBest(depth)
        val bestPoint = pickBest(point)

        return when {
            bestPlane != null -> Sample(bestPlane, HitType.PLANE, planeValid, gridSize * gridSize)
            bestDepth != null -> Sample(bestDepth, HitType.DEPTH, depthValid, gridSize * gridSize)
            bestPoint != null -> Sample(bestPoint, HitType.POINT, pointValid, gridSize * gridSize)
            else -> Sample(null, HitType.NONE, max(planeValid, max(depthValid, pointValid)), gridSize * gridSize)
        }
    }

    private fun pickBest(cands: List<Candidate>): HitResult? {
        if (cands.isEmpty()) return null
        var best: Candidate? = null
        for (c in cands) {
            if (best == null) {
                best = c
                continue
            }
            val bd = best!!.distToRoiCenter
            val cd = c.distToRoiCenter
            if (cd < bd - 0.0001f) {
                best = c
            } else if (abs(cd - bd) <= 0.0001f) {
                if (c.hit.distance < best!!.hit.distance - 0.0001f) {
                    best = c
                }
            }
        }
        return best!!.hit
    }

    private fun isDepthTrackable(trackable: Any): Boolean {
        val name = trackable.javaClass.name
        val simple = trackable.javaClass.simpleName
        return name == "com.google.ar.core.DepthPoint" || simple == "DepthPoint"
    }
}

