package cn.local.dailywork.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {
    private val dbName = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrate1To2_preservesDatesAndCreatesGlobalOrder() {
        helper.createDatabase(dbName, 1).apply {
            execSQL("INSERT INTO daily_tasks (id,date,title,completed,note,sortOrder) VALUES (11,'2026-07-01','完成项',1,'',1)")
            execSQL("INSERT INTO daily_tasks (id,date,title,completed,note,sortOrder) VALUES (12,'2026-07-01','遗留项',0,'',0)")
            execSQL("INSERT INTO daily_tasks (id,date,title,completed,note,sortOrder) VALUES (13,'2026-07-02','后建项',0,'',0)")
            close()
        }

        val db = helper.runMigrationsAndValidate(dbName, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT id, completed, completedDate, sortOrder FROM daily_tasks ORDER BY sortOrder").use { cursor ->
            val rows = buildList {
                while (cursor.moveToNext()) add(
                    listOf(cursor.getLong(0), cursor.getInt(1), cursor.getString(2), cursor.getInt(3))
                )
            }
            assertEquals(
                listOf(
                    listOf(12L, 0, null, 0),
                    listOf(11L, 1, "2026-07-01", 1),
                    listOf(13L, 0, null, 2)
                ),
                rows
            )
        }
    }

    @Test
    fun migrate2To3_preservesExistingDataAndCreatesTrackingTables() {
        helper.createDatabase(dbName, 2).apply {
            execSQL("INSERT INTO daily_notes(date, content) VALUES('2026-09-13', '保留')")
            close()
        }

        helper.runMigrationsAndValidate(dbName, 3, true, AppDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT content FROM daily_notes WHERE date='2026-09-13'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("保留", cursor.getString(0))
            }
            db.query("SELECT COUNT(*) FROM learning_sessions").close()
            db.query("SELECT COUNT(*) FROM water_entries").close()
            db.query("SELECT COUNT(*) FROM daily_wellness").close()
        }
    }
}
