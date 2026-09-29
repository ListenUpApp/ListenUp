package com.calypsan.listenup.web.design

import androidx.compose.runtime.Composable
import org.jetbrains.compose.web.attributes.AttrsScope
import org.jetbrains.compose.web.dom.A
import org.w3c.dom.HTMLAnchorElement
import org.w3c.dom.HTMLButtonElement
import org.jetbrains.compose.web.dom.Button as DomButton

/**
 * What a button is for, which decides how loudly it is drawn.
 *
 * - [Primary]: the one action a view is for — coral fill (`--coral-fill`, white at AA).
 * - [Secondary]: every other action — an outlined surface. The old `.btn-ghost` was a near-twin of
 *   this with a grey fill, and is folded in.
 * - [Ghost]: a quiet action with no box until the pointer is on it.
 * - [Danger]: an action that destroys something — `--danger-fill`, and only ever the confirming
 *   press, never the button that opens the question.
 * - [Icon]: a square control with a glyph and no words, so it must carry an accessible name.
 */
enum class ButtonKind { Primary, Secondary, Ghost, Danger, Icon }

/**
 * How big it is. [Md] (40px) is a page's button. [Lg] (52px) is the sign-in screens' call to action
 * and a hero's. [Sm] is a control inside a row or a card — 30px for an icon, 32px with words. On a
 * touchscreen every size takes taps from at least 44×44 (`03-press.css`), without being drawn bigger.
 */
enum class ButtonSize { Sm, Md, Lg }

/**
 * A button. Every action on web that looks like one is this, rather than a `<button>` wearing one of
 * the six overlapping classes it replaced (`.btn`, `.btn-c`, `.btn-o`, `.btn-ghost`, `.btn-sq`,
 * `.iconbtn`), 144 of them each picking its own height, radius and weight.
 *
 * A real `<button type="button">`: inside a `<form>` a bare `<button>` submits it, which is how a
 * Cancel press used to save. [submit] asks for `type="submit"` for the one button in a form that
 * should. [label] is the accessible name, and is required for [ButtonKind.Icon], whose content is a
 * glyph a screen reader cannot read; it doubles as the tooltip there.
 *
 * [kind] has no default on purpose: a call that does not say what its button is for resolves to
 * the plain DOM `Button`, which is what a custom control (a tab, a chip, a row) should be.
 *
 * [attrs] is for what this does not own — a page class for placement, a `disabledWhen`, an id.
 */
@Composable
fun Button(
    kind: ButtonKind,
    onClick: () -> Unit = {},
    size: ButtonSize = ButtonSize.Md,
    enabled: Boolean = true,
    label: String? = null,
    fill: Boolean = false,
    submit: Boolean = false,
    attrs: (AttrsScope<HTMLButtonElement>.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    requireAccessibleName(kind, label)
    val press = onClick
    DomButton(attrs = {
        classes(*buttonClasses(kind, size, fill))
        attr("type", if (submit) "submit" else "button")
        label?.let {
            attr("aria-label", it)
            if (kind == ButtonKind.Icon) attr("title", it)
        }
        if (!enabled) attr("disabled", "")
        this.onClick { if (enabled) press() }
        attrs?.invoke(this)
    }) { content() }
}

/**
 * A link drawn as a button: an action that has an address — a document to open, a file to
 * download. It is an `<a>` so the browser's own link behaviour (open in a new tab, copy the address,
 * middle-click) keeps working; a button that navigated by script would lose all of it.
 */
@Composable
fun ButtonLink(
    href: String,
    kind: ButtonKind,
    size: ButtonSize = ButtonSize.Md,
    label: String? = null,
    attrs: (AttrsScope<HTMLAnchorElement>.() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    requireAccessibleName(kind, label)
    A(href = href, attrs = {
        classes(*buttonClasses(kind, size, fill = false))
        label?.let { attr("aria-label", it) }
        attrs?.invoke(this)
    }) { content() }
}

/**
 * The classes a [kind] and [size] draw with. Public to the design package for the one control that
 * must look like a button but is built elsewhere: a popup menu's trigger.
 */
internal fun buttonClasses(
    kind: ButtonKind,
    size: ButtonSize,
    fill: Boolean = false,
): Array<String> =
    listOfNotNull(
        "btn",
        "btn-" + kind.name.lowercase(),
        "btn-" + size.name.lowercase(),
        if (fill) "btn-fill" else null,
    ).toTypedArray()

/** An icon-only button with no name is announced as "button" and nothing else. */
internal fun requireAccessibleName(
    kind: ButtonKind,
    label: String?,
) {
    require(kind != ButtonKind.Icon || !label.isNullOrBlank()) {
        "An icon button needs a label: it has no words of its own."
    }
}
