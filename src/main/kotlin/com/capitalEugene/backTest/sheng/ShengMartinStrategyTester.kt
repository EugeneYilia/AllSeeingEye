package com.capitalEugene.backTest.sheng

import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 修复版回测：
 * - 爆仓判定使用实时市值 (equity + unreal - usedMargin <= 0)
 * - 支持爆仓后自动注资并继续回测（AUTO_RESTART_AFTER_LIQUIDATION）
 * - 每年只输出一行中文摘要
 */

object ShengBacktestSummaryFixed {
    // 参数（按你需求）
    private val TOTAL_CAPITAL = bd(1000.0)
    private val LEVERAGE = bd(20.0)
    private val INITIAL_POSITION_VALUE = bd(400.0)
    private const val MAX_LAYERS = 5
    private val ADD_STEP_PCT = bd(0.02)
    private val FIRST_LAYER_TP_PCT = bd(0.03)
    private val FIRST_LAYER_CLOSE_VALUE = bd(12.0)
    private val LATER_LAYERS_TP_PCT = bd(0.02)
    private val CONTRACT_SIZE = bd(1.0)
    private val SCALE = 8
    private val zone = ZoneId.of("Asia/Shanghai") // 按北京时间划年
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // 爆仓处理策略
    private const val AUTO_RESTART_AFTER_LIQUIDATION = true  // 爆仓后是否自动注资继续
    private val RESTART_CAPITAL = TOTAL_CAPITAL             // 注资金额，默认恢复到初始资金

    private fun bd(v: Double) = BigDecimal.valueOf(v)
    private fun bd(v: String) = try { BigDecimal(v) } catch (_: Exception) { BigDecimal.ZERO }
    private fun BigDecimal.toStr(scale: Int = 6) = this.setScale(scale, RoundingMode.HALF_UP).toPlainString()

    data class Kline(val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal, val ts: Long)

    // 读取 CSV（与你之前一致）
    fun loadKlines(symbol: String, timeframe: String, folder: String = "HistoricalKLine"): List<Kline> {
        val dir = File(folder)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val symbolShort = symbol.replace("-USDT", "", ignoreCase = true)
        val files = dir.listFiles()?.filter {
            it.isFile && it.name.contains(symbolShort, ignoreCase = true) && it.name.contains(timeframe, ignoreCase = true)
        } ?: emptyList()
        val list = mutableListOf<Kline>()
        for (f in files) {
            f.useLines { lines ->
                val it = lines.iterator()
                if (!it.hasNext()) return@useLines
                it.next() // header
                while (it.hasNext()) {
                    val line = it.next().trim()
                    if (line.isEmpty()) continue
                    val parts = line.split(",")
                    if (parts.size < 6) continue
                    val ts = parts[0].toLongOrNull() ?: continue
                    val open = bd(parts[2]); val high = bd(parts[3]); val low = bd(parts[4]); val close = bd(parts[5])
                    list.add(Kline(open, high, low, close, ts))
                }
            }
        }
        return list.sortedBy { it.ts }
    }

    // 年度回测摘要数据结构
    data class YearSummary(
        val year: Int,
        var startCapital: BigDecimal,
        var endCapital: BigDecimal,
        var roiPct: BigDecimal,
        var tradesOpenCount: Int,
        var totalAddCount: Int,
        var partialTpCount: Int,
        var fullTpCount: Int,
        var liquidationCount: Int
    )

    // 回测一年（以北京时间年划分）
    fun backtestYear(symbol: String, timeframe: String, year: Int, klinesAll: List<Kline>): YearSummary {
        val startMs = LocalDateTime.of(year,1,1,0,0).atZone(zone).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year,12,31,23,59,59).atZone(zone).toInstant().toEpochMilli()
        val klines = klinesAll.filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val summary = YearSummary(year, TOTAL_CAPITAL, TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0, 0)
        if (klines.size < 10) {
            summary.endCapital = summary.startCapital
            summary.roiPct = bd(0.0)
            return summary
        }

        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)
        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0
        var posDir = "" // "long" or "short"
        var tradesOpen = 0
        var totalAdds = 0
        var partialTps = 0
        var fullTps = 0
        var liquidations = 0

        for (i in 9 until klines.size) {
            val window = klines.subList(i-9, i+1)
            val current = window.last()
            val price = current.close
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(current.ts), zone).format(dateFormatter)
            val prev9 = window.subList(0,9)
            val prev9MinLow = prev9.minOf { it.low }
            val prev9MaxHigh = prev9.maxOf { it.high }

            // 每根 bar 都要计算未实现 pnl（若有持仓）
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            // 关键：用 标记资产 = equity + unreal 去判断是否爆仓（是否可维持当前保证金）
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (marginBalance <= BigDecimal.ZERO) {
                // 触发爆仓（被强平）
                liquidations++
                // 清空持仓与释放保证金
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)
                // 对账户处理：根据 AUTO_RESTART_AFTER_LIQUIDATION 决定是否注资继续
                if (AUTO_RESTART_AFTER_LIQUIDATION) {
                    equity = RESTART_CAPITAL
                    // 继续当年交易（如你要求“爆仓了可以投入进去再开”）
                } else {
                    equity = bd(0.0)
                    // 停止当年交易：直接跳出循环
                    break
                }
                // 继续到下一 bar
            }

            val longSignal = price <= prev9MinLow
            val shortSignal = price >= prev9MaxHigh

            if (posLayer == 0) {
                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    val value = INITIAL_POSITION_VALUE
                    val margin = value.divide(LEVERAGE, SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= margin) {
                        val size = value.divide(price, SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.add(margin)
                        posSize = size; posEntry = price; posLayer = 1; posDir = dir
                        tradesOpen++
                    } else {
                        // 资金不足无法开仓，跳过
                    }
                }
                continue
            }

            // 若有持仓
            val unrealHere = unreal
            val currentValue = price.multiply(posSize).multiply(CONTRACT_SIZE)

            // 第一层部分止盈
            if (posLayer == 1) {
                val retPct = if (posDir == "long") price.subtract(posEntry).divide(posEntry, SCALE, RoundingMode.HALF_UP)
                else posEntry.subtract(price).divide(posEntry, SCALE, RoundingMode.HALF_UP)
                if (retPct >= FIRST_LAYER_TP_PCT) {
                    val closeValue = FIRST_LAYER_CLOSE_VALUE
                    val closeSize = closeValue.divide(price, SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, SCALE, RoundingMode.HALF_UP)
                    val actualClose = if (closeSize > posSize) posSize else closeSize
                    val realized = if (posDir == "long") price.subtract(posEntry).multiply(actualClose).multiply(CONTRACT_SIZE)
                    else posEntry.subtract(price).multiply(actualClose).multiply(CONTRACT_SIZE)
                    equity = equity.add(realized)
                    posSize = posSize.subtract(actualClose)
                    posLayer = if (posSize > BigDecimal.ZERO) posLayer else 0
                    val marginReleased = closeValue.divide(LEVERAGE, SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    partialTps++
                    if (posSize == BigDecimal.ZERO) { posEntry = bd(0.0); posDir = "" }
                    continue
                }
            }

            // 第二到五层整体止盈
            if (posLayer >= 2) {
                val denom = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                if (denom > BigDecimal.ZERO) {
                    val pnlPct = unrealHere.divide(denom, SCALE, RoundingMode.HALF_UP)
                    if (pnlPct >= LATER_LAYERS_TP_PCT) {
                        equity = equity.add(unrealHere)
                        val totalValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        val marginReleased = totalValue.divide(LEVERAGE, SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                        fullTps++
                        posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                        continue
                    }
                }
            }

            // 加仓（基于 entry）
            if (posLayer in 1 until MAX_LAYERS) {
                val dropPct = if (posDir == "long") posEntry.subtract(price).divide(posEntry, SCALE, RoundingMode.HALF_UP)
                else price.subtract(posEntry).divide(posEntry, SCALE, RoundingMode.HALF_UP)
                val thresholdsCrossed = dropPct.divide(ADD_STEP_PCT, SCALE, RoundingMode.HALF_UP).toBigInteger().toInt()
                val currentLayer = posLayer
                if (thresholdsCrossed >= currentLayer) {
                    val multiplier = BigDecimal.valueOf(2).pow(currentLayer)
                    val addValue = INITIAL_POSITION_VALUE.multiply(multiplier)
                    val addMargin = addValue.divide(LEVERAGE, SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= addMargin) {
                        val addSize = addValue.divide(price, SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, SCALE, RoundingMode.HALF_UP)
                        val oldValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        val newValue = price.multiply(addSize).multiply(CONTRACT_SIZE)
                        val combinedValue = oldValue.add(newValue)
                        val combinedSize = posSize.add(addSize)
                        val newEntry = if (combinedSize.compareTo(BigDecimal.ZERO) == 0) bd(0.0) else combinedValue.divide(combinedSize.multiply(CONTRACT_SIZE), SCALE, RoundingMode.HALF_UP)
                        posSize = combinedSize
                        posEntry = newEntry
                        posLayer = posLayer + 1
                        usedMargin = usedMargin.add(addMargin)
                        totalAdds++
                    } else {
                        // 跳过加仓
                    }
                }
            }
        } // bars end

        // 年末结算：如果仍持仓，计算未实现并加入 endCapital（注：此时若未触发爆仓，说明标记市值仍 > 0）
        val finalUnreal = if (posLayer > 0) {
            val lastPrice = klines.last().close
            if (posDir == "long") lastPrice.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
            else posEntry.subtract(lastPrice).multiply(posSize).multiply(CONTRACT_SIZE)
        } else bd(0.0)

        summary.endCapital = if (equity.add(finalUnreal) < bd(0.0)) bd(0.0) else equity.add(finalUnreal)
        summary.tradesOpenCount = tradesOpen
        summary.totalAddCount = totalAdds
        summary.partialTpCount = partialTps
        summary.fullTpCount = fullTps
        summary.liquidationCount = liquidations
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0) else (summary.endCapital.subtract(summary.startCapital).divide(summary.startCapital, 6, RoundingMode.HALF_UP).multiply(bd(100.0)))

        return summary
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val symbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT")
        val tfs = listOf("1H", "4H")

        for (symbol in symbols) {
            for (tf in tfs) {
                val allK = loadKlines(symbol, tf)
                if (allK.isEmpty()) {
                    println("[$symbol][$tf] 未找到数据，跳过")
                    continue
                }
                val years = allK.map { Instant.ofEpochMilli(it.ts).atZone(zone).year }.distinct().sorted()
                for (y in years) {
                    val sum = backtestYear(symbol, tf, y, allK)
                    println("【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓次数 ${sum.tradesOpenCount}，加仓次数 ${sum.totalAddCount}，第一层部分止盈 ${sum.partialTpCount} 次，后续整仓止盈 ${sum.fullTpCount} 次，爆仓次数 ${sum.liquidationCount}")
                }
            }
        }
    }
}
