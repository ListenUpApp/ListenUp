package com.calypsan.listenup.client.navigation.entries

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import com.calypsan.listenup.client.features.readingorder.ReadingOrderDetailScreen
import com.calypsan.listenup.client.features.readingorder.ReadingOrdersScreen
import com.calypsan.listenup.client.navigation.ReadingOrderDetail
import com.calypsan.listenup.client.navigation.ReadingOrders

/** Reading-order navigation entries — the per-series list and the order detail page. */
internal fun EntryProviderScope<NavKey>.readingOrderEntries(backStack: NavBackStack<NavKey>) {
    entry<ReadingOrders> { args ->
        ReadingOrdersScreen(
            seriesId = args.seriesId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
            onOrderClick = { orderId ->
                backStack.add(ReadingOrderDetail(orderId = orderId, seriesId = args.seriesId))
            },
        )
    }
    entry<ReadingOrderDetail> { args ->
        ReadingOrderDetailScreen(
            orderId = args.orderId,
            seriesId = args.seriesId,
            onBack = {
                backStack.removeAt(backStack.lastIndex)
            },
        )
    }
}
