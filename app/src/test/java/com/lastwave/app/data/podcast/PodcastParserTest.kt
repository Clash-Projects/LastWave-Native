package com.lastwave.app.data.podcast

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parsing of the YouTube Music podcast surfaces. The fixtures below mirror the
 * renderer shapes the InnerTube probes returned: show cards are
 * `musicTwoRowItemRenderer`, episodes are `musicMultiRowListItemRenderer`, and
 * search rows are `musicResponsiveListItemRenderer`.
 */
class PodcastParserTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parse(raw: String): JsonObject = json.parseToJsonElement(raw) as JsonObject

    @Test
    fun parseHome_readsShowCardsWithIdsTitlesAndArtwork() {
        val root = parse(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicCarouselShelfRenderer": {
                        "header": {
                          "musicCarouselShelfBasicHeaderRenderer": {
                            "title": { "runs": [ { "text": "Popular shows" } ] }
                          }
                        },
                        "contents": [
                          {
                            "musicTwoRowItemRenderer": {
                              "title": { "runs": [ { "text": "Waveform Weekly" } ] },
                              "subtitle": { "runs": [ { "text": "Acme Studios" } ] },
                              "navigationEndpoint": {
                                "browseEndpoint": { "browseId": "MPSPPLabc123" }
                              },
                              "thumbnailRenderer": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [ { "url": "https://lh3.googleusercontent.com/show=w120" } ]
                                  }
                                }
                              }
                            }
                          },
                          {
                            "musicTwoRowItemRenderer": {
                              "title": { "runs": [ { "text": "Not A Show" } ] },
                              "navigationEndpoint": {
                                "browseEndpoint": { "browseId": "MPREb_album" }
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val shelves = PodcastParser.parseHome(root)

        assertThat(shelves).hasSize(1)
        val shelf = shelves.first()
        assertThat(shelf.title).isEqualTo("Popular shows")
        assertThat(shelf.shows).hasSize(1)
        val show = shelf.shows.first()
        assertThat(show.showId).isEqualTo("MPSPPLabc123")
        assertThat(show.title).isEqualTo("Waveform Weekly")
        assertThat(show.publisher).isEqualTo("Acme Studios")
        assertThat(show.artworkUrl).isEqualTo("https://lh3.googleusercontent.com/show=w512-h512-l90-rj")
    }

    @Test
    fun parseHome_readsEpisodeShelfWithVideoIdAndDuration() {
        val root = parse(
            """
            {
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicCarouselShelfRenderer": {
                        "header": {
                          "musicCarouselShelfBasicHeaderRenderer": {
                            "title": { "runs": [ { "text": "Popular episodes" } ] }
                          }
                        },
                        "contents": [
                          {
                            "musicMultiRowListItemRenderer": {
                              "title": { "runs": [ { "text": "Episode 12" } ] },
                              "subtitle": { "runs": [ { "text": "902K views • 18h ago" } ] },
                              "onTap": { "watchEndpoint": { "videoId": "abcdefghijk" } },
                              "playbackProgress": {
                                "musicPlaybackProgressRenderer": {
                                  "durationText": { "runs": [ { "text": "1:02:03" } ] }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val shelves = PodcastParser.parseHome(root)

        assertThat(shelves).hasSize(1)
        val episode = shelves.first().episodes.first()
        assertThat(episode.videoId).isEqualTo("abcdefghijk")
        assertThat(episode.title).isEqualTo("Episode 12")
        assertThat(episode.durationMs).isEqualTo(3_723_000L)
        assertThat(episode.publishedLabel).isEqualTo("18h ago")
    }

    @Test
    fun parseShow_readsHeaderAndEpisodesWithTheShowId() {
        val root = parse(
            """
            {
              "header": {
                "musicResponsiveHeaderRenderer": {
                  "title": { "runs": [ { "text": "Deep Dive" } ] },
                  "description": { "runs": [ { "text": "Long conversations about audio." } ] },
                  "thumbnail": {
                    "musicThumbnailRenderer": {
                      "thumbnail": { "thumbnails": [ { "url": "https://lh3.googleusercontent.com/deep" } ] }
                    }
                  }
                }
              },
              "contents": {
                "sectionListRenderer": {
                  "contents": [
                    {
                      "musicShelfRenderer": {
                        "contents": [
                          {
                            "musicMultiRowListItemRenderer": {
                              "title": { "runs": [ { "text": "Pilot" } ] },
                              "subtitle": { "runs": [ { "text": "1.2K views • Oct 9" } ] },
                              "onTap": { "watchEndpoint": { "videoId": "pilotvideoid" } }
                            }
                          }
                        ]
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val page = PodcastParser.parseShow(root, "MPSPPLdeep1")

        assertThat(page.show.showId).isEqualTo("MPSPPLdeep1")
        assertThat(page.show.title).isEqualTo("Deep Dive")
        assertThat(page.show.description).isEqualTo("Long conversations about audio.")
        assertThat(page.episodes).hasSize(1)
        assertThat(page.episodes.first().videoId).isEqualTo("pilotvideoid")
        assertThat(page.episodes.first().publishedLabel).isEqualTo("Oct 9")
    }

    @Test
    fun parseEpisodeSearch_readsVideoIdTitleAndShowName() {
        val root = parse(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "musicResponsiveListItemRenderer": {
                                  "flexColumns": [
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [ { "text": "The Interview" } ] }
                                      }
                                    },
                                    {
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [ { "text": "18h ago • Deep Dive" } ] }
                                      }
                                    }
                                  ],
                                  "playlistItemData": { "videoId": "interviewvid" },
                                  "thumbnail": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": { "thumbnails": [ { "url": "https://i.ytimg.com/vi/interviewvid/hqdefault.jpg" } ] }
                                    }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        val episodes = PodcastParser.parseEpisodeSearch(root)

        assertThat(episodes).hasSize(1)
        val episode = episodes.first()
        assertThat(episode.videoId).isEqualTo("interviewvid")
        assertThat(episode.title).isEqualTo("The Interview")
        assertThat(episode.publishedLabel).isEqualTo("18h ago")
        assertThat(episode.showTitle).isEqualTo("Deep Dive")
    }

    @Test
    fun episodeSearchParams_readsTheEpisodesChipToken() {
        val root = parse(
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [
                    {
                      "tabRenderer": {
                        "content": {
                          "sectionListRenderer": {
                            "contents": [
                              {
                                "searchFilterRenderer": {
                                  "chips": [
                                    {
                                      "chipCloudChipRenderer": {
                                        "text": { "runs": [ { "text": "Songs" } ] },
                                        "navigationEndpoint": {
                                          "searchEndpoint": { "params": "EgWKAQIIAWoKEAkQBRAKEAMQBA==" }
                                        }
                                      }
                                    },
                                    {
                                      "chipCloudChipRenderer": {
                                        "text": { "runs": [ { "text": "Episodes" } ] },
                                        "navigationEndpoint": {
                                          "searchEndpoint": { "params": "EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV" }
                                        }
                                      }
                                    }
                                  ]
                                }
                              }
                            ]
                          }
                        }
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent(),
        )

        assertThat(PodcastParser.episodeSearchParams(root))
            .isEqualTo("EgWKAQJIAWoSEBEQAxAQEAUQChAOEAkQBBAV")
    }

    @Test
    fun toPlayableTrack_mapsEpisodeFieldsAndMarksItAsPodcastMedia() {
        val episode = PodcastEpisode(
            videoId = "abcdefghijk",
            title = "Episode 1",
            showId = "MPSPPLabc",
            showTitle = "Waveform Weekly",
            durationMs = 1_000_000L,
        )

        val track = episode.toPlayableTrack()

        assertThat(track.title).isEqualTo("Episode 1")
        assertThat(track.artist).isEqualTo("Waveform Weekly")
        assertThat(track.album).isNull()
        assertThat(track.videoId).isEqualTo("abcdefghijk")
        assertThat(track.durationMs).isEqualTo(1_000_000L)
        assertThat(track.mediaType).isEqualTo(com.lastwave.app.playback.MediaType.PODCAST_EPISODE)
        assertThat(track.showId).isEqualTo("MPSPPLabc")
    }

    @Test
    fun toPlayableTrack_withoutShowTitle_fallsBackToPodcastArtist() {
        val episode = PodcastEpisode(videoId = "abcdefghijk", title = "Episode 1")

        val track = episode.toPlayableTrack()

        assertThat(track.artist).isEqualTo("Podcast")
    }
}
