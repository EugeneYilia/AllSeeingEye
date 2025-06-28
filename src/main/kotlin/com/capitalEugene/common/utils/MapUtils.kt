package com.capitalEugene.common.utils

import java.math.BigDecimal
import java.util.SortedMap
import java.util.TreeMap

@Suppress("UNCHECKED_CAST")
fun SortedMap<BigDecimal, BigDecimal>.safeSnapshot(): SortedMap<BigDecimal, BigDecimal> {
    val comparator = this.comparator() as? Comparator<BigDecimal> ?: naturalOrder()
    val copy = TreeMap<BigDecimal, BigDecimal>(comparator)
    synchronized(this) {
        copy.putAll(this)
    }
    return copy
}