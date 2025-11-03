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

    // 仓位/金字塔规则（文档指定）
    private val INITIAL_POS_PCT = bd(0.40) // 首仓占总资金的40%
    private val ADD_THRESHOLDS = listOf(bd(0.015), bd(0.03), bd(0.045), bd(0.06), bd(0.09), bd(0.12)) // 累计盈利阈值
    private val ADD_MULTIPLIERS = listOf(bd(0.8), bd(0.6), bd(0.5), bd(0.4), bd(0.2), bd(0.1)) // 对首仓的加仓比例
    private val MAX_LAYERS = 1 + ADD_MULTIPLIERS.size

    // 止损 / 止盈规则
    private val FIRST_LAYER_SL_PCT = bd(-0.03) // 首仓固定止损 -3%（long 下跌 3% 触发，short 上涨 3% 触发）
    private val DAILY_MAX_LOSS_PCT = bd(0.07) // 单日最大亏损 7%（示意）
    private val SINGLE_TRADE_MAX_RISK_PCT = bd(0.04) // 单笔最大亏损 4%
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

    // --- indicator helpers (return lists aligned with klines) ---
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

    // ADX (Wilder) 简化实现，返回 ADX 列
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

        // Wilder smoothing
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

            // ADX smoothing: first ADX value is avg of DXs - here we approximate by incremental Wilder smoothing
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

    // --- main backtest per year (类似你原有结构) ---
    fun backtestYear(
        symbol: String,
        timeframe1H: String,
        timeframe4H: String,
        timeframe15m: String,
        year: Int,
        klinesAll: List<Kline>
    ): Pair<YearSummary, List<List<TradeRecord>>> {
        val startMs = LocalDateTime.of(year, 1, 1, 0, 0).atZone(ZONE).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59).atZone(ZONE).toInstant().toEpochMilli()
        val klines = klinesAll.filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val summary = YearSummary(year, INITIAL_TOTAL_CAPITAL, INITIAL_TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0, 0, 0)
        if (klines.size < 50) return summary to emptyList()

        // 为不同 timeframe 获取指标：假设 loadKlines 可以以不同 tf 加载（你项目里有该函数）
        // 这里加载 4H、1H、15M 的 klines（若数据不可得则直接用传入的 klines 做 degrade）
        val kl4h = loadKlines(symbol, timeframe4H).filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val kl1h = loadKlines(symbol, timeframe1H).filter { it.ts in startMs..endMs }.sortedBy { it.ts }
        val kl15 = loadKlines(symbol, timeframe15m).filter { it.ts in startMs..endMs }.sortedBy { it.ts }

        // 4H indicators (trend)
        val close4h = kl4h.map { it.close }
        val ema7_4h = ema(close4h, EMA_SHORT)
        val ema21_4h = ema(close4h, EMA_LONG)
        val macdHist4h = macdHistogram(close4h)
        val volSma4h = sma(kl4h.map { it.vol }, 10)

        // 1H indicators (entry)
        val close1h = kl1h.map { it.close }
        val ema7_1h = ema(close1h, EMA_SHORT)
        val ema21_1h = ema(close1h, EMA_LONG)
        val rsi1h = rsi(close1h, RSI_PERIOD)
        val volSma1h = sma(kl1h.map { it.vol }, 20)

        // 15m indicators (fine entry)
        val close15 = kl15.map { it.close }
        val ema7_15 = ema(close15, EMA_SHORT)
        val ema21_15 = ema(close15, EMA_LONG)

        // ADX on 1H (用于止盈决策)
        val adx1h = adx(kl1h, ADX_PERIOD)

        val allSequences = mutableListOf<List<TradeRecord>>()
        var currentSeq: MutableList<TradeRecord>? = null

        var equity = INITIAL_TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0
        var posDir = "" // "long" / "short"
        var stopPrice: BigDecimal? = null
        var entryBarIndex1h = -1
        var lastTradeDay = -1
        var dailyLoss = bd(0.0)

        // iterate over 1H bars (主循环以 1H 为粒度，因为规则以 4H/1H/15m 三重共振)
        for (i in 0 until kl1h.size) {
            val bar = kl1h[i]
            val price = bar.close
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(bar.ts), ZONE).format(DATE_FORMATTER)
            // reset daily loss at day boundary
            val day = LocalDateTime.ofInstant(Instant.ofEpochMilli(bar.ts), ZONE).dayOfYear
            if (day != lastTradeDay) {
                dailyLoss = bd(0.0)
                lastTradeDay = day
            }

            // compute 4H trend corresponding to this 1H time: find nearest 4H bar with ts <= current
            val idx4h = kl4h.indexOfLast { it.ts <= bar.ts }
            val trendBull = if (idx4h >= 0) ema7_4h[idx4h] > ema21_4h[idx4h] else false
            val trendBear = if (idx4h >= 0) ema7_4h[idx4h] < ema21_4h[idx4h] else false
            val macdPos = if (idx4h >= 0) macdHist4h[idx4h] > bd(0.0) else true
            val volOk4h =
                if (idx4h >= 0) kl4h[idx4h].vol >= volSma4h.getOrElse(idx4h) { bd(0.0) }.multiply(bd(1.0)) else true

            // 15m fine entry: find 15m bar index <= current
            val idx15 = kl15.indexOfLast { it.ts <= bar.ts }
            val nearSupport15 = if (idx15 >= 2) {
                // 参考：15m EMA7/21 距离当前价近于某阈 => 表示接近支撑/阻力
                val d7 = price.subtract(ema7_15[idx15]).abs()
                val d21 = price.subtract(ema21_15[idx15]).abs()
                d7 <= price.multiply(bd(0.005)) || d21 <= price.multiply(bd(0.006)) // 0.5%~0.6% 阈值
            } else true

            // entry logic: 需要 4H 三重共振 + 1H 回踩/RSI 条件 + 15m 靠近支撑/阻力
            var longSignal = false
            var shortSignal = false
            if (i >= 1) {
                val prevPrice = kl1h[i - 1].close
                // long: 4H 多头, MACD hist 正, 1H 回踩至 EMA7/21 且 RSI 从 <45 回升到 >50，成交量放大
                if (trendBull && macdPos && volOk4h) {
                    val rsiPrev = rsi1h.getOrElse(i - 1) { bd(50.0) }
                    val rsiCurr = rsi1h.getOrElse(i) { bd(50.0) }
                    val touchedEma = price <= ema7_1h.getOrElse(i) { price }.max(ema21_1h.getOrElse(i) { price })
                    val volCurr = kl1h[i].vol
                    val volAvg = volSma1h.getOrElse(i) { bd(0.0) }
                    val volOk = if (volAvg.compareTo(bd(0.0)) > 0) volCurr >= volAvg.multiply(bd(1.3)) else true
                    if (touchedEma && rsiPrev < bd(45.0) && rsiCurr > bd(50.0) && volOk && nearSupport15) longSignal =
                        true
                }
                // short: 4H 空头, MACD hist 负, 1H 反弹至 EMA7/21 且 RSI 从 >55 回落到 <50，成交量放大
                if (trendBear && !macdPos && volOk4h) {
                    val rsiPrev = rsi1h.getOrElse(i - 1) { bd(50.0) }
                    val rsiCurr = rsi1h.getOrElse(i) { bd(50.0) }
                    val touchedEma = price >= ema7_1h.getOrElse(i) { price }.min(ema21_1h.getOrElse(i) { price })
                    val volCurr = kl1h[i].vol
                    val volAvg = volSma1h.getOrElse(i) { bd(0.0) }
                    val volOk = if (volAvg.compareTo(bd(0.0)) > 0) volCurr >= volAvg.multiply(bd(1.3)) else true
                    if (touchedEma && rsiPrev > bd(55.0) && rsiCurr < bd(50.0) && volOk && nearSupport15) shortSignal =
                        true
                }
            }

            // compute unrealized P&L
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            // margin balance and liquidation check
            val denomRaw = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
            val denom = if (denomRaw.compareTo(bd(0.0)) <= 0) bd(1e-8) else denomRaw
            val unrealPct = if (posLayer > 0) unreal.divide(denom, BIGDECIMAL_SCALE, RoundingMode.HALF_UP) else bd(0.0)
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (posLayer > 0 && marginBalance <= bd(0.0)) {
                currentSeq?.add(TradeRecord(dt, "LIQUIDATION", posLayer, posDir, price, posSize, unreal))
                summary.liquidations++
                // clear
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 开仓逻辑（只有当没有持仓）
            if (posLayer == 0) {
                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    // 首仓资金基于当前 equity 的百分比
                    val posValue = equity.multiply(INITIAL_POS_PCT)
                    val margin = posValue.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= margin) {
                        val size = posValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        posSize = size; posEntry = price; posLayer = 1; posDir = dir
                        usedMargin = usedMargin.add(margin)
                        // 首仓止损（方向感知）
                        stopPrice =
                            if (dir == "long") posEntry.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT)) else posEntry.multiply(
                                BigDecimal.ONE.subtract(FIRST_LAYER_SL_PCT)
                            )
                        summary.opens++
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", posLayer, posDir, price, posSize, unreal))
                        entryBarIndex1h = i
                    }
                }
                continue
            }

            // ensure sequence exists
            if (posLayer > 0 && currentSeq == null) {
                currentSeq = mutableListOf()
                currentSeq.add(TradeRecord(dt, "OPEN(RECOVERED)", posLayer, posDir, posEntry, posSize, unreal))
            }

            // 更新移动止损规则（浮盈触发）
            val floatPct = unrealPct // relative to entry*size
            if (floatPct.compareTo(bd(0.15)) >= 0) {
                // 浮盈 15% 后将止损移到盈利10%位置（文档）
                stopPrice =
                    if (posDir == "long") posEntry.multiply(BigDecimal.ONE.add(bd(0.10))) else posEntry.multiply(
                        BigDecimal.ONE.subtract(bd(0.10))
                    )
            } else if (floatPct.compareTo(bd(0.25)) >= 0) {
                // 浮盈 25% 后每上涨4%上调2.5%（针对后续）
                val steps = floatPct.divide(bd(0.04), 0, RoundingMode.DOWN)
                val shift = steps.multiply(bd(0.025))
                stopPrice = if (posDir == "long") posEntry.multiply(BigDecimal.ONE.add(shift)) else posEntry.multiply(
                    BigDecimal.ONE.subtract(shift)
                )
            }

            // 止损触发检测（方向敏感）
            val triggerStop = if (posDir == "long") {
                stopPrice != null && price <= stopPrice
            } else {
                stopPrice != null && price >= stopPrice
            }
            if (triggerStop) {
                val realized = unreal
                equity = equity.add(realized)
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                currentSeq?.add(TradeRecord(dt, "STOP LOSS", posLayer, posDir, price, posSize, unreal))
                summary.stops++
                // 日亏损统计
                if (realized < bd(0.0)) dailyLoss = dailyLoss.add(realized.abs())
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 盈利加仓（按累计盈利阈值），这里用 unrealPct 与阈值比较（累计盈利相当于以 entry 参考）
            var addedThisBar = false
            if (posLayer >= 1 && posLayer < MAX_LAYERS) {
                val addIndex = posLayer - 1
                val threshold = ADD_THRESHOLDS.getOrElse(addIndex) { ADD_THRESHOLDS.last() }
                if (unrealPct >= threshold) {
                    val multiplier = ADD_MULTIPLIERS.getOrElse(addIndex) { ADD_MULTIPLIERS.last() }
                    val addValue = INITIAL_POS_PCT // 首仓资金是个比例，实际首仓 value = equity * INITIAL_POS_PCT
                        .let { equity.multiply(it).multiply(multiplier) } // addValue = 首仓资金 * multiplier
                    val addMargin = addValue.divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= addMargin) {
                        val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        // 合并头寸并计算新的平均开仓价
                        val oldVal = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        val newVal = price.multiply(addSize).multiply(CONTRACT_SIZE)
                        val combinedVal = oldVal.add(newVal)
                        val combinedSize = posSize.add(addSize)
                        val newEntry = if (combinedSize.compareTo(bd(0.0)) == 0) bd(0.0) else combinedVal.divide(
                            combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP
                        )
                        posSize = combinedSize
                        posEntry = newEntry
                        posLayer += 1
                        usedMargin = usedMargin.add(addMargin)
                        summary.adds++
                        currentSeq?.add(TradeRecord(dt, "ADD", posLayer, posDir, price, addSize, unreal))
                        addedThisBar = true
                    }
                }
            }

            // 智能止盈：基于 ADX 在 1H 上的强弱做不同处理
            val adxNow = adx1h.getOrElse(i) { bd(0.0) }
            if (!addedThisBar && posLayer >= 1) {
                if (adxNow.compareTo(bd(40.0)) > 0) {
                    // 强趋势：不主动平仓，仅移动止损（已经在移动止损逻辑里实现）
                } else if (adxNow.compareTo(bd(25.0)) >= 0) {
                    // 中等趋势：到达整体盈利阈值（例如 5%）后部分平仓（平掉 30%）
                    if (unrealPct >= bd(0.05)) {
                        // 平掉 30%
                        val partialSize = posSize.multiply(bd(0.3)).setScale(BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        val realized =
                            if (posDir == "long") price.subtract(posEntry).multiply(partialSize).multiply(CONTRACT_SIZE)
                            else posEntry.subtract(price).multiply(partialSize).multiply(CONTRACT_SIZE)
                        equity = equity.add(realized)
                        val marginReleased = posEntry.multiply(partialSize).multiply(CONTRACT_SIZE)
                            .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                        posSize = posSize.subtract(partialSize)
                        currentSeq?.add(TradeRecord(dt, "PARTIAL TP", posLayer, posDir, price, partialSize, unreal))
                        summary.partialTps++
                    }
                } else {
                    // ADX < 25：震荡，保守：达到 3% 时先平 50%
                    if (unrealPct >= bd(0.03)) {
                        val partialSize = posSize.multiply(bd(0.5)).setScale(BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        val realized =
                            if (posDir == "long") price.subtract(posEntry).multiply(partialSize).multiply(CONTRACT_SIZE)
                            else posEntry.subtract(price).multiply(partialSize).multiply(CONTRACT_SIZE)
                        equity = equity.add(realized)
                        val marginReleased = posEntry.multiply(partialSize).multiply(CONTRACT_SIZE)
                            .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                        posSize = posSize.subtract(partialSize)
                        currentSeq?.add(TradeRecord(dt, "PARTIAL TP", posLayer, posDir, price, partialSize, unreal))
                        summary.partialTps++
                    }
                }
            }

            // 全仓止盈：如果未在本 bar 加仓且 unrealPct 达到较高阈值（例如 8%），则全部平仓
            if (!addedThisBar && posLayer >= 1 && unrealPct >= bd(0.08)) {
                val realized = unreal
                equity = equity.add(realized)
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                currentSeq?.add(TradeRecord(dt, "FULL TP", posLayer, posDir, price, posSize, unreal))
                summary.fullTps++
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 单笔最大亏损 / 日亏损保护（如果当天亏损超过阈值，强制清仓）
            if (dailyLoss.compareTo(equity.multiply(DAILY_MAX_LOSS_PCT)) > 0) {
                // 当前日亏损超过阈值 -> 清仓并停止当日新增
                if (posLayer > 0) {
                    val realized = unreal
                    equity = equity.add(realized)
                    val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                        .divide(BASE_LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                    currentSeq?.add(TradeRecord(dt, "DAILY STOP", posLayer, posDir, price, posSize, unreal))
                    summary.stops++
                    posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                    currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                    continue
                }
            }
        } // end for each 1H bar

        // year-end unrealized settlement
        val finalUnreal = if (posLayer > 0) {
            val lastPrice = kl1h.last().close
            if (posDir == "long") lastPrice.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
            else posEntry.subtract(lastPrice).multiply(posSize).multiply(CONTRACT_SIZE)
        } else bd(0.0)
        summary.endCapital = maxOf(equity.add(finalUnreal), bd(0.0))
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0)
        else (summary.endCapital.subtract(summary.startCapital).divide(summary.startCapital, 6, RoundingMode.HALF_UP)
            .multiply(bd(100.0)))

        currentSeq?.let { allSequences.add(it.toList()) }
        return summary to allSequences
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val symbols = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")
        val tf1h = "1H"
        val tf4h = "4H"
        val tf15 = "15M"

        for (symbol in symbols) {
            val allKlines = loadKlines(symbol, tf1h) // baseline 1H data; loadKlines 可返回指定 tf 数据
            if (allKlines.isEmpty()) {
                println("[$symbol] 未找到 1H 数据，跳过")
                continue
            }
            val years = allKlines.map { Instant.ofEpochMilli(it.ts).atZone(ZONE).year }.distinct().sorted()
            for (y in years) {
                val (sum, sequences) = backtestYear(symbol, tf1h, tf4h, tf15, y, allKlines)
                println(
                    "【$symbol】 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${
                        sum.roiPct.toStr(
                            4
                        )
                    }%，开仓 ${sum.opens}，加仓 ${sum.adds}，止损 ${sum.stops}，部分止盈 ${sum.partialTps}，整仓止盈 ${sum.fullTps}，爆仓 ${sum.liquidations}"
                )
                if (sequences.isNotEmpty()) {
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
                } else {
                    println("（${y} 年无完整交易序列可抽样）")
                }
            }
        }
    }
}
