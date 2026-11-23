package com.vibepop.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.pow
import kotlin.math.sin

/**
 * Cross-screen earbud flight adapted from BudPop's FlyCard flight choreography.
 * BudPop Copyright (c) 2026 Sanjam (AweSanjam), MIT; see THIRD_PARTY_NOTICES.md.
 */
class BudFlightView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var start = 0L

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start = SystemClock.uptimeMillis()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val elapsed = SystemClock.uptimeMillis() - start
        val size = minOf(width, height) * .18f
        for (side in intArrayOf(-1, 1)) {
            val u = ((elapsed - if (side > 0) 90 else 0) / 1500f).coerceIn(0f, 1f)
            val eased = 1f - (1f - u).pow(3)
            val from = -side
            val x0 = if (from > 0) width * 1.22f else width * -.22f
            val y0 = height * if (side < 0) .80f else .18f
            val x1 = width * if (from > 0) .78f else .22f
            val y1 = height * if (side < 0) 1.02f else -.02f
            val x3 = width * .5f + side * size * .55f
            val y3 = height * .55f
            fun bez(a: Float, b: Float, c: Float, d: Float): Float {
                val t = eased
                return (1-t).pow(3)*a + 3*(1-t).pow(2)*t*b + 3*(1-t)*t*t*c + t*t*t*d
            }
            val x = bez(x0, x1, x3 - from * .18f * width, x3)
            val y = bez(y0, y1, y3 + (if (side < 0) -.22f else .22f) * height, y3)
            canvas.save()
            canvas.translate(x, y + if (u >= 1f) sin(elapsed / 500f + side) * size * .025f else 0f)
            canvas.rotate(from * 720f * (1f - (1f - (1f-u).pow(4))))
            canvas.scale(.34f + .66f * eased, .34f + .66f * eased)
            paint.color = Color.WHITE
            canvas.drawOval(-size*.42f, -size*.38f, size*.42f, size*.38f, paint)
            canvas.drawRoundRect(-size*.13f, 0f, size*.13f, size*.92f, size*.12f, size*.12f, paint)
            paint.color = Color.rgb(95, 100, 110)
            canvas.drawOval(side*size*.13f-size*.07f, -size*.09f, side*size*.13f+size*.07f, size*.09f, paint)
            canvas.restore()
        }
        if (isAttachedToWindow && visibility == VISIBLE) postInvalidateOnAnimation()
    }
}
