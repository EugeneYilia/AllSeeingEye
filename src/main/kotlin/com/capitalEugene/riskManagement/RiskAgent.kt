package com.capitalEugene.riskManagement

import com.capitalEugene.agent.email.EmailAgent
import com.capitalEugene.agent.mongo.MongoAgent.savePositionToMongo
import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.common.constants.CommunicationConstants
import com.capitalEugene.common.utils.TimeUtils
import com.capitalEugene.common.utils.fillTemplate
import com.capitalEugene.model.position.PositionRunningState
import com.capitalEugene.model.position.PositionState
import com.capitalEugene.model.tenant.Account
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory


@Serializable
class RiskAgent {

    companion object {
        val logger = LoggerFactory.getLogger("RiskAgent")
    }

    private val maxStopLossThreshold : Int = 1

    suspend fun monitorState(
        positionState: PositionState,
        accounts: List<Account>, )
    {
        if(positionState.positionRunningState != PositionRunningState.Running){
            return
        }

        if(positionState.stopLossCount > maxStopLossThreshold){
            logger.info("stop strategy ${positionState.strategyFullName} by risk agent")
            positionState.positionRunningState = PositionRunningState.StoppedByRiskAgent

            val currentTimestamp: Long = System.currentTimeMillis()
            positionState.stopTime = currentTimestamp

            savePositionToMongo(positionState)

            accounts.forEach { account ->
                if (account.emailList != null){
                    EmailAgent.sendEmail(
                        fillTemplate(CommunicationConstants.riskAgentEmailSubjectTemplate, mapOf("strategyName" to positionState.strategyFullName)),
                        fillTemplate(CommunicationConstants.riskAgentEmailContentTemplate, mapOf(
                            "userName" to (account.accountName ?: ""),
                            "strategyName" to positionState.strategyFullName,
                            "stopTime" to TimeUtils.formatToLocalTime(currentTimestamp, "yyyy-MM-dd HH:mm:ss"),
                            "dashboardUrl" to ApplicationConstants.dashboardUrl,
                            "currentDate" to TimeUtils.formatToLocalTime(currentTimestamp, "yyyy-MM-dd")
                        )),
                        account.emailList!!,
                    )
                }

                if(account.phoneList != null){
                    makePhoneCall()
                }
            }
        }
    }

    fun makePhoneCall(){

    }
}