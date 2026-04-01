package com.wmcho.puttingcaddie

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import com.wmcho.puttingcaddie.ui.AimmingOverlayView

/**
 * Graphic slope result page (ported from PuttingCaddyPro).
 *
 * Shows cups (incl. half-cup) break guidance with golf green visualization.
 * Appears after measurement result. "분석 상세" opens ResultDataActivity (text data).
 */
class ProAimingResultActivity : AppCompatActivity() {

    private lateinit var aimingOverlay: AimmingOverlayView
    private lateinit var btnBack: Button
    private lateinit var btnData: Button
    private lateinit var tvSimulationBadge: android.widget.TextView
    private lateinit var tvDistanceDebug: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_aiming_result)

        aimingOverlay = findViewById(R.id.aiming_overlay)
        btnBack = findViewById(R.id.btn_back)
        btnData = findViewById(R.id.btn_data)
        tvSimulationBadge = findViewById(R.id.tv_simulation_badge)
        tvDistanceDebug = findViewById(R.id.tv_distance_debug)

        tvSimulationBadge.visibility = android.view.View.GONE

        val distBanner = intent.getStringExtra(EXTRA_DISTANCE_DEBUG_BANNER)
        if (!distBanner.isNullOrBlank()) {
            tvDistanceDebug.text = distBanner
            tvDistanceDebug.visibility = android.view.View.VISIBLE
        } else {
            tvDistanceDebug.visibility = android.view.View.GONE
        }

        // "재측정": return to measurement page and reset flow.
        btnBack.setOnClickListener {
            val i =
                Intent(this, DistanceMeasurementActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_FORCE_RESET, true)
                }
            startActivity(i)
            finish()
        }

        // "분석 상세": show ResultDataActivity (text debug data).
        btnData.setOnClickListener {
            val data = intent.getStringExtra(EXTRA_RESULT_TEXT_DATA) ?: "(데이터 없음)"
            startActivity(Intent(this, ResultDataActivity::class.java).apply {
                putExtra(ResultDataActivity.EXTRA_RESULT_DATA, data)
            })
        }

        applyExtrasToOverlay()
    }

    private fun applyExtrasToOverlay() {
        val it = intent ?: return

        val theta = it.getFloatExtra("theta", 0f)
        val phiDeg = it.getFloatExtra("phiDeg", 0f)
        val pitchDeg = it.getFloatExtra("pitchDeg", 0f)
        val confidence = it.getFloatExtra("confidence", 0f)
        val distanceCm = it.getFloatExtra("distanceCm", 0f)

        aimingOverlay.theta = theta.toDouble()
        aimingOverlay.phi = phiDeg.toDouble()
        aimingOverlay.pitch = pitchDeg.toDouble()
        aimingOverlay.confidence = confidence.toDouble()
        aimingOverlay.distanceCm = distanceCm.toDouble()

        aimingOverlay.slopeValid = it.getBooleanExtra("slopeValid", false)
        aimingOverlay.slopeSkippedReason = it.getStringExtra("slopeSkippedReason")
        aimingOverlay.slopeCm = if (it.hasExtra("slopeCm")) it.getFloatExtra("slopeCm", 0f) else 0f
        aimingOverlay.slopeDirection = it.getStringExtra("slopeDirection")

        aimingOverlay.resultLevel = it.getStringExtra("resultLevel") ?: "LEVEL_0"
        aimingOverlay.sideSlopeCups =
            if (aimingOverlay.slopeValid && it.hasExtra("sideSlopeCups")) it.getFloatExtra("sideSlopeCups", 0f) else null
        aimingOverlay.sideSlopeHighSide = if (aimingOverlay.slopeValid) it.getStringExtra("sideSlopeHighSide") else null
        aimingOverlay.aimCups =
            if (aimingOverlay.slopeValid && it.hasExtra("aimCups")) it.getFloatExtra("aimCups", 0f) else null
        aimingOverlay.aimDir = if (aimingOverlay.slopeValid) it.getStringExtra("aimDir") else null
        aimingOverlay.aimOffsetCmAbs =
            if (aimingOverlay.slopeValid && it.hasExtra("aimOffsetCmAbs")) it.getFloatExtra("aimOffsetCmAbs", Float.NaN).takeIf { v -> v.isFinite() } else null

        aimingOverlay.slopeDisplayText = it.getStringExtra("slopeText")
        aimingOverlay.aimDisplayText = it.getStringExtra("aimText")
        aimingOverlay.graphicSource = it.getStringExtra("graphicSource")
        aimingOverlay.lateralHidden = it.getBooleanExtra("lateralHidden", false)

        aimingOverlay.invalidate()
    }

    companion object {
        const val EXTRA_FORCE_RESET = "pro_force_reset"
        const val EXTRA_RESULT_TEXT_DATA = "result_text_data"
        /** [DistanceMeasurementActivity.buildGraphicBundleFromUiModel] — debug only */
        const val EXTRA_DISTANCE_DEBUG_BANNER = "distanceDebugBanner"

        fun newIntent(
            from: android.content.Context,
            extras: android.os.Bundle,
            resultTextData: String? = null
        ): Intent = Intent(from, ProAimingResultActivity::class.java).apply {
            putExtras(extras)
            resultTextData?.let { putExtra(EXTRA_RESULT_TEXT_DATA, it) }
        }
    }
}
