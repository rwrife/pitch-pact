# Android Google Play Release Setup

Pitch Pact ships to Google Play as a signed Android App Bundle (AAB) via GitHub Actions (`.github/workflows/android-release.yml`).

## Required GitHub Actions Secrets

To enable automated release to Google Play Internal App Sharing / Internal Track, configure the following secrets in repository settings (`Settings -> Secrets and variables -> Actions`):

| Secret Name | Description | Example / Notes |
|---|---|---|
| `ANDROID_KEYSTORE_BASE64` | Base64-encoded Java keystore (`.jks` / `.keystore`) containing upload key | `base64 -w 0 upload-keystore.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | Password protecting the keystore file | Alphanumeric string |
| `ANDROID_KEY_ALIAS` | Key alias inside the keystore | e.g. `pitchpact` |
| `ANDROID_KEY_PASSWORD` | Password for the key entry | Alphanumeric string |
| `PLAY_SERVICE_ACCOUNT_JSON` | Google Cloud service account JSON key with Play Developer API access | Full JSON payload |

## Generating an Upload Key

```bash
keytool -genkeypair -v -keystore pitchpact-upload.jks \
  -alias pitchpact -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype JKS
```

Export base64 for GitHub Actions:
```bash
base64 -w 0 pitchpact-upload.jks
```

## Google Play Console Configuration

1. Create application in [Google Play Console](https://play.google.com/console) with Application ID `com.infinityball.pitchpact`.
2. Navigate to **API Access** under Account Details and link a Google Cloud Project.
3. Grant service account app-level permission to release to internal testing; avoid account-wide Admin access.
4. Download service account JSON key and set `PLAY_SERVICE_ACCOUNT_JSON`.
5. Enroll app in Play App Signing and complete required Play Console setup. First-upload requirements vary; confirm in Console before dispatch.

## Workflow behavior

- `.github/workflows/ci-ubuntu.yml` builds an unsigned release AAB to gate compilation; this is **not** a distributable artifact.
- `.github/workflows/android-release.yml` fails closed when any secret is missing. With provisioned credentials, it signs AAB, checks signer, then uploads to internal track. Do not claim release until workflow upload succeeds and Play Console confirms build.
- Use `workflow_dispatch` from trusted main branch. Every run uses monotonic GitHub run number as version code. Never print or commit signing material.
