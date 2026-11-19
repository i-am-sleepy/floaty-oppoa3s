# Floaty Volume

A tiny floating volume control for phones with broken hardware volume buttons,
built for the **OPPO A3s (Snapdragon 450, Android 9)** but compatible with
**Android 8.0 (API 26) and newer**.

No root, no Accessibility Service, no internet permission — just a bubble and
`AudioManager`.

## What it does

- A small draggable bubble floats above other apps.
- Tap it → a compact panel opens with **+ / −** step buttons.
- Sliders for **Media, Ringtone, Alarm, System, Call** streams (real system streams).
- Tap outside the panel (or ✕) to close it; the bubble re-appears in place.
- Runs in a foreground service (low-importance notification, "Stop" action).
- **Home-screen widget**: a full volume panel that lives in the launcher's
  process, so it survives an app kill. Tap its title to bring the bubble back;
  use its − / + buttons to adjust each stream. It stays in sync with the panel
  via the system volume-changed broadcast.
- Bubble position is remembered; it restarts after reboot if it was running.
- "Keep it alive" guidance: lock the app in Recents so the bubble can't be
  swiped away.

## Install

1. Download `app-debug.apk` from the latest
   [release](https://github.com/i-am-sleepy/floaty-oppoa3s/releases/latest)
   or from the CI run's **Artifacts**.
2. Allow "Install unknown apps" for your file manager / browser, then open the APK.
3. Launch **Floaty Volume** → **Grant permission** ("Display over other apps").
4. Tap **Start floating bubble**.

On OPPO/ColorOS also allow background activity:
Settings → Battery → App battery management → Floaty Volume → Allow background activity.
Otherwise ColorOS may kill the service.

## Compatibility notes

| Concern | Handling |
| --- | --- |
| Overlay window type | `TYPE_APPLICATION_OVERLAY` on API 28+, `TYPE_PHONE` on API 26–27 (`OverlayWindows.kt`) |
| `getStreamMinVolume` | API 28+ only; falls back to `0` below |
| Notification channel | Created only on API 26+ (always true here) |
| `PendingIntent` flags | `FLAG_IMMUTABLE` is safe from API 23 |
| Foreground service | `startForegroundService` on API 26+, plain `startService` below |
| `targetSdk 28` | Keeps pre-Android-10 overlay/background rules, so no `FOREGROUND_SERVICE_TYPE` or `POST_NOTIFICATIONS` needed |

## Build

The APK is built by GitHub Actions (`.github/workflows/build.yml`) on every push
to `main`; the workflow also publishes a release with the APK attached.

Local build:

```bash
./gradlew assembleDebug
# app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17 and Android SDK 34.

## Project layout

```
app/src/main/
  AndroidManifest.xml
  java/com/floaty/volume/
    MainActivity.kt      permission + start/stop screen
    FloatyService.kt     foreground service owning both overlay windows
    BubbleOverlay.kt     draggable bubble window
    VolumePanel.kt       panel window (sliders, +/-, outside-tap close)
    VolumeController.kt  AudioManager wrapper
    OverlayWindows.kt    version-aware overlay window type
    Prefs.kt             bubble position + running flag
    BootReceiver.kt      restart after reboot/update
    VolumeWidgetProvider.kt  home-screen widget (survives app kill)
  res/layout/            activity_main, overlay_bubble, overlay_panel, widget_volume
  res/xml/               widget_volume_info (widget metadata)
```

## License

MIT
