package com.wmcho.puttingcaddie

import java.util.Locale

/**
 * 필드/warmup/거리 안전 진단 — Logcat + 이메일 피드백 JSON 공용 링 버퍼.
 * 거리 계산 파이프라인과 분리된 관측 전용.
 */
object FieldDiagnosticLog {

    private const val MAX_ENTRIES = 150

    data class Entry(
        val tsMs: Long,
        val tag: String,
        val message: String
    )

    data class DistSafetySnapshot(
        val tsMs: Long,
        val warmupReady: Boolean,
        val warmupSuccessCount: Int,
        val startHitType: String?,
        val cupLiveSource: String?,
        val groundPlaneValid: Boolean,
        val endLiveM: Float?,
        val anchorM: Float?,
        val finalM: Float?
    )

    data class WarmupGateSnapshot(
        val tsMs: Long,
        val tracking: String,
        val failureReason: String,
        val outdoorRelaxed: Boolean,
        val planeHit: Boolean,
        val successCount: Int,
        val required: Int,
        val arWarmupReady: Boolean
    )

    private val entries = ArrayDeque<Entry>(MAX_ENTRIES + 8)
    private val lock = Any()

    @Volatile
    var lastDistSafety: DistSafetySnapshot? = null
        private set

    @Volatile
    var lastWarmupGate: WarmupGateSnapshot? = null
        private set

    @Volatile
    var lastWarmupReadyAtMs: Long? = null
        private set

    @Volatile
    var lastBallBlockedReason: String? = null
        private set

    fun record(tag: String, message: String) {
        val e = Entry(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            entries.addLast(e)
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun recordBallBlockedReason(reason: String?) {
        if (!reason.isNullOrBlank()) lastBallBlockedReason = reason
    }

    fun recordWarmup(
        tracking: String,
        failureReason: String,
        outdoorRelaxed: Boolean,
        planeHit: Boolean,
        successCount: Int,
        required: Int,
        arWarmupReady: Boolean
    ) {
        val snap =
            WarmupGateSnapshot(
                tsMs = System.currentTimeMillis(),
                tracking = tracking,
                failureReason = failureReason,
                outdoorRelaxed = outdoorRelaxed,
                planeHit = planeHit,
                successCount = successCount,
                required = required,
                arWarmupReady = arWarmupReady
            )
        lastWarmupGate = snap
        if (arWarmupReady && lastWarmupReadyAtMs == null) {
            lastWarmupReadyAtMs = snap.tsMs
            record("WARMUP", "READY successCount=$successCount/$required outdoorRelaxed=$outdoorRelaxed")
        }
        record(
            "WARMUP",
            "tracking=$tracking reason=$failureReason outdoorRelaxed=$outdoorRelaxed " +
                "planeHit=$planeHit successCount=$successCount/$required arWarmupReady=$arWarmupReady"
        )
    }

    fun recordDistSafety(
        warmupReady: Boolean,
        warmupSuccessCount: Int,
        startHitType: String?,
        cupLiveSource: String?,
        groundPlaneValid: Boolean,
        endLiveM: Float?,
        anchorM: Float?,
        finalM: Float?
    ) {
        val snap =
            DistSafetySnapshot(
                tsMs = System.currentTimeMillis(),
                warmupReady = warmupReady,
                warmupSuccessCount = warmupSuccessCount,
                startHitType = startHitType,
                cupLiveSource = cupLiveSource,
                groundPlaneValid = groundPlaneValid,
                endLiveM = endLiveM,
                anchorM = anchorM,
                finalM = finalM
            )
        lastDistSafety = snap
        record(
            "DIST_SAFETY",
            "warmupReady=$warmupReady warmupSuccessCount=$warmupSuccessCount " +
                "startHitType=${startHitType ?: "null"} cupLiveSource=${cupLiveSource ?: "null"} " +
                "groundPlaneValid=$groundPlaneValid " +
                "endLive=${fmt(endLiveM)} anchor=${fmt(anchorM)} final=${fmt(finalM)}"
        )
    }

    fun recordTrackingDiag(tracking: String, failureReason: String) {
        record("TRACKING_DIAG", "state=$tracking failureReason=$failureReason")
    }

    fun recordBallEnableGate(line: String) {
        record("BALL_ENABLE_GATE", line)
    }

    fun resetSession() {
        synchronized(lock) {
            entries.clear()
        }
        lastDistSafety = null
        lastWarmupGate = null
        lastWarmupReadyAtMs = null
        lastBallBlockedReason = null
    }

    fun snapshotEntries(): List<Entry> =
        synchronized(lock) {
            entries.toList()
        }

    fun appendEmailSummary(sb: StringBuilder) {
        sb.append("\n--- Field diagnostics (warmup / distance safety) ---\n")
        lastWarmupGate?.let { w ->
            sb.append("- Warmup(last): tracking=").append(w.tracking)
                .append(" reason=").append(w.failureReason)
                .append(" planeHit=").append(w.planeHit)
                .append(" count=").append(w.successCount).append('/').append(w.required)
                .append(" outdoor=").append(w.outdoorRelaxed)
                .append(" ready=").append(w.arWarmupReady).append('\n')
        }
        lastWarmupReadyAtMs?.let { sb.append("- WarmupReadyAt(ms): ").append(it).append('\n') }
        lastBallBlockedReason?.let { sb.append("- LastBallBlocked: ").append(it).append('\n') }
        lastDistSafety?.let { d ->
            sb.append("- DistSafety: startHit=").append(d.startHitType ?: "null")
                .append(" cupLive=").append(d.cupLiveSource ?: "null")
                .append(" groundPlane=").append(d.groundPlaneValid)
                .append(" endLive=").append(fmt(d.endLiveM))
                .append(" anchor=").append(fmt(d.anchorM))
                .append(" final=").append(fmt(d.finalM)).append('\n')
        }
        sb.append("- DiagnosticLogLines: ").append(snapshotEntries().size).append('\n')
    }

    fun appendJson(sb: StringBuilder, esc: (String) -> String) {
        fun fmtF(v: Float?): String =
            if (v == null || !v.isFinite()) "null" else String.format(Locale.US, "%.6f", v)

        sb.append("  \"fieldDiagnostics\": {\n")
        sb.append("    \"outdoorWarmupRelaxed\": ").append(ProductFlags.OUTDOOR_WARMUP_RELAXED).append(",\n")
        sb.append("    \"lastBallBlockedReason\": ")
        sb.append(if (lastBallBlockedReason == null) "null" else "\"${esc(lastBallBlockedReason!!)}\"")
        sb.append(",\n")
        sb.append("    \"lastWarmupReadyAtMs\": ").append(lastWarmupReadyAtMs ?: "null").append(",\n")

        val w = lastWarmupGate
        sb.append("    \"lastWarmupGate\": ")
        if (w == null) {
            sb.append("null")
        } else {
            sb.append("{\n")
            sb.append("      \"tsMs\": ").append(w.tsMs).append(",\n")
            sb.append("      \"tracking\": \"").append(esc(w.tracking)).append("\",\n")
            sb.append("      \"failureReason\": \"").append(esc(w.failureReason)).append("\",\n")
            sb.append("      \"outdoorRelaxed\": ").append(w.outdoorRelaxed).append(",\n")
            sb.append("      \"planeHit\": ").append(w.planeHit).append(",\n")
            sb.append("      \"successCount\": ").append(w.successCount).append(",\n")
            sb.append("      \"required\": ").append(w.required).append(",\n")
            sb.append("      \"arWarmupReady\": ").append(w.arWarmupReady).append("\n")
            sb.append("    }")
        }
        sb.append(",\n")

        val d = lastDistSafety
        sb.append("    \"lastDistSafety\": ")
        if (d == null) {
            sb.append("null")
        } else {
            sb.append("{\n")
            sb.append("      \"tsMs\": ").append(d.tsMs).append(",\n")
            sb.append("      \"warmupReady\": ").append(d.warmupReady).append(",\n")
            sb.append("      \"warmupSuccessCount\": ").append(d.warmupSuccessCount).append(",\n")
            sb.append("      \"startHitType\": ")
            sb.append(if (d.startHitType == null) "null" else "\"${esc(d.startHitType)}\"")
            sb.append(",\n")
            sb.append("      \"cupLiveSource\": ")
            sb.append(if (d.cupLiveSource == null) "null" else "\"${esc(d.cupLiveSource)}\"")
            sb.append(",\n")
            sb.append("      \"groundPlaneValid\": ").append(d.groundPlaneValid).append(",\n")
            sb.append("      \"endLiveM\": ").append(fmtF(d.endLiveM)).append(",\n")
            sb.append("      \"anchorM\": ").append(fmtF(d.anchorM)).append(",\n")
            sb.append("      \"finalM\": ").append(fmtF(d.finalM)).append("\n")
            sb.append("    }")
        }
        sb.append(",\n")

        val list = snapshotEntries()
        sb.append("    \"recentLines\": [\n")
        list.forEachIndexed { idx, e ->
            sb.append("      {\"tsMs\":").append(e.tsMs)
                .append(",\"tag\":\"").append(esc(e.tag)).append("\"")
                .append(",\"message\":\"").append(esc(e.message)).append("\"}")
            if (idx != list.size - 1) sb.append(',')
            sb.append('\n')
        }
        sb.append("    ]\n")
        sb.append("  }")
    }

    private fun fmt(v: Float?): String =
        if (v == null || !v.isFinite()) "null" else String.format(Locale.US, "%.3f", v)
}
