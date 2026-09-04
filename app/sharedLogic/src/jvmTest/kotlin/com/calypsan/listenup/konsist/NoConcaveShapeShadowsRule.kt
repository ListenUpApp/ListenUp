package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Elevation shadows must never be cast from a concave shape.
 *
 * Skia tessellates elevation shadows on the RenderThread, and its concave path
 * branch (`SkBaseShadowTessellator::computeConcaveShadow`) can spin for seconds
 * per frame on shapes like `MaterialShapes.Cookie9Sided`. When that happens the
 * main thread blocks in `syncAndDrawFrame`, input freezes, and the system ANRs
 * the app — observed in production on the alphabet-scrollbar scrub bubble
 * (app 0.8.1, Pixel 10 Pro XL). Clipping to a concave shape is fine; only the
 * shadow tessellation is pathological. Cast the shadow from a convex stand-in
 * (e.g. `CircleShape`) and clip to the decorative shape afterwards.
 */
class NoConcaveShapeShadowsRule :
    FunSpec({
        test("no Modifier.shadow call uses a concave shape") {
            // `.shadow(` argument lists may span lines and contain one level of nested
            // parens (e.g. `cookieScallopShape()`, `if (x) 8.dp else 4.dp`).
            val shadowArgs = """\.shadow\((?:[^()]|\([^()]*\))*"""
            // Direct: the concave shape source appears inline in the shadow call.
            val direct = Regex("""$shadowArgs(?:cookieScallopShape|MaterialShapes\.)""")
            // Indirect: `val x = cookieScallopShape()` (or MaterialShapes.*) captured
            // into a local, then passed to `.shadow` as an argument VALUE. Matching the
            // name only after `=`/`(`/`,` keeps the `shape =` argument LABEL from
            // colliding with a local that happens to be named `shape`.
            val concaveVal = Regex("""val\s+(\w+)\s*=\s*(?:cookieScallopShape\(|MaterialShapes\.)""")

            fun usedAsShadowArg(name: String) = Regex("""$shadowArgs[=(,]\s*$name\s*[,)]""")

            val offenders =
                productionScope()
                    .files
                    .filter { file ->
                        val text = file.text.replace(Regex("""\s+"""), " ")
                        direct.containsMatchIn(text) ||
                            concaveVal
                                .findAll(text)
                                .any { usedAsShadowArg(it.groupValues[1]).containsMatchIn(text) }
                    }.map { it.path }
            offenders shouldBe emptyList()
        }
    })
