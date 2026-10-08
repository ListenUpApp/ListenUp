package com.calypsan.listenup.client.features.match

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.calypsan.listenup.api.dto.match.MatchTier
import com.calypsan.listenup.client.design.components.ContributorCoverImage
import com.calypsan.listenup.client.design.components.SectionGroup
import com.calypsan.listenup.client.design.components.SectionSegment
import com.calypsan.listenup.client.design.components.TonalLabel
import com.calypsan.listenup.client.design.components.avatarInitials
import com.calypsan.listenup.client.design.haptics.LocalHaptics
import com.calypsan.listenup.client.design.theme.Spacing
import com.calypsan.listenup.client.design.theme.extendedColors
import com.calypsan.listenup.client.presentation.match.PersonCandidateUi
import com.calypsan.listenup.client.presentation.match.PersonFindUiState
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.match_best_match
import listenup.composeapp.generated.resources.match_maybe
import listenup.composeapp.generated.resources.match_review_this_match_a11y
import listenup.composeapp.generated.resources.match_strong_match
import listenup.composeapp.generated.resources.match_your_current_link
import org.jetbrains.compose.resources.stringResource

/** Test tag of one person row, by the candidate's stable id. */
internal fun personTag(id: String): String = "match-person-$id"

/** The people found: their count (announced), the partial banner, then Strong match and Maybe groups. */
internal fun LazyListScope.personGroups(
    results: PersonFindUiState.Results,
    highlightPicked: Boolean,
    onPick: (PersonCandidateUi) -> Unit,
    onRetrySources: () -> Unit,
) {
    item(key = "count") { ResultCount(text = peopleCount(results.all.size)) }
    results.partialFailure?.let { partial -> item(key = "partial") { PartialBanner(partial, onRetrySources) } }
    listOf("strong" to results.strong, "maybe" to results.maybe)
        .filter { it.second.isNotEmpty() }
        .forEach { (key, people) ->
            item(key = key) {
                SectionGroup(
                    label =
                        stringResource(
                            if (people.first().tier == MatchTier.STRONG) Res.string.match_strong_match else Res.string.match_maybe,
                        ),
                ) {
                    people.forEach { person ->
                        PersonRow(
                            person = person,
                            picked = highlightPicked && person.key == results.pickedKey,
                            onPick = { onPick(person) },
                        )
                    }
                }
            }
        }
}

/**
 * One person: photo (or initials), name with its badges, "<Role> · <known works>", how many of their books are
 * here, and where they were found. Its accessible name says all of it in one breath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonRow(
    person: PersonCandidateUi,
    picked: Boolean,
    onPick: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val sources = sourcesPhrase(person.foundIn)
    val roleLine = person.roleLine()
    val libraryLine = person.libraryLine()
    val accessibleName =
        listOfNotNull(person.name, roleLine, libraryLine, sources)
            .filter { it.isNotEmpty() }
            .joinToString(". ", postfix = ".")
    val reviewLabel = stringResource(Res.string.match_review_this_match_a11y, person.name, sources)
    SectionSegment(modifier = Modifier.testTag(personTag(person.id))) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .then(if (picked) Modifier.background(MaterialTheme.colorScheme.secondaryContainer) else Modifier)
                    .clickable(onClickLabel = reviewLabel, role = Role.Button) {
                        haptics.press()
                        onPick()
                    }.semantics {
                        selected = picked
                        contentDescription = accessibleName
                    }.padding(Spacing.md),
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PersonPhoto(name = person.name, url = person.photoUrl, size = 64.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Text(
                        text = person.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (person.tier == MatchTier.STRONG) FontWeight.Bold else FontWeight.SemiBold,
                    )
                    PersonBadges(person = person)
                }
                if (roleLine.isNotEmpty()) {
                    Text(
                        roleLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                libraryLine?.let {
                    val here = person.libraryCount > 0
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = if (here) FontWeight.SemiBold else null,
                        color = if (here) MaterialTheme.extendedColors.success else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = person.foundIn.map { it.label }.distinct().joinToString(DOT),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Best match and Your current link — each a label, never colour alone. */
@Composable
private fun PersonBadges(person: PersonCandidateUi) {
    if (person.isBest) {
        TonalLabel(
            label = stringResource(Res.string.match_best_match),
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            icon = Icons.Outlined.StarOutline,
        )
    }
    if (person.isCurrentLink) {
        TonalLabel(label = stringResource(Res.string.match_your_current_link), icon = Icons.Outlined.Link)
    }
}

/** A catalogue's photo of a person, round, over their initials while it loads (or when there is none). */
@Composable
internal fun PersonPhoto(
    name: String,
    url: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    InitialsCircle(name = name, size = size, modifier = modifier) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
            )
        }
    }
}

/** The person's own stored photo, round, over their initials; only initials when they have no photo yet. */
@Composable
internal fun CurrentPersonPhoto(
    contributorId: String,
    name: String,
    imagePath: String?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    InitialsCircle(name = name, size = size, modifier = modifier) {
        if (imagePath != null) {
            ContributorCoverImage(
                contributorId = contributorId,
                imagePath = imagePath,
                contentDescription = null,
                modifier = Modifier.size(size),
            )
        }
    }
}

@Composable
private fun InitialsCircle(
    name: String,
    size: Dp,
    modifier: Modifier,
    image: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = avatarInitials(name),
            style = initialsStyle(size),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // A stand-in for the photo: decoration, never read out as "R P".
            modifier = Modifier.clearAndSetSemantics {},
        )
        image()
    }
}

@Composable
private fun initialsStyle(size: Dp): TextStyle =
    if (size >= LARGE_PHOTO) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium

/** From this size the initials grow to a headline. */
private val LARGE_PHOTO = 96.dp
