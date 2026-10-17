package com.lastwave.app.data.update

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AppUpdateManagerTest {

    private val sampleAssets = listOf(
        ReleaseAsset(
            name = "LastWave-v4.2.4-android7.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-android7.apk",
        ),
        ReleaseAsset(
            name = "LastWave-v4.2.4-universal-uncompressed.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-universal-uncompressed.apk",
        ),
        ReleaseAsset(
            name = "LastWave-v4.2.4-universal.apk",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/LastWave-v4.2.4-universal.apk",
        ),
        ReleaseAsset(
            name = "SHA256SUMS.txt",
            downloadUrl = "https://github.com/Clash-Projects/LastWave-Native/releases/download/4.2.4/SHA256SUMS.txt",
        ),
    )

    @Test
    fun classifyAsset_recognizesVariantsCorrectly() {
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-android7.apk"))
            .isEqualTo(ApkVariant.ANDROID7)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-legacy.apk"))
            .isEqualTo(ApkVariant.ANDROID7)

        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-universal-uncompressed.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-raw.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-beta-raw.apk"))
            .isEqualTo(ApkVariant.UNCOMPRESSED)

        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-universal.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-release.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
        assertThat(AppUpdateManager.classifyAsset("LastWave-v4.2.4-beta.apk"))
            .isEqualTo(ApkVariant.UNIVERSAL)
    }

    @Test
    fun selectBestAsset_standardAndroid10Plus_selectsUniversalApk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = false,
            sdkInt = 34, // Android 14
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-universal.apk")
    }

    @Test
    fun selectBestAsset_uncompressedBuild_selectsUncompressedApk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = true,
            sdkInt = 34, // Android 14
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal-uncompressed.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-universal-uncompressed.apk")
    }

    @Test
    fun selectBestAsset_legacyAndroid_selectsAndroid7Apk() {
        val selected = AppUpdateManager.selectBestAsset(
            assets = sampleAssets,
            isUncompressed = false,
            sdkInt = 26, // Android 8.0 Oreo
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-android7.apk")
        assertThat(selected?.downloadUrl).contains("LastWave-v4.2.4-android7.apk")
    }

    @Test
    fun selectBestAsset_ignoresNonApkAssets() {
        val nonApkOnly = listOf(
            ReleaseAsset(name = "SHA256SUMS.txt", downloadUrl = "https://example.com/sha"),
            ReleaseAsset(name = "notice.md", downloadUrl = "https://example.com/md"),
        )
        val selected = AppUpdateManager.selectBestAsset(
            assets = nonApkOnly,
            isUncompressed = false,
            sdkInt = 34,
        )
        assertThat(selected).isNull()
    }

    @Test
    fun selectBestAsset_fallbackWhenUniversalMissing_fallsBackToUncompressedOrAndroid7() {
        val assetsWithoutUniversal = listOf(
            ReleaseAsset(name = "LastWave-v4.2.4-android7.apk", downloadUrl = "https://example.com/a7"),
            ReleaseAsset(name = "LastWave-v4.2.4-universal-uncompressed.apk", downloadUrl = "https://example.com/raw"),
        )
        val selected = AppUpdateManager.selectBestAsset(
            assets = assetsWithoutUniversal,
            isUncompressed = false,
            sdkInt = 34,
        )
        assertThat(selected).isNotNull()
        assertThat(selected?.name).isEqualTo("LastWave-v4.2.4-universal-uncompressed.apk")
    }
}
