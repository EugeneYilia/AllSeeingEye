package com.capitalEugene.backTest

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class Kline(
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val ts: Long)

val BIGDECIMAL_SCALE = 8
val ZONE = ZoneId.of("Asia/Shanghai") // 按北京时间划年
val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun bd(v: Double) = BigDecimal.valueOf(v)
fun bd(v: String) = try { BigDecimal(v) } catch (_: Exception) { BigDecimal.ZERO }
fun BigDecimal.toStr(scale: Int = 6) = this.setScale(scale, RoundingMode.HALF_UP).toPlainString()


fun loadKlines(symbol: String, timeframe: String, folder: String = "HistoricalKLine"): List<Kline> {
    val dir = File(folder)
    if (!dir.exists() || !dir.isDirectory) return emptyList()

    // 确保只加载 SWAP 合约类型的数据文件
    val normalizedSymbol = symbol.uppercase().replace("-USDT-SWAP", "")
    val files = dir.listFiles()?.filter { file ->
        file.isFile &&
                file.name.uppercase().contains("${normalizedSymbol}-USDT-SWAP") && // 精确匹配合约K线
                file.name.uppercase().contains(timeframe.uppercase()) &&
                file.name.endsWith(".CSV", ignoreCase = true)
    } ?: return emptyList()

    val list = mutableListOf<Kline>()

    for (f in files) {
        f.useLines { lines ->
            val it = lines.iterator()
            if (!it.hasNext()) return@useLines
            it.next() // 跳过表头

            while (it.hasNext()) {
                val parts = it.next().trim().split(',')
                if (parts.size < 6) continue

                val ts = parts[0].toLongOrNull() ?: continue
                val open = bd(parts[2])
                val high = bd(parts[3])
                val low = bd(parts[4])
                val close = bd(parts[5])

                list.add(Kline(open, high, low, close, ts))
            }
        }
    }

    return list.sortedBy { it.ts }
}