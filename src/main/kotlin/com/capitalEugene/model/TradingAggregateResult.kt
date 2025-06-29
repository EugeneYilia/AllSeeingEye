package com.capitalEugene.model

import com.capitalEugene.common.utils.BigDecimalAsStringSerializer
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class TradingAggregateResult(
    val takeProfitCount: Int,
    val stopLossCount: Int,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val totalTakeProfitAmount: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val totalStopLossAmount: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val avgProfitPerTrade: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val avgLossPerTrade: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val capitalChangeRatio: BigDecimal,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val avgHoldingDuration: BigDecimal,
)
