package com.capitalEugene

import com.capitalEugene.order.printAggregatedDepth
import com.capitalEugene.order.startWs
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

// 应用模块：既启动 API，也启动 WebSocket 与定时任务
fun Application.module() {
    configureRouting()

    // 启动 WebSocket 和定时任务
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    environment.monitor.subscribe(ApplicationStarted) {
        // 启动 WebSocket 任务
        launch {
            startWs(client)
        }

        // 启动定时聚合打印任务
        launch {
            while (isActive) {
                delay(5000)
                printAggregatedDepth()
            }
        }
    }

    environment.monitor.subscribe(ApplicationStopping) {
        // 优雅关闭 HttpClient
        client.close()
        println("🛑 WebSocket 客户端已关闭")
    }
}
