package com.hamed.smartdelivery.domain

import org.junit.Assert.*
import org.junit.Test

class ArchitecturePolicyTest {
    @Test fun gpsCycleMatchesAgreedFourPresses() {
        var mode = GpsMode.STOPPED
        mode = GpsModeCycle.next(mode); assertEquals(GpsMode.TRACKING, mode)
        mode = GpsModeCycle.next(mode); assertEquals(GpsMode.NAVIGATION, mode)
        mode = GpsModeCycle.next(mode); assertEquals(GpsMode.STOPPED, mode)
        mode = GpsModeCycle.next(mode); assertEquals(GpsMode.TRACKING, mode)
    }

    @Test fun waitBeginsAtClusterEntryAndExpiresAfterFiveMinutes() {
        val wait = ClusterPolicy.enterCluster("a", 1000L)
        assertFalse(ClusterPolicy.shouldAutoExclude(wait.copy(enteredAtEpochMillis = null), 999999L))
        assertFalse(ClusterPolicy.shouldAutoExclude(wait, 1000L + ClusterPolicy.WAIT_MILLIS - 1))
        assertTrue(ClusterPolicy.shouldAutoExclude(wait, 1000L + ClusterPolicy.WAIT_MILLIS))
    }

    @Test fun manualAndAutomaticExclusionDurationsDiffer() {
        val manual = ClusterPolicy.manualSwitch("a", 1000L)
        val automatic = ClusterPolicy.automaticTimeout("b", 1000L)
        assertEquals(ClusterPolicy.MANUAL_EXCLUSION_MILLIS, manual.untilEpochMillis - 1000L)
        assertEquals(ClusterPolicy.AUTO_EXCLUSION_MILLIS, automatic.untilEpochMillis - 1000L)
    }

    @Test fun visitedClusterNeedsFourOtherClustersUnlessItProducedAnOrder() {
        val visit = ClusterVisit("a", 1L, otherClustersVisitedSince = 3)
        assertFalse(ClusterPolicy.canRepeat(visit, false))
        assertTrue(ClusterPolicy.canRepeat(visit.copy(otherClustersVisitedSince = 4), false))
        assertTrue(ClusterPolicy.canRepeat(visit, true))
    }

    @Test fun resetExclusionsDoesNotChangeVisitHistory() {
        val history = ClusterVisit("a", 1L, otherClustersVisitedSince = 2)
        ClusterPolicy.resetTemporaryExclusions()
        assertEquals(2, history.otherClustersVisitedSince)
    }
}
