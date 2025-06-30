package com.capitalEugene.agent.mongo

import com.capitalEugene.model.position.PositionState
import com.mongodb.client.model.ReplaceOptions
import org.litote.kmongo.coroutine.coroutine
import org.litote.kmongo.reactivestreams.KMongo
import org.slf4j.LoggerFactory
import org.litote.kmongo.eq

object MongoAgent {
    // kmongo + ktor场景下，不需要配置数据库连接池
    // kmongo驱动默认使用连接池，池化是自动的，线程安全的，默认最大连接数是100(可配置)，空闲连接，最大等待时间等都有默认配置
    private val client = KMongo.createClient().coroutine
    val database = client.getDatabase("freemasonry")
    val positionCollection = database.getCollection<PositionState>("positions")

    private val logger = LoggerFactory.getLogger("mongo_agent")

    // name, position
    suspend fun savePositionToMongo(positionState: PositionState) {
        try {
            if (positionState.strategyFullName == null) {
                logger.warn("⚠️ 写入失败：strategyFullName 为空，无法识别唯一记录")
                return
            }

            val filter = PositionState::strategyFullName eq positionState.strategyFullName
            positionCollection.replaceOne(
                filter,
                positionState,
                ReplaceOptions().upsert(true)
            )
        } catch (e: Exception) {
            logger.error("❌ mongo 写入异常: ${e.message}", e)
        }
    }

    suspend fun getAllPositions(): List<PositionState>?{
        try {
            return positionCollection.find().toList()
        } catch (e: Exception) {
            logger.error("❌ mongo 读取异常: ${e.message}", e)
            return null
        }
    }
}