package com.capitalEugene.order

import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.common.constants.OrderConstants
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import kotlin.math.log

object BtcKLine {
    private val logger = LoggerFactory.getLogger("btc_kline")

    val CANDLE_CHANNELS = listOf(
        // 1分钟级别K线数据
        mapOf("channel" to "candle1m", "instId" to OrderConstants.BTC_SPOT),
        mapOf("channel" to "candle1m", "instId" to OrderConstants.BTC_SWAP)
    )

    // 连接断开之后，也会一直重连
    suspend fun startWs(client: HttpClient) {
        val url = "wss://ws.okx.com:8443/ws/v5/business"
        var retryInterval = 5000L
        while (true) {
            try {
                logger.info("🚀 尝试建立 WebSocket 连接...")
                client.webSocket(url) {
                    retryInterval = 5000L
                    subscribeChannels(this)
                    incoming.consumeEach { frame ->
                        if (frame is Frame.Text) {
                            handleMessage(frame.readText())
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("⚠️ WebSocket 运行异常: ${e.message}")
            }
            logger.warn("🌐 连接断开，${retryInterval / 1000} 秒后重试...")
            delay(retryInterval)
            retryInterval = (retryInterval * 2).coerceAtMost(60 * 1000L)
        }
    }

    suspend fun subscribeChannels(session: DefaultClientWebSocketSession) {
        val subMsg = buildJsonObject {
            put("op", "subscribe")
            putJsonArray("args") {
                CANDLE_CHANNELS.forEach { arg ->
                    add(buildJsonObject {
                        arg.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                    })
                }
            }
        }
        session.send(subMsg.toString())
        logger.info("✅ 已发送订阅请求: $subMsg")
    }

    fun handleMessage(message: String) {
        val data = ApplicationConstants.httpJson.parseToJsonElement(message)
        if (data.jsonObject["event"]?.jsonPrimitive?.content == "subscribe") {
            logger.info("✅ 成功订阅: ${data.jsonObject}")
            return
        }
        val arg = data.jsonObject["arg"]?.jsonObject ?: return
        val channel = arg["channel"]?.jsonPrimitive?.content ?: return
        val instId = arg["instId"]?.jsonPrimitive?.content ?: return
        val dtype = if (instId == OrderConstants.BTC_SPOT) "spot" else "swap"

        val dataArray = data.jsonObject["data"]?.jsonArray ?: return
        val first = dataArray.firstOrNull()?.jsonObject ?: return

        if (channel.startsWith("candle1m")) {
            val candle = first["candle"]?.jsonArray ?: return
            val time = candle.getOrNull(0)?.jsonPrimitive?.content
            val open = candle.getOrNull(1)?.jsonPrimitive?.content
            val high = candle.getOrNull(2)?.jsonPrimitive?.content
            val low = candle.getOrNull(3)?.jsonPrimitive?.content
            val close = candle.getOrNull(4)?.jsonPrimitive?.content
            val volume = candle.getOrNull(5)?.jsonPrimitive?.content

            // 区分现货和合约
            val dtype = when (instId) {
                OrderConstants.BTC_SPOT -> "spot"
                OrderConstants.BTC_SWAP -> "swap"
                else -> "unknown"
            }

            logger.info("🕐 [$dtype | ${channel.uppercase()}] 时间: $time 开: $open 高: $high 低: $low 收: $close 量: $volume")
        }
    }
}