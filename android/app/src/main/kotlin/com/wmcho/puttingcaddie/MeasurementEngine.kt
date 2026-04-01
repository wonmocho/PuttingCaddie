package com.wmcho.puttingcaddie

import android.graphics.RectF
import com.google.ar.core.Frame

interface MeasurementEngine {
    fun setAxisMode(axisMode: V31StateMachine.AxisMode)

    fun onUiEvent(e: V31StateMachine.UiEvent, nowNs: Long)

    fun onFrame(frame: Frame, roiScreen: RectF, nowNs: Long): V31StateMachine.UiModel

    fun reset()

    /** 필드 테스트 prefs → experimental slope 로그 상관용 (V31만 사용, 나머지 엔진은 무시) */
    fun setSlopeTestLogContext(sessionId: String?, repeatIndex: Int?, targetScenario: String?) {}
}

