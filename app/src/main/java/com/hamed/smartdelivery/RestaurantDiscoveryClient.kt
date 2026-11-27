package com.hamed.smartdelivery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln
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
        onUpdate: (List<RestaurantCluster>) -> Unit
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
        val radii = listOf(1000, 3000, radiusMeters.coerceIn(3000, 15000)).distinct().sorted()
        var best: List<RestaurantCluster> = emptyList()

        for (radius in radii) {
            val nearby = allClusters.map { cluster ->
                cluster.copy(
                    distanceMeters = RiyadhPlacesDatabase.distanceMeters(
                        latitude, longitude, cluster.latitude, cluster.longitude
                    ),
                    searchRadiusMeters = radius
                )
            }.filter { it.distanceMeters <= radius }

            val now = System.currentTimeMillis()
            val rankedIds = ClusterScoring.rank(nearby.map { cluster ->
                val id = cluster.clusterId?.takeIf { it.isNotBlank() }
                    ?: "geo:" + String.format(java.util.Locale.US, "%.5f:%.5f", cluster.latitude, cluster.longitude)
                val observed = observedStats[id]
                ClusterCandidate(
                    id = id,
                    distanceKm = cluster.distanceMeters / 1000.0,
                    restaurantCount = cluster.restaurantCount,
                    observedOrders = observed?.orderCount ?: 0,
                    hoursSinceLastObservedOrder = observed?.let { (now - it.lastOrderAtEpochMs).coerceAtLeast(0L) / 3_600_000.0 },
                    publicRating = null,
                    estimatedFuelCostSar = null
                )
            }).mapIndexed { index, ranked -> ranked.candidate.id to ranked.score }.toMap()
            val sortedNearby = nearby.sortedByDescending { cluster ->
                val id = cluster.clusterId?.takeIf { it.isNotBlank() }
                    ?: "geo:" + String.format(java.util.Locale.US, "%.5f:%.5f", cluster.latitude, cluster.longitude)
                rankedIds[id] ?: 0.0
            }

            if (nearby.isNotEmpty()) {
                best = sortedNearby
                onUpdate(sortedNearby.take(30))
            }
        }
        return best
    }
}
