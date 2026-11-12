# Spotify session and Canvas — source review, 2026-10-21

The Spotify integration has two halves that have to work together before a track can
carry a canvas: a session (a cookie plus the tokens minted from it) and the lookup that
turns a playing track into a canvas URL. Both were reworked in this pass. Nothing here
changes a call-site signature.

## Session

### The personal token

`SpotifyAuthRepository.getPersonalToken()` asks for a token twice, with different reasons.

- `transport` is asked for first. It is the reason the web player itself uses and it
  normally answers with a full session token.
- `init` is the fallback, for when `transport` answers with a short placeholder instead.

An answer is only taken when it is usable: not anonymous, and not shorter than
`MIN_PERSONAL_TOKEN_LENGTH`. A short answer and a blank one are both "no token", but only
one of them looks like a token at a glance, which is why the length is looked at and not
only the emptiness.

An answer that says the session is anonymous means the stored cookie is no longer a
signed-in session. The connection is dropped rather than cached: an anonymous token is a
guest session, and every canvas and lyrics request made with it answers with nothing
while looking, from the outside, exactly like a track that has no canvas.

The server-time and token requests carry `Origin`, `Referer`, `App-platform` and
`Spotify-App-Version` alongside the cookie. The client-token request describes one
browser, and the user agent it sends is that same browser: a profile that says "macos" in
the body and "Windows" in the header is two different machines.

### The client token

The profile the client-token endpoint is asked about describes a single browser: a brand,
a model, an operating system, a device type and a device id. The device id is generated
per install and kept (`spotify_device_id`), so two installs do not present as one device.

### The cookie

`sp_dc` is read from the WebView's cookie jar on the page the sign-in finishes on. Two
things were wrong with how that was done:

- The jar was wiped in a `LaunchedEffect`, which runs after the first composition — after
  the WebView had already been created and had already started loading the login page. The
  wipe therefore raced the response that sets the session cookies. It now happens in the
  WebView factory, immediately before the page is requested.
- The capture was attempted only when a page finished loading. The accounts page is a
  single-page app, so a sign-in can complete without a new document being loaded and the
  landing page reached without a page-finish either. The capture is now also attempted on
  every history entry.

A cookie that is already stored is not stored again, so the toast is wanted once; a cookie
that differs from the stored one is a newer session and replaces it. The jar is no longer
wiped after a capture: the wipe at the start of the next attempt is what keeps one
attempt's session from being picked up as if it were a new sign-in.

### The sign-in checks the session

Storing the cookie is not the same as having a session. The cookie is only worth
anything if Spotify will exchange it for a token, and it will not always: a cookie that
has expired, or one from a session Spotify has since ended, is answered with a guest
token that reads as a session but is not one.

The sign-in now exchanges the cookie for a token before it says anything, and the three
outcomes are told apart:

- **Connected** — the exchange worked.
- **Signed in, but Spotify refused the session** — the cookie was stored, the exchange
  was refused, so the cookie is dropped again and the user is told to sign in again or
  paste a fresh one.
- **Couldn't save the session** — the write did not land, and the dialog stays open so
  the value can be corrected.

Before this, the sign-in reported success the moment it had a cookie, so a session that
could not be used looked exactly like one that could — and every later lookup failed
quietly. That is the whole of why this feature looked broken rather than unfinished.

## The canvas lookup

### The route

The canvas is now asked for the way the current web player asks for it: the `canvas`
operation on Pathfinder v2, in `data/canvas/SpotifyCanvasQuery.kt`. The protobuf
`canvaz-cache` endpoint is kept as the fallback and is asked for when the query answers
with no canvas or fails outright.

The operation is named by a persisted-query hash that changes whenever the web player is
rebuilt. The live hash is read out of the web player's own script bundles and remembered
for twelve hours; a lookup that finds nothing is not retried for thirty minutes, and the
last known good hash is used in the meantime. A retired hash is reported by the endpoint
and the hash is looked up again once before the query is given up on.

The protobuf answer can carry a clip for another track as well, so the clip for the track
that was asked about is taken first, and only the first clip with a url is used when there
is no exact match.

### The match

The answer used to skip the check that the clip belongs to the track that is playing: a
canvas from Spotify was accepted whatever it turned out to be. It is now checked like every
other provider's answer.

The title is compared leniently, because the same release is packaged differently by every
service: a title that only adds packaging words ("remastered", "deluxe", "live"), a
credit ("feat") or a year to the other one is the same recording. A title that differs in
any other way is a different song, and a canvas for the wrong song is worse than no canvas
at all. "Love" and "Love Story" share their first word and are not the same song.

Every credited artist is carried on the answer, not only the first: a collaboration is
only credited when all of its artists are accounted for.

## Why no canvas was being found

The lookup has four gates, and every one of them turns a refusal into "no canvas for this
track". That is what made the feature look like it simply never finds anything: the app
said nothing, and the only sign was a logcat line that read the same either way.

The endpoints were probed directly to find out what each gate answers:

| Gate | Endpoint | Answer with a bad session |
| --- | --- | --- |
| client token | `clienttoken.spotify.com/v1/clienttoken` | **200, granted** — not the cause |
| personal token | `open.spotify.com/api/token` | **400** "Unauthorized request" |
| track search | `api-partner.spotify.com/pathfinder/v1/query` | **401** "Missing/invalid/expired access token" |
| canvas | `spclient.wg.spotify.com/canvaz-cache/v0/canvases` | **401**, empty body |

The real cause is further up, and it is not a refusal at all: **the search was asking for
an operation the web player no longer has.**

Every GraphQL operation on Spotify is named by a persisted-query hash, and the hash
changes when the operation is rebuilt. The search was asking for `searchTracks` with the
hash `bc1ca2fc…`. The web player that Spotify serves today declares 61 operations, and
`searchTracks` is not one of them — not even by name. The operation that replaced it is
`assistedCurationSearch`, with a different hash and with the term it takes renamed from
`searchTerm` to `term`.

A retired operation is refused with "persisted query not found". The app saw that, logged
"Pathfinder track search failed", and turned it into "no Spotify track matched" — which
is the same thing the app says for a track that genuinely is not on Spotify. There was
never a track id, so there was never a canvas, on any track, in any build, whatever the
session looked like.

The one-time-password secret was a second copy of the same problem. It was read from a
published file, and the process that writes that file had stopped working:

```
No real secrets with version.
No changes to commit
```

The action still ran, and still reported success, and stopped publishing anything. The
file froze at version 61 in January. The secret is the same one the player is using today,
so the copy was not wrong yet — but it was one rebuild away from being wrong, with nothing
in the app to notice.

So the fix reads both of them out of the web player, which is the thing that actually
moves:

- **The hashes.** The player's own script declares every operation it uses, so the live
  hash for the search and for the canvas is read from there. A rebuild changes them and
  the next lookup picks the new ones up.
- **The secret.** The player carries the one-time-password secrets in the same script, so
  the current one is read from there too. The published copy is kept only as a fallback
  for when the player cannot be read.

Both live in `data/spotify/SpotifyWebPlayer.kt`, which reads the player's page, finds its
bundles and keeps what it needs for a few hours.

The search now asks for `assistedCurationSearch` with the term it wants, tries both
pathfinder versions, and keeps the Web API search as the fallback it already was. The
response shape did not change — the player answers with `searchV2.tracksV2.items[].item.data`,
which is what the app was already reading.

The failures now name themselves: a refused session says so, in the log, next to the status
code, and a search that finds nothing says that too.

## The provider order

Spotify used to be asked first, and is now asked last by default. It is the only source
that needs an account and the only one that can be refused, and the other three between
them cover most of what it would have answered — so asking it first means every track
pays for a request that may be refused before the sources that always answer are
reached.

"Prefer Spotify Canvas" (Settings → Spotify) puts it back in front. The two orders answer
different questions — "the best clip" and "Spotify's clip" are not the same answer — so
the answers already gathered are dropped when the order changes rather than reused.

## Where the source is shown

The provider a clip came from is shown in the song's details, in the "Stream & Audio
Specs" card next to the bitrate, the provider and the duration. It used to be shown twice:
there, and as a row of its own in the three-dot menu. The row is gone; the details are
where the other technical facts about the track live, and the menu row was a second,
separately-resolved copy of the same value.

## Where the settings live

Spotify has a section of its own, "Spotify", reached from Settings → Services & Add-ons,
the same way Last.fm and YouTube have one. It used to be a group at the bottom of the
YouTube section, which made it hard to find and made the YouTube section's name a lie.

The section holds everything about Spotify in one place:

- the account, and whether it is connected
- your Spotify playlists, imported into this app's library
- the canvas toggle
- the "prefer Spotify Canvas" toggle

The playlist import was already there — it reads the signed-in account's playlists
through the Web API, and falls back to the public embed page — but it was only reachable
from Library & Content → Imports, on a screen shared with Apple Music. From here it is
where a Spotify user would look for it.

## What a like does

A like goes to two places and no others: this app's own Liked Songs playlist, and
YouTube Music. Nothing is pushed to Last.fm or Spotify any more.

- **Last.fm**: the heart no longer marks a track as "loved" there. Scrobbling, the
  listened-stats, the API-key settings and the Last.fm section all stay as they were.
- **Spotify**: the heart no longer touches the Spotify library. The endpoints that
  liked and unliked a track there are gone.

Two switches control the two halves, in Settings → YouTube & Sync → Liked Songs:

| Switch | Default | Off means |
| --- | --- | --- |
| **Save to Liked Songs** | on | The local Liked Local Song playlist is left alone. The like is still remembered by the app, so the heart still fills — but the song is not added to the list. |
| **Sync Liked Songs to YouTube Music** | on | The like is not marked on YouTube Music. With no YouTube account connected there is nowhere to mirror to anyway, so the like stays in this app. |

Switching the local half off moves what is in the playlist into the app's own record, so
the heart does not lose the likes it already had; switching it back on puts them into the
playlist again. Importing YouTube Music's Liked Music follows the same switch: with the
local list off, the imported tracks go into the app's own record rather than quietly
filling a list the user switched off.

### The two lists keep their own names

They are two different lists, and they keep their own names: **"Liked Local Song"** is
this app's own, **"Liked Yt Song"** is the account's. The account's list used to be
hidden from the library, which made the account look like it had no liked playlist of its
own. Both are shown now.

The account's list is shown as it is — streamed from YouTube when it is opened, the same
way YouTube Music shows it — and is never copied into this app. Merging it into the
local list would mean paging through every song the account has ever liked, keeping a
second copy of all of them, and never dropping the ones that go away on the other side.
What the app's own likes reach YouTube Music is the direct like, not a copy of the list.

The consequence, which is the same one every YouTube Music client has: a song liked only
in the YouTube Music app is in "Liked Yt Song" and not in "Liked Local Song", so the
app's heart for it is unfilled.

## Not verified here

No Gradle invocation, build, test run or on-device run was performed. The unit tests
alongside these sources (`SpotifyCanvasQueryTest`, `CanvasMatchingTest`) cover the pure
parts — the answer parsing, the hash lookup and the match — but they were written, not
executed. Whether the endpoints answer with what is expected here is a question for a
device with a signed-in session.

The endpoint probes above were made without a session, so they show what a refusal looks
like rather than what a working request looks like. A signed-in session is still needed
to confirm the personal token is minted and honoured.
