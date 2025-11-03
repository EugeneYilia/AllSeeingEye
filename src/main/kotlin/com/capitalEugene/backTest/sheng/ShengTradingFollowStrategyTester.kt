package com.capitalEugene.backTest.sheng

import com.capitalEugene.backTest.BIGDECIMAL_SCALE
import com.capitalEugene.backTest.DATE_FORMATTER
import com.capitalEugene.backTest.Kline
import com.capitalEugene.backTest.ZONE
import com.capitalEugene.backTest.bd
import com.capitalEugene.backTest.loadKlines
import com.capitalEugene.backTest.toStr

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDateTime

/**
 * ShengMartinPyramidBacktest.kt
 *
 * - 初始资金 1000U，杠杆 20x
 * - 初始仓位价值 400U（第一层）
 * - 最多 5 次加仓（initial + up to 5 adds）
 * - 加仓金额按 multipliers 相对 initial：0.7,0.6,0.5,0.4,0.2
 * - 开多：current_close <= prev9_min_low
 * - 开空：current_close >= prev9_max_high
 * - 盈利 >= 3% 时尝试加仓（会检查保证金）
 * - 第一层止损：未实现收益 <= -5% 平仓
 * - 追踪最高浮盈：如果回撤超过 30 个百分点（max_unreal_pct - 0.30），全部平仓
 * - 爆仓判定：equity + unreal - usedMargin <= 0
 * - 每年中文一行汇总输出
 */

object ShengMartinPyramidBacktest {
    // 基本资金与策略参数
    private val TOTAL_CAPITAL = bd(1000.0)
    private val LEVERAGE = bd(20.0)
    private val INITIAL_POSITION_VALUE = bd(400.0)
    private const val MAX_ADDS = 5 // 最多加5次（initial + 5 adds = 最多6层）
    private val ADD_MULTIPLIERS = listOf(bd(0.7), bd(0.6), bd(0.5), bd(0.4), bd(0.2))
    private val PROFIT_ADD_PCT = bd(0.03)  // 盈利3%触发加仓
    private val FIRST_LAYER_SL_PCT = bd(-0.05) // 第一层止损 -5%
    private val MAX_DRAWDOWN_FROM_PEAK_PCT = bd(0.30) // 回撤 30% 点触发止损（基于最高浮盈）
    private val CONTRACT_SIZE = bd(1.0)

    // 爆仓后自动注资开关（你之前提到希望爆仓后能再投入）
    private const val AUTO_RESTART_AFTER_LIQUIDATION = true
    private val RESTART_CAPITAL = TOTAL_CAPITAL

    // 年度摘要
    data class YearSummary(
        val year: Int,
        var startCapital: BigDecimal,
        var endCapital: BigDecimal,
        var roiPct: BigDecimal,
        var opens: Int,
        var adds: Int,
        var firstLayerStops: Int,
        var fullTps: Int,
        var liquidations: Int,
        var peakDrawdownStops: Int
    )

    // 回测一年（按北京时间）
    fun backtestYear(symbol: String, timeframe: String, year: Int, klinesAll: List<Kline>): YearSummary {
        val startMs = LocalDateTime.of(year,1,1,0,0).atZone(ZONE).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year,12,31,23,59,59).atZone(ZONE).toInstant().toEpochMilli()
        val klines = klinesAll.filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val summary = YearSummary(year, TOTAL_CAPITAL, TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0, 0, 0)
        if (klines.size < 10) return summary

        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        // position state (single-side at a time)
        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0 // 0 means no position; 1 means initial opened, etc.
        var posDir = "" // "long" or "short"
        // 使用 nullable BigDecimal 作为 sentinel：null 表示尚无 peak tracked
        var maxUnrealPct: BigDecimal? = null // track highest unreal pct since entry

        // counters
        var opens = 0
        var adds = 0
        var firstLayerStops = 0
        var fullTps = 0
        var liquidations = 0
        var peakDrawdownStops = 0

        for (i in 9 until klines.size) {
            val window = klines.subList(i-9, i+1)
            val current = window.last()
            val price = current.close
            val prev9 = window.subList(0,9)
            val prev9MinLow = prev9.minOf { it.low }
            val prev9MaxHigh = prev9.maxOf { it.high }
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(current.ts), ZONE).format(DATE_FORMATTER)

            // compute unrealized PnL and percent
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            val denomForPct = if (posLayer > 0) posEntry.multiply(posSize).multiply(CONTRACT_SIZE) else bd(1.0)
            val unrealPct = if (posLayer > 0 && denomForPct > BigDecimal.ZERO) unreal.divide(denomForPct, BIGDECIMAL_SCALE, RoundingMode.HALF_UP) else bd(0.0)

            // mark-to-market margin balance check (liquidation)
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (marginBalance <= BigDecimal.ZERO && posLayer > 0) {
                // liquidation occurs
                liquidations++
                // clear position and margin
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)
                // handle restart or stop
                if (AUTO_RESTART_AFTER_LIQUIDATION) {
                    equity = RESTART_CAPITAL
                } else {
                    equity = bd(0.0)
                    break
                }
                // continue to next bar
                continue
            }

            val longSignal = price <= prev9MinLow
            val shortSignal = price >= prev9MaxHigh

            // if no position -> open on signal
            if (posLayer == 0) {
                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    val value = INITIAL_POSITION_VALUE
                    val margin = value.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= margin) {
                        val size = value.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.add(margin)
                        posSize = size; posEntry = price; posLayer = 1; posDir = dir
                        maxUnrealPct = null // reset peak tracker: null 表示尚无峰值
                        opens++
                        // println("[$dt] OPEN $dir @${price.toStr(4)} size=${size.toStr(6)} val=${value.toStr(2)}")
                    } else {
                        // insufficient margin - skip
                    }
                }
                continue
            }

            // update peak unreal pct
            if (posLayer > 0) {
                if (maxUnrealPct == null || unrealPct > maxUnrealPct) maxUnrealPct = unrealPct
            }

            // FIRST LAYER STOP LOSS: if only first layer and unreal pct <= -5%
            if (posLayer == 1) {
                if (unrealPct <= FIRST_LAYER_SL_PCT) {
                    // close all
                    val realized = unreal
                    equity = equity.add(realized)
                    val totalValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    val marginReleased = totalValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                    firstLayerStops++
                    // println("[$dt] FIRST LAYER STOP at ${price.toStr(4)} realized=${realized.toStr(6)}")
                    continue
                }
            }

            // Peak drawdown stop: if current unreal pct <= maxUnrealPct - 30%
            if (posLayer > 0 && maxUnrealPct != null) {
                if (unrealPct <= maxUnrealPct!!.subtract(MAX_DRAWDOWN_FROM_PEAK_PCT)) {
                    // close all
                    val realized = unreal
                    equity = equity.add(realized)
                    val totalValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    val marginReleased = totalValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                    peakDrawdownStops++
                    // println("[$dt] PEAK DRAWSTOP at ${price.toStr(4)} realized=${realized.toStr(6)}")
                    continue
                }
            }

            // PROFIT-BASED ADD: if unreal_pct >= 3% add one layer (if possible)
            if (posLayer in 1..(1 + MAX_ADDS - 1)) { // allow adding while posLayer < 1+MAX_ADDS
                if (unrealPct >= PROFIT_ADD_PCT) {
                    val currentAddIndex = posLayer - 1 // posLayer=1 -> index 0 (first add)
                    if (currentAddIndex < ADD_MULTIPLIERS.size) {
                        val addMultiplier = ADD_MULTIPLIERS[currentAddIndex]
                        val addValue = INITIAL_POSITION_VALUE.multiply(addMultiplier)
                        val addMargin = addValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        if (equity.subtract(usedMargin) >= addMargin) {
                            val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            // update avg entry price (value-weighted)
                            val oldValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                            val newValue = price.multiply(addSize).multiply(CONTRACT_SIZE)
                            val combinedValue = oldValue.add(newValue)
                            val combinedSize = posSize.add(addSize)
                            val newEntry = if (combinedSize.compareTo(BigDecimal.ZERO) == 0) bd(0.0) else combinedValue.divide(combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            posSize = combinedSize
                            posEntry = newEntry
                            posLayer = posLayer + 1
                            usedMargin = usedMargin.add(addMargin)
                            adds++
                            // reset peak tracker? keep it (peak stays)
                            // println("[$dt] ADD layer ${posLayer} addVal=${addValue.toStr(2)}")
                        } else {
                            // insufficient margin for add -> skip
                        }
                    }
                }
            }

            // LATER LAYERS FULL TP (not specified exactly in new spec -> keep a conservative rule)
            // We keep earlier's LATER_LAYERS_TP_PCT as fallback: if overall pnlPct >= 2% and posLayer >=2 -> close all
            if (posLayer >= 2) {
                val denom = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                if (denom > BigDecimal.ZERO) {
                    val pnlPct = unreal.divide(denom, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (pnlPct >= bd(0.02)) {
                        val realized = unreal
                        equity = equity.add(realized)
                        val totalValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        val marginReleased = totalValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                        posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                        fullTps++
                        continue
                    }
                }
            }
            // continue loop
        } // end bars

        // 年末结算
        val finalUnreal = if (posLayer > 0) {
            val lastPrice = klines.last().close
            if (posDir == "long") lastPrice.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
            else posEntry.subtract(lastPrice).multiply(posSize).multiply(CONTRACT_SIZE)
        } else bd(0.0)

        val endCapital = maxOf(equity.add(finalUnreal), bd(0.0))
        summary.endCapital = endCapital
        summary.opens = opens
        summary.adds = adds
        summary.firstLayerStops = firstLayerStops
        summary.fullTps = fullTps
        summary.liquidations = liquidations
        summary.peakDrawdownStops = peakDrawdownStops
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
                val years = allK.map { Instant.ofEpochMilli(it.ts).atZone(ZONE).year }.distinct().sorted()
                for (y in years) {
                    val sum = backtestYear(symbol, tf, y, allK)
                    println("【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓次数 ${sum.opens}，加仓次数 ${sum.adds}，第一层止损 ${sum.firstLayerStops} 次，整仓止盈 ${sum.fullTps} 次，爆仓 ${sum.liquidations} 次，回撤触发止损 ${sum.peakDrawdownStops} 次")
                }
            }
        }
    }

    private fun BigDecimal.coerceAtLeast(other: BigDecimal): BigDecimal {
        return if (this < other) other else this
    }
}
