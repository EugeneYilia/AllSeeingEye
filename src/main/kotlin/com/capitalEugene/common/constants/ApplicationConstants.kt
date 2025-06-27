package com.capitalEugene.common.constants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

object ApplicationConstants {
    val configJson = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    val httpJson = Json { ignoreUnknownKeys = true }
}