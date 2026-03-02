package com.wmcho.puttingcaddie

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.max

class ViewFinderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class State { DEFAULT, STABILIZING }
    enum class QualityState { NONE, OK, NG }

    private fun dp(v: Float): Float = v * context.resources.displayMetrics.density
    private fun sp(v: Float): Float = v * context.resources.displayMetrics.scaledDensity

    // Golf UI: keep the square frame very subtle; emphasize the center target ring.
    private val strokeDefaultPx = dp(1f)
    private val strokeStabilizingPx = dp(1.5f)
    private val strokeFlashPx = dp(2.5f)
    private val crossStrokePx = dp(1.5f)
    private val ringStrokePx = dp(2.5f)

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeDefaultPx
        color = Color.WHITE
    }
    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = crossStrokePx
        color = Color.WHITE
        alpha = 180 // ~0.7
        strokeCap = Paint.Cap.SQUARE
    }
    private val crossOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = crossStrokePx + dp(1f)
        color = Color.BLACK
        alpha = 110
        strokeCap = Paint.Cap.SQUARE
    }
    private val qualityPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4CAF50")
        alpha = 230 // 0.9
        isSubpixelText = true
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStrokePx
        color = Color.WHITE
    }
    private val ringOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStrokePx + dp(1f)
        color = Color.BLACK
        alpha = 110
    }
    private val innerDotStrokePx = dp(1.2f)
    private val innerDotRadiusPx = dp(7f)
    private val innerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = innerDotStrokePx
        color = Color.WHITE
    }
    private val innerDotOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = innerDotStrokePx + dp(1f)
        color = Color.BLACK
        alpha = 110
    }

    private var state: State = State.DEFAULT
    private var flashUntilMs: Long = 0L
    private var flashColor: Int = Color.WHITE
    private var qualityState: QualityState = QualityState.NONE

    private var currentColor: Int = Color.WHITE
    private var colorAnim: ValueAnimator? = null

    private val rect = RectF()

    fun setState(s: State) {
        state = s
        invalidate()
    }

    fun setQualityState(q: QualityState) {
        if (qualityState != q) {
            qualityState = q
            invalidate()
        }
    }

    fun flashLock() {
        flashColor = Color.parseColor("#4CAF50")
        flashUntilMs = SystemClock.uptimeMillis() + 300L
        invalidate()
    }

    fun flashFail() {
        flashColor = Color.parseColor("#F44336")
        flashUntilMs = SystemClock.uptimeMillis() + 300L
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()

        val baseColor =
            when (state) {
                State.STABILIZING -> Color.parseColor("#FFF59D") // warning yellow
                State.DEFAULT -> {
                    // Golf UI: use accent green when quality is OK.
                    if (qualityState == QualityState.OK) ContextCompat.getColor(context, R.color.pc_crosshair_lime) else Color.WHITE
                }
            }
        val wantedColor = if (now <= flashUntilMs) flashColor else baseColor

        if (wantedColor != currentColor && (colorAnim?.isRunning != true)) {
            colorAnim?.cancel()
            colorAnim =
                ValueAnimator.ofArgb(currentColor, wantedColor).apply {
                    duration = 120L
                    addUpdateListener {
                        currentColor = it.animatedValue as Int
                        invalidate()
                    }
                    start()
                }
        } else if (colorAnim?.isRunning != true) {
            currentColor = wantedColor
        }

        val inFlash = now <= flashUntilMs
        borderPaint.strokeWidth =
            when {
                inFlash -> strokeFlashPx
                state == State.STABILIZING -> strokeStabilizingPx
                else -> strokeDefaultPx
            }
        borderPaint.color = currentColor
        borderPaint.alpha =
            when {
                inFlash -> 255
                state == State.STABILIZING -> 160
                else -> 90
            }

        val inset = borderPaint.strokeWidth * 0.5f
        rect.set(inset, inset, width.toFloat() - inset, height.toFloat() - inset)

        canvas.drawRect(rect, borderPaint)

        // Center ring (golf aiming aid) - primary target element.
        val cx = width * 0.5f
        val cy = height * 0.5f
        ringPaint.color = currentColor
        ringPaint.strokeWidth = ringStrokePx
        val screenW = context.resources.displayMetrics.widthPixels.toFloat().coerceAtLeast(1f)
        val wantedRadius = (screenW * 0.12f) * 0.5f
        val maxRadius = (kotlin.math.min(rect.width(), rect.height()) * 0.45f).coerceAtLeast(dp(12f))
        val radius = wantedRadius.coerceAtMost(maxRadius)
        canvas.drawCircle(cx, cy, radius, ringOutlinePaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)

        // Crosshair: slightly longer (requested), still centered on the ring.
        val hLen = max(radius * 1.25f, dp(14f))
        val vLen = max(radius * 1.75f, dp(18f))
        val baseAlpha = 150
        val alphaBoost = if (state == State.STABILIZING) 30 else 0
        crossPaint.color = currentColor
        crossPaint.alpha = (baseAlpha + alphaBoost).coerceAtMost(255)
        crossOutlinePaint.alpha = (crossPaint.alpha * 0.75f).toInt().coerceIn(0, 255)
        canvas.drawLine(cx - hLen, cy, cx + hLen, cy, crossOutlinePaint)
        canvas.drawLine(cx, cy - vLen, cx, cy + vLen, crossOutlinePaint)
        canvas.drawLine(cx - hLen, cy, cx + hLen, cy, crossPaint)
        canvas.drawLine(cx, cy - vLen, cx, cy + vLen, crossPaint)

        // 중심점: 십자가 교차부에 아주 작은 동그라미 (선 굵기 < 십자가)
        innerDotPaint.color = currentColor
        innerDotPaint.strokeWidth = innerDotStrokePx
        innerDotPaint.alpha = crossPaint.alpha
        innerDotOutlinePaint.alpha = (crossPaint.alpha * 0.75f).toInt().coerceIn(0, 255)
        canvas.drawCircle(cx, cy, innerDotRadiusPx, innerDotOutlinePaint)
        canvas.drawCircle(cx, cy, innerDotRadiusPx, innerDotPaint)

        if (now <= flashUntilMs || (colorAnim?.isRunning == true)) {
            postInvalidateOnAnimation()
        }
    }

    /**
     * Returns the actual drawn finder rect in Screen coordinates (px).
     * This is the ROI used for grid sampling.
     */
    fun getFinderRectOnScreen(out: RectF = RectF()): RectF {
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        out.set(
            loc[0].toFloat(),
            loc[1].toFloat(),
            (loc[0] + width).toFloat(),
            (loc[1] + height).toFloat()
        )
        return out
    }
}

