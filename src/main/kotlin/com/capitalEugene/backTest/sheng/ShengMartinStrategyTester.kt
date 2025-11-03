package com.capitalEugene.backTest.sheng

import com.capitalEugene.backTest.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime

/**
 * 修正版马丁策略回测：
 * - 爆仓判定使用实时市值 (equity + unreal - usedMargin <= 0)
 * - 支持爆仓后自动注资并继续回测
 * - 第一层及后续加仓层均整仓止盈
 */

object ShengBacktestSummaryFixed {
    // 参数
    private val TOTAL_CAPITAL = bd(1000.0)
    private val LEVERAGE = bd(20.0)
    private val INITIAL_POSITION_VALUE = bd(400.0)
    private const val MAX_LAYERS = 5
    private val ADD_STEP_PCT = bd(0.02)
    private val FIRST_LAYER_TP_PCT = bd(0.03)
    private val LATER_LAYERS_TP_PCT = bd(0.02)
    private val CONTRACT_SIZE = bd(1.0)

    private const val AUTO_RESTART_AFTER_LIQUIDATION = true
    private val RESTART_CAPITAL = TOTAL_CAPITAL

    // 年度回测摘要
    data class YearSummary(
        val year: Int,
        var startCapital: BigDecimal,
        var endCapital: BigDecimal,
        var roiPct: BigDecimal,
        var tradesOpenCount: Int,
        var totalAddCount: Int,
        var fullTpCount: Int,
        var liquidationCount: Int
    )

    fun backtestYear(symbol: String, timeframe: String, year: Int, klinesAll: List<Kline>): YearSummary {
        val startMs = LocalDateTime.of(year, 1, 1, 0, 0).atZone(ZONE).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59).atZone(ZONE).toInstant().toEpochMilli()
        val klines = klinesAll.filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val summary = YearSummary(year, TOTAL_CAPITAL, TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0)
        if (klines.size < 10) return summary

        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)
        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0
        var posDir = ""
        var tradesOpen = 0
        var totalAdds = 0
        var fullTps = 0
        var liquidations = 0

        for (i in 9 until klines.size) {
            val window = klines.subList(i - 9, i + 1)
            val current = window.last()
            val price = current.close
            val prev9 = window.subList(0, 9)
            val prev9MinLow = prev9.minOf { it.low }
            val prev9MaxHigh = prev9.maxOf { it.high }

            // 计算未实现 pnl
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            // 爆仓判定
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (marginBalance <= BigDecimal.ZERO) {
                liquidations++
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)
                equity = if (AUTO_RESTART_AFTER_LIQUIDATION) RESTART_CAPITAL else bd(0.0)
                if (!AUTO_RESTART_AFTER_LIQUIDATION) break
                continue
            }

            val longSignal = price <= prev9MinLow
            val shortSignal = price >= prev9MaxHigh

            // 开仓
            if (posLayer == 0 && (longSignal || shortSignal)) {
                val dir = if (longSignal) "long" else "short"
                val value = INITIAL_POSITION_VALUE
                val margin = value.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                if (equity.subtract(usedMargin) >= margin) {
                    val size = value.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.add(margin)
                    posSize = size; posEntry = price; posLayer = 1; posDir = dir
                    tradesOpen++
                }
                continue
            }

            // 若有持仓，计算当前未实现收益
            val unrealHere = unreal

            // 第一层及后续层整仓止盈
            if (posLayer >= 1) {
                val retPct = if (posDir == "long") price.subtract(posEntry)
                    .divide(posEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                else posEntry.subtract(price).divide(posEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                val tpPct = if (posLayer == 1) FIRST_LAYER_TP_PCT else LATER_LAYERS_TP_PCT
                if (retPct >= tpPct) {
                    val realized = unrealHere
                    equity = equity.add(realized)
                    val totalValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    val marginReleased = totalValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    fullTps++
                    posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                    continue
                }
            }

            // 加仓（基于 entry）
            if (posLayer in 1 until MAX_LAYERS) {
                val dropPct = if (posDir == "long") posEntry.subtract(price)
                    .divide(posEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                else price.subtract(posEntry).divide(posEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                val thresholdsCrossed =
                    dropPct.divide(ADD_STEP_PCT, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).toBigInteger().toInt()
                val currentLayer = posLayer
                if (thresholdsCrossed >= currentLayer) {
                    val multiplier = BigDecimal.valueOf(2).pow(currentLayer)
                    val addValue = INITIAL_POSITION_VALUE.multiply(multiplier)
                    val addMargin = addValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= addMargin) {
                        val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        val oldValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        val newValue = price.multiply(addSize).multiply(CONTRACT_SIZE)
                        val combinedValue = oldValue.add(newValue)
                        val combinedSize = posSize.add(addSize)
                        posEntry = combinedValue.divide(combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        posSize = combinedSize
                        posLayer++
                        usedMargin = usedMargin.add(addMargin)
                        totalAdds++
                    }
                }
            }
        } // bars end

        // 年末结算
        val finalUnreal = if (posLayer > 0) {
            val lastPrice = klines.last().close
            if (posDir == "long") lastPrice.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
            else posEntry.subtract(lastPrice).multiply(posSize).multiply(CONTRACT_SIZE)
        } else bd(0.0)

        summary.endCapital = equity.add(finalUnreal).coerceAtLeast(bd(0.0))
        summary.tradesOpenCount = tradesOpen
        summary.totalAddCount = totalAdds
        summary.fullTpCount = fullTps
        summary.liquidationCount = liquidations
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0)
        else (summary.endCapital.subtract(summary.startCapital)
            .divide(summary.startCapital, 6, RoundingMode.HALF_UP).multiply(bd(100.0)))

        return summary
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val symbols = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")
        val tfs = listOf("1H", "4H")

        for (symbol in symbols) {
            for (tf in tfs) {
                val allK = loadKlines(symbol, tf)
                if (allK.isEmpty()) {
                    println("[$symbol][$tf] 未找到数据，跳过")
                    continue
                }
                val years = allK.map { Instant.ofEpochMilli(it.ts).atZone(ZONE).year }.distinct().sorted()
                for (y in years) {
                    val sum = backtestYear(symbol, tf, y, allK)
                    println(
                        "【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${
                            sum.endCapital.toStr(2)
                        } USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓次数 ${sum.tradesOpenCount}，加仓次数 ${sum.totalAddCount}，整仓止盈 ${sum.fullTpCount} 次，爆仓次数 ${sum.liquidationCount}"
                    )
                }
            }
        }
    }
}
