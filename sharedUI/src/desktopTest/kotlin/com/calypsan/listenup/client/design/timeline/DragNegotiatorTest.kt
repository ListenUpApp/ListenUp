package com.calypsan.listenup.client.design.timeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/** Clamps between immediate neighbors — the chapter-shaped policy (no overlap, contiguous order). */
private class ContiguousTestPolicy(
    private val committed: MutableList<Pair<String, Long>>,
) : LanePolicy {
    override fun canDrag(marker: TimeMarker) = true

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ): Long {
        val before = siblings.filter { it.timeMs < marker.timeMs }.maxOfOrNull { it.timeMs } ?: 0L
        val after = siblings.filter { it.timeMs > marker.timeMs }.minOfOrNull { it.timeMs } ?: Long.MAX_VALUE
        return proposedMs.coerceIn(before + 1, (after - 1).coerceAtLeast(before + 1))
    }

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) {
        committed += marker.id to newMs
    }
}

/** canDrag always false — the file-boundary-shaped read-only policy. */
private class ReadOnlyTestPolicy : LanePolicy {
    var commitCount = 0

    override fun canDrag(marker: TimeMarker) = false

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ) = proposedMs

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) {
        commitCount++
    }
}

/**
 * The abstraction's acceptance test (grounding §0 point re: the primitive; spec source: Integration
 * Foundations §3 "build posture"). Overlaps legal, multiple markers may share a ms, clamp is
 * identity — the future Story-World event-lane shape. Proves [DragNegotiator] needs ZERO changes to
 * support a fundamentally different placement rule than chapters.
 */
private class FreePlacementPolicy(
    private val committed: MutableList<Pair<String, Long>>,
) : LanePolicy {
    override fun canDrag(marker: TimeMarker) = true

    override fun clamp(
        marker: TimeMarker,
        proposedMs: Long,
        siblings: List<TimeMarker>,
    ) = proposedMs // identity: no clamping, overlaps and stacking are both legal

    override fun onCommit(
        marker: TimeMarker,
        newMs: Long,
    ) {
        committed += marker.id to newMs
    }
}

private fun marker(
    id: String,
    ms: Long,
) = TimeMarker(id = id, timeMs = ms, label = null, styleKey = "default")

class DragNegotiatorTest :
    FunSpec({
        test("ACCEPTANCE: FreePlacementPolicy allows a marker to cross and land past a sibling") {
            val committed = mutableListOf<Pair<String, Long>>()
            val negotiator = DragNegotiator(FreePlacementPolicy(committed))
            val m1 = marker("m1", 10_000L)
            val sibling = marker("m2", 20_000L)

            negotiator.beginDrag(m1) shouldBe true
            val update = negotiator.dragTo(proposedMs = 25_000L, siblings = listOf(sibling))
            update?.ms shouldBe 25_000L
            update?.resisted shouldBe false
            negotiator.endDrag(25_000L)

            committed shouldBe listOf("m1" to 25_000L)
        }

        test("ACCEPTANCE: FreePlacementPolicy allows two markers to land on the identical ms (stacking)") {
            val committed = mutableListOf<Pair<String, Long>>()
            val policy = FreePlacementPolicy(committed)
            val negotiator = DragNegotiator(policy)
            val m1 = marker("m1", 5_000L)
            val m2 = marker("m2", 5_000L) // already stacked with m1 before the drag even starts

            negotiator.beginDrag(m2) shouldBe true
            val update = negotiator.dragTo(proposedMs = 5_000L, siblings = listOf(m1))
            update?.resisted shouldBe false
            negotiator.endDrag(5_000L)

            committed shouldBe listOf("m2" to 5_000L)
        }

        test("contiguity policy clamps a drag to stay between its neighbors") {
            val committed = mutableListOf<Pair<String, Long>>()
            val negotiator = DragNegotiator(ContiguousTestPolicy(committed))
            val target = marker("mid", 10_000L)
            val left = marker("left", 5_000L)
            val right = marker("right", 15_000L)

            negotiator.beginDrag(target)
            val update = negotiator.dragTo(proposedMs = 20_000L, siblings = listOf(left, right))

            update?.ms shouldBe 14_999L
            update?.resisted shouldBe true
        }

        test("read-only policy refuses beginDrag and never fires onCommit") {
            val policy = ReadOnlyTestPolicy()
            val negotiator = DragNegotiator(policy)

            val started = negotiator.beginDrag(marker("locked", 1_000L))

            started shouldBe false
            negotiator.isDragging shouldBe false
            negotiator.endDrag(2_000L)
            policy.commitCount shouldBe 0
        }

        test("dragTo before beginDrag returns null and does not throw") {
            val negotiator = DragNegotiator(ContiguousTestPolicy(mutableListOf()))
            negotiator.dragTo(proposedMs = 1_000L, siblings = emptyList()) shouldBe null
        }

        test("commit fires exactly once per drag even if endDrag is called twice") {
            val committed = mutableListOf<Pair<String, Long>>()
            val negotiator = DragNegotiator(FreePlacementPolicy(committed))
            negotiator.beginDrag(marker("m1", 1_000L))
            negotiator.dragTo(2_000L, emptyList())

            negotiator.endDrag(2_000L)
            negotiator.endDrag(2_000L) // second call: active is already null, this is a no-op

            committed shouldBe listOf("m1" to 2_000L)
        }

        test("cancelDrag commits nothing") {
            val committed = mutableListOf<Pair<String, Long>>()
            val negotiator = DragNegotiator(FreePlacementPolicy(committed))
            negotiator.beginDrag(marker("m1", 1_000L))
            negotiator.dragTo(9_999L, emptyList())

            negotiator.cancelDrag()

            committed.shouldBeEmpty()
            negotiator.isDragging shouldBe false
        }

        test("dragTo after cancelDrag returns null") {
            val negotiator = DragNegotiator(FreePlacementPolicy(mutableListOf()))
            negotiator.beginDrag(marker("m1", 1_000L))
            negotiator.cancelDrag()
            negotiator.dragTo(1_500L, emptyList()) shouldBe null
        }

        test("resisted is false when clamp returns the exact proposed value") {
            val negotiator = DragNegotiator(FreePlacementPolicy(mutableListOf()))
            negotiator.beginDrag(marker("m1", 1_000L))
            val update = negotiator.dragTo(1_234L, emptyList())
            update?.resisted shouldBe false
        }

        test("a negotiator with no active drag never fires onCommit regardless of dragTo calls") {
            // Models ghost-preview mode: the composable never calls beginDrag while ghosts != null,
            // so dragTo is always a no-op — this pins that contract at the negotiator level.
            val committed = mutableListOf<Pair<String, Long>>()
            val negotiator = DragNegotiator(FreePlacementPolicy(committed))
            negotiator.dragTo(5_000L, emptyList()) shouldBe null
            negotiator.endDrag(5_000L)
            committed.shouldBeEmpty()
        }
    })
