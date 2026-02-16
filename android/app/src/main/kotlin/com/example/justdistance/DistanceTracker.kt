package com.justdistance.measurepro

import android.util.Log
import com.google.ar.core.Pose
import kotlin.math.sqrt

class DistanceTracker {
    private val TAG = "DistanceTracker"

    enum class DistanceMode {
        XZ,
        XYZ
    }

    private var startPose: Pose? = null
    var isTracking: Boolean = false
        private set
    var mode: DistanceMode = DistanceMode.XZ

    fun startWithPose(pose: Pose): Boolean {
        startPose = pose
        isTracking = true
        Log.i(TAG, "✅ 거리 측정 시작 (모드: ${mode.name})")
        return true
    }

    fun stop() {
        isTracking = false
        Log.i(TAG, "✅ 거리 측정 중지")
    }

    fun reset() {
        startPose = null
        isTracking = false
        Log.i(TAG, "✅ 거리 추적기 리셋")
    }

    fun computeDistanceFromPose(current: Pose): Float? {
        if (!isTracking) return null
        val start = startPose ?: return null

        val dx = current.tx() - start.tx()
        val dz = current.tz() - start.tz()

        return when (mode) {
            DistanceMode.XZ -> sqrt(dx * dx + dz * dz)
            DistanceMode.XYZ -> {
                val dy = current.ty() - start.ty()
                sqrt(dx * dx + dy * dy + dz * dz)
            }
        }
    }

    fun computeHorizontalAndVerticalFromPose(current: Pose): Pair<Float, Float>? {
        if (!isTracking) return null
        val start = startPose ?: return null

        val dx = current.tx() - start.tx()
        val dz = current.tz() - start.tz()
        val dy = current.ty() - start.ty()

        val horizontalDistance = sqrt(dx * dx + dz * dz)
        // 수직 방향(ΔY)은 부호를 유지: 내려가면 음수, 올라가면 양수
        val verticalDistance = dy
        return Pair(horizontalDistance, verticalDistance)
    }
}

