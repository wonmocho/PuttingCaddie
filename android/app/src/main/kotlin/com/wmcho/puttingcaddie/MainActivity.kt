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

        startActivity(Intent(this, DistanceMeasurementActivity::class.java))
        finish()
    }

    private fun launchDistanceMeasurement() {
        startActivity(Intent(this, DistanceMeasurementActivity::class.java))
        finish()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "startDistanceMeasurement" -> {
                    launchDistanceMeasurement()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }
    }
}

