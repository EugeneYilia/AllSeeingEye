package com.capitalEugene.common.utils

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.math.BigDecimal
import java.math.RoundingMode

object BigDecimalAsStringSerializer : KSerializer<BigDecimal> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("BigDecimalAsString", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        encoder.encodeString(value.toPlainString())
    }

    override fun deserialize(decoder: Decoder): BigDecimal {
        return decoder.decodeString().toBigDecimal()
    }
}

fun BigDecimal.safeDiv(
    divisor: BigDecimal,
    scale: Int = 5,
    rounding: RoundingMode = RoundingMode.HALF_UP
): BigDecimal {
    return this.divide(divisor, scale, rounding)
}

fun BigDecimal.safeMultiply(
    multiplier: BigDecimal,
    scale: Int = 5,
    rounding: RoundingMode = RoundingMode.HALF_UP
): BigDecimal {
    return this.multiply(multiplier)
        .setScale(5, RoundingMode.HALF_UP)
}