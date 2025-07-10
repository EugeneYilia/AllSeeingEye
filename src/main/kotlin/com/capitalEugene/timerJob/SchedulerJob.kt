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

        // 该分钟下的最小支撑位和最大压力位
        val lowestBid = price - (bids.minOrNull() ?: return)
        val highestAsk = (asks.maxOrNull() ?: return) - price

        // 该分钟的平均支撑位差值和平均压力位差值
        val avgBid = bids.reduce { acc, bid -> acc + (price - bid) }.safeDiv(BigDecimal.valueOf(bids.size.toLong()))
        val avgAsk = asks.reduce { acc, ask -> acc + (ask - price) }.safeDiv(BigDecimal.valueOf(asks.size.toLong()))

        symbolDiffMap[symbol + "_bids_avg"]!!.add(avgBid)
        symbolDiffMap[symbol + "_asks_avg"]!!.add(avgAsk)
        symbolDiffMap[symbol + "_asks_highMax"]!!.add(highestAsk)
        symbolDiffMap[symbol + "_bids_lowMax"]!!.add(lowestBid)
    }

    // 分钟平均值转化为日平均值
    fun printOrderDiffValue(){
        for ((key, list) in symbolDiffMap) {
            if(list.isNotEmpty()){
                logger.info("key = ${key}, daily average value = ${list.reduce { acc, value -> acc + value }.safeDiv(BigDecimal.valueOf(list.size.toLong()))}")
            } else {
                logger.error("key = ${key}, daily average value = N/A(empty list)")
            }
        }
    }
}