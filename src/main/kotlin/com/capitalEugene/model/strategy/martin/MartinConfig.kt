package com.capitalEugene.model.strategy.martin

import com.capitalEugene.model.tenant.Account
import com.capitalEugene.riskManagement.RiskAgent
import java.math.BigDecimal

data class MartinConfig(
    // 操作币种   BTC-USDT-SWAP/ETH-USDT-SWAP
    val symbol: String,
    // 止盈比例
    val tpRatio: BigDecimal = BigDecimal.valueOf(0.00148),
    // 止损比例
    val slRatio: BigDecimal = BigDecimal.valueOf(0.01),
    // 开仓大小(张)
    val positionSize: BigDecimal = BigDecimal.valueOf(0.01),
    // 加仓触发条件
    val addPositionRatio: BigDecimal = BigDecimal.valueOf(0.0138),
    // 操作账户列表
    val accounts: List<Account>,
    // 最大加仓次数
    val maxAddPositionCount : Int = 7,
    // 策略名称
    val configName: String = "Default",
    // 买卖方订单量差距倍数，会触发开仓的倍数阈值
    val multiplesOfTheGap : BigDecimal = BigDecimal.valueOf(3.333),
    // 初始资本数
    val initCapital : BigDecimal = BigDecimal.valueOf(100.00),
    // 杠杆倍数
    val lever: BigDecimal = BigDecimal.valueOf(100.00),
    // 风控管理    不设置风控的话，不管出什么样的止损情况都会一直跑
    val riskAgent: RiskAgent? = null,
)
