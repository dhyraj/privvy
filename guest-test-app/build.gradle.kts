plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.privvy.guesttest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.privvy.guesttest"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
