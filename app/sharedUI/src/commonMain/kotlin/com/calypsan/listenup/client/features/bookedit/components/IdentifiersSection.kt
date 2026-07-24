package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.ListenUpTextField
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_edit_abridged
import listenup.composeapp.generated.resources.book_edit_shortened_version_of_the_original

/**
 * Identifiers section: ISBN, ASIN, and Abridged toggle.
 */
@Composable
fun IdentifiersSection(
    isbn: String,
    asin: String,
    abridged: Boolean,
    onIsbnChange: (String) -> Unit,
    onAsinChange: (String) -> Unit,
    onAbridgedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListenUpTextField(
                value = isbn,
                onValueChange = onIsbnChange,
                label = "ISBN",
                modifier = Modifier.weight(1f),
            )

            ListenUpTextField(
                value = asin,
                onValueChange = onAsinChange,
                label = "ASIN",
                modifier = Modifier.weight(1f),
            )
        }

        // Abridged toggle
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { onAbridgedChange(!abridged) }
                    .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(Res.string.book_edit_abridged),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(Res.string.book_edit_shortened_version_of_the_original),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = abridged,
                onCheckedChange = onAbridgedChange,
            )
        }
    }
}
