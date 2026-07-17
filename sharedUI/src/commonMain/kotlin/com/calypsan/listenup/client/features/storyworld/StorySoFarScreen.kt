package com.calypsan.listenup.client.features.storyworld

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.core.layout.WindowSizeClass
import com.calypsan.listenup.client.design.components.AvatarStackEntry
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.components.ListenUpLoadingIndicator
import com.calypsan.listenup.client.design.components.ListenUpScaffold
import com.calypsan.listenup.client.features.storyworld.components.SafeHereToggle
import com.calypsan.listenup.client.features.storyworld.components.SceneStack
import com.calypsan.listenup.client.features.storyworld.components.StandRow
import com.calypsan.listenup.client.features.storyworld.components.anchorLabelText
import com.calypsan.listenup.client.presentation.storyworld.EntityCard
import com.calypsan.listenup.client.presentation.storyworld.StandRowUi
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarUiState
import com.calypsan.listenup.client.presentation.storyworld.StorySoFarViewModel
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.story_so_far_empty_body
import listenup.composeapp.generated.resources.story_so_far_empty_cta
import listenup.composeapp.generated.resources.story_so_far_empty_title
import listenup.composeapp.generated.resources.story_so_far_en_route
import listenup.composeapp.generated.resources.story_so_far_en_route_unknown
import listenup.composeapp.generated.resources.story_so_far_floor_order_body
import listenup.composeapp.generated.resources.story_so_far_floor_order_cta
import listenup.composeapp.generated.resources.story_so_far_floor_order_hint
import listenup.composeapp.generated.resources.story_so_far_floor_order_title
import listenup.composeapp.generated.resources.story_so_far_here_caption
import listenup.composeapp.generated.resources.story_so_far_in_this_scene
import listenup.composeapp.generated.resources.story_so_far_new_badge
import listenup.composeapp.generated.resources.story_so_far_safe_caption
import listenup.composeapp.generated.resources.story_so_far_scene_summary
import listenup.composeapp.generated.resources.story_so_far_scene_summary_short
import listenup.composeapp.generated.resources.story_so_far_through_label
import listenup.composeapp.generated.resources.story_so_far_title
import listenup.composeapp.generated.resources.story_so_far_toggle_here
import listenup.composeapp.generated.resources.story_so_far_toggle_safe
import listenup.composeapp.generated.resources.story_so_far_where_things_stand

private val HEADER_COVER_SIZE = 48.dp
private val WIDE_LEFT_COLUMN_WIDTH = 360.dp
private val WIDE_TOGGLE_WIDTH = 300.dp
private val EMPTY_TILE_SIZE = 88.dp
private val EMPTY_ICON_SIZE = 42.dp
private val EMPTY_BODY_MAX_WIDTH = 290.dp
private const val SCENE_SUMMARY_MAX_NAMES = 2

/**
 * The Story So Far full-screen recap for a single book — a spoiler-safe fold of the Story World
 * up to the viewer's own listening frontier (or, opted in via the [SafeHereToggle], as of the
 * exact position currently playing). Reached from the book detail screen; hands off to the Story
 * World hub ([onOpenHub]) and, when a series has no followed reading order, to the reading-order
 * picker ([onSetReadingOrder]) for its "showing this book only" floor.
 */
@Composable
fun StorySoFarScreen(
    bookId: String,
    onBackClick: () -> Unit,
    onEntityClick: (String) -> Unit,
    onSetReadingOrder: (String) -> Unit,
    onOpenHub: (seriesId: String?, bookId: String?) -> Unit,
    viewModel: StorySoFarViewModel = koinViewModel(),
) {
    LaunchedEffect(bookId) { viewModel.load(bookId) }

    val state by viewModel.state.collectAsStateWithLifecycle()

    ListenUpScaffold(
        topBar = { StorySoFarTopBar(onBackClick = onBackClick) },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (val current = state) {
                StorySoFarUiState.Idle, StorySoFarUiState.Loading -> {
                    ListenUpLoadingIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is StorySoFarUiState.EmptyFloor -> {
                    EmptyFloorContent(state = current, onOpenHub = onOpenHub)
                }

                is StorySoFarUiState.Ready -> {
                    val wide =
                        currentWindowAdaptiveInfo().windowSizeClass.isWidthAtLeastBreakpoint(
                            WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND,
                        )
                    if (wide) {
                        WideReadyContent(
                            state = current,
                            onEntityClick = onEntityClick,
                            onSetReadingOrder = onSetReadingOrder,
                            onSetAsOfHere = viewModel::setAsOfHere,
                        )
                    } else {
                        NarrowReadyContent(
                            state = current,
                            onEntityClick = onEntityClick,
                            onSetReadingOrder = onSetReadingOrder,
                            onSetAsOfHere = viewModel::setAsOfHere,
                        )
                    }
                }
            }
        }
    }
}

// region top bar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorySoFarTopBar(onBackClick: () -> Unit) {
    CenterAlignedTopAppBar(
        title = { Text(stringResource(Res.string.story_so_far_title)) },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        },
    )
}

// endregion

// region header

/** The book-cover + overline/title/meta header shared by the Ready and EmptyFloor states. */
@Composable
private fun StorySoFarHeader(
    bookId: String,
    bookTitle: String,
    metaLine: String?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCoverImage(
            bookId = bookId,
            coverPath = null,
            contentDescription = bookTitle,
            title = bookTitle,
            modifier = Modifier.size(HEADER_COVER_SIZE).clip(RoundedCornerShape(10.dp)),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.story_so_far_title).uppercase(),
                style = MaterialTheme.typography.labelLarge,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = bookTitle,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (metaLine != null) {
                Text(
                    text = metaLine,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// endregion

// region empty floor

@Composable
private fun EmptyFloorContent(
    state: StorySoFarUiState.EmptyFloor,
    onOpenHub: (seriesId: String?, bookId: String?) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StorySoFarHeader(
            bookId = state.bookId,
            bookTitle = state.bookTitle,
            metaLine = null,
            modifier = Modifier.padding(16.dp),
        )
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp, start = 32.dp, end = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier =
                    Modifier
                        .size(EMPTY_TILE_SIZE)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_ICON_SIZE),
                )
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(Res.string.story_so_far_empty_title),
                fontSize = 21.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(Res.string.story_so_far_empty_body, state.bookTitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = EMPTY_BODY_MAX_WIDTH),
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                colors =
                    ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                onClick = {
                    val targetBookId = if (state.seriesId == null) state.bookId else null
                    onOpenHub(state.seriesId, targetBookId)
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.story_so_far_empty_cta))
            }
        }
    }
}

// endregion

// region ready — narrow layout

@Composable
private fun NarrowReadyContent(
    state: StorySoFarUiState.Ready,
    onEntityClick: (String) -> Unit,
    onSetReadingOrder: (String) -> Unit,
    onSetAsOfHere: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            StorySoFarHeader(bookId = state.bookId, bookTitle = state.bookTitle, metaLine = state.metaLine())
            SafeHereToggleSection(state = state, onSetAsOfHere = onSetAsOfHere)
            if (state.noOrderFloor) {
                NoOrderFloorBanner(bookTitle = state.bookTitle)
            }
            if (state.inScene.isNotEmpty()) {
                InThisSceneSection(entities = state.inScene)
            }
            WhereThingsStandSection(rows = state.standRows, onEntityClick = onEntityClick)
            val floorSeriesId = state.seriesId
            if (state.noOrderFloor && floorSeriesId != null) {
                FloorOrderCta(onClick = { onSetReadingOrder(floorSeriesId) })
            }
        }
    }
}

// endregion

// region ready — wide layout

@Composable
private fun WideReadyContent(
    state: StorySoFarUiState.Ready,
    onEntityClick: (String) -> Unit,
    onSetReadingOrder: (String) -> Unit,
    onSetAsOfHere: (Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StorySoFarHeader(
                bookId = state.bookId,
                bookTitle = state.bookTitle,
                metaLine = state.metaLine(),
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(20.dp))
            SafeHereToggleSection(
                state = state,
                onSetAsOfHere = onSetAsOfHere,
                modifier = Modifier.width(WIDE_TOGGLE_WIDTH),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Column(
                modifier = Modifier.width(WIDE_LEFT_COLUMN_WIDTH).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                if (state.inScene.isNotEmpty()) {
                    InThisSceneSection(entities = state.inScene)
                }
                if (state.noOrderFloor) {
                    NoOrderFloorBanner(bookTitle = state.bookTitle)
                }
            }
            Column(
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                WhereThingsStandSection(rows = state.standRows, onEntityClick = onEntityClick)
                val floorSeriesId = state.seriesId
                if (state.noOrderFloor && floorSeriesId != null) {
                    FloorOrderCta(onClick = { onSetReadingOrder(floorSeriesId) })
                }
            }
        }
    }
}

// endregion

// region shared sections

/** The "Safe" / "As of here" toggle, captioned per the current mode and this book's frontier. */
@Composable
private fun SafeHereToggleSection(
    state: StorySoFarUiState.Ready,
    onSetAsOfHere: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    SafeHereToggle(
        asOfHere = state.asOfHere,
        safeLabel = stringResource(Res.string.story_so_far_toggle_safe),
        hereLabel = stringResource(Res.string.story_so_far_toggle_here),
        caption =
            run {
                val hereLabel = state.hereLabel
                if (state.asOfHere && hereLabel != null) {
                    stringResource(Res.string.story_so_far_here_caption, anchorLabelText(hereLabel))
                } else {
                    stringResource(Res.string.story_so_far_safe_caption, anchorLabelText(state.frontierLabel))
                }
            },
        enabled = state.asOfHereAvailable,
        onChange = onSetAsOfHere,
        modifier = modifier,
    )
}

/** The "showing this book only" floor banner for a series with no followed reading order. */
@Composable
private fun NoOrderFloorBanner(bookTitle: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(
                    text = stringResource(Res.string.story_so_far_floor_order_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(Res.string.story_so_far_floor_order_body, bookTitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The full-width CTA (+ hint) that sends the viewer to pick a reading order, closing the floor. */
@Composable
private fun FloorOrderCta(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Button(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(imageVector = Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.story_so_far_floor_order_cta))
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(Res.string.story_so_far_floor_order_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** "In this scene" — the [SceneStack] glyph cluster for who's currently present. */
@Composable
private fun InThisSceneSection(entities: List<EntityCard>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(icon = Icons.Outlined.Group, text = stringResource(Res.string.story_so_far_in_this_scene))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(24.dp),
        ) {
            SceneStack(
                entries = entities.map { AvatarStackEntry(name = it.name, kind = it.kind, tintSeed = it.id) },
                summaryText = sceneSummaryText(entities),
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun sceneSummaryText(entities: List<EntityCard>): String {
    val firstNames = entities.map { it.name.substringBefore(' ') }
    return when {
        entities.isEmpty() -> {
            ""
        }

        entities.size == 1 -> {
            stringResource(Res.string.story_so_far_scene_summary_short, firstNames[0])
        }

        entities.size == SCENE_SUMMARY_MAX_NAMES -> {
            firstNames.joinToString(", ")
        }

        else -> {
            val leading = firstNames.take(SCENE_SUMMARY_MAX_NAMES).joinToString(", ")
            stringResource(Res.string.story_so_far_scene_summary, leading, entities.size - SCENE_SUMMARY_MAX_NAMES)
        }
    }
}

/** "Where things stand" — the grouped, dividing [StandRow] list. */
@Composable
private fun WhereThingsStandSection(
    rows: List<StandRowUi>,
    onEntityClick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(icon = Icons.Outlined.Public, text = stringResource(Res.string.story_so_far_where_things_stand))
        Column {
            rows.forEachIndexed { index, row ->
                val location = row.toLocationLabel()
                StandRow(
                    name = row.entity.name,
                    kind = row.entity.kind,
                    tintSeed = row.entity.id,
                    locationLabel = location,
                    statusLine = row.statusLine,
                    isNew = row.isNew,
                    newBadgeText = stringResource(Res.string.story_so_far_new_badge),
                    onClick = { onEntityClick(row.entity.id) },
                )
                if (index != rows.lastIndex) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                }
            }
        }
    }
}

@Composable
private fun StandRowUi.toLocationLabel(): String? {
    val from = enRouteFrom
    return when {
        enRoute && from != null -> stringResource(Res.string.story_so_far_en_route, from)
        enRoute -> stringResource(Res.string.story_so_far_en_route_unknown)
        else -> locationName
    }
}

@Composable
private fun SectionLabel(
    icon: ImageVector,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(19.dp),
            tint = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = text,
            fontSize = 19.sp,
            fontWeight = FontWeight.W800,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** This ready state's header meta line — the order-qualified or bare frontier label. */
@Composable
private fun StorySoFarUiState.Ready.metaLine(): String {
    val order = orderName
    return if (order != null) {
        stringResource(Res.string.story_so_far_through_label, order, anchorLabelText(frontierLabel))
    } else {
        anchorLabelText(frontierLabel)
    }
}

// endregion
