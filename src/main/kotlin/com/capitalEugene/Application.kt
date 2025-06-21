import com.capitalEugene.configureRouting
import com.capitalEugene.order.printAggregatedDepth
import com.capitalEugene.order.startWs
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.websocket.*
import io.ktor.server.application.*
import io.ktor.server.netty.EngineMain
import kotlinx.coroutines.*

fun main(args: Array<String>) {
    EngineMain.main(args)
}

// 应用模块：既启动 API，也启动 WebSocket 与定时任务
fun Application.module() {
    configureRouting()

    // 构建一个具备WebSocket能力的Http CIO客户端
    val client = HttpClient(CIO) {
        install(WebSockets)
    }

    // 直接用 application.monitor.subscribe
    monitor.subscribe(ApplicationStarted) {
        launch {
            startWs(client)
        }
        launch {
            // isActive绑定的是当前协程的上下文
            // 每个协程都有自己的job    每个协程体内的isActive检查的就是自己的job的状态
            // isActive等同于 this.coroutineContext[Job]?.isActive
            // 每个协程体内的isActive判断的是自己的状态
            // jobA.cancel() 不影响 jobB.isActive
            //
            // 协程体内部isActive检查当前协程的状态    协程体外部用job对象的isActive判断具体协程状态
            // 多个协程每个协程的isActive独立，互不干扰
            //
            // 调用job.cancel() 主动取消时
            // 协程正常完成时
            // 协程出现异常被取消时
            // 父协程或作用域被取消
            while (isActive) {
                delay(1000)
                printAggregatedDepth()
            }
        }
    }

    monitor.subscribe(ApplicationStopping) {
        client.close()
        println("🛑 WebSocket 客户端已关闭")
    }
}
