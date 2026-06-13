package com.wmcho.puttingcaddie.slope

import android.graphics.PointF
import android.graphics.RectF
import com.google.ar.core.Frame

/**
 * Slope Input 2.0: 경사 입력 제공자 인터페이스.
 * Baseline(Plane)과 Experimental(LocalSurfaceFit)이 구현.
 */
interface SlopeInputProvider {
    val sourceId: String

    /**
     * ball/cup 주변 표면 입력 수집 후 경사 계산.
     * @param ballNormalFromPlane 기존 Plane 기반 ball normal (baseline만 사용)
     * @param cupNormalFromPlane 기존 Plane 기반 cup normal (baseline만 사용)
     */
    fun collect(
        ballPos: FloatArray,
        cupPos: FloatArray,
        frame: Frame?,
        roiScreen: RectF?,
        ballNormalFromPlane: FloatArray?,
        cupNormalFromPlane: FloatArray?,
        ballScreenCenter: PointF?,
        cupScreenCenter: PointF?,
        isXyzMode: Boolean,
        trackingGood: Boolean
    ): SlopeInputResult
}
