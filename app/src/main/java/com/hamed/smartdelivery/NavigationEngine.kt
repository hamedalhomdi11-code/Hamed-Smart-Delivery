package com.hamed.smartdelivery

import android.location.Location
import org.maplibre.android.geometry.LatLng
import kotlin.math.*

/**
 * Lightweight alpha-beta style GPS filter. It predicts from the previous speed/heading,
 * then corrects toward the measured fix according to reported accuracy. Gross jumps are
 * rejected instead of being animated as real movement.
 */
class GpsMotionFilter {
    private var previous: Location? = null

    fun reset() { previous = null }

    fun filter(raw: Location): Location? {
        if (!raw.latitude.isFinite() || !raw.longitude.isFinite() ||
            raw.latitude !in -90.0..90.0 || raw.longitude !in -180.0..180.0) return null

        val prior = previous
        if (prior == null) {
            val first = Location(raw)
            previous = first
            return Location(first)
        }

        val dt = ((raw.time - prior.time).coerceAtLeast(1L) / 1000.0).coerceIn(0.001, 30.0)
        val measuredDistance = distanceMeters(prior.latitude, prior.longitude, raw.latitude, raw.longitude)
        val accuracy = if (raw.hasAccuracy()) raw.accuracy.coerceAtLeast(3f) else 35f
        val reportedSpeed = if (raw.hasSpeed() && raw.speed.isFinite()) raw.speed.coerceAtLeast(0f) else 0f
        val plausibleSpeed = max(42.0, reportedSpeed.toDouble() + 18.0)
        val jumpAllowance = max(accuracy.toDouble() * 2.5, 25.0)
        if (dt < 20.0 && measuredDistance > plausibleSpeed * dt + jumpAllowance) {
            return null
        }

        val priorSpeed = if (prior.hasSpeed()) prior.speed.toDouble().coerceAtLeast(0.0) else 0.0
        val priorBearing = if (prior.hasBearing()) prior.bearing.toDouble() else 0.0
        val predictedDistance = (priorSpeed * dt).coerceAtMost(180.0)
        val predicted = destinationPoint(prior.latitude, prior.longitude, priorBearing, predictedDistance)
        val alpha = (0.92 - accuracy / 140.0).coerceIn(0.22, 0.88)
        val latitude = predicted.first + (raw.latitude - predicted.first) * alpha
        val longitude = predicted.second + (raw.longitude - predicted.second) * alpha

        val filtered = Location(raw).apply {
            this.latitude = latitude
            this.longitude = longitude
            val derivedSpeed = distanceMeters(prior.latitude, prior.longitude, latitude, longitude) / dt
            speed = (if (raw.hasSpeed()) reportedSpeed * 0.65 + derivedSpeed * 0.35 else derivedSpeed)
                .coerceIn(0.0, 55.0).toFloat()
            val targetBearing = when {
                raw.hasBearing() && reportedSpeed >= 1.2 -> raw.bearing.toDouble()
                derivedSpeed >= 1.2 -> bearingDegrees(prior.latitude, prior.longitude, latitude, longitude)
                else -> priorBearing
            }
            val delta = ((targetBearing - priorBearing + 540.0) % 360.0) - 180.0
            bearing = ((priorBearing + delta * alpha + 360.0) % 360.0).toFloat()
            this.accuracy = accuracy
        }
        previous = filtered
        return Location(filtered)
    }
}

data class RouteMatch(
    val snappedPoint: LatLng,
    val crossTrackMeters: Double,
    val progressMeters: Double,
    val segmentIndex: Int
)

data class RouteSplit(val completed: List<LatLng>, val remaining: List<LatLng>)

/** Geometry-aware map matching and progress projection against the active route. */
object RouteGeometryMatcher {
    private const val EARTH_RADIUS_METERS = 6371008.8

    fun match(
        location: LatLng,
        route: List<LatLng>,
        previousProgressMeters: Double = 0.0
    ): RouteMatch? {
        if (route.size < 2) return null
        var cumulative = 0.0
        var best: RouteMatch? = null
        var bestScore = Double.POSITIVE_INFINITY
        val cosLat = cos(Math.toRadians(location.latitude)).coerceAtLeast(0.01)

        fun xy(point: LatLng): Pair<Double, Double> {
            val x = Math.toRadians(point.longitude - location.longitude) * EARTH_RADIUS_METERS * cosLat
            val y = Math.toRadians(point.latitude - location.latitude) * EARTH_RADIUS_METERS
            return x to y
        }

        for (index in 0 until route.lastIndex) {
            val a = route[index]
            val b = route[index + 1]
            val (ax, ay) = xy(a)
            val (bx, by) = xy(b)
            val dx = bx - ax
            val dy = by - ay
            val lengthSquared = dx * dx + dy * dy
            val segmentLength = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
            val fraction = if (lengthSquared <= 0.0001) 0.0 else
                ((-(ax * dx + ay * dy) / lengthSquared).coerceIn(0.0, 1.0))
            val px = ax + dx * fraction
            val py = ay + dy * fraction
            val crossTrack = hypot(px, py)
            val progress = cumulative + segmentLength * fraction
            val backwardPenalty = max(0.0, previousProgressMeters - progress - 12.0) * 1.8
            val score = crossTrack + backwardPenalty
            if (score < bestScore) {
                val snappedLat = location.latitude + Math.toDegrees(py / EARTH_RADIUS_METERS)
                val snappedLon = location.longitude + Math.toDegrees(px / (EARTH_RADIUS_METERS * cosLat))
                bestScore = score
                best = RouteMatch(LatLng(snappedLat, snappedLon), crossTrack, progress, index)
            }
            cumulative += segmentLength
        }
        return best
    }

    fun splitAtProgress(route: List<LatLng>, progressMeters: Double): RouteSplit {
        if (route.size < 2 || progressMeters <= 0.0) return RouteSplit(emptyList(), route)
        val completed = mutableListOf(route.first())
        val remaining = mutableListOf<LatLng>()
        var cumulative = 0.0
        for (index in 0 until route.lastIndex) {
            val a = route[index]
            val b = route[index + 1]
            val segmentLength = haversine(a.latitude, a.longitude, b.latitude, b.longitude)
            if (cumulative + segmentLength < progressMeters) {
                completed += b
            } else {
                val fraction = if (segmentLength <= 0.001) 0.0 else
                    ((progressMeters - cumulative) / segmentLength).coerceIn(0.0, 1.0)
                val split = LatLng(
                    a.latitude + (b.latitude - a.latitude) * fraction,
                    a.longitude + (b.longitude - a.longitude) * fraction
                )
                if (completed.lastOrNull() != split) completed += split
                remaining += split
                remaining += route.drop(index + 1)
                break
            }
            cumulative += segmentLength
        }
        if (remaining.isEmpty()) return RouteSplit(route, emptyList())
        return RouteSplit(completed, remaining)
    }

    fun routeLengthMeters(route: List<LatLng>): Double =
        route.zipWithNext().sumOf { (a, b) -> haversine(a.latitude, a.longitude, b.latitude, b.longitude) }

    private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

data class RouteFuelEstimate(val liters: Double, val litersPer100Km: Double, val label: String = "estimated")

/** General gasoline-car estimate; not a vehicle-specific or measured fuel reading. */
object GasolineFuelEstimator {
    fun estimate(distanceMeters: Double, durationSeconds: Double): RouteFuelEstimate {
        val km = (distanceMeters / 1000.0).coerceAtLeast(0.0)
        val hours = durationSeconds / 3600.0
        val averageKmh = if (hours > 0.0) km / hours else 45.0
        val trafficFactor = when {
            averageKmh < 12.0 -> 1.42
            averageKmh < 22.0 -> 1.27
            averageKmh < 35.0 -> 1.12
            averageKmh <= 85.0 -> 1.0
            else -> 1.14
        }
        val consumption = 7.8 * trafficFactor
        return RouteFuelEstimate(km * consumption / 100.0, consumption)
    }
}

object BalancedRouteScorer {
    /** 55% estimated arrival time and 45% estimated gasoline use. */
    fun chooseIndex(distances: List<Double>, durations: List<Double>): Int {
        require(distances.isNotEmpty() && distances.size == durations.size)
        if (distances.size == 1) return 0
        val fuels = distances.indices.map { GasolineFuelEstimator.estimate(distances[it], durations[it]).liters }
        val minTime = durations.minOrNull()?.coerceAtLeast(1.0) ?: 1.0
        val minFuel = fuels.minOrNull()?.coerceAtLeast(0.01) ?: 0.01
        return distances.indices.minByOrNull { index ->
            0.55 * (durations[index] / minTime) + 0.45 * (fuels[index] / minFuel)
        } ?: 0
    }
}

private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val radius = 6371008.8
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2.0 * radius * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

private fun destinationPoint(lat: Double, lon: Double, bearing: Double, distance: Double): Pair<Double, Double> {
    val radius = 6371008.8
    val angular = distance / radius
    val theta = Math.toRadians(bearing)
    val phi1 = Math.toRadians(lat)
    val lambda1 = Math.toRadians(lon)
    val phi2 = asin(sin(phi1) * cos(angular) + cos(phi1) * sin(angular) * cos(theta))
    val lambda2 = lambda1 + atan2(sin(theta) * sin(angular) * cos(phi1), cos(angular) - sin(phi1) * sin(phi2))
    return Math.toDegrees(phi2) to Math.toDegrees(lambda2)
}

private fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val lambda = Math.toRadians(lon2 - lon1)
    val y = sin(lambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(lambda)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}
