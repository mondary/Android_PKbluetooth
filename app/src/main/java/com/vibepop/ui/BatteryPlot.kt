package com.vibepop.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import androidx.core.content.ContextCompat
import com.vibepop.R

internal class BatteryPlot(context: Context, private val since: Long, private val points: List<Pair<Long,Float>>) : View(context) {
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scale = resources.displayMetrics.density
    private val secondaryColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val clock = android.text.format.DateFormat.getTimeFormat(context)
    private val startLabel = clock.format(java.util.Date(since))
    private val endLabel = clock.format(java.util.Date(since + 86_400_000))

    init {
        contentDescription = context.getString(R.string.chart_24h)
        ink.textSize = 11 * scale
        ink.strokeWidth = scale
    }

    override fun onDraw(canvas: Canvas) {
        val l = 42 * scale; val r = width - 16 * scale
        val t = 14 * scale; val b = height - 22 * scale
        ink.color = secondaryColor
        for (percent in 0..100 step 50) {
            val y = b - percent * (b-t)/100
            canvas.drawText("$percent%",2*scale,y+4*scale,ink)
            canvas.drawLine(l,y,r,y,ink)
        }
        canvas.drawText(startLabel,l,height-4*scale,ink)
        canvas.drawText(endLabel,r-ink.measureText(endLabel),height-4*scale,ink)
        ink.color = accentColor; ink.strokeWidth = 2*scale
        var px = 0f; var py = 0f; var prev = 0L
        for ((ts, level) in points) {
            val x = l + (r-l) * ((ts-since)/86_400_000f); val y = b - level*(b-t)/100
            if (ts-prev <= 3_600_000L) canvas.drawLine(px,py,x,y,ink)
            canvas.drawCircle(x,y,2.5f*scale,ink); px=x; py=y; prev=ts
        }
    }
}
