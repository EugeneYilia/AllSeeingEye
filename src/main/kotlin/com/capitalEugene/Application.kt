package com.capitalEugene

import com.capitalEugene.agent.mongo.MongoAgent.getAllPositions
import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.common.utils.cpuSchedulerScope
import com.capitalEugene.common.utils.ioSchedulerScope
import com.capitalEugene.model.config.ServerConfig
import com.capitalEugene.model.strategy.martin.MartinConfig
import com.capitalEugene.order.BtcOrder
import com.capitalEugene.order.KLine
import com.capitalEugene.riskManagement.RiskAgent
import com.capitalEugene.secrets.dogFoodAccounts
import com.capitalEugene.secrets.selfHostAccounts
import com.capitalEugene.trade.strategy.dogfood.MartinStrategy
import com.capitalEugene.trade.strategy.dogfood.martinDogFoodStateMap
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.InputStreamReader
import java.math.BigDecimal

private val logger = LoggerFactory.getLogger("application")

fun main(args: Array<String>) {
    EngineMain.main(args)
}

var serverConfig : ServerConfig? = null

// 应用模块：既启动 API，也启动 WebSocket 与定时任务
suspend fun Application.module() {

    serverConfig = loadServerConfig()

    logger.info("loading strategy states from mongo")
    initStrategyStateMap()

    // 配置Api服务
    configureRouting()

    // 构建一个具备WebSocket能力的Http CIO客户端
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    // 直接用 application.monitor.subscribe
    monitor.subscribe(ApplicationStarted) {
        ioSchedulerScope.launch {
            try {
                logger.info("开始启动订单和实时价格订阅")
                // 订单簿和实时价格ws获取
                BtcOrder.startWs(client)
            }
            catch (e: Exception) {
                logger.error("❌ 运行 BtcOrder 出错", e)
            }
        }

        ioSchedulerScope.launch {
            try {
                logger.info("开始启动k线订阅")
                KLine.startWs(client)
            } catch (e: Exception) {
                logger.error("❌ 运行 BtcKLine 出错", e)
            }
        }

        cpuSchedulerScope.launch {
            try {
                logger.info("开始启动策略服务")
                // 交易策略配置启动
                val dogfoodMartinBtcConfig = MartinConfig(
                    symbol = OrderConstants.BTC_SWAP,
                    positionSize = BigDecimal.valueOf(0.06),
                    accounts = dogFoodAccounts,
                    multiplesOfTheGap = BigDecimal.valueOf(2.567),
                    initCapital = BigDecimal.valueOf(100.00),
                    addPositionRatio = BigDecimal.valueOf(0.0158),
                    lever = BigDecimal.valueOf(100.00),
                    riskAgent = RiskAgent()
                )

                val dogfoodMartinEthConfig = MartinConfig(
                    symbol = OrderConstants.ETH_SWAP,
                    positionSize = BigDecimal.valueOf(0.04),
                    accounts = dogFoodAccounts,
                    multiplesOfTheGap = BigDecimal.valueOf(1.888),
                    initCapital = BigDecimal.valueOf(100.00),
                    addPositionRatio = BigDecimal.valueOf(0.0168),
                    lever = BigDecimal.valueOf(100.00),
                    riskAgent = RiskAgent()
                )

                val dogfoodMartinDogeConfig = MartinConfig(
                    symbol = OrderConstants.DOGE_SWAP,
                    positionSize = BigDecimal.valueOf(0.05),
                    accounts = dogFoodAccounts,
                    // 1.998
                    multiplesOfTheGap = BigDecimal.valueOf(1.345),
                    initCapital = BigDecimal.valueOf(100.00),
                    addPositionRatio = BigDecimal.valueOf(0.0178),
                    lever = BigDecimal.valueOf(50.00),
                    riskAgent = RiskAgent()
                )

                val selfHostMartinBtcConfig = MartinConfig(
                    symbol = OrderConstants.BTC_SWAP,
                    positionSize = BigDecimal.valueOf(1.2),
                    maxAddPositionCount = 7,
                    accounts = selfHostAccounts,
                    configName = "handsome_dog_0.5",
                    multiplesOfTheGap = BigDecimal.valueOf(2.567),
                    initCapital = BigDecimal.valueOf(800.00),
                    lever = BigDecimal.valueOf(100.00),
                    riskAgent = RiskAgent()
                )

                val selfHostMartinEthConfig = MartinConfig(
                    symbol = OrderConstants.ETH_SWAP,
                    positionSize = BigDecimal.valueOf(0.06),
                    maxAddPositionCount = 4,
                    accounts = selfHostAccounts,
                    configName = "handsome_dog_0.5",
                    multiplesOfTheGap = BigDecimal.valueOf(1.888),
                    initCapital = BigDecimal.valueOf(800.00),
                    lever = BigDecimal.valueOf(100.00),
                    riskAgent = RiskAgent()
                )

                MartinStrategy(listOf(
                    dogfoodMartinBtcConfig,
                    dogfoodMartinEthConfig,
                    dogfoodMartinDogeConfig,
                    selfHostMartinBtcConfig,
                    selfHostMartinEthConfig
                )).start()
            } catch (e: Exception) {
                logger.error("❌ 运行 MartinStrategy 出错", e)
            }
        }
    }

    // 应用停止时关闭掉WebSocket
    monitor.subscribe(ApplicationStopping) {
        client.close()
        logger.info("🛑 WebSocket 客户端已关闭")
    }
}

suspend fun initStrategyStateMap() {
    val positions = getAllPositions()
    if (positions != null) {
        for (position in positions) {
            val strategyShortName = position.strategyShortName
            val strategyFullName = position.strategyFullName
            if (!strategyShortName.isNullOrBlank() && !strategyFullName.isNullOrBlank()) {
                if(strategyShortName == "martin"){
                    martinDogFoodStateMap[strategyFullName] = position
                }
            } else {
                logger.error("⚠️ 发现 strategy name 为空的 position: $position")
            }
        }
        logger.info("✅ 已加载 ${positions.size} 条持仓信息进内存")
    } else {
        logger.warn("⚠️ 未能从 Mongo 中加载任何持仓信息")
    }
}

fun Application.loadServerConfig(): ServerConfig {
    val inputStream = environment.classLoader.getResourceAsStream("config.json")
        ?: throw IllegalStateException("❌ config.json not found in resources")
    val text = InputStreamReader(inputStream).readText()

    return ApplicationConstants.configJson.decodeFromString(ServerConfig.serializer(), text)
}