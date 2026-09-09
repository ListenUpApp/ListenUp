package com.calypsan.listenup.client.shortcuts

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The static app shortcuts each declared `android.shortcut.conversation`, the category that tells
 * the system a shortcut is a People/conversation shortcut — which makes it eligible for the
 * conversation section of the notification shade and for Bubbles. "Resume listening", "Search
 * library" and "Sleep timer" are none of those things.
 *
 * The resource is read as text rather than through Robolectric: the assertion is about what the
 * manifest resource DECLARES, and a text read states that directly with no inflation machinery
 * in between.
 */
class ShortcutCategoriesTest :
    FunSpec({

        test("the shortcuts resource is found and non-empty") {
            // Stated first so a path failure reports as a path failure, not as a passing assertion.
            shortcutsXml().isNotEmpty() shouldBe true
        }

        test("no shortcut declares the conversation category") {
            shortcutsXml() shouldNotContain "android.shortcut.conversation"
        }

        test("all three shortcuts are still declared") {
            // Guards against "fixing" the category by deleting the shortcuts.
            val xml = shortcutsXml()
            xml shouldContain "resume_listening"
            xml shouldContain "search_library"
            xml shouldContain "sleep_timer"
        }
    })

private fun shortcutsXml(): String {
    val relative = "src/androidMain/res/xml/shortcuts.xml"
    var dir = java.io.File(".").absoluteFile
    repeat(6) {
        val candidate = java.io.File(dir, relative)
        if (candidate.isFile) return candidate.readText()
        val fromRoot = java.io.File(dir, "app/sharedUI/$relative")
        if (fromRoot.isFile) return fromRoot.readText()
        dir = dir.parentFile ?: return@repeat
    }
    error("Could not locate $relative from ${java.io.File(".").absolutePath}")
}
