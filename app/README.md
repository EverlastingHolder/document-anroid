# Android app module

The `app` module is the single Android application module (`ru.passport.scanner`), using Kotlin, Jetpack Compose Material 3, CameraX, ViewModel, and a Ktor repository client. It targets Android API 36 and supports API 26 and newer.

## User flow and source of truth

The user captures a document spread with CameraX or selects one image through the system Photo Picker. The app shows the image without crop/rotate processing. The default `OCR_BASE_URL` is `http://192.168.1.91:8080`, so the app uses the backend operation in [`../passport-backend/api/openapi.yaml`](../passport-backend/api/openapi.yaml): `POST /v1/passport/recognize`, with exactly one multipart part named `file` and JPEG, PNG, or WebP content. Set `OCR_USE_STUB=true` to select the local demo repository with clearly labeled fake recognition data. An explicitly blank URL retains the previous demo default; `OCR_USE_STUB=false` selects the backend explicitly.

In the default demo flow, an editable form is prefilled with 11 fake values and one shared demo badge identifies them as fake. The user edits the current values; field-level recognition/review markers and a separate confirmation action are not part of the flow. Validation errors remain visible, and export is enabled only when the current values are valid. Export builds a TXT from a defensive `PassportSnapshot(values, revision)` copy, then writes exactly that TXT into a genuine AES-encrypted 7z archive with password `123`. Sharing opens an email-only chooser with `ACTION_SEND` and recipient `test@mail.ru`. The app does not use SMTP or send mail through the backend.

Use the backend repository as the source of truth for API and data rules. Do not add a second API contract or run OCR in the APK. No passport image or field values should be written to app logs. Camera is optional; permission denial must leave Photo Picker usable. A retry uses the current image, and a late response from an older scan must not replace the latest state.

## Endpoint configuration

The default base URL is `http://192.168.1.91:8080`; `OCR_USE_STUB` defaults to false for that URL. Override the URL with `-POCR_BASE_URL=https://<host>` or a local HTTP server address. Use `-POCR_USE_STUB=true` for demo data, or set `-POCR_BASE_URL=` to retain the simple blank-URL demo setup. `OCR_USE_STUB=false` explicitly selects the backend. A non-empty URL must be an absolute HTTP or HTTPS URL with a host and no credentials, query, or fragment.

For the default address, the Android phone and backend host must be on the same LAN. Start the backend listening on `0.0.0.0:8080` and allow inbound TCP port 8080 through the host firewall. The app's generated Network Security Config permits cleartext only to the exact host in the selected HTTP base URL, for both debug and release variants. HTTPS URLs create no cleartext exception; all other hosts remain blocked for HTTP.

OCR can take several minutes on the local CPU runtime. The client uses a 15-second connection timeout and allows up to 10 minutes for a response. Network failures, non-success HTTP statuses, and backend JSON errors are shown as retryable recognition errors; retry uses the current image. A successful HTTP response is still an unconfirmed OCR draft and does not establish recognition quality or export delivery. The main send button validates the edited values and exports the canonical `PassportSnapshot` without a separate OCR confirmation action.

## Build and local SDK

Run [`../scripts/bootstrap-local-jdk17.sh`](../scripts/bootstrap-local-jdk17.sh) to install the checksum-verified, project-local Temurin JDK 17.0.21 required by the pinned AGP toolchain. Then run [`../scripts/bootstrap-android-sdk.sh`](../scripts/bootstrap-android-sdk.sh) to install the pinned Android command-line tools, accept standard Android SDK package licenses, and install API 36, Build Tools 36.0.0, and Platform Tools. From the repository root:

```sh
export JAVA_HOME="$PWD/.tools/jdk17/Contents/Home"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

The unit test `PassportExporterTest.writesSyntheticArchiveForIndependent7ZipVerification` writes a synthetic archive to `build/verification/archive-proof.7z` and its expected TXT sidecar. Exports are named `document.7z` and contain `document.txt`. To verify the test archive independently, install the macOS console binary as the ignored local file `.tools/7zip/7zz` from the official [7-Zip 26.05 archive](https://github.com/ip7z/7zip/releases/download/26.05/7z2605-mac.tar.xz), checking SHA-256 `12ef6830317743cfd3f0ab1724bde43fcc270d8b6d8564f8c12c2a2b7f993570`. After the unit test completes, run `./scripts/verify-archive-proof.sh` to test extraction, compare `document.txt` with the sidecar, check the encrypted archive entry, and confirm wrong-password rejection.

Provide `-POCR_BASE_URL=...` to use the real backend by default; you may also set `-POCR_USE_STUB=false` explicitly. Use an HTTPS endpoint for release builds; only debug builds permit local emulator HTTP.

Current verification: debug and release assemble, `lintDebug` reports 0 errors and 19 warnings, 21/21 JVM unit tests pass, and 10/10 connected tests pass on an Android 36 emulator with 0 failures, errors, or skipped tests. The independent external 7-Zip verifier passed. This run used the fake demo data and exercised the editable form without per-field markers or a separate confirmation step. The backend and live OCR were not tested, and no real email was sent. Reports are in `app/build/reports/tests/testDebugUnitTest/index.html`, `app/build/reports/androidTests/connected/debug/index.html`, and `app/build/reports/lint-results-debug.html`; Gradle logs are in `build/verification/*-simplified.log`.

Connected tests ran on an Android 36 emulator; no physical phone was tested. APK alignment was not part of this verification.
