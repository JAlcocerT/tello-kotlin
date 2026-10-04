# Tello Native for Android

A 100% native Android controller for the DJI/Ryze Tello. It ports the proven
flight behavior from `desktop-rust` without Tauri, a WebView, FFmpeg, face
detection, or route planning.

## Features

- Tello SDK commands and 20 Hz RC control over UDP
- Native Tello Wi-Fi picker and per-socket Android network binding
- Telemetry, battery takeoff guard, input watchdog, and safe background landing
- Hardware H.264 decoding with `MediaCodec`
- Landscape camera-first Jetpack Compose UI with two touch control pads
- Takeoff, land, emergency land, speed mode, flips, photos, and MP4 recording
- Photos under `Pictures/Tello Native` and recordings under `Movies/Tello Native`

## Build and test

The command-line build needs JDK 17 and Android SDK Platform 37. The app still
targets the stable Android 16/API 36 behavior contract:

```bash
./gradlew testDebugUnitTest assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. Install it on
a connected phone with:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The unit tests exercise telemetry parsing, H.264 stream framing, acceleration,
opposing controls, and the stale-input watchdog. An emulator can verify the UI,
permissions, lifecycle, and media APIs, but it cannot validate real Tello Wi-Fi,
video timing, or flight. Those require the physical drone and an Android phone.

## Verified device

The signed production app has been installed through Obtainium and successfully
validated on a Google Pixel 9 Pro. Obtainium notified the phone about release
`android-v0.1.2`, updated it in place, and the top bar visibly confirmed the new
version.

## Obtainium releases

Use signed GitHub release APKs for Obtainium. Configure these repository secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Then push a tag such as `android-v0.1.2`. The release workflow builds a signed
APK and attaches it to a GitHub release. In Obtainium, add this repository's
GitHub URL and select the APK release asset.

Do not distribute CI debug APKs through Obtainium: changing debug signing keys
can prevent Android from installing updates over an earlier build.

## Physical-flight safety checklist

1. Use propeller guards and test indoors at low height with clear space.
2. Verify connect, live telemetry, and video while the drone remains landed.
3. Confirm every touch direction and STOP at low altitude.
4. Background the app and verify it lands immediately.
5. Test photos and short recordings before flips or fast mode.
