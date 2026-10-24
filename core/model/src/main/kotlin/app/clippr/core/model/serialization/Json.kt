package app.clippr.core.model.serialization

/**
 * A minimal JSON tree, parser and writer used for the project document format.
 *
 * Kept dependency-free on purpose: the project format is part of the core model, must be
 * testable on a plain JVM, and must round-trip 64-bit microsecond values exactly. Numbers are
 * therefore stored as their original text and converted on access instead of passing through
 * [Double].
 */
sealed interface JsonValue {
    data object Null : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Num(val raw: String) : JsonValue {
        fun toLong(): Long = raw.toLongOrNull() ?: raw.toDouble().toLong()
        fun toInt(): Int = toLong().toInt()
        fun toDouble(): Double = raw.toDouble()
        fun toFloat(): Float = raw.toFloat()
    }
    data class Str(val value: String) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue {
        operator fun get(key: String): JsonValue? = fields[key]
    }
}

class JsonException(message: String) : Exception(message)

object Json {

    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        val value = parser.readValue()
        parser.skipWhitespace()
        if (!parser.atEnd()) throw JsonException("Unexpected trailing content at ${parser.pos}")
        return value
    }

    fun write(value: JsonValue): String = StringBuilder().also { writeTo(value, it) }.toString()

    private fun writeTo(value: JsonValue, out: StringBuilder) {
        when (value) {
            JsonValue.Null -> out.append("null")
            is JsonValue.Bool -> out.append(value.value)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Str -> writeString(value.value, out)
            is JsonValue.Arr -> {
                out.append('[')
                value.items.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    writeTo(item, out)
                }
                out.append(']')
            }
            is JsonValue.Obj -> {
                out.append('{')
                var first = true
                for ((k, v) in value.fields) {
                    if (!first) out.append(',')
                    first = false
                    writeString(k, out)
                    out.append(':')
                    writeTo(v, out)
                }
                out.append('}')
            }
        }
    }

    private fun writeString(s: String, out: StringBuilder) {
        out.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (ch < ' ') out.append("\\u%04x".format(ch.code)) else out.append(ch)
            }
        }
        out.append('"')
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): JsonValue {
            skipWhitespace()
            if (atEnd()) throw JsonException("Unexpected end of input")
            return when (val c = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> if (c == '-' || c.isDigit()) readNumber() else throw JsonException("Unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!s.startsWith(word, pos)) throw JsonException("Expected $word at $pos")
            pos += word.length
            return value
        }

        private fun readObject(): JsonValue.Obj {
            pos++ // {
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw JsonException("Expected key at $pos")
                val key = readString()
                skipWhitespace()
                expect(':')
                fields[key] = readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return JsonValue.Obj(fields)
                    else -> throw JsonException("Expected ',' or '}' at ${pos - 1}")
                }
            }
        }

        private fun readArray(): JsonValue.Arr {
            pos++ // [
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items += readValue()
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return JsonValue.Arr(items)
                    else -> throw JsonException("Expected ',' or ']' at ${pos - 1}")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw JsonException("Unterminated string")
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (atEnd()) throw JsonException("Bad escape")
                        when (val e = s[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            if (raw.toDoubleOrNull() == null) throw JsonException("Bad number '$raw' at $start")
            return JsonValue.Num(raw)
        }

        private fun peek(): Char = if (atEnd()) '\u0000' else s[pos]

        private fun next(): Char = if (atEnd()) throw JsonException("Unexpected end of input") else s[pos++]

        private fun expect(c: Char) {
            if (next() != c) throw JsonException("Expected '$c' at ${pos - 1}")
        }
    }
}

// Builders -------------------------------------------------------------------------------------

fun jsonObject(vararg pairs: Pair<String, JsonValue?>): JsonValue.Obj =
    JsonValue.Obj(LinkedHashMap<String, JsonValue>().apply { pairs.forEach { (k, v) -> if (v != null) put(k, v) } })

fun json(value: String?): JsonValue = if (value == null) JsonValue.Null else JsonValue.Str(value)
fun json(value: Boolean): JsonValue = JsonValue.Bool(value)
fun json(value: Long): JsonValue = JsonValue.Num(value.toString())
fun json(value: Int): JsonValue = JsonValue.Num(value.toString())
fun json(value: Float): JsonValue = JsonValue.Num(value.toString())
fun jsonArray(items: List<JsonValue>): JsonValue = JsonValue.Arr(items)

// Readers --------------------------------------------------------------------------------------

fun JsonValue.Obj.obj(key: String): JsonValue.Obj? = this[key] as? JsonValue.Obj
fun JsonValue.Obj.arr(key: String): List<JsonValue> = (this[key] as? JsonValue.Arr)?.items ?: emptyList()
fun JsonValue.Obj.str(key: String): String? = (this[key] as? JsonValue.Str)?.value
fun JsonValue.Obj.long(key: String, default: Long = 0L): Long = (this[key] as? JsonValue.Num)?.toLong() ?: default
fun JsonValue.Obj.int(key: String, default: Int = 0): Int = (this[key] as? JsonValue.Num)?.toInt() ?: default
fun JsonValue.Obj.float(key: String, default: Float = 0f): Float = (this[key] as? JsonValue.Num)?.toFloat() ?: default
fun JsonValue.Obj.floatOrNull(key: String): Float? = (this[key] as? JsonValue.Num)?.toFloat()
fun JsonValue.Obj.bool(key: String, default: Boolean = false): Boolean = (this[key] as? JsonValue.Bool)?.value ?: default
