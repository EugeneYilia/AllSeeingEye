package com.capitalEugene.order

import com.capitalEugene.common.constants.OrderConstants
import io.ktor.client.*
import io.ktor.client.plugins.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import java.math.BigDecimal
import java.math.RoundingMode

// 现货和合约的支撑位挂单和阻力位挂单列表
val depthCache: MutableMap<String, MutableMap<String, MutableList<List<Double>>>> = mutableMapOf(
    "spot" to mutableMapOf(
        "bids" to mutableListOf(),
        "asks" to mutableListOf()
    ),
    "swap" to mutableMapOf(
        "bids" to mutableListOf(),
        "asks" to mutableListOf()
    )
)

// 当前现货和合约的实时价格
val priceCache = mutableMapOf<String, Double?>(
    "spot" to null,
    "swap" to null
)

// 订阅的频道  订单簿和实时价格   分别有btc现货和合约
val CHANNELS = listOf(
    mapOf("channel" to "books", "instId" to OrderConstants.BTC_SPOT),
    mapOf("channel" to "books", "instId" to OrderConstants.BTC_SWAP),
    mapOf("channel" to "tickers", "instId" to OrderConstants.BTC_SPOT),
    mapOf("channel" to "tickers", "instId" to OrderConstants.BTC_SWAP)
)

val json = Json { ignoreUnknownKeys = true }

// 默认降序
fun aggregateToUsdt(
    depthList: List<List<Double>>,
    precision: Int = 2,
    multiplier: BigDecimal = BigDecimal.ONE,
    ascending: Boolean = false
): List<Pair<BigDecimal, BigDecimal>> {
    val depthMap = mutableMapOf<BigDecimal, BigDecimal>()
    val safeDepthList = depthList.toList()  // ✅ 快照副本，防止并发修改
    for (entry in safeDepthList) {
        val price = entry.getOrNull(0)?.toBigDecimal() ?: continue
        val size = entry.getOrNull(1)?.toBigDecimal() ?: continue
        val factor = BigDecimal.TEN.pow(precision)
        val roundedPrice = price.divide(factor).setScale(0, RoundingMode.HALF_UP).multiply(factor)
        // swap 104000 * 30 * 0.01        spot   104000 * 30 * 1
        val usdtValue = price.multiply(size).multiply(multiplier)
        depthMap[roundedPrice] = depthMap.getOrDefault(roundedPrice, BigDecimal.ZERO) + usdtValue
    }

    val sorted = if (ascending) {
        depthMap.entries.sortedBy { it.key }
    } else {
        depthMap.entries.sortedByDescending { it.key }
    }
    return sorted.map { it.toPair() }
}

fun printAggregatedDepth() {
    println("\n================= 📊 挂单聚合 =================")
    listOf("bids", "asks").forEach { side ->
        val label = if (side == "bids") "🔵 支撑位" else "🔴 压力位"
        println("$label（$side，单位：USDT）:")

        listOf("spot", "swap").forEach { source ->
            val price = priceCache[source]?.takeIf { !it.isNaN() }?.let { "%.2f".format(it) } ?: "N/A"
            println("  来源: ${source.uppercase()} | 实时价格: $price")

            val depthListSnapshot = (depthCache[source]?.get(side) as? List<List<Double>>)?.toList() ?: emptyList()
            // 按照百位数进行聚合
            val agg = aggregateToUsdt(
                depthListSnapshot,
                precision = 2,
                multiplier = if (source == "spot") BigDecimal.ONE else OrderConstants.CONTRACT_VALUE,
                ascending = (side == "asks") // asks 卖单 升序， bids 买单 降序
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
                retryInterval = 5000L
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
    val dtype = if (instId == OrderConstants.BTC_SPOT) "spot" else "swap"

    val dataArray = data.jsonObject["data"]?.jsonArray ?: return
    val first = dataArray.firstOrNull()?.jsonObject ?: return

    if (channel == "books") {
        val bids = first["bids"]?.jsonArray?.mapNotNull { bidEntry ->
            val price = bidEntry.jsonArray.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val size = bidEntry.jsonArray.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (price != null && size != null) listOf(price, size) else {
                println("⚠️ 解析失败数据: $bidEntry")
                null
            }
        } ?: emptyList()

        val asks = first["asks"]?.jsonArray?.mapNotNull { askEntry ->
            val price = askEntry.jsonArray.getOrNull(0)?.jsonPrimitive?.doubleOrNull
            val size = askEntry.jsonArray.getOrNull(1)?.jsonPrimitive?.doubleOrNull
            if (price != null && size != null) listOf(price, size) else {
                println("⚠️ 解析失败数据: $askEntry")
                null
            }
        } ?: emptyList()

        // 按照现货或者合约 拿到支撑位的list 如果拿到了就清空旧的 并添加新的元素到集合里去
        depthCache[dtype]?.get("bids")?.apply {
            clear()
            addAll(bids)
        }
        depthCache[dtype]?.get("asks")?.apply {
            clear()
            addAll(asks)
        }
    } else if (channel == "tickers") {
        val last = first["last"]?.jsonPrimitive?.doubleOrNull
        priceCache[dtype] = last?.takeIf { !it.isNaN() }
    }
}
