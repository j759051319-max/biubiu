package cn.local.dailywork.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class DailyTrackingBackupTest {
    @Test
    fun trackingSheetHeadersRemainStable() {
        assertEquals(listOf("日期", "时长分钟", "记录时间"), LEARNING_HEADERS)
        assertEquals(listOf("日期", "饮水毫升", "记录时间"), WATER_HEADERS)
        assertEquals(
            listOf("日期", "起床分钟", "睡觉分钟", "手动步数", "同步步数", "同步时间"),
            WELLNESS_HEADERS
        )
    }
}
