package com.wmcho.puttingcaddie

import android.graphics.RectF
import com.google.ar.core.Frame

class V31Engine(mapper: ScreenToViewMapper, debugLoggingEnabled: Boolean = false) : MeasurementEngine {
    private val sampler = V31HitSampler(mapper)
    private val stats = PoseStatsMad()
    private val sm = V31StateMachine(sampler, stats, debugLoggingEnabled = debugLoggingEnabled)

    override fun setAxisMode(axisMode: V31StateMachine.AxisMode) {
        sm.axisMode = axisMode
    }

    override fun onUiEvent(e: V31StateMachine.UiEvent, nowNs: Long) {
        sm.onUiEvent(e, nowNs)
    }

    override fun onFrame(frame: Frame, roiScreen: RectF, nowNs: Long): V31StateMachine.UiModel {
        return sm.tick(frame, roiScreen, nowNs)
    }

    override fun reset() {
        sm.onUiEvent(V31StateMachine.UiEvent.ResetPressed, System.nanoTime())
    }
}

