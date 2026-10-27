package com.calypsan.listenup.client.features.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.SectionTitle
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.ScanProgressState
import com.calypsan.listenup.client.features.shell.components.AppHeaderSlot
import com.calypsan.listenup.client.presentation.home.groupedCount
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_resume
import listenup.composeapp.generated.resources.home_everything_in_progress
import listenup.composeapp.generated.resources.home_arriving_body
import listenup.composeapp.generated.resources.home_arriving_count
import listenup.composeapp.generated.resources.home_arriving_title
import listenup.composeapp.generated.resources.home_loading_books_a11y
import listenup.composeapp.generated.resources.home_up_next_loading_a11y
import listenup.composeapp.generated.resources.library_setup_building_title
import org.jetbrains.compose.resources.stringResource

private val SkeletonCover = 120.dp
private val SkeletonButtonHeight = 56.dp
private val SkeletonRailCard = 156.dp
private val GreetingBarWidth = 200.dp
private val GreetingBarHeight = 40.dp
private const val SKELETON_RAIL_CARDS = 3
private val SkeletonLineWidths = listOf(0.4f, 0.8f, 0.6f)

/**
 * "Building your library" above the first-sync skeleton (spec §2.3 FirstSync, H5): the count joins the title only
 * while a scan knows both numbers (open question 1), and the change is announced politely (§8.2).
 */
@Composable
internal fun FirstSyncStrip(
    scan: ScanProgressState?,
    modifier: Modifier = Modifier,
) {
    val building = stringResource(Res.string.library_setup_building_title)
    val title =
        scan
            ?.takeIf { it.booksTotal > 0 }
            ?.let { counted ->
                stringResource(
                    Res.string.home_arriving_title,
                    building,
                    stringResource(
                        Res.string.home_arriving_count,
                        groupedCount(counted.books),
                        groupedCount(counted.booksTotal),
                    ),
                )
            }
            ?: building
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = stringResource(Res.string.home_arriving_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            scan?.progressFraction?.let { fraction ->
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * The slot's skeleton, in the Resume card's geometry (H14). First sync draws its Resume disabled ([withResume], board
 * panel 2); page loading does not.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun HomeSkeletonSlot(
    withResume: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(Res.string.home_up_next_loading_a11y)
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            Row(
                Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = label },
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SkeletonBox(Modifier.size(SkeletonCover))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    SkeletonLineWidths.forEach { fraction ->
                        SkeletonBox(Modifier.fillMaxWidth(fraction).height(Spacing.md))
                    }
                }
            }
            if (withResume) {
                Button(
                    onClick = {},
                    enabled = false,
                    shapes = ButtonDefaults.shapes(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = SkeletonButtonHeight),
                ) {
                    Text(stringResource(Res.string.book_detail_resume))
                }
            }
        }
    }
}

/** "Everything in progress" over three card-sized skeletons, read as one "Loading books". */
@Composable
internal fun HomeSkeletonRail(modifier: Modifier = Modifier) {
    val label = stringResource(Res.string.home_loading_books_a11y)
    Column(modifier.fillMaxWidth()) {
        SectionTitle(
            title = stringResource(Res.string.home_everything_in_progress),
            modifier = Modifier.padding(horizontal = Spacing.screenMargin),
        )
        Spacer(Modifier.height(Spacing.titleGap))
        Row(
            Modifier.padding(horizontal = Spacing.screenMargin).clearAndSetSemantics { contentDescription = label },
            horizontalArrangement = Arrangement.spacedBy(Spacing.itemGap),
        ) {
            repeat(SKELETON_RAIL_CARDS) { SkeletonBox(Modifier.width(SkeletonRailCard).aspectRatio(1f)) }
        }
    }
}

/**
 * Home while its state is loading (spec §3.3 page loading): a greeting-sized bar in the shell header, the slot and rail
 * skeletons, and no strip. Replaces the spinner (H14).
 */
@Composable
internal fun HomeLoadingBody(
    width: HomeWidth,
    appHeader: AppHeaderSlot,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(Spacing.sectionGap),
    ) {
        appHeader { SkeletonBox(Modifier.size(GreetingBarWidth, GreetingBarHeight)) }
        val margin = Modifier.padding(horizontal = Spacing.screenMargin)
        HomeSkeletonSlot(
            withResume = false,
            modifier =
                if (width ==
                    HomeWidth.Expanded
                ) {
                    margin.width(SlotSkeletonWide)
                } else {
                    margin
                },
        )
        HomeSkeletonRail()
    }
}

/** The slot skeleton's width beside where the stats would sit on a tablet. */
private val SlotSkeletonWide: Dp = 560.dp

@Composable
private fun SkeletonBox(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant, ContentShapes.card))
}
