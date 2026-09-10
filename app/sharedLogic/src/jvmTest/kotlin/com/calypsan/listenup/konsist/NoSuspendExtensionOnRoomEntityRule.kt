package com.calypsan.listenup.konsist

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * No `suspend` extension function may take a Room `@Entity` class as its receiver.
 *
 * Two reasons, and the rule stands on the first alone. An entity is a persistence row; a mapper on
 * it (`fun ShelfEntity.toDomain(...)`) must be pure — every extra fact the domain needs arrives as a
 * parameter, gathered by the repository, so the mapper is testable with no database and I/O lives
 * in exactly one layer. A `suspend` extension is a mapper doing I/O, which is the drift this rule
 * pins shut.
 *
 * The second reason is why it exists today: Kotlin/Native 2.4.20 crashes lowering exactly this
 * shape (`NativeSuspendFunctionsLowering` → `isRestrictsSuspensionReceiver` → `toConstantValue`
 * cannot convert the nested `Index(...)` inside `@Entity(indices = [...])`). A suspend *member* and
 * a suspend function taking the entity as a *parameter* both compile — only the extension receiver
 * is affected. When JetBrains fixes it, the rule stays, for the first reason.
 */
class NoSuspendExtensionOnRoomEntityRule :
    FunSpec({
        test("no suspend extension function has a Room @Entity class as its receiver") {
            val sources = productionScope().files.map { it.path to it.text }
            val entities = sources.flatMap { (_, text) -> roomEntityNames(text) }.toSet()
            val offenders =
                sources.flatMap { (path, text) ->
                    suspendExtensionsOnEntities(text, entities).map { "$path:$it" }
                }
            offenders.shouldBeEmpty()
        }
    })

private val entityDeclaration =
    Regex("""@Entity\b(?:\((?:[^()]|\([^()]*\))*\))?\s*(?:@\w+(?:\([^)]*\))?\s*)*(?:\w+\s+)*class\s+(\w+)""")

/** Simple names of every class in [text] annotated `@Entity`. */
internal fun roomEntityNames(text: String): List<String> =
    entityDeclaration.findAll(text).map { it.groupValues[1] }.toList()

/**
 * `line:Receiver.function` for every `suspend fun` in [text] whose extension receiver's simple name
 * is in [entities]. The receiver may be fully qualified (`suspend fun a.b.ShelfEntity.f()`), which is
 * exactly how the one real offender was written and why a naive grep never found it.
 */
internal fun suspendExtensionsOnEntities(
    text: String,
    entities: Set<String>,
): List<String> =
    Regex("""\bsuspend\s+fun\s+(?:<[^>]*>\s+)?(?:[\w.]+\.)?(\w+)\.(\w+)\s*\(""")
        .findAll(text)
        .filter { it.groupValues[1] in entities }
        .map { m ->
            "${text.substring(0, m.range.first).count { it == '\n' } + 1}:${m.groupValues[1]}.${m.groupValues[2]}"
        }.toList()
