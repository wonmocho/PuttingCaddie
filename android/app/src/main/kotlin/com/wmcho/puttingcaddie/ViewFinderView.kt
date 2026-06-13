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

    // 야외 햇빛 가시성: 형광 링 조준표시 (lime #B4FF00, glow + 검은 외곽선)
    private var outdoorHighVisibility = false
    private val strokeDefaultPx get() = if (outdoorHighVisibility) dp(2.5f) else dp(1f)
    private val strokeStabilizingPx get() = if (outdoorHighVisibility) dp(2.5f) else dp(1.5f)
    private val strokeFlashPx get() = if (outdoorHighVisibility) dp(7f) else dp(5f)
    private val ringStrokePx get() = if (outdoorHighVisibility) dp(6f) else dp(4f)
    private val ringRadiusPx get() = if (outdoorHighVisibility) dp(32f) else dp(28f)
    private val ringGlowExtraPx get() = if (outdoorHighVisibility) dp(6f) else dp(4f)
    private val ringOutlineExtraPx get() = if (outdoorHighVisibility) dp(2.5f) else dp(1.5f)
    private val centerDotRadiusPx get() = if (outdoorHighVisibility) dp(4f) else dp(3f)
    private val LIME_COLOR = Color.parseColor("#B4FF00")

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokeDefaultPx
        color = Color.WHITE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = ringStrokePx
        color = LIME_COLOR
    }
    private val ringGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = LIME_COLOR
        alpha = 60
    }
    private val ringOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        alpha = 200
    }
    private val centerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = LIME_COLOR  // 야외 가시성: 링과 동일 형광 lime
    }

    private var state: State = State.DEFAULT
    private var flashUntilMs: Long = 0L
    private var flashColor: Int = Color.WHITE
    private var qualityState: QualityState = QualityState.NONE

    private var currentColor: Int = Color.WHITE
    private var colorAnim: ValueAnimator? = null

    private val rect = RectF()
    private val flashOverlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setOutdoorHighVisibility(enabled: Boolean) {
        if (outdoorHighVisibility != enabled) {
            outdoorHighVisibility = enabled
            invalidate()
        }
    }

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
        flashUntilMs = SystemClock.uptimeMillis() + 500L  // 300→500: 더 오래 보이도록
        invalidate()
    }

    fun flashFail() {
        flashColor = Color.parseColor("#F44336")
        flashUntilMs = SystemClock.uptimeMillis() + 500L
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
        val flashElapsed = if (inFlash) flashUntilMs - now else 0L
        val flashStart = flashUntilMs - 500L
        val flashOverlayAlpha = when {
            inFlash && now < flashStart + 100 -> 70  // 첫 100ms 가장 밝게
            inFlash -> (20 + (flashElapsed / 500f) * 50).toInt().coerceIn(15, 70)  // 서서히 감쇠
            else -> 0
        }
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
                state == State.STABILIZING -> if (outdoorHighVisibility) 200 else 160
                else -> if (outdoorHighVisibility) 150 else 90
            }

        val inset = borderPaint.strokeWidth * 0.5f
        rect.set(inset, inset, width.toFloat() - inset, height.toFloat() - inset)

        // capture 시 강조: 사각 위 반짝 오버레이 (카메라 찍힘 느낌)
        if (flashOverlayAlpha > 0) {
            flashOverlayPaint.color = flashColor
            flashOverlayPaint.alpha = flashOverlayAlpha
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashOverlayPaint)
        }
        canvas.drawRect(rect, borderPaint)

        // 야외 햇빛 가시성: 형광 링 항상 lime (#B4FF00) - 햇빛에서 잘 보이도록
        val cx = width * 0.5f
        val cy = height * 0.5f
        ringPaint.color = LIME_COLOR
        ringPaint.strokeWidth = ringStrokePx
        ringPaint.alpha = 255
        ringGlowPaint.color = LIME_COLOR
        ringGlowPaint.strokeWidth = ringStrokePx + ringGlowExtraPx
        ringGlowPaint.alpha = if (inFlash) 160 else if (outdoorHighVisibility) 100 else 80
        ringOutlinePaint.strokeWidth = ringStrokePx + ringOutlineExtraPx
        val radius = ringRadiusPx.coerceAtMost(kotlin.math.min(rect.width(), rect.height()) * 0.4f)
        canvas.drawCircle(cx, cy, radius, ringOutlinePaint)
        canvas.drawCircle(cx, cy, radius, ringGlowPaint)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, centerDotRadiusPx, centerDotPaint)

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

