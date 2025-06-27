package com.capitalEugene

import com.capitalEugene.agent.redis.RedisAgent
import com.capitalEugene.order.klineCache
import com.capitalEugene.trade.strategy.dogfood.stateMap
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory

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

        get("v1/strategy/position/state/{strategyName}") {
            val strategyName = call.parameters["strategyName"] ?: return@get call.respondText("Missing strategy name", status = io.ktor.http.HttpStatusCode.BadRequest)

            val state = stateMap[strategyName]
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "未找到该策略的持仓信息"))
                return@get
            }

            call.respond(mapOf(
                "strategy" to strategyName,
                "longPosition" to state.longPosition.toPlainString(),
                "shortPosition" to state.shortPosition.toPlainString(),
                "longEntryPrice" to state.longEntryPrice?.toPlainString(),
                "shortEntryPrice" to state.shortEntryPrice?.toPlainString(),
                "longAddCount" to state.longAddCount,
                "shortAddCount" to state.shortAddCount,
                "capital" to state.capital.toPlainString(),
                "longTransactionId" to state.longTransactionId,
                "shortTransactionId" to state.shortTransactionId
            ))
        }

        get("v1/strategy/all") {
            call.respond(stateMap.keys.toList())
        }

        get("v1/strategy/aggregate/{strategyName}") {
            val strategyName = call.parameters["strategyName"] ?: return@get call.respondText("Missing strategy name", status = io.ktor.http.HttpStatusCode.BadRequest)

            val tradingData = RedisAgent.aggregateTradingData(strategyName)
            if(tradingData == null){
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "未找到该策略的交易信息"))
                return@get
            }

            call.respond(tradingData)
        }

        get("v1/kline/{type}") {
            val type = call.parameters["type"]
            val list = klineCache[type]

            if (list == null) {
                call.respond(HttpStatusCode.NotFound, "Kline data not found for type: $type")
            } else {
                call.respond(list)
            }
        }
    }
}