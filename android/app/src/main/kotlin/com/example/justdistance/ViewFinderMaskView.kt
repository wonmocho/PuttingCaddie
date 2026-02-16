package com.justdistance.measurepro

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Draws a GREEN mask over the preview, leaving a transparent "hole" at ViewFinder rect.
 * This makes the camera preview visible only through the ViewFinder area.
 */
class ViewFinderMaskView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#0B5E3B")
    }

    private var viewFinder: View? = null
    private val vfRect = Rect()
    private val vfRectF = RectF()
    private val tmpMaskLoc = IntArray(2)

    fun attachViewFinder(vf: View) {
        viewFinder = vf
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val vf = viewFinder
        if (vf == null) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }

        // ViewFinder rect in *screen* coords, then convert into this view's local coords.
        if (!vf.getGlobalVisibleRect(vfRect)) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            return
        }
        getLocationOnScreen(tmpMaskLoc)
        val offX = tmpMaskLoc[0]
        val offY = tmpMaskLoc[1]
        vfRectF.set(
            (vfRect.left - offX).toFloat(),
            (vfRect.top - offY).toFloat(),
            (vfRect.right - offX).toFloat(),
            (vfRect.bottom - offY).toFloat()
        )

        // Top
        canvas.drawRect(0f, 0f, width.toFloat(), vfRectF.top, paint)
        // Bottom
        canvas.drawRect(0f, vfRectF.bottom, width.toFloat(), height.toFloat(), paint)
        // Left
        canvas.drawRect(0f, vfRectF.top, vfRectF.left, vfRectF.bottom, paint)
        // Right
        canvas.drawRect(vfRectF.right, vfRectF.top, width.toFloat(), vfRectF.bottom, paint)
    }
}

