# Сканер документов

Android-приложение для сканирования документа по фотографии, получения черновика и подготовки результата. В демо-режиме приложение использует локальный repository с 11 тестовыми полями и одной общей demo-плашкой.

## Сценарий первой версии

Пользователь фотографирует документ через CameraX или выбирает одно изображение в системном Photo Picker. Приложение показывает снимок без обрезки и поворота. По умолчанию `OCR_BASE_URL=http://192.168.1.91:8080`, поэтому приложение вызывает backend `POST /v1/passport/recognize` из [OpenAPI-контракта](../passport-backend/api/openapi.yaml), отправляя ровно одну multipart-часть `file` с JPEG, PNG или WebP. `OCR_USE_STUB=true` включает demo repository с фейковым OCR-черновиком и общей demo-плашкой. Если явно задать пустой `OCR_BASE_URL`, сохраняется demo по умолчанию; `OCR_USE_STUB=false` явно выбирает backend.

Пользователь редактирует значения в общей форме. Ошибки валидации остаются видимыми; когда текущие значения валидны, кнопка сразу создаёт `document.txt` из канонического `PassportSnapshot`, упаковывает его в архив `document.7z` с AES-шифрованием и паролем `123` и открывает email-only chooser на `test@mail.ru`. Отдельные маркеры проверки каждого поля и ручное подтверждение результата не используются. SMTP и отправка почты через backend не используются.

При отказе в разрешении камеры приложение оставляет Photo Picker. ViewModel сохраняет состояние при повороте экрана; поздний ответ старого сканирования не должен заменить новый. Ошибки изображения, сети и сервера позволяют повторить запрос с текущим изображением. Не записывай фотографии и паспортные значения в журналы.

## Demo и настоящий backend

`OCR_BASE_URL` по умолчанию равен `http://192.168.1.91:8080`, а `OCR_USE_STUB` для него — `false`. Для настройки используйте Gradle properties, например `-POCR_BASE_URL=https://<реальный-домен>` или `-POCR_BASE_URL=http://<адрес>:8080`; при необходимости явно добавьте `-POCR_USE_STUB=false`. Для demo используйте `-POCR_USE_STUB=true` или `-POCR_BASE_URL=`. URL должен быть абсолютным HTTP/HTTPS URL с host и без учётных данных, query и fragment.

Для адреса по умолчанию телефон должен быть в той же локальной сети, что и backend. Сервер должен слушать `0.0.0.0:8080`, а входящий TCP-порт 8080 должен быть разрешён в firewall хоста. Gradle генерирует Network Security Config из выбранного URL: HTTP разрешён только для его точного host в debug и release, а базовая политика запрещает cleartext для остальных адресов. Для HTTPS исключение cleartext не создаётся.

Распознавание может занимать несколько минут на локальном CPU runtime: клиент ждёт соединение до 15 секунд и ответ до 10 минут. Сетевые ошибки, HTTP-ошибки и ошибки backend можно повторить с текущим изображением. Успешный HTTP-ответ остаётся черновиком OCR; он не подтверждает качество распознавания или доставку экспорта. Одна кнопка отправки валидирует текущие значения и при отсутствии ошибок экспортирует канонический `PassportSnapshot`; отдельного ручного подтверждения результата OCR нет.

В APK не встраиваются общие ключи доступа.

## Android-проект

Один Kotlin-модуль `app`, Jetpack Compose Material 3, CameraX, ViewModel и Ktor/OkHttp. `minSdk` — 26. Камера объявлена необязательной.

Закреплённые версии: AGP 8.13.2, Gradle 8.13, Kotlin 2.3.21, compile/target SDK 36, AndroidX Core KTX 1.18.0, CameraX 1.6.2, Compose BOM 2026.02.00, Lifecycle 2.10.0, Ktor 3.5.2, Commons Compress 1.28.0, XZ for Java 1.12. Источники совместимости и версий: [AGP 8.13 release notes](https://developer.android.com/build/releases/agp-8-13-0-release-notes), [Gradle compatibility](https://docs.gradle.org/current/userguide/compatibility.html), [AndroidX versions](https://developer.android.com/jetpack/androidx/versions), [Compose BOM](https://developer.android.com/develop/ui/compose/bom), [Kotlin releases](https://kotlinlang.org/docs/releases.html), [Ktor releases](https://ktor.io/docs/releases.html), [Apache Commons Compress releases](https://commons.apache.org/proper/commons-compress/changes.html), [XZ for Java](https://tukaani.org/xz/java.html), [Temurin 17.0.21+9 for macOS ARM64](https://github.com/adoptium/temurin17-binaries/releases/tag/jdk-17.0.21%2B9).

## Подготовка Android SDK

Для AGP 8.13.2 требуется JDK 17. На текущей ARM64-среде предустановленный JBR 17.0.7 падал внутри HotSpot C1-компилятора во время lint, поэтому используйте изолированный Temurin 17.0.21+9 в `.tools/jdk17`; скрипт проверяет SHA-256 и не меняет глобальную установку Java. Затем `scripts/bootstrap-android-sdk.sh` загружает закреплённый Google command-line tools архив, проверяет его SHA-256 и устанавливает `platforms;android-36`, `build-tools;36.0.0` и `platform-tools`. Скрипт принимает стандартные лицензии Android SDK через `sdkmanager` и останавливается при ошибке.

```sh
./scripts/bootstrap-local-jdk17.sh
export JAVA_HOME="$PWD/.tools/jdk17/Contents/Home"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
./scripts/bootstrap-android-sdk.sh
./gradlew --version
```

## Проверки

```sh
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:assembleRelease :app:lintVitalRelease
```

Текущая проверка: assembleDebug и assembleRelease, `lintDebug` (0 errors, 19 warnings), 21/21 JVM unit tests и 10/10 connected tests на Android 36 emulator; у тестов 0 failures, errors и skipped. Независимый внешний 7-Zip verifier прошёл. Проверялся новый flow с общей demo-плашкой и редактируемыми значениями без field-маркеров и отдельного подтверждения; использовались demo-данные. Backend и live OCR не проверялись, настоящее письмо не отправлялось.

- Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Connected report: `app/build/reports/androidTests/connected/debug/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- Сохранённые Gradle логи: `build/verification/*-simplified.log`.
- Внешний 7z proof создаёт `build/verification/archive-proof.7z` и `archive-proof.expected.txt`; его можно повторить через `./scripts/verify-archive-proof.sh`.

Connected tests выполнялись на Android 36 emulator; физический телефон не проверялся. `FakeEmailActivity` включён только в test APK. APK alignment не входил в текущий набор проверок.

### Независимая проверка 7z

Unit test `PassportExporterTest.writesSyntheticArchiveForIndependent7ZipVerification` создаёт синтетический архив `build/verification/archive-proof.7z` и ожидаемый `archive-proof.expected.txt`. Скрипт [`scripts/verify-archive-proof.sh`](scripts/verify-archive-proof.sh) использует локальный `.tools/7zip/7zz`, проверяет шифрование паролем `123`, состав архива, содержимое TXT и отказ для неверного пароля. Проверка прошла; бинарник игнорируется Git и отдельно не коммитится.

Для установки консольного 7-Zip 26.05 для macOS возьмите официальный [архив 7z2605-mac.tar.xz](https://github.com/ip7z/7zip/releases/download/26.05/7z2605-mac.tar.xz). Архив содержит `7zz` в корне; проверьте SHA-256 `12ef6830317743cfd3f0ab1724bde43fcc270d8b6d8564f8c12c2a2b7f993570`, прежде чем устанавливать ignored-бинарник в `.tools/7zip/7zz`:

```sh
set -euo pipefail
tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
curl --fail --location --show-error \
  https://github.com/ip7z/7zip/releases/download/26.05/7z2605-mac.tar.xz \
  --output "$tmp_dir/7z2605-mac.tar.xz"
printf '12ef6830317743cfd3f0ab1724bde43fcc270d8b6d8564f8c12c2a2b7f993570  %s\n' \
  "$tmp_dir/7z2605-mac.tar.xz" | shasum --check --algorithm 256
tar -xJf "$tmp_dir/7z2605-mac.tar.xz" -C "$tmp_dir"
mkdir -p .tools/7zip
install -m 755 "$tmp_dir/7zz" .tools/7zip/7zz
```

После этого запустите unit test и независимую проверку:

```sh
./gradlew :app:testDebugUnitTest
./scripts/verify-archive-proof.sh
```

## Источники истины

- [Backend README](../passport-backend/README.md)
- [Продукт](../passport-backend/docs/PRODUCT.md)
- [Архитектура](../passport-backend/docs/ARCHITECTURE.md)
- [OCR](../passport-backend/docs/OCR.md)
- [Проверки backend](../passport-backend/docs/TESTING.md)
- [План backend](../passport-backend/docs/PLAN.md)
- [OpenAPI](../passport-backend/api/openapi.yaml)
