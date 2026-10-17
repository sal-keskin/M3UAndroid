# TR TV fork: implementation plan

This fork turns M3UAndroid's Android TV app (`:app:tv`) into a free IPTV player for Turkish
free-to-air channels, with self-healing channel links. The target device is a Xiaomi Mi Box S
(Android TV 9, 1 GB RAM, 32-bit ARM userland).

- Branch: `fork/trtv`, cut from upstream `master` at `32f3658d`.
- Registry repo: `https://github.com/sal-keskin/iptv`. It is still empty. A template ships in
  `registry-template/`.

## 1. What already exists (exploration summary)

| Area | Where | Notes |
|---|---|---|
| M3U parsing | `parser/` included build (git submodule `oxyroid/parser`), `data/.../parser/m3u/M3UData.kt` | `ParsedChannel` already carries `userAgent`, `referrer` and `options` (all `#EXTVLCOPT` keys). `M3UData.toChannel()` drops them. Kodi `url\|Header=…` suffixes are not split; they stay in `Channel.url`, and only `user-agent` is read back at play time (`PlayerManagerImpl.getUserAgent`). |
| Playlist import / refresh | `PlaylistRepositoryImpl.importM3uLocked` (L197–296), `SubscriptionWorker.m3u` | Refresh **deletes and re-inserts** every channel. Ids are autoincrement, so every refresh gives new ids. With the default strategy `ALL`, favourites and history are lost. `KEEP` keeps only fav/hidden rows, and leaves them stale. **Not suitable for link updates.** |
| Channel model | `data/.../database/model/Channel.kt` (`streams` table) | `relationId` = `tvg-id`, falling back to `tvg-name`. No headers and no channel number. Only `Playlist.userAgent` exists. |
| Room | `M3UDatabase` v26, `DatabaseMigrations` (manual 22→23, 24→25, 25→26), schemas in `data/schemas/`, migration tests in `data/src/androidTest/.../database/` | Manual `Migration` objects are registered in `DatabaseModule`. Tests use `MigrationTestHelper` and need a device or emulator. |
| Playback | `PlayerManagerImpl` (1230 lines) | OkHttp data source and `HlsMediaSource.Factory`. A mimetype chain tries progressive → HLS → DASH → RTSP. Errors: `BEHIND_LIVE_WINDOW` → seek to default; anything else → `playbackException`. There is no failover or stall detection. `CacheDataSource` is commented out. |
| Extension headers | `PlaybackHeaderValue` → `SubscriptionProviderRepositoryImpl.resolvePlayback` → `ProviderPlaybackSource.headers` → `PlayerManagerImpl.activeRequestHeaders` → `OkHttpDataSource.setDefaultRequestProperties` | The non-provider path already applies a `Map<String,String>` as default request properties. **I'm reusing this sink** for M3U/registry headers. I'm not reusing the extension contract itself: see §3. |
| Debug default library | `app/smartphone/src/debug/.../DebugDefaultLibraryWorker.kt` + manifest + `ApplicationStartupTask` multibinding | Debug-only and smartphone-only. It copies a bundled M3U and calls `m3uOrThrow`. TV has nothing like it. |
| WorkManager | `M3UApplication` (`Configuration.Provider` + `HiltWorkerFactory`), `ServicesModule` provides `WorkManager` | Periodic examples: `ExtensionBackgroundTaskScheduler`, `ExtensionContributionScheduler`. |
| Settings | `core/foundation/.../preferences/Preferences.kt` (`PreferencesKeys` + `PREFERENCES` defaults) | A new key needs a default in `PREFERENCES`. |
| TV UI | `app/tv` (`App.kt`, `TvScreens.kt` 2.9k lines, `TvComponents.kt`, `TvStyle.kt`, `TvPlayerScreen.kt`) | The player only has play/pause and close. There is no zapping, no number keys and no overlays. Channels are sorted by group, then title. |
| ABIs | `native-load.yml`, `native-packs/` | `:app:tv` does **not** apply the native-load plugin, so nextlib `.so` files are bundled for all 4 ABIs. I checked the baseline `tv-debug.apk`: `armeabi-v7a`, `arm64-v8a`, `x86` and `x86_64` are each present. |
| Mock server | `testing/mock-server` (Ktor, `Application.mockServerModule()`) | Has `/hls/{channel}/index.m3u8` (VOD fixture). I'll add link-check routes. |

I verified the data sources on 2026-10-17:

- `iptv-org/iptv/master/streams/tr.m3u` returns 200: 215 entries, 12 with `#EXTVLCOPT`, none with `|`.
- `iptv-org.github.io/api/streams.json` returns 200 (3.7 MB). It has **no country field**: channel ids end in `.tr`, and `channel` can be null.
- `Free-TV/IPTV/master/playlist.m3u8` returns 200 (537 KB). It is a global list.
- `Free-TV/IPTV/master/playlists/playlist_turkey.m3u8` returns 200 (9.5 KB, TRT channels only). **I use this one.**

Canonical-ID corrections against `api/channels.json`:

- **TV2 is `tv2.tr`** (lowercase, alt name *Teve2*).
- **Bi Kanal has no iptv-org entry.** I use a local id, `BiKanal.tr`, and note that tier-1 matching can't hit it. `BirTV.tr` is a *different* channel; it's a negative test case.
- **Cartoon Network Türkiye has no official free stream.** `cartoonnetwork.com.tr/canli-yayin` redirects to a WBD broadcaster page, so it is `enabled: false`.

## 2. Options considered

### 2.1 Where the link updater lives

| Option | Pros | Cons |
|---|---|---|
| A. Built-in **extension** (`SubscriptionProvider`) | It reuses the provider refresh and `PlaybackHeaderValue` paths. | Provider channels are `Channel.URL_DYNAMIC` behind provider sessions and accounts. That means credential-vault, broker-scope and IPC policy machinery we don't need. Provider refresh still goes through delete/re-insert. It's hard to validate on the device, write streams atomically, or fail over inside the player. It touches a compatibility-sensitive API. |
| B. Reuse **playlist refresh** (`SubscriptionWorker`) and point it at `playlist.m3u` | Smallest code. | Refresh **re-creates channel ids** and loses favourites and history, which breaks requirement 4. It has no notion of fallbacks, validation or ranking. |
| **C. New data-layer feature (chosen)**: a pure-Kotlin rules module plus Room tables plus a worker. The registry playlist is a normal M3U `Playlist` row whose channels are updated **in place** by canonical id. | Channel ids, favourites, `seen` and ordering survive every update. Matching, ranking and validation can be unit-tested on the JVM, and the same rules can run in the registry Action. Failover is data-driven, so non-registry playlists keep upstream behaviour. | More new code and one Room migration. |

**I chose C.** Requirement 4 alone rules out A and B. All fork behaviour (failover, timeshift, the 1080p cap, rank ordering) is keyed off "this channel belongs to the registry playlist". Other playlists and the smartphone app keep upstream behaviour. The exceptions are the two parser fixes that B1 asks for: they apply to all M3U playlists, and are listed in §6.

### 2.2 Sharing the matching rules with the registry GitHub Action

- A Kotlin CLI would mean building this Android Gradle project, including the Android SDK, inside the registry repo's Action. That is slow and fragile.
- **Chosen:** a **Python script** that mirrors the rules, plus **shared test vectors**. `registry-template/tests/match_vectors.json` is the single source of truth. It is read by the Kotlin unit tests (`:data:registry`) **and** by `pytest` in the registry repo, so if the two implementations drift, one of the test suites fails.

### 2.3 Headers model

- A per-channel `http_headers` JSON column on `streams` (all M3U playlists). It is filled from `#EXTVLCOPT:http-referrer`, `#EXTVLCOPT:http-user-agent`, `#EXTVLCOPT:http-origin` and the Kodi `|Referer=…&User-Agent=…&Origin=…` suffix. That suffix is stripped from the stored URL.
- For registry channels, the per-URL headers live in the new `channel_streams` table.
- At play time, headers go through the existing non-provider `setDefaultRequestProperties` / `setUserAgent` path. The `PlaybackHeaderValue` extension contract is not changed.

### 2.4 Timeshift ("pause and go back a little")

- ExoPlayer can only seek inside the live HLS window, which is often 20–60 s. A back-buffer in RAM is too expensive on 1 GB.
- **Chosen:** a `HlsPlaylistParserFactory` wrapper. It **accumulates live media playlists** using `previousMediaPlaylist`, stateless, and keeps up to 10 min of segments listed. Already-played segments are served from the existing on-disk `SimpleCache` through a segment-only `CacheDataSource`; playlists are never cached.
- Pausing keeps your position. ← and → seek ±10 s. "Go live" jumps to the live edge.
- If the paused position falls out of the window, or the CDN has purged the segment, the player quietly returns to live. This does **not** count as a failed source.
- It applies to registry channels only. Live speed adjustment is disabled for them, so playback doesn't drift.

### 2.5 First run

- **Chosen:** `app/tv/src/main/assets/registry/` holds a snapshot of `channels.json`, `sources.json` and `playlist.m3u`, generated by the registry script. On first launch, `LinkRegistryBootstrap` seeds the registry playlist from that snapshot with no network and no setup screen. It then schedules a link update. The list (1–38, disabled channels hidden) shows instantly, even before the registry repo is pushed or when the device is offline.
- This works in release builds. The debug `DebugDefaultLibrary*` is not reused: it's debug-only, smartphone-only, enforces a hash/host allowlist, and goes through delete/re-insert import.
- The registry base URL is editable in Settings and stored in DataStore.

## 3. Modules and files

### New module `:data:registry` (Kotlin/JVM, no Android)

Platform-neutral rules and networking. It depends on OkHttp, kotlinx-serialization and coroutines, all already in the catalog. It is located at `data/registry/`, following the same nesting pattern as `:core:foundation`.

- `model/RegistryModels.kt`: the `channels.json` / `sources.json` schema.
- `match/NameNormalizer.kt`: Turkish-aware casefolding plus noise and feed-suffix stripping.
- `match/JaroWinkler.kt`
- `match/ChannelMatcher.kt`: tier 1 exact id, tier 2 alias, tier 3 guarded fuzzy, with the exclude list applied to every tier.
- `source/CandidateParser.kt`: M3U with `#EXTVLCOPT` and Kodi `|` headers, plus a streaming parser for the iptv-org `streams.json` API.
- `source/HttpHeaderOptions.kt`: Kodi pipe splitting, shared with `M3UData.toChannel()`.
- `fetch/ConditionalFetcher.kt`: ETag / If-None-Match with a pluggable on-disk store, a 10 s timeout and per-source isolation.
- `validate/HlsInspector.kt`: pure master/media playlist parsing and picking the best variant at or below 1080p.
- `validate/StreamValidator.kt`: on-device checks over OkHttp, with a 6 s budget, 4-way concurrency and an optional 64 KB segment probe.
- `rank/StreamRanker.kt`: pinned > last-known-good > trust × tier weight > latency. It keeps 1 primary plus 3 fallbacks and the stale fallback.
- `plan/LinkUpdatePlanner.kt`: pure orchestration that turns registry, candidates, validation and the previous state into a write plan and a report.
- `timeshift/LiveWindowMerger.kt`: the pure sliding-window merge used by the HLS adapter.
- Tests (`src/test`): normalization, must and must-not match (from the shared vectors), headers parsing, ranking, the planner (stale keep), the merger, and the validator against `:testing:mock-server` running in-process.

### `:data` (Android)

- `database/model/Channel.kt`: adds `headers: Map<String,String>?` (`http_headers`).
- `database/model/RegistryEntities.kt` (new): `RegistryChannelEntity` (`registry_channels`) and `ChannelStreamEntity` (`channel_streams`).
- `database/dao/LinkRegistryDao.kt` (new).
- `database/Converters.kt`: adds the `Map<String,String>` converter.
- `database/M3UDatabase.kt`: v27 and the new entities. `DatabaseMigrations.kt`: `MIGRATION_26_27`. `DatabaseModule.kt`: registers it.
- `schemas/.../27.json`: generated by Room/KSP.
- `parser/m3u/M3UData.kt`: carries headers and strips the Kodi `|` suffix.
- `repository/linkupdate/` (new): `LinkUpdateRepository` and `LinkUpdateRepositoryImpl`, `LinkRegistryBootstrap`, `LinkRegistryConfig` (provided by the app), `LinkUpdateStateStore`, and the Android adapters for the fetch cache and asset loading.
- `worker/LinkUpdateWorker.kt` (new): one-time (all channels or one), weekly periodic, and auto on start after 24 h on unmetered networks. It reports progress through `setProgress`.
- `service/internal/PlayerManagerImpl.kt`: channel headers, registry stream list, silent failover, 10 s stall detector, demotion, timeshift factory, 1080p cap and fixed live speed (registry only).
- `service/internal/TimeshiftHls.kt` (new): the Media3 adapter for `LiveWindowMerger` plus the segment-only cache data source.
- `service/PlayerManager.kt`: additive `sourceEvents` and `sourceState` flows.
- `repository/RepositoryModule.kt` / `ServicesModule.kt`: bindings.
- Tests: `src/androidTest/.../Migration26To27Test.kt` and a JVM `M3UDataHeadersTest`.

### `:core:foundation`

- `Preferences.kt`: `REGISTRY_BASE_URL` (string, `""` means use the app default), `START_ON_LAST_CHANNEL` (false), `LINK_UPDATE_LAST_RUN` (0), `LINK_AUTO_UPDATE` (true).

### `:app:tv`

- `build.gradle.kts`: `applicationId = "com.salkeskin.trtv"`; the app name is set in resources. `ndk.abiFilters` limits the APK to `armeabi-v7a` and `arm64-v8a`, which roughly halves its size. This is fork packaging only; smartphone is untouched.
- `res/values/stings.xml`: `app_name` "TR TV".
- `assets/registry/{channels.json,sources.json,playlist.m3u}`: the first-run snapshot.
- `AppModule.kt`: provides `LinkRegistryConfig`. `M3UApplication.kt`: bootstrap and schedule.
- **Design system** (`/design-system`): `TvDesign.kt` (new tokens for colour, type scale, spacing, shape, focus, motion and elevation, plus base components), and `TvStyle.kt` (existing `TvColors` and `TvFonts` mapped onto the tokens so current screens restyle consistently).
- `live/` (new):
  - `TvLiveViewModel.kt`
  - `ChannelZapper.kt`: pure; zap up/down, the number buffer, Back history.
  - `TvLiveScreen.kt`: the ranked channel list, with the "Update links" and "Diagnostics" menu.
  - `TvPlayerOverlays.kt`: info banner, number entry, channel-list overlay, "Switching source…" toast, timeshift bar, and the error card with "Repair this channel" and "Next channel".
  - `TvDiagnosticsScreen.kt`
  - `TvLiveSettingsSection.kt`: update links, registry URL, start on last channel.
- `App.kt`, `TvStyle.kt` (`TvDestination.Live` first and the default), `TvPlayerScreen.kt` (key handling and overlays), and `TvScreens.kt` (adds the Live settings section to `StatusScreen`).
- `src/test`: `ChannelZapperTest`.

### `:i18n`

- New `values/tv_live.xml` and `values-tr-rTR/tv_live.xml` (English and Turkish).

### `:testing:mock-server`

- New link-check routes:
  - `/linkcheck/good/master.m3u8`: master with 360p, 1080p and 1440p variants, plus the media playlists and segments.
  - `/linkcheck/forbidden.m3u8`: 403.
  - `/linkcheck/empty.m3u8`: `#EXTM3U` with no segments.
  - `/linkcheck/slow.m3u8`: an 8 s delay.
  - `/linkcheck/needs-referer.m3u8`: 403 unless the Referer header is set.
- A public `startEmbeddedMockServer(port)` helper for in-process JVM tests.

### Other

- `baselineprofile/tv/build.gradle.kts`: target package `com.salkeskin.trtv`.
- `registry-template/`: `channels.json`, `sources.json`, `playlist.m3u`, `candidates.json`, `scripts/build_registry.py`, `tests/` (vectors + pytest), `.github/workflows/build.yml` and `README.md`.
- `docs/fork/PLAN.md` (this file), `CHANGES.md`, `DESIGN_SYSTEM.md` and `INSTALL.md`.

## 4. Room migration 26 → 27 (manual, additive, no data loss)

```sql
ALTER TABLE streams ADD COLUMN http_headers TEXT DEFAULT NULL;

CREATE TABLE IF NOT EXISTS registry_channels (
  canonical_id TEXT NOT NULL PRIMARY KEY,
  playlist_url TEXT NOT NULL,
  rank INTEGER NOT NULL,
  official_live_page TEXT DEFAULT NULL,
  stale INTEGER NOT NULL DEFAULT 0,
  updated_at INTEGER NOT NULL DEFAULT 0,
  FOREIGN KEY(playlist_url) REFERENCES playlists(url) ON UPDATE NO ACTION ON DELETE CASCADE);
CREATE INDEX IF NOT EXISTS index_registry_channels_playlist_url ON registry_channels(playlist_url);

CREATE TABLE IF NOT EXISTS channel_streams (
  canonical_id TEXT NOT NULL, url TEXT NOT NULL, position INTEGER NOT NULL,
  headers TEXT DEFAULT NULL, source_id TEXT NOT NULL, match_tier INTEGER NOT NULL,
  match_score REAL NOT NULL, status TEXT NOT NULL, latency_ms INTEGER DEFAULT NULL,
  height INTEGER DEFAULT NULL, failure_reason TEXT DEFAULT NULL,
  checked_at INTEGER NOT NULL DEFAULT 0, last_ok_at INTEGER NOT NULL DEFAULT 0,
  failure_count INTEGER NOT NULL DEFAULT 0, first_seen_at INTEGER NOT NULL DEFAULT 0,
  PRIMARY KEY(canonical_id, url),
  FOREIGN KEY(canonical_id) REFERENCES registry_channels(canonical_id) ON UPDATE NO ACTION ON DELETE CASCADE);
```

- Existing rows get `http_headers = NULL`, which is exactly the behaviour before this change.
- The new tables start empty.
- `Migration26To27Test` (androidTest, the same pattern as `Migration25To26Test`) seeds v26 rows, migrates, and checks that rows and flags are preserved, the new column defaults to NULL, and the FK cascades work.

## 5. Phases (each ends green: `:data:registry:test`, `:data:testDebugUnitTest`, `:app:tv:testDebugUnitTest`, `:app:tv:assembleDebug`)

1. Exploration and this plan.
2. **B1:** model, migration, parser headers and player header application.
3. **B2 core:** `:data:registry` with fetch, match, rank, the planner and their tests.
4. **B2 device:** validator and mock-server routes, the `:data` repository, worker, scheduling and bootstrap.
5. **B3:** failover, stall detection, demotion and timeshift.
6. **B4/B5:** design system, the Live screen, player overlays, zapping, number keys, diagnostics, settings, first run and i18n.
7. **Part A:** `registry-template`, its script, tests and Action. Its output generates the bundled snapshot.
8. Build the APK (release is signed with the upstream debug config, unchanged), run migration tests on an Android TV 12 arm64 emulator (the closest image to a Mi Box S on Apple Silicon), and write the install notes.

## 6. Behaviour changes to upstream paths (compatibility notes)

- **All M3U playlists:**
  - `#EXTVLCOPT` referrer, user-agent and origin are now applied at playback.
  - A Kodi `|…` suffix is stripped from stored URLs and applied as headers. Previously the suffix stayed in the URL, which was sent as-is, and only `user-agent` was honoured.
  - This is the B1 requirement. User-agent precedence is: channel header → Kodi option → `Playlist.userAgent`.
- **Registry playlist only:** failover, stall detection, timeshift, the 1080p cap, fixed live speed, rank ordering, and channel-number zapping.
- The registry playlist URL is a stable synthetic `registry://default`. `Playlist.refreshable` returns false for it, so the generic refresh, which would delete and re-insert, can never touch it. "Refresh" on it means "Update links".
- The `PlayerManager` interface gets two additive members. Smartphone ignores them.

## 7. Risks and open items

- **Show TV, CNN Türk, Sözcü TV, TV2, TLC, DMAX, TV100, Bi Kanal, Flash TV, Ulusal Kanal and TV8,5** have no stream in any source today.
  - Their official live pages use tokenized player URLs.
  - They are listed as `enabled: true` with `officialLivePage` filled in where it could be verified. They will show "No link yet" until a pinned URL is added or the Phase-2 WebView resolver exists, and that resolver is gated on asking first.
  - *Update:* Phase 2 was approved and built for Show TV (WebView resolver) and Sözcü TV (YouTube hand-off). CNN Türk was left out because its `robots.txt` forbids it. See section 9.
  - `tv8bucuk.com` and `flashtv.com.tr` were unreachable during verification, so their pages are left blank with a note.
- **Geo-blocking:** GitHub runners are outside Türkiye, so the Action's liveness result is only a hint and never drops a URL. The device is the validator.
- **No physical Mi Box S here.** I verify on an Android TV 12 arm64 emulator. `minSdk 26` and API 28 behaviour were not exercised on a real device.

## 8. As built: where the implementation deviates from this plan

These are recorded after phases 2–8. [CHANGES.md](CHANGES.md) is the authoritative file list.

- **Preferences:** `LINK_UPDATE_LAST_RUN` was not added. The last run time, the first-run flag and
  the last report live in `filesDir/link-registry/state.json` (`LinkUpdateStateStore`). User
  settings keep only `REGISTRY_BASE_URL`, `LINK_AUTO_UPDATE` and `START_ON_LAST_CHANNEL`.
- **`sources.json`** gained `officialHosts`. Links outside the broadcasters' CDNs keep 75 % of
  their source trust, so restreams never outrank the official stream. `blockedHosts` starts with
  three TRT restream hosts. The `self` source url is `{registry}/playlist.m3u`; the app fills in the
  configured base url, and the registry builder skips it because it is the builder's own output.
- **Ranking** gained a height preference (taller up to 1080p) between trust and latency.
  Equal-trust 360p and 1080p urls used to tie.
- **Validator** reads up to 2 MB, because TRT publishes 24 h DVR playlists of 14 400 segments.
  It probes the third-newest segment and retries network errors once. Timeshift skips merging when
  the server already offers a window of 10 minutes or more.
- **Failed candidates** fill the spare fallback slots after working and untested ones. Playback gets
  a last resort, and Diagnostics can show the failure reason.
- **Settings:** the Live TV block sits above the upstream `StatusScreen` instead of inside its list,
  whose focus restoration depends on item indices.
- **Logos:** the TV app's Coil loader sends a descriptive User-Agent, because Wikimedia rejects
  anonymous clients.
- **The remote-pairing pin** moved from the top-end corner, where it covered the new header
  actions, to a small pill under the rail. It is hidden during playback.

## 9. Phase 2: official live pages (approved 2026-10-17)

The request was a WebView resolver for channels whose free official stream sits behind an expiring
link on the broadcaster's own site: Show TV, CNN Türk and Sözcü TV. The condition was free official
streams only, respecting each site's terms. [CHANGES.md](CHANGES.md#phase-2-links-from-official-live-pages)
has the file list and the policy table.

Decisions:

- **Review each site before writing code.** Each site's terms of use and `robots.txt` were read
  first.
  - CNN Türk's `robots.txt` disallows its playlists for every agent, so it gets no resolver.
  - Show TV's terms allow personal, individual viewing and forbid redistribution and archiving.
  - Sözcü TV streams through its own YouTube channel.
- **The cheapest step first.** One plain GET of the page finds Show TV's link today. The hidden
  WebView is only a fallback for pages that build the link in script. It is capped at 20 s and
  destroyed after each use.
- **YouTube is handed off, never extracted.** YouTube's terms forbid extracting its media, so the
  app opens the YouTube app instead.
- **Opt-in and local.**
  - The setting is off by default.
  - Resolved links stay in the TV's database, and official-page segments are not cached to disk.
  - The registry Action never runs resolvers, so the public `playlist.m3u` never carries a token.
- **No new dependency.** The resolver uses Android's own `WebView`, OkHttp (already present) and the
  existing ranking pipeline.

Open items:

- Show TV's terms also say that any use of the site's content is subject to the company's
  permission. Playing the stream in this app's player, rather than on the site, for that viewer's
  personal viewing is a reasonable reading of the personal-use clause, but it is not an explicit
  grant. That is why the feature is opt-in. Ask the broadcaster if in doubt.
- The WebView fallback was tested against the mock server only, because Show TV's page declares its
  link in plain HTML today. If Show TV moves the link into script, the fallback is the path that will
  run on a real box.
