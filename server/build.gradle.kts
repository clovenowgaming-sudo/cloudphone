plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vinz.cloudphone.server"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.vinz.cloudphone.server"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        ndk { abiFilters += "arm64-v8a" }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true            // ngrok di-extract ke nativeLibraryDir agar bisa dieksekusi
            keepDebugSymbols += "**/libngrok.so"
            keepDebugSymbols += "**/libcloudflared.so" // jangan di-strip
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
