# Сканер документов

Android-приложение для съёмки или выбора изображения документа, распознавания данных, их редактирования и экспорта.

[Скачать APK для Android 8.0+](https://github.com/EverlastingHolder/document-anroid/releases/download/v1.0.0/document-scanner-1.0.apk) — debug-сборка. Для распознавания требуется доступный сервер.

## Требования

- JDK 17
- Android SDK 36 и Build Tools 36.0.0
- Android 8.0 (API 26) или новее; подойдёт устройство или эмулятор

## Запуск

Подключите устройство или запустите эмулятор, затем выполните:

```sh
./gradlew :app:installDebug
adb shell monkey -p ru.passport.scanner 1
```

Debug-сборка по умолчанию подключается к `http://192.168.1.91:8080`. Чтобы использовать другой сервер, замените пример адреса на адрес своего сервера:

```sh
./gradlew :app:installDebug -POCR_BASE_URL=http://192.168.1.100:8080
./gradlew :app:installDebug -POCR_BASE_URL=https://ocr.example.com
```

Для демонстрационного режима без подключения к серверу:

```sh
./gradlew :app:installDebug -POCR_USE_STUB=true
```

## Сборка

```sh
./gradlew :app:assembleDebug
```

Debug APK появится в `app/build/outputs/apk/debug/app-debug.apk`.
