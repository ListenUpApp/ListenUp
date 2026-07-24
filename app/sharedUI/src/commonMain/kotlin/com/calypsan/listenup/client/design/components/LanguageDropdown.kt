package com.calypsan.listenup.client.design.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.domain.model.Language
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.common_clear_selection
import listenup.composeapp.generated.resources.common_no_items_found
import listenup.composeapp.generated.resources.common_search_languages

/**
 * Searchable dropdown for selecting a language.
 *
 * Features:
 * - Type to filter languages by name or code
 * - Common languages appear at the top
 * - Shows display name but stores ISO 639-1 code
 *
 * @param selectedCode Currently selected ISO 639-1 code, or null if none
 * @param onLanguageSelected Callback with selected ISO code, or null to clear
 * @param modifier Optional modifier
 * @param label Label for the field
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguageDropdown(
    selectedCode: String?,
    onLanguageSelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Language",
) {
    var expanded by remember { mutableStateOf(false) }
    var filterText by remember { mutableStateOf("") }

    // When not expanded, show the selected language name
    // When expanded, show the filter text for searching
    val displayText =
        if (expanded) {
            filterText
        } else {
            selectedCode?.let { Language.getDisplayName(it) } ?: ""
        }

    // Filter languages based on search text
    val filteredLanguages =
        remember(filterText) {
            Language.filterLanguages(filterText)
        }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { newExpanded ->
            expanded = newExpanded
            if (newExpanded) {
                // Clear filter when opening
                filterText = ""
            }
        },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = displayText,
            onValueChange = { filterText = it },
            readOnly = !expanded, // Only allow typing when expanded
            label = { Text(label) },
            placeholder =
                if (expanded) {
                    { Text(stringResource(Res.string.common_search_languages)) }
                } else {
                    null
                },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
            shape = MaterialTheme.shapes.medium,
            singleLine = true,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 300.dp),
        ) {
            // Option to clear selection
            if (selectedCode != null) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.common_clear_selection),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = {
                        onLanguageSelected(null)
                        expanded = false
                    },
                )
            }

            // Language options
            filteredLanguages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onLanguageSelected(code)
                        expanded = false
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }

            // Show message if no results
            if (filteredLanguages.isEmpty() && filterText.isNotEmpty()) {
                DropdownMenuItem(
                    text = {
                        Text(
                            text = stringResource(Res.string.common_no_items_found, "languages"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    onClick = { },
                    enabled = false,
                )
            }
        }
    }
}
