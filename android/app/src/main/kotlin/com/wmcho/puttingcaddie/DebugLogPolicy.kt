package com.wmcho.puttingcaddie

import android.util.Log

object DebugLogFlags {
    const val LOG_DEPTH_INFO = true
    const val LOG_DEPTH_DEBUG = false
    const val LOG_DEPTH_TRACE = false

    const val LOG_CUP_INFO = true
    const val LOG_CUP_DEBUG = true
    const val LOG_CUP_TRACE = false

    const val LOG_SLOPE_INFO = true
    const val LOG_SLOPE_DEBUG = true
    const val LOG_SLOPE_TRACE = false

    const val LOG_ZOOM_INFO = true
    const val LOG_ZOOM_DEBUG = true
    const val LOG_ZOOM_TRACE = false
}

class ChangeOnlyLogger<T>(
    private val tag: String
) {
    private var lastValue: T? = null

    fun logIfChanged(newValue: T, message: () -> String) {
        if (lastValue != newValue) {
            lastValue = newValue
            Log.d(tag, message())
        }
    }
}

enum class DepthState {
    UNSUPPORTED,
    DISABLED,
    NO_FRAME,
    LOW_QUALITY,
    OK,
    GOOD
}

enum class CupBlockedReasonBucket {
    NONE,
    PROJECTED_PX_SMALL,
    VALID_HITS_LOW,
    WORLD_SPREAD_LARGE,
    SIGMA_LARGE,
    ZOOM_TRANSIENT,
    TRACKING_NOT_READY,
    SAME_FRAME_ALIGN_FAIL,
    START_ANCHOR_MISSING,
    CANNOT_RETRY,
    CENTER_FALLBACK_USED,
    TRANSFORM_UNSTABLE,
    STABLE_FRAMES_INSUFFICIENT,
    PREPARING,
    UNKNOWN
}

