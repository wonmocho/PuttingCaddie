package com.wmcho.puttingcaddie

import android.graphics.PointF
import android.graphics.Rect

data class Matrix3x3(val values: FloatArray) {
    init {
        require(values.size == 9) { "Matrix3x3 requires 9 values" }
    }

    fun map(point: PointF): PointF {
        val x = point.x
        val y = point.y
        val m = values
        val wx = (m[0] * x) + (m[1] * y) + m[2]
        val wy = (m[3] * x) + (m[4] * y) + m[5]
        val w = (m[6] * x) + (m[7] * y) + m[8]
        return if (kotlin.math.abs(w) > 1e-6f) PointF(wx / w, wy / w) else PointF(wx, wy)
    }

    companion object {
        fun identity(): Matrix3x3 = Matrix3x3(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))
    }
}

data class MeasurementTransform(
    val zoomRatio: Float,
    val cropRect: Rect,
    val sensorToPreview: Matrix3x3,
    val previewToSensor: Matrix3x3,
    val intrinsicsFxFyCxCy: FloatArray,
    val displayRotation: Int,
    val transformVersion: Long,
    val timestampNs: Long,
    val sourceFrameId: Long
)

data class CupDetectionSample(
    val centerPreviewPx: PointF,
    val centerSensorPx: PointF,
    val detectorFrameTimestampNs: Long,
    val transformVersion: Long,
    val sourceFrameId: Long
)

data class RaycastSample(
    val worldPoint: FloatArray,
    val arFrameTimestampNs: Long,
    val transformVersion: Long,
    val sourceFrameId: Long,
    val hitDistanceM: Float
)

data class ExperimentalThresholds(
    val minProjectedCupPxDistance: Float = 40f,
    val minProjectedCupPxSlope: Float = 52f,
    val maxZoomRatio: Float = 2.5f,
    val maxWorldSpreadM: Float = 0.03f,
    val maxCenterStdPx: Float = 4f,
    val minStableFrames: Int = 8,
    val minStabilizeMs: Long = 450L,
    val maxFrameDeltaMs: Long = 33L
)

enum class ConfirmRejectReason {
    FRAME_TIMESTAMP_MISMATCH,
    CUP_TOO_SMALL,
    CENTER_UNSTABLE,
    WORLD_SPREAD_LARGE,
    NOT_ENOUGH_STABLE_FRAMES,
    STABILIZING_TIME_SHORT,
    TRANSFORM_VERSION_MISMATCH,
    TRANSFORM_VERSION_CHANGED,
    TRANSFORM_NOT_STABLE
}

