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

object ShengTrendBacktest {

    private val TOTAL_CAPITAL = bd(1000.0)
    private val LEVERAGE = bd(20.0)
    private val INITIAL_POSITION_VALUE = bd(400.0)

    private val ADD_MULTIPLIERS = listOf(bd(0.7), bd(0.6), bd(0.5), bd(0.4), bd(0.2))
    private val MAX_LAYERS = 1 + ADD_MULTIPLIERS.size
    private val ADD_PROFIT_PCT = bd(0.015)  // 盈利 1.5% 加仓
    private val FIRST_LAYER_SL_PCT = bd(-0.05) // 用 -0.05 表示 long 下跌 5% 触发，short 上涨 5% 触发
    private val SECOND_LAYER_SL_PCT = bd(0.0) // 第二层止损回到开仓价
    private val CONTRACT_SIZE = bd(1.0)

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
        if (klines.size < 10) return summary to emptyList()

        val allSequences = mutableListOf<List<TradeRecord>>()
        var currentSeq: MutableList<TradeRecord>? = null

        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0
        var posDir = ""
        var stopPrice: BigDecimal? = null
        var peakPrice: BigDecimal? = null

        for (i in 9 until klines.size) {
            var addedThisBar = false // <- 新：标记本轮是否发生了 ADD（加仓）
            val window = klines.subList(i - 9, i + 1)
            val current = window.last()
            val price = current.close
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(current.ts), ZONE).format(DATE_FORMATTER)

            val prev9 = window.subList(0, 9)
            val prev9MinLow = prev9.minOf { it.low }
            val prev9MaxHigh = prev9.maxOf { it.high }

            // 未实现 pnl
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            // safe denom to avoid div-by-zero
            val denomRaw = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
            val denom = if (denomRaw.compareTo(bd(0.0)) <= 0) bd(1e-8) else denomRaw
            val unrealPct = if (posLayer > 0) unreal.divide(denom, BIGDECIMAL_SCALE, RoundingMode.HALF_UP) else bd(0.0)

            // 爆仓判定
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (posLayer > 0 && marginBalance <= bd(0.0)) {
                currentSeq?.add(TradeRecord(dt, "LIQUIDATION", posLayer, posDir, price, posSize, unreal))
                summary.liquidations++
                // 清仓
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)
                // 完成序列
                currentSeq?.let { allSequences.add(it.toList()) }
                currentSeq = null
                // 重置 equity（如果你保留自动注资逻辑，可以放这里）
                equity = TOTAL_CAPITAL
                continue
            }

            val longSignal = price <= prev9MinLow
            val shortSignal = price >= prev9MaxHigh

            // 开仓（开仓后继续下一条 bar）
            if (posLayer == 0) {
                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    val value = INITIAL_POSITION_VALUE
                    val margin = value.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    if (equity.subtract(usedMargin) >= margin) {
                        val size = value.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        posSize = size; posEntry = price; posLayer = 1; posDir = dir
                        usedMargin = usedMargin.add(margin)
                        peakPrice = price
                        // stop 方向感知：long 下跌 5%，short 上涨 5%
                        stopPrice = if (posDir == "long") {
                            posEntry.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT))
                        } else {
                            posEntry.multiply(BigDecimal.ONE.subtract(FIRST_LAYER_SL_PCT))
                        }
                        summary.opens++
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", posLayer, posDir, price, posSize, unreal))
                    }
                }
                continue
            }

            // if sequence missing, recover (robustness)
            if (posLayer > 0 && currentSeq == null) {
                currentSeq = mutableListOf()
                currentSeq.add(TradeRecord(dt, "OPEN(RECOVERED)", posLayer, posDir, posEntry, posSize, unreal))
            }

            // 更新 peak / 计算止损（按方向）
            peakPrice = if (posDir == "long") maxOf(peakPrice!!, price) else minOf(peakPrice!!, price)

            stopPrice = when (posLayer) {
                1 -> if (posDir == "long") posEntry.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT)) else posEntry.multiply(BigDecimal.ONE.subtract(FIRST_LAYER_SL_PCT))
                2 -> posEntry.multiply(BigDecimal.ONE.add(SECOND_LAYER_SL_PCT)) // 回到 entry
                else -> {
                    // 第3层及以后：按相对涨幅每 4% 步进，上调 2.5%（相对 posEntry）
                    val pctRise = if (posDir == "long") {
                        val num = price.subtract(posEntry)
                        val denomForPct = if (posEntry.compareTo(bd(0.0)) == 0) bd(1e-8) else posEntry
                        num.divide(denomForPct, BIGDECIMAL_SCALE, RoundingMode.DOWN)
                    } else {
                        val num = posEntry.subtract(price)
                        val denomForPct = if (posEntry.compareTo(bd(0.0)) == 0) bd(1e-8) else posEntry
                        num.divide(denomForPct, BIGDECIMAL_SCALE, RoundingMode.DOWN)
                    }
                    val steps = pctRise.divide(bd(0.04), 0, RoundingMode.DOWN)
                    val shiftPct = steps.multiply(bd(0.025))
                    if (posDir == "long") posEntry.multiply(BigDecimal.ONE.add(shiftPct)) else posEntry.multiply(BigDecimal.ONE.subtract(shiftPct))
                }
            }

            // 判断止损触发（方向敏感）
            val triggerStop = if (posDir == "long") {
                stopPrice != null && price <= stopPrice
            } else {
                stopPrice != null && price >= stopPrice
            }

            if (triggerStop) {
                val realized = unreal
                equity = equity.add(realized)
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                currentSeq?.add(TradeRecord(dt, "STOP LOSS", posLayer, posDir, price, posSize, unreal))
                summary.stops++
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
                continue
            }

            // 盈利加仓：如果本轮加仓，标记 addedThisBar = true
            if (unrealPct >= ADD_PROFIT_PCT && posLayer < MAX_LAYERS) {
                val addIndex = posLayer - 1
                val multiplier = if (addIndex in ADD_MULTIPLIERS.indices) ADD_MULTIPLIERS[addIndex] else ADD_MULTIPLIERS.last()
                val addValue = INITIAL_POSITION_VALUE.multiply(multiplier)
                val addMargin = addValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                if (equity.subtract(usedMargin) >= addMargin) {
                    val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    val oldVal = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    val newVal = price.multiply(addSize).multiply(CONTRACT_SIZE)
                    val combinedVal = oldVal.add(newVal)
                    val combinedSize = posSize.add(addSize)
                    val newEntry = if (combinedSize.compareTo(bd(0.0)) == 0) bd(0.0) else combinedVal.divide(combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                    posSize = combinedSize
                    posEntry = newEntry
                    posLayer = posLayer + 1
                    usedMargin = usedMargin.add(addMargin)
                    summary.adds++
                    currentSeq?.add(TradeRecord(dt, "ADD", posLayer, posDir, price, addSize, unreal))
                    addedThisBar = true // <- 关键：本轮已加仓，后续本轮不再触发 TP（直到下根 bar）
                }
            }

            // 整仓止盈：如果本轮刚加过仓则**跳过**本轮 TP 检查，避免加完立即被 TP
            if (!addedThisBar && unrealPct >= bd(0.02) && posLayer >= 1) {
                val realized = unreal
                equity = equity.add(realized)
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))
                currentSeq?.add(TradeRecord(dt, "TAKE PROFIT", posLayer, posDir, price, posSize, unreal))
                summary.fullTps++
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                currentSeq?.let { allSequences.add(it.toList()) }; currentSeq = null
            }
        } // end bars

        currentSeq?.let { allSequences.add(it.toList()) }

        // 年末结算（把未实现加入 equity）
        val finalUnreal = if (posLayer > 0) {
            val lastPrice = klines.last().close
            if (posDir == "long") lastPrice.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
            else posEntry.subtract(lastPrice).multiply(posSize).multiply(CONTRACT_SIZE)
        } else bd(0.0)
        summary.endCapital = maxOf(equity.add(finalUnreal), bd(0.0))
        summary.roiPct = if (summary.startCapital.compareTo(bd(0.0)) == 0) bd(0.0)
        else (summary.endCapital.subtract(summary.startCapital).divide(summary.startCapital, 6, RoundingMode.HALF_UP).multiply(bd(100.0)))

        return summary to allSequences
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
                    val (sum, sequences) = backtestYearSequences(symbol, tf, y, allK)
                    println("【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓 ${sum.opens}，加仓 ${sum.adds}，止损 ${sum.stops}，整仓止盈 ${sum.fullTps}，爆仓 ${sum.liquidations}")
                    if (sequences.isNotEmpty()) {
                        val index = Random.nextInt(sequences.size)
                        val sampled = sequences[index]
                        println("=== ${symbol} ${tf} ${y} 抽样完整交易序列（${index + 1}/${sequences.size}） ===")
                        sampled.forEach {
                            println("[${it.dt}] ${it.type} L${it.layer} ${it.dir} @${it.price.toStr(4)} size=${it.size.toStr(6)} unreal=${it.unreal.toStr(2)}")
                        }
                        println("=== END OF SAMPLE ===")
                    } else {
                        println("（${y} 年无完整交易序列可抽样）")
                    }
                }
            }
        }
    }
}
