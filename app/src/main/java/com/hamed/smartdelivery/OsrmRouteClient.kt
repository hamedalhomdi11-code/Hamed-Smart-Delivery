package com.hamed.smartdelivery

import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL

data class RouteStep(
    val instruction: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val location: LatLng?,
    val instructionEnglish: String = instruction
)

data class RouteResult(
    val points: List<LatLng>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val steps: List<RouteStep> = emptyList(),
    val estimatedFuelLiters: Double = GasolineFuelEstimator.estimate(distanceMeters, durationSeconds).liters,
    val alternativeCount: Int = 1,
    val routingProfile: String = "car",
    // OSRM demo endpoints do not expose live traffic, incident or roadwork feeds.
    val liveTrafficAvailable: Boolean = false,
    val liveClosuresAvailable: Boolean = false
)

/**
 * Car-profile route search. It evaluates OSRM's returned alternatives with a balanced
 * time/fuel score. The public OSRM endpoints are best-effort and do not provide live
 * traffic, accident, closure or roadwork feeds.
 */
object OsrmRouteClient {
    private data class Candidate(val result: RouteResult)

    fun fetchRoute(startLatitude: Double, startLongitude: Double, endLatitude: Double, endLongitude: Double): RouteResult? {
        val bases = listOf(
            "https://router.project-osrm.org/route/v1/driving/",
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
        )
        val coordinates = "$startLongitude,$startLatitude;$endLongitude,$endLatitude"
        for (base in bases) {
            var connection: HttpURLConnection? = null
            try {
                val endpoint = base + coordinates +
                    "?overview=full&geometries=geojson&steps=true&alternatives=3&continue_straight=false"
                connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 9000
                    readTimeout = 16000
                    setRequestProperty("User-Agent", "HamedSmartDelivery/0.4.3 (Android; car routing)")
                    setRequestProperty("Accept", "application/json")
                }
                if (connection.responseCode !in 200..299) continue
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                if (root.optString("code") != "Ok") continue
                val routes = root.optJSONArray("routes") ?: continue
                val candidates = buildList {
                    for (index in 0 until routes.length()) {
                        val route = routes.optJSONObject(index) ?: continue
                        val coordinatesArray = route.optJSONObject("geometry")?.optJSONArray("coordinates") ?: continue
                        val points = buildList {
                            for (pointIndex in 0 until coordinatesArray.length()) {
                                val pair = coordinatesArray.optJSONArray(pointIndex) ?: continue
                                if (pair.length() >= 2) add(LatLng(pair.getDouble(1), pair.getDouble(0)))
                            }
                        }
                        if (points.size < 2) continue
                        val distance = route.optDouble("distance", 0.0)
                        val duration = route.optDouble("duration", 0.0)
                        if (distance <= 0.0 || duration <= 0.0) continue
                        add(Candidate(RouteResult(
                            points = points,
                            distanceMeters = distance,
                            durationSeconds = duration,
                            steps = parseSteps(route),
                            estimatedFuelLiters = GasolineFuelEstimator.estimate(distance, duration).liters,
                            alternativeCount = routes.length(),
                            routingProfile = "car",
                            liveTrafficAvailable = false,
                            liveClosuresAvailable = false
                        )))
                    }
                }
                if (candidates.isEmpty()) continue
                val selectedIndex = BalancedRouteScorer.chooseIndex(
                    candidates.map { it.result.distanceMeters },
                    candidates.map { it.result.durationSeconds }
                )
                return candidates[selectedIndex].result
            } catch (_: Exception) {
                // Try the alternate car router if this endpoint times out or rejects the route.
            } finally {
                connection?.disconnect()
            }
        }
        return null
    }

    private fun parseSteps(route: JSONObject): List<RouteStep> = buildList {
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
                if (instruction.isNotBlank()) add(RouteStep(
                    instruction,
                    step.optDouble("distance", 0.0),
                    step.optDouble("duration", 0.0),
                    point,
                    formatInstructionEnglish(type, modifier, road)
                ))
            }
        }
    }

    private fun formatInstructionEnglish(type: String, modifier: String, road: String?): String {
        val action = when (modifier) {
            "left" -> "Turn left"
            "sharp left" -> "Turn sharply left"
            "slight left" -> "Keep slightly left"
            "right" -> "Turn right"
            "sharp right" -> "Turn sharply right"
            "slight right" -> "Keep slightly right"
            "uturn" -> "Make a U-turn"
            "straight" -> "Continue straight"
            else -> when (type) {
                "depart" -> "Start driving"
                "arrive" -> "You have arrived at your destination"
                "roundabout", "rotary" -> "Enter the roundabout"
                "merge" -> "Merge onto the road"
                "fork" -> "Take the fork"
                "on ramp" -> "Take the ramp"
                "off ramp" -> "Take the exit"
                else -> "Continue driving"
            }
        }
        return if (road.isNullOrBlank()) action else "$action toward $road"
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
