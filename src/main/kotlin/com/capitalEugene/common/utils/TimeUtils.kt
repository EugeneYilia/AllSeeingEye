package com.capitalEugene.common.utils

import java.time.Instant
import java.time.ZoneId
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

    // 默认按照东八区的时间进行format
    fun formatToLocalTime(epochMillis: Long, pattern: String): String {
        return Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern(pattern))
    }
}
