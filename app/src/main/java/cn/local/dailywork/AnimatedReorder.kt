package cn.local.dailywork

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

internal data class DragAdvance(
    val ids: List<Long>,
    val index: Int,
    val offsetPx: Float
)

internal fun advanceDrag(
    ids: List<Long>,
    index: Int,
    offsetPx: Float,
    stepPx: Float
): DragAdvance {
    if (ids.isEmpty() || index !in ids.indices || stepPx <= 0f) {
        return DragAdvance(ids, index, 0f)
    }

    val reordered = ids.toMutableList()
    var currentIndex = index
    var remaining = offsetPx
    val threshold = stepPx / 2f

    if (remaining > 0f) {
        while (remaining >= threshold && currentIndex < reordered.lastIndex) {
            val item = reordered.removeAt(currentIndex)
            reordered.add(currentIndex + 1, item)
            currentIndex++
            remaining -= stepPx
        }
        if (currentIndex == reordered.lastIndex && remaining > threshold) remaining = threshold
    } else if (remaining < 0f) {
        while (remaining <= -threshold && currentIndex > 0) {
            val item = reordered.removeAt(currentIndex)
            reordered.add(currentIndex - 1, item)
            currentIndex--
            remaining += stepPx
        }
        if (currentIndex == 0 && remaining < -threshold) remaining = -threshold
    }

    return DragAdvance(reordered, currentIndex, remaining)
}

@Stable
internal class ReorderController(initialIds: List<Long>) {
    var ids by mutableStateOf(initialIds.toList())
        private set
    var activeId by mutableStateOf<Long?>(null)
        private set
    var dragOffsetPx by mutableFloatStateOf(0f)
        private set

    val isDragging: Boolean get() = activeId != null

    private var originalIds = initialIds.toList()
    private var stepPx = 1f

    fun sync(sourceIds: List<Long>) {
        val normalized = sourceIds.toList()
        if (!isDragging) {
            ids = normalized
            originalIds = normalized
            return
        }
        if (activeId !in normalized) {
            ids = normalized
            originalIds = normalized
            resetDragState()
        }
    }

    fun start(id: Long, stepPx: Float) {
        if (ids.size < 2 || id !in ids || stepPx <= 0f) return
        originalIds = ids.toList()
        activeId = id
        dragOffsetPx = 0f
        this.stepPx = stepPx
    }

    fun dragBy(deltaPx: Float) {
        val id = activeId ?: return
        val index = ids.indexOf(id)
        if (index < 0) {
            cancel()
            return
        }
        val advanced = advanceDrag(ids, index, dragOffsetPx + deltaPx, stepPx)
        ids = advanced.ids
        dragOffsetPx = advanced.offsetPx
    }

    fun finish(): List<Long>? {
        if (!isDragging) return null
        val result = ids.toList().takeIf { it != originalIds }
        originalIds = ids.toList()
        resetDragState()
        return result
    }

    fun cancel() {
        if (isDragging) ids = originalIds.toList()
        resetDragState()
    }

    private fun resetDragState() {
        activeId = null
        dragOffsetPx = 0f
        stepPx = 1f
    }
}
