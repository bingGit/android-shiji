package com.bing.androidvoiceflow.capture.data

import android.database.Cursor
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class CaptureDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        CaptureDatabase::class.java
    )

    @Test
    fun migrateOneToTwoPreservesExistingCaptureAndCreatesRetentionTable() {
        helper.createDatabase(TEST_DB, 1).apply {
            execSQL(
                """
                INSERT INTO single_captures (
                    capture_id, capture_type, raw_text, source_url, title_hint,
                    source_package, received_at, grace_deadline_at, comment, state
                ) VALUES (
                    'legacy-capture', 'shared_text', '旧版本内容', NULL, NULL,
                    NULL, 1000, 9000, NULL, 'local_grace'
                )
                """.trimIndent()
            )
            close()
        }

        helper.runMigrationsAndValidate(
            TEST_DB,
            2,
            true,
            CaptureDatabase.MIGRATION_1_2
        ).use { database ->
            database.query("SELECT raw_text FROM single_captures WHERE capture_id = 'legacy-capture'")
                .use { cursor ->
                    assertEquals(true, cursor.moveToFirst())
                    assertEquals("旧版本内容", cursor.getString(0))
                }
            database.query("SELECT COUNT(*) FROM capture_retention").useCount {
                assertEquals(0, it)
            }
        }
    }

    @Test
    fun migrateTwoToThreeCreatesAndSeedsTagTables() {
        helper.createDatabase(TEST_DB, 2).close()

        helper.runMigrationsAndValidate(
            TEST_DB,
            3,
            true,
            CaptureDatabase.MIGRATION_2_3
        ).use { database ->
            database.query("SELECT COUNT(*) FROM capture_tags").useCount {
                assertEquals(4, it)
            }
            database.query("SELECT COUNT(*) FROM capture_tags WHERE is_pinned = 1").useCount {
                assertEquals(3, it)
            }
            database.query("SELECT COUNT(*) FROM capture_tag_refs").useCount {
                assertEquals(0, it)
            }
        }
    }

    private fun Cursor.useCount(assertion: (Int) -> Unit) = use { cursor ->
        check(cursor.moveToFirst())
        assertion(cursor.getInt(0))
    }

    private companion object {
        const val TEST_DB = "capture-migration-test"
    }
}
