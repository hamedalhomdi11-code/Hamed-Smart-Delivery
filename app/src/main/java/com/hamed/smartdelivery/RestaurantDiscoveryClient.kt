package com.hamed.smartdelivery

import android.location.Location
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.*

data class RestaurantCluster(
    val latitude: Double,
    val longitude: Double,
    val restaurantCount: Int,
    val names: List<String>,
    val distanceMeters: Double
)

/**
 * Live public-map discovery. No local restaurant catalogue or private delivery-account data.
 * OSM tags describe mapped venues, not live order volume or guaranteed opening status.
 */
object RestaurantDiscoveryClient {
    private data class Venue(val lat: Double, val lon: Double, val name: String)

    fun findNearestClusters(latitude: Double, longitude: Double, radiusMeters: Int = 4500): List<RestaurantCluster> {
        val query = """
            [out:json][timeout:25];
            (
              node["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
              way["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
              relation["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
            );
            out center tags;
        """.trimIndent()
        val endpoints = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")
        var lastError: Exception? = null
        for (endpoint in endpoints) {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 12000
                    readTimeout = 30000
                    doOutput = true
                    setRequestProperty("User-Agent", "HamedSmartDelivery/0.4.0 (Android)")
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                    setRequestProperty("Accept", "application/json")
                }
                val body = "data=" + URLEncoder.encode(query, "UTF-8")
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                if (connection.responseCode !in 200..299) throw IllegalStateException("Overpass HTTP ${connection.responseCode}")
                val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                val elements = root.optJSONArray("elements") ?: return emptyList()
                val venues = ArrayList<Venue>()
                for (i in 0 until elements.length()) {
                    val item = elements.optJSONObject(i) ?: continue
                    val center = item.optJSONObject("center")
                    val lat = if (center != null) center.optDouble("lat", Double.NaN) else item.optDouble("lat", Double.NaN)
                    val lon = if (center != null) center.optDouble("lon", Double.NaN) else item.optDouble("lon", Double.NaN)
                    if (!lat.isFinite() || !lon.isFinite()) continue
                    val tags = item.optJSONObject("tags")
                    val name = tags?.optString("name").orEmpty().ifBlank { tags?.optString("brand").orEmpty() }
                    venues.add(Venue(lat, lon, name))
                }
                return cluster(venues, latitude, longitude)
            } catch (error: Exception) {
                lastError = error
            } finally {
                connection?.disconnect()
            }
        }
        throw lastError ?: IllegalStateException("تعذر الوصول إلى بيانات المطاعم")
    }

    private fun cluster(venues: List<Venue>, originLat: Double, originLon: Double): List<RestaurantCluster> {
        if (venues.isEmpty()) return emptyList()
        val remaining = venues.indices.toMutableSet()
        val groups = ArrayList<List<Venue>>()
        while (remaining.isNotEmpty()) {
            val seed = remaining.first()
            remaining.remove(seed)
            val group = ArrayList<Venue>()
            val queue = ArrayDeque<Int>()
            queue.add(seed)
            while (queue.isNotEmpty()) {
                val index = queue.removeFirst()
                val current = venues[index]
                group.add(current)
                val nearby = remaining.filter { candidate ->
                    val other = venues[candidate]
                    distance(current.lat, current.lon, other.lat, other.lon) <= 450.0
                }
                nearby.forEach { remaining.remove(it); queue.add(it) }
            }
            groups.add(group)
        }
        val denseGroups = groups.filter { it.size >= 2 }.ifEmpty { groups }
        return denseGroups.map { group ->
            val lat = group.map { it.lat }.average()
            val lon = group.map { it.lon }.average()
            RestaurantCluster(
                latitude = lat,
                longitude = lon,
                restaurantCount = group.size,
                names = group.map { it.name }.filter { it.isNotBlank() }.distinct().take(5),
                distanceMeters = distance(originLat, originLon, lat, lon)
            )
        }.sortedWith(compareBy<RestaurantCluster> { it.distanceMeters / (1.0 + ln(1.0 + it.restaurantCount)) }
            .thenByDescending { it.restaurantCount })
    }

    private fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, result)
        return result[0].toDouble()
    }
}
