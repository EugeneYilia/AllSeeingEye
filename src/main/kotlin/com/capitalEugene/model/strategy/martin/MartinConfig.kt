package com.capitalEugene.model.strategy.martin

import com.capitalEugene.secrets.ApiSecret
import java.math.BigDecimal

data class MartinConfig(
    // 操作币种   BTC-USDT-SWAP/ETH-USDT-SWAP
    val symbol: String,
    // 止盈比例
    val tpRatio: BigDecimal = BigDecimal.valueOf(0.00186),
    // 止损比例
    val slRatio: BigDecimal = BigDecimal.valueOf(0.01),
    // 开仓大小(张)
    val positionSize: BigDecimal = BigDecimal.valueOf(0.01),
    // 加仓触发条件
    val addPositionRatio: BigDecimal = BigDecimal.valueOf(0.0089),
    // 操作账户列表
    val accounts: List<ApiSecret>,
    // 最大加仓次数
    val maxAddPositionCount : Int = 6,
    // 策略名称
    val configName: String = "Default",
    // 买卖方订单量差距倍数，会触发开仓的倍数阈值
    val multiplesOfTheGap : BigDecimal = BigDecimal.valueOf(3.333),
)
