package cn.local.dailywork.data

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val enabled: Boolean = true
)

@Entity(
    tableName = "habits",
    foreignKeys = [ForeignKey(entity = Category::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index("categoryId")]
)
data class Habit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val categoryId: Long? = null,
    val sortOrder: Int = 0,
    val enabled: Boolean = true,
    val reminderEnabled: Boolean = false,
    val reminderHour: Int = 20,
    val reminderMinute: Int = 0
)

@Entity(tableName = "daily_habit_records", primaryKeys = ["date", "habitId"], indices = [Index("habitId")])
data class DailyHabitRecord(
    val date: String,
    val habitId: Long,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val note: String = ""
)

@Entity(tableName = "daily_tasks", indices = [Index("date")])
data class DailyTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val title: String,
    val completed: Boolean = false,
    val completedDate: String? = null,
    val note: String = "",
    val sortOrder: Int = 0
)

data class DailyTaskForDate(
    @androidx.room.Embedded val task: DailyTask,
    val completedOnDate: Boolean
)

data class CalendarDayCount(val date: String, val habits: Int, val tasks: Int) {
    val total: Int get() = habits + tasks
}

@Entity(tableName = "daily_notes")
data class DailyNote(@PrimaryKey val date: String, val content: String = "")

data class HabitWithCategory(
    @androidx.room.Embedded val habit: Habit,
    @androidx.room.Relation(parentColumn = "categoryId", entityColumn = "id") val category: Category?
)

data class DayCount(val date: String, val completed: Int, val total: Int)

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories WHERE enabled = 1 ORDER BY sortOrder, id") fun observeActive(): Flow<List<Category>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, id") fun observeAll(): Flow<List<Category>>
    @Query("SELECT * FROM categories ORDER BY sortOrder, id") suspend fun all(): List<Category>
    @Query("SELECT * FROM categories WHERE name = :name LIMIT 1") suspend fun findByName(name: String): Category?
    @Insert suspend fun insert(item: Category): Long
    @Update suspend fun update(item: Category)
    @Query("UPDATE habits SET categoryId = NULL WHERE categoryId = :categoryId") suspend fun clearHabitCategory(categoryId: Long)
    @Query("DELETE FROM categories WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface HabitDao {
    @Transaction @Query("SELECT * FROM habits WHERE enabled = 1 ORDER BY sortOrder, id") fun observeActiveWithCategory(): Flow<List<HabitWithCategory>>
    @Transaction @Query("SELECT * FROM habits ORDER BY sortOrder, id") fun observeAllWithCategory(): Flow<List<HabitWithCategory>>
    @Query("SELECT * FROM habits ORDER BY sortOrder, id") suspend fun all(): List<Habit>
    @Query("SELECT * FROM habits WHERE title = :title AND ((:categoryId IS NULL AND categoryId IS NULL) OR categoryId = :categoryId) LIMIT 1") suspend fun find(title: String, categoryId: Long?): Habit?
    @Insert suspend fun insert(item: Habit): Long
    @Update suspend fun update(item: Habit)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM habits") suspend fun nextSortOrder(): Int
    @Query("UPDATE habits SET sortOrder = :sortOrder WHERE id = :id") suspend fun updateSortOrder(id: Long, sortOrder: Int)
    @Query("DELETE FROM habits WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM daily_habit_records WHERE date = :date") fun observeForDate(date: String): Flow<List<DailyHabitRecord>>
    @Query("SELECT date, SUM(CASE WHEN completed THEN 1 ELSE 0 END) AS completed, COUNT(*) AS total FROM daily_habit_records WHERE date BETWEEN :start AND :end GROUP BY date") fun observeCounts(start: String, end: String): Flow<List<DayCount>>
    @Query("SELECT * FROM daily_habit_records WHERE date BETWEEN :start AND :end AND completed = 1 ORDER BY date DESC") fun observeCompletedBetween(start: String, end: String): Flow<List<DailyHabitRecord>>
    @Query("""
        SELECT date,
               SUM(CASE WHEN kind = 'habit' THEN 1 ELSE 0 END) AS habits,
               SUM(CASE WHEN kind = 'task' THEN 1 ELSE 0 END) AS tasks
        FROM (
            SELECT date, 'habit' AS kind
            FROM daily_habit_records
            WHERE completed = 1
            UNION ALL
            SELECT completedDate AS date, 'task' AS kind
            FROM daily_tasks
            WHERE completedDate IS NOT NULL
        )
        WHERE date BETWEEN :start AND :end
        GROUP BY date
    """)
    fun observeCalendarCounts(start: String, end: String): Flow<List<CalendarDayCount>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DailyHabitRecord)
    @Query("SELECT * FROM daily_habit_records ORDER BY date, habitId") suspend fun all(): List<DailyHabitRecord>
}

@Dao
interface TaskDao {
    @Query("""
        SELECT daily_tasks.*,
               CASE WHEN completedDate = :date THEN 1 ELSE 0 END AS completedOnDate
        FROM daily_tasks
        WHERE daily_tasks.date <= :date
          AND (completedDate IS NULL OR completedDate >= :date)
        ORDER BY sortOrder, id
    """)
    fun observeTimeline(date: String): Flow<List<DailyTaskForDate>>

    @Query("SELECT * FROM daily_tasks WHERE completedDate BETWEEN :start AND :end ORDER BY completedDate DESC, sortOrder, id")
    fun observeCompletedBetween(start: String, end: String): Flow<List<DailyTask>>

    @Insert suspend fun insert(item: DailyTask): Long
    @Update suspend fun update(item: DailyTask)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM daily_tasks") suspend fun nextSortOrder(): Int
    @Query("SELECT * FROM daily_tasks ORDER BY sortOrder, id") suspend fun allInDisplayOrder(): List<DailyTask>
    @Query("UPDATE daily_tasks SET sortOrder = :sortOrder WHERE id = :id") suspend fun updateSortOrder(id: Long, sortOrder: Int)
    @Query("DELETE FROM daily_tasks WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM daily_tasks WHERE date = :date AND title = :title LIMIT 1") suspend fun find(date: String, title: String): DailyTask?
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM daily_notes WHERE date = :date") fun observeForDate(date: String): Flow<DailyNote?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DailyNote)
    @Query("SELECT * FROM daily_notes ORDER BY date") suspend fun all(): List<DailyNote>
}

@Dao
interface LearningDao {
    @Query("SELECT * FROM learning_sessions WHERE date = :date ORDER BY createdAt, id") fun observe(date: String): Flow<List<LearningSession>>
    @Query("SELECT * FROM learning_sessions ORDER BY date, createdAt, id") suspend fun all(): List<LearningSession>
    @Insert suspend fun insert(item: LearningSession): Long
    @Query("SELECT * FROM learning_sessions WHERE date = :date AND durationMinutes = :minutes AND createdAt = :createdAt LIMIT 1") suspend fun find(date: String, minutes: Int, createdAt: Long): LearningSession?
    @Query("DELETE FROM learning_sessions WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface WaterDao {
    @Query("SELECT * FROM water_entries WHERE date = :date ORDER BY createdAt, id") fun observe(date: String): Flow<List<WaterEntry>>
    @Query("SELECT * FROM water_entries ORDER BY date, createdAt, id") suspend fun all(): List<WaterEntry>
    @Insert suspend fun insert(item: WaterEntry): Long
    @Query("SELECT * FROM water_entries WHERE date = :date AND milliliters = :milliliters AND createdAt = :createdAt LIMIT 1") suspend fun find(date: String, milliliters: Int, createdAt: Long): WaterEntry?
    @Query("DELETE FROM water_entries WHERE id = :id") suspend fun delete(id: Long)
}

@Dao
interface WellnessDao {
    @Query("SELECT * FROM daily_wellness WHERE date = :date") fun observe(date: String): Flow<DailyWellness?>
    @Query("SELECT * FROM daily_wellness WHERE date = :date") suspend fun find(date: String): DailyWellness?
    @Query("SELECT * FROM daily_wellness ORDER BY date") suspend fun all(): List<DailyWellness>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(item: DailyWellness)
}

@Database(
    entities = [
        Category::class,
        Habit::class,
        DailyHabitRecord::class,
        DailyTask::class,
        DailyNote::class,
        LearningSession::class,
        WaterEntry::class,
        DailyWellness::class
    ],
    version = 3
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun habitDao(): HabitDao
    abstract fun recordDao(): RecordDao
    abstract fun taskDao(): TaskDao
    abstract fun noteDao(): NoteDao
    abstract fun learningDao(): LearningDao
    abstract fun waterDao(): WaterDao
    abstract fun wellnessDao(): WellnessDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE daily_tasks ADD COLUMN completedDate TEXT")
                db.execSQL("UPDATE daily_tasks SET completedDate = date WHERE completed = 1")
                db.query("SELECT id FROM daily_tasks ORDER BY date, sortOrder, id").use { cursor ->
                    var index = 0
                    while (cursor.moveToNext()) {
                        db.execSQL(
                            "UPDATE daily_tasks SET sortOrder = ? WHERE id = ?",
                            arrayOf(index++, cursor.getLong(0))
                        )
                    }
                }
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `learning_sessions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `durationMinutes` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_learning_sessions_date` ON `learning_sessions` (`date`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `water_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `date` TEXT NOT NULL, `milliliters` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_water_entries_date` ON `water_entries` (`date`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `daily_wellness` (`date` TEXT NOT NULL, `wakeMinute` INTEGER, `bedMinute` INTEGER, `manualSteps` INTEGER, `syncedSteps` INTEGER, `stepsSyncedAt` INTEGER, PRIMARY KEY(`date`))")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "daily_work.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
                .also { instance = it }
        }
    }
}
