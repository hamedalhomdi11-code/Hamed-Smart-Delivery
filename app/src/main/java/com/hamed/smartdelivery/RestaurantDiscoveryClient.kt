package com.hamed.smartdelivery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hamed.smartdelivery.domain.ClusterCandidate
import com.hamed.smartdelivery.domain.ClusterScoring

data class RestaurantCluster(
    val latitude: Double,
    val longitude: Double,
    val restaurantCount: Int,
    val names: List<String>,
    val distanceMeters: Double,
    val searchRadiusMeters: Int = 5000,
    val category: String = "restaurant",
    val clusterId: String? = null,
    val district: String? = null,
    val clusterRadiusMeters: Double = 0.0
)

/**
 * Local-only discovery backed by the bundled Riyadh SQLite database.
 * No runtime Overpass/OSM requests are made from the app.
 */
object RestaurantDiscoveryClient {
    suspend fun findNearestClustersStreaming(
        context: Context,
        latitude: Double,
        longitude: Double,
        radiusMeters: Int = 5000,
        onUpdate: (List<RestaurantCluster>) -> Boolean
    ): List<RestaurantCluster> {
        require(latitude.isFinite() && latitude in -90.0..90.0) {
            "إحداثيات خط العرض غير صالحة"
        }
        require(longitude.isFinite() && longitude in -180.0..180.0) {
            "إحداثيات خط الطول غير صالحة"
        }

        val (allClusters, observedStats) = withContext(Dispatchers.IO) {
            val places = RiyadhPlacesDatabase.foodClusters(context)
            val orderDatabase = OrderClusterDatabase(context)
            val stats = try { orderDatabase.orderStats() } finally { orderDatabase.close() }
            places to stats
        }
        // Expand the search progressively. The old implementation stopped at 5 km,
        // which left drivers in Riyadh outskirts in an endless empty-results loop.
        // The callback returns true only after it has selected a non-excluded cluster.
        val radii = listOf(1000, 3000, 5000, 8000, 12000, 15000)
        val now = System.currentTimeMillis()
        val rankedAll = allClusters.map { cluster ->
            cluster.copy(
                distanceMeters = RiyadhPlacesDatabase.distanceMeters(
                    latitude, longitude, cluster.latitude, cluster.longitude
                ),
                searchRadiusMeters = 15000
            )
        }.let { measured ->
            val scores = ClusterScoring.rank(measured.map { cluster ->
                val id = cluster.clusterId?.takeIf { it.isNotBlank() }
                    ?: "geo:" + String.format(java.util.Locale.US, "%.5f:%.5f", cluster.latitude, cluster.longitude)
                val observed = observedStats[id]
                ClusterCandidate(
                    id = id,
                    distanceKm = cluster.distanceMeters / 1000.0,
                    restaurantCount = cluster.restaurantCount,
                    observedOrders = observed?.orderCount ?: 0,
                    hoursSinceLastObservedOrder = observed?.let {
                        (now - it.lastOrderAtEpochMs).coerceAtLeast(0L) / 3_600_000.0
                    },
                    publicRating = null,
                    estimatedFuelCostSar = null
                )
            }).associate { it.candidate.id to it.score }
            measured.sortedByDescending { cluster ->
                val id = cluster.clusterId?.takeIf { it.isNotBlank() }
                    ?: "geo:" + String.format(java.util.Locale.US, "%.5f:%.5f", cluster.latitude, cluster.longitude)
                scores[id] ?: 0.0
            }
        }

        var lastCandidates: List<RestaurantCluster> = emptyList()
        for (radius in radii) {
            val candidates = rankedAll.filter { it.distanceMeters <= radius }
            if (candidates.isEmpty()) continue
            lastCandidates = candidates
            if (onUpdate(candidates.take(30))) return candidates
        }

        // Riyadh's local DB covers a wider metro boundary than the initial 5 km
        // search. If no eligible cluster exists within 15 km, still surface the
        // nearest real DB clusters rather than reporting that the database is empty.
        val insideRiyadhCoverage = latitude in 24.05..25.15 && longitude in 46.20..47.35
        if (insideRiyadhCoverage) {
            val nearestFallback = rankedAll.sortedBy { it.distanceMeters }.take(30)
            if (nearestFallback.isNotEmpty()) {
                lastCandidates = nearestFallback
                if (onUpdate(nearestFallback)) return nearestFallback
            }
        }

        return lastCandidates
    }
}
