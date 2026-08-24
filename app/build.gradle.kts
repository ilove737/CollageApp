plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.collage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.collage"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // 只打包 arm64-v8a（现代真机均为 64 位），避免 MediaPipe 原生库撑大 APK
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Material Design 3 组件库（Slider/Switch/Chip/BottomSheet/TabLayout）
    implementation("com.google.android.material:material:1.12.0")
    // 人像分割（MediaPipe，本地推理，模型 assets/selfie_segmenter.tflite）
    implementation("com.google.mediapipe:tasks-vision:0.10.35")
    // 人像抠图（TFLite 原生推理，MODNet/RMBG，模型 assets/modnet.tflite）
    implementation("org.tensorflow:tensorflow-lite:2.14.0")
    // TFLite GPU delegate（可选加速，与 MediaPipe GPU 互不冲突；如需可启用）
    implementation("org.tensorflow:tensorflow-lite-gpu:2.14.0")
}