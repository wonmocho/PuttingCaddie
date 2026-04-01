package com.wmcho.puttingcaddie.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.util.TypedValue
import android.view.View
import com.wmcho.puttingcaddie.R
import java.util.Locale
import kotlin.math.*

/**
 * Graphic putting result overlay (ported from PuttingCaddyPro / lie_caddy_v1).
 *
 * - Golf green background + grid
 * - Cup + guides + ball
 * - Center info panel (distance / up-down / side slope(=aim) / confidence)
 * - Cup-unit guidance including half-cup
 */
class AimmingOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val TAG = "AimmingOverlayView"

    // Input values (compatible with lie_caddy_v1)
    var theta: Double = 0.0
    var phi: Double = 0.0
    var pitch: Double = 0.0
    var confidence: Double = 0.0
    var distanceCm: Double = 0.0
    /** When false, slope was not computed (e.g. missing_plane_normal). Show "경사 미측정" instead of "평지". */
    var slopeValid: Boolean = false
    var slopeSkippedReason: String? = null
    var slopeCm: Float = 0f
    var slopeDirection: String? = null
    var aimOffsetCmAbs: Float? = null

    var measurementCase: String? = null

    var resultLevel: String = "LEVEL_0"
    var sideSlopeCups: Float? = null
    var sideSlopeHighSide: String? = null // "좌측 높음" | "우측 높음"
    var aimCups: Float? = null
    var aimDir: String? = null // "좌" | "우"

    /** [DistanceMeasurementActivity] 그래픽 번들에서 미리 포맷한 문구가 있으면 우선 사용 (SharedP3 우선 경로). */
    var slopeDisplayText: String? = null
    var aimDisplayText: String? = null
    var graphicSource: String? = null
    /** 제품 정책: 측면 에이밍 오프셋·문구 숨김(상하경사만). */
    var lateralHidden: Boolean = false

    private var thetaDeg: Float = 0f
    private var phiDeg: Float = 0f
    private var pitchDeg: Float = 0f
    private var hasSlopeData: Boolean = false
    private var arrowAngle: Float = 0f

    private var aimingOffsetCm: Double = 0.0
    private var calculatedDistanceM: Double = 0.0

    private val ballPoint: PointF
        get() = PointF(width / 2f, height * 0.75f + dp(40f))
    private val cupPoint: PointF
        get() = PointF(width / 2f, height * 0.25f)

    private var animationProgress: Float = 1.0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        try {
            thetaDeg = theta.toFloat()
            phiDeg = phi.toFloat()
            pitchDeg = pitch.toFloat()
            hasSlopeData = slopeValid || thetaDeg > 0.01f || kotlin.math.abs(phiDeg) > 0.01f
            arrowAngle = Math.toRadians(phiDeg.toDouble()).toFloat()
            calculatedDistanceM = distanceCm / 100.0

            val alpha = 255

            drawGolfGreenBackground(canvas, alpha)

            val puttingLinePaint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb(alpha, 41, 182, 246)
                    strokeWidth = dp(3f)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                }
            canvas.drawLine(ballPoint.x, ballPoint.y, cupPoint.x, cupPoint.y, puttingLinePaint)

            calculateAimingPoint()
            drawGolfBallWithDimples(canvas, ballPoint.x, ballPoint.y, alpha)
            drawCupGuides(canvas, cupPoint.x, cupPoint.y, alpha)
            drawHoleWithFlag(canvas, cupPoint.x, cupPoint.y, alpha)

            val aimingPoint = calculateAimingPointPosition()
            if (aimingPoint != null) {
                drawBallToAimingLine(canvas, ballPoint.x, ballPoint.y, aimingPoint.x, aimingPoint.y, alpha)
                drawCupToAimingLine(canvas, cupPoint.x, cupPoint.y, aimingPoint.x, aimingPoint.y, alpha)
                drawAimingPointGolfStyle(canvas, cupPoint.x, cupPoint.y, alpha)
            } else {
                drawAimingPointGolfStyle(canvas, cupPoint.x, cupPoint.y, alpha)
            }

            drawResultModeSlopeInfo(canvas, alpha)
            drawLevelMessage(canvas, alpha)

            val ballRadius = dp(10f)
            val cupRadius = ballRadius * 2.5f
            val cupDiameter = cupRadius * 2f
            drawCupDimensionLine(canvas, cupPoint.x, cupPoint.y, cupDiameter, alpha)
        } catch (e: Exception) {
            Log.e(TAG, "onDraw error: ${e.message}", e)
        }
    }

    private fun drawGolfGreenBackground(canvas: Canvas, alpha: Int) {
        val baseGreenPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 46, 125, 50)
                style = Paint.Style.FILL
            }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), baseGreenPaint)
        drawSlopeGrid(canvas, alpha)
    }

    private fun drawSlopeGrid(canvas: Canvas, alpha: Int) {
        if (!hasSlopeData) return

        val ballRadius = dp(10f)
        val cupRadius = ballRadius * 2.5f
        val cupDiameter = cupRadius * 2f
        val cellSize = cupDiameter

        val cupScreenX = width / 2f
        val cupScreenY = height * 0.25f

        val gridStartX = cupScreenX - (cellSize / 2f)
        val gridStartY = cupScreenY - (cellSize / 2f)

        val leftCells = (gridStartX / cellSize).toInt() + 2
        val topCells = (gridStartY / cellSize).toInt() + 2
        val rightCells = ((width - gridStartX) / cellSize).toInt() + 2
        val bottomCells = ((height - gridStartY) / cellSize).toInt() + 2

        val totalCols = leftCells + rightCells
        val totalRows = topCells + bottomCells

        val startColIndex = -leftCells
        val startRowIndex = -topCells

        val baseGreenColor = Color.argb(alpha, 76, 153, 0)
        val cellPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = baseGreenColor
                style = Paint.Style.FILL
            }

        for (i in startColIndex until (startColIndex + totalCols)) {
            for (j in startRowIndex until (startRowIndex + totalRows)) {
                val cellX = gridStartX + (i * cellSize)
                val cellY = gridStartY + (j * cellSize)

                if (cellX + cellSize < 0 || cellX > width || cellY + cellSize < 0 || cellY > height) continue

                val drawX = cellX.coerceAtLeast(0f)
                val drawY = cellY.coerceAtLeast(0f)
                val drawWidth = (cellX + cellSize).coerceAtMost(width.toFloat()) - drawX
                val drawHeight = (cellY + cellSize).coerceAtMost(height.toFloat()) - drawY

                if (drawWidth > 0 && drawHeight > 0) {
                    canvas.drawRect(drawX, drawY, drawX + drawWidth, drawY + drawHeight, cellPaint)
                    val gridPaint =
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.argb((alpha * 0.15f).toInt(), 255, 255, 255)
                            strokeWidth = dp(0.5f)
                            style = Paint.Style.STROKE
                        }
                    canvas.drawRect(drawX, drawY, drawX + drawWidth, drawY + drawHeight, gridPaint)
                }
            }
        }
    }

    private fun drawCupGuides(canvas: Canvas, cupX: Float, cupY: Float, alpha: Int) {
        val ballRadius = dp(10f)
        val cupRadius = ballRadius * 2.5f
        val cupDiameter = cupRadius * 2f

        val guideCupPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.3f).toInt(), 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
            }

        val columnLinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.6f).toInt(), 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = dp(0.5f)
            }

        val horizontalLinePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.6f).toInt(), 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = columnLinePaint.strokeWidth
            }

        val leftEdge = cupX - 4f * cupDiameter - cupRadius
        val rightEdge = cupX + 4f * cupDiameter + cupRadius
        canvas.drawLine(leftEdge, cupY, rightEdge, cupY, horizontalLinePaint)

        for (i in -4..4) {
            if (i == 0) continue
            val guideCupX = cupX + (i * cupDiameter)
            canvas.drawCircle(guideCupX, cupY, cupRadius, guideCupPaint)

            val lineLen = dp(22f) + mm(8f)
            canvas.drawLine(guideCupX, cupY - lineLen, guideCupX, cupY + lineLen, columnLinePaint)
        }
    }

    private fun drawHoleWithFlag(canvas: Canvas, cupX: Float, cupY: Float, alpha: Int) {
        val ballRadius = dp(10f)
        val cupRadius = ballRadius * 2.5f

        val cupPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 0, 0, 0)
                style = Paint.Style.FILL
            }
        canvas.drawCircle(cupX, cupY, cupRadius, cupPaint)

        val rimPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.85f).toInt(), 255, 255, 255)
                style = Paint.Style.STROKE
                strokeWidth = dp(2f)
            }
        canvas.drawCircle(cupX, cupY, cupRadius, rimPaint)

        val flagPolePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 255, 255, 255)
                strokeWidth = dp(2f)
                style = Paint.Style.STROKE
            }

        val poleTopY = cupY - dp(70f)
        canvas.drawLine(cupX, cupY - cupRadius, cupX, poleTopY, flagPolePaint)

        val flagPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 244, 67, 54)
                style = Paint.Style.FILL
            }
        val flagW = dp(38f)
        val flagH = dp(22f)
        val path = Path()
        path.moveTo(cupX, poleTopY)
        path.lineTo(cupX + flagW, poleTopY + flagH * 0.25f)
        path.lineTo(cupX, poleTopY + flagH)
        path.close()
        canvas.drawPath(path, flagPaint)
    }

    private fun drawGolfBallWithDimples(canvas: Canvas, x: Float, y: Float, alpha: Int) {
        val r = dp(10f)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 255, 255, 255)
                style = Paint.Style.FILL
            }
        canvas.drawCircle(x, y, r, paint)

        val shadow =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.25f).toInt(), 0, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = dp(1.5f)
            }
        canvas.drawCircle(x, y, r, shadow)

        val dPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.25f).toInt(), 0, 0, 0)
                style = Paint.Style.STROKE
                strokeWidth = dp(1f)
            }
        val d = dp(4f)
        canvas.drawCircle(x - d, y - d, dp(2f), dPaint)
        canvas.drawCircle(x + d, y - d, dp(2f), dPaint)
        canvas.drawCircle(x, y + d, dp(2f), dPaint)
    }

    private fun drawAimingPointGolfStyle(canvas: Canvas, cupX: Float, cupY: Float, alpha: Int) {
        val aimingPoint = calculateAimingPointPosition() ?: return
        val r = dp(9f)
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 33, 150, 243)
                style = Paint.Style.FILL
            }
        canvas.drawCircle(aimingPoint.x, aimingPoint.y, r, paint)
    }

    private fun drawBallToAimingLine(canvas: Canvas, bx: Float, by: Float, ax: Float, ay: Float, alpha: Int) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.8f).toInt(), 255, 235, 59)
                strokeWidth = dp(2.5f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        canvas.drawLine(bx, by, ax, ay, paint)
    }

    private fun drawCupToAimingLine(canvas: Canvas, cx: Float, cy: Float, ax: Float, ay: Float, alpha: Int) {
        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.9f).toInt(), 244, 67, 54)
                strokeWidth = dp(2.5f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }
        canvas.drawLine(cx, cy, ax, ay, paint)
    }

    private fun drawCupDimensionLine(canvas: Canvas, cupX: Float, cupY: Float, cupDiameter: Float, alpha: Int) {
        // 치수선: 왼쪽 둘째·셋째 원(가이드컵) 중심 간 거리 = 10.8cm
        val x0 = cupX - 3f * cupDiameter
        val x1 = cupX - 2f * cupDiameter
        val centerX = (x0 + x1) / 2f
        val y = cupY + dp(40f) + mm(5f)

        val linePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.7f).toInt(), 255, 255, 255)
                strokeWidth = dp(1.5f)
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
            }

        // 수평 치수선
        canvas.drawLine(x0, y, x1, y, linePaint)

        // 양끝 화살표 (쌍방향 화살표)
        val arrowSize = dp(6f)
        val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((alpha * 0.7f).toInt(), 255, 255, 255)
            style = Paint.Style.FILL
        }
        val arrowPath = Path()
        // 왼쪽 화살표 (→)
        arrowPath.moveTo(x0, y)
        arrowPath.lineTo(x0 + arrowSize, y - arrowSize * 0.6f)
        arrowPath.lineTo(x0 + arrowSize, y + arrowSize * 0.6f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)
        // 오른쪽 화살표 (←)
        arrowPath.reset()
        arrowPath.moveTo(x1, y)
        arrowPath.lineTo(x1 - arrowSize, y - arrowSize * 0.6f)
        arrowPath.lineTo(x1 - arrowSize, y + arrowSize * 0.6f)
        arrowPath.close()
        canvas.drawPath(arrowPath, arrowPaint)

        // 치수값 "10.8cm" (치수선 위 중앙)
        val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.8f).toInt(), 255, 255, 255)
                textSize = dp(12f)
                textAlign = Paint.Align.CENTER
            }
        canvas.drawText(resources.getString(R.string.cup_dimension_value), centerX, y - dp(12f), textPaint)
    }

    private fun calculateAimingPoint() {
        if (lateralHidden) {
            aimingOffsetCm = 0.0
            return
        }
        if (aimCups == null || aimDir == null) {
            aimingOffsetCm = 0.0
            return
        }
        val cm = (kotlin.math.abs(aimCups!!) * 10.8f).toDouble()
        aimingOffsetCm =
            when (aimDir) {
                "좌" -> -cm
                "우" -> cm
                else -> 0.0
            }
    }

    private fun calculateAimingPointPosition(): PointF? {
        if (lateralHidden) return null
        if (aimCups == null || aimDir == null) return null
        val ballRadius = dp(10f)
        val cupRadius = ballRadius * 2.5f
        val cupDiameter = cupRadius * 2f

        val cups = kotlin.math.abs(aimCups!!)
        if (cups <= 0f) return null

        val screenOffsetX =
            when (aimDir) {
                "좌" -> -cups * cupDiameter
                "우" -> cups * cupDiameter
                else -> 0f
            }
        return PointF(cupPoint.x + screenOffsetX, cupPoint.y)
    }

    private fun drawResultModeSlopeInfo(canvas: Canvas, alpha: Int) {
        val label1 = resources.getString(R.string.label_distance)
        val label2 = resources.getString(R.string.label_updown_slope)
        val label3 = resources.getString(R.string.label_side_slope)
        val label4 = resources.getString(R.string.label_confidence)

        val distanceMValue = if (distanceCm > 0) (distanceCm / 100.0) else null
        val value1 = if (distanceMValue != null) String.format(Locale.getDefault(), "%.1f m", distanceMValue) else "--"

        val isKo = Locale.getDefault().language == "ko"
        val slopeNotMeasured = resources.getString(R.string.slope_not_measured)
        val value2 =
            if (!slopeDisplayText.isNullOrBlank()) {
                slopeDisplayText!!
            } else if (!slopeValid) {
                slopeNotMeasured
            } else {
                val signed = slopeCm
                val absCm = kotlin.math.abs(signed)
                when {
                    !absCm.isFinite() -> "--"
                    absCm < 1f -> if (isKo) "평지" else "Flat"
                    signed > 0f -> if (isKo) "오르막 ${String.format(Locale.getDefault(), "%.1f", absCm)}cm" else "Uphill ${String.format(Locale.getDefault(), "%.1f", absCm)}cm"
                    else -> if (isKo) "내리막 ${String.format(Locale.getDefault(), "%.1f", absCm)}cm" else "Downhill ${String.format(Locale.getDefault(), "%.1f", absCm)}cm"
                }
            }

        val value3 =
            if (!aimDisplayText.isNullOrBlank()) {
                aimDisplayText!!
            } else if (!slopeValid) {
                slopeNotMeasured
            } else if (aimCups != null && aimDir != null && aimCups!!.isFinite()) {
                val cups = kotlin.math.abs(aimCups!!)
                val dirKo = if (aimDir == "좌") "좌측" else "우측"
                val dirEn = if (aimDir == "좌") "LEFT" else "RIGHT"
                val off = aimOffsetCmAbs
                val offText = if (off != null && off.isFinite() && off > 0f) " (${String.format(Locale.getDefault(), "%.1f", off)}cm)" else ""
                if (isKo) {
                    "에이밍 $dirKo ${String.format(Locale.getDefault(), "%.1f", cups)}컵$offText"
                } else {
                    "Aim $dirEn ${String.format(Locale.getDefault(), "%.1f", cups)} cups$offText"
                }
            } else {
                if (isKo) "직선" else "Straight"
            }

        val value4 =
            if (distanceMValue != null) {
                String.format(Locale.getDefault(), "%.2f", confidence)
            } else {
                "--"
            }

        val labelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 255, 255, 255)
                textSize = dp(18f)
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        val valuePaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 255, 255, 255)
                textSize = dp(18f)
                textAlign = Paint.Align.LEFT
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }

        val labelMaxW =
            maxOf(
                labelPaint.measureText(label1),
                labelPaint.measureText(label2),
                labelPaint.measureText(label3),
                labelPaint.measureText(label4)
            )

        val fm = labelPaint.fontMetrics
        val lineHeight = (fm.bottom - fm.top)
        val padding = dp(12f)
        val lineSpacing = dp(6f)

        val panelW = min(width.toFloat() - dp(40f), dp(520f))
        val panelX = (width - panelW) / 2f
        val panelTop = (height * 0.43f).coerceAtLeast(dp(140f))
        val panelH = padding * 2f + lineHeight * 4f + lineSpacing * 3f
        val panelRect = RectF(panelX, panelTop, panelX + panelW, panelTop + panelH)

        val panelPaint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.25f).toInt(), 0, 0, 0)
                style = Paint.Style.FILL
            }
        canvas.drawRoundRect(panelRect, dp(16f), dp(16f), panelPaint)

        val colonX = panelRect.left + padding + labelMaxW + dp(10f)
        val labelX = panelRect.left + padding
        val valueX = colonX + dp(12f)
        var y = panelRect.top + padding - fm.top

        fun drawRow(l: String, v: String) {
            canvas.drawText(l, labelX, y, labelPaint)
            canvas.drawText(":", colonX, y, labelPaint)
            canvas.drawText(v, valueX, y, valuePaint)
            y += lineHeight + lineSpacing
        }

        drawRow(label1, value1)
        drawRow(label2, value2)
        drawRow(label3, value3)
        drawRow(label4, value4)
    }

    private fun drawLevelMessage(canvas: Canvas, alpha: Int) {
        val msg =
            when (resultLevel) {
                "LEVEL_0" -> resources.getString(R.string.level0_message)
                "LEVEL_1" -> resources.getString(R.string.level1_message)
                "LEVEL_2" -> resources.getString(R.string.level2_message)
                "LEVEL_3" -> resources.getString(R.string.level3_message)
                else -> resources.getString(R.string.level0_message)
            }

        val paint =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb((alpha * 0.9f).toInt(), 255, 255, 255)
                textSize = dp(16f)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        canvas.drawText(msg, width / 2f, height * 0.82f + mm(3f), paint)
    }

    private fun dp(v: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    }

    private fun mm(v: Float): Float {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_MM, v, resources.displayMetrics)
    }
}
