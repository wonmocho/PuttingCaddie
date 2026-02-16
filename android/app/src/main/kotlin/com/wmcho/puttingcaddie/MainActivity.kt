package com.wmcho.puttingcaddie

import android.content.Intent
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "com.wmcho.puttingcaddie/native"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // DistanceMeasurementActivity (JustDistance UI) is the main UX.
        startActivity(Intent(this, DistanceMeasurementActivity::class.java))
        finish()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startDistanceMeasurement" -> {
                    startActivity(Intent(this, DistanceMeasurementActivity::class.java))
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}

