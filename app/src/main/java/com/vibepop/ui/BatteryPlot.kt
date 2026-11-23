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
    init { contentDescription = context.getString(R.string.chart_24h) }
    override fun onDraw(canvas: Canvas) {
        val l = 42 * scale; val r = width - 16 * scale
        val t = 14 * scale; val b = height - 22 * scale
        ink.textSize = 11 * scale; ink.strokeWidth = scale
        ink.color = ContextCompat.getColor(context, R.color.text_secondary)
        for (percent in listOf(0,50,100)) {
            val y = b - percent * (b-t)/100
            canvas.drawText("$percent%",2*scale,y+4*scale,ink)
            canvas.drawLine(l,y,r,y,ink)
        }
        val clock = android.text.format.DateFormat.getTimeFormat(context)
        canvas.drawText(clock.format(java.util.Date(since)),l,height-4*scale,ink)
        val end = clock.format(java.util.Date(since+86_400_000)); canvas.drawText(end,r-ink.measureText(end),height-4*scale,ink)
        ink.color = ContextCompat.getColor(context,R.color.accent); ink.strokeWidth = 2*scale
        var px = 0f; var py = 0f; var prev = 0L
        for ((ts, level) in points) {
            val x = l + (r-l) * ((ts-since)/86_400_000f); val y = b - level*(b-t)/100
            if (ts-prev <= 3_600_000L) canvas.drawLine(px,py,x,y,ink)
            canvas.drawCircle(x,y,2.5f*scale,ink); px=x; py=y; prev=ts
        }
    }
}
