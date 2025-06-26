package com.capitalEugene

import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.model.strategy.martin.MartinConfig
import com.capitalEugene.order.startWs
import com.capitalEugene.secrets.dogFoodAccounts
import com.capitalEugene.secrets.selfHostAccounts
import com.capitalEugene.trade.strategy.dogfood.MartinStrategy
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.netty.*
import kotlinx.coroutines.launch
import java.math.BigDecimal

fun main(args: Array<String>) {
    EngineMain.main(args)
}

// 应用模块：既启动 API，也启动 WebSocket 与定时任务
fun Application.module() {
    // 配置Api服务
    configureRouting()

    // 构建一个具备WebSocket能力的Http CIO客户端
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    // 直接用 application.monitor.subscribe
    monitor.subscribe(ApplicationStarted) {
        launch {
            // 订单簿和实时价格ws获取
            startWs(client)
        }

        launch {
            // 交易策略配置启动
            val dogfoodMartinConfig = MartinConfig(
                symbol = OrderConstants.BTC_SWAP,
                positionSize = BigDecimal(0.05),
                accounts = dogFoodAccounts
            )

            val selfHostMartinConfig = MartinConfig(
                symbol = OrderConstants.BTC_SWAP,
                positionSize = BigDecimal(0.04),
                tpRatio = BigDecimal(0.0048),
                maxAddPositionCount = 4,
                accounts = selfHostAccounts,
                configName = "ZhaoShuai-Martin"
            )
            MartinStrategy(listOf(dogfoodMartinConfig, selfHostMartinConfig)).start()
        }

//        launch {
//            // isActive绑定的是当前协程的上下文
//            // 每个协程都有自己的job    每个协程体内的isActive检查的就是自己的job的状态
//            // isActive等同于 this.coroutineContext[Job]?.isActive
//            // 每个协程体内的isActive判断的是自己的状态
//            // jobA.cancel() 不影响 jobB.isActive
//            //
//            // 协程体内部isActive检查当前协程的状态    协程体外部用job对象的isActive判断具体协程状态
//            // 多个协程每个协程的isActive独立，互不干扰
//            //
//            // 调用job.cancel() 主动取消时
//            // 协程正常完成时
//            // 协程出现异常被取消时
//            // 父协程或作用域被取消
//            while (isActive) {
//                delay(1000)
//                printAggregatedDepth()
//            }
//        }
    }

    // 应用停止时关闭掉WebSocket
    monitor.subscribe(ApplicationStopping) {
        client.close()
        println("🛑 WebSocket 客户端已关闭")
    }
}
