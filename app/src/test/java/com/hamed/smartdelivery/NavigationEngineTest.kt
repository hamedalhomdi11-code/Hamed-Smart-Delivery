package com.hamed.smartdelivery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.android.geometry.LatLng

class NavigationEngineTest {
    @Test
    fun routeMatcherProjectsFixOntoTheCorrectSegment() {
        val route = listOf(LatLng(24.0, 46.0), LatLng(24.0, 46.01))
        val match = RouteGeometryMatcher.match(LatLng(24.0001, 46.005), route)
        assertNotNull(match)
        assertTrue(match!!.crossTrackMeters < 20.0)
        assertTrue(match.progressMeters in 500.0..600.0)
        assertEquals(0, match.segmentIndex)
    }

    @Test
    fun routeProgressSplitsCompletedGreyAndRemainingBlueGeometry() {
        val route = listOf(LatLng(24.0, 46.0), LatLng(24.0, 46.01), LatLng(24.01, 46.01))
        val halfFirstSegment = RouteGeometryMatcher.routeLengthMeters(route) * 0.25
        val split = RouteGeometryMatcher.splitAtProgress(route, halfFirstSegment)
        assertTrue(split.completed.size >= 2)
        assertTrue(split.remaining.size >= 2)
        assertEquals(split.completed.last().latitude, split.remaining.first().latitude, 0.0000001)
        assertEquals(split.completed.last().longitude, split.remaining.first().longitude, 0.0000001)
    }

    @Test
    fun balancedRouterCanPreferFasterRouteWhenFuelPenaltyIsReasonable() {
        val selected = BalancedRouteScorer.chooseIndex(
            distances = listOf(8_000.0, 12_000.0),
            durations = listOf(1_200.0, 600.0)
        )
        assertEquals(1, selected)
    }

    @Test
    fun gasolineEstimateIsPositiveAndExplicitlyEstimated() {
        val estimate = GasolineFuelEstimator.estimate(10_000.0, 900.0)
        assertTrue(estimate.liters > 0.0)
        assertEquals("estimated", estimate.label)
    }
}
