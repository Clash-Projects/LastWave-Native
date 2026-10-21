package com.lastwave.app.playback

import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import androidx.media3.common.Player

/**
 * The music card of OriginOS (vivo / iQOO), shown on the lock screen and the
 * Origin Island by `com.vivo.musicwidgetmix`.
 *
 * The card controls most apps through a plain media controller that only has
 * the basic transport buttons. It offers its heart button, loop mode button
 * and playlist, favorites and downloads lists to apps whose media browser
 * service declares [SERVICE_ACTION], and reads what to show from keys of its
 * own in the session metadata. It sends a heart rating, a custom action for
 * the loop mode, and plays list entries by their media ids.
 */
internal object OriginOsMusicCard {
    const val PACKAGE = "com.vivo.musicwidgetmix"

    /** Declared on the media browser service in the manifest. */
    const val SERVICE_ACTION = "com.vivo.musicwidgetmix.support.service"

    /** Bit mask of what the card may show and control. Without it, the card disables everything. */
    const val KEY_SUPPORT_EVENT = "vivomusicmix.media.metadata.support_event"
    private const val EVENT_PLAY_CONTROL = 1L
    private const val EVENT_MUSIC_INFO = 1L shl 1
    private const val EVENT_MUSIC_IMAGE = 1L shl 2
    private const val EVENT_SEEK_POSITION = 1L shl 4
    private const val EVENT_FAVORITE = 1L shl 6
    private const val EVENT_LOOP_MODE = 1L shl 7
    private const val EVENT_LIST_CURRENT = 1L shl 8
    private const val EVENT_LIST_FAVORITE = 1L shl 9
    private const val EVENT_LIST_LOCAL = 1L shl 10

    /** Lyrics (bit 3) stay with LastWave's own lyrics screen. */
    const val SUPPORTED_EVENTS = EVENT_PLAY_CONTROL or EVENT_MUSIC_INFO or EVENT_MUSIC_IMAGE or
        EVENT_SEEK_POSITION or EVENT_FAVORITE or EVENT_LOOP_MODE or
        EVENT_LIST_CURRENT or EVENT_LIST_FAVORITE or EVENT_LIST_LOCAL

    const val KEY_LOOP_MODE = "vivomusicmix.media.metadata.LOOP_MODE"
    const val LOOP_MODE_LIST = 1
    const val LOOP_MODE_SINGLE = 2
    const val LOOP_MODE_RANDOM = 3

    /** Custom action the card sends with the [KEY_LOOP_MODE] to change to. */
    const val ACTION_PLAY_MODE = "vivomusicmix.media.action.PLAY_MODE"

    /** Media browser parents of the playlist, the favorites and the downloads. */
    const val LIST_CURRENT = "vivomusicmix_current_list"
    const val LIST_FAVORITE = "vivomusicmix_favorite_list"
    const val LIST_LOCAL = "vivomusicmix_local_list"

    /** The card loads lists in pages, and reads from the last entry of a page if there is another. */
    const val KEY_PAGE = "vivomusicmix_key_media_page"
    private const val KEY_HAS_MORE = "vivomusicmix_key_has_more"

    /**
     * Media id of the entry that says a list has nothing in it. The card keeps
     * loading a list without entries, and then reports a network error.
     */
    const val EMPTY_LIST_MEDIA_ID = "vivomusicmix:empty"

    private const val QUEUE_PREFIX = "queue:"

    fun isList(parentId: String) = parentId == LIST_CURRENT || parentId == LIST_FAVORITE || parentId == LIST_LOCAL

    /**
     * Media id of a queue entry. The card only notices a new track when the
     * media id changes, and the queue index alone stays the same as a new
     * queue starts, so the track is part of it.
     */
    fun mediaIdOf(index: Int, track: PlayableTrack): String {
        val identity = track.videoId ?: "${track.title}|${track.artist}"
        return "$QUEUE_PREFIX$index:${Integer.toHexString(identity.hashCode())}"
    }

    /** The `queue:index` media id the Android Auto library plays, for a [mediaIdOf] one. */
    fun queueMediaIdOf(mediaId: String): String =
        if (mediaId.startsWith(QUEUE_PREFIX)) mediaId.substringBeforeLast(':').takeIf { it != "queue" } ?: mediaId
        else mediaId

    fun loopModeOf(shuffleEnabled: Boolean, repeatMode: Int): Int = when {
        shuffleEnabled -> LOOP_MODE_RANDOM
        repeatMode == Player.REPEAT_MODE_ONE -> LOOP_MODE_SINGLE
        else -> LOOP_MODE_LIST
    }

    /** @return shuffle and the Media3 repeat mode for a loop mode of the card, or null for an unknown one. */
    fun playModesOf(loopMode: Int): Pair<Boolean, Int>? = when (loopMode) {
        LOOP_MODE_LIST -> false to Player.REPEAT_MODE_ALL
        LOOP_MODE_SINGLE -> false to Player.REPEAT_MODE_ONE
        LOOP_MODE_RANDOM -> true to Player.REPEAT_MODE_ALL
        else -> null
    }

    /**
     * The entries of a list as the only page the card has to load of it. The
     * card plays every entry it shows, so browsable entries are made playable.
     */
    fun singlePage(items: List<MediaBrowserCompat.MediaItem>, emptyTitle: String, emptySubtitle: String): List<MediaBrowserCompat.MediaItem> {
        val descriptions = items.map { it.description }.ifEmpty {
            listOf(
                MediaDescriptionCompat.Builder()
                    .setMediaId(EMPTY_LIST_MEDIA_ID)
                    .setTitle(emptyTitle)
                    .setSubtitle(emptySubtitle)
                    .build(),
            )
        }
        return descriptions.mapIndexed { index, description ->
            val entry = MediaDescriptionCompat.Builder()
                .setMediaId(description.mediaId)
                .setTitle(description.title)
                // The card shows a subtitle that is not set as text.
                .setSubtitle(description.subtitle ?: "")
                .setDescription(description.description)
                .setIconUri(description.iconUri)
            if (index == descriptions.lastIndex) {
                entry.setExtras(
                    Bundle().apply {
                        putBoolean(KEY_HAS_MORE, false)
                        putInt(KEY_PAGE, 1)
                    },
                )
            }
            MediaBrowserCompat.MediaItem(entry.build(), MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
        }
    }
}
