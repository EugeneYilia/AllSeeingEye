package com.capitalEugene.model

import java.math.BigDecimal

data class TradingAggregateResult(
    val takeProfitCount: Int,
    val stopLossCount: Int,
    val totalTakeProfitAmount: BigDecimal,
    val totalStopLossAmount: BigDecimal,
    val avgProfitPerTrade: BigDecimal,
    val avgLossPerTrade: BigDecimal,
    val capitalChangeRatio: BigDecimal,
    val avgHoldingDuration: BigDecimal,
)
