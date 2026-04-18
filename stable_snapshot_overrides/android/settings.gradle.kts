pluginManagement {
    val flutterSdkPath =
        run {
            val properties = java.util.Properties()
            val localProps =
                sequenceOf(
                    file("local.properties"),
                    // 안정: …/PuttingCaddy/android — 형제 Plus: …/PuttingCaddyPlus/android
                    file("../../PuttingCaddyPlus/android/local.properties"),
                    // 예전: Plus 저장소 안에 스냅샷이 중첩된 경우
                    file("../../android/local.properties")
                ).firstOrNull { it.exists() }
                    ?: error(
                        "local.properties missing: copy PuttingCaddyPlus/android/local.properties to this android/local.properties (sibling PuttingCaddyPlus)."
                    )
            localProps.inputStream().use { properties.load(it) }
            val flutterSdkPath = properties.getProperty("flutter.sdk")
            require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
            flutterSdkPath
        }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    id("com.android.application") version "8.11.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
}

include(":app")
