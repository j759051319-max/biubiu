package cn.local.dailywork

internal fun mergeVisibleOrder(allIds: List<Long>, orderedVisibleIds: List<Long>): List<Long> {
    val visible = orderedVisibleIds.toSet()
    var visibleIndex = 0
    return allIds.map { id ->
        if (id in visible) orderedVisibleIds[visibleIndex++] else id
    }
}
