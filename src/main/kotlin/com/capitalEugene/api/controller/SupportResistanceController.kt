package com.capitalEugene.api.controller

import com.capitalEugene.common.utils.BigDecimalAsStringSerializer
import com.capitalEugene.order.depthCache
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.SortedMap
import java.util.TreeMap

private val logger = LoggerFactory.getLogger("SupportResistanceController")

@Serializable
data class SupportResistanceZone(
    val type: String,               // "support" / "resistance"

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val price: BigDecimal,         // 加权平均价格

    @Serializable(with = BigDecimalAsStringSerializer::class)
    val totalVolume: BigDecimal    // 区间挂单量
)

fun calculateZones(
    map: SortedMap<BigDecimal, BigDecimal>,
    type: String,
    binCount: Int = 10
): List<SupportResistanceZone> {
    if (map.isEmpty()) return emptyList()


    val prices = map.keys
    val min = prices.minOrNull()!!
    val max = prices.maxOrNull()!!
    if (min == max) return listOf(SupportResistanceZone(type, min, map[min]!!))

    // 向上取整  增加interval，确保可以都涵盖
    val interval = (max - min).divide(BigDecimal(binCount), 8, RoundingMode.CEILING)
    val bins = Array(binCount) { mutableListOf<Pair<BigDecimal, BigDecimal>>() }

    map.forEach { (price, volume) ->
        try {
            // 向下取整  确保索引位置处于合理范围内
            val index = ((price - min).divide(interval, 8, RoundingMode.FLOOR))
                .toInt()
                .coerceIn(0, binCount - 1)

            bins[index].add(price to volume)
        } catch (e: Exception) {
            print(price)
            println("  " + ((price - min) / interval) + "  ")
            println(((price - min) / interval).toInt())
            throw e
        }
    }

    return bins.mapNotNull { bucket ->
        if (bucket.isEmpty()) return@mapNotNull null

        val totalVol = bucket.fold(BigDecimal.ZERO) { acc, (_, vol) -> acc + vol }
        val weightedPrice = bucket.fold(BigDecimal.ZERO) { acc, (p, v) -> acc + (p * v) }
            .divide(totalVol, 2, RoundingMode.HALF_UP)

        SupportResistanceZone(
            type = type,
            price = weightedPrice,
            totalVolume = totalVol.setScale(2, RoundingMode.HALF_UP)
        )
    }

}


fun Route.registerSupportResistanceRoute() {
    get("/v1/level-lines/{symbol}") {
        val symbol = call.parameters["symbol"] ?: return@get call.respond(HttpStatusCode.BadRequest, "Missing symbol")
        val depth = depthCache[symbol] ?: return@get call.respond(HttpStatusCode.NotFound, "No depth data")

        val bids = TreeMap(depth["bids"])
        val asks = TreeMap(depth["asks"])

        val supportZones = calculateZones(bids, "support", 10)
        val resistanceZones = calculateZones(asks, "resistance", 10)

        call.respond(supportZones + resistanceZones)
    }
}