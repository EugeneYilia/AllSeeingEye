package com.capitalEugene

import com.capitalEugene.agent.redis.RedisAgent
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

import com.capitalEugene.order.depthCache
import com.capitalEugene.order.priceCache

fun Application.configureRouting() {
    val logger = LoggerFactory.getLogger("api")

    // ktor请求日志插件，并定义日志输出格式
    // 在每次http请求进来并处理时，自动记录日志，比如请求地址，方法，响应状态码
    install(CallLogging) {

        // 自定义日志输出格式
        format { call ->
            // 对方ip   请求方法    call的哪个api   响应码
            "${call.request.origin.remoteHost} - ${call.request.httpMethod.value} ${call.request.uri} ${call.response.status()?.value ?: "-"}"
        }
    }

    routing {
        staticResources("/static", "static")

        get("/") {
            call.respondRedirect("/static/capital.html")
        }

        get("/api/status") {
            val response = mapOf(
                "price" to order_all_btc.priceCache["swap"],
                "position" to martin_strategy.position,
                "capital" to martin_strategy.capital,
                "entry" to martin_strategy.averagePrice,
                "current_pnl" to martin_strategy.currentPnl,
                "effective_capital" to martin_strategy.effectiveCapital,
                "depth" to mapOf(
                    "spot_bids" to order_all_btc.depthCache["spot"]?.get("bids")?.take(5),
                    "spot_asks" to order_all_btc.depthCache["spot"]?.get("asks")?.take(5),
                    "swap_bids" to order_all_btc.depthCache["swap"]?.get("bids")?.take(5),
                    "swap_asks" to order_all_btc.depthCache["swap"]?.get("asks")?.take(5),
                )
            )
            call.respond(response)
        }

        get("/api/aggregate/{strategyName}") {
            val strategyName = call.parameters["strategyName"] ?: return@get call.respondText("Missing strategy name", status = io.ktor.http.HttpStatusCode.BadRequest)
            call.respond(RedisAgent.aggregateTradingData(strategyName))
        }
    }
}