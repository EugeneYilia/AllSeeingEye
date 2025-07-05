package com.capitalEugene.common.utils

fun fillTemplate(template: String, values: Map<String, String>): String {
    var result = template
    values.forEach { (key, value) ->
        result = result.replace("{{${key}}}", value)
    }
    return result
}