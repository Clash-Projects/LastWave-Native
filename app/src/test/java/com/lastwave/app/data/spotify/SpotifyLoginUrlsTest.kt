package com.lastwave.app.data.spotify

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * The `sp_dc` cookie is issued by Spotify's authentication response, so it
 * must only be read once the sign-in actually completed. Reading it on the
 * login page captures an anonymous session and the WebView latches onto a
 * cookie that can never produce a personal access token.
 */
class SpotifyLoginUrlsTest {

    @Test
    fun `the accounts status page is a post-login page`() {
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/status")).isTrue()
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/status")).isTrue()
    }

    @Test
    fun `the status page still matches when a flow context is appended`() {
        assertThat(
            SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/status?flow_ctx=abc123"),
        ).isTrue()
    }

    @Test
    fun `the login page is not a post-login page`() {
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/login")).isFalse()
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/login")).isFalse()
        assertThat(
            SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/login?continue=%2F"),
        ).isFalse()
    }

    @Test
    fun `sign up and password reset pages are not post-login pages`() {
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/signup")).isFalse()
        assertThat(SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/signup")).isFalse()
        assertThat(
            SpotifyLoginUrls.isPostAuth("https://accounts.spotify.com/en/password-reset"),
        ).isFalse()
    }

    @Test
    fun `the spotify web player is not a post-login page`() {
        assertThat(SpotifyLoginUrls.isPostAuth("https://open.spotify.com/")).isFalse()
        assertThat(SpotifyLoginUrls.isPostAuth("https://open.spotify.com/search")).isFalse()
    }

    @Test
    fun `a missing url is not a post-login page`() {
        assertThat(SpotifyLoginUrls.isPostAuth(null)).isFalse()
        assertThat(SpotifyLoginUrls.isPostAuth("")).isFalse()
    }

    @Test
    fun `the login url has no continue parameter so the flow ends on the status page`() {
        assertThat(SpotifyLoginUrls.LOGIN_URL).isEqualTo("https://accounts.spotify.com/en/login")
        assertThat(SpotifyLoginUrls.LOGIN_URL).doesNotContain("continue")
    }
}
