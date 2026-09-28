package com.calypsan.listenup.client.features.settings

import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.TwoPaneMinWidth
import com.calypsan.listenup.client.design.components.DistributionMeter
import com.calypsan.listenup.client.design.components.FullScreenLoadingIndicator
import com.calypsan.listenup.client.design.components.HeroNavRow
import com.calypsan.listenup.client.design.components.LicenseChip
import com.calypsan.listenup.client.design.components.ListenUpSearchField
import com.calypsan.listenup.client.design.components.MeterSegment
import com.calypsan.listenup.client.design.theme.Spacing
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_back
import listenup.composeapp.generated.resources.licenses_count_suffix
import listenup.composeapp.generated.resources.licenses_families_subtitle
import listenup.composeapp.generated.resources.licenses_footer
import listenup.composeapp.generated.resources.licenses_overline_open_source
import listenup.composeapp.generated.resources.licenses_search_placeholder
import listenup.composeapp.generated.resources.licenses_section_libraries
import listenup.composeapp.generated.resources.licenses_subtitle_makes_possible
import listenup.composeapp.generated.resources.licenses_version_prefix
import org.jetbrains.compose.resources.stringResource
import com.calypsan.listenup.client.design.theme.ContentShapes
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.calypsan.listenup.client.design.theme.HeroInk

@Composable
fun LicensesScreen(
    onNavigateBack: () -> Unit,
    onLicenseClick: (String) -> Unit,
) {
    val rows by rememberLicenseRows()

    if (rows.isEmpty()) {
        FullScreenLoadingIndicator()
        return
    }

    var query by remember { mutableStateOf("") }
    val filtered =
        remember(rows, query) {
            if (query.isBlank()) rows else rows.filter { it.name.contains(query, ignoreCase = true) }
        }
    val familyCounts =
        remember(rows) {
            rows
                .groupBy { it.spdxId }
                .entries
                .sortedByDescending { it.value.size }
                .map { (spdxId, libs) -> spdxId to libs.size }
        }
    val segments =
        familyCounts.map { (spdxId, count) ->
            MeterSegment(
                label = spdxId,
                weight = count.toFloat(),
                color = licenseFamilyColor(spdxId).current.content,
            )
        }

    val isWide =
        currentWindowAdaptiveInfo()
            .windowSizeClass
            .isWidthAtLeastBreakpoint(TwoPaneMinWidth.value.toInt())

    if (isWide) {
        LicensesWideLayout(
            rows = rows,
            filtered = filtered,
            segments = segments,
            query = query,
            onQueryChange = { query = it },
            onLicenseClick = onLicenseClick,
            onNavigateBack = onNavigateBack,
        )
    } else {
        LicensesPhoneLayout(
            rows = rows,
            filtered = filtered,
            segments = segments,
            query = query,
            onQueryChange = { query = it },
            onLicenseClick = onLicenseClick,
            onNavigateBack = onNavigateBack,
        )
    }
}

@Composable
private fun LicensesPhoneLayout(
    rows: List<LicenseRow>,
    filtered: List<LicenseRow>,
    segments: List<MeterSegment>,
    query: String,
    onQueryChange: (String) -> Unit,
    onLicenseClick: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val sectionLabel = stringResource(Res.string.licenses_section_libraries)
    val searchPlaceholder = stringResource(Res.string.licenses_search_placeholder)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
        item {
            LicensesMobileHero(
                rowCount = rows.size,
                segments = segments,
                onNavigateBack = onNavigateBack,
            )
        }
        item {
            ListenUpSearchField(
                value = query,
                onValueChange = onQueryChange,
                onSubmit = {},
                placeholder = searchPlaceholder,
                onClear = { onQueryChange("") },
                modifier = Modifier.padding(horizontal = Spacing.screenMargin, vertical = Spacing.lg),
            )
        }
        item {
            LibrariesSectionHeader(
                label = sectionLabel,
                modifier =
                    Modifier.padding(
                        start = Spacing.screenMargin,
                        end = Spacing.screenMargin,
                        bottom = Spacing.xs,
                    ),
            )
        }
        itemsIndexed(filtered, key = { _, row -> row.uniqueId }) { index, row ->
            LicenseLibraryRow(
                row = row,
                onClick = { onLicenseClick(row.uniqueId) },
                showDivider = index < filtered.lastIndex,
                modifier = Modifier.padding(horizontal = Spacing.screenMargin),
            )
        }
        item {
            LicensesFooter(modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
        }
    }
}

@Composable
private fun LicensesMobileHero(
    rowCount: Int,
    segments: List<MeterSegment>,
    onNavigateBack: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = ContentShapes.hero,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            HeroNavRow(
                onBack = onNavigateBack,
                buttonBackground =
                    HeroInk.wash(
                        MaterialTheme.colorScheme.onTertiaryContainer,
                        MaterialTheme.colorScheme.tertiaryContainer,
                    ),
            )
            Column(modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 24.dp)) {
                Text(
                    text = stringResource(Res.string.licenses_overline_open_source).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        HeroInk.muted(
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    letterSpacing = 1.sp,
                )
                Row(
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // The count and its noun are one title: "42 licences", read as a single heading.
                    modifier = Modifier.padding(top = 6.dp).semantics(mergeDescendants = true) { heading() },
                ) {
                    Text(
                        text = rowCount.toString(),
                        style = MaterialTheme.typography.displayLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        letterSpacing = (-2).sp,
                    )
                    Text(
                        text = stringResource(Res.string.licenses_count_suffix),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                }
                Text(
                    text = stringResource(Res.string.licenses_subtitle_makes_possible),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color =
                        HeroInk.muted(
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    modifier = Modifier.padding(top = 8.dp),
                )
                Surface(
                    color =
                        HeroInk.wash(
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                ) {
                    DistributionMeter(
                        segments = segments,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LicensesWideLayout(
    rows: List<LicenseRow>,
    filtered: List<LicenseRow>,
    segments: List<MeterSegment>,
    query: String,
    onQueryChange: (String) -> Unit,
    onLicenseClick: (String) -> Unit,
    onNavigateBack: () -> Unit,
) {
    val sectionLabel = stringResource(Res.string.licenses_section_libraries)
    val searchPlaceholder = stringResource(Res.string.licenses_search_placeholder)

    Column(modifier = Modifier.fillMaxSize()) {
        LicensesWideHero(onNavigateBack = onNavigateBack)
        Row(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.width(380.dp).fillMaxHeight(),
            ) {
                Column(modifier = Modifier.padding(26.dp)) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        Text(
                            text = rows.size.toString(),
                            style = MaterialTheme.typography.displayLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            letterSpacing = (-2.4).sp,
                        )
                        Text(
                            text = stringResource(Res.string.licenses_count_suffix),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = stringResource(Res.string.licenses_families_subtitle, segments.size),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    DistributionMeter(
                        segments = segments,
                        modifier = Modifier.padding(top = 24.dp),
                    )
                    LicensesFooter(modifier = Modifier.padding(top = 16.dp))
                }
            }
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                item {
                    ListenUpSearchField(
                        value = query,
                        onValueChange = onQueryChange,
                        onSubmit = {},
                        placeholder = searchPlaceholder,
                        onClear = { onQueryChange("") },
                    )
                }
                item {
                    LibrariesSectionHeader(label = sectionLabel, modifier = Modifier.padding(start = 4.dp))
                }
                itemsIndexed(filtered, key = { _, row -> row.uniqueId }) { index, row ->
                    LicenseLibraryRow(
                        row = row,
                        onClick = { onLicenseClick(row.uniqueId) },
                        showDivider = index < filtered.lastIndex,
                    )
                }
            }
        }
    }
}

@Composable
private fun LicensesWideHero(onNavigateBack: () -> Unit) {
    val haptics = LocalHaptics.current
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp),
    ) {
        Row(
            modifier = Modifier.padding(end = 30.dp, top = 24.dp, bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            IconButton(
                onClick = {
                    haptics.press()
                    onNavigateBack()
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Column {
                Text(
                    text = stringResource(Res.string.licenses_overline_open_source).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color =
                        HeroInk.muted(
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            MaterialTheme.colorScheme.tertiaryContainer,
                        ),
                    letterSpacing = 1.sp,
                )
                Text(
                    text = stringResource(Res.string.licenses_section_libraries),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
            }
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The libraries list's subheader, in [SectionGroup]'s header voice — a sentence-case `titleSmall`
 * heading in `primary` — but emitted as a standalone composable so it can live as a lazy [item]
 * rather than inside a non-lazy [SectionGroup.content] slot.
 */
@Composable
private fun LibrariesSectionHeader(
    label: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.semantics { heading() },
    )
}

@Composable
private fun LicenseLibraryRow(
    row: LicenseRow,
    onClick: () -> Unit,
    showDivider: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    Column(modifier = modifier) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        haptics.press()
                        onClick()
                    }.padding(horizontal = Spacing.lg, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (row.version.isNotEmpty()) {
                    Text(
                        text = stringResource(Res.string.licenses_version_prefix, row.version),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (row.spdxId.isNotEmpty()) {
                LicenseChip(
                    label = row.spdxId,
                    color = licenseFamilyColor(row.spdxId),
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(modifier = Modifier.padding(horizontal = Spacing.lg))
        }
    }
}

@Composable
private fun LicensesFooter(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Favorite,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(17.dp).padding(top = 1.dp),
        )
        Text(
            text = stringResource(Res.string.licenses_footer),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
