package com.capitalEugene.common.utils

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object TimeUtils {
    fun getTimestamp(): String {
        val instant = Instant.now()
        val formatter = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
            .withZone(ZoneOffset.UTC)
        return formatter.format(instant)
    }
}
