package com.hamed.smartdelivery

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/**
 * Requests a driving route from the public OSRM demo endpoint.
 * This endpoint is suitable for development/testing only, not a production SLA.
 */
object OsrmRouteClient {
    fun fetchRoute(startLatitude: Double, startLongitude: Double, endLatitude: Double, endLongitude: Double): RouteResult? {
        val endpoint = "https://router.project-osrm.org/route/v1/driving/" +
            "$startLongitude,$startLatitude;$endLongitude,$endLatitude?overview=full&geometries=geojson"
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 18000
                setRequestProperty("User-Agent", "HamedSmartDelivery/0.2.0 (Android)")
                setRequestProperty("Accept", "application/json")
            }
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            if (root.optString("code") != "Ok") return null
            val route = root.getJSONArray("routes").optJSONObject(0) ?: return null
            val coordinates = route.getJSONObject("geometry").getJSONArray("coordinates")
            val points = buildList {
                for (index in 0 until coordinates.length()) {
                    val pair = coordinates.getJSONArray(index)
                    add(LatLng(pair.getDouble(1), pair.getDouble(0)))
                }
            }
            if (points.size < 2) null else RouteResult(
                points = points,
                distanceMeters = route.optDouble("distance", 0.0),
                durationSeconds = route.optDouble("duration", 0.0)
            )
        } catch (_: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
