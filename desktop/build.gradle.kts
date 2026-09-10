plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

group = "com.lastwave.desktop"
version = "1.0.0"

repositories {
    mavenCentral()
    google()
}

dependencies {
    // Compose for Desktop core
    implementation("androidx.compose.desktop:compose-desktop-jvm:1.6.0")

    // Compose for Desktop Material3
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-systems")

    // Compose for Desktop Window integration
    implementation("androidx.compose.desktop:compose-desktop-window:1.6.0")

    // Accompanist for additional Material3 features
    implementation("com.google.accompanist:accompanist-material3:0.43.0")
    implementation("com.google.accompanist:accompanist-interpolation:0.43.0")

    // Coroutines and serialization
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.1")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("com.google.code.gson:gson:2.10.1")

    // Hilt for dependency injection (JVM compatible)
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-android-compiler:2.48")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

compose {
    kotlinCompilerExtensionVersion = "1.5.0"
    kotlinCompilerVersion = "1.9.23"
}