package ru.wayfinder.app.runner

import ru.wayfinder.app.BuildConfig

/**
 * Единая точка для чувствительных, не-в-коде настроек: твои серверы и домен туннеля.
 * Значения приходят из BuildConfig (их туда кладёт сборка из переменных окружения /
 * секретов репозитория). В исходниках — ничего приватного, можно держать реп открытым.
 *
 * Формат WAYFINDER_SERVERS:  name|ip|port|sni;name|ip|port|sni
 *   пример:  RU|176.0.0.1|8443|www.example.com;SW|89.0.0.1|443|www.example.com
 * Формат WAYFINDER_TUNNEL:   домен туннеля, например  t.example.xyz
 *
 * Пусто → соответствующие зонды просто не запускаются.
 */
object Config {

    data class Server(val name: String, val ip: String, val port: Int, val sni: String)

    val servers: List<Server> by lazy { parseServers(BuildConfig.SERVERS_CONFIG) }

    val tunnelDomain: String? by lazy {
        BuildConfig.TUNNEL_DOMAIN.trim().ifEmpty { null }
    }

    // probe.<домен> для TXT-теста и длинное имя для проверки обрезки.
    val tunnelTxtName: String? get() = tunnelDomain?.let { "probe.$it" }
    val tunnelLongName: String? get() = tunnelDomain?.let {
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.$it"
    }

    private fun parseServers(raw: String): List<Server> {
        if (raw.isBlank()) return emptyList()
        return raw.split(";").mapNotNull { entry ->
            val p = entry.split("|")
            if (p.size != 4) return@mapNotNull null
            val port = p[2].trim().toIntOrNull() ?: return@mapNotNull null
            Server(p[0].trim(), p[1].trim(), port, p[3].trim())
        }
    }
}
