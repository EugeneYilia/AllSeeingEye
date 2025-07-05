package com.capitalEugene.common.utils

import kotlinx.serialization.json.JsonObject

fun JsonObject.mergeWith(override: JsonObject): JsonObject {
    val merged = this.toMutableMap()

    for ((key, overrideValue) in override) {
        merged[key] = overrideValue
    }

    return JsonObject(merged)
}