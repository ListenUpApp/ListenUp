package com.calypsan.listenup.client.features.bookdetail.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.DisplayFontFamily
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.presentation.bookdetail.AudioFormat
import com.calypsan.listenup.client.presentation.bookdetail.CreditRoleGroup
import com.calypsan.listenup.client.presentation.bookdetail.groupContributorsByRole
import com.calypsan.listenup.client.presentation.bookdetail.languageDisplayName
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_details
import listenup.composeapp.generated.resources.book_detail_format
import listenup.composeapp.generated.resources.book_detail_language
import listenup.composeapp.generated.resources.book_detail_published
import listenup.composeapp.generated.resources.book_detail_publisher
import org.jetbrains.compose.resources.stringResource

/**
 * Book "Details" section: formal metadata rows (publisher, published year, language, audio format)
 * followed by the contributor credits grouped by role. Renders nothing when there is neither
 * metadata nor credits. Each metadata row mirrors the credit-row style: value on the left, dim
 * label on the right.
 */
@Composable
fun DetailsSection(
    publisher: String?,
    publishYear: Int?,
    language: String?,
    audioFormat: AudioFormat?,
    credits: List<BookContributor>,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasMetadata =
        !publisher.isNullOrBlank() || publishYear != null || !language.isNullOrBlank() || audioFormat != null
    if (!hasMetadata && credits.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stringResource(Res.string.book_detail_details),
            style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFontFamily, fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        if (!publisher.isNullOrBlank()) {
            MetadataRow(value = publisher, label = stringResource(Res.string.book_detail_publisher))
        }
        if (publishYear != null) {
            MetadataRow(value = publishYear.toString(), label = stringResource(Res.string.book_detail_published))
        }
        if (!language.isNullOrBlank()) {
            MetadataRow(value = languageDisplayName(language), label = stringResource(Res.string.book_detail_language))
        }
        if (audioFormat != null) {
            MetadataRow(value = audioFormat.displayLabel(), label = stringResource(Res.string.book_detail_format))
        }
        if (hasMetadata && credits.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        val groups = groupContributorsByRole(credits)
        groups.forEachIndexed { index, group ->
            CreditRow(group = group, onContributorClick = onContributorClick)
            if (index < groups.lastIndex) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** A metadata row: emphasis value on the left, dim label on the right — matching [CreditRow]. */
@Composable
private fun MetadataRow(value: String, label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * A single credits row: the role's contributor names on the left (each tappable, wrapping across
 * lines when there are many), and the role label on the right.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CreditRow(
    group: CreditRoleGroup,
    onContributorClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlowRow(modifier = Modifier.weight(1f)) {
            group.contributors.forEachIndexed { index, contributor ->
                Text(
                    text = contributor.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier =
                        Modifier
                            .semantics { this.role = Role.Button }
                            .clickable { onContributorClick(contributor.id) },
                )
                if (index < group.contributors.lastIndex) {
                    Text(
                        text = ", ",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = group.roleLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
