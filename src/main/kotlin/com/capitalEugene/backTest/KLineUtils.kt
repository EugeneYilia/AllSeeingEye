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
    val vol: BigDecimal,         // 成交量（数量）
    val volCcy: BigDecimal,      // 成交量对应的计价货币（如果 CSV 有的话，否则 0）
    val ts: Long
)

val BIGDECIMAL_SCALE = 8
val ZONE = ZoneId.of("Asia/Shanghai") // 按北京时间划年
val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun bd(v: Double) = BigDecimal.valueOf(v)
fun bd(v: String) = try { BigDecimal(v) } catch (_: Exception) { BigDecimal.ZERO }
fun BigDecimal.toStr(scale: Int = 6) = this.setScale(scale, RoundingMode.HALF_UP).toPlainString()

/**
 * loadKlines:
 * - 从 HistoricalKLine 文件夹加载符合 symbol + timeframe 的 CSV 文件
 * - 期望 CSV 至少包含这些列 (常见顺序): timestamp, datetime, open, high, low, close, volume, volume_ccy
 * - 对不同格式（少了 volume_ccy）做了兼容处理
 */
fun loadKlines(symbol: String, timeframe: String, folder: String = "HistoricalKLine"): List<Kline> {
    val dir = File(folder)
    if (!dir.exists() || !dir.isDirectory) return emptyList()

    // 规范化 symbol 用于匹配文件名，例如 "BTC-USDT-SWAP"
    val normalizedSymbol = symbol.uppercase().replace("-USDT-SWAP", "")
    val files = dir.listFiles()?.filter { file ->
        file.isFile &&
                file.name.uppercase().contains("${normalizedSymbol}-USDT-SWAP") &&
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
                val raw = it.next().trim()
                if (raw.isEmpty()) continue

                // 简单按逗号拆分；若你的 CSV 含有引号、逗号内字段，建议替换为更健壮的 CSV 解析器
                val parts = raw.split(',')

                // 期望至少到 close + volume: indices (0)ts (1)datetime (2)open (3)high (4)low (5)close (6)volume (7)volume_ccy
                if (parts.size < 7) continue

                val ts = parts[0].toLongOrNull() ?: continue
                val open = bd(parts[2])
                val high = bd(parts[3])
                val low = bd(parts[4])
                val close = bd(parts[5])

                // volume 在 index 6，volume_ccy 在 index 7（如果存在）
                val vol = try { bd(parts[6]) } catch (_: Exception) { bd(0.0) }
                val volCcy = if (parts.size > 7) try { bd(parts[7]) } catch (_: Exception) { bd(0.0) } else bd(0.0)

                list.add(Kline(open, high, low, close, vol, volCcy, ts))
            }
        }
    }

    return list.sortedBy { it.ts }
}
