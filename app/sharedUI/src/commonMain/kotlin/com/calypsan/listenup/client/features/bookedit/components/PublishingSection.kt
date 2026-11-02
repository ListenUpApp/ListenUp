package com.calypsan.listenup.client.features.bookedit.components

import com.calypsan.listenup.client.design.components.transformedBy
import com.calypsan.listenup.client.design.components.rememberForwardingTextFieldState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import listenup.composeapp.generated.resources.Res
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.book_edit_publisher
import listenup.composeapp.generated.resources.book_edit_year

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
    val publisherState = rememberForwardingTextFieldState(publisher, onEdited = onPublisherChange)
    val yearState = rememberForwardingTextFieldState(publishYear, onEdited = onPublishYearChange)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ListenUpTextField(
            state = publisherState,
            label = stringResource(Res.string.book_edit_publisher),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            ListenUpTextField(
                state = yearState,
                label = stringResource(Res.string.book_edit_year),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                // Restricted inside the field: the VM only ever hears digits, and re-normalizes once
                // more at the save boundary.
                inputTransformation = yearTransformation,
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

private val yearTransformation = transformedBy { it.filter(Char::isDigit).take(4) }
