package com.hamed.smartdelivery.domain

import org.junit.Assert.*
import org.junit.Test

class ArchitecturePolicyTest {
    @Test fun gpsModeHasOnlyStoppedAndTrackingStates() {
        assertEquals(GpsMode.TRACKING, GpsModeCycle.next(GpsMode.STOPPED))
        assertEquals(GpsMode.STOPPED, GpsModeCycle.next(GpsMode.TRACKING))
    }

    @Test fun waitBeginsAtClusterEntryAndExpiresAfterFiveMinutes() {
        val wait = ClusterPolicy.enterCluster("a", 1000L)
        assertFalse(ClusterPolicy.shouldAutoExclude(wait.copy(enteredAtEpochMillis = null), 999999L))
        assertFalse(ClusterPolicy.shouldAutoExclude(wait, 1000L + ClusterPolicy.WAIT_MILLIS - 1))
        assertTrue(ClusterPolicy.shouldAutoExclude(wait, 1000L + ClusterPolicy.WAIT_MILLIS))
    }

    @Test fun pausedTimeDoesNotCountTowardClusterWait() {
        val entered = ClusterPolicy.enterCluster("a", 1000L)
        val paused = ClusterPolicy.pause(entered, 60_000L)
        assertFalse(ClusterPolicy.shouldAutoExclude(paused, 400_000L))
        val resumed = ClusterPolicy.resume(paused, 120_000L)
        assertEquals(61_000L, resumed.enteredAtEpochMillis)
        assertFalse(ClusterPolicy.shouldAutoExclude(resumed, 360_999L))
        assertTrue(ClusterPolicy.shouldAutoExclude(resumed, 361_000L))
    }

    @Test fun manualSwitchIsImmediatelyAllowedBeforeArrivalAndDuringWait() {
        val notArrived = ClusterWaitState("a", null)
        val waiting = ClusterPolicy.enterCluster("a", 1000L)
        val paused = ClusterPolicy.pause(waiting, 30_000L)
        assertTrue(ClusterPolicy.canManualSwitch(notArrived, 1000L))
        assertTrue(ClusterPolicy.canManualSwitch(waiting, 1001L))
        assertTrue(ClusterPolicy.canManualSwitch(paused, 30_001L))
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
