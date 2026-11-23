package app.budpop.popup

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.vibepop.R
import app.budpop.core.BudState
import app.budpop.core.Kind

/**
 * Bare minimum: one flat card at the bottom, icons only, no animation.
 * Device icon · name · battery icon and % (or a power note) · close. Swipe down or tap the cross to dismiss.
 */
@SuppressLint("ClickableViewAccessibility")
class BareCard(context: Context, private val actions: PopupActions, private val kind: Kind = Kind.EARBUDS) : PopupView {
    private val ctx = context
    private val dm = ctx.resources.displayMetrics
    private fun dp(v: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), dm)
    private fun dpi(v: Number) = dp(v).toInt()

    override val startsFullScreen = false
    override val gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
    override val offsetY = dpi(16)

    private val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val bg = if (night) Color.rgb(0x12, 0x12, 0x14) else Color.WHITE
    private val fg = if (night) Color.rgb(0xEE, 0xEE, 0xF0) else Color.rgb(0x16, 0x16, 0x18)
    private val muted = if (night) Color.rgb(0x9A, 0x9A, 0xA2) else Color.rgb(0x66, 0x66, 0x6E)
    private val line = if (night) Color.rgb(0x2A, 0x2A, 0x2E) else Color.rgb(0xDD, 0xDD, 0xE2)

    private val name = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(fg); typeface = Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private val status = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f); setTextColor(muted); text = "Connected" }
    private val battery = BatteryIcon()
    private val batteryView = View(ctx).apply { background = battery }
    private val percent = TextView(ctx).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(fg); typeface = Typeface.DEFAULT_BOLD }

    private val card: LinearLayout
    override val root: View

    init {
        val wide = ctx.resources.configuration.screenWidthDp >= 600
        val width = if (wide) dpi(420) else dm.widthPixels - dpi(24)
        val icon = ImageView(ctx).apply { setImageResource(kind.icon); setColorFilter(fg); contentDescription = kind.label }
        val close = ImageView(ctx).apply {
            setImageResource(R.drawable.ic_close); setColorFilter(fg)
            scaleType = ImageView.ScaleType.CENTER; contentDescription = "Close"
            background = GradientDrawable().apply { setColor(bg); setStroke(dpi(1), line); cornerRadius = dp(10) }
            setOnClickListener { actions.close() }
        }
        card = object : LinearLayout(ctx) {
            private var downY = 0f
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) { downY = ev.rawY; actions.touched() }
                return ev.actionMasked == MotionEvent.ACTION_MOVE && ev.rawY - downY > dp(8)
            }
            override fun onTouchEvent(ev: MotionEvent): Boolean {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_MOVE -> translationY = (ev.rawY - downY).coerceAtLeast(0f)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        if (translationY > dp(48)) actions.close() else translationY = 0f
                }
                return true
            }
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(14), dpi(12), dpi(12), dpi(12))
            background = GradientDrawable().apply { setColor(bg); setStroke(dpi(1), line); cornerRadius = dp(14) }
            addView(icon, LinearLayout.LayoutParams(dpi(30), dpi(30)).apply { marginEnd = dpi(12) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(name); addView(status)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(batteryView, LinearLayout.LayoutParams(dpi(26), dpi(14)).apply { marginEnd = dpi(6) })
            addView(percent, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dpi(10) })
            addView(close, LinearLayout.LayoutParams(dpi(40), dpi(40)))
        }
        root = FrameLayout(ctx).apply { addView(card, FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT)) }
    }

    override fun bind(s: BudState) {
        name.text = s.name
        status.text = if (s.connected) "Connected" else "Connecting…"
        battery.level = s.battery
        battery.charging = s.charging && s.battery != null
        // Soundbars and car adapters run on mains/car power: say so instead of a blank battery.
        val note = s.battery == null && !kind.hasBattery
        batteryView.visibility = if (note) View.GONE else View.VISIBLE
        if (note) status.text = kind.powerNote
        percent.text = s.battery?.let { "$it%" } ?: if (note) "" else "–"
        percent.setTextColor(if (s.battery != null && s.battery <= 20 && !s.charging) 0xFFE57373.toInt() else fg)
    }

    override fun enter() {}
    override fun exit(done: () -> Unit) = done()

    /** Battery outline with a fill bar. */
    private inner class BatteryIcon : Drawable() {
        var level: Int? = null
            set(v) { field = v; invalidateSelf() }
        var charging = false
            set(v) { field = v; invalidateSelf() }
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun draw(c: Canvas) {
            val b = bounds
            val sw = dp(1.5f)
            val tip = b.width() * 0.1f
            r.set(b.left + sw / 2, b.top + sw / 2, b.right - tip - sw / 2, b.bottom - sw / 2)
            p.style = Paint.Style.STROKE; p.strokeWidth = sw; p.color = muted
            c.drawRoundRect(r, sw * 1.5f, sw * 1.5f, p)
            p.style = Paint.Style.FILL
            c.drawRect(r.right + sw / 2, b.exactCenterY() - b.height() * 0.18f, b.right.toFloat(), b.exactCenterY() + b.height() * 0.18f, p)
            val lv = level ?: return
            p.color = when {
                charging -> Color.rgb(0x66, 0xBB, 0x6A)
                lv <= 20 -> 0xFFE57373.toInt()
                else -> fg
            }
            c.drawRect(r.left + sw * 1.5f, r.top + sw * 1.5f, r.left + sw * 1.5f + (r.width() - sw * 3) * lv / 100f, r.bottom - sw * 1.5f, p)
        }

        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(colorFilter: ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
