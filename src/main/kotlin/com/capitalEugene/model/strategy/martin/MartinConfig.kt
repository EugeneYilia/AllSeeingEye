package com.capitalEugene.model.strategy.martin

import com.capitalEugene.secrets.ApiSecret
import java.math.BigDecimal

data class MartinConfig(
    // 操作币种   BTC-USDT-SWAP/ETH-USDT-SWAP
    val symbol: String,
    // 止盈比例
    val tpRatio: BigDecimal = BigDecimal(0.00176),
    // 止损比例
    val slRatio: BigDecimal = BigDecimal(0.01),
    // 开仓大小(张)
    val positionSize: BigDecimal = BigDecimal(0.01),
    // 加仓触发条件
    val addPositionRatio: BigDecimal = BigDecimal(0.0089),
    // 操作账户列表
    val accounts: List<ApiSecret>,
    // 最大加仓次数
    val maxAddPositionCount : Int = 6,
    // 策略名称
    val configName: String = "DefaultMartin"
)
