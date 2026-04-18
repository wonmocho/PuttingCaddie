import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    id("kotlin-android")
    // Flutter Gradle Plugin은 반드시 Android/Kotlin 뒤에
    id("dev.flutter.flutter-gradle-plugin")
}

/**
 * release signing 설정 로드
 * android/key.properties 사용
 */
val keystoreProperties = Properties()
val keystorePropertiesFile =
    sequenceOf(
        rootProject.file("key.properties"),
        rootProject.file("../../PuttingCaddyPlus/android/key.properties"),
        rootProject.file("../../android/key.properties")
    ).firstOrNull { it.exists() }
        ?: error("key.properties not found (copy to this android/ or keep sibling PuttingCaddyPlus/android/key.properties)")
keystoreProperties.load(FileInputStream(keystorePropertiesFile))

android {
    namespace = "com.wmcho.puttingcaddie"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        /** 2026-04-03 안정 스냅샷(커밋 3e10423) — PuttingCaddy+ 와 동시 설치 */
        applicationId = "com.wmcho.puttingcaddy"

        // ARCore + Play 안정성 고려
        minSdk = 24
        targetSdk = flutter.targetSdkVersion

        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    /**
     * 🔐 서명 설정 (release)
     */
    signingConfigs {
        create("release") {
            val storeFilePath =
                keystoreProperties["storeFile"] ?: error("storeFile not found in key.properties")
            val storePasswordValue =
                keystoreProperties["storePassword"] ?: error("storePassword not found in key.properties")
            val keyAliasValue = keystoreProperties["keyAlias"] ?: error("keyAlias not found in key.properties")
            val keyPasswordValue =
                keystoreProperties["keyPassword"] ?: error("keyPassword not found in key.properties")

            // storeFile in key.properties is relative to the directory that contains key.properties
            val keystoreDir = keystorePropertiesFile.parentFile ?: rootProject.projectDir
            val keystoreFile = File(keystoreDir, storeFilePath.toString()).canonicalFile
            if (!keystoreFile.exists()) {
                error("Keystore file not found: ${keystoreFile.path}")
            }
            storeFile = keystoreFile
            storePassword = storePasswordValue.toString()
            keyAlias = keyAliasValue.toString()
            keyPassword = keyPasswordValue.toString()
        }
    }

    buildTypes {
        release {
            // ❗ 중요: debug 절대 사용하지 않음
            signingConfig = signingConfigs.getByName("release")

            isMinifyEnabled = false
            isShrinkResources = false
        }

        debug {
            // debug는 기본 debug 키 사용
        }
    }

    lint {
        // local.properties is machine-local and may contain Windows drive paths.
        // Keep production code unchanged; disable only this environment lint rule.
        disable += "PropertyEscape"
    }

}

flutter {
    source = "../.."
}

dependencies {
    // ARCore (16KB page size 호환)
    implementation("com.google.ar:core:1.52.0")

    // AndroidX / Material
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // 16KB page size 호환 (1.23.0+)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.0")

    // Google Play In-App Review (used only on 2nd survey positive path)
    implementation("com.google.android.play:review:2.0.1")
    // Google Play Billing (Pro 구독. Phase 2)
    implementation("com.android.billingclient:billing:6.1.0")
    implementation("com.android.billingclient:billing-ktx:6.1.0")
}


