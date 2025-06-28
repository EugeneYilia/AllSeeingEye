package com.capitalEugene.model.kline

import com.capitalEugene.common.utils.BigDecimalAsStringSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class KlineBar(
    val timestamp: Long,       // 毫秒时间戳

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val open: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val high: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val low: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val close: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val volume: BigDecimal
)
