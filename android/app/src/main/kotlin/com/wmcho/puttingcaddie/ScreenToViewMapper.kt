package com.wmcho.puttingcaddie

import android.graphics.Matrix
import android.graphics.PointF
import android.view.View
/**
 * SSOT mapper: Screen(px) <-> targetView local(px)
 *
 * Canonical for hitTest in this project:
 * - Always call Frame.hitTest() with GLSurfaceView local pixel coordinates.
 * - ROI points are defined in Screen coordinates and inverse-mapped here.
 *
 * NOTE:
 * If the camera preview is rotated/mirrored at the texture(UV) level, we must apply the same
 * mapping to hitTest inputs so that rays match what the user sees.
 */
class ScreenToViewMapper(private val targetView: View) {
    private val tmpLoc = IntArray(2)
    private val localToScreen = Matrix()
    private val screenToLocal = Matrix()
    @Volatile private var dirty: Boolean = true

    // Screen-adjust parameters (applied to preview texture). Keep in sync with BackgroundRenderer.
    @Volatile var rotationDeg: Float = 0f   // [-180..180] allowed
    @Volatile var mirrorH: Boolean = false  // left<->right
    @Volatile var mirrorV: Boolean = false  // top<->bottom
    // Render zoom level (BackgroundRenderer.uZoom). Raycast path must apply inverse zoom.
    @Volatile var zoomLevel: Float = 1.0f

    fun markDirty() {
        dirty = true
    }

    fun viewWidthPx(): Int = targetView.width

    fun viewHeightPx(): Int = targetView.height

    private fun ensureMatrices() {
        if (!dirty) return
        dirty = false

        targetView.getLocationOnScreen(tmpLoc)
        val tx = tmpLoc[0].toFloat()
        val ty = tmpLoc[1].toFloat()

        localToScreen.reset()
        localToScreen.set(targetView.matrix)
        localToScreen.postTranslate(tx, ty)

        localToScreen.invert(screenToLocal)
    }

    fun screenToLocal(p: PointF): PointF {
        ensureMatrices()
        val pts = floatArrayOf(p.x, p.y)
        screenToLocal.mapPoints(pts)
        return PointF(pts[0], pts[1])
    }

    /**
     * Inverse of render zoom around view center.
     * Render uses: pZoomed = (pBase - c) * z + c
     * For raycast we need base coords: pBase = (pZoomed - c) / z + c
     */
    fun unzoomLocal(p: PointF): PointF {
        val z = zoomLevel.coerceAtLeast(1.0f)
        if (z <= 1.0001f) return p
        val w = targetView.width.toFloat()
        val h = targetView.height.toFloat()
        if (w <= 1f || h <= 1f) return p
        val cx = w * 0.5f
        val cy = h * 0.5f
        val x = ((p.x - cx) / z) + cx
        val y = ((p.y - cy) / z) + cy
        return PointF(x.coerceIn(0f, w), y.coerceIn(0f, h))
    }

    /**
     * Screen(px) -> glView local(px), then apply the SAME UV transform that the preview uses.
     * Result is used for Frame.hitTest() to match what the user sees on screen.
     */
    fun screenToHitTestLocal(p: PointF): PointF {
        val local = screenToLocal(p)
        val w = targetView.width.toFloat()
        val h = targetView.height.toFloat()
        if (w <= 1f || h <= 1f) return local

        // NOTE: Exact mapping requires Frame.transformCoordinates2d() round-trip.
        // This helper is kept only for backward compatibility; do not use for precise alignment.
        return local
    }
}

