package com.capitalEugene.common.utils

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("coroutine_utils")

val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
    logger.error("🌋 未捕获协程异常: ${throwable.message}", throwable)
}

// Dispatchers.IO是为I/O密集型任务(网络，文件，数据库)优化的，会自动扩容线程池数量以避免阻塞，不适合用于CPU密集任务
// CPU密集场景用IO，如果在CPU密集型场景用IO Dispatcher，会导致线程切换过多(切换到这个后发现仍在使用，一直用着线程)，调度开销变大，反而性能更差
val ioSchedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ioAgent") + exceptionHandler)

// cpu密集型任务的协程应该用Dispatchers.Default
// Dispatchers.Default使用的是共享的、基于cpu核心数的线程池(默认是cpu核心数 或 cpu核心数 * 2)
// 其专门为高计算量，低I/O操作的任务设计的，比如数学计算，加解密，排序，搜索，图算法，大量数据处理
val cpuSchedulerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CoroutineName("cpuAgent") + exceptionHandler)
