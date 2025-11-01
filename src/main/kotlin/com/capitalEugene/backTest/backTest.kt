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
    val years = listOf(2022, 2025) // 先测试最近年份
    val outputDir = File("HistoricalKLine")
    if (!outputDir.exists()) outputDir.mkdirs()

    println("🚀 开始下载历史K线数据...")
    println("📁 数据将保存到: ${outputDir.absolutePath}")
    println("=" * 50)

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

suspend fun downloadYearKlines(client: HttpClient, symbol: String, interval: String, year: Int): List<List<String>> {
    val allData = mutableListOf<List<String>>()
    val limit = 100
    val start = LocalDateTime.of(year, 1, 1, 0, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    val end = LocalDateTime.of(year, 12, 31, 23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli()
    var after: Long? = start
    var requestCount = 0

    println("   📅 时间范围: ${LocalDateTime.ofInstant(Instant.ofEpochMilli(start), ZoneOffset.UTC)} 至 ${LocalDateTime.ofInstant(Instant.ofEpochMilli(end), ZoneOffset.UTC)}")

    while (after != null && after < end) {
        requestCount++
        val url = "https://www.okx.com/api/v5/market/history-candles?instId=$symbol&bar=$interval&limit=$limit&after=$after"

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
                println("API错误: ${okxResponse.msg}")
                break
            }

            if (okxResponse.data.isEmpty()) {
                println("无更多数据")
                break
            }

            val filtered = okxResponse.data.filter { entry ->
                val ts = entry[0].toLong()
                ts <= end
            }

            allData.addAll(filtered)
            println("获取 ${filtered.size}条记录, 总计: ${allData.size}条")

            if (filtered.isNotEmpty()) {
                after = filtered.last()[0].toLong()
                if (filtered.size < limit) {
                    after = null
                }
            } else {
                after = null
            }

        } catch (e: Exception) {
            println("请求失败: ${e.message}")
            break
        }

        kotlinx.coroutines.delay(500)
    }

    println("   📊 下载完成: 共 ${requestCount}次请求, 总记录数: ${allData.size}")
    return allData.distinctBy { it[0] }.sortedBy { it[0].toLong() }
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
                val open = arr[1]
                val high = arr[2]
                val low = arr[3]
                val close = arr[4]
                val volume = arr[5]
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