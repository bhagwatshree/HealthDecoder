package com.example.medicalscanner.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.medicalscanner.model.MedLogEntry
import com.example.medicalscanner.model.MedicalReport
import com.example.medicalscanner.model.PendingTest

/**
 * On-device SQLite store for all medical records (replaces the reports.json /
 * pending_tests.json / med_logs.json files). The database file lives inside the
 * records/ folder so BackupManager's zip-the-folder snapshots keep working unchanged.
 *
 * Opened in TRUNCATE journal mode: the single .db file is always complete on disk after
 * each committed write, so a zip taken between writes is a consistent snapshot.
 */
@Database(
    entities = [MedicalReport::class, ReportFts::class, PendingTest::class, MedLogEntry::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class MedicalDatabase : RoomDatabase() {
    abstract fun reportDao(): ReportDao
    abstract fun pendingTestDao(): PendingTestDao
    abstract fun medLogDao(): MedLogDao

    companion object {
        /** v2: per-page SHA-256 hashes on reports, for duplicate-scan detection. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE reports ADD COLUMN pageHashes TEXT NOT NULL DEFAULT '[]'")
            }
        }
    }
}
