package com.capitalEugene.common.utils

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

object TradeUtils {
    fun generateTransactionId(): String {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        val randPart = Random.nextInt(1000, 10000)  // 生成 1000 到 9999 之间的四位随机数
        return "T$timestamp$randPart"
    }

    fun hmacSha256Base64(secret: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val keySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(keySpec)
        val hash = mac.doFinal(message.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }
}