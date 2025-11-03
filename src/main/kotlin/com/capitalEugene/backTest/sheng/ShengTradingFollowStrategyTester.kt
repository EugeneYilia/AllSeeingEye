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

    // 金字塔加仓比例（按你之前的偏好）
    private val ADD_MULTIPLIERS = listOf(bd(0.7), bd(0.6), bd(0.5), bd(0.4), bd(0.2))
    private val MAX_LAYERS = 1 + ADD_MULTIPLIERS.size // layer1 initial + number of adds
    private val ADD_PROFIT_PCT = bd(0.015)  // 盈利 1.5% 触发加仓（整体未实现收益达到 1.5%）
    private val FIRST_LAYER_SL_PCT = bd(-0.05) // 第一层止损 -5%
    private val SECOND_LAYER_SL_PCT = bd(0.0) // 第二层止损抬到开仓价（实现时按 posEntry）
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
        val type: String,    // OPEN / ADD / STOP / TP / LIQ / ...
        val layer: Int,
        val dir: String,
        val price: BigDecimal,
        val size: BigDecimal,
        val unreal: BigDecimal
    )

    /**
     * 回测一年：返回年度统计 + 当年每笔完整交易序列的列表（每笔序列是按时间顺序的 TradeRecord 列表）
     * （调用者可以从 sequences 中随机抽取一条打印）
     */
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
        var currentSeq: MutableList<TradeRecord>? = null // 正在进行的序列（从 OPEN 开始，到完全平仓/爆仓结束）

        var equity = TOTAL_CAPITAL
        var usedMargin = bd(0.0)

        var posSize = bd(0.0)
        var posEntry = bd(0.0)
        var posLayer = 0 // 0: 无仓位；1: 初始层；2..: 后续加仓
        var posDir = ""  // "long" / "short"
        var stopPrice: BigDecimal? = null
        var peakPrice: BigDecimal? = null

        for (i in 9 until klines.size) {
            val window = klines.subList(i - 9, i + 1)
            val current = window.last()
            val price = current.close
            val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(current.ts), ZONE).format(DATE_FORMATTER)

            val prev9 = window.subList(0, 9)
            val prev9MinLow = prev9.minOf { it.low }
            val prev9MaxHigh = prev9.maxOf { it.high }

            // 计算未实现 PnL
            val unreal = if (posLayer > 0) {
                if (posDir == "long") price.subtract(posEntry).multiply(posSize).multiply(CONTRACT_SIZE)
                else posEntry.subtract(price).multiply(posSize).multiply(CONTRACT_SIZE)
            } else bd(0.0)

            // safe denom (avoid divide-by-zero)
            val denomRaw = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
            val denom = if (denomRaw.compareTo(bd(0.0)) <= 0) bd(1e-8) else denomRaw
            val unrealPct = if (posLayer > 0) unreal.divide(denom, BIGDECIMAL_SCALE, RoundingMode.HALF_UP) else bd(0.0)

            // 爆仓判定（标记权益不足以覆盖保证金）
            val marginBalance = equity.add(unreal).subtract(usedMargin)
            if (posLayer > 0 && marginBalance <= bd(0.0)) {
                // 记录爆仓事件到当前序列（如果存在）
                currentSeq?.add(TradeRecord(dt, "LIQUIDATION", posLayer, posDir, price, posSize, unreal))
                summary.liquidations++

                // 清仓并释放保证金（按市值释放）
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                usedMargin = bd(0.0)

                // 完成当前序列（如果有），加入 allSequences
                currentSeq?.let { allSequences.add(it.toList()) }
                currentSeq = null

                // equity 重置为初始（按你之前的设定）
                equity = TOTAL_CAPITAL
                continue
            }

            val longSignal = price <= prev9MinLow
            val shortSignal = price >= prev9MaxHigh

            // 当没有持仓：判断是否开仓
            if (posLayer == 0) {
                if (longSignal || shortSignal) {
                    val dir = if (longSignal) "long" else "short"
                    val value = INITIAL_POSITION_VALUE
                    val margin = value.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                    if (equity.subtract(usedMargin) >= margin) {
                        val size = value.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                            .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                        // 开仓
                        posSize = size
                        posEntry = price
                        posLayer = 1
                        posDir = dir
                        usedMargin = usedMargin.add(margin)
                        peakPrice = price
                        stopPrice = when (posLayer) {
                            1 -> posEntry.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT)) // first layer SL
                            else -> posEntry
                        }
                        summary.opens++

                        // 开始新序列并记录 OPEN
                        currentSeq = mutableListOf()
                        currentSeq.add(TradeRecord(dt, "OPEN", posLayer, posDir, price, size, unreal))
                    }
                }
                // 如果没有开仓，直接进入下一 bar
                continue
            }

            // 如果有持仓（posLayer > 0），并且 currentSeq 为 null 表示这是非记录序列（说明我们并不为每笔序列创建 currentSeq?）
            // 我们在设计里是为每笔真实开仓都创建 currentSeq，因此 currentSeq != null
            // 为稳健起见，如果 currentSeq == null，也为该仓位临时创建序列（避免漏记）
            if (posLayer > 0 && currentSeq == null) {
                currentSeq = mutableListOf()
                // 记录一个伪 OPEN（历史中某处开仓未被记录）——这情况很少发生，但保险处理
                currentSeq.add(TradeRecord(dt, "OPEN(RECOVERED)", posLayer, posDir, posEntry, posSize, unreal))
            }

            // 更新 peakPrice（用于追踪或未来策略）
            peakPrice = if (posDir == "long") maxOf(peakPrice!!, price) else minOf(peakPrice!!, price)

            // 计算并调整止损（你要求：L1 = -5%，L2 = 回到开仓价，L3+ = 每上涨4%提升止损2.5%）
            stopPrice = when (posLayer) {
                1 -> posEntry.multiply(BigDecimal.ONE.add(FIRST_LAYER_SL_PCT))
                2 -> posEntry.multiply(BigDecimal.ONE.add(SECOND_LAYER_SL_PCT))
                else -> {
                    // 计算上涨幅度 (相对 posEntry)，每上涨 4% 记作一步，止损上移 2.5%/步（示例性处理）
                    val rise = if (posDir == "long") price.subtract(posEntry) else posEntry.subtract(price)
                    val steps = rise.divide(bd(0.04), 0, RoundingMode.DOWN) // 整数步数
                    // newStop = posEntry + steps * 2.5% (long)，对于 short 对称处理
                    val shift = steps.multiply(bd(0.025))
                    if (posDir == "long") posEntry.add(shift.multiply(posEntry)) else posEntry.subtract(shift.multiply(posEntry))
                }
            }

            // 止损触发判断
            val triggerStop = if (posDir == "long") {
                // 注意：stopPrice 可能为 null 安全检查
                stopPrice != null && price <= stopPrice
            } else {
                stopPrice != null && price >= stopPrice
            }

            if (triggerStop) {
                // 平仓：以市价全部平掉
                // realized 已包含未实现 pnl
                val realized = unreal
                equity = equity.add(realized)
                // 释放保证金（按 posEntry * posSize * CONTRACT_SIZE / LEVERAGE）
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))

                // 记录 STOP 到当前序列
                currentSeq?.add(TradeRecord(dt, "STOP LOSS", posLayer, posDir, price, posSize, unreal))
                summary.stops++

                // 结束仓位
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""

                // 完成并保存序列
                currentSeq?.let { allSequences.add(it.toList()) }
                currentSeq = null
                continue
            }

            // 盈利条件下加仓（基于 unrealPct 或其他条件）
            if (unrealPct >= ADD_PROFIT_PCT && posLayer < MAX_LAYERS) {
                // 使用对应层的加仓比例：posLayer = 当前层 (1..)，第1次加仓使用 ADD_MULTIPLIERS[0]
                val addIndex = posLayer - 1
                val multiplier = if (addIndex in ADD_MULTIPLIERS.indices) ADD_MULTIPLIERS[addIndex] else ADD_MULTIPLIERS.last()
                val addValue = INITIAL_POSITION_VALUE.multiply(multiplier)
                val addMargin = addValue.divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                if (equity.subtract(usedMargin) >= addMargin) {
                    val addSize = addValue.divide(price, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                        .divide(CONTRACT_SIZE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                    // 更新仓均价（价值加权）
                    val oldValue = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    val newValue = price.multiply(addSize).multiply(CONTRACT_SIZE)
                    val combinedValue = oldValue.add(newValue)
                    val combinedSize = posSize.add(addSize)
                    val newEntry = if (combinedSize.compareTo(bd(0.0)) == 0) bd(0.0) else combinedValue.divide(combinedSize.multiply(CONTRACT_SIZE), BIGDECIMAL_SCALE, RoundingMode.HALF_UP)

                    posSize = combinedSize
                    posEntry = newEntry
                    posLayer = posLayer + 1
                    usedMargin = usedMargin.add(addMargin)
                    summary.adds++
                    // 记录 ADD
                    currentSeq?.add(TradeRecord(dt, "ADD", posLayer, posDir, price, addSize, unreal))
                }
            }

            // 简单整仓止盈：当 unrealPct >= 2% 时平掉全部（你之前希望有整仓止盈）
            if (unrealPct >= bd(0.02) && posLayer >= 1) {
                val realized = unreal
                equity = equity.add(realized)
                val marginReleased = posEntry.multiply(posSize).multiply(CONTRACT_SIZE)
                    .divide(LEVERAGE, BIGDECIMAL_SCALE, RoundingMode.HALF_UP)
                usedMargin = usedMargin.subtract(marginReleased).coerceAtLeast(bd(0.0))

                currentSeq?.add(TradeRecord(dt, "TAKE PROFIT", posLayer, posDir, price, posSize, unreal))
                summary.fullTps++

                // 平仓并结束序列
                posSize = bd(0.0); posEntry = bd(0.0); posLayer = 0; posDir = ""
                currentSeq?.let { allSequences.add(it.toList()) }
                currentSeq = null
            }
        } // end bars

        // 若最后还有未闭合的 currentSeq（持仓未平，但到年末），也把它当作一笔“未完成”序列保存
        currentSeq?.let { allSequences.add(it.toList()) }

        // 计算年末结算（把未实现加入 equity）
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

                    // 年度统计输出（始终打印）
                    println("【$symbol][$tf] 年份 $y：起始资金 ${sum.startCapital.toStr(2)} USDT，期末资金 ${sum.endCapital.toStr(2)} USDT，收益率 ${sum.roiPct.toStr(4)}%，开仓 ${sum.opens}，加仓 ${sum.adds}，止损 ${sum.stops}，整仓止盈 ${sum.fullTps}，爆仓 ${sum.liquidations}")

                    // 抽样打印：如果当年有完整序列，从 sequences 中随机抽取一条完整序列打印（你要“一年抽样一次完整开仓记录”）
                    if (sequences.isNotEmpty()) {
                        // 随机抽取一个序列索引；如果你想要固定抽取第一笔，改为 index = 0
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
