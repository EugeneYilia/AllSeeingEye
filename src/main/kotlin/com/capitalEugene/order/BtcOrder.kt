package com.capitalEugene.order

import com.capitalEugene.common.constants.BTC_SPOT
import com.capitalEugene.common.constants.BTC_SWAP
import com.capitalEugene.common.constants.CONTRACT_VALUE
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

// 现货和合约的支撑位挂单和阻力位挂单列表
val depthCache = mutableMapOf(
    "spot" to mutableMapOf("bids" to mutableListOf<List<String>>(), "asks" to mutableListOf<List<String>>()),
    "swap" to mutableMapOf("bids" to mutableListOf<List<String>>(), "asks" to mutableListOf<List<String>>())
)

// 当前现货和合约的实时价格
val priceCache = mutableMapOf<String, Double?>(
    "spot" to null,
    "swap" to null
)

// 订阅的频道  订单簿和实时价格   分别有btc现货和合约
val CHANNELS = listOf(
    mapOf("channel" to "books", "instId" to BTC_SPOT),
    mapOf("channel" to "books", "instId" to BTC_SWAP),
    mapOf("channel" to "tickers", "instId" to BTC_SPOT),
    mapOf("channel" to "tickers", "instId" to BTC_SWAP)
)

val json = Json { ignoreUnknownKeys = true }

fun aggregateToUsdt(
    depthList: List<List<String>>,
    precision: Int = 5,
    multiplier: BigDecimal = BigDecimal.ONE
): List<Pair<BigDecimal, BigDecimal>> {
    val depthMap = mutableMapOf<BigDecimal, BigDecimal>()
    val safeDepthList = depthList.toList()  // ✅ 快照副本，防止并发修改
    for (entry in safeDepthList) {
        val price = entry.getOrNull(0)?.toBigDecimalOrNull() ?: continue
        val size = entry.getOrNull(1)?.toBigDecimalOrNull() ?: continue
        val factor = BigDecimal.TEN.pow(precision)
        val roundedPrice = price.divide(factor).setScale(0, RoundingMode.HALF_UP).multiply(factor)
        val usdtValue = price.multiply(size).multiply(multiplier)
        depthMap[roundedPrice] = depthMap.getOrDefault(roundedPrice, BigDecimal.ZERO) + usdtValue
    }
    return depthMap.entries.sortedByDescending { it.key }.map { it.toPair() }
}

fun printAggregatedDepth() {
    println("\n================= 📊 挂单聚合 =================")
    listOf("bids", "asks").forEach { side ->
        val label = if (side == "bids") "🔵 支撑位" else "🔴 压力位"
        println("$label（$side，单位：USDT）:")

        listOf("spot", "swap").forEach { source ->
            val price = priceCache[source]?.takeIf { !it.isNaN() }?.let { "%.2f".format(it) } ?: "N/A"
            println("  来源: ${source.uppercase()} | 实时价格: $price")

            val depthListSnapshot = (depthCache[source]?.get(side) as? List<List<String>>)?.toList() ?: emptyList()
            val agg = aggregateToUsdt(
                depthListSnapshot,
                precision = 2,
                multiplier = if (source == "spot") BigDecimal.ONE else CONTRACT_VALUE
            )

            agg.take(5).forEach { (priceVal, usdt) ->
                println("    $priceVal USDT - 挂单金额: ${usdt.setScale(2, RoundingMode.HALF_UP)} USDT")
            }
        }
    }
    println("==================================================\n")
}

suspend fun startWs(client: HttpClient) {
    val url = "wss://ws.okx.com:8443/ws/v5/public"
    var retryInterval = 5000L
    while (true) {
        try {
            println("🚀 尝试建立 WebSocket 连接...")
            client.webSocket(url) {
                subscribeChannels(this)
                incoming.consumeEach { frame ->
                    if (frame is Frame.Text) {
                        handleMessage(frame.readText())
                    }
                }
            }
        } catch (e: Exception) {
            println("⚠️ WebSocket 运行异常: ${e.message}")
        }
        println("🌐 连接断开，${retryInterval / 1000} 秒后重试...")
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
    println("✅ 已发送订阅请求: $subMsg")
}

fun handleMessage(message: String) {
    val data = json.parseToJsonElement(message)
    if (data.jsonObject["event"]?.jsonPrimitive?.content == "subscribe") {
        return
    }
    val arg = data.jsonObject["arg"]?.jsonObject ?: return
    val channel = arg["channel"]?.jsonPrimitive?.content ?: return
    val instId = arg["instId"]?.jsonPrimitive?.content ?: return
    val dtype = if (instId == BTC_SPOT) "spot" else "swap"

    val dataArray = data.jsonObject["data"]?.jsonArray ?: return
    val first = dataArray.firstOrNull()?.jsonObject ?: return

    if (channel == "books") {
        val bids = first["bids"]?.jsonArray?.map { it.jsonArray.map { e -> e.jsonPrimitive.content } } ?: emptyList()
        val asks = first["asks"]?.jsonArray?.map { it.jsonArray.map { e -> e.jsonPrimitive.content } } ?: emptyList()

        (depthCache[dtype]?.get("bids") as? MutableList<List<String>>)?.apply {
            clear()
            addAll(bids)
        }
        (depthCache[dtype]?.get("asks") as? MutableList<List<String>>)?.apply {
            clear()
            addAll(asks)
        }
    } else if (channel == "tickers") {
        val last = first["last"]?.jsonPrimitive?.doubleOrNull
        priceCache[dtype] = last
    }
}
