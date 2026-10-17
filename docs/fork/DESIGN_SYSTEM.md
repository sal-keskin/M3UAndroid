# Gece: the TR TV design system

*Gece* means "night" in Turkish. It is the visual language of the TR TV fork's Android TV app: dark
ink surfaces that disappear around the picture, a near-white focus container that you can read from
the couch, and one broadcast accent, crimson, reserved for live state and channel numbers.

- **Code:**
  - [`TvStyle.kt`](../../app/tv/src/main/java/com/m3u/tv/TvStyle.kt): colours, fonts, destinations.
  - [`TvDesign.kt`](../../app/tv/src/main/java/com/m3u/tv/TvDesign.kt): spacing, shapes, type, motion.
  - [`live/TvLiveComponents.kt`](../../app/tv/src/main/java/com/m3u/tv/live/TvLiveComponents.kt): components.
- **Canvas:** 960 × 540 dp. This is what every 1080p Android TV box, including the Mi Box S,
  reports at xhdpi.
- **Rules it builds on:** [`app/tv/AGENTS.md`](../../app/tv/AGENTS.md) and
  [`docs/ai/playbooks/android-tv-ui.md`](../ai/playbooks/android-tv-ui.md).

## Principles

1. **The picture is the hero.** Overlays are transient and edge-anchored, and they never take focus
   from the video unless you asked for a list or an error needs a decision.
2. **Focus is a state pair.** The focused container turns near-white (`Focus`) and its content turns
   ink (`OnFocus`). It also scales up slightly. It casts no shadow: 1 GB Android 9 sticks redraw
   a shadow on every frame of the scale animation. Focus never relies on colour alone.
3. **Crimson means live.** `Live` appears only on live badges, the channel number in focus or on
   air, the playhead and progress. Never use it for decoration.
4. **One leading edge.** Headers, rows and panels share the 48 dp overscan-safe margin.
5. **Remote first.** Every action is reachable with the DPad and OK. Holding OK is the only
   secondary gesture. Number and media keys are shortcuts, never requirements: the Mi Box S remote
   has neither.

## Tokens

### Colour (`TvColors`)

| Token | Value | Use |
|---|---|---|
| `Background` | `#07090D` | App canvas: a flat fill, with no picture behind it |
| `BackgroundSoft` | `#0D1118` | Secondary canvas |
| `Surface` | `#141A24` | Panels, rows at rest, cards |
| `SurfaceRaised` | `#1D2533` | Number badges at rest, toasts, stream blocks |
| `Outline` | `#FFFFFF` at 10 % | Hairlines on surfaces |
| `Focus` | `#F4F6FA` | Focused container (rows, buttons, rail) |
| `OnFocus` | `#0A0D12` | Text and icons on `Focus` |
| `Live` | `#D91F35` | LIVE badge, emphasized channel number, playhead, progress |
| `OnLive` | `#FFFFFF` | Content on `Live` |
| `Success` | `#34D399` | Link works |
| `Accent` | `#FFB547` | Last known link (stale), not tested yet, switching source |
| `Danger` | `#FF6B6B` | Not working, failures |
| `TextPrimary` | `#F4F6FA` | Titles, values |
| `TextSecondary` | `#AAB4C3` | Supporting lines |
| `TextMuted` | `#7C8798` | Hints, disabled |
| `Scrim` | `#000000` at 72 % | Contrast surface behind text over video |

Contrast on `Background`:

- `TextPrimary` is 17:1.
- `TextSecondary` is 9:1.
- `TextMuted` is 5.5:1 (4.8:1 on `Surface`), so it is still for hints only.
- `OnFocus` on `Focus` is 18:1.
- `OnLive` on `Live` is 5.0:1.

### Type (`TvType`)

Inter for text. Lexend Exa, a wide geometric face, for figures that must be read across a room.

| Token | Size / line | Use |
|---|---|---|
| `Numeral` | 64 / 72 Lexend Exa | Number entry |
| `Display` | 40 / 48 Bold | Rare hero figures |
| `Headline` | 28 / 34 SemiBold | Screen titles |
| `Title` | 20 / 26 SemiBold | Panel titles, player channel name, clock |
| `TitleSmall` | 17 / 22 SemiBold | Row titles |
| `Body` | 15 / 21 | Subtitles, explanations |
| `Label` | 13 / 17 Medium | Supporting lines, buttons, hints |
| `Caption` | 11 / 14 Bold, +0.8 tracking | Badges (LIVE / CANLI) |
| `Number` | 15 / 18 Lexend Exa | Channel numbers in badges |

### Spacing, shape and motion

| | Tokens |
|---|---|
| `TvSpacing` | 2 · 4 · 8 · 12 · 16 · 24 · 32 · 48. `SafeHorizontal` 48, `SafeVertical` 28 (5 % overscan) |
| `TvShapes` | `Xs` 4 · `Sm` 8 (badges) · `Md` 12 (rows, logos) · `Lg` 16 (panels, toasts) · `Xl` 24 · `Pill` |
| `TvMotion` | Focus 150 ms · overlay 200 ms · banner hold 4 s · toast hold 2.5 s (summary 7.5 s) · timeshift hold 5 s · row focus scale 1.03 · card focus scale 1.08 |

## Components

| Component | Where | Anatomy and states |
|---|---|---|
| **ChannelNumberBadge** | Rows, preview, banner | ≥ 44 × 32, `Sm` corners, `Number` type. At rest it is `SurfaceRaised`. **Emphasized** (focused or on air) it is `Live` with `OnLive`. Inside a focused diagnostics row it is a tinted ink. |
| **StatusDot** | Rows, preview, stream blocks | 10 dp circle: `Success` works or **Opens in YouTube**, `Accent` stale or untested, `Danger` not working, `TextMuted` no link. It is always next to a text label somewhere on screen, never colour alone. |
| **LiveBadge** | Player banner | Pill. At the live edge it is crimson with a white dot and **LIVE / CANLI**. When rewound or paused it turns `SurfaceRaised` with a crimson dot and `−0:31`. |
| **ChannelLogo** | Rows (44), banner (56), preview (96) | A soft white tile behind the logo: broadcasters ship logos for light backgrounds. Falls back to a TV glyph. |
| **TvChannelRow** | Live list, player channel list (compact) | Number, logo, title over group, status. Focused it scales ×1.03 with the white container, an ink title and a crimson number. On air it shows an equalizer icon and "Now playing". Hold OK to toggle favourite. |
| **TvActionButton** | Headers, settings, error card | The upstream button with the new tokens. Checked toggles use a 72 % `Focus` fill. |
| **LinkUpdateProgressLine** | Live, Diagnostics, Settings | A `Label` line ("Testing links… 14/33") over a 4 dp crimson bar. The fraction is weighted by phase. |
| **TimeshiftBar** | Player banner | A white 24 % track, a crimson elapsed segment and a 16 dp `Focus` playhead. The right end is the live edge. |
| **TvToast** | App overlay | Opaque `SurfaceRaised`, `Lg` corners, 16 dp shadow and an accent dot (`Accent` automatic, `Success` done, `Danger` failed). It sits top-end over video and bottom-end while browsing, so it never covers the focused header. It is never focusable. |
| **InfoBanner** | Player | A bottom gradient scrim (0 → 78 % → 96 % black) holding the badge, logo, title, "group · source · height · 2/3", the LIVE badge and the clock. Below it sit the timeshift row and a key hint. |
| **NumberEntry** | Player, top-end | A `Numeral` digit buffer with a blinking-style underscore while more digits can follow. It turns `Danger` with "No channel 39" when a finished number matches nothing. |
| **ChannelListOverlay** | Player, left 400 dp | A horizontal gradient panel with compact rows. Focus starts on the channel on air. |
| **ErrorCard** | Player, centre | Title, explanation, then **Repair this channel** (initial focus) and **Next channel**. Repair keeps focus and its label changes to "Repairing ATV…". |
| **HandoffCard** | Player, centre | The ErrorCard's shape for a channel whose official live is on YouTube: "Sözcü TV streams on YouTube", one line saying it opens in the YouTube app, then **Open in YouTube** (initial focus) and **Next channel**. No video plays behind it. ▲ ▼ and number keys still zap. |
| **DiagnosticsRow / StreamBlock** | Diagnostics | Rows: number, title, "source · tier" and status. Stream blocks: Primary or Fallback n, url (middle ellipsis), "source · tier · 1080p · 225 ms", "Checked 3 min ago · Reason: HTTP 403". |

## Patterns

### Live screen

- The ranked list takes 58 % of the width and a non-focusable preview pane the other 42 %.
- Initial focus is the channel on air, else the last watched, else channel 1.
- ▲ from the first row reaches **Update links / Diagnostics**.

### Player keys

| Key | Action |
|---|---|
| ▲ / CH− | Previous channel number (wraps) |
| ▼ / CH+ | Next channel number (wraps) |
| 0–9 | Number entry. It commits after 1.5 s, or at once when no longer number exists (`4` of 38 jumps immediately) |
| OK | Channel list. While the timeshift bar is up, OK pauses or plays instead |
| ◀ / ⏪ | Back 10 s inside the timeshift window, and shows the bar |
| ▶ / ⏩ | Forward 10 s. Within 10 s of live it jumps to live |
| ⏯ / Space | Pause or play |
| Info / Menu / Guide | Show the banner |
| Back | Hide the bar, then the previous channel (history stack), then leave the player |

### Automatic source handling

- When a source fails or stalls for 10 s, the toast says **"Switching source… (2/4)"** and the next
  url plays. The failed url moves to the end of the list for the next time.
- A rewound position the CDN has already dropped returns to live with **"Back to live"** instead of
  counting as a failure.
- When every url fails, the ErrorCard appears.

### Diagnostics

The channel list sits on the left and the focused channel's streams on the right. ◀ ▶ moves
between the list and **Repair this channel**. The header shows the last report:
"Links: X updated, Y unchanged, Z still failing. New fuzzy matches to review: N".

## Do and don't

| Do | Don't |
|---|---|
| Put live information on crimson | Use crimson for decoration or emphasis in prose |
| Keep overlays on the edges and transient | Cover the centre of the picture with controls |
| Put a scrim behind any text drawn over video | Rely on the broadcaster's picture for contrast |
| Keep a button enabled and ignore repeats while it works | Disable the focused button: on TV, focus jumps away |
| Show colour and a label together | Signal state with a dot alone |
