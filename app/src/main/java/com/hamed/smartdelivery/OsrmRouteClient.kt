package com.hamed.smartdelivery

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: LatLng?
)

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep> = emptyList()
)

/**
 * Requests a driving route with turn-by-turn steps from the public OSRM demo endpoint.
 * The public demo service is best-effort for development and is not a production SLA.
 */
object OsrmRouteClient {
    fun fetchRoute(startLatitude: Double, startLongitude: Double, endLatitude: Double, endLongitude: Double): RouteResult? {
        // Two independent public OSRM demo endpoints. The second is a fallback only;
        // both are best-effort services and neither is a production SLA.
        val bases = listOf(
            "https://router.project-osrm.org/route/v1/driving/",
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
        )
        val coordinates = "$startLongitude,$startLatitude;$endLongitude,$endLatitude"
        for (base in bases) {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = base + coordinates + "?overview=full&geometries=geojson&steps=true"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 9000
                    readTimeout = 14000
                    setRequestProperty("User-Agent", "HamedSmartDelivery/0.4.3 (Android)")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) continue
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                if (root.optString("code") != "Ok") continue
                val route = root.optJSONArray("routes")?.optJSONObject(0) ?: continue
                val coordinatesArray = route.getJSONObject("geometry").getJSONArray("coordinates")
                val points = buildList {
                    for (index in 0 until coordinatesArray.length()) {
                        val pair = coordinatesArray.getJSONArray(index)
                        add(LatLng(pair.getDouble(1), pair.getDouble(0)))
                    }
                }
                if (points.size < 2) continue
                val steps = buildList {
                    val legs = route.optJSONArray("legs") ?: return@buildList
                    for (legIndex in 0 until legs.length()) {
                        val leg = legs.optJSONObject(legIndex) ?: continue
                        val legSteps = leg.optJSONArray("steps") ?: continue
                        for (stepIndex in 0 until legSteps.length()) {
                            val step = legSteps.optJSONObject(stepIndex) ?: continue
                            val maneuver = step.optJSONObject("maneuver")
                            val type = maneuver?.optString("type").orEmpty()
                            val modifier = maneuver?.optString("modifier").orEmpty()
                            val road = step.optString("name").takeIf { it.isNotBlank() }
                            val instruction = formatInstruction(type, modifier, road)
                            val coordinate = maneuver?.optJSONArray("location")
                            val point = if (coordinate != null && coordinate.length() >= 2)
                                LatLng(coordinate.getDouble(1), coordinate.getDouble(0)) else null
                            if (instruction.isNotBlank()) add(
                                RouteStep(instruction, step.optDouble("distance", 0.0), step.optDouble("duration", 0.0), point)
                            )
                        }
                    }
                }
                return RouteResult(
                    points = points,
                    distanceMeters = route.optDouble("distance", 0.0),
                    durationSeconds = route.optDouble("duration", 0.0),
                    steps = steps
                )
            } catch (_: Exception) {
                // Try the alternate router if this endpoint times out or rejects the route.
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun formatInstruction(type: String, modifier: String, road: String?): String {
        val action = when (modifier) {
            "left", "sharp left", "slight left" -> "انعطف يساراً"
            "right", "sharp right", "slight right" -> "انعطف يميناً"
            "uturn" -> "قم بالدوران للخلف"
            "straight" -> "واصل السير للأمام"
            else -> when (type) {
                "depart" -> "ابدأ السير"
                "arrive" -> "وصلت إلى وجهتك"
                "roundabout", "rotary" -> "ادخل الدوار"
                "merge" -> "اندمج مع الطريق"
                "fork" -> "خذ التفرع"
                "on ramp" -> "اصعد إلى المنحدر"
                "off ramp" -> "اسلك المخرج"
                else -> "واصل السير"
            }
        }
        return if (road.isNullOrBlank()) action else "$action باتجاه $road"
    }
}
