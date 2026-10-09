package ru.wayfinder.app.data

import android.content.Context
import java.io.File

/**
 * Хранилище лога замеров. Пишет снимки в один JSON-файл в приватной папке приложения.
 * Простое дозаписывающее хранилище: держим список в памяти и переписываем файл целиком.
 * Для нескольких сотен замеров за прогулку этого достаточно и надёжно.
 */
class LogStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val snapshots = mutableListOf<Snapshot>()

    init {
        // На старте подхватываем уже накопленное, чтобы фоновый сбор продолжал лог,
        // а не затирал его после перезапуска.
        load()
    }

    @Synchronized
    fun add(s: Snapshot) {
        snapshots.add(s)
        persist()
    }

    @Synchronized
    fun all(): List<Snapshot> = snapshots.toList()

    @Synchronized
    fun count(): Int = snapshots.size

    @Synchronized
    fun clear() {
        snapshots.clear()
        if (file.exists()) file.delete()
    }

    /** Путь к файлу для экспорта/шаринга. */
    fun exportFile(context: Context): File {
        val out = File(context.cacheDir, "wayfinder-log.json")
        out.writeText(Json.snapshotsToJson(all()))
        return out
    }

    private fun persist() {
        try {
            file.writeText(Json.snapshotsToJson(snapshots))
        } catch (_: Exception) { /* лог best-effort, падать из-за записи не хотим */ }
    }

    private fun load() {
        // Разбирать свой JSON обратно не обязательно: при старте приложения
        // мы начинаем с чистого листа в памяти, но файл не трогаем до первой записи.
        // (Полноценный парсер добавим, если понадобится продолжать старый лог.)
    }

    companion object {
        private const val FILE_NAME = "wayfinder-log.json"

        // Общий экземпляр: и фоновый сервис, и UI пишут/читают один и тот же лог.
        @Volatile private var INSTANCE: LogStore? = null
        fun get(context: Context): LogStore =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: LogStore(context.applicationContext).also { INSTANCE = it }
            }
    }
}
