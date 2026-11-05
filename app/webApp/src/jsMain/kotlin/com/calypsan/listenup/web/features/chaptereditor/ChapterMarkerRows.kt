package com.calypsan.listenup.web.features.chaptereditor

import androidx.compose.runtime.Composable
import com.calypsan.listenup.client.domain.model.Chapter
import com.calypsan.listenup.client.presentation.chaptereditor.ChapterMarkerTier
import com.calypsan.listenup.client.presentation.chaptereditor.PartSuggestion
import com.calypsan.listenup.web.design.Button
import com.calypsan.listenup.web.design.ButtonKind
import com.calypsan.listenup.web.design.ButtonSize
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Span
import org.jetbrains.compose.web.dom.Text

/**
 * What the editor's marker controls call, named as the ViewModel names them. Grouped so the page and
 * the session each take one more value rather than five.
 *
 * @property onSave the chapter carrying the marker, its tier, its name, and the chapter it starts at.
 */
class ChapterMarkerCalls(
    val onStart: (String, ChapterMarkerTier, String) -> Unit = { _, _, _ -> },
    val onSave: (String, ChapterMarkerTier, String, String) -> Unit = { _, _, _, _ -> },
    val onRemove: (String, ChapterMarkerTier) -> Unit = { _, _ -> },
    val onAcceptSuggestion: () -> Unit = {},
    val onDismissSuggestion: () -> Unit = {},
)

/** The marker rows [chapter] opens, Book above Part. Most chapters draw nothing here. */
@Composable
internal fun ChapterMarkerRows(
    chapter: Chapter,
    onRemove: (String, ChapterMarkerTier) -> Unit,
    onAction: (RowAction) -> Unit,
) {
    ChapterMarkerTier.entries.reversed().forEach { tier ->
        tier.titleOn(chapter)?.let { title ->
            ChapterMarkerRow(
                title = title,
                tier = tier,
                onRename = { onAction(RowAction.NamingMarker(chapter.id, tier, isNew = false)) },
                onMove = { onAction(RowAction.MovingMarker(chapter.id, tier)) },
                onRemove = { onRemove(chapter.id, tier) },
            )
        }
    }
}

/** One marker, and its three actions in the open: a wide screen has the room to not hide them. */
@Composable
private fun ChapterMarkerRow(
    title: String,
    tier: ChapterMarkerTier,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemove: () -> Unit,
) {
    Div(attrs = {
        classes("chm")
        if (tier == ChapterMarkerTier.Book) classes("is-book")
    }) {
        Span(attrs = { classes("chm-t") }) { Text(title) }
        Div(attrs = { classes("chm-acts") }) {
            Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onRename) { Text("Rename") }
            Button(kind = ButtonKind.Ghost, size = ButtonSize.Sm, onClick = onMove) { Text("Move") }
            Button(
                kind = ButtonKind.Ghost,
                size = ButtonSize.Sm,
                onClick = onRemove,
                attrs = { classes("chm-remove") },
            ) { Text("Remove marker") }
        }
    }
}

/** The offer to mark chapters that look like parts. It stays an offer until one of its buttons. */
@Composable
internal fun PartSuggestionStrip(
    suggestion: PartSuggestion,
    markers: ChapterMarkerCalls,
) {
    Div(attrs = {
        classes("ched-suggest")
        attr("role", "status")
    }) {
        Div(attrs = { classes("ched-suggest-tx") }) {
            P(attrs = { classes("ched-suggest-t") }) { Text("${suggestion.titles.size} chapters look like parts") }
            P(attrs = { classes("ched-suggest-b") }) {
                Text("${spokenList(suggestion.titles)}. They stay as chapters and open each part.")
            }
        }
        Button(kind = ButtonKind.Ghost, onClick = markers.onDismissSuggestion) { Text("Not now") }
        Button(kind = ButtonKind.Primary, onClick = markers.onAcceptSuggestion) { Text("Use as parts") }
    }
}

/** "Part I, Part II and Part III". There are always at least two, or there is no offer. */
private fun spokenList(titles: List<String>): String = "${titles.dropLast(1).joinToString(", ")} and ${titles.last()}"
