package com.lastwave.app.data.spotify

/**
 * Which Spotify accounts pages count as "the sign-in finished".
 *
 * The `sp_dc` cookie is issued by Spotify's *authentication* response, so it
 * only exists once the user has actually signed in. Reading it on the login
 * page captures an anonymous session, and the WebView then latches onto a
 * cookie that can never produce a personal access token.
 *
 * The canonical landing page is the accounts status page. Some flows append a
 * query string (`/en/status?flow_ctx=…`), so an anchor that stops right
 * after "status" silently never matches.
 */
internal object SpotifyLoginUrls {

    /** Post-login landing page. Optional locale segment and query string. */
    val STATUS_URL: Regex = Regex("^https://accounts\\.spotify\\.com/(?:[^/]+/)?status(?:\\?.*)?$")

    /** Pages an anonymous visitor can reach — the session is not established here. */
    val ANONYMOUS_URL: Regex = Regex(
        "^https://accounts\\.spotify\\.com/(?:[^/]+/)?(?:login|signup|sign-up|reset-password|password-reset)(?:[/?#].*)?$",
    )

    /**
     * The bare, localized login entry point.
     *
     * No `continue` parameter on purpose: with one, Spotify sends the browser
     * to the Spotify web player instead of the accounts status page, and the
     * cookie is never read where it is issued.
     */
    const val LOGIN_URL: String = "https://accounts.spotify.com/en/login"

    /**
     * True for a page that can only be reached once the sign-in completed.
     *
     * The status page is the canonical one; any other accounts page that is
     * not a login/sign-up/reset page counts too, because the anonymous flow
     * never leaves those pages.
     *
     * Safe to be broader than the status page because the WebView's cookie
     * jar is wiped when the sign-in screen opens: a cookie can therefore only
     * be present if an authentication response issued it in this session.
     */
    fun isPostAuth(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        if (STATUS_URL.matches(url)) return true
        if (!url.startsWith("https://accounts.spotify.com/")) return false
        return !ANONYMOUS_URL.matches(url)
    }
}
