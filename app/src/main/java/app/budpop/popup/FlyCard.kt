package app.budpop.popup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vibepop.R
import app.budpop.art.BudPose
import app.budpop.art.Earbuds3D
import app.budpop.art.ProductArt
import app.budpop.core.BudState
import app.budpop.core.Kind
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * The animated popup, two looks, for every [Kind] of device. Earbuds fly in as a pair (below); every
 * other device flies in alone with its own move: the speaker is lobbed in and bounces, the soundbar
 * slides in twirling, the keyboard drops onto the desk, the mouse zips in like a cursor, the car adapter
 * drives in and brakes, headphones drop in spinning, the controller tumbles in. After landing each one
 * keeps a little life of its own (EQ, key ripple, scroll wheel, LED ring, sound waves).
 *
 * Fanciest ([mini] false): the screen dims, the left bud flies in from the right edge and the right bud
 * from the left edge, crossing paths, spinning and flipping, and they land side by side in the middle
 * with a ripple and sparks; the card unfolds around them. On close they fly back out the way they came.
 *
 * Lovely ([mini] true): the same flight, but inside a small card at the bottom of the screen; no dimming,
 * the buds never leave the card, and closing just slides the card away.
 *
 * Both: battery and a close button only (plain earbuds have no noise modes), swipe the card down to dismiss,
 * tap anywhere during the fly-in to skip it. Timing runs on the vsync frame clock, smooth at 120-165 Hz.
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class FlyCard(
    context: Context,
    private val actions: PopupActions,
    private val mini: Boolean,
    private val kind: Kind = Kind.EARBUDS,
) : PopupView {
    private val ctx = context
    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm)
    private fun dpi(v: Number) = dp(v).toInt()
    private fun now() = AnimationUtils.currentAnimationTimeMillis()

    override val startsFullScreen = !mini
    override val gravity = if (mini) Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
    override val offsetY = if (mini) dpi(16) else 0

    // ---------- Material You colours ----------
    private val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private fun sys(id: Int): Int = if (android.os.Build.VERSION.SDK_INT >= 31) ctx.getColor(id) else when (id) {
        android.R.color.system_neutral1_900 -> 0xff202128.toInt()
        android.R.color.system_neutral2_800 -> 0xff383944.toInt()
        android.R.color.system_neutral2_600 -> 0xff535560.toInt()
        android.R.color.system_neutral2_300 -> 0xffbcc0cd.toInt()
        android.R.color.system_neutral2_100 -> 0xffe1e3ec.toInt()
        android.R.color.system_neutral1_10, android.R.color.system_neutral1_50 -> Color.WHITE
        else -> if (night) 0xffa6cbff.toInt() else 0xff275eaa.toInt()
    }
    private val cBg = if (night) sys(android.R.color.system_neutral1_900) else sys(android.R.color.system_neutral1_10)
    private val cVariant = if (night) sys(android.R.color.system_neutral2_800) else sys(android.R.color.system_neutral2_100)
    private val cText = if (night) sys(android.R.color.system_neutral1_50) else sys(android.R.color.system_neutral1_900)
    private val cMuted = if (night) sys(android.R.color.system_neutral2_300) else sys(android.R.color.system_neutral2_600)
    private val cAccent = if (night) sys(android.R.color.system_accent1_200) else sys(android.R.color.system_accent1_600)
    private val cTertiary = if (night) sys(android.R.color.system_accent3_200) else sys(android.R.color.system_accent3_600)
    private val cRed = Color.rgb(0xEF, 0x53, 0x50)

    private val art = Earbuds3D()
    private val product = ProductArt().apply { accent = cAccent; accent2 = cTertiary }
    /** Earbuds come as a left and right piece; everything else is one piece (side 0). */
    private val pair = kind == Kind.EARBUDS
    private val pieces = if (pair) SIDES else ONE

    // ---------- views ----------
    private val wide = ctx.resources.configuration.screenWidthDp >= 600
    private val cardWidth = if (wide) dpi(400) else min(dm.widthPixels - dpi(32), dpi(380))
    private val stageHeight = dpi(if (wide) 190 else 172)
    private val budSize = if (pair) stageHeight * 0.80f else singleSize()
    private val gap = budSize * 0.36f

    /** Unit height of a single device so it fits the stage, wide ones limited by the card width. */
    private fun singleSize(): Float {
        val fillH = when (kind) {
            Kind.SPEAKER -> 0.5f
            Kind.HEADPHONES -> 0.7f
            Kind.CAR -> 0.8f
            Kind.MOUSE -> 0.76f
            Kind.GAMEPAD -> 0.58f
            Kind.KEYBOARD -> 0.62f
            else -> 0.6f
        }
        return min(stageHeight * fillH, (cardWidth - dp(64)) * 0.9f / product.aspect(kind))
    }

    private val scrim = ScrimView()
    private val stage = StageView()
    private val idle = IdleBudsView()
    private val battery = BatteryBar()
    private val name = text(20f, cText, bold = true).apply { gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val status = text(14f, cMuted).apply { gravity = Gravity.CENTER; text = "Connected" }
    private val content = ArrayList<View>()
    private val card = CardFrame()

    override val root: FrameLayout = object : FrameLayout(ctx) {
        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
                if (phase == Phase.INTRO) { skip(); return true }
                if (phase == Phase.EXIT) return true
                actions.touched()
            }
            return super.dispatchTouchEvent(ev)
        }
    }

    init {
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpi(20), dpi(6), dpi(20), dpi(20))
        }
        // Grab handle hints at swipe-to-dismiss.
        column.addView(View(ctx).apply { background = pill(blendA(cMuted, 0.45f)) },
            LinearLayout.LayoutParams(dpi(36), dpi(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = dpi(4) })
        val stageBox = FrameLayout(ctx).apply {
            addView(idle, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageHeight))
            addView(ImageView(ctx).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(cText)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "Close"
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cVariant) }
                setOnClickListener { actions.close() }
            }, FrameLayout.LayoutParams(dpi(40), dpi(40), Gravity.TOP or Gravity.END))
        }
        column.addView(stageBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, stageHeight))
        column.addView(name.also(content::add))
        column.addView(status.also(content::add), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dpi(14) })
        column.addView(battery.also(content::add), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpi(56)))

        card.addView(column)
        card.background = GradientDrawable().apply {
            setColor(cBg)
            cornerRadius = dp(32)
            setStroke(dpi(1), blendA(cText, 0.08f))
        }
        card.elevation = dp(12)
        card.clipToOutline = true

        if (mini) {
            card.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(card, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(dpi(8), dpi(8), dpi(8), dpi(8)) })
        } else {
            root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.addView(card, FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply { setMargins(dpi(8), dpi(16), dpi(8), dpi(16)) })
            root.addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Elevation outranks child order: without this the unfolding card hides the landing device.
            stage.elevation = card.elevation + dp(1)
        }
        root.clipChildren = false
        card.reveal = 0f
        content.forEach { it.alpha = 0f }
    }

    override fun bind(s: BudState) {
        name.text = s.name
        status.text = if (s.connected) "Connected" else "Connecting…"
        battery.set(s.battery, s.charging)
    }

    // ---------- timeline ----------

    private enum class Phase { INTRO, SETTLED, EXIT, DONE }
    private var phase = Phase.INTRO
    private var t0 = 0L
    private var running = false
    private var exitDone: (() -> Unit)? = null
    private var exitFromY = 0f

    private val tick = object : Runnable {
        override fun run() {
            if (!running || !root.isAttachedToWindow) return
            frame(now() - t0)
            if (running) root.postOnAnimation(this)
        }
    }

    override fun enter() {
        phase = Phase.INTRO
        t0 = now()
        running = true
        root.postOnAnimation(tick)
    }

    private fun skip() {
        if (phase != Phase.INTRO) return
        t0 = now() - INTRO_END
        frame(INTRO_END)
    }

    override fun exit(done: () -> Unit) {
        if (phase == Phase.EXIT || phase == Phase.DONE) return
        exitDone = done
        if (phase == Phase.INTRO) skip()
        phase = Phase.EXIT
        exitFromY = card.translationY
        if (!mini && !dragged) {
            actions.fullScreen(true)
            stage.visibility = View.VISIBLE
            // Wait a frame for the window to grow back to full screen before the buds take off.
            root.post { t0 = now(); if (!running) { running = true; root.postOnAnimation(tick) } }
            return
        }
        t0 = now()
        if (!running) { running = true; root.postOnAnimation(tick) }
    }

    /** For the frame renderer test: the intro (or exit) as it looks [t] ms in. */
    internal fun renderAt(t: Long, exit: Boolean = false) {
        if (exit) { phase = Phase.EXIT; exitFrame(t) } else { phase = Phase.INTRO; introFrame(t) }
    }

    private fun frame(t: Long) = when (phase) {
        Phase.INTRO -> introFrame(t)
        Phase.EXIT -> exitFrame(t)
        else -> running = false
    }

    private fun introFrame(t: Long) {
        val tf = t.toFloat()
        scrim.level = when {
            tf < 300 -> ease(tf / 300)
            tf < 1950 -> 1f
            else -> 1f - ease(((tf - 1950) / 500).coerceAtMost(1f))
        }
        stage.time = tf
        stage.invalidate()
        if (mini) {
            val up = (tf / 420f).coerceIn(0f, 1f)
            card.reveal = 1f
            card.translationY = (1f - outBack(up)) * dp(170)
            card.alpha = (tf / 160f).coerceIn(0f, 1f)
        } else {
            val rp = ((tf - REVEAL_AT) / 550f).coerceIn(0f, 1f)
            val (sx, sy) = slotIn(card)
            card.revealX = sx; card.revealY = sy
            card.reveal = outCubic(rp)
        }
        val contentAt = if (mini) 260f else CONTENT_AT
        content.forEachIndexed { i, v ->
            val p = ((tf - contentAt - i * 70f) / 360f).coerceIn(0f, 1f)
            val e = outCubic(p)
            v.alpha = e
            v.translationY = (1f - e) * dp(18)
        }
        if (tf >= LAND_AT + 60) battery.start()
        if (tf >= HANDOFF_AT) { idle.visible = true; stage.handedOff = true }
        if (t >= INTRO_END) {
            scrim.visibility = View.GONE
            stage.visibility = View.GONE
            phase = Phase.SETTLED
            running = false
            if (!mini) actions.fullScreen(false)
        }
    }

    private fun exitFrame(t: Long) {
        val tf = t.toFloat()
        if (mini || dragged) {
            // No fly-out: slide the card away (continuing from wherever a swipe left it).
            val u = (tf / SLIDE_MS).coerceIn(0f, 1f)
            card.translationY = exitFromY + inCubic(u) * (card.height + dp(60))
            card.alpha = 1f - u * u
            if (u >= 1f) finish()
            return
        }
        content.forEach { it.alpha = 1f - (tf / 200f).coerceIn(0f, 1f) }
        val rp = ((tf - 100) / 340f).coerceIn(0f, 1f)
        card.reveal = 1f - inCubic(rp)
        idle.visible = false
        stage.handedOff = false
        stage.exitTime = tf
        stage.invalidate()
        if (tf >= EXIT_END) finish()
    }

    private fun finish() {
        phase = Phase.DONE
        running = false
        card.visibility = View.INVISIBLE
        stage.visibility = View.INVISIBLE
        exitDone?.invoke()
        exitDone = null
    }

    // ---------- geometry ----------

    /** Centre of the buds' slot in [ancestor]'s coordinates. */
    private fun slotIn(ancestor: View): Pair<Float, Float> = offset(idle, ancestor).let { (x, y) -> (x + idle.width / 2f) to (y + idle.height * 0.5f) }

    /** Slot centre in the flight layer's coordinates (screen for Fanciest, card for Lovely). */
    private fun stageSlot() = if (mini) slotIn(card) else slotIn(root)

    private fun offset(v: View, ancestor: View): Pair<Float, Float> {
        var x = 0f; var y = 0f
        var cur: View? = v
        while (cur != null && cur !== ancestor) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View
        }
        return x to y
    }

    /** Resting pose of a bud ([side] -1 left, +1 right) around the slot centre. */
    private fun rest(side: Int, cx: Float, cy: Float, size: Float = budSize): BudPose {
        if (side != 0) return BudPose(cx + side * gap * size / budSize, cy, size, yaw = -side * REST_YAW, pitch = REST_PITCH, roll = side * REST_ROLL)
        // A three-quarter view so the device shows its thickness; keyboards lie back on the desk.
        val (yaw, pitch, roll) = when (kind) {
            Kind.KEYBOARD -> Triple(-12f, 42f, 0f)
            Kind.MOUSE -> Triple(-14f, 24f, 14f)
            Kind.GAMEPAD -> Triple(-16f, 22f, -4f)
            Kind.SOUNDBAR -> Triple(-14f, 10f, 0f)
            Kind.CAR -> Triple(-20f, 6f, 8f)
            Kind.HEADPHONES -> Triple(-18f, 6f, 0f)
            else -> Triple(-20f, 8f, 0f)
        }
        return BudPose(cx, cy, size, yaw = yaw, pitch = pitch, roll = roll)
    }

    /** Draws one piece: a bud (side ±1) or the single device (side 0). */
    private fun piece(c: Canvas, p: BudPose, side: Int, t: Float = -1f, pulse: Float = 0f) {
        if (side != 0) art.draw(c, p, side) else product.draw(c, kind, p, t, pulse)
    }

    /** Contact shadow under a piece at rest centre (x, y). */
    private fun shadowUnder(c: Canvas, side: Int, x: Float, y: Float, lift: Float, strength: Float) {
        if (side != 0) { art.shadow(c, x, y + budSize * 0.55f, budSize, lift, strength); return }
        val w = budSize * product.aspect(kind) * 0.9f
        art.shadow(c, x, y + budSize * 0.5f + dp(6), w / 0.55f, lift, strength)
    }

    // ---------- the flight layer ----------

    private inner class ScrimView : View(ctx) {
        var level = 0f
            set(v) { field = v; invalidate() }
        private val p = Paint()
        private var shaderFor = 0

        override fun onDraw(c: Canvas) {
            if (level <= 0f) return
            val key = width * 31 + height
            if (key != shaderFor) {
                shaderFor = key
                p.shader = RadialGradient(width / 2f, height / 2f, hypot(width / 2f, height / 2f),
                    intArrayOf(Color.argb(70, 0, 0, 0), Color.argb(150, 0, 0, 0)), null, Shader.TileMode.CLAMP)
            }
            p.alpha = (level * 255).toInt()
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), p)
        }
    }

    private inner class StageView : View(ctx) {
        var time = 0f
        var exitTime = -1f
        var handedOff = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sparks = List(26) { Spark() }

        init { isClickable = false; isFocusable = false }

        // Inside the Lovely card this layer must not make the card taller.
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            fun size(spec: Int) = if (MeasureSpec.getMode(spec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(spec) else 0
            setMeasuredDimension(size(widthMeasureSpec), size(heightMeasureSpec))
        }

        private inner class Spark {
            val angle = Random.nextFloat() * 2 * PI.toFloat()
            val speed = 260 + Random.nextFloat() * 380
            val size = 1.6f + Random.nextFloat() * 2.2f
            val delay = Random.nextFloat() * 90f
            val white = Random.nextFloat() < 0.4f
        }

        override fun onDraw(c: Canvas) {
            val (tx, ty) = stageSlot()
            if (exitTime >= 0f) { drawExit(c, exitTime, tx, ty); return }
            val t = time
            val landed = LAND_AT + if (pair) RIGHT_DELAY else 0f
            if (t >= landed) {
                for (i in 0 until 3) {
                    val rt = (t - landed - i * 120f) / 900f
                    if (rt <= 0f || rt >= 1f) continue
                    val e = outCubic(rt)
                    p.style = Paint.Style.STROKE
                    p.strokeWidth = dp(3.2f * (1 - rt) + 0.8f)
                    p.color = if (i == 1) cTertiary else cAccent
                    p.alpha = ((1 - rt).pow(2) * 200).toInt()
                    c.drawCircle(tx, ty, budSize * 0.5f + e * min(width, height) * (if (mini) 0.6f else 0.5f), p)
                }
                p.style = Paint.Style.FILL
                for (s in sparks) {
                    val st = (t - landed - s.delay) / 950f
                    if (st <= 0f || st >= 1f) continue
                    val travel = dp(s.speed) * (1 - exp(-3.2f * st)) / 3.2f * (if (mini) 0.55f else 1f)
                    val x = tx + cos(s.angle) * (budSize * 0.45f + travel)
                    val y = ty + sin(s.angle) * (budSize * 0.3f + travel) + dp(200) * st * st
                    p.color = if (s.white) Color.WHITE else cAccent
                    p.alpha = ((1 - st) * 255).toInt()
                    c.drawCircle(x, y, dp(s.size) * (1 - st * 0.6f), p)
                }
            }
            if (handedOff) return
            for (side in pieces) {
                val tt = t - (if (side > 0) RIGHT_DELAY else 0f)
                if (tt < 0f) continue
                val near = ((tt - FLIGHT_MS * 0.55f) / (FLIGHT_MS * 0.45f)).coerceIn(0f, 1f)
                if (near > 0f) shadowUnder(c, side, tx + side * gap, ty, 1f - near, near)
                // Motion trail: the buds, and the fast single devices (dark trails on others just look smudgy).
                val trail = pair || kind == Kind.MOUSE || kind == Kind.CAR
                if (trail && tt < FLIGHT_MS * 0.85f) for (g in 3 downTo 1) {
                    val gt = tt - g * 30f
                    if (gt >= 0f) piece(c, pose(side, gt, tx, ty).copy(alpha = 0.2f / g), side)
                }
                val hit = if (tt >= FLIGHT_MS) (1f - (tt - FLIGHT_MS) / 600f).coerceIn(0f, 1f) else 0f
                piece(c, pose(side, tt, tx, ty), side, if (tt >= FLIGHT_MS) tt - FLIGHT_MS else -1f, hit)
            }
        }

        fun pose(side: Int, t: Float, tx: Float, ty: Float) = if (side == 0) single(t, tx, ty) else flight(side, t, tx, ty)

        /** The single device's fly-in: a different move for each kind, all landing at [FLIGHT_MS]. */
        fun single(t: Float, tx: Float, ty: Float): BudPose {
            val r = rest(0, tx, ty)
            if (t >= FLIGHT_MS) {
                val lt = ((t - FLIGHT_MS) / 520f).coerceIn(0f, 1f)
                val amp = 0.09f * exp(-5f * lt) * cos(2 * PI.toFloat() * 2.2f * lt)
                return r.copy(squashX = 1 + amp, squashY = 1 - amp)
            }
            val w = width.toFloat(); val h = height.toFloat()
            val u = (t / FLIGHT_MS).coerceIn(0f, 1f)
            return when (kind) {
                Kind.SPEAKER -> {
                    // Lobbed in from the lower left, then two hops with a squash on each landing.
                    if (u < 0.55f) {
                        val v = u / 0.55f
                        val x = lerp(-0.25f * w, r.x, outCubic(v))
                        val y = lerp(1.1f * h, r.y, v) - 0.55f * h * 4 * v * (1 - v)
                        r.copy(x = x, y = y, size = r.size * (0.55f + 0.45f * v), roll = r.roll - 360f * (1 - v), yaw = r.yaw + 180f * (1 - v))
                    } else {
                        val v = (u - 0.55f) / 0.45f
                        val (p, hop) = if (v < 0.62f) (v / 0.62f) to 0.16f else ((v - 0.62f) / 0.38f) to 0.05f
                        val squash = (0.16f * (1f - min(p, 1f - p) * 7f)).coerceAtLeast(0f) * (hop / 0.16f)
                        r.copy(y = r.y - hop * h * 4 * p * (1 - p), squashX = 1 + squash, squashY = 1 - squash)
                    }
                }
                Kind.SOUNDBAR -> r.copy(
                    x = lerp(1.3f * w, r.x, outBack(u)),
                    y = r.y - sin(PI.toFloat() * u) * 0.08f * h,
                    yaw = r.yaw + 720f * (1 - outCubic(u)),
                    size = r.size * (0.7f + 0.3f * outCubic(u)),
                )
                Kind.KEYBOARD -> {
                    // Falls from above standing up, tips back onto the desk, one small bounce.
                    val v = (u / 0.72f).coerceAtMost(1f)
                    val y = if (u < 0.72f) lerp(-0.3f * h, r.y, v * v) else { val p = (u - 0.72f) / 0.28f; r.y - 0.06f * h * 4 * p * (1 - p) }
                    r.copy(
                        x = r.x + 0.1f * w * sin(2 * PI.toFloat() * u) * (1 - u),
                        y = y,
                        pitch = lerp(-15f, r.pitch, outCubic(u)),
                        roll = r.roll + 25f * sin(3 * PI.toFloat() * u) * (1 - u),
                        size = r.size * (0.8f + 0.2f * u),
                    )
                }
                Kind.MOUSE -> {
                    // A cursor-like dash from the top left with a wiggle that dies out, heading along the path.
                    val pu = outCubic(u)
                    fun at(q: Float) = bez(-0.2f * w, 0.35f * w, 0.95f * w, r.x, q) to bez(0.1f * h, -0.15f * h, 0.45f * h, r.y, q)
                    val (x, y) = at(pu)
                    val (x2, y2) = at((pu + 0.02f).coerceAtMost(1f))
                    val wig = 0.07f * h * sin(6 * PI.toFloat() * u) * (1 - u)
                    val heading = Math.toDegrees(atan2((y2 - y).toDouble(), (x2 - x).toDouble())).toFloat() + 90f
                    r.copy(x = x, y = y + wig, roll = lerp(heading, r.roll, outQuart(u)), size = r.size * (0.5f + 0.5f * outBack(u)))
                }
                Kind.CAR -> {
                    // Drives in from the left over a bumpy road, overshoots as it brakes and tips forward.
                    val bump = abs(sin(u * PI.toFloat() * 9f)) * 0.025f * h * (1 - u)
                    val brake = if (u > 0.7f) -12f * sin(PI.toFloat() * (u - 0.7f) / 0.3f) else 0f
                    r.copy(x = lerp(-0.3f * w, r.x, outBack(u)), y = r.y - bump, roll = r.roll + brake)
                }
                Kind.HEADPHONES -> r.copy(
                    y = lerp(-0.35f * h, r.y, outBack(u)),
                    yaw = r.yaw + 720f * (1 - outCubic(u)),
                    size = r.size * (0.4f + 0.6f * outCubic(u)),
                )
                Kind.GAMEPAD -> r.copy(
                    x = bez(1.2f * w, 0.95f * w, r.x + 0.15f * w, r.x, outCubic(u)),
                    y = bez(1.1f * h, 0.15f * h, r.y - 0.15f * h, r.y, outCubic(u)),
                    roll = r.roll - 720f * (1 - outQuart(u)),
                    pitch = r.pitch + 50f * sin(PI.toFloat() * u) * (1 - u),
                    size = r.size * (0.4f + 0.6f * outBack(u)),
                )
                else -> r.copy(
                    size = r.size * max(0.02f, outBack(u)),
                    yaw = r.yaw + 360f * (1 - outCubic(u)),
                    alpha = (u * 4f).coerceAtMost(1f),
                )
            }
        }

        /**
         * One bud's fly-in. The left bud starts off the right edge (low) and the right bud off the left
         * edge (high), so they cross; each spins twice and flips once before settling into its rest pose.
         */
        fun flight(side: Int, t: Float, tx: Float, ty: Float): BudPose {
            val r = rest(side, tx, ty)
            if (t >= FLIGHT_MS) {
                val lt = ((t - FLIGHT_MS) / 520f).coerceIn(0f, 1f)
                val amp = 0.11f * exp(-5f * lt) * cos(2 * PI.toFloat() * 2.2f * lt)
                val tilt = 8f * exp(-4f * lt) * sin(2 * PI.toFloat() * 1.7f * lt)
                return r.copy(roll = r.roll + side * tilt, squashX = 1 + amp, squashY = 1 - amp)
            }
            val w = width.toFloat(); val h = height.toFloat()
            val u = (t / FLIGHT_MS).coerceIn(0f, 1f)
            val pu = outCubic(u)
            val from = -side * FROM // left (-1) enters from the right (+), right (+1) from the left (-)
            val x0 = if (from > 0) 1.22f * w else -0.22f * w
            val y0 = if (side < 0) 0.80f * h else 0.18f * h
            val x1 = if (from > 0) 0.78f * w else 0.22f * w
            val y1 = if (side < 0) 1.02f * h else -0.02f * h
            val x2 = r.x - from * 0.18f * w
            val y2 = r.y + (if (side < 0) -0.22f else 0.22f) * h
            val x = bez(x0, x1, x2, r.x, pu)
            val y = bez(y0, y1, y2, r.y, pu)
            val roll = r.roll + from * 720f * (1 - outQuart(u))
            val yaw = r.yaw - side * 360f * (1 - outCubic(u))
            val pitch = r.pitch + 40f * sin(PI.toFloat() * u) * (1 - u)
            val scale = 0.34f + 0.66f * outBack(u)
            return BudPose(x, y, budSize * scale, yaw = yaw, pitch = pitch, roll = roll)
        }

        private fun drawExit(c: Canvas, t: Float, tx: Float, ty: Float) {
            val w = width.toFloat(); val h = height.toFloat()
            if (!pair) {
                // Leave the way it came: the fly-in played backwards, fading at the very end.
                val e = inCubic((t / EXIT_END).coerceIn(0f, 1f))
                val p = single(FLIGHT_MS * (1f - e), tx, ty)
                piece(c, p.copy(alpha = p.alpha * (1f - ((e - 0.7f) / 0.3f).coerceIn(0f, 1f))), 0)
                return
            }
            for (side in SIDES) {
                val r = idle.pose(side) ?: rest(side, tx, ty)
                val start = rest(side, tx, ty).let { r.copy(x = it.x, y = it.y) }
                val tt = t - (if (side > 0) 60f else 0f)
                val u = ((tt - 180f) / (EXIT_END - 240f)).coerceIn(0f, 1f)
                val e = inCubic(u)
                val lift = outCubic((tt / 180f).coerceIn(0f, 1f))
                // Back out the way they came: the left bud to the right edge, the right bud to the left.
                val to = -side * FROM
                val x = bez(start.x, start.x - to * 0.06f * w, if (to > 0) 0.85f * w else 0.15f * w, if (to > 0) 1.25f * w else -0.25f * w, e)
                val y = bez(start.y - lift * dp(10), start.y + 0.1f * h, if (side < 0) 0.75f * h else 0.25f * h, if (side < 0) 0.85f * h else 0.15f * h, e)
                art.draw(c, BudPose(x, y, start.size * (1f - 0.45f * e), yaw = start.yaw + side * 360f * e, pitch = start.pitch, roll = start.roll + to * 540f * e, alpha = 1f - 0.3f * e), side)
            }
        }
    }

    // ---------- inside the card ----------

    /** The landed buds: gentle alternating bob and sway; tap to spin them. */
    private inner class IdleBudsView : View(ctx) {
        var visible = false
            set(v) {
                if (v && !field) { start = now(); postInvalidateOnAnimation() }
                field = v
                invalidate()
            }
        private var start = 0L
        private var spinAt = -1L
        private var lastL: BudPose? = null
        private var lastR: BudPose? = null

        init {
            isClickable = true
            setOnClickListener { spinAt = now(); actions.touched(); invalidate() }
            contentDescription = kind.label
        }

        fun pose(side: Int) = when { side < 0 -> lastL; side > 0 -> lastR; else -> null }

        override fun onDraw(c: Canvas) {
            if (!visible) return
            val tau = (now() - start).toFloat()
            val cx = width / 2f; val cy = height * 0.5f
            var spin = 0f
            if (spinAt > 0) {
                val st = ((now() - spinAt) / 900f).coerceIn(0f, 1f)
                spin = 360f * outCubic(st)
                if (st >= 1f) spinAt = -1
            }
            if (!pair) { idleSingle(c, tau, cx, cy, spin); postInvalidateOnAnimation(); return }
            // A soft disc behind the buds so white earbuds read on a light card too.
            backdrop(c, cx, cy)
            for (side in SIDES) {
                val ph = if (side < 0) 0f else PI.toFloat()
                val bob = dp(3) * sin(2 * PI.toFloat() * tau / 3200f + ph)
                val r = rest(side, cx, cy)
                art.shadow(c, r.x, cy + budSize * 0.55f, budSize, (bob / dp(3) + 1f) * 0.08f)
                val pose = r.copy(
                    y = r.y + bob,
                    yaw = r.yaw + side * 10f * sin(2 * PI.toFloat() * tau / 5400f + ph) + side * spin,
                    roll = r.roll + 2.5f * sin(2 * PI.toFloat() * tau / 6100f + ph),
                )
                if (side < 0) lastL = pose else lastR = pose
                art.draw(c, pose, side)
            }
            postInvalidateOnAnimation()
        }

        private fun idleSingle(c: Canvas, tau: Float, cx: Float, cy: Float, spin: Float) {
            val r = rest(0, cx, cy)
            backdrop(c, cx, cy, budSize * product.aspect(kind) * 0.62f, budSize * 0.72f)
            // Keyboards stay put on the desk; everything else floats a little.
            val float = if (kind == Kind.KEYBOARD) 0.25f else 1f
            val bob = dp(3) * sin(2 * PI.toFloat() * tau / 3200f) * float
            shadowUnder(c, 0, r.x, cy, (bob / dp(3) + 1f) * 0.08f * float, 1f)
            val pose = r.copy(
                y = r.y + bob,
                yaw = r.yaw + 8f * sin(2 * PI.toFloat() * tau / 5400f) * float + spin,
                roll = r.roll + 2f * sin(2 * PI.toFloat() * tau / 6100f) * float,
            )
            val hit = if (spinAt > 0) 1f - ((now() - spinAt) / 600f).coerceIn(0f, 1f) else 0f
            product.fx(c, kind, pose, tau)
            piece(c, pose, 0, tau + 1f, hit)
        }

    }

    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private var backFor = 0f

    private fun backdrop(c: Canvas, cx: Float, cy: Float, rx: Float = budSize * 0.72f, ry: Float = rx) {
        if (backFor != cx * 7 + cy) {
            backFor = cx * 7 + cy
            back.shader = RadialGradient(cx, cy, rx, intArrayOf(cVariant, cVariant, blendA(cVariant, 0f)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        // Stretch the round glow into an ellipse for wide devices.
        c.save()
        c.scale(1f, ry / rx, cx, cy)
        c.drawCircle(cx, cy, rx, back)
        c.restore()
    }

    /** Clips its children to a growing circle centred on the buds, and drags down to dismiss. */
    private inner class CardFrame : FrameLayout(ctx) {
        var reveal = 1f
            set(v) { field = v; visibility = if (v <= 0f) View.INVISIBLE else View.VISIBLE; invalidate() }
        var revealX = 0f
        var revealY = 0f
        private val clip = Path()

        override fun draw(c: Canvas) {
            if (reveal >= 1f) { super.draw(c); return }
            val far = max(max(hypot(revealX, revealY), hypot(width - revealX, revealY)), max(hypot(revealX, height - revealY), hypot(width - revealX, height - revealY)))
            clip.rewind()
            clip.addCircle(revealX, revealY, far * reveal, Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            super.draw(c)
            c.restore()
        }

        // ----- swipe down to dismiss -----
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var downY = 0f
        private var dragging = false
        private var vt: VelocityTracker? = null

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            if (phase != Phase.SETTLED) return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY; dragging = false }
                MotionEvent.ACTION_MOVE -> if (ev.rawY - downY > slop) { dragging = true; downY = ev.rawY - translationY; return true }
            }
            return false
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (phase != Phase.SETTLED) return false
            if (vt == null) vt = VelocityTracker.obtain()
            vt?.addMovement(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = ev.rawY - translationY; return true }
                MotionEvent.ACTION_MOVE -> {
                    val d = ev.rawY - downY
                    // Follow the finger downwards; resist going up.
                    translationY = if (d > 0) d else d * 0.25f
                    alpha = 1f - (translationY / (height * 1.4f)).coerceIn(0f, 0.6f)
                    actions.touched()
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    vt?.computeCurrentVelocity(1000)
                    val vy = vt?.yVelocity ?: 0f
                    vt?.recycle(); vt = null
                    if (translationY > dp(70) || vy > dp(900)) {
                        dragged = true
                        actions.close()
                    } else {
                        animate().translationY(0f).alpha(1f).setDuration(220).start()
                    }
                    return true
                }
            }
            return true
        }
    }

    private var dragged = false

    /** Battery for the pair: level bar, big number, bolt while charging, red when low. */
    private inner class BatteryBar : View(ctx) {
        private var level: Int? = null
        private var charging = false
        private var startAt = -1L
        private var from = 0f
        private var shown = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val bud = ctx.getDrawable(kind.icon)!!.mutate()
        private val bolt = ctx.getDrawable(R.drawable.ic_bolt)!!.mutate()

        fun set(l: Int?, ch: Boolean) {
            if (l != level) { from = shown; if (startAt >= 0) startAt = now() }
            level = l; charging = ch
            contentDescription = l?.let { "Battery $it percent" + if (ch) ", charging" else "" } ?: kind.powerNote.ifEmpty { "Battery unknown" }
            invalidate()
        }

        fun start() { if (startAt < 0) { startAt = now(); invalidate() } }

        override fun onDraw(c: Canvas) {
            val target = (level ?: 0).toFloat()
            var animating = false
            if (startAt >= 0) {
                val tt = ((now() - startAt) / 1000f).coerceIn(0f, 1f)
                shown = from + (target - from) * outCubic(tt)
                animating = tt < 1f
            }
            val low = level != null && level!! <= 20 && !charging
            val h = height.toFloat(); val w = width.toFloat()
            // Background pill.
            r.set(0f, 0f, w, h)
            p.style = Paint.Style.FILL; p.color = cVariant
            c.drawRoundRect(r, h / 2, h / 2, p)
            // Level fill.
            if (level != null && shown > 0.5f) {
                val fw = max(h, w * shown / 100f)
                r.set(0f, 0f, fw, h)
                p.color = blendA(if (low) cRed else cAccent, if (night) 0.45f else 0.30f)
                c.drawRoundRect(r, h / 2, h / 2, p)
            }
            // Earbuds icon, label and number.
            val isz = (h * 0.5f).toInt()
            val ix = (h * 0.42f).toInt(); val iy = ((h - isz) / 2).toInt()
            bud.setTint(cText); bud.setBounds(ix, iy, ix + isz, iy + isz); bud.draw(c)
            p.color = cText; p.textAlign = Paint.Align.LEFT; p.textSize = dp(15); p.typeface = Typeface.DEFAULT
            // Mains- or car-powered devices have no battery to show.
            val note = level == null && !kind.hasBattery
            c.drawText(if (note) kind.powerNote else "Battery", ix + isz + dp(10), h / 2 - (p.descent() + p.ascent()) / 2, p)
            p.textAlign = Paint.Align.RIGHT; p.textSize = dp(22); p.typeface = Typeface.DEFAULT_BOLD
            val label = if (note) "" else if (level == null) "–" else "${shown.toInt()}%"
            val tx = w - h * 0.45f
            c.drawText(label, tx, h / 2 - (p.descent() + p.ascent()) / 2, p)
            if (charging) {
                val s = (dp(18) * (0.85f + 0.15f * sin(now() / 160f))).toInt()
                val bx = (tx - p.measureText(label) - dp(14)).toInt()
                bolt.setTint(cTertiary); bolt.setBounds(bx - s / 2, (h / 2 - s / 2).toInt(), bx + s / 2, (h / 2 + s / 2).toInt()); bolt.draw(c)
                animating = true
            }
            if (animating) postInvalidateOnAnimation()
        }
    }

    // ---------- helpers ----------

    private fun text(sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun pill(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(100) }

    private fun blendA(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))

    companion object {
        private val SIDES = intArrayOf(-1, 1)
        private val ONE = intArrayOf(0)
        /** Which edge each bud enters from: +1 = the left bud comes from the right edge and the right bud from the left (they cross). Set to -1 for each from its own side. */
        const val FROM = 1
        const val REST_YAW = 32f
        const val REST_PITCH = 6f
        const val REST_ROLL = 8f
        const val FLIGHT_MS = 1500f
        const val RIGHT_DELAY = 90f
        const val LAND_AT = 1500f
        const val REVEAL_AT = 1620f
        const val CONTENT_AT = 1800f
        const val HANDOFF_AT = 2150f
        const val INTRO_END = 2500L
        const val EXIT_END = 900f
        const val SLIDE_MS = 320f

        fun ease(x: Float) = x * x * (3 - 2 * x)
        fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun outCubic(x: Float) = 1 - (1 - x).pow(3)
        fun inCubic(x: Float) = x * x * x
        fun outQuart(x: Float) = 1 - (1 - x).pow(4)
        fun outBack(x: Float): Float {
            val c1 = 1.70158f; val c3 = c1 + 1
            return 1 + c3 * (x - 1).pow(3) + c1 * (x - 1).pow(2)
        }
        fun bez(a: Float, b: Float, c: Float, d: Float, t: Float): Float {
            val u = 1 - t
            return u * u * u * a + 3 * u * u * t * b + 3 * u * t * t * c + t * t * t * d
        }
    }
}
