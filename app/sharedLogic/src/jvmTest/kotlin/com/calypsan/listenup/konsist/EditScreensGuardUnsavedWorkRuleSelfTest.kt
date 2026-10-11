package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs for [EditScreensGuardUnsavedWorkRule]'s predicates, so it is known to fire. */
class EditScreensGuardUnsavedWorkRuleSelfTest :
    FunSpec({
        test("every way of reading a draft flag is seen") {
            readsDraftFlag("val a = state.hasChanges") shouldBe true
            readsDraftFlag("val a = ready?.isDirty == true") shouldBe true
            readsDraftFlag("ListenerRatingLimits.isDraftChanged(a, b, c, d)") shouldBe true
            readsDraftFlag("viewModel.hasChanges(name, d, p)") shouldBe true
        }

        test("a flag named in a comment, or an unrelated word, is not a read") {
            readsDraftFlag("// state.hasChanges") shouldBe false
            readsDraftFlag("val hasChangesToShow = true") shouldBe false
        }

        test("the guard call is seen, a mention is not") {
            callsGuard("val leave = rememberUnsavedWorkGuard(dirty = d, onLeave = b)") shouldBe true
            callsGuard("// rememberUnsavedWorkGuard( would go here") shouldBe false
        }

        test("a hand-rolled hold or dialog is seen, the shared pieces are not") {
            holdsOrAsksByHand("HoldUnsavedWork(active = d)") shouldBe true
            holdsOrAsksByHand("private fun UnsavedChangesDialog(a: () -> Unit) {}") shouldBe true
            holdsOrAsksByHand("internal fun DiscardChapterEditsDialog(a: () -> Unit) {}") shouldBe true
            holdsOrAsksByHand("UnsavedChangesDialog(onDiscard = {}, onKeepEditing = {})") shouldBe false
            holdsOrAsksByHand("fun HoldUnsavedWork(active: Boolean) {}") shouldBe false
            holdsOrAsksByHand("// HoldUnsavedWork(active = d)") shouldBe false
        }
    })
