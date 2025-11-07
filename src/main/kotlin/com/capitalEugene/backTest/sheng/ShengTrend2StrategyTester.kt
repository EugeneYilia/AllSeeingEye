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

object ShengDailyEMABacktest {

    private val TOTAL_CAPITAL = bd(1000.0)       // 仅作初始显示/对比
    private val LEVERAGE = bd(3.0)
    private val CONTRACT_SIZE = bd(1.0)

    // 加仓倍数（基于初始开仓名义）
    private val ADD_EMA7_52_MULT = bd(0.8)
    private val ADD_EMA21_52_MULT = bd(0.6)
    private val ADD_UP_2PCT_MULT = bd(0.6)
    private val ADD_UP_5PCT_MULT = bd(0.4)

    private val MAX_ADDS = 4

    // 每笔开仓时按“当时总资金的比例”计算止损阈值：0.05 => 5%
    private val STOP_TOTAL_PCT = bd(0.05)

    // 维护保证金率（用于计算 maintenance = notional * rate）
    private val MAINTENANCE_MARGIN_RATE = bd(0.005)

    data class YearSummary(
        val year: Int,
        var startCapital: BigDecimal,
        var endCapital: BigDecimal,
        var roiPct: BigDecimal,
        var opens: Int,
        var adds: Int,
        var stops: Int,
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

    // EMA 计算
    private fun computeEMA(closePrices: List<BigDecimal>, period: Int): List<BigDecimal?> {
        val n = closePrices.size
        val res = MutableList<BigDecimal?>(n) { null }
        if (n < period) return res

        var sma = bd(0.0)
        for (i in 0 until period) sma = sma.add(closePrices[i])
        sma = sma.divide(bd(period.toDouble()), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        res[period - 1] = sma

        val alpha = bd(2.0).divide(bd(period + 1.0), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
        var prev = sma
        for (i in period until n) {
            val price = closePrices[i]
            val oneMinusAlpha = bd(1.0).subtract(alpha)
            val ema = price.multiply(alpha).add(prev.multiply(oneMinusAlpha))
            res[i] = ema
            prev = ema
        }
        return res
    }

    fun backtestYearSequences(
        symbol: String,
        timeframe: String,
        year: Int,
        klinesAll: List<Kline>
    ): Pair<YearSummary, List<List<TradeRecord>>> {
        val startMs = LocalDateTime.of(year, 1, 1, 0, 0).atZone(ZONE).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59).atZone(ZONE).toInstant().toEpochMilli()
        val klines = klinesAll.filter { it.ts in startMs..endMs }.sortedBy { it.ts }

        val summary = YearSummary(year, TOTAL_CAPITAL, TOTAL_CAPITAL, bd(0.0), 0, 0, 0, 0, 0)
        if (klines.size < 60) return summary to emptyList()

        val closes = klines.map { it.close }
        val ema7 = computeEMA(closes, 7)
        val ema21 = computeEMA(closes, 21)
        val ema52 = computeEMA(closes, 52)

        val allSequences = mutableListOf<List<TradeRecord>>()   // 每次完整交易序列
        var currentSeq: MutableList<TradeRecord>? = null

        // 账户状态
        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        // 持仓状态
        var positionSize = bd(0.0)       // 合约数量
        var positionEntry = bd(0.0)      // 持仓均价
        var positionLayers = 0
        var positionDirection = ""       // "long" 或 "short"
        var stopLossPrice = bd(0.0)      // price-based 参考（不再主用）

        // 每笔开仓时固定的“该笔止损绝对值”（基于当时 equity）
        var positionStopAbs = bd(0.0)

        // 交易状态
        var initialOpenNotional = bd(0.0)   // 初始开仓名义（notional），用于按比例加仓
        var addCount = 0

        var usedAddEma7x52 = false
        var usedAddEma21x52 = false
        var usedAddUp2 = false
        var usedAddUp5 = false

        fun resetAddFlags() {
            addCount = 0
            usedAddEma7x52 = false
            usedAddEma21x52 = false
            usedAddUp2 = false
            usedAddUp5 = false
        }

        // helper: 当前持仓名义（entry * size * contract）
        fun currentNotional(entry: BigDecimal, size: BigDecimal): BigDecimal {
            return entry.multiply(size).multiply(CONTRACT_SIZE)
        }

        // 尝试加仓（基于 initialOpenNotional）
        fun tryAddPosition(multiplier: BigDecimal, addType: String, price: BigDecimal, dateTime: String, unrealizedPnl: BigDecimal) {
            val availableEquity = equity.subtract(usedMargin).coerceAtLeast(bd(0.0))
            if (availableEquity <= bd(0.0)) return
            if (addCount >= MAX_ADDS) return

            val addValue = initialOpenNotional.multiply(multiplier) // 名义金额
            val addMargin = addValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
            if (availableEquity.compareTo(addMargin) < 0) return

            val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

            val oldVal = positionEntry.multiply(positionSize).multiply(CONTRACT_SIZE)
            val newVal = price.multiply(addSize).multiply(CONTRACT_SIZE)
            val combinedVal = oldVal.add(newVal)
            val combinedSize = positionSize.add(addSize)
            val newEntry = if (combinedSize.compareTo(bd(0.0)) == 0) bd(0.0)
            else combinedVal.divide(combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

            positionSize = combinedSize
            positionEntry = newEntry
            positionLayers++
            usedMargin = usedMargin.add(addMargin)
            summary.adds++
            addCount++
            currentSeq?.add(TradeRecord(dateTime, addType, positionLayers, positionDirection, price, addSize, unrealizedPnl))

            println("[${dateTime}] ADD ${addType} dir=${positionDirection} price=${price.toStr(4)} newEntry=${positionEntry.toStr(4)} size=${positionSize.toStr(6)} equity=${equity.toStr(2)} usedMargin=${usedMargin.toStr(4)}")
        }

        // 逐条 bar 回测
        for (i in 1 until klines.size) {
            val k = klines[i]
            val price = k.close
            val low = k.low
            val high = k.high
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(k.ts), ZONE).format(DATE_FORMATTER)

            val e7Prev = ema7.getOrNull(i - 1)
            val e21Prev = ema21.getOrNull(i - 1)
            val e52Prev = ema52.getOrNull(i - 1)
            val e7Now = ema7.getOrNull(i)
            val e21Now = ema21.getOrNull(i)
            val e52Now = ema52.getOrNull(i)

            // 未实现 pnl（方向敏感）
            val unrealizedPnl = if (positionLayers > 0) {
                if (positionDirection == "long") {
                    price.subtract(positionEntry).multiply(positionSize).multiply(CONTRACT_SIZE)
                } else {
                    positionEntry.subtract(price).multiply(positionSize).multiply(CONTRACT_SIZE)
                }
            } else bd(0.0)

            // ---------- 1) 总资金浮亏止损：按“该笔 positionStopAbs”判断并**限定实际结算损失不会超过阈值** ----------
            if (positionLayers > 0) {
                val unrealLossAbs = if (unrealizedPnl.compareTo(bd(0.0)) < 0) unrealizedPnl.abs() else bd(0.0)
                if (positionStopAbs > bd(0.0) && unrealLossAbs.compareTo(positionStopAbs) >= 0) {
                    // 当触发时，按用户意图“单次亏损为总资金的 5%”严格限制实际损失：
                    // realizedLoss = max( unrealizedPnl, -positionStopAbs )
                    val realized = if (unrealizedPnl.compareTo(positionStopAbs.negate()) < 0) {
                        // unrealized 更负（亏得更多），我们**按阈值（更小的损失）结算**以匹配“单次最多亏总资金的5%”原则
                        positionStopAbs.negate()
                    } else {
                        unrealizedPnl
                    }

                    val dirBefore = positionDirection
                    val sizeBefore = positionSize
                    val priceBefore = price

                    equity = equity.add(realized)

                    currentSeq?.add(TradeRecord(dt, "STOP LOSS (PER-TRADE ${STOP_TOTAL_PCT.multiply(bd(100.0)).toStr(0)}%)", positionLayers, dirBefore, priceBefore, sizeBefore, realized))
                    summary.stops++

                    println("[${dt}] STOP LOSS(dir=$dirBefore) price=${price.toStr(4)} realized=${realized.toStr(4)} equityAfter=${equity.toStr(4)} unrealLossAbs=${unrealLossAbs.toStr(4)} threshold=${positionStopAbs.toStr(4)}")

                    // 清仓释放保证金
                    usedMargin = bd(0.0)
                    positionSize = bd(0.0)
                    positionEntry = bd(0.0)
                    positionLayers = 0
                    positionDirection = ""
                    stopLossPrice = bd(0.0)
                    positionStopAbs = bd(0.0)

                    currentSeq?.let { allSequences.add(it.toList()) }
                    currentSeq = null
                    resetAddFlags()
                    continue
                }
            }

            // ---------- 2) 爆仓判定（基于 notional 的维护保证金） ----------
            if (positionLayers > 0) {
                val notional = currentNotional(positionEntry, positionSize)
                val maintenanceRequired = notional.multiply(MAINTENANCE_MARGIN_RATE)
                if (equity.add(unrealizedPnl).compareTo(maintenanceRequired) <= 0) {
                    val dirBefore = positionDirection
                    val entryBefore = positionEntry
                    val sizeBefore = positionSize
                    val unrealBefore = unrealizedPnl

                    currentSeq?.add(TradeRecord(dt, "LIQUIDATION", positionLayers, dirBefore, price, sizeBefore, unrealBefore))
                    summary.liquidations++

                    println("[${dt}] LIQUIDATION dir=$dirBefore entry=${entryBefore.toStr(4)} price=${price.toStr(4)} unreal=${unrealBefore.toStr(4)} notional=${notional.toStr(4)} maintenanceReq=${maintenanceRequired.toStr(6)}")

                    equity = maxOf(equity.add(unrealBefore), bd(0.0))

                    usedMargin = bd(0.0)
                    positionSize = bd(0.0)
                    positionEntry = bd(0.0)
                    positionLayers = 0
                    positionDirection = ""
                    stopLossPrice = bd(0.0)
                    positionStopAbs = bd(0.0)

                    currentSeq?.let { allSequences.add(it.toList()) }
                    currentSeq = null
                    resetAddFlags()
                    continue
                }
            }

            // ---------- 3) 开仓 / 加仓 / 平仓（EMA 交叉） ----------
            val crossUp = (e7Prev != null && e21Prev != null && e7Now != null && e21Now != null &&
                    e7Prev < e21Prev && e7Now >= e21Now)
            val crossDown = (e7Prev != null && e21Prev != null && e7Now != null && e21Now != null &&
                    e7Prev >= e21Prev && e7Now < e21Now)

            if (positionLayers == 0) {
                if (crossUp) {
                    val availableEquity = equity.subtract(usedMargin).coerceAtLeast(bd(0.0))
                    if (availableEquity > bd(0.0)) {
                        // 初始开仓名义（notional）= 可用资金 * 杠杆
                        initialOpenNotional = availableEquity.multiply(LEVERAGE)
                        val marginRequired = initialOpenNotional.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        val size = initialOpenNotional.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        positionSize = size
                        positionEntry = price
                        positionLayers = 1
                        positionDirection = "long"
                        usedMargin = usedMargin.add(marginRequired)

                        // 核心改动：**每笔开仓时计算该笔的绝对浮亏阈值 = 当时 equity 的 5%**
                        positionStopAbs = equity.multiply(STOP_TOTAL_PCT)

                        // price-based stopLossPrice 仅作打印参考（不再主用）
                        stopLossPrice = positionEntry.multiply(BigDecimal.ONE.subtract(bd(-0.05)))

                        resetAddFlags()
                        summary.opens++
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", positionLayers, positionDirection, price, positionSize, bd(0.0)))

                        println("[${dt}] OPEN LONG price=${price.toStr(4)} size=${positionSize.toStr(6)} entry=${positionEntry.toStr(4)} equity=${equity.toStr(2)} usedMargin=${usedMargin.toStr(4)} notional=${initialOpenNotional.toStr(4)} posStopAbs=${positionStopAbs.toStr(4)}")
                    }
                }

                if (crossDown) {
                    val availableEquity = equity.subtract(usedMargin).coerceAtLeast(bd(0.0))
                    if (availableEquity > bd(0.0)) {
                        initialOpenNotional = availableEquity.multiply(LEVERAGE)
                        val marginRequired = initialOpenNotional.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        val size = initialOpenNotional.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP).divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        positionSize = size
                        positionEntry = price
                        positionLayers = 1
                        positionDirection = "short"
                        usedMargin = usedMargin.add(marginRequired)

                        // 此笔的止损阈值（绝对浮亏）基于当时 equity
                        positionStopAbs = equity.multiply(STOP_TOTAL_PCT)
                        stopLossPrice = positionEntry.multiply(BigDecimal.ONE.add(bd(0.05)))

                        resetAddFlags()
                        summary.opens++
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", positionLayers, positionDirection, price, positionSize, bd(0.0)))

                        println("[${dt}] OPEN SHORT price=${price.toStr(4)} size=${positionSize.toStr(6)} entry=${positionEntry.toStr(4)} equity=${equity.toStr(2)} usedMargin=${usedMargin.toStr(4)} notional=${initialOpenNotional.toStr(4)} posStopAbs=${positionStopAbs.toStr(4)}")
                    }
                }
            } else {
                // 已有持仓：检查加仓条件（按 initialOpenNotional）
                if (addCount < MAX_ADDS) {
                    if (positionDirection == "long") {
                        val risePct = if (positionEntry.compareTo(bd(0.0)) == 0) bd(0.0)
                        else price.subtract(positionEntry).divide(positionEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        val cond1 = (!usedAddEma7x52 && e7Prev != null && e52Prev != null && e7Now != null && e52Now != null &&
                                e7Prev < e52Prev && e7Now >= e52Now)
                        val cond2 = (!usedAddEma21x52 && e21Prev != null && e52Prev != null && e21Now != null && e52Now != null &&
                                e21Prev < e52Prev && e21Now >= e52Now)
                        val cond3 = (!usedAddUp2 && risePct.compareTo(bd(0.02)) >= 0)
                        val cond4 = (!usedAddUp5 && risePct.compareTo(bd(0.05)) >= 0)

                        when {
                            cond1 -> { tryAddPosition(ADD_EMA7_52_MULT, "ADD_EMA7x52", price, dt, unrealizedPnl); usedAddEma7x52 = true }
                            cond2 -> { tryAddPosition(ADD_EMA21_52_MULT, "ADD_EMA21x52", price, dt, unrealizedPnl); usedAddEma21x52 = true }
                            cond3 -> { tryAddPosition(ADD_UP_2PCT_MULT, "ADD_UP_2%", price, dt, unrealizedPnl); usedAddUp2 = true }
                            cond4 -> { tryAddPosition(ADD_UP_5PCT_MULT, "ADD_UP_5%", price, dt, unrealizedPnl); usedAddUp5 = true }
                        }
                    } else { // short
                        val fallPct = if (positionEntry.compareTo(bd(0.0)) == 0) bd(0.0)
                        else positionEntry.subtract(price).divide(positionEntry, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        val cond1 = (!usedAddEma7x52 && e7Prev != null && e52Prev != null && e7Now != null && e52Now != null &&
                                e7Prev >= e52Prev && e7Now < e52Now)
                        val cond2 = (!usedAddEma21x52 && e21Prev != null && e52Prev != null && e21Now != null && e52Now != null &&
                                e21Prev >= e52Prev && e21Now < e52Now)
                        val cond3 = (!usedAddUp2 && fallPct.compareTo(bd(0.02)) >= 0)
                        val cond4 = (!usedAddUp5 && fallPct.compareTo(bd(0.05)) >= 0)

                        when {
                            cond1 -> { tryAddPosition(ADD_EMA7_52_MULT, "ADD_EMA7x52_SHORT", price, dt, unrealizedPnl); usedAddEma7x52 = true }
                            cond2 -> { tryAddPosition(ADD_EMA21_52_MULT, "ADD_EMA21x52_SHORT", price, dt, unrealizedPnl); usedAddEma21x52 = true }
                            cond3 -> { tryAddPosition(ADD_UP_2PCT_MULT, "ADD_DOWN_2%_SHORT", price, dt, unrealizedPnl); usedAddUp2 = true }
                            cond4 -> { tryAddPosition(ADD_UP_5PCT_MULT, "ADD_DOWN_5%_SHORT", price, dt, unrealizedPnl); usedAddUp5 = true }
                        }
                    }
                }

                // 平仓（EMA 反向穿越）
                val closeLongCond = (positionDirection == "long" && e7Prev != null && e21Prev != null && e7Now != null && e21Now != null &&
                        e7Prev >= e21Prev && e7Now < e21Now)
                val closeShortCond = (positionDirection == "short" && e7Prev != null && e21Prev != null && e7Now != null && e21Now != null &&
                        e7Prev < e21Prev && e7Now >= e21Now)

                if (closeLongCond || closeShortCond) {
                    val realized = unrealizedPnl
                    val dirBefore = positionDirection
                    val priceBefore = price
                    val sizeBefore = positionSize

                    equity = equity.add(realized)
                    currentSeq?.add(TradeRecord(dt, "TAKE PROFIT (EMA CROSS)", positionLayers, dirBefore, priceBefore, sizeBefore, realized))
                    summary.fullTps++

                    println("[${dt}] TAKE PROFIT dir=${dirBefore} price=${priceBefore.toStr(4)} realized=${realized.toStr(4)} equity=${equity.toStr(4)}")

                    usedMargin = bd(0.0)
                    positionSize = bd(0.0)
                    positionEntry = bd(0.0)
                    positionLayers = 0
                    positionDirection = ""
                    stopLossPrice = bd(0.0)
                    positionStopAbs = bd(0.0)

                    currentSeq?.let { allSequences.add(it.toList()) }
                    currentSeq = null
                    resetAddFlags()
                }
            }
        } // end for bars

        // 年末结算：把未实现加入 equity（如果有残余持仓）
        if (positionLayers > 0) {
            val lastPrice = klines.last().close
            val finalUnreal = if (positionDirection == "long") {
                lastPrice.subtract(positionEntry).multiply(positionSize).multiply(CONTRACT_SIZE)
            } else {
                positionEntry.subtract(lastPrice).multiply(positionSize).multiply(CONTRACT_SIZE)
            }
            equity = equity.add(finalUnreal)

            currentSeq?.add(
                TradeRecord(
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(klines.last().ts), ZONE).format(DATE_FORMATTER),
                    "EOD_UNREAL_SETTLE",
                    positionLayers,
                    positionDirection,
                    klines.last().close,
                    positionSize,
                    finalUnreal
                )
            )
            currentSeq?.let { allSequences.add(it.toList()) }
            currentSeq = null
        }

        // 汇总
        summary.endCapital = equity.coerceAtLeast(bd(0.0))
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0)
        else (summary.endCapital.subtract(summary.startCapital).divide(summary.startCapital, 6, RoundingMode.HALF_UP).multiply(bd(100.0)))

        return summary to allSequences
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val symbols = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")
        val tfs = listOf("1D")

        for (symbol in symbols) {
            for (tf in tfs) {
                val allK = loadKlines(symbol, tf)
                if (allK.isEmpty()) {
                    println("[$symbol][$tf] 未找到数据，跳过")
                    continue
                }
                val years = allK.map { Instant.ofEpochMilli(it.ts).atZone(ZONE).year }.distinct().sorted()
                for (y in years) {
                    val (sum, sequences) = backtestYearSequences(symbol, tf, y, allK)
                    println("【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓 ${sum.opens}，加仓 ${sum.adds}，止损 ${sum.stops}，整仓止盈 ${sum.fullTps}，爆仓 ${sum.liquidations}")

                    if (sequences.isNotEmpty()) {
                        println("=== ${symbol} ${tf} ${y} 全部交易序列（共 ${sequences.size} 组） ===")
                        sequences.forEachIndexed { idx, seq ->
                            println("--- 序列 ${idx + 1} / ${sequences.size} （${seq.size} 步）---")
                            seq.forEach { tr ->
                                println("[${tr.dt}] ${tr.type} L${tr.layer} ${tr.dir} @${tr.price.toStr(4)} size=${tr.size.toStr(6)} unreal=${tr.unreal.toStr(4)}")
                            }
                        }
                        println("=== END OF ALL SEQUENCES ===")
                    } else {
                        println("（${y} 年无完整交易序列可抽样）")
                    }
                }
            }
        }
    }
}
