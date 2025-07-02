package com.capitalEugene.common.constants

import java.math.BigDecimal

object OrderConstants {
    val CONTRACT_VALUE = BigDecimal.valueOf(0.01)
    const val BTC_SPOT = "BTC-USDT"
    const val BTC_SWAP = "BTC-USDT-SWAP"

    const val ETH_SWAP = "ETH-USDT-SWAP"
    const val DOGE_SWAP = "DOGE-USDT-SWAP"
    val DEFAULT_SPOT_VALUE = BigDecimal.valueOf(1.0)

    val LUCKY_MAGIC_NUMBER = BigDecimal.valueOf(3.333)

    // 不同币种   每一张对应的币种数量是不同的
    val contractSizeMap = mapOf(
        BTC_SWAP to BigDecimal("0.01"),     // 1 张 = 0.01 BTC
        ETH_SWAP to BigDecimal("0.1"),      // 1 张 = 0.1 ETH
        DOGE_SWAP to BigDecimal("1000")     // 1 张 = 1000 DOGE
    )

    val symbol2RangMap = mapOf(
        BTC_SWAP to BigDecimal("3333"),     // 1 张 = 0.01 BTC
        ETH_SWAP to BigDecimal("233"),      // 1 张 = 0.1 ETH
        DOGE_SWAP to BigDecimal("0.0233")     // 1 张 = 1000 DOGE
    )
}