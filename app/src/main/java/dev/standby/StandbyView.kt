package dev.standby

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.text.TextUtils
import android.text.TextPaint
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.text.DateFormatSymbols
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Analog clock (left) + swipeable widget: calendar / weather (right). Battery ring + notification banner overlay. */
class StandbyView(ctx: Context, private val onLongPress: (() -> Unit)? = null) : View(ctx) {
    private val red = Color.rgb(255, 69, 58)
    private val orange = Color.rgb(255, 159, 10)
    private val green = Color.rgb(48, 209, 88)
    private val dim = Color.rgb(142, 142, 147)
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val tick = Runnable { invalidate() }
    private var page = 0 // 0 = calendar, 1 = weather
    private val bannerMs = 8_000L

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean { page = 1 - page; invalidate(); return true }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (abs(vy) > abs(vx)) return false
            page = 1 - page; invalidate(); return true
        }
        override fun onLongPress(e: MotionEvent) { onLongPress?.invoke() }
    })

    override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        Weather.refresh(context)
        val now = Calendar.getInstance()
        val w = width.toFloat(); val h = height.toFloat()
        if (w >= h) {
            drawClock(c, now, w / 4, h / 2, min(w / 2, h) * 0.44f)
            drawPage(c, now, w / 2, 0f, w / 2, h)
        } else {
            drawClock(c, now, w / 2, h / 4, min(w, h / 2) * 0.44f)
            drawPage(c, now, 0f, h / 2, w, h / 2)
        }
        drawBattery(c, w - min(w, h) * 0.09f, min(w, h) * 0.09f, min(w, h) * 0.06f)
        drawBanner(c, w, h)
        removeCallbacks(tick)
        postDelayed(tick, 1000L - now.get(Calendar.MILLISECOND))
    }

    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    private fun drawPage(c: Canvas, now: Calendar, x: Float, y: Float, w: Float, h: Float) =
        if (page == 0) drawCalendar(c, now, x, y, w, h) else drawWeather(c, x, y, w, h)

    private fun drawClock(c: Canvas, now: Calendar, cx: Float, cy: Float, r: Float) {
        p.strokeCap = Paint.Cap.ROUND; p.style = Paint.Style.STROKE
        for (i in 0 until 60) {
            val five = i % 5 == 0
            val a = Math.toRadians(i * 6.0)
            val outer = r; val inner = r * if (five) 0.9f else 0.93f
            p.color = if (five) Color.WHITE else Color.rgb(90, 90, 94)
            p.strokeWidth = r * if (five) 0.018f else 0.014f
            c.drawLine(cx + outer * sin(a).toFloat(), cy - outer * cos(a).toFloat(),
                cx + inner * sin(a).toFloat(), cy - inner * cos(a).toFloat(), p)
        }

        p.style = Paint.Style.FILL; p.color = Color.WHITE
        p.textSize = r * 0.2f; p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        for (n in 1..12) {
            val a = Math.toRadians(n * 30.0)
            c.drawText("$n", cx + r * 0.76f * sin(a).toFloat(),
                cy - r * 0.76f * cos(a).toFloat() + p.textSize * 0.36f, p)
        }

        // City tag from time zone, e.g. Asia/Kolkata -> KOL
        p.color = dim; p.textSize = r * 0.14f
        c.drawText(TimeZone.getDefault().id.substringAfterLast('/').take(3).uppercase(), cx, cy - r * 0.38f, p)

        val s = now.get(Calendar.SECOND); val m = now.get(Calendar.MINUTE) + s / 60f
        val hr = now.get(Calendar.HOUR) + m / 60f
        hand(c, cx, cy, hr * 30, r * 0.5f, r)
        hand(c, cx, cy, m * 6, r * 0.88f, r)
        p.color = orange; p.strokeWidth = r * 0.008f; p.style = Paint.Style.STROKE
        c.save(); c.rotate(s * 6f, cx, cy); c.drawLine(cx, cy + r * 0.18f, cx, cy - r * 0.95f, p); c.restore()
        p.style = Paint.Style.FILL
        p.color = Color.WHITE; c.drawCircle(cx, cy, r * 0.05f, p)
        p.color = orange; c.drawCircle(cx, cy, r * 0.032f, p)
        p.color = Color.BLACK; c.drawCircle(cx, cy, r * 0.012f, p)
    }

    /** iOS-style hand: thin stem from the hub, then a thick rounded bar. */
    private fun hand(c: Canvas, cx: Float, cy: Float, deg: Float, len: Float, r: Float) {
        p.color = Color.WHITE; p.style = Paint.Style.STROKE
        c.save(); c.rotate(deg, cx, cy)
        p.strokeWidth = r * 0.025f; c.drawLine(cx, cy, cx, cy - r * 0.14f, p)
        p.strokeWidth = r * 0.06f; c.drawLine(cx, cy - r * 0.14f, cx, cy - len, p)
        c.restore()
    }

    private fun drawCalendar(c: Canvas, now: Calendar, x: Float, y: Float, w: Float, h: Float) {
        val today = now.get(Calendar.DAY_OF_MONTH)
        val first = (now.clone() as Calendar).apply { set(Calendar.DAY_OF_MONTH, 1) }
        val offset = (first.get(Calendar.DAY_OF_WEEK) - now.firstDayOfWeek + 7) % 7
        val days = now.getActualMaximum(Calendar.DAY_OF_MONTH)
        val rows = (offset + days + 6) / 7

        // title + header + week rows; size cells to fit
        val cell = min(w * 0.8f / 7, h * 0.8f / (rows + 2.2f))
        val left = x + (w - cell * 7) / 2
        var top = y + (h - cell * (rows + 2.2f)) / 2
        p.style = Paint.Style.FILL; p.typeface = Typeface.DEFAULT_BOLD

        p.textAlign = Paint.Align.LEFT; p.color = red; p.textSize = cell * 0.6f
        val month = DateFormatSymbols.getInstance().months[now.get(Calendar.MONTH)].uppercase()
        c.drawText(month, left + cell * 0.2f, top + cell * 0.7f, p)
        p.textAlign = Paint.Align.CENTER
        top += cell * 1.2f

        val names = DateFormatSymbols.getInstance().shortWeekdays // index 1..7 = SUNDAY..SATURDAY
        p.textSize = cell * 0.38f; p.color = dim
        for (col in 0 until 7) {
            val dow = (now.firstDayOfWeek - 1 + col) % 7 + 1
            c.drawText(names[dow].take(1).uppercase(), left + cell * (col + 0.5f), top + cell * 0.6f, p)
        }
        top += cell

        p.textSize = cell * 0.45f
        for (d in 1..days) {
            val i = offset + d - 1
            val cx = left + cell * (i % 7 + 0.5f); val cy = top + cell * (i / 7 + 0.5f)
            if (d == today) { p.color = red; c.drawCircle(cx, cy, cell * 0.42f, p) }
            p.color = Color.WHITE
            c.drawText("$d", cx, cy + p.textSize * 0.35f, p)
        }
    }

    private fun drawWeather(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val wx = Weather.now
        val left = x + w * 0.06f
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.LEFT; p.color = Color.WHITE
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        if (wx == null) {
            p.color = dim; p.textSize = h * 0.07f
            c.drawText(Weather.status, left, y + h / 2, p); return
        }
        p.textSize = h * 0.1f
        val city = wx.city.ifEmpty { "My Location" }
        c.drawText(city, left, y + h * 0.17f, p)
        locationArrow(c, left + p.measureText(city) + h * 0.02f, y + h * 0.085f, h * 0.07f)

        p.textSize = h * 0.38f
        c.drawText("${wx.temp}°", left, y + h * 0.52f, p)

        p.textSize = h * 0.09f; c.drawText(wx.emoji, left, y + h * 0.7f, p)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); p.textSize = h * 0.08f
        c.drawText(wx.label, left, y + h * 0.81f, p)
        c.drawText("H:${wx.hi}° L:${wx.lo}°", left, y + h * 0.92f, p)
        p.textAlign = Paint.Align.CENTER
    }

    private fun locationArrow(c: Canvas, x: Float, y: Float, s: Float) {
        val path = Path().apply {
            moveTo(x + s, y); lineTo(x, y + s * 0.45f); lineTo(x + s * 0.45f, y + s * 0.55f)
            lineTo(x + s * 0.55f, y + s); close()
        }
        c.drawPath(path, p)
    }

    private fun drawBattery(c: Canvas, cx: Float, cy: Float, r: Float) {
        val b = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val pct = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val color = if (pct <= 20) red else green
        val oval = RectF(cx - r, cy - r, cx + r, cy + r)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.2f; p.strokeCap = Paint.Cap.ROUND
        p.color = Color.rgb(40, 40, 42); c.drawArc(oval, 0f, 360f, false, p)
        p.color = color; c.drawArc(oval, -90f, 360f * pct / 100, false, p)
        p.style = Paint.Style.FILL; p.textAlign = Paint.Align.CENTER
        p.typeface = Typeface.DEFAULT_BOLD; p.textSize = r * 0.7f
        c.drawText("$pct", cx, cy + p.textSize * 0.36f, p)
    }

    private fun drawBanner(c: Canvas, w: Float, h: Float) {
        val n = NotificationFeed.latest ?: return
        val age = System.currentTimeMillis() - n.at
        if (age > bannerMs) return
        val u = min(w, h)
        val bw = min(w * 0.6f, u * 1.3f); val bh = u * 0.2f
        // slide down during the first 300 ms
        val top = u * 0.04f - (1 - (age / 300f).coerceAtMost(1f)) * (bh + u * 0.05f)
        val rect = RectF((w - bw) / 2, top, (w + bw) / 2, top + bh)
        p.style = Paint.Style.FILL; p.color = Color.rgb(44, 44, 46)
        c.drawRoundRect(rect, bh * 0.3f, bh * 0.3f, p)

        val pad = bh * 0.18f; val icon = bh - pad * 2
        n.icon?.let { it.setBounds((rect.left + pad).toInt(), (rect.top + pad).toInt(),
            (rect.left + pad + icon).toInt(), (rect.top + pad + icon).toInt()); it.draw(c) }

        val tx = rect.left + pad * 2 + icon; val tw = rect.right - pad - tx
        p.textAlign = Paint.Align.LEFT
        p.textSize = bh * 0.15f; p.color = dim; p.typeface = Typeface.DEFAULT
        c.drawText(n.app.uppercase(), tx, rect.top + pad + p.textSize, p)
        p.textSize = bh * 0.2f; p.color = Color.WHITE; p.typeface = Typeface.DEFAULT_BOLD
        c.drawText(TextUtils.ellipsize(n.title, p, tw, TextUtils.TruncateAt.END).toString(), tx, rect.top + bh * 0.55f, p)
        p.typeface = Typeface.DEFAULT; p.textSize = bh * 0.18f
        c.drawText(TextUtils.ellipsize(n.text, p, tw, TextUtils.TruncateAt.END).toString(), tx, rect.top + bh * 0.8f, p)
        p.textAlign = Paint.Align.CENTER
        if (age < 300) postInvalidateOnAnimation()
    }
}
