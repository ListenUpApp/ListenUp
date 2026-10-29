package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.calypsan.listenup.client.design.text.LayoutStart
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.BookCoverImage
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.transitions.bookCoverHeroKey
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.contributors.CastRole
import com.calypsan.listenup.client.features.contributors.ClickableContributorLine
import com.calypsan.listenup.client.presentation.bookdetail.BookSeriesPath
import com.calypsan.listenup.client.presentation.bookdetail.HERO_CONTRIBUTOR_FOLD_LIMIT
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_all_credits
import listenup.composeapp.generated.resources.book_detail_other_authors
import listenup.composeapp.generated.resources.book_detail_other_narrators
import listenup.composeapp.generated.resources.book_detail_read_by
import org.jetbrains.compose.resources.stringResource

/** The phone hero's square cover (board rev 7: 232 dp, flat, 28 dp corners). */
internal val PhoneHeroCoverSize = 232.dp

/** The tablet hero's square cover. */
private val WideHeroCoverSize = 240.dp

/**
 * Book Detail's hero on a phone (spec §3.2.1 D2–D10): a flat square cover, the series path, the title (the screen's
 * one display moment), the subtitle, the authors, "Read by … · All credits", and the quick-stat chips. It sits on the
 * screen's own `surface`: on Android the cover carries the colour and the hero takes no wash (Simon, 2026-10-10:
 * surface roles only, so Material You stays coherent). No shadow under the cover (M3E: elevation by tone, not shadow).
 */
@Suppress("LongParameterList")
@Composable
internal fun BookDetailHeroBand(
    coverPath: String?,
    coverHash: String?,
    bookId: String,
    title: String,
    subtitle: String?,
    seriesPaths: List<BookSeriesPath>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    durationMs: Long,
    year: Int?,
    abridged: Boolean,
    onContributorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HeroCover(
            coverPath = coverPath,
            coverHash = coverHash,
            bookId = bookId,
            title = title,
            authors = authors,
            size = PhoneHeroCoverSize,
        )
        HeroIdentity(
            title = title,
            subtitle = subtitle,
            seriesPaths = seriesPaths,
            authors = authors,
            narrators = narrators,
            centered = true,
            titleStyleSize = TitleSize.Phone,
            onContributorClick = onContributorClick,
            onSeriesClick = onSeriesClick,
            onShowCast = onShowCast,
        )
        HeroStatChips(durationMs = durationMs, year = year, abridged = abridged, centered = true)
    }
}

/**
 * Book Detail's hero on a wide layout (spec §3.2.4: Android tablet ≥ 840 dp): the cover on the left, and beside it
 * the same identity as the phone, then the [resumeUnit], the [actions] and the [social] line inside the hero so the
 * Resume unit, the action group and who else is reading stay with the book they act on. On `surface`, like the phone
 * hero.
 */
@Suppress("LongParameterList")
@Composable
internal fun WideHeroBand(
    coverPath: String?,
    coverHash: String?,
    bookId: String,
    title: String,
    subtitle: String?,
    seriesPaths: List<BookSeriesPath>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    durationMs: Long,
    year: Int?,
    abridged: Boolean,
    onContributorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onShowCast: (CastRole) -> Unit,
    resumeUnit: @Composable () -> Unit,
    actions: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    social: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.screenMargin, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.spacedBy(40.dp),
        verticalAlignment = Alignment.Top,
    ) {
        HeroCover(
            coverPath = coverPath,
            coverHash = coverHash,
            bookId = bookId,
            title = title,
            authors = authors,
            size = WideHeroCoverSize,
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            HeroIdentity(
                title = title,
                subtitle = subtitle,
                seriesPaths = seriesPaths,
                authors = authors,
                narrators = narrators,
                centered = false,
                titleStyleSize = TitleSize.Wide,
                onContributorClick = onContributorClick,
                onSeriesClick = onSeriesClick,
                onShowCast = onShowCast,
            )
            HeroStatChips(durationMs = durationMs, year = year, abridged = abridged, centered = false)
            // Capped so Resume holds at least half the row and the unit's line keeps a readable length.
            Column(
                modifier = Modifier.widthIn(max = 640.dp).padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                resumeUnit()
                actions()
                social()
            }
        }
    }
}

@Composable
private fun HeroCover(
    coverPath: String?,
    coverHash: String?,
    bookId: String,
    title: String,
    authors: List<BookContributor>,
    size: Dp,
) {
    val shape = MaterialTheme.shapes.extraLarge
    BookCoverImage(
        bookId = bookId,
        coverPath = coverPath,
        coverHash = coverHash,
        contentDescription = title,
        title = title,
        author = authors.firstOrNull()?.name.orEmpty(),
        heroKey = bookCoverHeroKey(bookId),
        heroClipShape = shape,
        modifier = Modifier.size(size).clip(shape),
    )
}

/** The phone and wide titles differ only in size; both are the screen's one ExtraBold display moment. */
private enum class TitleSize { Phone, Wide }

@Suppress("LongParameterList")
@Composable
private fun ColumnScope.HeroIdentity(
    title: String,
    subtitle: String?,
    seriesPaths: List<BookSeriesPath>,
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    centered: Boolean,
    titleStyleSize: TitleSize,
    onContributorClick: (String) -> Unit,
    onSeriesClick: (String) -> Unit,
    onShowCast: (CastRole) -> Unit,
) {
    val textAlign = if (centered) TextAlign.Center else TextAlign.LayoutStart
    val lineArrangement = Arrangement.spacedBy(0.dp, if (centered) Alignment.CenterHorizontally else Alignment.Start)

    // Series path above the title: where the book sits, read before what it is called (board rev 7).
    if (seriesPaths.isNotEmpty()) {
        SeriesPathLines(
            paths = seriesPaths,
            onSeriesClick = onSeriesClick,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            centered = centered,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val baseTitleStyle =
        if (titleStyleSize ==
            TitleSize.Phone
        ) {
            MaterialTheme.typography.displaySmall
        } else {
            MaterialTheme.typography.displayMedium
        }
    Text(
        text = title,
        style = baseTitleStyle.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight.ExtraBold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = textAlign,
        maxLines = 3,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().semantics { heading() },
    )

    if (!subtitle.isNullOrBlank()) {
        Text(
            text = subtitle,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = textAlign,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (authors.isNotEmpty()) {
        ClickableContributorLine(
            contributors = authors,
            onContributorClick = onContributorClick,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            nameColor = MaterialTheme.colorScheme.onSurface,
            separatorColor = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = lineArrangement,
            foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
            overflowTextRes = Res.string.book_detail_other_authors,
            onOverflowClick = { onShowCast(CastRole.Authors) },
        )
    }

    NarratorsAndCredits(
        narrators = narrators,
        centered = centered,
        onContributorClick = onContributorClick,
        onShowCast = onShowCast,
    )
}

/** "Read by Michael Kramer & Kate Reading · All credits": narrator links, then one link to the whole cast (D7, D8). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NarratorsAndCredits(
    narrators: List<BookContributor>,
    centered: Boolean,
    onContributorClick: (String) -> Unit,
    onShowCast: (CastRole) -> Unit,
) {
    val haptics = LocalHaptics.current
    val style = MaterialTheme.typography.bodyLarge
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
            Arrangement.spacedBy(
                0.dp,
                if (centered) Alignment.CenterHorizontally else Alignment.Start,
            ),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (narrators.isNotEmpty()) {
            ClickableContributorLine(
                contributors = narrators,
                onContributorClick = onContributorClick,
                style = style,
                nameColor = muted,
                separatorColor = muted,
                prefix = "${stringResource(Res.string.book_detail_read_by)} ",
                foldLimit = HERO_CONTRIBUTOR_FOLD_LIMIT,
                overflowTextRes = Res.string.book_detail_other_narrators,
                onOverflowClick = { onShowCast(CastRole.Narrators) },
            )
        }
        // One item: the separator travels with its link, so a wrap never leaves a dangling "·".
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (narrators.isNotEmpty()) Text(text = " · ", style = style, color = muted)
            Text(
                text = stringResource(Res.string.book_detail_all_credits),
                style = style.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier.minimumInteractiveComponentSize().clickable(role = Role.Button) {
                        haptics.press()
                        onShowCast(CastRole.All)
                    },
            )
        }
    }
}
