package com.capitalEugene

import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.model.config.ServerConfig
import com.capitalEugene.model.strategy.martin.MartinConfig
import com.capitalEugene.order.BtcKLine
import com.capitalEugene.order.BtcOrder
import com.capitalEugene.secrets.dogFoodAccounts
import com.capitalEugene.secrets.selfHostAccounts
import com.capitalEugene.trade.strategy.dogfood.MartinStrategy
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

// 应用模块：既启动 API，也启动 WebSocket 与定时任务
fun Application.module() {
    val serverConfig = loadServerConfig()

    // 配置Api服务
    configureRouting()

    // 构建一个具备WebSocket能力的Http CIO客户端
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    // Dispatchers.IO是为I/O密集型任务(网络，文件，数据库)优化的，会自动扩容线程池数量以避免阻塞，不适合用于CPU密集任务
    // CPU密集场景用IO，如果在CPU密集型场景用IO Dispatcher，会导致线程切换过多(切换到这个后发现仍在使用，一直用着线程)，调度开销变大，反而性能更差
    val ioSchedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("priceAgent"))
    // cpu密集型任务的协程应该用Dispatchers.Default
    // Dispatchers.Default使用的是共享的、基于cpu核心数的线程池(默认是cpu核心数 或 cpu核心数 * 2)
    // 其专门为高计算量，低I/O操作的任务设计的，比如数学计算，加解密，排序，搜索，图算法，大量数据处理
    val cpuSchedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("strategyAgent"))

    // 直接用 application.monitor.subscribe
    monitor.subscribe(ApplicationStarted) {
        ioSchedulerScope.launch {
            try {
                logger.info("开始启动btc订单和实时价格订阅")
                // 订单簿和实时价格ws获取
                BtcOrder.startWs(client)
            }
            catch (e: Exception) {
                logger.error("❌ 运行 BtcOrder 出错", e)
            }
        }

        ioSchedulerScope.launch {
            try {
                logger.info("开始启动btc k线订阅")
                BtcKLine.startWs(client)
            } catch (e: Exception) {
                logger.error("❌ 运行 BtcKLine 出错", e)
            }
        }

        if(!serverConfig.isLocalDebug) {
            cpuSchedulerScope.launch {
                try {
                    logger.info("开始启动策略服务")
                    // 交易策略配置启动
                    val dogfoodMartinConfig = MartinConfig(
                        symbol = OrderConstants.BTC_SWAP,
                        positionSize = BigDecimal.valueOf(0.05),
                        accounts = dogFoodAccounts,
                        multiplesOfTheGap = OrderConstants.LUCKY_MAGIC_NUMBER,
                    )

                    val selfHostMartinConfig = MartinConfig(
                        symbol = OrderConstants.BTC_SWAP,
                        positionSize = BigDecimal.valueOf(0.04),
                        tpRatio = BigDecimal.valueOf(0.0048),
                        maxAddPositionCount = 4,
                        accounts = selfHostAccounts,
                        configName = "handsome_dog_0.5",
                        multiplesOfTheGap = BigDecimal.valueOf(1.888),
                    )
                    MartinStrategy(listOf(dogfoodMartinConfig, selfHostMartinConfig)).start()
                } catch (e: Exception) {
                    logger.error("❌ 运行 MartinStrategy 出错", e)
                }
            }
        }
    }

    // 应用停止时关闭掉WebSocket
    monitor.subscribe(ApplicationStopping) {
        client.close()
        logger.info("🛑 WebSocket 客户端已关闭")
    }
}

fun Application.loadServerConfig(): ServerConfig {
    val inputStream = environment.classLoader.getResourceAsStream("config.json")
        ?: throw IllegalStateException("❌ config.json not found in resources")
    val text = InputStreamReader(inputStream).readText()

    return ApplicationConstants.configJson.decodeFromString(ServerConfig.serializer(), text)
}