package com.calypsan.listenup.web.features.nowplaying

import com.calypsan.listenup.web.ViewportFrames
import com.calypsan.listenup.web.awaitFrame
import com.calypsan.listenup.web.motion.forgetPageArrival
import com.calypsan.listenup.web.nav.forgetScrollMemory
import com.calypsan.listenup.web.press
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import org.w3c.dom.Element
import org.w3c.dom.MutationObserver
import org.w3c.dom.MutationObserverInit
import org.w3c.dom.MutationRecord

/**
 * What a frame of the morph costs (canvas `GestureWeb`, HOW IT'S DRAWN): one style write, of one number, on one
 * element. A move under the finger adds and removes no node, touches no other attribute, and recomposes neither Now
 * Playing nor the bar, which is what keeps the drag off the event loop (the blob-URL churn that once starved it).
 */
class PlayerMorphCostTest :
    FunSpec({
        val frames = ViewportFrames()
        afterTest {
            frames.disposeAll()
            forgetScrollMemory()
            forgetPageArrival()
        }

        test("a 30-move drag writes only --mp-p on the shell, adds no node, and recomposes neither the page nor the bar") {
            val probes = mutableMapOf<String, Int>()
            val rig =
                frames.morphRig("/library", compositionProbe = { name -> probes[name] = (probes[name] ?: 0) + 1 })
            try {
                awaitFrame()
                val finger = rig.frame.find(".tport-body").press("touch")
                // Past the slop: `/playing` is pushed and the page mounts and is measured. That is the start's cost,
                // paid once; what follows is the per-frame cost.
                finger.moveBy(-20.0)
                awaitFrame()
                awaitFrame()
                rig.isMorphing() shouldBe true
                // It mounted (and settled its own refs, a composition or two): the probe is wired, so a zero below means
                // something.
                ((probes[NOW_PLAYING_PAGE_PROBE] ?: 0) > 0) shouldBe true
                probes.clear()

                val records = mutableListOf<MutationRecord>()
                val observer = MutationObserver { delivered, _ -> records += delivered }
                val options = js("{}")
                options.subtree = true
                options.childList = true
                options.attributes = true
                options.attributeOldValue = true
                observer.observe(rig.frame.host.ownerDocument!!.documentElement!!, options.unsafeCast<MutationObserverInit>())
                finger.moveBy(-150.0, steps = 30, stepMs = 16)
                awaitFrame()
                records += observer.takeRecords()
                observer.disconnect()

                records.shouldNotBeEmpty()
                records.filter { it.type == "childList" }.shouldBeEmpty()
                records.filterNot { it.type == "attributes" && it.attributeName == "style" && it.target == rig.shell }
                    .map { "${it.type} ${it.attributeName} on ${(it.target as? Element)?.className}" }
                    .shouldBeEmpty()
                styleValuesAcross(records, rig.shell.getAttribute("style").orEmpty())
                    .map(::withoutProgress)
                    .distinct()
                    .size shouldBe 1

                (probes[NOW_PLAYING_PAGE_PROBE] ?: 0) shouldBe 0
                (probes[TRANSPORT_BAR_PROBE] ?: 0) shouldBe 0
                finger.up()
            } finally {
                rig.dispose()
            }
        }
    })

/** The style attribute before each record, then [now]: every value it took across the drag. */
private fun styleValuesAcross(
    records: List<MutationRecord>,
    now: String,
): List<String> = records.map { it.oldValue.orEmpty() } + now

/** A style attribute with its `--mp-p` declaration taken out: what must not change from one frame to the next. */
private fun withoutProgress(style: String): String =
    style
        .split(";")
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("$MORPH_PROGRESS:") }
        .joinToString(";")
