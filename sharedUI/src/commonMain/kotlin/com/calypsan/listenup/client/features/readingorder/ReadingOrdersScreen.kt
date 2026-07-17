package com.calypsan.listenup.client.features.readingorder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.ListenUpExtendedFab
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.domain.model.ReadingOrder
import com.calypsan.listenup.client.features.readingorder.components.OrderCard
import com.calypsan.listenup.client.features.readingorder.components.booksCountText
import com.calypsan.listenup.client.presentation.readingorder.OrderRowUi
import com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersEvent
import com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersUiState
import com.calypsan.listenup.client.presentation.readingorder.SeriesReadingOrdersViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.reading_orders_by_owner
import listenup.composeapp.generated.resources.reading_orders_discover_failed
import listenup.composeapp.generated.resources.reading_orders_discover_header
import listenup.composeapp.generated.resources.reading_orders_discover_retry
import listenup.composeapp.generated.resources.reading_orders_empty_body
import listenup.composeapp.generated.resources.reading_orders_empty_title
import listenup.composeapp.generated.resources.reading_orders_header_hint
import listenup.composeapp.generated.resources.reading_orders_new_order
import listenup.composeapp.generated.resources.reading_orders_title

private val WIDE_CONTENT_MAX_WIDTH = 680.dp

/**
 * The per-series "Reading Orders" screen — the caller's own orders plus other users' discoverable
 * ones, with the ability to open one (follow/detail live on [ReadingOrderDetailScreen]) or mint a
 * new one via [CreateOrderSheet].
 */
@Composable
fun ReadingOrdersScreen(
    seriesId: String,
    onBack: () -> Unit,
    onOrderClick: (String) -> Unit,
    vm: SeriesReadingOrdersViewModel = koinViewModel(),
) {
    LaunchedEffect(seriesId) { vm.load(seriesId) }

    val state by vm.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is SeriesReadingOrdersEvent.Created -> onOrderClick(event.orderId)
            }
        }
    }

    var showCreateSheet by rememberSaveable { mutableStateOf(false) }

    ListenUpScaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            if (state is SeriesReadingOrdersUiState.Ready) {
                ListenUpExtendedFab(
                    onClick = { showCreateSheet = true },
                    icon = Icons.Default.Add,
                    text = stringResource(Res.string.reading_orders_new_order),
                )
            }
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                SeriesReadingOrdersUiState.Idle, SeriesReadingOrdersUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is SeriesReadingOrdersUiState.Ready -> {
                    val wide =
                        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                        )
                    ReadingOrdersContent(
                        state = current,
                        wide = wide,
                        onBack = onBack,
                        onOrderClick = onOrderClick,
                        onRetryDiscover = vm::retryDiscover,
                    )
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateOrderSheet(
            onCreate = { name, attribution, isPrivate, setActive ->
                vm.createOrder(name = name, attribution = attribution, isPrivate = isPrivate, setActive = setActive)
                showCreateSheet = false
            },
            onDismiss = { showCreateSheet = false },
        )
    }
}

@Composable
private fun ReadingOrdersContent(
    state: SeriesReadingOrdersUiState.Ready,
    wide: Boolean,
    onBack: () -> Unit,
    onOrderClick: (String) -> Unit,
    onRetryDiscover: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ReadingOrdersHero(onBack = onBack)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            ReadingOrdersBody(
                state = state,
                wide = wide,
                onOrderClick = onOrderClick,
                onRetryDiscover = onRetryDiscover,
            )
        }
    }
}

// region header

/** Color-blocked hero: back button, title, and the "active order = spoiler clock" hint banner. */
@Composable
private fun ReadingOrdersHero(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(bottomStart = 34.dp, bottomEnd = 34.dp),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(start = 8.dp, end = 20.dp, top = 8.dp, bottom = 20.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Text(
                    text = stringResource(Res.string.reading_orders_title),
                    fontSize = 33.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.height(14.dp))
                ReadingOrdersHeaderHint()
            }
        }
    }
}

@Composable
private fun ReadingOrdersHeaderHint() {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.08f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Bolt,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(Res.string.reading_orders_header_hint),
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

// endregion

// region body

@Composable
private fun ReadingOrdersBody(
    state: SeriesReadingOrdersUiState.Ready,
    wide: Boolean,
    onOrderClick: (String) -> Unit,
    onRetryDiscover: () -> Unit,
) {
    val isEmpty = state.owned.isEmpty() && state.discovered.isEmpty() && !state.discoverFailed
    if (isEmpty) {
        ReadingOrdersEmptyState(modifier = Modifier.padding(top = 24.dp))
        return
    }

    Column(
        modifier =
            Modifier
                .let { if (wide) it.widthIn(max = WIDE_CONTENT_MAX_WIDTH) else it.fillMaxWidth() }
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (state.owned.isNotEmpty()) {
            OwnedOrdersSection(owned = state.owned, onOrderClick = onOrderClick)
        }
        if (state.discovered.isNotEmpty() || state.discoverFailed) {
            DiscoveredOrdersSection(
                discovered = state.discovered,
                discoverFailed = state.discoverFailed,
                onOrderClick = onOrderClick,
                onRetry = onRetryDiscover,
            )
        }
    }
}

@Composable
private fun OwnedOrdersSection(
    owned: List<OrderRowUi>,
    onOrderClick: (String) -> Unit,
) {
    GroupedOrderRows(rows = owned, showPrivacy = true, onOrderClick = onOrderClick, subtitle = {
        booksCountText(it.bookCount)
    })
}

@Composable
private fun DiscoveredOrdersSection(
    discovered: List<OrderRowUi>,
    discoverFailed: Boolean,
    onOrderClick: (String) -> Unit,
    onRetry: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(Res.string.reading_orders_discover_header),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (discovered.isNotEmpty()) {
            GroupedOrderRows(
                rows = discovered,
                showPrivacy = false,
                onOrderClick = onOrderClick,
                subtitle = { order -> discoveredSubtitle(order) },
            )
        }
        if (discoverFailed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(Res.string.reading_orders_discover_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onRetry) {
                    Text(stringResource(Res.string.reading_orders_discover_retry))
                }
            }
        }
    }
}

@Composable
private fun discoveredSubtitle(order: ReadingOrder): String =
    if (order.attribution.isNotBlank()) {
        order.attribution
    } else {
        stringResource(Res.string.reading_orders_by_owner, order.ownerDisplayName)
    }

/** A [MaterialTheme.colorScheme.surfaceContainerLow] card of [OrderCard] rows, divider-separated. */
@Composable
private fun GroupedOrderRows(
    rows: List<OrderRowUi>,
    showPrivacy: Boolean,
    onOrderClick: (String) -> Unit,
    subtitle: @Composable (ReadingOrder) -> String,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column {
            rows.forEachIndexed { index, row ->
                OrderCard(
                    order = row.order,
                    isActive = row.isActive,
                    subtitle = subtitle(row.order),
                    showPrivacy = showPrivacy,
                    onClick = { onOrderClick(row.order.idString) },
                )
                if (index != rows.lastIndex) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

// endregion

// region empty state

@Composable
private fun ReadingOrdersEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
                Modifier
                    .size(66.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Bolt,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = stringResource(Res.string.reading_orders_empty_title),
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.reading_orders_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// endregion
