import java.math.BigDecimal
import java.math.RoundingMode

// 小工具：统一除法精度 & 打印
fun BigDecimal.divInt(n: Int): BigDecimal =
    this.divide(BigDecimal.valueOf(n.toLong()), 8, RoundingMode.HALF_UP)

fun BigDecimal.pretty(): String = this.stripTrailingZeros().toPlainString()

fun main() {
    val price = BigDecimal("100")
    val asks = listOf(
        BigDecimal("101"),
        BigDecimal("102"),
        BigDecimal("103")
    )

    // ❌ 你的 reduce 写法（演示错在哪）
    val wrongSum = asks.reduce { acc, ask -> acc.add(ask.subtract(price)) }
    val wrongAvg = wrongSum.divInt(asks.size)

    // ✅ 正确写法：fold 从 0 开始
    val correctSum = asks.fold(BigDecimal.ZERO) { acc, ask -> acc.add(ask.subtract(price)) }
    val correctAvg = correctSum.divInt(asks.size)

    // ✅ 另一种安全写法：先映射为差值再 reduce
    val manualSum = asks.map { it.subtract(price) }.fold(BigDecimal.ZERO) { a, b -> a.add(b) }
    val manualAvg = manualSum.divInt(asks.size)

    println("asks = ${asks.map { it.pretty() }}, price = ${price.pretty()}")
    println("reduce(avg) = ${wrongAvg.pretty()}   <-- 错（系统性偏大）")
    println("fold  (avg) = ${correctAvg.pretty()} <-- 对")
    println("manual(avg) = ${manualAvg.pretty()}  <-- 对")

    // 验证“错法 - 正解 = price/size”
    val diff = wrongAvg.subtract(correctAvg)
    println("difference (reduce - fold) = ${diff.pretty()}  ; price/size = ${price.divInt(asks.size).pretty()}")

    // ====== 轨迹：让差异一目了然 ======
    println("\n[Trace - reduce]")
    var acc = asks.first() // 注意：acc 从 A1 开始！
    println("init acc = A1 = ${acc.pretty()}")
    for (i in 1 until asks.size) {
        val term = asks[i].subtract(price)
        acc = acc.add(term)
        println("i=$i, ask=${asks[i].pretty()}, add(ask - price)=${term.pretty()} -> acc=${acc.pretty()}")
    }
    println("final wrongAvg = acc/size = ${acc.divInt(asks.size).pretty()}")

    println("\n[Trace - fold]")
    var acc2 = BigDecimal.ZERO // 从 0 开始
    for (i in asks.indices) {
        val term = asks[i].subtract(price)
        acc2 = acc2.add(term)
        println("i=$i, ask=${asks[i].pretty()}, add(ask - price)=${term.pretty()} -> acc=${acc2.pretty()}")
    }
    println("final correctAvg = acc/size = ${acc2.divInt(asks.size).pretty()}")
}
