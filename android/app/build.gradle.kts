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
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
} else {
    error("key.properties file not found")
}

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
        applicationId = "com.wmcho.puttingcaddie"

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

            val keystoreFile = rootProject.file(storeFilePath.toString())
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
}

flutter {
    source = "../.."
}

dependencies {
    // ARCore
    implementation("com.google.ar:core:1.44.0")

    // AndroidX / Material
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Google Play In-App Review (used only on 2nd survey positive path)
    implementation("com.google.android.play:review:2.0.1")
}


