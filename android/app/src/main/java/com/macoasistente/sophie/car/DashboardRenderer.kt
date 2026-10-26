package com.macoasistente.sophie.car

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import com.macoasistente.sophie.R
import java.text.NumberFormat
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
    private val photo: Bitmap? = try { BitmapFactory.decodeResource(ctx.resources, R.drawable.sophie) } catch (_: Exception) { null }

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

    private fun paint(c: Canvas) {
        val area = visible?.takeIf { it.width() > 100 && it.height() > 100 } ?: Rect(0, 0, width, height)
        // background
        fill.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), Color.rgb(14, 17, 24), Color.BLACK, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = RadialGradient(width * 0.15f, height * 0.05f, width * 0.6f, Color.argb(60, 10, 132, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
        fill.shader = null

        val k = min(area.width() / 900f, area.height() / 500f)
        val pad = 14 * k
        val left = area.left + pad
        val top = area.top + pad
        val right = area.right - pad
        val bottom = area.bottom - pad
        val v = snap.vals
        val live = snap.mode == "live"

        // ---- header: photo, name, status, odometer
        val hh = 64 * k
        photo?.let {
            val ph = hh
            val pw = ph * it.width / it.height
            c.drawBitmap(it, null, RectF(left, top, left + pw, top + ph), bmp)
        }
        val nameX = left + (photo?.let { hh * it.width / it.height } ?: 0f) + 14 * k
        label(c, info.name, nameX, top + 30 * k, 30 * k, Color.WHITE, bold = true)
        val (stColor, stText) = when {
            live -> OK to "Live"
            snap.mode == "connecting" -> WARN to "Connecting…"
            snap.waiting -> MUTED to "Waiting for ${info.name} to start"
            else -> MUTED to "Not connected"
        }
        dot(c, nameX + 6 * k, top + 52 * k, 5 * k, stColor)
        label(c, stText, nameX + 20 * k, top + 58 * k, 18 * k)
        info.odoMi?.let {
            val (o, u) = if (us) it to "mi" else it / 0.621371 to "km"
            label(c, "${n0(o)} $u", right, top + 30 * k, 24 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
            label(c, "ODOMETER", right, top + 54 * k, 14 * k, align = Paint.Align.RIGHT)
        }

        // ---- alert bar at the bottom
        val alert = topAlert()
        var contentBottom = bottom
        if (alert != null) {
            val ah = 52 * k
            val r = RectF(left, bottom - ah, right, bottom)
            glass(c, r, k, alert.second)
            dot(c, r.left + 24 * k, r.centerY(), 6 * k, alert.second)
            label(c, ellipsize(alert.first, r.width() - 60 * k, 20 * k), r.left + 44 * k, r.centerY() + 7 * k, 20 * k, Color.WHITE, bold = true)
            contentBottom = r.top - pad
        }

        // ---- left: speed + rpm + mpg
        val bodyTop = top + hh + pad
        val split = left + (right - left) * 0.40f
        val big = RectF(left, bodyTop, split - pad / 2, contentBottom)
        glass(c, big, k)
        val (spd, spdU) = speed(v["speed"] ?: 0.0)
        label(c, "SPEED", big.left + 22 * k, big.top + 34 * k, 15 * k)
        label(c, if (live) n0(spd) else "–", big.left + 18 * k, big.top + 34 * k + 92 * k, 104 * k, Color.WHITE, bold = true)
        label(c, spdU, big.left + 22 * k, big.top + 34 * k + 122 * k, 20 * k)

        // rpm bar
        val rpm = v["rpm"]
        val barY = big.bottom - 92 * k
        val barL = big.left + 22 * k
        val barR = big.right - 22 * k
        label(c, "RPM", barL, barY - 14 * k, 15 * k)
        label(c, if (live) n0(rpm) else "–", barR, barY - 14 * k, 20 * k, Color.WHITE, bold = true, align = Paint.Align.RIGHT)
        fill.color = Color.argb(30, 255, 255, 255)
        c.drawRoundRect(RectF(barL, barY, barR, barY + 10 * k), 5 * k, 5 * k, fill)
        if (live && rpm != null) {
            val f = (rpm / 6500.0).coerceIn(0.0, 1.0).toFloat()
            fill.shader = LinearGradient(barL, 0f, barR, 0f, intArrayOf(ACCENT, ACCENT, WARN, CRIT), floatArrayOf(0f, 0.6f, 0.8f, 1f), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(barL, barY, barL + (barR - barL) * f, barY + 10 * k), 5 * k, 5 * k, fill)
            fill.shader = null
        }
        val mpg = if (live) Obd.instantMpg(v) else null
        val mpgTxt = when {
            mpg == null -> "–"
            us -> n1(mpg) + " mpg"
            else -> n1(235.215 / mpg) + " L/100km"
        }
        label(c, "RIGHT NOW", barL, big.bottom - 44 * k, 15 * k)
        label(c, mpgTxt, barL, big.bottom - 18 * k, 24 * k, Color.WHITE, bold = true)

        // ---- right: 2 x 3 tiles
        val gx = split + pad / 2
        val cols = 2
        val rows = 3
        val tw = (right - gx - pad * (cols - 1)) / cols
        val th = (contentBottom - bodyTop - pad * (rows - 1)) / rows
        val tiles = tiles()
        for ((i, t) in tiles.withIndex()) {
            val col = i % cols
            val row = i / cols
            val r = RectF(gx + col * (tw + pad), bodyTop + row * (th + pad), gx + col * (tw + pad) + tw, bodyTop + row * (th + pad) + th)
            glass(c, r, k)
            val ts = min(1f, th / (100 * k))
            label(c, t.label, r.left + 18 * k, r.top + 28 * k * ts, 15 * k)
            if (t.color != null) dot(c, r.right - 22 * k, r.top + 23 * k * ts, 5 * k, t.color)
            label(c, t.value, r.left + 18 * k, r.top + 28 * k * ts + 40 * k * ts, 36 * k * ts, Color.WHITE, bold = true)
            text.textSize = 36 * k * ts
            text.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            val w = text.measureText(t.value)
            label(c, t.unit, r.left + 18 * k + w + 6 * k, r.top + 28 * k * ts + 40 * k * ts, 18 * k * ts)
            t.note?.let { label(c, ellipsize(it, r.width() - 36 * k, 14 * k * ts), r.left + 18 * k, r.bottom - 12 * k * ts, 14 * k * ts) }
        }
    }

    private class Tile(val label: String, val value: String, val unit: String, val color: Int?, val note: String? = null)

    private fun tiles(): List<Tile> {
        val v = snap.vals
        val live = snap.mode == "live"
        fun has(id: String) = live && v[id] != null
        val list = mutableListOf<Tile>()
        v["coolant"].let { x ->
            val (t, u) = temp(x ?: 0.0)
            list += Tile("ENGINE TEMP", if (has("coolant")) n0(t) else "–", u,
                x?.takeIf { live }?.let { if (it > 112) CRIT else if (it > 105) WARN else OK },
                x?.takeIf { live }?.let { if (it < 70) "Warming up" else if (it > 105) "Running hot" else "Normal" })
        }
        v["volt"].let { x ->
            list += Tile("BATTERY", if (has("volt")) n1(x) else "–", "V",
                x?.takeIf { live }?.let { if (it < 11.8 || it > 15.2) CRIT else if (it < 12.4 || it > 14.9) WARN else OK },
                x?.takeIf { live }?.let { if (it >= 13.4) "Charging" else if (it >= 12.4) "OK, engine off" else "Low" })
        }
        v["fuel"].let { x ->
            list += Tile("FUEL", if (has("fuel")) n0(x) else "–", "%",
                x?.takeIf { live }?.let { if (it < 7) CRIT else if (it < 15) WARN else OK },
                x?.takeIf { live }?.let { if (it < 15) "Fill up soon" else if (us) "About ${n1(it * 14.2 / 100)} gal left" else "About ${n0(it * 54 / 100)} L left" })
        }
        v["ltft"].let { x ->
            list += Tile("FUEL TRIM", if (has("ltft")) (if (x!! > 0) "+" else "") + n1(x) else "–", "%",
                x?.takeIf { live }?.let { if (abs(it) > 20) CRIT else if (abs(it) > 10) WARN else OK },
                x?.takeIf { live }?.let { if (it > 10) "Adding fuel (air leak?)" else if (it < -10) "Running rich" else "Normal" })
        }
        v["iat"].let { x ->
            val (t, u) = temp(x ?: 0.0)
            list += Tile("INTAKE AIR", if (has("iat")) n0(t) else "–", u, null)
        }
        v["load"].let { x -> list += Tile("ENGINE LOAD", if (has("load")) n0(x) else "–", "%", null) }
        return list
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
