package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import javax.sql.DataSource

private fun DataSource.run(sql: String) = connection.use { c -> c.createStatement().use { it.execute(sql) } }

private fun DataSource.rows(sql: String): List<List<Any?>> =
    connection.use { c ->
        c.createStatement().use { s ->
            s.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) })
                }
            }
        }
    }

private fun DataSource.contributor(
    id: String,
    asin: String?,
) = run(
    "INSERT INTO contributors (id, normalized_name, name, revision, created_at, updated_at, asin) " +
        "VALUES ('$id', '$id', '$id', 1, 0, 0, ${asin?.let { "'$it'" } ?: "NULL"})",
)

/**
 * V91 gives contributors what books got in V87: their refs, backfilled from the legacy `asin` column — an
 * Audnexus key is an Audible ASIN, and the legacy Hardcover apply's `hardcover:author:<id>` is a Hardcover ref —
 * plus a `field_provenance` column so hand edits can be told apart from matched values.
 */
class V91MigrationTest :
    FunSpec({
        test("V91 backfills contributor refs from asin and starts every row with empty provenance") {
            val path =
                Files
                    .createTempFile("listenup-v91-", ".db")
                    .toFile()
                    .apply { deleteOnExit() }
                    .absolutePath
            val ds = fileBackedTestDataSource("jdbc:sqlite:$path")
            MigrationRunner(path).migrate(upTo = 90)
            ds.contributor("audible", asin = " B001IGFHW6 ")
            ds.contributor("hardcover", asin = "hardcover:author:250716")
            ds.contributor("blank", asin = "  ")
            ds.contributor("none", asin = null)

            MigrationRunner(path).migrate()

            ds.rows(
                "SELECT entity_id, provider, external_id, region FROM external_refs " +
                    "WHERE entity_kind = 'contributor' ORDER BY entity_id",
            ) shouldBe
                listOf(
                    listOf("audible", "audible", "B001IGFHW6", null),
                    listOf("hardcover", "hardcover", "250716", null),
                )
            ds.rows("SELECT DISTINCT field_provenance FROM contributors") shouldBe listOf(listOf("{}"))
        }
    })
