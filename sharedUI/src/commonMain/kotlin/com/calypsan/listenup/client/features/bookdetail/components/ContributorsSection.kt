package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.domain.model.ContributorRole

/**
 * Authors and Narrators displayed as centered, clickable text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TalentSection(
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (authors.isEmpty() && narrators.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Authors line
        if (authors.isNotEmpty()) {
            TalentLine(
                prefix = "By ",
                contributors = authors,
                onContributorClick = onContributorClick,
            )
        }

        // Narrators line
        if (narrators.isNotEmpty()) {
            TalentLine(
                prefix = "Narrated by ",
                contributors = narrators,
                onContributorClick = onContributorClick,
            )
        }
    }
}

/**
 * A single talent line like "By **Stephen King** & **Peter Straub**"
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TalentLine(
    prefix: String,
    contributors: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Center,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = horizontalArrangement,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = prefix,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        contributors.forEachIndexed { index, contributor ->
            Text(
                text = contributor.name,
                style =
                    MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                    ),
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onContributorClick(contributor.id) },
            )

            if (index < contributors.size - 1) {
                val separator = if (index == contributors.size - 2) " & " else ", "
                Text(
                    text = separator,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Extended talent section that includes additional contributor roles beyond authors and narrators.
 * Displays roles like Editor, Translator, etc.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TalentSectionWithRoles(
    authors: List<BookContributor>,
    narrators: List<BookContributor>,
    allContributors: List<BookContributor>,
    onContributorClick: (contributorId: String) -> Unit,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
) {
    if (authors.isEmpty() && narrators.isEmpty() && allContributors.isEmpty()) return

    val talentArrangement = if (horizontalAlignment == Alignment.Start) Arrangement.Start else Arrangement.Center

    // Known primary roles to exclude from "additional roles"
    val primaryRoles = setOf(ContributorRole.AUTHOR.apiValue, ContributorRole.NARRATOR.apiValue, "writer")

    // Group contributors by their non-primary roles
    val additionalRoleGroups =
        allContributors
            .flatMap { contributor ->
                contributor.roles
                    .filter { role -> role.lowercase() !in primaryRoles }
                    .map { role -> role to contributor }
            }.groupBy({ it.first }, { it.second })
            .mapValues { (_, contributors) -> contributors.distinctBy { it.id } }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Authors line
        if (authors.isNotEmpty()) {
            TalentLine(
                prefix = "By ",
                contributors = authors,
                onContributorClick = onContributorClick,
                horizontalArrangement = talentArrangement,
            )
        }

        // Narrators line
        if (narrators.isNotEmpty()) {
            TalentLine(
                prefix = "Narrated by ",
                contributors = narrators,
                onContributorClick = onContributorClick,
                horizontalArrangement = talentArrangement,
            )
        }

        // Additional roles (e.g., Editor, Translator, etc.)
        additionalRoleGroups.forEach { (role, contributors) ->
            if (contributors.isNotEmpty()) {
                TalentLine(
                    prefix = formatRolePrefix(role),
                    contributors = contributors,
                    onContributorClick = onContributorClick,
                    horizontalArrangement = talentArrangement,
                )
            }
        }
    }
}

/**
 * Formats a role name into a display prefix.
 * e.g., "editor" -> "Edited by ", "translator" -> "Translated by "
 */
private fun formatRolePrefix(role: String): String {
    val normalizedRole = role.lowercase().trim()
    return when {
        normalizedRole.endsWith("or") -> {
            // editor -> Edited by, translator -> Translated by
            val base = normalizedRole.dropLast(2)
            "${base.replaceFirstChar { it.uppercase() }}ed by "
        }

        normalizedRole.endsWith("er") -> {
            // producer -> Produced by
            val base = normalizedRole.dropLast(2)
            "${base.replaceFirstChar { it.uppercase() }}ed by "
        }

        else -> {
            // Fallback: just capitalize and add "by"
            "${role.replaceFirstChar { it.uppercase() }} by "
        }
    }
}
