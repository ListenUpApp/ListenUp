package com.calypsan.listenup.client.testing

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.test.core.app.ApplicationProvider
import org.robolectric.Shadows.shadowOf

/**
 * The order TalkBack and the D-pad walk a Compose screen, as Compose itself hands it to the platform: each node's
 * `AccessibilityNodeInfo` names the node read after it (`traversalBefore`), sorted by Compose's own geometry and
 * traversal-group rules. Reading those links, rather than comparing bounds, catches a traversal group or index that
 * reorders the screen without moving anything.
 *
 * Compose computes the links only while an accessibility service is on, so [enableAccessibility] must run before the
 * content is set.
 */
object TalkBackOrder {
    /** Compose's test-only extra carrying `traversalBefore`'s semantics id on a node info. */
    private const val TRAVERSAL_BEFORE = "android.view.accessibility.extra.EXTRA_DATA_TEST_TRAVERSALBEFORE_VAL"

    /** Turns on a stand-in accessibility service with touch exploration, as TalkBack would. */
    fun enableAccessibility() {
        val manager =
            ApplicationProvider
                .getApplicationContext<Context>()
                .getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        shadowOf(manager).apply {
            setEnabled(true)
            setTouchExplorationEnabled(true)
            setEnabledAccessibilityServiceList(listOf(AccessibilityServiceInfo()))
        }
    }

    /**
     * The semantics ids of [composeView]'s screen in the order TalkBack visits them. A merged node (a button, a row) is
     * one stop, under the id the test's merged tree gives it.
     */
    fun of(
        composeView: View,
        rule: ComposeTestRule,
    ): List<Int> {
        val provider =
            checkNotNull(composeView.accessibilityNodeProvider) { "no accessibility provider on $composeView" }
        val root = rule.onNode(isRoot(), useUnmergedTree = true).fetchSemanticsNode()
        val next =
            generateSequence(listOf(root)) { level -> level.flatMap { it.children }.ifEmpty { null } }
                .flatten()
                .mapNotNull { node ->
                    provider
                        .createAccessibilityNodeInfo(node.id)
                        ?.extras
                        ?.getInt(TRAVERSAL_BEFORE, -1)
                        ?.takeIf { it != -1 }
                        ?.let { node.id to it }
                }.toMap()
        // The walk starts at the one stop nothing leads to; the longest chain is the screen's, if the root's own
        // link was not reported.
        return (next.keys - next.values.toSet())
            .map { head -> generateSequence(head) { next[it] }.toList() }
            .maxByOrNull { it.size }
            .orEmpty()
    }
}
