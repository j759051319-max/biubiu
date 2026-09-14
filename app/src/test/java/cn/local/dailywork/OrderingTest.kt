package cn.local.dailywork

import org.junit.Assert.assertEquals
import org.junit.Test

class OrderingTest {
    @Test
    fun reordersOnlyVisibleIdsAndPreservesHiddenSlots() {
        assertEquals(
            listOf(9L, 3L, 2L, 8L),
            mergeVisibleOrder(listOf(9L, 2L, 3L, 8L), listOf(3L, 2L))
        )
    }
}
