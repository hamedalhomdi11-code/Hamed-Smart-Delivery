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
    fun routeDeviationTriggersForAccurateMovingFixFarFromRoute() {
        val location = android.location.Location("test").apply {
            latitude = 24.01
            longitude = 46.005
            speed = 12f
            accuracy = 8f
        }
        val match = RouteMatch(LatLng(24.01, 46.005), 1_100.0, 500.0, 0)
        assertTrue(RouteDeviationPolicy.shouldReroute(match, location))
    }

    @Test
    fun routeDeviationIgnoresSlowOrInaccurateGpsFixes() {
        val match = RouteMatch(LatLng(24.01, 46.005), 1_100.0, 500.0, 0)
        val slow = android.location.Location("test").apply { speed = 0.5f; accuracy = 5f }
        val inaccurate = android.location.Location("test").apply { speed = 8f; accuracy = 120f }
        assertTrue(!RouteDeviationPolicy.shouldReroute(match, slow))
        assertTrue(!RouteDeviationPolicy.shouldReroute(match, inaccurate))
        assertTrue(!RouteDeviationPolicy.shouldReroute(null, slow))
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
