package com.capitalEugene.trade.strategy.dogfood

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
import com.capitalEugene.order.depthCache
import com.capitalEugene.order.priceCache
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.math.pow

// key: "martin_${config.symbol}_${config.configName}"   value: positionState
val martinDogFoodStateMap = mutableMapOf<String, PositionState>()

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
            config.accounts.forEach {
                account -> account.apiSecrets.forEach { apiSecret ->
                setCrossLeverage(config.symbol, config.lever, apiSecret) }}

            val strategyName = "martin_${config.symbol}_${config.configName}"

            martinDogFoodStateMap.getOrPut(strategyName) {
                PositionState(
                    strategyShortName = "martin",
                    strategyFullName = strategyName,
                    capital = config.initCapital,
                    riskAgent = config.riskAgent
                ).also {
                    savePositionToMongo(it) // 仅在首次创建时保存
                }
            }
        }

        while (true) {
            try {
                // 确保当前轮的所有子任务都完成后再进行下一轮
                configs.forEach { config ->
                    // 每一个对应的state已在前面做过初始化
                    val state = martinDogFoodStateMap["martin_${config.symbol}_${config.configName}"]!!

                    state.riskAgent?.monitorState(
                        state,
                        config.accounts)

                    if(state.positionRunningState != PositionRunningState.Running){
                        return@forEach
                    }

                    // 不同config下用户想要去操作的币种可能都是不同的
                    val price = priceCache[config.symbol] ?: return@forEach

                    // Deep copy to deal with concurrent modification problems
                    val bids = depthCache[config.symbol]?.get("bids")?.safeSnapshot() ?: return@forEach
                    val asks = depthCache[config.symbol]?.get("asks")?.safeSnapshot() ?: return@forEach

                    if (bids.isEmpty() || asks.isEmpty() || price == BigDecimal.ZERO) return@forEach

                    // 特定币种下的买方力量和卖方力量
                    val buyPower = getTotalPower(price, bids, config.symbol)
                    val sellPower = getTotalPower(price, asks, config.symbol)

//                    logger.info("buy_power: $buyPower       sell_power: $sellPower")
                    val longSignal = buyPower > sellPower.safeMultiply(config.multiplesOfTheGap)
                    val shortSignal = sellPower > buyPower.safeMultiply(config.multiplesOfTheGap)

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
            val change = (price - state.longEntryPrice!!).safeDiv(state.longEntryPrice!!)
            // 持仓收益(usdt) = 张数 * 0.01(每张为0.01BTC) * 开仓均价 * 变化率
            val pnl = state.longPosition
                .safeMultiply(OrderConstants.CONTRACT_VALUE)
                .safeMultiply(state.longEntryPrice!!)
                .safeMultiply(change)
//            logger.info("💰 多仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change.safeMultiply(BigDecimal.valueOf(100)))}%")
            processPosition(config, state, price, pnl, change, true)
        }
    }

    private suspend fun handleShort(config: MartinConfig, state: PositionState, price: BigDecimal, signal: Boolean) {
        if (state.shortPosition == BigDecimal.ZERO && signal) {
            operateOpen(config, state, price, false)
        } else if (state.shortPosition != BigDecimal.ZERO) {
            val change = (state.shortEntryPrice!! - price).safeDiv(state.shortEntryPrice!!)
            val pnl = state.shortPosition
                .safeMultiply(OrderConstants.CONTRACT_VALUE)
                .safeMultiply(state.shortEntryPrice!!)
                .safeMultiply(change)
//            logger.info("💰 空仓盈亏: ${"%.5f".format(pnl)} 变动: ${"%.2f".format(change.safeMultiply(BigDecimal.valueOf(100)))}%")
            processPosition(config, state, price, pnl, change, false)
        }
    }

    private suspend fun operateOpen(config: MartinConfig, state: PositionState, price: BigDecimal, isLong: Boolean) {
        val side = if (isLong) "LONG" else "SHORT"
        config.accounts.forEach { account ->
            account.apiSecrets.forEach { apiSecret ->
                if (isLong) openLong(config.symbol, price, config.positionSize, apiSecret)
                else openShort(config.symbol, price, config.positionSize, apiSecret)
            }
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
//        logger.info("📈 开$side @ $price 仓位: ${config.positionSize}")
        buildRedisDataAndSave(config, "open", config.positionSize, BigDecimal.ZERO, LocalDateTime.now().format(dateFormatter), transactionId)
        savePositionToMongo(state)
    }

    private suspend fun processPosition(config: MartinConfig, state: PositionState, price: BigDecimal, pnl: BigDecimal, change: BigDecimal, isLong: Boolean) {
        if (change >= config.tpRatio) {
            val side = if (isLong) "sell" else "buy"
            val position = if (isLong) state.longPosition else state.shortPosition
            val entryPrice = if (isLong) state.longEntryPrice else state.shortEntryPrice
            // 同一批次config的accounts，持仓情况是一样的
            config.accounts.forEach { account ->
                account.apiSecrets.forEach { apiSecret ->
                    closePosition(config.symbol, side, price, position.abs(), apiSecret)
                }
            }
            state.capital += pnl
//            logger.info("✅ 平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
            if (isLong) resetLong(state) else resetShort(state)
            // 止盈的时候用运行时的对应的transactionId
            val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
            state.takeProfitCount += 1
            buildRedisDataAndSave(config, "close", BigDecimal.ZERO, position.abs().safeMultiply(OrderConstants.CONTRACT_VALUE).safeMultiply(entryPrice!!).safeMultiply(config.tpRatio), LocalDateTime.now().format(dateFormatter), transactionId!!)
            savePositionToMongo(state)
        } else if (change < BigDecimal.ZERO && change.abs() > config.addPositionRatio) {
            val addCount = if (isLong) state.longAddCount else state.shortAddCount
            // 只有加仓到对应的阈值的时候且亏损率达到预设值才会涉及到止损
            if (change < config.slRatio.negate() && addCount >= config.maxAddPositionCount) {
                val side = if (isLong) "sell" else "buy"
                val position = if (isLong) state.longPosition else state.shortPosition
                val entryPrice = if (isLong) state.longEntryPrice else state.shortEntryPrice
                config.accounts.forEach { account ->
                    account.apiSecrets.forEach { apiSecret ->
                        closePosition(config.symbol, side, price, position.abs(), apiSecret)
                    }
                }
                state.capital += pnl
//                logger.info("❌ 止损平仓 @ $price 盈亏: ${"%.5f".format(pnl)} 本金: ${"%.5f".format(state.capital)}")
                if (isLong) resetLong(state) else resetShort(state)
                // 止损的时候用运行时的对应的transactionId
                val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
                state.stopLossCount += 1
                buildRedisDataAndSave(config, "close", BigDecimal.ZERO, position.abs().safeMultiply(OrderConstants.CONTRACT_VALUE).safeMultiply(entryPrice!!).safeMultiply(config.slRatio.negate()), LocalDateTime.now().format(dateFormatter), transactionId!!)
                savePositionToMongo(state)
            } else if (addCount < config.maxAddPositionCount) {
                // 未达阈值，到达加仓触发点时可以继续加仓
                // 1 2       2 4       3 8
                // 4 16      5 32      6 64
                val addSize = config.positionSize.safeMultiply(BigDecimal.valueOf(2.0.pow(addCount)))
                if (isLong) {
                    state.longAddCount++
                    config.accounts.forEach { account ->
                        account.apiSecrets.forEach { apiSecret ->
                            openLong(config.symbol, price, addSize, apiSecret)
                        }
                    }
                    state.longPosition += addSize
                    state.longEntryPrice = (state.longEntryPrice!!.safeMultiply(state.longPosition - addSize) + price.safeMultiply(addSize)).safeDiv(state.longPosition)
                } else {
                    state.shortAddCount++
                    config.accounts.forEach { account ->
                        account.apiSecrets.forEach { apiSecret ->
                            openShort(config.symbol, price, addSize, apiSecret)
                        }
                    }
                    state.shortPosition += addSize
                    state.shortEntryPrice = (state.shortEntryPrice!!.safeMultiply(state.shortPosition - addSize) + price.safeMultiply(addSize)).safeDiv(state.shortPosition)
                }
//                logger.info("➕ 加仓 @ $price 当前持仓: ${if (isLong) state.longPosition else state.shortPosition}")
                // 加仓的时候用运行时的对应的transactionId
                val transactionId = if (isLong) state.longTransactionId else state.shortTransactionId
                buildRedisDataAndSave(config, "add", addSize, BigDecimal.ZERO, "", transactionId!!)
                savePositionToMongo(state)
            }
        }
    }

    // 与当前价格相差200以内的实际挂单对应的usdt量总额
    private fun getTotalPower(
        price: BigDecimal,
        depth: SortedMap<BigDecimal, BigDecimal>,
        symbol: String
    ): BigDecimal {
        var total = BigDecimal.ZERO
        val range = OrderConstants.symbol2RangMap[symbol]!!
        val lowerBound = price.subtract(range)
        val upperBound = price.add(range)

        val isAscending = depth.comparator() == null

        for ((price, sizeRaw) in depth.entries) {
            try {
                if (isAscending) {
                    if (price > upperBound) break
                } else {
                    if (price < lowerBound) break
                }

                // 1张 = 0.01BTC
                // 张数 * 0.01 = btc实际数量
                // 实际价格 * btc实际数量 = 此价格的实际usdt挂单量
                // 实时价格 * 张数 * 0.01
                //
                // 价值 = 当前价格 * 币种数量
                // 币种数量 = 张数 * 每张对应的币种数量
                // 价值 = 当前价格 * 张数 * 每张对应的币种数量
                total += price // 当前价格
                    .safeMultiply(sizeRaw) // 张数
                    .safeMultiply(OrderConstants.contractSizeMap[symbol]!!) // 每张对应的币种数量
            } catch (e: Exception) {
                logger.error("解析深度失败: ${e.message}")
            }
        }
        return total
    }

    private suspend fun buildRedisDataAndSave(config: MartinConfig, op: String, addPositionAmount: BigDecimal, result: BigDecimal, time: String, transactionId: String) {
        // 不同name的策略分开存储，因为其配置项各不相同
        val data = TradingData(
            transactionId = transactionId,
            strategyName = "martin_${config.symbol}_${config.configName}",
            returnPerformance = result,
            openTime = if (op == "open") time else "",
            closeTime = if (op == "close") time else "",
            holdingAmount = addPositionAmount
        )

        saveToRedis(data, op)
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
