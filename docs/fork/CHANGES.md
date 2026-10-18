# TR TV fork: changes

Branch `fork/trtv`, based on upstream `master` @ `32f3658d`. This file lists what changed and what
still matches upstream. For the reasoning, see [PLAN.md](PLAN.md); for the visual language, see
[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md).

## Summary

| Area | Change |
|---|---|
| App identity | `applicationId` `com.salkeskin.trtv`, name **TR TV**, version `1.0.1-trtv.1`, ABIs `armeabi-v7a` and `arm64-v8a` |
| First run | Opens straight into **Live TV**: 37 channels in audience-share order, numbered by rank (Cartoon Network is disabled, so number 24 is skipped). Seeded offline from a bundled registry snapshot |
| Link updater | Fetches the registry and its sources with ETag caching, matches with a 3-tier matcher, validates every url **on the device**, ranks, and writes **in place** by canonical id |
| Triggers | The **Update links** button (Live header, Settings), automatically on start when older than 24 h (unmetered only), weekly periodic, and **Repair this channel** from the player error or Diagnostics |
| Playback | Per-channel headers, silent **failover** with a 10 s stall detector, demotion of failed urls, a 1080p cap, and a **timeshift** buffer (pause, ◀ ▶ 10 s, back to live) |
| TV UX | Zapping, number keys, a channel list on OK, a Back history, an info banner, toasts, an error card, **Diagnostics**, Live TV settings, English and Turkish |
| Design | The "Gece" design system (tokens and components), applied to the whole TV app |
| Registry | `registry-template/`: `channels.json` (38 channels), `sources.json`, a Python builder that mirrors the app's rules, shared test vectors and a nightly Action |
| Official pages (Phase 2) | Opt-in, **off by default**. Show TV's expiring link is read off its own live page, on the TV, for that viewer. Sözcü TV hands off to the YouTube app. CNN Türk gets no resolver because its `robots.txt` disallows it. See [Phase 2](#phase-2-links-from-official-live-pages) |

## Modules and files

### New: `:data:registry` (pure Kotlin/JVM)

These are the rules shared with the registry builder. There are no Android APIs.

- `model/RegistryModels.kt`: the `channels.json` and `sources.json` schema.
- `match/NameNormalizer.kt`, `match/JaroWinkler.kt`, `match/ChannelMatcher.kt`: tier 1 exact id,
  tier 2 alias, tier 3 guarded fuzzy. Exclusions apply to every tier.
- `source/PlaybackHeaders.kt`: `#EXTVLCOPT` and Kodi `url|Header=`. Only Referer, User-Agent and
  Origin are carried.
- `source/CandidateParser.kt`: M3U (reusing the upstream parser) and a streaming iptv-org
  `streams.json` reader.
- `fetch/ConditionalFetcher.kt`: ETag / If-None-Match / Last-Modified on disk, 10 s timeout, falls
  back to the cached body.
- `validate/HlsInspector.kt` and `validate/StreamValidator.kt`: 6 s budget, 4 in parallel, picks a
  variant at or below 1080p, requires segments, probes 64 KB of the third-newest segment, reads 2 MB
  of playlist (TRT's 24 h DVR playlist is about 1 MB), and retries network errors once.
- `rank/StreamRanker.kt`: pinned > last known good > trust × tier weight > picture height up to
  1080p > latency.
- `plan/LinkUpdatePlanner.kt`: candidate pool, validation targets (at most 8 per channel), the
  per-channel plan, keeping the last known good url as **stale**, the summary, and host-aware trust
  (`officialHosts`).
- `timeshift/LiveWindowMerger.kt`: the sliding-window merge.
- Tests: 40 across normalization and matching (shared vectors), parsing, headers, the planner and
  ranking, the merger, the fetcher, and the validator against `:testing:mock-server`. Phase 2 adds
  7 more.

### `:data`

- **Room v27** (`MIGRATION_26_27`, manual and additive):
  - `streams.http_headers`
  - new tables `registry_channels` and `channel_streams`
  - schema `27.json`
  - `Migration26To27Test`, and the five older migration tests now also chain 26→27.
- `Channel.headers` and a `Map<String,String>` type converter.
- `M3UData.toChannel()` carries `#EXTVLCOPT` and `|Header=` headers and strips the Kodi suffix from
  the url.
- `LinkRegistryDao`, plus `LinkUpdateRepository` and its implementation, `LinkUpdateModels` and
  `LinkUpdateStateStore` (in `repository/linkupdate`). `LinkRegistryConfig` is optional
  (`@BindsOptionalOf`), so apps without a registry are unaffected.
- `LinkUpdateWorker`: the unique works `link-update`, `link-update-weekly` and `link-repair:<id>`,
  with progress through `setProgress`.
- `PlayerManagerImpl`:
  - channel headers;
  - the registry stream list;
  - failover, the stall detector and demotion;
  - "back to live" for expired timeshift segments;
  - `TimeshiftHlsPlaylistParserFactory` and a segment-only `CacheDataSource`;
  - a 1080p track cap and fixed 1× live speed (registry channels only);
  - `seekBy` and `seekToLive`.
- `PlayerManager` gains `sourceState`, `sourceEvents`, `seekBy` and `seekToLive`. These are
  additive; smartphone does not use them.
- `Playlist.URL_REGISTRY_SCHEME`: `registry://` playlists are not `refreshable`, so the generic
  delete-and-reinsert refresh can never run on them.
- Tests: `M3UDataHeadersTest` and `TimeshiftHlsTest` (the real Media3 `HlsMediaPlaylist` merge).
  These are the first JVM tests in `:data`. `data/.gitignore` no longer ignores `/src/test`, so
  git tracks them.

### `:core:foundation`

- Preferences: `REGISTRY_BASE_URL` (`""` means the app default), `LINK_AUTO_UPDATE` (true) and
  `START_ON_LAST_CHANNEL` (false).

### `:app:tv`

- `build.gradle.kts`:
  - `applicationId` and `versionName`;
  - `BuildConfig.REGISTRY_BASE_URL`, overridable with `-PtrtvRegistryBaseUrl=…`;
  - `ndk.abiFilters`.
- `assets/registry/`: the first-run snapshot generated by `registry-template/scripts/build_registry.py`.
- `AppModule` provides `LinkRegistryConfig`.
- `M3UApplication`:
  - bootstraps the registry and schedules updates;
  - its Coil image loader sends a descriptive User-Agent, because Wikimedia, which hosts 14 of the
    logos, rejects anonymous clients.
- Design system: `TvStyle.kt` (new palette and the `Live` and `Diagnostics` destinations; Home is
  hidden from the rail) and `TvDesign.kt`.
- `live/` (new):
  - `ChannelZapper` and its tests;
  - `TvLiveViewModel`;
  - `TvLiveScreen`;
  - `TvLivePlayerLayer`;
  - `TvDiagnosticsScreen`;
  - `TvLiveSettings` (the Settings section and the registry url panel);
  - `TvLiveComponents`.
- `App.kt`:
  - Live is the default destination;
  - Diagnostics and Settings wiring;
  - the live player layer for registry channels;
  - toasts;
  - start on last channel;
  - the remote-pairing pin moved under the rail and is hidden during playback. TR TV no longer
    shows it, because its phone-remote server is off (see Performance).
- `TvHomeViewModel`: refreshing the registry playlist from Library runs a link update.
- `proguard-rules.pro`: keeps `io.netty.**`, `io.ktor.**` and `kotlin.reflect.jvm.internal.**`, as
  `:app:smartphone` already does. This fixes an upstream bug: release builds crashed on launch on a
  television, because R8 stripped the constructor that Netty's `ReflectiveChannelFactory` needs for
  the phone-remote server.

### `:i18n`

- `values/tv_live.xml` and `values-tr-rTR/tv_live.xml` (English and Turkish).

### `:testing:mock-server`

- `LinkCheckRoutes.kt`:
  - good HLS master (360p, 1080p, 1440p);
  - 403;
  - empty playlist;
  - slow (8 s);
  - Referer required;
  - segment 404;
  - not HLS;
  - ETag;
  - long DVR playlists.
- `startEmbeddedMockServer()` for in-process JVM tests.

### Other

- `baselineprofile/tv`: target app id `com.salkeskin.trtv`.
- `gradle/libs.versions.toml`: an explicit `squareup-okhttp` entry, the same version OkHttp already
  had transitively. No new third-party dependency.

## Performance on low-end sticks

Measured on a Xiaomi Mi TV Stick (Android 9, 1.2 GHz Cortex-A53, Mali-450, 1 GB RAM) with the
release APK. The scroll test is 40 ▲ ▼ steps through the Live TV list (`dumpsys gfxinfo`).

| | Before | After |
|---|---|---|
| Cold start (`am start -W`) | 1.7–3.5 s | 1.1–1.2 s |
| Frame time: median / 90th / 99th percentile | 26 / 69 / 125 ms | 19 / 36 / 48 ms |
| Frames that missed the deadline | 88 % | 60 %, most of them only just |
| Memory (PSS) on Live TV | 61 MB | 57 MB |
| Threads | 37 | 31 |

What changed:

- **No backdrop.** `TvBackdrop` drew the channel's logo full screen, cropped, under two
  full-screen translucent layers, behind every screen. It is removed; the canvas is a flat
  `Background`.
- **No focus shadow.** `FocusFrame` cast an 18 dp shadow, which the GPU redrew on every frame of the
  focus scale animation. Focus keeps its white fill, border and scale.
- **No phone-remote server.** TV builds started a Ktor/Netty HTTP server and an NSD broadcast on
  every launch, for pairing the upstream phone app as a remote; that is the pairing pin. A new
  optional `TvRemoteControlConfig` turns it off. TR TV binds it with `broadcastOnTv = false`; apps
  that don't bind it keep the upstream behaviour.
- **Precompile the fork's code.** The generated baseline profile was recorded before the fork and
  had no rule for its code. `app/tv/src/main/baselineProfiles/trtv-fork.txt` adds the Live TV
  screens, the player, the link updater and the registry rules. Android 9 compiles a sideloaded app
  with its profile only during idle maintenance, so [INSTALL.md](INSTALL.md) gives the one-time
  `cmd package compile` command. Compiling everything (`-m speed`) was no smoother, and it cost
  0.5 s of start time and 15 MB of memory.

## Storage

On the Mi TV Stick (4.6 GB of storage), TR TV had filled the disk to 93 %.

- **Cause.** The timeshift buffer wrote live segments into upstream's *download* cache:
  `getExternalFilesDir()/downloads`, with a 2 GB LRU limit, never cleared. After some hours of
  watching it held 1.9 GB.
- **Fix.** Timeshift now has its own cache (`@TimeshiftCache`). It is capped at 128 MB (about
  3 minutes of 1080p; older segments are fetched again when rewound), lives in `cacheDir/timeshift`
  where Android can trim it, and is emptied when the app starts. Upstream's download cache is
  unchanged.
- **Cleanup.** TR TV has no downloads, so it empties the download cache on start
  (`PlayerManager.clearCache()`). On the stick this went from 1.9 GB to 108 KB, and free storage from
  366 MB to 2.3 GB.
- **Logos.** The Coil disk cache is capped at 32 MB instead of 2 % of the disk.

## Phase 2: links from official live pages

Some broadcasters offer their free live stream only through the player on their own website. The
player's link carries a token that expires, so a fixed url in the registry cannot work. Phase 2 lets
the TV find that link itself.

### Policy review (2026-10-17)

| Channel | What its site says | Result |
|---|---|---|
| Show TV | The terms of use allow content watched on the site for personal, individual use only. They forbid commercial use, passing it to third parties, copying, reproducing and archiving. A general clause says any use of the site's content is subject to the company's permission. `robots.txt` disallows `/canli-yayin` only for `Googlebot-Video` | `webview` resolver. The link is read on the viewer's own TV and played for that viewer only. It is never cached to disk or published |
| CNN Türk | `robots.txt` disallows `/*index.m3u8` for every agent and blocks AI agents entirely | **No resolver.** The channel shows "No link yet" unless a free public source carries it |
| Sözcü TV | `robots.txt` allows all agents. The live page embeds Sözcü's own YouTube live | `youtube` resolver. The app reads only the video id and opens the **YouTube app**. It never extracts YouTube media |
| NOW (reviewed 2026-10-18) | The live page declares signed links that expire after a few hours. The terms licence viewing only through NOW's own services, and forbid stream capture, playing, displaying or deep-linking content elsewhere, and circumventing access controls | **No resolver.** NOW keeps playing from the static TurkNet link that iptv-org lists (720p, a 30-minute live window) |

### How it works

1. **Opt-in.** Nothing runs until the viewer turns on **Settings → Live TV → Links from official
   websites** (`OFFICIAL_PAGE_LINKS`, default off). Turning it on runs **Update links**.
2. **One page read.** During an update, `OfficialPageScanner.fetchAndScan` does one GET of the
   channel's live page with a TV browser's User-Agent and scans the HTML. No page script runs.
   It accepts an `.m3u8` url only if it matches the channel's `match` pattern (so the sister
   channel on the same page is never picked) and sits on the broadcaster's own `hosts`.
3. **WebView fallback**, only for `webview` resolvers when step 2 found nothing: a hidden WebView loads
   the page for at most 20 s and captures the player's own playlist request. It also checks the
   page's resource list and `<video>` sources. Then it destroys the WebView. On boxes without a
   WebView provider, it skips this step.
4. **Validated and ranked like every other link**, from the source `official-page` (trust 1.0). It
   sends the page's own Referer and Origin.
5. **Fresh before playback.** When a channel's official link is older than its `ttlMinutes`
   (60 for Show TV), the player resolves it again just before playing. That check uses the cached
   registry and makes no other network call.
6. **No disk cache.** The timeshift segment cache is skipped for `official-page` streams. Pause and
   rewind still work: older segments are fetched again from the broadcaster's CDN. When the CDN no
   longer serves one, playback goes back to live.
7. **YouTube.** A `youtube` resolver stores a `youtube.com/watch` link. Tuning to the channel shows a
   hand-off card: **Open in YouTube** or **Next channel**. The intent names the YouTube TV package,
   then the phone YouTube package, because Android TV sends a plain `https` link to a do-nothing
   browser stub. If neither app is installed, a toast says so.

Resolved links never leave the TV. The registry Action never runs resolvers, and resolved links
never appear in `playlist.m3u` or `candidates.json`. When the setting is off, the update drops
streams from the `official-page` and `youtube` sources.

### Files

- `:data:registry`:
  - `ResolverSpec` in `model/RegistryModels.kt`;
  - `resolve/OfficialPageScanner.kt` (pure text scanning plus one GET);
  - `ConditionalFetcher.cachedFile`;
  - `OfficialPageScannerTest` (7 tests, against the mock server).
- `:data`:
  - `repository/linkupdate/OfficialPageResolver.kt` (the scan, then the WebView fallback);
  - `LinkUpdateRepository.refreshOfficialLink`;
  - `LinkUpdateRepositoryImpl` merges resolved links into the plan;
  - `PlayerManagerImpl` refreshes stale official links, never plays YouTube urls and never caches
    official-page segments;
  - androidTest `OfficialPageResolverTest` (static page, script-built page through the WebView,
    YouTube).
- `:core:foundation`: the `OFFICIAL_PAGE_LINKS` preference, default `false`.
- `:app:tv`:
  - the **Links from official websites** toggle;
  - `LiveStatus.EXTERNAL`;
  - the `HandoffCard` in `TvLivePlayerLayer`;
  - `LiveEvent.OpenExternal` and the YouTube intent in `App.kt`.
- `:i18n`: 7 strings in English and Turkish.
- `:testing:mock-server`: the `/official/static`, `/official/script` and `/official/youtube`
  fixtures.
- `registry-template`: `resolver` blocks for Show TV and Sözcü TV, CNN Türk's note, and the README
  section "Official live pages (resolvers)".

## Behaviour changes for existing paths (compatibility)

- **All M3U playlists:**
  - `#EXTVLCOPT:http-referrer`, `http-user-agent` and `http-origin` are now sent at playback.
  - A Kodi `|…` suffix is removed from the stored url and sent as headers. Before, the suffix stayed
    in the url and only `user-agent` was honoured.
  - User-agent precedence: channel header → Kodi option in a legacy url → `Playlist.userAgent`.
  - Under the KEEP refresh strategy, a favourite channel whose url carried a `|` suffix and has no
    `tvg-id` is re-inserted once, because its url changed shape.
- **Registry playlist only:** failover, stall detection, timeshift, the 1080p cap, fixed live speed,
  rank order, zapping and number keys. Other playlists keep upstream playback and the upstream
  player screen.
- **TV app:** the palette changed for every screen, and the rail is now Live, Favourites, Library,
  Settings (Home is hidden).
- **Room:** v26 → v27 is additive. Downgrading to an upstream build would hit
  `fallbackToDestructiveMigration`.
- **Upstream bug fixed (all apps, Android 9 and older):** `ChannelDao.deleteOrphanedMetadata` used
  `DELETE FROM channel_metadata_bases AS base`. A table alias in `DELETE` needs SQLite 3.24, and
  Android 9 ships 3.22. The statement failed, and the transaction that writes a playlist's channels
  rolled back, so the list stayed empty. The alias is removed; the statement means the same on every
  version.

## Verification

Commands:

```bash
./gradlew :data:registry:test :data:testDebugUnitTest :app:tv:testDebugUnitTest :i18n:testDebugUnitTest
./gradlew test                      # every JVM unit test in the build
./gradlew :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.m3u.data.database.Migration26To27Test
./gradlew :data:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.m3u.data.repository.linkupdate.OfficialPageResolverTest   # starts the mock server itself
./gradlew :app:tv:assembleRelease   # signed with the upstream debug config, unchanged
```

Manual checks on an Android TV 12 arm64 emulator (1080p, xhdpi), against the live sources from
Türkiye:

- A fresh install opens on Live TV with 37 numbered channels.
- The automatic update ran on start.
- **Update links:** live progress ("Testing links… 14/33"), then the summary toast.
- **Diagnostics:** primary and fallback per channel with source, tier, height, latency and reason.
- **Playback:** ▲ ▼ zapping, `1` `0` jumps to channel 10, OK opens the channel list, ◀ ×3 shows
  "0:31 behind live", OK pauses.
- **Failover:** with a deliberately broken primary url, playback switched sources on its own.
- **Repair:** the next **Update links** repaired the channel (broken url kept last, as `failed`).
- **Error card:** a channel with no link shows Repair or Next channel.
- **Turkish:** the UI was checked under the system locale *Türkçe*.
- **Phase 2:**
  - With **Links from official websites** on, the update resolved Show TV from its live page with
    the single page read: `official-page`, 1080p, status ok. Show TV played.
  - Sözcü TV showed the hand-off card, and **Open in YouTube** opened Sözcü's live in YouTube for
    Android TV. Back inside YouTube goes to YouTube's home, which is YouTube's behaviour. Home or the
    launcher returns to TR TV.

Results:

- `./gradlew test`: 397 JVM tests across 13 modules, 0 failures. This includes the upstream
  smartphone, extension and settings suites.
- Room migrations: 7 instrumented tests (21→22 through 26→27) on the emulator, 0 failures.
- `OfficialPageResolverTest`: 3 instrumented tests on the emulator, 0 failures. They cover a static
  page, a page that builds its link in script (through the real WebView) and YouTube.
- `assembleRelease` (R8): a fresh install opens Live TV, the automatic update succeeds, and
  playback and zapping work. After clearing app data, turning on **Links from official websites**
  resolved Show TV (`official-page`, 1080p), it played, and the Sözcü TV hand-off opened YouTube.

On a real device, a Xiaomi Mi TV Stick (`MiTV-AESP0`: Android 9, API 28, `armeabi-v7a`, 1 GB RAM),
installed over Wi-Fi ADB:

- A fresh install of the release APK opens Live TV with 37 channels, and the first link update
  succeeds. This needed the upstream SQLite fix above; before it, the list stayed at 0 channels.
- TRT 1 plays at 1080p.
- `dumpsys dbinfo` shows no failed SQL statements.

The registry Action ran on GitHub (`sal-keskin/iptv`): it passed and committed a refreshed playlist.

Not verified: a Mi Box S itself. It has the same Android 9 and 32-bit setup as the stick.
