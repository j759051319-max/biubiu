package cn.local.dailywork.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskExcelColumnsTest {
    @Test
    fun oldHeaderMapsWithoutCompletionDate() {
        assertEquals(
            TaskColumns(created = 0, title = 1, completed = 2, completedDate = null, note = 3, sort = 4),
            taskColumns(listOf("日期", "标题", "已完成", "备注", "排序"))
        )
    }

    @Test
    fun newHeaderMapsCompletionDate() {
        assertEquals(
            TaskColumns(created = 0, title = 1, completed = 2, completedDate = 3, note = 4, sort = 5),
            taskColumns(listOf("创建日期", "标题", "已完成", "完成日期", "备注", "排序"))
        )
    }
}
