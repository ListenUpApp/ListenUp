package com.calypsan.listenup.client.presentation.home

/**
 * One section of Home, in the order [homeBlocks] puts them. A sealed interface of objects, not an enum, so it crosses
 * into Swift safely inside a list (a bridged `List<Enum>` traps on element access).
 */
sealed interface HomeBlock {
    /** The quiet offline notice, directly under the greeting (spec §3.1). */
    data object Offline : HomeBlock

    /** "Building your library", above the first-sync skeleton (spec §2.3 FirstSync, H5). */
    data object FirstSyncStrip : HomeBlock

    /** The just-finished card. */
    data object Finished : HomeBlock

    /** The nothing-in-progress card. */
    data object NothingInProgress : HomeBlock

    /** The new-listener welcome. */
    data object Welcome : HomeBlock

    /** The skeleton slot that resolves in place (spec §2.1 rule 4). */
    data object FirstSync : HomeBlock

    /** Continue Listening could not load: one line with Try again, in the slot's position (spec §3.3). */
    data object ContinueListeningFailed : HomeBlock

    /** Whichever slot applies, beside the stats on a wide window (spec §3.4). */
    data object SlotBesideStats : HomeBlock

    /** Every book in progress. */
    data object EverythingInProgress : HomeBlock

    /** Your last 7 days. */
    data object Stats : HomeBlock

    /** My shelves. */
    data object Shelves : HomeBlock

    /** The stats beside the shelves, on a wide window with no slot. */
    data object StatsBesideShelves : HomeBlock
}

/**
 * Home's sections, top to bottom (spec §2.1, §3.4), decided once for every platform. The offline notice sits under the
 * greeting ([isOffline]), then the library-arriving strip ([showsStrip], spec H5), then the slot — or, when Continue
 * Listening failed and there is no slot, the one-line failure in its place. Offline and the failure are separate
 * signals, so both can show. Below the slot the order is always Everything in progress → Your last 7 days → My
 * shelves. A wide window ([isWide]) puts the slot beside the stats, and the stats beside the shelves when there is no
 * slot. What counts as wide is each platform's layout decision; the order is not.
 */
fun homeBlocks(
    slot: HomeSlot,
    isOffline: Boolean,
    continueListeningFailed: Boolean,
    showsStrip: Boolean,
    hasEverythingInProgress: Boolean,
    isWide: Boolean,
): List<HomeBlock> =
    buildList {
        if (isOffline) add(HomeBlock.Offline)
        if (showsStrip) add(HomeBlock.FirstSyncStrip)
        val slotBlock = slotBlockFor(slot)
        when {
            slotBlock != null && isWide -> add(HomeBlock.SlotBesideStats)
            slotBlock != null -> add(slotBlock)
            continueListeningFailed -> add(HomeBlock.ContinueListeningFailed)
        }
        if (hasEverythingInProgress) add(HomeBlock.EverythingInProgress)
        when {
            contains(HomeBlock.SlotBesideStats) -> {
                add(HomeBlock.Shelves)
            }

            isWide -> {
                add(HomeBlock.StatsBesideShelves)
            }

            else -> {
                add(HomeBlock.Stats)
                add(HomeBlock.Shelves)
            }
        }
    }

/** The block that draws [slot], or null when there is no slot. */
internal fun slotBlockFor(slot: HomeSlot): HomeBlock? =
    when (slot) {
        is HomeSlot.JustFinished -> HomeBlock.Finished
        is HomeSlot.NothingInProgress -> HomeBlock.NothingInProgress
        is HomeSlot.NewListener -> HomeBlock.Welcome
        HomeSlot.FirstSync -> HomeBlock.FirstSync
        HomeSlot.Absent -> null
    }
