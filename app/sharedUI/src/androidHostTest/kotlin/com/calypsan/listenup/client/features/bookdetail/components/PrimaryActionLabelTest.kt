package com.calypsan.listenup.client.features.bookdetail.components

import com.calypsan.listenup.client.presentation.bookdetail.PrimaryAction
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class PrimaryActionLabelTest :
    FunSpec({
        test("each stage names its action") {
            primaryLabelFor(PrimaryAction.Play, isPreparing = false) shouldBe PrimaryLabel.Play
            primaryLabelFor(PrimaryAction.Resume, isPreparing = false) shouldBe PrimaryLabel.Resume
            primaryLabelFor(PrimaryAction.ListenAgain, isPreparing = false) shouldBe PrimaryLabel.ListenAgain
            primaryLabelFor(PrimaryAction.UnavailableOffline, isPreparing = false) shouldBe PrimaryLabel.UnavailableOffline
        }

        test("preparing wins over the stage, so the tap is acknowledged") {
            primaryLabelFor(PrimaryAction.Resume, isPreparing = true) shouldBe PrimaryLabel.Preparing
        }

        test("only Unavailable offline is disabled (N8): Resume and Listen again are enabled") {
            isPrimaryEnabled(PrimaryAction.Resume) shouldBe true
            isPrimaryEnabled(PrimaryAction.ListenAgain) shouldBe true
            isPrimaryEnabled(PrimaryAction.Play) shouldBe true
            isPrimaryEnabled(PrimaryAction.UnavailableOffline) shouldBe false
        }
    })
