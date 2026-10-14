package com.pablo.yumemi.data.remote

import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** Defensive JSON helpers: Hermes field names are not fully documented. */
internal val LenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

internal fun parseJson(text: String): JsonElement? =
    try {
        if (text.isBlank()) null else LenientJson.parseToJsonElement(text)
    } catch (e: Exception) {
        null
    }

internal fun parseJsonObject(text: String): JsonObject? = parseJson(text) as? JsonObject

/** First non-empty primitive string among keys. */
internal fun JsonObject.str(vararg keys: String): String? {
    for (k in keys) {
        val e = this[k]
        if (e is JsonPrimitive && e !is JsonNull) {
            val c = e.contentOrNull
            if (!c.isNullOrEmpty()) return c
        }
    }
    return null
}

internal fun JsonObject.num(vararg keys: String): Double? {
    for (k in keys) {
        val e = this[k]
        if (e is JsonPrimitive && e !is JsonNull) {
            val d = e.doubleOrNull
            if (d != null) return d
        }
    }
    return null
}

internal fun JsonObject.bool(vararg keys: String): Boolean? {
    for (k in keys) {
        val e = this[k]
        if (e is JsonPrimitive && e !is JsonNull) {
            val b = e.booleanOrNull
            if (b != null) return b
        }
    }
    return null
}

internal fun JsonObject.arr(vararg keys: String): JsonArray? {
    for (k in keys) {
        val e = this[k]
        if (e is JsonArray) return e
    }
    return null
}

/** Message content may be a string, an array of {type,text} parts, or an object with "text". */
internal fun contentText(e: JsonElement?): String? {
    return when (e) {
        null, is JsonNull -> null
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> e.str("text", "content")
        is JsonArray -> {
            val sb = StringBuilder()
            for (item in e) {
                val t = when (item) {
                    is JsonPrimitive -> item.contentOrNull
                    is JsonObject -> item.str("text", "content")
                    else -> null
                }
                if (!t.isNullOrEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(t)
                }
            }
            sb.toString().ifEmpty { null }
        }
    }
}

/** Epoch seconds, epoch millis, numeric strings or ISO-8601 strings -> epoch millis (0 if unknown). */
internal fun parseTime(e: JsonElement?): Long {
    if (e !is JsonPrimitive || e is JsonNull) return 0L
    val raw = e.contentOrNull?.trim().orEmpty()
    if (raw.isEmpty()) return 0L
    val d = raw.toDoubleOrNull()
    if (d != null) {
        return if (d < 100_000_000_000.0) (d * 1000).toLong() else d.toLong()
    }
    return try {
        Instant.parse(raw).toEpochMilli()
    } catch (_: Exception) {
        try {
            OffsetDateTime.parse(raw).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

internal fun JsonObject.time(vararg keys: String): Long {
    for (k in keys) {
        val t = parseTime(this[k])
        if (t > 0) return t
    }
    return 0L
}

internal fun String.truncated(max: Int): String = if (length <= max) this else take(max)
