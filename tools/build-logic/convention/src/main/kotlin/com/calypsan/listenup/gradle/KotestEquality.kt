package com.calypsan.listenup.gradle

import org.gradle.api.tasks.testing.Test

/** The Kotest switch for its field-by-field data class comparison. */
internal const val KOTEST_DATA_CLASS_DIFFS_PROPERTY = "kotest.assertions.show-data-class-diffs"

/**
 * Makes Kotest's `shouldBe` plain `equals` in [this] JVM test task, by turning off its field-by-field
 * comparison of data classes.
 *
 * That comparison exists to print a readable diff, and on Kotest 6.2.5 it can pass two values that are
 * not equal: `Holder(Filled(1)) shouldBe Holder(Empty)` passes when the expected side nests a data
 * object where the actual side nests a data class. Any "this is a failure of kind X" assertion has that
 * shape. With the comparison off, a mismatch still fails, with both values printed in full.
 *
 * JVM only, because the comparison lives only in Kotest's JVM artifact: the native lanes already compare
 * with `equals`. `ShouldBeIsEqualsSpec` in each module's commonTest holds this setting in place.
 */
fun Test.useStrictKotestEquality() {
    systemProperty(KOTEST_DATA_CLASS_DIFFS_PROPERTY, "false")
}
