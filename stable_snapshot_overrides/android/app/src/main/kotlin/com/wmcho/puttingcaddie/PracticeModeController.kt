package com.wmcho.puttingcaddie

import kotlin.math.abs

enum class PracticeModeState {
    OFF,
    ACTIVE
}

data class PracticeSession(
    val targetDistanceMeters: Float,
    val toleranceMeters: Float = 0.2f,
    val startedAtMs: Long = System.currentTimeMillis(),
    val attemptCount: Int = 0,
    val successCount: Int = 0,
    val lastMeasuredDistanceMeters: Float? = null,
    val lastErrorMeters: Float? = null
)

data class PracticeUiModel(
    val modeState: PracticeModeState = PracticeModeState.OFF,
    val targetDistanceMeters: Float? = null,
    val currentDistanceMeters: Float? = null,
    val errorMeters: Float? = null,
    val isSuccess: Boolean = false,
    val attemptCount: Int = 0,
    val successCount: Int = 0,
    val toleranceMeters: Float = 0.2f
)

class PracticeModeController {
    private var session: PracticeSession? = null

    fun startPractice(targetDistanceMeters: Float, toleranceMeters: Float = 0.2f) {
        session = PracticeSession(
            targetDistanceMeters = targetDistanceMeters,
            toleranceMeters = toleranceMeters
        )
    }

    fun stopPractice() {
        session = null
    }

    fun isActive(): Boolean = session != null

    fun currentSession(): PracticeSession? = session

    fun recordMeasurement(distanceMeters: Float) {
        val current = session ?: return
        val error = distanceMeters - current.targetDistanceMeters
        val success = abs(error) <= current.toleranceMeters
        session = current.copy(
            attemptCount = current.attemptCount + 1,
            successCount = current.successCount + if (success) 1 else 0,
            lastMeasuredDistanceMeters = distanceMeters,
            lastErrorMeters = error
        )
    }

    fun buildUiModel(): PracticeUiModel {
        val current = session ?: return PracticeUiModel()
        val error = current.lastErrorMeters
        return PracticeUiModel(
            modeState = PracticeModeState.ACTIVE,
            targetDistanceMeters = current.targetDistanceMeters,
            currentDistanceMeters = current.lastMeasuredDistanceMeters,
            errorMeters = error,
            isSuccess = error != null && abs(error) <= current.toleranceMeters,
            attemptCount = current.attemptCount,
            successCount = current.successCount,
            toleranceMeters = current.toleranceMeters
        )
    }
}
