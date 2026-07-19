plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.yourbusiness.formfusion"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.yourbusiness.formfusion"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        val backendUrl = providers.gradleProperty("FORMFUSION_BACKEND_URL")
            .orElse("http://10.0.2.2:8000")
        buildConfigField("String", "BACKEND_URL", "\"${backendUrl.get()}\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    androidResources { noCompress += "tflite" }

    testOptions {
        unitTests {
            // Lets plain JVM unit tests call simple Android SDK methods (e.g. android.util.Log)
            // without throwing "not mocked" — no Robolectric needed for our pure-logic tests.
            isReturnDefaultValues = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)

    // Outlined/rounded icon set used throughout the redesigned UI (chevrons, status icons,
    // etc.) — the default icon set bundled with material3 only covers a handful of glyphs.
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // QR CODE GENERATION (host shows a QR code) — pure Kotlin/Java, no JNI
    implementation("com.google.zxing:core:3.5.3")

    // QR CODE SCANNING (join — ML Kit's ready-made scanner UI, no custom camera screen)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")

    // HTTP + resilient WebSocket transport to the FormFusion backend.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // COROUTINES (async WebSocket + shared state)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // TFLITE / LITERT (runs rtmdet.tflite; QNN delegate attaches to this Interpreter at runtime)
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // QNN HTP delegate for rtmdet.tflite. Version pinned to match the QAIRT version
    // rtmdet.tflite was exported with (see assets/metadata.json -> tool_versions.qairt).
    // These AARs bundle the QNN backend .so files (incl. libqnn_delegate_jni.so), so the
    // native libs no longer need to be placed manually under jniLibs/arm64-v8a.
    implementation("com.qualcomm.qti:qnn-litert-delegate:2.45.0")
    implementation("com.qualcomm.qti:qnn-runtime:2.45.0")

    // ONNX RUNTIME (runs rtmpose_body2d.onnx). This build bundles the QNN execution
    // provider; it reuses the same QNN backend .so files pulled in above by qnn-runtime,
    // so no extra native libs are needed for HTP acceleration here.
    implementation("com.microsoft.onnxruntime:onnxruntime-android-qnn:1.26.0")

    testImplementation(libs.junit)
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
