package com.capitalEugene.riskManagement

import com.capitalEugene.model.position.PositionRunningState
import com.capitalEugene.model.position.PositionState
import kotlinx.serialization.Serializable
import org.slf4j.Logger
import org.slf4j.LoggerFactory


@Serializable
class RiskAgent {

    // lateinit强制需要对字段进行赋值，就一定会走init {}进行赋值
    @Transient
    private lateinit var logger : Logger

    init {
        logger = LoggerFactory.getLogger("RiskAgent")
    }

    private val maxStopLossThreshold : Int = 1

    fun monitorState(positionState: PositionState){
        if(positionState.positionRunningState != PositionRunningState.Running){
            return
        }

        if(positionState.stopLossCount > maxStopLossThreshold){
            logger.info("stop strategy ${positionState.strategyFullName} by risk agent")
            positionState.positionRunningState = PositionRunningState.StoppedByRiskAgent
        }
    }
}