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
import java.time.*
import java.time.format.DateTimeFormatter

object DownloadHistoricalKLineByDay {
    @Serializable
    data class OkxResponse(
        val code: String,
        val msg: String,
        val data: List<List<String>> = emptyList()
    )

    fun main() = runBlocking {
        println("🔧 使用HTTP代理: 127.0.0.1:33210")

        var client = HttpClient(CIO) {
            engine {
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

        // 测试代理
        try {
            val testResponse: HttpResponse = client.get("https://httpbin.org/ip")
            println("✅ 代理连接测试成功: ${testResponse.bodyAsText().take(100)}...")
        } catch (e: Exception) {
            println("❌ HTTP代理失败: ${e.message}, 尝试SOCKS代理...")
            client.close()
            client = buildClientWithSocksProxy()
        }

        val symbols = listOf("BTC-USDT-SWAP", "ETH-USDT-SWAP", "SOL-USDT-SWAP")
        val intervals = listOf("1D")
        val years = listOf(2022, 2023, 2024, 2025)
        val outputDir = File("HistoricalKLine")
        if (!outputDir.exists()) outputDir.mkdirs()

        println("🚀 开始下载历史日线K线数据...")
        val totalTasks = symbols.size * intervals.size * years.size
        var completedTasks = 0

        for (symbol in symbols) {
            for (interval in intervals) {
                for (year in years) {
                    completedTasks++
                    println("\n📥 任务 $completedTasks/$totalTasks: 下载 $symbol $interval $year")
                    try {
                        val data = downloadYearKlines(client, symbol, interval, year)
                        if (data.isNotEmpty()) saveToCsv(symbol, interval, year, data, outputDir)
                    } catch (e: Exception) {
                        println("❌ 下载失败 $symbol $interval $year: ${e.message}")
                    }
                    kotlinx.coroutines.delay(1000)
                }
            }
        }

        client.close()
        println("🎉 下载完成!")
    }

    // SOCKS代理备用
    suspend fun buildClientWithSocksProxy(): HttpClient {
        return HttpClient(CIO) {
            engine {
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
    }

    // 下载指定年份K线（修正版）
    suspend fun downloadYearKlines(
        client: HttpClient,
        symbol: String,
        interval: String,
        year: Int
    ): List<List<String>> {
        val allData = mutableListOf<List<String>>()
        val limit = 100

        // ✅ 用北京时间计算开始与结束时间
        val zone = ZoneId.of("Asia/Shanghai")
        val startMs = LocalDateTime.of(year, 1, 1, 0, 0)
            .atZone(zone).toInstant().toEpochMilli()
        val endMs = LocalDateTime.of(year, 12, 31, 23, 59, 59)
            .atZone(zone).toInstant().toEpochMilli()

        // ✅ 提前8小时，防止丢掉前一日的K线（因为OKX返回UTC）
        var afterRaw: Long? = endMs + 8 * 3600_000L
        var responseReturnsSeconds = false

        try {
            val probeUrl = "https://www.okx.com/api/v5/market/history-candles?instId=$symbol&bar=$interval&limit=1"
            val probeResp: OkxResponse = client.get(probeUrl).body()
            if (probeResp.code == "0" && probeResp.data.isNotEmpty()) {
                val probeFirst = probeResp.data.first()[0].toLong()
                responseReturnsSeconds = probeFirst < 1_000_000_000_000L
                afterRaw = if (responseReturnsSeconds) (endMs / 1000L + 8 * 3600) else (endMs + 8 * 3600_000L)
            }
        } catch (_: Exception) {
        }

        var requestCount = 0
        while (afterRaw != null) {
            requestCount++
            val url =
                "https://www.okx.com/api/v5/market/history-candles?instId=$symbol&bar=$interval&limit=$limit&after=$afterRaw"
            try {
                val okxResponse: OkxResponse = client.get(url).body()
                if (okxResponse.code != "0" || okxResponse.data.isEmpty()) break

                val filtered = okxResponse.data.mapNotNull { entry ->
                    try {
                        val tsMs = if (responseReturnsSeconds) entry[0].toLong() * 1000L else entry[0].toLong()
                        if (tsMs in (startMs - 8 * 3600_000L)..(endMs + 8 * 3600_000L)) {
                            listOf(tsMs.toString()) + entry.subList(1, entry.size)
                        } else null
                    } catch (_: Exception) {
                        null
                    }
                }
                allData.addAll(filtered)

                val minRawInBatch = okxResponse.data.minByOrNull { it[0].toLong() }?.get(0)?.toLong()
                if (minRawInBatch == null) break
                afterRaw = minRawInBatch - 1
                val afterRawMs = if (responseReturnsSeconds) afterRaw * 1000L else afterRaw
                if (afterRawMs <= startMs - 8 * 3600_000L) break
                if (okxResponse.data.size < limit) break
            } catch (_: Exception) {
                break
            }

            kotlinx.coroutines.delay(500)
        }
        return allData.distinctBy { it[0] }.sortedBy { it[0].toLong() }
    }

    // 保存CSV（输出北京时间）
    fun saveToCsv(symbol: String, interval: String, year: Int, data: List<List<String>>, outputDir: File) {
        val file = File(outputDir, "${symbol}_${year}_${interval}.csv")
        FileWriter(file).use { writer ->
            writer.append("timestamp,datetime,open,high,low,close,volume,volume_ccy\n")
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val zone = ZoneId.of("Asia/Shanghai")

            for (arr in data) {
                try {
                    val ts = arr[0].toLong()
                    val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(ts), zone).format(formatter)
                    val open = arr.getOrNull(1) ?: ""
                    val high = arr.getOrNull(2) ?: ""
                    val low = arr.getOrNull(3) ?: ""
                    val close = arr.getOrNull(4) ?: ""
                    val volume = arr.getOrNull(5) ?: ""
                    val volumeCcy = arr.getOrNull(6) ?: ""
                    writer.append("$ts,$dt,$open,$high,$low,$close,$volume,$volumeCcy\n")
                } catch (_: Exception) {
                }
            }
        }
        println("   💾 已保存: ${file.name}")
    }
}