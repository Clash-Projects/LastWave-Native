plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.lastwave.trueglass"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    // Must mirror every app build type (debug/release/rawRelease) or variant
    // matching fails when the app builds rawRelease (same as the audio
    // driver modules).
    buildTypes {
        getByName("debug")
        getByName("release")
        create("rawRelease") {
            initWith(getByName("release"))
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation(libs.androidx.core.ktx)
}
