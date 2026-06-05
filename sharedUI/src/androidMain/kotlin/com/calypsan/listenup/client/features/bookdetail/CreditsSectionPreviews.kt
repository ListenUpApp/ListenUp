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
        BookContributor(id = "c1", name = "Brandon Sanderson", roles = listOf("Author")),
        BookContributor(id = "c2", name = "Michael Kramer", roles = listOf("Narrator")),
        BookContributor(id = "c3", name = "Kate Reading", roles = listOf("Narrator")),
        BookContributor(id = "c4", name = "Natalia Sylvester", roles = listOf("Translator")),
        BookContributor(id = "c5", name = "Peter Ahlstrom", roles = listOf("Editor")),
        BookContributor(id = "c6", name = "Bryce Moore", roles = listOf("Foreword")),
    )

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

// ── Grid layout (desktop) ─────────────────────────────────────────────────────

@Preview(name = "CreditsSection · grid · light", widthDp = 600, heightDp = 400)
@Composable
private fun CreditsSectionGridLight() {
    PreviewTheme(dark = false) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            grid = true,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "CreditsSection · grid · dark", widthDp = 600, heightDp = 400)
@Composable
private fun CreditsSectionGridDark() {
    PreviewTheme(dark = true) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            grid = true,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

// ── List layout (mobile) ──────────────────────────────────────────────────────

@Preview(name = "CreditsSection · list · light", widthDp = 412, heightDp = 500)
@Composable
private fun CreditsSectionListLight() {
    PreviewTheme(dark = false) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            grid = false,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "CreditsSection · list · dark", widthDp = 412, heightDp = 500)
@Composable
private fun CreditsSectionListDark() {
    PreviewTheme(dark = true) {
        CreditsSection(
            credits = PREVIEW_CREDITS,
            grid = false,
            onContributorClick = {},
            showHeader = true,
            modifier = Modifier.padding(16.dp),
        )
    }
}
