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
    val distanceMeters: Double,
    val searchRadiusMeters: Int = 5000
)

/**
 * Best-effort public OpenStreetMap discovery.
 * Overpass is a shared community service, not a guaranteed production API.
 */
object RestaurantDiscoveryClient {
    private data class Venue(val lat: Double, val lon: Double, val name: String)

    private val radiiMeters = listOf(1000, 3000, 5000)
    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter"
    )

    fun findNearestClusters(latitude: Double, longitude: Double, radiusMeters: Int = 5000): List<RestaurantCluster> {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "إحداثيات خط العرض غير صالحة" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "إحداثيات خط الطول غير صالحة" }

        val maximumRadius = radiusMeters.coerceIn(1000, 10000)
        val radii = radiiMeters.filter { it <= maximumRadius }.ifEmpty { listOf(maximumRadius) }
        var lastError: Exception? = null

        for (radius in radii) {
            var radiusResult: List<RestaurantCluster>? = null
            for (endpoint in endpoints) {
                try {
                    val venues = requestVenues(endpoint, latitude, longitude, radius)
                    radiusResult = cluster(venues, latitude, longitude, radius)
                    break
                } catch (error: Exception) {
                    lastError = error
                }
            }
            val found = radiusResult
            if (found != null && (found.size >= 3 || radius == radii.last())) return found
        }

        if (lastError != null) {
            throw IllegalStateException(
                "تعذر الاتصال بمصادر بيانات المطاعم بعد تجربة نطاقات 1 و3 و5 كم. تحقق من الإنترنت أو VPN ثم أعد المحاولة.",
                lastError
            )
        }
        return emptyList()
    }

    private fun requestVenues(endpoint: String, latitude: Double, longitude: Double, radiusMeters: Int): List<Venue> {
        val query = """
            [out:json][timeout:20];
            (
              node["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
              way["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
              relation["amenity"~"^(restaurant|fast_food|cafe|food_court)$"](around:$radiusMeters,$latitude,$longitude);
            );
            out center tags;
        """.trimIndent()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10000
                readTimeout = 22000
                doOutput = true
                useCaches = false
                setRequestProperty("User-Agent", "HamedSmartDelivery/0.4.1 (Android; OSM Overpass client)")
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
            }
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val status = connection.responseCode
            if (status !in 200..299) {
                val detail = runCatching {
                    (connection.errorStream ?: connection.inputStream).bufferedReader().use { it.readText().take(300) }
                }.getOrDefault("")
                throw IllegalStateException("Overpass HTTP $status ${detail.take(120)}")
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val elements = JSONObject(response).optJSONArray("elements") ?: return emptyList()
            val venues = ArrayList<Venue>(elements.length())
            val seen = HashSet<String>()
            for (i in 0 until elements.length()) {
                val item = elements.optJSONObject(i) ?: continue
                val center = item.optJSONObject("center")
                val lat = if (center != null) center.optDouble("lat", Double.NaN) else item.optDouble("lat", Double.NaN)
                val lon = if (center != null) center.optDouble("lon", Double.NaN) else item.optDouble("lon", Double.NaN)
                if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
                val tags = item.optJSONObject("tags")
                val name = tags?.optString("name").orEmpty().ifBlank { tags?.optString("brand").orEmpty() }
                val key = "${name.trim().lowercase()}:${(lat * 10000).roundToInt()}:${(lon * 10000).roundToInt()}"
                if (seen.add(key)) venues.add(Venue(lat, lon, name))
            }
            return venues
        } finally {
            connection?.disconnect()
        }
    }

    private fun cluster(venues: List<Venue>, originLat: Double, originLon: Double, radiusMeters: Int): List<RestaurantCluster> {
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
                distanceMeters = distance(originLat, originLon, lat, lon),
                searchRadiusMeters = radiusMeters
            )
        }.sortedWith(
            compareBy<RestaurantCluster> { it.distanceMeters / (1.0 + ln(1.0 + it.restaurantCount)) }
                .thenByDescending { it.restaurantCount }
        )
    }

    private fun distance(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val result = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, result)
        return result[0].toDouble()
    }
}
