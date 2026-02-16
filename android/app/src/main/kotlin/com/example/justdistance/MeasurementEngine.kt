package com.justdistance.measurepro

import android.graphics.RectF
import com.google.ar.core.Frame

interface MeasurementEngine {
    fun setAxisMode(axisMode: V31StateMachine.AxisMode)

    fun onUiEvent(e: V31StateMachine.UiEvent, nowNs: Long)

    fun onFrame(frame: Frame, roiScreen: RectF, nowNs: Long): V31StateMachine.UiModel

    fun reset()
}

