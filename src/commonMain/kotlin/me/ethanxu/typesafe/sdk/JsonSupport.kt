package me.ethanxu.typesafe.sdk

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/*
 * Safe accessors for response parsing.
 *
 * Upstream is TypeScript, where accessing a missing field on a deserialized
 * object yields `undefined` and propagates silently. This port instead surfaces
 * structural problems as a TypeSafeException at the point of parsing, because
 * the typical caller is an automated pipeline where a silent `undefined` turns
 * into a much harder-to-diagnose "the classification came back empty".
 */

internal fun JsonObject.requireDouble(key: String): Double {
    val primitive = this[key] as? JsonPrimitive
        ?: throw TypeSafeException("Response field \"$key\" is missing or not a number.")
    return primitive.doubleOrNull
        ?: throw TypeSafeException("Response field \"$key\" is not a number: ${primitive.content}")
}

internal fun JsonObject.requireString(key: String): String {
    val primitive = this[key] as? JsonPrimitive
        ?: throw TypeSafeException("Response field \"$key\" is missing or not a string.")
    if (!primitive.isString) {
        throw TypeSafeException("Response field \"$key\" is not a string: ${primitive.content}")
    }
    return primitive.content
}

internal fun JsonObject.requireDoubleMap(key: String): Map<String, Double> {
    val obj = this[key] as? JsonObject
        ?: throw TypeSafeException("Response field \"$key\" is missing or not an object.")
    return obj.mapValues { (entryKey, value) ->
        (value as? JsonPrimitive)?.doubleOrNull
            ?: throw TypeSafeException("Response field \"$key.$entryKey\" is not a number.")
    }
}

internal fun JsonObject.requireStringMap(key: String): Map<String, String> {
    val obj = this[key] as? JsonObject
        ?: throw TypeSafeException("Response field \"$key\" is missing or not an object.")
    return obj.mapValues { (entryKey, value) ->
        (value as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw TypeSafeException("Response field \"$key.$entryKey\" is not a string.")
    }
}

/** Lenient access: a missing field or a type mismatch both yield null. Used for optional fields. */
internal fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.doubleOrNull?.toInt()

internal fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** Renders any JSON value as readable text, for embedding a state in a log line. */
internal fun JsonElement.describeForLog(maxLength: Int = 200): String {
    val text = toString()
    return if (text.length > maxLength) text.take(maxLength) + "…" else text
}
