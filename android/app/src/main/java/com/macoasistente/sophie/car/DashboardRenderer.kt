package com.macoasistente.sophie.car

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import com.macoasistente.sophie.Hub
import com.macoasistente.sophie.Obd
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

/** Draws Sophie's dashboard on the car screen in the same dark glass style as the phone. */
class DashboardRenderer(ctx: CarContext) : SurfaceCallback {
    private var surface: Surface? = null
    private var width = 0
    private var height = 0
    private var visible: Rect? = null
    private var snap = Hub.Snap()
    private var info = Hub.CarInfo()
    private val photo: Bitmap? get() = Hub.photo.value

    private val OK = Color.rgb(48, 209, 88)
    private val WARN = Color.rgb(255, 214, 10)
    private val CRIT = Color.rgb(255, 69, 58)
    private val ACCENT = Color.rgb(10, 132, 255)
    private val MUTED = Color.argb(150, 235, 235, 245)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { color = Color.WHITE }
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val nf = NumberFormat.getIntegerInstance()

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        surface = c.surface; width = c.width; height = c.height
        draw()
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) { surface = null }
    override fun onVisibleAreaChanged(r: Rect) { visible = r; draw() }
    override fun onStableAreaChanged(r: Rect) { if (visible == null) visible = r; draw() }

    fun update(s: Hub.Snap, i: Hub.CarInfo) { snap = s; info = i; draw() }
    fun release() { surface = null }

    // ------------------------------------------------------------------ units

    private val us get() = info.units != "metric"
    private fun temp(c: Double) = if (us) c * 9 / 5 + 32 to "°F" else c to "°C"
    private fun speed(k: Double) = if (us) k * 0.621371 to "mph" else k to "km/h"
    private fun n0(v: Double?) = if (v == null || v.isNaN()) "–" else nf.format(Math.round(v))
    private fun n1(v: Double?) = if (v == null || v.isNaN()) "–" else String.format("%.1f", v)

    // ------------------------------------------------------------------ drawing

    private fun draw() {
        val s = surface ?: return
        if (!s.isValid || width == 0) return
        val canvas = try { s.lockCanvas(null) } catch (_: Exception) { return } ?: return
        try { paint(canvas) } finally { try { s.unlockCanvasAndPost(canvas) } catch (_: Exception) {} }
    }

    private fun glass(c: Canvas, r: RectF, k: Float, tint: Int? = null) {
        fill.shader = null
        fill.color = Color.argb(20, 255, 255, 255)
        c.drawRoundRect(r, 22 * k, 22 * k, fill)
        if (tint != null) {
            fill.color = Color.argb(28, Color.red(tint), Color.green(tint), Color.blue(tint))
            c.drawRoundRect(r, 22 * k, 22 * k, fill)
        }
        stroke.color = Color.argb(34, 255, 255, 255)
        stroke.strokeWidth = 1.2f * k
        c.drawRoundRect(r, 22 * k, 22 * k, stroke)
    }

    private fun label(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int = MUTED, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) {
        text.textSize = size
        text.color = color
        text.typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        text.textAlign = align
        c.drawText(s, x, y, text)
    }

    private fun dot(c: Canvas, x: Float, y: Float, r: Float, color: Int) {
        fill.shader = null
        fill.color = Color.argb(70, Color.red(color), Color.green(color), Color.blue(color))
        c.drawCircle(x, y, r * 2.2f, fill)
        fill.color = color
        c.drawCircle(x, y, r, fill)
    }

    private var liveSince = 0L

    private fun statusColor(id: String, x: Double): Int = when (id) {
        "coolant" -> if (x > 112) CRIT else if (x > 105) WARN else OK
        "volt" -> if (x < 11.8 || x > 15.2) CRIT else if (x < 12.4 || x > 14.9) WARN else OK
        "fuel" -> if (x < 7) CRIT else if (x < 15) WARN else OK
        "ltft" -> if (abs(x) > 20) CRIT else if (abs(x) > 10) WARN else OK
        else -> OK
    }

    private fun paint(c: Canvas) {
        val area = visible?.takeIf { it.width() > 100 && it.height() > 100 } ?: Rect(0, 0, width, height)
        val W = width.toFloat()
        val H = height.toFloat()
        fill.shader = LinearGradient(0f, 0f, 0f, H, Color.rgb(14, 17, 24), Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, W, H, fill)
        fill.shader = null

        val aw = area.width().toFloat()
        val ah = area.height().toFloat()
        val k = min(aw / 1000f, ah / 520f)
        val pad = 18 * k
        val L = area.left + pad
        val T = area.top + pad
        val R = area.right - pad
        val B = area.bottom - pad
        val v = snap.vals
        val live = snap.mode == "live"
        if (live && liveSince == 0L) liveSince = System.currentTimeMillis()
        if (!live) liveSince = 0L

        // ---- header: name + status, clock
        val name = info.name.uppercase()
        label(c, name, L, T + 26 * k, 26 * k, Color.WHITE, bold = true)
        val nw = text.measureText(name)
        val (stColor, stText) = when {
            live -> OK to "Live"
            snap.mode == "connecting" -> WARN to "Connecting…"
            snap.waiting -> MUTED to "Waiting for ${info.name} to start"
            else -> MUTED to "Not connected"
        }
        dot(c, L + nw + 18 * k, T + 17 * k, 5 * k, stColor)
        label(c, stText, L + nw + 30 * k, T + 24 * k, 17 * k)
        val now = Date()
        label(c, SimpleDateFormat("h:mm", Locale.getDefault()).format(now), R, T + 30 * k, 34 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        label(c, SimpleDateFormat("EEEE, MMM d", Locale.getDefault()).format(now), R, T + 52 * k, 14 * k, align = Paint.Align.RIGHT)

        // ---- Sophie in the middle, with a soft glow and a floor line
        val cx = (L + R) / 2
        var carW = 0f
        var carH = ah * 0.56f
        var carX = cx
        val carY = area.top + ah * 0.19f
        val aspect = photo?.let { it.width.toFloat() / it.height } ?: (880f / 616f)
        carH = min(ah * 0.56f, (R - L) * 0.40f / aspect)
        carW = carH * aspect
        carX = cx - carW / 2
        photo?.let { ph ->
            fill.shader = RadialGradient(cx, carY + carH * 0.55f, carW * 0.75f, Color.argb(56, 10, 132, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, W, H, fill)
            fill.shader = LinearGradient(L, 0f, R, 0f, intArrayOf(Color.TRANSPARENT, Color.argb(46, 255, 255, 255), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawRect(L, carY + carH * 0.90f, R, carY + carH * 0.90f + 1.2f * k, fill)
            fill.shader = null
            c.drawBitmap(ph, null, RectF(carX, carY, carX + carW, carY + carH), bmp)
        }

        // ---- callouts pointing at the car
        fun callout(fx: Float, fy: Float, lx: Float, ly: Float, leftAligned: Boolean, lab: String, value: String, unit: String, color: Int?) {
            if (photo != null) {
                val px = carX + fx * carW
                val py = carY + fy * carH
                stroke.color = Color.argb(90, 255, 255, 255)
                stroke.strokeWidth = 1 * k
                c.drawLine(px, py, if (leftAligned) lx - 8 * k else lx + 8 * k, ly - 8 * k, stroke)
                dot(c, px, py, 3.5f * k, color ?: Color.WHITE)
            }
            if (leftAligned) {
                label(c, lab, lx, ly - 14 * k, 13 * k)
                label(c, value, lx, ly + 20 * k, 30 * k, Color.WHITE, bold = true)
                val w = text.measureText(value)
                label(c, unit, lx + w + 5 * k, ly + 20 * k, 15 * k)
            } else {
                label(c, lab, lx, ly - 14 * k, 13 * k, align = Paint.Align.RIGHT)
                label(c, unit, lx, ly + 20 * k, 15 * k, align = Paint.Align.RIGHT)
                val w = text.measureText(unit)
                label(c, value, lx - w - 5 * k, ly + 20 * k, 30 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
            }
        }
        fun has(id: String) = live && v[id] != null
        val lx0 = carX - 28 * k
        val lx1 = carX + carW + 28 * k
        val (tc, tu) = temp(v["coolant"] ?: 0.0)
        callout(0.40f, 0.30f, lx0, carY + carH * 0.12f, false, "ENGINE TEMP", if (has("coolant")) n0(tc) else "–", tu, v["coolant"]?.takeIf { live }?.let { statusColor("coolant", it) })
        val trim = v["ltft"]
        callout(0.56f, 0.36f, lx1, carY + carH * 0.12f, true, "FUEL TRIM", if (has("ltft") && trim != null) (if (trim > 0) "+" else "") + n1(trim) else "–", "%", trim?.takeIf { live }?.let { statusColor("ltft", it) })
        callout(0.08f, 0.40f, lx0, carY + carH * 0.62f, false, "BATTERY", if (has("volt")) n1(v["volt"]) else "–", "V", v["volt"]?.takeIf { live }?.let { statusColor("volt", it) })
        callout(0.90f, 0.42f, lx1, carY + carH * 0.62f, true, "FUEL", if (has("fuel")) n0(v["fuel"]) else "–", "%", v["fuel"]?.takeIf { live }?.let { statusColor("fuel", it) })

        // ---- left: speed and rpm
        val colW = (R - L) * 0.15f
        val sy = carY + carH * 0.24f
        val (spd, spdU) = speed(v["speed"] ?: 0.0)
        label(c, "SPEED", L, sy, 14 * k)
        label(c, if (live) n0(spd) else "–", L - 4 * k, sy + 84 * k, 92 * k, Color.WHITE, bold = true)
        label(c, spdU, L, sy + 110 * k, 17 * k)
        val by = sy + 140 * k
        val rpm = v["rpm"]
        label(c, "RPM", L, by - 10 * k, 13 * k)
        label(c, if (live) n0(rpm) else "–", L + colW, by - 10 * k, 16 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        fill.color = Color.argb(30, 255, 255, 255)
        c.drawRoundRect(RectF(L, by, L + colW, by + 7 * k), 3.5f * k, 3.5f * k, fill)
        if (live && rpm != null) {
            val f = (rpm / 6500.0).coerceIn(0.0, 1.0).toFloat()
            fill.shader = LinearGradient(L, 0f, L + colW, 0f, intArrayOf(ACCENT, ACCENT, WARN, CRIT), floatArrayOf(0f, 0.6f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(L, by, L + colW * f, by + 7 * k), 3.5f * k, 3.5f * k, fill)
            fill.shader = null
        }

        // ---- right: this drive
        val mins = if (liveSince > 0) (System.currentTimeMillis() - liveSince) / 60000 else -1
        val trip = if (mins >= 0) "%d:%02d".format(mins / 60, mins % 60) else "–"
        val (dist, du) = if (us) snap.distKm * 0.621371 to "mi" else snap.distKm to "km"
        label(c, "THIS DRIVE", R, sy, 14 * k, align = Paint.Align.RIGHT)
        label(c, trip, R, sy + 48 * k, 44 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        label(c, if (live) "${n1(dist)} $du" else "", R, sy + 72 * k, 17 * k, align = Paint.Align.RIGHT)
        val mpg = if (live) Obd.instantMpg(v) else null
        val mpgTxt = when {
            mpg == null -> "–"
            us -> n1(mpg) + " mpg"
            else -> n1(235.215 / mpg) + " L/100km"
        }
        label(c, "RIGHT NOW", R, sy + 112 * k, 13 * k, align = Paint.Align.RIGHT)
        label(c, mpgTxt, R, sy + 140 * k, 24 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)

        // ---- secondary line and alert pill at the bottom
        val extras = buildList {
            v["iat"]?.takeIf { live }?.let { val (t, u) = temp(it); add("Intake ${n0(t)}$u") }
            v["load"]?.takeIf { live }?.let { add("Load ${n0(it)}%") }
            info.odoMi?.let { add(if (us) "${n0(it)} mi" else "${n0(it / 0.621371)} km") }
        }.joinToString("   ·   ")
        val alert = topAlert()
        val pillTop = B - 44 * k
        if (extras.isNotEmpty()) label(c, extras, cx, if (alert != null) pillTop - 16 * k else B - 8 * k, 15 * k, align = Paint.Align.CENTER)
        if (alert != null) {
            val msg = ellipsize(alert.first, (R - L) * 0.8f, 18 * k)
            text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val w = text.measureText(msg) + 64 * k
            val r = RectF(cx - w / 2, pillTop, cx + w / 2, B)
            glass(c, r, k, alert.second)
            dot(c, r.left + 24 * k, r.centerY(), 5 * k, alert.second)
            label(c, msg, r.left + 42 * k, r.centerY() + 6 * k, 18 * k, Color.WHITE, bold = true)
        }
    }

    private fun topAlert(): Pair<String, Int>? {
        val c = snap.codes
        if (c.milOn == true) {
            val first = c.stored.firstOrNull()
            val title = first?.let { code -> info.codes.firstOrNull { it.code == code }?.title ?: Obd.family(code) }
            return (if (first != null) "Check-engine light: $first · $title" else "Check-engine light is on") to CRIT
        }
        if (c.pending.isNotEmpty()) return "Pending code ${c.pending.first()}: a problem may be developing" to WARN
        val a = info.alerts.firstOrNull() ?: return null
        return a.text to if (a.sev == "crit") CRIT else WARN
    }

    private fun ellipsize(s: String, maxW: Float, size: Float): String {
        text.textSize = size
        if (text.measureText(s) <= maxW) return s
        var t = s
        while (t.length > 1 && text.measureText("$t…") > maxW) t = t.dropLast(1)
        return "$t…"
    }
}
