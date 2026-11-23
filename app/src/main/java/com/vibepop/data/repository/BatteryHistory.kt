package com.vibepop.data.repository

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Records only actual reported battery levels, never estimates. */
class BatteryHistory(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "battery_history.db", null, 1) {
    private val app = context.applicationContext
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE readings (id INTEGER PRIMARY KEY, address TEXT NOT NULL, name TEXT NOT NULL, level INTEGER NOT NULL, recorded_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX readings_time ON readings(recorded_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun record(address: String, name: String, level: Int) {
        if (address.isBlank() || level !in 0..100) return
        val now = System.currentTimeMillis()
        val db = writableDatabase
        val recentlyRecorded = db.query("readings", arrayOf("level", "recorded_at"),
            "address = ?", arrayOf(address), null, null, "recorded_at DESC", "1").use {
            it.moveToFirst() && it.getInt(0) == level && now - it.getLong(1) < 60_000
        }
        if (recentlyRecorded) return
        db.insert("readings", null, ContentValues().apply {
            put("address", address)
            put("name", name)
            put("level", level)
            put("recorded_at", now)
        })
        db.delete("readings", "recorded_at < ?", arrayOf((now - 30L * 24 * 60 * 60 * 1000).toString()))
        com.vibepop.ui.BatteryWidget.refresh(app, this)
    }

    data class Reading(val address: String, val name: String, val level: Int, val timestamp: Long)

    fun latestDevices(): List<Reading> = readableDatabase.rawQuery(
        "SELECT address,name,level,recorded_at FROM readings WHERE recorded_at >= ? AND id IN (SELECT MAX(id) FROM readings GROUP BY address) ORDER BY recorded_at DESC LIMIT 4",
        arrayOf((System.currentTimeMillis() - 30L * 86_400_000).toString())).use { c ->
        buildList { while (c.moveToNext()) add(Reading(c.getString(0),c.getString(1),c.getInt(2),c.getLong(3))) }
    }

    fun recent(address: String? = null): List<Reading> {
        val rows = mutableListOf<Reading>()
        val oldest = (System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000).toString()
        readableDatabase.query("readings", arrayOf("address", "name", "level", "recorded_at"),
            if (address == null) "recorded_at >= ?" else "recorded_at >= ? AND address = ?",
            if (address == null) arrayOf(oldest) else arrayOf(oldest,address), null, null, "recorded_at DESC, id DESC", "500").use { cursor ->
            while (cursor.moveToNext()) rows.add(Reading(cursor.getString(0), cursor.getString(1),
                cursor.getInt(2), cursor.getLong(3)))
        }
        return rows
    }

    /** At most 288 five-minute averages; the time axis always spans a full day. */
    fun day(address: String, since: Long): List<Pair<Long,Float>> = readableDatabase.rawQuery(
        "SELECT MAX(recorded_at),AVG(level) FROM readings WHERE address=? AND recorded_at BETWEEN ? AND ? GROUP BY ((recorded_at - ?) / 300000) ORDER BY MAX(recorded_at)",
        arrayOf(address,since.toString(),(since+86_400_000).toString(),since.toString())
    ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0) to c.getFloat(1)) } }
}
