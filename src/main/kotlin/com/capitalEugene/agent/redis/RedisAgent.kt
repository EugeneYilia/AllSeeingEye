package com.capitalEugene.agent.redis

import com.capitalEugene.model.TradingAggregateResult
import com.capitalEugene.model.TradingData
import io.lettuce.core.RedisClient
import io.lettuce.core.api.coroutines
import kotlinx.coroutines.*
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import org.slf4j.LoggerFactory

object RedisAgent {
    // 初始化 Redis
    private val redisClient = RedisClient.create("redis://127.0.0.1:6379/0")
    private val connection = redisClient.connect().coroutines()
    private val logger = LoggerFactory.getLogger("redis_agent")

    // 创建协程作用域
    // SupervisorJob(生命周期管理): 子协程失败时不会取消整个作用域或其他兄弟协程
    // Dispatchers.IO  运行在IO线程池中，专门用于IO密集型任务的线程池
    private val redisScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("redis-agent"))

    // 启动保存协程
    fun coroutineSaveToRedis(data: TradingData, operation: String) {
        redisScope.launch {
            try {
                saveToRedis(data, operation)
                logger.info("✅ 已写入 Redis，事务ID: ${data.transactionId}, 操作: $operation")
            } catch (e: Exception) {
                logger.error("❌ Redis 写入异常: ${e.message}", e)
            }
        }
    }

    suspend fun saveToRedis(data: TradingData, operation: String) {
        try {
            val tradeKey = "trading:${data.transactionId}"

            // data in db
            val existing = mutableMapOf<String, String>()
            connection.hgetall(tradeKey).collect { kv ->
                existing[kv.key] = kv.value
            }

            when (operation) {
                "open" -> {
                    connection.hset(
                        tradeKey, mapOf(
                            "transaction_id" to data.transactionId,
                            "strategy_name" to data.strategyName,
                            "profit_amount" to "0.0",
                            "open_time" to data.openTime,
                            "close_time" to "",
                            "holding_amount" to data.holdingAmount.toString()
                        )
                    )
                    connection.sadd("strategy:${data.strategyName}", tradeKey)
                    logger.info("✅ 开仓记录已写入: $tradeKey")
                }

                "add" -> {
                    if (existing.isNotEmpty()) {
                        val holdingStr = existing["holding_amount"]
                        val currentHolding = holdingStr?.toDoubleOrNull()

                        if (currentHolding == null) {
                            logger.error("❌ 加仓失败，持仓金额无效或缺失: $holdingStr")
                            return
                        }

                        val newHolding = currentHolding + data.holdingAmount
                        connection.hset(tradeKey, mapOf("holding_amount" to newHolding.toString()))
                        logger.info("✅ 加仓成功，新持仓金额: $newHolding")
                    } else {
                        logger.error("⚠️ 加仓失败，记录不存在: $tradeKey")
                    }
                }

                "close" -> {
                    if (existing.isNotEmpty()) {
                        connection.hset(
                            tradeKey, mapOf(
                                "close_time" to data.closeTime,
                                "profit_amount" to data.returnPerformance.toString()
                            )
                        )
                        logger.info("✅ 平仓更新成功: $tradeKey，利润金额: ${data.returnPerformance}")
                    } else {
                        logger.error("⚠️ 平仓失败，记录不存在: $tradeKey")
                    }
                }

                else -> {
                    logger.error("⚠️ 未知操作类型: $operation")
                }
            }
        } catch (e: Exception) {
            logger.error("❌ Redis 操作异常: ${e.message}", e)
        }
    }

    suspend fun aggregateTradingData(strategyName: String): TradingAggregateResult {
        val keys = mutableSetOf<String>()
        connection.smembers("strategy:$strategyName").collect { key ->
            keys.add(key)
        }

        val formatter = DateTimeFormatter.ISO_DATE_TIME
        var takeProfitCount = 0
        var stopLossCount = 0
        var totalTakeProfitAmount = 0.0
        var totalStopLossAmount = 0.0
        var totalHoldingDurationSec = 0.0
        var initialTotal = 0.0
        var finalTotal = 0.0
        val profitList = mutableListOf<Double>()
        val lossList = mutableListOf<Double>()

        for (key in keys) {
            val data = mutableMapOf<String, String>()
            connection.hgetall(key).collect { kv ->
                data[kv.key] = kv.value
            }

            val closeTimeRaw = data["close_time"]
            if (closeTimeRaw.isNullOrBlank()) continue

            val open_timeRaw = data["open_time"]
            if (open_timeRaw.isNullOrBlank()) continue

            val profitAmount = data["profit_amount"]?.toDoubleOrNull() ?: continue
            val holdingAmount = data["holding_amount"]?.toDoubleOrNull() ?: continue

            if (profitAmount > 0) {
                takeProfitCount++
                totalTakeProfitAmount += profitAmount
                profitList.add(profitAmount)
            } else if (profitAmount < 0) {
                stopLossCount++
                totalStopLossAmount += profitAmount
                lossList.add(profitAmount)
            }

            // 开仓资本数
            initialTotal += holdingAmount

            // 最终资本数
            finalTotal += holdingAmount + profitAmount

            val openTime = LocalDateTime.parse(open_timeRaw, formatter)
            val closeTime = LocalDateTime.parse(closeTimeRaw, formatter)
            val durationSec = Duration.between(openTime, closeTime).seconds.toDouble()
            // 总持仓时间
            totalHoldingDurationSec += durationSec
        }

        // 总交易次数
        val totalTrades = takeProfitCount + stopLossCount

        // 平均盈利金额
        val avgProfit = if (takeProfitCount > 0) totalTakeProfitAmount / takeProfitCount else 0.0
        // 平均亏损金额
        val avgLoss = if (stopLossCount > 0) totalStopLossAmount / stopLossCount else 0.0

        // 总资本变化情况
        val capitalChange = if (initialTotal > 0) finalTotal - initialTotal else 0.0

        // 平均持仓分钟数
        val avgHoldingMinutes = if (totalTrades > 0) totalHoldingDurationSec / 60 / totalTrades else 0.0


        return TradingAggregateResult(
            takeProfitCount,
            stopLossCount,
            totalTakeProfitAmount,
            totalStopLossAmount,
            avgProfit,
            avgLoss,
            capitalChange,
            avgHoldingMinutes
        )
    }
}