package com.capitalEugene

import com.capitalEugene.agent.redis.RedisAgent
import com.capitalEugene.order.klineCache
import com.capitalEugene.trade.strategy.dogfood.martinDogFoodStateMap
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureRouting() {
    val logger = LoggerFactory.getLogger("api")

    // ✅ 启用内容协商插件，支持 JSON
    install(ContentNegotiation) {
        json(Json {
            prettyPrint = false
            isLenient = false
            ignoreUnknownKeys = true
            // false时会省略所有没有显式修改的默认值字段
            encodeDefaults = true
        })
    }

    // ✅ 请求日志插件
    install(CallLogging) {
        format { call ->
            "${call.request.origin.remoteHost} - ${call.request.httpMethod.value} ${call.request.uri} ${call.response.status()?.value ?: "-"}"
        }
    }

    routing {
        staticResources("/static", "static")

        get("/") {
            call.respondRedirect("/static/capital.html")
        }

        get("v1/strategy/position/state/{strategyName}") {
            val strategyName = call.parameters["strategyName"]
                ?: return@get call.respondText("Missing strategy name", status = HttpStatusCode.BadRequest)

            val state = martinDogFoodStateMap[strategyName]
            if (state == null) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "未找到该策略的持仓信息"))
                return@get
            }

            call.respond(state)
        }

        get("v1/strategy/all") {
            // ✅ 显式设置响应 Content-Type 避免 406
            val strategyList = martinDogFoodStateMap.keys.toList()
            logger.info("返回策略列表: $strategyList")
            call.respondText(
                Json.encodeToString(strategyList),
                ContentType.Application.Json
            )
        }

        get("v1/strategy/aggregate/{strategyName}") {
            val strategyName = call.parameters["strategyName"]
                ?: return@get call.respondText("Missing strategy name", status = HttpStatusCode.BadRequest)

            val tradingData = RedisAgent.aggregateTradingData(strategyName)
            if (tradingData == null) {
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
