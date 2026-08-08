package com.calypsan.listenup.client.data.local.db

import androidx.room3.RoomDatabase
import androidx.sqlite.SQLiteConnection

/**
 * Room callback that creates the FTS5 search tables on every platform.
 *
 * The `books_fts` / `contributors_fts` / `series_fts` virtual tables are created here via raw
 * `CREATE VIRTUAL TABLE` rather than being declared as `@Fts5` Room entities. `onOpen` (not
 * `onCreate`) guarantees they exist on fresh installs *and* on databases that predate this
 * callback — `IF NOT EXISTS` makes it idempotent.
 *
 * **Why not `@Fts5 @Entity`, now that Room 3 ships the annotation?** Because `@Fts4`/`@Fts5`
 * do not work on Kotlin/Native targets in Room 3.0.0, so declaring these as entities breaks
 * the iOS build. `FtsTableEntityProcessor.getContentEntity()` decides "this entity has no
 * external content" by comparing the annotation's `contentEntity` against
 * `processingEnv.requireType(Object::class)` — a *JVM* type. The annotation's default value is
 * `java.lang.Object`, which does not resolve under native KSP, so the lookup yields null and
 * the processor fails with `Cannot find external content entity class.` Since "no external
 * content" can only be expressed by leaving that default, standalone FTS entities are
 * unreachable on native. Verified on `kspKotlinIosArm64` (2026-07-24): setting `contentEntity`
 * explicitly makes the error disappear, which isolates the defaulted value as the cause.
 * External content is not a workaround here either — `books_fts` denormalizes contributor,
 * series and genre names into the index, so its columns are not a subset of any single table.
 *
 * Revisit when Room fixes that sentinel comparison — re-run `kspKotlinIosArm64` to check, since
 * the JVM and Android lanes pass either way and will not tell you. The migration is small and
 * fully specified: each `CREATE VIRTUAL TABLE` below becomes an `internal data class` annotated
 * `@Entity(tableName = "…") @Fts5(tokenizer = FtsOptions.TOKENIZER_TRIGRAM)` with a
 * `@PrimaryKey @ColumnInfo(name = "rowid") val rowid: Int = 0` plus one property per column
 * here; register all three in [ListenUpDatabase]'s `entities`; drop every
 * `@SkipQueryVerification` in [SearchDao]; delete this class. The payoff is compile-time
 * verification of every FTS query, which is why it is worth doing when it becomes possible.
 *
 * **Tokenizer: trigram.** Previously `porter`, which stems English words to a common root.
 * Trigram indexes overlapping three-character sequences instead, which buys substring matching
 * — "undat" finds *Foundation*, "arkne" finds *Darkness* — and much better behaviour on
 * non-Latin text. It costs English stemming ("runs" no longer finds "Running") and cannot
 * satisfy a query shorter than three characters at all. That trade is deliberate: search intent
 * here is dominated by titles, authors and narrators, which are proper nouns where stemming
 * does nothing and partial recall does a great deal, while stemming only really pays over
 * description prose — the weakest signal we index. `SearchTokenizerCharacterizationTest` pins
 * both halves of the trade, including the sub-three-character limit, which the UI must surface
 * as "keep typing" rather than "no results".
 *
 * **Why standalone tables rather than external-content FTS:** sync deletes all and reinserts,
 * so there are no triggers to keep honest, and the storage overhead is acceptable for the
 * reliability.
 *
 * Registered in exactly one place — `buildConfigured` in `DatabaseBuilder.kt` — so it cannot
 * be wired into some platforms and forgotten on others. It once was: the callback reached the
 * Android and JVM builders but not the Apple one, so every search and FTS rebuild on iOS threw
 * `no such table: books_fts`, storming the sync/reconcile loop. `FtsTableCallbackTest` pins the
 * contract that opening a database makes all three tables queryable.
 */
internal class FtsTableCallback : RoomDatabase.Callback() {
    override suspend fun onOpen(connection: SQLiteConnection) {
        super.onOpen(connection)

        connection.executeDdl(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS books_fts USING fts5(
                bookId,
                title,
                subtitle,
                description,
                author,
                narrator,
                seriesName,
                genres,
                tokenize='trigram'
            )
            """.trimIndent(),
        )

        connection.executeDdl(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS contributors_fts USING fts5(
                contributorId,
                name,
                sortName,
                aliases,
                description,
                tokenize='trigram'
            )
            """.trimIndent(),
        )

        connection.executeDdl(
            """
            CREATE VIRTUAL TABLE IF NOT EXISTS series_fts USING fts5(
                seriesId,
                name,
                description,
                tokenize='trigram'
            )
            """.trimIndent(),
        )
    }
}
