# Persistent Related-Track Graph — Design Spec

Date: 2026-10-16
Status: Draft — awaiting user review
Scope: `com.lastwave.app` (Android, Kotlin, Room, Hilt). No UI changes, no new Gradle dependencies.

> Compile targets: default `minSdk = 29` with a legacy `-PminSdk=24` CI build
> (`.github/workflows/build.yml`). All new code must compile and run against
> API 24. The user has directed that Gradle is not run locally; CI is the
> compile gate.

---

## 1. Problem

Every related-track retrieval in the app is live-only. `InnerTubeMusicApi.fetchRelatedSongs()`
(`InnerTubeMusicApi.kt:1740`) is called fresh from 14 sites across 7 consumer files (feed,
discover, generation, radio queue, guest engine, search, artist pages), and nothing is
remembered between calls. Consequences:

- **Network-bound refills.** A Discover refill fans out to many network fetches
  (`DiscoverRepository.refillQueue`, `GenerateRepository.fetchSimilarTracks`), each of which
  internally performs YouTube radio and Last.fm requests. Warm data would serve in
  milliseconds; instead every refill pays full network latency.
- **Queue stalls.** `MusicPlayer.extendRadioQueueIfNeeded` fetches related for the next seed
  on demand; at the end of a queue the refill latency is user-visible.
- **Zero resilience.** On timeout or throttling a seed contributes no candidates; the Discover
  scorer then relaxes its caps (`selectFinal` progressive relaxation) and quality silently degrades.
- **Starved modes.** With no YouTube account connected, Discover loses its two highest-weight
  supply families — `yt-new` (94, gated on `isYtConnected`, `DiscoverRepository.kt:172-187`)
  and `yt-feed` (92, requires `ytMusicFeedRaw`, `:192-195`) — plus track seeds at weights
  78/72/68. Last.fm-only and guest users are candidate-starved even though the anonymous
  related endpoint works with no account.
- **No cross-time signal.** The existing consensus terms (`DiscoverRepository` `sources.size`,
  `RecommendationEngine` `sourceCount`) only count co-occurrence within a single refill batch.
  A track confirmed by many different seeds across weeks ranks the same as one that happened
  to appear four times in one batch.

## 2. Goals

1. **Persist** every related-fetch result as graph edges; serve as a **cache-first layer with
   background refresh** (stale-while-revalidate).
2. **Add a cross-time referral signal** to scoring: bounded, time-decayed, additive.
3. **Resilience:** cached candidates survive network failures and offline use for seeds that
   have been fetched before.
4. **Speed:** warm related lookups become Room reads; Discover refills and radio extensions
   become near-instant and never stall at queue end.
5. **All connection modes:** identical behavior for YouTube-connected, Last.fm-only, and guest
   users (the underlying endpoint is cookie-free).
6. **Bounded storage:** decayed ranking, age pruning, hard row cap.

## 3. Non-goals

- Replacing live fetching. The network stays the source of truth; background refresh keeps the
  graph fresh. Cache-first changes *when* data is served, never *whether* it is refreshed.
- Any UI change. No new screens, settings, or strings.
- Changing search results or artist-page content freshness (those call sites force the network
  path; see §6).
- New Gradle dependencies.
- Reworking non-related recommendation inputs (profile fetches, Last.fm, YT home) — those are
  unchanged.

## 4. Architecture

New units, each with one purpose:

```
data/local/db/RelatedGraphEntity.kt      RelatedGraphEdgeEntity + RelatedGraphDao (follows
                                         RecommendationExclusionEntity.kt: entity + DAO in one file)
data/repository/RelatedGraphRepository.kt @Singleton; the single cache-first wrapper around
                                         InnerTubeMusicApi.fetchRelatedSongs
data/generate/RelatedGraphScoring.kt     pure functions + constants for the referral signal
                                         (cross-surface, parallels TasteProfileProvider's placement)
di/DatabaseModule.kt                     migration13To14 + relatedGraphDao provider
```

Flow:

```
caller (feed | discover | generate | radio | guest engine)
   |
   v
RelatedGraphRepository.relatedFor(videoId, limit, forceNetwork)
   |
   +-- DAO.edgesFor(source)            // Room read, ~ms
   |
   +-- fresh & sufficient?  -> return cached; no network
   +-- stale or short?      -> return cached NOW + background refresh (persist on completion)
   +-- empty?               -> await network fetch; persist; return (today's behavior)
   +-- network failure      -> empty (cold) / cached already served (warm)
```

The wrapper persists results for **every** path that reaches the network, including
`forceNetwork` calls.

## 5. Data model

```kotlin
@Entity(
    tableName = "related_graph_edges",
    primaryKeys = ["sourceVideoId", "relatedVideoId"],
    indices = [Index("relatedVideoId"), Index("lastSeenAtMillis")],
)
data class RelatedGraphEdgeEntity(
    val sourceVideoId: String,
    val relatedVideoId: String,
    val title: String = "",
    val artist: String = "",
    val album: String? = null,
    val artworkUrl: String? = null,
    val durationSeconds: Int? = null,
    val seenCount: Int = 1,          // times a refresh re-listed this edge
    val firstSeenAtMillis: Long,
    val lastSeenAtMillis: Long,
)
```

DAO operations:

| Operation | Shape |
|---|---|
| `upsertAll(edges)` | `@Upsert` batch write |
| `edgesFor(sourceVideoId, limit)` | `WHERE sourceVideoId = :id ORDER BY seenCount DESC, lastSeenAtMillis DESC LIMIT :limit` |
| `referralStats(relatedIds)` | `SELECT relatedVideoId, COUNT(DISTINCT sourceVideoId) AS sourceCount, MAX(lastSeenAtMillis) AS lastSeenAt FROM ... WHERE relatedVideoId IN (:ids) GROUP BY relatedVideoId` |
| `topReferred(limit)` | same aggregate, no source filter, ordered by `sourceCount DESC, lastSeenAt DESC` — returns ids + counts only |
| `snapshotsFor(relatedIds)` | `SELECT * WHERE relatedVideoId IN (:ids)`; repository picks the most recently seen row per id (avoids bare-column ambiguity in a GROUP BY select) |
| `count()` | table size |
| `pruneOlderThan(cutoffMillis)` | `DELETE WHERE lastSeenAtMillis < :cutoff` |
| `trimToCap(cap)` | `DELETE WHERE rowid NOT IN (SELECT rowid ... ORDER BY lastSeenAtMillis DESC LIMIT :cap)` |

**Merge semantics.** The repository reads existing rows for the source, then for each fetched
track: existing edge → `seenCount + 1`, `lastSeenAtMillis = now`, snapshot fields updated;
new edge → inserted with `seenCount = 1`. Missing tracks are *not* deleted (another fetch may
re-list them; hygiene handles growth). The read-merge-upsert is not transactional; concurrent
double-merge is benign because in-flight refresh deduplication (§6) prevents two refreshes of
the same source from running at once.

## 6. RelatedGraphRepository

```kotlin
@Singleton
class RelatedGraphRepository @Inject constructor(
    private val innerTube: InnerTubeMusicApi,
    private val dao: RelatedGraphDao,
) {
    suspend fun relatedFor(
        videoId: String,
        limit: Int = 30,
        prefetchStreams: Boolean = false,
        forceNetwork: Boolean = false,
    ): List<YouTubeMusicTrack>
}
```

Constants: `FRESH_TTL_MS = 24h`, `PRUNE_AFTER_MS = 90d`, `MAX_EDGES = 100_000`.
Age of a source = `now - MAX(lastSeenAtMillis)` over its edges.

Decision table:

| Cache state | Action | Returned |
|---|---|---|
| fresh (age ≤ 24h), cached ≥ limit | return cache | `cached.take(limit)` |
| fresh, cached < limit | return cache; background refresh | `cached.take(limit)` |
| stale, non-empty | return cache; background refresh | `cached.take(limit)` |
| empty | await network; persist | network result |
| empty, network fails | — | `emptyList()` (today's behavior) |
| `forceNetwork = true` | await network; persist; on failure fall back to cache | network result else `cached.take(limit)` |
| `prefetchStreams = true` | network path only; cached path cannot prefetch (currently no caller requests it) | — |

Background refresh: repository-owned `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, with
an in-flight map (`ConcurrentHashMap<String, Deferred<...>>`) collapsing concurrent refreshes
of the same source — the same pattern as `GenerateRepository.call` (`GenerateRepository.kt:75-115`).
Refresh failures are swallowed and logged (existing `Log.d` convention); the cache keeps serving.

The cache path never blocks a caller longer than a Room read. The cold path is exactly today's
behavior, so this change can only move latency down.

## 7. Call-site migration

`fetchRelatedSongs` has 14 call sites across 7 consumer files (grep matches at definition and
comments excluded). All switch to `relatedGraph.relatedFor(...)` with identical arguments;
callers gain a `RelatedGraphRepository` injection (all are already Hilt-injected classes).

| File:line | Context | Mode |
|---|---|---|
| `SearchRepository.kt:167` | search results | `forceNetwork = true` |
| `ArtistRepository.kt:238` | artist page | `forceNetwork = true` |
| `FeedViewModel.kt:257, 286` | feed sections | cache-first |
| `FeedRepository.kt:270, 686` | discovery blend, "Because you listen to X" | cache-first |
| `GenerateRepository.kt:399, 630, 639, 774` | radio/discovery/never-heard | cache-first |
| `LocalTasteSuggestionEngine.kt:150, 180` | guest engine | cache-first |
| `MusicPlayer.kt:4896, 4911` | radio cascade | cache-first |

## 8. Scoring integration

Pure functions and constants in `data/generate/RelatedGraphScoring.kt`:

```kotlin
internal const val GRAPH_DECAY_TAU_DAYS = 30.0

internal data class ReferralPreset(val perSource: Double, val sourceCap: Int, val maxBonus: Double)
internal val ENGINE_REFERRAL   = ReferralPreset(perSource = 2.0, sourceCap = 8, maxBonus = 16.0)
internal val DISCOVER_REFERRAL = ReferralPreset(perSource = 3.0, sourceCap = 4, maxBonus = 12.0)
internal val FEED_REFERRAL     = ReferralPreset(perSource = 1.5, sourceCap = 4, maxBonus = 6.0)

internal fun referralBonus(
    sourceCount: Int,
    lastSeenAtMillis: Long,
    nowMillis: Long,
    preset: ReferralPreset,
): Double {
    if (sourceCount <= 0) return 0.0
    val ageDays = (nowMillis - lastSeenAtMillis).coerceAtLeast(0L) / 86_400_000.0
    return (minOf(sourceCount, preset.sourceCap) * preset.perSource * exp(-ageDays / GRAPH_DECAY_TAU_DAYS))
        .coerceAtMost(preset.maxBonus)
}
```

Integration, per surface:

- **Discover** (`DiscoverRepository` ranking loop): one bulk `referralStats(candidateIds)`
  before scoring; add `referralBonus(..., DISCOVER_REFERRAL)` to `finalScore`. This is a
  cross-time complement to the existing same-batch `consensus` term, which stays unchanged.
- **Recommendation engine** (`RecommendationEngine.recoScore`): one bulk stats query after the
  candidate pool is built; bonus enters `recoScore` as an added term via a `referralBonus`
  parameter passed through the existing scoring call path.
- **Home feed** (`FeedRepository.trackScore`): one bulk stats query for the combined pools;
  bonus added inside `trackScore` (the ±35 jitter already dominates ordering noise; this
  term is deliberately small).
- **Radio queue:** supply-side only — no scoring change. The cache-first layer is the whole win.
- **Empty-graph neutrality:** `sourceCount = 0` yields `0.0`, so with no persisted edges every
  surface scores exactly as today. A test asserts this against the pre-change formulas.

## 9. Graph-consensus seed family (Discover)

Add one unconditional `SourceBatch` to `DiscoverRepository.refillQueue`:

- source name `graph`, weight **56** (between frontier 58 and local feed 48)
- up to 30 tracks from `topReferred(30)` + `snapshotsFor(...)`
- works for every connection mode, including Last.fm-only and guest
- candidates flow through all existing filters (exclusions, artist cap 3, saved penalty,
  jitter) like any other batch

This is the family that backfills the supply lost when `yt-new` (94) and `yt-feed` (92) are
unavailable without a YouTube account.

## 10. Hygiene

- Ranking decay is handled by `referralBonus` (τ = 30 days).
- Storage: after each merge batch, `if (dao.count() > MAX_EDGES)` → `pruneOlderThan(now - 90d)`
  then `trimToCap(MAX_EDGES)`.
- One `pruneOlderThan` also runs on first repository use per process.
- Downstream purpose unchanged: snapshots are candidate material only; `filterPlayable`,
  exclusions, skip bans, junk-title and version filters are applied exactly as today, and are
  never bypassed for graph-served tracks.

## 11. Room migration and DI wiring

- `AppDatabase.kt`: version 13 → 14; add `RelatedGraphEdgeEntity` to `entities`; add
  `abstract fun relatedGraphDao(): RelatedGraphDao`.
- `DatabaseModule.kt`: `migration13To14` creates the table and both indices
  (`CREATE TABLE IF NOT EXISTS ...`, `CREATE INDEX IF NOT EXISTS ...`), added to
  `addMigrations` alongside the existing 12→13 entry. `fallbackToDestructiveMigration`
  remains in place unchanged.
- `DatabaseModule.kt`: `@Provides @Singleton fun provideRelatedGraphDao(database: AppDatabase)`,
  matching the existing DAO providers.

## 12. Performance expectations

| Path | Today | With graph (warm) |
|---|---|---|
| Home first paint | ~10 parallel calls | neutral to −0.3–1s; only if related fetches were the critical path (profile fetches dominate) |
| Discover batch refill | seconds (fans out to 10+ network fetches) | one Room query, ~ms |
| Radio refill at queue end | network-bound, stall risk | instant when warm; no audible gap |
| Cold install | baseline | identical (empty graph = today) |
| Write cost | — | one batched upsert per fetch (≤75 rows), negligible |
| Storage | — | bounded by MAX_EDGES = 100k rows |

## 13. Risks

| Risk | Mitigation |
|---|---|
| Echo chamber / stale repeats from cache-first | Background refresh always runs on stale/short hits; downstream jitter, previous-load penalties, seen-key filters, and exclusions are unchanged. The graph only supplies candidates; it never bypasses ranking or filters. |
| Rich-get-richer amplification (referred tracks get played, their seeds refetched, count grows) | `referralBonus` caps + 30-day decay; skip bans and exclusions still remove tracks permanently. |
| Table growth | Hard cap 100k rows, prune >90 days, checked after merge batches. |
| Stale snapshot metadata (renamed/removed videos) | Snapshots refresh on every background refresh; `filterPlayable` and downstream validation drop unplayable results. |
| Cache-first subtly reducing radio variety | Radio already filters by `seen videoIds`/keys and `radioUsedSeeds`; background refresh feeds the *next* extension with fresh candidates. |
| Double-scoring confusion (graph bonus + existing consensus) | Both terms are separate, capped, and defined in the single constants file; tuned in one place. |
| Hilt wiring or migration mistakes | CI compile gate (`:app:testDebugUnitTest`, release assemble, `-PminSdk=24` assemble); migration follows the exact 12→13 table-creation pattern. |

## 14. Testing

No local Gradle (standing user direction). CI is the compile and execution gate. Tests:

- **Pure JVM** (`app/src/test/.../data/generate/RelatedGraphScoringTest.kt`): bonus bounds
  (never exceeds `maxBonus`), monotonicity in sourceCount, decay behavior over 0/30/90 days,
  `sourceCount = 0 → 0.0`, clock-skew (`lastSeen > now`) clamped.
- **Pure JVM** (`RelatedCachePolicyTest.kt`): freshness/refresh decision logic extracted into
  pure helpers (`isFresh(ageMs)`, `needsBackgroundRefresh(ageMs, cachedCount, limit)`),
  tested at TTL boundaries.
- **Robolectric** (already a test dependency, `robolectric 4.12.2`): DAO merge increments
  `seenCount` and updates `lastSeenAtMillis`; `referralStats` counts distinct sources;
  `topReferred` ordering; prune and trim remove exactly the right rows.
- **Neutrality regression:** with an empty graph, engine/discover/feed scores are identical
  to the pre-change formulas (guards against cold-start regressions).
- **Migration test** for 13→14 (table + indices exist after migration).

## 15. Delivery — 2 stacked PRs

Both target `main`; each branch contains the previous, so CI runs on each.

| PR | Branch | Contents |
|---|---|---|
| 1 | `feat/related-graph-cache` | entity + DAO, migration 13→14, DI wiring, `RelatedGraphRepository`, all 14 call-site switches, cache-policy + DAO tests. Behavior change: candidate supply only (faster, steadier); zero scoring changes. |
| 2 | `feat/related-graph-scoring` | `RelatedGraphScoring` presets, engine/discover/feed integration, `graph` seed family (weight 56), neutrality regression tests. |

PR 1 is intentionally behavior-neutral for ranking so the two concerns can be reviewed and
reverted independently.
