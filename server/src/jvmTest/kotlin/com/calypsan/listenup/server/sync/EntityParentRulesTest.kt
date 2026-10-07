package com.calypsan.listenup.server.sync

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** [EntityParentRules.isAncestor]'s walk: found, not found, and a corrupt chain that never ends. */
class EntityParentRulesTest :
    FunSpec({
        test("an ancestor on the chain is found, and a chain that ends without it is not") {
            val parents = mapOf("c" to "b", "b" to "a")
            EntityParentRules.isAncestor("a", startingAt = "c", parentOf = parents::get) shouldBe true
            EntityParentRules.isAncestor("z", startingAt = "c", parentOf = parents::get) shouldBe false
        }

        test("a stored loop that never reaches the ancestor counts as a cycle, not as clear") {
            val loop = mapOf("x" to "y", "y" to "x")
            EntityParentRules.isAncestor("z", startingAt = "x", parentOf = loop::get) shouldBe true
        }
    })
