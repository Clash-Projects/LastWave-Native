package com.lastwave.app.data.spotify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CanvasRequest(
    @ProtoNumber(1) val tracks: List<TrackRequest> = emptyList(),
) {
    @Serializable
    data class TrackRequest(
        @ProtoNumber(1) val trackUri: String, // "spotify:track:<trackId>"
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class CanvasResponse(
    @ProtoNumber(1) val canvases: List<CanvasItem> = emptyList(),
) {
    @Serializable
    data class CanvasItem(
        @ProtoNumber(1) val id: String? = null,
        @ProtoNumber(2) val canvasUrl: String? = null,
        @ProtoNumber(5) val trackUri: String? = null,
        @ProtoNumber(8) val artist: ArtistItem? = null,
    ) {
        @Serializable
        data class ArtistItem(
            @ProtoNumber(1) val artistUri: String? = null,
            @ProtoNumber(2) val artistName: String? = null,
        )
    }
}

@Serializable
data class ClientTokenRequest(
    val client_data: ClientData = ClientData(),
) {
    @Serializable
    data class ClientData(
        val client_version: String = "1.3.7.1345.gcff6cec98b6e",
        val client_id: String = "d8a5ed958d274c2e8ee717e6a4b0971d",
        val js_sdk_data: JsSdkData = JsSdkData(),
    )

    @Serializable
    data class JsSdkData(
        val device_brand: String = "unknown",
        val device_model: String = "unknown",
        val os: String = "windows",
        val os_version: String = "10.0.0",
    )
}

@Serializable
data class SpotifyWebSearchResponse(
    val tracks: TracksContainer? = null,
) {
    @Serializable
    data class TracksContainer(
        val items: List<TrackItem> = emptyList(),
    ) {
        @Serializable
        data class TrackItem(
            val id: String? = null,
            val name: String? = null,
            val duration_ms: Long? = null,
            val artists: List<ArtistItem> = emptyList(),
        ) {
            @Serializable
            data class ArtistItem(val name: String? = null)
        }
    }
}

@Serializable
data class ClientTokenResponse(
    val response_type: String? = null,
    val granted_token: GrantedToken? = null,
) {
    @Serializable
    data class GrantedToken(
        val token: String? = null,
        val expires_after_seconds: Long? = null,
        val refresh_after_seconds: Long? = null,
    )
}

@Serializable
data class SpotifyServerTimeResponse(
    val serverTime: Long? = null,
)

@Serializable
data class SpotifyPersonalTokenResponse(
    val accessToken: String? = null,
    val accessTokenExpirationTimestampMs: Long? = null,
    val isAnonymous: Boolean? = null,
    val clientId: String? = null,
)

@Serializable
data class SpotifySearchResponse(
    val data: Data? = null,
) {
    @Serializable
    data class Data(
        val searchV2: Search? = null,
    ) {
        @Serializable
        data class Search(
            val query: String? = null,
            val tracksV2: TracksV2? = null,
        ) {
            @Serializable
            data class TracksV2(
                val items: List<Items>? = null,
                val pagingInfo: PagingInfo? = null,
                val totalCount: Int? = null,
            ) {
                @Serializable
                data class PagingInfo(
                    val limit: Int? = null,
                    val nextOffset: Int? = null,
                )

                @Serializable
                data class Items(
                    val item: Item? = null,
                ) {
                    @Serializable
                    data class Item(
                        val data: TrackData? = null,
                    ) {
                        @Serializable
                        data class TrackData(
                            val id: String? = null,
                            val uri: String? = null,
                            val name: String? = null,
                            val artists: Artists? = null,
                            val duration: Duration? = null,
                        ) {
                            @Serializable
                            data class Duration(
                                val totalMilliseconds: Int? = null,
                            )

                            @Serializable
                            data class Artists(
                                val items: List<ArtistItem>? = null,
                            ) {
                                @Serializable
                                data class ArtistItem(
                                    val profile: Profile? = null,
                                    val uri: String? = null,
                                ) {
                                    @Serializable
                                    data class Profile(
                                        val name: String? = null,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Serializable
data class SpotifyColorLyricsResponse(
    val lyrics: LyricsData? = null,
    val colors: ColorData? = null,
) {
    @Serializable
    data class LyricsData(
        val syncType: String? = null,
        val lines: List<LineData> = emptyList(),
        val provider: String? = null,
        val providerDisplayName: String? = null,
        val syncLyrics: Boolean? = null,
        val language: String? = null,
    ) {
        @Serializable
        data class LineData(
            val startTimeMs: String? = null,
            val words: String? = null,
            val endTimeMs: String? = null,
        )
    }

    @Serializable
    data class ColorData(
        val background: Int? = null,
        val text: Int? = null,
        val highlightText: Int? = null,
    )
}

@Serializable
data class SpotifyLikedTracksResponse(
    val items: List<Item> = emptyList(),
    val total: Int = 0,
    val next: String? = null,
) {
    @Serializable
    data class Item(
        val added_at: String? = null,
        val track: Track? = null,
    ) {
        @Serializable
        data class Track(
            val id: String? = null,
            val name: String? = null,
            val artists: List<Artist> = emptyList(),
            val album: Album? = null,
            val duration_ms: Long? = null,
            val uri: String? = null,
        ) {
            @Serializable
            data class Artist(
                val id: String? = null,
                val name: String? = null,
            )

            @Serializable
            data class Album(
                val id: String? = null,
                val name: String? = null,
                val images: List<Image> = emptyList(),
            ) {
                @Serializable
                data class Image(
                    val url: String? = null,
                    val height: Int? = null,
                    val width: Int? = null,
                )
            }
        }
    }
}
