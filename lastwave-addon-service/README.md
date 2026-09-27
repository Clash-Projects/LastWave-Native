# LastWave Addons

Your personal addon URL for LastWave.

Sign in with Telegram or Discord, prove you joined the channel, and get a private URL that unlocks **500 songs a day** in your LastWave app:

```
{BASE_URL}/a/<64-hex-token>/
{BASE_URL}/a/<token>/manifest.json
{BASE_URL}/a/<token>/search?q=…
{BASE_URL}/a/<token>/stream/<id>
{BASE_URL}/a/<token>/media/<id>.mpd   (audio)
```

Paste the URL into LastWave → Sources → Add addon. Keep the URL somewhere safe —
the dashboard offers one-tap copy plus a downloadable backup file. Anyone
holding the URL plays on your quota; if it leaks, regenerate it.

## How it works

1. **Create an account** — pick a username and password on the site. That's your
   sign-in from now on.
2. **Verify** — the site hands you a short 6-letter code (valid 10 minutes).
   Send it to our Telegram bot, or run `/verify CODE` in Discord. The bot
   checks you joined the channel/server, then approves the code. Type the same
   code back on the site and your account is linked.
3. **Confirm** — your private addon URL is ready. Only verified accounts get one.

Forgot your password? Type your username on the reset page — the server checks
which bot you verified with and DMs you a reset code there (Telegram DMs or
Discord DMs, never email). Enter it with a new password and you're back in.

## Rules enforced

- Real Telegram/Discord accounts only. Identity comes from the bot-seen
  `message.from` / Discord user object — never self-declared.
  - Telegram: must join `TELEGRAM_REQUIRED_CHANNEL`. Not joined → the bot
    replies with the join link; join and send the code again. No age requirement.
  - Discord: account must be **≥ 30 days old** and a member of `DISCORD_GUILD_ID`.
    Too young → rejected. Not joined → the bot replies with the invite link;
    join and run `/verify` again.
- **500 songs a day per account** (resets 00:00 UTC). Only successful plays
  count — never per IP or per device. When the limit is hit, plays answer
  `429 + Retry-After` until reset.
- Addon URLs are random 256-bit tokens tied to one account. Revoked URLs answer
  `404` everywhere, so they stop working immediately.
- API keys and bot tokens stay server-side; they never appear in addon URLs.

## One-way lock: your app only

Any addon-format URL works in LastWave — but LastWave addon URLs work in
**nothing else**. The server demands proof of the app-embedded key on every
manifest/search/stream call; stock players never send it, so pasting our URL
anywhere else fails as “no addon here”.

How the proof works (the key itself never travels):

- The APK holds a secret (obfuscate it: split it, build it at runtime,
  ideally assemble it in native code — never a plain `const` in git).
- Each request sends two headers:
  `X-LW-TS` = unix seconds, `X-LW-Sign` = hex
  `HMAC-SHA256(secret, "ts\nMETHOD\npath\ntoken")`.
- The server accepts timestamps within ±120s and compares in constant time.
  Missing/stale/wrong proof → `404`, identical to a dead URL (no oracle).
- The audio player fetches with no headers, so `/stream` mints media links
  with their own non-expiring signature (`exp`+`sig`, bound to
  token+track+quality). Only an authenticated `/stream` call can mint one.

Set `ADDON_CLIENT_SECRET` (comma-separated list allowed, `"old,new"`, so key
rotations overlap). Empty = lock OFF — the admin console warns loudly.

App-side integration (OkHttp interceptor — drop into whatever client fetches
addon URLs):

```kotlin
fun signedRequest(secret: ByteArray, method: String, path: String): Request {
  val ts = (System.currentTimeMillis() / 1000).toString()
  val payload = "$ts\n${method.uppercase()}\n$path\n${extractToken(path)}"
  val mac = Mac.getInstance("HmacSHA256").apply {
    init(SecretKeySpec(secret, "HmacSHA256"))
  }
  val sign = mac.doFinal(payload.toByteArray()).joinToString("") { "%02x".format(it) }
  return Request.Builder().url(BASE + path)
    .header("X-LW-TS", ts)
    .header("X-LW-Sign", sign)
    .build()
}
// extractToken: the segment after "/a/" up to the next "/" ("" if absent).
// `path` must be the exact request path the server sees (no query string).
```

Honest limits: this stops every stock player, every pasted URL, and all casual
curl use. It does **not** stop someone who decompiles your APK, lifts the key,
and forges proofs — no client-side secret can. That residual risk is why the
 500/day per-URL quota, non-expiring but HMAC-bound media links, and one-click revoke exist: a
forged client still only gets a trickle before you see it and kill the URL.

## Quick start

```bash
cd lastwave-addon-service
cp .env.example .env   # fill in BASE_URL, SESSION_SECRET, ADMIN_TOKEN, bot tokens
npm install
npm start              # http://localhost:8787
```

Required env: `BASE_URL` (public HTTPS URL in production),
`SESSION_SECRET`, `ADMIN_TOKEN`, `UPSTREAM_BASE_URL`, `UPSTREAM_API_KEY`.

### Telegram bot setup

1. Talk to `@BotFather` → `/newbot` → note bot username + token.
2. Add the bot as an **admin** of your channel (required for membership checks —
   no permissions needed, just admin status).
3. Set `TELEGRAM_BOT_USERNAME` + `TELEGRAM_BOT_TOKEN` + `TELEGRAM_REQUIRED_CHANNEL`
   (`@yourchannel` or `-100…` id) + optionally `TELEGRAM_INVITE_URL`.
4. Start the service — it long-polls `getUpdates`. Send `/start` to the bot to
   confirm it's alive.

### Discord bot setup

1. <https://discord.com/developers/applications> → New Application → Bot →
   Reset Token → copy to `DISCORD_BOT_TOKEN`.
2. Bot → Privileged Gateway Intents → enable **Server Members Intent**.
3. OAuth2 → URL Generator → scopes `bot` + `applications.commands` → open the
   URL and add the bot to your server.
4. Right-click the server (Developer Mode) → Copy Server ID → `DISCORD_GUILD_ID`.
   Set `DISCORD_INVITE_URL` (e.g. `https://discord.gg/xxxx`).
5. Start the service — it logs in and self-registers the `/verify` slash
   command. Type `/verify` in the server to confirm.

### Admin console

Open `/admin`, sign in with `ADMIN_TOKEN`. You get:

- live totals: accounts, active URLs, plays today, accounts near the limit,
- a watchlist of accounts at 450+ plays today,
- every account with site username, identity, copyable @mention, full addon URL,
  URL status, used/left today, search filter,
- per-account detail: quota, URLs (copyable), 30-day history, recent plays,
  verification codes, revoke / regenerate / delete,
- a one-click **Revoke all URLs** danger zone that kills every active URL at once,
- a code log showing every issued verification code and its status.

JSON API: `GET /admin/api/users` (admin session cookie required).

## Deploy

Any Node 18+ host works. Serve over HTTPS in production. Example (Docker):

```bash
docker build -t lastwave-addons .
docker run -p 8787:8787 --env-file .env -v ./data:/app/data lastwave-addons
```

SQLite lives in `./data/addon.db` (WAL). Back it up to preserve accounts/quota.

## Layout

```
src/index.js      app + mounting + bot startup
src/config.js     env
src/db.js         SQLite (users, tokens, daily_usage, stream_logs, sessions, verification_codes)
src/auth.js       legacy sign-in + sessions
src/verify.js     shared verification helpers (codes, account age, membership)
src/bots/telegram.js  Telegram bot (code DM + channel gate)
src/bots/discord.js   Discord bot (/verify + server gate + 30-day age gate)
src/catalog.js    addon response mapping (manifest/search/stream, quality, atmos)
src/upstream.js   catalogue backend client (server-side key)
src/quota.js      500/day per-account gate
src/html.js       page shell + shared fragments
src/routes/web.js    home / sign in / dashboard
src/routes/verify.js code issue -> bot check -> confirm
src/routes/addon.js  /a/:token/* addon endpoints
src/routes/admin.js  admin console + JSON API
public/           stylesheet + interactions (no build step)
```

## Security notes

- `x-powered-by` off, `httpOnly + signed + SameSite` cookies, `Secure` in production.
- Addon tokens are validated against a strict pattern; server logs never print
  full tokens.
- Verification codes are 6 letters from a 32-character set (~1B combos),
  expire in 10 minutes, and are single-use.
