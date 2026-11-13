package org.caniorebirth.android

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class HistoryDb(context: Context) : SQLiteOpenHelper(context, "canion_history.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE samples (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            ts INTEGER NOT NULL,
            soc REAL,
            packVoltage REAL,
            packCurrent REAL,
            motorRpm INTEGER,
            gear TEXT,
            odometer REAL
        )""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun insert(soc: Double?, v: Double?, a: Double?, rpm: Int?, gear: String?, odo: Double?) {
        val db = writableDatabase
        db.execSQL("INSERT INTO samples(ts,soc,packVoltage,packCurrent,motorRpm,gear,odometer) VALUES(?,?,?,?,?,?,?)",
            arrayOf(System.currentTimeMillis(), soc, v, a, rpm, gear, odo))
    }

    fun recent(limit: Int = 50): List<String> {
        val out = mutableListOf<String>()
        val c = readableDatabase.rawQuery("SELECT ts,soc,packVoltage,packCurrent,gear FROM samples ORDER BY id DESC LIMIT $limit", null)
        while (c.moveToNext()) {
            out.add("${c.getLong(0)} SOC=${c.getDouble(1)} V=${c.getDouble(2)} A=${c.getDouble(3)} gear=${c.getString(4)}")
        }
        c.close()
        return out
    }
}
