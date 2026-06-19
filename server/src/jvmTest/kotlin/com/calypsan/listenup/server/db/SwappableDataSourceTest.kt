package com.calypsan.listenup.server.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files

class SwappableDataSourceTest :
    FunSpec({
        fun poolOn(path: String): HikariDataSource =
            HikariDataSource(
                HikariConfig().apply {
                    jdbcUrl = "jdbc:sqlite:$path"
                    maximumPoolSize = 2
                    isAutoCommit = true
                    validate()
                },
            )

        test("delegates getConnection to the current pool, and routes to the new pool after install") {
            val dbA = Files.createTempFile("swap-a", ".db")
            val dbB = Files.createTempFile("swap-b", ".db")
            val poolA = poolOn(dbA.toString())
            val swappable = SwappableDataSource(poolA)

            swappable.connection.use { c ->
                c.createStatement().use { it.execute("CREATE TABLE t(v TEXT)") }
                c.createStatement().use { it.execute("INSERT INTO t VALUES ('A')") }
            }

            swappable.closeCurrent()
            shouldThrowAny { swappable.connection } // closed pool serves nothing

            val poolB = poolOn(dbB.toString())
            swappable.install(poolB)
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
                v shouldBe "B"
            }
            swappable.close()
        }

        test("current() returns the installed delegate") {
            val db = Files.createTempFile("swap-c", ".db")
            val pool = poolOn(db.toString())
            val swappable = SwappableDataSource(pool)
            swappable.current() shouldBe pool
            swappable.close()
        }
    })
