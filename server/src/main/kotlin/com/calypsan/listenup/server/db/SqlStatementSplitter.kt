package com.calypsan.listenup.server.db

/**
 * Splits a multi-statement SQLite migration script into individual executable statements.
 *
 * Aware of `--` line comments, slash-star block comments, single- and double-quoted
 * literals/identifiers (with doubled-quote escapes), and `CREATE TRIGGER … BEGIN … END;`
 * blocks whose internal semicolons must NOT terminate the statement. Terminating semicolons
 * are dropped; comments and internal semicolons are preserved verbatim.
 */
internal object SqlStatementSplitter {
    fun split(sql: String): List<String> = Scan(sql).run()

    /**
     * Single-pass cursor over [sql]. Each lexical span (comment, quoted literal, word) is consumed
     * by its own small method so no single function carries the whole scanner's complexity.
     */
    private class Scan(
        private val sql: String,
    ) {
        private val statements = mutableListOf<String>()
        private val current = StringBuilder()
        private var i = 0
        private var beginDepth = 0

        fun run(): List<String> {
            while (i < sql.length) step()
            flush()
            return statements
        }

        private fun step() {
            val c = sql[i]
            when {
                c == '-' && peek(1) == '-' -> consumeLineComment()
                c == '/' && peek(1) == '*' -> consumeBlockComment()
                c == '\'' || c == '"' -> consumeQuoted(c)
                c.isLetter() -> consumeWord()
                c == ';' && beginDepth == 0 -> terminate()
                else -> appendCurrent()
            }
        }

        private fun peek(offset: Int): Char? = sql.getOrNull(i + offset)

        /** Appends the char at the cursor and advances past it. */
        private fun appendCurrent() {
            current.append(sql[i])
            i++
        }

        private fun consumeLineComment() {
            while (i < sql.length && sql[i] != '\n') appendCurrent()
        }

        private fun consumeBlockComment() {
            current.append("/*")
            i += 2
            while (i < sql.length && !isBlockCommentEnd()) appendCurrent()
            if (i < sql.length) {
                current.append("*/")
                i += 2
            }
        }

        private fun isBlockCommentEnd(): Boolean = sql[i] == '*' && peek(1) == '/'

        private fun consumeQuoted(quote: Char) {
            appendCurrent() // opening quote
            while (i < sql.length) {
                val c = sql[i]
                when {
                    c == quote && peek(1) == quote -> {
                        current.append(quote).append(quote) // doubled-quote escape
                        i += 2
                    }

                    c == quote -> {
                        appendCurrent() // closing quote
                        return
                    }

                    else -> {
                        appendCurrent()
                    }
                }
            }
        }

        private fun consumeWord() {
            val start = i
            while (i < sql.length && isWordChar(sql[i])) i++
            val word = sql.substring(start, i)
            current.append(word)
            when (word.uppercase()) {
                "BEGIN" -> beginDepth++
                "END" -> if (beginDepth > 0) beginDepth--
            }
        }

        private fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

        private fun terminate() {
            flush()
            i++
        }

        private fun flush() {
            val stmt = current.toString().trim()
            if (stmt.isNotEmpty()) statements.add(stmt)
            current.clear()
        }
    }
}
