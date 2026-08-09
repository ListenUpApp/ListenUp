package com.calypsan.listenup.web.features.bookdetail

import androidx.compose.runtime.Composable
import com.calypsan.listenup.web.design.Breadcrumb
import com.calypsan.listenup.web.design.Cover
import com.calypsan.listenup.web.design.MetaEntry
import com.calypsan.listenup.web.design.MetaList
import com.calypsan.listenup.web.design.Panel
import com.calypsan.listenup.web.design.Pill
import com.calypsan.listenup.web.design.ProgressLine
import com.calypsan.listenup.web.design.TabItem
import com.calypsan.listenup.web.design.Tabs
import org.jetbrains.compose.web.dom.Div
import org.jetbrains.compose.web.dom.H1
import org.jetbrains.compose.web.dom.H3
import org.jetbrains.compose.web.dom.P
import org.jetbrains.compose.web.dom.Text

/**
 * Book Detail — the Workbench layout in the Paper voice.
 *
 * The pane is URL state (`?tab=…`), reported through [onSelectTab] so the caller can `replace`
 * the history entry: Back leaves the page, not the pane. Content is a static sample until the
 * store wires in — the browser Koin modules are deliberately empty at this stage.
 */
@Composable
fun BookDetailPage(
    tab: String,
    onSelectTab: (String) -> Unit,
    onOpenLibrary: () -> Unit,
) {
    Div(attrs = { classes("bd") }) {
        Breadcrumb(listOf("Library", SAMPLE_TITLE), onNavigate = { onOpenLibrary() })

        Div(attrs = { classes("bd-head") }) {
            Cover(title = SAMPLE_TITLE, size = COVER_SIZE, radius = COVER_RADIUS)
            Div(attrs = { classes("bd-tblock") }) {
                H1(attrs = { classes("bd-t") }) { Text(SAMPLE_TITLE) }
                Div(attrs = { classes("bd-by") }) { Text("Stephen King · read by Santino Fontana") }
                ProgressLine(percent = SAMPLE_PROGRESS_PERCENT, remaining = "4h 42m left")
            }
        }

        Tabs(
            items =
                listOf(
                    TabItem("overview", "Overview"),
                    TabItem("chapters", "Chapters", count = SAMPLE_CHAPTER_COUNT),
                ),
            active = tab,
            onSelect = onSelectTab,
        )

        if (tab == "chapters") ChaptersPane() else OverviewPane()
    }
}

@Composable
private fun OverviewPane() {
    Div(attrs = { classes("bd-cols") }) {
        Div(attrs = { classes("bd-main") }) {
            Panel(title = "About") {
                P(attrs = {
                    style {
                        property("margin", "0 0 14px")
                        property("font-size", "14.5px")
                        property("line-height", "1.6")
                        property("color", "var(--ink-2)")
                    }
                }) {
                    Text(SAMPLE_BLURB)
                }
                Div(attrs = {
                    style {
                        property("display", "flex")
                        property("flex-wrap", "wrap")
                        property("gap", "8px")
                    }
                }) {
                    SAMPLE_GENRES.forEach { genre -> Pill(genre) }
                }
            }
        }
        Div(attrs = { classes("bd-side") }) {
            Panel(title = "Details") {
                MetaList(SAMPLE_DETAILS)
            }
        }
    }
}

/** Stands in for the chapters workbench, which is the next step of the plan. */
@Composable
private fun ChaptersPane() {
    Div(attrs = { classes("empty") }) {
        H3 { Text("Chapters") }
        P { Text("This pane is not built yet.") }
    }
}

private const val SAMPLE_TITLE = "The Institute"

private const val SAMPLE_CHAPTER_COUNT = "33"

private const val SAMPLE_PROGRESS_PERCENT = 49

private const val SAMPLE_BLURB =
    "In the middle of the night, in a house on a quiet street in suburban Minneapolis, intruders " +
        "take twelve-year-old Luke Ellis. He wakes up at The Institute, in a room that looks " +
        "just like his own — except there's no window."

private val SAMPLE_GENRES = listOf("Horror", "Thriller", "Supernatural")

private val SAMPLE_DETAILS =
    listOf(
        MetaEntry("Duration", "9:14:06", machine = true),
        MetaEntry("Chapters", "33"),
        MetaEntry("Published", "2019"),
        MetaEntry("Format", "M4B · 64 kb/s", machine = true),
        MetaEntry("Size", "512 MB", machine = true),
        MetaEntry("Added", "2026-06-02", machine = true),
    )

private const val COVER_SIZE = 180

private const val COVER_RADIUS = 16
