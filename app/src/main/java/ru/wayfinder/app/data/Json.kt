package ru.wayfinder.app.data

/**
 * Ручная сериализация в JSON — намеренно без внешних библиотек.
 * Меньше зависимостей = меньше конфликтов версий при сборке и проще править.
 * Формат: массив снимков. Экспортируется одним файлом и отдаётся тебе.
 */
object Json {

    fun snapshotsToJson(list: List<Snapshot>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        list.forEachIndexed { i, s ->
            sb.append(snapshot(s))
            if (i < list.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]\n")
        return sb.toString()
    }

    private fun snapshot(s: Snapshot): String {
        val b = StringBuilder()
        b.append("  {\n")
        b.append("    \"schemaVersion\": ${s.schemaVersion},\n")
        b.append("    \"timestamp\": ${s.timestamp},\n")
        b.append("    \"isoTime\": ${str(s.isoTime)},\n")
        b.append("    \"verdict\": ${str(s.verdict)},\n")
        b.append("    \"source\": ${str(s.source)},\n")
        b.append("    \"context\": ${ctx(s.context)},\n")
        b.append("    \"probes\": [\n")
        s.probes.forEachIndexed { i, p ->
            b.append(probe(p))
            if (i < s.probes.size - 1) b.append(",")
            b.append("\n")
        }
        b.append("    ]\n")
        b.append("  }")
        return b.toString()
    }

    private fun ctx(c: NetCtx): String {
        val f = mutableListOf<String>()
        f.add("\"networkType\": ${str(c.networkType)}")
        f.add("\"operator\": ${str(c.operator)}")
        f.add("\"mccMnc\": ${str(c.mccMnc)}")
        f.add("\"signalDbm\": ${num(c.signalDbm)}")
        f.add("\"cellId\": ${str(c.cellId)}")
        f.add("\"lac\": ${str(c.lac)}")
        f.add("\"lat\": ${num(c.lat)}")
        f.add("\"lon\": ${num(c.lon)}")
        f.add("\"resolver\": ${str(c.resolver)}")
        f.add("\"localIp\": ${str(c.localIp)}")
        f.add("\"vpnActive\": ${c.vpnActive}")
        return "{ ${f.joinToString(", ")} }"
    }

    private fun probe(p: ProbeLine): String {
        val f = mutableListOf<String>()
        f.add("\"group\": ${str(p.group)}")
        f.add("\"kind\": ${str(p.kind)}")
        f.add("\"label\": ${str(p.label)}")
        f.add("\"target\": ${str(p.target)}")
        f.add("\"sni\": ${str(p.sni)}")
        f.add("\"outcome\": ${str(p.outcome)}")
        f.add("\"ms\": ${p.ms}")
        f.add("\"detail\": ${str(p.detail)}")
        return "      { ${f.joinToString(", ")} }"
    }

    private fun str(v: String?): String {
        if (v == null) return "null"
        val e = StringBuilder("\"")
        for (ch in v) when (ch) {
            '\\' -> e.append("\\\\")
            '"' -> e.append("\\\"")
            '\n' -> e.append("\\n")
            '\r' -> e.append("\\r")
            '\t' -> e.append("\\t")
            else -> if (ch < ' ') e.append("\\u%04x".format(ch.code)) else e.append(ch)
        }
        e.append("\"")
        return e.toString()
    }

    private fun num(v: Number?): String = v?.toString() ?: "null"
}
