package com.capitalEugene.trade.strategy.dogfood

import com.capitalEugene.agent.exchange.okx.TradeAgent.*
import com.capitalEugene.common.utils.TradeUtils.generateTransactionId
import com.capitalEugene.model.TradingData
import com.capitalEugene.model.strategy.martin.MartinConfig
import com.capitalEugene.order.depthCache
import com.capitalEugene.order.priceCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.pow

class MartinStrategy(
    private val configs: List<MartinConfig>,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val CONTRACT_VALUE = 0.01
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    private val logger = LoggerFactory.getLogger("martin_strategy")

    private data class PositionState(
        var longPosition: Double = 0.0,
        var shortPosition: Double = 0.0,
        var longEntryPrice: Double? = null,
        var shortEntryPrice: Double? = null,
        var longAddCount: Int = 1,
        var shortAddCount: Int = 1,
        var capital: Double = 100.0
    )

    private val stateMap = mutableMapOf<String, PositionState>()

    suspend fun start() {
        logger.info("🚀 启动多策略马丁循环...")

        configs.forEach { config ->
            config.accounts.forEach { setCrossLeverage(config.symbol, 100, it) }
            stateMap[config.symbol] = PositionState()
        }

        while (true) {
            try {
                configs.forEach { config ->
                    val price = priceCache[config.symbol] ?: return@forEach
                    val bids = depthCache[config.symbol]?.get("bids") ?: return@forEach
                    val asks = depthCache[config.symbol]?.get("asks") ?: return@forEach

                    if (bids.isEmpty() || asks.isEmpty() || price == 0.0) return@forEach

                    val buyPower = getTotalUsdt(bids)
                    val sellPower = getTotalUsdt(asks)

                    val state = stateMap[config.symbol]!!

                    val longSignal = buyPower > sellPower * 2
                    val shortSignal = sellPower > buyPower * 2

                    handleLong(config, state, price, longSignal)
                    handleShort(config, state, price, shortSignal)
                }
                delay(200) // 防止空转占满CPU
            } catch (e: Exception) {
                logger.error("策略运行异常: ${e.message}", e)
            }
        }
    }

    private fun handleLong(config: MartinConfig, state: PositionState, price: Double, signal: Boolean) {
        if (state.longPosition == 0.0 && signal) {
            operateOpen(config, state, price, true)
        } else if (state.longPosition != 0.0) {
            val change = (price - state.longEntryPrice!!) / state.longEntryPrice!!
            val pnl = state.longPosition * CONTRACT_VALUE * state.longEntryPrice!! * change
            logger.info("💰 多仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change * 100)}%")
            processPosition(config, state, price, pnl, change, true)
        }
    }

    private fun handleShort(config: MartinConfig, state: PositionState, price: Double, signal: Boolean) {
        if (state.shortPosition == 0.0 && signal) {
            operateOpen(config, state, price, false)
        } else if (state.shortPosition != 0.0) {
            val change = (state.shortEntryPrice!! - price) / state.shortEntryPrice!!
            val pnl = state.shortPosition * CONTRACT_VALUE * state.shortEntryPrice!! * change
            logger.info("💰 空仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change * 100)}%")
            processPosition(config, state, price, pnl, change, false)
        }
    }

    private fun operateOpen(config: MartinConfig, state: PositionState, price: Double, isLong: Boolean) {
        val side = if (isLong) "LONG" else "SHORT"
        config.accounts.forEach {
            if (isLong) openLong(config.symbol, price, config.positionSize, it)
            else openShort(config.symbol, price, config.positionSize, it)
        }
        if (isLong) {
            state.longPosition = config.positionSize
            state.longEntryPrice = price
            state.longAddCount = 1
        } else {
            state.shortPosition = config.positionSize
            state.shortEntryPrice = price
            state.shortAddCount = 1
        }
        logger.info("📈 开$side @ $price 仓位: ${config.positionSize}")
        saveToRedis(config, "open", config.positionSize, 0.0, LocalDateTime.now().format(dateFormatter))
    }

    private fun processPosition(config: MartinConfig, state: PositionState, price: Double, pnl: Double, change: Double, isLong: Boolean) {
        if (change >= config.tpRatio) {
            val side = if (isLong) "sell" else "buy"
            val position = if (isLong) state.longPosition else state.shortPosition
            config.accounts.forEach { closePosition(config.symbol, side, price, abs(position), it) }
            state.capital += pnl
            logger.info("✅ 平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
            if (isLong) resetLong(state) else resetShort(state)
            saveToRedis(config, "close", 0.0, config.tpRatio, LocalDateTime.now().format(dateFormatter))
        } else if (change < 0 && abs(change) > config.addPositionRatio) {
            val addCount = if (isLong) state.longAddCount else state.shortAddCount
            if (change < -config.slRatio && addCount >= 8) {
                val side = if (isLong) "sell" else "buy"
                val position = if (isLong) state.longPosition else state.shortPosition
                config.accounts.forEach { closePosition(config.symbol, side, price, abs(position), it) }
                state.capital += pnl
                logger.info("❌ 止损平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
                if (isLong) resetLong(state) else resetShort(state)
                saveToRedis(config, "close", 0.0, -config.slRatio, LocalDateTime.now().format(dateFormatter))
            } else if (addCount < 8) {
                val addSize = config.positionSize * 2.0.pow(addCount)
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
                saveToRedis(config, "add", addSize, 0.0, "")
            }
        }
    }

    private fun getTotalUsdt(depth: List<List<String>>): Double {
        var total = 0.0
        depth.take(3).forEach {
            try {
                val price = it[0].toDouble()
                val size = it[1].toDouble()
                total += price * size * CONTRACT_VALUE
            } catch (e: Exception) {
                logger.error("解析深度失败: ${e.message}")
            }
        }
        return total
    }

    private fun saveToRedis(config: MartinConfig, op: String, size: Double, result: Double, time: String) {
        val data = TradingData(
            transactionId = generateTransactionId(),
            strategyName = "martin_multi",
            resultRatio = result,
            openTime = if (op == "open") time else "",
            closeTime = if (op == "close") time else "",
            holdingAmount = size
        )
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                threadSaveToRedis(data, op)
            }
        }
    }

    private fun resetLong(state: PositionState) {
        state.longPosition = 0.0
        state.longEntryPrice = null
        state.longAddCount = 1
    }

    private fun resetShort(state: PositionState) {
        state.shortPosition = 0.0
        state.shortEntryPrice = null
        state.shortAddCount = 1
    }
}
