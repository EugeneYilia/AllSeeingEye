package com.capitalEugene.model

data class TradingAggregateResult(
    val takeProfitCount: Int,
    val stopLossCount: Int,
    val totalTakeProfitAmount: Double,
    val totalStopLossAmount: Double,
    val avgProfitPerTrade: Double,
    val avgLossPerTrade: Double,
    val capitalChangeRatio: Double,
    val avgHoldingDuration: Double,
)
