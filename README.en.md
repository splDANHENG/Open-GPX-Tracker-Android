# Open GPX Tracker for Android

An unofficial Android recreation of [iOS Open GPX Tracker](https://github.com/merlos/iOS-Open-GPX-Tracker), licensed under GPL-3.0-or-later.

Android 0.12.1; minimum Android 6.0 (API 23).

## Current milestone

The app displays an interactive OpenStreetMap map using MapLibre Native. It currently supports:

- OpenStreetMap and OpenTopoMap map sources, with panning, zooming and rotation;
- a 250 MB ambient tile cache for map areas actually viewed; cached tiles can be reused without a network connection, subject to cache eviction and server expiry; there is no area pre-download;
- foreground precise or approximate location permission;
- a unified permission checklist on first launch and whenever the app is reopened with missing location, notification or overlay permission; each missing permission can be requested again, and app settings are available for permissions blocked by the system;
- live location updates through Android system location providers, without bundling Google Play services;
- a current-location marker and accuracy area;
- live GPS status using satellite C/N₀/fix counts when available, with stale fixes marked lost after 10 seconds;
- current ground speed in km/h and altitude in meters, hidden when the location is stale; Mean Sea Level altitude is preferred on Android 14+ when available, otherwise WGS84 altitude is used;
- automatic map following, suspended when the user pans;
- one-tap recentering and follow restoration.
- track recording with start, pause, resume and finish controls; a location foreground service keeps recording after the app leaves the screen, with a persistent notification that returns to the app for controls;
- pause and finish controls lock one minute after a new track starts; tap the separate lock icon three times quickly to unlock them; controls relock after 15 seconds without interaction;
- live route drawing, elapsed time, distance and point count;
- keep recording in the same track segment through weak or missing GPS signal; pausing and resuming starts a new segment.
- automatic local SQLite storage of recordings and their track points;
- a history list for reopening recorded tracks, including interrupted recordings recovered as paused;
- history tracks can be opened and continued in a new segment, including tracks previously marked finished; the history list shows a separately stored place name instead of a point count;
- deletion of saved tracks after confirmation, without deleting GPX files already exported;
- GPX 1.1 export through a named-file dialog with an editable place-name prefix suggested from the track's first point using OpenStreetMap Nominatim: save to `Documents/OpenGPXTracker` on Android 10+, save and clear the current recording while retaining history, or choose "Save as" in the Android file picker. On older Android versions, export uses the file picker.
- the export dialog pre-fills the generated GPX name and clears it when the user focuses the name field to type a replacement;
- after a successful GPX export, the submitted place-name prefix is saved as metadata on the track and shown in its history entry;
- import GPX tracks from the Android file picker into history, preserving track segment breaks; imported tracks can be opened and continued recording;
- browse, share, and delete GPX files in the default `Documents/OpenGPXTracker` export folder on Android 10+; deleting a file leaves its local history track intact.

The recording service runs only while a track is actively recording. Pausing or finishing stops its location updates and removes the notification. If Android terminates the process, the saved track is recovered as paused when the app next opens; Android does not guarantee uninterrupted recording after a process kill or force stop.

While recording, unavailable location or a provider error does not pause or finish the track. The service retries location collection after an error, and the elapsed timer keeps running. Fixes received after a gap continue the current segment, matching the iOS reference behavior.

## Requirements

- Android Studio compatible with Android Gradle Plugin 8.13.2
- JDK 17
- Android SDK 36

## Build

```shell
./gradlew assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

## Test

Start an Android emulator or connect a dedicated test device, then run:

```shell
./gradlew connectedDebugAndroidTest
```

The connected test task may uninstall the app and erase its local track database. Do not run it on a device containing tracks you need to keep.

## Map data

Map rendering is provided by MapLibre Native. Map data and raster tiles are provided by OpenStreetMap contributors. OpenStreetMap attribution is always visible on the map.

The public OpenStreetMap tile service must not be used for bulk downloading or heavy production traffic. A production release should use a compliant hosted tile provider or self-hosted tiles.

The place-prefix suggestion sends the track's first coordinate to the public Nominatim reverse-geocoding service only when the export dialog is opened. Results are cached per track, requests are serialized and limited to one per second on the device, and the user can edit or clear the suggestion. Public distribution at scale requires a replaceable geocoding endpoint or self-hosted service in line with the [Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/).

## Recording overlay

With the system overlay permission, an overlay appears while recording. It shows coordinates, altitude, current speed, distance and elapsed time to the second. Drag to move it; the position is remembered. Tap to return to the app. Pausing or finishing removes it. GPS recording also works without overlay permission.

## Licence and attribution

Application source: **GPL-3.0-or-later**. See [LICENSE](LICENSE), [NOTICE](NOTICE), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

The iOS reference is by Juan M. Merlos and contributors, Copyright (C) 2014-2023, under GPL version 3 or later. This Android version is an unofficial recreation, not an official release or endorsement by the iOS maintainers. Reference commit: `cd0954ffcd5ae688b76f8b796607c98ea5c6a0eb`. Android adaptations and fixes were made in 2026.

When distributing APKs, provide their corresponding source and preserve applicable licence and attribution notices. Build tools and third-party libraries retain their own licences. No proprietary Google Play services client is bundled in version 0.12.1.

## Known limitations

- Very large tracks still require a full map redraw; performance with hundreds of thousands of points has not been stress-tested.
- GPX import retains track points, segment boundaries, altitude and timestamps; waypoints, routes and arbitrary extension metadata are not imported.
- GPX file management lists the default export directory on Android 10+. Other files can be selected using the system file picker.
- Browsed-map caching is limited to 250 MB and does not guarantee complete offline coverage. Public tile services do not allow area pre-download.
- The app has no analytics or track-upload service. Android backup settings and destinations selected for sharing/export apply independently.
