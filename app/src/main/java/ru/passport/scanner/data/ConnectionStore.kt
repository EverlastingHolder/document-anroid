package ru.passport.scanner.data

/** Stores one operator-provisioned API connection outside APK configuration and backup. */
interface ConnectionStore {
    suspend fun load(): ApiConnection?
    suspend fun save(connection: ApiConnection)
    suspend fun clear()
}
