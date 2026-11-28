package com.hamed.smartdelivery.domain

/** Three-action driver workflow agreed for Architecture 1. */
enum class DriverMode { IDLE, SEARCHING, ORDER_RECEIVED, SAFE_MODE }

sealed interface DriverCommand {
    data object StartSearch : DriverCommand
    data object StopSearch : DriverCommand
    data object OrderReceived : DriverCommand
    data object CompleteDelivery : DriverCommand
    data object EndWork : DriverCommand
    data object EnterSafeMode : DriverCommand
    data object ExitSafeMode : DriverCommand
}

data class DriverSnapshot(val mode: DriverMode = DriverMode.IDLE, val lastCommandId: String? = null)
data class TransitionResult(val snapshot: DriverSnapshot, val accepted: Boolean, val reason: String? = null)

object DriverReducer {
    fun reduce(current: DriverSnapshot, command: DriverCommand, commandId: String? = null): TransitionResult {
        val next = when (command) {
            DriverCommand.StartSearch -> if (current.mode == DriverMode.IDLE) DriverMode.SEARCHING else null
            DriverCommand.StopSearch -> if (current.mode == DriverMode.SEARCHING) DriverMode.IDLE else null
            DriverCommand.OrderReceived -> if (current.mode == DriverMode.SEARCHING) DriverMode.ORDER_RECEIVED else null
            // Completing a delivery immediately resumes automatic cluster search.
            DriverCommand.CompleteDelivery -> if (current.mode == DriverMode.ORDER_RECEIVED) DriverMode.SEARCHING else null
            DriverCommand.EndWork -> if (current.mode != DriverMode.IDLE) DriverMode.IDLE else null
            DriverCommand.EnterSafeMode -> if (current.mode != DriverMode.SAFE_MODE) DriverMode.SAFE_MODE else null
            DriverCommand.ExitSafeMode -> if (current.mode == DriverMode.SAFE_MODE) DriverMode.IDLE else null
        }
        return if (next == null) TransitionResult(current, false, "Command is not valid for ${current.mode}")
        else TransitionResult(DriverSnapshot(next, commandId), true)
    }
}
