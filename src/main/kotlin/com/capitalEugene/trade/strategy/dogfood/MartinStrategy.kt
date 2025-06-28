package com.capitalEugene.trade.strategy.dogfood

import com.capitalEugene.agent.exchange.okx.TradeAgent.closePosition
import com.capitalEugene.agent.exchange.okx.TradeAgent.openLong
import com.capitalEugene.agent.exchange.okx.TradeAgent.openShort
import com.capitalEugene.agent.exchange.okx.TradeAgent.setCrossLeverage
import com.capitalEugene.agent.redis.RedisAgent.coroutineSaveToRedis
import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.common.utils.TradeUtils.generateTransactionId
import com.capitalEugene.model.TradingData
import com.capitalEugene.model.strategy.martin.MartinConfig
import com.capitalEugene.order.depthCache
import com.capitalEugene.order.priceCache
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.pow

data class PositionState(
    var longPosition: BigDecimal = BigDecimal.ZERO,
    var shortPosition: BigDecimal = BigDecimal.ZERO,
    var longEntryPrice: BigDecimal? = null,
    var shortEntryPrice: BigDecimal? = null,
    var longAddCount: Int = 1,
    var shortAddCount: Int = 1,
    var capital: BigDecimal = BigDecimal.valueOf(100.0),
    var longTransactionId : String? = null,
    var shortTransactionId: String? = null,
)

// key: "martin_${config.symbol}_${config.configName}"   value: positionState
val stateMap = mutableMapOf<String, PositionState>()

class MartinStrategy(
    private val configs: List<MartinConfig>
) {
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val logger = LoggerFactory.getLogger("martin_strategy")

    // 如果都是开btc，其中一批账户想要加仓次数为6次，一批账户想要加仓次数为5次
    // 那么可以定义两份策略，每份的symbol都是BTC-USDT-SWAP
    // 其中一份的maxAddPositionCount是5次另外一份是6次
    // 同理，也可以分别设置自己的初始开仓数
    //
    // 每一批config对应的账户可以有自己独立的MartinConfig 开平仓方式
    // 也有一份自己对应的运行时数据记录 持仓情况
    suspend fun start() {
        logger.info("🚀 启动多策略马丁循环...")

        configs.forEach { config ->
            config.accounts.forEach { setCrossLeverage(config.symbol, 100, it) }
            stateMap["martin_${config.symbol}_${config.configName}"] = PositionState()
        }

        while (true) {
            try {
                // 确保当前轮的所有子任务都完成后再进行下一轮
                configs.forEach { config ->
                    val price = priceCache[config.symbol] ?: return@forEach
                    val bids = depthCache[config.symbol]?.get("bids") ?: return@forEach
                    val asks = depthCache[config.symbol]?.get("asks") ?: return@forEach

                    if (bids.isEmpty() || asks.isEmpty() || price == BigDecimal.ZERO) return@forEach

                    val buyPower = getTotalPower(bids)
                    val sellPower = getTotalPower(asks)

                    // 每一个对应的state已在前面做过初始化
                    val state = stateMap["martin_${config.symbol}_${config.configName}"]!!

                    logger.info("buy_power: $buyPower     sell_power: $sellPower")
                    val longSignal = buyPower > sellPower * config.multiplesOfTheGap
                    val shortSignal = sellPower > buyPower * config.multiplesOfTheGap

                    handleLong(config, state, price, longSignal)
                    handleShort(config, state, price, shortSignal)
                }
            } catch (e: Exception) {
                logger.error("策略运行异常: ${e.message}", e)
            }
        }
    }

    private suspend fun handleLong(config: MartinConfig, state: PositionState, price: BigDecimal, signal: Boolean) {
        if (state.longPosition == BigDecimal.ZERO && signal) {
            operateOpen(config, state, price, true)
        } else if (state.longPosition != BigDecimal.ZERO) {
            val change = (price - state.longEntryPrice!!) / state.longEntryPrice!!
            // 持仓收益(usdt) = 张数 * 0.01(每张为0.01BTC) * 开仓均价 * 变化率
            val pnl = state.longPosition * OrderConstants.CONTRACT_VALUE * state.longEntryPrice!! * change
            logger.info("💰 多仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change * BigDecimal.valueOf(100))}%")
            processPosition(config, state, price, pnl, change, true)
        }
    }

    private suspend fun handleShort(config: MartinConfig, state: PositionState, price: BigDecimal, signal: Boolean) {
        if (state.shortPosition == BigDecimal.ZERO && signal) {
            operateOpen(config, state, price, false)
        } else if (state.shortPosition != BigDecimal.ZERO) {
            val change = (state.shortEntryPrice!! - price) / state.shortEntryPrice!!
            val pnl = state.shortPosition * OrderConstants.CONTRACT_VALUE * state.shortEntryPrice!! * change
            logger.info("💰 空仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change * BigDecimal.valueOf(100))}%")
            processPosition(config, state, price, pnl, change, false)
        }
    }

    private suspend fun operateOpen(config: MartinConfig, state: PositionState, price: BigDecimal, isLong: Boolean) {
        val side = if (isLong) "LONG" else "SHORT"
        config.accounts.forEach {
            if (isLong) openLong(config.symbol, price, config.positionSize, it)
            else openShort(config.symbol, price, config.positionSize, it)
        }
        val transactionId = generateTransactionId()
        // 只有在开仓的时候会更新对应的transactionId
        if (isLong) {
            state.longPosition = config.positionSize
            state.longEntryPrice = price
            state.longAddCount = 1
            state.longTransactionId = transactionId
        } else {
            state.shortPosition = config.positionSize
            state.shortEntryPrice = price
            state.shortAddCount = 1
            state.shortTransactionId = transactionId
        }
        logger.info("📈 开$side @ $price 仓位: ${config.positionSize}")
        saveToRedis(config, "open", config.positionSize, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), transactionId)
    }

    private suspend fun processPosition(config: MartinConfig, state: PositionState, price: BigDecimal, pnl: BigDecimal, change: BigDecimal, isLong: Boolean) {
        if (change >= config.tpRatio) {
            val side = if (isLong) "sell" else "buy"
            val position = if (isLong) state.longPosition else state.shortPosition
            val entryPrice = if (isLong) state.longEntryPrice else state.shortEntryPrice
            // 同一批次config的accounts，持仓情况是一样的
            config.accounts.forEach { closePosition(config.symbol, side, price, position.abs(), it) }
            state.capital += pnl
            logger.info("✅ 平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
            if (isLong) resetLong(state) else resetShort(state)
            // 止盈的时候用运行时的对应的transactionId
            val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
            saveToRedis(config, "close", BigDecimal.ZERO, position.abs() * OrderConstants.CONTRACT_VALUE * entryPrice!! * config.tpRatio, LocalDateTime.now().format(dateFormatter), transactionId!!)
        } else if (change < BigDecimal.ZERO && change.abs() > config.addPositionRatio) {
            val addCount = if (isLong) state.longAddCount else state.shortAddCount
            // 只有加仓到对应的阈值的时候且亏损率达到预设值才会涉及到止损
            if (change < -config.slRatio && addCount >= config.maxAddPositionCount) {
                val side = if (isLong) "sell" else "buy"
                val position = if (isLong) state.longPosition else state.shortPosition
                val entryPrice = if (isLong) state.longEntryPrice else state.shortEntryPrice
                config.accounts.forEach { closePosition(config.symbol, side, price, position.abs(), it) }
                state.capital += pnl
                logger.info("❌ 止损平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
                if (isLong) resetLong(state) else resetShort(state)
                // 止损的时候用运行时的对应的transactionId
                val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
                saveToRedis(config, "close", BigDecimal.ZERO, position.abs() * OrderConstants.CONTRACT_VALUE * entryPrice!! * -config.slRatio, LocalDateTime.now().format(dateFormatter), transactionId!!)
            } else if (addCount < config.maxAddPositionCount) {
                // 未达阈值，到达加仓触发点时可以继续加仓
                // 1 2       2 4       3 8
                // 4 16      5 32      6 64
                val addSize = config.positionSize * (BigDecimal.valueOf(2.0.pow(addCount)))
                if (isLong) {
                    state.longAddCount++
                    config.accounts.forEach { openLong(config.symbol, price, addSize, it) }
                    state.longPosition += addSize
                    state.longEntryPrice = (state.longEntryPrice!! * (state.longPosition - addSize) + price * addSize) / state.longPosition
                } else {
                    state.shortAddCount++
                    config.accounts.forEach { openShort(config.symbol, price, addSize, it) }
                    state.shortPosition += addSize
                    state.shortEntryPrice = (state.shortEntryPrice!! * (state.shortPosition - addSize) + price * addSize) / state.shortPosition
                }
                logger.info("➕ 加仓 @ $price 当前持仓: ${if (isLong) state.longPosition else state.shortPosition}")
                // 加仓的时候用运行时的对应的transactionId
                val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
                saveToRedis(config, "add", addSize, BigDecimal.ZERO, "", transactionId!!)
            }
        }
    }

    private fun getTotalPower(depth: List<List<BigDecimal>>): BigDecimal {
        var total = BigDecimal.ZERO
        depth.take(3).forEach {
            try {
                val price = it[0]
                val size = it[1]
                total += price * size
            } catch (e: Exception) {
                logger.error("解析深度失败: ${e.message}")
            }
        }
        return total
    }

    private fun saveToRedis(config: MartinConfig, op: String, addPositionAmount: BigDecimal, result: BigDecimal, time: String, transactionId: String) {
        // 不同name的策略分开存储，因为其配置项各不相同
        val data = TradingData(
            transactionId = transactionId,
            strategyName = "martin_${config.symbol}_${config.configName}",
            returnPerformance = result,
            openTime = if (op == "open") time else "",
            closeTime = if (op == "close") time else "",
            holdingAmount = addPositionAmount
        )

        coroutineSaveToRedis(data, op)
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
