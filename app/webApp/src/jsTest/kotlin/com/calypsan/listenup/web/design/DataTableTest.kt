package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.browser.document
import org.jetbrains.compose.web.dom.Text
import org.jetbrains.compose.web.renderComposable
import org.w3c.dom.HTMLElement

private data class Chapter(
    val number: String,
    val title: String,
    val start: String,
)

private val CHAPTERS =
    listOf(
        Chapter("1", "Night Knocks", "0:00:00"),
        Chapter("2", "Tim Jamieson", "0:38:02"),
        Chapter("9", "The Back Half", "4:32:11"),
    )

private val COLUMNS =
    listOf(
        TableColumn<Chapter>("n", "#", width = 46, align = ColumnAlign.End, mono = true) { Text(it.number) },
        TableColumn<Chapter>("t", "Chapter") { Text(it.title) },
        TableColumn<Chapter>("s", "Starts", width = 96, align = ColumnAlign.End, mono = true) { Text(it.start) },
    )

private fun mount(content: @Composable () -> Unit): HTMLElement {
    val host = document.createElement("div") as HTMLElement
    document.body!!.appendChild(host)
    renderComposable(root = host) { content() }
    return host
}

/**
 * The table is the component the rest of the web client leans on — chapters, files, readers and
 * the whole library browse are all this one thing. The class contract matters as much as the
 * structure: `web.css` styles by class, so a row that loses `sel` is not merely mis-drawn, it
 * stops looking selected while still being selected.
 */
class DataTableTest :
    FunSpec({

        test("every row and column reaches the DOM") {
            val host = mount { DataTable(columns = COLUMNS, rows = CHAPTERS) }

            host.querySelectorAll("tbody tr").length shouldBe CHAPTERS.size
            host.querySelectorAll("thead th").length shouldBe COLUMNS.size
        }

        test("numeric columns are marked so digits align") {
            // `.num` is what applies the mono face and tabular figures. Without it a column of
            // durations renders proportionally and stops being scannable — the exact thing a
            // table is for.
            val host = mount { DataTable(columns = COLUMNS, rows = CHAPTERS) }

            val firstRowCells = host.querySelectorAll("tbody tr td")
            (firstRowCells.item(0) as HTMLElement).className shouldBe "num"
            (firstRowCells.item(1) as HTMLElement).className shouldBe ""
        }

        test("a selected row carries the selection class") {
            val host =
                mount {
                    DataTable(columns = COLUMNS, rows = CHAPTERS, isSelected = { it.number == "2" })
                }

            host.querySelectorAll("tbody tr.sel").length shouldBe 1
        }

        test("the playing row is marked separately from selection") {
            // Selection owns the filled background; playing takes coral text. They are different
            // states and a row can be both, so they must not share a class.
            val host =
                mount {
                    DataTable(
                        columns = COLUMNS,
                        rows = CHAPTERS,
                        isSelected = { it.number == "2" },
                        isPlaying = { it.number == "9" },
                    )
                }

            host.querySelectorAll("tbody tr.sel").length shouldBe 1
            host.querySelectorAll("tbody tr.on").length shouldBe 1
        }

        test("the sorted column is flagged and shows its direction") {
            val host = mount { DataTable(columns = COLUMNS, rows = CHAPTERS, sortKey = "s") }

            host.querySelectorAll("thead th.srt").length shouldBe 1
            host.querySelectorAll("thead th.srt svg").length shouldBe 1
        }

        test("selection adds a checkbox column to header and rows") {
            val host = mount { DataTable(columns = COLUMNS, rows = CHAPTERS, selectable = true) }

            host.querySelectorAll("thead th").length shouldBe COLUMNS.size + 1
            host.querySelectorAll("tbody tr td .cbx").length shouldBe CHAPTERS.size
        }

        test("a partial selection renders the indeterminate mark, not a tick") {
            val host =
                mount {
                    DataTable(
                        columns = COLUMNS,
                        rows = CHAPTERS,
                        selectable = true,
                        allState = SelectAllState.Some,
                    )
                }

            val headerBox = host.querySelector("thead .cbx") as HTMLElement
            headerBox.className.contains("ind") shouldBe true
            // The dash is a CSS pseudo-element, so a tick here would double up.
            headerBox.querySelectorAll("svg").length shouldBe 0
        }

        test("row actions render one button per action") {
            val host =
                mount {
                    DataTable(
                        columns = COLUMNS,
                        rows = CHAPTERS,
                        rowActions = listOf(WebIcon.Play, WebIcon.Pencil),
                    )
                }

            host.querySelectorAll("tbody tr .rowact .iconbtn").length shouldBe CHAPTERS.size * 2
        }

        test("clicking a row reports the row it was given") {
            var clicked: Chapter? = null
            val host =
                mount {
                    DataTable(columns = COLUMNS, rows = CHAPTERS, onRowClick = { clicked = it })
                }

            (host.querySelectorAll("tbody tr").item(2) as HTMLElement).click()

            clicked shouldBe CHAPTERS[2]
        }
    })
