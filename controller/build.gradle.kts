plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.vinz.cloudphone.controller"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.vinz.cloudphone.controller"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("fixed.p12")
            storePassword = "android"
            keyAlias = "key"
            keyPassword = "android"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
