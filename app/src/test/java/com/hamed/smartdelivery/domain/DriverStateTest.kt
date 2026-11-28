package com.hamed.smartdelivery.domain

import org.junit.Assert.*
import org.junit.Test

class DriverStateTest {
    @Test fun agreedThreeActionWorkflowResumesSearchAfterDelivery() {
        var state = DriverSnapshot()
        state = DriverReducer.reduce(state, DriverCommand.StartSearch).snapshot
        assertEquals(DriverMode.SEARCHING, state.mode)
        state = DriverReducer.reduce(state, DriverCommand.OrderReceived).snapshot
        assertEquals(DriverMode.ORDER_RECEIVED, state.mode)
        state = DriverReducer.reduce(state, DriverCommand.CompleteDelivery).snapshot
        assertEquals(DriverMode.SEARCHING, state.mode)
    }

    @Test fun endWorkReturnsSearchingOrDeliveryToIdle() {
        val searching = DriverReducer.reduce(DriverSnapshot(), DriverCommand.StartSearch).snapshot
        val endedSearch = DriverReducer.reduce(searching, DriverCommand.EndWork)
        assertTrue(endedSearch.accepted)
        assertEquals(DriverMode.IDLE, endedSearch.snapshot.mode)

        val delivering = DriverReducer.reduce(searching, DriverCommand.OrderReceived).snapshot
        val endedDelivery = DriverReducer.reduce(delivering, DriverCommand.EndWork)
        assertTrue(endedDelivery.accepted)
        assertEquals(DriverMode.IDLE, endedDelivery.snapshot.mode)
    }

    @Test fun invalidCommandDoesNotMutateState() {
        val state = DriverSnapshot(DriverMode.IDLE)
        val result = DriverReducer.reduce(state, DriverCommand.OrderReceived)
        assertFalse(result.accepted)
        assertEquals(state, result.snapshot)
    }

    @Test fun searchCanBeStoppedAndRestarted() {
        val searching = DriverReducer.reduce(DriverSnapshot(), DriverCommand.StartSearch).snapshot
        val stopped = DriverReducer.reduce(searching, DriverCommand.StopSearch)
        assertTrue(stopped.accepted)
        assertEquals(DriverMode.IDLE, stopped.snapshot.mode)
    }
}
