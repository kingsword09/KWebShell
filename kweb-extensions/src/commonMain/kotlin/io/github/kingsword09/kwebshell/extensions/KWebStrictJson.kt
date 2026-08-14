package io.github.kingsword09.kwebshell.extensions

internal object KWebStrictJsonObjectKeyValidator {
    fun validate(text: String) {
        duplicateKey(text)?.let { duplicate ->
            extensionFailure(
                code = "extensions.manifest.duplicate-json-key",
                details = mapOf("key" to duplicate),
                message = "The extension manifest contains duplicate JSON object key '$duplicate'.",
            )
        }
    }

    fun duplicateKey(text: String): String? {
        val reader = Reader(text)
        return if (reader.readDocument()) reader.duplicateKey else null
    }

    private class Reader(private val text: String) {
        var duplicateKey: String? = null
            private set
        private var index = 0

        fun readDocument(): Boolean = try {
            skipWhitespace()
            readValue(0)
            skipWhitespace()
            index == text.length
        } catch (_: MalformedJson) {
            false
        }

        private fun readValue(depth: Int) {
            if (depth > MAX_JSON_DEPTH) {
                extensionFailure(
                    code = "extensions.manifest.nesting-too-deep",
                    details = mapOf("depth" to depth.toString()),
                    message = "The extension manifest exceeds the maximum JSON nesting depth.",
                )
            }
            when (peek()) {
                '{' -> readObject(depth + 1)
                '[' -> readArray(depth + 1)
                '"' -> readString()
                't' -> readLiteral("true")
                'f' -> readLiteral("false")
                'n' -> readLiteral("null")
                '-', in '0'..'9' -> readNumber()
                else -> malformed()
            }
        }

        private fun readObject(depth: Int) {
            consume('{')
            skipWhitespace()
            if (consumeIf('}')) return
            val keys = mutableSetOf<String>()
            while (true) {
                if (peek() != '"') malformed()
                val key = readString()
                if (!keys.add(key) && duplicateKey == null) duplicateKey = key
                skipWhitespace()
                consume(':')
                skipWhitespace()
                readValue(depth)
                skipWhitespace()
                if (consumeIf('}')) return
                consume(',')
                skipWhitespace()
            }
        }

        private fun readArray(depth: Int) {
            consume('[')
            skipWhitespace()
            if (consumeIf(']')) return
            while (true) {
                readValue(depth)
                skipWhitespace()
                if (consumeIf(']')) return
                consume(',')
                skipWhitespace()
            }
        }

        private fun readString(): String {
            consume('"')
            val result = StringBuilder()
            while (true) {
                val value = next()
                when {
                    value == '"' -> return result.toString()
                    value == '\\' -> result.append(readEscape())
                    value.code < 0x20 -> malformed()
                    else -> result.append(value)
                }
            }
        }

        private fun readEscape(): Char = when (val value = next()) {
            '"', '\\', '/' -> value
            'b' -> '\b'
            'f' -> '\u000c'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> malformed()
        }

        private fun readUnicodeEscape(): Char {
            var value = 0
            repeat(4) {
                value = value shl 4 or when (val digit = next()) {
                    in '0'..'9' -> digit.code - '0'.code
                    in 'a'..'f' -> digit.code - 'a'.code + 10
                    in 'A'..'F' -> digit.code - 'A'.code + 10
                    else -> malformed()
                }
            }
            return value.toChar()
        }

        private fun readNumber() {
            consumeIf('-')
            if (consumeIf('0')) {
                if (peekOrNull() in '0'..'9') malformed()
            } else {
                requireDigit('1'..'9')
                while (peekOrNull() in '0'..'9') index += 1
            }
            if (consumeIf('.')) {
                requireDigit('0'..'9')
                while (peekOrNull() in '0'..'9') index += 1
            }
            if (peekOrNull() == 'e' || peekOrNull() == 'E') {
                index += 1
                if (peekOrNull() == '+' || peekOrNull() == '-') index += 1
                requireDigit('0'..'9')
                while (peekOrNull() in '0'..'9') index += 1
            }
        }

        private fun requireDigit(range: CharRange) {
            if (peekOrNull() !in range) malformed()
            index += 1
        }

        private fun readLiteral(value: String) {
            if (index > text.length - value.length || text.substring(index, index + value.length) != value) malformed()
            index += value.length
        }

        private fun skipWhitespace() {
            while (peekOrNull() in JSON_WHITESPACE) index += 1
        }

        private fun consume(expected: Char) {
            if (next() != expected) malformed()
        }

        private fun consumeIf(expected: Char): Boolean {
            if (peekOrNull() != expected) return false
            index += 1
            return true
        }

        private fun next(): Char = if (index < text.length) text[index++] else malformed()

        private fun peek(): Char = peekOrNull() ?: malformed()

        private fun peekOrNull(): Char? = text.getOrNull(index)

        private fun malformed(): Nothing = throw MalformedJson
    }

    private data object MalformedJson : RuntimeException()
    private val JSON_WHITESPACE = setOf(' ', '\t', '\r', '\n')
    private const val MAX_JSON_DEPTH = 128
}
