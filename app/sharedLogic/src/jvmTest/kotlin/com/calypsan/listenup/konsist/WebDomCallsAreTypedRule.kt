package com.calypsan.listenup.konsist

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The web client reaches the browser through typed declarations, not `asDynamic()` and `js("…")`.
 *
 * ⛔ **The regression this exists to stop.** Kotlin/JS's bundled DOM declarations predate pointer
 * capture, the Web Animations API, focus and scroll options, the popover API, `navigator.share`
 * and `mediaSession`, so the client once reached them through ~54 untyped calls: a misspelt method
 * or option (`setPointerCaptrue`, `{ preventscroll: true }`) was not a compile error, it was a
 * silent runtime no-op in the newest and most interactive code. Those calls now live as typed
 * `external` declarations under `web/dom/`, and a new untyped call elsewhere is a review decision:
 * add it to [UNTYPED_DOM_EXEMPTIONS] with a reason, or declare it in `dom/`.
 *
 * Matched on source text with comments stripped, over `:app:webApp`'s `jsMain` outside `dom/`.
 * Each exemption names how many untyped calls its file may hold, so a second one beside a listed
 * one is still caught, and a stale entry fails too.
 */
class WebDomCallsAreTypedRule :
    FunSpec({
        test("no web code outside dom/ reaches the browser through an untyped call without a listed reason") {
            val files = webSourcesOutsideDom()
            assertScopeNotEmpty(
                files.keys,
                expectedMin = 150,
                why = "every :app:webApp jsMain file outside web/dom — if discovery breaks, this rule polices nothing",
            )
            val offenders =
                files.mapNotNull { (path, text) ->
                    val found = untypedDomCalls(text)
                    val allowed = UNTYPED_DOM_EXEMPTIONS[path]?.calls ?: 0
                    when {
                        found > allowed -> {
                            "$path — $found untyped asDynamic()/js(…) call(s), $allowed allowed; declare it in web/dom/ or list it"
                        }

                        found < allowed -> {
                            "$path — exempts $allowed untyped call(s) but has $found; tighten UNTYPED_DOM_EXEMPTIONS"
                        }

                        else -> {
                            null
                        }
                    }
                } + (UNTYPED_DOM_EXEMPTIONS.keys - files.keys).map { "$it — exempted but no longer exists" }
            withClue(offenders.joinToString("\n", prefix = "\n")) { offenders.shouldBeEmpty() }
        }
    })

/** How many untyped DOM calls a file may hold, and the one-line reason none of them can be a declaration. */
internal data class UntypedDomExemption(
    val calls: Int,
    val reason: String,
)

/** Keyed by path relative to `com/calypsan/listenup/web/`. */
internal val UNTYPED_DOM_EXEMPTIONS: Map<String, UntypedDomExemption> =
    mapOf(
        "playback/HlsAttachment.kt" to
            UntypedDomExemption(
                2,
                "dynamic module loading of hls.js, a library chosen at runtime (a `new constructor()` and an `import()`)",
            ),
        "playback/HtmlAudioPlayer.kt" to
            UntypedDomExemption(
                2,
                "`webkitAudioDecodedByteCount` is a non-standard WebKit counter probed for presence; " +
                    "`name` is read off an arbitrary rejected JS value",
            ),
        "features/bookdetail/ShareOutcome.kt" to
            UntypedDomExemption(
                2,
                "reads `name`/`cause` off an arbitrary thrown JS value to recognise a cancelled share",
            ),
        "logging/RecentLogCapture.kt" to
            UntypedDomExemption(1, "reads `reason` off an unhandled-rejection event whose payload is any JS value"),
        "SqliteWorker.kt" to
            UntypedDomExemption(
                1,
                "`new Worker(new URL(…, import.meta.url))` must stay literal in this file for the bundler to emit the worker",
            ),
    )

private fun webSourcesOutsideDom(): Map<String, String> =
    productionScope()
        .files
        .filter { "/app/webApp/src/jsMain/" in it.path && "/com/calypsan/listenup/web/dom/" !in it.path }
        .associate { it.path.substringAfter("/com/calypsan/listenup/web/") to it.text }

private val UNTYPED_CALL = Regex("""\basDynamic\(\)|\bjs\(""")

/** How many `asDynamic()` / `js(` calls [source] makes, comments (KDoc included) not counted. */
internal fun untypedDomCalls(source: String): Int = UNTYPED_CALL.findAll(stripComments(source)).count()
