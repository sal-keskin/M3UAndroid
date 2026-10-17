# Install TR TV on a Xiaomi Mi Box S

## 1. Build the APK

You need JDK 17 and the Android SDK (platform 37, build-tools 37). Put the SDK path in
`local.properties` (`sdk.dir=…`).

```bash
./gradlew :app:tv:assembleRelease
```

- The APK is written to `app/tv/build/outputs/apk/release/tv-release.apk`.
- Release builds are signed with the upstream debug signing config, so no keystore is needed.
- For a debug build, run `./gradlew :app:tv:assembleDebug`. The output is
  `app/tv/build/outputs/apk/debug/tv-debug.apk`.
- The APK contains only `armeabi-v7a` and `arm64-v8a`. The Mi Box S runs a 32-bit ARM userland,
  so it uses `armeabi-v7a`.
- To point a build at a different registry, add
  `-PtrtvRegistryBaseUrl=https://raw.githubusercontent.com/<user>/<repo>/main`.

## 2. Enable ADB on the Mi Box S

1. Go to **Settings → Device Preferences → About** and select **Build** seven times. Developer
   options appear.
2. Go to **Settings → Device Preferences → Developer options**. Turn on **USB debugging**, and
   **Network debugging** if your box offers it.
3. Find the box's IP address in **Settings → Network & Internet → (your network)**.

## 3. Install

```bash
adb connect 192.168.1.50:5555
```

```bash
adb install -r app/tv/build/outputs/apk/release/tv-release.apk
```

- The first `adb connect` shows an "Allow USB debugging?" prompt on the TV. Accept it, then run the
  command again.
- TR TV installs next to any upstream M3U app (`com.m3u.tv`), because its id is
  `com.salkeskin.trtv`.

## 4. First launch

- Open **TR TV** from the Apps row. You can add it to favourites with **Add app to favourites**.
- The app opens straight into **Live TV**, with the channel list numbered by audience share. It is
  seeded from the bundled snapshot, so it appears even before the registry repository exists.
- On an unmetered network, a link update starts on its own. It tests every link on the box and
  takes about 10–30 s. You can watch it in **Diagnostics**.

## 5. Remote cheat sheet

| Where | Key | Action |
|---|---|---|
| Live list | OK / hold OK | Watch / toggle favourite |
| Live list | ▲ from the first row | **Update links**, **Diagnostics** |
| Player | ▲ ▼ | Change channel |
| Player | OK | Channel list |
| Player | ◀ ▶ | Rewind or forward 10 s; the bar appears |
| Player | OK while the bar is up | Pause or play |
| Player | Back | Previous channel, and leave when there is none |
| Player | 0–9 (other remotes) | Jump to a channel number |
| Player error | **Repair this channel** / **Next channel** | Re-test this channel's links / skip it |

The Mi Box S remote has no number or play/pause keys. ◀ ▶ then OK pauses, and every other action
works with the DPad. A phone remote app or a Bluetooth keyboard adds number keys.

## 6. Settings → Live TV

- **Update links:** run the whole pipeline now.
- **Diagnostics:** see every channel's primary and fallback links, their source, match tier, last
  check and failure reason. **Re-validate** runs a full update; **Repair this channel** runs it for
  one channel.
- **Start on last channel:** open the last channel you watched when the app starts.
- **Update links automatically:** a weekly update, plus one at start when the last update is more
  than a day old. Both wait for Wi-Fi or Ethernet.
- **Links from official websites** (off by default, for personal viewing only):
  - Show TV's link is read off its own live page, on this TV.
  - Sözcü TV opens in the YouTube app.
  - Turning it on runs an update. Turning it off removes those links at the same time.
  - Resolved links never leave the TV.
- **Registry URL:** where `channels.json`, `sources.json` and `playlist.m3u` live. Reset restores
  `https://raw.githubusercontent.com/sal-keskin/iptv/main`.

## 7. Publish the registry

Push `registry-template/` as the root of `https://github.com/sal-keskin/iptv`. Its Action builds
`playlist.m3u` and `candidates.json` every night. See
[`registry-template/README.md`](../../registry-template/README.md).

Until the repository exists, the app falls back to its bundled snapshot plus the public sources, so
everything still works.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Use an APK built from this branch: it keeps `armeabi-v7a` |
| A channel shows **No link yet** | No public source lists it (CNN Türk, TV2, …). For Show TV and Sözcü TV, turn on **Links from official websites**. Otherwise add a `pinnedUrls` entry in the registry |
| **Open in YouTube** says "The YouTube app is not installed on this TV" | Install YouTube for Android TV from the Play Store |
| Back from YouTube lands on YouTube's home | That is YouTube's behaviour. Press Home, or open TR TV from the launcher |
| Links show **Last known link** (amber) | Every candidate failed the last check. The old link is kept. Run **Update links** again later |
| Picture stutters | The app caps registry channels at 1080p. Check **Diagnostics** for a source with lower latency |
