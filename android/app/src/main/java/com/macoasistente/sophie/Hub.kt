package com.macoasistente.sophie

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** Shared state between the Bluetooth service, the phone screen and the car screen. */
object Hub {
    data class Codes(
        val stored: List<String> = emptyList(),
        val pending: List<String> = emptyList(),
        val permanent: List<String> = emptyList(),
        val milOn: Boolean? = null,
        val milCount: Int = 0,
        val scanned: Boolean = false,
    )

    data class Snap(
        val mode: String = "idle", // idle | connecting | live
        val waiting: Boolean = false, // link dropped, waiting for the adapter to come back
        val vals: Map<String, Double> = emptyMap(),
        val supported: Set<String>? = null,
        val codes: Codes = Codes(),
        val scanning: Boolean = false,
        val protocol: String = "",
        val adapter: String = "",
        val volt: String = "",
        val vin: String = "",
        val distKm: Double = 0.0,
    )

    /** Summary the dashboard page sends down so the car screen can show it. */
    data class CodeInfo(val code: String, val title: String, val drive: String, val sev: String)
    data class Alert(val text: String, val sev: String)
    data class CarInfo(
        val name: String = "My car",
        val units: String = "us",
        val odoMi: Double? = null,
        val alerts: List<Alert> = emptyList(),
        val codes: List<CodeInfo> = emptyList(),
    )

    val state = MutableStateFlow(Snap())
    /** Photo of the active car, sent by the dashboard page. */
    val photo = MutableStateFlow<android.graphics.Bitmap?>(null)
    /** Last auto-saved parking spot as JSON {t, lat, lng, acc}. */
    val park = MutableStateFlow<String?>(null)
    val info = MutableStateFlow(CarInfo())
    val logs = MutableSharedFlow<String>(replay = 40, extraBufferCapacity = 64)
    val cluster = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun update(f: (Snap) -> Snap) = state.update(f)

    fun log(s: String) {
        Log.i("Sophie", s)
        logs.tryEmit(s)
    }

    fun snapJson(s: Snap): String {
        val o = JSONObject()
        o.put("mode", s.mode)
        o.put("waiting", s.waiting)
        val v = JSONObject()
        for ((k, x) in s.vals) if (!x.isNaN() && !x.isInfinite()) v.put(k, x)
        o.put("vals", v)
        o.put("supported", s.supported?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)
        val c = JSONObject()
        c.put("stored", JSONArray(s.codes.stored))
        c.put("pending", JSONArray(s.codes.pending))
        c.put("permanent", JSONArray(s.codes.permanent))
        c.put("mil", if (s.codes.milOn == null) JSONObject.NULL else JSONObject().put("on", s.codes.milOn).put("count", s.codes.milCount))
        c.put("scanned", s.codes.scanned)
        o.put("codes", c)
        o.put("scanning", s.scanning)
        o.put("protocol", s.protocol)
        o.put("adapter", s.adapter)
        o.put("volt", s.volt)
        o.put("vin", s.vin)
        o.put("distKm", s.distKm)
        return o.toString()
    }

    fun parseInfo(json: String): CarInfo? = try {
        val o = JSONObject(json)
        val al = o.optJSONArray("alerts") ?: JSONArray()
        val cs = o.optJSONArray("codes") ?: JSONArray()
        CarInfo(
            name = o.optString("name", "My car"),
            units = o.optString("units", "us"),
            odoMi = if (o.isNull("odoMi") || !o.has("odoMi")) null else o.getDouble("odoMi"),
            alerts = (0 until al.length()).map { al.getJSONObject(it).let { a -> Alert(a.optString("t"), a.optString("cls")) } },
            codes = (0 until cs.length()).map {
                cs.getJSONObject(it).let { a -> CodeInfo(a.optString("c"), a.optString("title"), a.optString("drive"), a.optString("sev")) }
            },
        )
    } catch (e: Exception) {
        null
    }
}
