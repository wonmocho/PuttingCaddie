package com.wmcho.puttingcaddie.slope

import android.graphics.PointF
import android.graphics.RectF
import com.google.ar.core.Frame
import com.wmcho.puttingcaddie.SlopeComputer

/**
 * Slope Input 2.0: 기존 Plane 기반 경사 입력.
 * groundPlaneModel.normal, cupPlaneNormal 사용. 비교용 baseline.
 */
object PlaneBaselineInputProvider : SlopeInputProvider {
    override val sourceId: String = "PLANE_BASED"

    override fun collect(
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
    ): SlopeInputResult {
        val sd = SlopeComputer.compute(
            ballPos = ballPos,
            cupPos = cupPos,
            ballNormalRaw = ballNormalFromPlane,
            cupNormalRaw = cupNormalFromPlane,
            isXyzMode = isXyzMode,
            trackingGood = trackingGood
        )
        return SlopeInputResult(
            ballNormal = sd.ballNormal,
            cupNormal = sd.cupNormal,
            refNormal = sd.refNormal,
            forwardPct = sd.forwardPct,
            lateralPct = sd.lateralPct,
            quality = sd.quality,
            rejectReason = sd.blockedReason,
            sourceId = sourceId,
            sampleCountBall = 0,
            sampleCountCup = 0,
            validSampleRatio = 0f,
            fitResidualBall = null,
            fitResidualCup = null,
            sampleSourceTypes = "PLANE"
        )
    }
}
