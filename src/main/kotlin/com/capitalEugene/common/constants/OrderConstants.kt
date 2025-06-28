package com.capitalEugene.common.constants

import java.math.BigDecimal

object OrderConstants {
    val CONTRACT_VALUE = BigDecimal.valueOf(0.01)
    const val BTC_SPOT = "BTC-USDT"
    const val BTC_SWAP = "BTC-USDT-SWAP"
    val DEFAULT_SPOT_VALUE = BigDecimal.valueOf(1.0)

    val LUCKY_MAGIC_NUMBER = BigDecimal.valueOf(3.333)
}