package ru.wayfinder.app.data

/**
 * Один «снимок» сети = результат одного полного прогона зондов плюс контекст.
 * Это и есть единица лога: массив таких объектов экспортируется в JSON.
 *
 * schemaVersion нужен, чтобы при будущих правках формата старые логи
 * оставались читаемыми и ты не терял данные прошлых прогулок.
 */
data class Snapshot(
    val schemaVersion: Int = SCHEMA_VERSION,
    val timestamp: Long,              // epoch millis
    val isoTime: String,              // человекочитаемое время
    val context: NetCtx,              // контекст сети на момент замера
    val probes: List<ProbeLine>,      // плоский список всех зондов
    val verdict: String = ""          // краткий авто-вывод (эвристика), заполняется раннером
) {
    companion object { const val SCHEMA_VERSION = 1 }
}

/** Контекст сети. Поля, которые не удалось получить (нет разрешения и т.п.), остаются null. */
data class NetCtx(
    val networkType: String? = null,   // "mobile 4G" / "wifi" / ...
    val operator: String? = null,      // имя оператора
    val mccMnc: String? = null,        // код сети
    val signalDbm: Int? = null,        // уровень сигнала
    val cellId: String? = null,        // идентификатор соты (по разрешению)
    val lac: String? = null,           // LAC/TAC
    val lat: Double? = null,           // координаты (по разрешению)
    val lon: Double? = null,
    val resolver: String? = null,      // IP DNS-резолвера оператора (его опрашивает DnsProbe)
    val localIp: String? = null        // локальный IP устройства
)

/**
 * Одна строка результата зонда в плоском, удобном для анализа виде.
 * Сюда приводятся и Probe.ProbeResult, и DnsProbe.DnsResult.
 */
data class ProbeLine(
    val group: String,     // "neutral" | "whitelist" | "subnet" | "dns" | "tunnel" | "server"
    val kind: String,      // "tcp" | "tls" | "udp" | "dns"
    val label: String,
    val target: String,
    val sni: String? = null,
    val outcome: String,   // строковое имя исхода
    val ms: Long,
    val detail: String = ""
)
