package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** Planted inputs, so the rule is known to fire before it is trusted to stay quiet. */
class NoSuspendExtensionOnRoomEntityRuleSelfTest :
    FunSpec({
        val entities =
            """
            |@Entity(tableName = "shelves", indices = [Index(value = ["deletedAt"])])
            |internal data class ShelfEntity(@PrimaryKey val id: String)
            |
            |@Entity
            |data class PlainEntity(val id: String)
            |
            |data class NotAnEntity(val id: String)
            """.trimMargin()

        test("finds @Entity classes, with and without arguments, and nothing else") {
            roomEntityNames(entities) shouldBe listOf("ShelfEntity", "PlainEntity")
        }

        test("a suspend extension on an entity is reported by line, even fully qualified") {
            val source =
                """
                |class Repo {
                |    private suspend fun com.example.db.ShelfEntity.toDomain(): Shelf = TODO()
                |    private suspend fun PlainEntity.toDomain(): Plain = TODO()
                |}
                """.trimMargin()
            suspendExtensionsOnEntities(source, setOf("ShelfEntity", "PlainEntity")) shouldBe
                listOf("2:ShelfEntity.toDomain", "3:PlainEntity.toDomain")
        }

        test("the allowed shapes are not reported") {
            val source =
                """
                |private fun ShelfEntity.toDomain(owner: UserEntity?): Shelf = TODO()      // pure mapper
                |private suspend fun deriveShelf(entity: ShelfEntity): Shelf = TODO()      // entity as parameter
                |private suspend fun ShelfWithBookCount.toDomain(): Shelf = TODO()         // receiver is not an entity
                |private suspend fun <T> List<T>.firstShelf(): ShelfEntity? = TODO()       // generic, non-entity receiver
                """.trimMargin()
            suspendExtensionsOnEntities(source, setOf("ShelfEntity")) shouldBe emptyList()
        }
    })
