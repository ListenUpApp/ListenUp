package com.calypsan.listenup.web.design

import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.awaitFrame
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.w3c.dom.HTMLButtonElement
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLInputElement
import org.w3c.dom.asList

/**
 * The shared yes-or-no dialog. Every other web confirm is unchanged by Book Detail's delete: no checkbox, a coral
 * confirm that works at once. Only an [ConfirmDialog] given an acknowledgement waits for it.
 */
class ConfirmDialogTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        fun actions(host: HTMLElement): List<HTMLButtonElement> =
            host.querySelectorAll("dialog.dlg .dlg-actions button").asList().filterIsInstance<HTMLButtonElement>()

        test("without an acknowledgement there is no checkbox, and the confirm works at once") {
            var confirms = 0
            val host =
                mounts.mount {
                    ConfirmDialog(
                        open = true,
                        title = "Sign out?",
                        body = "Every other device signs out.",
                        confirmLabel = "Sign out",
                        onConfirm = { confirms++ },
                        onDismiss = {},
                    )
                }
            awaitFrame()
            host.querySelector("dialog.dlg input[type=checkbox]") shouldBe null
            actions(host)[1].disabled shouldBe false
            actions(host)[1].classList.contains("btn-primary") shouldBe true
            actions(host)[1].click()
            awaitFrame()
            confirms shouldBe 1
        }

        test("an acknowledgement gates the confirm until it is ticked, and destructive wears the danger colour") {
            var confirms = 0
            val host =
                mounts.mount {
                    ConfirmDialog(
                        open = true,
                        title = "Delete?",
                        body = "It goes.",
                        confirmLabel = "Delete",
                        onConfirm = { confirms++ },
                        onDismiss = {},
                        acknowledgement = "I understand",
                        destructive = true,
                    )
                }
            awaitFrame()
            val box = host.querySelector("dialog.dlg input[type=checkbox]") as HTMLInputElement
            box.checked shouldBe false
            actions(host)[1].disabled shouldBe true
            actions(host)[1].classList.contains("btn-danger") shouldBe true
            box.click()
            awaitFrame()
            actions(host)[1].click()
            awaitFrame()
            confirms shouldBe 1
        }
    })
