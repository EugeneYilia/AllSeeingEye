package com.capitalEugene.timerJob

import com.capitalEugene.order.klineCache
import org.slf4j.LoggerFactory

object InMemoryClearJob {
    private val logger = LoggerFactory.getLogger("in_memory_clear_job")

    fun cleanOldKlineData(){
        val millisInTenDays = 10 * 24 * 60 * 60 * 1000L
        val millisInFortyDays = 40 * 24 * 60 * 60 * 1000L

        for ((instId, cache) in klineCache) {
            if (cache.size < 2) continue

            val earliest = cache.firstOrNull()?.timestamp ?: continue
            val latest = cache.lastOrNull()?.timestamp ?: continue

            if (latest - earliest > millisInFortyDays) {
                val cutoff = earliest + millisInTenDays
                val removeIndex = cache.indexOfFirst { it.timestamp >= cutoff }
                if (removeIndex > 0) {
                    cache.subList(0, removeIndex).clear()
                    logger.info("🧹 [$instId] 清除前10天的K线数据，共移除 $removeIndex 条，当前缓存数量: ${cache.size}")
                }
            }
        }

    }
}