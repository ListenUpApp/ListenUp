package com.calypsan.listenup.web.design

import com.calypsan.listenup.api.dto.image.ImageFrame
import com.calypsan.listenup.client.domain.model.BookContributor
import com.calypsan.listenup.client.presentation.imageframe.FrameShape
import com.calypsan.listenup.web.MountRegistry
import com.calypsan.listenup.web.features.bookdetail.CreditsDialog
import com.calypsan.listenup.web.features.match.failed
import com.calypsan.listenup.web.features.match.photo
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import org.w3c.dom.asList

/**
 * Guards the seam between Kotlin and `web.css`.
 *
 * The kit styles by class name, which makes a typo or an invented class invisible: the component
 * compiles, its tests pass, and it renders unstyled. That already happened once — a header span
 * was given a `.dt-head` class the sheet has never defined, and nothing caught it but eye.
 *
 * Asserting the *rendered* DOM against the *loaded* stylesheet catches both directions of that
 * mistake, and does it for every component at once rather than one assertion per class.
 */
class ClassContractTest :
    FunSpec({
        val mounts = MountRegistry()
        afterTest { mounts.disposeAll() }

        test("web.css is actually loaded, or this whole spec is vacuous") {
            // Without this, an unloaded stylesheet makes `definedClasses()` empty and every
            // assertion below would fail loudly rather than silently — but a *partially* loaded
            // sheet would not. Pin a class we know the sheet defines.
            definedClasses().contains("tblwrap") shouldBe true
        }

        test("a class defined only inside @media is still seen as defined") {
            // `.sb-foot` exists solely inside the shell sheet's phone-width block. Reading
            // top-level rules alone missed it — a grouping rule has no selectorText of its own —
            // so a page rendering such a class failed this contract even though the sheet defines
            // it perfectly well, and the tempting "fix" was to restructure correct CSS.
            definedClasses().contains("sb-foot") shouldBe true
        }

        test("every class the photo framing draws is defined, measured and loaded") {
            // Its own case: the editor draws its photo and scrim only once the stage has a size, and a
            // framed image takes its placement only once the picture has loaded, so the main case's
            // synchronous read would never see them.
            val url = portraitPngUrl(300, 400)
            val host =
                mounts.mount {
                    FramedImg(src = url, alt = "", frame = ImageFrame(0.5, 0.3, 1.5), classes = listOf("uav-frame"))
                    ContributorPhoto("c1", "Ann Leckie", imagePath = null, frame = null)
                    FrameEditorDialog(
                        photo = DecodedPhoto.Ready(url, 300, 400),
                        initialFrame = null,
                        shape = FrameShape.Circle,
                        confirm = FrameConfirm.Done,
                        onConfirm = {},
                        onCancel = {},
                        onChooseAnother = {},
                    )
                }
            awaitEditorReady(host)
            awaitImagesApplied(host)
            val used =
                host
                    .querySelectorAll("*")
                    .asList()
                    .flatMap { element -> (element as Element).classList.asList() }
                    .toSet()
            closeDialogs(host)
            mounts.disposeAll()

            val others =
                mounts.classesUsedIn {
                    FrameEditorDialog(DecodedPhoto.CouldNotOpen, null, FrameShape.Circle, FrameConfirm.Save, {}, {}, {})
                    FrameEditorDialog(null, null, FrameShape.Circle, FrameConfirm.Done, {}, {}, {})
                    CreditsDialog(
                        credits = listOf(BookContributor(id = "a1", name = "Ann Leckie", roles = listOf("author"))),
                        onOpenContributor = {},
                        onDismiss = {},
                    )
                }

            used shouldContain "fed-photo"
            used shouldContain "fed-scrim"
            val undefined = used + others - definedClasses()
            undefined shouldBe emptySet()
        }
    })
