package com.capitalEugene.model.kline

import java.math.BigDecimal

data class KlineBar(
    val timestamp: Long,       // 毫秒时间戳
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal
)
