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
- First successful-release candidate: `android-v0.1.1`
- Release asset format: `tello-native-android-vMAJOR.MINOR.PATCH.apk`
- Release workflow: `.github/workflows/android-release.yml`
- Workflow trigger: pushing an `android-v*` tag to GitHub
- Public GitHub repository: `https://github.com/JAlcocerT/tello-kotlin`

The repository and workflow are already on GitHub. Resume at Step 1; do not
re-run repository creation.

Current progress:

- [x] Standalone Android repository created on local `main`
- [x] Public GitHub repository created and pushed
- [x] Unit tests and debug APK build passed on this PC
- [x] Correct permanent key created and locally backed up
- [x] GitHub Actions secrets configured
- [x] Signed release built and verified locally
- [x] `android-v0.1.0` CI attempt diagnosed as an obsolete SDK setup failure
- [x] Corrected `android-v0.1.1` release published and independently verified
- [ ] Obtainium configured on the phone

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
| Base64 keystore text | Yes | Piped directly to GitHub Secret | Transfers the binary keystore into Actions |
| Signed release APK | No | GitHub Release asset | File Obtainium downloads and installs |
| Public repository URL | No | Obtainium | Source Obtainium checks for updates |

You do **not** need a GitHub token inside Obtainium for a normal public repository.

You also do not need a Play Store listing, PGP key, SSH key on the phone, or an Obtainium-specific signing key.

## Step 1: replace the first key with the final permanent key

The first key was created with `C=41710`. Android would normally ignore that
identity field, but `C` is supposed to contain a two-letter country code. No
release has used that key, so abandon it now and make the final key with
`C=ES`. Never replace the new key after distributing the first APK.

Current status on this PC: the replacement file already exists at
`/home/jalcocert/.local/share/tello-signing/tello-native-release.p12`, is owned
by `jalcocert`, and has mode `600`. **Do not run the generation command again**
if that is the key you just created. Continue at "Record and verify the public
fingerprint" below.

First restrict access to the abandoned root-owned directory:

```bash
sudo chmod 700 /secure/off-repo/path/tello-native
```

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

Generate the final PKCS#12 keystore. The explicit `-dname` prevents the country
code from being entered incorrectly again:

```bash
keytool -genkeypair \
  -keystore tello-native-release.p12 \
  -storetype PKCS12 \
  -alias tello-native \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=JAlcocerT, OU=JAlcocerTech, O=JAlcocerTech, L=Seville, ST=Seville, C=ES"

chmod 600 tello-native-release.p12
stat -c '%U:%G %a %n' tello-native-release.p12
```

`keytool` asks for a strong keystore password. The final `stat` output must show
your user as owner and permission mode `600`.

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

### Record and verify the public fingerprint

Run:

```bash
keytool -list -v \
  -keystore tello-native-release.p12 \
  -alias tello-native
```

Confirm the owner contains `C=ES`, then copy the new SHA-256 certificate
fingerprint into the password-manager entry. Do not keep using the earlier
fingerprint beginning `A8:9B:4C:46`.

The fingerprint is safe to share; the `.p12` file and passwords are not.

The `keytool -list -v` command above is the verification step. Exporting a
`.pem` file is unnecessary for this release process and is intentionally not
part of the procedure.

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

## Step 3: confirm the completed GitHub repository setup

This step is already complete. Verify it; do not run `git init` or
`gh repo create` again:

```bash
cd ~/Desktop/tello-kotlin
git status --short --branch
git remote -v
gh repo view JAlcocerT/tello-kotlin \
  --json nameWithOwner,isPrivate,url,defaultBranchRef
```

Expected: clean `main`, remote URL ending in `JAlcocerT/tello-kotlin.git`,
`"isPrivate":false`, and default branch `main`.

Do not push a release tag yet; configure signing first.

## Step 4: check the key before uploading it

Run these checks from the private signing directory:

```bash
cd /home/jalcocert/.local/share/tello-signing
test -s tello-native-release.p12
test "$(stat -c '%a' tello-native-release.p12)" = 600
keytool -list -v \
  -keystore tello-native-release.p12 \
  -alias tello-native
```

Do not continue unless the alias is `tello-native`, owner ends in `C=ES`, and
the displayed SHA-256 matches the one saved in your password manager.

## Step 5: add the four GitHub Actions secrets

Upload all four with GitHub CLI. These commands do not create a Base64 file and
do not print the password:

```bash
cd /home/jalcocert/Desktop/tello-kotlin

base64 -w 0 /home/jalcocert/.local/share/tello-signing/tello-native-release.p12 \
  | gh secret set ANDROID_KEYSTORE_BASE64 --repo JAlcocerT/tello-kotlin

read -rsp "Keystore password: " TELLO_KEYSTORE_PASSWORD
echo
printf '%s' "$TELLO_KEYSTORE_PASSWORD" \
  | gh secret set ANDROID_KEYSTORE_PASSWORD --repo JAlcocerT/tello-kotlin
printf '%s' 'tello-native' \
  | gh secret set ANDROID_KEY_ALIAS --repo JAlcocerT/tello-kotlin
printf '%s' "$TELLO_KEYSTORE_PASSWORD" \
  | gh secret set ANDROID_KEY_PASSWORD --repo JAlcocerT/tello-kotlin
unset TELLO_KEYSTORE_PASSWORD

gh secret list --repo JAlcocerT/tello-kotlin
```

The final command displays secret names and update times, not their values.
Confirm that all four expected names appear.

GitHub Secrets are encrypted variables made available only where a workflow
explicitly references them. See [GitHub's Secrets documentation](https://docs.github.com/en/actions/concepts/security/secrets).

Keep the original `.p12` and both encrypted backups. No temporary Base64 file
is created by the commands above.

## Step 6: test signing locally with the permanent key

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

apksigner verify --verbose --print-certs \
  app/build/outputs/apk/release/app-release.apk

sha256sum app/build/outputs/apk/release/app-release.apk
unset ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_PASSWORD
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
git tag -a android-v0.1.1 -m "Tello Native Android 0.1.1"
git push origin android-v0.1.1

gh run watch --repo JAlcocerT/tello-kotlin --exit-status
```

The tag push triggers the Android release workflow. It will:

1. Check out the tagged commit.
2. Install JDK 17 and Android SDK 37.2.
3. Restore the `.p12` from the Base64 secret.
4. Run the JVM tests.
5. Build and sign the minified release APK.
6. Create a GitHub Release.
7. Upload `tello-native-android-v0.1.1.apk` as its asset.

`gh run watch` waits for the tag-triggered workflow and returns an error if it
fails. A failed run means no usable release was produced.

The workflow uses GitHub's increasing run number as Android's `versionCode`.
Do not replace this release workflow with a newly named workflow that restarts
its run-number sequence unless the version-code strategy is updated first.

## Step 8: verify the public GitHub Release

Open:

```text
https://github.com/JAlcocerT/tello-kotlin/releases
```

The `android-v0.1.1` release should contain exactly one relevant APK:

```text
tello-native-android-v0.1.1.apk
```

Download it into a dedicated directory and verify it again:

```bash
mkdir -p /home/jalcocert/Downloads/tello-native-v0.1.1
cd /home/jalcocert/Downloads/tello-native-v0.1.1
gh release download android-v0.1.1 \
  --repo JAlcocerT/tello-kotlin \
  --pattern '*.apk'

apksigner verify --verbose --print-certs \
  tello-native-android-v0.1.1.apk
sha256sum tello-native-android-v0.1.1.apk

gh release view android-v0.1.1 --repo JAlcocerT/tello-kotlin
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
8. Select the detected `android-v0.1.1` release and install it.
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
5. Push the tag to the `origin` remote.
6. Wait for the green Actions run.
7. Verify the release asset and certificate.
8. Ask Obtainium to check for updates.

Example for the next patch release:

```bash
git switch main
git pull --ff-only origin main
git tag -a android-v0.1.2 -m "Tello Native Android 0.1.2"
git push origin android-v0.1.2
```

Android accepts the Obtainium update when all of these remain true:

- Package ID stays `tech.jalcocer.tello`.
- The permanent signing key stays the same.
- The new `versionCode` is greater than the installed one.
- The phone supports the app's minimum SDK.

## If a release tag or workflow fails

Do not move or overwrite a public release tag after users may have installed
it. Fix the problem in a new commit and use the next version tag.

The `android-v0.1.0` workflow failed before it reached the key or build because
`setup-android@v3` requested the removed SDK package named `tools`. Keep that
tag as failed history. The workflow now uses the Node 24 action releases and
installs only `platform-tools`, Android platform 37.2, and build-tools 36.0.0.
The corrected release uses `android-v0.1.1`.

Do not delete or move `android-v0.1.0`; continue with the higher corrected tag.

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

- [x] Public GitHub repository exists.
- [x] Android project and workflow are committed and pushed to GitHub `main`.
- [x] Permanent `.p12` signing key exists outside the repository.
- [ ] Two encrypted backups exist.
- [ ] Password manager contains alias, passwords, package ID, and fingerprint.
- [x] Four GitHub Actions secrets are configured.
- [x] Permanent key successfully signs a local release APK.
- [x] Local certificate fingerprint matches the recorded fingerprint.
- [x] Failed `android-v0.1.0` tag is retained as diagnostic history.
- [x] Corrected `android-v0.1.1` tag is pushed to the GitHub remote.
- [x] GitHub Actions run is green.
- [x] Public release contains the correctly named APK.
- [x] Downloaded release APK signature is verified.
- [ ] Debug/test-signed copy is uninstalled from the phone.
- [ ] Obtainium is configured with the public repository URL.
- [ ] First release installs successfully.
- [ ] A second release proves that Obtainium updates in place.
