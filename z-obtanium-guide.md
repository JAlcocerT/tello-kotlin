# Publishing Tello Native through Obtainium

Last checked: 2026-10-03

This is the complete path from the current local branch to repeatable Android
updates through a public GitHub Releases page and Obtainium.

Obtainium itself needs only the public repository URL. The signing key and
GitHub Secrets exist because Android requires every APK to be signed and only
accepts an update when it has the same package name and signing certificate as
the installed app. See the official [Android app-signing guide](https://developer.android.com/studio/publish/app-signing)
and the [Obtainium project](https://github.com/ImranR98/Obtainium).

## Current project facts

- Android package ID: `tech.jalcocer.tello`
- Release tag format: `android-vMAJOR.MINOR.PATCH`
- First suggested tag: `android-v0.1.0`
- Release asset format: `tello-native-android-vMAJOR.MINOR.PATCH.apk`
- Release workflow: `.github/workflows/android-release.yml`
- Workflow trigger: pushing an `android-v*` tag to GitHub
- Intended GitHub repository: `https://github.com/JAlcocerT/tello-kotlin`

The local repository must have an initial commit before `gh repo create --push`
can create and populate the public GitHub repository.

## What you need

Keep these concepts separate:

| Artifact | Secret? | Where it belongs | Purpose |
|---|---:|---|---|
| Public GitHub repository | No | GitHub | Hosts source, workflow, and releases |
| Release keystore (`.p12`) | Yes | Offline backup and GitHub Secret | Holds the permanent private signing key |
| Keystore password | Yes | Password manager and GitHub Secret | Opens the keystore |
| Key alias | No, but stored as a Secret here | Password manager and GitHub Secret | Selects the key inside the keystore |
| Key password | Yes | Password manager and GitHub Secret | Unlocks the signing key |
| SHA-256 certificate fingerprint | No | Password manager/release records | Confirms future APKs use the same key |
| Base64 keystore text | Yes | Temporary local file/GitHub Secret | Transfers the binary keystore into Actions |
| Signed release APK | No | GitHub Release asset | File Obtainium downloads and installs |
| Public repository URL | No | Obtainium | Source Obtainium checks for updates |

You do **not** need a GitHub token inside Obtainium for a normal public repository.

You also do not need a Play Store listing, PGP key, SSH key on the phone, or an Obtainium-specific signing key.

## Step 1: create the permanent Android signing key

Do this once. Do not create a new key for later releases.

Choose a directory outside the Git repository and restrict it to your user. On
this PC, use this user-owned location (do not run these commands as `root`):

```bash
umask 077
mkdir -p /home/jalcocert/.local/share/tello-signing
chmod 700 /home/jalcocert/.local/share/tello-signing
cd /home/jalcocert/.local/share/tello-signing
```

On this PC, make the locally installed JDK tools available first:

```bash
export JAVA_HOME=/home/jalcocert/.local/share/tello-android/jdk
export PATH="$JAVA_HOME/bin:$PATH"
```

Generate a PKCS#12 keystore with the JDK `keytool`:

```bash
keytool -genkeypair \
  -keystore tello-native-release.p12 \
  -storetype PKCS12 \
  -alias tello-native \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

`keytool` asks for:

1. A strong `keystore` password.
2. Certificate identity fields. They identify the certificate owner; they are not displayed in the app UI.

For the final country-code question, enter the two-letter ISO code `ES`, not a
postal code. For example, use `L=Seville`, `ST=Seville`, and `C=ES`.

For PKCS#12, use the same password for the keystore and key.

Store this record in a password manager:

```text
Entry: Tello Native Android signing
Keystore filename: tello-native-release.p12
Alias: tello-native
Keystore password: <your generated password>
Key password: <same password>
Package ID: tech.jalcocer.tello
```

Android recommends a signing-key validity of at least 25 years.

The `10000` days above is roughly 27 years.

### Record the public fingerprint

Run:

```bash
keytool -list -v \
  -keystore tello-native-release.p12 \
  -alias tello-native
```

Copy the SHA-256 certificate fingerprint into the password-manager entry.

The fingerprint is safe to share; the `.p12` file and passwords are not.

Optionally export the public certificate for future verification:

```bash
keytool -exportcert -rfc \
  -keystore tello-native-release.p12 \
  -alias tello-native \
  -file tello-native-release-certificate.pem
```

The `.pem` certificate is public and cannot sign an APK.

## Step 2: back up the key before releasing anything

Make at least two encrypted backups of `tello-native-release.p12`, for example:

1. An encrypted password-manager attachment or encrypted local vault.
2. An encrypted offline drive kept separately.

Also back up the password-manager record containing both passwords, the alias, the package ID, and the SHA-256 fingerprint.

Do not:

- Commit the keystore or its Base64 representation to Git.
- Put it in a public cloud folder without client-side encryption.
- Rely only on GitHub Secrets; secret values cannot be downloaded later.
- Delete the original after uploading it to GitHub.
- generate a replacement key for each version.

If this key or its password is permanently lost, Android users cannot update the existing app.

The recovery is a new package ID plus an uninstall/reinstall.

## Step 3: create the public GitHub repository

From the `tello-kotlin` directory, make the initial commit first:

```bash
cd ~/Desktop/tello-kotlin
git branch -m main
git add .
git commit -m "Initial native Android Tello controller"
```

Then create the public repository and push that commit:

```bash
gh repo create tello-kotlin --public --source=. --remote=origin --push
git remote -v
```

The release tag must point to a commit containing the Gradle project and
`.github/workflows/android-release.yml`.

Do not push a release tag yet; configure signing first.

## Step 4: prepare the keystore for GitHub Actions

On Linux, return to the private signing directory and create a single-line
Base64 representation:

```bash
cd /home/jalcocert/.local/share/tello-signing
base64 -w 0 tello-native-release.p12 > tello-native-release.p12.base64
wc -c tello-native-release.p12.base64
```

The Base64 file is still secret. It is not encryption; it is only a text
representation that GitHub Actions can restore into the original binary file.

## Step 5: add the four GitHub Actions secrets

Open the new GitHub repository and go to:

**Settings → Secrets and variables → Actions → New repository secret**

Create these exact names:

### `ANDROID_KEYSTORE_BASE64`

Value: the full single-line contents of
`tello-native-release.p12.base64`.

On Linux, this copies it to the clipboard without printing it in the terminal:

```bash
xclip -selection clipboard < tello-native-release.p12.base64
```

If `xclip` is unavailable, use the desktop file editor carefully and ensure no
line breaks are introduced.

### `ANDROID_KEYSTORE_PASSWORD`

Value: the strong keystore password stored in the password manager.

### `ANDROID_KEY_ALIAS`

Value:

```text
tello-native
```

### `ANDROID_KEY_PASSWORD`

Value: the same password used for the PKCS#12 keystore.

GitHub Secrets are encrypted variables made available only where a workflow
explicitly references them. See [GitHub's Secrets documentation](https://docs.github.com/en/actions/concepts/security/secrets).

Once all four secrets exist, delete only the temporary Base64 file from the
workstation. Keep the original `.p12` and its backups.

## Step 6: optionally test signing locally with the permanent key

This step confirms the alias and passwords before consuming a release tag.

From `~/Desktop/tello-kotlin/`:

```bash
export JAVA_HOME=/home/jalcocert/.local/share/tello-android/jdk
export ANDROID_HOME=/home/jalcocert/.local/share/tello-android/sdk
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/36.0.0:$PATH"

export ANDROID_KEYSTORE_PATH=/home/jalcocert/.local/share/tello-signing/tello-native-release.p12
export ANDROID_KEY_ALIAS=tello-native
read -rsp "Keystore password: " ANDROID_KEYSTORE_PASSWORD
echo
export ANDROID_KEYSTORE_PASSWORD
export ANDROID_KEY_PASSWORD="$ANDROID_KEYSTORE_PASSWORD"

./gradlew testDebugUnitTest lintDebug assembleRelease
```

Clear the password variables afterward:

```bash
unset ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
```

Verify the resulting APK:

```bash
apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk
```

Confirm that its SHA-256 certificate fingerprint matches the fingerprint saved
in Step 1.

## Step 7: create the first release

Start from the merged, up-to-date GitHub release branch, normally `main`:

```bash
git switch main
git pull --ff-only origin main
git status --short
```

The working tree should be clean. Create and push an annotated tag:

```bash
git tag -a android-v0.1.0 -m "Tello Native Android 0.1.0"
git push origin android-v0.1.0
```

The tag push triggers the Android release workflow. It will:

1. Check out the tagged commit.
2. Install JDK 17 and Android SDK 37.2.
3. Restore the `.p12` from the Base64 secret.
4. Run the JVM tests.
5. Build and sign the minified release APK.
6. Create a GitHub Release.
7. Upload `tello-native-android-v0.1.0.apk` as its asset.

Monitor **GitHub repository → Actions → Android release**. A red run means no
usable release was produced; open the failed step before creating another tag.

The workflow uses GitHub's increasing run number as Android's `versionCode`.
Do not replace this release workflow with a newly named workflow that restarts
its run-number sequence unless the version-code strategy is updated first.

## Step 8: verify the public GitHub Release

Open:

```text
https://github.com/JAlcocerT/tello-kotlin/releases
```

The `android-v0.1.0` release should contain exactly one relevant APK:

```text
tello-native-android-v0.1.0.apk
```

Download it to a PC and verify it again:

```bash
apksigner verify --verbose --print-certs \
  tello-native-android-v0.1.0.apk
sha256sum tello-native-android-v0.1.0.apk
```

Check that:

- `apksigner` says `Verifies`.
- Its certificate SHA-256 matches the permanent fingerprint.
- The GitHub release is public.
- The asset ends in `.apk`, not `.aab`, `.zip`, or `.idsig`.

## Step 9: remove incompatible local test builds from the phone

Before installing the first production-signed release, uninstall any debug APK
or temporary-test-key APK of `tech.jalcocer.tello`. Those builds use a different
certificate, so Android will reject an in-place update.

You can uninstall from Android Settings or with ADB:

```bash
adb uninstall tech.jalcocer.tello
```

This removes that app's local data. After installing the first permanently
signed GitHub release, future releases should update in place; do not uninstall
between them.

## Step 10: add the app to Obtainium

On the Android phone:

1. Install Obtainium from its official source.
2. Open Obtainium and choose **Add App**.
3. Paste the public repository URL, not a local Git URL:

   ```text
   https://github.com/JAlcocerT/tello-kotlin
   ```

4. Let Obtainium identify GitHub as the source.
5. If an APK filter is requested, use:

   ```regex
   ^tello-native-android-v.*\.apk$
   ```

6. Leave prereleases disabled unless you intentionally publish beta builds.
7. Save/add the app.
8. Select the detected `android-v0.1.0` release and install it.
9. When Android asks, allow Obtainium to install unknown apps.

Obtainium obtains release data from GitHub and installs the APK asset. It does
not receive the private signing key or any GitHub Actions secret. Obtainium's
source/filter behavior is documented in its [App Sources wiki](https://wiki.obtainium.imranr.dev/sources/).

## Step 11: publish future updates

For each version:

1. Make and test the code changes.
2. Commit and merge them into the GitHub release branch.
3. Choose a larger semantic version.
4. Tag the exact tested commit.
5. Push the tag to the `github` remote.
6. Wait for the green Actions run.
7. Verify the release asset and certificate.
8. Ask Obtainium to check for updates.

Example for the next patch release:

```bash
git switch main
git pull --ff-only origin main
git tag -a android-v0.1.1 -m "Tello Native Android 0.1.1"
git push origin android-v0.1.1
```

Android accepts the Obtainium update when all of these remain true:

- Package ID stays `tech.jalcocer.tello`.
- The permanent signing key stays the same.
- The new `versionCode` is greater than the installed one.
- The phone supports the app's minimum SDK.

## If a release tag or workflow fails

Do not move or overwrite a public release tag after users may have installed
it. Fix the problem in a new commit and use the next version tag, such as
`android-v0.1.1`.

Before the first public install, a bad unpublished tag can be removed carefully:

```bash
git tag -d android-v0.1.0
git push origin :refs/tags/android-v0.1.0
```

Also delete the corresponding draft/failed GitHub Release if one was created.
Once users have installed a version, prefer a new higher version instead.

## Key rotation and loss

For this direct APK distribution model, assume the signing key cannot be
replaced transparently:

- **Lost key or password:** no more updates to existing installs.
- **Different key:** Android reports a signature conflict.
- **Compromised key:** stop publishing and assess a migration to a new package
  ID; attackers holding the old key could impersonate updates.
- **Lost GitHub Secret only:** restore it from the offline `.p12` backup and
  password manager.
- **Changed GitHub repository:** Obtainium can follow a new URL, but APK updates
  still require the same package ID, key, and increasing version code.

## Android developer verification: separate from Obtainium

Android developer verification is not an Obtainium key requirement. As of
2026-10-03, direct sideloading/other stores are not part of the initial
September 2026 enforcement described by Google, but a broader certified-device
rollout is planned for 2027.

For a small personal deployment, Google's limited-distribution path supports
up to 20 devices without identity verification. For broad distribution, plan
to create an Android Developer Console account, verify the developer identity,
and register `tech.jalcocer.tello` by proving ownership with an APK signed by
the permanent key. Keeping the key from Step 1 is therefore important beyond
ordinary APK updates.

Read the current official guidance before a public launch:

- [Android developer verification overview](https://developer.android.com/developer-verification)
- [Android Developer Console registration](https://developer.android.com/developer-verification/guides/android-developer-console)
- [Developer verification FAQ](https://developer.android.com/developer-verification/guides/faq)

## Final checklist

Before the first Obtainium release:

- [ ] Public GitHub repository exists.
- [ ] Android project and workflow are committed and pushed to GitHub `main`.
- [ ] Permanent `.p12` signing key exists outside the repository.
- [ ] Two encrypted backups exist.
- [ ] Password manager contains alias, passwords, package ID, and fingerprint.
- [ ] Four GitHub Actions secrets are configured.
- [ ] Permanent key successfully signs a local release APK.
- [ ] Local certificate fingerprint matches the recorded fingerprint.
- [ ] `android-v0.1.0` tag is pushed to the GitHub remote.
- [ ] GitHub Actions run is green.
- [ ] Public release contains the correctly named APK.
- [ ] Downloaded release APK signature is verified.
- [ ] Debug/test-signed copy is uninstalled from the phone.
- [ ] Obtainium is configured with the public repository URL.
- [ ] First release installs successfully.
- [ ] A second release proves that Obtainium updates in place.
