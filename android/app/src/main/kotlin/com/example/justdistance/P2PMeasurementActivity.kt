package com.justdistance.measurepro

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Frame
import com.google.ar.core.InstantPlacementPoint
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * JustDistance Pro (Phase 1)
 * - No camera preview shown (GLSurfaceView is fully transparent)
 * - Implicit ROI at screen center
 * - P2P only (movement excluded)
 * - SSOT: session.update() only inside onDrawFrame
 */
class P2PMeasurementActivity : AppCompatActivity(), GLSurfaceView.Renderer {
    private val TAG = "P2PMeasurePro"

    // --- v3 fixed constants ---
    private val HIT_THROTTLE_NS = 50_000_000L // 20Hz (nanoTime-based)
    private val FAIL_NO_VALID_HITS_M = 10
    private val STABILIZING_TIMEOUT_NS = 2_000_000_000L // 2.0s
    private val PAUSED_GRACE_NS = 1_000_000_000L // 1.0s

    // sigma model (meters)
    private val A = 0.003f
    private val B = 0.0015f
    private val SIGMA_MIN = 0.005f
    private val SIGMA_CAP = 0.030f

    private enum class AxisMode { XYZ, XZ }

    private enum class State {
        IDLE,
        AIM_START,
        STABILIZING_START,
        START_LOCKED,
        AIM_END,
        STABILIZING_END,
        END_LOCKED,
        FAIL
    }

    private enum class FailReason { NO_VALID_HITS, TRACKING_LOST, TIMEOUT }

    private enum class HitType { PLANE, DEPTH_OR_POINT, NONE }

    private var session: Session? = null
    private var installRequested = false
    private var glView: GLSurfaceView? = null
    private val backgroundRenderer = BackgroundRenderer()

    // UI
    private lateinit var txtState: TextView
    private lateinit var txtTracking: TextView
    private lateinit var txtSpan: TextView
    private lateinit var txtSigma: TextView
    private lateinit var txtHits: TextView
    private lateinit var txtGrid: TextView
    private lateinit var txtHitType: TextView
    private lateinit var txtResult: TextView
    private lateinit var btnSetStart: Button
    private lateinit var btnSetEnd: Button
    private lateinit var btnReset: Button
    private lateinit var radioXYZ: RadioButton
    private lateinit var radioXZ: RadioButton

    // state
    private var state: State = State.IDLE
    private var failReason: FailReason? = null
    private var axisMode: AxisMode = AxisMode.XYZ

    // anchors
    private var startAnchor: Anchor? = null
    private var endAnchor: Anchor? = null

    // sampling / stabilization
    private var lastHitTestNs: Long = 0L
    private var lastBestTrackable: Any? = null
    private var lastBestHit: com.google.ar.core.HitResult? = null

    private var consecutiveNoValidHits: Int = 0
    private var stabilizingEnterNs: Long = 0L
    private var pausedStartNs: Long = 0L

    private var fixedDEstMeters: Float = 0f
    private var fixedGrid: Int = 9
    private var fixedMinSamples: Int = 10

    private val buffer = ArrayList<Vec3>(40)

    // latest display stats (for UI)
    private var latestTracking: TrackingState = TrackingState.PAUSED
    private var latestValidHits: Int = 0
    private var latestTotalHits: Int = 9
    private var latestGrid: Int = 9
    private var latestHitType: HitType = HitType.NONE
    private var latestSpanEst: Float = 0f
    private var latestSigmaXYZ: Float = Float.NaN
    private var latestSigmaXZ: Float = Float.NaN
    private var latestSigmaMax: Float = Float.NaN

    private val mainHandler = Handler(Looper.getMainLooper())

    data class Vec3(val x: Float, val y: Float, val z: Float)

    private data class Candidate(
        val hit: com.google.ar.core.HitResult,
        val distToCenter: Float,
        val trackableRef: Any?
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_p2p_measurement)

        bindUi()
        setupTransparentGlSurface()

        state = State.AIM_START
        renderUi()

        if (hasCameraPermission()) {
            initializeArCore()
        } else {
            requestCameraPermission()
        }
    }

    private fun bindUi() {
        txtState = findViewById(R.id.txt_state)
        txtTracking = findViewById(R.id.txt_tracking)
        txtSpan = findViewById(R.id.txt_span)
        txtSigma = findViewById(R.id.txt_sigma)
        txtHits = findViewById(R.id.txt_hits)
        txtGrid = findViewById(R.id.txt_grid)
        txtHitType = findViewById(R.id.txt_hit_type)
        txtResult = findViewById(R.id.txt_result)
        btnSetStart = findViewById(R.id.btn_set_start)
        btnSetEnd = findViewById(R.id.btn_set_end)
        btnReset = findViewById(R.id.btn_reset)
        radioXYZ = findViewById(R.id.radio_mode_xyz)
        radioXZ = findViewById(R.id.radio_mode_xz)

        btnSetStart.setOnClickListener { onSetStartClicked() }
        btnSetEnd.setOnClickListener { onSetEndClicked() }
        btnReset.setOnClickListener { resetAll() }

        radioXYZ.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                axisMode = AxisMode.XYZ
                radioXYZ.setTextColor(Color.parseColor("#4CAF50"))
                radioXZ.setTextColor(Color.parseColor("#9E9E9E"))
                renderUi()
            }
        }
        radioXZ.setOnCheckedChangeListener { _, checked ->
            if (checked) {
                axisMode = AxisMode.XZ
                radioXYZ.setTextColor(Color.parseColor("#9E9E9E"))
                radioXZ.setTextColor(Color.parseColor("#4CAF50"))
                renderUi()
            }
        }

        btnSetStart.isEnabled = false
        btnSetEnd.isEnabled = false
    }

    private fun setupTransparentGlSurface() {
        glView =
            GLSurfaceView(this).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(this@P2PMeasurementActivity)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                // Fullscreen but invisible: required for correct hitTest coordinates without preview.
                layoutParams =
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                        android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                    )
                alpha = 0f
                visibility = View.VISIBLE
            }
        findViewById<android.view.ViewGroup>(android.R.id.content).addView(glView, 0)
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
        if (requestCode == 1001 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            initializeArCore()
        }
    }

    private fun initializeArCore() {
        if (session != null) return
        try {
            val installStatus = ArCoreApk.getInstance().requestInstall(this, !installRequested)
            if (installStatus == ArCoreApk.InstallStatus.INSTALL_REQUESTED) {
                installRequested = true
                return
            }
            session = Session(this)
        } catch (e: Exception) {
            Log.e(TAG, "ARCore init failed", e)
            Toast.makeText(this, getString(R.string.status_arcore_init_failed), Toast.LENGTH_LONG).show()
            state = State.FAIL
            failReason = FailReason.TRACKING_LOST
            renderUi()
            return
        }
    }

    private fun configureSession() {
        val s = session ?: return
        val config = s.config ?: Config(s)
        config.focusMode = Config.FocusMode.AUTO
        config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE

        // v3 policy: plane ON. Depth optional (used only as fallback hit type).
        config.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
        if (s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
            config.depthMode = Config.DepthMode.AUTOMATIC
        } else {
            config.depthMode = Config.DepthMode.DISABLED
        }
        // Instant placement OFF by default (do not use for confirmation).
        config.instantPlacementMode = Config.InstantPlacementMode.DISABLED

        s.configure(config)
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        try {
            backgroundRenderer.createOnGlThread(this)
        } catch (e: Exception) {
            Log.e(TAG, "BackgroundRenderer init failed", e)
        }
        val s = session ?: return
        try {
            configureSession()
            s.resume()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "Camera not available", e)
        } catch (e: Exception) {
            Log.e(TAG, "Session resume/config failed", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val s = session ?: return

        try {
            // SSOT update
            val frame = s.update()
            latestTracking = frame.camera.trackingState

            // STOPPED -> immediate FAIL (v3)
            if (latestTracking == TrackingState.STOPPED) {
                if (state != State.FAIL) {
                    state = State.FAIL
                    failReason = FailReason.TRACKING_LOST
                    postRenderUi()
                }
                return
            }

            // Basic display geometry sync
            val dm = resources.displayMetrics
            val rotation = windowManager.defaultDisplay.rotation
            val displayRotation =
                when (rotation) {
                    android.view.Surface.ROTATION_0 -> 0
                    android.view.Surface.ROTATION_90 -> 90
                    android.view.Surface.ROTATION_180 -> 180
                    android.view.Surface.ROTATION_270 -> 270
                    else -> 0
                }
            s.setDisplayGeometry(displayRotation, dm.widthPixels, dm.heightPixels)

            // Note: no preview shown (GL view is transparent), but we keep renderer calls consistent.
            try {
                s.setCameraTextureName(backgroundRenderer.getTextureId())
                backgroundRenderer.draw(frame)
            } catch (_: Exception) {
                // ignore preview errors for phase1
            }

            val nowNs = System.nanoTime()
            val shouldSample = (nowNs - lastHitTestNs) >= HIT_THROTTLE_NS

            if (shouldSample) {
                lastHitTestNs = nowNs
                onSampleTick(frame, nowNs)
            }
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun onSampleTick(frame: Frame, nowNs: Long) {
        val samplingState =
            when (state) {
                State.AIM_START, State.STABILIZING_START, State.AIM_END, State.STABILIZING_END -> true
                else -> false
            }
        if (!samplingState) {
            postRenderUi()
            return
        }

        // PAUSED handling: do not collect, but do not immediate fail.
        if (latestTracking == TrackingState.PAUSED) {
            if (state == State.STABILIZING_START || state == State.STABILIZING_END) {
                if (pausedStartNs == 0L) pausedStartNs = nowNs
                // If PAUSED persists >= 1s, treat as timeout (v3)
                if ((nowNs - pausedStartNs) >= PAUSED_GRACE_NS) {
                    state = State.FAIL
                    failReason = FailReason.TIMEOUT
                }
            }
            postRenderUi()
            return
        } else {
            pausedStartNs = 0L
        }

        val inStabilizing = (state == State.STABILIZING_START || state == State.STABILIZING_END)

        // Grid rule (v3):
        // - AIM_* always uses Grid 9 (or 1pt, but we stick to 9 for simplicity).
        // - Stabilizing uses fixed grid chosen at entry (49 allowed only in stabilizing).
        val grid =
            if (inStabilizing) {
                fixedGrid
            } else {
                9
            }

        val sample = sampleBestHit(frame, grid)
        latestGrid = grid
        latestTotalHits = grid
        latestValidHits = sample.validHits
        latestHitType = sample.hitType
        lastBestHit = sample.bestHit

        val spanEst = computeSpanEstMeters(sample.bestHit)
        latestSpanEst = spanEst

        if (!inStabilizing) {
            // Enable buttons based on minValidHits in AIM states
            val canLock = sample.validHits >= minValidHitsForGrid(grid)
            if (state == State.AIM_START) {
                btnSetStartEnabled(canLock)
                btnSetEndEnabled(false)
            } else if (state == State.AIM_END) {
                btnSetStartEnabled(false)
                btnSetEndEnabled(canLock)
            }
            postRenderUi()
            return
        }

        // Stabilizing: enforce minValidHits and failure M=10
        if (sample.validHits < minValidHitsForGrid(grid) || sample.bestHit == null) {
            consecutiveNoValidHits++
            if (consecutiveNoValidHits >= FAIL_NO_VALID_HITS_M) {
                state = State.FAIL
                failReason = FailReason.NO_VALID_HITS
            }
            postRenderUi()
            return
        }

        consecutiveNoValidHits = 0

        // Timeout (FAIL only)
        if ((nowNs - stabilizingEnterNs) >= STABILIZING_TIMEOUT_NS) {
            state = State.FAIL
            failReason = FailReason.TIMEOUT
            postRenderUi()
            return
        }

        // Collect translation sample
        val p = sample.bestHit.hitPose
        buffer.add(Vec3(p.tx(), p.ty(), p.tz()))

        if (buffer.size >= fixedMinSamples) {
            val stats = PoseStats.compute(buffer, axisMode)
            latestSigmaXYZ = stats.sigmaXYZ
            latestSigmaXZ = stats.sigmaXZ
            latestSigmaMax = sigmaMax(fixedDEstMeters)

            val sigmaUsed =
                when (axisMode) {
                    AxisMode.XYZ -> latestSigmaXYZ
                    AxisMode.XZ -> latestSigmaXZ
                }

            if (sigmaUsed.isFinite() && sigmaUsed <= latestSigmaMax) {
                // Confirm: create anchor only after stabilization passes
                confirmWithHit(sample.bestHit)
            }
        }

        postRenderUi()
    }

    private fun btnSetStartEnabled(enabled: Boolean) {
        mainHandler.post { btnSetStart.isEnabled = enabled }
    }

    private fun btnSetEndEnabled(enabled: Boolean) {
        mainHandler.post { btnSetEnd.isEnabled = enabled }
    }

    private fun postRenderUi() {
        mainHandler.post { renderUi() }
    }

    private fun renderUi() {
        txtTracking.text = getString(R.string.p2p_tracking_format, latestTracking.name)
        txtState.text = getString(R.string.p2p_state_format, state.name)

        if (latestSpanEst > 0f) {
            txtSpan.text = getString(R.string.p2p_span_format, latestSpanEst)
        } else {
            txtSpan.text = getString(R.string.p2p_span_placeholder)
        }

        val sigmaMaxValue = if (latestSigmaMax.isFinite()) latestSigmaMax else sigmaMax(fixedDEstMeters)
        val sigmaUsed =
            when (axisMode) {
                AxisMode.XYZ -> latestSigmaXYZ
                AxisMode.XZ -> latestSigmaXZ
            }
        txtSigma.text =
            if (sigmaUsed.isFinite()) {
                getString(R.string.p2p_sigma_format, sigmaUsed, sigmaMaxValue)
            } else {
                getString(R.string.p2p_sigma_placeholder)
            }

        txtHits.text = getString(R.string.p2p_hits_format, latestValidHits, latestTotalHits)
        txtGrid.text = getString(R.string.p2p_grid_format, latestGrid)
        txtHitType.text =
            when (latestHitType) {
                HitType.PLANE -> getString(R.string.p2p_hit_plane)
                HitType.DEPTH_OR_POINT -> getString(R.string.p2p_hit_depth_point)
                HitType.NONE -> getString(R.string.p2p_hit_none)
            }

        when (state) {
            State.AIM_START -> {
                btnSetStart.isEnabled = latestValidHits >= minValidHitsForGrid(9)
                btnSetEnd.isEnabled = false
            }
            State.AIM_END -> {
                btnSetStart.isEnabled = false
                btnSetEnd.isEnabled = latestValidHits >= minValidHitsForGrid(9)
            }
            State.STABILIZING_START, State.STABILIZING_END -> {
                btnSetStart.isEnabled = false
                btnSetEnd.isEnabled = false
            }
            State.END_LOCKED -> {
                btnSetStart.isEnabled = false
                btnSetEnd.isEnabled = false
            }
            State.FAIL -> {
                btnSetStart.isEnabled = false
                btnSetEnd.isEnabled = false
                val msg =
                    when (failReason) {
                        FailReason.NO_VALID_HITS -> getString(R.string.p2p_fail_no_valid_hits)
                        FailReason.TRACKING_LOST -> getString(R.string.p2p_fail_tracking_lost)
                        FailReason.TIMEOUT -> getString(R.string.p2p_fail_timeout)
                        else -> getString(R.string.p2p_fail_timeout)
                    }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
            else -> {
                // no-op
            }
        }
    }

    private fun onSetStartClicked() {
        if (state != State.AIM_START) return
        val hit = lastBestHit ?: return
        val valid = latestValidHits >= minValidHitsForGrid(9)
        if (!valid) return

        // enter stabilizing_start
        state = State.STABILIZING_START
        failReason = null
        consecutiveNoValidHits = 0
        buffer.clear()
        stabilizingEnterNs = System.nanoTime()

        fixedDEstMeters = computeSpanEstMeters(hit)
        fixedGrid = chooseGridForDEst(fixedDEstMeters, allow49 = true)
        fixedMinSamples = chooseMinSamplesForDEst(fixedDEstMeters)
        latestSigmaXYZ = Float.NaN
        latestSigmaXZ = Float.NaN
        latestSigmaMax = sigmaMax(fixedDEstMeters)

        postRenderUi()
    }

    private fun onSetEndClicked() {
        if (state != State.AIM_END) return
        val hit = lastBestHit ?: return
        val valid = latestValidHits >= minValidHitsForGrid(9)
        if (!valid) return

        state = State.STABILIZING_END
        failReason = null
        consecutiveNoValidHits = 0
        buffer.clear()
        stabilizingEnterNs = System.nanoTime()

        // v3: End uses startAnchor ↔ candidateHit for d_est, fixed at entry
        fixedDEstMeters = computeSpanEstMeters(hit)
        fixedGrid = chooseGridForDEst(fixedDEstMeters, allow49 = true)
        fixedMinSamples = chooseMinSamplesForDEst(fixedDEstMeters)
        latestSigmaXYZ = Float.NaN
        latestSigmaXZ = Float.NaN
        latestSigmaMax = sigmaMax(fixedDEstMeters)

        postRenderUi()
    }

    private fun confirmWithHit(hit: com.google.ar.core.HitResult) {
        try {
            val anchor = hit.createAnchor()
            when (state) {
                State.STABILIZING_START -> {
                    startAnchor?.detach()
                    startAnchor = anchor
                    state = State.AIM_END
                }
                State.STABILIZING_END -> {
                    endAnchor?.detach()
                    endAnchor = anchor
                    state = State.END_LOCKED
                    val dist = computeAnchorDistanceMeters()
                    mainHandler.post { txtResult.text = getString(R.string.p2p_distance_format, dist) }
                }
                else -> {
                    anchor.detach()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Anchor create failed", e)
            state = State.FAIL
            failReason = FailReason.TIMEOUT
        }
    }

    private fun computeAnchorDistanceMeters(): Float {
        val a = startAnchor?.pose ?: return 0f
        val b = endAnchor?.pose ?: return 0f
        val dx = b.tx() - a.tx()
        val dy = b.ty() - a.ty()
        val dz = b.tz() - a.tz()
        return when (axisMode) {
            AxisMode.XYZ -> sqrt(dx * dx + dy * dy + dz * dz)
            AxisMode.XZ -> sqrt(dx * dx + dz * dz)
        }
    }

    private fun computeSpanEstMeters(hit: com.google.ar.core.HitResult?): Float {
        if (hit == null) return 0f
        return if (state == State.STABILIZING_END || state == State.AIM_END || state == State.END_LOCKED) {
            val start = startAnchor?.pose
            if (start != null) {
                val p = hit.hitPose
                val dx = p.tx() - start.tx()
                val dy = p.ty() - start.ty()
                val dz = p.tz() - start.tz()
                sqrt(dx * dx + dy * dy + dz * dz)
            } else {
                hit.distance
            }
        } else {
            hit.distance
        }
    }

    private fun resetAll() {
        startAnchor?.detach()
        endAnchor?.detach()
        startAnchor = null
        endAnchor = null
        buffer.clear()
        consecutiveNoValidHits = 0
        stabilizingEnterNs = 0L
        pausedStartNs = 0L
        fixedDEstMeters = 0f
        fixedGrid = 9
        fixedMinSamples = 10
        latestSpanEst = 0f
        latestSigmaXYZ = Float.NaN
        latestSigmaXZ = Float.NaN
        latestSigmaMax = Float.NaN
        latestValidHits = 0
        latestTotalHits = 9
        latestGrid = 9
        latestHitType = HitType.NONE
        state = State.AIM_START
        failReason = null
        mainHandler.post {
            txtResult.text = getString(R.string.p2p_distance_placeholder)
        }
        renderUi()
    }

    private fun chooseGridForDEst(d: Float, allow49: Boolean): Int {
        return when {
            d < 1.0f -> 9
            d < 3.0f -> 25
            else -> if (allow49) 49 else 25
        }
    }

    private fun chooseMinSamplesForDEst(d: Float): Int {
        val n = (10f + 3f * d).toInt()
        return n.coerceIn(10, 30)
    }

    private fun minValidHitsForGrid(grid: Int): Int {
        return when (grid) {
            9 -> 3
            25 -> 7
            49 -> 12
            else -> 3
        }
    }

    private data class Sample(
        val bestHit: com.google.ar.core.HitResult?,
        val hitType: HitType,
        val validHits: Int
    )

    private fun sampleBestHit(frame: Frame, gridCount: Int): Sample {
        val dm = resources.displayMetrics
        val w = dm.widthPixels.toFloat()
        val h = dm.heightPixels.toFloat()

        val centerX = w * 0.5f
        val centerY = h * 0.5f
        val roiHalfW = w * 0.12f
        val roiHalfH = h * 0.10f

        val gridSize = when (gridCount) {
            9 -> 3
            25 -> 5
            49 -> 7
            else -> 3
        }

        val planeCandidates = ArrayList<Candidate>()
        val depthOrPointCandidates = ArrayList<Candidate>()
        var planeValidPoints = 0
        var depthValidPoints = 0

        fun pointHasPlaneHit(results: List<com.google.ar.core.HitResult>): com.google.ar.core.HitResult? {
            for (r in results) {
                val t = r.trackable
                if (t is Plane && t.isPoseInPolygon(r.hitPose)) {
                    return r
                }
            }
            return null
        }

        fun pointHasDepthOrPointHit(results: List<com.google.ar.core.HitResult>): com.google.ar.core.HitResult? {
            for (r in results) {
                val t = r.trackable
                if (t is InstantPlacementPoint) continue
                if (t is Plane) continue
                if (t is Point) return r
                // DepthPoint is not available on all versions; treat unknown non-plane as fallback
                return r
            }
            return null
        }

        for (iy in 0 until gridSize) {
            val ty = if (gridSize == 1) 0.5f else iy.toFloat() / (gridSize - 1).toFloat()
            val y = (centerY - roiHalfH) + (ty * (roiHalfH * 2f))
            for (ix in 0 until gridSize) {
                val tx = if (gridSize == 1) 0.5f else ix.toFloat() / (gridSize - 1).toFloat()
                val x = (centerX - roiHalfW) + (tx * (roiHalfW * 2f))

                val results = frame.hitTest(x, y)
                val distToCenter = abs(x - centerX) + abs(y - centerY)

                val planeHit = pointHasPlaneHit(results)
                if (planeHit != null) {
                    planeValidPoints++
                    planeCandidates.add(Candidate(planeHit, distToCenter, planeHit.trackable))
                    continue
                }

                val fallbackHit = pointHasDepthOrPointHit(results)
                if (fallbackHit != null) {
                    depthValidPoints++
                    depthOrPointCandidates.add(Candidate(fallbackHit, distToCenter, fallbackHit.trackable))
                }
            }
        }

        val minValid = minValidHitsForGrid(gridCount)
        return if (planeValidPoints >= minValid) {
            val best = pickBestCandidate(planeCandidates)
            Sample(best, HitType.PLANE, planeValidPoints)
        } else if (depthValidPoints >= minValid) {
            val best = pickBestCandidate(depthOrPointCandidates)
            Sample(best, HitType.DEPTH_OR_POINT, depthValidPoints)
        } else {
            Sample(null, HitType.NONE, max(planeValidPoints, depthValidPoints))
        }
    }

    private fun pickBestCandidate(candidates: List<Candidate>): com.google.ar.core.HitResult? {
        if (candidates.isEmpty()) return null
        var best: Candidate? = null
        for (c in candidates) {
            if (best == null) {
                best = c
                continue
            }
            val bd = best!!.distToCenter
            val cd = c.distToCenter
            if (cd < bd - 0.0001f) {
                best = c
            } else if (abs(cd - bd) <= 0.0001f) {
                // tie-breaker: closer hit.distance
                if (c.hit.distance < best!!.hit.distance - 0.0001f) {
                    best = c
                } else if (abs(c.hit.distance - best!!.hit.distance) <= 0.0001f) {
                    // optional: prevent type jump within same type (prefer same trackable as last)
                    if (lastBestTrackable != null && c.trackableRef === lastBestTrackable) {
                        best = c
                    }
                }
            }
        }
        val chosen = best!!.hit
        lastBestTrackable = (best!!.trackableRef ?: chosen.trackable)
        return chosen
    }

    private fun sigmaMax(dMeters: Float): Float {
        val raw = A + (B * dMeters)
        return raw.coerceIn(SIGMA_MIN, SIGMA_CAP)
    }

    private fun formatMeters(v: Float): String {
        if (!v.isFinite()) return "--"
        return String.format(java.util.Locale.US, "%.3f", v)
    }

    override fun onResume() {
        super.onResume()
        glView?.onResume()
        try {
            if (session != null) {
                configureSession()
                session?.resume()
            }
        } catch (e: Exception) {
            Log.e(TAG, "onResume session resume failed", e)
        }
    }

    override fun onPause() {
        super.onPause()
        glView?.onPause()
        session?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        startAnchor?.detach()
        endAnchor?.detach()
        session?.close()
    }

    private object PoseStats {
        data class Stats(val sigmaXYZ: Float, val sigmaXZ: Float)

        // MAD-based outlier removal + sigma on remaining points
        fun compute(points: List<Vec3>, axisMode: AxisMode): Stats {
            if (points.size < 3) return Stats(Float.NaN, Float.NaN)

            val filtered = removeOutliersMad(points)
            if (filtered.size < 3) return Stats(Float.NaN, Float.NaN)

            val xs = filtered.map { it.x }
            val ys = filtered.map { it.y }
            val zs = filtered.map { it.z }

            val sx = stddev(xs)
            val sy = stddev(ys)
            val sz = stddev(zs)

            val sigmaXYZ = sqrt(sx * sx + sy * sy + sz * sz)
            val sigmaXZ = sqrt(sx * sx + sz * sz)
            return Stats(sigmaXYZ, sigmaXZ)
        }

        private fun removeOutliersMad(points: List<Vec3>): List<Vec3> {
            val mx = median(points.map { it.x })
            val my = median(points.map { it.y })
            val mz = median(points.map { it.z })

            val dxs = points.map { abs(it.x - mx) }
            val dys = points.map { abs(it.y - my) }
            val dzs = points.map { abs(it.z - mz) }

            val madX = median(dxs)
            val madY = median(dys)
            val madZ = median(dzs)

            val k = 3.5f
            fun ok(dev: Float, mad: Float): Boolean {
                if (mad == 0f) return dev == 0f
                return (dev / mad) <= k
            }

            return points.filter { p ->
                ok(abs(p.x - mx), madX) && ok(abs(p.y - my), madY) && ok(abs(p.z - mz), madZ)
            }
        }

        private fun median(values: List<Float>): Float {
            if (values.isEmpty()) return 0f
            val s = values.sorted()
            val n = s.size
            val mid = n / 2
            return if (n % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
        }

        private fun stddev(values: List<Float>): Float {
            val n = values.size
            if (n < 2) return 0f
            val mean = values.sum() / n.toFloat()
            var acc = 0f
            for (v in values) {
                val d = v - mean
                acc += d * d
            }
            return sqrt(acc / (n - 1).toFloat())
        }
    }
}

