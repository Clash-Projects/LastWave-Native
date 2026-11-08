@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.lastwave.app.data.plugin

import android.util.Log
import androidx.media3.common.Format
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Descriptor

/** Media3 1.2.x does not derive a sample MIME type from DASH codec `alac`. */
internal class AlacDashManifestParser : DashManifestParser() {
    override fun buildFormat(
        id: String?,
        containerMimeType: String?,
        width: Int,
        height: Int,
        frameRate: Float,
        audioChannels: Int,
        audioSamplingRate: Int,
        bitrate: Int,
        language: String?,
        roleDescriptors: List<Descriptor>,
        accessibilityDescriptors: List<Descriptor>,
        codecs: String?,
        essentialProperties: List<Descriptor>,
        supplementalProperties: List<Descriptor>,
    ): Format {
        val parsed = super.buildFormat(
            id,
            containerMimeType,
            width,
            height,
            frameRate,
            audioChannels,
            audioSamplingRate,
            bitrate,
            language,
            roleDescriptors,
            accessibilityDescriptors,
            codecs,
            essentialProperties,
            supplementalProperties,
        )
        val isAlac = codecs.orEmpty().split(',').any { it.trim().equals("alac", ignoreCase = true) }
        if (!isAlac || parsed.sampleMimeType != null) return parsed

        Log.i(TAG, "Mapped DASH codec=alac to audio/alac (${audioSamplingRate}Hz)")
        return parsed.buildUpon().setSampleMimeType(ALAC_SAMPLE_MIME_TYPE).build()
    }

    private companion object {
        const val TAG = "AlacDashManifest"
        const val ALAC_SAMPLE_MIME_TYPE = "audio/alac"
    }
}
