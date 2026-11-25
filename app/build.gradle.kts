plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val compileHiddenApi by tasks.registering(JavaCompile::class) {
    source(fileTree("src/hiddenApi/java"))
    classpath = files(android.bootClasspath)
    destinationDirectory.set(layout.buildDirectory.dir("generated/hiddenApi/classes"))
    sourceCompatibility = JavaVersion.VERSION_17.toString()
    targetCompatibility = JavaVersion.VERSION_17.toString()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    dependsOn(compileHiddenApi)
    libraries.from(layout.buildDirectory.dir("generated/hiddenApi/classes"))
}

android {
    namespace = "com.privvy.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.privvy.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndkVersion = "26.3.11579264"  // Dobby arm64 asm fails on NDK 27's clang (@PAGE relocation)

        // arm64-v8a mandatory (spec NFR). armeabi-v7a optional; add when Dobby hook set is validated.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20 -fvisibility=hidden"
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DDOBBY_DISABLE_GENERATE_OBJS=ON",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        // Keep extractNativeLibs=true in the manifest (required: guest APKs are
        // loaded via DexClassLoader from the extracted lib dir, not mmap'd APK).
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    // TODO(D-00x): strip guest APK contents must never be processed by AAPT — imported .apk/.xapk files
    // are stored under files/virtual/apks/ at runtime, not bundled.
}

dependencies {
    compileOnly(files(layout.buildDirectory.dir("generated/hiddenApi/classes")))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.security.crypto)
    implementation(libs.hiddenapibypass)          // D-006: hidden API reflection on API 30–36
    implementation(libs.kotlinx.coroutines.android)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // UI testing (instrumented, runs on emulator/device)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
