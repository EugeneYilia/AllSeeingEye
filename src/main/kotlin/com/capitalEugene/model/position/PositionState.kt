package com.capitalEugene.model.position

import com.capitalEugene.common.utils.BigDecimalAsStringSerializer
import com.capitalEugene.riskManagement.RiskAgent
import kotlinx.serialization.Serializable
import java.math.BigDecimal

@Serializable
data class PositionState(
    @Serializable(with = BigDecimalAsStringSerializer::class)
    var longPosition: BigDecimal = BigDecimal.ZERO,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    var shortPosition: BigDecimal = BigDecimal.ZERO,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    var longEntryPrice: BigDecimal? = null,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    var shortEntryPrice: BigDecimal? = null,

    var longAddCount: Int = 1,

    var shortAddCount: Int = 1,

    @Serializable(with = BigDecimalAsStringSerializer::class)
    var capital: BigDecimal = BigDecimal.valueOf(100.0),

    var longTransactionId : String? = null,

    var shortTransactionId: String? = null,

    // 例如 martin
    var strategyShortName: String,

    // 例如 martin_${config.symbol}_${config.configName}, fullName不强制限定格式
    var strategyFullName: String,

    // 止盈次数
    var takeProfitCount: Int = 0,
    // 止损次数
    var stopLossCount: Int = 0,
    // 风控管理
    val RiskAgent: RiskAgent? = null,
    // 仓位运行状态
    var positionRunningState: PositionRunningState = PositionRunningState.Running,
    // 停止时间
    var stopTime: Long? = null,
)
