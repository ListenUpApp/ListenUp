package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ElevatedCoverCard
import com.calypsan.listenup.client.design.components.ProgressOverlay
import com.calypsan.listenup.client.design.theme.ContentShapes
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.BookSeries
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_abridged
import listenup.composeapp.generated.resources.book_detail_narrated_by
import listenup.composeapp.generated.resources.book_detail_other_authors
import listenup.composeapp.generated.resources.book_detail_other_narrators
import listenup.composeapp.generated.resources.book_detail_unabridged
import listenup.composeapp.generated.resources.series_book_sequence
import org.jetbrains.compose.resources.stringResource

/**
 * Centered "compact" hero for the Book Detail screen (phone layout).
 *
 * Stacks a 200×200 cover card, an optional overline in the brand coral, a large display title
 * (max 2 lines), a subtitle, an author line, and an optional narrator line — all centered on the
 * [MaterialTheme.colorScheme.surface] background. Stat chips belong to the StatsRow above the
 * scroll content, not here.
 *
 * @param coverPath Local cover file path, or null to show the placeholder
 * @param bookId Book ID for server-URL fallback cover loading
 * @param title Book title (max 2 lines, ellipsised)
 * @param genre Most-specific genre name shown as an uppercase label above the title (e.g. "Progression
 *   Fantasy"); null hides it
 * @param abridged Whether the book is abridged — drives the Abridged/Unabridged flag beside the
 *   genre label
 * @param subtitle Independent subtitle line (e.g. "The Final Empire"); null/blank hides it. The
 *   caller suppresses subtitles that merely restate a series, so this is shown verbatim when present
 * @param series Series memberships rendered as tappable chips; empty hides the row
 * @param authors Author contributors — each name is individually tappable
 * @param narrators Narrator contributors — each name is individually tappable; empty hides the row
 * @param onContributorClick Invoked with a contributor id when an author or narrator name is tapped
 * @param onSeriesClick Invoked with a series id when a series chip is tapped
 * @param onShowCast Invoked with the role whose folded line ("{lead}, N other …") was tapped, to
 *   open the full-cast overlay for that role
 * @param progress Playback progress from 0.0 to 1.0; null hides the [ProgressOverlay]
 * @param timeRemaining Formatted time remaining (e.g. "21h 30m left"); null hides the label
 * @param modifier Optional layout modifier
 */
@Suppress("LongParameterList")
@Composable
fun CompactHero(
    coverPath: String?,
    coverHash: String?,
    bookId: String,
    title: String,
    genre: String?,
    abridged: Boolean,
    subtitle: String?,
    series: List<BookSeries>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    progress: Float?,
    timeRemaining: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Cover — 200×200 dp, with an optional progress pill at the bottom
        ElevatedCoverCard(
            path = coverPath,
            bookId = bookId,
            coverHash = coverHash,
            contentDescription = title,
            title = title,
            author = authors.firstOrNull()?.name.orEmpty(),
            modifier = Modifier.size(200.dp),
        ) {
            progress?.let { prog ->
                ProgressOverlay(
                    progress = prog,
                    timeRemaining = timeRemaining,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }

        // Classification — most-specific genre label beside the Abridged/Unabridged flag
        HeroClassification(
            genre = genre,
            abridged = abridged,
            classificationColor = MaterialTheme.colorScheme.primary,
            centered = true,
            // larger cover→classification gap than the 8dp column rhythm
            modifier = Modifier.padding(top = 16.dp),
        )

        // Title — large display text, max 2 lines
        Text(
            text = title,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        // Subtitle — an independent quiet, italic line; hidden when null/blank
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.titleMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }

        // Author — up to two names individually tappable; folds to "{lead}, N other authors" beyond
        if (authors.isNotEmpty()) {
            ClickableContributorLine(
                contributors = authors,
                onContributorClick = onContributorClick,
                style = MaterialTheme.typography.titleSmall,
                nameColor = MaterialTheme.colorScheme.onSurface,
                separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_authors,
                onOverflowClick = { onShowCast(CastRole.Authors) },
            )
        }

        // Narrator — record_voice_over icon + tappable names; folds to "{lead}, N others" beyond two
        if (narrators.isNotEmpty()) {
            ClickableContributorLine(
                contributors = narrators,
                onContributorClick = onContributorClick,
                style = MaterialTheme.typography.bodyMedium,
                nameColor = MaterialTheme.colorScheme.onSurfaceVariant,
                separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                prefix = "${stringResource(Res.string.book_detail_narrated_by)} ",
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_narrators,
                onOverflowClick = { onShowCast(CastRole.Narrators) },
            )
        }

        // Series — tappable chips, one per membership (Mistborn · Book 1, The Cosmere · Book 3)
        if (series.isNotEmpty()) {
            SeriesChips(
                series = series,
                onSeriesClick = onSeriesClick,
                contentColor = MaterialTheme.colorScheme.onSurface,
                centered = true,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            )
        }
    }
}

/**
 * Wide "color band" hero for the Book Detail screen (tablet / desktop layout).
 *
 * A [MaterialTheme.colorScheme.primaryContainer] surface that spans the full width, carrying the
 * book identity — cover, overline, title, subtitle, author, and narrator — arranged in a horizontal
 * row. Identity only: no Play FAB (the Play + Download group lives in a separate actions section).
 *
 * A decorative oversized blob is rendered top-right behind the content at 16 % primary opacity,
 * clipped to the band so it never overflows. The shape is intentionally organic to break up the
 * rectangular band without distracting from the content.
 *
 * @param coverPath Local cover file path, or null to show the placeholder
 * @param bookId Book ID for server-URL fallback cover loading
 * @param title Book title (max 2 lines, ellipsised)
 * @param genre Most-specific genre name shown as an uppercase label above the title (e.g. "Progression
 *   Fantasy"); null hides it
 * @param abridged Whether the book is abridged — drives the Abridged/Unabridged flag beside the
 *   genre label
 * @param subtitle Independent subtitle line (e.g. "The Final Empire"); null/blank hides it. The
 *   caller suppresses subtitles that merely restate a series, so this is shown verbatim when present
 * @param series Series memberships rendered as tappable chips; empty hides the row
 * @param authors Author contributors — each name is individually tappable
 * @param narrators Narrator contributors — each name is individually tappable; empty hides the row
 * @param onContributorClick Invoked with a contributor id when an author or narrator name is tapped
 * @param onSeriesClick Invoked with a series id when a series chip is tapped
 * @param onShowCast Invoked with the role whose folded line ("{lead}, N other …") was tapped, to
 *   open the full-cast overlay for that role
 * @param progress Playback progress from 0.0 to 1.0; null hides the [ProgressOverlay]
 * @param timeRemaining Formatted time remaining (e.g. "21h 30m left"); null hides the label
 * @param rating Average rating (0–5); null/≤0 hides the rating stat chip
 * @param duration Total duration in milliseconds, shown as the duration stat chip
 * @param year Publication year; null/≤0 hides the year stat chip
 * @param addedAt Epoch millis the book was added; null hides the lead "Added" accent stat chip
 * @param modifier Optional layout modifier
 */
@Suppress("LongParameterList")
@Composable
fun WideHeroBand(
    coverPath: String?,
    coverHash: String?,
    bookId: String,
    title: String,
    genre: String?,
    abridged: Boolean,
    subtitle: String?,
    series: List<BookSeries>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    progress: Float?,
    timeRemaining: String?,
    rating: Double?,
    duration: Long,
    year: Int?,
    addedAt: Long?,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = ContentShapes.card,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Clip the blob to the band so it never overflows the surface bounds
        Box(modifier = Modifier.fillMaxWidth().clip(ContentShapes.card)) {
            // Decorative background blob — top-right, oversized, organic corners, very subtle
            Box(
                modifier =
                    Modifier
                        .size(340.dp)
                        .offset(x = (-80).dp, y = (-100).dp)
                        .align(Alignment.TopEnd)
                        .clip(
                            RoundedCornerShape(
                                topStart = 40.dp,
                                topEnd = 120.dp,
                                bottomEnd = 80.dp,
                                bottomStart = 160.dp,
                            ),
                        ),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                    modifier = Modifier.matchParentSize(),
                ) {}
            }

            // Main content row
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 44.dp, vertical = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Cover — 240×240 dp with optional progress pill
                ElevatedCoverCard(
                    path = coverPath,
                    bookId = bookId,
                    coverHash = coverHash,
                    contentDescription = title,
                    title = title,
                    author = authors.firstOrNull()?.name.orEmpty(),
                    modifier = Modifier.size(240.dp),
                ) {
                    progress?.let { prog ->
                        ProgressOverlay(
                            progress = prog,
                            timeRemaining = timeRemaining,
                            modifier = Modifier.align(Alignment.BottomCenter),
                        )
                    }
                }

                // Identity column
                WideHeroIdentity(
                    title = title,
                    genre = genre,
                    abridged = abridged,
                    subtitle = subtitle,
                    series = series,
                    authors = authors,
                    narrators = narrators,
                    onContributorClick = onContributorClick,
                    onSeriesClick = onSeriesClick,
                    onShowCast = onShowCast,
                    rating = rating,
                    duration = duration,
                    year = year,
                    addedAt = addedAt,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The identity text column inside [WideHeroBand]: classification flag, title, optional subtitle,
 * the author · narrator row, optional series chips, and — as the last element, matching the design —
 * the [StatsRow] recoloured to read on the colour band. Extracted so [WideHeroBand] stays within the
 * method-length budget.
 */
@Suppress("LongParameterList")
@Composable
private fun WideHeroIdentity(
    title: String,
    genre: String?,
    abridged: Boolean,
    subtitle: String?,
    series: List<BookSeries>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    onSeriesClick: (seriesId: String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    rating: Double?,
    duration: Long,
    year: Int?,
    addedAt: Long?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Classification — most-specific genre label beside the Abridged/Unabridged flag
        HeroClassification(
            genre = genre,
            abridged = abridged,
            classificationColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
            centered = false,
        )

        // Title — large display, max 2 lines
        Text(
            text = title,
            style =
                MaterialTheme.typography.displaySmall.copy(
                    fontFamily = DisplayFontFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )

        // Subtitle — an independent quiet, italic line; hidden when null/blank
        if (!subtitle.isNullOrBlank()) {
            Text(
                text = subtitle,
                style =
                    MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Medium,
                        fontStyle = FontStyle.Italic,
                    ),
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f),
            )
        }

        // Author · narrator row
        WideContributorRow(
            authors = authors,
            narrators = narrators,
            onContributorClick = onContributorClick,
            onShowCast = onShowCast,
            modifier = Modifier.padding(top = 14.dp),
        )

        // Series — tappable chips, one per membership; hidden when empty
        if (series.isNotEmpty()) {
            SeriesChips(
                series = series,
                onSeriesClick = onSeriesClick,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                centered = false,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            )
        }

        // Stats — Added (accent) · Duration · Year · Rating, last in the identity column and
        // recoloured to read on the colour band.
        StatsRow(
            rating = rating,
            duration = duration,
            year = year,
            addedAt = addedAt,
            modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.Start),
            onHeroBand = true,
        )
    }
}

/**
 * The classification row shared by both heroes: the most-specific [genre] and the
 * Abridged/Unabridged flag, both rendered as plain uppercase `labelMedium` text in the brand-accent
 * [classificationColor] — the hero's flag style, not a pill. (`FacetChip` pills are reserved for the
 * list/section surfaces where the facet is interactive.)
 *
 * The genre is omitted when null; the flag is always shown. [centered] aligns the row for the
 * compact (centered) hero versus the wide (left-aligned) band.
 */
@Composable
private fun HeroClassification(
    genre: String?,
    abridged: Boolean,
    classificationColor: Color,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    val classification =
        stringResource(
            if (abridged) Res.string.book_detail_abridged else Res.string.book_detail_unabridged,
        )
    Row(
        modifier = modifier,
        horizontalArrangement =
            Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The most-specific genre reads as the same understated flag as Abridged/Unabridged — a
        // plain uppercase label, not a filled pill.
        if (!genre.isNullOrBlank()) {
            Text(
                text = genre.uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = classificationColor,
            )
            Text(
                text = "•",
                style = MaterialTheme.typography.labelMedium,
                color = classificationColor,
            )
        }
        Text(
            text = classification.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = classificationColor,
        )
    }
}

/**
 * The author · narrator credit row for [WideHeroBand]: tappable author names, a dot separator, then
 * a [Icons.Default.RecordVoiceOver] icon, the "Narrated by" prefix, and tappable narrator names —
 * all in [MaterialTheme.colorScheme.onPrimaryContainer] tones to read against the colour band.
 */
@Composable
private fun WideContributorRow(
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Stacked vertically (author over narrator) so long names never overflow a single row and
    // collapse character-by-character; each line is full-width and left-aligned.
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier,
    ) {
        if (authors.isNotEmpty()) {
            ClickableContributorLine(
                contributors = authors,
                onContributorClick = onContributorClick,
                style = MaterialTheme.typography.titleMedium,
                nameColor = MaterialTheme.colorScheme.onPrimaryContainer,
                separatorColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.Start),
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_authors,
                onOverflowClick = { onShowCast(CastRole.Authors) },
            )
        }

        if (narrators.isNotEmpty()) {
            ClickableContributorLine(
                contributors = narrators,
                onContributorClick = onContributorClick,
                style = MaterialTheme.typography.titleMedium,
                nameColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                separatorColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(0.dp, Alignment.Start),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.RecordVoiceOver,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.86f),
                    )
                },
                prefix = "${stringResource(Res.string.book_detail_narrated_by)} ",
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_narrators,
                onOverflowClick = { onShowCast(CastRole.Narrators) },
            )
        }
    }
}

/**
 * A wrapping row of tappable series chips — one per [BookSeries] membership, so a book in several
 * series (e.g. "Mistborn · Book 1" and "The Cosmere · Book 3") shows one chip each.
 *
 * Each chip is a tonal pill (a faint [contentColor] wash) carrying a stacked-books icon, the series
 * name, and — when a sequence is known — a "Book N" position. The whole chip routes to the series
 * via [onSeriesClick]. Colours are passed in so the same component reads correctly both on the wide
 * hero's colour band ([MaterialTheme.colorScheme.onPrimaryContainer]) and the compact hero's
 * surface ([MaterialTheme.colorScheme.onSurface]).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeriesChips(
    series: List<BookSeries>,
    onSeriesClick: (seriesId: String) -> Unit,
    contentColor: Color,
    centered: Boolean,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement =
            Arrangement.spacedBy(8.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        series.forEach { membership ->
            SeriesChip(
                membership = membership,
                contentColor = contentColor,
                onClick = { onSeriesClick(membership.seriesId) },
            )
        }
    }
}

/** A single series pill: stacked-books icon · series name · optional "Book N" position. */
@Composable
private fun SeriesChip(
    membership: BookSeries,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier =
            modifier
                .clip(RoundedCornerShape(percent = 50))
                .clickable(onClick = onClick)
                .background(contentColor.copy(alpha = 0.12f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = null,
            modifier = Modifier.size(17.dp),
            tint = contentColor,
        )
        Text(
            text = membership.seriesName,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = contentColor,
        )
        val sequence = membership.sequence
        if (!sequence.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier
                        .size(3.5.dp)
                        .background(contentColor.copy(alpha = 0.45f), CircleShape),
            )
            Text(
                text = stringResource(Res.string.series_book_sequence, sequence),
                style = MaterialTheme.typography.labelLarge,
                color = contentColor.copy(alpha = 0.72f),
            )
        }
    }
}
