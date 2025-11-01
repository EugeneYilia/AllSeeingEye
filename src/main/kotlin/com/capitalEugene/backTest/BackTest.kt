package com.capitalEugene.backTest

import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.FileWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Serializable
data class OkxResponse(
    val code: String,
    val msg: String,
    val data: List<List<String>> = emptyList()
)

fun main() = runBlocking {
    println("🔧 使用HTTP代理: 127.0.0.1:33210")

    val client = HttpClient(CIO) {
        engine {
            // 配置HTTP代理
            proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", 33210))
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // 先测试连接
    println("🔍 测试代理连接...")
    try {
        val testResponse: HttpResponse = client.get("https://httpbin.org/ip")
        val responseBody: String = testResponse.bodyAsText()
        println("✅ 代理连接测试成功")
        println("📡 响应: ${responseBody.take(100)}...")
    } catch (e: Exception) {
        println("❌ 代理连接测试失败: ${e.message}")
        // 如果HTTP代理不行，尝试SOCKS代理
        println("🔄 尝试SOCKS代理...")
        client.close()
        mainWithSocksProxy()
        return@runBlocking
    }

    val symbols = listOf("BTC-USDT", "ETH-USDT", "SOL-USDT")
    val intervals = listOf("1H", "4H")
    val years = listOf(2022, 2025) // 与你之前一致，按需调整
    val outputDir = File("HistoricalKLine")
    if (!outputDir.exists()) outputDir.mkdirs()

    println("🚀 开始下载历史K线数据...")
    println("📁 数据将保存到: ${outputDir.absolutePath}")
    println("=".repeat(50))

    var totalTasks = symbols.size * intervals.size * years.size
    var completedTasks = 0

    for (symbol in symbols) {
        for (interval in intervals) {
            for (year in years) {
                completedTasks++
                println("\n📥 任务 $completedTasks/$totalTasks: 下载 $symbol $interval $year")

                try {
                    val startTime = System.currentTimeMillis()
                    val data = downloadYearKlines(client, symbol, interval, year)
                    val endTime = System.currentTimeMillis()
                    val duration = (endTime - startTime) / 1000.0

                    if (data.isNotEmpty()) {
                        saveToCsv(symbol, interval, year, data, outputDir)
                        println("✅ 完成 $symbol $interval $year, 记录数: ${data.size}, 耗时: ${"%.2f".format(duration)}秒")
                    } else {
                        println("⚠️  无数据 $symbol $interval $year, 耗时: ${"%.2f".format(duration)}秒")
                    }
                } catch (e: Exception) {
                    println("❌ 下载失败 $symbol $interval $year: ${e.message}")
                }

                if (completedTasks < totalTasks) {
                    println("⏸️  等待1秒后继续...")
                    kotlinx.coroutines.delay(1000)
                }
            }
        }
    }

    client.close()
    println("\n🎉 下载完成!")
}

// 使用SOCKS代理的备用函数
suspend fun mainWithSocksProxy() {
    println("🔧 使用SOCKS代理: 127.0.0.1:33211")

    val client = HttpClient(CIO) {
        engine {
            // 配置SOCKS代理
            proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 33211))
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    // 测试SOCKS代理连接
    println("🔍 测试SOCKS代理连接...")
    try {
        val testResponse: HttpResponse = client.get("https://httpbin.org/ip")
        val responseBody: String = testResponse.bodyAsText()
        println("✅ SOCKS代理连接测试成功")
        println("📡 响应: ${responseBody.take(100)}...")
    } catch (e: Exception) {
        println("❌ SOCKS代理连接测试失败: ${e.message}")
        println("💡 请检查：")
        println("   1. VPN是否已连接")
        println("   2. 代理端口是否正确")
        println("   3. 代理软件是否允许本地连接")
        client.close()
        return
    }

    // 下载代码...
    val symbols = listOf("BTC-USDT")
    val intervals = listOf("1H")
    val years = listOf(2024)
    val outputDir = File("HistoricalKLine")
    if (!outputDir.exists()) outputDir.mkdirs()

    for (symbol in symbols) {
        for (interval in intervals) {
            for (year in years) {
                println("\n📥 测试下载: $symbol $interval $year")
                try {
                    val data = downloadYearKlines(client, symbol, interval, year)
                    if (data.isNotEmpty()) {
                        saveToCsv(symbol, interval, year, data, outputDir)
                        println("✅ 测试成功! 记录数: ${data.size}")
                    } else {
                        println("⚠️  无数据")
                    }
                } catch (e: Exception) {
                    println("❌ 下载失败: ${e.message}")
                }
            }
        }
    }

    client.close()
}

/**
 * 关键函数：保留你原始分页风格（after），但自动检测返回时间戳单位（秒/毫秒）：
 * - 将返回的每条记录归一化为毫秒（用于过滤与保存）
 * - 分页时使用 API 返回的“原始单位”（afterRaw）
 */
suspend fun downloadYearKlines(client: HttpClient, symbol: String, interval: String, year: Int): List<List<String>> {
    val allData = mutableListOf<List<String>>()
    val limit = 100

    // 目标范围（毫秒）
    val startMs = LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()

    // afterRaw：初始使用毫秒（和你原始版本一致）
    var afterRaw: Long? = startMs
    // 未知时设为 null；第一次拿到响应后检测 true => 返回的是秒，false => 返回的是毫秒
    var responseReturnsSeconds: Boolean? = null

    var requestCount = 0

    println("   📅 时间范围: ${LocalDateTime.ofInstant(Instant.ofEpochMilli(startMs), ZoneOffset.UTC)} 至 ${LocalDateTime.ofInstant(Instant.ofEpochMilli(endMs), ZoneOffset.UTC)}")

    while (afterRaw != null) {
        // 构造请求参数：如果已知接口返回的是秒，则把 afterRaw 转为秒（因为 afterRaw 可能当前是毫秒）
        val afterParam = if (responseReturnsSeconds == true) {
            // afterRaw stored in raw units; ensure we pass seconds
            // if afterRaw currently is milliseconds (first loop), convert:
            if (afterRaw > 1_000_000_000_000L) afterRaw / 1000 else afterRaw
        } else {
            // 不确定或已知为毫秒 -> 直接传毫秒（和你原始版本一致）
            afterRaw
        }

        requestCount++
        val url = "https://www.okx.com/api/v5/market/history-candles?instId=$symbol&bar=$interval&limit=$limit&after=$afterParam"

        try {
            print("   🔄 请求 #$requestCount... ")
            val response: HttpResponse = client.get(url) {
                headers {
                    append("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    append("Accept", "application/json")
                }
            }

            val okxResponse: OkxResponse = response.body()

            if (okxResponse.code != "0") {
                println("API错误: ${okxResponse.code} - ${okxResponse.msg}")
                break
            }

            if (okxResponse.data.isEmpty()) {
                println("无更多数据")
                break
            }

            // 读取原始首/末时间戳，检测单位（秒还是毫秒）
            val firstRaw = okxResponse.data.first()[0].toLong()
            val lastRaw = okxResponse.data.last()[0].toLong()

            if (responseReturnsSeconds == null) {
                // 经验阈值：小于 1e12 -> 秒；否则毫秒
                responseReturnsSeconds = firstRaw < 1_000_000_000_000L
                println("   🔎 探测到接口返回时间戳单位: ${if (responseReturnsSeconds == true) "秒" else "毫秒"} (firstRaw=$firstRaw)")
                // 如果我们一开始把 afterRaw 当成毫秒，但接口返回秒，调整 afterRaw 为秒（避免下一次请求传错单位）
                if (responseReturnsSeconds == true && afterRaw != null && afterRaw > 1_000_000_000_000L) {
                    afterRaw = afterRaw / 1000
                }
            }

            // 调试：打印本批次返回的首/末原始时间戳
            println(" 返回批次原始时间戳: firstRaw=$firstRaw, lastRaw=$lastRaw")

            // 过滤并把每条记录归一化为毫秒（用于比较和保存）
            val filteredNormalized = okxResponse.data.mapNotNull { entry ->
                try {
                    val rawTs = entry[0].toLong()
                    val tsMs = if (responseReturnsSeconds == true) rawTs * 1000L else rawTs
                    // 只保留目标年份范围内的数据
                    if (tsMs in startMs..endMs) {
                        // 构造新的 entry：将第一个元素替换为归一化后的毫秒字符串，保留其余字段
                        val newEntry = mutableListOf<String>()
                        newEntry.add(tsMs.toString())
                        if (entry.size > 1) newEntry.addAll(entry.subList(1, entry.size))
                        newEntry.toList()
                    } else {
                        null
                    }
                } catch (ex: Exception) {
                    null
                }
            }

            allData.addAll(filteredNormalized)
            println(" 获取 ${filteredNormalized.size} 条符合年份的数据, 累计: ${allData.size} 条")

            // 计算本批次中原始最小时间戳（raw）；用于下一次分页（保持与接口相同的单位）
            val minRawInBatch = okxResponse.data.minByOrNull { it[0].toLong() }?.get(0)?.toLong()
            if (minRawInBatch == null) {
                println("   ⚠️ 无法取得本批次最小原始时间戳，停止翻页")
                break
            }

            // 如果接口返回的是秒，则 minRawInBatch 单位为秒；我们把 afterRaw 设为该 raw 单位（与接口一致）
            afterRaw = minRawInBatch
            // 继续翻页时，为避免重复，减 1 单位（raw 单位）
            afterRaw = afterRaw - 1

            // 检查归一化后是否已经跨出起始范围：先将 afterRaw 转为毫秒再比较
            val afterRawAsMs = if (responseReturnsSeconds == true) afterRaw * 1000L else afterRaw
            if (afterRawAsMs <= startMs) {
                println("   💡 已到达或超出开始时间，停止翻页 (afterRawAsMs=$afterRawAsMs <= startMs=$startMs)")
                break
            }

            // 如果返回的数据少于 limit，通常说明没有更多历史数据可拿
            if (okxResponse.data.size < limit) {
                println("   💡 本批次小于 limit（$limit），可能已到历史末端")
                break
            }
        } catch (e: Exception) {
            println("请求失败: ${e.message}")
            e.printStackTrace()
            break
        }

        kotlinx.coroutines.delay(500)
    }

    println("   📊 下载完成: 共 ${requestCount} 次请求, 原始总记录数: ${allData.size}")
    // 去重并按时间升序返回（这里 timestamp 已归一化为毫秒）
    return allData
        .distinctBy { it[0] }
        .sortedBy { it[0].toLong() }
}

fun saveToCsv(symbol: String, interval: String, year: Int, data: List<List<String>>, outputDir: File) {
    val fileName = "${symbol.replace("-USDT", "")}_${year}_${interval}.csv"
    val file = File(outputDir, fileName)
    FileWriter(file).use { writer ->
        writer.append("timestamp,datetime,open,high,low,close,volume,volume_ccy\n")
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        for (arr in data) {
            try {
                val ts = arr[0].toLong()
                val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), ZoneOffset.UTC).format(formatter)
                val open = arr.getOrNull(1) ?: ""
                val high = arr.getOrNull(2) ?: ""
                val low = arr.getOrNull(3) ?: ""
                val close = arr.getOrNull(4) ?: ""
                val volume = arr.getOrNull(5) ?: ""
                val volumeCcy = arr.getOrNull(6) ?: ""
                writer.append("$ts,$dt,$open,$high,$low,$close,$volume,$volumeCcy\n")
            } catch (e: Exception) {
                // 忽略单条记录错误
            }
        }
    }
    println("   💾 文件已保存: $fileName")
}

operator fun String.times(n: Int): String = repeat(n)
