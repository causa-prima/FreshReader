# Releasing

Every pushed version tag builds a signed APK and publishes it as a GitHub Release (`.github/workflows/release.yml`). Obtainium installs FreshReader from there and offers each newer release as an update.

## Publishing a release

```sh
git checkout main && git pull
git tag v2026.11.10.0          # vYYYY.M.D.N, no leading zeros; N counts the day's releases from 0
git push origin v2026.11.10.0
```

The tag is the version: the APK gets versionName `2026.11.10.0` and versionCode `26111000` (YYMMDDNN). A tag that is not a valid date in this form fails the build. Builds without a tag, such as debug builds, are version `dev`.

The release notes list the pull requests merged since the previous tag; they can be edited on GitHub afterwards.

## Obtainium

Add the app with the repository URL `https://github.com/causa-prima/FreshReader`. Obtainium takes the release tag as the version and compares it with the installed app's versionName, which the build makes equal to it.

## The signing key

Android installs an update only if it is signed with the same key as the installed app. **If the key is lost, no further update can be installed**: the app has to be uninstalled and installed anew, which loses the login and the Feed Settings. Keep the backup.

- The key lives in a PKCS12 keystore with the alias `freshreader`; the key has the keystore's password.
- The reader keeps the keystore and its password in a backup outside this repository. `*.p12`, `*.jks` and `*.keystore` files are ignored by git.
- GitHub Actions gets it from two repository secrets: `RELEASE_KEYSTORE`, the keystore in base64, and `RELEASE_KEYSTORE_PASSWORD`.

A release build on the own machine, e.g. to check R8:

```sh
FRESHREADER_KEYSTORE=/path/to/release.p12 FRESHREADER_KEYSTORE_PASSWORD=… ./gradlew :app:assembleRelease -PreleaseTag=v2026.11.10.0
```

## Debug builds beside a release

Debug builds are the app `io.github.causaprima.freshreader.debug`, named "FreshReader Debug" with a blue-grey icon, so they install beside a release without replacing it.
