package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The centred reading-width column may not return to the Compose UI.
 *
 * `Modifier.readingWidth()` capped a whole screen at 640dp and centred it — a phone column floating in
 * a tablet's window, which is exactly what `app/sharedUI/CLAUDE.md` rule 11 forbids. Every screen that
 * used it was given a real wide layout (a side panel, `SectionColumns`, or an adaptive grid) and the
 * helper was deleted. `ReadableMeasure` survives for its legitimate job: limiting line length *inside*
 * a pane.
 *
 * Matched on file text, so a re-declared helper is caught as well as a call to one. This guards the
 * name, not the shape: a centred cap re-rolled under another name gets past it, and review has to
 * catch that.
 */
class NoCentredReadingColumnRule :
    FunSpec({
        test("no sharedUI production file declares or calls readingWidth") {
            val sharedUiFiles = productionScope().files.filter { it.path.contains("/sharedUI/") }

            assertScopeNotEmpty(
                sharedUiFiles,
                expectedMin = 190,
                why = "every :app:sharedUI production file — the module the retired helper lived in",
            )

            sharedUiFiles
                .filter { file -> "readingWidth" in file.text }
                .map { it.path }
                .shouldBeEmpty()
        }
    })
