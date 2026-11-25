package com.hamed.smartdelivery.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterScoringTest {
    @Test fun ranksUsingObservedDemandAndDistanceSignals() {
        val near = ClusterCandidate("near", .5, 2, 0, null, 4.7, 1.0)
        val active = ClusterCandidate("active", 2.0, 12, 8, 1.0, 4.3, 2.0)
        assertEquals("active", ClusterScoring.rank(listOf(near, active)).first().candidate.id)
    }
}
