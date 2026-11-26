package com.hamed.smartdelivery

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ln

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

        val allClusters = withContext(Dispatchers.IO) {
            RiyadhPlacesDatabase.foodClusters(context)
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
                .sortedWith(
                    compareBy<RestaurantCluster> {
                        it.distanceMeters / (1.0 + ln(1.0 + it.restaurantCount.coerceAtLeast(0)))
                    }.thenByDescending { it.restaurantCount }
                )

            if (nearby.isNotEmpty()) {
                best = nearby
                onUpdate(nearby.take(30))
            }
        }
        return best
    }
}
