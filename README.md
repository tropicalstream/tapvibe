# TapVibe

An audio-reactive visualizer suite **and** a zen breathing mode for the RayNeo X3
Pro, with a **companion drag-and-drop uploader** so you can send music to the
glasses and have the visuals react to *that* track (not the room). Neon,
projected-3D wireframes glowing over pure black.

Self-contained: custom Canvas rendering inside a dual-draw `BinocularSbsLayout`,
NanoHTTPD for the companion server, synthesized-free playback via `MediaPlayer`.

## Send music to the glasses (companion app)

1. On the glasses, open **Music Library** from the menu — it shows an address like
   `http://192.168.1.42:8080`.
2. On your **phone or computer** (same Wi-Fi), open that address in a browser.
3. **Drag audio files onto the page** (or tap to pick). They upload and appear in
   the on-glasses library instantly. Delete from the page with the ×.

Uploads are copied in as **raw bytes with their original extension preserved** — no
re-encoding, never touched as text — so the container/codec stays intact (the
TapInsight "encapsulation" bug). Supported: mp3, m4a/aac, flac, wav, ogg/opus.

## Menu & controls (right temple pad)

| | Menu | Library | IP Radio | Visualizer |
|---|---|---|---|---|
| **Swipe ↑ / ↓** | Choose | Select track | Browse | — |
| **Swipe ← / →** | — | — | Cycle ranking | Prev / next visualizer |
| **Tap** | Enter | Play track | Open / tune in | Play / pause |
| **Hold** | — | — | ★ favorite | — |
| **Double-tap** | — | Back to menu | Back | Back |

Menu: **Zen Breathing · Music Library · IP Radio · Live Visualizer (mic)**.

## IP Radio

A full browser over the [radio-browser.info](https://www.radio-browser.info/) community
directory (~50k Icecast/SHOUTcast streams — the same catalogue VLC's Icecast discovery
draws on): **Favorites · Trending · Top Voted · Most Played · Genres · Countries**. The
station list pages in lazily as you scroll (the whole directory is reachable), swiping
← / → re-ranks the current list live, and holding a station stars it into Favorites
(persisted on-device). Tuning in streams the station through `MediaPlayer` exactly like
an uploaded track — the visualizer attaches to that same audio session, so live radio
drives the neon scenes identically to your own music.

## Audio → visuals

Two capture paths, one analysis pipeline (log-FFT bins → Sub/Bass/Mid/Treble,
auto-gain, asymmetric attack/decay, RMS, bass beat):

- **Uploaded music (featured):** `Visualizer` attaches to the `MediaPlayer`'s **own
  audio session**, so it reacts cleanly to the exact track you're playing. (Session-0
  global-mix capture is silenced for third-party apps — that's why an earlier cut
  showed "no audio".)

## Visualizers

1. **Tunnel** — warp-speed frequency rings (concept 3).
2. **Orb** — bass-pulsing wireframe sphere with treble spikes (concept 2).
3. **Terrain** — scrolling FFT-history heightfield (concept 1).
4. **Swarm** — particles pulled between Sub/Bass/Mid/Treble gravity anchors (concept 4).
5. **Waveform** — double-helix ribbon from raw time-domain samples (concept 6).

## Zen mode

Concentric breathing torus rings paced to box-breathing (4-4-4-4) with an
INHALE/HOLD/EXHALE guide; ambient audio delicately ripples the rings.

## Build & install

```bash
cd /Users/me/Projects/tapvibe
./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk && adb shell am start -n com.tropicalstream.tapvibe.debug/com.tropicalstream.tapvibe.MainActivity
```

Grant the mic permission on launch (`Visualizer` needs `RECORD_AUDIO` even for our
own session).

## Architecture

`net/CompanionServer.kt` (NanoHTTPD :8080, drag-drop page, binary-preserving uploads)
→ `music/MusicLibrary.kt` (raw-byte storage) → `music/MusicPlayer.kt` (MediaPlayer,
plays files or streams a URL, exposes session id) → `audio/AudioAnalyzer.kt`
(Visualizer-on-session **or** mic) → `audio/Audio.kt` snapshot → `scenes/*` →
`render/StageView.kt` (menu/library/radio/visualizer) → `MainActivity.kt` (Host).
`radio/RadioStore.kt` (radio-browser.info client) + `radio/RadioBrowser.kt`
(navigation state machine + async paging) power IP Radio. Reused:
`ui/BinocularSbsLayout.kt`, `input/TrackpadGestureEngine.kt`.
