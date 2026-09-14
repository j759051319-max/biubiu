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
class TaskDaoTimelineTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: TaskDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = db.taskDao()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun timelineRestoresStateForEverySelectedDate() = runBlocking {
        val completedId = dao.insert(
            DailyTask(
                date = "2026-07-20",
                title = "跨日事项",
                completed = true,
                completedDate = "2026-07-22",
                sortOrder = 0
            )
        )
        val openId = dao.insert(
            DailyTask(date = "2026-07-21", title = "持续待办", sortOrder = 1)
        )
        suspend fun ids(date: String) = dao.observeTimeline(date).first().map { it.task.id }

        assertEquals(emptyList<Long>(), ids("2026-07-19"))
        assertEquals(listOf(completedId), ids("2026-07-20"))
        assertEquals(listOf(completedId, openId), ids("2026-07-21"))
        assertEquals(listOf(completedId, openId), ids("2026-07-22"))
        assertEquals(listOf(openId), ids("2026-07-23"))
        assertEquals(true, dao.observeTimeline("2026-07-22").first().first().completedOnDate)
        assertEquals(false, dao.observeTimeline("2026-07-21").first().first().completedOnDate)
    }
}
