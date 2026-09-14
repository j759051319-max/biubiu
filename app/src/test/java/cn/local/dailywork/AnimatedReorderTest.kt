package cn.local.dailywork

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimatedReorderTest {
    private val ids = listOf(1L, 2L, 3L, 4L)

    @Test
    fun movementBelowHalfStepDoesNotReorder() {
        assertEquals(
            DragAdvance(ids, index = 1, offsetPx = 20f),
            advanceDrag(ids, index = 1, offsetPx = 20f, stepPx = 64f)
        )
    }

    @Test
    fun crossesAdjacentRowsInBothDirections() {
        assertEquals(
            DragAdvance(listOf(1L, 3L, 2L, 4L), index = 2, offsetPx = -24f),
            advanceDrag(ids, index = 1, offsetPx = 40f, stepPx = 64f)
        )
        assertEquals(
            DragAdvance(listOf(2L, 1L, 3L, 4L), index = 0, offsetPx = 24f),
            advanceDrag(ids, index = 1, offsetPx = -40f, stepPx = 64f)
        )
    }

    @Test
    fun fastMovementCrossesMultipleRows() {
        assertEquals(
            DragAdvance(listOf(2L, 3L, 4L, 1L), index = 3, offsetPx = -32f),
            advanceDrag(ids, index = 0, offsetPx = 160f, stepPx = 64f)
        )
    }

    @Test
    fun boundariesClampRemainingTranslation() {
        assertEquals(
            DragAdvance(ids, index = 0, offsetPx = -32f),
            advanceDrag(ids, index = 0, offsetPx = -100f, stepPx = 64f)
        )
        assertEquals(
            DragAdvance(ids, index = 3, offsetPx = 32f),
            advanceDrag(ids, index = 3, offsetPx = 100f, stepPx = 64f)
        )
    }

    @Test
    fun cancellationRestoresOriginalOrder() {
        val controller = ReorderController(ids)
        controller.start(id = 2L, stepPx = 64f)
        controller.dragBy(40f)
        assertEquals(listOf(1L, 3L, 2L, 4L), controller.ids)

        controller.cancel()

        assertEquals(ids, controller.ids)
        assertFalse(controller.isDragging)
        assertEquals(0f, controller.dragOffsetPx)
    }

    @Test
    fun finishReturnsOnlyChangedOrder() {
        val unchanged = ReorderController(ids)
        unchanged.start(id = 2L, stepPx = 64f)
        unchanged.dragBy(10f)
        assertNull(unchanged.finish())

        val changed = ReorderController(ids)
        changed.start(id = 2L, stepPx = 64f)
        changed.dragBy(40f)
        assertEquals(listOf(1L, 3L, 2L, 4L), changed.finish())
        assertFalse(changed.isDragging)
    }

    @Test
    fun missingActiveItemCancelsAndResynchronizes() {
        val controller = ReorderController(ids)
        controller.start(id = 2L, stepPx = 64f)
        controller.dragBy(40f)

        controller.sync(listOf(1L, 3L, 4L))

        assertEquals(listOf(1L, 3L, 4L), controller.ids)
        assertFalse(controller.isDragging)
        assertNull(controller.activeId)
    }

    @Test
    fun validStartExposesDraggingState() {
        val controller = ReorderController(ids)
        controller.start(id = 3L, stepPx = 64f)
        assertTrue(controller.isDragging)
        assertEquals(3L, controller.activeId)
    }
}
