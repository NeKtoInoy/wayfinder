package ru.wayfinder.app.runner

import android.content.Context
import ru.wayfinder.app.context.LocationProvider
import ru.wayfinder.app.context.NetworkContext
import ru.wayfinder.app.data.NetCtx
import ru.wayfinder.app.data.ProbeLine
import ru.wayfinder.app.data.Snapshot
import ru.wayfinder.app.probe.DnsProbe
import ru.wayfinder.app.probe.Probe
import ru.wayfinder.app.probe.ProbeTargets
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Склеивает всё в один замер (Snapshot): собирает контекст, прогоняет зонды,
 * выносит грубый авто-вердикт. Вся последовательность зондов — здесь, в одном месте,
 * чтобы добавить/убрать проверку = поправить один метод, не трогая остальное.
 *
 * Важная тонкость: перед TLS-SNI тестом эталоны (ya.ru/vk.com) резолвятся заново,
 * потому что их IP плавают, а тест «чужой SNI на IP» корректен только на живом IP.
 */
class ProbeRunner(private val ctx: Context) {

    private val netCtx = NetworkContext(ctx)
    private val loc = LocationProvider(ctx)
    private val iso = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun runOnce(source: String = "manual", onProgress: (String) -> Unit = {}): Snapshot {
        val (lat, lon) = loc.fix()
        val context = netCtx.collect(lat, lon)
        val lines = mutableListOf<ProbeLine>()

        onProgress("Нейтральные IP (фильтр по IP)…")
        probeNeutral(lines)

        onProgress("Белые эталоны + SNI…")
        probeWhitelist(lines)

        onProgress("Подсети-кандидаты…")
        probeSubnets(lines)

        onProgress("UDP / QUIC…")
        probeUdp(lines)

        onProgress("DNS…")
        probeDns(lines, context)

        onProgress("Перебор SNI-доноров…")
        probeSniScan(lines)

        onProgress("Твои серверы…")
        probeServers(lines)

        val verdict = buildString {
            // VPN искажает всё — выносим предупреждение в начало вердикта.
            if (context.vpnActive) append("⚠ ВКЛЮЧЁН VPN — замер недостоверен · ")
            append(heuristicVerdict(lines))
        }
        return Snapshot(
            timestamp = System.currentTimeMillis(),
            isoTime = iso.format(Date()),
            context = context,
            probes = lines,
            verdict = verdict,
            source = source
        )
    }

    // ── наборы зондов ────────────────────────────────────────────────────────

    private fun probeNeutral(out: MutableList<ProbeLine>) {
        for (h in ProbeTargets.NEUTRAL) {
            out += Probe.tcpConnect(h.ip, 443, "${h.name} ${h.ip}").toLine("neutral")
        }
    }

    private fun probeWhitelist(out: MutableList<ProbeLine>) {
        for (h in ProbeTargets.WHITELIST) {
            val domain = h.note
            val ip = resolve(domain) ?: continue
            // TCP до живого IP
            out += Probe.tcpConnect(ip, 443, "${h.name} $domain").toLine("whitelist")
            // Набор SNI на этот же IP — ядро диагностики
            for (c in ProbeTargets.SNI_SET) {
                val sni = if (c.sni.isEmpty()) domain else c.sni
                out += Probe.tlsWithSni(ip, sni, 443, "${h.name} [${c.kind}:$sni]")
                    .toLine("whitelist")
            }
        }
    }

    private fun probeSubnets(out: MutableList<ProbeLine>) {
        for (h in ProbeTargets.SUBNETS) {
            // Резолвим домен; если DNS отравлен/молчит — берём запасной IP из h.ip.
            var ip = resolve(h.note)
            if (ip == null || DnsProbe.looksPoisoned(ip)) ip = h.ip.ifEmpty { null }
            if (ip == null) {
                out += ProbeLine("subnet", "tcp", "${h.name} ${h.note}", h.note,
                    null, "DNS_FAIL", 0, "не разрешился и нет запасного IP")
                continue
            }
            out += Probe.tcpConnect(ip, 443, "${h.name} ${h.note}").toLine("subnet")
        }
    }

    private fun probeUdp(out: MutableList<ProbeLine>) {
        // UDP к одному нейтральному и одному белому — проходит ли QUIC вообще
        out += Probe.udpProbe("8.8.8.8", 443, "Google UDP").toLine("udp")
        val yaIp = resolve("ya.ru")
        if (yaIp != null) out += Probe.udpProbe(yaIp, 443, "Яндекс UDP").toLine("udp")
    }

    private fun probeDns(out: MutableList<ProbeLine>, c: NetCtx) {
        val resolver = c.resolver ?: return   // без резолвера оператора DNS-тесты бессмысленны
        for (name in ProbeTargets.DNS_NAMES) {
            val r = DnsProbe.query(name, "A", resolver)
            // Детектор подмены: заблокированный домен, отвечающий loopback/приватным
            // адресом — признак отравления DNS оператором.
            val blocked = name in ProbeTargets.BLOCKED_DNS_NAMES
            val ip = r.answer.removePrefix("A ").trim()
            val poisoned = blocked && r.outcome.name == "OK" && DnsProbe.looksPoisoned(ip)
            val line = r.toLine("dns")
            out += if (poisoned)
                line.copy(outcome = "POISONED", detail = "подмена: $ip")
            else line
        }
        // Жизнеспособность туннеля — только если задан домен туннеля (из секретов).
        val txt = Config.tunnelTxtName
        val long = Config.tunnelLongName
        if (txt != null) out += DnsProbe.query(txt, "TXT", resolver, "туннель TXT").toLine("tunnel")
        if (long != null) out += DnsProbe.query(long, "A", resolver, "туннель длинный").toLine("tunnel")
    }

    // Перебор SNI-доноров к зарубежному IP. Какие имена проходят TLS, а какие
    // молча режутся фильтром оператора по имени. Сначала проверяем, что до IP
    // вообще есть TCP — иначе перебор бессмыслен (режется сам IP).
    private fun probeSniScan(out: MutableList<ProbeLine>) {
        val ip = ProbeTargets.SNI_SCAN_IP
        val tcp = Probe.tcpConnect(ip, 443, "SNI-скан: TCP до $ip")
        out += tcp.toLine("sniscan")
        if (tcp.outcome != Probe.Outcome.OK) return   // до IP не достучались
        for (sni in ProbeTargets.SNI_DONORS) {
            out += Probe.tlsWithSni(ip, sni, 443, "SNI $sni").toLine("sniscan")
        }
    }

    private fun probeServers(out: MutableList<ProbeLine>) {
        // Твои узлы берутся из Config (из секретов сборки). Пусто → блок пропускается,
        // поэтому у друзей без твоих секретов этих зондов просто нет.
        for (s in Config.servers) {
            out += Probe.tcpConnect(s.ip, s.port, "${s.name} TCP").toLine("server")
            out += Probe.tlsWithSni(s.ip, s.sni, s.port, "${s.name} TLS[${s.sni}]")
                .toLine("server")
        }
    }

    // ── вспомогательное ────────────────────────────────────────────────────────

    // ВАЖНО: берём именно IPv4 (A-запись). На мобильных сетях эталоны часто
    // отдаются по IPv6, а белые списки и твой каскад работают по IPv4 — тест по
    // IPv6-адресу измерял бы не то. Среди всех адресов выбираем первый IPv4.
    private fun resolve(host: String): String? = try {
        InetAddress.getAllByName(host)
            .firstOrNull { it is java.net.Inet4Address }
            ?.hostAddress
    } catch (_: Exception) { null }

    private fun Probe.ProbeResult.toLine(group: String) = ProbeLine(
        group = group, kind = kind, label = label, target = target,
        sni = sni, outcome = outcome.name, ms = ms, detail = detail
    )

    private fun DnsProbe.DnsResult.toLine(group: String) = ProbeLine(
        group = group, kind = "dns", label = label, target = resolver,
        sni = null, outcome = outcome.name, ms = ms, detail = "$type ${answer}"
    )

    /**
     * Грубая эвристика для быстрого «что это вообще». Не истина в последней инстанции —
     * настоящий вывод делается при анализе полного лога. Намеренно простая и правимая.
     */
    private fun heuristicVerdict(lines: List<ProbeLine>): String {
        val neutral = lines.filter { it.group == "neutral" }
        val whitelistTcp = lines.filter { it.group == "whitelist" && it.kind == "tcp" }

        val neutralDead = neutral.isNotEmpty() && neutral.all {
            it.outcome == "TIMEOUT" || it.outcome == "REFUSED" || it.outcome == "RESET"
        }
        val whitelistAlive = whitelistTcp.any { it.outcome == "OK" }

        // Фильтр по SNI: запрещённый домен в SNI на белом IP.
        // RESET = активный сброс фильтром. TIMEOUT тут НЕ фильтр (сервер молчит).
        val banned = lines.filter {
            it.group == "whitelist" && it.kind == "tls" && it.sni == "www.instagram.com"
        }
        val sniReset = banned.count { it.outcome == "RESET" }
        val sniOk = banned.count { it.outcome == "OK" }

        val base = when {
            neutralDead && whitelistAlive -> "Белые списки: фильтр по IP"
            !neutralDead -> "Фильтра по IP нет"
            else -> "Неоднозначно по IP — смотреть лог"
        }
        val sni = when {
            sniReset > 0 && sniReset >= sniOk -> "DPI по SNI активен (RESET на запрещённый домен)"
            banned.isNotEmpty() && sniOk == banned.size -> "SNI не фильтруется"
            else -> "SNI: неоднозначно"
        }
        // Подмена DNS — отдельный механизм блокировки, выносим в вердикт.
        val poisoned = lines.count { it.group == "dns" && it.outcome == "POISONED" }
        val dns = if (poisoned > 0) " · DNS отравлен (подмена ответов)" else ""

        // Итог перебора SNI-доноров: сколько прошло к зарубежному IP.
        val scan = lines.filter { it.group == "sniscan" && it.kind == "tls" }
        val scanInfo = if (scan.isNotEmpty()) {
            val alive = scan.count { it.outcome == "OK" }
            " · SNI-доноры: прошло $alive/${scan.size}"
        } else ""
        return "$base · $sni$dns$scanInfo"
    }
}
