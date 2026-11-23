package app.budpop.art

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import app.budpop.core.Kind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin

/**
 * Every device that isn't earbuds: headphones, speaker, soundbar, keyboard, mouse, car adapter,
 * game controller and a generic Bluetooth badge.
 *
 * Each is a flat front (and back) drawn in unit space, height 1 and width [aspect], then turned in
 * "2.5D": yaw and pitch squash it and a stack of offset silhouettes gives it thickness, so it flips and
 * tilts like a real object. [t] (ms) drives the idle life (EQ, key ripple, scroll wheel, LEDs) and
 * [pulse] 0..1 the landing hit. No allocation per frame.
 */
class ProductArt {
    var accent = Color.rgb(0x8A, 0xB4, 0xF8)
    var accent2 = Color.rgb(0xF2, 0xB8, 0xD8)

    private val body = Color.rgb(0x2D, 0x30, 0x37)
    private val bodyLight = Color.rgb(0x45, 0x49, 0x52)
    private val fabric = Color.rgb(0x38, 0x3B, 0x44)
    private val fabricDark = Color.rgb(0x22, 0x24, 0x2A)
    private val rubber = Color.rgb(0x18, 0x19, 0x1D)
    private val edge = Color.rgb(0x12, 0x13, 0x16)
    private val metal = Color.rgb(0xB3, 0xB9, 0xC2)
    private val keyCap = Color.rgb(0xDD, 0xE0, 0xE6)
    private val glass = Color.rgb(0x08, 0x09, 0x0B)

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val rect = RectF()
    private val tmp = Path()
    private val gloss = LinearGradient(0f, -0.5f, 0f, 0.5f,
        intArrayOf(Color.argb(64, 255, 255, 255), Color.argb(14, 255, 255, 255), Color.argb(0, 255, 255, 255)),
        floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)

    /** Global alpha for this draw, and how lit the face is (1 = facing us). */
    private var ga = 1f
    private var lit = 1f

    fun aspect(k: Kind) = when (k) {
        Kind.SPEAKER -> 2.0f
        Kind.SOUNDBAR -> 5.2f
        Kind.KEYBOARD -> 2.7f
        Kind.MOUSE -> 0.62f
        Kind.CAR -> 0.62f
        Kind.HEADPHONES -> 1.0f
        Kind.GAMEPAD -> 1.6f
        else -> 1f
    }

    /** Thickness as a fraction of the height. */
    private fun depth(k: Kind) = when (k) {
        Kind.SPEAKER -> 0.42f
        Kind.SOUNDBAR -> 0.55f
        Kind.KEYBOARD -> 0.10f
        Kind.MOUSE -> 0.30f
        Kind.CAR -> 0.28f
        Kind.HEADPHONES -> 0.22f
        Kind.GAMEPAD -> 0.26f
        else -> 0.16f
    }

    // ---------- silhouettes (unit space, built once) ----------

    private val silhouettes = HashMap<Kind, Path>()
    private fun sil(k: Kind): Path = silhouettes.getOrPut(k) { buildSil(k) }

    private fun buildSil(k: Kind): Path = Path().apply {
        val a = aspect(k) / 2
        when (k) {
            Kind.SPEAKER -> addRoundRect(-a, -0.5f, a, 0.5f, 0.5f, 0.5f, Path.Direction.CW)
            Kind.SOUNDBAR -> addRoundRect(-a, -0.5f, a, 0.5f, 0.24f, 0.24f, Path.Direction.CW)
            Kind.KEYBOARD -> addRoundRect(-a, -0.5f, a, 0.5f, 0.09f, 0.09f, Path.Direction.CW)
            Kind.MOUSE -> {
                moveTo(0f, -0.5f)
                cubicTo(0.19f, -0.5f, a, -0.3f, a, 0.02f)
                cubicTo(a, 0.33f, 0.19f, 0.5f, 0f, 0.5f)
                cubicTo(-0.19f, 0.5f, -a, 0.33f, -a, 0.02f)
                cubicTo(-a, -0.3f, -0.19f, -0.5f, 0f, -0.5f)
                close()
            }
            Kind.CAR -> {
                addCircle(0f, -0.19f, 0.31f, Path.Direction.CW)
                val neck = Path().apply { moveTo(-0.2f, 0.0f); lineTo(0.2f, 0.0f); lineTo(0.13f, 0.17f); lineTo(-0.13f, 0.17f); close() }
                val plug = Path().apply { addRoundRect(-0.125f, 0.12f, 0.125f, 0.45f, 0.03f, 0.03f, Path.Direction.CW) }
                val tip = Path().apply { addRoundRect(-0.07f, 0.4f, 0.07f, 0.5f, 0.05f, 0.05f, Path.Direction.CW) }
                op(neck, Path.Op.UNION); op(plug, Path.Op.UNION); op(tip, Path.Op.UNION)
            }
            Kind.HEADPHONES -> {
                val band = Path()
                line.strokeWidth = 0.085f
                line.getFillPath(headband, band)
                addPath(band)
                for (s in SIDES) addRoundRect(s * 0.37f - 0.13f, 0.02f, s * 0.37f + 0.13f, 0.5f, 0.11f, 0.11f, Path.Direction.CW)
                op(Path(), Path.Op.UNION)
            }
            Kind.GAMEPAD -> {
                addRoundRect(-0.7f, -0.44f, 0.7f, 0.12f, 0.24f, 0.24f, Path.Direction.CW)
                val m = Matrix()
                for (s in SIDES) {
                    val g = Path().apply { addOval(RectF(-0.21f, -0.32f, 0.21f, 0.32f), Path.Direction.CW) }
                    m.setRotate(-s * 22f); m.postTranslate(s * 0.53f, 0.16f)
                    g.transform(m)
                    op(g, Path.Op.UNION)
                }
            }
            else -> addRoundRect(-0.5f, -0.5f, 0.5f, 0.5f, 0.26f, 0.26f, Path.Direction.CW)
        }
    }

    private val headband = Path().apply { moveTo(-0.37f, 0.06f); cubicTo(-0.41f, -0.64f, 0.41f, -0.64f, 0.37f, 0.06f) }
    private val rune = Path().apply {
        moveTo(-0.2f, -0.21f); lineTo(0.21f, 0.19f); lineTo(0f, 0.4f); lineTo(0f, -0.4f); lineTo(0.21f, -0.19f); lineTo(-0.2f, 0.21f)
    }

    /** Keyboard key rects (unit space): [l, t, r, b] × n. */
    private val keys: FloatArray = run {
        val rows = listOf(
            floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 2f),
            floatArrayOf(1.5f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1.5f),
            floatArrayOf(1.75f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 2.25f),
            floatArrayOf(2.25f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f, 2.75f),
            floatArrayOf(1.25f, 1.25f, 1.25f, 6.25f, 1.25f, 1.25f, 1.25f, 1.25f),
        )
        val x0 = -1.27f; val w = 2.54f; val y0 = -0.42f; val h = 0.84f
        val u = w / 15f; val rh = h / rows.size; val g = 0.024f
        val out = ArrayList<Float>()
        rows.forEachIndexed { ri, row ->
            var x = x0
            for (units in row) {
                out += listOf(x + g / 2, y0 + ri * rh + g / 2, x + units * u - g / 2, y0 + (ri + 1) * rh - g / 2)
                x += units * u
            }
        }
        out.toFloatArray()
    }

    // ---------- drawing ----------

    fun draw(c: Canvas, k: Kind, p: BudPose, t: Float, pulse: Float = 0f) {
        if (p.alpha <= 0.01f || p.size <= 1f) return
        ga = p.alpha.coerceIn(0f, 1f)
        val yaw = Math.toRadians(p.yaw.toDouble()).toFloat()
        val pitch = Math.toRadians(p.pitch.toDouble()).toFloat()
        val cy = cos(yaw); val cp = cos(pitch)
        val sx = max(abs(cy), 0.045f) * p.squashX * p.size
        val sy = max(abs(cp), 0.045f) * p.squashY * p.size
        val back = (cy < 0f) != (cp < 0f)
        lit = 0.62f + 0.38f * abs(cy) * abs(cp)
        val d = depth(k) * p.size
        val ox = sin(yaw) * d
        val oy = -sin(pitch) * d
        val shape = sil(k)

        c.save()
        c.translate(p.x, p.y)
        c.rotate(p.roll)
        // Thickness: silhouettes stacked from the far side towards the face, darker further back,
        // about one every 1.5 px so edge-on views look solid rather than sliced.
        val steps = (max(abs(ox), abs(oy)) / 1.5f).toInt().coerceIn(2, 40)
        for (i in steps downTo 1) {
            val f = i / steps.toFloat()
            c.save()
            c.translate(ox * f, oy * f)
            c.scale(sx, sy)
            paint(fill, shade(sideColor(k), 0.55f + 0.35f * (1f - f)))
            c.drawPath(shape, fill)
            c.restore()
        }
        c.save()
        c.scale(sx, sy)
        if (back) back(c, k) else face(c, k, t, pulse)
        // Gloss from above and a faint rim so dark devices read on a dark card.
        fill.shader = gloss; fill.color = Color.WHITE; fill.alpha = (ga * 255 * lit).toInt()
        c.drawPath(shape, fill)
        fill.shader = null
        line.strokeWidth = 1.4f / max(1f, minOf(sx, sy)) // ~1.4 px whatever the scale
        paint(line, Color.argb(60, 255, 255, 255), raw = true)
        c.drawPath(shape, line)
        c.restore()
        c.restore()
    }

    /** Sound waves, radio arcs and the like, drawn around the device (screen space, unrotated). */
    fun fx(c: Canvas, k: Kind, p: BudPose, t: Float) {
        if (p.alpha <= 0.05f) return
        val w = p.size * aspect(k) * max(abs(cos(Math.toRadians(p.yaw.toDouble()))).toFloat(), 0.2f)
        val h = p.size * max(abs(cos(Math.toRadians(p.pitch.toDouble()))).toFloat(), 0.2f)
        line.shader = null
        when (k) {
            Kind.SPEAKER, Kind.HEADPHONES -> for (j in 0 until 3) {
                val ph = ((t / 1100f) + j / 3f) % 1f
                val r = h * (0.32f + ph * 0.42f)
                line.strokeWidth = h * 0.035f * (1f - ph * 0.6f)
                line.color = accent; line.alpha = ((1f - ph).pow(1.5f) * 200 * p.alpha).toInt()
                for (s in SIDES) {
                    val cx = p.x + s * (w / 2 + h * 0.02f)
                    rect.set(cx - r, p.y - r, cx + r, p.y + r)
                    c.drawArc(rect, if (s < 0) 180f - 32f else -32f, 64f, false, line)
                }
            }
            Kind.SOUNDBAR -> for (j in 0 until 3) {
                val ph = ((t / 1300f) + j / 3f) % 1f
                val r = h * (0.9f + ph * 1.6f)
                line.strokeWidth = h * 0.09f * (1f - ph * 0.6f)
                line.color = accent; line.alpha = ((1f - ph).pow(1.5f) * 170 * p.alpha).toInt()
                rect.set(p.x - r * 1.8f, p.y - r, p.x + r * 1.8f, p.y + r)
                c.drawArc(rect, 245f, 50f, false, line)
            }
            Kind.CAR -> for (j in 0 until 3) {
                val ph = ((t / 900f) + j / 3f) % 1f
                val r = h * (0.22f + ph * 0.32f)
                val cx = p.x; val cy = p.y - h * 0.19f
                line.strokeWidth = h * 0.03f
                line.color = accent; line.alpha = ((1f - ph).pow(1.5f) * 210 * p.alpha).toInt()
                rect.set(cx - r, cy - r, cx + r, cy + r)
                c.drawArc(rect, -60f, 40f, false, line)
                c.drawArc(rect, -160f, 40f, false, line)
            }
            else -> {}
        }
    }

    private fun sideColor(k: Kind) = when (k) {
        Kind.KEYBOARD -> edge
        Kind.OTHER -> blend(accent, Color.BLACK, 0.55f)
        else -> edge
    }

    private fun face(c: Canvas, k: Kind, t: Float, pulse: Float) {
        when (k) {
            Kind.SPEAKER -> speaker(c, t, pulse)
            Kind.SOUNDBAR -> soundbar(c, t, pulse)
            Kind.KEYBOARD -> keyboard(c, t, pulse)
            Kind.MOUSE -> mouse(c, t, pulse)
            Kind.CAR -> car(c, t, pulse)
            Kind.HEADPHONES -> headphones(c, t, pulse, logo = true)
            Kind.GAMEPAD -> gamepad(c, t, pulse)
            else -> badge(c, t, pulse)
        }
    }

    private fun back(c: Canvas, k: Kind) {
        val shape = sil(k)
        when (k) {
            Kind.HEADPHONES -> headphones(c, 0f, 0f, logo = false)
            Kind.OTHER -> { paint(fill, shade(accent, 0.6f)); c.drawPath(shape, fill) }
            else -> {
                paint(fill, if (k == Kind.SPEAKER || k == Kind.SOUNDBAR) fabric else body); c.drawPath(shape, fill)
                // A label plate and a couple of feet so the back reads as a back.
                paint(fill, bodyLight)
                val a = aspect(k) / 2
                rect.set(-a * 0.35f, -0.08f, a * 0.35f, 0.08f); c.drawRoundRect(rect, 0.05f, 0.05f, fill)
                paint(fill, rubber)
                for (s in SIDES) c.drawCircle(s * a * 0.7f, 0.32f, 0.06f, fill)
            }
        }
    }

    // ---------- the devices ----------

    /** Portable capsule speaker: fabric body, a passive radiator at each end that pumps to the beat. */
    private fun speaker(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.SPEAKER)
        paint(fill, fabric); c.drawPath(shape, fill)
        c.save(); c.clipPath(shape)
        line.strokeWidth = 0.012f; paint(line, fabricDark)
        var x = -1.02f
        while (x < 1.02f) { c.drawLine(x, -0.5f, x + 0.12f, 0.5f, line); x += 0.055f }
        c.restore()
        val b = beat(t, 520f) * 0.8f + pulse
        for (s in SIDES) {
            val cx = s * 0.66f
            paint(fill, rubber); c.drawCircle(cx, 0f, 0.38f, fill)
            paint(fill, bodyLight); c.drawCircle(cx, 0f, 0.29f * (1f + 0.08f * b), fill)
            paint(fill, fabricDark); c.drawCircle(cx, 0f, 0.2f * (1f + 0.1f * b), fill)
            paint(fill, blend(bodyLight, Color.WHITE, 0.2f)); c.drawCircle(cx - 0.06f, -0.07f, 0.05f, fill)
        }
        paint(fill, accent)
        rect.set(-0.2f, -0.065f, 0.2f, 0.065f); c.drawRoundRect(rect, 0.065f, 0.065f, fill)
        paint(fill, rubber)
        for (i in -1..1) { rect.set(i * 0.12f - 0.04f, -0.5f, i * 0.12f + 0.04f, -0.44f); c.drawRoundRect(rect, 0.02f, 0.02f, fill) }
    }

    /** Long bar: perforated grille, a glass window with a dancing EQ, little feet. */
    private fun soundbar(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.SOUNDBAR)
        val a = aspect(Kind.SOUNDBAR) / 2
        paint(fill, rubber); c.drawPath(shape, fill)
        rect.set(-a + 0.12f, -0.34f, a - 0.12f, 0.4f); paint(fill, fabric); c.drawRoundRect(rect, 0.16f, 0.16f, fill)
        paint(fill, fabricDark)
        for (row in -1..1) {
            var x = -a + 0.26f
            while (x < a - 0.2f) {
                if (abs(x) > 0.55f) c.drawCircle(x, 0.03f + row * 0.19f, 0.024f, fill)
                x += 0.105f
            }
        }
        rect.set(-a + 0.06f, -0.47f, a - 0.06f, -0.39f); paint(fill, bodyLight); c.drawRoundRect(rect, 0.04f, 0.04f, fill)
        rect.set(-0.5f, -0.2f, 0.5f, 0.3f); paint(fill, glass); c.drawRoundRect(rect, 0.09f, 0.09f, fill)
        for (i in 0 until 11) {
            val lv = (0.5f + 0.5f * sin(t / 170f * (1f + i * 0.31f) + i * 1.7f)) * (0.55f + 0.45f * beat(t, 600f)) + pulse * 0.8f
            val hgt = 0.05f + 0.36f * lv.coerceIn(0f, 1f)
            val x = (i - 5) * 0.075f
            rect.set(x - 0.02f, 0.24f - hgt, x + 0.02f, 0.24f)
            paint(fill, if (i % 3 == 1) accent2 else accent, raw = true)
            c.drawRoundRect(rect, 0.02f, 0.02f, fill)
        }
        paint(fill, rubber)
        for (s in SIDES) { rect.set(s * (a - 0.5f) - 0.18f, 0.5f, s * (a - 0.5f) + 0.18f, 0.6f); c.drawRect(rect, fill) }
    }

    /** Keyboard lying on a desk: a light ripple runs across the keys and one key is always going down. */
    private fun keyboard(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.KEYBOARD)
        paint(fill, body); c.drawPath(shape, fill)
        val waveX = ((t / 1400f) % 1f) * 3.6f - 1.8f
        val n = keys.size / 4
        val pressed = ((t / 150f).toInt() * 7919 % n + n) % n
        for (i in 0 until n) {
            val l = keys[i * 4]; val tp = keys[i * 4 + 1]; val r = keys[i * 4 + 2]; val b = keys[i * 4 + 3]
            val cx = (l + r) / 2
            val g = max(0f, 1f - abs(cx - waveX) / 0.35f).pow(2) * 0.85f + pulse * 0.9f
            var col = blend(keyCap, accent, g.coerceIn(0f, 1f))
            var inset = 0f
            if (i == pressed && t > 0f) { col = blend(col, Color.BLACK, 0.25f); inset = 0.008f }
            paint(fill, blend(keyCap, Color.BLACK, 0.45f))
            rect.set(l, tp + 0.012f, r, b + 0.006f); c.drawRoundRect(rect, 0.02f, 0.02f, fill)
            paint(fill, col)
            rect.set(l + inset, tp + inset, r - inset, b - 0.008f + inset); c.drawRoundRect(rect, 0.02f, 0.02f, fill)
        }
    }

    /** Top-down mouse: two buttons, a scrolling wheel, a breathing logo light; it clicks on landing. */
    private fun mouse(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.MOUSE)
        paint(fill, body); c.drawPath(shape, fill)
        c.save(); c.clipPath(shape)
        paint(fill, bodyLight); c.drawRect(-0.4f, -0.55f, 0.4f, -0.06f, fill)
        val click = max(pulse, if (t > 0f) clickAt(t) else 0f)
        if (click > 0f) { paint(fill, Color.argb((click * 120).toInt(), 0, 0, 0), raw = true); c.drawRect(-0.4f, -0.55f, 0f, -0.06f, fill) }
        line.strokeWidth = 0.014f; paint(line, edge)
        c.drawLine(0f, -0.5f, 0f, -0.06f, line)
        c.drawLine(-0.4f, -0.06f, 0.4f, -0.06f, line)
        c.restore()
        rect.set(-0.04f, -0.37f, 0.04f, -0.18f); paint(fill, rubber); c.drawRoundRect(rect, 0.04f, 0.04f, fill)
        c.save(); c.clipRect(rect)
        line.strokeWidth = 0.012f; paint(line, bodyLight)
        val off = (t / 22f) % 0.05f
        var y = -0.4f + off
        while (y < -0.16f) { c.drawLine(-0.04f, y, 0.04f, y, line); y += 0.05f }
        c.restore()
        val glow = 0.5f + 0.5f * sin(t / 500f)
        paint(fill, Color.argb((90 + glow * 165).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)), raw = true)
        rect.set(-0.09f, 0.33f, 0.09f, 0.36f); c.drawRoundRect(rect, 0.02f, 0.02f, fill)
    }

    /** Cigarette-lighter FM / Bluetooth adapter: round head with a sweeping LED ring, plug below. */
    private fun car(c: Canvas, t: Float, pulse: Float) {
        // Plug and neck.
        paint(fill, rubber)
        rect.set(-0.125f, 0.12f, 0.125f, 0.45f); c.drawRoundRect(rect, 0.03f, 0.03f, fill)
        line.strokeWidth = 0.012f; paint(line, bodyLight)
        for (i in 0 until 4) { val y = 0.2f + i * 0.055f; c.drawLine(-0.11f, y, 0.11f, y, line) }
        paint(fill, metal)
        rect.set(-0.07f, 0.4f, 0.07f, 0.5f); c.drawRoundRect(rect, 0.05f, 0.05f, fill)
        for (s in SIDES) c.drawCircle(s * 0.13f, 0.3f, 0.025f, fill)
        tmp.rewind(); tmp.moveTo(-0.2f, 0.0f); tmp.lineTo(0.2f, 0.0f); tmp.lineTo(0.13f, 0.17f); tmp.lineTo(-0.13f, 0.17f); tmp.close()
        paint(fill, body); c.drawPath(tmp, fill)
        // Head.
        paint(fill, body); c.drawCircle(0f, -0.19f, 0.31f, fill)
        line.strokeWidth = 0.022f; paint(line, metal); c.drawCircle(0f, -0.19f, 0.295f, line)
        paint(fill, glass); c.drawCircle(0f, -0.19f, 0.22f, fill)
        val head = (t / 700f) % 1f
        line.strokeWidth = 0.034f
        for (i in 0 until 12) {
            val a = i / 12f
            val dist = ((a - head) % 1f + 1f) % 1f
            val lv = max(pulse, (1f - dist).pow(3))
            paint(line, Color.argb((40 + lv * 215).toInt(), Color.red(accent), Color.green(accent), Color.blue(accent)), raw = true)
            rect.set(-0.17f, -0.36f, 0.17f, -0.02f)
            c.drawArc(rect, a * 360f - 90f + 6f, 18f, false, line)
        }
        c.save(); c.translate(0f, -0.19f); c.scale(0.2f, 0.2f)
        line.strokeWidth = 0.13f; paint(line, Color.WHITE)
        c.drawPath(rune, line)
        c.restore()
    }

    /** Over-ear headphones, front on: band, sliders and cups that bounce out on the beat. */
    private fun headphones(c: Canvas, t: Float, pulse: Float, logo: Boolean) {
        line.strokeWidth = 0.085f; paint(line, body); c.drawPath(headband, line)
        c.save(); c.translate(0f, 0.025f)
        line.strokeWidth = 0.03f; paint(line, bodyLight); c.drawPath(headband, line)
        c.restore()
        val b = if (logo) beat(t, 480f) * 0.6f + pulse else 0f
        for (s in SIDES) {
            val cx = s * (0.37f + 0.012f * b)
            paint(fill, metal); rect.set(cx - 0.025f, -0.04f, cx + 0.025f, 0.08f); c.drawRect(rect, fill)
            paint(fill, body); rect.set(cx - 0.13f, 0.02f, cx + 0.13f, 0.5f); c.drawRoundRect(rect, 0.11f, 0.11f, fill)
            paint(fill, bodyLight); rect.set(cx - 0.1f, 0.06f, cx + 0.1f, 0.46f); c.drawRoundRect(rect, 0.09f, 0.09f, fill)
            if (logo) {
                line.strokeWidth = 0.018f; paint(line, accent)
                c.drawCircle(cx, 0.26f, 0.05f + 0.01f * b, line)
            }
        }
    }

    /** Controller: d-pad, sticks that wiggle, face buttons lighting in turn. */
    private fun gamepad(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.GAMEPAD)
        paint(fill, body); c.drawPath(shape, fill)
        paint(fill, rubber)
        rect.set(-0.5f, -0.21f, -0.34f, -0.15f); c.drawRoundRect(rect, 0.02f, 0.02f, fill)
        rect.set(-0.45f, -0.26f, -0.39f, -0.1f); c.drawRoundRect(rect, 0.02f, 0.02f, fill)
        val faces = intArrayOf(Color.rgb(0xF6, 0xC3, 0x44), Color.rgb(0xEF, 0x5B, 0x5B), Color.rgb(0x5C, 0xC9, 0x7B), Color.rgb(0x5B, 0x9B, 0xF0))
        val dx = floatArrayOf(0f, 0.1f, 0f, -0.1f); val dy = floatArrayOf(-0.1f, 0f, 0.1f, 0f)
        val on = ((t / 260f).toInt() % 4)
        for (i in 0 until 4) {
            val lv = if (t > 0f && i == on) 1f else pulse
            paint(fill, blend(blend(faces[i], Color.BLACK, 0.35f), faces[i], lv))
            c.drawCircle(0.42f + dx[i], -0.18f + dy[i], 0.05f, fill)
        }
        for (s in SIDES) {
            val wx = if (t > 0f) 0.025f * sin(t / (300f + s * 70f)) else 0f
            val wy = if (t > 0f) 0.025f * cos(t / (340f - s * 50f)) else 0f
            paint(fill, rubber); c.drawCircle(s * 0.2f, 0.06f, 0.11f, fill)
            paint(fill, bodyLight); c.drawCircle(s * 0.2f + wx, 0.06f + wy, 0.075f, fill)
            paint(fill, body); rect.set(s * 0.09f - 0.035f, -0.25f, s * 0.09f + 0.035f, -0.22f); c.drawRoundRect(rect, 0.015f, 0.015f, fill)
        }
        paint(fill, accent); c.drawCircle(0f, -0.08f, 0.04f + 0.012f * pulse, fill)
    }

    /** Anything else: a Material-coloured badge with the Bluetooth rune, breathing. */
    private fun badge(c: Canvas, t: Float, pulse: Float) {
        val shape = sil(Kind.OTHER)
        paint(fill, accent); c.drawPath(shape, fill)
        val glow = 0.5f + 0.5f * sin(t / 600f)
        paint(fill, Color.argb((glow * 70 + pulse * 120).toInt(), 255, 255, 255), raw = true)
        c.drawCircle(0f, 0f, 0.3f, fill)
        line.strokeWidth = 0.075f; paint(line, blend(accent, Color.BLACK, 0.7f), raw = true)
        c.drawPath(rune, line)
    }

    // ---------- helpers ----------

    private fun beat(t: Float, period: Float): Float = if (t <= 0f) 0f else max(0f, sin(2 * PI.toFloat() * t / period)).pow(6)

    /** A double click every few seconds. */
    private fun clickAt(t: Float): Float {
        val ph = t % 2600f
        return when {
            ph < 90f -> 1f - ph / 90f
            ph in 160f..250f -> 1f - (ph - 160f) / 90f
            else -> 0f
        }
    }

    /** Sets [p]'s colour, lit by the face angle unless [raw], with this draw's alpha. */
    private fun paint(p: Paint, color: Int, raw: Boolean = false) {
        p.shader = null
        p.color = if (raw) color else shade(color, lit)
        p.alpha = (Color.alpha(color) * ga).toInt()
    }

    private fun shade(c: Int, f: Float) = Color.argb(Color.alpha(c), (Color.red(c) * f).toInt().coerceIn(0, 255),
        (Color.green(c) * f).toInt().coerceIn(0, 255), (Color.blue(c) * f).toInt().coerceIn(0, 255))

    companion object {
        private val SIDES = intArrayOf(-1, 1)

        fun blend(a: Int, b: Int, t: Float): Int {
            val u = t.coerceIn(0f, 1f)
            return Color.argb(
                (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * u).toInt(),
                (Color.red(a) + (Color.red(b) - Color.red(a)) * u).toInt(),
                (Color.green(a) + (Color.green(b) - Color.green(a)) * u).toInt(),
                (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * u).toInt(),
            )
        }
    }
}
