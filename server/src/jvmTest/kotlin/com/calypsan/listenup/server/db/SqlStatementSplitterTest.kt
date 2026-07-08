package com.calypsan.listenup.server.db

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

class SqlStatementSplitterTest :
    FunSpec({

        test("splits two simple statements and drops the terminating semicolons") {
            val out = SqlStatementSplitter.split("CREATE TABLE a (x INT);\nCREATE TABLE b (y INT);")
            out shouldBe listOf("CREATE TABLE a (x INT)", "CREATE TABLE b (y INT)")
        }

        test("a semicolon inside a string literal is not a terminator") {
            val out = SqlStatementSplitter.split("INSERT INTO t VALUES ('a;b'); SELECT 1;")
            out shouldHaveSize 2
            out[0] shouldContain "'a;b'"
        }

        test("a semicolon inside a line comment is not a terminator") {
            val out = SqlStatementSplitter.split("-- drop; me\nCREATE TABLE a (x INT);")
            out shouldHaveSize 1
            out[0] shouldContain "CREATE TABLE a (x INT)"
        }

        test("a CREATE TRIGGER ... BEGIN ... END; block stays a single statement") {
            val sql =
                """
                CREATE TRIGGER t AFTER INSERT ON src BEGIN
                    INSERT INTO dst(a) VALUES (new.a);
                    INSERT INTO dst(b) VALUES (new.b);
                END;
                CREATE TABLE after (z INT);
                """.trimIndent()
            val out = SqlStatementSplitter.split(sql)
            out shouldHaveSize 2
            out[0] shouldContain "END"
            out[1] shouldContain "CREATE TABLE after"
        }

        test("a doubled single-quote is an escape, and a semicolon inside the literal is not a terminator") {
            val out = SqlStatementSplitter.split("INSERT INTO t VALUES ('it''s; ok'); SELECT 1;")
            out shouldHaveSize 2
            out[0] shouldContain "'it''s; ok'"
        }

        test("a semicolon inside a block comment is not a terminator") {
            val out = SqlStatementSplitter.split("/* drop; me */\nCREATE TABLE a (x INT);")
            out shouldHaveSize 1
            out[0] shouldContain "CREATE TABLE a (x INT)"
        }

        test("a final statement with no trailing semicolon is still emitted") {
            val out = SqlStatementSplitter.split("CREATE TABLE z (w INT)")
            out shouldBe listOf("CREATE TABLE z (w INT)")
        }
    })
