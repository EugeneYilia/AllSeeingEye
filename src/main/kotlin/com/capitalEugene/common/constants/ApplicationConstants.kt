package com.capitalEugene.common.constants

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

object ApplicationConstants {
    val configJson = Json {
        ignoreUnknownKeys = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    val httpJson = Json { ignoreUnknownKeys = true }

    const val applicationGeneralName = "CapitalEugene"
    const val applicationChineseName = "尤金资本"

    const val dashboardUrl = "172.184.97.100:9019"
}