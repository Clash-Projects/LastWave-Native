# Persistent Related-Track Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist every related-track fetch into a local Room graph, serve it cache-first with background refresh, and add a time-decayed referral-consensus signal to Discover, the Recommendation engine, and the Home feed.

**Architecture:** One new Room table (`related_graph_edges`) written by a new `RelatedGraphRepository` that wraps `InnerTubeMusicApi.fetchRelatedSongs` with cache-first + stale-while-revalidate semantics. All 14 existing call sites switch to the wrapper. A pure scoring module adds a bounded, decayed bonus based on how many distinct seed tracks have referred a track. Two stacked PRs: cache first (behavior-neutral for ranking), scoring second.

**Tech Stack:** Kotlin, Room (13→14 migration), Hilt, JUnit4 + Truth + MockK + Robolectric + kotlinx-coroutines-test.

**Spec:** `docs/superpowers/specs/2026-10-16-related-graph-design.md`

## Global Constraints

- **No new Gradle dependencies.** JUnit 4.13.2, Truth 1.4.2, Robolectric 4.12.2, MockK 1.13.10, kotlinx-coroutines-test 1.8.1 are already present (`app/build.gradle.kts:308-312`).
- **API 24 floor.** CI builds `-PminSdk=24`; no API-gated calls.
- **Gradle is not run locally (standing user directive).** Tests are written before implementation; their first execution is CI (`:app:testDebugUnitTest`). Pure-JVM classes may be exercised locally with the cache-resident `kotlinc` + JUnitCore flow documented in `docs/superpowers/specs/2026-07-02`-lineage logging work; do not block on local execution either way. Never invoke `gradlew`.
- **Exact constants (do not retune):** `FRESH_TTL_MS = 24h`, `PRUNE_AFTER_MS = 90d`, `MAX_EDGES = 100_000`, decay `GRAPH_DECAY_TAU_DAYS = 30.0`, presets ENGINE `(2.0, 8, 16.0)`, DISCOVER `(3.0, 4, 12.0)`, FEED `(1.5, 4, 6.0)`, graph seed family weight `56` with up to `30` tracks.
- **Downstream filters are never bypassed** for graph-served tracks (exclusions, artist caps, skip bans, junk-title/version filters).
- **Branching (spec §15):** PR 1 on `feat/related-graph-cache`, PR 2 on `feat/related-graph-scoring`, both targeting `main`, stacked. The working tree contains unrelated in-progress work: **stage only the exact files named in each task (`git add <path>`), never `git add -A` / `git add .`**. If switching branches would disturb uncommitted work, ask the user first.
- **Commit style:** repo uses conventional commits (`feat(audio): ...`, `fix(yt): ...`).
- **No UI changes, no strings, no user-visible feature toggles.**

## Review Focus

Spec-implied behaviors no single task's happy-path test exercises; each is pinned to a test in the owning task:

1. **Cold install (empty graph):** every surface must behave exactly as today — no crash, no added latency, identical scores; Task 2 (empty-cache path) and Task 5 (zero-count neutrality).
2. **Warm cache + dead network:** related lookups return cached candidates; cold + dead network returns `emptyList()` and never throws into callers (radio extension runs on a playback path); Task 2 repository tests.
3. **`forceNetwork` failure:** search/artist pages fall back to cache when it exists instead of showing nothing; Task 2 repository test.
4. **Concurrent refresh storm:** rapid queue extensions requesting the same stale seed collapse into a single network refresh (no duplicate upsert races); Task 2 repository test.
5. **Clock skew and boundary ages:** `lastSeenAtMillis` in the future clamps decay to full strength, and TTL/prune boundaries are inclusive/exclusive exactly as specified; Task 5 (clamp) and Task 2 (boundary) tests.

---

### Task 1: Schema — entity, DAO, AppDatabase v14, migration, DI

**Files:**
- Create: `app/src/main/java/com/lastwave/app/data/local/db/RelatedGraphEntity.kt`
- Modify: `app/src/main/java/com/lastwave/app/data/local/db/AppDatabase.kt`
- Modify: `app/src/main/java/com/lastwave/app/di/DatabaseModule.kt`
- Test: `app/src/test/java/com/lastwave/app/data/local/db/RelatedGraphDaoTest.kt`

**Interfaces:**
- Consumes: existing `@Upsert` entity/DAO conventions (`RecommendationExclusionEntity.kt`, `SongPlayStatsEntity.kt`); migration pattern `migration12To13` (`DatabaseModule.kt:243-261`).
- Produces:
  - `RelatedGraphEdgeEntity(sourceVideoId: String, relatedVideoId: String, title: String = "", artist: String = "", album: String? = null, artworkUrl: String? = null, durationSeconds: Int? = null, seenCount: Int = 1, firstSeenAtMillis: Long, lastSeenAtMillis: Long)` — `@Entity(tableName = "related_graph_edges", primaryKeys = ["sourceVideoId", "relatedVideoId"], indices = [Index("relatedVideoId"), Index("lastSeenAtMillis")])`
  - `RelatedReferralStats(relatedVideoId: String, sourceCount: Int, lastSeenAtMillis: Long)`
  - `RelatedGraphDao` with exactly: `upsertAll(List<RelatedGraphEdgeEntity>)`, `edgesFor(sourceVideoId: String, limit: Int): List<RelatedGraphEdgeEntity>` (ORDER BY seenCount DESC, lastSeenAtMillis DESC), `lastSeenFor(sourceVideoId: String): Long?` (MAX), `referralStats(relatedVideoIds: List<String>): List<RelatedReferralStats>` (COUNT(DISTINCT sourceVideoId), GROUP BY relatedVideoId), `topReferred(limit: Int): List<RelatedReferralStats>` (same aggregate, ORDER BY sourceCount DESC, lastSeenAtMillis DESC), `snapshotsFor(relatedVideoIds: List<String>): List<RelatedGraphEdgeEntity>`, `count(): Int`, `pruneOlderThan(cutoffMillis: Long)`, `trimToCap(cap: Int)` (`DELETE ... WHERE rowid NOT IN (SELECT rowid ... ORDER BY lastSeenAtMillis DESC LIMIT :cap)`)
  - `AppDatabase` version `14` + `abstract fun relatedGraphDao(): RelatedGraphDao`
  - `DatabaseModule.provideRelatedGraphDao(database): RelatedGraphDao`

- [ ] **Step 1: Write the failing test**

`RelatedGraphDaoTest.kt` (Robolectric, in-memory Room):

```kotlin
@RunWith(RobolectricTestRunner::class)
class RelatedGraphDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: RelatedGraphDao

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.relatedGraphDao()
    }
    @After fun tearDown() = db.close()

    // test: upsertAll then edgesFor returns both edges ordered by seenCount DESC
    // test: referralStats counts DISTINCT sources: 3 edges from 2 sources for one relatedId -> sourceCount == 2
    // test: topReferred orders by sourceCount DESC then lastSeenAtMillis DESC
    // test: pruneOlderThan deletes only rows strictly older than cutoff
    // test: trimToCap keeps exactly `cap` newest rows by lastSeenAtMillis
    // test: count reflects upserts
    // test: sqlite_master contains table "related_graph_edges" and both indices
    //       (query: SELECT name FROM sqlite_master WHERE type IN ('table','index') AND name LIKE '%related_graph_edges%')
}
```

Use Truth (`assertThat`) and build the six concrete test bodies named above with distinct seeds/related ids and distinct timestamps (e.g. `now = 1_000_000L`, one edge at `now - PRUNE_AFTER_MS - 1`).

- [ ] **Step 2: Verify it fails**

Cannot run locally (Global Constraints). Expected CI/compile failure: unresolved `RelatedGraphDao`. Push is deferred to Task 4; proceed on inspection.

- [ ] **Step 3: Implement entity + DAO + database + migration + DI**

- `RelatedGraphEntity.kt`: entity, `RelatedReferralStats`, DAO exactly as the Interfaces block pins. No comments beyond the file's existing style.
- `AppDatabase.kt`: add entity to `entities`, bump `version = 14`, add `relatedGraphDao()`.
- `DatabaseModule.kt`: add `migration13To14` that runs
  `CREATE TABLE IF NOT EXISTS \`related_graph_edges\` (\`sourceVideoId\` TEXT NOT NULL, \`relatedVideoId\` TEXT NOT NULL, \`title\` TEXT NOT NULL DEFAULT '', \`artist\` TEXT NOT NULL DEFAULT '', \`album\` TEXT, \`artworkUrl\` TEXT, \`durationSeconds\` INTEGER, \`seenCount\` INTEGER NOT NULL DEFAULT 1, \`firstSeenAtMillis\` INTEGER NOT NULL, \`lastSeenAtMillis\` INTEGER NOT NULL, PRIMARY KEY(\`sourceVideoId\`, \`relatedVideoId\`))`,
  plus `CREATE INDEX IF NOT EXISTS \`index_related_graph_edges_relatedVideoId\` ON \`related_graph_edges\` (\`relatedVideoId\`)` and `..._lastSeenAtMillis ...`, append it to `addMigrations`, and add the `provideRelatedGraphDao` provider next to the other DAO providers.

- [ ] **Step 4: Verify it passes**

CI `:app:testDebugUnitTest` must show `RelatedGraphDaoTest` green. (Bundled into the Task 4 push if working offline.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/local/db/RelatedGraphEntity.kt \
        app/src/main/java/com/lastwave/app/data/local/db/AppDatabase.kt \
        app/src/main/java/com/lastwave/app/di/DatabaseModule.kt \
        app/src/test/java/com/lastwave/app/data/local/db/RelatedGraphDaoTest.kt
git commit -m "feat(reco): add related-graph edge table with cache-read DAO"
```

---

### Task 2: RelatedGraphRepository — cache-first with background refresh

**Files:**
- Create: `app/src/main/java/com/lastwave/app/data/repository/RelatedGraphRepository.kt`
- Test: `app/src/test/java/com/lastwave/app/data/repository/RelatedGraphPolicyTest.kt`
- Test: `app/src/test/java/com/lastwave/app/data/repository/RelatedGraphRepositoryTest.kt`

**Interfaces:**
- Consumes: `RelatedGraphDao`, `RelatedGraphEdgeEntity` (Task 1); `InnerTubeMusicApi.fetchRelatedSongs(videoId, limit, prefetchStreams)`; DI `CoroutineScope` binding (`AppModule.kt:18`).
- Produces:
  - `@Singleton class RelatedGraphRepository @Inject constructor(innerTube: InnerTubeMusicApi, dao: RelatedGraphDao, scope: CoroutineScope)`
  - `suspend fun relatedFor(videoId: String, limit: Int = 30, prefetchStreams: Boolean = false, forceNetwork: Boolean = false): List<YouTubeMusicTrack>`
  - `suspend fun topReferredTracks(limit: Int = 30): List<YouTubeMusicTrack>` (ids from `topReferred`, snapshots from `snapshotsFor`, most-recent row per id wins)
  - `internal const val FRESH_TTL_MS: Long = 24 * 60 * 60 * 1000L`
  - `internal const val PRUNE_AFTER_MS: Long = 90L * 24 * 60 * 60 * 1000`
  - `internal const val MAX_EDGES: Int = 100_000`
  - `internal fun isFresh(ageMs: Long, ttlMs: Long = FRESH_TTL_MS): Boolean` — `ageMs <= ttlMs`
  - `internal fun needsBackgroundRefresh(ageMs: Long, cachedCount: Int, limit: Int, ttlMs: Long = FRESH_TTL_MS): Boolean` — `!isFresh(ageMs, ttlMs) || cachedCount < limit`
  - `internal fun mergeFetchedEdges(existing: List<RelatedGraphEdgeEntity>, fetched: List<YouTubeMusicTrack>, sourceVideoId: String, nowMillis: Long): List<RelatedGraphEdgeEntity>` — re-listed edge → `seenCount + 1`, `lastSeenAtMillis = nowMillis`, snapshot fields updated; new track → `seenCount = 1`, `firstSeenAtMillis = nowMillis`; edges absent from `fetched` are not returned for deletion
  - `internal suspend fun enforceHygiene(nowMillis: Long = System.currentTimeMillis(), maxEdges: Int = MAX_EDGES)` — `pruneOlderThan(nowMillis - PRUNE_AFTER_MS)` then `trimToCap(maxEdges)` when `count() > maxEdges`

Behavior contract (spec §6 decision table): fresh & `cached >= limit` → cache, no network. Fresh & short, or stale & non-empty → return cache now + background refresh in `scope`. Empty → await network, persist, return; on exception return `emptyList()`. `forceNetwork` → await network, persist, return; on exception fall back to cache. Persist also on every awaited network path. Background refresh failures are swallowed (log `Log.d`).

- [ ] **Step 1: Write the failing pure tests**

`RelatedGraphPolicyTest.kt` (plain JUnit, no Android):

```kotlin
// isFresh: age == FRESH_TTL_MS is fresh; age == FRESH_TTL_MS + 1 is not
// needsBackgroundRefresh: fresh + short (cachedCount < limit) == true; fresh + sufficient == false; stale == true
// mergeFetchedEdges: existing edge re-listed -> seenCount+1, lastSeenAt updated, firstSeen preserved
// mergeFetchedEdges: new track -> seenCount 1, firstSeen == lastSeen == now
// mergeFetchedEdges: existing edge NOT in fetched stays untouched (absent from result's changes)
```

`RelatedGraphRepositoryTest.kt` (Robolectric + MockK + coroutines-test), with real in-memory Room, `mockk<InnerTubeMusicApi>(relaxed = true)`, and the test's own scope:

```kotlin
// fresh hit: seed 2 edges with lastSeenAtMillis = now; relatedFor(limit = 2) returns cache; coVerify(exactly = 0) { api.fetchRelatedSongs(any(), any(), any()) }
// stale hit: lastSeenAtMillis = now - FRESH_TTL_MS - 1; returns cache immediately; advanceUntilIdle(); verify api called once; dao gained seenCount+1
// cold cache: returns api result; dao has the edges persisted
// cold cache + api throws: returns emptyList(), no exception
// forceNetwork + api throws + warm cache: returns cached tracks
// forceNetwork + api throws + cold cache: returns emptyList()
// refresh collapse: two concurrent relatedFor on the same stale seed -> exactly one api call
// hygiene: seed 5 rows, call enforceHygiene(now, maxEdges = 3) -> 3 newest rows remain; a row older than PRUNE_AFTER_MS is deleted regardless
```

- [ ] **Step 2: Verify it fails**

Cannot run locally; expected unresolved `RelatedGraphRepository` at CI compile.

- [ ] **Step 3: Implement `RelatedGraphRepository`**

Implement the decision table exactly. Details the signature and tests leave open:
- resolve network results through `runCatching`; only `CancellationException` is rethrown.
- background refresh: `inFlight: ConcurrentHashMap<String, Deferred<List<YouTubeMusicTrack>>>` keyed by `videoId`, mirroring `GenerateRepository.kt:99-115`; launch in `scope`.
- every successful network result is merged via `mergeFetchedEdges` + `dao.upsertAll`, then `enforceHygiene()` runs.
- `topReferredTracks`: `topReferred(limit)` → `snapshotsFor(ids)` → one `YouTubeMusicTrack` per id (most recent snapshot), skipping blank titles.

- [ ] **Step 4: Verify it passes**

CI green for both test classes (pure class may also be run via the local kotlinc/JUnitCore flow).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/repository/RelatedGraphRepository.kt \
        app/src/test/java/com/lastwave/app/data/repository/RelatedGraphPolicyTest.kt \
        app/src/test/java/com/lastwave/app/data/repository/RelatedGraphRepositoryTest.kt
git commit -m "feat(reco): cache-first related-track graph with background refresh"
```

---

### Task 3: Switch all 14 call sites to the wrapper

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/search/SearchRepository.kt:167` (+ constructor :44)
- Modify: `app/src/main/java/com/lastwave/app/data/repository/ArtistRepository.kt:238` (+ constructor :25)
- Modify: `app/src/main/java/com/lastwave/app/ui/feed/FeedViewModel.kt:257, 286` (+ constructor :51)
- Modify: `app/src/main/java/com/lastwave/app/data/feed/FeedRepository.kt:270, 686` (+ constructor :105)
- Modify: `app/src/main/java/com/lastwave/app/data/generate/GenerateRepository.kt:399, 630, 639, 774` (+ constructor :47)
- Modify: `app/src/main/java/com/lastwave/app/data/generate/LocalTasteSuggestionEngine.kt:150, 180` (+ constructor :38)
- Modify: `app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt:4896, 4911` (+ constructor :204)

**Interfaces:**
- Consumes: `RelatedGraphRepository.relatedFor` (Task 2). Every class above is Hilt-injected (`@Singleton`/`@HiltViewModel`), so each simply gains `private val relatedGraph: RelatedGraphRepository` as a constructor parameter.
- Produces: no new API; `InnerTubeMusicApi.fetchRelatedSongs` keeps its remaining role as the repository's network backend only.

- [ ] **Step 1: Perform the mechanical replacement**

Replace each `innerTube.fetchRelatedSongs(...)` call with `relatedGraph.relatedFor(...)`, preserving all arguments verbatim. `forceNetwork = true` **only** for:
- `SearchRepository.kt:167` — `relatedFor(videoId, limit = limit * 2, prefetchStreams = false, forceNetwork = true)`
- `ArtistRepository.kt:238` — `relatedFor(seedVideoId, limit = 30, prefetchStreams = false, forceNetwork = true)`

All other 12 sites stay cache-first (no extra argument).

- [ ] **Step 2: Verify no call sites remain**

Run: `git grep -n "fetchRelatedSongs" -- app/src/main` — expected: exactly one hit, the definition in `InnerTubeMusicApi.kt:1740` (plus the `RelatedGraphRepository` backend call, if it lives in another file, and doc comments). No consumer file may still call it.

- [ ] **Step 3: Verify compile**

CI compile is the gate (no local Gradle). Confirm no other uncommitted files were modified: `git status --short` must show only the 7 files above.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/search/SearchRepository.kt \
        app/src/main/java/com/lastwave/app/data/repository/ArtistRepository.kt \
        app/src/main/java/com/lastwave/app/ui/feed/FeedViewModel.kt \
        app/src/main/java/com/lastwave/app/data/feed/FeedRepository.kt \
        app/src/main/java/com/lastwave/app/data/generate/GenerateRepository.kt \
        app/src/main/java/com/lastwave/app/data/generate/LocalTasteSuggestionEngine.kt \
        app/src/main/java/com/lastwave/app/playback/MusicPlayer.kt
git commit -m "feat(reco): route related lookups through the graph cache"
```

---

### Task 4: PR 1 — push and confirm CI

- [ ] **Step 1: Push the branch**

```bash
git push -u origin feat/related-graph-cache
```

- [ ] **Step 2: Read CI results**

Wait for the workflow on the PR (`.github/workflows/build.yml`): `:app:testDebugUnitTest`, `assembleRelease`, `assembleRelease -PminSdk=24` must all pass. Investigate failures before continuing — do not start PR 2 on a red branch.

- [ ] **Step 3: Report**

State plainly: PR 1 verified by CI only (no local Gradle), tests named above covered, and open PR 2.

---

### Task 5: RelatedGraphScoring — pure referral math

**Files:**
- Create: `app/src/main/java/com/lastwave/app/data/generate/RelatedGraphScoring.kt`
- Test: `app/src/test/java/com/lastwave/app/data/generate/RelatedGraphScoringTest.kt`

**Interfaces:**
- Consumes: nothing (pure Kotlin + `kotlin.math.exp`).
- Produces:
  - `internal const val GRAPH_DECAY_TAU_DAYS: Double = 30.0`
  - `internal data class ReferralPreset(val perSource: Double, val sourceCap: Int, val maxBonus: Double)`
  - `internal val ENGINE_REFERRAL = ReferralPreset(2.0, 8, 16.0)`
  - `internal val DISCOVER_REFERRAL = ReferralPreset(3.0, 4, 12.0)`
  - `internal val FEED_REFERRAL = ReferralPreset(1.5, 4, 6.0)`
  - `internal fun referralBonus(sourceCount: Int, lastSeenAtMillis: Long, nowMillis: Long, preset: ReferralPreset): Double` — `0.0` when `sourceCount <= 0`; `ageDays = (nowMillis - lastSeenAtMillis).coerceAtLeast(0L) / 86_400_000.0`; result `minOf(sourceCount, preset.sourceCap) * preset.perSource * exp(-ageDays / GRAPH_DECAY_TAU_DAYS)` coerced at most `preset.maxBonus`.

- [ ] **Step 1: Write the failing test**

```kotlin
// ENGINE_REFERRAL: bonus(8, now, now) == 16.0 (cap reached); bonus(8, now - 30d, now) ~= 16.0 * exp(-1.0) = 5.886 (delta 1e-6)
// DISCOVER_REFERRAL: bonus(10, now, now, DISCOVER) == 12.0 (sourceCap 4 -> 4*3.0)
// FEED_REFERRAL: bonus(1, now, now) == 1.5
// monotonic in sourceCount below the cap: bonus(2, now, now) > bonus(1, now, now)
// sourceCount == 0 -> 0.0 exactly (empty-graph neutrality)
// future lastSeen (now + 1d) -> clamps to full strength, equals bonus(same, now, now)
// 90 days: bonus(8, now - 90d, now) ~= 16.0 * exp(-3.0) = 0.797 (delta 1e-6)
```

- [ ] **Step 2: Verify it fails**

Expected unresolved `referralBonus`. (Pure class: may run via the local kotlinc/JUnitCore flow.)

- [ ] **Step 3: Implement `RelatedGraphScoring.kt`**

Exactly the formulas and constants above.

- [ ] **Step 4: Verify it passes**

CI green for `RelatedGraphScoringTest`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/generate/RelatedGraphScoring.kt \
        app/src/test/java/com/lastwave/app/data/generate/RelatedGraphScoringTest.kt
git commit -m "feat(reco): add time-decayed referral consensus scoring"
```

---

### Task 6: Repository bonus lookup + Discover integration

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/repository/RelatedGraphRepository.kt`
- Modify: `app/src/main/java/com/lastwave/app/data/discover/DiscoverRepository.kt` (constructor :46, refillQueue :80-195, ranking :223-236)
- Test: extend `app/src/test/java/com/lastwave/app/data/repository/RelatedGraphRepositoryTest.kt`

**Interfaces:**
- Consumes: `referralBonus`, `ReferralPreset` (Task 5); `youtubeVideoIdOrNull()` from `GeneratedTrack.kt:172` (`GeneratedTrack` has no videoId field — the join key is the `url`); `RelatedGraphRepository.topReferredTracks` (Task 2).
- Produces:
  - `suspend fun RelatedGraphRepository.referralBonuses(videoIds: Collection<String>, preset: ReferralPreset): Map<String, Double>` — one `dao.referralStats(ids.toList())` call, mapped through `referralBonus(..., System.currentTimeMillis(), preset)`; ids absent from the table are omitted (callers default to `0.0`).
  - New Discover `SourceBatch(source = "graph", weight = 56)` built from `relatedGraph.topReferredTracks(30)`.

- [ ] **Step 1: Write the failing repository test**

Append to `RelatedGraphRepositoryTest.kt`:

```kotlin
// referralBonuses: seed 2 edges (different sources) for relatedId "r1", lastSeen now; referralBonuses(listOf("r1"), DISCOVER_REFERRAL)["r1"] == 6.0
// referralBonuses: unknown id omitted from the map; empty input -> empty map with zero DAO issues
```

- [ ] **Step 2: Verify it fails** — expected unresolved `referralBonuses`.

- [ ] **Step 3: Implement `referralBonuses` + Discover changes**

- Repository: implement the bulk lookup as specified.
- `DiscoverRepository`: inject `private val relatedGraph: RelatedGraphRepository`; in `refillQueue` add an unconditional batch (no `isYtConnected` gate):
  `jobs += async(Dispatchers.IO) { SourceBatch(tracks = relatedGraph.topReferredTracks(30).map { it.toGeneratedTrack() }, weight = 56, source = "graph") }`
- Discover ranking loop (around :223): before the `.map`, resolve `val rankedBonuses = relatedGraph.referralBonuses(candidates.values.mapNotNull { it.track.youtubeVideoIdOrNull() }, DISCOVER_REFERRAL)`; add `+ (rankedBonuses[candidate.track.youtubeVideoIdOrNull()] ?: 0.0)` to `finalScore`. Nothing else in the loop changes (consensus term, saved penalty, jitter, artist cap, balanced interleave all stay).

- [ ] **Step 4: Verify it passes** — CI green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/repository/RelatedGraphRepository.kt \
        app/src/main/java/com/lastwave/app/data/discover/DiscoverRepository.kt \
        app/src/test/java/com/lastwave/app/data/repository/RelatedGraphRepositoryTest.kt
git commit -m "feat(reco): add referral bonuses and graph-consensus seed family to Discover"
```

---

### Task 7: Recommendation engine integration

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt` (constructor :92, `recoScore` :140, call site :586)
- Modify: `app/src/main/java/com/lastwave/app/data/generate/GenerateRepository.kt:1333` (engine construction)

**Interfaces:**
- Consumes: `RelatedGraphRepository.referralBonuses(ids, ENGINE_REFERRAL)` (Task 6); `GenerateRepository` already holds `relatedGraph` (Task 3).
- Produces:
  - `RecommendationEngine` constructor gains `private val referralLookup: suspend (Collection<String>) -> Map<String, Double>`.
  - `recoScore(...)` gains `referralBonus: Double = 0.0` parameter, added to `score` (separate from the existing same-batch `sourceCount` consensus term, which is unchanged).

- [ ] **Step 1: Implement the wiring (no new unit test — the math is covered by Task 5; this task is plumbing)**

- In `RecommendationEngine.run` before the scored list is built (~:560-586): one bulk lookup —
  `val referralBonuses = referralLookup(pool.mapNotNull { it.track.youtubeVideoIdOrNull() })`; at the :586 call site pass `referralBonus = referralBonuses[c.track.youtubeVideoIdOrNull()] ?: 0.0`.
- In `GenerateRepository.fetchRecommendations` engine construction (:1333): add `referralLookup = { ids -> relatedGraph.referralBonuses(ids, ENGINE_REFERRAL) }`.

- [ ] **Step 2: Verify** — CI compile + green tests.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/generate/RecommendationEngine.kt \
        app/src/main/java/com/lastwave/app/data/generate/GenerateRepository.kt
git commit -m "feat(reco): fold referral consensus into recommendation engine scoring"
```

---

### Task 8: Home feed integration

**Files:**
- Modify: `app/src/main/java/com/lastwave/app/data/feed/FeedRepository.kt` (`trackScore` :212-221, familiar pool :280-293; `relatedGraph` already injected in Task 3)

**Interfaces:**
- Consumes: `referralBonuses(ids, FEED_REFERRAL)` (Task 6).
- Produces: no new API; `trackScore` gains the bounded `+ referralBonus` term (max 6.0) inside the same sum that carries `affinity * 60.0`, `sourceBoost`, and jitter.

- [ ] **Step 1: Implement (no new unit test — bounded math covered by Task 5)**

- After the familiar pools are assembled and before `trackScore` is first applied: one bulk lookup,
  `val referralBonuses = relatedGraph.referralBonuses(familiarPoolsVideoIds, FEED_REFERRAL)` where the id list is every candidate with a non-null `youtubeVideoIdOrNull()` (YT quick picks, liked, recent, local picks, home songs, charts).
- Inside `trackScore`, add `+ (referralBonuses[t.videoId] ?: 0.0)` to the existing sum. Do not change any other term or the diversity/blend/rotation logic.

- [ ] **Step 2: Verify** — CI green; knee-check that with an empty table the term is exactly `0.0` for every track (same-expression guarantee from `referralBonus`).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/lastwave/app/data/feed/FeedRepository.kt
git commit -m "feat(reco): add referral consensus term to home feed ranking"
```

---

### Task 9: PR 2 — push and confirm CI

- [ ] **Step 1: Push the stacked branch**

```bash
git push -u origin feat/related-graph-scoring
```

- [ ] **Step 2: Read CI results** — same three jobs as Task 4; all green before considering the feature complete.

- [ ] **Step 3: Final report** — state PRs, CI evidence, the exact scoring constants shipped, and that ranking changes are limited to the three bounded terms (engine ≤16, discover ≤12, feed ≤6) with empty-graph neutrality tests green.
