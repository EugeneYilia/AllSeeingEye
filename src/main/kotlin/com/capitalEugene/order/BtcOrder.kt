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
import java.math.BigDecimal
import java.util.SortedMap
import java.util.TreeMap

// 使用 TreeMap 保证价格自动排序
val depthCache: MutableMap<String, MutableMap<String, SortedMap<BigDecimal, BigDecimal>>> = mutableMapOf(
    OrderConstants.BTC_SPOT to mutableMapOf(
        "bids" to TreeMap(reverseOrder()),
        "asks" to TreeMap()
    ),
    OrderConstants.BTC_SWAP to mutableMapOf(
        "bids" to TreeMap(reverseOrder()),
        "asks" to TreeMap()
    )
)

// 当前现货和合约的实时价格
val priceCache = mutableMapOf<String, BigDecimal?>(
    OrderConstants.BTC_SPOT to null,
    OrderConstants.BTC_SWAP to null
)

object BtcOrder {
    private val logger = LoggerFactory.getLogger("btc_order")

    // 订阅的频道  订单簿和实时价格   分别有btc现货和合约
    val CHANNELS = listOf(
        // 订单簿
        mapOf("channel" to "books", "instId" to OrderConstants.BTC_SPOT),
        mapOf("channel" to "books", "instId" to OrderConstants.BTC_SWAP),
        // 实时价格
        mapOf("channel" to "tickers", "instId" to OrderConstants.BTC_SPOT),
        mapOf("channel" to "tickers", "instId" to OrderConstants.BTC_SWAP),
    )

    // 连接断开之后，也会一直重连
    suspend fun startWs(client: HttpClient) {
        val url = "wss://ws.okx.com:8443/ws/v5/public"
        var retryInterval = 5000L
        while (true) {
            try {
                logger.info("🚀 尝试建立 WebSocket 连接...")
                client.webSocket(url) {
                    // 连接成功时，重置retryInterval为默认值5000L
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
                CHANNELS.forEach { arg ->
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

        val dataArray = data.jsonObject["data"]?.jsonArray ?: return
        val first = dataArray.firstOrNull()?.jsonObject ?: return

        if (channel == "books") {
            val bidsMap = depthCache[instId]?.get("bids") ?: TreeMap(reverseOrder())
            val asksMap = depthCache[instId]?.get("asks") ?: TreeMap()

            synchronized(bidsMap) {
                first["bids"]?.jsonArray?.forEach { bidEntry ->
                    val price = bidEntry.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    val size = bidEntry.jsonArray.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    if (price != null && size != null) {
                        if (size == BigDecimal.ZERO) {
                            bidsMap.remove(price)
                        } else {
                            // upsert操作   过去有就update  没有就创建出来新的
                            bidsMap[price] = size
                        }
                    } else logger.error("⚠️ 解析失败数据: $bidEntry")
                }
            }
            synchronized(asksMap) {
                first["asks"]?.jsonArray?.forEach { askEntry ->
                    val price = askEntry.jsonArray.getOrNull(0)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    val size = askEntry.jsonArray.getOrNull(1)?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    if (price != null && size != null) {
                        if (size == BigDecimal.ZERO) {
                            asksMap.remove(price)
                        } else {
                            asksMap[price] = size
                        }
                    } else logger.error("⚠️ 解析失败数据: $askEntry")
                }
            }
        } else if (channel == "tickers") {
            // contentOrNull会返回一个字符串"10500.12",第二步将字符串安全转换为BigDecimal，第三步如果是非法格式"NaN","abc",""都将会返回null
            // 已经避免了Double.NaN的问题
            val realTimePrice = first["last"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
            priceCache[instId] = realTimePrice

            // 移除过时的 bids/asks 档位
            val bidsMap = depthCache[instId]?.get("bids")
            val asksMap = depthCache[instId]?.get("asks")
            if (realTimePrice != null) {
                // 移除所有价格高于当前价的 bids（买不到）
                if (bidsMap != null) {
                    synchronized(bidsMap) {
                        bidsMap.entries.removeIf { (price, _) -> price > realTimePrice }
                    }
                }
                // 移除所有价格低于当前价的 asks（已卖出）
                if (asksMap != null) {
                    synchronized(asksMap) {
                        asksMap.entries.removeIf { (price, _) -> price < realTimePrice }
                    }
                }
            }
        }
    }
}