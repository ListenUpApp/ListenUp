package com.calypsan.listenup.server.db

import com.calypsan.listenup.server.testing.fileBackedTestDataSource
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SwappableDataSourceTest :
    FunSpec({
        test("delegates getConnection to the current source, and routes to the new source after install") {
            val dbA = Files.createTempFile("swap-a", ".db")
            val dbB = Files.createTempFile("swap-b", ".db")
            val swappable = SwappableDataSource(fileBackedTestDataSource("jdbc:sqlite:$dbA"))

            // Writes go to dbA via the initial delegate.
            swappable.connection.use { c ->
                c.createStatement().use { it.execute("CREATE TABLE t(v TEXT)") }
                c.createStatement().use { it.execute("INSERT INTO t VALUES ('A')") }
            }

            // Swap the delegate to a different file; subsequent connections route to the new source.
            swappable.closeCurrent()
            swappable.install(fileBackedTestDataSource("jdbc:sqlite:$dbB"))
            swappable.connection.use { c ->
                c.createStatement().use { it.execute("CREATE TABLE t(v TEXT)") }
                c.createStatement().use { it.execute("INSERT INTO t VALUES ('B')") }
                val v =
                    c.createStatement().use { st ->
                        st.executeQuery("SELECT v FROM t").use { rs ->
                            rs.next()
                            rs.getString(1)
                        }
                    }
                // Reads the row from dbB (the swapped-in source), not dbA's 'A'.
                v shouldBe "B"
            }
            swappable.close()
        }

        test("current() returns the installed delegate") {
            val db = Files.createTempFile("swap-c", ".db")
            val ds = fileBackedTestDataSource("jdbc:sqlite:$db")
            val swappable = SwappableDataSource(ds)
            swappable.current() shouldBe ds
            swappable.close()
        }
    })
