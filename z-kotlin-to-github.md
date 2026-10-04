# Kotlin to GitHub Releases: What We Learned

Last updated: 2026-10-03

This document records how the native Tello Android app went from a local
Kotlin project to a signed public GitHub Release that Obtainium can install.
It also explains the first failed GitHub Actions run and why Java appears in a
Kotlin project.

## Final result

- Repository: `https://github.com/JAlcocerT/tello-kotlin`
- Package ID: `tech.jalcocer.tello`
- Latest successful release: `android-v0.1.2`
- Release asset: `tello-native-android-v0.1.2.apk`
- Release page: `https://github.com/JAlcocerT/tello-kotlin/releases/tag/android-v0.1.2`
- APK SHA-256: `670f08cfb87480279d11a2b71b5b555258d62325e17c896609acae94516b1bca`
- Signing-certificate SHA-256:
  `f0ada1be3083d3e37a5c3e9222d5978854e3c3c67a91a5323e42c3881337898e`
- Latest workflow run: `37201016041`
- Result: all release steps passed in 3m22s
- Obtainium result: update notification received and `v0.1.1` updated in place
  to `v0.1.2` on a Pixel 9 Pro

The downloaded public APK was independently checked on this PC with
`apksigner verify`. It verifies with APK Signature Scheme v2 and the same
permanent certificate used for the local signed build.

## Why a Kotlin app runs Java setup

The app source is Kotlin and the UI is Jetpack Compose. It is still normal and
necessary for the build to install a Java Development Kit:

1. Gradle runs on the Java Virtual Machine.
2. The Kotlin compiler itself runs on the JVM.
3. The Android Gradle Plugin requires a compatible JDK.
4. This project is configured for Java 17 bytecode compatibility.

Therefore this workflow step is expected:

```yaml
- uses: actions/setup-java@v6
  with:
    distribution: temurin
    java-version: "17"
    cache: gradle
```

It does not convert the application into a Java application, add a WebView, or
make the result less native. It supplies the build runtime used to compile the
Kotlin Android project.

Most importantly, Java setup did **not** cause the first CI failure. The failed
run shows `actions/setup-java@v4` completed successfully.

## What actually failed in `android-v0.1.0`

The first tag triggered workflow run `37141059773`. Its sequence was:

```text
checkout@v4                 passed
setup-java@v4               passed
setup-android@v3            failed
keystore restoration        skipped
Gradle tests/release build  skipped
GitHub Release creation     skipped
```

`android-actions/setup-android@v3` had the following old default packages:

```text
tools platform-tools
```

The current Android SDK repository no longer provides the legacy package named
`tools`. The action eventually ran this equivalent operation:

```text
sdkmanager tools
```

It failed with:

```text
Warning: Failed to find package 'tools'
```

That was the real error. The Node.js 20 deprecation messages were warnings,
not the cause of the failed run. The signing key, GitHub secrets, tests, and
Gradle build had not been reached, so the failure said nothing about their
validity.

## The workflow fix

The actions were updated to their current Node.js 24 generations:

```yaml
- uses: actions/checkout@v7
- uses: actions/setup-java@v6
- uses: android-actions/setup-android@v4
- uses: softprops/action-gh-release@v3
```

The Android packages are now explicit:

```yaml
- uses: android-actions/setup-android@v4
  with:
    packages: platform-tools platforms;android-37.2 build-tools;36.0.0
```

This avoids the removed `tools` package and installs exactly what the project
needs. The corrected `android-v0.1.1` run passed SDK setup, key restoration,
tests, signing, asset naming, and release creation.

## Repository creation lesson

This command initially failed:

```bash
gh repo create tello-kotlin --public --source=. --remote=origin --push
```

The reason was that the newly initialized repository had no commit to push.
The correct order for a new repository is:

```bash
git init
git branch -m main
git add .
git commit -m "Initial native Android Tello controller"
gh repo create tello-kotlin --public --source=. --remote=origin --push
```

That setup is now complete and must not be repeated in this repository.

## Minimal repository contents

Source code, Gradle wrapper/configuration, documentation, and the workflow are
tracked. Generated and machine-specific files are not:

```gitignore
.gradle/
.kotlin/
.idea/
build/
app/build/
local.properties
*.iml
captures/
*.jks
*.keystore
*.p12
*.base64
```

Seeing `build`, `.gradle`, or `.kotlin` in `ls -a` is normal. Their presence on
disk does not mean Git will commit them. Use these commands to distinguish the
two concepts:

```bash
git status --short
git ls-files
git check-ignore -v build .gradle .kotlin app/build local.properties
```

The permanent key must never be copied into the repository.

## Signing-key lessons

The first key was created as root in the literal example directory and used
`C=41710`. That was not a build failure, but it was corrected before any APK
was distributed because the X.509 `C` field should be a two-letter country
code.

The final key is:

```text
Path: /home/jalcocert/.local/share/tello-signing/tello-native-release.p12
Owner: jalcocert
Mode: 600
Alias: tello-native
Identity: CN=JAlcocerT, OU=JAlcocerTech, O=JAlcocerTech,
          L=Seville, ST=Seville, C=ES
Validity: 2026-10-03 through 2054-02-18
```

The `.p12` contains the private key and is secret. Its SHA-256 certificate
fingerprint is public and is used to prove that APKs came from the same key.
The password is also secret.

`keytool -list -v` verifies the keystore metadata and certificate. The earlier
`keytool -exportcert` command only exported a public `.pem`; it was not needed
for verification or Obtainium and was removed from the procedure.

The working key and Desktop backup produced the same file checksum:

```text
b430d9d58a11cd3740476b69bdd5b0f373820dd7c12fca8c2ebb9b3758b6a543
```

The Desktop copy protects against accidental edits or deletion, but it is on
the same disk. A second encrypted off-device backup is still advisable.

## GitHub Secrets are for Android signing, not Obtainium

Obtainium needs only the public repository URL. The four repository secrets
exist because GitHub Actions must reconstruct and unlock the signing key:

```text
ANDROID_KEYSTORE_BASE64
ANDROID_KEYSTORE_PASSWORD
ANDROID_KEY_ALIAS
ANDROID_KEY_PASSWORD
```

Base64 is transport encoding, not encryption. We avoided writing a temporary
Base64 file by piping it directly into `gh secret set`. GitHub displays secret
names and update times but never returns their values.

The original `.p12` and password-manager entry remain the source of truth.
GitHub Secrets are not a backup because their values cannot be downloaded.

## Local validation before tagging

The final key successfully completed:

```bash
./gradlew testDebugUnitTest lintDebug assembleRelease
```

The build reported 86 successful/up-to-date tasks. `apksigner` then reported:

```text
Verifies
APK Signature Scheme v2: true
Number of signers: 1
RSA key size: 4096
Certificate country: ES
```

The v1, v3, v3.1, v4, and SourceStamp `false` results are not failures. Scheme
v2 is valid for the app's supported Android versions, and the command's overall
result was `Verifies`.

## Do not reuse or move failed release tags

`android-v0.1.0` points to the workflow configuration that failed. Instead of
moving that tag, the fix was committed and a higher tag was created:

```text
android-v0.1.0  failed CI history
android-v0.1.1  first successful public release
android-v0.1.2  visible version, backup hardening, Obtainium update test
```

This keeps published Git history understandable and avoids different people
seeing different commits under the same version name.

## Repeatable future release process

For the next update, change and test the code, commit it to `main`, run the
permanent-key signing block from Step 6 of `z-obtanium-guide.md`, and use a
higher tag:

```bash
cd /home/jalcocert/Desktop/tello-kotlin
git switch main
git pull --ff-only origin main
git status --short

./gradlew testDebugUnitTest lintDebug assembleDebug

git tag -a android-v0.1.3 -m "Tello Native Android 0.1.3"
git push origin android-v0.1.3
gh run watch --repo JAlcocerT/tello-kotlin --exit-status
```

Before tagging, `git status --short` must be empty. Each release must keep:

- Package ID `tech.jalcocer.tello`
- The same permanent signing key
- A higher workflow-generated `versionCode`
- A higher release tag/version

The workflow uses `github.run_number` as `versionCode`. Keep the same workflow
name/file history or change the version-code strategy before replacing it.

## Obtainium and phone-install lesson

Obtainium tracks:

```text
https://github.com/JAlcocerT/tello-kotlin
```

Optional APK filter:

```regex
^tello-native-android-v.*\.apk$
```

The application APK requests Internet/network state, Wi-Fi state/change,
nearby Wi-Fi devices, and location only on Android 12L/API 32 or older where
Wi-Fi discovery required it. It does not request accessibility, device-admin,
all-files, SMS, contacts, phone, or package-install permissions.

Allowing Obtainium to install unknown apps lets Obtainium request Android's
package installer for downloaded APKs; it does not bypass Android's signature
checks. Updates to `tech.jalcocer.tello` must continue to match the permanent
certificate. No software is zero-risk, but the installed release was built
from this public source, by the public workflow, signed with the verified key,
and independently checked after download.

## What remains device-specific

The PC and GitHub tests prove compilation, unit logic, release signing, and
artifact distribution. They cannot prove real Tello behavior. A physical phone
and drone are still required to validate Wi-Fi selection, command/telemetry
UDP traffic, H.264 decoding, media saving, touch directions, landing safety,
and flight behavior.
