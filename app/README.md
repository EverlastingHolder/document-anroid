# Android app module

The `app` module is the single Android application module (`ru.passport.scanner`), using Kotlin, Jetpack Compose Material 3, CameraX, ViewModel, and a Ktor repository client. It targets Android API 36 and supports API 26 and newer.

## User flow and source of truth

The user captures a document spread with CameraX or selects one image through the system Photo Picker. The app shows the image without crop/rotate processing. In the real-client flow, first connection creates a unique per-install bearer credential, stores it with the server URL in Android Keystore-backed encrypted connection settings, and sends only its SHA-256 hash to backend registration. These are the only app data persisted by the client; there is no login, token, or QR field. Upload uses `POST /v1/passport/jobs` with exactly one multipart part named `file`; status comes from `GET /v1/passport/jobs/{job_id}`, following [`../passport-backend/api/openapi.yaml`](../passport-backend/api/openapi.yaml).

Only one job may be active in the current process. The selected photo, sampled preview, exact upload bytes, idempotency key, accepted job ID, and terminal result live in RAM only. CameraX supplies an in-memory JPEG `ImageProxy`; picker images are read into memory. Exact bytes and key are kept only for ambiguous POST retries in the same process. After `202`, upload bytes are erased while foreground polling keeps the job ID in memory. Process death discards all document and job state: there is no automatic restore, GET, or resubmit after restart, and the remote job may continue on the server.

When a terminal response is applied to the live screen, the client sends `/v1/passport/jobs/{job_id}/receipt`. No photo, job journal, result, or error summary is written to app storage. If the process exits before the terminal result is applied, the app cannot restore it. If it exits after receipt succeeds, the server may have purged the result and the app cannot retrieve it again. The server removes unacknowledged terminal results after 15 minutes. Polling runs only while the app is foregrounded; there is no job history. Stopping local tracking does not cancel a server job. Queued, processing, failed, and completed states are shown without job IDs or OCR internals.

The canonical completed draft opens in the existing editable form. The user edits current values; field-level recognition markers and a separate confirmation action are not part of the flow. Validation errors remain visible, and export is enabled only when the current values are valid. Export builds a TXT from a defensive `PassportSnapshot(values, revision)` copy and encrypts it as a genuine 7z archive with password `123` entirely in RAM. Sharing opens an email-only chooser with `ACTION_SEND` and recipient `test@mail.ru`; a non-exported, URI-grant-enabled provider streams the encrypted archive from RAM. At most one bounded export is retained in process memory until replaced, explicitly cleared, or process death. No archive file is written by the app. The app does not use SMTP or send mail through the backend.

Use the backend repository as the source of truth for API and data rules. Do not add a second API contract or run OCR in the APK. No passport image or field values should be written to app logs. Camera is optional; permission denial must leave Photo Picker usable. A retry uses the current image, and a late response from an older scan must not replace the latest state.

## Endpoint configuration

The debug base URL defaults to `http://192.168.1.91:8080`; override it with `-POCR_BASE_URL=https://<host>` or, for debug only, a local HTTP address. Release builds accept HTTPS only; if only an HTTP address is configured, the server address prompt starts empty. A blank URL never selects demo data. Use `-POCR_USE_STUB=true` to explicitly select demo data in a debug build; release builds force it to false. A non-empty URL must be an absolute HTTP or HTTPS URL with a host and no credentials, query, or fragment.

For local HTTP debug use, the Android phone and backend host must be on the same LAN. Debug Network Security Config permits cleartext for debug traffic, while `ApiConnection` restricts configured HTTP servers to loopback or private IP addresses. Release Network Security Config has no cleartext exception and the client rejects non-HTTPS URLs.

Android does not run OCR; accepted jobs may remain queued until a backend worker is available. Completed server results are editable drafts. Export validates the current edited values and uses the canonical `PassportSnapshot` without a separate OCR confirmation action.

## Build and local SDK

Run [`../scripts/bootstrap-local-jdk17.sh`](../scripts/bootstrap-local-jdk17.sh) to install the checksum-verified, project-local Temurin JDK 17.0.21 required by the pinned AGP toolchain. Then run [`../scripts/bootstrap-android-sdk.sh`](../scripts/bootstrap-android-sdk.sh) to install the pinned Android command-line tools, accept standard Android SDK package licenses, and install API 36, Build Tools 36.0.0, and Platform Tools. From the repository root:

```sh
export JAVA_HOME="$PWD/.tools/jdk17/Contents/Home"
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:connectedDebugAndroidTest
```

Provide `-POCR_BASE_URL=...` to use the real backend by default; you may also set `-POCR_USE_STUB=false` explicitly. Use an HTTPS endpoint for release builds; only debug builds permit local emulator HTTP.

Current RAM-only verification: `assembleDebug`, `assembleRelease`, `lintDebug`, 73 JVM unit tests and 19 Android instrumented tests pass with zero failures/errors/skips. Instrumented tests ran on the task-only Android 36 AVD `emulator-5566` via explicit-serial `adb am instrument`; they cover synthetic RAM image conversion/preview, exact cleanup boundaries for legacy scan and pending-job files, connection record preservation, status/receipt transport, the synthetic async-client composition, progress, review, export encryption, and cross-UID archive streaming through the in-memory provider. `EncryptedConnectionStore` persists only the server URL and hidden per-install credential; document bytes, job state, canonical result, and export archive are not persisted. Startup removes only known old scan, pending-job, and file-export artifacts from earlier app versions. Live Go retention, production OCR/models, camera hardware, physical device, and user photo were not exercised. The earlier 76/17 totals belonged to the superseded encrypted pending-job/result variant.

Connected tests ran on an Android 36 emulator; no physical phone was tested. APK alignment was not part of this verification.
