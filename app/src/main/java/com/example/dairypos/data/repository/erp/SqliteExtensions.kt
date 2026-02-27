package com.example.dairypos.data.repository.erp

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

internal fun SQLiteDatabase.singleInt(sql: String, args: Array<String> = emptyArray()): Int =
    rawQuery(sql, args).use { c ->
        if (c.moveToFirst()) c.getInt(0)
        else error("Expected singleInt result for: $sql")
    }

internal inline fun SQLiteDatabase.forEach(
    sql: String,
    args: Array<String> = emptyArray(),
    action: (Cursor) -> Unit
) {
    rawQuery(sql, args).use { c ->
        while (c.moveToNext()) action(c)
    }
}

internal fun Cursor.optString(columnIndex: Int, defaultValue: String = ""): String {
    return this.getString(columnIndex) ?: defaultValue
}