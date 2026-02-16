package com.justdistance.measurepro

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

