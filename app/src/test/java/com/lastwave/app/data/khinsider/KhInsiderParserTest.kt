package com.lastwave.app.data.khinsider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Fixtures mirror the structure of KHInsider pages (search table, album
 * songlist, track page links). Hosts and file names are made up.
 */
class KhInsiderParserTest {

    private val searchHtml = """
        <html><body><div id="pageContent">
        <p>Found 373 matching albums.</p>
        <table class="albumList">
          <tr><th></th><th><a href="/search?sort=name">Album</a></th><th>Platform</th><th>Type</th><th><a href="#">Year</a></th></tr>
          <tr>
            <td><a href="https://downloads.khinsider.com/game-soundtracks/album/zelda-one-2014"><img src="https://img.example/soundtracks/zelda-one-2014/thumbs_small/cover.png"></a></td>
            <td><a href="https://downloads.khinsider.com/game-soundtracks/album/zelda-one-2014">Zelda &amp; Link - One</a></td>
            <td><a href="https://downloads.khinsider.com/game-soundtracks/cd-i">CD-i</a></td>
            <td>Arrangement</td>
            <td>2014</td>
          </tr>
          <tr>
            <td><a href="/game-soundtracks/album/oot"><img src="/thumbs/oot.jpg"></a></td>
            <td><a href="/game-soundtracks/album/oot">Ocarina of Time</a></td>
            <td><a href="/game-soundtracks/nintendo-64">N64</a></td>
            <td>Soundtrack</td>
            <td>1998</td>
          </tr>
        </table>
        </div></body></html>
    """.trimIndent()

    private val albumHtml = """
        <html><body><div id="pageContent">
        <h2>Minecraft Soundtrack - Volume Alpha</h2>
        <table><tr><td><a href="https://img.example/soundtracks/minecraft/cover.png"><img src="https://img.example/soundtracks/minecraft/thumbs/cover.png"></a></td></tr></table>
        <p align="left">
          Platforms: <a href="/game-soundtracks/android">Android</a>, <a href="/game-soundtracks/windows">Windows</a><br>
          Year: <b>2011</b><br>
          Published by: <a href="/game-soundtracks/publisher/mojang">Mojang</a><br>
          Number of Files: <b>3</b><br>
          Date Added: <b>Apr 23rd, 2021</b><br>
          Album type: <a href="/game-soundtracks/ost">Soundtrack</a><br>
        </p>
        <table id="songlist">
          <tr id="songlist_header"><th>&nbsp;</th><th>CD</th><th>#</th><th colspan="2">Song Name</th><th><b>MP3</b></th><th><b>FLAC</b></th><th></th><th></th></tr>
          <tr>
            <td><div class="playTrack"></div></td><td>1</td><td>1.</td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-01.%2520Key.mp3">Key</a></td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-01.%2520Key.mp3">1:05</a></td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-01.%2520Key.mp3">1.68 MB</a></td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-01.%2520Key.mp3">2.41 MB</a></td>
            <td><a href="/game-soundtracks/album/minecraft/1-01.%2520Key.mp3"><i>get_app</i></a></td><td><i>playlist_add</i></td>
          </tr>
          <tr>
            <td></td><td>1</td><td>2.</td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-02.%2520Door.mp3">Door</a></td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/1-02.%2520Door.mp3">1:51</a></td>
            <td>3.22 MB</td><td>7.49 MB</td><td></td><td></td>
          </tr>
          <tr>
            <td></td><td>2</td><td>1.</td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/2-01.%2520Subwoofer%2520Lullaby.mp3">Subwoofer Lullaby</a></td>
            <td class="clickable-row"><a href="/game-soundtracks/album/minecraft/2-01.%2520Subwoofer%2520Lullaby.mp3">1:03:29</a></td>
            <td>5.38 MB</td><td>10.00 MB</td><td></td><td></td>
          </tr>
          <tr id="songlist_footer"><th colspan="4">Total:</th><th>6:25</th><th>10 MB</th><th>20 MB</th></tr>
        </table>
        <table><tr><td><a href="/game-soundtracks/album/other">Other album</a></td></tr></table>
        </div></body></html>
    """.trimIndent()

    private val trackHtml = """
        <html><body><div id="pageContent">
        <p>Album name: <b>Minecraft</b></p>
        <p><a href="https://files.example/soundtracks/minecraft/abcd/1-01.%20Key.mp3"><span>Click here to download as MP3</span></a></p>
        <p><a href="https://files.example/soundtracks/minecraft/efgh/1-01.%20Key.flac"><span>Click here to download as FLAC</span></a></p>
        <p><a href="https://files.example/soundtracks/minecraft/abcd/1-01.%20Key.mp3">https://files.example/…</a></p>
        <p><a href="https://downloads.khinsider.com/forums">Forums</a></p>
        </div></body></html>
    """.trimIndent()

    @Test
    fun parsesSearchRowsByHeaderName() {
        val results = KhInsiderParser.parseSearch(searchHtml)
        assertThat(results).hasSize(2)
        val first = results[0]
        assertThat(first.id).isEqualTo("zelda-one-2014")
        assertThat(first.name).isEqualTo("Zelda & Link - One")
        assertThat(first.platform).isEqualTo("CD-i")
        assertThat(first.type).isEqualTo("Arrangement")
        assertThat(first.year).isEqualTo("2014")
        assertThat(first.thumbnailUrl).isEqualTo("https://img.example/soundtracks/zelda-one-2014/thumbs_small/cover.png")
        // Relative links resolve against the site.
        assertThat(results[1].thumbnailUrl).isEqualTo("https://downloads.khinsider.com/thumbs/oot.jpg")
    }

    @Test
    fun parsesAlbumPage() {
        val album = KhInsiderParser.parseAlbum("minecraft", albumHtml)!!
        assertThat(album.name).isEqualTo("Minecraft Soundtrack - Volume Alpha")
        assertThat(album.formats).containsExactly("mp3", "flac").inOrder()
        assertThat(album.hasFlac).isTrue()
        assertThat(album.platforms).containsExactly("Android", "Windows").inOrder()
        assertThat(album.year).isEqualTo("2011")
        assertThat(album.publisher).isEqualTo("Mojang")
        assertThat(album.type).isEqualTo("Soundtrack")
        assertThat(album.coverUrl).isEqualTo("https://img.example/soundtracks/minecraft/thumbs/cover.png")
        assertThat(album.tracks.map { it.name }).containsExactly("Key", "Door", "Subwoofer Lullaby").inOrder()
        val key = album.tracks[0]
        // The encoded href is kept exactly (KHInsider double-encodes spaces).
        assertThat(key.pagePath).isEqualTo("/game-soundtracks/album/minecraft/1-01.%2520Key.mp3")
        assertThat(key.number).isEqualTo(1)
        assertThat(key.disc).isEqualTo(1)
        assertThat(key.durationSeconds).isEqualTo(65)
        assertThat(album.tracks[2].disc).isEqualTo(2)
        assertThat(album.tracks[2].durationSeconds).isEqualTo(3809)
    }

    @Test
    fun albumWithoutSongListIsNull() {
        assertThat(KhInsiderParser.parseAlbum("x", "<html><body><div id=pageContent><p>No such album</p></div></body></html>")).isNull()
    }

    @Test
    fun picksTheChosenFormatAndFallsBackToMp3() {
        val files = KhInsiderParser.parseTrackFiles(trackHtml)
        assertThat(files.keys).containsExactly("mp3", "flac").inOrder()
        assertThat(KhInsiderParser.pickFile(files, KhFormat.FLAC)).endsWith("efgh/1-01.%20Key.flac")
        assertThat(KhInsiderParser.pickFile(files, KhFormat.MP3)).endsWith("abcd/1-01.%20Key.mp3")
        val mp3Only = files.filterKeys { it == "mp3" }
        assertThat(KhInsiderParser.pickFile(mp3Only, KhFormat.FLAC)).endsWith(".mp3")
        assertThat(KhInsiderParser.pickFile(emptyMap(), KhFormat.FLAC)).isNull()
    }

    @Test
    fun playbackUriRoundTripsTheEncodedPage() {
        val page = "/game-soundtracks/album/minecraft/2-01.%2520Subwoofer%2520Lullaby.mp3"
        val uri = KhInsiderParser.playbackUri(page)
        assertThat(uri).startsWith("khinsider://track?page=")
        assertThat(KhInsiderParser.isPlaybackUri(uri)).isTrue()
        assertThat(KhInsiderParser.pagePathOf(uri)).isEqualTo(page)
        assertThat(KhInsiderParser.pagePathOf("https://example.com/a.mp3")).isNull()
        assertThat(KhInsiderParser.trackPageUrl(page)).isEqualTo("https://downloads.khinsider.com$page")
    }

    @Test
    fun recognisesAlbumRedirectsAndCloudflarePages() {
        assertThat(KhInsiderParser.isAlbumPage("https://downloads.khinsider.com/game-soundtracks/album/minecraft")).isTrue()
        assertThat(KhInsiderParser.isAlbumPage("https://downloads.khinsider.com/game-soundtracks/album/minecraft/1-01.mp3")).isFalse()
        assertThat(KhInsiderParser.isAlbumPage("https://downloads.khinsider.com/search?search=zelda")).isFalse()
        assertThat(KhInsiderParser.albumIdOf("/game-soundtracks/album/minecraft/1-01.mp3")).isEqualTo("minecraft")
        assertThat(KhInsiderParser.isCloudflareChallenge("<html><head><title>Just a moment...</title>")).isTrue()
        assertThat(KhInsiderParser.isCloudflareBlock("<title>Attention Required! | Cloudflare</title>")).isTrue()
        assertThat(KhInsiderParser.isCloudflareChallenge(albumHtml)).isFalse()
        assertThat(KhInsiderParser.searchUrl("zelda ocarina")).isEqualTo("https://downloads.khinsider.com/search?search=zelda+ocarina")
    }

    @Test
    fun parsesAListPageAndItsNextPage() {
        val html = """
            <html><body><div id="pageContent">
            <p>Found 6586 albums!</p>
            <table class="albumList">
              <tr><th></th><th>Album</th><th>Platform</th><th>Type</th><th>Year</th></tr>
              <tr><td><a href="/game-soundtracks/album/a-2021"><img src="/t/a.jpg"></a></td><td><a href="/game-soundtracks/album/a-2021">A</a></td><td>Switch</td><td>Soundtrack</td><td>2021</td></tr>
            </table>
            <a href="https://downloads.khinsider.com/game-soundtracks/nintendo-switch?page=2">2</a>
            </div></body></html>
        """.trimIndent()
        val page = KhInsiderParser.parseAlbumPage(html, 1)
        assertThat(page.albums.map { it.id }).containsExactly("a-2021")
        assertThat(page.hasNext).isTrue()
        assertThat(KhInsiderParser.parseAlbumPage(html, 2).hasNext).isFalse()
    }

    @Test
    fun parsesTop40WithRankColumn() {
        val html = """
            <table><tr><th></th><th>#</th><th>Album</th><th></th></tr>
            <tr><td><a href="https://downloads.khinsider.com/game-soundtracks/album/minecraft"><img src="https://img.example/m.png"></a></td>
            <td>1.</td><td><a href="https://downloads.khinsider.com/game-soundtracks/album/minecraft">Minecraft (2011)</a></td><td><i>remove</i></td></tr></table>
        """.trimIndent()
        val albums = KhInsiderParser.parseSearch(html)
        assertThat(albums).hasSize(1)
        assertThat(albums[0].name).isEqualTo("Minecraft (2011)")
        assertThat(albums[0].thumbnailUrl).isEqualTo("https://img.example/m.png")
    }

    @Test
    fun parsesPlatformListSkippingNonPlatformLinks() {
        val html = """
            <div id="pageContent">
              <a href="/game-soundtracks/nintendo-switch">Switch</a>
              <a href="/game-soundtracks/pc-9801">PC-98</a>
              <a href="/game-soundtracks/album/minecraft">Minecraft</a>
              <a href="/game-soundtracks/year/2024">2024</a>
              <a href="/game-soundtracks/ost">Soundtracks</a>
              <a href="/forums">Forums</a>
            </div>
        """.trimIndent()
        assertThat(KhInsiderParser.parsePlatforms(html))
            .containsExactly("nintendo-switch" to "Switch", "pc-9801" to "PC-98").inOrder()
    }

    @Test
    fun buildsListUrls() {
        assertThat(KhInsiderParser.listUrl("/top40", 1)).isEqualTo("https://downloads.khinsider.com/top40")
        assertThat(KhInsiderParser.listUrl("/game-soundtracks/year/2024", 3))
            .isEqualTo("https://downloads.khinsider.com/game-soundtracks/year/2024?page=3")
    }
}
