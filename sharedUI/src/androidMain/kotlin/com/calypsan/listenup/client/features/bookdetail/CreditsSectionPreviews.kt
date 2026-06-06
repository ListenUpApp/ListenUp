package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.features.bookdetail.components.CreditsSection

private val PREVIEW_CREDITS =
    listOf(
        BookContributor(id = "c1", name = "Brandon Sanderson", roles = listOf("author")),
        BookContributor(id = "c2", name = "Michael Kramer", roles = listOf("narrator")),
        BookContributor(id = "c3", name = "Kate Reading", roles = listOf("narrator")),
        BookContributor(id = "c4", name = "Natalia Sylvester", roles = listOf("translator")),
        BookContributor(id = "c5", name = "Peter Ahlstrom", roles = listOf("editor")),
        BookContributor(id = "c6", name = "Bryce Moore", roles = listOf("foreword by")),
    )

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

// Same-role contributors group into one row (the two narrators share a "Narrators" row).

@Preview(name = "CreditsSection · light", widthDp = 412, heightDp = 500)
@Composable
private fun CreditsSectionLight() {
    PreviewTheme(dark = false) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "CreditsSection · dark", widthDp = 412, heightDp = 500)
@Composable
private fun CreditsSectionDark() {
    PreviewTheme(dark = true) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
