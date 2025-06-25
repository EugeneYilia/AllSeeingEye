package com.capitalEugene.model

import java.math.BigDecimal

data class TradingData(
    val transactionId: String,
    val strategyName: String,
    val holdingAmount: BigDecimal,
    val openTime: String,
    val closeTime: String = "",
    val returnPerformance: BigDecimal = BigDecimal.ZERO
)
