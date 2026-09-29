package com.calypsan.listenup.web.features.bookdetail

import com.calypsan.listenup.web.InShell
import com.calypsan.listenup.web.PHONE
import com.calypsan.listenup.web.Pointer
import com.calypsan.listenup.web.SMALL_PHONE
import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.contentOverflow
import com.calypsan.listenup.web.design.DataTable
import com.calypsan.listenup.web.design.TableColumn
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.jetbrains.compose.web.dom.Text
import org.w3c.dom.Element

/**
 * The data tables on a phone: every column still reachable, and the page around them stays put.
 *
 * `.tblwrap` used to clip with `overflow:hidden`, which on a phone simply cut the Size and Codec
 * columns off — not scrolled out of view, gone. A data table's columns line up to be compared, so
 * it scrolls sideways inside its own card rather than stacking into cards that lose the alignment.
 */
class TablesOnAPhoneTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest { frames.disposeAll() }

        listOf(SMALL_PHONE, PHONE).forEach { width ->
            test("at ${width}px the files table scrolls inside its card, and the page does not") {
                val frame = frames.mount(width) { InShell { FilesPane(readyBook(), sampleDocuments()) } }
                val wrap = frame.findAll(".tblwrap").first()

                frame.css(wrap, "overflow-x") shouldBe "auto"
                wrap.scrollWidth - wrap.clientWidth shouldBeGreaterThan 0
                frame.contentOverflow() shouldBe 0
            }

            test("at ${width}px the last column of every table can be scrolled into view") {
                val frame = frames.mount(width) { InShell { FilesPane(readyBook(), sampleDocuments()) } }

                frame.findAll(".tblwrap").forEach { wrap ->
                    wrap.scrollLeft = wrap.scrollWidth.toDouble()
                    val last = wrap.querySelectorAll("thead th").let { it.item(it.length - 1) as Element }
                    frame.rect(last).right shouldBeLessThanOrEqual frame.rect(wrap).right
                }
            }
        }

        test("the scrolling edge is shaded, so a reader can tell there is more to the right") {
            val frame = frames.mount(SMALL_PHONE) { InShell { FilesPane(readyBook(), sampleDocuments()) } }

            frame.css(frame.findAll(".tblwrap").first(), "background-image") shouldNotBe "none"
        }

        test("a selected row is visibly selected on a device that cannot hover") {
            // It was inside `(hover: hover)` alongside the hover wash, so on a touchscreen a
            // selected chapter looked exactly like an unselected one.
            val frame =
                frames.mount(PHONE, pointer = Pointer.Touch) {
                    DataTable(
                        columns = listOf(TableColumn<String>("t", "Title") { Text(it) }),
                        rows = listOf("One", "Two"),
                        selectable = true,
                        isSelected = { it == "Two" },
                    )
                }

            val selected = frame.find(".tbl tbody tr.sel")
            val plain = frame.find(".tbl tbody tr:not(.sel)")
            frame.css(selected, "background-color") shouldNotBe frame.css(plain, "background-color")
        }
    })
