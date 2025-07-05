package com.capitalEugene.model.tenant

import com.capitalEugene.secrets.ApiSecret
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
data class Account(
    // 可以接听电话的时间起点
    val activeHourStart: String? = null,
    // 可以接听电话的时间终点
    val activeHourEnd: String? = null,
    // 时区
    val timeZone: String? = null,
    // 账户名字
    val accountName: String? = null,
    @Transient
    // 交易密钥
    var apiSecrets: List<ApiSecret> = emptyList(),
    // 电话联系方式,可配置多个
    var phoneList : List<String>? = null,
    // 邮件联系方式,可配置多个
    var emailList : List<String>? = null,
)
