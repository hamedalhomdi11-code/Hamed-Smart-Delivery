package com.hamed.smartdelivery

import java.net.URLDecoder

data class DeliveryDestination(val latitude: Double, val longitude: Double)

object DestinationLinkParser {
    private val coordinatePatterns = listOf(
        Regex("""!3d(-?\d+(?:\.\d+)?)!4d(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(?:q|query|destination|daddr)=(-?\d+(?:\.\d+)?)[,%2C]+(-?\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE),
        Regex("""(-?\d{1,2}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)""")
    )

    fun parse(sharedText: String?): DeliveryDestination? {
        if (sharedText.isNullOrBlank()) return null
        val decoded = runCatching { URLDecoder.decode(sharedText.replace("+", "%2B"), "UTF-8") }.getOrElse { sharedText }
        for (pattern in coordinatePatterns) {
            val match = pattern.find(decoded) ?: continue
            val latitude = match.groupValues[1].toDoubleOrNull() ?: continue
            val longitude = match.groupValues[2].toDoubleOrNull() ?: continue
            if (latitude in -90.0..90.0 && longitude in -180.0..180.0) {
                return DeliveryDestination(latitude, longitude)
            }
        }
        return null
    }
}
