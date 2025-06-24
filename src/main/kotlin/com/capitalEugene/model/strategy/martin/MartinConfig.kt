package com.capitalEugene.model.strategy.martin

import com.capitalEugene.secrets.ApiSecret

data class MartinConfig(
    // 操作币种   BTC-USDT-SWAP/ETH-USDT-SWAP
    val symbol: String,
    // 止盈比例
    val tpRatio: Double = 0.00176,
    // 止损比例
    val slRatio: Double = 0.01,
    // 开仓大小(张)
    val positionSize: Double = 0.01,
    // 加仓触发条件
    val addPositionRatio: Double = 0.0089,
    // 操作账户列表
    val accounts: List<ApiSecret>
)
