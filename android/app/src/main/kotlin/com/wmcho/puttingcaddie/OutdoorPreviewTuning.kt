package com.wmcho.puttingcaddie

import android.util.Log

/**
 * 프리뷰 셰이더·UI 고대비용 튜닝 값. 거리/노출(AE) 로직과 분리.
 */
object OutdoorPreviewTuning {
    data class Params(
        val brightness: Float,
        val contrast: Float,
        val gamma: Float,
        val saturation: Float,
    )

    /** 기존 동작: 밝기 곱만 (contrast/gamma/saturation 중립) */
    val baseline = Params(brightness = 1.2f, contrast = 1.0f, gamma = 1.0f, saturation = 1.0f)

    /** 야외 직사광선: 대비·감마·채도 보정 (screenBrightness=1.0 유지) */
    val outdoorEnhanced =
        Params(brightness = 1.2f, contrast = 1.18f, gamma = 0.92f, saturation = 1.08f)

    fun active(): Params =
        if (ProductFlags.OUTDOOR_HIGH_VISIBILITY) outdoorEnhanced else baseline

    fun logOutdoorVisibility(screenBrightnessApplied: Float) {
        val p = active()
        Log.i(
            "OUTDOOR_VISIBILITY",
            "enhanced=${ProductFlags.OUTDOOR_HIGH_VISIBILITY} " +
                "screenBrightness=$screenBrightnessApplied " +
                "previewBrightness=${p.brightness} contrast=${p.contrast} " +
                "gamma=${p.gamma} saturation=${p.saturation}",
        )
    }
}
