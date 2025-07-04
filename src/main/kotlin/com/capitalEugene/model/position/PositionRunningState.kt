package com.capitalEugene.model.position

import kotlinx.serialization.Serializable

@Serializable
enum class PositionRunningState {
    Running,
    StoppedByRiskAgent,
    StoppedByManual,
    SelfError,
    OtherError,
}