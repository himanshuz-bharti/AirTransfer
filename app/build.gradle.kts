import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Machine-local OpenCV SDK location. local.properties is ignored by Git.
val localProperties = Properties()
val localPropertiesFile = rootProject.file("local.properties")
if (localPropertiesFile.exists()) {
    localPropertiesFile.inputStream().use { input ->
        localProperties.load(input)
    }
}

val openCvSdkRoot = localProperties.getProperty("opencv.sdk.dir")
    ?.trim()
    ?.replace("\\", "/")

val openCvConfigDir = openCvSdkRoot?.let { "$it/sdk/native/jni" }

android {
    namespace = "com.example.myapplication"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.myapplication"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                if (openCvConfigDir != null) {
                    arguments += "-DOpenCV_DIR=$openCvConfigDir"
                }
            }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

}

dependencies {
    implementation(libs.activity.ktx)
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)

        // CameraX Core, Camera2, Lifecycle, and View
        val cameraxVersion = "1.3.4"
        implementation("androidx.camera:camera-core:$cameraxVersion")
        implementation("androidx.camera:camera-camera2:$cameraxVersion")
        implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
        implementation("androidx.camera:camera-view:$cameraxVersion")
        implementation("com.google.mediapipe:tasks-vision:0.10.29")

        implementation("com.google.ai.edge.litert:litert:1.4.0")
        implementation("com.google.android.material:material:1.11.0")

        implementation("org.java-websocket:Java-WebSocket:1.6.0")
        implementation("com.squareup.okhttp3:okhttp:5.5.0")


        // Guava for Async Futures handling in Java
        implementation("com.google.guava:guava:31.1-android")
}
