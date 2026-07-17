package com.calypsan.listenup.client.features.bookdetail

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.calypsan.listenup.client.design.theme.ListenUpTheme
import com.calypsan.listenup.client.domain.model.Genre
import com.calypsan.listenup.client.domain.model.Mood
import com.calypsan.listenup.client.domain.model.Tag
import com.calypsan.listenup.client.features.bookdetail.components.AboutSection
import org.jetbrains.compose.resources.stringResource
import listenup.composeapp.generated.resources.Res
import listenup.composeapp.generated.resources.book_detail_credits_placeholder

private val PREVIEW_DESCRIPTION =
    """
    The **Way of Kings** is a landmark in modern fantasy. Stormlight Archive begins with this
    massive, absorbing epic — a sweeping story of war, mystery, and the redemption of broken
    people. On the storm-lashed world of Roshar, ancient powers have been lost, and new ones are
    only beginning to stir. A soldier, a scholar, and a young woman seeking purpose find their
    fates entwined in ways that will reshape the world.
    """.trimIndent()

private val PREVIEW_GENRES =
    listOf(
        Genre(id = "g1", name = "Epic Fantasy", slug = "epic-fantasy", path = "/fantasy/epic-fantasy"),
        Genre(id = "g2", name = "High Fantasy", slug = "high-fantasy", path = "/fantasy/high-fantasy"),
        Genre(id = "g3", name = "Adventure", slug = "adventure", path = "/adventure"),
    )

private val PREVIEW_TAGS =
    listOf(
        Tag(id = "1", name = "Found Family", slug = "found-family"),
        Tag(id = "2", name = "War & Conflict", slug = "war-conflict"),
    )

private val PREVIEW_MOODS =
    listOf(
        Mood(id = "1", name = "Dark", slug = "dark"),
        Mood(id = "2", name = "Epic", slug = "epic"),
        Mood(id = "3", name = "Tense", slug = "tense"),
    )

@Composable
private fun PreviewTheme(
    dark: Boolean,
    content: @Composable () -> Unit,
) {
    ListenUpTheme(darkTheme = dark, dynamicColor = false, content = content)
}

@Composable
private fun CreditsSlotSample() {
    Text(
        text = stringResource(Res.string.book_detail_credits_placeholder),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

// ── isCard = true (desktop card) ──────────────────────────────────────────────

@Preview(name = "AboutSection · card · light", widthDp = 600, heightDp = 700)
@Composable
private fun AboutSectionCardLight() {
    PreviewTheme(dark = false) {
        var expanded by remember { mutableStateOf(false) }
        AboutSection(
            description = PREVIEW_DESCRIPTION,
            genres = PREVIEW_GENRES,
            tags = PREVIEW_TAGS,
            moods = PREVIEW_MOODS,
            isLoadingTags = false,
            isCard = true,
            isDescriptionExpanded = expanded,
            onToggleDescriptionExpanded = { expanded = !expanded },
            onGenreClick = {},
            onTagClick = {},
            onMoodClick = {},
            creditsSlot = { CreditsSlotSample() },
        )
    }
}

@Preview(name = "AboutSection · card · dark", widthDp = 600, heightDp = 700)
@Composable
private fun AboutSectionCardDark() {
    PreviewTheme(dark = true) {
        var expanded by remember { mutableStateOf(false) }
        AboutSection(
            description = PREVIEW_DESCRIPTION,
            genres = PREVIEW_GENRES,
            tags = PREVIEW_TAGS,
            moods = PREVIEW_MOODS,
            isLoadingTags = false,
            isCard = true,
            isDescriptionExpanded = expanded,
            onToggleDescriptionExpanded = { expanded = !expanded },
            onGenreClick = {},
            onTagClick = {},
            onMoodClick = {},
            creditsSlot = { CreditsSlotSample() },
        )
    }
}

// ── isCard = false (mobile frameless) ─────────────────────────────────────────

@Preview(name = "AboutSection · frameless · light", widthDp = 412, heightDp = 700)
@Composable
private fun AboutSectionFramelessLight() {
    PreviewTheme(dark = false) {
        var expanded by remember { mutableStateOf(false) }
        AboutSection(
            description = PREVIEW_DESCRIPTION,
            genres = PREVIEW_GENRES,
            tags = PREVIEW_TAGS,
            moods = PREVIEW_MOODS,
            isLoadingTags = false,
            isCard = false,
            isDescriptionExpanded = expanded,
            onToggleDescriptionExpanded = { expanded = !expanded },
            onGenreClick = {},
            onTagClick = {},
            onMoodClick = {},
        )
    }
}

@Preview(name = "AboutSection · frameless · dark", widthDp = 412, heightDp = 700)
@Composable
private fun AboutSectionFramelessDark() {
    PreviewTheme(dark = true) {
        var expanded by remember { mutableStateOf(false) }
        AboutSection(
            description = PREVIEW_DESCRIPTION,
            genres = PREVIEW_GENRES,
            tags = PREVIEW_TAGS,
            moods = PREVIEW_MOODS,
            isLoadingTags = false,
            isCard = false,
            isDescriptionExpanded = expanded,
            onToggleDescriptionExpanded = { expanded = !expanded },
            onGenreClick = {},
            onTagClick = {},
            onMoodClick = {},
        )
    }
}
