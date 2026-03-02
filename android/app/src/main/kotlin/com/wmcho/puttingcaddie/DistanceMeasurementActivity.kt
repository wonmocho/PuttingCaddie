package com.wmcho.puttingcaddie

import android.Manifest
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.YuvImage
import android.graphics.Matrix
import android.content.res.ColorStateList
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.RelativeSizeSpan
import android.text.style.ReplacementSpan
import android.util.Log
import android.view.LayoutInflater
import android.view.HapticFeedbackConstants
import android.view.View
import android.animation.ObjectAnimator
import android.animation.Animator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.interpolator.view.animation.FastOutSlowInInterpolator
import com.google.android.material.button.MaterialButton
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.android.play.core.review.ReviewManagerFactory
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class DistanceMeasurementActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    enum class UnitMode {
        METER,
        YARD
    }

    data class AccuracyRange(
        val distanceRange: String,
        val xzError: String,
        val xyzError: String,
        val reliability: String
    )

    companion object {
        private const val PREFS_NAME = "distancemeter_prefs"
        private const val KEY_LANG = "lang" // "ko" | "en"
        private const val KEY_UNIT = "unit_mode" // "m" | "yd"

        // Feedback / survey prefs
        private const val KEY_APP_LAUNCHES = "fb_app_launches"
        private const val KEY_RESULT_SUCCESS_COUNT = "fb_result_success_count"
        private const val KEY_INSTALL_TIME_MS = "fb_install_time_ms"
        private const val KEY_LAST_USE_MS = "fb_last_use_ms"

        private const val KEY_SURVEY1_SHOWN = "fb_survey1_shown"
        private const val KEY_SURVEY2_SHOWN = "fb_survey2_shown"
        private const val KEY_SURVEY1_CHOICE = "fb_survey1_choice"
        private const val KEY_SURVEY2_CHOICE = "fb_survey2_choice"
        private const val KEY_REVIEW_REQUESTED = "fb_review_requested"

        // YOLO cup assist: false = 완전 배제 (수동 조준만)
        private const val CUP_YOLO_ENABLED = false
        // YOLO cup assist tuning (골프장=엄격 검출만 AUTO, 일반환경=수동 위주)
        private const val CUP_YOLO_CONF_THRESHOLD = 0.72f
        private const val CUP_YOLO_IOU_THRESHOLD = 0.45f
        private const val CUP_YOLO_STABLE_FRAMES_ON = 4
        private const val CUP_YOLO_STABLE_FRAMES_OFF = 2
        private const val CUP_YOLO_CONSECUTIVE_MISS_TO_OFF = 2
        private const val CUP_YOLO_MAX_AGE_NS = 900_000_000L   // 900ms
        private const val CUP_YOLO_INFER_INTERVAL_NS = 200_000_000L  // 200ms
        private const val CUP_YOLO_MIN_BOX_PX = 14
        private const val CUP_YOLO_ROI_SHIFT_MAX_RATIO = 0.12f
        private const val CUP_YOLO_SMOOTHING_ALPHA = 0.25f   // cur*(1-alpha) + new*alpha
        private const val CUP_YOLO_FREEZE_AFTER_FINISH_NS = 1_200_000_000L  // 1.2s
    }

    private val TAG = "DistanceMeasurement"

    private var session: Session? = null
    private var installRequested = false
    private var previewGlView: GLSurfaceView? = null
    private var isGlContextReady = false
    private var isArCoreReady = false
    private val backgroundRenderer = BackgroundRenderer()

    // Latest AR tracking sample (updated from onDrawFrame only)
    @Volatile private var latestTrackingState: TrackingState = TrackingState.PAUSED

    private lateinit var txtDistance: TextView
    private lateinit var txtDistanceLabel: TextView
    private lateinit var txtTracking: TextView
    private lateinit var txtInstruction: TextView
    private lateinit var txtNgHint: TextView
    private lateinit var txtCupAssist: TextView
    private lateinit var dotYolo: View
    private lateinit var dotHit: View
    private lateinit var dotRoi: View
    private lateinit var btnStart: MaterialButton
    private lateinit var btnFinish: MaterialButton
    private lateinit var layoutResetTouch: FrameLayout
    private lateinit var txtZoomRatio: TextView
    private lateinit var layoutZoomButtons: View
    private lateinit var btnZoomPlus: com.google.android.material.button.MaterialButton
    private lateinit var btnZoomMinus: com.google.android.material.button.MaterialButton
    private lateinit var imgCheck: ImageView
    private lateinit var btnSettings: ImageButton

    // --- v3.1 UI overlay (ViewFinder) ---
    private var viewFinder: ViewFinderView? = null
    private var viewFinderMask: ViewFinderMaskView? = null

    // --- v3.4 smooth-surface UX (UI-only) ---
    private var ngStartTimeNs: Long = 0L
    private var ngShakeExecuted: Boolean = false
    private var startLockedOkUntilNs: Long = 0L
    private var lastEngineState: V31StateMachine.State = V31StateMachine.State.IDLE
    private var startPressedAtNs: Long = 0L
    private var ngStartAtNs: Long = 0L
    private var hasEverBeenOkSinceStart: Boolean = false
    private var cupAssistActivePrev: Boolean = false
    private var lastCupAssistCueMs: Long = 0L
    private var cupYoloDetector: CupYoloDetector? = null
    private var cupYoloInitTried: Boolean = false
    private var cupYoloLastInferNs: Long = 0L
    private var cupYoloLastSeenNs: Long = 0L
    private var cupYoloStableFrames: Int = 0
    private var cupYoloLastCenter: PointF? = null
    @Volatile private var cupYoloAssistActive: Boolean = false
    @Volatile private var cupYoloFreezeUntilNs: Long = 0L  // Finish 직후 AUTO 정지 구간
    @Volatile private var cupYoloConsecutiveMisses: Int = 0
    private var cupYoloActivationCount: Int = 0
    private var cupYoloDeactivationConsecutiveMissCount: Int = 0
    private var cupYoloAssistActiveAtLock: Boolean = false
    private var cupYoloStableFramesAtLock: Int = 0

    private val zoomSteps = floatArrayOf(1.0f, 1.5f, 2.0f, 2.5f, 3.0f)
    private var zoomStepIndex = 0
    @Volatile private var cupDebugYoloPoint: PointF? = null
    @Volatile private var cupDebugHitPoint: PointF? = null
    @Volatile private var cupDebugRoiPoint: PointF? = null

    private var unitMode: UnitMode = UnitMode.METER
    // 마지막 측정값(항상 meters 기준) - 단위 변경 시 즉시 재표시용
    private var lastDistanceMeters: Float = 0f
    private var lastNonFinalDistanceMeters: Float = 0f
    private var lastNonFinalLiveSource: String? = null
    private var lastNonFinalLiveRawMeters: Float? = null
    private var lastNonFinalCenterHitValid: Boolean? = null

    // GREENIQ feedback: lock stats (captured at flashLock)
    private var ballValidHits: Int? = null
    private var ballTotalPoints: Int? = null
    private var ballSigmaCm: Float? = null
    private var ballSigmaMaxCm: Float? = null
    private var ballDistanceFromCameraMeters: Float? = null
    private var trackingAtBallFix: String? = null
    private var ballGroundPlaneNormalY: Float? = null
    private var ballGroundPlaneAbsNormalY: Float? = null
    private var ballGroundPlaneNormalLen: Float? = null
    private var ballGroundPlaneType: String? = null
    private var ballGroundPlaneTrackingState: String? = null
    private var ballGroundPlaneHitDistanceFromCameraMeters: Float? = null
    private var ballGroundPlaneExtentX: Float? = null
    private var ballGroundPlaneExtentZ: Float? = null
    private var ballCupPlaneAngleDeg: Float? = null

    private var cupValidHits: Int? = null
    private var cupTotalPoints: Int? = null
    private var cupSigmaCm: Float? = null
    private var cupSigmaMaxCm: Float? = null
    private var cupDistanceFromCameraMeters: Float? = null
    private var trackingAtCupFix: String? = null
    private var cupHoldSigmaCm: Float? = null
    private var cupHoldMaxCm: Float? = null
    private var cupHoldDurationMs: Long? = null

    // GREENIQ Cup multi-ray diagnostics (captured at cup flashLock)
    private var cupMultiRayGridHalfSpanPx: Float? = null
    private var cupMultiRayStepPx: Float? = null
    private var cupValidSampleCount: Int? = null
    private var cupHitDistanceAvgMeters: Float? = null
    private var cupHitDistanceMaxMeters: Float? = null
    private var cupCameraY: Float? = null
    private var cupMedianY: Float? = null
    private var cupCenterYOffsetApplied: Boolean? = null
    private var cupMultiRayPlan: String? = null
    private var cupMultiRayEstimatedDistanceMeters: Float? = null
    private var cupMultiRayProjectedCupPx: Float? = null
    private var cupMultiRayCenterFallbackUsed: Boolean? = null

    // Field test tags (persisted)
    private val KEY_TEST_DISTANCE_GROUP = "test_distance_group" // "2m" | "5m" | "10m"
    private val KEY_TEST_GROUND_TRUTH_M = "test_ground_truth_m"
    private val KEY_TEST_LIGHT = "test_light_condition" // SUNNY|CLOUDY|SHADE
    private val KEY_TEST_NOTES = "test_notes"

    // --- Feedback / survey (no server; email only) ---
    private val feedbackEmailTo = "wonmocho62@gmail.com" // v1: fixed; replace later with support@...
    private val recentSessions: ArrayDeque<FeedbackSession> = ArrayDeque()

    private var startStabilizingEnteredAtMs: Long = 0L
    private var endStabilizingEnteredAtMs: Long = 0L
    private var lastStartLockTimeMs: Long? = null
    private var lastEndLockTimeMs: Long? = null

    private var surveyCheckScheduledThisRun: Boolean = false

    private val updateHandler = Handler(Looper.getMainLooper())

    private var trackingUiRunnable: Runnable? = null
    private var showDoneStatus: Boolean = false
    private var lastIsMeasuringFlow: Boolean = false

    // --- Screen adjust (lie-caddy-v1 style; texture-level) ---
    private val KEY_ROTATION = "screen_rotation" // Float [-180..180]
    private val KEY_MIRROR_H = "mirror_h"
    private val KEY_MIRROR_V = "mirror_v"
    private var screenRotDeg: Float = 0f
    private var screenMirrorH: Boolean = false
    private var screenMirrorV: Boolean = false

    // --- Engine routing (v3.1 is SSOT for UI) ---
    private val pendingEngineEvents: java.util.concurrent.ConcurrentLinkedQueue<V31StateMachine.UiEvent> =
        java.util.concurrent.ConcurrentLinkedQueue()
    private var mapper: ScreenToViewMapper? = null
    private var v31Engine: MeasurementEngine? = null
    private var legacyEngine: MeasurementEngine? = null
    private var activeEngine: MeasurementEngine? = null
    private var shadowLegacy: Boolean = false

    // --- v3.1 throttle ---
    private val HIT_THROTTLE_NS = 50_000_000L
    private var lastEngineTickNs: Long = 0L
    private var lastZoomDebugLogNs: Long = 0L

    override fun attachBaseContext(newBase: Context) {
        val prefs = newBase.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val stored = prefs.getString(KEY_LANG, null)
        val lang =
            stored ?: "en".also { // 기본 언어는 English
                prefs.edit().putString(KEY_LANG, it).apply()
            }
        super.attachBaseContext(updateLocale(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_distance_measurement)

        initUI()
        setupPreviewGLViewBehindUi()
        loadScreenAdjustPrefs()
        applyScreenAdjustToPreviewWhenLaidOut()
        setupEngines()
        incrementLaunchCount()
        ensureInstallTimeSaved()

        if (hasCameraPermission()) {
            initializeARCore()
        } else {
            requestCameraPermission()
        }
    }

    private fun initUI() {
        txtDistance = findViewById(R.id.txtDistance)
        txtDistanceLabel = findViewById(R.id.txtDistanceLabel)
        imgCheck = findViewById(R.id.imgCheck)
        layoutResetTouch = findViewById(R.id.layoutResetTouch)
        txtTracking = findViewById(R.id.txtTracking)
        txtInstruction = findViewById(R.id.txt_instruction)
        txtNgHint = findViewById(R.id.txtNgHint)
        txtCupAssist = findViewById(R.id.txtCupAssist)
        dotYolo = findViewById(R.id.dotYolo)
        dotHit = findViewById(R.id.dotHit)
        dotRoi = findViewById(R.id.dotRoi)
        btnStart = findViewById(R.id.btn_start)
        btnFinish = findViewById(R.id.btn_finish)
        btnSettings = findViewById(R.id.btnSettings)
        viewFinder = findViewById(R.id.viewFinder)
        txtZoomRatio = findViewById(R.id.txtZoomRatio)
        layoutZoomButtons = findViewById(R.id.layoutZoomButtons)
        btnZoomPlus = findViewById(R.id.btnZoomPlus)
        btnZoomMinus = findViewById(R.id.btnZoomMinus)

        // Load unit preference early (unit toggle is in menu only).
        unitMode = loadUnitPref()

        btnStart.setOnClickListener {
            // v3.8 UX: grace period begins at press time.
            val now = System.nanoTime()
            startPressedAtNs = now
            ngStartAtNs = 0L
            ngStartTimeNs = 0L
            ngShakeExecuted = false
            hasEverBeenOkSinceStart = false
            showNgHint(false)
            pendingEngineEvents.add(V31StateMachine.UiEvent.StartPressed)
        }
        btnFinish.setOnClickListener {
            cupYoloFreezeUntilNs = System.nanoTime() + CUP_YOLO_FREEZE_AFTER_FINISH_NS
            pendingEngineEvents.add(V31StateMachine.UiEvent.FinishPressed)
        }
        layoutResetTouch.setOnClickListener {
            // quick tap feedback: subtle scale-down then restore
            it.animate().cancel()
            it.scaleX = 1f
            it.scaleY = 1f
            it.animate()
                .scaleX(0.92f)
                .scaleY(0.92f)
                .setDuration(60L)
                .withEndAction {
                    it.animate().scaleX(1f).scaleY(1f).setDuration(60L).start()
                }
                .start()
            pendingEngineEvents.add(V31StateMachine.UiEvent.ResetPressed)
        }
        btnSettings.setOnClickListener { showSettingsBottomSheet() }

        fun applyZoom() {
            val z = zoomSteps[zoomStepIndex]
            backgroundRenderer.setZoomLevel(z)
            mapper?.zoomLevel = z
            txtZoomRatio.text = when (z) {
                1.0f -> "1.0x"
                1.5f -> "1.5x"
                2.0f -> "2.0x"
                2.5f -> "2.5x"
                3.0f -> "3.0x"
                else -> "%.1fx".format(Locale.US, z)
            }
        }
        btnZoomPlus.setOnClickListener {
            if (zoomStepIndex < zoomSteps.size - 1) {
                zoomStepIndex++
                applyZoom()
            }
        }
        btnZoomMinus.setOnClickListener {
            if (zoomStepIndex > 0) {
                zoomStepIndex--
                applyZoom()
            }
        }

        btnStart.isEnabled = false
        btnFinish.isEnabled = false
        txtDistance.text = formatDistanceWithDecimals(0f, 1)
        txtDistanceLabel.text = getString(R.string.greeniq_label_live)
        txtTracking.text = getString(R.string.status_initializing_arcore)
        txtInstruction.text = getString(R.string.greeniq_hint_ball)
        imgCheck.visibility = View.GONE
        imgCheck.alpha = 0f
        txtNgHint.visibility = View.GONE
        txtNgHint.alpha = 0f
        txtCupAssist.visibility = View.GONE
        txtCupAssist.alpha = 0f
        dotYolo.visibility = View.GONE
        dotHit.visibility = View.GONE
        dotRoi.visibility = View.GONE
        txtZoomRatio.visibility = View.GONE
        layoutZoomButtons.visibility = View.GONE
        backgroundRenderer.setZoomLevel(1.0f)
        applyZoom()
    }

    private fun showCheckFeedback() {
        // Success-only feedback: ✓ (no NG)
        imgCheck.animate().cancel()
        imgCheck.scaleX = 0.9f
        imgCheck.scaleY = 0.9f
        imgCheck.alpha = 0f
        imgCheck.visibility = View.VISIBLE

        imgCheck.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(120L)
            .withEndAction {
                imgCheck.postDelayed(
                    {
                        imgCheck.animate()
                            .alpha(0f)
                            .setDuration(260L)
                            .withEndAction { imgCheck.visibility = View.GONE }
                            .start()
                    },
                    900L
                )
            }
            .start()
    }

    private fun prefs() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    private fun dpPx(v: Float): Int = (v * resources.displayMetrics.density).toInt()

    private fun animateFinalDistance(view: TextView) {
        view.animate().cancel()
        view.pivotX = view.width * 0.5f
        view.pivotY = view.height * 0.5f
        view.scaleX = 1.0f
        view.scaleY = 1.0f
        val itp = FastOutSlowInInterpolator()
        view.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(75L)
            .setInterpolator(itp)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(75L)
                    .setInterpolator(itp)
                    .start()
            }
            .start()
    }

    private fun animateLockPulse(view: TextView) {
        // CUP lock emphasis: briefly enlarge then settle to normal size.
        view.animate().cancel()
        view.pivotX = view.width * 0.5f
        view.pivotY = view.height * 0.5f
        view.scaleX = 1.0f
        view.scaleY = 1.0f
        val itp = FastOutSlowInInterpolator()
        view.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(110L)
            .setInterpolator(itp)
            .withEndAction {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .setDuration(170L)
                    .setInterpolator(itp)
                    .start()
            }
            .start()
    }

    private fun loadUnitPref(): UnitMode {
        val p = prefs()
        val stored = p.getString(KEY_UNIT, null)
        if (stored == null) {
            val default =
                if (Locale.getDefault().country.equals("US", ignoreCase = true)) {
                    UnitMode.YARD
                } else {
                    UnitMode.METER
                }
            saveUnitPref(default)
            return default
        }
        return when (stored) {
            "yd" -> UnitMode.YARD
            else -> UnitMode.METER
        }
    }

    private fun saveUnitPref(mode: UnitMode) {
        val v = if (mode == UnitMode.YARD) "yd" else "m"
        prefs().edit().putString(KEY_UNIT, v).apply()
    }

    private fun loadScreenAdjustPrefs() {
        val p = prefs()
        screenRotDeg = p.getFloat(KEY_ROTATION, 0f).coerceIn(-180f, 180f)
        screenMirrorH = p.getBoolean(KEY_MIRROR_H, false)
        screenMirrorV = p.getBoolean(KEY_MIRROR_V, false)
    }

    private fun saveScreenAdjustPrefs() {
        prefs().edit()
            .putBoolean(KEY_MIRROR_H, screenMirrorH)
            .putBoolean(KEY_MIRROR_V, screenMirrorV)
            .putFloat(KEY_ROTATION, screenRotDeg)
            .apply()
    }

    private fun updateLocale(context: Context, lang: String): Context {
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return context.createConfigurationContext(config)
    }

    private fun currentLang(): String = prefs().getString(KEY_LANG, "en") ?: "en"

    private fun isKorean(): Boolean = currentLang() == "ko"

    private fun toUnitValue(meters: Float): Float {
        return when (unitMode) {
            UnitMode.METER -> meters
            UnitMode.YARD -> meters * 1.0936133f
        }
    }

    private fun unitLabel(): String {
        return when (unitMode) {
            UnitMode.METER -> getString(R.string.unit_m)
            UnitMode.YARD -> getString(R.string.unit_yd)
        }
    }

    private class FixedSpaceSpan(private val widthPx: Int) : ReplacementSpan() {
        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?
        ): Int = widthPx

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint
        ) {
            // draw nothing (just a fixed-width gap)
        }
    }

    private fun formatDistanceWithDecimals(meters: Float, decimals: Int): CharSequence {
        val v = toUnitValue(meters)
        val numberFmt = "%.${decimals}f"
        val numberStr = String.format(Locale.US, numberFmt, v)
        val unitStr = unitLabel()

        // "4.23  yd" but with fixed 4dp gap and unit at 60% size.
        val sb = SpannableStringBuilder()
        sb.append(numberStr)

        val gapStart = sb.length
        sb.append(" ") // placeholder for span width
        sb.setSpan(
            FixedSpaceSpan(dpPx(4f)),
            gapStart,
            gapStart + 1,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )

        val unitStart = sb.length
        sb.append(unitStr)
        sb.setSpan(
            RelativeSizeSpan(0.6f),
            unitStart,
            sb.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return sb
    }

    // GREENIQ Distance v1: single mode only (XZ / horizontal).
    private fun isAxisXzSelected(): Boolean = true

    private fun refreshDisplayedDistance() {
        // Unit switch should update LIVE/FINAL immediately with correct decimals.
        val decimals = if (lastEngineState == V31StateMachine.State.RESULT) 2 else 1
        txtDistance.text = formatDistanceWithDecimals(lastDistanceMeters, decimals)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun requestCameraPermission() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 1001)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            initializeARCore()
        }
    }

    private fun initializeARCore() {
        if (session != null) return
        try {
            val installStatus =
                ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            session = Session(this)
            Log.i(TAG, "✅ ARCore Session created")
            if (isGlContextReady) {
                configureSession()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ ARCore 초기화 실패: ${e.message}", e)
            txtTracking.text = getString(R.string.status_arcore_init_failed)
        }
    }

    private fun configureSession() {
        val currentSession = session ?: return
        val glView = previewGlView ?: return

        glView.queueEvent {
            try {
                val config = currentSession.config ?: Config(currentSession)
                config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                config.focusMode = Config.FocusMode.AUTO
                // v3.1 SessionConfig policy
                config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                config.depthMode =
                    if (currentSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                        Config.DepthMode.AUTOMATIC
                    } else {
                        Config.DepthMode.DISABLED
                    }
                config.instantPlacementMode = Config.InstantPlacementMode.DISABLED
                currentSession.configure(config)

                runOnUiThread {
                    // NOTE:
                    // setDisplayGeometry() must use the *GL surface size* (onSurfaceChanged width/height),
                    // not DisplayMetrics, otherwise some devices show pillar/letterbox.
                    try {
                        currentSession.resume()
                        Log.i(TAG, "✅ Session resumed")
                    } catch (e: CameraNotAvailableException) {
                        Log.e(TAG, "❌ 카메라 사용 불가", e)
                        txtTracking.text = getString(R.string.status_camera_not_available)
                        return@runOnUiThread
                    }

                    isArCoreReady = true
                    txtTracking.text = getString(R.string.status_ready)
                    btnStart.isEnabled = true
                    startTrackingUiUpdate()
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Session 설정 실패: ${e.message}", e)
            }
        }
    }

    private fun startTrackingUiUpdate() {
        stopTrackingUiUpdate()
        trackingUiRunnable =
            object : Runnable {
                override fun run() {
                    updateTrackingStatusText()
                    updateHandler.postDelayed(this, 500)
                }
            }
        updateHandler.postDelayed(trackingUiRunnable!!, 500)
    }

    private fun stopTrackingUiUpdate() {
        trackingUiRunnable?.let {
            updateHandler.removeCallbacks(it)
            trackingUiRunnable = null
        }
    }

    private fun updateTrackingStatusText() {
        val trackingState = latestTrackingState
        // NOTE: v3.1 engine is the single writer for START/FINISH enables.
        // This method should only update status text.

        val moveDevice = getString(R.string.toast_move_device)
        val statusText =
            when (trackingState) {
                TrackingState.TRACKING -> getString(R.string.tracking_good)
                TrackingState.PAUSED ->
                    getString(R.string.tracking_paused_move, moveDevice)
                TrackingState.STOPPED -> getString(R.string.tracking_stopped)
                else -> getString(R.string.tracking_other, trackingState.name)
            }
        txtTracking.text = statusText
    }

    private fun checkTrackingState() {
        // Legacy entry point: keep method but route to shared latest tracking state.
        updateTrackingStatusText()
    }

    private var frameCount = 0
    private var surfaceW: Int = 0
    private var surfaceH: Int = 0

    private fun setupPreviewGLViewBehindUi() {
        previewGlView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@DistanceMeasurementActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            visibility = View.VISIBLE
            alpha = 1f
        }
        val content = findViewById<android.view.ViewGroup>(android.R.id.content)
        content.addView(previewGlView, 0)

        // GREENIQ UI: mask the preview so camera shows ONLY inside ViewFinder.
        if (viewFinderMask == null) {
            val mask = ViewFinderMaskView(this).apply {
                id = View.generateViewId()
                isClickable = false
                isFocusable = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            // Draw order: [GL preview] -> [GREEN mask] -> [UI root]
            content.addView(mask, 1)
            viewFinderMask = mask
            viewFinder?.let { vf ->
                mask.attachViewFinder(vf)
                vf.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> mask.invalidate() }
            }
        }
    }

    // v3.3: ViewFinder is defined in XML (`R.id.viewFinder`).
    private fun addViewFinderOverlay() = Unit

    private fun applyScreenAdjustToPreview(glView: GLSurfaceView) {
        // lie-caddy-v1 style: adjust preview at texture(UV) level (BackgroundRenderer),
        // and apply the SAME mapping for hitTest inputs (ScreenToViewMapper).
        glView.pivotX = glView.width * 0.5f
        glView.pivotY = glView.height * 0.5f
        glView.rotation = 0f
        glView.scaleX = 1f
        glView.scaleY = 1f

        mapper?.rotationDeg = screenRotDeg
        mapper?.mirrorH = screenMirrorH
        mapper?.mirrorV = screenMirrorV
        mapper?.markDirty()

        glView.queueEvent {
            backgroundRenderer.setUserAdjust(screenRotDeg, screenMirrorH, screenMirrorV)
        }
    }

    private fun applyScreenAdjustToPreviewWhenLaidOut() {
        previewGlView?.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            applyScreenAdjustToPreview(v as GLSurfaceView)
        }
    }

    private fun setupEngines() {
        val glView = previewGlView ?: return
        mapper = ScreenToViewMapper(glView)
        mapper?.rotationDeg = screenRotDeg
        mapper?.mirrorH = screenMirrorH
        mapper?.mirrorV = screenMirrorV
        mapper?.zoomLevel = backgroundRenderer.getZoomLevel()
        v31Engine = V31Engine(mapper!!, debugLoggingEnabled = isDebuggableBuild())

        // Legacy engine is kept as a safety net but is OFF by default.
        legacyEngine = LegacyPoseEngine()
        val debug = isDebuggableBuild()
        shadowLegacy = debug && prefs().getBoolean("legacy_shadow_enabled", false)
        val legacyOnly = debug && prefs().getBoolean("legacy_only_enabled", false)
        activeEngine = if (legacyOnly) legacyEngine else v31Engine
    }

    private fun isDebuggableBuild(): Boolean {
        return (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f)
        try {
            backgroundRenderer.createOnGlThread(this)
            Log.i(TAG, "✅ BackgroundRenderer 초기화 완료")
        } catch (e: Exception) {
            Log.e(TAG, "❌ BackgroundRenderer 초기화 실패: ${e.message}", e)
        }
        isGlContextReady = true

        if (session != null && !isArCoreReady) {
            updateHandler.postDelayed({ configureSession() }, 100)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        surfaceW = width
        surfaceH = height

        // IMPORTANT: match ARCore display geometry to the actual GL surface.
        val currentSession = session ?: return
        try {
            val rotation = windowManager.defaultDisplay.rotation
            val displayRotation =
                when (rotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            currentSession.setDisplayGeometry(displayRotation, width, height)
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val currentSession = session ?: return

        try {
            val rotation = windowManager.defaultDisplay.rotation
            val displayRotation =
                when (rotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            val w = surfaceW
            val h = surfaceH
            if (w > 0 && h > 0) {
                currentSession.setDisplayGeometry(displayRotation, w, h)
            }
        } catch (_: Exception) {
            // ignore
        }

        try {
            currentSession.setCameraTextureName(backgroundRenderer.getTextureId())
        } catch (e: Exception) {
            Log.e(TAG, "❌ setCameraTextureName 실패: ${e.message}", e)
        }

        try {
            val frame = currentSession.update()
            frameCount++
            if (frameCount < 3) return

            if (!isArCoreReady) {
                isArCoreReady = true
            }

            val trackingState = frame.camera.trackingState
            latestTrackingState = trackingState

            // GREENIQ UI: instruction text is driven by the engine state (applyUiModel).

            backgroundRenderer.draw(frame)

            // v3.1 engine tick @ 20Hz (SSOT UI)
            val nowNs = System.nanoTime()
            if (nowNs - lastEngineTickNs >= HIT_THROTTLE_NS) {
                lastEngineTickNs = nowNs

                // consume events on GL thread
                val engine = activeEngine
                if (engine != null) {
                    val axis =
                        if (isAxisXzSelected()) V31StateMachine.AxisMode.XZ else V31StateMachine.AxisMode.XYZ
                    engine.setAxisMode(axis)

                    while (true) {
                        val e = pendingEngineEvents.poll() ?: break
                        engine.onUiEvent(e, nowNs)
                        if (shadowLegacy && engine !== legacyEngine) {
                            legacyEngine?.onUiEvent(e, nowNs)
                        }
                    }

                    // ROI from ViewFinder (screen) -> engine handles hitTest mapping via sampler/mapper
                    val vf = viewFinder
                    if (vf != null) {
                        val roi = vf.getFinderRectOnScreen(android.graphics.RectF())
                        val roiForEngine = maybeApplyCupYoloAssist(frame, roi, nowNs)
                        if (isDebuggableBuild() && nowNs - lastZoomDebugLogNs >= 300_000_000L) {
                            lastZoomDebugLogNs = nowNs
                            val intr = frame.camera.imageIntrinsics
                            val f = intr.focalLength
                            val pp = intr.principalPoint
                            val centerSx = roiForEngine.centerX()
                            val centerSy = roiForEngine.centerY()
                            val localRaw = mapper?.screenToLocal(PointF(centerSx, centerSy))
                            val localUnzoom = localRaw?.let { mapper?.unzoomLocal(it) }
                            Log.d(
                                "ZOOM_DEBUG",
                                "zoom=${"%.2f".format(Locale.US, backgroundRenderer.getZoomLevel())} " +
                                    "fx=${"%.1f".format(Locale.US, f[0])} fy=${"%.1f".format(Locale.US, f[1])} " +
                                    "cx=${"%.1f".format(Locale.US, pp[0])} cy=${"%.1f".format(Locale.US, pp[1])} " +
                                    "centerS=(${String.format(Locale.US, "%.1f", centerSx)},${String.format(Locale.US, "%.1f", centerSy)}) " +
                                    "centerLocalRaw=(${String.format(Locale.US, "%.1f", localRaw?.x ?: 0f)},${String.format(Locale.US, "%.1f", localRaw?.y ?: 0f)}) " +
                                    "centerLocalUnzoom=(${String.format(Locale.US, "%.1f", localUnzoom?.x ?: 0f)},${String.format(Locale.US, "%.1f", localUnzoom?.y ?: 0f)})"
                            )
                        }
                        val ui = engine.onFrame(frame, roiForEngine, nowNs)
                        if (shadowLegacy && engine !== legacyEngine) {
                            legacyEngine?.setAxisMode(axis)
                            legacyEngine?.onFrame(frame, roiForEngine, nowNs) // compute only; ignore result
                        }
                        runOnUiThread { applyUiModel(ui) }
                    }
                }
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    override fun onResume() {
        super.onResume()
        previewGlView?.onResume()
        updateLastUseNow()
        scheduleSurveyCheckIfNeeded()
        if (session != null) {
            try {
                session?.resume()
            } catch (e: CameraNotAvailableException) {
                Log.e(TAG, "❌ 카메라 사용 불가", e)
                txtTracking.text = getString(R.string.status_camera_not_available)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        previewGlView?.onPause()
        // Keep last use up-to-date for survey2 gating.
        updateLastUseNow()
        session?.pause()
        stopTrackingUiUpdate()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTrackingUiUpdate()
        previewGlView?.let {
            findViewById<android.view.ViewGroup>(android.R.id.content).removeView(it)
        }
        runCatching { cupYoloDetector?.close() }
        cupYoloDetector = null
        session?.close()
    }

    private fun isCupPhaseForYolo(): Boolean {
        return when (lastEngineState) {
            V31StateMachine.State.AIM_END,
            V31StateMachine.State.STABILIZING_END,
            V31StateMachine.State.END_LOCKED -> true
            else -> false
        }
    }

    private fun maybeApplyCupYoloAssist(frame: com.google.ar.core.Frame, roi: android.graphics.RectF, nowNs: Long): android.graphics.RectF {
        if (!CUP_YOLO_ENABLED) {
            cupYoloAssistActive = false
            cupYoloStableFrames = 0
            cupYoloConsecutiveMisses = 0
            cupDebugYoloPoint = null
            cupDebugHitPoint = null
            cupDebugRoiPoint = null
            return roi
        }
        if (!isCupPhaseForYolo()) {
            cupYoloAssistActive = false
            cupYoloStableFrames = 0
            cupYoloConsecutiveMisses = 0
            cupDebugYoloPoint = null
            cupDebugHitPoint = null
            cupDebugRoiPoint = null
            return roi
        }
        cupDebugRoiPoint = PointF(roi.centerX(), roi.centerY())

        // Finish 직후 1.2초 동안 AUTO 정지 → 확정 순간은 수동 기준으로 유지
        if (nowNs < cupYoloFreezeUntilNs) {
            cupYoloAssistActive = false
            cupDebugHitPoint = PointF(roi.centerX(), roi.centerY())
            return roi
        }

        val detector =
            if (cupYoloDetector != null) {
                cupYoloDetector
            } else {
                if (!cupYoloInitTried) {
                    cupYoloInitTried = true
                    cupYoloDetector = runCatching { CupYoloDetector(this) }.getOrNull()
                }
                cupYoloDetector
            } ?: return roi

        // YOLO inference throttle: keep CPU/thermal stable.
        if (nowNs - cupYoloLastInferNs >= CUP_YOLO_INFER_INTERVAL_NS) {
            cupYoloLastInferNs = nowNs
            val gl = previewGlView
            val targetW = gl?.width ?: 0
            val targetH = gl?.height ?: 0
            val bmp = frameToCanonicalBitmap(frame, targetW, targetH)
            if (bmp != null) {
                val detections = detector.detectCup(
                    bmp,
                    confThreshold = CUP_YOLO_CONF_THRESHOLD,
                    iouThreshold = CUP_YOLO_IOU_THRESHOLD
                )
                val best =
                    detections
                        .filter {
                            it.rect.width() >= CUP_YOLO_MIN_BOX_PX &&
                                it.rect.height() >= CUP_YOLO_MIN_BOX_PX
                        }
                        .maxByOrNull { it.score * (it.rect.width() * it.rect.height()) }
                if (best != null) {
                    val nx = (best.rect.centerX() / bmp.width.toFloat()).coerceIn(0f, 1f)
                    val ny = (best.rect.centerY() / bmp.height.toFloat()).coerceIn(0f, 1f)
                    val gl = previewGlView
                    val glLoc = IntArray(2)
                    gl?.getLocationOnScreen(glLoc)
                    val glW = gl?.width?.toFloat()?.takeIf { it > 1f }
                    val glH = gl?.height?.toFloat()?.takeIf { it > 1f }
                    val sx =
                        if (glW != null) {
                            glLoc[0] + (nx * glW)
                        } else {
                            roi.left + (roi.width() * nx)
                        }
                    val sy =
                        if (glH != null) {
                            glLoc[1] + (ny * glH)
                        } else {
                            roi.top + (roi.height() * ny)
                        }
                    cupDebugYoloPoint = PointF(sx, sy)
                    val cur = cupYoloLastCenter
                    val alpha = CUP_YOLO_SMOOTHING_ALPHA
                    cupYoloLastCenter =
                        if (cur == null) {
                            PointF(sx, sy)
                        } else {
                            PointF((cur.x * (1f - alpha)) + (sx * alpha), (cur.y * (1f - alpha)) + (sy * alpha))
                        }
                    cupYoloStableFrames = (cupYoloStableFrames + 1).coerceAtMost(20)
                    cupYoloLastSeenNs = nowNs
                    cupYoloConsecutiveMisses = 0
                } else {
                    cupDebugYoloPoint = null
                    cupYoloConsecutiveMisses = (cupYoloConsecutiveMisses + 1).coerceAtMost(10)
                    if (cupYoloConsecutiveMisses >= CUP_YOLO_CONSECUTIVE_MISS_TO_OFF) {
                        cupYoloStableFrames = 0
                        cupYoloDeactivationConsecutiveMissCount++
                    } else {
                        cupYoloStableFrames = (cupYoloStableFrames - 1).coerceAtLeast(0)
                    }
                }
                bmp.recycle()
            }
        }

        val ageOk = (nowNs - cupYoloLastSeenNs <= CUP_YOLO_MAX_AGE_NS)
        val onThreshold = cupYoloStableFrames >= CUP_YOLO_STABLE_FRAMES_ON
        val offThreshold = cupYoloStableFrames >= CUP_YOLO_STABLE_FRAMES_OFF
        val active = ageOk && (onThreshold || (cupYoloAssistActive && offThreshold))
        cupYoloAssistActive = active
        if (!active) {
            cupDebugHitPoint = PointF(roi.centerX(), roi.centerY())
            return roi
        }

        val target = cupYoloLastCenter ?: return roi
        val maxShiftX = roi.width() * CUP_YOLO_ROI_SHIFT_MAX_RATIO
        val maxShiftY = roi.height() * CUP_YOLO_ROI_SHIFT_MAX_RATIO
        val dx = (target.x - roi.centerX()).coerceIn(-maxShiftX, maxShiftX)
        val dy = (target.y - roi.centerY()).coerceIn(-maxShiftY, maxShiftY)
        val shifted = android.graphics.RectF(roi.left + dx, roi.top + dy, roi.right + dx, roi.bottom + dy)
        cupDebugHitPoint = PointF(shifted.centerX(), shifted.centerY())
        return shifted
    }

    private fun frameToBitmap(frame: com.google.ar.core.Frame): android.graphics.Bitmap? {
        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return null
        } catch (_: Exception) {
            return null
        }
        return try {
            val planes = image.planes
            if (planes.size < 3) return null
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuv = YuvImage(nv21, android.graphics.ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream()
            yuv.compressToJpeg(Rect(0, 0, image.width, image.height), 85, out)
            val bytes = out.toByteArray()
            out.close()
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            image.close()
        }
    }

    private fun frameToCanonicalBitmap(frame: com.google.ar.core.Frame, targetW: Int, targetH: Int): android.graphics.Bitmap? {
        val raw = frameToBitmap(frame) ?: return null
        val adjusted = try {
            val cx = raw.width * 0.5f
            val cy = raw.height * 0.5f
            val m = Matrix()
            // Keep canonical orientation aligned with preview texture adjustment.
            if (screenMirrorH || screenMirrorV) {
                m.postScale(if (screenMirrorH) -1f else 1f, if (screenMirrorV) -1f else 1f, cx, cy)
            }
            if (screenRotDeg != 0f) {
                m.postRotate(-screenRotDeg, cx, cy)
            }
            val transformed = android.graphics.Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
            if (transformed !== raw) raw.recycle()
            transformed
        } catch (_: Exception) {
            raw
        }

        // Option A: enforce View aspect ratio via center-crop to keep YOLO/View coordinates 1:1.
        if (targetW <= 1 || targetH <= 1) return adjusted
        val srcAspect = adjusted.width.toFloat() / adjusted.height.toFloat()
        val dstAspect = targetW.toFloat() / targetH.toFloat()
        val cropped =
            if (kotlin.math.abs(srcAspect - dstAspect) < 0.0001f) {
                adjusted
            } else {
                val (cropW, cropH) =
                    if (srcAspect > dstAspect) {
                        Pair((adjusted.height * dstAspect).toInt().coerceAtLeast(1), adjusted.height)
                    } else {
                        Pair(adjusted.width, (adjusted.width / dstAspect).toInt().coerceAtLeast(1))
                    }
                val left = ((adjusted.width - cropW) / 2).coerceAtLeast(0)
                val top = ((adjusted.height - cropH) / 2).coerceAtLeast(0)
                runCatching { android.graphics.Bitmap.createBitmap(adjusted, left, top, cropW, cropH) }.getOrNull() ?: adjusted
            }
        if (cropped !== adjusted) adjusted.recycle()
        return cropped
    }

    private fun applyUiModel(ui: V31StateMachine.UiModel) {
        // Track lock timings / result transitions for feedback logging.
        val nowMs = SystemClock.elapsedRealtime()
        if (ui.engineState == V31StateMachine.State.STABILIZING_START &&
            lastEngineState != V31StateMachine.State.STABILIZING_START
        ) {
            startStabilizingEnteredAtMs = nowMs
            lastStartLockTimeMs = null
        }
        if (ui.engineState == V31StateMachine.State.START_LOCKED &&
            ui.flashLock &&
            startStabilizingEnteredAtMs > 0L
        ) {
            lastStartLockTimeMs = (nowMs - startStabilizingEnteredAtMs).coerceAtLeast(0L)
            startStabilizingEnteredAtMs = 0L
        }
        if (ui.engineState == V31StateMachine.State.STABILIZING_END &&
            lastEngineState != V31StateMachine.State.STABILIZING_END
        ) {
            endStabilizingEnteredAtMs = nowMs
            lastEndLockTimeMs = null
        }
        if (ui.engineState == V31StateMachine.State.END_LOCKED &&
            ui.flashLock &&
            endStabilizingEnteredAtMs > 0L
        ) {
            lastEndLockTimeMs = (nowMs - endStabilizingEnteredAtMs).coerceAtLeast(0L)
            endStabilizingEnteredAtMs = 0L
        }
        if (ui.engineState == V31StateMachine.State.IDLE) {
            startStabilizingEnteredAtMs = 0L
            endStabilizingEnteredAtMs = 0L
            lastStartLockTimeMs = null
            lastEndLockTimeMs = null
        }

        // RESULT reached: record a session and potentially show the one-time survey.
        if (ui.engineState == V31StateMachine.State.RESULT &&
            lastEngineState != V31StateMachine.State.RESULT
        ) {
            onResultReached(ui)
            // Field test: append MEASUREMENT log line
            appendMeasurementLog(buildMeasurementLogJson(ui))
        }
        // FAIL reached: append log as well (helps diagnose)
        if (ui.engineState == V31StateMachine.State.FAIL &&
            lastEngineState != V31StateMachine.State.FAIL
        ) {
            appendMeasurementLog(buildMeasurementLogJson(ui))
        }

        // Keep a copy of the last non-final distance for logging (live_at_finish)
        if (ui.engineState != V31StateMachine.State.RESULT) {
            lastNonFinalDistanceMeters = ui.distanceMeters
            lastNonFinalLiveSource = ui.liveSource
            lastNonFinalLiveRawMeters = ui.liveRawMeters
            lastNonFinalCenterHitValid = ui.centerHitValid
        }
        lastDistanceMeters = ui.distanceMeters
        // Golf UI: LIVE(1 decimal) vs FINAL(2 decimals)
        val isFinal = ui.engineState == V31StateMachine.State.RESULT
        val hasLive =
            ui.engineState == V31StateMachine.State.AIM_END ||
                ui.engineState == V31StateMachine.State.STABILIZING_END ||
                ui.engineState == V31StateMachine.State.END_LOCKED

        val decimals = if (isFinal) 2 else 1
        txtDistance.text = formatDistanceWithDecimals(ui.distanceMeters, decimals)

        // LIVE / FINAL label: always white, subtle
        txtDistanceLabel.setTextColor(Color.WHITE)
        txtDistanceLabel.alpha = 0.6f

        // Optional readability: subtle shadow for both live/final
        txtDistance.setShadowLayer(6f, 0f, 2f, Color.parseColor("#33000000"))

        if (isFinal) {
            // FINAL: strong contrast, bold, 2 decimals
            txtDistance.setTextColor(Color.WHITE)
            txtDistance.alpha = 1.0f
            txtDistance.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            txtDistanceLabel.text = getString(R.string.greeniq_label_final)
            txtDistance.textSize = 66f

            // Animate ONLY on transition into FINAL
            if (lastEngineState != V31StateMachine.State.RESULT) {
                animateFinalDistance(txtDistance)
            }
        } else if (hasLive) {
            // LIVE: use high-contrast white (camera background can be green grass).
            txtDistance.setTextColor(Color.WHITE)
            txtDistance.alpha = 1.0f
            txtDistance.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            txtDistanceLabel.text = getString(R.string.greeniq_label_live)
            txtDistance.textSize = 62f
        } else {
            // Initial: white 70% (no live yet)
            txtDistance.setTextColor(Color.WHITE)
            txtDistance.alpha = 0.7f
            txtDistance.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            txtDistanceLabel.text = getString(R.string.greeniq_label_live)
            txtDistance.textSize = 62f
        }

        // Golf hint text (keep it simple; no technical box)
        txtInstruction.text =
            when (ui.engineState) {
                V31StateMachine.State.IDLE,
                V31StateMachine.State.AIM_START,
                V31StateMachine.State.STABILIZING_START,
                V31StateMachine.State.START_LOCKED,
                V31StateMachine.State.FAIL -> getString(R.string.greeniq_hint_ball)

                V31StateMachine.State.AIM_END,
                V31StateMachine.State.STABILIZING_END,
                V31StateMachine.State.END_LOCKED -> getString(R.string.greeniq_hint_cup)

                V31StateMachine.State.RESULT -> getString(R.string.greeniq_hint_done)
            }

        btnStart.isEnabled = ui.startEnabled
        btnFinish.isEnabled = ui.finishEnabled

        // Buttons: keep BALL filled. Make CUP look "alive" when enabled (white + green outline).
        val accent = Color.parseColor("#18A558")
        val disabledBg = Color.parseColor("#5A5A5A") // softer than near-black
        val disabledText = Color.parseColor("#D0D0D0")

        btnStart.backgroundTintList =
            ColorStateList.valueOf(if (btnStart.isEnabled) accent else Color.parseColor("#2A2A2A"))
        btnStart.setTextColor(Color.WHITE)
        btnStart.strokeWidth = 0

        if (btnFinish.isEnabled) {
            btnFinish.backgroundTintList = ColorStateList.valueOf(Color.WHITE)
            btnFinish.setTextColor(accent)
            btnFinish.strokeWidth = dpPx(2f)
            btnFinish.strokeColor = ColorStateList.valueOf(accent)
        } else {
            btnFinish.backgroundTintList = ColorStateList.valueOf(disabledBg)
            btnFinish.setTextColor(disabledText)
            btnFinish.strokeWidth = 0
        }

        // Reset must ALWAYS be available (recover from errors anytime).
        layoutResetTouch.isEnabled = true
        layoutResetTouch.alpha = 1.0f

        val nowNs = System.nanoTime()
        val startGraceNs = 300_000_000L // 300ms
        val failDelayNs = 900_000_000L // 0.9s: final NG if OK never appears

        val inStartStates =
            ui.engineState == V31StateMachine.State.AIM_START ||
                ui.engineState == V31StateMachine.State.STABILIZING_START

        val inEndStates =
            ui.engineState == V31StateMachine.State.AIM_END ||
                ui.engineState == V31StateMachine.State.STABILIZING_END

        // If we entered AIM_START without press timestamp, start grace now.
        if (ui.engineState == V31StateMachine.State.AIM_START && lastEngineState != V31StateMachine.State.AIM_START) {
            if (startPressedAtNs == 0L) startPressedAtNs = nowNs
            ngStartAtNs = 0L
            ngStartTimeNs = 0L
            ngShakeExecuted = false
            showNgHint(false)
        }

        val inStartGrace = inStartStates && startPressedAtNs != 0L && (nowNs - startPressedAtNs <= startGraceNs)
        val elapsedSinceStartNs = if (startPressedAtNs == 0L) 0L else (nowNs - startPressedAtNs)

        // v3.6: START_LOCKED confirmation OK for 0.2s, then hide.
        if (ui.engineState == V31StateMachine.State.START_LOCKED && ui.flashLock) {
            startLockedOkUntilNs = nowNs + 200_000_000L
            showCheckFeedback()
            // Capture BALL fix stats for feedback log.
            ballValidHits = ui.sampleValidHits
            ballTotalPoints = ui.sampleTotalPoints
            ballSigmaCm = ui.sigmaUsedCm
            ballSigmaMaxCm = ui.sigmaMaxCm
            ballDistanceFromCameraMeters = ui.bestHitDistanceFromCameraMeters
            trackingAtBallFix = latestTrackingState.name
            ballGroundPlaneNormalY = ui.ballGroundPlaneNormalY
            ballGroundPlaneAbsNormalY = ui.ballGroundPlaneAbsNormalY
            ballGroundPlaneNormalLen = ui.ballGroundPlaneNormalLen
            ballGroundPlaneType = ui.ballGroundPlaneType
            ballGroundPlaneTrackingState = ui.ballGroundPlaneTrackingState
            ballGroundPlaneHitDistanceFromCameraMeters = ui.ballGroundPlaneHitDistanceFromCameraMeters
            ballGroundPlaneExtentX = ui.ballGroundPlaneExtentX
            ballGroundPlaneExtentZ = ui.ballGroundPlaneExtentZ
            ballCupPlaneAngleDeg = ui.ballCupPlaneAngleDeg
            // Reset NG UX when START locks successfully.
            ngStartTimeNs = 0L
            ngStartAtNs = 0L
            ngShakeExecuted = false
            showNgHint(false)
            startPressedAtNs = 0L
            hasEverBeenOkSinceStart = false
        }
        if (ui.engineState == V31StateMachine.State.END_LOCKED && ui.flashLock) {
            showCheckFeedback()
            animateLockPulse(txtDistance)
            cupYoloAssistActiveAtLock = cupYoloAssistActive
            cupYoloStableFramesAtLock = cupYoloStableFrames
            // Capture CUP fix stats for feedback log.
            cupValidHits = ui.sampleValidHits
            cupTotalPoints = ui.sampleTotalPoints
            cupSigmaCm = ui.sigmaUsedCm
            cupSigmaMaxCm = ui.sigmaMaxCm
            cupDistanceFromCameraMeters = ui.bestHitDistanceFromCameraMeters
            trackingAtCupFix = latestTrackingState.name
            cupHoldSigmaCm = ui.cupHoldSigmaCm
            cupHoldMaxCm = ui.cupHoldMaxCm
            cupHoldDurationMs = ui.cupHoldDurationMs
            cupMultiRayGridHalfSpanPx = ui.multiRayGridHalfSpanPx
            cupMultiRayStepPx = ui.multiRayStepPx
            cupValidSampleCount = ui.validSampleCount
            cupHitDistanceAvgMeters = ui.hitDistanceAvgMeters
            cupHitDistanceMaxMeters = ui.hitDistanceMaxMeters
            cupCameraY = ui.cameraY
            cupMedianY = ui.medianY
            cupCenterYOffsetApplied = ui.centerYOffsetApplied
            cupMultiRayPlan = ui.multiRayPlan
            cupMultiRayEstimatedDistanceMeters = ui.multiRayEstimatedDistanceMeters
            cupMultiRayProjectedCupPx = ui.multiRayProjectedCupPx
            cupMultiRayCenterFallbackUsed = ui.multiRayCenterFallbackUsed
            ballCupPlaneAngleDeg = ui.ballCupPlaneAngleDeg
        }
        if (ui.engineState == V31StateMachine.State.IDLE) {
            zoomStepIndex = 0
            backgroundRenderer.setZoomLevel(1.0f)
            mapper?.zoomLevel = 1.0f
            txtZoomRatio.text = "1.0x"
            txtZoomRatio.visibility = View.GONE
            layoutZoomButtons.visibility = View.GONE
            startLockedOkUntilNs = 0L
            startPressedAtNs = 0L
            ngStartAtNs = 0L
            ngStartTimeNs = 0L
            ngShakeExecuted = false
            showNgHint(false)
            viewFinder?.translationX = 0f
            hasEverBeenOkSinceStart = false
            // Ensure ✓ is hidden on reset/idle.
            imgCheck.animate().cancel()
            imgCheck.visibility = View.GONE
            imgCheck.alpha = 0f

            // Clear lock stats so sessions won't mix.
            ballValidHits = null
            ballTotalPoints = null
            ballSigmaCm = null
            ballSigmaMaxCm = null
            ballDistanceFromCameraMeters = null
            trackingAtBallFix = null
            ballGroundPlaneNormalY = null
            ballGroundPlaneAbsNormalY = null
            ballGroundPlaneNormalLen = null
            ballGroundPlaneType = null
            ballGroundPlaneTrackingState = null
            ballGroundPlaneHitDistanceFromCameraMeters = null
            ballGroundPlaneExtentX = null
            ballGroundPlaneExtentZ = null
            ballCupPlaneAngleDeg = null
            cupValidHits = null
            cupTotalPoints = null
            cupSigmaCm = null
            cupSigmaMaxCm = null
            cupDistanceFromCameraMeters = null
            trackingAtCupFix = null
            cupHoldSigmaCm = null
            cupHoldMaxCm = null
            cupHoldDurationMs = null
            cupMultiRayGridHalfSpanPx = null
            cupMultiRayStepPx = null
            cupValidSampleCount = null
            cupHitDistanceAvgMeters = null
            cupHitDistanceMaxMeters = null
            cupCameraY = null
            cupMedianY = null
            cupCenterYOffsetApplied = null
            cupMultiRayPlan = null
            cupMultiRayEstimatedDistanceMeters = null
            cupMultiRayProjectedCupPx = null
            cupMultiRayCenterFallbackUsed = null
            lastNonFinalDistanceMeters = 0f
            lastNonFinalLiveSource = null
            lastNonFinalLiveRawMeters = null
            lastNonFinalCenterHitValid = null
            cupYoloAssistActive = false
            cupYoloStableFrames = 0
            cupYoloLastCenter = null
            cupYoloLastSeenNs = 0L
            cupYoloFreezeUntilNs = 0L
            cupYoloConsecutiveMisses = 0
            cupYoloActivationCount = 0
            cupYoloDeactivationConsecutiveMissCount = 0
            cupYoloAssistActiveAtLock = false
            cupYoloStableFramesAtLock = 0
            cupAssistActivePrev = false
        }

        val qualityToShow: ViewFinderView.QualityState =
            when {
                startLockedOkUntilNs != 0L && nowNs <= startLockedOkUntilNs -> ViewFinderView.QualityState.OK
                ui.engineState == V31StateMachine.State.FAIL -> ViewFinderView.QualityState.NG
                inStartStates -> {
                    // v4.0 policy:
                    // - Never show NG initially.
                    // - If OK appears once, keep showing OK (no NG) during start phase.
                    // - If OK never appears, show final NG only after failDelay.
                    when (ui.viewFinderQuality) {
                        ViewFinderView.QualityState.OK -> {
                            hasEverBeenOkSinceStart = true
                            ngStartTimeNs = 0L
                            ngShakeExecuted = false
                            showNgHint(false)
                            ViewFinderView.QualityState.OK
                        }
                        ViewFinderView.QualityState.NG -> {
                            if (hasEverBeenOkSinceStart) {
                                // OK once => keep OK (no NG flicker)
                                ViewFinderView.QualityState.OK
                            } else if (inStartGrace) {
                                ViewFinderView.QualityState.NONE
                            } else if (elapsedSinceStartNs >= failDelayNs) {
                                ViewFinderView.QualityState.NG
                            } else {
                                ViewFinderView.QualityState.NONE
                            }
                        }
                        else -> ViewFinderView.QualityState.NONE
                    }
                }
                inEndStates -> {
                    // END phase: show only OK when ready; keep NG hidden to avoid distracting flicker.
                    when (ui.viewFinderQuality) {
                        ViewFinderView.QualityState.OK -> ViewFinderView.QualityState.OK
                        else -> ViewFinderView.QualityState.NONE
                    }
                }
                else -> ViewFinderView.QualityState.NONE
            }

        val cupAssistScope =
            CUP_YOLO_ENABLED && (
                inEndStates ||
                    ui.engineState == V31StateMachine.State.END_LOCKED
            )
        val cupAssistActive = cupAssistScope && cupYoloAssistActive
        updateCupAssistUi(cupAssistScope, cupAssistActive)
        updateCupDebugDots(cupAssistScope)
        if (cupAssistActive && !cupAssistActivePrev) {
            cupYoloActivationCount++
            runCupAssistAcquiredCue()
        }
        cupAssistActivePrev = cupAssistActive

        val inCupPhase =
            ui.engineState == V31StateMachine.State.AIM_END ||
                ui.engineState == V31StateMachine.State.STABILIZING_END ||
                ui.engineState == V31StateMachine.State.END_LOCKED
        txtZoomRatio.visibility = if (inCupPhase) View.VISIBLE else View.GONE
        layoutZoomButtons.visibility = if (inCupPhase) View.VISIBLE else View.GONE

        viewFinder?.let { vf ->
            vf.setState(ui.viewFinderState)
            vf.setQualityState(qualityToShow)
            if (ui.flashLock) vf.flashLock()
            if (ui.flashFail) vf.flashFail()
        }

        // v3.4 smooth-surface UX: NG persistent hint + one-time shake (UI-only)
        // Keep hint/shake only when we're still waiting for the first OK.
        val rawNgActive =
            inStartStates &&
                !hasEverBeenOkSinceStart &&
                ui.viewFinderQuality == ViewFinderView.QualityState.NG &&
                !inStartGrace
        if (rawNgActive) {
            if (ngStartTimeNs == 0L) ngStartTimeNs = nowNs
            val durNs = nowNs - ngStartTimeNs
            showNgHint(durNs >= 800_000_000L)
            if (!ngShakeExecuted && durNs >= 1_000_000_000L) {
                ngShakeExecuted = true
                runNgShakeOnce()
            }
        } else {
            ngStartTimeNs = 0L
            ngShakeExecuted = false
            showNgHint(false)
            viewFinder?.translationX = 0f
        }

        if (!inStartStates) {
            ngStartAtNs = 0L
            hasEverBeenOkSinceStart = false
        }

        lastEngineState = ui.engineState
        lastIsMeasuringFlow = ui.isMeasuringFlow
    }

    private fun showNgHint(show: Boolean) {
        if (show) {
            if (txtNgHint.visibility != View.VISIBLE) {
                txtNgHint.text = getString(R.string.hint_ng_textured)
                txtNgHint.visibility = View.VISIBLE
                txtNgHint.animate().cancel()
                txtNgHint.alpha = 0f
                txtNgHint.animate().alpha(0.9f).setDuration(150L).start()
            } else {
                // Keep text updated in case locale changed.
                txtNgHint.text = getString(R.string.hint_ng_textured)
            }
        } else {
            if (txtNgHint.visibility == View.VISIBLE) {
                txtNgHint.animate().cancel()
                txtNgHint.animate()
                    .alpha(0f)
                    .setDuration(120L)
                    .withEndAction { txtNgHint.visibility = View.GONE }
                    .start()
            }
        }
    }

    private fun updateCupAssistUi(show: Boolean, active: Boolean) {
        if (!show) {
            if (txtCupAssist.visibility == View.VISIBLE) {
                txtCupAssist.animate().cancel()
                txtCupAssist.animate()
                    .alpha(0f)
                    .setDuration(100L)
                    .withEndAction { txtCupAssist.visibility = View.GONE }
                    .start()
            }
            return
        }

        txtCupAssist.text = if (active) getString(R.string.cup_auto_active_short) else getString(R.string.cup_manual_aim)
        txtCupAssist.setBackgroundResource(
            if (active) R.drawable.bg_cup_assist_active else R.drawable.bg_cup_assist_inactive
        )
        if (txtCupAssist.visibility != View.VISIBLE) {
            txtCupAssist.visibility = View.VISIBLE
            txtCupAssist.alpha = 0f
            txtCupAssist.animate().alpha(0.96f).setDuration(130L).start()
        } else if (txtCupAssist.alpha < 0.96f) {
            txtCupAssist.animate().alpha(0.96f).setDuration(90L).start()
        }
    }

    private fun runCupAssistAcquiredCue() {
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - lastCupAssistCueMs < 1200L) return
        lastCupAssistCueMs = nowMs

        txtCupAssist.animate().cancel()
        txtCupAssist.text = getString(R.string.cup_auto_detected)
        txtCupAssist.setBackgroundResource(R.drawable.bg_cup_assist_active)
        txtCupAssist.alpha = 1f
        txtCupAssist.scaleX = 1f
        txtCupAssist.scaleY = 1f
        txtCupAssist.animate()
            .scaleX(1.08f)
            .scaleY(1.08f)
            .setDuration(90L)
            .withEndAction {
                txtCupAssist.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(120L)
                    .withEndAction {
                        if (txtCupAssist.visibility == View.VISIBLE) {
                            txtCupAssist.text = getString(R.string.cup_auto_active_short)
                        }
                    }
                    .start()
            }
            .start()
        txtCupAssist.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
    }

    private fun updateCupDebugDots(show: Boolean) {
        if (!show) {
            dotYolo.visibility = View.GONE
            dotHit.visibility = View.GONE
            dotRoi.visibility = View.GONE
            return
        }
        placeDebugDot(dotRoi, cupDebugRoiPoint, Color.WHITE)
        placeDebugDot(dotYolo, cupDebugYoloPoint, Color.parseColor("#F44336"))
        placeDebugDot(dotHit, cupDebugHitPoint, Color.parseColor("#18A558"))
    }

    private fun placeDebugDot(view: View, point: PointF?, color: Int) {
        if (point == null) {
            view.visibility = View.GONE
            return
        }
        val root = findViewById<View>(android.R.id.content)
        val rootLoc = IntArray(2)
        root.getLocationOnScreen(rootLoc)
        view.setBackgroundColor(color)
        view.visibility = View.VISIBLE
        view.translationX = point.x - rootLoc[0] - (view.width * 0.5f)
        view.translationY = point.y - rootLoc[1] - (view.height * 0.5f)
    }

    private fun runNgShakeOnce() {
        val vf = viewFinder ?: return
        val dx = 3f * resources.displayMetrics.density
        val anim = ObjectAnimator.ofFloat(vf, "translationX", 0f, dx, -dx, dx, 0f)
        anim.duration = 240L // 80ms * 3
        anim.start()
    }

    // GREENIQ UI: instruction message is driven by engine state (applyUiModel).

    private fun accuracyTable(): List<AccuracyRange> {
        return if (isKorean()) {
            listOf(
                AccuracyRange("0 ~ 1 m", "±0.5 ~ 1 cm", "±1 ~ 2 cm", "매우 높음"),
                AccuracyRange("1 ~ 3 m", "±1 ~ 3 cm", "±3 ~ 5 cm", "높음"),
                AccuracyRange("3 ~ 5 m", "±3 ~ 7 cm", "±5 ~ 10 cm", "높음"),
                AccuracyRange("5 ~ 10 m", "±7 ~ 15 cm", "±10 ~ 20 cm", "중간"),
                AccuracyRange("10 ~ 20 m", "±15 ~ 40 cm", "±30 ~ 50 cm", "낮음"),
                AccuracyRange("20 m 이상", "40 cm 이상", "50 cm 이상", "참고용")
            )
        } else {
            listOf(
                AccuracyRange("0–1 m", "±0.5–1 cm", "±1–2 cm", "Very high"),
                AccuracyRange("1–3 m", "±1–3 cm", "±3–5 cm", "High"),
                AccuracyRange("3–5 m", "±3–7 cm", "±5–10 cm", "High"),
                AccuracyRange("5–10 m", "±7–15 cm", "±10–20 cm", "Medium"),
                AccuracyRange("10–20 m", "±15–40 cm", "±30–50 cm", "Low"),
                AccuracyRange("20 m or more", "40 cm or more", "50 cm or more", "Reference")
            )
        }
    }

    private fun reliabilityColor(reliability: String): Int {
        return if (isKorean()) {
            when (reliability) {
                "매우 높음" -> Color.parseColor("#4CAF50")
                "높음" -> Color.parseColor("#8BC34A")
                "중간" -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#9E9E9E")
            }
        } else {
            when (reliability) {
                "Very high" -> Color.parseColor("#4CAF50")
                "High" -> Color.parseColor("#8BC34A")
                "Medium" -> Color.parseColor("#FF9800")
                else -> Color.parseColor("#9E9E9E")
            }
        }
    }

    private fun showAccuracyBottomSheet() {
        val bottomSheetView =
            LayoutInflater.from(this).inflate(R.layout.bottom_sheet_accuracy, null)
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(bottomSheetView)

        val txtCurrentMode = bottomSheetView.findViewById<TextView>(R.id.txt_current_mode)
        txtCurrentMode.text = getString(R.string.accuracy_current_mode, getString(R.string.mode_xz))

        val txtHeaderXZ = bottomSheetView.findViewById<TextView>(R.id.txt_header_xz)
        txtHeaderXZ.setTextColor(Color.parseColor("#4CAF50"))

        val layoutTableRows =
            bottomSheetView.findViewById<LinearLayout>(R.id.layout_table_rows)
        layoutTableRows.removeAllViews()

        accuracyTable().forEach { range ->
            val rowLayout =
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                        LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 4, 0, 4) }
                    setPadding(12, 12, 12, 12)
                    setBackgroundColor(Color.parseColor("#2A2A2A"))
                }

            val txtDistanceRange =
                TextView(this).apply {
                    text = range.distanceRange
                    setTextColor(Color.parseColor("#FFFFFF"))
                    textSize = 14f
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1.5f
                        )
                }

            val txtXZError =
                TextView(this).apply {
                    text = range.xzError
                    setTextColor(Color.parseColor("#4CAF50"))
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1.2f
                        )
                }

            val txtReliability =
                TextView(this).apply {
                    text = range.reliability
                    setTextColor(reliabilityColor(range.reliability))
                    textSize = 14f
                    gravity = android.view.Gravity.CENTER
                    layoutParams =
                        LinearLayout.LayoutParams(
                            0,
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            1.2f
                        )
                }

            rowLayout.addView(txtDistanceRange)
            rowLayout.addView(txtXZError)
            rowLayout.addView(txtReliability)
            layoutTableRows.addView(rowLayout)
        }

        val btnClose = bottomSheetView.findViewById<Button>(R.id.btn_close_accuracy)
        btnClose.setOnClickListener { bottomSheetDialog.dismiss() }
        bottomSheetDialog.show()
    }

    private fun showMeasurementGuideBottomSheet() {
        val bottomSheetView =
            LayoutInflater.from(this).inflate(R.layout.bottom_sheet_measurement_guide, null)
        val bottomSheetDialog = BottomSheetDialog(this)
        bottomSheetDialog.setContentView(bottomSheetView)

        val btnClose = bottomSheetView.findViewById<Button>(R.id.btn_close_measurement_guide)
        btnClose.setOnClickListener { bottomSheetDialog.dismiss() }

        bottomSheetDialog.show()
    }

    private fun incrementLaunchCount() {
        val p = prefs()
        val n = p.getInt(KEY_APP_LAUNCHES, 0) + 1
        p.edit().putInt(KEY_APP_LAUNCHES, n).apply()
    }

    private fun ensureInstallTimeSaved() {
        val p = prefs()
        if (p.getLong(KEY_INSTALL_TIME_MS, 0L) == 0L) {
            p.edit().putLong(KEY_INSTALL_TIME_MS, System.currentTimeMillis()).apply()
        }
    }

    private fun updateLastUseNow() {
        prefs().edit().putLong(KEY_LAST_USE_MS, System.currentTimeMillis()).apply()
    }

    private fun daysBetweenMs(aMs: Long, bMs: Long): Long {
        if (aMs <= 0L || bMs <= 0L) return 0L
        val diff = kotlin.math.abs(bMs - aMs)
        return diff / (24L * 60L * 60L * 1000L)
    }

    private fun scheduleSurveyCheckIfNeeded() {
        if (surveyCheckScheduledThisRun) return
        surveyCheckScheduledThisRun = true
        // Per spec: show on next app execution, 2~3 seconds after entering home/activity.
        updateHandler.postDelayed({ maybeShowSurveyFlow() }, 2500L)
    }

    private data class FeedbackSession(
        val timestampMs: Long,
        val axisMode: String, // "XZ" | "XYZ"
        val distanceFinalMeters: Float,
        val distanceLiveAtFinishMeters: Float?,
        val distanceDisplayed: Float,
        val distanceDisplayedUnit: String, // "m" | "yd"
        val liveFinalDiffCm: Float?,
        val hMeters: Float?,
        val vMeters: Float?,
        val startLockTimeMs: Long?,
        val endLockTimeMs: Long?,
        val unitMode: String,
        val ballValidHits: Int?,
        val ballTotalPoints: Int?,
        val ballSigmaCm: Float?,
        val ballSigmaMaxCm: Float?,
        val ballDistanceFromCameraMeters: Float?,
        val trackingAtBallFix: String?,
        val cupValidHits: Int?,
        val cupTotalPoints: Int?,
        val cupSigmaCm: Float?,
        val cupSigmaMaxCm: Float?,
        val cupDistanceFromCameraMeters: Float?,
        val cupHoldSigmaCm: Float?,
        val cupHoldMaxCm: Float?,
        val cupHoldDurationMs: Long?,
        val trackingAtCupFix: String?,
        val failReasonCode: String?,
        val screenRotDeg: Int,
        val mirrorH: Boolean,
        val mirrorV: Boolean
    )

    private fun onResultReached(ui: V31StateMachine.UiModel) {
        val p = prefs()
        val successCount = p.getInt(KEY_RESULT_SUCCESS_COUNT, 0) + 1
        p.edit().putInt(KEY_RESULT_SUCCESS_COUNT, successCount).apply()

        // Record session in memory (last 5)
        val axis = if (isAxisXzSelected()) "XZ" else "XYZ"
        val hv = ui.horizontalVerticalMeters
        val liveAtFinish = lastNonFinalDistanceMeters.takeIf { it.isFinite() && it > 0f }
        val finalMeters = ui.distanceMeters
        val displayed = toUnitValue(finalMeters)
        val dispUnit = if (unitMode == UnitMode.YARD) "yd" else "m"
        val diffCm = if (liveAtFinish == null) null else kotlin.math.abs(finalMeters - liveAtFinish) * 100f
        val s =
            FeedbackSession(
                timestampMs = System.currentTimeMillis(),
                axisMode = axis,
                distanceFinalMeters = finalMeters,
                distanceLiveAtFinishMeters = liveAtFinish,
                distanceDisplayed = displayed,
                distanceDisplayedUnit = dispUnit,
                liveFinalDiffCm = diffCm,
                hMeters = hv?.first,
                vMeters = hv?.second,
                startLockTimeMs = lastStartLockTimeMs,
                endLockTimeMs = lastEndLockTimeMs,
                unitMode = if (unitMode == UnitMode.YARD) "yd" else "m",
                ballValidHits = ballValidHits,
                ballTotalPoints = ballTotalPoints,
                ballSigmaCm = ballSigmaCm,
                ballSigmaMaxCm = ballSigmaMaxCm,
                ballDistanceFromCameraMeters = ballDistanceFromCameraMeters,
                trackingAtBallFix = trackingAtBallFix,
                cupValidHits = cupValidHits,
                cupTotalPoints = cupTotalPoints,
                cupSigmaCm = cupSigmaCm,
                cupSigmaMaxCm = cupSigmaMaxCm,
                cupDistanceFromCameraMeters = cupDistanceFromCameraMeters,
                cupHoldSigmaCm = cupHoldSigmaCm,
                cupHoldMaxCm = cupHoldMaxCm,
                cupHoldDurationMs = cupHoldDurationMs,
                trackingAtCupFix = trackingAtCupFix,
                failReasonCode = ui.failReasonCode,
                screenRotDeg = screenRotDeg.toInt(),
                mirrorH = screenMirrorH,
                mirrorV = screenMirrorV
            )
        recentSessions.addLast(s)
        while (recentSessions.size > 5) recentSessions.removeFirst()
    }

    private fun measurementLogFile(): File = File(filesDir, "GIQ_measurements.jsonl")

    private fun escJson(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")

    private fun appendMeasurementLog(jsonObjectLine: String) {
        try {
            val f = measurementLogFile()
            if (!f.exists()) f.parentFile?.mkdirs()
            f.appendText(jsonObjectLine + "\n", Charsets.UTF_8)
        } catch (_: Exception) {
            // ignore (logging must never crash measurement)
        }
    }

    private fun buildMeasurementLogJson(ui: V31StateMachine.UiModel): String {
        val p = prefs()
        val distanceGroup = p.getString(KEY_TEST_DISTANCE_GROUP, "2m") ?: "2m"
        val groundTruth = p.getFloat(KEY_TEST_GROUND_TRUTH_M, 0f).toDouble()
        val light = p.getString(KEY_TEST_LIGHT, "SUNNY") ?: "SUNNY"
        val notes = p.getString(KEY_TEST_NOTES, "") ?: ""

        // Timestamp (for JSONL ordering / "latest" clarity)
        val tsMs = System.currentTimeMillis()
        val tsUtcIso =
            runCatching {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.format(java.util.Date(tsMs))
            }.getOrNull() ?: ""
        val tsLocalIso =
            runCatching {
                // e.g. 2026-02-17T11:30:12.345+09:00
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).apply {
                    timeZone = TimeZone.getDefault()
                }.format(java.util.Date(tsMs))
            }.getOrNull() ?: ""

        val isResult = ui.engineState == V31StateMachine.State.RESULT
        val isFail = ui.engineState == V31StateMachine.State.FAIL

        val ballFixed = ballValidHits != null
        val cupFixed = cupValidHits != null

        val liveAtFinish = lastNonFinalDistanceMeters.takeIf { it.isFinite() && it > 0f }?.toDouble() ?: 0.0
        val finalMeters = if (isResult) ui.distanceMeters.toDouble() else 0.0
        val liveFinalDiffCm = if (isResult && liveAtFinish > 0.0) kotlin.math.abs(finalMeters - liveAtFinish) * 100.0 else 0.0
        val errorCm = if (isResult && groundTruth > 0.0) kotlin.math.abs(finalMeters - groundTruth) * 100.0 else 0.0

        val trackingAtCup = trackingAtCupFix ?: latestTrackingState.name

        // Schema: MEASUREMENT_LOG (Cup 중심 분석용)
        val sb = StringBuilder()
        sb.append("{")
        // Visual separator: make each JSONL line easy to spot in a text viewer.
        // Keep JSON-per-line (do not prefix non-JSON text).
        sb.append("\"MEASUREMENT\":\"START\",")
        // Put time fields first (requested): easiest to see latest logs.
        sb.append("\"timestamp_local\":\"").append(escJson(tsLocalIso)).append("\",")
        sb.append("\"timestamp_ms\":").append(tsMs).append(",")
        sb.append("\"timestamp_utc\":\"").append(escJson(tsUtcIso)).append("\",")
        sb.append("\"type\":\"MEASUREMENT\",")
        sb.append("\"appVersion\":\"").append(escJson(appVersionName())).append("\",")
        sb.append("\"deviceModel\":\"").append(escJson(android.os.Build.MODEL ?: "")).append("\",")
        sb.append("\"distanceGroup\":\"").append(escJson(distanceGroup)).append("\",")
        sb.append("\"groundTruth_m\":").append(String.format(Locale.US, "%.3f", groundTruth)).append(",")

        fun appendBallJsonStringOrNull(key: String, value: String?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append("\"").append(escJson(value)).append("\"")
        }
        fun appendBallJsonIntOrNull(key: String, value: Int?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(value)
        }
        fun appendBallJsonFloatOrNull(key: String, value: Float?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(String.format(Locale.US, "%.3f", value))
        }
        fun appendBallJsonBoolOrNull(key: String, value: Boolean?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(if (value) "true" else "false")
        }

        sb.append("\"ball\":{")
        sb.append("\"fixState\":\"").append(if (ballFixed) "FIXED" else if (isFail) "FAIL" else "FAIL").append("\",")
        sb.append("\"validHits\":").append(ballValidHits ?: 0).append(",")
        sb.append("\"sigma_cm\":").append(String.format(Locale.US, "%.3f", ballSigmaCm ?: 0f)).append(",")
        sb.append("\"fixTime_ms\":").append(lastStartLockTimeMs ?: 0).append(",")
        sb.append("\"distanceFromCamera_m\":").append(String.format(Locale.US, "%.3f", ballDistanceFromCameraMeters ?: 0f)).append(",")
        sb.append("\"groundPlane\":{")
        sb.append("\"normalY\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneNormalY ?: 0f)).append(",")
        sb.append("\"absNormalY\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneAbsNormalY ?: 0f)).append(",")
        sb.append("\"normalLen\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneNormalLen ?: 0f)).append(",")
        sb.append("\"planeType\":\"").append(escJson(ballGroundPlaneType ?: "")).append("\",")
        sb.append("\"trackingState\":\"").append(escJson(ballGroundPlaneTrackingState ?: "")).append("\",")
        sb.append("\"hitDistanceFromCamera_m\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneHitDistanceFromCameraMeters ?: 0f)).append(",")
        sb.append("\"extentX_m\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneExtentX ?: 0f)).append(",")
        sb.append("\"extentZ_m\":").append(String.format(Locale.US, "%.3f", ballGroundPlaneExtentZ ?: 0f))
        sb.append("}")
        sb.append(",\"diag\":{")
        appendBallJsonStringOrNull("ballGridMode", ui.ballGridMode)
        sb.append(',')
        appendBallJsonFloatOrNull("ballGridStepPx", ui.ballGridStepPx)
        sb.append(',')
        appendBallJsonIntOrNull("ballSampleTotalPoints", ui.ballSampleTotalPoints)
        sb.append(',')
        appendBallJsonIntOrNull("ballSampleValidHits", ui.ballSampleValidHits)
        sb.append(',')
        appendBallJsonStringOrNull("ballHitSourceUsed", ui.ballHitSourceUsed)
        sb.append(',')
        appendBallJsonBoolOrNull("ballFreezeUsed", ui.ballFreezeUsed)
        sb.append(',')
        sb.append("\"ballFreezeAgeMs\":").append(ui.ballFreezeAgeMs ?: "null")
        sb.append(',')
        appendBallJsonBoolOrNull("ballJumpRejected", ui.ballJumpRejected)
        sb.append(',')
        appendBallJsonIntOrNull("ballFixRuleWindow", ui.ballFixRuleWindow)
        sb.append(',')
        appendBallJsonIntOrNull("ballFixRuleNeedHits", ui.ballFixRuleNeedHits)
        sb.append(',')
        appendBallJsonIntOrNull("ballFixHitsInWindow", ui.ballFixHitsInWindow)
        sb.append(',')
        appendBallJsonStringOrNull("ballFixState", ui.ballFixState)
        sb.append("}")
        sb.append("},")

        sb.append("\"cup\":{")
        sb.append("\"fixState\":\"").append(if (cupFixed) "FIXED" else if (isFail) "FAIL" else "FAIL").append("\",")
        sb.append("\"validHits\":").append(cupValidHits ?: 0).append(",")
        sb.append("\"sigma_cm\":").append(String.format(Locale.US, "%.3f", cupSigmaCm ?: 0f)).append(",")
        sb.append("\"fixTime_ms\":").append(lastEndLockTimeMs ?: 0).append(",")
        sb.append("\"distanceFromCamera_m\":").append(String.format(Locale.US, "%.3f", cupDistanceFromCameraMeters ?: 0f)).append(",")
        sb.append("\"holdSigma_cm\":").append(String.format(Locale.US, "%.3f", cupHoldSigmaCm ?: 0f)).append(",")
        sb.append("\"holdMax_cm\":").append(String.format(Locale.US, "%.3f", cupHoldMaxCm ?: 0f)).append(",")
        sb.append("\"holdDuration_ms\":").append(cupHoldDurationMs ?: 0).append(",")
        sb.append("\"multiRayGridHalfSpanPx\":").append(String.format(Locale.US, "%.1f", cupMultiRayGridHalfSpanPx ?: 0f)).append(",")
        sb.append("\"multiRayStepPx\":").append(String.format(Locale.US, "%.1f", cupMultiRayStepPx ?: 0f)).append(",")
        sb.append("\"validSampleCount\":").append(cupValidSampleCount ?: 0).append(",")
        sb.append("\"hitDistanceAvg_m\":").append(String.format(Locale.US, "%.3f", cupHitDistanceAvgMeters ?: 0f)).append(",")
        sb.append("\"hitDistanceMax_m\":").append(String.format(Locale.US, "%.3f", cupHitDistanceMaxMeters ?: 0f)).append(",")
        sb.append("\"cameraY\":").append(String.format(Locale.US, "%.3f", cupCameraY ?: 0f)).append(",")
        sb.append("\"medianY\":").append(String.format(Locale.US, "%.3f", cupMedianY ?: 0f)).append(",")
        sb.append("\"centerYOffsetApplied\":").append(if (cupCenterYOffsetApplied == true) "true" else "false").append(",")
        sb.append("\"multiRayPlan\":\"").append(escJson(cupMultiRayPlan ?: "")).append("\",")
        sb.append("\"multiRayEstimatedDistance_m\":").append(String.format(Locale.US, "%.3f", cupMultiRayEstimatedDistanceMeters ?: 0f)).append(",")
        sb.append("\"multiRayProjectedCupPx\":").append(String.format(Locale.US, "%.1f", cupMultiRayProjectedCupPx ?: 0f)).append(",")
        sb.append("\"multiRayCenterFallbackUsed\":").append(if (cupMultiRayCenterFallbackUsed == true) "true" else "false")
        sb.append(",\"yolo\":{")
        sb.append("\"assistActiveAtLock\":").append(cupYoloAssistActiveAtLock).append(",")
        sb.append("\"stableFramesAtLock\":").append(cupYoloStableFramesAtLock).append(",")
        sb.append("\"activationCount\":").append(cupYoloActivationCount).append(",")
        sb.append("\"deactivationConsecutiveMissCount\":").append(cupYoloDeactivationConsecutiveMissCount).append(",")
        sb.append("\"confThreshold\":").append(String.format(Locale.US, "%.2f", CUP_YOLO_CONF_THRESHOLD)).append(",")
        sb.append("\"stableFramesOn\":").append(CUP_YOLO_STABLE_FRAMES_ON).append(",")
        sb.append("\"consecutiveMissToOff\":").append(CUP_YOLO_CONSECUTIVE_MISS_TO_OFF)
        sb.append("}")

        // Always-on diagnostics (use UiModel values directly; do NOT rely on END_LOCKED capture vars).
        fun appendJsonStringOrNull(key: String, value: String?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append("\"").append(escJson(value)).append("\"")
        }
        fun appendJsonIntOrNull(key: String, value: Int?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(value)
        }
        fun appendJsonFloatOrNull(key: String, value: Float?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(String.format(Locale.US, "%.3f", value))
        }
        fun appendJsonBoolOrNull(key: String, value: Boolean?) {
            sb.append("\"").append(key).append("\":")
            if (value == null) sb.append("null") else sb.append(if (value) "true" else "false")
        }

        sb.append(",\"diag\":{")
        appendJsonStringOrNull("engineState", ui.engineState.name)
        sb.append(',')
        appendJsonStringOrNull("failReasonCode", ui.failReasonCode)
        sb.append(',')
        appendJsonStringOrNull("failDetailCode", ui.failDetailCode)
        sb.append(',')
        appendJsonIntOrNull("fixedMinSamples", ui.fixedMinSamples)
        sb.append(',')
        appendJsonIntOrNull("bufSize", ui.bufSize)
        sb.append(',')
        appendJsonIntOrNull("sigmaOkConsecutive", ui.sigmaOkConsecutive)
        sb.append(',')
        sb.append("\"sigmaOkElapsedMs\":").append(ui.sigmaOkElapsedMs ?: "null")
        sb.append(',')
        appendJsonFloatOrNull("sigmaCurrent_cm", ui.sigmaCurrentCmEnd)
        sb.append(',')
        appendJsonFloatOrNull("sigmaThreshold_cm", ui.sigmaThresholdCmEnd)
        sb.append(',')
        sb.append("\"sampleValidHits\":").append(ui.sampleValidHits).append(',')
        sb.append("\"sampleTotalPoints\":").append(ui.sampleTotalPoints).append(',')
        appendJsonStringOrNull("multiRayPlan", ui.multiRayPlan)
        sb.append(',')
        appendJsonIntOrNull("validSampleCount", ui.validSampleCount)
        sb.append(',')
        appendJsonFloatOrNull("multiRayGridHalfSpanPx", ui.multiRayGridHalfSpanPx)
        sb.append(',')
        appendJsonFloatOrNull("multiRayStepPx", ui.multiRayStepPx)
        sb.append(',')
        appendJsonBoolOrNull("centerYOffsetApplied", ui.centerYOffsetApplied)
        sb.append(',')
        appendJsonBoolOrNull("multiRayCenterFallbackUsed", ui.multiRayCenterFallbackUsed)
        sb.append(',')
        appendJsonFloatOrNull("multiRayEstimatedDistance_m", ui.multiRayEstimatedDistanceMeters)
        sb.append(',')
        appendJsonFloatOrNull("multiRayProjectedCupPx", ui.multiRayProjectedCupPx)
        sb.append("}")
        sb.append("},")

        sb.append("\"result\":{")
        sb.append("\"distanceSource\":\"LIVE_SNAPSHOT\",")
        sb.append("\"liveSource\":\"").append(escJson(lastNonFinalLiveSource ?: (ui.liveSource ?: "null"))).append("\",")
        sb.append("\"liveRaw_m\":").append(String.format(Locale.US, "%.3f", lastNonFinalLiveRawMeters ?: 0f)).append(",")
        sb.append("\"centerHitValid\":").append(if (lastNonFinalCenterHitValid == true) "true" else "false").append(",")
        sb.append("\"liveAtFinish_m\":").append(String.format(Locale.US, "%.3f", liveAtFinish)).append(",")
        sb.append("\"finalDistance_m\":").append(String.format(Locale.US, "%.3f", finalMeters)).append(",")
        sb.append("\"ballCupPlaneAngleDeg\":").append(String.format(Locale.US, "%.2f", ballCupPlaneAngleDeg ?: 0f)).append(",")
        sb.append("\"liveFinalDiff_cm\":").append(String.format(Locale.US, "%.3f", liveFinalDiffCm)).append(",")
        sb.append("\"error_cm\":").append(String.format(Locale.US, "%.3f", errorCm))
        sb.append("},")

        sb.append("\"trackingStateAtCup\":\"").append(escJson(trackingAtCup)).append("\",")
        sb.append("\"lightCondition\":\"").append(escJson(light)).append("\",")
        sb.append("\"notes\":\"").append(escJson(notes)).append("\"")
        sb.append("}")
        return sb.toString()
    }

    private fun maybeShowSurveyFlow() {
        val p = prefs()
        val appLaunchCount = p.getInt(KEY_APP_LAUNCHES, 0)
        val resultCount = p.getInt(KEY_RESULT_SUCCESS_COUNT, 0)
        val installMs = p.getLong(KEY_INSTALL_TIME_MS, 0L)
        val lastUseMs = p.getLong(KEY_LAST_USE_MS, 0L)
        val now = System.currentTimeMillis()

        val daysSinceInstall = daysBetweenMs(installMs, now)
        val daysSinceLastUse = daysBetweenMs(lastUseMs, now) // usually 0 on resume

        val survey1Shown = p.getBoolean(KEY_SURVEY1_SHOWN, false)
        val survey2Shown = p.getBoolean(KEY_SURVEY2_SHOWN, false)

        // Survey 2 has priority if eligible (but only after survey1 was shown).
        val survey2Eligible =
            daysSinceInstall >= 60 &&
                resultCount >= 30 &&
                appLaunchCount >= 10 &&
                daysSinceLastUse <= 14 &&
                survey1Shown &&
                !survey2Shown

        val survey1Eligible =
            resultCount >= 10 &&
                appLaunchCount >= 3 &&
                daysSinceInstall >= 2 &&
                !survey1Shown

        when {
            survey2Eligible -> showSurvey2BottomSheet()
            survey1Eligible -> showSurvey1BottomSheet()
            else -> Unit
        }
    }

    private fun showSurvey1BottomSheet() {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_survey, null)
        dialog.setContentView(v)

        v.findViewById<TextView>(R.id.txt_survey_title).text = getString(R.string.survey1_title)
        v.findViewById<TextView>(R.id.txt_survey_desc).text = getString(R.string.survey_desc)

        val btn1 = v.findViewById<Button>(R.id.btn_opt_1)
        val btn2 = v.findViewById<Button>(R.id.btn_opt_2)
        val btn3 = v.findViewById<Button>(R.id.btn_opt_3)
        val btnClose = v.findViewById<Button>(R.id.btn_close_survey)

        btn1.text = getString(R.string.survey1_opt_very_stable)
        btn2.text = getString(R.string.survey1_opt_slightly_shaky)
        btn3.text = getString(R.string.survey1_opt_unstable)

        fun pick(choice: String) {
            prefs().edit()
                .putBoolean(KEY_SURVEY1_SHOWN, true)
                .putString(KEY_SURVEY1_CHOICE, choice)
                .apply()
            dialog.dismiss()
            if (choice == "unstable") {
                showSendLogBottomSheet(source = "survey1", surveyChoice = choice)
            } else {
                Toast.makeText(this, getString(R.string.feedback_toast_thanks), Toast.LENGTH_SHORT).show()
            }
        }

        btn1.setOnClickListener { pick("very_stable") }
        btn2.setOnClickListener { pick("slightly_shaky") }
        btn3.setOnClickListener { pick("unstable") }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showSurvey2BottomSheet() {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_survey, null)
        dialog.setContentView(v)

        v.findViewById<TextView>(R.id.txt_survey_title).text = getString(R.string.survey2_title)
        v.findViewById<TextView>(R.id.txt_survey_desc).text = getString(R.string.survey_desc)

        val btn1 = v.findViewById<Button>(R.id.btn_opt_1)
        val btn2 = v.findViewById<Button>(R.id.btn_opt_2)
        val btn3 = v.findViewById<Button>(R.id.btn_opt_3)
        val btnClose = v.findViewById<Button>(R.id.btn_close_survey)

        btn1.text = getString(R.string.survey2_opt_trust_high)
        btn2.text = getString(R.string.survey2_opt_sometimes_unstable)
        btn3.text = getString(R.string.survey2_opt_hard_to_trust)

        fun pick(choice: String) {
            prefs().edit()
                .putBoolean(KEY_SURVEY2_SHOWN, true)
                .putString(KEY_SURVEY2_CHOICE, choice)
                .apply()
            dialog.dismiss()

            when (choice) {
                "trust_high" -> showReviewPromptBottomSheet()
                "sometimes_unstable" -> showSendLogBottomSheet(source = "survey2", surveyChoice = choice)
                else -> showSendLogBottomSheet(source = "survey2", surveyChoice = choice)
            }
        }

        btn1.setOnClickListener { pick("trust_high") }
        btn2.setOnClickListener { pick("sometimes_unstable") }
        btn3.setOnClickListener { pick("hard_to_trust") }
        btnClose.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun showReviewPromptBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt()
            )
            setBackgroundColor(Color.parseColor("#111111"))
        }
        val title = TextView(this).apply {
            text = getString(R.string.review_prompt_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }
        val body = TextView(this).apply {
            text = getString(R.string.review_prompt_body)
            setTextColor(Color.parseColor("#CFCFCF"))
            textSize = 13f
        }
        val btnRate = Button(this).apply {
            text = getString(R.string.review_btn_rate)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#4CAF50"))
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnClickListener {
                dialog.dismiss()
                requestInAppReviewOnce()
            }
        }
        val btnClose = Button(this).apply {
            text = getString(R.string.btn_close)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, 0)
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(title)
        root.addView(body)
        root.addView(btnRate)
        root.addView(btnClose)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun requestInAppReviewOnce() {
        val p = prefs()
        if (p.getBoolean(KEY_REVIEW_REQUESTED, false)) return
        p.edit().putBoolean(KEY_REVIEW_REQUESTED, true).apply()

        try {
            val manager = ReviewManagerFactory.create(this)
            val request = manager.requestReviewFlow()
            request.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val reviewInfo = task.result
                    manager.launchReviewFlow(this, reviewInfo)
                }
            }
        } catch (_: Exception) {
            // Ignore silently (review API not available on some devices/distributions)
        }
    }

    private fun showSendLogBottomSheet(source: String, surveyChoice: String?) {
        val dialog = BottomSheetDialog(this)
        val v = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_send_log, null)
        dialog.setContentView(v)

        val btnSend = v.findViewById<Button>(R.id.btn_send_log)
        val btnCancel = v.findViewById<Button>(R.id.btn_cancel_send_log)
        btnSend.setOnClickListener {
            dialog.dismiss()
            sendFeedbackEmail(surveyChoice = if (surveyChoice == null) null else "${source}:${surveyChoice}")
            Toast.makeText(this, getString(R.string.feedback_toast_thanks), Toast.LENGTH_SHORT).show()
        }
        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSendFeedbackConfirmDialog() {
        showSendLogBottomSheet(source = "manual", surveyChoice = null)
    }

    private fun sendFeedbackEmail(surveyChoice: String?) {
        val logFile = buildFeedbackLogFile(surveyChoice)
        val uri =
            try {
                FileProvider.getUriForFile(this, "${packageName}.fileprovider", logFile)
            } catch (e: Exception) {
                Log.e(TAG, "FileProvider failed: ${e.message}", e)
                return
            }

        val subject = "[GreenIQ Distance Feedback] ${android.os.Build.MODEL} / ${appVersionName()}"
        val body = buildEmailBodyText(surveyChoice)

        val intent =
            android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "application/json"
                `package` = "com.google.android.gm"
                putExtra(android.content.Intent.EXTRA_EMAIL, arrayOf(feedbackEmailTo))
                putExtra(android.content.Intent.EXTRA_SUBJECT, subject)
                putExtra(android.content.Intent.EXTRA_TEXT, body)
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.feedback_toast_no_email_app), Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildEmailBodyText(surveyChoice: String?): String {
        val axis = if (isAxisXzSelected()) "XZ" else "XYZ"
        val sb = StringBuilder()
        if (surveyChoice != null) sb.append("[Survey] ").append(surveyChoice).append('\n')
        sb.append('\n')
        sb.append("- App: ").append(getString(R.string.app_name)).append(" / ").append(appVersionName()).append('\n')
        sb.append("- Package: ").append(packageName).append('\n')
        sb.append("- Device: ").append(android.os.Build.MODEL).append('\n')
        sb.append("- Android: ").append(android.os.Build.VERSION.RELEASE).append('\n')
        sb.append("- Mode: ").append(axis).append('\n')
        sb.append("- Unit: ").append(if (unitMode == UnitMode.YARD) "yd" else "m").append('\n')
        sb.append("- Locale: ").append(Locale.getDefault().toLanguageTag()).append('\n')
        sb.append("- ScreenAdjust rot=").append(screenRotDeg.toInt())
            .append(" mirrorH=").append(screenMirrorH)
            .append(" mirrorV=").append(screenMirrorV).append('\n')
        sb.append('\n')
        val last = recentSessions.lastOrNull()
        if (last != null) {
            sb.append("- Final(m): ").append(String.format(Locale.US, "%.3f", last.distanceFinalMeters)).append('\n')
            if (last.distanceLiveAtFinishMeters != null) {
                sb.append("- Live@finish(m): ").append(String.format(Locale.US, "%.3f", last.distanceLiveAtFinishMeters)).append('\n')
            }
            sb.append("- Displayed: ")
                .append(String.format(Locale.US, "%.3f", last.distanceDisplayed))
                .append(' ')
                .append(last.distanceDisplayedUnit)
                .append('\n')
            if (last.liveFinalDiffCm != null) sb.append("- Live↔Final diff(cm): ").append(String.format(Locale.US, "%.1f", last.liveFinalDiffCm)).append('\n')
            if (last.hMeters != null && last.vMeters != null) {
                sb.append("- H/V(m): ").append(String.format(Locale.US, "%.3f", last.hMeters))
                    .append(" / ").append(String.format(Locale.US, "%.3f", last.vMeters)).append('\n')
            }
            if (last.startLockTimeMs != null) sb.append("- START lock(ms): ").append(last.startLockTimeMs).append('\n')
            if (last.endLockTimeMs != null) sb.append("- END lock(ms): ").append(last.endLockTimeMs).append('\n')
        }
        sb.append('\n')
        sb.append("(See attached log file.)\n")
        return sb.toString()
    }

    private fun buildFeedbackLogFile(surveyChoice: String?): File {
        val ts = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(java.util.Date())
        val f = File(cacheDir, "GIQ_feedback_${ts}.json")

        fun esc(s: String): String =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")

        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"surveyChoice\": ").append(if (surveyChoice == null) "null" else "\"${esc(surveyChoice)}\"").append(",\n")
        sb.append("  \"appName\": \"").append(esc(getString(R.string.app_name))).append("\",\n")
        sb.append("  \"packageName\": \"").append(esc(packageName)).append("\",\n")
        sb.append("  \"appVersion\": \"").append(esc(appVersionName())).append("\",\n")
        sb.append("  \"debuggable\": ").append(isDebuggableBuild()).append(",\n")
        sb.append("  \"deviceModel\": \"").append(esc(android.os.Build.MODEL ?: "unknown")).append("\",\n")
        sb.append("  \"androidVersion\": \"").append(esc(android.os.Build.VERSION.RELEASE ?: "unknown")).append("\",\n")
        sb.append("  \"locale\": \"").append(esc(Locale.getDefault().toLanguageTag())).append("\",\n")
        sb.append("  \"unitMode\": \"").append(if (unitMode == UnitMode.YARD) "yd" else "m").append("\",\n")
        sb.append("  \"distanceUnit\": \"").append(if (unitMode == UnitMode.YARD) "YARD" else "METER").append("\",\n")
        sb.append("  \"screenAdjust\": {")
        sb.append("\"rotationDeg\": ").append(screenRotDeg.toInt()).append(", ")
        sb.append("\"mirrorH\": ").append(screenMirrorH).append(", ")
        sb.append("\"mirrorV\": ").append(screenMirrorV)
        sb.append("},\n")

        fun appendPointOrNullJson(p: PointF?): String {
            return if (p == null) {
                "null"
            } else {
                "{\"x\":${String.format(Locale.US, "%.1f", p.x)},\"y\":${String.format(Locale.US, "%.1f", p.y)}}"
            }
        }

        val yoloHitDeltaPx =
            if (cupDebugYoloPoint != null && cupDebugHitPoint != null) {
                val dx = cupDebugYoloPoint!!.x - cupDebugHitPoint!!.x
                val dy = cupDebugYoloPoint!!.y - cupDebugHitPoint!!.y
                kotlin.math.sqrt(dx * dx + dy * dy)
            } else {
                null
            }
        sb.append("  \"yoloDebug\": {")
        sb.append("\"cupAssistActive\": ").append(cupYoloAssistActive).append(", ")
        sb.append("\"yoloStableFrames\": ").append(cupYoloStableFrames).append(", ")
        sb.append("\"yoloConsecutiveMisses\": ").append(cupYoloConsecutiveMisses).append(", ")
        sb.append("\"yoloActivationCount\": ").append(cupYoloActivationCount).append(", ")
        sb.append("\"yoloDeactivationConsecutiveMissCount\": ").append(cupYoloDeactivationConsecutiveMissCount).append(", ")
        sb.append("\"yoloAssistActiveAtLock\": ").append(cupYoloAssistActiveAtLock).append(", ")
        sb.append("\"yoloStableFramesAtLock\": ").append(cupYoloStableFramesAtLock).append(", ")
        sb.append("\"yoloConfThreshold\": ").append(String.format(Locale.US, "%.2f", CUP_YOLO_CONF_THRESHOLD)).append(", ")
        sb.append("\"yoloStableFramesOn\": ").append(CUP_YOLO_STABLE_FRAMES_ON).append(", ")
        sb.append("\"yoloConsecutiveMissToOff\": ").append(CUP_YOLO_CONSECUTIVE_MISS_TO_OFF).append(", ")
        sb.append("\"yoloCenterPx\": ").append(appendPointOrNullJson(cupDebugYoloPoint)).append(", ")
        sb.append("\"hitCenterPx\": ").append(appendPointOrNullJson(cupDebugHitPoint)).append(", ")
        sb.append("\"roiCenterPx\": ").append(appendPointOrNullJson(cupDebugRoiPoint)).append(", ")
        sb.append("\"yoloHitDeltaPx\": ").append(if (yoloHitDeltaPx == null) "null" else String.format(Locale.US, "%.2f", yoloHitDeltaPx))
        sb.append("},\n")

        // Include field-test measurement logs (JSONL) for easy sharing.
        val mf = measurementLogFile()
        val lines =
            if (mf.exists()) {
                try {
                    mf.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
                } catch (_: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
        sb.append("  \"measurements\": [\n")
        lines.forEachIndexed { idx, line ->
            sb.append("    ").append(line.trim())
            if (idx != lines.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ],\n")

        sb.append("  \"recentSessions\": [\n")
        recentSessions.forEachIndexed { idx, s ->
            sb.append("    {\n")
            sb.append("      \"timestampMs\": ").append(s.timestampMs).append(",\n")
            sb.append("      \"axisMode\": \"").append(esc(s.axisMode)).append("\",\n")
            sb.append("      \"distance_final_meters\": ").append(String.format(Locale.US, "%.6f", s.distanceFinalMeters)).append(",\n")
            sb.append("      \"distance_live_at_finish_meters\": ").append(if (s.distanceLiveAtFinishMeters == null) "null" else String.format(Locale.US, "%.6f", s.distanceLiveAtFinishMeters)).append(",\n")
            sb.append("      \"distance_displayed\": ").append(String.format(Locale.US, "%.6f", s.distanceDisplayed)).append(",\n")
            sb.append("      \"distance_displayed_unit\": \"").append(esc(s.distanceDisplayedUnit)).append("\",\n")
            sb.append("      \"live_final_diff_cm\": ").append(if (s.liveFinalDiffCm == null) "null" else String.format(Locale.US, "%.2f", s.liveFinalDiffCm)).append(",\n")
            sb.append("      \"hMeters\": ").append(if (s.hMeters == null) "null" else String.format(Locale.US, "%.6f", s.hMeters)).append(",\n")
            sb.append("      \"vMeters\": ").append(if (s.vMeters == null) "null" else String.format(Locale.US, "%.6f", s.vMeters)).append(",\n")
            sb.append("      \"startLockTimeMs\": ").append(s.startLockTimeMs ?: "null").append(",\n")
            sb.append("      \"endLockTimeMs\": ").append(s.endLockTimeMs ?: "null").append(",\n")
            sb.append("      \"unitMode\": \"").append(esc(s.unitMode)).append("\",\n")
            sb.append("      \"distanceUnit\": \"").append(if (s.unitMode == "yd") "YARD" else "METER").append("\",\n")
            sb.append("      \"ball\": {")
            sb.append("\"validHits\": ").append(s.ballValidHits ?: "null").append(", ")
            sb.append("\"totalPoints\": ").append(s.ballTotalPoints ?: "null").append(", ")
            sb.append("\"sigmaCm\": ").append(if (s.ballSigmaCm == null) "null" else String.format(Locale.US, "%.3f", s.ballSigmaCm)).append(", ")
            sb.append("\"sigmaMaxCm\": ").append(if (s.ballSigmaMaxCm == null) "null" else String.format(Locale.US, "%.3f", s.ballSigmaMaxCm)).append(", ")
            sb.append("\"distanceFromCamera_m\": ").append(if (s.ballDistanceFromCameraMeters == null) "null" else String.format(Locale.US, "%.6f", s.ballDistanceFromCameraMeters)).append(", ")
            sb.append("\"trackingStateAtFix\": ").append(if (s.trackingAtBallFix == null) "null" else "\"${esc(s.trackingAtBallFix)}\"")
            sb.append("},\n")
            sb.append("      \"cup\": {")
            sb.append("\"validHits\": ").append(s.cupValidHits ?: "null").append(", ")
            sb.append("\"totalPoints\": ").append(s.cupTotalPoints ?: "null").append(", ")
            sb.append("\"sigmaCm\": ").append(if (s.cupSigmaCm == null) "null" else String.format(Locale.US, "%.3f", s.cupSigmaCm)).append(", ")
            sb.append("\"sigmaMaxCm\": ").append(if (s.cupSigmaMaxCm == null) "null" else String.format(Locale.US, "%.3f", s.cupSigmaMaxCm)).append(", ")
            sb.append("\"distanceFromCamera_m\": ").append(if (s.cupDistanceFromCameraMeters == null) "null" else String.format(Locale.US, "%.6f", s.cupDistanceFromCameraMeters)).append(", ")
            sb.append("\"holdSigma_cm\": ").append(if (s.cupHoldSigmaCm == null) "null" else String.format(Locale.US, "%.3f", s.cupHoldSigmaCm)).append(", ")
            sb.append("\"holdMax_cm\": ").append(if (s.cupHoldMaxCm == null) "null" else String.format(Locale.US, "%.3f", s.cupHoldMaxCm)).append(", ")
            sb.append("\"holdDuration_ms\": ").append(s.cupHoldDurationMs ?: "null").append(", ")
            sb.append("\"trackingStateAtFix\": ").append(if (s.trackingAtCupFix == null) "null" else "\"${esc(s.trackingAtCupFix)}\"")
            sb.append("},\n")
            sb.append("      \"failReasonCode\": ").append(if (s.failReasonCode == null) "null" else "\"${esc(s.failReasonCode)}\"").append(",\n")
            sb.append("      \"screenRotationDeg\": ").append(s.screenRotDeg).append(",\n")
            sb.append("      \"mirrorH\": ").append(s.mirrorH).append(",\n")
            sb.append("      \"mirrorV\": ").append(s.mirrorV).append("\n")
            sb.append("    }")
            if (idx != recentSessions.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("  ]\n")
        sb.append("}\n")

        f.writeText(sb.toString(), Charsets.UTF_8)
        return f
    }

    private fun appVersionName(): String {
        return try {
            @Suppress("DEPRECATION")
            val pi = packageManager.getPackageInfo(packageName, 0)
            pi.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun showSettingsBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.settings_sheet_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }

        fun mkItem(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setTextColor(Color.WHITE)
                textSize = 14f
                setBackgroundColor(Color.parseColor("#2A2A2A"))
                layoutParams =
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = (8 * resources.displayMetrics.density).toInt() }
                setOnClickListener {
                    dialog.dismiss()
                    onClick()
                }
            }
        }

        val itemLang = mkItem(getString(R.string.menu_language)) { showLanguageBottomSheet() }
        val itemAdjust = mkItem(getString(R.string.menu_screen_adjust)) { showScreenAdjustBottomSheet() }
        val itemUnit = mkItem(getString(R.string.menu_unit)) { showUnitBottomSheet() }
        val itemAcc = mkItem(getString(R.string.btn_accuracy)) { showAccuracyBottomSheet() }
        val itemGuide = mkItem(getString(R.string.menu_measurement_guide)) { showMeasurementGuideBottomSheet() }
        val itemFeedback = mkItem(getString(R.string.menu_send_feedback)) { showSendFeedbackConfirmDialog() }

        val btnClose = Button(this).apply {
            text = getString(R.string.btn_cancel)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams =
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = (14 * resources.displayMetrics.density).toInt() }
            setOnClickListener { dialog.dismiss() }
        }

        root.addView(title)
        root.addView(itemLang)
        root.addView(itemAdjust)
        root.addView(itemUnit)
        root.addView(itemAcc)
        root.addView(itemGuide)
        root.addView(itemFeedback)
        root.addView(btnClose)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showFieldTestBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.menu_field_test)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }

        fun label(text: String) =
            TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#B0B0B0"))
                textSize = 13f
                setPadding(0, (10 * resources.displayMetrics.density).toInt(), 0, (6 * resources.displayMetrics.density).toInt())
            }

        val p = prefs()
        val savedGroup = p.getString(KEY_TEST_DISTANCE_GROUP, "2m") ?: "2m"
        val savedTruth = p.getFloat(KEY_TEST_GROUND_TRUTH_M, 0f)
        val savedLight = p.getString(KEY_TEST_LIGHT, "SUNNY") ?: "SUNNY"
        val savedNotes = p.getString(KEY_TEST_NOTES, "") ?: ""

        root.addView(title)

        root.addView(label("Distance group"))
        val groupDistance = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        fun rb(text: String) = android.widget.RadioButton(this).apply { id = View.generateViewId(); this.text = text; setTextColor(Color.WHITE) }
        val rb2 = rb("2m")
        val rb5 = rb("5m")
        val rb10 = rb("10m")
        groupDistance.addView(rb2); groupDistance.addView(rb5); groupDistance.addView(rb10)
        when (savedGroup) {
            "10m" -> groupDistance.check(rb10.id)
            "5m" -> groupDistance.check(rb5.id)
            else -> groupDistance.check(rb2.id)
        }
        root.addView(groupDistance)

        root.addView(label("Ground truth (m)"))
        val editTruth = EditText(this).apply {
            setText(if (savedTruth <= 0f) "" else String.format(Locale.US, "%.2f", savedTruth))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(16, 14, 16, 14)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            hint = "e.g., 2.00"
            setHintTextColor(Color.parseColor("#808080"))
        }
        root.addView(editTruth)

        root.addView(label("Light condition"))
        val groupLight = android.widget.RadioGroup(this).apply { orientation = android.widget.RadioGroup.HORIZONTAL }
        val rbSunny = rb("SUNNY")
        val rbCloudy = rb("CLOUDY")
        val rbShade = rb("SHADE")
        groupLight.addView(rbSunny); groupLight.addView(rbCloudy); groupLight.addView(rbShade)
        when (savedLight) {
            "SHADE" -> groupLight.check(rbShade.id)
            "CLOUDY" -> groupLight.check(rbCloudy.id)
            else -> groupLight.check(rbSunny.id)
        }
        root.addView(groupLight)

        root.addView(label("Notes"))
        val editNotes = EditText(this).apply {
            setText(savedNotes)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setPadding(16, 14, 16, 14)
            hint = "(optional)"
            setHintTextColor(Color.parseColor("#808080"))
        }
        root.addView(editNotes)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val btnCancel = Button(this).apply {
            text = getString(R.string.btn_cancel)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (8 * resources.displayMetrics.density).toInt() }
            setOnClickListener { dialog.dismiss() }
        }

        val btnSave = Button(this).apply {
            text = "SAVE"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#18A558"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val dg =
                    when (groupDistance.checkedRadioButtonId) {
                        rb10.id -> "10m"
                        rb5.id -> "5m"
                        else -> "2m"
                    }
                val truth = editTruth.text.toString().trim().toFloatOrNull() ?: 0f
                val light =
                    when (groupLight.checkedRadioButtonId) {
                        rbShade.id -> "SHADE"
                        rbCloudy.id -> "CLOUDY"
                        else -> "SUNNY"
                    }
                val notes = editNotes.text.toString()
                p.edit()
                    .putString(KEY_TEST_DISTANCE_GROUP, dg)
                    .putFloat(KEY_TEST_GROUND_TRUTH_M, truth)
                    .putString(KEY_TEST_LIGHT, light)
                    .putString(KEY_TEST_NOTES, notes)
                    .apply()
                dialog.dismiss()
            }
        }

        btnRow.addView(btnCancel)
        btnRow.addView(btnSave)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showUnitBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.unit_sheet_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        val rbM = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.unit_m)
            setTextColor(Color.WHITE)
        }
        val rbYd = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.unit_yd)
            setTextColor(Color.WHITE)
        }
        group.addView(rbM)
        group.addView(rbYd)

        when (unitMode) {
            UnitMode.METER -> group.check(rbM.id)
            UnitMode.YARD -> group.check(rbYd.id)
        }

        val btnCancel = Button(this).apply {
            text = getString(R.string.btn_close)
            setTextColor(Color.WHITE)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener { dialog.dismiss() }
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val newMode =
                when (checkedId) {
                    rbYd.id -> UnitMode.YARD
                    else -> UnitMode.METER
                }
            if (newMode != unitMode) {
                unitMode = newMode
                saveUnitPref(newMode)
                refreshDisplayedDistance()
            }
            dialog.dismiss()
        }

        root.addView(title)
        root.addView(group)
        root.addView(btnCancel)
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showScreenAdjustBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        // Saved values (for CANCEL/dismiss restore)
        val savedRot = screenRotDeg
        val savedMh = screenMirrorH
        val savedMv = screenMirrorV
        var applied = false

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.screen_adjust_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val desc = TextView(this).apply {
            text = getString(R.string.screen_adjust_desc)
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 13f
            setPadding(0, 0, 0, (10 * resources.displayMetrics.density).toInt())
        }

        val rotHeader = TextView(this).apply {
            text = getString(R.string.screen_adjust_rotation)
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 14f
        }

        val rotValue = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(0, (6 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        fun snap90(deg: Int): Int {
            // Snap to {-180,-90,0,90,180} with correct negative rounding.
            // NOTE: integer division truncates toward 0, so use float rounding.
            val clamped = deg.coerceIn(-180, 180)
            val snapped = kotlin.math.round(clamped / 90f).toInt() * 90
            return snapped.coerceIn(-180, 180)
        }

        // Local state inside dialog (CANCEL does not persist).
        var rot = snap90(screenRotDeg.toInt())
        var mh = screenMirrorH
        var mv = screenMirrorV

        fun updateRotText() {
            rotValue.text = getString(R.string.screen_adjust_rotation_value, rot)
        }
        updateRotText()

        val seek = android.widget.SeekBar(this).apply {
            // SeekBar min is API 26; use offset mapping for minSdk 24.
            max = 360
            progress = rot + 180
        }
        seek.setOnSeekBarChangeListener(
            object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    val raw = (progress - 180).coerceIn(-180, 180)
                    val snapped = snap90(raw)
                    rot = snapped
                    updateRotText()
                    // Keep the thumb on snapped steps so the user feels "90° clicks".
                    if (fromUser && snapped + 180 != progress) {
                        seekBar?.progress = snapped + 180
                    }
                    // Live preview
                    screenRotDeg = rot.toFloat()
                    screenMirrorH = mh
                    screenMirrorV = mv
                    previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
                }
                override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
            }
        )

        // 90° tick labels (snapped steps) for better clarity.
        val tickRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (4 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        fun mkTick(text: String): TextView {
            return TextView(this).apply {
                this.text = text
                setTextColor(Color.parseColor("#8A8A8A"))
                textSize = 12f
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        }
        tickRow.addView(mkTick("-180°"))
        tickRow.addView(mkTick("-90°"))
        tickRow.addView(mkTick("0°"))
        tickRow.addView(mkTick("90°"))
        tickRow.addView(mkTick("180°"))

        val mirrorHeader = TextView(this).apply {
            text = getString(R.string.screen_adjust_mirror_header)
            setTextColor(Color.parseColor("#B0B0B0"))
            textSize = 14f
            setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        val cbH = android.widget.CheckBox(this).apply {
            text = getString(R.string.screen_adjust_mirror_h)
            setTextColor(Color.WHITE)
            isChecked = mh
            setOnCheckedChangeListener { _, checked ->
                mh = checked
                // Live preview
                screenRotDeg = rot.toFloat()
                screenMirrorH = mh
                screenMirrorV = mv
                previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
            }
        }
        val cbV = android.widget.CheckBox(this).apply {
            text = getString(R.string.screen_adjust_mirror_v)
            setTextColor(Color.WHITE)
            isChecked = mv
            setOnCheckedChangeListener { _, checked ->
                mv = checked
                // Live preview
                screenRotDeg = rot.toFloat()
                screenMirrorH = mh
                screenMirrorV = mv
                previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
            }
        }

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (16 * resources.displayMetrics.density).toInt(), 0, 0)
        }

        fun mkBtn(text: String): Button {
            return Button(this).apply {
                this.text = text
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.parseColor("#2A2A2A"))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = (6 * resources.displayMetrics.density).toInt()
                }
            }
        }

        val btnReset = mkBtn(getString(R.string.screen_adjust_btn_reset)).apply {
            setOnClickListener {
                rot = 0
                mh = false
                mv = false
                seek.progress = 180
                cbH.isChecked = false
                cbV.isChecked = false
                updateRotText()
                // Live preview
                screenRotDeg = 0f
                screenMirrorH = false
                screenMirrorV = false
                previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
            }
        }

        val btnCancel = mkBtn(getString(R.string.screen_adjust_btn_cancel)).apply {
            setOnClickListener {
                // Restore immediately
                screenRotDeg = savedRot
                screenMirrorH = savedMh
                screenMirrorV = savedMv
                previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
                dialog.dismiss()
            }
        }

        val btnApply = mkBtn(getString(R.string.screen_adjust_btn_apply)).apply {
            layoutParams = (layoutParams as LinearLayout.LayoutParams).apply { marginEnd = 0 }
            setOnClickListener {
                // Persist snapped 90° steps only.
                screenRotDeg = snap90(rot).toFloat()
                screenMirrorH = mh
                screenMirrorV = mv
                saveScreenAdjustPrefs()
                previewGlView?.let { gl ->
                    applyScreenAdjustToPreview(gl)
                    gl.post { mapper?.markDirty() }
                }
                applied = true
                dialog.dismiss()
            }
        }

        btnRow.addView(btnReset)
        btnRow.addView(btnCancel)
        btnRow.addView(btnApply)

        root.addView(title)
        root.addView(desc)
        root.addView(rotHeader)
        root.addView(rotValue)
        root.addView(seek)
        root.addView(tickRow)
        root.addView(mirrorHeader)
        root.addView(cbH)
        root.addView(cbV)
        root.addView(btnRow)

        dialog.setContentView(root)
        dialog.setOnDismissListener {
            // If user dismissed without APPLY (back/outside), restore saved values.
            if (!applied) {
                screenRotDeg = savedRot
                screenMirrorH = savedMh
                screenMirrorV = savedMv
                previewGlView?.let { gl -> applyScreenAdjustToPreview(gl) }
            }
        }
        dialog.show()
    }

    private fun showLanguageBottomSheet() {
        val dialog = BottomSheetDialog(this)
        val padding = (24 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
            // 일부 기기/테마에서 BottomSheet 기본 배경이 흰색이라,
            // 텍스트(Color.WHITE)가 보이지 않는 문제가 있어 배경을 명시적으로 지정.
            setBackgroundColor(Color.parseColor("#111111"))
        }

        val title = TextView(this).apply {
            text = getString(R.string.lang_sheet_title)
            setTextColor(Color.WHITE)
            textSize = 18f
            setPadding(0, 0, 0, (12 * resources.displayMetrics.density).toInt())
        }

        val group = android.widget.RadioGroup(this).apply {
            orientation = android.widget.RadioGroup.VERTICAL
        }

        val rbKo = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.lang_korean)
            setTextColor(Color.WHITE)
        }
        val rbEn = android.widget.RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.lang_english)
            setTextColor(Color.WHITE)
        }

        group.addView(rbKo)
        group.addView(rbEn)

        when (currentLang()) {
            "ko" -> group.check(rbKo.id)
            else -> group.check(rbEn.id)
        }

        val btnCancel = Button(this).apply {
            text = getString(R.string.btn_close)
            setTextColor(Color.WHITE)
            setPadding(0, (12 * resources.displayMetrics.density).toInt(), 0, 0)
            // 루트 배경을 어둡게 고정했으므로 버튼도 대비를 맞춤
            setBackgroundColor(Color.parseColor("#2A2A2A"))
            setOnClickListener { dialog.dismiss() }
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val newLang =
                when (checkedId) {
                    rbKo.id -> "ko"
                    rbEn.id -> "en"
                    else -> currentLang()
                }
            if (newLang != currentLang()) {
                prefs().edit().putString(KEY_LANG, newLang).apply()
                dialog.dismiss()
                recreate()
            } else {
                dialog.dismiss()
            }
        }

        root.addView(title)
        root.addView(group)
        root.addView(btnCancel)

        dialog.setContentView(root)
        dialog.show()
    }
}

