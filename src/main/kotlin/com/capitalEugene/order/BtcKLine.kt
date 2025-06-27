package com.capitalEugene.order

import com.capitalEugene.common.constants.ApplicationConstants
import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.model.kline.KlineBar
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

val klineCache : MutableMap<String, MutableList<KlineBar>> = mutableMapOf(
    "spot" to mutableListOf(),
    "swap" to mutableListOf()
)

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
                e.printStackTrace()
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

        val obj = data as? JsonObject ?: run {
            logger.warn("⚠️ 非 JsonObject 消息: $message")
            return
        }

        if (obj["event"]?.jsonPrimitive?.content == "subscribe") {
            logger.info("✅ 成功订阅: $obj")
            return
        }

        val arg = obj["arg"] as? JsonObject ?: run {
            logger.warn("⚠️ 非法 arg 类型: ${obj["arg"]?.javaClass?.simpleName}, 内容: ${obj["arg"]}")
            return
        }

        val channel = arg["channel"]?.jsonPrimitive?.content ?: return
        val instId = arg["instId"]?.jsonPrimitive?.content ?: return
        val dtype = when (instId) {
            OrderConstants.BTC_SPOT -> "spot"
            OrderConstants.BTC_SWAP -> "swap"
            else -> "unknown"
        }

        val dataArray = obj["data"] as? JsonArray ?: run {
            logger.warn("⚠️ data 不是 JsonArray，内容: ${obj["data"]}")
            return
        }

        val first = dataArray.firstOrNull() as? JsonArray ?: run {
            logger.warn("⚠️ data[0] 不是 JsonArray，内容: ${dataArray.firstOrNull()}")
            return
        }

        // 0  ts   开始时间
        // 1  o    开盘价格
        // 2  h    最高价格
        // 3  l    最低价格
        // 4  c    收盘价格
        // 5  vol  交易量，以张为单位
        // 6  volCcy   交易量，以交易币种为单位  BTC/USDT  就是BTC
        // 7  volCcyQuote    交易量，以计价货币为单位   BTC/USDT   就是USDT
        // 8  confirm  K线状态，0表示K线未完结，1表示K线已完结
        if (channel.startsWith("candle1m")) {
            val timestamp = first.getOrNull(0)?.jsonPrimitive?.longOrNull ?: return
            val open = first.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return
            val high = first.getOrNull(2)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return
            val low = first.getOrNull(3)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return
            val close = first.getOrNull(4)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return
            val volume = first.getOrNull(7)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: return
            val isEnd = first.getOrNull(8)?.jsonPrimitive?.contentOrNull ?: return

            if(isEnd == "1") {
                logger.info("🕐 [$dtype | ${channel.uppercase()}] 时间: $timestamp 开: $open 高: $high 低: $low 收: $close 量: $volume")

                val kLineBar = KlineBar(
                    timestamp = timestamp,
                    open = open,
                    high = high,
                    low = low,
                    close = close,
                    volume = volume
                )
                klineCache.getOrPut(dtype){mutableListOf()}.add(kLineBar)
            }
        }
    }
}