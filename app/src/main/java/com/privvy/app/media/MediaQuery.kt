package com.privvy.app.media

import android.content.Context
import android.database.MatrixCursor
import java.math.BigDecimal
import java.util.Locale
import java.util.regex.Pattern

/** Projection and the deliberately small, non-executing SQL selection grammar. */
internal object MediaQuery {
    val DEFAULT_COLUMNS = arrayOf(
        "_id",
        "_data",
        "display_name",
        "_display_name",
        "mime_type",
        "size",
        "_size",
        "date_added",
        "date_modified",
        "relative_path",
        "width",
        "height",
        "media_type",
        "bucket_id",
        "bucket_display_name",
        "volume_name",
        "is_pending",
        "is_trashed",
        "owner_package_name",
    )

    private val columns = DEFAULT_COLUMNS.map { it.lowercase(Locale.ROOT) }.toSet()

    fun validateProjection(projection: Array<out String>?): Array<out String> {
        if (projection == null) return DEFAULT_COLUMNS
        projection.forEach { column ->
            require(column.lowercase(Locale.ROOT) in columns) {
                "Unsupported MediaStore column: $column"
            }
        }
        return projection
    }

    fun row(context: Context, spaceId: Int, entry: MediaEntry): Map<String, Any?> {
        val file = MediaMetadataStore(context, spaceId).resolveFile(entry)
        val size = if (file.isFile) file.length() else entry.size
        val modified = if (file.isFile && file.lastModified() > 0L) {
            file.lastModified() / 1000L
        } else {
            entry.dateModified
        }
        val parentPath = entry.relativePath.removeSuffix("/")
        val bucketName = parentPath.substringAfterLast('/', parentPath)
        val bucketId = parentPath.lowercase(Locale.ROOT).hashCode()
        val values = linkedMapOf<String, Any?>(
            "_id" to entry.id,
            "_data" to file.absolutePath,
            "display_name" to entry.displayName,
            "_display_name" to entry.displayName,
            "mime_type" to entry.mimeType,
            "size" to size,
            "_size" to size,
            "date_added" to entry.dateAdded,
            "date_modified" to modified,
            "relative_path" to entry.relativePath,
            "width" to entry.width,
            "height" to entry.height,
            "media_type" to mediaTypeCode(entry.mimeType),
            "bucket_id" to bucketId,
            "bucket_display_name" to bucketName,
            "volume_name" to "external",
            "is_pending" to entry.isPending,
            "is_trashed" to 0,
            "owner_package_name" to entry.ownerPackageName,
        )
        return values.mapKeys { it.key.lowercase(Locale.ROOT) }
    }

    fun mediaTypeCode(mimeType: String): Int = when {
        mimeType.startsWith("image/") -> 1
        mimeType.startsWith("audio/") -> 2
        mimeType.startsWith("video/") -> 3
        else -> 0
    }

    fun buildCursor(
        context: Context,
        spaceId: Int,
        projection: Array<out String>,
        rows: List<Map<String, Any?>>,
        sortOrder: String?,
        limit: Int?,
        offset: Int,
    ): MatrixCursor {
        val cursor = MatrixCursor(projection)
        val ordered = sort(rows, sortOrder)
        val start = offset.coerceAtLeast(0).coerceAtMost(ordered.size)
        val end = if (limit == null) ordered.size else {
            (start.toLong() + limit.coerceAtLeast(0).toLong()).coerceAtMost(ordered.size.toLong()).toInt()
        }
        for (index in start until end) {
            val row = ordered[index]
            cursor.addRow(projection.map { row[it.lowercase(Locale.ROOT)] })
        }
        cursor.setNotificationUri(
            context.applicationContext.contentResolver,
            VirtualMediaStore.collectionUri(spaceId),
        )
        return cursor
    }

    fun selection(selection: String?, args: Array<out String>?): (Map<String, Any?>) -> Boolean {
        if (selection.isNullOrBlank()) {
            if (!args.isNullOrEmpty()) {
                throw IllegalArgumentException("Selection arguments supplied without a selection")
            }
            return { true }
        }
        return SelectionParser(selection, args ?: emptyArray()).parse()
    }

    private fun sort(rows: List<Map<String, Any?>>, sortOrder: String?): List<Map<String, Any?>> {
        if (sortOrder.isNullOrBlank()) return rows.sortedBy { it["_id"] as Long }
        val terms = splitSortTerms(sortOrder)
        if (terms.isEmpty()) throw IllegalArgumentException("Invalid sort order")
        val regex = Regex(
            "^([A-Za-z_][A-Za-z0-9_]*)(?:\\s+COLLATE\\s+(NOCASE|BINARY|RINARY))?" +
                "(?:\\s+(ASC|DESC))?(?:\\s+NULLS\\s+(FIRST|LAST))?$",
            RegexOption.IGNORE_CASE,
        )
        val specs = terms.map { term ->
            val match = regex.matchEntire(term.trim())
                ?: throw IllegalArgumentException("Unsupported sort term: $term")
            val column = match.groupValues[1].lowercase(Locale.ROOT)
            require(column in columns) { "Unsupported sort column: $column" }
            SortSpec(
                column = column,
                descending = match.groupValues[3].equals("DESC", ignoreCase = true),
                caseInsensitive = match.groupValues[2].equals("NOCASE", ignoreCase = true),
                nullsFirst = match.groupValues[4].equals("FIRST", ignoreCase = true),
                hasNullsOrder = match.groupValues[4].isNotEmpty(),
            )
        }
        return rows.sortedWith(Comparator { left, right ->
            for (spec in specs) {
                val a = left[spec.column]
                val b = right[spec.column]
                if (a == null || b == null) {
                    if (a === b) continue
                    val aFirst = if (spec.hasNullsOrder) spec.nullsFirst else !spec.descending
                    return@Comparator if (a == null) {
                        if (aFirst) -1 else 1
                    } else {
                        if (aFirst) 1 else -1
                    }
                }
                val comparison = compareValues(a, b, spec.caseInsensitive)
                if (comparison != 0) {
                    return@Comparator if (spec.descending) -comparison else comparison
                }
            }
            (left["_id"] as Long).compareTo(right["_id"] as Long)
        })
    }

    private fun splitSortTerms(value: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var depth = 0
        value.forEach { char ->
            when (char) {
                '(' -> { depth++; current.append(char) }
                ')' -> { depth--; current.append(char) }
                ',' -> if (depth == 0) {
                    result.add(current.toString())
                    current.clear()
                } else current.append(char)
                else -> current.append(char)
            }
        }
        if (depth != 0) throw IllegalArgumentException("Invalid sort order")
        result.add(current.toString())
        return result.filter { it.isNotBlank() }
    }

    private fun compareValues(a: Any, b: Any, caseInsensitive: Boolean): Int {
        val numberA = a as? Number
        val numberB = b as? Number
        if (numberA != null && numberB != null) {
            return BigDecimal(numberA.toString()).compareTo(BigDecimal(numberB.toString()))
        }
        val textA = a.toString()
        val textB = b.toString()
        return if (caseInsensitive) {
            textA.compareTo(textB, ignoreCase = true)
        } else {
            textA.compareTo(textB)
        }
    }

    private data class SortSpec(
        val column: String,
        val descending: Boolean,
        val caseInsensitive: Boolean,
        val nullsFirst: Boolean,
        val hasNullsOrder: Boolean,
    )

    private class SelectionParser(
        private val source: String,
        private val args: Array<out String>,
    ) {
        private val tokens = tokenize(source)
        private var position = 0
        private var argument = 0

        fun parse(): (Map<String, Any?>) -> Boolean {
            val expression = parseOr()
            expect(TokenType.EOF)
            if (argument != args.size) {
                throw IllegalArgumentException("Wrong number of selection arguments")
            }
            return expression
        }

        private fun parseOr(): (Map<String, Any?>) -> Boolean {
            var left = parseAnd()
            while (match(TokenType.OR)) {
                val right = parseAnd()
                val previous = left
                left = { row -> previous(row) || right(row) }
            }
            return left
        }

        private fun parseAnd(): (Map<String, Any?>) -> Boolean {
            var left = parsePredicate()
            while (match(TokenType.AND)) {
                val right = parsePredicate()
                val previous = left
                left = { row -> previous(row) && right(row) }
            }
            return left
        }

        private fun parsePredicate(): (Map<String, Any?>) -> Boolean {
            if (match(TokenType.LPAREN)) {
                val nested = parseOr()
                expect(TokenType.RPAREN)
                return nested
            }

            val columnToken = expect(TokenType.IDENT)
            val column = columnToken.text.lowercase(Locale.ROOT)
            require(column in columns) { "Unsupported selection column: $column" }

            if (match(TokenType.IS)) {
                val negate = match(TokenType.NOT)
                expect(TokenType.NULL)
                return { row ->
                    val isNull = row[column] == null
                    if (negate) !isNull else isNull
                }
            }

            val negate = match(TokenType.NOT)
            if (match(TokenType.IN)) {
                expect(TokenType.LPAREN)
                val values = mutableListOf<Any?>()
                do {
                    values.add(parseValue())
                } while (match(TokenType.COMMA))
                expect(TokenType.RPAREN)
                return { row ->
                    val actual = row[column]
                    val contained = actual != null && values.any { expected ->
                        expected != null && equalValues(actual, expected)
                    }
                    if (negate) actual != null && !contained else contained
                }
            }

            if (match(TokenType.LIKE)) {
                val expected = parseValue()
                val pattern = likePattern(expected?.toString() ?: "")
                return { row ->
                    val actual = row[column]?.toString()
                    val matches = actual != null && pattern.matcher(actual).matches()
                    if (negate) actual != null && !matches else matches
                }
            }
            if (negate) throw IllegalArgumentException("Expected IN or LIKE after NOT")

            val operator = expect(TokenType.OP).text
            val expected = parseValue()
            return { row ->
                val actual = row[column]
                actual != null && expected != null && compare(operator, actual, expected)
            }
        }

        private fun parseValue(): Any? = when {
            match(TokenType.PARAM) -> {
                if (argument >= args.size) throw IllegalArgumentException("Missing selection argument")
                args[argument++]
            }
            match(TokenType.STRING) -> tokens[position - 1].text
            match(TokenType.NUMBER) -> BigDecimal(tokens[position - 1].text)
            match(TokenType.NULL) -> null
            else -> throw IllegalArgumentException("Expected a selection value at position $position")
        }

        private fun compare(operator: String, actual: Any, expected: Any): Boolean {
            val numericActual = actual as? Number
            val numericExpected = expected as? BigDecimal
                ?: expected.toString().toBigDecimalOrNull()
            val comparison = if (numericActual != null && numericExpected != null) {
                BigDecimal(numericActual.toString()).compareTo(numericExpected)
            } else if (numericActual != null) {
                return false
            } else {
                actual.toString().compareTo(expected.toString())
            }
            return when (operator) {
                "=", "==" -> comparison == 0
                "!=", "<>" -> comparison != 0
                "<" -> comparison < 0
                "<=" -> comparison <= 0
                ">" -> comparison > 0
                ">=" -> comparison >= 0
                else -> throw IllegalArgumentException("Unsupported operator: $operator")
            }
        }

        private fun equalValues(actual: Any, expected: Any): Boolean =
            compare("=", actual, expected)

        private fun likePattern(value: String): Pattern {
            val regex = StringBuilder("^")
            value.forEach { char ->
                when (char) {
                    '%' -> regex.append(".*")
                    '_' -> regex.append('.')
                    else -> regex.append(Pattern.quote(char.toString()))
                }
            }
            regex.append('$')
            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE)
        }

        private fun match(type: TokenType): Boolean {
            if (position >= tokens.size || tokens[position].type != type) return false
            position++
            return true
        }

        private fun expect(type: TokenType): Token {
            val token = tokens.getOrNull(position)
                ?: throw IllegalArgumentException("Unexpected end of selection")
            if (token.type != type) {
                throw IllegalArgumentException("Expected ${type.name}, found ${token.type.name}")
            }
            position++
            return token
        }

        companion object {
            private fun tokenize(value: String): List<Token> {
                val result = mutableListOf<Token>()
                var index = 0
                while (index < value.length) {
                    val char = value[index]
                    if (char.isWhitespace()) {
                        index++
                        continue
                    }
                    when {
                        char == '(' -> { result.add(Token(TokenType.LPAREN)); index++ }
                        char == ')' -> { result.add(Token(TokenType.RPAREN)); index++ }
                        char == ',' -> { result.add(Token(TokenType.COMMA)); index++ }
                        char == '?' -> { result.add(Token(TokenType.PARAM)); index++ }
                        char == '\'' -> {
                            val text = StringBuilder()
                            index++
                            var closed = false
                            while (index < value.length) {
                                val current = value[index++]
                                if (current == '\'') {
                                    if (index < value.length && value[index] == '\'') {
                                        text.append('\'')
                                        index++
                                    } else {
                                        closed = true
                                        break
                                    }
                                } else {
                                    text.append(current)
                                }
                            }
                            if (!closed) throw IllegalArgumentException("Unterminated selection string")
                            result.add(Token(TokenType.STRING, text.toString()))
                        }
                        char.isDigit() || (char == '-' && index + 1 < value.length && value[index + 1].isDigit()) -> {
                            val start = index
                            if (char == '-') index++
                            while (index < value.length && value[index].isDigit()) index++
                            if (index < value.length && value[index] == '.') {
                                index++
                                while (index < value.length && value[index].isDigit()) index++
                            }
                            result.add(Token(TokenType.NUMBER, value.substring(start, index)))
                        }
                        char.isLetter() || char == '_' -> {
                            val start = index
                            index++
                            while (index < value.length && (value[index].isLetterOrDigit() || value[index] == '_')) {
                                index++
                            }
                            val text = value.substring(start, index)
                            val type = keywords[text.uppercase(Locale.ROOT)] ?: TokenType.IDENT
                            result.add(Token(type, text))
                        }
                        char == '=' || char == '!' || char == '<' || char == '>' -> {
                            val start = index++
                            if (index < value.length && value[index] == '=') index++
                            if (char == '!' && value.substring(start, index) != "!=") {
                                throw IllegalArgumentException("Invalid selection operator")
                            }
                            if (char == '<' && index < value.length && value[index] == '>') index++
                            result.add(Token(TokenType.OP, value.substring(start, index)))
                        }
                        else -> throw IllegalArgumentException("Invalid selection character: $char")
                    }
                }
                result.add(Token(TokenType.EOF))
                return result
            }

            private val keywords = mapOf(
                "AND" to TokenType.AND,
                "OR" to TokenType.OR,
                "NOT" to TokenType.NOT,
                "IN" to TokenType.IN,
                "LIKE" to TokenType.LIKE,
                "IS" to TokenType.IS,
                "NULL" to TokenType.NULL,
            )
        }
    }

    private enum class TokenType { IDENT, STRING, NUMBER, PARAM, NULL, OP, LPAREN, RPAREN, COMMA, AND, OR, NOT, IN, LIKE, IS, EOF }
    private data class Token(val type: TokenType, val text: String = "")
}
