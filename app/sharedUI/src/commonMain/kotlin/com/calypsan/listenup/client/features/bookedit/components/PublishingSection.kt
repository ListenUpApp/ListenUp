package com.calypsan.listenup.client.features.bookedit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.components.LanguageDropdown
import com.calypsan.listenup.client.design.components.ListenUpTextField

/**
 * Publishing information section: Publisher, Year, Language.
 */
@Composable
fun PublishingSection(
    publisher: String,
    publishYear: String,
    language: String?,
    onPublisherChange: (String) -> Unit,
    onPublishYearChange: (String) -> Unit,
    onLanguageChange: (String?) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListenUpTextField(
            value = publisher,
            onValueChange = onPublisherChange,
            label = "Publisher",
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListenUpTextField(
                value = publishYear,
                onValueChange = onPublishYearChange,
                label = "Year",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f),
            )

            LanguageDropdown(
                selectedCode = language,
                onLanguageSelected = onLanguageChange,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
