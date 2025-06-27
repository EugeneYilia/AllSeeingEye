package com.capitalEugene.model.config

import kotlinx.serialization.Serializable

@Serializable
data class ServerConfig(
    val isLocalDebug: Boolean
)
