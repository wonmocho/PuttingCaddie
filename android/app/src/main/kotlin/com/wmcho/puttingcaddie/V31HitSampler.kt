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
import kotlin.math.min
import kotlin.math.max
import kotlin.math.sqrt

class V31HitSampler(private val mapper: ScreenToViewMapper) {
    enum class HitType { PLANE, DEPTH, POINT, NONE }
    private val BALL_GRID_STEP_PX = 6f

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
        val centerYOffsetApplied: Boolean? = null,
        // Optional diagnostics (distance-adaptive grid)
        val gridPlan: String? = null,
        val gridEstimatedDistanceMeters: Float? = null,
        val gridProjectedCupPx: Float? = null,
        val centerFallbackUsed: Boolean? = null
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
        // If true: allow plane hits even when hitPose is outside polygon.
        // Useful for far distances where plane extent is smaller than the target area.
        val allowOutsidePolygon: Boolean = false,
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
        var bestUpwardInside: HitResult? = null
        var bestOtherInside: HitResult? = null
        var bestUpwardOutside: HitResult? = null
        var bestOtherOutside: HitResult? = null

        for (r in results) {
            val t = r.trackable
            if (t is InstantPlacementPoint) continue
            if (t !is Plane) continue
            val inside = t.isPoseInPolygon(r.hitPose)
            if (!inside && !policy.allowOutsidePolygon) continue
            if (r.distance > policy.maxDistanceMeters) continue

            val hitY = r.hitPose.ty()
            val yBelow = policy.yBelowCameraMeters
            if (yBelow != null) {
                if (hitY > (cameraY - yBelow)) continue
            }

            val isUpward = t.type == Plane.Type.HORIZONTAL_UPWARD_FACING
            if (policy.requireUpwardFacing && !isUpward) continue

            if (isUpward) {
                if (inside) {
                    if (bestUpwardInside == null || r.distance < bestUpwardInside!!.distance - 0.0001f) bestUpwardInside = r
                } else {
                    if (bestUpwardOutside == null || r.distance < bestUpwardOutside!!.distance - 0.0001f) bestUpwardOutside = r
                }
            } else {
                if (inside) {
                    if (bestOtherInside == null || r.distance < bestOtherInside!!.distance - 0.0001f) bestOtherInside = r
                } else {
                    if (bestOtherOutside == null || r.distance < bestOtherOutside!!.distance - 0.0001f) bestOtherOutside = r
                }
            }
        }

        // Prefer inside-polygon hits. Only fall back to outside-polygon hits when necessary.
        val bestUpward = bestUpwardInside ?: bestUpwardOutside
        val bestOther = bestOtherInside ?: bestOtherOutside
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
        allowOutsidePolygon: Boolean = false,
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
                allowOutsidePolygon = allowOutsidePolygon,
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

        // Distance-adaptive grid (0~10m). Goal: grid/span/step never collapses to 0.
        data class Plan(val name: String, val gridX: Int, val gridY: Int, val spanRatio: Float)
        val CUP_DIAMETER_M = 0.108f // 4.25in golf cup
        val MIN_GRID_SPAN_PX = 12f
        val MIN_STEP_PX = 1f

        // 1) Estimate distance using a center plane hit (view-space coordinate, UV-adjusted).
        val centerHit =
            hitTestBestPlaneAtScreenPoint(
                frame = frame,
                screenX = cx,
                screenY = cy,
                maxDistanceMeters = maxHitDistanceMeters.coerceAtLeast(10f),
                preferUpwardFacing = preferUpwardFacing,
                // Distance estimate should not depend on plane polygon extent.
                allowOutsidePolygon = true,
                yBelowCameraMeters = yBelowCameraMeters
            )
        val dMeters = (centerHit?.distance ?: maxHitDistanceMeters).coerceIn(0.3f, 10f)

        // 2) Mode selection by distance
        val plan =
            when {
                dMeters < 1.0f -> Plan("NEAR_7x7", 7, 7, 0.80f)
                dMeters < 3.0f -> Plan("MID_5x5", 5, 5, 0.70f)
                dMeters < 6.0f -> Plan("FAR_3x3", 3, 3, 0.60f)
                else -> Plan("ULTRA_LINE_5", 5, 1, 0.55f) // 6~10m: keep minimal samples, maximize hit availability
            }

        // 3) Compute projected cup size in view pixels (keep Float throughout).
        // Fallback to view-percent span if intrinsics/dims are unavailable.
        val intr = frame.camera.textureIntrinsics
        val dims = intr.imageDimensions
        val texW = dims.getOrNull(0)?.toFloat()?.takeIf { it > 1f }
        val texH = dims.getOrNull(1)?.toFloat()?.takeIf { it > 1f }
        val fxTex = intr.focalLength.getOrNull(0)?.takeIf { it > 1e-6f }
        val fyTex = intr.focalLength.getOrNull(1)?.takeIf { it > 1e-6f }
        val projectedCupPxView: Float? =
            if (texW != null && texH != null && fxTex != null && fyTex != null) {
                val fxView = fxTex * (glW / texW)
                val fyView = fyTex * (glH / texH)
                val fView = (fxView + fyView) * 0.5f
                val px = (fView * CUP_DIAMETER_M) / dMeters
                if (px.isFinite() && px > 0f) px else null
            } else {
                null
            }

        val maxSpanPx = (min(glW, glH) * 0.25f).coerceAtLeast(MIN_GRID_SPAN_PX)
        val spanCandidate =
            (projectedCupPxView?.let { it * plan.spanRatio })
                ?: (2f * max(glW * offsetPercent, glH * offsetPercent))

        val minSpanForStepX = if (plan.gridX <= 1) 0f else MIN_STEP_PX * (plan.gridX - 1).toFloat()
        val minSpanForStepY = if (plan.gridY <= 1) 0f else MIN_STEP_PX * (plan.gridY - 1).toFloat()
        val minSpanPx = max(MIN_GRID_SPAN_PX, max(minSpanForStepX, minSpanForStepY))
        val spanPx = spanCandidate.coerceIn(minSpanPx, maxSpanPx)

        val gridHalfX = if (plan.gridX <= 1) 0f else (spanPx * 0.5f)
        val gridHalfY = if (plan.gridY <= 1) 0f else (spanPx * 0.5f)
        val stepX = if (plan.gridX <= 1) 0f else (spanPx / (plan.gridX - 1).toFloat()).coerceAtLeast(MIN_STEP_PX)
        val stepY = if (plan.gridY <= 1) 0f else (spanPx / (plan.gridY - 1).toFloat()).coerceAtLeast(MIN_STEP_PX)
        val gridHalf = max(gridHalfX, gridHalfY)
        val stepPx = max(stepX, stepY)

        val camY = frame.camera.pose.ty()
        val allowOutsidePolygonForFar = (plan.gridX <= 3) // FAR_3x3 or ULTRA_LINE_5
        val policy =
            PlanePolicy(
                maxDistanceMeters = maxHitDistanceMeters,
                preferUpwardFacing = preferUpwardFacing,
                requireUpwardFacing = requireUpwardFacing,
                allowOutsidePolygon = allowOutsidePolygonForFar,
                yBelowCameraMeters = yBelowCameraMeters
            )

        val total = plan.gridX * plan.gridY
        val selected = ArrayList<SelectedHit>(total)
        val distances = ArrayList<Float>(total)

        // Build samples directly in screen coords, but execute hitTest at UV-adjusted local coords.
        for (iy in 0 until plan.gridY) {
            val yOffset = if (plan.gridY <= 1) 0f else (-gridHalfY + (iy.toFloat() * stepY))
            val yScreen = cy + yOffset
            for (ix in 0 until plan.gridX) {
                val xOffset = if (plan.gridX <= 1) 0f else (-gridHalfX + (ix.toFloat() * stepX))
                val xScreen = cx + xOffset

                val pAdj = adjustedHitTestLocalPoint(frame, xScreen, yScreen) ?: continue
                val results = frame.hitTest(pAdj.x, pAdj.y)
                val hit = selectBestPlaneHit(results, policy, camY) ?: continue
                val pose = hit.hitPose
                selected.add(SelectedHit(hit = hit, pose = pose, hitDistanceMeters = hit.distance))
                distances.add(hit.distance)
            }
        }

        val valid = selected.size
        if (valid <= 0) {
            // Fallback: ensure "not 찍힘" is eliminated (at least a center hit if available).
            val fallbackPlaneHit =
                centerHit
                    ?: hitTestBestPlaneAtScreenPoint(
                        frame = frame,
                        screenX = cx,
                        screenY = cy,
                        maxDistanceMeters = maxHitDistanceMeters.coerceAtLeast(25f),
                        preferUpwardFacing = true,
                        allowOutsidePolygon = true,
                        yBelowCameraMeters = yBelowCameraMeters
                    )
            val fallbackHit =
                if (fallbackPlaneHit != null) {
                    fallbackPlaneHit
                } else {
                    val pAdj = adjustedHitTestLocalPoint(frame, cx, cy)
                    if (pAdj != null) {
                        val results = frame.hitTest(pAdj.x, pAdj.y)
                        pickBestDepthOrPoint(results, maxHitDistanceMeters.coerceAtLeast(25f))
                    } else {
                        null
                    }
                }
            val fallbackType =
                when {
                    fallbackHit == null -> HitType.NONE
                    fallbackHit.trackable is Plane -> HitType.PLANE
                    isDepthTrackable(fallbackHit.trackable) -> HitType.DEPTH
                    fallbackHit.trackable is Point -> HitType.POINT
                    else -> HitType.NONE
                }
            return Sample(
                bestHit = fallbackHit,
                hitType = fallbackType,
                validHits = if (fallbackHit != null) 1 else 0,
                totalPoints = total,
                gridHalfSpanPx = gridHalf,
                gridStepPx = stepPx,
                hitDistanceAvgMeters = fallbackHit?.distance,
                hitDistanceMaxMeters = fallbackHit?.distance,
                cameraY = camY,
                medianY = fallbackHit?.hitPose?.ty(),
                centerYOffsetApplied = true,
                gridPlan = plan.name,
                gridEstimatedDistanceMeters = dMeters,
                gridProjectedCupPx = projectedCupPxView,
                centerFallbackUsed = (fallbackHit != null)
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
            centerYOffsetApplied = true,
            gridPlan = plan.name,
            gridEstimatedDistanceMeters = dMeters,
            gridProjectedCupPx = projectedCupPxView,
            centerFallbackUsed = false
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

        // Collect sample points first (Ball robustness: 3x3 fixed patch for START).
        val samplePoints = ArrayList<PointF>(gridSize * gridSize)
        if (gridCount == 9) {
            // 3x3 patch around center: less sensitive to user centering error.
            for (iy in -1..1) {
                for (ix in -1..1) {
                    samplePoints.add(PointF(cx + (ix * BALL_GRID_STEP_PX), cy + (iy * BALL_GRID_STEP_PX)))
                }
            }
        } else {
            for (iy in 0 until gridSize) {
                val ty = if (gridSize == 1) 0.5f else iy.toFloat() / (gridSize - 1).toFloat()
                val yScreen = roiScreen.top + (ty * roiScreen.height())
                for (ix in 0 until gridSize) {
                    val tx = if (gridSize == 1) 0.5f else ix.toFloat() / (gridSize - 1).toFloat()
                    val xScreen = roiScreen.left + (tx * roiScreen.width())
                    samplePoints.add(PointF(xScreen, yScreen))
                }
            }
        }

        // Single transform batch.
        val screenPts = FloatArray(samplePoints.size * 2)
        var idxPt = 0

        // Normalize by GLSurfaceView size (hitTest expects view pixel coords).
        val glW = mapper.viewWidthPx().toFloat().takeIf { it > 1f } ?: 1f
        val glH = mapper.viewHeightPx().toFloat().takeIf { it > 1f } ?: 1f

        for (p in samplePoints) {
            val pLocal = mapper.screenToLocal(p)
            screenPts[idxPt++] = (pLocal.x / glW).coerceIn(0f, 1f)
            screenPts[idxPt++] = (pLocal.y / glH).coerceIn(0f, 1f)
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
        for (pt in samplePoints) {
                val xScreen = pt.x
                val yScreen = pt.y

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

        val bestPlane = pickBestByMedianDistance(plane)
        val bestDepth = pickBestByMedianDistance(depth)
        val bestPoint = pickBestByMedianDistance(point)

        return when {
            bestPlane != null -> Sample(bestPlane, HitType.PLANE, planeValid, samplePoints.size)
            bestDepth != null -> Sample(bestDepth, HitType.DEPTH, depthValid, samplePoints.size)
            bestPoint != null -> Sample(bestPoint, HitType.POINT, pointValid, samplePoints.size)
            else -> Sample(null, HitType.NONE, max(planeValid, max(depthValid, pointValid)), samplePoints.size)
        }
    }

    private fun pickBestByMedianDistance(cands: List<Candidate>): HitResult? {
        if (cands.isEmpty()) return null
        val sortedDist = cands.map { it.hit.distance }.sorted()
        val median =
            if (sortedDist.size % 2 == 1) {
                sortedDist[sortedDist.size / 2]
            } else {
                val i = sortedDist.size / 2
                (sortedDist[i - 1] + sortedDist[i]) * 0.5f
            }
        var best: Candidate? = null
        var bestDelta = Float.POSITIVE_INFINITY
        for (c in cands) {
            val delta = abs(c.hit.distance - median)
            if (delta < bestDelta - 0.0001f) {
                best = c
                bestDelta = delta
            } else if (abs(delta - bestDelta) <= 0.0001f) {
                // Tie-breaker: closer to ROI center
                if (best == null || c.distToRoiCenter < best!!.distToRoiCenter - 0.0001f) best = c
            }
        }
        return best?.hit
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

    private fun pickBestDepthOrPoint(results: List<HitResult>, maxDistanceMeters: Float): HitResult? {
        var bestDepth: HitResult? = null
        var bestPoint: HitResult? = null
        for (r in results) {
            val t = r.trackable
            if (t is InstantPlacementPoint) continue
            if (r.distance > maxDistanceMeters) continue
            if (isDepthTrackable(t)) {
                if (bestDepth == null || r.distance < bestDepth!!.distance - 0.0001f) bestDepth = r
            } else if (t is Point) {
                if (bestPoint == null || r.distance < bestPoint!!.distance - 0.0001f) bestPoint = r
            }
        }
        return bestDepth ?: bestPoint
    }

    private fun isDepthTrackable(trackable: Any): Boolean {
        val name = trackable.javaClass.name
        val simple = trackable.javaClass.simpleName
        return name == "com.google.ar.core.DepthPoint" || simple == "DepthPoint"
    }
}

