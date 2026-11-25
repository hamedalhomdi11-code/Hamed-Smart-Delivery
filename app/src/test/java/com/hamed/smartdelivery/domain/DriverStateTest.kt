package com.hamed.smartdelivery.domain

import org.junit.Assert.*
import org.junit.Test

class DriverStateTest {
    @Test fun orderLifecycleUsesSingleReducer() {
        var s = DriverSnapshot()
        val commands = listOf(DriverCommand.StartSearch, DriverCommand.OrderReceived, DriverCommand.NavigateToRestaurant, DriverCommand.ConfirmPickup, DriverCommand.NavigateToCustomer, DriverCommand.CompleteDelivery)
        commands.forEach { c -> val result = DriverReducer.reduce(s, c); assertTrue(result.accepted); s = result.snapshot }
        assertEquals(DriverMode.COMPLETED, s.mode)
    }
    @Test fun invalidCommandDoesNotMutateState() {
        val s = DriverSnapshot(DriverMode.IDLE)
        val result = DriverReducer.reduce(s, DriverCommand.OrderReceived)
        assertFalse(result.accepted)
        assertEquals(s, result.snapshot)
    }
}
