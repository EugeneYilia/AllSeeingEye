package com.capitalEugene.timerJob

import com.capitalEugene.common.constants.OrderConstants
import com.capitalEugene.common.utils.safeDiv
import com.capitalEugene.common.utils.safeSnapshot
import com.capitalEugene.order.depthCache
import com.capitalEugene.order.priceCache
import org.slf4j.LoggerFactory
import java.math.BigDecimal

var symbolDiffMap : HashMap<String, ArrayList<BigDecimal>> = hashMapOf(
    OrderConstants.BTC_SWAP + "_bids_avg" to ArrayList(),
    OrderConstants.BTC_SWAP + "_asks_avg" to ArrayList(),
    OrderConstants.BTC_SWAP + "_asks_highMax" to ArrayList(),
    OrderConstants.BTC_SWAP + "_bids_lowMax" to ArrayList(),

    OrderConstants.ETH_SWAP + "_bids_avg" to ArrayList(),
    OrderConstants.ETH_SWAP + "_asks_avg" to ArrayList(),
    OrderConstants.ETH_SWAP + "_asks_highMax" to ArrayList(),
    OrderConstants.ETH_SWAP + "_bids_lowMax" to ArrayList(),

    OrderConstants.DOGE_SWAP + "_bids_avg" to ArrayList(),
    OrderConstants.DOGE_SWAP + "_asks_avg" to ArrayList(),
    OrderConstants.DOGE_SWAP + "_asks_highMax" to ArrayList(),
    OrderConstants.DOGE_SWAP + "_bids_lowMax" to ArrayList(),
)


object SchedulerJob {
    private val logger = LoggerFactory.getLogger("scheduler_job")

    fun calcOrderDiffValue() {
        calcDiff(OrderConstants.BTC_SWAP)
        calcDiff(OrderConstants.ETH_SWAP)
        calcDiff(OrderConstants.DOGE_SWAP)
    }

    private fun calcDiff(symbol: String){
        val bids = depthCache[symbol]?.get("bids")?.safeSnapshot()?.keys ?: return
        val asks = depthCache[symbol]?.get("asks")?.safeSnapshot()?.keys ?: return
        val price = priceCache[symbol] ?: return

        // 分别取两侧极值（你的口径：低侧最小价 => 最大支撑差值；高侧最大价 => 最大压力差值）
        val lowestBid  = price - (bids.minOrNull() ?: return)
        val highestAsk = (asks.maxOrNull() ?: return) - price

        // —— 关键修正：用 fold 从 0 开始累加（避免 reduce 把第一档 ask/bid 当作初值）——
        val askCount = asks.size.toLong()
        val bidCount = bids.size.toLong()

        val avgAsk = asks.fold(BigDecimal.ZERO) { acc, ask -> acc + (ask - price) }
            .safeDiv(BigDecimal.valueOf(askCount))

        val avgBid = bids.fold(BigDecimal.ZERO) { acc, bid -> acc + (price - bid) }
            .safeDiv(BigDecimal.valueOf(bidCount))

        // 如需自检，可加入以下断言（可选）
        // check(avgAsk <= highestAsk) { "avgAsk should not exceed highestAsk" }
        // check(avgBid <= lowestBid)  { "avgBid should not exceed lowestBid" }

        symbolDiffMap[symbol + "_bids_avg"]!!.add(avgBid)
        symbolDiffMap[symbol + "_asks_avg"]!!.add(avgAsk)
        symbolDiffMap[symbol + "_asks_highMax"]!!.add(highestAsk)
        symbolDiffMap[symbol + "_bids_lowMax"]!!.add(lowestBid)
    }

    // 分钟平均值转化为日平均值
    fun printOrderDiffValue(){
        for ((key, list) in symbolDiffMap) {
            if (list.isNotEmpty()) {
                logger.info(
                    "key = ${key}, daily average value = ${
                        list.fold(BigDecimal.ZERO) { acc, v -> acc + v }
                            .safeDiv(BigDecimal.valueOf(list.size.toLong()))
                    }"
                )
            } else {
                logger.info("key = ${key}, daily average value = N/A(empty list)")
            }
        }
    }
}