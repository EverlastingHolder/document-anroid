package ru.passport.scanner.data.secure

/** The encrypted local record exists but its key or authenticated contents cannot be recovered. */
class SecureStorageNeedsResetException : Exception("Сохранённые защищённые данные недоступны. Настройте подключение заново.")

/** A private encrypted file operation failed. The cause is intentionally not exposed. */
class SecureStorageUnavailableException : Exception("Локальное защищённое хранилище временно недоступно.")
