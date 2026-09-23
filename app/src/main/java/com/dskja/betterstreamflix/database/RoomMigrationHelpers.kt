package com.dskja.betterstreamflix.database

import androidx.sqlite.db.SupportSQLiteDatabase

internal object RoomMigrationHelpers {

    fun addColumnIfMissing(
        db: SupportSQLiteDatabase,
        table: String,
        column: String,
        typeSql: String,
    ) {
        if (hasColumn(db, table, column)) return
        db.execSQL("ALTER TABLE `$table` ADD COLUMN `$column` $typeSql")
    }

    fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
        db.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            if (nameIndex < 0) return false
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == column) return true
            }
        }
        return false
    }
}
