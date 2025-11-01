package com.capitalEugene.trade.strategy.sheng

import com.capitalEugene.agent.exchange.okx.TradeAgent.closePosition
import com.capitalEugene.agent.exchange.okx.TradeAgent.openLong
import com.capitalEugene.agent.exchange.okx.TradeAgent.openShort
import com.capitalEugene.agent.exchange.okx.TradeAgent.setCrossLeverage
import com.capitalEugene.agent.mongo.MongoAgent.savePositionToMongo
import com.capitalEugene.agent.redis.RedisAgent.saveToRedis
import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.common.utils.TradeUtils.generateTransactionId
import com.capitalEugene.common.utils.safeDiv
import com.capitalEugene.common.utils.safeMultiply
import com.capitalEugene.common.utils.safeSnapshot
import com.capitalEugene.model.TradingData
import com.capitalEugene.model.position.PositionRunningState
import com.capitalEugene.model.position.PositionState
import com.capitalEugene.model.strategy.martin.MartinConfig
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.max

// state map（与 martin 原风格保持一致，key 可区分 timeframe）
val shengStateMap = mutableMapOf<String, PositionState>()

class ShengMartinStrategy(
    private val configs: List<MartinConfig>
) {
    private val logger = LoggerFactory.getLogger("sheng_martin_strategy")
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    // 策略固定参数（按你的需求）
    private val totalCapital = BigDecimal.valueOf(1000.0)
    private val leverage = BigDecimal.valueOf(20)                                              // 20x
    private val initialPositionValue = BigDecimal.valueOf(400.0)           // 每次开仓 400 USDT（价值）
    private val maxAddLayers = 5                                           // 总共 5 层（含第一层）
    private val addStepPct = BigDecimal.valueOf(0.02)                      // 每跌 2% 加仓
    private val firstLayerTpPct = BigDecimal.valueOf(0.03)                 // 第一层 3% 部分止盈（平 12U）
    private val firstLayerCloseValue = BigDecimal.valueOf(12.0)            // 12 USDT 平仓金额（第一层）
    private val laterLayersTpPct = BigDecimal.valueOf(0.02)                // 第二到五层整体盈利 2% 止盈
    private val timeframes = listOf("1h", "4h")

    // 简单 kline model（项目里如果已有 kline 缓存，请把数据写入这个 cache）
    data class Kline(val open: BigDecimal, val high: BigDecimal, val low: BigDecimal, val close: BigDecimal, val ts: Long)
    companion object {
        // key = "${symbol}_${timeframe}" -> list of last klines (latest at list.last())
        val klineCache: MutableMap<String, List<Kline>> = mutableMapOf()
    }

    /**
     * 启动策略（suspend）
     */
    suspend fun start() {
        logger.info("🚀 ShengMartinStrategy 启动...")

        // 为每个 config 下的 account 设置杠杆，并初始化 state（按 symbol + timeframe）
        configs.forEach { config ->
            // 设置杠杆（与原 martin 一致）
            config.accounts.forEach { account ->
                account.apiSecrets.forEach { apiSecret ->
                    // setCrossLeverage 是 suspend 的；start 为 suspend，可以直接调用
                    setCrossLeverage(config.symbol, leverage, apiSecret)
                }
            }

            // 初始化每个 timeframe 的 state
            timeframes.forEach { tf ->
                val key = "sheng_${config.symbol}_$tf"
                shengStateMap.getOrPut(key) {
                    PositionState(
                        strategyShortName = "sheng_martin",
                        strategyFullName = key,
                        capital = totalCapital,
                        riskAgent = config.riskAgent
                    ).also {
                        savePositionToMongo(it) // 首次保存
                    }
                }
            }
        }

        // 主循环（简单实现，与原 martin 保持相同结构）
        while (true) {
            try {
                configs.forEach { config ->
                    timeframes.forEach { tf ->
                        val stateKey = "sheng_${config.symbol}_$tf"
                        val state = shengStateMap[stateKey] ?: return@forEach

                        state.riskAgent?.monitorState(state, config.accounts)

                        if (state.positionRunningState != PositionRunningState.Running) return@forEach

                        val price = com.capitalEugene.order.priceCache[config.symbol] ?: return@forEach
                        if (price == BigDecimal.ZERO) return@forEach

                        // 读取至少 10 根 K 线（最新在最后）
                        val klines = fetchKlines(config.symbol, tf, 10)
                        if (klines.size < 10) return@forEach
                        val currentClose = klines.last().close
                        val prev9 = klines.subList(klines.size - 10, klines.size - 1)
                        val prev9MinLow = prev9.minOf { it.low }
                        val prev9MaxHigh = prev9.maxOf { it.high }

                        val longSignal = currentClose <= prev9MinLow
                        val shortSignal = currentClose >= prev9MaxHigh

                        handleLong(config, state, price, currentClose, longSignal, tf)
                        handleShort(config, state, price, currentClose, shortSignal, tf)
                    }
                }
            } catch (ex: Exception) {
                logger.error("策略运行异常: ${ex.message}", ex)
            }

            // 与原策略相同，sleep/延时交给外层或保留微延迟
            kotlinx.coroutines.delay(1000L)
        }
    }

    // 计算按价值 (USDT) 得到的合约张数： valueUSDT / entryPrice / contractSize
    private fun computeSizeByValue(symbol: String, entryPrice: BigDecimal, valueUSDT: BigDecimal): BigDecimal {
        val contractSize = OrderConstants.contractSizeMap[symbol] ?: BigDecimal.ONE
        // 币数量 = valueUSDT / entryPrice
        // 张数 = 币数量 / contractSize
        return valueUSDT.safeDiv(entryPrice).safeDiv(contractSize)
    }

    // 开多 / 加仓 / 止盈逻辑（suspend）
    private suspend fun handleLong(config: MartinConfig, state: PositionState, price: BigDecimal, currentClose: BigDecimal, signal: Boolean, timeframe: String) {
        val key = "sheng_${config.symbol}_$timeframe"
        // 开仓（无持仓且信号触发）
        if (state.longPosition == BigDecimal.ZERO && signal) {
            val size = computeSizeByValue(config.symbol, price, initialPositionValue)
            config.accounts.forEach { account ->
                account.apiSecrets.forEach { apiSecret ->
                    openLong(config.symbol, price, size, apiSecret)
                }
            }
            val tx = generateTransactionId()
            state.longPosition = size
            state.longEntryPrice = price
            state.longAddCount = 1
            state.longTransactionId = tx

            buildRedisDataAndSave(config, "open", size, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), tx)
            savePositionToMongo(state)
            logger.info("📈 [$key] 开多 @ $price size=$size")
            return
        }

        if (state.longPosition != BigDecimal.ZERO) {
            // 变动率（基于 entry price）
            val entry = state.longEntryPrice!!
            val change = (price - entry).safeDiv(entry)
            val contractSize = OrderConstants.contractSizeMap[config.symbol] ?: BigDecimal.ONE
            val pnl = state.longPosition.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(change) // 盈亏（USDT）

            // 第一层部分止盈：若当前为第一层（longAddCount ==1）且盈利 >=3%，则平掉 12USDT 对应张数
            if (state.longAddCount == 1 && change >= firstLayerTpPct) {
                val contractsToClose = firstLayerCloseValue.safeDiv(entry.safeMultiply(contractSize))
                val toClose = minOf(contractsToClose, state.longPosition)
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        closePosition(config.symbol, "sell", price, toClose.abs(), apiSecret)
                    }
                }
                // 更新 state、资金
                state.longPosition = (state.longPosition - toClose)
                state.capital += toClose.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(firstLayerTpPct)
                state.takeProfitCount += 1
                buildRedisDataAndSave(config, "first_layer_tp", toClose, toClose.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(firstLayerTpPct), LocalDateTime.now().format(dateFormatter), state.longTransactionId!!)
                savePositionToMongo(state)
                logger.info("🟢 [${config.symbol}][$timeframe] 第一层部分止盈, 平掉 ${toClose} 张 @ $price")
                if (state.longPosition <= BigDecimal.ZERO) resetLong(state)
                return
            }

            // 第二到五层：若整体盈利 >= 2%，全部平仓
            if (state.longAddCount >= 2 && change >= laterLayersTpPct) {
                val toClose = state.longPosition.abs()
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        closePosition(config.symbol, "sell", price, toClose, apiSecret)
                    }
                }
                state.capital += pnl
                state.takeProfitCount += 1
                buildRedisDataAndSave(config, "tp_all", BigDecimal.ZERO, pnl, LocalDateTime.now().format(dateFormatter), state.longTransactionId!!)
                savePositionToMongo(state)
                logger.info("🟢 [${config.symbol}][$timeframe] 多仓整体止盈 @ $price pnl=${pnl}")
                resetLong(state)
                return
            }

            // 加仓逻辑：当当前 price 相对 entry 下跌到触发点，且未超过最大加仓层数
            val dropPct = (entry - price).safeDiv(entry) // 正值表示下跌比例
            // thresholdsCrossed = floor(dropPct / addStepPct)
            val thresholdsCrossed = dropPct.safeDiv(addStepPct).toDouble().toInt()
            // 当前已经在第几层（1..maxAddLayers）
            val currentLayer = state.longAddCount
            // 当 thresholdsCrossed >= currentLayer 且 currentLayer < maxAddLayers 时触发加仓
            if (thresholdsCrossed >= currentLayer && currentLayer < maxAddLayers) {
                // 新增仓位 = 初始 position size * 2^(currentLayer)  (currentLayer 从 1 开始)
                val baseSize = computeSizeByValue(config.symbol, price, initialPositionValue)
                val multiplier = BigDecimal.valueOf(2).pow(currentLayer) // e.g. layer1->2^1=2 (但我们想要层序列 1,2,4,8...：为了匹配原 Martin，采用 pow(currentLayer-1) 也可；此处使用 pow(currentLayer) 保持与需求“每次加2倍”的含义）
                val addSize = baseSize.safeMultiply(multiplier)

                // 执行开仓 add
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        openLong(config.symbol, price, addSize, apiSecret)
                    }
                }
                // 更新均价与仓位
                val oldPos = state.longPosition
                state.longPosition = state.longPosition + addSize
                state.longEntryPrice = (entry.safeMultiply(oldPos) + price.safeMultiply(addSize)).safeDiv(state.longPosition)
                state.longAddCount = currentLayer + 1
                buildRedisDataAndSave(config, "add", addSize, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), state.longTransactionId!!)
                savePositionToMongo(state)
                logger.info("➕ [${config.symbol}][$timeframe] 多仓加仓 @ $price addSize=$addSize nowPos=${state.longPosition} addCount=${state.longAddCount}")
            }
        }
    }

    // 空仓逻辑（对称）
    private suspend fun handleShort(config: MartinConfig, state: PositionState, price: BigDecimal, currentClose: BigDecimal, signal: Boolean, timeframe: String) {
        val key = "sheng_${config.symbol}_$timeframe"
        if (state.shortPosition == BigDecimal.ZERO && signal) {
            val size = computeSizeByValue(config.symbol, price, initialPositionValue)
            config.accounts.forEach { account ->
                account.apiSecrets.forEach { apiSecret ->
                    openShort(config.symbol, price, size, apiSecret)
                }
            }
            val tx = generateTransactionId()
            state.shortPosition = size
            state.shortEntryPrice = price
            state.shortAddCount = 1
            state.shortTransactionId = tx
            buildRedisDataAndSave(config, "open_short", size, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), tx)
            savePositionToMongo(state)
            logger.info("📉 [$key] 开空 @ $price size=$size")
            return
        }

        if (state.shortPosition != BigDecimal.ZERO) {
            val entry = state.shortEntryPrice!!
            val change = (entry - price).safeDiv(entry) // 空仓收益率（价格下降为正）
            val contractSize = OrderConstants.contractSizeMap[config.symbol] ?: BigDecimal.ONE
            val pnl = state.shortPosition.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(change)

            // 第一层空仓部分止盈：盈利 >=3% 则平掉 12 USDT 等值合约
            if (state.shortAddCount == 1 && change >= firstLayerTpPct) {
                val contractsToClose = firstLayerCloseValue.safeDiv(entry.safeMultiply(contractSize))
                val toClose = minOf(contractsToClose, state.shortPosition)
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        closePosition(config.symbol, "buy", price, toClose.abs(), apiSecret)
                    }
                }
                state.shortPosition = (state.shortPosition - toClose)
                state.capital += toClose.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(firstLayerTpPct)
                state.takeProfitCount += 1
                buildRedisDataAndSave(config, "first_layer_tp_short", toClose, toClose.safeMultiply(contractSize).safeMultiply(entry).safeMultiply(firstLayerTpPct), LocalDateTime.now().format(dateFormatter), state.shortTransactionId!!)
                savePositionToMongo(state)
                logger.info("🟢 [${config.symbol}][$timeframe] 空仓第一层部分止盈, 平掉 $toClose 张 @ $price")
                if (state.shortPosition <= BigDecimal.ZERO) resetShort(state)
                return
            }

            // 第二到五层空仓整体止盈 >=2%
            if (state.shortAddCount >= 2 && change >= laterLayersTpPct) {
                val toClose = state.shortPosition.abs()
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        closePosition(config.symbol, "buy", price, toClose, apiSecret)
                    }
                }
                state.capital += pnl
                state.takeProfitCount += 1
                buildRedisDataAndSave(config, "tp_all_short", BigDecimal.ZERO, pnl, LocalDateTime.now().format(dateFormatter), state.shortTransactionId!!)
                savePositionToMongo(state)
                logger.info("🟢 [${config.symbol}][$timeframe] 空仓整体止盈 @ $price pnl=${pnl}")
                resetShort(state)
                return
            }

            // 空仓加仓：当价格相对 entry 上涨达到加仓阈值且未超过层数
            val risePct = (price - entry).safeDiv(entry)
            val thresholdsCrossed = risePct.safeDiv(addStepPct).toDouble().toInt()
            val currentLayer = state.shortAddCount
            if (thresholdsCrossed >= currentLayer && currentLayer < maxAddLayers) {
                val baseSize = computeSizeByValue(config.symbol, price, initialPositionValue)
                val multiplier = BigDecimal.valueOf(2).pow(currentLayer)
                val addSize = baseSize.safeMultiply(multiplier)
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        openShort(config.symbol, price, addSize, apiSecret)
                    }
                }
                val oldPos = state.shortPosition
                state.shortPosition = state.shortPosition + addSize
                state.shortEntryPrice = (entry.safeMultiply(oldPos) + price.safeMultiply(addSize)).safeDiv(state.shortPosition)
                state.shortAddCount = currentLayer + 1
                buildRedisDataAndSave(config, "add_short", addSize, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), state.shortTransactionId!!)
                savePositionToMongo(state)
                logger.info("➕ [${config.symbol}][$timeframe] 空仓加仓 @ $price addSize=$addSize nowPos=${state.shortPosition} addCount=${state.shortAddCount}")
            }
        }
    }

    // 从 cache 读取 k 线（返回最新 needed 根）
    private fun fetchKlines(symbol: String, timeframe: String, needed: Int): List<Kline> {
        val key = "${symbol}_$timeframe"
        val all = klineCache[key] ?: emptyList()
        return if (all.size <= needed) all else all.takeLast(needed)
    }

    private suspend fun buildRedisDataAndSave(config: MartinConfig, op: String, addPositionAmount: BigDecimal, result: BigDecimal, time: String, transactionId: String) {
        val data = TradingData(
            transactionId = transactionId,
            strategyName = "sheng_${config.symbol}_$op",
            returnPerformance = result,
            openTime = if (op.contains("open")) time else "",
            closeTime = if (op.contains("tp") || op.contains("close")) time else "",
            holdingAmount = addPositionAmount
        )
        // op 以 open/add => 存为 open; tp/close => 存为 close（和原 martin 一致）
        val opType = if (op.contains("open") || op.contains("add")) "open" else "close"
        saveToRedis(data, opType)
    }

    private fun resetLong(state: PositionState) {
        state.longPosition = BigDecimal.ZERO
        state.longEntryPrice = null
        state.longAddCount = 1
    }

    private fun resetShort(state: PositionState) {
        state.shortPosition = BigDecimal.ZERO
        state.shortEntryPrice = null
        state.shortAddCount = 1
    }
}
