package com.macoasistente.sophie

/** Standard OBD2 mode 01 values. Same ids and formulas as the dashboard page. */
class Pid(val pid: String, val id: String, val fast: Boolean = false, val f: (Int, Int, Int, Int) -> Double)

object Obd {
    val PIDS = listOf(
        Pid("0C", "rpm", true) { a, b, _, _ -> (a * 256 + b) / 4.0 },
        Pid("04", "load", true) { a, _, _, _ -> a * 100 / 255.0 },
        Pid("05", "coolant") { a, _, _, _ -> a - 40.0 },
        Pid("0E", "timing") { a, _, _, _ -> a / 2.0 - 64 },
        Pid("43", "absload") { a, b, _, _ -> (a * 256 + b) * 100 / 255.0 },
        Pid("1F", "runtime") { a, b, _, _ -> a * 256.0 + b },
        Pid("5C", "oil") { a, _, _, _ -> a - 40.0 },
        Pid("2F", "fuel") { a, _, _, _ -> a * 100 / 255.0 },
        Pid("03", "fuelsys") { a, _, _, _ -> a.toDouble() },
        Pid("06", "stft", true) { a, _, _, _ -> (a - 128) * 100 / 128.0 },
        Pid("07", "ltft") { a, _, _, _ -> (a - 128) * 100 / 128.0 },
        Pid("44", "lambda") { a, b, _, _ -> (a * 256 + b) * 2 / 65536.0 },
        Pid("5E", "rate") { a, b, _, _ -> (a * 256 + b) / 20.0 },
        Pid("10", "maf", true) { a, b, _, _ -> (a * 256 + b) / 100.0 },
        Pid("0F", "iat") { a, _, _, _ -> a - 40.0 },
        Pid("46", "ambient") { a, _, _, _ -> a - 40.0 },
        Pid("0B", "map", true) { a, _, _, _ -> a.toDouble() },
        Pid("33", "baro") { a, _, _, _ -> a.toDouble() },
        Pid("3C", "cat") { a, b, _, _ -> (a * 256 + b) / 10.0 - 40 },
        Pid("42", "volt") { a, b, _, _ -> (a * 256 + b) / 1000.0 },
        Pid("14", "o2a") { a, _, _, _ -> a / 200.0 },
        Pid("15", "o2b") { a, _, _, _ -> a / 200.0 },
        Pid("2E", "purge") { a, _, _, _ -> a * 100 / 255.0 },
        Pid("31", "sinceDist") { a, b, _, _ -> a * 256.0 + b },
        Pid("30", "warmups") { a, _, _, _ -> a.toDouble() },
        Pid("21", "milDist") { a, b, _, _ -> a * 256.0 + b },
        Pid("4E", "sinceMin") { a, b, _, _ -> a * 256.0 + b },
        Pid("0D", "speed", true) { a, _, _, _ -> a.toDouble() },
        Pid("11", "throttle", true) { a, _, _, _ -> a * 100 / 255.0 },
        Pid("49", "pedal") { a, _, _, _ -> a * 100 / 255.0 },
        Pid("45", "relthr") { a, _, _, _ -> a * 100 / 255.0 },
        Pid("A6", "odometer") { a, b, c, d -> (a * 16777216.0 + b * 65536.0 + c * 256.0 + d) / 10 },
        Pid("4C", "cmdthr") { a, _, _, _ -> a * 100 / 255.0 },
    )

    private val hexLine = Regex("^[0-9A-F:]+$")
    private val lenLine = Regex("^[0-9A-F]{3}$")
    private val frameLine = Regex("^([0-9A-F]):(.*)$")

    /** Data bytes after the mode+pid echo for a single-frame response. */
    fun hexBytes(resp: String, mode: String, pid: String): IntArray? {
        val want = (mode + pid).uppercase()
        for (line in resp.split('\n')) {
            val h = line.replace(Regex("\\s"), "").uppercase()
            val i = h.indexOf(want)
            if (i >= 0) {
                val rest = h.substring(i + want.length)
                return IntArray(rest.length / 2) { j -> rest.substring(j * 2, j * 2 + 2).toIntOrNull(16) ?: 0 }
            }
        }
        return null
    }

    /** Flattens multi-frame CAN responses ("0: 43 02 ...", "1: ...") into one hex string. */
    fun joinFrames(resp: String): String = resp.split('\n')
        .map { it.replace(Regex("\\s"), "").uppercase() }
        .filter { hexLine.matches(it) && !lenLine.matches(it) }
        .joinToString("") { it.replace(Regex("^[0-9A-F]:"), "") }

    fun parseDTCs(resp: String, modeEcho: String, protocol: String): List<String> {
        if (Regex("NO ?DATA", RegexOption.IGNORE_CASE).containsMatchIn(resp)) return emptyList()
        val isCan = Regex("^[6-9AB]").containsMatchIn(protocol)
        class Msg(val len: Int?, var hex: String)
        val msgs = mutableListOf<Msg>()
        var cur: Msg? = null
        for (raw in resp.split('\n')) {
            val l = raw.replace(Regex("\\s"), "").uppercase()
            if (!hexLine.matches(l)) continue
            if (lenLine.matches(l)) { cur = Msg(l.toInt(16), ""); msgs.add(cur); continue }
            val m = frameLine.matchEntire(l)
            if (m != null && cur != null) { cur.hex += m.groupValues[2]; continue }
            cur = null
            msgs.add(Msg(null, l))
        }
        val codes = mutableListOf<String>()
        for (msg in msgs) {
            val h = if (msg.len != null) msg.hex.take(msg.len * 2) else msg.hex
            if (!h.startsWith(modeEcho)) continue
            val words = if (isCan) {
                val n = h.substring(2, minOf(4, h.length)).toIntOrNull(16) ?: 0
                h.substring(minOf(4, h.length), minOf(4 + n * 4, h.length))
            } else h.substring(2)
            var j = 0
            while (j + 3 < words.length) {
                val w = words.substring(j, j + 4)
                j += 4
                if (w == "0000") continue
                val n = w.toInt(16)
                val c = "PCBU"[n shr 14] + ((n shr 12) and 3).toString() + w.substring(1)
                if (c !in codes) codes.add(c)
            }
        }
        return codes
    }

    /** Short fallback description when the page hasn't sent one. */
    fun family(c: String): String {
        val n = c.drop(1).take(2)
        return when (c.firstOrNull()) {
            'P' -> when (n) {
                "01", "02" -> "Fuel and air mix problem"
                "03" -> "Ignition or misfire problem"
                "04" -> "Emissions system problem"
                "05" -> "Speed or idle control problem"
                "06" -> "Engine computer problem"
                "07", "08", "09" -> "Transmission problem"
                else -> "Engine or transmission problem"
            }
            'C' -> "Brakes, steering or suspension problem"
            'B' -> "Body system problem"
            else -> "Communication problem between modules"
        }
    }

    /** Air flow in g/s: the MAF sensor if present, else a speed-density estimate (Sophie has no MAF). */
    fun airFlow(v: Map<String, Double>): Double? {
        v["maf"]?.let { return it }
        val rpm = v["rpm"] ?: return null
        val map = v["map"] ?: return null
        val iat = v["iat"] ?: return null
        return 0.002 * (rpm / 120) * 0.85 * (map * 1000) / (287.05 * (iat + 273.15)) * 1000
    }

    fun fuelLph(v: Map<String, Double>): Double? = v["rate"] ?: airFlow(v)?.let { it * 3600 / (14.7 * 740) }

    fun instantMpg(v: Map<String, Double>): Double? {
        val lph = fuelLph(v) ?: return null
        val spd = v["speed"] ?: return null
        if (spd <= 0) return null
        return minOf((spd * 0.621371) / (lph / 3.785), 99.0)
    }
}
