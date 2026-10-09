package ru.wayfinder.app.probe

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Random
import kotlin.system.measureTimeMillis

/**
 * DNS-зонды. Отвечают на два вопроса:
 *   1) резолвит ли резолвер оператора произвольные домены (зарубежные/заблокированные);
 *   2) жизнеспособен ли DNS-туннель — проходят ли TXT-ответы и длинные поддомены.
 *
 * Запросы строятся вручную и шлются на указанный резолвер по UDP:53,
 * чтобы померить тип записи (A/TXT), длину имени и то, что реально вернулось,
 * а не полагаться на системный getByName (он умеет только A/AAAA и прячет детали).
 */
object DnsProbe {

    enum class DnsOutcome { OK, TIMEOUT, REFUSED, NXDOMAIN, TRUNCATED, FORMERR, EMPTY, OTHER }

    data class DnsResult(
        val label: String,
        val name: String,       // запрошенное имя
        val type: String,       // "A" | "TXT"
        val resolver: String,   // какой резолвер опрашивали
        val outcome: DnsOutcome,
        val ms: Long,
        val answer: String = "" // первая запись ответа, если есть
    )

    private const val TYPE_A = 1
    private const val TYPE_TXT = 16
    private const val DEFAULT_MS = 4000

    /** Запрос записи [type] имени [name] к резолверу [resolver] по UDP:53. */
    fun query(
        name: String,
        typeStr: String = "A",
        resolver: String,
        label: String = "$name/$typeStr @$resolver",
        timeoutMs: Int = DEFAULT_MS
    ): DnsResult {
        val type = if (typeStr.equals("TXT", true)) TYPE_TXT else TYPE_A
        var outcome = DnsOutcome.OTHER
        var answer = ""
        val t = measureTimeMillis {
            var sock: DatagramSocket? = null
            try {
                val id = Random().nextInt(0xFFFF)
                val packet = buildQuery(id, name, type)
                sock = DatagramSocket()
                sock.soTimeout = timeoutMs
                sock.connect(InetSocketAddress(InetAddress.getByName(resolver), 53))
                sock.send(DatagramPacket(packet, packet.size))

                val buf = ByteArray(4096)
                val resp = DatagramPacket(buf, buf.size)
                sock.receive(resp)
                val parsed = parseResponse(buf, resp.length, type)
                outcome = parsed.first
                answer = parsed.second
            } catch (e: java.net.SocketTimeoutException) {
                outcome = DnsOutcome.TIMEOUT
            } catch (e: Exception) {
                val m = (e.message ?: "").lowercase()
                outcome = if ("refused" in m) DnsOutcome.REFUSED else DnsOutcome.OTHER
                answer = e.message ?: ""
            } finally {
                try { sock?.close() } catch (_: Exception) {}
            }
        }
        return DnsResult(label, name, typeStr.uppercase(), resolver, outcome, t, answer)
    }

    // Сборка минимального DNS-запроса (header + один вопрос), recursion desired.
    private fun buildQuery(id: Int, name: String, type: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id shr 8); out.write(id and 0xFF)
        out.write(0x01); out.write(0x00)          // flags: RD=1
        out.write(0x00); out.write(0x01)          // QDCOUNT=1
        out.write(0x00); out.write(0x00)          // ANCOUNT
        out.write(0x00); out.write(0x00)          // NSCOUNT
        out.write(0x00); out.write(0x00)          // ARCOUNT
        for (part in name.split(".")) {
            val b = part.toByteArray(Charsets.US_ASCII)
            out.write(b.size); out.write(b)
        }
        out.write(0x00)                           // конец имени
        out.write(type shr 8); out.write(type and 0xFF)
        out.write(0x00); out.write(0x01)          // класс IN
        return out.toByteArray()
    }

    // Очень экономный разбор: нам нужны RCODE, TC-бит, ANCOUNT и первая запись.
    private fun parseResponse(buf: ByteArray, len: Int, type: Int): Pair<DnsOutcome, String> {
        if (len < 12) return DnsOutcome.OTHER to "короткий ответ"
        val flags2 = buf[3].toInt() and 0xFF
        val rcode = flags2 and 0x0F
        val truncated = (buf[2].toInt() and 0x02) != 0
        if (truncated) return DnsOutcome.TRUNCATED to ""
        when (rcode) {
            1 -> return DnsOutcome.FORMERR to ""
            3 -> return DnsOutcome.NXDOMAIN to ""
            5 -> return DnsOutcome.REFUSED to ""
        }
        val anCount = ((buf[6].toInt() and 0xFF) shl 8) or (buf[7].toInt() and 0xFF)
        if (anCount == 0) return DnsOutcome.EMPTY to ""

        // Пропускаем вопрос, затем читаем первую запись answer.
        var p = 12
        p = skipName(buf, p); p += 4               // QTYPE+QCLASS
        p = skipName(buf, p)                       // имя записи (обычно compression ptr)
        if (p + 10 > len) return DnsOutcome.OK to "запись есть"
        val rType = ((buf[p].toInt() and 0xFF) shl 8) or (buf[p + 1].toInt() and 0xFF)
        val rdLen = ((buf[p + 8].toInt() and 0xFF) shl 8) or (buf[p + 9].toInt() and 0xFF)
        val rd = p + 10
        val ans = when (rType) {
            TYPE_A -> if (rd + 4 <= len)
                "${buf[rd].toInt() and 0xFF}.${buf[rd+1].toInt() and 0xFF}." +
                "${buf[rd+2].toInt() and 0xFF}.${buf[rd+3].toInt() and 0xFF}" else "?"
            TYPE_TXT -> if (rd + 1 <= len) {
                val l = buf[rd].toInt() and 0xFF
                if (rd + 1 + l <= len) String(buf, rd + 1, l, Charsets.US_ASCII) else "txt?"
            } else "txt?"
            else -> "тип $rType, $rdLen байт"
        }
        return DnsOutcome.OK to ans
    }

    private fun skipName(buf: ByteArray, start: Int): Int {
        var p = start
        while (p < buf.size) {
            val l = buf[p].toInt() and 0xFF
            when {
                l == 0 -> return p + 1
                l and 0xC0 == 0xC0 -> return p + 2   // compression pointer
                else -> p += l + 1
            }
        }
        return p
    }
}
