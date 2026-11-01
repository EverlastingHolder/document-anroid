# Сканер документов

Android-приложение для отправки снимка документа в backend, просмотра результата и подготовки экспорта. Настоящий режим асинхронный; демонстрационные данные доступны только в явно выбранной debug-сборке.

## Сценарий первой версии

Пользователь фотографирует документ через CameraX или выбирает одно изображение в системном Photo Picker. Приложение показывает снимок без обрезки и поворота. При первом подключении создаётся отдельная установка клиента: приложение формирует случайный bearer-токен, сохраняет его в Android Keystore-backed зашифрованном хранилище и регистрирует на backend только SHA-256 токена. В зашифрованном профиле подключения сохраняются только адрес сервера и скрытый credential установки; полей для логина, токена или QR-кода нет. Снимок отправляется `POST /v1/passport/jobs` как одна multipart-часть `file` с JPEG, PNG или WebP; состояние читается через `GET /v1/passport/jobs/{job_id}` согласно [OpenAPI-контракту](../passport-backend/api/openapi.yaml).

Клиент поддерживает одно активное задание в памяти текущего процесса. Выбранное изображение читается в RAM; CameraX передаёт JPEG через `ImageProxy`, без файла снимка. Точные байты и ключ повтора остаются в RAM только для неоднозначного повтора `POST` в том же процессе. После `202` байты очищаются, а для foreground polling в RAM остаётся только ID задания. При новом запуске приложения задание не восстанавливается и снимок не отправляется повторно: пользователь начинает новый скан, а уже принятое сервером задание может продолжить выполняться.

После завершения результат сначала применяется к живому состоянию экрана в RAM, затем клиент отправляет `POST /v1/passport/jobs/{job_id}/receipt`. Результат и сводка ошибки не записываются на устройство. Если процесс завершится до получения или применения результата, восстановить его из приложения нельзя. Если процесс завершится после подтверждения receipt, серверный результат может быть удалён и не будет доступен приложению повторно. Сервер удаляет неподтверждённый результат по истечении 15 минут. Приложение опрашивает сервер только на переднем плане и не хранит историю заданий. «Остановить отслеживание» прекращает локальное отслеживание, но не отменяет уже принятое сервером задание. Состояния queued/processing/failed показываются без job ID и внутренних деталей OCR.

Завершённый canonical draft открывается в существующей форме редактирования. Ошибки валидации остаются видимыми; когда текущие значения валидны, кнопка сразу создаёт `document.txt` из канонического `PassportSnapshot`, упаковывает его в архив `document.7z` с AES-шифрованием и паролем `123` и открывает email-only chooser на `test@mail.ru`. Маркеры распознавания отдельных полей и ручное подтверждение результата не используются. SMTP и отправка почты через backend не используются.

При отказе в разрешении камеры приложение оставляет Photo Picker. ViewModel сохраняет состояние только при повороте экрана и пока жив процесс. На старте удаляются только известные старые каталоги снимков, pending-job файлы и архивы экспорта предыдущих версий; зашифрованный профиль подключения и пользовательские файлы не затрагиваются. Фото, задания, результаты и архив экспорта существуют только в RAM; явный экспорт формирует зашифрованный 7z и передаёт его почтовому приложению через read-only URI provider, не создавая архивный файл приложения. В RAM удерживается не более одного архива до его замены, очистки или завершения процесса. Не записывай фотографии, токены и паспортные значения в журналы.

## Demo и настоящий backend

Debug-сборка по умолчанию использует `OCR_BASE_URL=http://192.168.1.91:8080`; адрес можно изменить Gradle property `-POCR_BASE_URL=https://<домен>` или, только для debug, `-POCR_BASE_URL=http://<адрес>:8080`. Release-сборка принимает только HTTPS URL; если задан только HTTP-адрес, поле адреса останется пустым и приложение предложит настроить сервер. Пустой URL не включает demo. Явный demo выбирается только для debug командой `-POCR_USE_STUB=true`; release всегда принудительно задаёт `false`. URL должен быть абсолютным HTTP/HTTPS URL с host и без учётных данных, query и fragment.

Для локального HTTP debug-адреса телефон должен быть в той же локальной сети, что и backend. Debug Network Security Config разрешает cleartext в debug-сборке, а `ApiConnection` допускает HTTP только для loopback и частных IP. Release Network Security Config запрещает cleartext, а `ApiConnection` требует HTTPS до регистрации клиента.

Сетевые запросы и повторная регистрация используют защищённый транспорт. Задание не начинает распознаваться синхронно внутри Android; принятое состояние может оставаться в очереди до запуска backend worker. Готовый результат открывается как редактируемый черновик, а экспорт использует текущий canonical `PassportSnapshot`; успешный статус не подтверждает качество OCR или доставку письма.

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

Текущая проверка RAM-only версии: `assembleDebug`, `assembleRelease`, `lintDebug`, 73/73 JVM unit tests и 19/19 instrumented tests на отдельном Android 36 AVD; failures, errors и skipped — 0. Instrumented suite запускался адресно через `adb -s emulator-5566 shell am instrument` и включает in-memory JPEG/previews, очистку legacy scan/pending-job путей без затрагивания connection record, HTTP multipart/status/receipt, синтетическую композицию manager → hash-only registrar → Ktor repository → ViewModel, progress, editing, экспорт 7z в RAM и чтение архива тестовым почтовым приложением через read-only URI provider. Live Go retention endpoint, production OCR/models, camera hardware и реальная фотография в этом прогоне не проверялись; настоящее письмо не отправлялось. Ранее опубликованные 76/17 counts относились к варианту с зашифрованным локальным job/result journal и больше не являются текущим доказательством.

- Unit report: `app/build/reports/tests/testDebugUnitTest/index.html`.
- Connected report: `app/build/reports/androidTests/connected/debug/index.html`.
- Lint report: `app/build/reports/lint-results-debug.html`.
- Сохранённые Gradle логи: `build/verification/*-simplified.log`.

Connected tests выполнялись на Android 36 emulator; физический телефон не проверялся. `FakeEmailActivity` включён только в test APK. APK alignment не входил в текущий набор проверок.

## Источники истины

- [Backend README](../passport-backend/README.md)
- [Продукт](../passport-backend/docs/PRODUCT.md)
- [Архитектура](../passport-backend/docs/ARCHITECTURE.md)
- [OCR](../passport-backend/docs/OCR.md)
- [Проверки backend](../passport-backend/docs/TESTING.md)
- [План backend](../passport-backend/docs/PLAN.md)
- [OpenAPI](../passport-backend/api/openapi.yaml)
