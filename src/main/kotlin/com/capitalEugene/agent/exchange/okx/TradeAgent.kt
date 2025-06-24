package com.capitalEugene.agent.exchange.okx

import com.capitalEugene.common.constants.TradeConstants
import com.capitalEugene.common.utils.TimeUtils
import com.capitalEugene.common.utils.TradeUtils
import com.capitalEugene.secrets.ApiSecret
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

object TradeAgent {
    private const val BASE_URL = "https://www.okx.com"

    private val logger = LoggerFactory.getLogger("trade_agent")

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    suspend fun setCrossLeverage(
        instId: String,
        lever: Int,
        apiSecret: ApiSecret): JsonObject
    {
        val path = "/api/v5/account/set-leverage"
        val url = "$BASE_URL$path"
        val body = buildJsonObject {
            put("instId", instId)
            put("lever", lever.toString())
            put("mgnMode", "cross")
        }

        val ts = TimeUtils.getTimestamp()
        val msg = "${ts}POST$path${Json.encodeToString(body)}"
        val sign = TradeUtils.hmacSha256Base64(apiSecret.okxApiSecret, msg)

        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            headers {
                append("OK-ACCESS-KEY", apiSecret.okxApiKey)
                append("OK-ACCESS-SIGN", sign)
                append("OK-ACCESS-TIMESTAMP", ts)
                append("OK-ACCESS-PASSPHRASE", apiSecret.okxApiPassPhase)
            }
            setBody(body)
        }
        return response.body()
    }

    suspend fun placeOrder(
        instId: String,
        side: String,
        posSide: String,
        price: Double,
        size: Double,
        apiSecret: ApiSecret): JsonObject?
    {
        val path = "/api/v5/trade/order"
        val url = "$BASE_URL$path"
        val body = buildJsonObject {
            put("instId", instId)   // BTC-USDT-SWAP
            put("tdMode", "cross")  // cross/isolated
            put("posSide", posSide) // long/short
            put("side", side)       // buy/sell
            put("ordType", "market")// market
            put("px", price.toString())   // price   市价单，价格无所谓，走的对手盘
            put("sz", size.toString())    // size
        }

        val ts = TimeUtils.getTimestamp()
        val msg = "${ts}POST$path${Json.encodeToString(body)}"
        val sign = TradeUtils.hmacSha256Base64(apiSecret.okxApiSecret, msg)

        var attempt = 0
        var delayMs : Long = TradeConstants.RETRY_DELAY_SECONDS * 1000L
        while (true) {
            try {
                val response = client.post(url) {
                    contentType(ContentType.Application.Json)
                    headers {
                        append("OK-ACCESS-KEY", apiSecret.okxApiKey)
                        append("OK-ACCESS-SIGN", sign)
                        append("OK-ACCESS-TIMESTAMP", ts)
                        append("OK-ACCESS-PASSPHRASE", apiSecret.okxApiPassPhase)
                    }
                    setBody(body)
                }

                if (response.status != HttpStatusCode.OK) {
                    logger.error("[Attempt $attempt] 非 200 响应: ${response.status}, 内容: ${response.bodyAsText()}")
                    delay(delayMs)
                    delayMs = (delayMs * 2).coerceAtMost(TradeConstants.MAX_DELAY_SECONDS * 1000L)
                    attempt++
                    continue
                }

                return response.body()
            } catch (ex: Exception) {
                logger.error("[Attempt $attempt] 请求异常: ${ex.message}")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(TradeConstants.MAX_DELAY_SECONDS * 1000L)
                attempt++
            }
        }
    }

    suspend fun openLong(symbol: String, price: Double, size: Double, apiSecret: ApiSecret): JsonObject? {
        return placeOrder(symbol, "buy", "long", price, size, apiSecret)
    }

    suspend fun openShort(symbol: String, price: Double, size: Double, apiSecret: ApiSecret): JsonObject? {
        return placeOrder(symbol, "sell", "short", price, size, apiSecret)
    }

    suspend fun closePosition(symbol: String, side: String, price: Double, size: Double, apiSecret: ApiSecret): JsonObject? {
        val posSide = if (side == "buy") "short" else "long"
        return placeOrder(symbol, side, posSide, price, size, apiSecret)
    }
}