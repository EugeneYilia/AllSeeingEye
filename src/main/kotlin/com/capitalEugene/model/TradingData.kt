package com.capitalEugene.model

data class TradingData(
    val transactionId: String,
    val strategyName: String,
    val holdingAmount: Double,
    val openTime: String,
    val closeTime: String = "",
    val returnPerformance: Double = 0.0
)
