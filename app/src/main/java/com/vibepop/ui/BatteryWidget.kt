package com.vibepop.ui

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.widget.RemoteViews
import com.vibepop.R
import com.vibepop.data.repository.BatteryHistory
import java.util.Date

class BatteryWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        io.execute {
            try { BatteryHistory(context).use { refresh(context, it) } }
            finally { pending.finish() }
        }
    }
    companion object {
        private val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        fun refresh(context: Context, history: BatteryHistory) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, BatteryWidget::class.java))
            if (ids.isEmpty()) return
            val clock = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT)
            val text = history.latestDevices().joinToString("\n") {
                "${it.name}  ·  ${it.level}%  ·  ${clock.format(Date(it.timestamp))}"
            }.ifEmpty { context.getString(R.string.history_empty) }
            val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val view = RemoteViews(context.packageName,R.layout.widget_battery).apply {
                setTextViewText(R.id.lastReadings, text); setOnClickPendingIntent(R.id.widgetRoot, open)
            }
            manager.updateAppWidget(ids, view)
        }
    }
}
