# LastWave Live // Telemetry & Music Discovery Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build **LastWave Live**, an anonymous, zero-tracking global telemetry and music discovery web dashboard for the open-source music app "LastWave", featuring a strict monochrome design system (Linear / Vercel / Nothing OS aesthetic), an interactive Leaflet 2D dark matter map with glowing listener nodes, click-to-inspect regional drawers, KPI telemetry micro-cards, tabbed global trending charts, a high-performance Go backend with 60-second ephemeral RAM aggregation, and a modular TypeScript + Tailwind CSS frontend (with a self-contained zero-dependency `index.html` bundle).

**Architecture:**
- **Dedicated Directory:** `lastwave-live/` located in the root of `LastWave-native`.
- **Go Backend (`lastwave-live/backend/`):** High-throughput, thread-safe Go HTTP service with an in-memory ephemeral bucket engine. Enforces zero IP logging, truncates geo-coordinates to coarse regions, aggregates rolling 60-second telemetry windows into active listener counts, regional top 5 songs, and global top 10 trending charts, and exposes REST endpoints (`/api/live-map`, `/api/telemetry/ping`, `/api/simulate-tick`, `/api/health`).
- **Frontend Core (`lastwave-live/`):**
  - **Standalone Single-File Production Dashboard (`lastwave-live/index.html`):** Fully operational, zero-build dashboard using Tailwind CSS via CDN, Lucide Icons, and Leaflet.js with CartoDB Dark Matter monochrome tiles. Runs instantly in any browser or served directly by the Go backend.
  - **Modern TypeScript Frontend (`lastwave-live/frontend/`):** Vite + TypeScript + Tailwind CSS structure with modular components (`MapController`, `RegionalDrawer`, `TelemetrySidebar`, `PrivacyModal`, `TelemetryService`).

**Tech Stack:**
- **Backend:** Go 1.21+ (standard library `net/http`, `sync`, `time`, `encoding/json` — zero heavy external dependencies for maximum portability).
- **Frontend:** TypeScript, Vite, Tailwind CSS, Leaflet.js (`leaflet@1.9.4`), CartoDB Dark Matter basemap, Lucide Icons, Google Fonts (`Inter`, `JetBrains Mono`).

---

## Global Constraints

- **Strict Monochrome Palette:** App background `#050505`, card surface `#0D0D0D` with `bg-opacity-80` + `backdrop-blur-md`, elevated `#161616`, borders `rgba(255, 255, 255, 0.08)` with hover `white/20`, text `#FAFAFA`, `#A1A1AA`, `#52525B`. Zero color accents — all visual cues rely on white radial glow, luminance contrast, border states, and opacity.
- **Typography:** `Inter` for UI labels and headings; `JetBrains Mono` with `tabular-nums` for all numbers, ranks, timestamps, coordinates, badges, and stream counts. Section headers `text-[11px] font-mono uppercase tracking-[0.15em] text-zinc-400`.
- **Privacy Core Principle:** 100% anonymous, zero-IP logging, zero disk persistence of user IDs. Telemetry requests discard connection IPs immediately at the handler boundary and aggregate coarse regional counts in 60s ephemeral RAM buckets.
- **Responsive Layout:** Desktop is a full-viewport 12-column Bento grid (`lg:h-screen lg:overflow-hidden flex flex-col`) with an 8-column interactive map on the left and a 4-column telemetry sidebar on the right; mobile automatically stacks with a smooth vertical scroll fallback.

---

## Review Focus

1. **Map Layer Styling:** CartoDB Dark Matter tiles must be strictly desaturated and balanced with CSS `filter: grayscale(100%) contrast(115%) brightness(90%)` to eliminate blue water or yellow road tint.
2. **Coarse Geolocation Privacy:** User coordinates must never be precise GPS fixes; they are coarse regional centroids (e.g., `19.1° N, 72.8° E` for Mumbai) to prevent deanonymization.
3. **Smooth Marker & Drawer Transitions:** Selecting a region via map marker or quick-jump pill must smoothly pan/fly without jumping, and the glassmorphic regional overlay card must animate cleanly in the map corner.
4. **Resilient Data Fetching:** Frontend must poll `/api/live-map` every 60s, smoothly transitioning countdown pills, with an immediate fallback to rich bundled mock data if the Go backend is offline or run statically.
5. **Immediate Interactive Simulation:** "Simulate 60s Sync" button must instantly recalculate listener jitter, re-render markers, update KPI micro-counters, and refresh the sync countdown timer.

---

## Proposed Changes

```
LastWave-native/
└── lastwave-live/
    ├── index.html                       # Standalone zero-build single-file dashboard (Tailwind CDN, Lucide, Leaflet)
    ├── README.md                        # Architecture, privacy specification, and run guide
    ├── backend/                         # Go Telemetry Service
    │   ├── go.mod
    │   ├── cmd/
    │   │   └── server/
    │   │       └── main.go              # HTTP server, routing, static file embedding, API endpoints
    │   └── pkg/
    │       └── telemetry/
    │           ├── types.go             # Data structures (Region, Song, Metrics, Payload)
    │           ├── bucket.go            # Thread-safe in-RAM 60s ephemeral bucket aggregator
    │           ├── mock_data.go         # Realistic pre-populated data for 12+ Indian & global cities
    │           └── bucket_test.go       # Unit tests verifying zero-IP, 60s window rollover, concurrent pings
    └── frontend/                        # Modular TypeScript + Tailwind web project
        ├── package.json
        ├── tsconfig.json
        ├── vite.config.ts
        ├── tailwind.config.js
        ├── postcss.config.js
        ├── index.html
        └── src/
            ├── index.css                # Custom monochrome styling, animations, Leaflet overrides
            ├── main.ts                  # App bootstrap and state coordinator
            ├── types/
            │   └── telemetry.ts         # TypeScript data definitions
            ├── services/
            │   ├── api.ts               # API client with fallback mock engine & 60s sync timer
            │   └── mockData.ts          # Rich telemetry dataset (Mumbai, Delhi, Bengaluru, London, Tokyo, etc.)
            └── components/
                ├── Header.ts            # Wordmark, live pulsing indicator, countdown pill, privacy badge, simulate button
                ├── MapView.ts           # Leaflet controller, dark matter tiles, glowing L.divIcon nodes, quick-jump pills
                ├── RegionalDrawer.ts    # Glassmorphic overlay card, regional Top 5 songs with rank & stream bars
                ├── Sidebar.ts           # 2x2 KPI micro-cards + Daily/Weekly/Monthly tabbed Top 10 songs
                └── PrivacyModal.ts      # Zero-IP & RAM bucket architecture explanation dialog
```

---

## Detailed Task Breakdown

### Task 1: Go Backend Telemetry Engine & In-Memory Ephemeral Bucket

**Files:**
- Create: `lastwave-live/backend/go.mod`
- Create: `lastwave-live/backend/pkg/telemetry/types.go`
- Create: `lastwave-live/backend/pkg/telemetry/bucket.go`
- Create: `lastwave-live/backend/pkg/telemetry/mock_data.go`
- Create: `lastwave-live/backend/pkg/telemetry/bucket_test.go`
- Create: `lastwave-live/backend/cmd/server/main.go`

**Interfaces:**
- `Bucket`: `NewBucket(windowDuration time.Duration) *Bucket`
- `RecordPing(songID, title, artist, regionCode string)` (strictly drops `RemoteAddr`)
- `GetSnapshot() TelemetryPayload`
- `SimulateTick()`
- HTTP Handlers:
  - `GET /api/live-map` -> returns `TelemetryPayload` JSON
  - `POST /api/telemetry/ping` -> consumes anonymous play event
  - `POST /api/simulate-tick` -> triggers instant bucket rotation / jitter
  - `GET /api/health` -> returns `{"status":"ok","uptime":...,"ram_only":true}`
  - Static file fallback -> serves `lastwave-live/index.html`

- [ ] **Step 1: Write failing unit test in `bucket_test.go`**
  - Verify that `RecordPing` aggregates listeners by region without storing IP addresses.
  - Verify `GetSnapshot` returns active regions, listener sums, and top songs sorted by play count.
  - Verify `SimulateTick` applies realistic activity jitter and maintains data integrity under concurrent access.
- [ ] **Step 2: Implement types and thread-safe RAM bucket in `pkg/telemetry/`**
  - Define `Region`, `Song`, `GlobalKPIs`, and `TelemetryPayload`.
  - Implement `sync.RWMutex`-protected sliding window bucket with atomic updates.
- [ ] **Step 3: Implement rich mock data generator in `pkg/telemetry/mock_data.go`**
  - Populate 12+ major global and Indian hubs: Mumbai (IN), Delhi NCR (IN), Bengaluru (IN), Hyderabad (IN), Kolkata (IN), London (UK), Berlin (DE), Tokyo (JP), New York (US), São Paulo (BR), Sydney (AU), Toronto (CA).
  - Include authentic regional song charts, coarse lat/long coordinates, and global Daily, Weekly, Monthly Top 10 rankings.
- [ ] **Step 4: Build HTTP server in `cmd/server/main.go`**
  - Implement CORS headers, JSON serialization, graceful shutdown, and static dashboard file serving.
- [ ] **Step 5: Run tests and verify backend functionality**
  - Run `go test ./pkg/telemetry/... -v` to ensure thread-safety and snapshot correctness.

---

### Task 2: Standalone Production Single-File Dashboard (`lastwave-live/index.html`)

**Files:**
- Create: `lastwave-live/index.html`

**Key Sections & UI Specifications:**
1. **Header Bar:**
   - Brand: `LastWave // LIVE` with animated glowing white dot indicator.
   - Sync Indicator: `60s CYCLE` badge and live countdown pill (`NEXT SYNC IN: 58s` in `JetBrains Mono`).
   - Action items:
     - `Simulate 60s Sync` button (triggers visual reload, numbers roll, countdown reset).
     - `ZERO-IP LOGS • RAM BUCKET ONLY` privacy pill with shield icon; clicking opens the sleek architectural explanation modal.
     - `GitHub` link to `https://github.com/Clash-Projects/LastWave-native`.
2. **Monochrome 2D Leaflet World Map (8 Columns on desktop):**
   - Leaflet container styled with strict grayscale CartoDB Dark Matter tiles.
   - Custom `L.divIcon` pulsing markers: pure `#FFFFFF` center dot, expanding CSS ripple animation, hover tooltip, and count badge (`142 LIVE`).
   - Quick-jump bottom pills bar: `All Global`, `Mumbai`, `Delhi NCR`, `Bengaluru`, `London`, `Tokyo`, `New York`, etc., with active glow state.
   - Glassmorphic Regional Drawer (anchored bottom-left or top-left inside map container):
     - Displays Region Name, coarse coordinates (`19.07° N, 72.87° E`), active live listeners (`142 LIVE`).
     - Top 5 Regional Songs Today: ranked `01` to `05` in `JetBrains Mono`, desaturated album art with hover contrast effect, title, artist, play count, and horizontal stream proportion bar.
3. **Telemetry & Trending Sidebar (4 Columns on desktop):**
   - Top 2x2 KPI Grid:
     - `ACTIVE LISTENERS` (e.g., `1,428`) with micro up-trend indicator.
     - `STREAMS TODAY` (e.g., `48,910`).
     - `PEAK CONCURRENT` (e.g., `2,104`).
     - `ACTIVE REGIONS` (e.g., `64 Cities`).
   - Global Top 10 Trending Card:
     - Segmented tabs: `DAILY` | `WEEKLY` | `MONTHLY` with sliding active indicator.
     - Scrollable list with ranks `01`–`10`, album art, song title, artist, and total stream count.
4. **Interactive JavaScript Engine:**
   - State manager with 60-second ticker loop.
   - Dual-mode data loader: attempts `fetch('/api/live-map')`; if backend is unavailable or running locally via `file://`, seamlessly uses bundled mock dataset.
   - Smooth map `flyTo` transitions on marker or quick-jump click.
   - Privacy modal backdrop and keyboard (`Escape`) dismiss handlers.

- [ ] **Step 1: Construct HTML skeleton and load fonts / CDN libraries**
  - Include Google Fonts (`Inter`, `JetBrains Mono`), Tailwind CSS CDN with custom config, Leaflet CSS/JS, and Lucide Icons.
- [ ] **Step 2: Build Header Bar & Privacy Modal**
  - Implement zero-IP architecture explanation modal with diagrams and technical specs.
- [ ] **Step 3: Build Leaflet Map container with monochrome styling & custom markers**
  - Configure CartoDB dark tiles, CSS filter overrides, marker ripple animations, and quick-jump pills.
- [ ] **Step 4: Build Regional Drawer & Sidebar components**
  - Implement Top 5 regional list and Top 10 global tabbed lists with smooth transitions.
- [ ] **Step 5: Implement state controller, sync loop, and simulated sync handler**
  - Verify smooth UI updates on both timer expiration and "Simulate 60s Sync" button clicks.

---

### Task 3: Modular TypeScript Frontend (`lastwave-live/frontend/`)

**Files:**
- Create: `lastwave-live/frontend/package.json`
- Create: `lastwave-live/frontend/tsconfig.json`
- Create: `lastwave-live/frontend/vite.config.ts`
- Create: `lastwave-live/frontend/tailwind.config.js`
- Create: `lastwave-live/frontend/postcss.config.js`
- Create: `lastwave-live/frontend/index.html`
- Create: `lastwave-live/frontend/src/index.css`
- Create: `lastwave-live/frontend/src/types/telemetry.ts`
- Create: `lastwave-live/frontend/src/services/mockData.ts`
- Create: `lastwave-live/frontend/src/services/api.ts`
- Create: `lastwave-live/frontend/src/components/Header.ts`
- Create: `lastwave-live/frontend/src/components/MapView.ts`
- Create: `lastwave-live/frontend/src/components/RegionalDrawer.ts`
- Create: `lastwave-live/frontend/src/components/Sidebar.ts`
- Create: `lastwave-live/frontend/src/components/PrivacyModal.ts`
- Create: `lastwave-live/frontend/src/main.ts`

- [ ] **Step 1: Scaffold Vite + TypeScript + Tailwind configuration**
  - Define dependencies (`leaflet`, `lucide`, `@types/leaflet`), compiler options, and build outputs.
- [ ] **Step 2: Create strict TypeScript interfaces and data services**
  - Define interfaces for telemetry snapshots, regions, song rankings, and KPI metrics.
- [ ] **Step 3: Implement modular UI components**
  - Decouple Header, Map, Sidebar, and Regional Drawer into maintainable TypeScript modules with explicit event contracts.
- [ ] **Step 4: Connect main application coordinator and test Vite build**
  - Run `npm install` and `npm run build` inside `lastwave-live/frontend/` to verify type safety and bundle generation.

---

### Task 4: Documentation, Verification & Browser Testing

**Files:**
- Create: `lastwave-live/README.md`

- [ ] **Step 1: Write comprehensive README with architecture & run commands**
  - Document Go server startup (`go run cmd/server/main.go`), frontend dev server (`npm run dev`), single-file static usage, and privacy design guarantees.
- [ ] **Step 2: Automated verification of Go backend**
  - Run `go test` and test API endpoints via HTTP requests.
- [ ] **Step 3: Verification of Frontend dashboard**
  - Launch browser testing subagent / local dev server to verify:
    1. Monochrome aesthetic matches Linear/Vercel standard (zero color leaks).
    2. Map renders tiles in grayscale with glowing white listener nodes.
    3. Clicking a region marker or quick-jump button opens the Top 5 regional songs drawer.
    4. Tab switching between Daily, Weekly, Monthly correctly updates the Global Top 10 list.
    5. Privacy modal opens and closes smoothly.
    6. "Simulate 60s Sync" immediately updates numbers and markers.

---

## Verification Plan

### Automated Tests
1. **Go Unit & Concurrency Tests:**
   ```powershell
   cd lastwave-live/backend
   go test -v ./...
   ```
2. **TypeScript Compilation & Build:**
   ```powershell
   cd lastwave-live/frontend
   npm run build
   ```

### Manual Verification
1. Open `lastwave-live/index.html` in browser.
2. Verify visual appearance: pure `#050505` background, `#0D0D0D` cards, white glowing accents, `Inter` and `JetBrains Mono` fonts.
3. Click on "Mumbai, IN" node or quick-jump pill: verify map smoothly pans to `19.1° N, 72.8° E` and displays Mumbai's Top 5 tracks.
4. Click "Simulate 60s Sync": verify live counters smoothly transition, countdown pill resets to 60s, and marker counts update.
5. Click "ZERO-IP LOGS" badge: verify privacy modal opens with clear technical explanation of the 60s ephemeral RAM bucket architecture.
6. Verify responsive layout on mobile viewport.
