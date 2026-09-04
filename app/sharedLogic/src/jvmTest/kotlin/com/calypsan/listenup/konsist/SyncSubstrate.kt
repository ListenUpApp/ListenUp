package com.calypsan.listenup.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.modifierprovider.withoutAbstractModifier
import java.io.File

/**
 * Shared discovery for the server's SQLDelight sync substrate — the single place the
 * architectural rules learn what the substrate looks like.
 *
 * **Why this exists.** Four rules used to hard-code the Exposed-era names `SyncableRepository`,
 * `SyncableTable` and `UserScopedSyncableTable`. When the substrate migrated to SQLDelight those
 * declarations vanished, every one of those rules matched nothing, and all four passed green
 * while the invariants they named went unguarded. Centralising discovery means the next rename
 * breaks *one* file — and the `shouldNotBeEmpty` guards at each call site turn that break into a
 * red build instead of a silent hollowing-out.
 */
private const val SYNC_BASE_CLASS = "SqlSyncableRepository"

/** Sync-substrate columns every syncable root table must carry for catch-up/digest to work. */
val REQUIRED_SYNC_COLUMNS = listOf("revision", "created_at", "updated_at", "deleted_at", "client_op_id")

/**
 * The column that marks a row as belonging to one user. Exact-match matters:
 * `libraries.created_by_user_id` is provenance, not ownership, and a substring match would flag it
 * as a per-user domain it isn't.
 */
const val USER_ID_COLUMN = "user_id"

/** A concrete syncable repository, with the facts the rules need projected off its declaration. */
data class SyncableRepositoryFacts(
    val name: String,
    /** The generated queries wrapper the `substrate` adapter reads, e.g. `series` for `db.seriesQueries`. */
    val queriesName: String?,
    /** Root table name, resolved through the `.sq` file that backs [queriesName]. Null if unresolvable. */
    val rootTable: String?,
    /** The value-class name of the `ID` type argument, or null when the id is not a value class. */
    val valueClassIdName: String?,
    /** Declares `override val userScoped = true` — the per-user substrate path. */
    val isUserScoped: Boolean,
    /** Declares `override val driver` — the per-row access-filtered catch-up/digest path. */
    val isAccessFiltered: Boolean,
    /** Declares `override fun idAsString(...)`. */
    val overridesIdAsString: Boolean,
)

/** Strips a generic argument list: `SqlSyncableRepository<Shelf, ShelfId>` → `SqlSyncableRepository`. */
private fun String.bareTypeName(): String = substringBefore('<')

/**
 * The root table's queries wrapper is whatever the `substrate` property adapts — by definition.
 * Reading it from the `substrate` declaration specifically (rather than the first `db.*Queries`
 * anywhere in the class) stops aggregates that also read child tables resolving to a child.
 */
private val queriesRef = Regex("""\bdb\.([A-Za-z][A-Za-z0-9]*)Queries\b""")

private val createTable =
    Regex("""^CREATE\s+TABLE\s+(?:IF\s+NOT\s+EXISTS\s+)?[`"]?(\w+)[`"]?""", RegexOption.IGNORE_CASE)

private val sqlDelightDir: File
    get() = File(Konsist.projectRootPath, "server/src/commonMain/sqldelight")

/**
 * SQLDelight names the generated queries wrapper after the **`.sq` filename**, not the table:
 * `Series.sq` generates `seriesQueries` for a table actually called `book_series`. So the
 * queries name resolves to a file, and the file's first `CREATE TABLE` is the aggregate root.
 * (Only `Contributors.sq` declares a second table — `contributor_aliases`, a child.)
 *
 * Deriving the table by snake_casing the queries name instead — the obvious-looking shortcut —
 * silently mis-resolves `seriesQueries` to a non-existent `series` table, and every rule keyed on
 * it would skip that repository without a word. [rootTable] is null when the file is missing, and
 * the rules assert on that rather than skipping.
 */
private fun rootTableOf(queriesName: String): String? {
    val file =
        File(sqlDelightDir.absolutePath).walkTopDown().firstOrNull { candidate ->
            candidate.isFile &&
                candidate.extension == "sq" &&
                candidate.nameWithoutExtension.equals(queriesName, ignoreCase = true)
        } ?: return null
    return file.readLines().firstNotNullOfOrNull { createTable.find(it)?.groupValues?.get(1) }
}

/** Every concrete `SqlSyncableRepository` subclass in `:server` production code. */
fun syncableRepositories(): List<SyncableRepositoryFacts> =
    productionScope()
        .classes()
        .withoutAbstractModifier()
        .filter { cls ->
            cls.path.contains("/server/") &&
                cls.parents().any { it.name.bareTypeName() == SYNC_BASE_CLASS }
        }.map { cls ->
            val queriesName =
                cls
                    .properties()
                    .firstOrNull { it.name == "substrate" }
                    ?.text
                    ?.let { queriesRef.find(it)?.groupValues?.get(1) }
            val idArg =
                cls
                    .parents()
                    .first { it.name.bareTypeName() == SYNC_BASE_CLASS }
                    .typeArguments
                    ?.getOrNull(1)
            val idClass = idArg?.sourceDeclaration?.asClassDeclaration()
            SyncableRepositoryFacts(
                name = cls.name,
                queriesName = queriesName,
                rootTable = queriesName?.let { rootTableOf(it) },
                valueClassIdName = idClass?.takeIf { it.hasValueModifier }?.name,
                isUserScoped =
                    cls.properties().any { prop ->
                        prop.name == "userScoped" && prop.hasOverrideModifier && prop.text.contains("true")
                    },
                isAccessFiltered =
                    cls.properties().any { prop -> prop.name == "driver" && prop.hasOverrideModifier },
                overridesIdAsString =
                    cls.functions().any { fn -> fn.name == "idAsString" && fn.hasOverrideModifier },
            )
        }

/** The declared columns of [table], or null when no such `CREATE TABLE` exists in the schema. */
fun sqlColumnsOf(table: String): Set<String>? {
    sqlDelightDir.walkTopDown().filter { it.isFile && it.extension == "sq" }.forEach { file ->
        val lines = file.readLines()
        val start = lines.indexOfFirst { createTable.find(it)?.groupValues?.get(1) == table }
        if (start < 0) return@forEach
        val columns = mutableSetOf<String>()
        for (line in lines.drop(start + 1)) {
            if (line.trimStart().startsWith(")")) break
            Regex("""^\s*(\w+)\s+""")
                .find(line)
                ?.groupValues
                ?.get(1)
                ?.let { columns += it }
        }
        return columns
    }
    return null
}
