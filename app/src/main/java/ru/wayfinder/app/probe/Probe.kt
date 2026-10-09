package ru.wayfinder.app.probe

import java.io.IOException
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.net.ssl.SNIHostName
import javax.net.ssl.SNIServerName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate
import kotlin.system.measureTimeMillis

/**
 * Низкоуровневые сетевые зонды для диагностики ограничений мобильной сети.
 *
 * Основано на DpiProbe/DpiDiag из форка WhiteListCheck (MIT).
 * Каждый зонд возвращает ProbeResult с точным типом исхода и временем в мс,
 * чтобы потом отличить фильтр по IP (таймаут на SYN) от DPI по SNI (RST после ClientHello)
 * от блокировки конкретного IP/порта.
 */
object Probe {

    // Как завершился зонд. Именно тип исхода, а не просто "ошибка",
    // позволяет сделать вывод о характере ограничения.
    enum class Outcome {
        OK,          // штатно отработал
        TIMEOUT,     // истёк таймаут (нет ответа) — типично для L3-фильтра по IP (дроп SYN)
        RESET,       // соединение сброшено (RST) — типично для активного DPI
        REFUSED,     // ECONNREFUSED — порт закрыт либо сброс у оператора
        DNS_FAIL,    // имя не разрешилось
        TLS_FAIL,    // рукопожатие TLS не удалось (не RST)
        OTHER        // прочее
    }

    data class ProbeResult(
        val label: String,       // человекочитаемое имя зонда
        val kind: String,        // "tcp" | "tls" | "udp" | "dns" — для JSON
        val target: String,      // ip/host:port или имя
        val sni: String? = null, // какой SNI использовали (для tls)
        val outcome: Outcome,
        val ms: Long,            // длительность
        val detail: String = ""  // текст исключения/подробность
    )

    private const val DEFAULT_CONNECT_MS = 4000
    private const val DEFAULT_TLS_MS = 5000
    private const val DEFAULT_UDP_MS = 2500

    // Доверяем любому сертификату: зонд проверяет, ДОШЛО ли рукопожатие сквозь фильтр,
    // а не валиден ли сертификат. Иначе самоподписанные/нестандартные серты (напр. Сбер
    // за балансировщиком) давали бы ложную ошибку, хотя соединение прошло.
    private val trustAllFactory: SSLSocketFactory by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(null, trustAll, java.security.SecureRandom())
        ctx.socketFactory
    }

    // ── TCP: чистый connect на порт, без TLS ────────────────────────────────
    // Таймаут  → фильтр по IP (пакет дропнут у оператора).
    // REFUSED  → порт закрыт или оператор шлёт RST на SYN.
    // OK       → до хоста дошли, L3 не режет.
    fun tcpConnect(
        ip: String,
        port: Int = 443,
        label: String = "$ip:$port",
        timeoutMs: Int = DEFAULT_CONNECT_MS
    ): ProbeResult {
        var outcome = Outcome.OTHER
        var detail = ""
        val t = measureTimeMillis {
            try {
                Socket().use { s ->
                    s.connect(InetSocketAddress(ip, port), timeoutMs)
                    outcome = Outcome.OK
                }
            } catch (e: SocketTimeoutException) {
                outcome = Outcome.TIMEOUT; detail = e.message ?: "timeout"
            } catch (e: ConnectException) {
                val m = (e.message ?: "").lowercase()
                outcome = when {
                    "refused" in m -> Outcome.REFUSED
                    "reset" in m -> Outcome.RESET
                    else -> Outcome.REFUSED
                }
                detail = e.message ?: "connect error"
            } catch (e: IOException) {
                val m = (e.message ?: "").lowercase()
                outcome = if ("reset" in m) Outcome.RESET else Outcome.OTHER
                detail = e.message ?: "io error"
            }
        }
        return ProbeResult(label, "tcp", "$ip:$port", null, outcome, t, detail)
    }

    // ── TLS: ClientHello с заданным SNI на уже достижимый IP ─────────────────
    // Соль метода — подавать РАЗНЫЕ SNI на ОДИН IP:
    //   свой (родной для IP), запрещённый, разрешённый-чужой.
    // RESET/обрыв после ClientHello при работающем TCP → DPI именно по SNI.
    // Сравнение исходов для разных SNI на одном IP показывает,
    // сверяет ли ТСПУ соответствие SNI и IP.
    fun tlsWithSni(
        ip: String,
        sni: String,
        port: Int = 443,
        label: String = "$ip ($sni)",
        timeoutMs: Int = DEFAULT_TLS_MS
    ): ProbeResult {
        var outcome = Outcome.OTHER
        var detail = ""
        val t = measureTimeMillis {
            var raw: Socket? = null
            try {
                raw = Socket()
                raw.connect(InetSocketAddress(ip, port), timeoutMs)
                val ssl = trustAllFactory.createSocket(raw, ip, port, true) as SSLSocket
                ssl.soTimeout = timeoutMs
                // Подставляем нужный SNI вручную, не привязываясь к IP.
                val params: SSLParameters = ssl.sslParameters
                params.serverNames = listOf<SNIServerName>(SNIHostName(sni))
                ssl.sslParameters = params
                ssl.startHandshake()   // здесь DPI по SNI и обрывает
                ssl.close()
                outcome = Outcome.OK
            } catch (e: SocketTimeoutException) {
                outcome = Outcome.TIMEOUT; detail = e.message ?: "timeout"
            } catch (e: IOException) {
                val m = (e.message ?: "").lowercase()
                outcome = when {
                    "reset" in m -> Outcome.RESET
                    "refused" in m -> Outcome.REFUSED
                    "handshake" in m || "ssl" in m || "tls" in m -> Outcome.TLS_FAIL
                    else -> Outcome.OTHER
                }
                detail = e.message ?: "tls error"
            } finally {
                try { raw?.close() } catch (_: Exception) {}
            }
        }
        return ProbeResult(label, "tls", "$ip:$port", sni, outcome, t, detail)
    }

    // ── UDP/QUIC: пробный датаграм-пакет и ожидание ответа ───────────────────
    // Проверяем, проходит ли UDP вообще (важно для Hysteria2/WireGuard/QUIC).
    // Шлём минимальный QUIC-подобный Initial в никуда и ждём любой ответ:
    // нам важен не разбор ответа, а сам факт, что UDP на этот IP не глухой.
    fun udpProbe(
        ip: String,
        port: Int = 443,
        label: String = "$ip:$port/udp",
        timeoutMs: Int = DEFAULT_UDP_MS
    ): ProbeResult {
        var outcome = Outcome.OTHER
        var detail = ""
        val t = measureTimeMillis {
            var sock: DatagramSocket? = null
            try {
                sock = DatagramSocket()
                sock.soTimeout = timeoutMs
                val addr = InetAddress.getByName(ip)
                // 1200 байт случайных данных с выставленным "long header" битом —
                // достаточно, чтобы сервер с QUIC/UDP хоть как-то отреагировал
                // (ответ, ICMP-unreachable или тишина). Нам нужен сам факт ответа.
                val payload = ByteArray(64).also { it[0] = 0xC0.toByte() }
                sock.send(DatagramPacket(payload, payload.size, addr, port))
                val buf = ByteArray(1500)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)   // любой ответ → UDP проходит
                outcome = Outcome.OK
                detail = "ответ ${resp.length} байт"
            } catch (e: SocketTimeoutException) {
                outcome = Outcome.TIMEOUT; detail = "нет ответа"
            } catch (e: IOException) {
                val m = (e.message ?: "").lowercase()
                outcome = if ("refused" in m) Outcome.REFUSED else Outcome.OTHER
                detail = e.message ?: "udp error"
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        return ProbeResult(label, "udp", "$ip:$port", null, outcome, t, detail)
    }
}
