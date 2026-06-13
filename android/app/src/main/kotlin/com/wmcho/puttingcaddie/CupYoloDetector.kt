package com.wmcho.puttingcaddie

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Log
import java.nio.FloatBuffer

data class CupDetection(val rect: RectF, val score: Float)

class CupYoloDetector(context: Context) {
    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession
    private val modelFile = "yolo/cup_1shot_best.onnx"
    private val inputWidth = 640
    private val inputHeight = 640

    init {
        val modelBytes = context.assets.open(modelFile).readBytes()
        session = env.createSession(modelBytes, OrtSession.SessionOptions())
        Log.i("CupYoloDetector", "YOLO model loaded: $modelFile")
    }

    fun detectCup(
        bitmap: Bitmap,
        confThreshold: Float = 0.55f,
        iouThreshold: Float = 0.45f
    ): List<CupDetection> {
        return runCatching {
            val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)
            val inputBuffer = preprocess(resized)
            val inputName = session.inputNames.iterator().next()
            val shape = longArrayOf(1, 3, inputHeight.toLong(), inputWidth.toLong())
            val inputTensor = OnnxTensor.createTensor(env, inputBuffer, shape)
            val outputs = session.run(mapOf(inputName to inputTensor))
            val out = outputs[0].value
            val parsed = mutableListOf<CupDetection>()

            when (out) {
                is Array<*> -> parseArrayOutput(out, bitmap.width, bitmap.height, confThreshold, parsed)
                is FloatArray -> parseFloatArrayOutput(out, bitmap.width, bitmap.height, confThreshold, parsed)
            }
            inputTensor.close()
            outputs.close()
            nms(parsed, iouThreshold)
        }.getOrElse {
            Log.w("CupYoloDetector", "detectCup failed: ${it.message}")
            emptyList()
        }
    }

    private fun preprocess(bitmap: Bitmap): FloatBuffer {
        val buffer = FloatBuffer.allocate(1 * 3 * inputWidth * inputHeight)
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (c in 0 until 3) {
            for (y in 0 until inputHeight) {
                for (x in 0 until inputWidth) {
                    val px = pixels[y * inputWidth + x]
                    val value =
                        when (c) {
                            0 -> ((px shr 16) and 0xFF) / 255f
                            1 -> ((px shr 8) and 0xFF) / 255f
                            else -> (px and 0xFF) / 255f
                        }
                    buffer.put(value)
                }
            }
        }
        buffer.rewind()
        return buffer
    }

    private fun parseArrayOutput(
        output: Array<*>,
        origW: Int,
        origH: Int,
        confThreshold: Float,
        detections: MutableList<CupDetection>
    ) {
        val first = output.firstOrNull()
        when (first) {
            is Array<*> -> {
                for (item in first) {
                    if (item is FloatArray && item.size >= 6) parseBox(item, origW, origH, confThreshold, detections)
                }
            }
            is FloatArray -> {
                for (item in output) {
                    if (item is FloatArray && item.size >= 6) parseBox(item, origW, origH, confThreshold, detections)
                }
            }
        }
    }

    private fun parseFloatArrayOutput(
        output: FloatArray,
        origW: Int,
        origH: Int,
        confThreshold: Float,
        detections: MutableList<CupDetection>
    ) {
        val boxSize = 6
        for (i in output.indices step boxSize) {
            if (i + boxSize > output.size) break
            parseBox(floatArrayOf(output[i], output[i + 1], output[i + 2], output[i + 3], output[i + 4], output[i + 5]), origW, origH, confThreshold, detections)
        }
    }

    private fun parseBox(
        box: FloatArray,
        origW: Int,
        origH: Int,
        confThreshold: Float,
        detections: MutableList<CupDetection>
    ) {
        val x1 = box[0]
        val y1 = box[1]
        val x2 = box[2]
        val y2 = box[3]
        val raw = box[4]
        val score = sigmoid(raw)
        if (score < confThreshold) return

        val scaleX = origW.toFloat() / inputWidth
        val scaleY = origH.toFloat() / inputHeight
        val left = (x1 * scaleX).coerceIn(0f, origW.toFloat())
        val top = (y1 * scaleY).coerceIn(0f, origH.toFloat())
        val right = (x2 * scaleX).coerceIn(0f, origW.toFloat())
        val bottom = (y2 * scaleY).coerceIn(0f, origH.toFloat())
        val rect = RectF(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))
        if (rect.width() < 6f || rect.height() < 6f) return
        detections.add(CupDetection(rect, score))
    }

    private fun nms(detections: List<CupDetection>, iouThreshold: Float): List<CupDetection> {
        if (detections.isEmpty()) return emptyList()
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<CupDetection>()
        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            keep.add(best)
            val it = sorted.iterator()
            while (it.hasNext()) {
                if (iou(best.rect, it.next().rect) > iouThreshold) it.remove()
            }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val inter = maxOf(0f, right - left) * maxOf(0f, bottom - top)
        if (inter <= 0f) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - inter
        return if (union <= 0f) 0f else inter / union
    }

    private fun sigmoid(x: Float): Float = when {
        x > 10f -> 1f
        x < -10f -> 0f
        else -> 1f / (1f + kotlin.math.exp(-x))
    }

    fun close() {
        runCatching { session.close() }
    }
}

