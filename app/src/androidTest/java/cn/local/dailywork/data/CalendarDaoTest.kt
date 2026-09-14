package cn.local.dailywork.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CalendarDaoTest {
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun calendarCountsOnlyActualCompletions() = runBlocking {
        db.recordDao().upsert(DailyHabitRecord("2026-07-20", 1, completed = false))
        db.recordDao().upsert(DailyHabitRecord("2026-07-21", 1, completed = true))
        db.taskDao().insert(
            DailyTask(
                date = "2026-07-19",
                title = "已完成日程",
                completed = true,
                completedDate = "2026-07-21"
            )
        )

        val counts = db.recordDao().observeCalendarCounts("2026-07-01", "2026-07-31").first()
        assertEquals(listOf(CalendarDayCount("2026-07-21", habits = 1, tasks = 1)), counts)
        assertEquals(2, counts.single().total)
    }
}
