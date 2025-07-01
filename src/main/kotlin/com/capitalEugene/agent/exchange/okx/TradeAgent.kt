package com.capitalEugene.agent.exchange.okx

import com.capitalEugene.common.constants.TradeConstants
import com.capitalEugene.common.utils.TimeUtils
import com.capitalEugene.common.utils.TradeUtils
import com.capitalEugene.secrets.ApiSecret
import com.capitalEugene.serverConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.serverConfig
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.math.BigDecimal

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
        lever: BigDecimal,
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
        price: BigDecimal,
        size: BigDecimal,
        apiSecret: ApiSecret): JsonObject?
    {
        if(serverConfig!!.isLocalDebug) {
            logger.debug("Local debug, skip place order")
            return null
        }

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

        var attempt = 0
        var delayMs : Long = TradeConstants.INIT_RETRY_DELAY_SECONDS * 1000L
        // 会一直下单，就算失败了，也会一直重试
        while (true) {
            try {
                val ts = TimeUtils.getTimestamp()
                val msg = "${ts}POST$path${Json.encodeToString(body)}"
                val sign = TradeUtils.hmacSha256Base64(apiSecret.okxApiSecret, msg)

                logger.info("place order request: ${body}")
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
                    delayMs = (delayMs * 2).coerceAtMost(TradeConstants.MAX_RETRY_DELAY_SECONDS * 1000L)
                    attempt++
                    continue
                }

                logger.info("place order response: ${response.bodyAsText()}")
                return response.body()
            } catch (ex: Exception) {
                logger.error("[Attempt $attempt] 请求异常: ${ex.message}")
                delay(delayMs)
                delayMs = (delayMs * 2).coerceAtMost(TradeConstants.MAX_RETRY_DELAY_SECONDS * 1000L)
                attempt++
            }
        }
    }

    suspend fun openLong(symbol: String, price: BigDecimal, size: BigDecimal, apiSecret: ApiSecret): JsonObject? {
        return placeOrder(symbol, "buy", "long", price, size, apiSecret)
    }

    suspend fun openShort(symbol: String, price: BigDecimal, size: BigDecimal, apiSecret: ApiSecret): JsonObject? {
        return placeOrder(symbol, "sell", "short", price, size, apiSecret)
    }

    suspend fun closePosition(symbol: String, side: String, price: BigDecimal, size: BigDecimal, apiSecret: ApiSecret): JsonObject? {
        val posSide = if (side == "buy") "short" else "long"
        return placeOrder(symbol, side, posSide, price, size, apiSecret)
    }
}