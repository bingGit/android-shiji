package com.bing.androidvoiceflow.capture.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SingleCaptureEntity::class,
        ReadingSessionEntity::class,
        ReadingBlockEntity::class,
        OutboundCaptureRequestEntity::class,
        CaptureRetentionEntity::class,
        CaptureTagEntity::class,
        CaptureTagRefEntity::class
    ],
    version = 3,
    exportSchema = true
)
@TypeConverters(CaptureTypeConverters::class)
internal abstract class CaptureDatabase : RoomDatabase() {
    abstract fun singleCaptureDao(): SingleCaptureDao
    abstract fun readingSessionDao(): ReadingSessionDao
    abstract fun captureRecordDao(): CaptureRecordDao
    abstract fun outboundCaptureRequestDao(): OutboundCaptureRequestDao
    abstract fun captureRetentionDao(): CaptureRetentionDao
    abstract fun captureTagDao(): CaptureTagDao
    abstract fun captureTagRefDao(): CaptureTagRefDao

    companion object {
        @Volatile
        private var instance: CaptureDatabase? = null

        fun getInstance(context: Context): CaptureDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CaptureDatabase::class.java,
                    "reading_capture.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .addCallback(SEED_DEFAULT_TAGS_CALLBACK)
                    .build()
                    .also { instance = it }
            }

        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS capture_retention (
                        origin_type TEXT NOT NULL,
                        origin_id TEXT NOT NULL,
                        retained_at INTEGER NOT NULL,
                        delete_after INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        PRIMARY KEY(origin_type, origin_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_capture_retention_delete_after " +
                        "ON capture_retention(delete_after)"
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                createTagTables(db)
                seedDefaultTags(db)
            }
        }

        private val SEED_DEFAULT_TAGS_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                seedDefaultTags(db)
            }
        }

        private fun createTagTables(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS capture_tags (
                    tag_id TEXT NOT NULL PRIMARY KEY,
                    name TEXT NOT NULL,
                    normalized_name TEXT NOT NULL,
                    is_pinned INTEGER NOT NULL,
                    sort_order INTEGER NOT NULL,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_capture_tags_normalized_name ON capture_tags(normalized_name)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS capture_tag_refs (
                    origin_type TEXT NOT NULL,
                    origin_id TEXT NOT NULL,
                    tag_id TEXT NOT NULL,
                    tag_name_snapshot TEXT NOT NULL,
                    added_at INTEGER NOT NULL,
                    PRIMARY KEY(origin_type, origin_id, tag_id)
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_tag_refs_origin_type_origin_id ON capture_tag_refs(origin_type, origin_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_capture_tag_refs_tag_id ON capture_tag_refs(tag_id)")
        }

        private fun seedDefaultTags(db: SupportSQLiteDatabase) {
            val now = System.currentTimeMillis()
            listOf(
                arrayOf("tag_todo", "待办", "待办", 1, 0),
                arrayOf("tag_inspiration", "灵感", "灵感", 0, 1),
                arrayOf("tag_work", "工作", "工作", 1, 2),
                arrayOf("tag_life", "生活", "生活", 1, 3)
            ).forEach { row ->
                db.execSQL(
                    "INSERT OR IGNORE INTO capture_tags(tag_id, name, normalized_name, is_pinned, sort_order, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    arrayOf(row[0], row[1], row[2], row[3], row[4], now, now)
                )
            }
        }
    }
}
