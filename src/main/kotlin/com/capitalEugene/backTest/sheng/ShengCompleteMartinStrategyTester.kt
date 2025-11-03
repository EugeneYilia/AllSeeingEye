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
import kotlin.random.Random

object ShengAntiMartingaleBacktest {

    // 基本资金与杠杆
    private val INITIAL_TOTAL_CAPITAL = bd(1000.0)
    private val BASE_LEVERAGE = bd(20.0)

    // 仓位/金字塔规则（优化版）
    private val INITIAL_POS_PCT = bd(0.30) // 降低首仓比例到30%
    private val ADD_THRESHOLDS = listOf(bd(0.025), bd(0.05), bd(0.08), bd(0.12), bd(0.18), bd(0.25)) // 提高盈利阈值
    private val ADD_MULTIPLIERS = listOf(bd(0.6), bd(0.5), bd(0.4), bd(0.3), bd(0.2), bd(0.1)) // 降低加仓比例
    private val STOP_LOSS_AFTER_ADD = listOf(bd(0.005), bd(0.01), bd(0.015), bd(0.02), bd(0.03), bd(0.04)) // 加仓后止损位置

    // 止损 / 止盈规则（优化版）
    private val FIRST_LAYER_SL_PCT = bd(0.02) // 降低首仓止损到2%
    private val DAILY_MAX_LOSS_PCT = bd(0.07)
    private val SINGLE_TRADE_MAX_RISK_PCT = bd(0.04)
    private val MAX_DRAWDOWN_STOP = bd(0.30) // 提高最大回撤到30%
    private val CONTRACT_SIZE = bd(1.0)

    // 指标参数
    private const val EMA_SHORT = 7
    private const val EMA_LONG = 21
    private const val RSI_PERIOD = 14
    private const val MACD_FAST = 12
    private const val MACD_SLOW = 26
    private const val MACD_SIGNAL = 9
    private const val ADX_PERIOD = 14

    data class YearSummary(
        val year: Int,
        var startCapital: BigDecimal,
        var endCapital: BigDecimal,
        var roiPct: BigDecimal,
        var opens: Int,
        var adds: Int,
        var stops: Int,
        var partialTps: Int,
        var fullTps: Int,
        var liquidations: Int
    )

    data class TradeRecord(
        val dt: String,
        val type: String,
        val layer: Int,
        val dir: String,
        val price: BigDecimal,
        val size: BigDecimal,
        val unreal: BigDecimal
    )

    data class Position(
        var size: BigDecimal = bd(0.0),
        var entryPrice: BigDecimal = bd(0.0),
        var direction: String = "",
        var layers: Int = 0,
        var totalInvested: BigDecimal = bd(0.0),
        var initialSize: BigDecimal = bd(0.0),
        var highestProfit: BigDecimal = bd(0.0),
        var totalValue: BigDecimal = bd(0.0) // 仓位总价值
    )

    // --- 指标计算函数保持不变 ---
    private fun ema(values: List<BigDecimal>, period: Int): List<BigDecimal> {
        val out = MutableList(values.size) { bd(0.0) }
        if (values.isEmpty()) return out
        val alpha = bd(2.0).divide(bd(period.toDouble() + 1.0), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        out[0] = values[0]
        for (i in 1 until values.size) {
            out[i] = values[i].multiply(alpha).add(out[i - 1].multiply(BigDecimal.ONE.subtract(alpha)))
        }
        return out
    }

    private fun sma(values: List<BigDecimal>, period: Int): List<BigDecimal> {
        val out = MutableList(values.size) { bd(0.0) }
        if (values.size < period) return out
        var sum = bd(0.0)
        for (i in values.indices) {
            sum = sum.add(values[i])
            if (i >= period) sum = sum.subtract(values[i - period])
            if (i >= period - 1) out[i] = sum.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        }
        return out
    }

    private fun rsi(values: List<BigDecimal>, period: Int): List<BigDecimal> {
        val out = MutableList(values.size) { bd(0.0) }
        if (values.size <= period) return out
        var gain = bd(0.0)
        var loss = bd(0.0)
        for (i in 1..period) {
            val diff = values[i].subtract(values[i - 1])
            if (diff > bd(0.0)) gain = gain.add(diff) else loss = loss.add(diff.abs())
        }
        var avgGain = gain.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        var avgLoss = loss.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        for (i in period + 1 until values.size) {
            val diff = values[i].subtract(values[i - 1])
            val g = if (diff > bd(0.0)) diff else bd(0.0)
            val l = if (diff < bd(0.0)) diff.abs() else bd(0.0)
            avgGain = (avgGain.multiply(bd(period.toDouble() - 1))).add(g)
                .divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
            avgLoss = (avgLoss.multiply(bd(period.toDouble() - 1))).add(l)
                .divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
            val rs = if (avgLoss.compareTo(bd(0.0)) == 0) bd(0.0) else avgGain.divide(
                avgLoss,
                BIGDECIMAL_SCALE,
                RoundingMode.HALF_UP
            )
            out[i] = if (avgLoss.compareTo(bd(0.0)) == 0) bd(100.0) else bd(100.0).subtract(
                bd(100.0).divide(
                    rs.add(bd(1.0)),
                    BIGDECIMAL_SCALE,
                    RoundingMode.HALF_UP
                )
            )
        }
        return out
    }

    private fun macdHistogram(values: List<BigDecimal>): List<BigDecimal> {
        if (values.isEmpty()) return listOf()
        val emaFast = ema(values, MACD_FAST)
        val emaSlow = ema(values, MACD_SLOW)
        val diff = values.indices.map { emaFast[it].subtract(emaSlow[it]) }
        val signal = ema(diff, MACD_SIGNAL)
        return diff.indices.map { diff[it].subtract(signal[it]) }
    }

    private fun adx(klines: List<Kline>, period: Int = ADX_PERIOD): List<BigDecimal> {
        val n = klines.size
        val out = MutableList(n) { bd(0.0) }
        if (n <= period) return out

        val tr = MutableList(n) { bd(0.0) }
        val plusDM = MutableList(n) { bd(0.0) }
        val minusDM = MutableList(n) { bd(0.0) }

        for (i in 1 until n) {
            val up = klines[i].high.subtract(klines[i - 1].high)
            val down = klines[i - 1].low.subtract(klines[i].low)
            plusDM[i] = if (up > down && up > bd(0.0)) up else bd(0.0)
            minusDM[i] = if (down > up && down > bd(0.0)) down else bd(0.0)
            val tr1 = klines[i].high.subtract(klines[i].low).abs()
            val tr2 = (klines[i].high.subtract(klines[i - 1].close)).abs()
            val tr3 = (klines[i - 1].close.subtract(klines[i].low)).abs()
            tr[i] = listOf(tr1, tr2, tr3).maxOrNull() ?: bd(0.0)
        }

        var smTR = tr.subList(1, period + 1).fold(bd(0.0)) { acc, v -> acc.add(v) }
        var smPlus = plusDM.subList(1, period + 1).fold(bd(0.0)) { acc, v -> acc.add(v) }
        var smMinus = minusDM.subList(1, period + 1).fold(bd(0.0)) { acc, v -> acc.add(v) }

        for (i in period + 1 until n) {
            smTR = smTR.subtract(smTR.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)).add(tr[i])
            smPlus = smPlus.subtract(smPlus.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP))
                .add(plusDM[i])
            smMinus = smMinus.subtract(smMinus.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP))
                .add(minusDM[i])

            val plus = if (smTR.compareTo(bd(0.0)) == 0) bd(0.0) else smPlus.divide(
                smTR,
                BIGDECIMAL_SCALE,
                RoundingMode.HALF_UP
            ).multiply(bd(100.0))
            val minus = if (smTR.compareTo(bd(0.0)) == 0) bd(0.0) else smMinus.divide(
                smTR,
                BIGDECIMAL_SCALE,
                RoundingMode.HALF_UP
            ).multiply(bd(100.0))
            val dx = if (plus.add(minus).compareTo(bd(0.0)) == 0) bd(0.0)
            else (plus.subtract(minus).abs().divide(plus.add(minus), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)).multiply(
                bd(100.0)
            )

            if (i == period + 1) {
                out[i] = dx
            } else {
                out[i] = (out[i - 1].multiply(bd(period.toDouble() - 1)).add(dx)).divide(
                    bd(period.toDouble()),
                    BIGDECIMAL_SCALE,
                    RoundingMode.HALF_UP
                )
            }
        }
        return out
    }

    // 主要回测函数 - 支持不同时间框架
    fun backtestYear(
        symbol: String,
        entryTimeframe: String, // 开仓时间框架: "1H" 或 "4H"
        year: Int,
        klinesAll: List<Kline>
    ): Pair<YearSummary, List<List<TradeRecord>>> {
        val startMs = LocalDateTime.of(year, 1, 1, 0, 0).atZone(ZONE).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59).atZone(ZONE).toInstant().toEpochMilli()

        // 根据开仓时间框架加载数据
        val klEntry = loadKlines(symbol, entryTimeframe).filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val kl4h = loadKlines(symbol, "4H").filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val kl1h = loadKlines(symbol, "1H").filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val kl15 = loadKlines(symbol, "15m").filter { it.ts in startMs..endMs }.sortedBy { it.ts }

        val summary = YearSummary(year, INITIAL_TOTAL_CAPITAL, INITIAL_TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0, 0, 0)
        if (klEntry.size < 50) return summary to emptyList()

        // 计算指标
        val closeEntry = klEntry.map { it.close }
        val ema7_entry = ema(closeEntry, EMA_SHORT)
        val ema21_entry = ema(closeEntry, EMA_LONG)
        val rsiEntry = rsi(closeEntry, RSI_PERIOD)
        val volSmaEntry = sma(klEntry.map { it.vol }, 20)

        val close4h = kl4h.map { it.close }
        val ema7_4h = ema(close4h, EMA_SHORT)
        val ema21_4h = ema(close4h, EMA_LONG)
        val macdHist4h = macdHistogram(close4h)

        val close15 = kl15.map { it.close }
        val ema7_15 = ema(close15, EMA_SHORT)
        val ema21_15 = ema(close15, EMA_LONG)

        val adxEntry = adx(klEntry, ADX_PERIOD)

        val allSequences = mutableListOf<List<TradeRecord>>()
        var currentSeq: MutableList<TradeRecord>? = null

        var equity = INITIAL_TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        val position = Position()
        var stopPrice: BigDecimal? = null
        var lastTradeDay = -1
        var dailyLoss = bd(0.0)

        // 迭代开仓时间框架的K线
        for (i in 0 until klEntry.size) {
            val bar = klEntry[i]
            val price = bar.close
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(bar.ts), ZONE).format(DATE_FORMATTER)

            // 重置日亏损
            val day = LocalDateTime.ofInstant(Instant.ofEpochMilli(bar.ts), ZONE).dayOfYear
            if (day != lastTradeDay) {
                dailyLoss = bd(0.0)
                lastTradeDay = day
            }

            // 计算未实现盈亏（基于总投入资金）
            val unreal = if (position.size > bd(0.0)) {
                if (position.direction == "long")
                    price.subtract(position.entryPrice).multiply(position.size).multiply(CONTRACT_SIZE)
                else
                    position.entryPrice.subtract(price).multiply(position.size).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            val unrealPct = if (position.totalInvested > bd(0.0))
                unreal.divide(position.totalInvested, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
            else bd(0.0)

            // 更新最高浮盈
            if (unreal > position.highestProfit) {
                position.highestProfit = unreal
            }

            // 最大回撤止损
            if (position.highestProfit > bd(0.0) && unreal < bd(0.0)) {
                val drawdown = position.highestProfit.subtract(unreal).divide(position.highestProfit, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                if (drawdown > MAX_DRAWDOWN_STOP) {
                    equity = equity.add(unreal)
                    usedMargin = bd(0.0)
                    currentSeq?.add(TradeRecord(dt, "DRAWDOWN STOP", position.layers, position.direction, price, position.size, unreal))
                    summary.stops++
                    if (unreal < bd(0.0)) dailyLoss = dailyLoss.add(unreal.abs())
                    resetPosition(position)
                    currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                    continue
                }
            }

            // 保证金和爆仓检查
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (position.size > bd(0.0) && marginBalance <= bd(0.0)) {
                currentSeq?.add(TradeRecord(dt, "LIQUIDATION", position.layers, position.direction, price, position.size, unreal))
                summary.liquidations++
                resetPosition(position)
                usedMargin = bd(0.0)
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 开仓逻辑（无持仓时）
            if (position.layers == 0) {
                // 4H趋势判断
                val idx4h = kl4h.indexOfLast { it.ts <= bar.ts }
                val trendBull = if (idx4h >= 0) ema7_4h[idx4h] > ema21_4h[idx4h] else false
                val trendBear = if (idx4h >= 0) ema7_4h[idx4h] < ema21_4h[idx4h] else false
                val macdPos = if (idx4h >= 0) macdHist4h[idx4h] > bd(0.0) else false

                // 入场信号（放宽条件）
                var longSignal = false
                var shortSignal = false

                if (i >= 1) {
                    val prevPrice = klEntry[i - 1].close

                    // long信号 - 放宽条件
                    if (trendBull && macdPos) {
                        val rsiPrev = rsiEntry.getOrElse(i - 1) { bd(50.0) }
                        val rsiCurr = rsiEntry.getOrElse(i) { bd(50.0) }
                        val touchedEma = price <= ema7_entry.getOrElse(i) { price }.max(ema21_entry.getOrElse(i) { price })
                        val volCurr = klEntry[i].vol
                        val volAvg = volSmaEntry.getOrElse(i) { bd(0.0) }
                        val volOk = if (volAvg.compareTo(bd(0.0)) > 0) volCurr >= volAvg.multiply(bd(1.2)) else true

                        if (touchedEma && rsiPrev < bd(50.0) && rsiCurr > bd(45.0) && volOk) longSignal = true
                    }

                    // short信号 - 放宽条件
                    if (trendBear && !macdPos) {
                        val rsiPrev = rsiEntry.getOrElse(i - 1) { bd(50.0) }
                        val rsiCurr = rsiEntry.getOrElse(i) { bd(50.0) }
                        val touchedEma = price >= ema7_entry.getOrElse(i) { price }.min(ema21_entry.getOrElse(i) { price })
                        val volCurr = klEntry[i].vol
                        val volAvg = volSmaEntry.getOrElse(i) { bd(0.0) }
                        val volOk = if (volAvg.compareTo(bd(0.0)) > 0) volCurr >= volAvg.multiply(bd(1.2)) else true

                        if (touchedEma && rsiPrev > bd(50.0) && rsiCurr < bd(55.0) && volOk) shortSignal = true
                    }
                }

                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    val posValue = equity.multiply(INITIAL_POS_PCT)
                    val margin = posValue.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                    if (equity.subtract(usedMargin) >= margin) {
                        val size = posValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        position.size = size
                        position.entryPrice = price
                        position.direction = dir
                        position.layers = 1
                        position.totalInvested = posValue
                        position.initialSize = size
                        position.highestProfit = bd(0.0)
                        position.totalValue = posValue

                        usedMargin = usedMargin.add(margin)

                        // 首仓止损
                        stopPrice = if (dir == "long")
                            position.entryPrice.multiply(BigDecimal.ONE.subtract(FIRST_LAYER_SL_PCT))
                        else
                            position.entryPrice.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT))

                        summary.opens++
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", position.layers, position.direction, price, position.size, unreal))
                    }
                }
                continue
            }

            // 确保交易序列存在
            if (position.layers > 0 && currentSeq == null) {
                currentSeq = mutableListOf()
                currentSeq.add(TradeRecord(dt, "OPEN(RECOVERED)", position.layers, position.direction, position.entryPrice, position.size, unreal))
            }

            // 移动止损机制
            if (unrealPct.compareTo(bd(0.20)) >= 0) {
                // 浮盈20%后：止损设置在盈利15%位置
                stopPrice = if (position.direction == "long")
                    position.entryPrice.multiply(BigDecimal.ONE.add(bd(0.15)))
                else
                    position.entryPrice.multiply(BigDecimal.ONE.subtract(bd(0.15)))
            } else if (unrealPct.compareTo(bd(0.35)) >= 0) {
                // 浮盈35%后：每上涨5%，止损上调3%
                val steps = unrealPct.divide(bd(0.05), 0, RoundingMode.DOWN)
                val shift = steps.multiply(bd(0.03))
                stopPrice = if (position.direction == "long")
                    position.entryPrice.multiply(BigDecimal.ONE.add(shift))
                else
                    position.entryPrice.multiply(BigDecimal.ONE.subtract(shift))
            }

            // 止损触发检测
            val triggerStop = if (position.direction == "long") {
                stopPrice != null && price <= stopPrice!!
            } else {
                stopPrice != null && price >= stopPrice!!
            }

            if (triggerStop) {
                equity = equity.add(unreal)
                val marginReleased = position.totalInvested.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                currentSeq?.add(TradeRecord(dt, "STOP LOSS", position.layers, position.direction, price, position.size, unreal))
                summary.stops++
                if (unreal < bd(0.0)) dailyLoss = dailyLoss.add(unreal.abs())
                resetPosition(position)
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 盈利加仓
            var addedThisBar = false
            if (position.layers >= 1 && position.layers <= ADD_THRESHOLDS.size) {
                val addIndex = position.layers - 1
                val threshold = ADD_THRESHOLDS[addIndex]

                if (unrealPct >= threshold) {
                    val multiplier = ADD_MULTIPLIERS[addIndex]
                    val addValue = equity.multiply(INITIAL_POS_PCT).multiply(multiplier)
                    val addMargin = addValue.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                    if (equity.subtract(usedMargin) >= addMargin) {
                        val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        // 计算新的平均开仓价
                        val oldValue = position.entryPrice.multiply(position.size).multiply(CONTRACT_SIZE)
                        val addValueActual = price.multiply(addSize).multiply(CONTRACT_SIZE)
                        val totalValue = oldValue.add(addValueActual)
                        val totalSize = position.size.add(addSize)
                        val newEntryPrice = totalValue.divide(totalSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        position.size = totalSize
                        position.entryPrice = newEntryPrice
                        position.layers += 1
                        position.totalInvested = position.totalInvested.add(addValue)
                        usedMargin = usedMargin.add(addMargin)

                        // 加仓后立即调整止损位
                        val stopProfitPct = STOP_LOSS_AFTER_ADD[addIndex]
                        stopPrice = if (position.direction == "long")
                            position.entryPrice.multiply(BigDecimal.ONE.add(stopProfitPct))
                        else
                            position.entryPrice.multiply(BigDecimal.ONE.subtract(stopProfitPct))

                        summary.adds++
                        currentSeq?.add(TradeRecord(dt, "ADD", position.layers, position.direction, price, addSize, unreal))
                        addedThisBar = true
                    }
                }
            }

            // 智能止盈系统（修正版）
            val adxNow = adxEntry.getOrElse(i) { bd(0.0) }
            if (!addedThisBar && position.layers >= 1 && unrealPct > bd(0.0)) {
                when {
                    adxNow > bd(40.0) -> {
                        // 强趋势：盈利60%后平仓20%
                        if (unrealPct >= bd(0.60)) {
                            closePartialPosition(position, price, equity, usedMargin, currentSeq, dt, summary, 0.2)
                        }
                    }
                    adxNow >= bd(25.0) -> {
                        // 中等趋势：盈利40%后平仓30%
                        if (unrealPct >= bd(0.40)) {
                            closePartialPosition(position, price, equity, usedMargin, currentSeq, dt, summary, 0.3)
                        }
                    }
                    else -> {
                        // 弱趋势：盈利25%后平仓50%
                        if (unrealPct >= bd(0.25)) {
                            closePartialPosition(position, price, equity, usedMargin, currentSeq, dt, summary, 0.5)
                        }
                    }
                }
            }

            // 趋势反转全平
            if (position.layers > 0) {
                val idx4h = kl4h.indexOfLast { it.ts <= bar.ts }
                val trendBull = if (idx4h >= 0) ema7_4h[idx4h] > ema21_4h[idx4h] else false
                val trendBear = if (idx4h >= 0) ema7_4h[idx4h] < ema21_4h[idx4h] else false

                val trendReversal = (position.direction == "long" && trendBear) || (position.direction == "short" && trendBull)
                if (trendReversal && unrealPct >= bd(0.10)) {
                    equity = equity.add(unreal)
                    val marginReleased = position.totalInvested.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    currentSeq?.add(TradeRecord(dt, "TREND REVERSAL TP", position.layers, position.direction, price, position.size, unreal))
                    summary.fullTps++
                    resetPosition(position)
                    currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                    continue
                }
            }

            // 单日最大亏损保护
            if (dailyLoss.compareTo(equity.multiply(DAILY_MAX_LOSS_PCT)) > 0) {
                if (position.layers > 0) {
                    equity = equity.add(unreal)
                    val marginReleased = position.totalInvested.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    currentSeq?.add(TradeRecord(dt, "DAILY STOP", position.layers, position.direction, price, position.size, unreal))
                    summary.stops++
                    resetPosition(position)
                    currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                    continue
                }
            }
        }

        // 年末结算
        val finalUnreal = if (position.layers > 0) {
            val lastPrice = klEntry.last().close
            if (position.direction == "long")
                lastPrice.subtract(position.entryPrice).multiply(position.size).multiply(CONTRACT_SIZE)
            else
                position.entryPrice.subtract(lastPrice).multiply(position.size).multiply(CONTRACT_SIZE)
        } else bd(0.0)

        summary.endCapital = maxOf(equity.add(finalUnreal), bd(0.0))
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0)
        else (summary.endCapital.subtract(summary.startCapital).divide(summary.startCapital, 6, RoundingMode.HALF_UP)
            .multiply(bd(100.0)))

        currentSeq?.let { allSequences.add(it.toList()) }
        return summary to allSequences
    }

    private fun resetPosition(position: Position) {
        position.size = bd(0.0)
        position.entryPrice = bd(0.0)
        position.direction = ""
        position.layers = 0
        position.totalInvested = bd(0.0)
        position.initialSize = bd(0.0)
        position.highestProfit = bd(0.0)
        position.totalValue = bd(0.0)
    }

    private fun closePartialPosition(
        position: Position,
        price: BigDecimal,
        equity: BigDecimal,
        usedMargin: BigDecimal,
        currentSeq: MutableList<TradeRecord>?,
        dt: String,
        summary: YearSummary,
        closeRatio: Double
    ) {
        val partialSize = position.size.multiply(bd(closeRatio)).setScale(BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        val realized = if (position.direction == "long")
            price.subtract(position.entryPrice).multiply(partialSize).multiply(CONTRACT_SIZE)
        else
            position.entryPrice.subtract(price).multiply(partialSize).multiply(CONTRACT_SIZE)

        // 更新权益和保证金
        val newEquity = equity.add(realized)
        val marginReleased = position.entryPrice.multiply(partialSize).multiply(CONTRACT_SIZE)
            .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        val newUsedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))

        // 更新仓位
        position.size = position.size.subtract(partialSize)
        position.totalInvested = position.totalInvested.multiply(BigDecimal.ONE.subtract(bd(closeRatio)))

        currentSeq?.add(TradeRecord(dt, "PARTIAL TP", position.layers, position.direction, price, partialSize, realized))
        summary.partialTps++
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val symbols = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")
        val timeframes = listOf("1H", "4H") // 分别测试1H和4H开仓

        for (symbol in symbols) {
            for (timeframe in timeframes) {
                println("\n=== 测试 $symbol - $timeframe 开仓 ===")
                val allKlines = loadKlines(symbol, timeframe)
                if (allKlines.isEmpty()) {
                    println("[$symbol-$timeframe] 未找到数据，跳过")
                    continue
                }
                val years = allKlines.map { Instant.ofEpochMilli(it.ts).atZone(ZONE).year }.distinct().sorted()
                for (y in years) {
                    val (sum, sequences) = backtestYear(symbol, timeframe, y, allKlines)
                    println(
                        "【$symbol-$timeframe】 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${
                            sum.roiPct.toStr(
                                4
                            )
                        }%，开仓 ${sum.opens}，加仓 ${sum.adds}，止损 ${sum.stops}，部分止盈 ${sum.partialTps}，整仓止盈 ${sum.fullTps}，爆仓 ${sum.liquidations}"
                    )
                    if (sequences.isNotEmpty() && sequences.size <= 3) {
                        sequences.forEachIndexed { index, sequence ->
                            println("=== 交易序列 ${index + 1}/${sequences.size} ===")
                            sequence.forEach {
                                println(
                                    "[${it.dt}] ${it.type} L${it.layer} ${it.dir} @${it.price.toStr(4)} size=${
                                        it.size.toStr(
                                            6
                                        )
                                    } unreal=${it.unreal.toStr(2)}"
                                )
                            }
                            println("=== END ===")
                        }
                    } else if (sequences.isNotEmpty()) {
                        val idx = Random.nextInt(sequences.size)
                        val sample = sequences[idx]
                        println("=== 抽样完整交易序列 ${idx + 1}/${sequences.size} ===")
                        sample.forEach {
                            println(
                                "[${it.dt}] ${it.type} L${it.layer} ${it.dir} @${it.price.toStr(4)} size=${
                                    it.size.toStr(
                                        6
                                    )
                                } unreal=${it.unreal.toStr(2)}"
                            )
                        }
                        println("=== END ===")
                    }
                }
            }
        }
    }
}