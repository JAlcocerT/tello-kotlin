# Tello Native Android — Learnings and Test Record

- Last updated: 2026-10-03
- Branch: `feature/android-native`
- Initial implementation commit: `685641c`

## Goal and scope

We created a 100% native Android equivalent of `desktop-rust` focused on the
features needed to fly the DJI/Ryze Tello:

- Drone connection and SDK commands over UDP
- Telemetry and battery protection
- Touch flight controls, speed modes, flips, takeoff, and landing
- Live H.264 video
- Photos and MP4 recording
- A camera-first landscape UI

Face detection and route/mission functionality were deliberately excluded.
The existing Rust, face-detection, and route applications were not modified by
this work.

## Architecture chosen

The project lives under `android-native/` and uses:

- Kotlin
- Jetpack Compose for the native UI
- `DatagramSocket` for command, telemetry, and video UDP traffic
- Android `MediaCodec` for hardware-backed H.264 decoding
- `MediaMuxer` for MP4 creation
- `MediaStore` for photos and recordings
- `WifiNetworkSpecifier` and per-socket `Network.bindSocket()` calls for the
  Tello's local-only Wi-Fi network

We did not reuse Rust through JNI. The Tello protocol layer is small, while JNI
would add lifecycle, packaging, debugging, and ABI complexity. We instead
ported the proven behavior and safety rules from `desktop-rust`.

## Flight behavior ported

- Command mode, battery query, and video stream startup use retry handling.
- Takeoff is blocked below 20% battery.
- A warning is shown below 30% battery.
- RC commands run at 20 Hz.
- Slow mode starts at 30 RC units.
- A held control accelerates to 100 RC units after two seconds.
- Fast mode immediately uses 100 RC units.
- Opposing directions cancel each other.
- Stale touch input is cleared after 750 ms.
- Releasing a control immediately removes that direction.
- Losing the foreground clears RC input and requests landing when airborne.
- ViewModel teardown sends zero RC, land, and stream-off commands on a
  best-effort basis before closing sockets.
- Takeoff timeout is treated as an uncertain airborne state so landing remains
  available.

## Android-specific lessons

### Tello Wi-Fi has no internet

Android may prefer mobile data because the Tello access point has no internet.
The app therefore asks Android for a temporary `TELLO-*` Wi-Fi connection and
binds every Tello UDP socket to the returned `Network`. Binding the entire app
process was avoided.

The required permissions are broader than Wi-Fi discovery alone:

- `NEARBY_WIFI_DEVICES` on Android 13+
- Fine and coarse location through Android 12
- `ACCESS_NETWORK_STATE`
- `CHANGE_NETWORK_STATE`
- `ACCESS_WIFI_STATE`
- `CHANGE_WIFI_STATE`
- `INTERNET`, because Android uses it for IP sockets even on a local network

The emulator test found that omitting `CHANGE_NETWORK_STATE` caused a
`SecurityException` immediately after the user accepted the nearby-device
permission. This was fixed and the Wi-Fi request boundary was wrapped so an
OEM rejection becomes a status message instead of an app crash.

The Wi-Fi request has a 30-second timeout. This prevents the Connect button
from remaining in a busy state forever when the system selector is cancelled
or no `TELLO-*` network is available.

### Video cannot use the desktop FFmpeg strategy

The Tello sends a raw Annex-B H.264 byte stream over UDP port 11111. Android
does not rely on a system FFmpeg executable, so the app includes a small parser
that:

- Accepts arbitrary UDP packet boundaries
- Finds three-byte and four-byte Annex-B start codes
- Preserves partial NAL units between packets
- Detects SPS and PPS headers
- Feeds complete NAL units to `MediaCodec`

The decoder outputs directly to a `SurfaceView`. This avoids the desktop
application's H.264-to-JPEG conversion and repeated Base64/UI copies.

The parser waits for a following start code before emitting a NAL unit. That
adds one NAL of buffering but ensures the unit is complete.

### Recording shares the H.264 stream

Recording uses the same parsed H.264 units as the decoder. `MediaMuxer` receives
SPS/PPS codec data and writes video samples with monotonic timestamps.

Recording waits for an IDR/keyframe before writing its first sample. Empty or
failed recordings are removed instead of publishing an invalid MP4. Successful
recordings are stored under `Movies/Tello Native`.

Photos use `PixelCopy` on the video surface and are stored under
`Pictures/Tello Native`. Like the desktop application's saved JPEG, the photo
captures the camera image and not the Compose HUD overlay.

### Lifecycle is part of flight safety

The Activity keeps the display awake and uses a landscape controller layout.
When the app leaves the foreground it clears all held controls and lands if the
drone is believed to be airborne. State updates use atomic `StateFlow` updates
because telemetry, video, command, and UI callbacks arrive from different
threads.

The app does not attempt to keep flying invisibly in the background.

## UI implemented

- Camera-first landscape layout
- Connection and status bar
- Flight/landed, battery, height, speed mode, and recording HUD
- Battery, height, speed, temperature, and Wi-Fi telemetry panel
- Takeoff, land, and emergency-land controls
- Slow/fast mode toggle
- Separate movement and altitude/yaw touch pads
- Immediate STOP controls
- Left, right, forward, and back flips
- Photo and recording controls
- Disabled states until the relevant connection, flight, or video state exists

The control column is scrollable so all controls remain reachable on shorter
landscape screens.

## Build-system lessons

The host initially had no Java, Gradle, Android SDK, ADB, or emulator.
A user-local command-line toolchain was installed instead of the full Android
Studio IDE:

```text
JDK: /home/jalcocert/.local/share/tello-android/jdk
SDK: /home/jalcocert/.local/share/tello-android/sdk
```

`android-native/local.properties` points to that SDK and is intentionally
ignored by Git.

Android Gradle Plugin 9.4 rejected the old `org.jetbrains.kotlin.android`
plugin because modern AGP provides built-in Kotlin support. The project was
updated to the AGP 9 model while retaining the Compose compiler plugin.

The September 2026 AndroidX versions require compile SDK 37.2. The app compiles
against 37.2 while targeting API 36 runtime behavior and supporting API 29+.

The repository contains a Gradle 9.6 wrapper. On this PC, use:

```bash
export JAVA_HOME=/home/jalcocert/.local/share/tello-android/jdk
export ANDROID_HOME=/home/jalcocert/.local/share/tello-android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
make test-android
make build-android
```

## Tests completed on this PC

### JVM unit tests

All four tests pass:

- Tello telemetry parsing
- Opposing RC controls cancel
- Held controls accelerate and stale input stops
- Annex-B parsing across UDP packet boundaries

Command used:

```bash
./gradlew testDebugUnitTest
```

### Static and build verification

The following completed successfully together:

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug assembleRelease
```

Verified results:

- Kotlin and Android resources compile
- Debug APK builds
- Android lint completes without errors
- Release code is minified by R8
- Release resources are shrunk
- Release APK builds with a supplied signing key
- The APK signature verifies with Android `apksigner`
- Package ID is `tech.jalcocer.tello`
- Version name is `0.1.0`
- Minimum SDK is 29

The local release build was signed with a temporary test key only. It must not
be distributed or used as the Obtainium signing identity.

### Emulator verification

An Android API 36 x86_64 emulator was installed and booted with KVM. The APK
was installed through ADB and launched from a cold start.

Verified in the emulator:

- Landscape Activity launch
- Camera-first UI rendering at 2340×1080
- Scrollable controls and correct disabled states
- Native nearby-device permission prompt
- Native "Connect to device" Wi-Fi selector
- Cancellation/return lifecycle
- App process remains alive after the corrected network request
- No application crash during normal launch and selector flow

The emulator initially exposed the missing `CHANGE_NETWORK_STATE` permission;
the permission was added and the complete flow was repeated successfully.

The emulator and its API 36 system image were removed after testing because
they consumed approximately 6.7 GB and the PC was close to full. The build
toolchain remains installed. Reinstalling an emulator is possible when another
UI test is needed.

### What a PC emulator cannot verify

An Android emulator cannot advertise or join the physical drone's `TELLO-*`
access point in the same way as a phone. Therefore these items remain unproven
until a physical Android phone and Tello are used:

- Actual command and response traffic to `192.168.10.1:8889`
- Telemetry reception on UDP 8890
- H.264 packet reception and device-specific `MediaCodec` timing
- Live video latency and decoder compatibility on the target phone
- Photo contents from a real stream
- MP4 playback and duration from a real Tello stream
- Touch direction correctness against physical drone motion
- Takeoff, landing, flips, acceleration, watchdog, and background landing

## APK artifacts produced

Local build paths:

```text
app/build/outputs/apk/debug/app-debug.apk
app/build/outputs/apk/release/app-release.apk
```

After moving the Android project into its standalone repository, the clean
debug build produced:

```text
Debug: feb300d50d0e31cfa8c054ed3890b1cd8f3b976aca37a0bf46e803bc8de0bdb0
```

These generated APKs are ignored by Git. Rebuilding after any source change
will produce different checksums.

The standalone repository was tested with `testDebugUnitTest assembleDebug`;
all 44 Gradle tasks completed successfully. The permanent key was then used for
`testDebugUnitTest lintDebug assembleRelease`; all 86 tasks completed and
`apksigner` verified the APK with v2 signing and the expected certificate.

## Obtainium and GitHub releases

`.github/workflows/android-release.yml` is prepared to run for tags matching:

```text
android-v*
```

It installs the required SDK, runs unit tests, builds a minified signed APK,
and attaches the APK to a GitHub release. Obtainium can then follow the GitHub
repository and install that release asset.

A permanent release keystore must be created and preserved. Configure these
GitHub repository secrets before creating the first release tag:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

The first production keystore must remain the signing key for every future
update. A debug APK or locally test-signed release APK cannot be upgraded in
place to an APK signed by a different key.

The standalone project and root-relative workflow are committed and pushed to
the public repository at `https://github.com/JAlcocerT/tello-kotlin`. All four
GitHub secrets are configured. The `android-v0.1.0` run failed before reaching
the key or Gradle because `android-actions/setup-android@v3` requested the
removed Android SDK package `tools`. The workflow was updated to the Node 24
action releases (`checkout@v7`, `setup-java@v6`, `setup-android@v4`, and
`action-gh-release@v3`); setup-android now installs only the three packages the
project needs. The failed tag is retained. The corrected `android-v0.1.1` run
passed all steps in 3m30s and published a non-draft, non-prerelease APK:

```text
Asset: tello-native-android-v0.1.1.apk
Size: 1,643,155 bytes
SHA-256: e4a59705885a90580b26572daf6b41b92b0cc1e2aa2f662f23324c31c7e712a7
Signing certificate: f0ada1be3083d3e37a5c3e9222d5978854e3c3c67a91a5323e42c3881337898e
```

The public asset was downloaded to the PC and independently passed
`apksigner verify` using APK Signature Scheme v2.

## Remaining physical test plan

1. Store a second encrypted/off-device backup of the final signing key.
2. Add the public repository URL to Obtainium and install `android-v0.1.1`.
3. With propellers removed, test Wi-Fi selection, command mode, telemetry,
   video, photo, and recording.
4. Play the saved MP4 and inspect the photo before flight.
5. Fit propeller guards and test at low altitude in a clear indoor space.
6. Confirm movement, altitude, yaw, STOP, slow/fast mode, and acceleration.
7. Background the app while hovering and verify the safety landing.
8. Test one flip direction at a time only after basic control is reliable.

## Standalone repository

The Android implementation now lives at `/home/jalcocert/Desktop/tello-kotlin`
with the Gradle project at the repository root. Generated build directories,
local SDK configuration, keystores, and Base64 key material are ignored. No
signing artifact was copied into Git.
