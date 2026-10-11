package com.calypsan.listenup.web.nav

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.asList

/** The button on the page (not in a dialog) whose label is exactly [label]. */
internal fun pageButton(
    host: HTMLElement,
    label: String,
): HTMLElement? =
    host
        .querySelectorAll("button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label && it.closest("dialog") == null }

/** The first link of the breadcrumb trail, which leaves the page. */
internal fun breadcrumbLink(host: HTMLElement): HTMLElement? = host.querySelector(".crumb a") as? HTMLElement

/** The button labelled [label] inside the open dialog. */
internal fun discardDialogButton(
    host: HTMLElement,
    label: String,
): HTMLButtonElement? =
    host
        .querySelectorAll("dialog button")
        .asList()
        .filterIsInstance<HTMLButtonElement>()
        .firstOrNull { it.textContent?.trim() == label }

/** Whether the shared "Discard your changes?" question is showing. */
internal fun asksToDiscard(host: HTMLElement): Boolean =
    host
        .querySelector("dialog")
        ?.textContent
        .orEmpty()
        .contains("Discard your changes?")

/**
 * The four things every guarded edit page does with its leave control, as one set of specs: a clean
 * form leaves at once; a dirty one asks; Cancel in the question keeps the draft and stays; Discard leaves.
 *
 * @param mount mounts the page with a draft ([dirty]) or without, reporting each departure to `onLeave`.
 * @param control the page's own way out (its Cancel, or the first breadcrumb link).
 */
internal fun FunSpec.guardedLeaveSpecs(
    page: String,
    mounts: MountRegistry,
    mount: (mounts: MountRegistry, dirty: Boolean, onLeave: () -> Unit) -> HTMLElement,
    control: (HTMLElement) -> HTMLElement?,
) {
    test("$page: a clean form leaves at once") {
        var left = 0
        val host = mount(mounts, false) { left++ }

        control(host).shouldNotBeNull().click()
        awaitFrame()

        left shouldBe 1
        asksToDiscard(host) shouldBe false
    }

    test("$page: a form with a draft asks first, and keeping it stays with the draft intact") {
        var left = 0
        val host = mount(mounts, true) { left++ }

        control(host).shouldNotBeNull().click()
        awaitFrame()

        asksToDiscard(host) shouldBe true
        left shouldBe 0

        discardDialogButton(host, "Cancel").shouldNotBeNull().click()
        awaitFrame()

        asksToDiscard(host) shouldBe false
        left shouldBe 0
    }

    test("$page: discarding the draft leaves") {
        var left = 0
        val host = mount(mounts, true) { left++ }

        control(host).shouldNotBeNull().click()
        awaitFrame()
        discardDialogButton(host, "Discard").shouldNotBeNull().click()
        awaitFrame()

        left shouldBe 1
    }
}
