package com.hamed.smartdelivery.domain

/** Single source of truth for manual and remote driver commands. */
enum class DriverMode { IDLE, SEARCHING, ORDER_RECEIVED, TO_RESTAURANT, PICKUP, TO_CUSTOMER, COMPLETED, SAFE_MODE }

sealed interface DriverCommand {
    data object StartSearch : DriverCommand
    data object StopSearch : DriverCommand
    data object OrderReceived : DriverCommand
    data object NavigateToRestaurant : DriverCommand
    data object ConfirmPickup : DriverCommand
    data object NavigateToCustomer : DriverCommand
    data object CompleteDelivery : DriverCommand
    data object EnterSafeMode : DriverCommand
    data object ExitSafeMode : DriverCommand
}

data class DriverSnapshot(val mode: DriverMode = DriverMode.IDLE, val lastCommandId: String? = null)

data class TransitionResult(val snapshot: DriverSnapshot, val accepted: Boolean, val reason: String? = null)

object DriverReducer {
    fun reduce(current: DriverSnapshot, command: DriverCommand, commandId: String? = null): TransitionResult {
        val next = when (command) {
            DriverCommand.StartSearch -> if (current.mode in setOf(DriverMode.IDLE, DriverMode.COMPLETED)) DriverMode.SEARCHING else null
            DriverCommand.StopSearch -> if (current.mode == DriverMode.SEARCHING) DriverMode.IDLE else null
            DriverCommand.OrderReceived -> if (current.mode == DriverMode.SEARCHING) DriverMode.ORDER_RECEIVED else null
            DriverCommand.NavigateToRestaurant -> if (current.mode == DriverMode.ORDER_RECEIVED) DriverMode.TO_RESTAURANT else null
            DriverCommand.ConfirmPickup -> if (current.mode == DriverMode.TO_RESTAURANT) DriverMode.PICKUP else null
            DriverCommand.NavigateToCustomer -> if (current.mode == DriverMode.PICKUP) DriverMode.TO_CUSTOMER else null
            DriverCommand.CompleteDelivery -> if (current.mode == DriverMode.TO_CUSTOMER) DriverMode.COMPLETED else null
            DriverCommand.EnterSafeMode -> if (current.mode != DriverMode.SAFE_MODE) DriverMode.SAFE_MODE else null
            DriverCommand.ExitSafeMode -> if (current.mode == DriverMode.SAFE_MODE) DriverMode.IDLE else null
        }
        return if (next == null) TransitionResult(current, false, "Command is not valid for ${current.mode}")
        else TransitionResult(DriverSnapshot(next, commandId), true)
    }
}
