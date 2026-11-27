package com.hamed.smartdelivery.domain

import kotlin.math.max

data class ClusterCandidate(
    val id: String,
    val distanceKm: Double,
    val restaurantCount: Int,
    val observedOrders: Int,
    val hoursSinceLastObservedOrder: Double?,
    val publicRating: Double?,
    val estimatedFuelCostSar: Double?
)

data class RankedCluster(val candidate: ClusterCandidate, val score: Double, val confidence: String)

/** Explainable heuristic only. It never represents guaranteed/live order demand. */
object ClusterScoring {
    fun rank(candidates: List<ClusterCandidate>): List<RankedCluster> = candidates.map { c ->
        val distance = 1.0 / (1.0 + max(0.0, c.distanceKm))
        val density = (c.restaurantCount.coerceAtLeast(0) / 20.0).coerceIn(0.0, 1.0)
        val observed = (c.observedOrders.coerceAtLeast(0) / 15.0).coerceIn(0.0, 1.0)
        val freshness = c.hoursSinceLastObservedOrder?.let { 1.0 / (1.0 + max(0.0, it) / 8.0) } ?: 0.0
        val reputation = ((c.publicRating ?: 0.0) / 5.0).coerceIn(0.0, 1.0)
        val fuel = c.estimatedFuelCostSar?.let { 1.0 / (1.0 + max(0.0, it) / 5.0) } ?: 0.5
        val score = distance * .22 + density * .18 + observed * .25 + freshness * .15 + reputation * .08 + fuel * .12
        val confidence = when { c.observedOrders >= 3 && c.hoursSinceLastObservedOrder != null -> "observed-data"; c.restaurantCount > 0 -> "public-signals"; else -> "low" }
        RankedCluster(c, score, confidence)
    }.sortedByDescending { it.score }
}
