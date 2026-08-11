package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.dom.Div

/**
 * How tightly a surface packs its rows and controls.
 *
 * From Foundations: dense is the default for tables, admin and editing surfaces — it fits 14 rows
 * where comfortable fits 8 — while reading surfaces (Home, Discover, Book Detail's overview) stay
 * comfortable. It is a real preference, destined for Settings › Appearance, not a per-component
 * whim.
 *
 * Density is carried entirely by CSS custom properties (`--row`, `--fs`, `--rad`), so the same
 * components serve both without a second implementation.
 */
enum class Density {
    Comfortable,
    Dense,
}

/**
 * The root wrapper every ListenUp web surface sits inside.
 *
 * `.luw` is what scopes the whole design sheet — tokens, component classes and the focus-ring
 * rule all hang off it — and the direction class picks the voice. `dir-a` ("Paper") is the house
 * voice: `Web/Foundations.html` renders every spec surface in it and does not load the Console
 * face at all.
 */
@Composable
fun WebAppSurface(
    density: Density = Density.Comfortable,
    content: @Composable () -> Unit,
) {
    Div(attrs = {
        classes("luw", "dir-a")
        if (density == Density.Dense) classes("den-dense")
    }) {
        content()
    }
}
