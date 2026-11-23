package com.calypsan.listenup.server.konsist.fixtures

import com.calypsan.listenup.server.io.writeBytesAtomically
import kotlinx.io.files.Path

/**
 * Deliberately violates [com.calypsan.listenup.server.konsist.LibraryWritesGoThroughBrokerRule]: a
 * library-folder write through the io layer's atomic helper, which the bare `.writeBytes(` token never
 * matched. Exists ONLY so [com.calypsan.listenup.server.konsist.LibraryWritesGoThroughBrokerRuleSelfTest]
 * can assert the rule's predicate fires. Never called by production code, and excluded from
 * [com.lemonappdev.konsist.api.Konsist.scopeFromProduction] because it lives under `jvmTest`.
 */
internal object RogueLibraryWriterFixture {
    fun writeBesideTheAudio(root: String) {
        Path(root, "x").writeBytesAtomically(byteArrayOf())
    }
}
